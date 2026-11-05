package com.hazbu.xcam.data

import android.content.Context
import androidx.core.content.edit
import com.hazbu.xcam.core.settings.OrientationConfig
import org.json.JSONArray

/** Persistent source of truth. It is only used in xCam's provider process. */
class PlaybackController(private val context: Context) {
    data class Snapshot(
        val mediaPath: String?,
        val playlist: List<String>,
        val playlistIndex: Int,
        val playbackState: String,
        val loopEnabled: Boolean,
        val orientation: OrientationConfig,
        val revision: Long,
    ) {
        val isPlaying: Boolean get() = playbackState == STATE_PLAYING
        val title: String get() = mediaPath?.substringAfterLast('/') ?: "No media"
    }

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun snapshot(): Snapshot = synchronized(lock) { readSnapshot() }

    fun setMedia(path: String): Snapshot = synchronized(lock) {
        val values = playlist().toMutableList()
        val current = values.indexOf(path)
        val index = if (current >= 0) current else {
            values.add(path)
            values.lastIndex
        }
        write(values, index, STATE_PLAYING)
        readSnapshot()
    }

    fun removeCurrentMedia(): Snapshot = synchronized(lock) {
        val values = playlist().toMutableList()
        val oldIndex = prefs.getInt(Constants.KEY_PLAYLIST_INDEX, 0)
        if (values.isNotEmpty()) values.removeAt(oldIndex.coerceIn(0, values.lastIndex))
        val index = if (values.isEmpty()) -1 else oldIndex.coerceIn(0, values.lastIndex)
        write(values, index, if (values.isEmpty()) STATE_STOPPED else STATE_PAUSED)
        readSnapshot()
    }

    fun setOrientation(orientation: OrientationConfig): Snapshot = synchronized(lock) {
        prefs.edit {
            putInt(Constants.KEY_ROTATION_ANGLE, orientation.normalizedRotation)
            putBoolean(Constants.KEY_IS_MIRRORED, orientation.mirrorHorizontal)
            putBoolean(Constants.KEY_MIRROR_VERTICAL, orientation.mirrorVertical)
            incrementRevision(this)
        }
        readSnapshot()
    }

    fun perform(action: String): Snapshot = synchronized(lock) {
        val current = readSnapshot()
        when (action) {
            Constants.ACTION_PLAYBACK_TOGGLE -> write(
                current.playlist, current.playlistIndex,
                if (current.isPlaying) STATE_PAUSED else STATE_PLAYING
            )
            Constants.ACTION_PLAYBACK_NEXT -> move(current, +1)
            Constants.ACTION_PLAYBACK_PREVIOUS -> move(current, -1)
            Constants.ACTION_PLAYBACK_COMPLETED -> completion(current)
            Constants.ACTION_PLAYBACK_STARTED -> write(current.playlist, current.playlistIndex, STATE_PLAYING)
            Constants.ACTION_PLAYBACK_PAUSED -> write(current.playlist, current.playlistIndex, STATE_PAUSED)
            Constants.ACTION_PLAYBACK_STOPPED -> write(current.playlist, current.playlistIndex, STATE_STOPPED)
        }
        readSnapshot()
    }

    private fun move(current: Snapshot, direction: Int) {
        if (current.playlist.isEmpty()) return
        val last = current.playlist.lastIndex
        val proposed = current.playlistIndex + direction
        val next = when {
            proposed in 0..last -> proposed
            current.loopEnabled -> if (direction > 0) 0 else last
            else -> current.playlistIndex
        }
        write(current.playlist, next, if (next == current.playlistIndex && !current.loopEnabled) STATE_PAUSED else STATE_PLAYING)
    }

    private fun completion(current: Snapshot) {
        if (current.playlist.isEmpty()) return
        if (current.playlistIndex < current.playlist.lastIndex) {
            write(current.playlist, current.playlistIndex + 1, STATE_PLAYING)
        } else if (current.loopEnabled) {
            write(current.playlist, 0, STATE_PLAYING)
        } else {
            write(current.playlist, current.playlistIndex, STATE_STOPPED)
        }
    }

    private fun write(values: List<String>, requestedIndex: Int, state: String) {
        val index = if (values.isEmpty()) -1 else requestedIndex.coerceIn(0, values.lastIndex)
        prefs.edit {
            putString(Constants.KEY_PLAYLIST, JSONArray(values).toString())
            putInt(Constants.KEY_PLAYLIST_INDEX, index)
            putString(Constants.KEY_MEDIA_PATH, values.getOrNull(index) ?: "")
            putString(Constants.KEY_PLAYBACK_STATE, state)
            incrementRevision(this)
        }
    }

    private fun readSnapshot(): Snapshot {
        val items = playlist()
        val index = if (items.isEmpty()) -1 else prefs.getInt(Constants.KEY_PLAYLIST_INDEX, 0).coerceIn(0, items.lastIndex)
        return Snapshot(
            mediaPath = items.getOrNull(index),
            playlist = items,
            playlistIndex = index,
            playbackState = prefs.getString(Constants.KEY_PLAYBACK_STATE, STATE_PAUSED) ?: STATE_PAUSED,
            loopEnabled = prefs.getBoolean(Constants.KEY_LOOP_ENABLED, true),
            orientation = OrientationConfig(
                prefs.getInt(Constants.KEY_ROTATION_ANGLE, 0),
                prefs.getBoolean(Constants.KEY_IS_MIRRORED, false),
                prefs.getBoolean(Constants.KEY_MIRROR_VERTICAL, false),
            ),
            revision = prefs.getLong(Constants.KEY_PLAYBACK_REVISION, 0L),
        )
    }

    private fun playlist(): List<String> {
        val stored = prefs.getString(Constants.KEY_PLAYLIST, null)
        if (stored.isNullOrEmpty()) {
            return prefs.getString(Constants.KEY_MEDIA_PATH, "")?.takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
        }
        return runCatching {
            val json = JSONArray(stored)
            buildList { for (i in 0 until json.length()) json.optString(i).takeIf { it.isNotEmpty() }?.let(::add) }
        }.getOrDefault(emptyList())
    }

    private fun incrementRevision(editor: android.content.SharedPreferences.Editor) {
        editor.putLong(Constants.KEY_PLAYBACK_REVISION, prefs.getLong(Constants.KEY_PLAYBACK_REVISION, 0L) + 1L)
    }

    companion object {
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_STOPPED = "stopped"
    }
}
