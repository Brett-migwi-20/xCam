package com.hazbu.xcam.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.ArrayDeque

/** File-backed, bounded log history living in xCam's own process (the provider writes here). */
object LogStore {
    class Item(val seq: Long, val line: String)

    private const val FILE_NAME = "xcam_diagnostic_log.txt"
    private const val TRIM_SLACK = 500

    private val lock = Any()
    private val buffer = ArrayDeque<Item>()
    private var nextSeq = 1L
    private var loaded = false
    private var writer: BufferedWriter? = null
    @Volatile private var listener: (() -> Unit)? = null

    fun setListener(value: (() -> Unit)?) { listener = value }

    fun append(context: Context, line: String) {
        if (line.isBlank()) return
        val entry = line.replace('\n', ' ').take(Constants.LOG_MAX_MESSAGE_CHARS + 200)
        synchronized(lock) {
            load(context)
            buffer.addLast(Item(nextSeq++, entry))
            runCatching { writer(context).run { write(entry); newLine(); flush() } }
            if (buffer.size > Constants.LOG_STORE_MAX_ENTRIES + TRIM_SLACK) {
                trim()
                rewrite(context)
            }
        }
        listener?.invoke()
    }

    /** Entries with afterSeq < seq <= upToSeq, oldest first. */
    fun items(context: Context, afterSeq: Long, upToSeq: Long = Long.MAX_VALUE): List<Item> =
        synchronized(lock) {
            load(context)
            val out = ArrayList<Item>()
            val it = buffer.descendingIterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.seq <= afterSeq) break
                if (item.seq <= upToSeq) out.add(item)
            }
            out.reverse()
            out
        }

    fun clear(context: Context) = synchronized(lock) {
        load(context)
        buffer.clear()
        closeWriter()
        runCatching { file(context).delete() }
    }

    // ---- internals (call with lock held) ----

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (f.exists()) f.readLines().takeLast(Constants.LOG_STORE_MAX_ENTRIES)
                .forEach { buffer.addLast(Item(nextSeq++, it)) }
        }
    }

    private fun writer(context: Context): BufferedWriter =
        writer ?: FileWriter(file(context), true).buffered().also { writer = it }

    private fun closeWriter() { runCatching { writer?.close() }; writer = null }

    private fun rewrite(context: Context) {
        closeWriter()
        runCatching {
            file(context).bufferedWriter().use { w -> buffer.forEach { w.write(it.line); w.newLine() } }
        }
    }

    /** Drops the oldest low-severity entries first so ERROR/CRITICAL history is kept longest. */
    private fun trim() {
        var excess = buffer.size - Constants.LOG_STORE_MAX_ENTRIES
        val it = buffer.iterator()
        while (excess > 0 && it.hasNext()) {
            if (!isImportant(it.next().line)) { it.remove(); excess-- }
        }
        while (excess > 0) { buffer.removeFirst(); excess-- }
    }

    private fun isImportant(line: String): Boolean {
        val a = line.indexOf('|'); if (a < 0) return false
        val b = line.indexOf('|', a + 1); if (b < 0) return false
        val level = line.substring(a + 1, b)
        return level == "ERROR" || level == "CRITICAL"
    }
}