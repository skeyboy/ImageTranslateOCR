package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.ContextThemeWrapper
import com.example.imagetranslate.R
import com.example.imagetranslate.databinding.OverlayScreenshotActionsBinding
import kotlin.math.abs

internal data class ScreenshotOverlayItem(
    val uri: android.net.Uri,
    val key: String,
    val notificationId: Int
)

internal class ScreenshotOverlayController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onIgnore(item: ScreenshotOverlayItem)
        fun onTranslate(item: ScreenshotOverlayItem)
        fun onTranslateAndView(item: ScreenshotOverlayItem)
        fun onTranslateAndSave(item: ScreenshotOverlayItem)
        fun onAutoTranslateChanged(item: ScreenshotOverlayItem, enabled: Boolean)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
    private val binding = OverlayScreenshotActionsBinding.inflate(LayoutInflater.from(themedContext))
    private val marginPx = dp(12)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var currentItem: ScreenshotOverlayItem? = null
    private var currentPreview: Bitmap? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var suppressAutoTranslateCallback = false
    private var viewGeneration = 0

    init {
        binding.btnOverlayDismiss.setOnClickListener {
            currentItem?.let(listener::onIgnore)
        }
        binding.btnOverlayTranslate.setOnClickListener {
            currentItem?.let(listener::onTranslate)
        }
        binding.btnOverlayView.setOnClickListener {
            currentItem?.let(listener::onTranslateAndView)
        }
        binding.btnOverlaySave.setOnClickListener {
            currentItem?.let(listener::onTranslateAndSave)
        }
        binding.switchOverlayAutoTranslate.setOnCheckedChangeListener { _, enabled ->
            if (!suppressAutoTranslateCallback) {
                currentItem?.let { listener.onAutoTranslateChanged(it, enabled) }
            }
        }
        attachDragGesture()
    }

    fun show(item: ScreenshotOverlayItem, autoTranslate: Boolean) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) {
                return@post
            }
            currentItem = item
            recyclePreview()
            binding.screenshotPreviewLoading.visibility = View.VISIBLE
            suppressAutoTranslateCallback = true
            binding.switchOverlayAutoTranslate.isChecked = autoTranslate
            suppressAutoTranslateCallback = false
            showLoading(item.key)

            if (binding.root.isAttachedToWindow) {
                binding.root.animate().cancel()
                binding.root.alpha = 1f
                binding.root.scaleX = 1f
                binding.root.scaleY = 1f
                return@post
            }

            val bounds = windowBounds()
            val width = (bounds.first - marginPx * 2).coerceAtMost(dp(336))
            binding.screenshotPreviewContainer.layoutParams =
                binding.screenshotPreviewContainer.layoutParams.apply {
                    height = if (bounds.second < bounds.first) dp(96) else dp(224)
                }
            val initial = ScreenshotOverlayPositionPolicy.initial(marginPx)
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initial.x
                y = initial.y
            }
            layoutParams = params
            runCatching { windowManager.addView(binding.root, params) }
                .onFailure {
                    layoutParams = null
                    recyclePreview()
                    return@post
                }
            viewGeneration++
            binding.root.apply {
                pivotX = 0f
                pivotY = 0f
                alpha = 0f
                scaleX = 0.96f
                scaleY = 0.96f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
        }
    }

    fun showPreview(key: String, preview: Bitmap) {
        mainHandler.post {
            if (currentItem?.key != key) {
                if (!preview.isRecycled) preview.recycle()
                return@post
            }
            replacePreview(preview)
            binding.screenshotPreviewLoading.visibility = View.GONE
        }
    }

    fun showReady(key: String) = update(key) {
        showWaiting(key)
    }

    fun showProcessing(key: String) = update(key) {
        setBusy(true)
        binding.tvOverlayStatus.setText(R.string.screenshot_overlay_processing)
    }

    fun showTranslationResult(key: String, result: BackgroundTranslationResult) = update(key) {
        setBusy(false)
        if (result.translatedLines.isEmpty()) {
            binding.tvOverlayStatus.setText(R.string.screenshot_overlay_no_text)
        } else {
            binding.tvOverlayStatus.text = appContext.getString(
                R.string.screenshot_overlay_result,
                result.recognizedCount,
                result.translatedLines.size
            )
        }
    }

    fun showSaving(key: String) = update(key) {
        setBusy(true)
        binding.tvOverlayStatus.setText(R.string.screenshot_overlay_saving)
    }

    fun showSaved(key: String, replacedCount: Int) = update(key) {
        setBusy(false)
        binding.tvOverlayStatus.text = appContext.getString(
            R.string.screenshot_overlay_saved,
            replacedCount
        )
    }

    fun showFailure(key: String) = update(key) {
        setBusy(false)
        binding.tvOverlayStatus.setText(R.string.screenshot_overlay_failed)
    }

    fun dismiss(key: String? = null, immediate: Boolean = false) {
        mainHandler.post {
            if (key != null && currentItem?.key != key) return@post
            currentItem = null
            val generation = ++viewGeneration
            if (!binding.root.isAttachedToWindow) {
                recyclePreview()
                return@post
            }
            binding.root.animate().cancel()
            if (immediate) {
                removeView()
                return@post
            }
            binding.root.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(140L)
                .withEndAction {
                    if (generation == viewGeneration) removeView()
                }
                .start()
        }
    }

    private fun showWaiting(key: String) {
        if (currentItem?.key != key) return
        setBusy(false)
        binding.tvOverlayStatus.setText(R.string.screenshot_overlay_waiting)
    }

    private fun showLoading(key: String) {
        if (currentItem?.key != key) return
        binding.overlayProgress.visibility = View.VISIBLE
        binding.btnOverlayTranslate.isEnabled = false
        binding.btnOverlayView.isEnabled = false
        binding.btnOverlaySave.isEnabled = false
        binding.switchOverlayAutoTranslate.isEnabled = false
        binding.tvOverlayStatus.setText(R.string.screenshot_overlay_loading_preview)
    }

    private fun update(key: String, block: () -> Unit) {
        mainHandler.post {
            if (currentItem?.key == key) block()
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.overlayProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnOverlayTranslate.isEnabled = !busy
        binding.btnOverlayView.isEnabled = !busy
        binding.btnOverlaySave.isEnabled = !busy
        binding.switchOverlayAutoTranslate.isEnabled = !busy
    }

    private fun replacePreview(preview: Bitmap) {
        val previous = currentPreview
        currentPreview = preview
        binding.ivScreenshotPreview.setImageBitmap(preview)
        previous?.takeIf { it !== preview && !it.isRecycled }?.recycle()
    }

    private fun recyclePreview() {
        binding.ivScreenshotPreview.setImageDrawable(null)
        currentPreview?.takeIf { !it.isRecycled }?.recycle()
        currentPreview = null
    }

    private fun removeView() {
        runCatching { windowManager.removeViewImmediate(binding.root) }
        layoutParams = null
        recyclePreview()
    }

    private fun attachDragGesture() {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        binding.dragHandle.setOnTouchListener { view, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (!dragging && (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop)) {
                        dragging = true
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    if (dragging) {
                        val bounds = windowBounds()
                        val position = ScreenshotOverlayPositionPolicy.clamp(
                            startX + deltaX,
                            startY + deltaY,
                            bounds.first,
                            bounds.second,
                            binding.root.width,
                            binding.root.height,
                            marginPx
                        )
                        params.x = position.x
                        params.y = position.y
                        runCatching { windowManager.updateViewLayout(binding.root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun windowBounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }
    }

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).toInt()
}
