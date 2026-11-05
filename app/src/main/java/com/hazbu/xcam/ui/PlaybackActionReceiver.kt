package com.hazbu.xcam.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.data.PlaybackGateway
import com.hazbu.xcam.utils.Logger

/** Notification actions always mutate the same provider-backed playback state as the UI. */
class PlaybackActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Constants.ACTION_PLAYBACK_TOGGLE,
            Constants.ACTION_PLAYBACK_PREVIOUS,
            Constants.ACTION_PLAYBACK_NEXT -> {
                Logger.event(context, Logger.Category.NOTIFICATION, Logger.Level.INFO, "action", action)
                PlaybackGateway.action(context, action)
            }
        }
    }
}
