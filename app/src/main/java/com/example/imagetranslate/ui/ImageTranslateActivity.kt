package com.example.imagetranslate.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.imagetranslate.App
import com.example.imagetranslate.R
import com.example.imagetranslate.databinding.ActivityImageTranslateBinding
import com.example.imagetranslate.databinding.PopupOcrReviewBinding
import com.example.imagetranslate.databinding.PopupReplacementInfoBinding
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.inpaint.InpaintResult
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import com.example.imagetranslate.screenshot.OneShotScreenCaptureService
import com.example.imagetranslate.screenshot.ScreenshotMonitorPreferences
import com.example.imagetranslate.screenshot.ScreenshotMonitorService
import com.example.imagetranslate.screenshot.TranslatedImageGallerySaver
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

class ImageTranslateActivity : AppCompatActivity() {

    private companion object {
        const val STATE_PENDING_CAMERA_URI = "pending_camera_uri"
        const val TRANSLATION_WORKFLOW_TIMEOUT_MS = 90_000L
        const val UI_PREFERENCES = "image_translate_ui"
        const val PREFERENCE_ADVANCED_SETTINGS_EXPANDED = "advanced_settings_expanded"
        const val PREFERENCE_REVIEW_BEFORE_TRANSLATION = "review_before_translation"
    }

    private enum class WorkflowStage {
        READY,
        REVIEW,
        RESULT
    }

    private lateinit var binding: ActivityImageTranslateBinding
    private val ocrManager by lazy { OCRManager(applicationContext) }
    private val translateManager = TranslateManager()
    private val inpainter = ImageInpainter()
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    private var workflowStage = WorkflowStage.READY
    private var workflowBusy = false
    private var updatingScreenshotMonitorControl = false
    private var modelDownloadJob: Job? = null

    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var ocrReviewRegions: MutableList<OcrReviewRegion>? = null
    private var replacementRegions = emptyList<ReplacementRegion>()
    private var replacementInfoPopup: PopupWindow? = null
    private var pendingCameraUri: Uri? = null

    private data class TranslatedRegion(
        val source: RecognizedText,
        val translation: String,
        val translated: Boolean,
        val translationFailed: Boolean = false
    )

    private data class OcrReviewRegion(
        var source: RecognizedText,
        var included: Boolean = true
    )

    private data class TextStyle(
        val foregroundColor: Int,
        val isDarkBackground: Boolean,
        val typeface: Typeface,
        val fontSizeMultiplier: Float
    )

    private data class ReplacementRegion(
        val bounds: Rect,
        val translatedPatch: Bitmap,
        val sourceText: String,
        val translatedText: String,
        val consensusScore: Float,
        val passCount: Int,
        var showingOriginal: Boolean = false
    )

    private data class RenderedRegion(
        val bounds: Rect,
        val sourceText: String,
        val translatedText: String,
        val consensusScore: Float,
        val passCount: Int
    )

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { loadImage(it) } }

    private val takePhoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (captured && uri != null) {
            loadImage(uri)
        } else if (uri != null) {
            runCatching { contentResolver.delete(uri, null, null) }
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureScreenshotMediaPermission()
        } else {
            updateScreenshotMonitorControl(running = false)
            Toast.makeText(
                this,
                R.string.screenshot_monitor_notification_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestScreenshotMediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && hasFullImageAccess()) {
            ensureScreenshotOverlayPermission()
        } else {
            updateScreenshotMonitorControl(running = false)
            Toast.makeText(
                this,
                R.string.screenshot_monitor_media_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestScreenshotOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startScreenshotMonitor()
        } else {
            updateScreenshotMonitorControl(running = false)
            Toast.makeText(
                this,
                R.string.screenshot_monitor_overlay_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestOptionalScreenshotOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val enabled = Settings.canDrawOverlays(this)
        ScreenshotMonitorPreferences.setOverlayEnabled(this, enabled)
        updateScreenshotOverlayControl(enabled)
        if (!enabled) {
            Toast.makeText(
                this,
                R.string.screenshot_monitor_overlay_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestActiveCaptureOverlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            showActiveScreenTranslationOverlay()
        } else {
            Toast.makeText(
                this,
                R.string.active_screenshot_overlay_denied,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraUri = savedInstanceState
            ?.getString(STATE_PENDING_CAMERA_URI)
            ?.let(Uri::parse)
        binding = ActivityImageTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uiPreferences = getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
        binding.switchReviewBeforeTranslation.isChecked = uiPreferences.getBoolean(
            PREFERENCE_REVIEW_BEFORE_TRANSLATION,
            false
        )
        setAdvancedSettingsExpanded(
            expanded = uiPreferences.getBoolean(PREFERENCE_ADVANCED_SETTINGS_EXPANDED, false),
            animate = false,
            persist = false
        )
        setupListeners()
        downloadModel()
        handleScreenshotIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) restoreConfiguredScreenshotMonitor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScreenshotIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCameraUri?.let { outState.putString(STATE_PENDING_CAMERA_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun setupListeners() {
        binding.replacementOverlay.attachTo(binding.ivResult)
        binding.ivResult.setOnMatrixChangedListener {
            binding.replacementOverlay.invalidate()
        }
        binding.replacementOverlay.setOnMarkerClickListener { index, x, y ->
            if (ocrReviewRegions != null) {
                showOcrReview(index, x, y)
            } else {
                showReplacementInfo(index, x, y)
            }
        }
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }
        binding.btnTakePhoto.setOnClickListener { openCamera() }
        binding.btnCaptureScreenshot.setOnClickListener { beginActiveScreenCapture() }
        binding.switchScreenshotMonitor.setOnCheckedChangeListener { _, checked ->
            if (updatingScreenshotMonitorControl) return@setOnCheckedChangeListener
            if (checked) {
                beginScreenshotMonitorSetup()
            } else {
                stopScreenshotMonitor()
            }
        }
        binding.switchScreenshotAutoStart.setOnCheckedChangeListener { _, checked ->
            if (updatingScreenshotMonitorControl) return@setOnCheckedChangeListener
            if (!binding.switchScreenshotMonitor.isChecked) {
                updateScreenshotMonitorControl(running = false)
                Toast.makeText(
                    this,
                    R.string.screenshot_auto_start_requires_monitor,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnCheckedChangeListener
            }
            ScreenshotMonitorPreferences.setStartOnBootEnabled(this, checked)
        }
        binding.switchScreenshotOverlay.setOnCheckedChangeListener { _, checked ->
            if (updatingScreenshotMonitorControl) return@setOnCheckedChangeListener
            if (checked && !Settings.canDrawOverlays(this)) {
                ScreenshotMonitorPreferences.setOverlayEnabled(this, false)
                updateScreenshotOverlayControl(false)
                requestOptionalScreenshotOverlayPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnCheckedChangeListener
            }
            ScreenshotMonitorPreferences.setOverlayEnabled(this, checked)
            if (!checked) {
                startService(
                    Intent(this, ScreenshotMonitorService::class.java)
                        .setAction(ScreenshotMonitorService.ACTION_DISMISS_OVERLAY)
                )
            }
        }
        binding.btnAdvancedSettings.setOnClickListener {
            setAdvancedSettingsExpanded(
                expanded = binding.advancedSettingsPanel.visibility != View.VISIBLE,
                animate = true,
                persist = true
            )
        }
        binding.switchReviewBeforeTranslation.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(PREFERENCE_REVIEW_BEFORE_TRANSLATION, checked)
                .apply()
        }

        binding.translationModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            translationMode = when (checkedId) {
                binding.btnModeChineseEnglish.id -> TranslationMode.CHINESE_TO_ENGLISH
                binding.btnModeEnglishChinese.id -> TranslationMode.ENGLISH_TO_CHINESE
                else -> TranslationMode.AUTO_BIDIRECTIONAL
            }
        }

        binding.btnTranslate.setOnClickListener {
            val bitmap = originalBitmap ?: return@setOnClickListener
            when (workflowStage) {
                WorkflowStage.READY -> beginOcrReview(
                    bitmap,
                    autoTranslate = !binding.switchReviewBeforeTranslation.isChecked
                )
                WorkflowStage.REVIEW -> {
                    if (!translateManager.areModelsReady) {
                        downloadModel()
                        Toast.makeText(
                            this,
                            "翻译模型未就绪，正在重新下载",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    if (!App.isOpenCVReady) {
                        Toast.makeText(this, "OpenCV 未就绪，请稍后", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    translateReviewedImage(bitmap)
                }
                WorkflowStage.RESULT -> restartRecognition()
            }
        }
        binding.btnRestartRecognition.setOnClickListener { restartRecognition() }

        binding.btnSave.setOnClickListener {
            processedBitmap?.let { saveImage(it) }
        }

        binding.checkShowMarkers.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) dismissReplacementInfo()
            binding.replacementOverlay.visibility = if (isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        binding.ivResult.setOnSingleTapConfirmedListener { x, y ->
            val markerHandled = binding.replacementOverlay.visibility == View.VISIBLE &&
                binding.replacementOverlay.performMarkerClick(x, y)
            if (!markerHandled) toggleReplacementAt(x, y)
        }
    }

    private fun beginScreenshotMonitorSetup() {
        if (ScreenshotMonitorPreferences.isDisclosureAccepted(this)) {
            continueScreenshotMonitorSetup()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.screenshot_monitor_disclosure_title)
            .setMessage(R.string.screenshot_monitor_disclosure_message)
            .setNegativeButton(R.string.screenshot_monitor_cancel) { _, _ ->
                updateScreenshotMonitorControl(running = false)
            }
            .setPositiveButton(R.string.screenshot_monitor_continue) { _, _ ->
                ScreenshotMonitorPreferences.setDisclosureAccepted(this)
                continueScreenshotMonitorSetup()
            }
            .setOnCancelListener { updateScreenshotMonitorControl(running = false) }
            .show()
    }

    private fun beginActiveScreenCapture() {
        ensureActiveCaptureOverlayPermission()
    }

    private fun ensureActiveCaptureOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            showActiveScreenTranslationOverlay()
            return
        }
        requestActiveCaptureOverlayPermission.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun showActiveScreenTranslationOverlay() {
        OneShotScreenCaptureService.showOverlay(this)
        Toast.makeText(
            this,
            R.string.active_screenshot_session_started,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun continueScreenshotMonitorSetup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ensureScreenshotMediaPermission()
        }
    }

    private fun ensureScreenshotMediaPermission() {
        if (hasFullImageAccess()) {
            ensureScreenshotOverlayPermission()
            return
        }
        requestScreenshotMediaPermission.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        )
    }

    private fun ensureScreenshotOverlayPermission() {
        if (!ScreenshotMonitorPreferences.isOverlayEnabled(this)) {
            startScreenshotMonitor()
            return
        }
        if (Settings.canDrawOverlays(this)) {
            startScreenshotMonitor()
            return
        }
        requestScreenshotOverlayPermission.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun hasFullImageAccess(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startScreenshotMonitor() {
        ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(this, true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenshotMonitorService::class.java)
                .setAction(ScreenshotMonitorService.ACTION_START)
        )
        updateScreenshotMonitorControl(running = true)
        Toast.makeText(this, R.string.screenshot_monitor_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopScreenshotMonitor() {
        ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(this, false)
        ScreenshotMonitorPreferences.setStartOnBootEnabled(this, false)
        startService(
            Intent(this, ScreenshotMonitorService::class.java)
                .setAction(ScreenshotMonitorService.ACTION_STOP)
        )
        updateScreenshotMonitorControl(running = false)
        Toast.makeText(this, R.string.screenshot_monitor_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun updateScreenshotMonitorControl(
        running: Boolean = ScreenshotMonitorService.isRunning
    ) {
        updatingScreenshotMonitorControl = true
        binding.switchScreenshotMonitor.isChecked = running
        binding.switchScreenshotAutoStart.isEnabled = running
        binding.switchScreenshotAutoStart.isChecked = running &&
            ScreenshotMonitorPreferences.isStartOnBootEnabled(this)
        val overlayEnabled = ScreenshotMonitorPreferences.isOverlayEnabled(this) &&
            Settings.canDrawOverlays(this)
        if (!overlayEnabled && ScreenshotMonitorPreferences.isOverlayEnabled(this)) {
            ScreenshotMonitorPreferences.setOverlayEnabled(this, false)
        }
        binding.switchScreenshotOverlay.isChecked = overlayEnabled
        binding.switchScreenshotMonitor.contentDescription = getString(
            if (running) R.string.screenshot_monitor_status_on
            else R.string.screenshot_monitor_status_off
        )
        updatingScreenshotMonitorControl = false
    }

    private fun updateScreenshotOverlayControl(enabled: Boolean) {
        updatingScreenshotMonitorControl = true
        binding.switchScreenshotOverlay.isChecked = enabled
        updatingScreenshotMonitorControl = false
    }

    private fun restoreConfiguredScreenshotMonitor() {
        if (ScreenshotMonitorService.isRunning ||
            !ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(this) ||
            !hasFullImageAccess() ||
            (ScreenshotMonitorPreferences.isOverlayEnabled(this) &&
                !Settings.canDrawOverlays(this)) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED)
        ) {
            updateScreenshotMonitorControl()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, ScreenshotMonitorService::class.java)
                .setAction(ScreenshotMonitorService.ACTION_START)
        )
        updateScreenshotMonitorControl(running = true)
    }

    private fun setAdvancedSettingsExpanded(
        expanded: Boolean,
        animate: Boolean,
        persist: Boolean
    ) {
        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.bottomPanel,
                AutoTransition().apply {
                    duration = 180L
                    interpolator = AccelerateDecelerateInterpolator()
                }
            )
        }
        binding.advancedSettingsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.btnAdvancedSettings.setIconResource(
            if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
        binding.btnAdvancedSettings.contentDescription = getString(
            if (expanded) R.string.action_hide_translation_settings
            else R.string.action_show_translation_settings
        )
        if (persist) {
            getSharedPreferences(UI_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(PREFERENCE_ADVANCED_SETTINGS_EXPANDED, expanded)
                .apply()
        }
    }

    private fun handleScreenshotIntent(incomingIntent: Intent?) {
        if (incomingIntent?.action == OneShotScreenCaptureService.ACTION_CAPTURE_FAILED) {
            incomingIntent.action = null
            binding.tvStatus.text = getString(R.string.active_screenshot_failed)
            Toast.makeText(this, R.string.active_screenshot_failed, Toast.LENGTH_LONG).show()
            return
        }
        val screenshotIntent = incomingIntent ?: return
        val screenshotAction = screenshotIntent.action
        if (screenshotAction !in setOf(
                ScreenshotMonitorService.ACTION_OPEN_SCREENSHOT,
                ScreenshotMonitorService.ACTION_TRANSLATE_SCREENSHOT
            )
        ) {
            return
        }
        val screenshotUri = screenshotIntent.data ?: return
        val notificationId = screenshotIntent.getIntExtra(
            ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID,
            0
        )
        if (notificationId != 0) {
            getSystemService(NotificationManager::class.java).cancel(notificationId)
        }
        startService(
            Intent(this, ScreenshotMonitorService::class.java).apply {
                action = ScreenshotMonitorService.ACTION_CONFIRM_APP_HANDOFF
                data = screenshotUri
            }
        )
        screenshotIntent.action = null
        screenshotIntent.data = null
        loadImage(
            screenshotUri,
            autoTranslate = screenshotAction != ScreenshotMonitorService.ACTION_OPEN_SCREENSHOT
        )
    }

    private fun openCamera() {
        var cameraUri: Uri? = null
        try {
            val cameraDirectory = File(cacheDir, "camera").apply {
                check(exists() || mkdirs()) { "无法创建相机缓存目录" }
            }
            val photoFile = File.createTempFile("capture_", ".jpg", cameraDirectory)
            cameraUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                photoFile
            )
            pendingCameraUri = cameraUri
            takePhoto.launch(cameraUri)
        } catch (e: Exception) {
            cameraUri?.let { runCatching { contentResolver.delete(it, null, null) } }
            pendingCameraUri = null
            Toast.makeText(this, "无法打开相机：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadModel() {
        if (modelDownloadJob?.isActive == true) {
            if (!workflowBusy) binding.tvStatus.text = "正在准备翻译模型..."
            return
        }
        modelDownloadJob = lifecycleScope.launch {
            try {
                binding.tvStatus.text = "下载翻译模型中..."
                translateManager.downloadModelIfNeeded()
                if (!workflowBusy) {
                    binding.tvStatus.text = when (workflowStage) {
                        WorkflowStage.REVIEW -> "翻译模型已就绪，可继续翻译"
                        WorkflowStage.RESULT -> "翻译模型已就绪"
                        WorkflowStage.READY -> if (originalBitmap == null) {
                            "就绪，请选择图片"
                        } else {
                            "图片已加载"
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (!workflowBusy) {
                    binding.tvStatus.text = "翻译模型下载超时，请检查网络后重试"
                }
            } catch (e: Exception) {
                if (!workflowBusy) {
                    binding.tvStatus.text = "翻译模型下载失败，请检查网络后重试"
                }
            }
        }
    }

    private fun loadImage(
        uri: Uri,
        autoTranslate: Boolean = false
    ) {
        lifecycleScope.launch {
            try {
                binding.tvStatus.text = "加载图片中..."
                val bitmap = withContext(Dispatchers.IO) {
                    decodeOrientedBitmap(uri)
                }
                originalBitmap = bitmap
                binding.ivOriginal.setImageBitmap(bitmap)
                binding.ivOriginal.resetZoom()
                binding.emptyOriginalState.visibility = View.GONE
                binding.ivResult.setImageBitmap(null)
                binding.emptyResultState.visibility = View.VISIBLE
                processedBitmap = null
                clearReplacementRegions()
                clearOcrReview()
                updateWorkflowActions(WorkflowStage.READY)
                binding.tvStatus.text = "图片已加载"
                binding.imageWorkspace.post { binding.imageWorkspace.smoothScrollTo(0, 0) }
                if (autoTranslate) {
                    beginOcrReview(bitmap, autoTranslate = true)
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "图片加载失败"
                Toast.makeText(this@ImageTranslateActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decodeOrientedBitmap(uri: Uri): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val decoded = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("无法读取图片数据")
        val transform = exifTransform(orientation) ?: return decoded
        val oriented = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            transform,
            true
        )
        if (oriented !== decoded) decoded.recycle()
        return oriented
    }

    private fun exifTransform(orientation: Int): Matrix? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply {
            setScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply {
            setRotate(180f)
        }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply {
            setScale(1f, -1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            setRotate(90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply {
            setRotate(90f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            setRotate(-90f)
            postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply {
            setRotate(-90f)
        }
        else -> null
    }

    private fun beginOcrReview(
        bitmap: Bitmap,
        autoTranslate: Boolean = false
    ) {
        lifecycleScope.launch {
            var shouldAutoTranslate = false
            binding.tvStatus.text = "识别中..."
            binding.progressBar.visibility = View.VISIBLE
            setWorkflowBusy(true)

            try {
                val ocrBitmap = withContext(Dispatchers.Default) { createOcrBitmap(bitmap) }
                val texts = try {
                    val recognized = ocrManager.recognize(ocrBitmap)
                    mapRecognizedBounds(recognized, ocrBitmap, bitmap)
                } finally {
                    if (ocrBitmap !== bitmap) ocrBitmap.recycle()
                }

                if (texts.isEmpty()) {
                    binding.tvStatus.text = "未识别到文字"
                    return@launch
                }

                clearReplacementRegions()
                ocrReviewRegions = texts.map { OcrReviewRegion(it) }.toMutableList()
                processedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                binding.ivResult.setImageBitmap(processedBitmap)
                binding.ivResult.resetZoom()
                binding.emptyResultState.visibility = View.GONE
                updateWorkflowActions(WorkflowStage.REVIEW)
                updateReplacementMarkers()
                updateOcrReviewStatus()
                shouldAutoTranslate = autoTranslate
                if (!autoTranslate) scrollToResult()
            } catch (e: Exception) {
                binding.tvStatus.text = "识别失败：${e.message}"
                Toast.makeText(this@ImageTranslateActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                setWorkflowBusy(false)
            }
            if (shouldAutoTranslate) {
                try {
                    if (!translateManager.areModelsReady) {
                        binding.tvStatus.text = "正在准备翻译模型..."
                        translateManager.downloadModelIfNeeded()
                    }
                    if (!App.isOpenCVReady) {
                        binding.tvStatus.text = "OpenCV 未就绪，请稍后继续翻译"
                    } else {
                        translateReviewedImage(bitmap)
                    }
                } catch (e: Exception) {
                    binding.tvStatus.text = "自动翻译准备失败，可点击继续翻译"
                }
            }
        }
    }

    private fun translateReviewedImage(bitmap: Bitmap) {
        val reviewRegions = ocrReviewRegions ?: return
        val texts = reviewRegions.filter { it.included }.map { it.source }
        if (texts.isEmpty()) {
            binding.tvStatus.text = "没有参与翻译的文字"
            return
        }

        lifecycleScope.launch {
            val activeMode = translationMode
            binding.progressBar.visibility = View.VISIBLE
            setWorkflowBusy(true)

            try {

                binding.tvStatus.text = "翻译 ${texts.size} 段文字..."
                val regions = withTimeout(TRANSLATION_WORKFLOW_TIMEOUT_MS) {
                    texts.mapIndexed { index, item ->
                        binding.tvStatus.text = "翻译 ${index + 1}/${texts.size}..."
                        try {
                            val translatedText = translateManager.translate(item.text, activeMode)
                            val changed = translatedText.trim() != item.text.trim()
                            TranslatedRegion(item, translatedText, changed)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TranslatedRegion(item, item.text, false, translationFailed = true)
                        }
                    }
                }

                binding.tvStatus.text = "擦除原文字..."
                val translatedRegions = regions.filter { it.translated }
                val usePrecise = binding.switchPreciseMask.isChecked
                val inpaintResult = withContext(Dispatchers.Default) {
                    if (translatedRegions.isEmpty()) {
                        InpaintResult(
                            bitmap.copy(Bitmap.Config.ARGB_8888, true),
                            emptyList()
                        )
                    } else if (usePrecise) {
                        inpainter.eraseWithPreciseMask(
                            bitmap, translatedRegions.map { it.source.bounds }
                        )
                    } else {
                        inpainter.eraseWithRectMask(
                            bitmap, translatedRegions.map { it.source.bounds }
                        )
                    }
                }
                val erased = inpaintResult.bitmap
                val erasedBounds = inpaintResult.erasedRegions.toSet()

                binding.tvStatus.text = "写入翻译..."
                val renderedRegions = withContext(Dispatchers.Default) {
                    drawTexts(Canvas(erased), bitmap, regions, erasedBounds)
                }

                processedBitmap = erased
                ocrReviewRegions = null
                clearReplacementRegions()
                replacementRegions = renderedRegions.map { bounds ->
                    ReplacementRegion(
                        bounds = bounds.bounds,
                        translatedPatch = createBitmapPatch(erased, bounds.bounds),
                        sourceText = bounds.sourceText,
                        translatedText = bounds.translatedText,
                        consensusScore = bounds.consensusScore,
                        passCount = bounds.passCount
                    )
                }
                binding.ivResult.setImageBitmap(processedBitmap)
                binding.ivResult.resetZoom()
                binding.emptyResultState.visibility = View.GONE
                updateReplacementMarkers()
                updateWorkflowActions(WorkflowStage.RESULT)
                scrollToResult()
                val failedCount = regions.count { it.translationFailed }
                val replacedCount = renderedRegions.size
                val skippedEraseCount = translatedRegions.size - erasedBounds.size
                binding.tvStatus.text = buildString {
                    append("完成，共替换 $replacedCount 段文字")
                    if (failedCount > 0) append("；$failedCount 段翻译失败")
                    if (skippedEraseCount > 0) append("；$skippedEraseCount 段因擦除风险保留原文")
                }
            } catch (e: TimeoutCancellationException) {
                binding.tvStatus.text = "翻译超时，请检查网络后重试"
                Toast.makeText(
                    this@ImageTranslateActivity,
                    "翻译服务响应超时，已停止本次处理",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                binding.tvStatus.text = "失败：${e.message}"
                Toast.makeText(this@ImageTranslateActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                setWorkflowBusy(false)
            }
        }
    }

    private fun restartRecognition() {
        val bitmap = originalBitmap ?: return
        clearOcrReview()
        clearReplacementRegions()
        binding.ivResult.setImageBitmap(null)
        processedBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        processedBitmap = null
        binding.emptyResultState.visibility = View.VISIBLE
        updateWorkflowActions(WorkflowStage.READY)
        beginOcrReview(bitmap)
    }

    private fun updateWorkflowActions(stage: WorkflowStage) {
        workflowStage = stage
        when (stage) {
            WorkflowStage.READY -> {
                binding.btnRestartRecognition.visibility = View.GONE
                binding.btnTranslate.setText(R.string.action_recognize_text)
                binding.btnTranslate.setIconResource(R.drawable.ic_translate)
                binding.btnSave.visibility = View.VISIBLE
                binding.btnSave.isEnabled = false
            }
            WorkflowStage.REVIEW -> {
                binding.btnRestartRecognition.visibility = View.VISIBLE
                binding.btnTranslate.setText(R.string.action_confirm_translation)
                binding.btnTranslate.setIconResource(R.drawable.ic_translate)
                binding.btnSave.visibility = View.GONE
                binding.btnSave.isEnabled = false
            }
            WorkflowStage.RESULT -> {
                binding.btnRestartRecognition.visibility = View.GONE
                binding.btnTranslate.setText(R.string.action_recognize_again)
                binding.btnTranslate.setIconResource(R.drawable.ic_refresh)
                binding.btnSave.visibility = View.VISIBLE
                binding.btnSave.isEnabled = !workflowBusy
            }
        }
        binding.btnTranslate.isEnabled = !workflowBusy && originalBitmap != null
        binding.btnRestartRecognition.isEnabled = !workflowBusy
    }

    private fun setWorkflowBusy(busy: Boolean) {
        workflowBusy = busy
        binding.btnTranslate.isEnabled = !busy && originalBitmap != null
        binding.btnRestartRecognition.isEnabled = !busy
        binding.btnSave.isEnabled = !busy && workflowStage == WorkflowStage.RESULT
        binding.btnPickImage.isEnabled = !busy
        binding.btnTakePhoto.isEnabled = !busy
        binding.btnCaptureScreenshot.isEnabled = !busy
        binding.btnAdvancedSettings.isEnabled = !busy
        binding.switchReviewBeforeTranslation.isEnabled = !busy
        setTranslationModeEnabled(!busy)
    }

    private fun scrollToResult() {
        binding.imageWorkspace.post {
            binding.imageWorkspace.smoothScrollTo(0, binding.resultCard.top)
        }
    }

    private fun setTranslationModeEnabled(enabled: Boolean) {
        binding.translationModeGroup.isEnabled = enabled
        binding.btnModeChineseEnglish.isEnabled = enabled
        binding.btnModeEnglishChinese.isEnabled = enabled
        binding.btnModeBidirectional.isEnabled = enabled
    }

    private fun drawTexts(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        regions: List<TranslatedRegion>,
        erasedBounds: Set<Rect>
    ): List<RenderedRegion> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val renderedRegions = mutableListOf<RenderedRegion>()

        for (region in regions.filter { it.translated && it.source.bounds in erasedBounds }) {
            val bounds = region.source.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val style = estimateTextStyle(sourceBitmap, bounds, region.source.text)
            val isControlLabel = style.isDarkBackground &&
                bounds.height() < canvas.height / 10
            val layoutBounds = if (isControlLabel) {
                Rect(bounds)
            } else {
                findAvailableBounds(canvas, bounds, region.source.text, regions)
            }
            val horizontalPadding = if (isControlLabel) 0 else maxOf(2, bounds.height() / 8)
            val layoutWidth = maxOf(1, layoutBounds.width() - horizontalPadding * 2)
            val preferredSize = maxOf(8f, bounds.height() * style.fontSizeMultiplier)
            val minimumSize = maxOf(8f, bounds.height() * 0.68f)
            paint.color = style.foregroundColor
            paint.typeface = style.typeface

            var low = minimumSize
            var high = preferredSize
            val alignment = if (isControlLabel) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }
            var best = createTextLayout(region.translation, paint, layoutWidth, low, alignment)
            repeat(8) {
                val candidateSize = (low + high) / 2f
                val candidate = createTextLayout(
                    region.translation, paint, layoutWidth, candidateSize, alignment
                )
                if (candidate.height <= layoutBounds.height()) {
                    low = candidateSize
                    best = candidate
                } else {
                    high = candidateSize
                }
            }

            val x = layoutBounds.left + horizontalPadding.toFloat()
            val y = bounds.top + maxOf(0f, (bounds.height() - best.getLineBottom(0)) / 2f)
            canvas.save()
            canvas.clipRect(layoutBounds)
            canvas.translate(x, y)
            best.draw(canvas)
            canvas.restore()

            val widestLine = (0 until best.lineCount)
                .maxOfOrNull { best.getLineWidth(it) } ?: 0f
            val textLeft = if (alignment == Layout.Alignment.ALIGN_CENTER) {
                x + (layoutWidth - widestLine) / 2f
            } else {
                x
            }
            val restorePadding = maxOf(4, bounds.height() / 5)
            renderedRegions.add(
                RenderedRegion(
                    bounds = Rect(
                        minOf(bounds.left - restorePadding, textLeft.toInt()).coerceAtLeast(0),
                        (bounds.top - restorePadding).coerceAtLeast(0),
                        maxOf(bounds.right + restorePadding, (textLeft + widestLine).toInt())
                            .coerceAtMost(canvas.width),
                        maxOf(bounds.bottom + restorePadding, (y + best.height).toInt())
                            .coerceAtMost(canvas.height)
                    ),
                    sourceText = region.source.text,
                    translatedText = region.translation,
                    consensusScore = region.source.consensusScore,
                    passCount = region.source.passCount
                )
            )
        }
        return renderedRegions
    }

    private fun toggleReplacementAt(viewX: Float, viewY: Float): Boolean {
        val current = processedBitmap ?: return false
        val original = originalBitmap ?: return false
        if (current.width != original.width || current.height != original.height) return false

        val inverse = Matrix()
        if (!binding.ivResult.imageMatrix.invert(inverse)) return false
        val imagePoint = floatArrayOf(
            viewX - binding.ivResult.paddingLeft,
            viewY - binding.ivResult.paddingTop
        )
        inverse.mapPoints(imagePoint)
        val imageX = imagePoint[0].toInt()
        val imageY = imagePoint[1].toInt()
        val region = replacementRegions
            .filter { it.bounds.contains(imageX, imageY) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            ?: return false

        val canvas = Canvas(current)
        if (region.showingOriginal) {
            canvas.drawBitmap(region.translatedPatch, null, region.bounds, null)
        } else {
            canvas.drawBitmap(original, region.bounds, region.bounds, null)
        }
        region.showingOriginal = !region.showingOriginal
        binding.ivResult.invalidate()
        updateReplacementMarkers()
        return true
    }

    private fun showReplacementInfo(index: Int, markerX: Float, markerY: Float) {
        val region = replacementRegions.getOrNull(index) ?: return

        val popupBinding = PopupReplacementInfoBinding.inflate(layoutInflater)
        popupBinding.tvReplacementNumber.text = "#${index + 1}"
        popupBinding.tvOcrSource.text = region.sourceText
        popupBinding.tvTranslation.text = region.translatedText
        popupBinding.tvOcrConsensus.text = getString(
            R.string.ocr_consensus_format,
            (region.consensusScore * 100).toInt(),
            region.passCount
        )
        val popup = showMarkerPopup(popupBinding.root, markerX, markerY, 250)
        popupBinding.btnCloseReplacementInfo.setOnClickListener { popup.dismiss() }
    }

    private fun showOcrReview(index: Int, markerX: Float, markerY: Float) {
        val regions = ocrReviewRegions ?: return
        val region = regions.getOrNull(index) ?: return
        val popupBinding = PopupOcrReviewBinding.inflate(layoutInflater)
        popupBinding.tvOcrReviewNumber.text = "#${index + 1}"
        popupBinding.editOcrSource.setText(region.source.text)
        popupBinding.checkIncludeTranslation.isChecked = region.included
        popupBinding.tvOcrReviewMeta.text = getString(
            R.string.ocr_review_meta_format,
            (region.source.modelConfidence * 100).toInt(),
            (region.source.consensusScore * 100).toInt(),
            region.source.recognizerScript.displayName
        )

        val popup = showMarkerPopup(popupBinding.root, markerX, markerY, 286)
        popupBinding.btnCloseOcrReview.setOnClickListener { popup.dismiss() }
        popupBinding.btnSaveOcrReview.setOnClickListener {
            val correctedText = popupBinding.editOcrSource.text?.toString()?.trim().orEmpty()
            val included = popupBinding.checkIncludeTranslation.isChecked
            if (included && correctedText.isEmpty()) {
                popupBinding.editOcrSource.error = getString(R.string.ocr_review_empty_error)
                return@setOnClickListener
            }
            region.source = region.source.copy(text = correctedText)
            region.included = included
            updateReplacementMarkers()
            updateOcrReviewStatus()
            popup.dismiss()
        }
    }

    private val RecognizerScript.displayName: String
        get() = when (this) {
            RecognizerScript.CHINESE -> "中文"
            RecognizerScript.LATIN -> "拉丁"
            RecognizerScript.FUSED -> "融合"
        }

    private fun showMarkerPopup(
        content: View,
        markerX: Float,
        markerY: Float,
        preferredHeightDp: Int
    ): PopupWindow {
        dismissReplacementInfo()

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val horizontalMargin = (16 * density).toInt()
        val popupWidth = minOf((300 * density).toInt(), screenWidth - horizontalMargin * 2)
        val popupHeight = minOf(
            (preferredHeightDp * density).toInt(),
            screenHeight - horizontalMargin * 2
        )
        val popup = PopupWindow(
            content,
            popupWidth,
            popupHeight,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = 8 * density
            animationStyle = R.style.Animation_ImageTranslate_MarkerPopup
            setOnDismissListener { replacementInfoPopup = null }
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(popupHeight, View.MeasureSpec.EXACTLY)
        )
        val overlayLocation = IntArray(2)
        binding.replacementOverlay.getLocationOnScreen(overlayLocation)
        val markerScreenX = overlayLocation[0] + markerX.toInt()
        val markerScreenY = overlayLocation[1] + markerY.toInt()
        val popupX = (markerScreenX + (12 * density).toInt())
            .coerceIn(horizontalMargin, screenWidth - popupWidth - horizontalMargin)
        val preferredY = markerScreenY - popupHeight / 2
        val popupY = preferredY.coerceIn(
            horizontalMargin,
            screenHeight - popupHeight - horizontalMargin
        )

        replacementInfoPopup = popup
        popup.showAtLocation(binding.root, Gravity.TOP or Gravity.START, popupX, popupY)
        return popup
    }

    private fun dismissReplacementInfo() {
        replacementInfoPopup?.dismiss()
        replacementInfoPopup = null
    }

    private fun clearReplacementRegions() {
        dismissReplacementInfo()
        replacementRegions.forEach { it.translatedPatch.recycle() }
        replacementRegions = emptyList()
        if (::binding.isInitialized) binding.replacementOverlay.setMarkers(emptyList())
    }

    private fun clearOcrReview() {
        dismissReplacementInfo()
        ocrReviewRegions = null
        if (::binding.isInitialized) {
            binding.replacementOverlay.setMarkers(emptyList())
        }
    }

    private fun updateOcrReviewStatus() {
        val regions = ocrReviewRegions ?: return
        val includedCount = regions.count { it.included }
        binding.tvStatus.text = getString(
            R.string.ocr_review_status_format,
            regions.size,
            includedCount
        )
    }

    private fun updateReplacementMarkers() {
        val reviewRegions = ocrReviewRegions
        binding.replacementOverlay.setMarkers(
            if (reviewRegions != null) {
                reviewRegions.mapIndexed { index, region ->
                    ReplacementOverlayView.Marker(
                        number = index + 1,
                        bounds = region.source.bounds,
                        showingOriginal = !region.included
                    )
                }
            } else {
                replacementRegions.mapIndexed { index, region ->
                    ReplacementOverlayView.Marker(
                        number = index + 1,
                        bounds = region.bounds,
                        showingOriginal = region.showingOriginal
                    )
                }
            }
        )
        binding.replacementOverlay.postInvalidate()
    }

    private fun createBitmapPatch(bitmap: Bitmap, bounds: Rect): Bitmap {
        val patch = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).drawBitmap(bitmap, -bounds.left.toFloat(), -bounds.top.toFloat(), null)
        return patch
    }

    private fun findAvailableBounds(
        canvas: Canvas,
        bounds: Rect,
        sourceText: String,
        regions: List<TranslatedRegion>
    ): Rect {
        val gap = maxOf(3, bounds.height() / 6)
        val isShortUiLabel = isShortUiLabel(sourceText, bounds, canvas)
        val trailingInset = if (isShortUiLabel) {
            maxOf(bounds.height() * 3, canvas.width / 10)
        } else {
            gap
        }
        var right = canvas.width - trailingInset
        var bottom = minOf(
            canvas.height,
            bounds.bottom + bounds.height() * if (isShortUiLabel) 1 else 3
        )

        for (other in regions) {
            val candidate = other.source.bounds
            if (candidate === bounds) continue

            val verticalOverlap = minOf(bounds.bottom, candidate.bottom) -
                maxOf(bounds.top, candidate.top)
            if (verticalOverlap > minOf(bounds.height(), candidate.height()) / 3 &&
                candidate.left >= bounds.right
            ) {
                right = minOf(right, candidate.left - gap)
            }

            val horizontalOverlap = minOf(bounds.right, candidate.right) -
                maxOf(bounds.left, candidate.left)
            if (horizontalOverlap > minOf(bounds.width(), candidate.width()) / 3 &&
                candidate.top >= bounds.bottom
            ) {
                bottom = minOf(bottom, candidate.top - gap)
            }
        }

        right = maxOf(bounds.right, right)
        bottom = maxOf(bounds.bottom, bottom)
        return Rect(bounds.left, bounds.top, right, bottom)
    }

    private fun isShortUiLabel(sourceText: String, bounds: Rect, canvas: Canvas): Boolean {
        val compact = sourceText.filterNot(Char::isWhitespace)
        if (compact.length !in 2..16 || bounds.height() >= canvas.height / 10) return false
        if (compact.any { it in "。！？.!?；;" }) return false
        return bounds.width() < canvas.width * 0.75f
    }

    private fun estimateTextStyle(bitmap: Bitmap, bounds: Rect, sourceText: String): TextStyle {
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val histogram = IntArray(4096)
        val backgroundPadding = maxOf(3, (bottom - top) / 3)
        val outerLeft = (left - backgroundPadding).coerceAtLeast(0)
        val outerTop = (top - backgroundPadding).coerceAtLeast(0)
        val outerRight = (right + backgroundPadding).coerceAtMost(bitmap.width)
        val outerBottom = (bottom + backgroundPadding).coerceAtMost(bitmap.height)
        var backgroundSamples = 0

        for (y in outerTop until outerBottom) {
            for (x in outerLeft until outerRight) {
                if (x in left until right && y in top until bottom) continue
                val color = bitmap.getPixel(x, y)
                val bucket = (Color.red(color) / 16 shl 8) or
                    (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
                histogram[bucket]++
                backgroundSamples++
            }
        }
        if (backgroundSamples == 0) {
            for (y in top until bottom) {
                for (x in left until right) {
                    val color = bitmap.getPixel(x, y)
                    val bucket = (Color.red(color) / 16 shl 8) or
                        (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
                    histogram[bucket]++
                }
            }
        }

        val backgroundBucket = histogram.indices.maxByOrNull { histogram[it] }
            ?: return TextStyle(Color.BLACK, false, Typeface.DEFAULT, 1.1f)
        val backgroundRed = ((backgroundBucket shr 8) and 0xF) * 16 + 8
        val backgroundGreen = ((backgroundBucket shr 4) and 0xF) * 16 + 8
        val backgroundBlue = (backgroundBucket and 0xF) * 16 + 8
        var maximumDistanceSquared = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                val redDifference = Color.red(color) - backgroundRed
                val greenDifference = Color.green(color) - backgroundGreen
                val blueDifference = Color.blue(color) - backgroundBlue
                maximumDistanceSquared = maxOf(
                    maximumDistanceSquared,
                    redDifference * redDifference + greenDifference * greenDifference +
                        blueDifference * blueDifference
                )
            }
        }

        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var count = 0
        val foregroundThreshold = maxOf(1600, (maximumDistanceSquared * 0.45f).toInt())
        val strokeThreshold = maxOf(900, (maximumDistanceSquared * 0.12f).toInt())
        var strokePixels = 0

        for (y in top until bottom) {
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val redDifference = red - backgroundRed
                val greenDifference = green - backgroundGreen
                val blueDifference = blue - backgroundBlue
                val distanceSquared = redDifference * redDifference +
                    greenDifference * greenDifference + blueDifference * blueDifference
                if (distanceSquared >= strokeThreshold) strokePixels++
                if (distanceSquared >= foregroundThreshold) {
                    redTotal += red
                    greenTotal += green
                    blueTotal += blue
                    count++
                }
            }
        }

        val backgroundLuminance = (backgroundRed * 299 + backgroundGreen * 587 +
            backgroundBlue * 114) / 1000
        val estimatedForeground = if (count == 0) {
            if (backgroundLuminance < 145) Color.WHITE else Color.BLACK
        } else {
            Color.rgb(
                (redTotal / count).toInt(),
                (greenTotal / count).toInt(),
                (blueTotal / count).toInt()
            )
        }
        val foregroundLuminance = (Color.red(estimatedForeground) * 299 +
            Color.green(estimatedForeground) * 587 + Color.blue(estimatedForeground) * 114) / 1000
        val foreground = if (kotlin.math.abs(foregroundLuminance - backgroundLuminance) < 90) {
            if (backgroundLuminance < 145) Color.WHITE else Color.BLACK
        } else {
            estimatedForeground
        }
        val area = maxOf(1, (right - left) * (bottom - top))
        val strokeCoverage = strokePixels.toFloat() / area
        val isBold = strokeCoverage >= 0.3f
        val baseTypeface = if (looksLikeCode(sourceText)) {
            Typeface.MONOSPACE
        } else {
            Typeface.SANS_SERIF
        }
        val typeface = Typeface.create(
            baseTypeface,
            if (isBold) Typeface.BOLD else Typeface.NORMAL
        )
        return TextStyle(
            foregroundColor = foreground,
            isDarkBackground = backgroundLuminance < 145,
            typeface = typeface,
            fontSizeMultiplier = if (isBold) 1.05f else 1.12f
        )
    }

    private fun looksLikeCode(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return false
        if (compact.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            return false
        }
        val hasAsciiContent = compact.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }
        if (!hasAsciiContent) return false
        return text.contains('_') || text.contains("://") ||
            (compact.length >= 4 && compact.all {
                it.isLetterOrDigit() || it in charArrayOf('.', '/', '-', ':')
            })
    }

    private fun createTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textSize: Float,
        alignment: Layout.Alignment
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun createOcrBitmap(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= 1600) return bitmap
        val scale = minOf(3f, 3072f / longestSide)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun mapRecognizedBounds(
        texts: List<RecognizedText>,
        ocrBitmap: Bitmap,
        processingBitmap: Bitmap
    ): List<RecognizedText> {
        if (ocrBitmap === processingBitmap) return texts
        val scaleX = processingBitmap.width.toFloat() / ocrBitmap.width
        val scaleY = processingBitmap.height.toFloat() / ocrBitmap.height
        return texts.map { item ->
            val source = item.bounds
            RecognizedText(
                text = item.text,
                bounds = Rect(
                    (source.left * scaleX).toInt().coerceIn(0, processingBitmap.width),
                    (source.top * scaleY).toInt().coerceIn(0, processingBitmap.height),
                    (source.right * scaleX).toInt().coerceIn(0, processingBitmap.width),
                    (source.bottom * scaleY).toInt().coerceIn(0, processingBitmap.height)
                ),
                consensusScore = item.consensusScore,
                passCount = item.passCount,
                modelConfidence = item.modelConfidence,
                recognizerScript = item.recognizerScript
            )
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        lifecycleScope.launch {
            binding.btnSave.isEnabled = false
            binding.btnSave.setText(R.string.action_saving)
            binding.tvStatus.setText(R.string.status_saving_to_gallery)
            val savedUri = withContext(Dispatchers.IO) {
                runCatching { TranslatedImageGallerySaver(this@ImageTranslateActivity).save(bitmap) }
                    .getOrNull()
            }
            val message = if (savedUri != null) {
                R.string.screenshot_auto_save_complete
            } else {
                R.string.screenshot_auto_save_failed
            }
            binding.btnSave.setText(R.string.action_save)
            binding.btnSave.isEnabled = workflowStage == WorkflowStage.RESULT && !workflowBusy
            binding.tvStatus.setText(message)
            Toast.makeText(this@ImageTranslateActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        clearOcrReview()
        clearReplacementRegions()
        ocrManager.close()
        translateManager.close()
        super.onDestroy()
    }
}
