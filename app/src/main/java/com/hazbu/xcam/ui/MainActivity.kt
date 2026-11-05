package com.hazbu.xcam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.hazbu.xcam.R
import com.hazbu.xcam.data.Constants.KEY_ENABLE_CAPTURE_NOTIF
import com.hazbu.xcam.data.Constants.KEY_IS_MIRRORED
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_APP
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_ROUTE
import com.hazbu.xcam.data.Constants.KEY_LATEST_PIPELINE_TIME
import com.hazbu.xcam.data.Constants.KEY_ROTATION_ANGLE
import com.hazbu.xcam.data.Constants.KEY_MEDIA_PATH
import com.hazbu.xcam.data.Constants.PREFS_NAME
import com.hazbu.xcam.data.Constants.ACTION_PLAYBACK_NEXT
import com.hazbu.xcam.data.Constants.ACTION_PLAYBACK_PREVIOUS
import com.hazbu.xcam.data.Constants.ACTION_PLAYBACK_TOGGLE
import com.hazbu.xcam.data.PlaybackGateway
import com.hazbu.xcam.utils.Logger
import com.hazbu.xcam.utils.MediaConverter
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val VIRTUAL_PREFIX = "virtual."
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvNoPreview: TextView
    private lateinit var btnDeleteMedia: MaterialButton
    private lateinit var btnMirror: MaterialButton
    private lateinit var btnRotateLeft: MaterialButton
    private lateinit var btnRotateRight: MaterialButton
    private lateinit var btnSelectVideo: MaterialButton
    private lateinit var tvRotationValue: TextView
    private lateinit var tvPlaylistState: TextView
    private lateinit var btnPlaylistPrevious: MaterialButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPlaylistNext: MaterialButton
    private lateinit var tvModuleStatus: TextView
    private lateinit var cardModuleStatus: com.google.android.material.card.MaterialCardView
    private lateinit var cardScopedApps: com.google.android.material.card.MaterialCardView
    private lateinit var layoutScopedApps: LinearLayout

    private lateinit var switchCaptureNotif: MaterialSwitch
    private lateinit var tvPipelineEmpty: TextView
    private lateinit var layoutPipelineDetails: LinearLayout
    private lateinit var tvPipelineApp: TextView
    private lateinit var tvPipelineRoute: TextView
    private lateinit var tvPipelineTime: TextView
    private lateinit var btnCopyPipeline: MaterialButton
    private lateinit var btnClearPipeline: MaterialButton
    private lateinit var spinnerLogCategory: Spinner
    private lateinit var spinnerLogLevel: Spinner
    private lateinit var tvLogs: TextView
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var btnExportLogs: MaterialButton
    private lateinit var btnClearLogs: MaterialButton

    private var isMirrored = false
    private var rotationAngle = 0
    private var mXposedService: XposedService? = null
    private val mediaImportExecutor = Executors.newSingleThreadExecutor()
    private val logRefreshHandler = Handler(Looper.getMainLooper())
    private var displayedLogs = ""
    private val logRefreshTask = object : Runnable {
        override fun run() {
            loadLogs()
            logRefreshHandler.postDelayed(this, 2_000L)
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for Capture Alert Card", Toast.LENGTH_SHORT).show()
        }
    }

    private val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data: Intent? = result.data
            data?.data?.let { uri ->
                copyMediaToInternal(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        XposedServiceHelper.registerListener(this)
        setupWindowInsets()
        setupUI()
        loadSettings()
        updateModuleStatusUI()
        Logger.event(this, Logger.Category.UI, Logger.Level.INFO, "activity_created")
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        updateModuleStatusUI()
        logRefreshHandler.removeCallbacks(logRefreshTask)
        logRefreshHandler.post(logRefreshTask)
    }

    override fun onPause() {
        logRefreshHandler.removeCallbacks(logRefreshTask)
        super.onPause()
    }

    override fun onServiceBind(service: XposedService) {
        mXposedService = service
        runOnUiThread { updateModuleStatusUI() }
    }

    override fun onServiceDied(service: XposedService) {
        mXposedService = null
        runOnUiThread { updateModuleStatusUI() }
    }

    private fun updateModuleStatusUI() {
        val service = mXposedService
        val isActive = service != null || checkSelfActive()

        if (isActive) {
            val statusText = if (service != null && !service.frameworkName.isNullOrBlank()) {
                "${getString(R.string.status_module_active)} (${service.frameworkName})"
            } else {
                getString(R.string.status_module_active)
            }
            tvModuleStatus.text = statusText
            val activeColor = MaterialColors.getColor(tvModuleStatus, androidx.appcompat.R.attr.colorPrimary)
            tvModuleStatus.setTextColor(activeColor)
            cardModuleStatus.strokeColor = activeColor
            refreshLSPosedScope()
        } else {
            tvModuleStatus.text = getString(R.string.status_module_inactive)
            val inactiveColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val strokeColor = MaterialColors.getColor(tvModuleStatus, com.google.android.material.R.attr.colorOutline)
            tvModuleStatus.setTextColor(inactiveColor)
            cardModuleStatus.strokeColor = strokeColor
            cardScopedApps.visibility = View.GONE
        }
    }

    private fun setupWindowInsets() {
        val mainView = findViewById<View>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val basePadding = (24 * density).toInt()
            v.setPadding(
                systemBars.left + basePadding,
                systemBars.top + basePadding,
                systemBars.right + basePadding,
                systemBars.bottom + basePadding
            )
            insets
        }
    }

    private fun setupUI() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        setupTitleSpannable(tvTitle)
        ivPreview = findViewById(R.id.iv_preview)
        tvNoPreview = findViewById(R.id.tv_no_preview)
        btnDeleteMedia = findViewById(R.id.btn_delete_media)
        btnMirror = findViewById(R.id.btn_mirror)
        btnRotateLeft = findViewById(R.id.btn_rotate_left)
        btnRotateRight = findViewById(R.id.btn_rotate_right)
        btnSelectVideo = findViewById(R.id.btn_select_video)
        tvRotationValue = findViewById(R.id.tv_rotation_value)
        tvPlaylistState = findViewById(R.id.tv_playlist_state)
        btnPlaylistPrevious = findViewById(R.id.btn_playlist_previous)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPlaylistNext = findViewById(R.id.btn_playlist_next)
        tvModuleStatus = findViewById(R.id.tv_module_status)
        cardModuleStatus = findViewById(R.id.card_module_status)
        cardScopedApps = findViewById(R.id.card_scoped_apps)
        layoutScopedApps = findViewById(R.id.layout_scoped_apps)

        switchCaptureNotif = findViewById(R.id.switch_capture_notif)
        tvPipelineEmpty = findViewById(R.id.tv_pipeline_empty)
        layoutPipelineDetails = findViewById(R.id.layout_pipeline_details)
        tvPipelineApp = findViewById(R.id.tv_pipeline_app)
        tvPipelineRoute = findViewById(R.id.tv_pipeline_route)
        tvPipelineTime = findViewById(R.id.tv_pipeline_time)
        btnCopyPipeline = findViewById(R.id.btn_copy_pipeline)
        btnClearPipeline = findViewById(R.id.btn_clear_pipeline)
        spinnerLogCategory = findViewById(R.id.spinner_log_category)
        spinnerLogLevel = findViewById(R.id.spinner_log_level)
        tvLogs = findViewById(R.id.tv_logs)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnExportLogs = findViewById(R.id.btn_export_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)
        setupLogsUi()

        switchCaptureNotif.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(KEY_ENABLE_CAPTURE_NOTIF, isChecked)
            }
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        btnCopyPipeline.setOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val app = prefs.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
            val route = prefs.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
            val time = prefs.getLong(KEY_LATEST_PIPELINE_TIME, 0L)

            if (app.isEmpty() && route.isEmpty()) {
                Toast.makeText(this, "No route to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val formattedTime = if (time > 0L) {
                SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(time))
            } else ""

            val copyText = buildString {
                if (app.isNotEmpty()) append("Target: $app\n")
                if (route.isNotEmpty()) append("Route: $route\n")
                if (formattedTime.isNotEmpty()) append("Time: $formattedTime")
            }.trim()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("xCam Pipeline", copyText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, "Pipeline route copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClearPipeline.setOnClickListener {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                remove(KEY_LATEST_PIPELINE_APP)
                remove(KEY_LATEST_PIPELINE_ROUTE)
                remove(KEY_LATEST_PIPELINE_TIME)
            }
            loadTelemetryUI()
            Toast.makeText(this, "Pipeline history cleared", Toast.LENGTH_SHORT).show()
        }

        btnMirror.setOnClickListener {
            isMirrored = !isMirrored
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateLeft.setOnClickListener {
            rotationAngle = (rotationAngle - 90 + 360) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnRotateRight.setOnClickListener {
            rotationAngle = (rotationAngle + 90) % 360
            saveSettings()
            updatePreviewTransform()
        }
        btnPlaylistPrevious.setOnClickListener { performPlaybackAction(ACTION_PLAYBACK_PREVIOUS) }
        btnPlayPause.setOnClickListener { performPlaybackAction(ACTION_PLAYBACK_TOGGLE) }
        btnPlaylistNext.setOnClickListener { performPlaybackAction(ACTION_PLAYBACK_NEXT) }
        btnDeleteMedia.setOnClickListener { deleteMedia() }
        btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            }
            videoPickerLauncher.launch(intent)
        }
    }

    private fun copyMediaToInternal(uri: Uri) {
        // File copying runs on the background executor. The Transformer itself is
        // started on the main thread inside MediaConverter, and its callbacks
        // (onSuccess / onError) are delivered on the main thread.
        mediaImportExecutor.execute {
            try {
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val isImage = mimeType.startsWith("image/")
                val isVideo = mimeType.startsWith("video/")

                if (!isImage && !isVideo) {
                    runOnUiThread {
                        Toast.makeText(this, "Invalid file type. Only images and videos are supported.", Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }

                val extension = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType) ?: "bin"
                val importId = System.currentTimeMillis()

                if (isImage) {
                    val internalImage = File(filesDir, "$VIRTUAL_PREFIX$importId.$extension")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(internalImage).use { output -> input.copyTo(output) }
                    }

                    val outputVideo = File(filesDir, "$VIRTUAL_PREFIX$importId.mp4")
                    if (outputVideo.exists()) outputVideo.delete() // Transformer won't overwrite

                    runOnUiThread {
                        Toast.makeText(this, "Converting image to video...", Toast.LENGTH_SHORT).show()
                    }

                    MediaConverter.convertImageToMp4(
                        context = this,
                        imageUri = Uri.fromFile(internalImage),
                        outputPath = outputVideo.absolutePath,
                        onSuccess = { path ->
                            // Main thread
                            internalImage.delete()
                            saveMediaPath(path)
                            Logger.event(this, Logger.Category.MEDIA, Logger.Level.INFO, "media_imported", outputVideo.name)
                            Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
                        },
                        onError = { e ->
                            // Main thread
                            internalImage.delete()
                            outputVideo.delete()
                            Logger.event(this, Logger.Category.ERROR, Logger.Level.ERROR, "media_import_failed", e.message.orEmpty(), e)
                            Toast.makeText(this, getString(R.string.toast_media_import_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    )
                } else {
                    val internalFile = File(filesDir, "$VIRTUAL_PREFIX$importId.$extension")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(internalFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    runOnUiThread {
                        saveMediaPath(internalFile.absolutePath)
                        Logger.event(this, Logger.Category.MEDIA, Logger.Level.INFO, "media_imported", internalFile.name)
                        Toast.makeText(this, R.string.toast_media_imported, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Logger.event(this, Logger.Category.ERROR, Logger.Level.ERROR, "media_import_failed", e.message.orEmpty(), e)
                runOnUiThread {
                    val errorMsg = getString(R.string.toast_media_import_failed, e.message)
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val mediaPath = prefs.getString(KEY_MEDIA_PATH, "") ?: ""
        isMirrored = prefs.getBoolean(KEY_IS_MIRRORED, false)
        rotationAngle = prefs.getInt(KEY_ROTATION_ANGLE, 0)
        switchCaptureNotif.isChecked = prefs.getBoolean(KEY_ENABLE_CAPTURE_NOTIF, true)
        updatePreview(mediaPath)
        updatePreviewTransform()
        updatePlaylistControls(prefs)
        loadTelemetryUI()
    }

    private fun loadTelemetryUI() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val app = prefs.getString(KEY_LATEST_PIPELINE_APP, "") ?: ""
        val route = prefs.getString(KEY_LATEST_PIPELINE_ROUTE, "") ?: ""
        val time = prefs.getLong(KEY_LATEST_PIPELINE_TIME, 0L)

        if (app.isEmpty() || route.isEmpty()) {
            tvPipelineEmpty.visibility = View.VISIBLE
            layoutPipelineDetails.visibility = View.GONE
        } else {
            tvPipelineEmpty.visibility = View.GONE
            layoutPipelineDetails.visibility = View.VISIBLE
            tvPipelineApp.text = getString(R.string.label_last_active_app, app)
            tvPipelineRoute.text = route
            val formatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
            tvPipelineTime.text = getString(R.string.label_last_timestamp, formatted)
        }
    }

    private fun updatePreviewTransform() {
        ivPreview.scaleX = if (isMirrored) -1f else 1f
        ivPreview.rotation = rotationAngle.toFloat()
        tvRotationValue.text = getString(R.string.label_rotation_value, rotationAngle)
        val activeColor = MaterialColors.getColor(btnMirror, androidx.appcompat.R.attr.colorPrimary)
        val activeIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnPrimary, android.graphics.Color.WHITE)
        val inactiveColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.LTGRAY)
        val inactiveIconColor = MaterialColors.getColor(btnMirror, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.DKGRAY)
        btnMirror.setBackgroundColor(if (isMirrored) activeColor else inactiveColor)
        btnMirror.iconTint = android.content.res.ColorStateList.valueOf(if (isMirrored) activeIconColor else inactiveIconColor)
    }

    private fun updatePreview(path: String) {
        if (path.isEmpty() || !File(path).exists()) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.visibility = View.VISIBLE
            btnDeleteMedia.visibility = View.GONE
            return
        }
        tvNoPreview.visibility = View.GONE
        btnDeleteMedia.visibility = View.VISIBLE
        try {
            if (path.endsWith(".mp4", ignoreCase = true)) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                val bitmap = retriever.getFrameAtTime(1000000)
                ivPreview.setImageBitmap(bitmap)
                retriever.release()
            } else {
                val bitmap = BitmapFactory.decodeFile(path)
                ivPreview.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            ivPreview.setImageBitmap(null)
            tvNoPreview.visibility = View.VISIBLE
        }
    }

    private fun saveSettings() {
        PlaybackGateway.setOrientation(this, rotationAngle, isMirrored)
        Logger.event(this, Logger.Category.ROTATION, Logger.Level.INFO, "orientation_requested", "rotation=$rotationAngle, mirror=$isMirrored")
    }

    private fun saveMediaPath(path: String) {
        PlaybackGateway.setMedia(this, path)
        updatePreview(path)
        updatePreviewTransform()
        loadSettings()
    }

    private fun deleteMedia() {
        val path = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MEDIA_PATH, null)
        path?.let { File(it).takeIf(File::exists)?.delete() }
        PlaybackGateway.setMedia(this, "")
        loadSettings()
        Logger.event(this, Logger.Category.MEDIA, Logger.Level.INFO, "media_removed", path?.substringAfterLast('/').orEmpty())
        Toast.makeText(this, R.string.toast_media_removed, Toast.LENGTH_SHORT).show()
    }

    private fun performPlaybackAction(action: String) {
        PlaybackGateway.action(this, action)
        Logger.event(this, Logger.Category.PLAYBACK, Logger.Level.INFO, "ui_action", action.substringAfterLast('.'))
        loadSettings()
    }

    private fun updatePlaylistControls(prefs: android.content.SharedPreferences) {
        val index = prefs.getInt(com.hazbu.xcam.data.Constants.KEY_PLAYLIST_INDEX, -1)
        val state = prefs.getString(com.hazbu.xcam.data.Constants.KEY_PLAYBACK_STATE, "paused") ?: "paused"
        val raw = prefs.getString(com.hazbu.xcam.data.Constants.KEY_PLAYLIST, "[]") ?: "[]"
        val size = runCatching { org.json.JSONArray(raw).length() }.getOrDefault(0)
        tvPlaylistState.text = if (size == 0) getString(R.string.label_playlist_empty) else getString(R.string.label_playlist_state, index + 1, size, state.replaceFirstChar { it.uppercase() })
        val playing = state == "playing"
        btnPlayPause.setIconResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlayPause.iconTint = ContextCompat.getColorStateList(
            this,
            if (playing) R.color.playback_red else R.color.playback_green
        )
        btnPlayPause.contentDescription = getString(if (playing) R.string.btn_pause else R.string.btn_play)
        val enabled = size > 0
        btnPlaylistPrevious.isEnabled = enabled
        btnPlayPause.isEnabled = enabled
        btnPlaylistNext.isEnabled = enabled
    }

    private fun setupLogsUi() {
        val categoryOptions = listOf("All") + Logger.Category.entries.map { it.name }
        val levelOptions = listOf("All") + Logger.Level.entries.map { it.name }
        spinnerLogCategory.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoryOptions)
        spinnerLogLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levelOptions)
        val reload = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = loadLogs()
        }
        spinnerLogCategory.onItemSelectedListener = reload
        spinnerLogLevel.onItemSelectedListener = reload
        btnCopyLogs.setOnClickListener { copyLogsToClipboard() }
        btnExportLogs.setOnClickListener {
            if (displayedLogs.isBlank()) return@setOnClickListener
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, displayedLogs)
                putExtra(Intent.EXTRA_TITLE, "xCam diagnostic log")
            }, getString(R.string.btn_export_logs)))
        }
        btnClearLogs.setOnClickListener {
            contentResolver.call(android.net.Uri.parse("content://com.hazbu.xcam.provider"), com.hazbu.xcam.data.Constants.METHOD_CLEAR_LOGS, null, null)
            loadLogs()
        }
    }

    private fun loadLogs() {
        val logs = runCatching {
            contentResolver.call(
                android.net.Uri.parse("content://com.hazbu.xcam.provider"),
                com.hazbu.xcam.data.Constants.METHOD_GET_LOGS,
                null,
                null,
            )?.getStringArrayList("logs").orEmpty()
        }.getOrDefault(emptyList())
        val category = spinnerLogCategory.selectedItem?.toString() ?: "All"
        val level = spinnerLogLevel.selectedItem?.toString() ?: "All"
        displayedLogs = logs.asReversed().filter { entry ->
            val fields = entry.split('|', limit = 5)
            (category == "All" || fields.getOrNull(2) == category) &&
                    (level == "All" || fields.getOrNull(1) == level)
        }.joinToString("\n") { it.replace('|', ' ') }
        tvLogs.text = displayedLogs.ifBlank { getString(R.string.label_no_logs) }
    }

    private fun copyLogsToClipboard() {
        if (displayedLogs.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(
            ClipData.newPlainText("xCam diagnostic log", displayedLogs)
        )
        Toast.makeText(this, R.string.toast_logs_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        logRefreshHandler.removeCallbacks(logRefreshTask)
        mediaImportExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupTitleSpannable(tvTitle: TextView) {
        val titleText = tvTitle.text.toString()
        val spannable = SpannableStringBuilder(titleText)
        val primaryColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, "#FF6200EE".toColorInt())
        spannable.setSpan(ForegroundColorSpan(primaryColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvTitle.text = spannable
    }

    private fun checkSelfActive(): Boolean = false

    private fun getOfficialScope(): List<String> {
        return try {
            mXposedService?.scope ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshLSPosedScope() {
        layoutScopedApps.removeAllViews()
        val officialScope = getOfficialScope().filter { it != packageName }
        if (officialScope.isNotEmpty()) {
            officialScope.forEach { addAppIconToLayout(it) }
            cardScopedApps.visibility = View.VISIBLE
        } else {
            cardScopedApps.visibility = View.GONE
        }
    }

    private fun addAppIconToLayout(pkgName: String) {
        try {
            val icon = packageManager.getApplicationIcon(pkgName)
            val size = (40 * resources.displayMetrics.density).toInt()
            val imageView = ImageView(this).apply {
                val params = LinearLayout.LayoutParams(size, size)
                params.setMargins(0, 0, 16, 0)
                layoutParams = params
                setImageDrawable(icon)
                contentDescription = pkgName
                setOnClickListener { Toast.makeText(this@MainActivity, pkgName, Toast.LENGTH_SHORT).show() }
            }
            layoutScopedApps.addView(imageView)
        } catch (_: Exception) {}
    }
}