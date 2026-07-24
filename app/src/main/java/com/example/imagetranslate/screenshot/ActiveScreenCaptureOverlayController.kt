package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.PopupMenu
import com.example.imagetranslate.R
import com.example.imagetranslate.databinding.OverlayActiveScreenCaptureBinding
import com.example.imagetranslate.translate.TranslationMode
import kotlin.math.abs

internal class ActiveScreenCaptureOverlayController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onCapture()
        fun onStop()
        fun onCancelPreview()
        fun onTranslationModeChanged(mode: TranslationMode)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
    private val binding = OverlayActiveScreenCaptureBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private val translationView = ScreenTranslationOverlayView(themedContext)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = dp(12)
    private val expandedWidth = dp(306)
    private val expandedHeight = dp(56)
    private val collapsedWidth = dp(132)
    private val collapsedHeight = dp(42)
    private var controlParams: WindowManager.LayoutParams? = null
    private var translationParams: WindowManager.LayoutParams? = null
    private var expandedX = edgeMargin
    private var expandedY = edgeMargin
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    private var sessionActive = false
    private var processing = false
    private var collapsed = false

    private val collapseRunnable = Runnable {
        if (sessionActive && !processing) collapseNow()
    }

    init {
        binding.btnActiveOverlayCapture.setOnClickListener { listener.onCapture() }
        binding.btnActiveOverlayStop.setOnClickListener { listener.onStop() }
        binding.btnCancelActivePreview.setOnClickListener {
            listener.onCancelPreview()
            showReadyNow()
        }
        binding.btnActiveOverlayMode.setOnClickListener {
            showTranslationModeMenu()
        }
        binding.btnCollapseActiveOverlay.setOnClickListener { collapseNow() }
        binding.btnExpandActiveOverlay.setOnClickListener { expandNow() }
        binding.collapsedCaptureHandle.setOnClickListener { expandNow() }
        attachDragGesture()
        updateModeLabel()
    }

    fun showCompact() = onMainThread(::showReadyNow)

    fun hideForCapture() = onMainThread {
        mainHandler.removeCallbacks(collapseRunnable)
        binding.root.visibility = View.INVISIBLE
        translationView.visibility = View.INVISIBLE
    }

    fun showProcessing(expandControls: Boolean) = onMainThread {
        if (!ensureControlAttachedNow()) return@onMainThread
        ensureTranslationLayerAttachedNow()
        processing = true
        sessionActive = true
        translationView.clearPatches()
        if (expandControls || !collapsed) expandNow() else collapseNow()
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.VISIBLE
        binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_processing_short)
        binding.btnCancelActivePreview.visibility = View.VISIBLE
        binding.btnActiveOverlayMode.isEnabled = false
        updateCompactStatus(R.string.active_screenshot_compact_processing, showProgress = true)
    }

    fun showWaitingForStable() = onMainThread {
        translationView.clearPatches()
        sessionActive = true
        updateCompactStatus(R.string.active_screenshot_compact_waiting, showProgress = true)
        if (binding.expandedCaptureControls.visibility == View.VISIBLE) {
            binding.btnActiveOverlayCapture.visibility = View.GONE
            binding.activeOverlayStatusGroup.visibility = View.VISIBLE
            binding.activeOverlayProgress.visibility = View.VISIBLE
            binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_waiting_stable)
            binding.btnCancelActivePreview.visibility = View.VISIBLE
        }
    }

    fun showResult(
        patches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int,
        recognizedCount: Int
    ) = onMainThread {
        if (!ensureControlAttachedNow() || !ensureTranslationLayerAttachedNow()) {
            patches.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            return@onMainThread
        }
        processing = false
        sessionActive = true
        translationView.replacePatches(patches, sourceWidth, sourceHeight)
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.GONE
        binding.tvActiveOverlayStatus.text = if (patches.isEmpty()) {
            appContext.getString(R.string.active_screenshot_no_translatable_text)
        } else {
            appContext.getString(R.string.active_screenshot_live_result, recognizedCount)
        }
        binding.btnCancelActivePreview.visibility = View.VISIBLE
        binding.btnActiveOverlayMode.isEnabled = true
        updateCompactStatus(
            if (patches.isEmpty()) {
                R.string.active_screenshot_compact_no_text
            } else {
                R.string.active_screenshot_compact_translated
            },
            showProgress = false
        )
        mainHandler.removeCallbacks(collapseRunnable)
        mainHandler.postDelayed(collapseRunnable, AUTO_COLLAPSE_DELAY_MS)
    }

    fun showCaptureFailed() = onMainThread(::showReadyNow)

    fun clearTranslations() = onMainThread {
        translationView.clearPatches()
    }

    fun dismiss() = onMainThread(::dismissNow)

    private fun showReadyNow() {
        if (!Settings.canDrawOverlays(appContext)) {
            dismissNow()
            return
        }
        processing = false
        sessionActive = false
        mainHandler.removeCallbacks(collapseRunnable)
        removeTranslationLayerNow()
        if (!ensureControlAttachedNow()) return
        expandNow()
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.VISIBLE
        binding.activeOverlayStatusGroup.visibility = View.GONE
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        updateCompactStatus(R.string.active_screenshot_compact_ready, showProgress = false)
    }

    private fun expandNow() {
        if (!ensureControlAttachedNow()) return
        val bounds = windowBounds()
        expandedX = expandedX.coerceIn(
            edgeMargin,
            (bounds.first - expandedWidth - edgeMargin).coerceAtLeast(edgeMargin)
        )
        if (expandedY == edgeMargin) {
            expandedY = (
                bounds.second - expandedHeight - dp(EXPANDED_BOTTOM_MARGIN_DP)
            ).coerceAtLeast(edgeMargin)
        } else {
            expandedY = expandedY.coerceIn(
                edgeMargin,
                (bounds.second - expandedHeight - edgeMargin).coerceAtLeast(edgeMargin)
            )
        }
        binding.collapsedCaptureHandle.visibility = View.GONE
        binding.expandedCaptureControls.visibility = View.VISIBLE
        collapsed = false
        updateControlWindow(expandedWidth, expandedHeight, expandedX, expandedY)
    }

    private fun collapseNow() {
        if (!ensureControlAttachedNow()) return
        mainHandler.removeCallbacks(collapseRunnable)
        val bounds = windowBounds()
        binding.expandedCaptureControls.visibility = View.GONE
        binding.collapsedCaptureHandle.visibility = View.VISIBLE
        collapsed = true
        updateControlWindow(
            collapsedWidth,
            collapsedHeight,
            ((bounds.first - collapsedWidth) / 2).coerceAtLeast(0),
            (bounds.second - collapsedHeight - dp(COMPACT_BOTTOM_MARGIN_DP)).coerceAtLeast(0)
        )
    }

    private fun ensureControlAttachedNow(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
        if (binding.root.isAttachedToWindow) return true
        val bounds = windowBounds()
        expandedX = ((bounds.first - expandedWidth) / 2).coerceAtLeast(edgeMargin)
        expandedY = (bounds.second - expandedHeight - dp(EXPANDED_BOTTOM_MARGIN_DP))
            .coerceAtLeast(edgeMargin)
        val params = createLayoutParams(
            width = expandedWidth,
            height = expandedHeight,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            x = expandedX,
            y = expandedY
        )
        controlParams = params
        runCatching { windowManager.addView(binding.root, params) }
            .onFailure { controlParams = null }
        return binding.root.isAttachedToWindow
    }

    private fun ensureTranslationLayerAttachedNow(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
        if (translationView.isAttachedToWindow) return true
        val params = createLayoutParams(
            width = WindowManager.LayoutParams.MATCH_PARENT,
            height = WindowManager.LayoutParams.MATCH_PARENT,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            x = 0,
            y = 0
        )
        translationParams = params
        runCatching { windowManager.addView(translationView, params) }
            .onFailure { translationParams = null }
        return translationView.isAttachedToWindow
    }

    private fun dismissNow() {
        mainHandler.removeCallbacks(collapseRunnable)
        removeTranslationLayerNow()
        if (binding.root.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(binding.root) }
        }
        controlParams = null
    }

    private fun removeTranslationLayerNow() {
        translationView.clearPatches()
        if (translationView.isAttachedToWindow) {
            runCatching { windowManager.removeViewImmediate(translationView) }
        }
        translationParams = null
    }

    private fun updateModeLabel() {
        binding.btnActiveOverlayMode.setText(
            when (translationMode) {
                TranslationMode.AUTO_BIDIRECTIONAL ->
                    R.string.active_screenshot_mode_bidirectional
                TranslationMode.ENGLISH_TO_CHINESE ->
                    R.string.active_screenshot_mode_english_chinese
                TranslationMode.CHINESE_TO_ENGLISH ->
                    R.string.active_screenshot_mode_chinese_english
            }
        )
    }

    private fun showTranslationModeMenu() {
        PopupMenu(themedContext, binding.btnActiveOverlayMode).apply {
            menu.add(
                MODE_MENU_GROUP,
                MODE_MENU_BIDIRECTIONAL,
                0,
                R.string.active_screenshot_mode_bidirectional
            )
            menu.add(
                MODE_MENU_GROUP,
                MODE_MENU_ENGLISH_CHINESE,
                1,
                R.string.active_screenshot_mode_english_chinese
            )
            menu.add(
                MODE_MENU_GROUP,
                MODE_MENU_CHINESE_ENGLISH,
                2,
                R.string.active_screenshot_mode_chinese_english
            )
            menu.setGroupCheckable(MODE_MENU_GROUP, true, true)
            menu.findItem(
                when (translationMode) {
                    TranslationMode.AUTO_BIDIRECTIONAL -> MODE_MENU_BIDIRECTIONAL
                    TranslationMode.ENGLISH_TO_CHINESE -> MODE_MENU_ENGLISH_CHINESE
                    TranslationMode.CHINESE_TO_ENGLISH -> MODE_MENU_CHINESE_ENGLISH
                }
            ).isChecked = true
            setOnMenuItemClickListener { item ->
                val selectedMode = when (item.itemId) {
                    MODE_MENU_BIDIRECTIONAL -> TranslationMode.AUTO_BIDIRECTIONAL
                    MODE_MENU_ENGLISH_CHINESE -> TranslationMode.ENGLISH_TO_CHINESE
                    MODE_MENU_CHINESE_ENGLISH -> TranslationMode.CHINESE_TO_ENGLISH
                    else -> return@setOnMenuItemClickListener false
                }
                if (selectedMode != translationMode) {
                    translationMode = selectedMode
                    updateModeLabel()
                    listener.onTranslationModeChanged(selectedMode)
                }
                true
            }
            show()
        }
    }

    private fun updateCompactStatus(textRes: Int, showProgress: Boolean) {
        binding.tvCollapsedOverlayStatus.setText(textRes)
        binding.collapsedOverlayProgress.visibility =
            if (showProgress) View.VISIBLE else View.INVISIBLE
    }

    private fun updateControlWindow(width: Int, height: Int, x: Int, y: Int) {
        val params = controlParams ?: return
        params.width = width
        params.height = height
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(binding.root, params) }
    }

    private fun attachDragGesture() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        binding.activeOverlayDragHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = expandedX
                    startY = expandedY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val bounds = windowBounds()
                        expandedX = (startX + dx.toInt()).coerceIn(
                            edgeMargin,
                            (bounds.first - expandedWidth - edgeMargin).coerceAtLeast(edgeMargin)
                        )
                        expandedY = (startY + dy.toInt()).coerceIn(
                            edgeMargin,
                            (bounds.second - expandedHeight - edgeMargin).coerceAtLeast(edgeMargin)
                        )
                        updateControlWindow(expandedWidth, expandedHeight, expandedX, expandedY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun createLayoutParams(
        width: Int,
        height: Int,
        flags: Int,
        x: Int,
        y: Int
    ) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun windowBounds(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        @Suppress("DEPRECATION")
        appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val AUTO_COLLAPSE_DELAY_MS = 3_500L
        const val EXPANDED_BOTTOM_MARGIN_DP = 44
        const val COMPACT_BOTTOM_MARGIN_DP = 36
        const val MODE_MENU_GROUP = 1
        const val MODE_MENU_BIDIRECTIONAL = 101
        const val MODE_MENU_ENGLISH_CHINESE = 102
        const val MODE_MENU_CHINESE_ENGLISH = 103
    }
}
