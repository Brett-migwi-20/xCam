package com.hazbu.xcam.data
object Constants {
    const val PREFS_NAME = "xcam_prefs"
    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_IS_ENABLED = "is_enabled"
    const val KEY_IS_MIRRORED = "is_mirrored"
    const val KEY_ROTATION_ANGLE = "rotation_angle"
    const val KEY_MIRROR_VERTICAL = "mirror_vertical"
    const val KEY_PLAYLIST = "playlist"
    const val KEY_PLAYLIST_INDEX = "playlist_index"
    const val KEY_PLAYBACK_STATE = "playback_state"
    const val KEY_LOOP_ENABLED = "loop_enabled"
    const val KEY_PLAYBACK_REVISION = "playback_revision"
    const val AUTHORITY = "com.hazbu.xcam.provider"

    const val DEFAULT_CAPTURE_WIDTH = 1280
    const val DEFAULT_CAPTURE_HEIGHT = 1280
    const val DUMMY_SURFACE_TEXTURE_ID = 999
    const val MIN_SESSION_DEBOUNCE_MS = 100L
    const val STREAM_FRAME_INTERVAL_MS = 33L
    const val KEY_ENABLE_CAPTURE_NOTIF = "enable_capture_notif"
    const val KEY_LATEST_PIPELINE_APP = "latest_pipeline_app"
    const val KEY_LATEST_PIPELINE_ROUTE = "latest_pipeline_route"
    const val KEY_LATEST_PIPELINE_TIME = "latest_pipeline_time"

    const val METHOD_RECORD_NODE = "record_node"
    const val METHOD_NOTIFY_CAPTURE = "notify_capture"
    const val METHOD_PLAYBACK_ACTION = "playback_action"
    const val METHOD_SET_MEDIA = "set_media"
    const val METHOD_SET_ORIENTATION = "set_orientation"
    const val METHOD_GET_LOGS = "get_logs"
    const val METHOD_APPEND_LOG = "append_log"
    const val METHOD_CLEAR_LOGS = "clear_logs"

    const val EXTRA_ACTION = "action"
    const val EXTRA_MEDIA_PATH = "media_path"
    const val EXTRA_ROTATION = "rotation"
    const val EXTRA_MIRRORED = "mirrored"
    const val EXTRA_MIRROR_VERTICAL = "mirror_vertical"
    const val EXTRA_LOG_ENTRY = "log_entry"

    const val ACTION_PLAYBACK_TOGGLE = "com.hazbu.xcam.action.PLAYBACK_TOGGLE"
    const val ACTION_PLAYBACK_PREVIOUS = "com.hazbu.xcam.action.PLAYBACK_PREVIOUS"
    const val ACTION_PLAYBACK_NEXT = "com.hazbu.xcam.action.PLAYBACK_NEXT"
    const val ACTION_PLAYBACK_COMPLETED = "com.hazbu.xcam.action.PLAYBACK_COMPLETED"
    const val ACTION_PLAYBACK_STARTED = "com.hazbu.xcam.action.PLAYBACK_STARTED"
    const val ACTION_PLAYBACK_PAUSED = "com.hazbu.xcam.action.PLAYBACK_PAUSED"
    const val ACTION_PLAYBACK_STOPPED = "com.hazbu.xcam.action.PLAYBACK_STOPPED"

    const val CAPTURE_NOTIFICATION_CHANNEL_ID = "xcam_capture_channel"
    const val CAPTURE_NOTIFICATION_ID = 1001
    const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "xcam_playback_channel"
    const val PLAYBACK_NOTIFICATION_ID = 1002
    const val KEY_LOG_HISTORY = "log_history"

    const val LOG_STORE_MAX_ENTRIES = 5_000   // persisted history
    const val LOG_VIEW_MAX_ENTRIES = 2_000    // rows kept in the on-screen list
    const val LOG_MAX_MESSAGE_CHARS = 4_000

    const val LOGCAT_MIN_PRIORITY = "I"   // V, D, I, W, E; applies when READ_LOGS lets us see other apps

}
