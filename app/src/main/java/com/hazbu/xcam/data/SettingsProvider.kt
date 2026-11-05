package com.hazbu.xcam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.hazbu.xcam.data.Constants.AUTHORITY
import com.hazbu.xcam.data.Constants.KEY_IS_ENABLED
import com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import com.hazbu.xcam.data.Constants.EXTRA_ACTION
import com.hazbu.xcam.data.Constants.EXTRA_LOG_ENTRY
import com.hazbu.xcam.data.Constants.EXTRA_MEDIA_PATH
import com.hazbu.xcam.data.Constants.EXTRA_MIRRORED
import com.hazbu.xcam.data.Constants.EXTRA_MIRROR_VERTICAL
import com.hazbu.xcam.data.Constants.EXTRA_ROTATION
import com.hazbu.xcam.data.Constants.KEY_LOG_HISTORY
import com.hazbu.xcam.data.Constants.KEY_LOOP_ENABLED
import com.hazbu.xcam.data.Constants.KEY_MIRROR_VERTICAL
import com.hazbu.xcam.data.Constants.KEY_PLAYBACK_REVISION
import com.hazbu.xcam.data.Constants.KEY_PLAYBACK_STATE
import com.hazbu.xcam.data.Constants.KEY_PLAYLIST
import com.hazbu.xcam.data.Constants.KEY_PLAYLIST_INDEX
import com.hazbu.xcam.data.Constants.METHOD_APPEND_LOG
import com.hazbu.xcam.data.Constants.METHOD_CLEAR_LOGS
import com.hazbu.xcam.data.Constants.METHOD_GET_LOGS
import com.hazbu.xcam.data.Constants.METHOD_PLAYBACK_ACTION
import com.hazbu.xcam.data.Constants.METHOD_SET_MEDIA
import com.hazbu.xcam.data.Constants.METHOD_SET_ORIENTATION
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.hazbu.xcam.core.settings.OrientationConfig
import com.hazbu.xcam.ui.PlaybackNotificationHelper
import com.hazbu.xcam.utils.Logger
import org.json.JSONArray
import java.io.File

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playback = PlaybackController(ctx)

        when (method) {
            METHOD_SET_MEDIA -> {
                val path = extras?.getString(EXTRA_MEDIA_PATH).orEmpty()
                val state = if (path.isEmpty()) playback.removeCurrentMedia() else playback.setMedia(path)
                publishPlayback(ctx, state)
                return snapshotBundle(state)
            }
            METHOD_SET_ORIENTATION -> {
                val state = playback.setOrientation(
                    OrientationConfig(
                        extras?.getInt(EXTRA_ROTATION, 0) ?: 0,
                        extras?.getBoolean(EXTRA_MIRRORED, false) ?: false,
                        extras?.getBoolean(EXTRA_MIRROR_VERTICAL, false) ?: false,
                    )
                )
                publishPlayback(ctx, state)
                Logger.event(ctx, Logger.Category.ROTATION, Logger.Level.INFO, "orientation_changed", "rotation=${state.orientation.normalizedRotation}, mirror=${state.orientation.mirrorHorizontal}")
                return snapshotBundle(state)
            }
            METHOD_PLAYBACK_ACTION -> {
                val action = extras?.getString(EXTRA_ACTION).orEmpty()
                val state = playback.perform(action)
                publishPlayback(ctx, state)
                return snapshotBundle(state)
            }
            METHOD_APPEND_LOG -> {
                appendLog(prefs, extras?.getString(EXTRA_LOG_ENTRY))
                return Bundle()
            }
            METHOD_GET_LOGS -> return Bundle().apply { putStringArrayList("logs", readLogs(prefs)) }
            METHOD_CLEAR_LOGS -> {
                prefs.edit().remove(KEY_LOG_HISTORY).apply()
                return Bundle()
            }
            com.hazbu.xcam.data.Constants.METHOD_RECORD_NODE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: ""
                prefs.edit()
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()
            }
            com.hazbu.xcam.data.Constants.METHOD_NOTIFY_CAPTURE -> {
                val packageName = arg ?: "Unknown"
                val route = extras?.getString("route") ?: "Standard"
                val width = extras?.getInt("width") ?: 0
                val height = extras?.getInt("height") ?: 0
                val thumbnail = extras?.getByteArray("thumbnail")

                prefs.edit()
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP, packageName)
                    .putString(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE, route)
                    .putLong(com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME, System.currentTimeMillis())
                    .apply()

                val isNotifEnabled = prefs.getBoolean(com.hazbu.xcam.data.Constants.KEY_ENABLE_CAPTURE_NOTIF, true)
                if (isNotifEnabled) {
                    com.hazbu.xcam.ui.CaptureNotificationHelper.showCaptureNotification(
                        ctx, packageName, route, width, height, thumbnail
                    )
                }
            }
        }
        return Bundle()
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE))
        val state = PlaybackController(ctx).snapshot()
        val path = state.mediaPath.orEmpty()
        val fileName = if (path.isNotEmpty()) File(path).name else "media"
        
        val cursor = MatrixCursor(arrayOf(
            KEY_MEDIA_PATH, KEY_IS_ENABLED, KEY_IS_MIRRORED, KEY_ROTATION_ANGLE,
            KEY_MIRROR_VERTICAL, KEY_PLAYBACK_STATE, KEY_LOOP_ENABLED,
            KEY_PLAYLIST_INDEX, KEY_PLAYLIST, KEY_PLAYBACK_REVISION,
        ))
        val videoUri = "content://$AUTHORITY/$fileName"
        
        cursor.addRow(arrayOf(
            videoUri,
            "1",
            if (state.orientation.mirrorHorizontal) "1" else "0",
            state.orientation.normalizedRotation.toString(),
            if (state.orientation.mirrorVertical) "1" else "0",
            state.playbackState,
            if (state.loopEnabled) "1" else "0",
            state.playlistIndex.toString(),
            JSONArray(state.playlist).toString(),
            state.revision.toString(),
        ))
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val fileName = uri.lastPathSegment ?: return null
        val file = File(context.filesDir, fileName)
        
        if (file.exists()) {
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: Exception) {
                null
            }
        }

        val fallbackFile = context.filesDir.listFiles { _, name -> 
            name.startsWith("virtual.") 
        }?.firstOrNull()
        
        return try {
            fallbackFile?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }
        } catch (_: Exception) {
            null
        }
    }

    override fun getType(uri: Uri): String {
        val extension = uri.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        
        return when {
            mimeType?.startsWith("image/") == true -> mimeType
            mimeType?.startsWith("video/") == true -> mimeType
            else -> "video/mp4"
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun publishPlayback(context: Context, state: PlaybackController.Snapshot) {
        context.contentResolver.notifyChange(android.net.Uri.parse("content://$AUTHORITY"), null)
        PlaybackNotificationHelper.update(context, state)
    }

    private fun snapshotBundle(state: PlaybackController.Snapshot) = Bundle().apply {
        putString(KEY_MEDIA_PATH, state.mediaPath)
        putString(KEY_PLAYBACK_STATE, state.playbackState)
        putInt(KEY_PLAYLIST_INDEX, state.playlistIndex)
        putInt("playlist_size", state.playlist.size)
        putInt(KEY_ROTATION_ANGLE, state.orientation.normalizedRotation)
        putBoolean(KEY_IS_MIRRORED, state.orientation.mirrorHorizontal)
    }

    private fun appendLog(prefs: android.content.SharedPreferences, entry: String?) {
        if (entry.isNullOrBlank()) return
        val logs = readLogs(prefs)
        logs.add(entry.take(900))
        while (logs.size > 250) logs.removeAt(0)
        prefs.edit().putString(KEY_LOG_HISTORY, JSONArray(logs).toString()).apply()
    }

    private fun readLogs(prefs: android.content.SharedPreferences): ArrayList<String> {
        val raw = prefs.getString(KEY_LOG_HISTORY, null) ?: return arrayListOf()
        return runCatching {
            val json = JSONArray(raw)
            ArrayList<String>(json.length()).apply { for (i in 0 until json.length()) add(json.optString(i)) }
        }.getOrDefault(arrayListOf())
    }
}
