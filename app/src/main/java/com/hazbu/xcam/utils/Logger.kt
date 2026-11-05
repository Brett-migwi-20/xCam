package com.hazbu.xcam.utils

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.core.net.toUri
import com.hazbu.xcam.data.Constants
import io.github.libxposed.api.XposedInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object Logger {
    private const val TAG = "xCam"
    private const val MAX_IN_MEMORY_ENTRIES = 250
    private val history = ArrayDeque<String>(MAX_IN_MEMORY_ENTRIES)
    private val historyLock = Any()
    private val reporter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xCam-LogReporter").apply { isDaemon = true }
    }

    enum class Category { UI, MEDIA, PLAYBACK, DECODER, FRAME, ROTATION, CAMERA, INJECTION, NOTIFICATION, AUDIO, PERFORMANCE, ERROR }

    enum class Level(val xPriority: Int) {
        DEBUG(XposedInterface.PRIORITY_DEFAULT),
        INFO(XposedInterface.PRIORITY_DEFAULT),
        WARN(XposedInterface.PRIORITY_DEFAULT),
        ERROR(XposedInterface.PRIORITY_DEFAULT)
    }

    /**
     * Structured application event. The provider call is serialized off the caller's render,
     * decoder, and hook threads; Logcat remains immediately available for development.
     */
    fun event(
        context: Context?,
        category: Category,
        level: Level,
        event: String,
        details: String = "",
        throwable: Throwable? = null,
        xi: XposedInterface? = null,
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
        val safeDetails = sanitize(details)
        val entry = "$timestamp|${level.name}|${category.name}|$event|$safeDetails"
        synchronized(historyLock) {
            if (history.size == MAX_IN_MEMORY_ENTRIES) history.removeFirst()
            history.addLast(entry)
        }
        printLog(xi, "[${category.name}][$event] $safeDetails", level, throwable)
        if (context != null) {
            val appContext = context.applicationContext
            reporter.execute {
                runCatching {
                    appContext.contentResolver.call(
                        "content://${Constants.AUTHORITY}".toUri(),
                        Constants.METHOD_APPEND_LOG,
                        null,
                        Bundle().apply { putString(Constants.EXTRA_LOG_ENTRY, entry) },
                    )
                }
            }
        }
    }

    fun recent(): List<String> = synchronized(historyLock) { history.toList() }

    private fun sanitize(value: String): String {
        // Keep media names useful, but avoid turning the persistent support log into a path dump.
        return value.replace(Regex("(?:[A-Za-z]:)?[/\\\\][^\\s|]+[/\\\\]"), "…/")
            .replace('|', '/')
            .take(800)
    }

    fun d(xi: XposedInterface?, msg: String) = printLog(xi, msg, Level.DEBUG)
    fun i(xi: XposedInterface?, msg: String) = printLog(xi, msg, Level.INFO)
    fun e(xi: XposedInterface?, msg: String, tr: Throwable? = null) = printLog(xi, msg, Level.ERROR, tr)

    fun printLog(xi: XposedInterface?, msg: String, level: Level = Level.ERROR, tr: Throwable? = null) {
        val fullMsg = msg
        
        if (xi != null) {
            val finalMsg = if (tr != null) "$fullMsg\n${Log.getStackTraceString(tr)}" else fullMsg
            xi.log(level.xPriority, TAG, finalMsg)
        } else {
            when (level) {
                Level.DEBUG -> Log.d(TAG, fullMsg)
                Level.INFO -> Log.i(TAG, fullMsg)
                Level.WARN -> Log.w(TAG, fullMsg, tr)
                Level.ERROR -> Log.e(TAG, fullMsg, tr)
            }
        }
    }
}
