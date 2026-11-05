package com.hazbu.xcam.data

import android.content.Context
import android.os.Bundle
import androidx.core.net.toUri

/** Cross-process entry point used by the UI, notification and injected process. */
object PlaybackGateway {
    private val uri = "content://${Constants.AUTHORITY}".toUri()

    fun setMedia(context: Context, path: String) = call(
        context, Constants.METHOD_SET_MEDIA, Bundle().apply { putString(Constants.EXTRA_MEDIA_PATH, path) }
    )

    fun setOrientation(context: Context, rotation: Int, mirrored: Boolean, mirrorVertical: Boolean = false) = call(
        context, Constants.METHOD_SET_ORIENTATION, Bundle().apply {
            putInt(Constants.EXTRA_ROTATION, rotation)
            putBoolean(Constants.EXTRA_MIRRORED, mirrored)
            putBoolean(Constants.EXTRA_MIRROR_VERTICAL, mirrorVertical)
        }
    )

    fun action(context: Context, action: String) = call(
        context, Constants.METHOD_PLAYBACK_ACTION, Bundle().apply { putString(Constants.EXTRA_ACTION, action) }
    )

    private fun call(context: Context, method: String, extras: Bundle) = runCatching {
        context.contentResolver.call(uri, method, null, extras)
    }.getOrNull()
}
