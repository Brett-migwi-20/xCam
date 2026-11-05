package com.hazbu.xcam.core.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_HEIGHT
import com.hazbu.xcam.data.Constants.DEFAULT_CAPTURE_WIDTH

/**
 * Manages frame extraction for still captures and video streaming.
 */
class CaptureManager(
    private val contextProvider: () -> Context?,
    private val refreshSettingsAction: (Context) -> Unit,
    private val logAction: (String) -> Unit,
) {
    private val uiHandler = Handler(Looper.getMainLooper())

    @Volatile var isCapturing = false
        private set

    @Volatile private var cachedCaptureFrame: ByteArray? = null
    
    private var lastCapturePulseTime = 0L
    private var captureTimeMs = 0
    

    private fun log(msg: String) = logAction("[CAPTURE] $msg")

    fun reset() {
        uiHandler.removeCallbacksAndMessages(null)
        isCapturing = false
        cachedCaptureFrame = null
        log("[*] Manager state RESET")
    }

    fun triggerCaptureState(currentPositionProvider: () -> Int) {
        val now = System.currentTimeMillis()
        if ((now - lastCapturePulseTime) < 2000) return
        lastCapturePulseTime = now

        captureTimeMs = try { currentPositionProvider() } catch (_: Throwable) { 0 }
        log("[*] Pulse detected! Target Frame Time: $captureTimeMs ms")

        isCapturing = true
        cachedCaptureFrame = null

        contextProvider()?.let { refreshSettingsAction(it) }
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.postDelayed({
            isCapturing = false
            uiHandler.postDelayed({ cachedCaptureFrame = null }, 5000)
        }, 3000)
    }

    private fun performExtraction(
        context: Context,
        path: String,
        width: Int,
        height: Int,
        rotation: Int,
        mirrored: Boolean,
        timeMs: Int,
        maxSizeBytes: Int,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        return try {
            setIgnoringHooks(true)
            val finalW = if (width > 0) width else liveFrame?.width ?: DEFAULT_CAPTURE_WIDTH
            val finalH = if (height > 0) height else liveFrame?.height ?: DEFAULT_CAPTURE_HEIGHT
            XCamCapture.createJpeg(
                context, path, 
                finalW, 
                finalH, 
                rotation, mirrored, timeMs, maxSizeBytes, logAction,
                liveFrame
            )
        } catch (e: Throwable) {
            log("Extraction failed: ${e.message}")
            null
        } finally {
            setIgnoringHooks(false)
        }
    }

    @JvmOverloads
    fun handleCapture(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        maxSizeBytes: Int,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit,
        liveFrame: MediaCodecYuvDecoder.YuvFrame? = null
    ): ByteArray? {
        if (isIgnoringHooks() || path == null) return null
        val context = contextProvider() ?: return null

        return synchronized(this) {
            val cached = cachedCaptureFrame
            if (cached != null && cached.size <= maxSizeBytes) {
                cached
            } else {
                performExtraction(
                    context, path, width, height, rotationAngle, isMirrored, captureTimeMs, maxSizeBytes, setIgnoringHooks, liveFrame
                ).also {
                    cachedCaptureFrame = it
                    if (it != null) log("[+] Capture extraction successful (liveFrame: ${liveFrame != null}, size: ${it.size})")
                }
            }
        }
    }

    fun handleStreamFrame(
        path: String?,
        width: Int,
        height: Int,
        rotationAngle: Int,
        isMirrored: Boolean,
        isIgnoringHooks: () -> Boolean,
        setIgnoringHooks: (Boolean) -> Unit
    ): ByteArray? {
        val actualPath = path ?: return null
        
        if (!actualPath.lowercase().endsWith(".mp4")) {
            return handleCapture(path, width, height, rotationAngle, isMirrored, Int.MAX_VALUE, isIgnoringHooks, setIgnoringHooks)
        }

        // Video frames are supplied by MediaCodecYuvDecoder in XCamModule. Do not use
        // MediaMetadataRetriever as a 30fps fallback: it independently decodes every request.
        return null
    }
}
