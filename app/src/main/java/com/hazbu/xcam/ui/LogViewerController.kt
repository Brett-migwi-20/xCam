package com.hazbu.xcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants
import com.hazbu.xcam.data.LogStore
import com.hazbu.xcam.utils.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.max

/** Terminal-style log console: single-line rows, 2-axis scrolling, filters, pause, auto-scroll. */
class LogViewerController(root: View) {

    private class LogLine(
        val seq: Long,
        val level: Logger.Level,
        val category: String,
        time: String,
        message: String,
    ) {
        private val timeTag = "[$time] "
        private val levelTag = "[${level.name}]".padEnd(10)
        private val categoryTag = "[$category]".padEnd(14)
        private val message = message
        val plain: String = "$timeTag$levelTag $categoryTag $message"

        val styled: CharSequence by lazy {
            val critical = level == Logger.Level.CRITICAL
            val sb = SpannableStringBuilder()
            fun add(text: String, color: Int, bold: Boolean = false) {
                val s = sb.length
                sb.append(text)
                sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            add(timeTag, DIM)
            add(levelTag, tagColor(level), bold = true)
            add(" ", DIM)
            add(categoryTag, DIM_CATEGORY)
            add(" ", DIM)
            add(message, messageColor(level), bold = critical)
            sb
        }

        companion object {
            private val GENERIC_EVENTS = setOf("module", "hook", "init")
            private const val DIM = 0xFF6E7681.toInt()
            private const val DIM_CATEGORY = 0xFF8B949E.toInt()

            fun tagColor(l: Logger.Level) = when (l) {
                Logger.Level.DEBUG -> 0xFF6E7681.toInt()
                Logger.Level.INFO -> 0xFF58A6FF.toInt()
                Logger.Level.SUCCESS -> 0xFF3FB950.toInt()
                Logger.Level.WARNING -> 0xFFE3B341.toInt()
                Logger.Level.ERROR -> 0xFFF85149.toInt()
                Logger.Level.CRITICAL -> 0xFFFF7B72.toInt()
            }

            fun messageColor(l: Logger.Level) = when (l) {
                Logger.Level.DEBUG -> 0xFF8B949E.toInt()
                Logger.Level.INFO -> 0xFFE6EDF3.toInt()
                Logger.Level.SUCCESS -> 0xFF56D364.toInt()
                Logger.Level.WARNING -> 0xFFE3B341.toInt()
                Logger.Level.ERROR -> 0xFFFF7B72.toInt()
                Logger.Level.CRITICAL -> 0xFFFFA198.toInt()
            }

            fun parse(seq: Long, raw: String): LogLine {
                val f = raw.split('|', limit = 5)
                if (f.size < 5) return LogLine(seq, Logger.Level.INFO, "LOG", "", raw)
                val level = when (f[1]) {
                    "WARN" -> Logger.Level.WARNING
                    else -> runCatching { Logger.Level.valueOf(f[1]) }.getOrDefault(Logger.Level.INFO)
                }
                val event = f[3]
                val details = f[4]
                val message = when {
                    details.isBlank() -> event
                    event in GENERIC_EVENTS -> details
                    else -> "$event: $details"
                }
                return LogLine(seq, level, f[2], f[0].substringAfter('T', f[0]), message)
            }
        }
    }

    private val ctx: Context = root.context
    private val handler = Handler(Looper.getMainLooper())
    private val rv: RecyclerView = root.findViewById(R.id.rv_logs)
    private val hsv: HorizontalScrollView = root.findViewById(R.id.hsv_logs)
    private val tvEmpty: TextView = root.findViewById(R.id.tv_logs_empty)
    private val tvCount: TextView = root.findViewById(R.id.tv_log_count)
    private val btnLatest: MaterialButton = root.findViewById(R.id.btn_scroll_latest)
    private val chipAuto: Chip = root.findViewById(R.id.chip_log_autoscroll)
    private val chipPause: Chip = root.findViewById(R.id.chip_log_pause)
    private val spinner: Spinner = root.findViewById(R.id.spinner_log_category)
    private val levelGroup: ChipGroup = root.findViewById(R.id.chips_log_levels)
    private val chipLogcat: Chip = root.findViewById(R.id.chip_log_logcat)

    private val visible = ArrayList<LogLine>()
    private val enabledLevels = Logger.Level.entries.toMutableSet()
    private var category = "All"
    private var lastSeq = 0L
    private var paused = false
    private var autoScroll = true
    private var unseen = 0
    private var maxLinePx = 0f
    private var started = false
    private val flushPending = AtomicBoolean(false)
    private val density = ctx.resources.displayMetrics.density
    private val rowPadH = (8 * density).toInt()
    private val rowPadV = (3 * density).toInt()
    private val measurePaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, ctx.resources.displayMetrics)
    }
    private val adapter = LogAdapter()
    private val flushTask = Runnable { flushPending.set(false); if (!paused) ingest() }

    init {
        rv.layoutManager = LinearLayoutManager(ctx)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.setHasFixedSize(true)
        rv.setItemViewCacheSize(24)
        rv.isNestedScrollingEnabled = false
        rv.addItemDecoration(Separator())
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = refreshLatestButton()
        })
        hsv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyContentWidth() }

        spinner.adapter = ArrayAdapter(
            ctx, android.R.layout.simple_spinner_dropdown_item,
            listOf("All") + Logger.Category.entries.map { it.name },
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selected = spinner.selectedItem?.toString() ?: "All"
                if (selected != category) { category = selected; if (started) rebuild() }
            }
        }


        chipLogcat.isChecked = com.hazbu.xcam.data.LogcatBridge.isRunning()
        chipLogcat.setOnCheckedChangeListener { _, checked ->
            if (checked) com.hazbu.xcam.data.LogcatBridge.start(ctx)
            else com.hazbu.xcam.data.LogcatBridge.stop()
        }
        for (i in 0 until levelGroup.childCount) {
            val chip = levelGroup.getChildAt(i) as? Chip ?: continue
            val level = Logger.Level.valueOf(chip.tag as String)
            chip.setOnCheckedChangeListener { _, checked ->
                if (checked) enabledLevels.add(level) else enabledLevels.remove(level)
                rebuild()
            }
        }
        chipAuto.setOnCheckedChangeListener { _, checked ->
            autoScroll = checked
            if (checked) scrollToLatest()
        }
        chipPause.setOnCheckedChangeListener { _, checked ->
            paused = checked
            chipPause.setText(if (checked) R.string.label_log_resume else R.string.label_log_pause)
            if (!checked) ingest() else updateCounter()
        }
        btnLatest.setOnClickListener { scrollToLatest() }
        root.findViewById<View>(R.id.btn_copy_logs).setOnClickListener { copy() }
        root.findViewById<View>(R.id.btn_export_logs).setOnClickListener { export() }
        root.findViewById<View>(R.id.btn_clear_logs).setOnClickListener { clear() }
    }

    fun start() {
        LogStore.setListener {
            if (flushPending.compareAndSet(false, true)) handler.postDelayed(flushTask, FLUSH_MS)
        }
        if (!started) { started = true; rebuild() } else if (!paused) ingest()
    }

    fun stop() {
        LogStore.setListener(null)
        handler.removeCallbacks(flushTask)
        flushPending.set(false)
    }

    // ---- data flow ----

    private fun matches(l: LogLine) =
        l.level in enabledLevels && (category == "All" || l.category == category)

    private fun ingest() {
        val items = LogStore.items(ctx, lastSeq)
        if (items.isEmpty()) return
        lastSeq = items.last().seq
        val wasAtBottom = !rv.canScrollVertically(1)
        val fresh = items.map { LogLine.parse(it.seq, it.line) }.filter(::matches)
            .takeLast(Constants.LOG_VIEW_MAX_ENTRIES)
        if (fresh.isEmpty()) return

        val start = visible.size
        visible.addAll(fresh)
        adapter.notifyItemRangeInserted(start, fresh.size)
        val overflow = visible.size - Constants.LOG_VIEW_MAX_ENTRIES
        if (overflow > 0) {
            visible.subList(0, overflow).clear()
            adapter.notifyItemRangeRemoved(0, overflow)
        }
        fresh.forEach { maxLinePx = max(maxLinePx, measurePaint.measureText(it.plain)) }
        applyContentWidth()

        if (autoScroll && wasAtBottom) rv.scrollToPosition(visible.lastIndex) else unseen += fresh.size
        updateCounter()
        rv.post { refreshLatestButton() }
    }

    private fun rebuild() {
        val upTo = if (paused) lastSeq else Long.MAX_VALUE
        val items = LogStore.items(ctx, 0, upTo)
        if (items.isNotEmpty()) lastSeq = max(lastSeq, items.last().seq)

        val picked = ArrayList<LogLine>()
        for (i in items.indices.reversed()) {
            val line = LogLine.parse(items[i].seq, items[i].line)
            if (matches(line)) picked.add(line)
            if (picked.size >= Constants.LOG_VIEW_MAX_ENTRIES) break
        }
        picked.reverse()
        visible.clear()
        visible.addAll(picked)
        maxLinePx = 0f
        visible.forEach { maxLinePx = max(maxLinePx, measurePaint.measureText(it.plain)) }
        unseen = 0
        adapter.notifyDataSetChanged()
        applyContentWidth()
        if (visible.isNotEmpty()) rv.scrollToPosition(visible.lastIndex)
        updateCounter()
        rv.post { refreshLatestButton() }
    }

    private fun clear() {
        LogStore.clear(ctx)           // display + stored history only; the pipeline is untouched
        visible.clear()
        maxLinePx = 0f
        unseen = 0
        adapter.notifyDataSetChanged()
        applyContentWidth()
        updateCounter()
        refreshLatestButton()
    }

    // ---- UI helpers ----

    /** Widens the list to the longest line so the HorizontalScrollView can pan; never wraps or truncates. */
    private fun applyContentWidth() {
        val needed = ceil(maxLinePx).toInt() + 2 * rowPadH + (16 * density).toInt()
        val viewport = hsv.width - hsv.paddingLeft - hsv.paddingRight
        val target = if (viewport > 0 && needed <= viewport) ViewGroup.LayoutParams.MATCH_PARENT else needed
        val lp = rv.layoutParams
        if (lp.width != target) { lp.width = target; rv.layoutParams = lp }
    }

    private fun scrollToLatest() {
        if (visible.isNotEmpty()) rv.scrollToPosition(visible.lastIndex)
        unseen = 0
        rv.post { refreshLatestButton() }
    }

    private fun refreshLatestButton() {
        val atBottom = !rv.canScrollVertically(1)
        if (atBottom) unseen = 0
        btnLatest.visibility = if (!atBottom && visible.isNotEmpty()) View.VISIBLE else View.GONE
        btnLatest.text = if (unseen > 0) ctx.getString(R.string.label_log_new_count, unseen)
        else ctx.getString(R.string.label_log_latest)
        tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCounter() {
        val base = ctx.getString(R.string.label_log_line_count, visible.size)
        tvCount.text = if (paused) "$base · ${ctx.getString(R.string.label_log_pause).uppercase()}D" else base
        tvEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun text() = visible.joinToString("\n") { it.plain }

    private fun copy() {
        if (visible.isEmpty()) return
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("xCam diagnostic log", text()))
        Toast.makeText(ctx, R.string.toast_logs_copied, Toast.LENGTH_SHORT).show()
    }

    private fun export() {
        if (visible.isEmpty()) return
        ctx.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text())
                putExtra(Intent.EXTRA_TITLE, "xCam diagnostic log")
            }, ctx.getString(R.string.btn_export_logs)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ---- RecyclerView plumbing ----

    private class RowHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private inner class LogAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount() = visible.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
                maxLines = 1
                setHorizontallyScrolling(true)   // never wrap
                ellipsize = null                 // never truncate
                includeFontPadding = false
                textDirection = View.TEXT_DIRECTION_LTR
                setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
            }
            return RowHolder(tv)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val line = visible[position]
            holder.tv.text = line.styled
            holder.tv.setBackgroundColor(if (line.level == Logger.Level.CRITICAL) CRITICAL_BG else Color.TRANSPARENT)
        }
    }

    private class Separator : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply { color = 0xFF21262D.toInt() }
        override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            out.bottom = 1
        }
        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val v = parent.getChildAt(i)
                c.drawRect(v.left.toFloat(), v.bottom.toFloat(), v.right.toFloat(), v.bottom + 1f, paint)
            }
        }
    }

    private companion object {
        const val TEXT_SP = 11f
        const val FLUSH_MS = 150L
        const val CRITICAL_BG = 0xFF3B1219.toInt()
    }
}