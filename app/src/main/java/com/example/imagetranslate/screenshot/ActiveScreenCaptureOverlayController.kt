package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.PopupMenu
import com.example.imagetranslate.R
import com.example.imagetranslate.databinding.OverlayActiveScreenCaptureBinding
import com.example.imagetranslate.translate.TranslationMode
import kotlin.math.abs

internal class ActiveScreenCaptureOverlayController(
    context: Context,
    initialExperienceMode: LiveOverlayExperienceMode,
    private val listener: Listener
) {
    interface Listener {
        fun onCapture()
        fun onStop()
        fun onCancelPreview()
        fun onTranslationModeChanged(mode: TranslationMode)
        fun onTranslationVisibilityChanged()
        fun onExperienceModeRequested(mode: LiveOverlayExperienceMode)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val applicationWindowManager = appContext.getSystemService(WindowManager::class.java)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
    private val binding = OverlayActiveScreenCaptureBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private val translationView = ScreenTranslationOverlayView(themedContext)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = dp(12)
    private val expandedWidth = dp(342)
    private val expandedHeight = dp(56)
    private val collapsedWidth = dp(132)
    private val collapsedHeight = dp(42)
    private var controlParams: WindowManager.LayoutParams? = null
    private var translationParams: WindowManager.LayoutParams? = null
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    private var experienceMode = initialExperienceMode
    private var attachedWindowManager: WindowManager? = null
    private var sessionActive = false
    private var processing = false
    private var collapsed = false
    private var translationVisible = true
    private var hasTranslationResult = false

    init {
        binding.btnActiveOverlayCapture.setOnClickListener { listener.onCapture() }
        binding.btnActiveOverlayStop.setOnClickListener { listener.onStop() }
        binding.btnCancelActivePreview.setOnClickListener {
            listener.onCancelPreview()
            showReadyNow()
        }
        binding.btnActiveOverlayMode.setOnClickListener {
            showOverlayMenu()
        }
        binding.btnToggleActiveTranslation.addOnCheckedChangeListener { _, checked ->
            translationVisible = checked
            translationView.setPatchesVisible(checked)
            listener.onTranslationVisibilityChanged()
        }
        binding.btnCollapseActiveOverlay.setOnClickListener { collapseNow() }
        binding.btnExpandActiveOverlay.setOnClickListener { expandNow() }
        binding.collapsedCaptureHandle.setOnClickListener { expandNow() }
        attachDragGestures()
        updateModeLabel()
    }

    fun showReadyExpanded() = onMainThread(::showReadyNow)

    fun setExperienceMode(mode: LiveOverlayExperienceMode) = onMainThread {
        if (experienceMode == mode) return@onMainThread
        dismissNow()
        experienceMode = mode
        showReadyNow()
    }

    fun hideForCapture() = onMainThread {
        binding.root.visibility = View.INVISIBLE
        translationView.setPatchesVisible(false, animateChange = false)
    }

    fun pulseTransparentCaptureSurface() = onMainThread {
        if (translationView.parent == null) return@onMainThread
        translationView.clearPatches()
        translationView.alpha = 0f
        translationView.visibility = View.VISIBLE
        translationView.postOnAnimation {
            translationView.visibility = View.INVISIBLE
            translationView.alpha = 1f
        }
    }

    fun showProcessing() = onMainThread {
        if (!ensureControlAttachedNow()) return@onMainThread
        processing = true
        sessionActive = true
        removeTranslationLayersNow()
        collapseNow()
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.VISIBLE
        binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_processing_short)
        binding.btnCancelActivePreview.visibility = View.VISIBLE
        binding.btnActiveOverlayMode.isEnabled = false
        binding.btnToggleActiveTranslation.isEnabled = hasTranslationResult
        updateCompactStatus(R.string.active_screenshot_compact_processing, showProgress = true)
    }

    fun showWaitingForStable() = onMainThread {
        translationView.hideForViewportMovement()
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
        recognizedCount: Int,
        onPresented: () -> Unit
    ) = onMainThread {
        if (!ensureControlAttachedNow()) {
            patches.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            mainHandler.post(onPresented)
            return@onMainThread
        }
        processing = false
        sessionActive = true
        hasTranslationResult = patches.isNotEmpty()
        showTranslationPatchesNow(patches, sourceWidth, sourceHeight)
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
        binding.btnToggleActiveTranslation.isEnabled = hasTranslationResult
        binding.btnToggleActiveTranslation.isChecked = translationVisible
        updateCompactStatus(
            if (patches.isEmpty()) {
                R.string.active_screenshot_compact_no_text
            } else {
                R.string.active_screenshot_compact_translated
            },
            showProgress = false
        )
        binding.root.postOnAnimation(onPresented)
    }

    fun showCaptureFailed() = onMainThread(::showReadyNow)

    fun restoreAfterSkippedCapture() = onMainThread {
        binding.root.visibility = View.VISIBLE
        translationView.setPatchesVisible(translationVisible, animateChange = false)
    }

    fun clearTranslations() = onMainThread {
        removeTranslationLayersNow()
    }

    fun dismiss() = onMainThread(::dismissNow)

    fun onDisplayGeometryChanged(clearTranslations: Boolean) = onMainThread {
        val bounds = windowBounds()
        translationParams?.let { params ->
            params.width = bounds.first
            params.height = bounds.second
            runCatching {
                (attachedWindowManager ?: activeWindowManager())
                    ?.updateViewLayout(translationView, params)
            }
        }
        controlParams?.let { params ->
            val position = ScreenshotOverlayPositionPolicy.clamp(
                x = params.x,
                y = params.y,
                windowWidth = bounds.first,
                windowHeight = bounds.second,
                overlayWidth = params.width,
                overlayHeight = params.height,
                marginPx = edgeMargin
            )
            updateControlWindow(params.width, params.height, position.x, position.y)
        }
        if (clearTranslations) translationView.clearPatches()
    }

    private fun showReadyNow() {
        if (!canAttachOverlay()) {
            dismissNow()
            return
        }
        processing = false
        sessionActive = false
        hasTranslationResult = false
        translationVisible = true
        removeTranslationLayersNow()
        if (!ensureControlAttachedNow()) return
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.VISIBLE
        binding.activeOverlayStatusGroup.visibility = View.GONE
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnToggleActiveTranslation.isEnabled = false
        binding.btnToggleActiveTranslation.isChecked = true
        updateCompactStatus(R.string.active_screenshot_compact_ready, showProgress = false)
        expandNow()
    }

    private fun expandNow() {
        if (!ensureControlAttachedNow()) return
        val params = controlParams ?: return
        val bounds = windowBounds()
        val position = if (collapsed) {
            ActiveOverlayPositionPolicy.resizeAroundCenter(
                x = params.x,
                y = params.y,
                fromWidth = params.width,
                fromHeight = params.height,
                toWidth = expandedWidth,
                toHeight = expandedHeight,
                screenWidth = bounds.first,
                screenHeight = bounds.second,
                margin = edgeMargin
            )
        } else {
            ScreenshotOverlayPositionPolicy.clamp(
                x = params.x,
                y = params.y,
                windowWidth = bounds.first,
                windowHeight = bounds.second,
                overlayWidth = expandedWidth,
                overlayHeight = expandedHeight,
                marginPx = edgeMargin
            )
        }
        val stateChanged = collapsed
        binding.collapsedCaptureHandle.visibility = View.GONE
        binding.expandedCaptureControls.visibility = View.VISIBLE
        collapsed = false
        updateControlWindow(expandedWidth, expandedHeight, position.x, position.y)
        if (stateChanged) animateControlMaterialization()
    }

    private fun collapseNow() {
        if (!ensureControlAttachedNow()) return
        val params = controlParams ?: return
        if (collapsed) return
        val bounds = windowBounds()
        val position = ActiveOverlayPositionPolicy.resizeAroundCenter(
            x = params.x,
            y = params.y,
            fromWidth = params.width,
            fromHeight = params.height,
            toWidth = collapsedWidth,
            toHeight = collapsedHeight,
            screenWidth = bounds.first,
            screenHeight = bounds.second,
            margin = edgeMargin
        )
        binding.expandedCaptureControls.visibility = View.GONE
        binding.collapsedCaptureHandle.visibility = View.VISIBLE
        collapsed = true
        updateControlWindow(
            collapsedWidth,
            collapsedHeight,
            position.x,
            position.y
        )
        animateControlMaterialization()
    }

    private fun ensureControlAttachedNow(): Boolean {
        if (!canAttachOverlay()) return false
        if (binding.root.parent != null) return true
        if (!ensureTranslationAttachedNow()) return false
        val bounds = windowBounds()
        val initialX = ((bounds.first - expandedWidth) / 2).coerceAtLeast(edgeMargin)
        val initialY = (bounds.second - expandedHeight - dp(EXPANDED_BOTTOM_MARGIN_DP))
            .coerceAtLeast(edgeMargin)
        val params = createLayoutParams(
            width = expandedWidth,
            height = expandedHeight,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            x = initialX,
            y = initialY
        )
        controlParams = params
        binding.root.alpha = 0f
        binding.root.scaleX = CONTROL_ENTRY_SCALE
        binding.root.scaleY = CONTROL_ENTRY_SCALE
        val windowManager = activeWindowManager() ?: return false
        runCatching { windowManager.addView(binding.root, params) }
            .onFailure { controlParams = null }
        if (binding.root.parent != null) attachedWindowManager = windowManager
        if (binding.root.parent != null) animateControlMaterialization()
        return binding.root.parent != null
    }

    private fun showTranslationPatchesNow(
        patches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        removeTranslationLayersNow()
        if (!ensureTranslationAttachedNow() || sourceWidth <= 0 || sourceHeight <= 0) {
            patches.recyclePatchBitmaps()
            return
        }
        translationView.replacePatches(patches, sourceWidth, sourceHeight)
        translationView.setPatchesVisible(translationVisible, animateChange = false)
    }

    private fun ensureTranslationAttachedNow(): Boolean {
        if (!canAttachOverlay()) return false
        if (translationView.parent != null) return true
        val bounds = windowBounds()
        val params = createLayoutParams(
            width = bounds.first,
            height = bounds.second,
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            x = 0,
            y = 0
        ).apply {
            alpha = translationPatchWindowAlpha(overlapCount = 1)
        }
        val windowManager = activeWindowManager() ?: return false
        translationParams = params
        return runCatching {
            windowManager.addView(translationView, params)
            if (translationView.parent != null) attachedWindowManager = windowManager
            translationView.parent != null
        }.onFailure { translationParams = null }.getOrDefault(false)
    }

    private fun dismissNow() {
        removeTranslationLayersNow()
        val windowManager = attachedWindowManager ?: applicationWindowManager
        if (binding.root.parent != null) {
            runCatching { windowManager.removeViewImmediate(binding.root) }
        }
        if (translationView.parent != null) {
            runCatching { windowManager.removeViewImmediate(translationView) }
        }
        controlParams = null
        translationParams = null
        attachedWindowManager = null
    }

    private fun removeTranslationLayersNow() {
        translationView.clearPatches()
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

    private fun showOverlayMenu() {
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
            menu.add(
                MENU_HEADER_GROUP,
                MENU_HEADER_EXPERIENCE,
                10,
                R.string.active_screenshot_experience_group
            ).isEnabled = false
            menu.add(
                EXPERIENCE_MENU_GROUP,
                EXPERIENCE_MENU_DEFAULT,
                11,
                R.string.active_screenshot_experience_default
            )
            menu.add(
                EXPERIENCE_MENU_GROUP,
                EXPERIENCE_MENU_ENHANCED,
                12,
                R.string.active_screenshot_experience_enhanced
            )
            menu.setGroupCheckable(EXPERIENCE_MENU_GROUP, true, true)
            menu.findItem(
                if (experienceMode == LiveOverlayExperienceMode.ENHANCED) {
                    EXPERIENCE_MENU_ENHANCED
                } else {
                    EXPERIENCE_MENU_DEFAULT
                }
            ).isChecked = true
            setOnMenuItemClickListener { item ->
                val selectedExperience = when (item.itemId) {
                    EXPERIENCE_MENU_DEFAULT -> LiveOverlayExperienceMode.DEFAULT
                    EXPERIENCE_MENU_ENHANCED -> LiveOverlayExperienceMode.ENHANCED
                    else -> null
                }
                if (selectedExperience != null) {
                    listener.onExperienceModeRequested(selectedExperience)
                    return@setOnMenuItemClickListener true
                }
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
        runCatching {
            (attachedWindowManager ?: activeWindowManager())
                ?.updateViewLayout(binding.root, params)
        }
    }

    private fun attachDragGestures() {
        attachDragGesture(binding.activeOverlayDragHandle)
        attachDragGesture(binding.collapsedCaptureHandle)
        attachDragGesture(binding.btnExpandActiveOverlay)
    }

    private fun attachDragGesture(target: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        target.setOnTouchListener { view, event ->
            val params = controlParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    setControlPressed(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (dragging) {
                        val bounds = windowBounds()
                        val position = ScreenshotOverlayPositionPolicy.clamp(
                            x = startX + dx.toInt(),
                            y = startY + dy.toInt(),
                            windowWidth = bounds.first,
                            windowHeight = bounds.second,
                            overlayWidth = params.width,
                            overlayHeight = params.height,
                            marginPx = edgeMargin
                        )
                        updateControlWindow(
                            params.width,
                            params.height,
                            position.x,
                            position.y
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    setControlPressed(false)
                    if (!dragging) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    setControlPressed(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setControlPressed(pressed: Boolean) {
        binding.root.animate().cancel()
        binding.root.animate()
            .alpha(if (pressed) CONTROL_PRESSED_ALPHA else 1f)
            .scaleX(if (pressed) CONTROL_PRESSED_SCALE else 1f)
            .scaleY(if (pressed) CONTROL_PRESSED_SCALE else 1f)
            .setDuration(CONTROL_PRESS_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateControlMaterialization() {
        binding.root.animate().cancel()
        binding.root.alpha = CONTROL_MATERIALIZE_ALPHA
        binding.root.scaleX = CONTROL_ENTRY_SCALE
        binding.root.scaleY = CONTROL_ENTRY_SCALE
        binding.root.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(CONTROL_MATERIALIZE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
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
        if (experienceMode == LiveOverlayExperienceMode.ENHANCED) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        applicationWindowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
    } else {
        @Suppress("DEPRECATION")
        appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }

    private fun translationPatchWindowAlpha(overlapCount: Int): Float {
        if (experienceMode == LiveOverlayExperienceMode.ENHANCED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        ) return 1f
        val maximumAlpha = runCatching {
            appContext.getSystemService(InputManager::class.java)
                .maximumObscuringOpacityForTouch
        }.getOrDefault(DEFAULT_MAXIMUM_OBSCURING_ALPHA)
        return LiveOverlayExperiencePolicy.translationWindowAlpha(
            experienceMode,
            TranslationOverlayTouchPolicy.windowAlpha(maximumAlpha, overlapCount)
        )
    }

    private fun canAttachOverlay(): Boolean =
        (experienceMode == LiveOverlayExperienceMode.ENHANCED &&
            ScreenTranslationAccessibilityService.isConnected) ||
            Settings.canDrawOverlays(appContext)

    private fun activeWindowManager(): WindowManager? =
        if (experienceMode == LiveOverlayExperienceMode.ENHANCED) {
            ScreenTranslationAccessibilityService.windowManagerOrNull()
        } else {
            applicationWindowManager
        }

    private fun onMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val EXPANDED_BOTTOM_MARGIN_DP = 44
        const val DEFAULT_MAXIMUM_OBSCURING_ALPHA = 0.8f
        const val CONTROL_PRESS_DURATION_MS = 90L
        const val CONTROL_MATERIALIZE_DURATION_MS = 150L
        const val CONTROL_PRESSED_ALPHA = 0.92f
        const val CONTROL_MATERIALIZE_ALPHA = 0.82f
        const val CONTROL_PRESSED_SCALE = 0.985f
        const val CONTROL_ENTRY_SCALE = 0.96f
        const val MODE_MENU_GROUP = 1
        const val MODE_MENU_BIDIRECTIONAL = 101
        const val MODE_MENU_ENGLISH_CHINESE = 102
        const val MODE_MENU_CHINESE_ENGLISH = 103
        const val MENU_HEADER_GROUP = 2
        const val MENU_HEADER_EXPERIENCE = 200
        const val EXPERIENCE_MENU_GROUP = 3
        const val EXPERIENCE_MENU_DEFAULT = 301
        const val EXPERIENCE_MENU_ENHANCED = 302
    }
}
