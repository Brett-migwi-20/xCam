package com.hazbu.xcam.xposed

import android.content.Context
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.util.UnstableApi
import com.hazbu.xcam.core.audio.MediaCodecAudioDecoder
import com.hazbu.xcam.core.capture.CaptureManager
import com.hazbu.xcam.core.capture.MediaCodecYuvDecoder
import com.hazbu.xcam.core.capture.YuvFrameProcessor
import com.hazbu.xcam.core.engine.MediaEngine
import com.hazbu.xcam.core.engine.XCamEngine
import com.hazbu.xcam.core.settings.SettingsManager
import com.hazbu.xcam.core.surface.SurfaceManager
import com.hazbu.xcam.core.surface.SurfaceProvider
import com.hazbu.xcam.core.telemetry.PipelineTracker
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.SystemUtils
import com.hazbu.xcam.utils.UIUtils
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.data.PlaybackGateway
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

@UnstableApi
class XCamModule : XposedModule() {
    private var isInitialized = false
    private var mContext: Context? = null
    private var hooksInstalled = false
    private val ignoreHooks = ThreadLocal.withInitial { false }
    @Volatile private var yuvDecoder: MediaCodecYuvDecoder? = null
    @Volatile private var audioDecoder: MediaCodecAudioDecoder? = null
    private val yuvLock = Any()
    private val audioLock = Any()
    
    private val injectors = XCamInjectors(this)
    private val settings = SettingsManager()
    private val surfaceManager = SurfaceManager { printLog(it) }
    private val surfaceProvider = SurfaceProvider { printLog(it) }
    private val mediaEngine = MediaEngine({ printLog(it) }) { event ->
        val action = when (event) {
            MediaEngine.PlaybackEvent.STARTED -> Constants.ACTION_PLAYBACK_STARTED
            MediaEngine.PlaybackEvent.PAUSED -> Constants.ACTION_PLAYBACK_PAUSED
            MediaEngine.PlaybackEvent.COMPLETED -> Constants.ACTION_PLAYBACK_COMPLETED
            MediaEngine.PlaybackEvent.ERROR -> null
        }
        val context = mContext
        if (action != null && context != null) {
            Logger.event(context, Logger.Category.PLAYBACK, Logger.Level.INFO, "engine_${event.name.lowercase()}")
            PlaybackGateway.action(context, action)
        }
    }
    private val yuvProcessor = YuvFrameProcessor()
    
    private val captureManager = CaptureManager(
        contextProvider = { mContext },
        refreshSettingsAction = { settings.refreshSettings(it) },
    ) { printLog(it) }
    
    private val engine = XCamEngine(
        contextProvider = { mContext },
        settingsProvider = { settings },
        surfaceManager = surfaceManager,
        mediaEngine = mediaEngine,
        surfaceProvider = surfaceProvider,
    ) { printLog(it) }

    private var observingSettings = false
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshObservedSettings()
        }
    }

    fun isIgnoringHooks(): Boolean = ignoreHooks.get() ?: false
    fun setIgnoringHooks(ignore: Boolean) { ignoreHooks.set(ignore) }

    val context: Context? get() = mContext
    val mediaPath: String? get() = settings.mediaPath
    var previewSwapped: Boolean
        get() = surfaceManager.previewSwapped
        set(value) { surfaceManager.previewSwapped = value }

    fun printLog(msg: String, tr: Throwable? = null) {
        val category = when {
            msg.contains("YUV-DECODER") || msg.contains("DECODER") -> Logger.Category.DECODER
            msg.contains("AUDIO") -> Logger.Category.AUDIO
            msg.contains("SURFACE") -> Logger.Category.INJECTION
            msg.contains("CAPTURE") -> Logger.Category.FRAME
            msg.contains("PLAYBACK") || msg.contains("MEDIA-ENGINE") -> Logger.Category.PLAYBACK
            else -> Logger.Category.CAMERA
        }
        val level = if (tr != null || msg.contains("error", true) || msg.contains("failed", true) || msg.contains("FATAL")) Logger.Level.ERROR else Logger.Level.INFO
        Logger.event(mContext, category, level, "module", msg, tr, this)
    }

    fun logInit(msg: String) = Logger.event(mContext, Logger.Category.CAMERA, Logger.Level.INFO, "init", msg, xi = this)
    fun logHook(msg: String) = Logger.event(mContext, Logger.Category.CAMERA, Logger.Level.DEBUG, "hook", msg, xi = this)

    fun showToast(message: String) = UIUtils.showToast(mContext, message) { printLog(it) }

    fun isCapturingState() = captureManager.isCapturing

    fun triggerCaptureState() {
        captureManager.triggerCaptureState { engine.getCurrentPosition().toInt() }
    }

    fun getCurrentPosition(): Long = engine.getCurrentPosition()

    fun registerPreviewSurface(s: Surface) = surfaceManager.registerPreviewSurface(s)
    fun registerImageReaderSurface(s: Surface, f: Int, w: Int, h: Int) = surfaceManager.registerImageReaderSurface(s, f, w, h)
    fun isPreviewSurface(s: Surface?) = surfaceManager.isPreviewSurface(s)
    fun isNonPreviewSurface(s: Surface?) = surfaceManager.isNonPreviewSurface(s)
    fun logSessionOutput(s: Surface) = surfaceManager.logSessionOutput(s)
    fun incrementSessionGeneration() = surfaceManager.incrementSessionGeneration()
    fun clearPreviewSurfaces() = surfaceManager.clearPreviewSurfaces(engine.isPlaying())

    fun recordPipelineNode(node: String) {
        PipelineTracker.recordNode(mContext, node)
    }

    @JvmOverloads
    fun reportPipelineCapture(width: Int = 0, height: Int = 0, thumbnail: ByteArray? = null) {
        PipelineTracker.reportCapture(mContext, width, height, thumbnail)
    }

    private fun getOrCreateYuvDecoder(ctx: Context, path: String): MediaCodecYuvDecoder {
        return yuvDecoder ?: synchronized(yuvLock) {
            yuvDecoder ?: MediaCodecYuvDecoder(ctx, path) { printLog(it) }.apply { start() }.also { yuvDecoder = it }
        }
    }

    private fun getOrCreateAudioDecoder(ctx: Context, path: String): MediaCodecAudioDecoder {
        return audioDecoder ?: synchronized(audioLock) {
            audioDecoder ?: MediaCodecAudioDecoder(ctx, path) { printLog(it) }.apply { start() }.also { audioDecoder = it }
        }
    }

    fun injectYuvFrame(image: android.media.Image, width: Int, height: Int) {
        val path = settings.mediaPath
        val ctx = mContext
        if (path != null && ctx != null && isVideoPath(path)) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.acquireLatestFrame()
            if (frame != null) {
                try {
                    yuvProcessor.injectYuvFrame(image, frame, settings.rotationAngle, settings.isMirrored, settings.isMirrorVertical)
                } finally {
                    frame.release()
                }
                return
            }
            // The bounded latest-frame strategy intentionally drops early/late frames instead of
            // falling back to MediaMetadataRetriever and building latency behind real time.
            return
        }

        val jpeg = handleStreamFrame(width, height) ?: return
        yuvProcessor.injectToImage(image, jpeg)
    }

    fun handleNv21Frame(width: Int, height: Int): ByteArray? {
        val path = settings.mediaPath ?: return null
        val ctx = mContext ?: return null

        if (isVideoPath(path)) {
            val decoder = getOrCreateYuvDecoder(ctx, path)
            val frame = decoder.acquireLatestFrame()
            if (frame != null) {
                return try {
                    yuvProcessor.frameToNv21(frame, width, height, settings.rotationAngle, settings.isMirrored, settings.isMirrorVertical)
                } finally {
                    frame.release()
                }
            }
            return null
        }

        val jpeg = handleStreamFrame(width, height) ?: return null
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val nv21 = yuvProcessor.rgbToNv21(bmp, width, height)
            bmp.recycle()
            nv21
        } catch (_: Throwable) {
            null
        }
    }

    fun stopEngine() {
        synchronized(yuvLock) {
            yuvDecoder?.stop()
            yuvDecoder = null
        }
        synchronized(audioLock) {
            audioDecoder?.stop()
            audioDecoder = null
        }
        engine.stop()
        captureManager.reset()
        injectors.imageReaderHook.reset()
    }
    fun handleCamera1Preview(st: SurfaceTexture) = engine.handleCamera1Preview(st)
    fun handleModernPreview(s: Surface) = engine.handleModernPreview(s)
    fun getDummySurface() = engine.getDummySurface()

    fun injectAudioBytes(buffer: ByteArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readBytes(buffer, offset, length)
    }

    fun injectAudioShorts(buffer: ShortArray, offset: Int, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readShorts(buffer, offset, length)
    }

    fun injectAudioByteBuffer(buffer: java.nio.ByteBuffer, length: Int) {
        val path = settings.mediaPath ?: return
        val ctx = mContext ?: return
        getOrCreateAudioDecoder(ctx, path).readByteBuffer(buffer, length)
    }

    fun getImageReaderCaptureWidth(): Int = injectors.imageReaderHook.activeCaptureWidth
    fun getImageReaderCaptureHeight(): Int = injectors.imageReaderHook.activeCaptureHeight

    @JvmOverloads
    fun handleCapture(w: Int = 0, h: Int = 0, maxSize: Int = Int.MAX_VALUE): ByteArray? {
        val irW = injectors.imageReaderHook.activeCaptureWidth
        val irH = injectors.imageReaderHook.activeCaptureHeight
        val targetW = if (w > 0) w else if (irW > 0) irW else Constants.DEFAULT_CAPTURE_WIDTH
        val targetH = if (h > 0) h else if (irH > 0) irH else Constants.DEFAULT_CAPTURE_HEIGHT
        val liveFrame = yuvDecoder?.acquireLatestFrame()
        return try {
            captureManager.handleCapture(
                settings.mediaPath, targetW, targetH, settings.rotationAngle, settings.isMirrored, maxSize,
                { isIgnoringHooks() },
                { setIgnoringHooks(it) }, liveFrame
            )
        } finally {
            liveFrame?.release()
        }
    }

    fun handleStreamFrame(w: Int, h: Int) = captureManager.handleStreamFrame(
        settings.mediaPath, w, h, settings.rotationAngle, settings.isMirrored,
        { isIgnoringHooks() },
        { setIgnoringHooks(it) },
    )

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        super.onPackageReady(param)
        val processName = SystemUtils.getProcessNameStrict()
        if (param.packageName == "com.hazbu.xcam") {
            hookManagerApp(param)
            return
        }
        
        if (!processName.contains(param.packageName)) return
        if (hooksInstalled) return
        hooksInstalled = true

        incrementSessionGeneration()
        clearPreviewSurfaces()
        PipelineTracker.reset()
        logInit(">>> ACTIVE IN: $processName (API ${Build.VERSION.SDK_INT}) <<<")
        hookContextInit()
        injectors.install(param)
    }

    private fun hookManagerApp(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val clazz = param.classLoader.loadClass("com.hazbu.xcam.ui.MainActivity")
            hook(clazz.getDeclaredMethod("checkSelfActive")).intercept { true }
        } catch (_: Throwable) {}
    }

    private fun hookContextInit() {
        val currentApp = SystemUtils.getCurrentApplication()
        if (currentApp != null && !isInitialized) {
            mContext = currentApp
            logInit("Context immediately resolved from ActivityThread: ${mContext?.packageName}")
            mContext?.let { initialiseSettingsObservation(it) }
            isInitialized = true
        }

        try {
            val attachMethod = Class.forName("android.content.ContextWrapper")
                .getDeclaredMethod("attachBaseContext", Context::class.java)

            hook(attachMethod).intercept { chain ->
                val result = chain.proceed()
                if (!isInitialized) {
                    mContext = chain.thisObject as? Context
                    logInit("Context Initialized: ${mContext?.packageName}")
                    mContext?.let { initialiseSettingsObservation(it) }
                    isInitialized = true
                }
                result
            }
        } catch (e: Exception) {
            logInit("Context hook failure: ${e.message}")
        }
    }

    private fun initialiseSettingsObservation(context: Context) {
        settings.refreshSettings(context)
        if (!observingSettings) {
            context.contentResolver.registerContentObserver(
                android.net.Uri.parse("content://${Constants.AUTHORITY}"), true, settingsObserver
            )
            observingSettings = true
        }
    }

    private fun refreshObservedSettings() {
        val context = mContext ?: return
        val previousPath = settings.mediaPath
        val previousRevision = settings.revision
        settings.refreshSettings(context)
        if (settings.revision == previousRevision) return
        if (previousPath != settings.mediaPath) {
            synchronized(yuvLock) {
                yuvDecoder?.stop()
                yuvDecoder = null
            }
            synchronized(audioLock) {
                audioDecoder?.stop()
                audioDecoder = null
            }
            captureManager.reset()
        }
        Logger.event(context, Logger.Category.PLAYBACK, Logger.Level.INFO, "settings_changed", "revision=${settings.revision}, media=${settings.mediaPath?.substringAfterLast('/')}")
        engine.onSettingsChanged()
    }

    private fun isVideoPath(path: String): Boolean {
        val extension = path.substringBefore('?').substringAfterLast('.', "").lowercase()
        return extension in setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov", "avi")
    }
}
