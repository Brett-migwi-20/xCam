package com.hazbu.xcam.data

import android.content.Context
import android.os.Process
import java.util.Calendar

/** Mirrors logcat into LogStore. Own-process only unless READ_LOGS has been granted via adb. */
object LogcatBridge {
    private val lock = Any()
    private var thread: Thread? = null
    private var process: java.lang.Process? = null
    private val LINE = Regex("""^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+\d+\s+([VDIWEFA])\s+(.+?)\s*: (.*)$""")

    fun isRunning() = synchronized(lock) { thread?.isAlive == true }

    fun start(context: Context) = synchronized(lock) {
        if (thread?.isAlive == true) return
        val app = context.applicationContext
        val ownPid = Process.myPid()
        val year = Calendar.getInstance().get(Calendar.YEAR)
        thread = Thread({
            runCatching {
                val p = ProcessBuilder("logcat", "-v", "threadtime", "-T", "100")
                    .redirectErrorStream(true).start()
                synchronized(lock) { process = p }
                p.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        if (Thread.currentThread().isInterrupted) break
                        val m = LINE.matchEntire(raw) ?: continue
                        val (date, time, pid, prio, tag, msg) = m.destructured
                        if (tag == "xCam") continue                      // already stored by Logger
                        val foreign = pid.toInt() != ownPid
                        if (foreign && priorityRank(prio) < priorityRank(Constants.LOGCAT_MIN_PRIORITY)) continue
                        val level = when (prio) {
                            "V", "D" -> "DEBUG"; "I" -> "INFO"; "W" -> "WARNING"
                            "E" -> "ERROR"; else -> "CRITICAL"
                        }
                        val ts = "$year-$date".replace(' ', 'T') + "T$time"
                        val details = msg.replace('|', '/').take(Constants.LOG_MAX_MESSAGE_CHARS)
                        LogStore.append(app, "$ts|$level|LOGCAT|$tag($pid)|$details")
                    }
                }
            }
        }, "xCam-LogcatBridge").apply { isDaemon = true; start() }
    }

    fun stop() = synchronized(lock) {
        runCatching { process?.destroy() }
        process = null
        thread?.interrupt()
        thread = null
    }

    private fun priorityRank(p: String) = "VDIWEFA".indexOf(p)
}