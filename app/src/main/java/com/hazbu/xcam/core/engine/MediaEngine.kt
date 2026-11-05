package com.hazbu.xcam.core.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer

/** One hardware-backed Media3 renderer per active preview surface. */
@UnstableApi
class MediaEngine(
    private val logAction: (String) -> Unit,
    private val playbackEvent: (PlaybackEvent) -> Unit = {},
) {
    enum class PlaybackEvent { STARTED, PAUSED, COMPLETED, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var prepared = false
    private var startRequestedAtMs = 0L

    @Volatile private var isPlayingInternal = false
    @Volatile private var currentPositionInternal = 0L
    @Volatile var videoWidth = 0; private set
    @Volatile var videoHeight = 0; private set

    val isPlaying: Boolean get() = isPlayingInternal
    val currentPosition: Long
        get() = player?.let { candidate ->
            if (Looper.myLooper() == Looper.getMainLooper()) candidate.currentPosition
            else currentPositionInternal
        } ?: currentPositionInternal

    private fun log(tag: String, msg: String) = logAction("[$tag] $msg")

    fun stop() {
        mainHandler.post {
            stopNow()
            currentPositionInternal = 0L
            videoWidth = 0
            videoHeight = 0
            log("PLAYBACK", "Player released")
        }
    }

    fun setPlaying(playing: Boolean) {
        mainHandler.post {
            player?.let {
                it.playWhenReady = playing
                if (playing && it.playbackState == Player.STATE_READY) it.play()
            }
        }
    }

    /** Applying Media3 effects is renderer-local and does not recreate the decoder. */
    fun updateTransform(isMirrored: Boolean, rotationAngle: Int) {
        mainHandler.post { player?.setVideoEffects(videoEffects(isMirrored, rotationAngle)) }
    }

    fun play(
        context: Context,
        path: String,
        surface: Surface,
        tag: String,
        isMirrored: Boolean = false,
        rotationAngle: Int = 0,
        startPlaying: Boolean = true,
        onPrepared: ((ExoPlayer?) -> Unit)? = null,
    ) {
        mainHandler.post {
            stopNow()
            startRequestedAtMs = SystemClock.elapsedRealtime()
            prepared = false
            try {
                val renderersFactory = DefaultRenderersFactory(context.applicationContext)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                val exoPlayer = ExoPlayer.Builder(context.applicationContext, renderersFactory).build()
                player = exoPlayer
                exoPlayer.setVideoEffects(videoEffects(isMirrored, rotationAngle))
                exoPlayer.setMediaItem(MediaItem.fromUri(path.toUri()))
                exoPlayer.setVideoSurface(surface)
                // Playlist advancement is owned by PlaybackController, not a hidden repeat mode.
                exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
                exoPlayer.playWhenReady = startPlaying
                exoPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (player !== exoPlayer || !prepared) return
                        isPlayingInternal = isPlaying
                        if (isPlaying) {
                            mainHandler.removeCallbacks(positionPoller)
                            mainHandler.post(positionPoller)
                            playbackEvent(PlaybackEvent.STARTED)
                        } else {
                            mainHandler.removeCallbacks(positionPoller)
                            playbackEvent(PlaybackEvent.PAUSED)
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        videoWidth = videoSize.width
                        videoHeight = videoSize.height
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (player !== exoPlayer) return
                        when (state) {
                            Player.STATE_READY -> {
                                prepared = true
                                videoWidth = exoPlayer.videoSize.width
                                videoHeight = exoPlayer.videoSize.height
                                log(tag, "Decoder ready ${videoWidth}x${videoHeight} in ${SystemClock.elapsedRealtime() - startRequestedAtMs}ms")
                                onPrepared?.invoke(exoPlayer)
                                if (startPlaying) exoPlayer.play()
                            }
                            Player.STATE_ENDED -> {
                                isPlayingInternal = false
                                mainHandler.removeCallbacks(positionPoller)
                                log(tag, "Media item completed")
                                playbackEvent(PlaybackEvent.COMPLETED)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (player !== exoPlayer) return
                        prepared = false
                        isPlayingInternal = false
                        log(tag, "Player error ${error.errorCodeName}: ${error.message}")
                        playbackEvent(PlaybackEvent.ERROR)
                    }
                })
                log(tag, "Preparing ${path.substringAfterLast('/')}")
                exoPlayer.prepare()
            } catch (error: Throwable) {
                log(tag, "Prepare failed: ${error.message}")
                stopNow()
                playbackEvent(PlaybackEvent.ERROR)
            }
        }
    }

    private fun stopNow() {
        mainHandler.removeCallbacks(positionPoller)
        player?.runCatching {
            stop()
            clearVideoSurface()
            release()
        }
        player = null
        prepared = false
        isPlayingInternal = false
    }

    private fun videoEffects(isMirrored: Boolean, rotationAngle: Int): List<Effect> {
        val rotation = ((rotationAngle % 360) + 360) % 360
        if (!isMirrored && rotation == 0) return emptyList()
        return listOf(
            ScaleAndRotateTransformation.Builder()
                .setScale(if (isMirrored) -1f else 1f, 1f)
                .setRotationDegrees(rotation.toFloat())
                .build()
        )
    }

    private val positionPoller = object : Runnable {
        override fun run() {
            player?.let { currentPositionInternal = it.currentPosition }
            if (isPlayingInternal) mainHandler.postDelayed(this, 250)
        }
    }
}
