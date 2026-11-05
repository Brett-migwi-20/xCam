package com.hazbu.xcam.core.capture

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.core.net.toUri
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Hardware decoder for ImageReader injection. It maintains a bounded, reference-counted
 * latest-frame pool: consumers get the newest complete frame or no frame, never a backlog.
 */
class MediaCodecYuvDecoder(
    private val context: Context,
    private val mediaPath: String,
    private val logAction: (String) -> Unit,
) {
    class YuvFrame internal constructor(
        val width: Int,
        val height: Int,
        val yPlane: ByteArray,
        val uPlane: ByteArray,
        val vPlane: ByteArray,
        val timestampNs: Long,
        private val releaseAction: () -> Unit,
    ) : AutoCloseable {
        private var references = 1
        private var released = false

        internal fun retain(): Boolean = synchronized(this) {
            if (released) false else {
                references++
                true
            }
        }

        fun release() = close()

        override fun close() {
            val releaseStorage = synchronized(this) {
                if (released) return
                references--
                if (references == 0) {
                    released = true
                    true
                } else false
            }
            if (releaseStorage) releaseAction()
        }
    }

    data class Metrics(
        val deliveredFrames: Long,
        val droppedFrames: Long,
        val averageCopyMs: Double,
        val outputFps: Double,
        val initializationMs: Long,
    )

    private class FrameStorage(val width: Int, val height: Int) {
        val y = ByteArray(width * height)
        val u = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
        val v = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
    }

    private val frameLock = Any()
    private val poolLock = Any()
    private val available = ArrayDeque<FrameStorage>()
    private var allocatedStorage = 0
    private var latestFrame: YuvFrame? = null

    @Volatile private var isRunning = false
    @Volatile private var decodeThread: Thread? = null
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set
    @Volatile var metrics = Metrics(0, 0, 0.0, 0.0, 0); private set

    fun start() {
        if (isRunning) return
        isRunning = true
        decodeThread = Thread(::decodeLoop, "xCam-YuvDecoder").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        decodeThread?.interrupt()
        runCatching { decodeThread?.join(1_000) }
        decodeThread = null
        synchronized(frameLock) {
            latestFrame?.release()
            latestFrame = null
        }
        synchronized(poolLock) {
            available.clear()
            allocatedStorage = 0
        }
    }

    /** Acquire a stable reference; callers must release it in a finally block. */
    fun acquireLatestFrame(): YuvFrame? = synchronized(frameLock) {
        latestFrame?.takeIf { it.retain() }
    }

    private fun decodeLoop() {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        val initStartedMs = SystemClock.elapsedRealtime()
        var delivered = 0L
        var dropped = 0L
        var totalCopyNs = 0L
        var statsStartedMs = initStartedMs
        var statsDelivered = 0L
        var firstPtsUs = Long.MIN_VALUE
        var presentationBaseNs = 0L
        var lastPtsUs = Long.MIN_VALUE

        try {
            extractor = MediaExtractor().apply { setDataSource(context, mediaPath.toUri(), null) }
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: run {
                logAction("[DECODER] No video track in ${mediaPath.substringAfterLast('/')}")
                return
            }
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val initializationMs = SystemClock.elapsedRealtime() - initStartedMs
            metrics = metrics.copy(initializationMs = initializationMs)
            logAction("[DECODER] Hardware decoder ready ${videoWidth}x${videoHeight} in ${initializationMs}ms")

            val info = MediaCodec.BufferInfo()
            while (isRunning) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input != null) {
                        var sampleSize = extractor.readSampleData(input, 0)
                        if (sampleSize < 0) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            sampleSize = extractor.readSampleData(input, 0)
                        }
                        if (sampleSize > 0) {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val ptsUs = info.presentationTimeUs
                        if (firstPtsUs == Long.MIN_VALUE || ptsUs < lastPtsUs) {
                            firstPtsUs = ptsUs
                            presentationBaseNs = System.nanoTime()
                        }
                        lastPtsUs = ptsUs
                        val desiredNs = presentationBaseNs + (ptsUs - firstPtsUs) * 1_000L
                        val waitNs = desiredNs - System.nanoTime()
                        if (waitNs > 0L) LockSupport.parkNanos(waitNs.coerceAtMost(20_000_000L))
                        val latenessNs = System.nanoTime() - desiredNs
                        if (latenessNs > MAX_FRAME_LATENESS_NS) {
                            dropped++
                        } else {
                            val image = codec.getOutputImage(outputIndex)
                            if (image != null) {
                                val copyStarted = System.nanoTime()
                                val storage = acquireStorage(image.width, image.height)
                                if (storage == null) {
                                    dropped++
                                } else {
                                    copyImage(image, storage)
                                    publish(storage, ptsUs * 1_000L)
                                    delivered++
                                    statsDelivered++
                                    totalCopyNs += System.nanoTime() - copyStarted
                                }
                                image.close()
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        videoWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                        videoHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        logAction("[DECODER] Output format ${videoWidth}x${videoHeight}")
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (now - statsStartedMs >= 5_000L) {
                    val intervalMs = (now - statsStartedMs).coerceAtLeast(1)
                    val fps = statsDelivered * 1_000.0 / intervalMs
                    val averageCopyMs = if (delivered == 0L) 0.0 else totalCopyNs / 1_000_000.0 / delivered
                    metrics = Metrics(delivered, dropped, averageCopyMs, fps, metrics.initializationMs)
                    logAction("[PERFORMANCE] decoder fps=${"%.1f".format(fps)} copy=${"%.2f".format(averageCopyMs)}ms delivered=$delivered dropped=$dropped")
                    statsStartedMs = now
                    statsDelivered = 0
                }
            }
        } catch (_: InterruptedException) {
            // stop() uses interruption to unblock MediaCodec waits.
        } catch (error: Throwable) {
            logAction("[DECODER] Failure: ${error.message}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun acquireStorage(width: Int, height: Int): FrameStorage? = synchronized(poolLock) {
        val existing = available.firstOrNull { it.width == width && it.height == height }
        if (existing != null) {
            available.remove(existing)
            return@synchronized existing
        }
        if (allocatedStorage >= MAX_BUFFERED_FRAMES) return@synchronized null
        allocatedStorage++
        FrameStorage(width, height)
    }

    private fun publish(storage: FrameStorage, timestampNs: Long) {
        val frame = YuvFrame(storage.width, storage.height, storage.y, storage.u, storage.v, timestampNs) {
            synchronized(poolLock) { available.addLast(storage) }
        }
        synchronized(frameLock) {
            val previous = latestFrame
            latestFrame = frame
            previous?.release()
        }
    }

    private fun copyImage(image: Image, storage: FrameStorage) {
        copyPlane(image.planes[0], storage.y, image.width, image.height)
        val chromaWidth = (image.width + 1) / 2
        val chromaHeight = (image.height + 1) / 2
        copyPlane(image.planes[1], storage.u, chromaWidth, chromaHeight)
        copyPlane(image.planes[2], storage.v, chromaWidth, chromaHeight)
    }

    private fun copyPlane(plane: Image.Plane, target: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer.duplicate()
        val start = buffer.position()
        for (row in 0 until height) {
            val rowStart = start + row * plane.rowStride
            val destination = row * width
            for (column in 0 until width) {
                val source = rowStart + column * plane.pixelStride
                if (source < buffer.limit()) target[destination + column] = buffer.get(source)
            }
        }
    }

    companion object {
        private const val MAX_BUFFERED_FRAMES = 4
        private const val MAX_FRAME_LATENESS_NS = 100_000_000L
    }
}
