package com.hazbu.xcam.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.data.PlaybackController

/** Lightweight control notification; it has no player of its own. */
object PlaybackNotificationHelper {
    fun update(context: Context, state: PlaybackController.Snapshot) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    Constants.PLAYBACK_NOTIFICATION_CHANNEL_ID,
                    "xCam playback controls",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Controls the active xCam media playlist" }
            )
        }
        if (state.mediaPath == null) {
            manager.cancel(Constants.PLAYBACK_NOTIFICATION_ID)
            return
        }

        val open = PendingIntent.getActivity(
            context, 100,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val previous = actionIntent(context, Constants.ACTION_PLAYBACK_PREVIOUS, 101)
        val toggle = actionIntent(context, Constants.ACTION_PLAYBACK_TOGGLE, 102)
        val next = actionIntent(context, Constants.ACTION_PLAYBACK_NEXT, 103)
        val playLabel = if (state.isPlaying) "Pause" else "Play"
        val playIcon = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val builder = NotificationCompat.Builder(context, Constants.PLAYBACK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.title)
            .setContentText(if (state.isPlaying) "Playing • ${state.playlistIndex + 1}/${state.playlist.size}" else "Paused • ${state.playlistIndex + 1}/${state.playlist.size}")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Previous", previous)
            .addAction(playIcon, playLabel, toggle)
            .addAction(android.R.drawable.ic_media_next, "Next", next)
        manager.notify(Constants.PLAYBACK_NOTIFICATION_ID, builder.build())
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlaybackActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
