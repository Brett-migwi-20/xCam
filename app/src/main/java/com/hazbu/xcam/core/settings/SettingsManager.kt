package com.hazbu.xcam.core.settings

import android.content.Context
import androidx.core.net.toUri
import com.hazbu.xcam.data.Constants.AUTHORITY
import com.hazbu.xcam.data.Constants.KEY_LOOP_ENABLED
import com.hazbu.xcam.data.Constants.KEY_MIRROR_VERTICAL
import com.hazbu.xcam.data.Constants.KEY_PLAYBACK_REVISION
import com.hazbu.xcam.data.Constants.KEY_PLAYBACK_STATE

class SettingsManager {
    var mediaPath: String? = null
    var isMirrored = false
    var rotationAngle = 0
    var isMirrorVertical = false
    var playbackState = "paused"
    var loopEnabled = true
    var revision = 0L

    val orientation: OrientationConfig
        get() = OrientationConfig(rotationAngle, isMirrored, isMirrorVertical)
    val isPlaybackRequested: Boolean
        get() = playbackState == "playing"

    fun refreshSettings(context: Context) {
        try {
            val uri = "content://$AUTHORITY".toUri()
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    mediaPath = cursor.getString(cursor.getColumnIndexOrThrow("media_path"))?.takeIf { it.isNotEmpty() }
                    isMirrored = cursor.getString(cursor.getColumnIndexOrThrow("is_mirrored")) == "1"
                    rotationAngle = ((cursor.getString(cursor.getColumnIndexOrThrow("rotation_angle")).toIntOrNull() ?: 0) % 360 + 360) % 360
                    isMirrorVertical = cursor.stringOrNull(KEY_MIRROR_VERTICAL) == "1"
                    playbackState = cursor.stringOrNull(KEY_PLAYBACK_STATE) ?: "paused"
                    loopEnabled = cursor.stringOrNull(KEY_LOOP_ENABLED) != "0"
                    revision = cursor.stringOrNull(KEY_PLAYBACK_REVISION)?.toLongOrNull() ?: revision
                }
            }
        } catch (_: Exception) {}
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}
