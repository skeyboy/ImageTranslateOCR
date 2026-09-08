package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
import com.example.imagetranslate.ocr.OcrEngineType
import com.example.imagetranslate.ocr.OcrModel
import com.example.imagetranslate.ocr.OcrModelState
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.translate.TranslationBackend
import com.example.imagetranslate.translate.TranslationBackendSettings
import com.example.imagetranslate.translate.TranslationMode
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import kotlin.math.abs

private const val SIGNATURE_OCCLUSION_PADDING_DP = 8

internal data class TranslationBackendMenuOption(
    val backend: TranslationBackend,
    val selected: Boolean,
    val enabled: Boolean
)

internal fun translationBackendMenuOptions(
    selectedBackend: TranslationBackend,
    networkConfigured: Boolean,
    selfHostedConfigured: Boolean = false,
    embeddedConfigured: Boolean = false
): List<TranslationBackendMenuOption> = TranslationBackend.entries.map { backend ->
    TranslationBackendMenuOption(
        backend = backend,
        selected = backend == selectedBackend,
        enabled = when (backend) {
            TranslationBackend.LOCAL -> true
            TranslationBackend.NETWORK -> networkConfigured
            TranslationBackend.SELF_HOSTED -> selfHostedConfigured
            TranslationBackend.SELF_HOSTED_V4 -> selfHostedConfigured
            TranslationBackend.EMBEDDED_V4 -> embeddedConfigured
        }
    )
}

internal data class LiveOcrTranslationEngineMenuOption(
    val engine: LiveOcrTranslationEngineType,
    val selected: Boolean,
    val enabled: Boolean
)

internal enum class OverlayPresentationFailure {
    CONTROL_LAYER_ATTACH_FAILED,
    TRANSLATION_LAYER_ATTACH_FAILED,
    INVALID_SOURCE_GEOMETRY,
    PATCHES_NOT_ACCEPTED,
    PATCHES_NOT_VISIBLE
}

internal data class OverlayPresentationResult(
    val attemptCount: Int,
    val controlAttached: Boolean,
    val translationLayerAttached: Boolean,
    val translationVisible: Boolean,
    val acceptedPatchCount: Int,
    val visiblePatchCount: Int,
    val failure: OverlayPresentationFailure? = null
) {
    val presented: Boolean
        get() = failure == null && controlAttached && translationLayerAttached &&
            (acceptedPatchCount == 0 ||
                (translationVisible && visiblePatchCount == acceptedPatchCount))
}

internal class OverlayTranslationVisibilityState {
    var visible: Boolean = true
        private set

    fun update(visible: Boolean) {
        this.visible = visible
    }

    fun resetForCaptureGeneration() {
        visible = true
    }
}

internal fun liveOcrTranslationEngineMenuOptions(
    selectedEngine: LiveOcrTranslationEngineType,
    paddleNetworkConfigured: Boolean
): List<LiveOcrTranslationEngineMenuOption> =
    LiveOcrTranslationEngineType.entries.map { engine ->
        LiveOcrTranslationEngineMenuOption(
            engine = engine,
            selected = engine == selectedEngine,
            enabled = engine != LiveOcrTranslationEngineType.PADDLE_NETWORK ||
                paddleNetworkConfigured
        )
    }

internal class ActiveScreenCaptureOverlayController(
    context: Context,
    initialExperienceMode: LiveOverlayExperienceMode,
    initialCaptureSettings: LiveCaptureSettings,
    initialOcrEngine: OcrEngineType,
    initialRecognitionMode: OcrRecognitionMode,
    initialSmartAssistEnabled: Boolean,
    initialBackgroundExperienceMode: LivePatchBackgroundExperienceMode,
    initialExperimentalTranslationEngine: ExperimentalTranslationEngine,
    initialTranslationBackend: TranslationBackend,
    private val networkTranslationConfigured: Boolean,
    private val selfHostedTranslationConfigured: Boolean,
    initialLiveOcrTranslationEngine: LiveOcrTranslationEngineType,
    initialPaddleNetworkConfigured: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onCapture()
        fun onStop()
        fun onCancelPreview()
        fun onTranslationModeChanged(mode: TranslationMode)
        fun onTranslationBackendChanged(backend: TranslationBackend)
        fun onLiveOcrTranslationEngineChanged(engine: LiveOcrTranslationEngineType)
        fun onTranslationVisibilityChanged(visible: Boolean)
        fun onExperienceModeRequested(mode: LiveOverlayExperienceMode)
        fun onCaptureSettingsChanged(settings: LiveCaptureSettings)
        fun onOcrSettingsOpened()
        fun onOcrEngineChanged(engine: OcrEngineType)
        fun onOcrRecognitionModeChanged(mode: OcrRecognitionMode)
        fun onOcrModelDownloadRequested(model: OcrModel)
        fun onSmartAssistEnabledChanged(enabled: Boolean)
        fun onBackgroundExperienceModeChanged(mode: LivePatchBackgroundExperienceMode)
        fun onExperimentalTranslationEngineChanged(engine: ExperimentalTranslationEngine)
        fun onExperimentalModelManagerRequested(engine: ExperimentalTranslationEngine)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val applicationWindowManager = appContext.getSystemService(WindowManager::class.java)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
    private val binding = OverlayActiveScreenCaptureBinding.inflate(
        LayoutInflater.from(themedContext)
    )
    private val translationView = ScreenTranslationOverlayView(themedContext)
    private val translationOverlayInstanceId =
        "translation-${Integer.toHexString(System.identityHashCode(translationView))}"
    @Volatile
    private var translationLayerAttached = false
    private var layerAttachSequence = 0L
    @Volatile
    private var controlLayerAttachOrder = 0L
    @Volatile
    private var translationLayerAttachOrder = 0L
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edgeMargin = dp(12)
    private val expandedWidth: Int
        get() = minOf(dp(380), (windowBounds().first - edgeMargin * 2).coerceAtLeast(dp(300)))
    private val expandedHeight = dp(78)
    private val collapsedWidth = dp(168)
    private val collapsedHeight = dp(42)
    private var controlParams: WindowManager.LayoutParams? = null
    private var translationParams: WindowManager.LayoutParams? = null
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    private var experienceMode = initialExperienceMode
    private var captureSettings = initialCaptureSettings
    private var ocrEngine = initialOcrEngine
    private var recognitionMode = initialRecognitionMode
    private var smartAssistEnabled = initialSmartAssistEnabled
    private var backgroundExperienceMode = initialBackgroundExperienceMode
    private var experimentalTranslationEngine = initialExperimentalTranslationEngine
    private var translationBackend = initialTranslationBackend
    private var liveOcrTranslationEngine = initialLiveOcrTranslationEngine
    private var paddleNetworkConfigured = initialPaddleNetworkConfigured
    private val ocrModelStates = OcrModel.entries.associateWith {
        OcrModelState.UNKNOWN
    }.toMutableMap()
    private var attachedWindowManager: WindowManager? = null
    private var sessionActive = false
    private var processing = false
    private var collapsed = false
    private val translationVisibility = OverlayTranslationVisibilityState()
    private var hasTranslationResult = false
    private var frameHeartbeatPhase = false
    private val presentationGenerationGate = OverlayPresentationGenerationGate()
    private var latestPerformanceSummary: String? = null
    private var latestCompactPerformance: String? = null

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
        binding.btnActiveOverlaySettings.setOnClickListener {
            listener.onOcrSettingsOpened()
            showCaptureSettingsMenu()
        }
        binding.btnToggleActiveTranslation.addOnCheckedChangeListener { _, checked ->
            translationVisibility.update(checked)
            translationView.setPatchesVisible(checked)
            listener.onTranslationVisibilityChanged(checked)
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

    fun beginCaptureGeneration(generation: Int) = onMainThread {
        if (!presentationGenerationGate.begin(generation)) return@onMainThread
        hasTranslationResult = false
        latestPerformanceSummary = null
        latestCompactPerformance = null
        removeTranslationLayersNow()
        translationVisibility.resetForCaptureGeneration()
        binding.btnToggleActiveTranslation.isChecked = true
        translationView.setPatchesVisible(true, animateChange = false)
        binding.root.visibility = View.INVISIBLE
    }

    fun invalidatePresentationGeneration(generation: Int) = onMainThread {
        if (!presentationGenerationGate.begin(generation)) return@onMainThread
        clearTranslationsNow()
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

    fun pulseFrameHeartbeat(onPulsed: (Boolean) -> Unit) = onMainThread {
        if (binding.root.parent == null ||
            binding.root.visibility != View.VISIBLE
        ) {
            onPulsed(false)
            return@onMainThread
        }
        frameHeartbeatPhase = !frameHeartbeatPhase
        binding.root.alpha = if (frameHeartbeatPhase) {
            FRAME_HEARTBEAT_CONTROL_ALPHA
        } else {
            1f
        }
        binding.root.postOnAnimation { onPulsed(true) }
    }

    fun translationOverlayInstanceId(): String = translationOverlayInstanceId

    fun attachedFullscreenTranslationLayerCount(): Int =
        if (translationLayerAttached) 1 else 0

    fun isControlLayerAboveTranslation(): Boolean =
        binding.root.parent != null &&
            (translationView.parent == null ||
                controlLayerAttachOrder > translationLayerAttachOrder)

    fun signatureOcclusionBounds(maskTranslationPatches: Boolean = false): List<Rect> {
        val padding = dp(SIGNATURE_OCCLUSION_PADDING_DP)
        val regions = if (
            maskTranslationPatches ||
            LiveOverlayExperiencePolicy.shouldMaskTranslationPatchesFromSignature(experienceMode)
        ) {
            translationView.signatureOcclusionBounds().map { bounds ->
                Rect(bounds).apply { inset(-padding, -padding) }
            }.toMutableList()
        } else {
            // Default overlays are translucent, so their pixels retain the underlying motion signal.
            mutableListOf<Rect>()
        }
        val params = controlParams
        if (params != null && binding.root.parent != null) {
            regions += Rect(
                params.x - padding,
                params.y - padding,
                params.x + params.width + padding,
                params.y + params.height + padding
            )
        }
        return regions
    }

    fun visibleOcrOcclusionBounds(): List<Rect> {
        if (binding.root.visibility == View.VISIBLE) {
            return signatureOcclusionBounds(maskTranslationPatches = true)
        }
        return translationView.signatureOcclusionBounds()
    }

    fun showProcessing(shouldShow: () -> Boolean = { true }) = onMainThread {
        if (!shouldShow()) return@onMainThread
        if (!ensureControlAttachedNow()) return@onMainThread
        processing = true
        binding.tvActiveOverlayPerformance.visibility = View.GONE
        sessionActive = true
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

    fun showPreparingOcrModels() = onMainThread {
        if (!ensureControlAttachedNow()) return@onMainThread
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.VISIBLE
        binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_ocr_model_preparing)
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = false
        binding.btnActiveOverlaySettings.isEnabled = false
        updateCompactStatus(R.string.active_screenshot_ocr_model_preparing_short, showProgress = true)
    }

    fun showRecognitionSucceeded() = onMainThread {
        if (!processing || !ensureControlAttachedNow()) return@onMainThread
        binding.activeOverlayProgress.visibility = View.VISIBLE
        binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_recognition_succeeded)
        updateCompactStatus(
            R.string.active_screenshot_recognition_succeeded_short,
            showProgress = true
        )
    }

    fun showRecognitionFailed() = showTransientRecognitionStatus(
        R.string.active_screenshot_recognition_failed
    )

    fun showRecognitionCancelled() = showTransientRecognitionStatus(
        R.string.active_screenshot_recognition_cancelled
    )

    fun updateOcrModelState(model: OcrModel, state: OcrModelState) = onMainThread {
        ocrModelStates[model] = state
    }

    fun setTranslationBackend(backend: TranslationBackend) = onMainThread {
        translationBackend = backend
    }

    fun setLiveOcrTranslationEngine(
        engine: LiveOcrTranslationEngineType,
        networkConfigured: Boolean
    ) = onMainThread {
        liveOcrTranslationEngine = engine
        paddleNetworkConfigured = networkConfigured
    }

    fun showWaitingForStable(onHidden: ((Boolean) -> Unit)? = null) = onMainThread {
        val translationLayerCleared = translationView.clearForViewportMovement()
        hasTranslationResult = false
        sessionActive = true
        updateCompactStatus(R.string.active_screenshot_compact_waiting, showProgress = false)
        if (binding.expandedCaptureControls.visibility == View.VISIBLE) {
            binding.btnActiveOverlayCapture.visibility = View.GONE
            binding.activeOverlayStatusGroup.visibility = View.VISIBLE
            binding.activeOverlayProgress.visibility = View.INVISIBLE
            binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_waiting_stable)
            binding.btnCancelActivePreview.visibility = View.VISIBLE
        }
        binding.btnToggleActiveTranslation.isEnabled = false
        binding.root.postOnAnimation { onHidden?.invoke(translationLayerCleared) }
    }

    fun showResult(
        generation: Int,
        patches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int,
        recognizedCount: Int,
        ocrMs: Long,
        translationMs: Long,
        renderingMs: Long,
        shouldPresent: () -> Boolean = { true },
        onPresented: (OverlayPresentationResult) -> Unit,
        onPresentationFailed: (OverlayPresentationResult) -> Unit = {}
    ) = onMainThread {
        if (!presentationGenerationGate.accepts(generation) || !shouldPresent()) {
            patches.recyclePatchBitmaps()
            return@onMainThread
        }
        presentResultWithRetry(
            generation = generation,
            patches = patches,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            shouldPresent = shouldPresent,
            onPresented = { presentation ->
                finishShowingResult(
                    patches = patches,
                    recognizedCount = recognizedCount,
                    ocrMs = ocrMs,
                    translationMs = translationMs,
                    renderingMs = renderingMs
                )
                binding.root.postOnAnimation { onPresented(presentation) }
            },
            onPresentationFailed = onPresentationFailed
        )
    }

    private fun finishShowingResult(
        patches: List<ScreenTranslationPatch>,
        recognizedCount: Int,
        ocrMs: Long,
        translationMs: Long,
        renderingMs: Long
    ) {
        processing = false
        sessionActive = true
        hasTranslationResult = patches.isNotEmpty()
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.GONE
        val resultText = if (patches.isEmpty()) {
            appContext.getString(R.string.active_screenshot_no_translatable_text)
        } else {
            appContext.getString(R.string.active_screenshot_live_result, recognizedCount)
        }
        binding.tvActiveOverlayStatus.text = resultText
        val totalMs = ocrMs + translationMs + renderingMs
        latestPerformanceSummary = appContext.getString(
            R.string.active_screenshot_performance_metrics,
            ocrMs,
            translationMs,
            renderingMs,
            totalMs
        )
        latestCompactPerformance = appContext.getString(
            R.string.active_screenshot_performance_compact,
            totalMs
        )
        binding.tvActiveOverlayPerformance.text = latestPerformanceSummary
        binding.tvActiveOverlayPerformance.visibility = View.VISIBLE
        binding.btnCancelActivePreview.visibility = View.VISIBLE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnActiveOverlaySettings.isEnabled = true
        binding.btnToggleActiveTranslation.isEnabled = hasTranslationResult
        binding.btnToggleActiveTranslation.isChecked = translationVisibility.visible
        updateCompactPerformance()
    }

    private fun presentResultWithRetry(
        generation: Int,
        patches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int,
        shouldPresent: () -> Boolean,
        onPresented: (OverlayPresentationResult) -> Unit,
        onPresentationFailed: (OverlayPresentationResult) -> Unit,
        attempt: Int = 1
    ) {
        if (!presentationGenerationGate.accepts(generation) || !shouldPresent()) {
            patches.recyclePatchBitmaps()
            return
        }
        val presentation = showTranslationPatchesNow(
            patches = patches,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            attemptCount = attempt
        )
        if (presentation.presented) {
            onPresented(presentation)
            return
        }
        val retryable = presentation.failure ==
            OverlayPresentationFailure.CONTROL_LAYER_ATTACH_FAILED ||
            presentation.failure == OverlayPresentationFailure.TRANSLATION_LAYER_ATTACH_FAILED
        if (retryable && attempt < OVERLAY_PRESENTATION_MAX_ATTEMPTS) {
            Log.w(TAG, "Translation overlay presentation failed; retrying: $presentation")
            mainHandler.postDelayed(
                {
                    presentResultWithRetry(
                        generation = generation,
                        patches = patches,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        shouldPresent = shouldPresent,
                        onPresented = onPresented,
                        onPresentationFailed = onPresentationFailed,
                        attempt = attempt + 1
                    )
                },
                OVERLAY_PRESENTATION_RETRY_DELAY_MS
            )
            return
        }
        Log.e(TAG, "Translation overlay presentation failed: $presentation")
        patches.recyclePatchBitmaps()
        mainHandler.post { onPresentationFailed(presentation) }
    }

    fun showCaptureFailed() = onMainThread(::showReadyNow)

    private fun showTransientRecognitionStatus(textRes: Int) = onMainThread {
        if (!ensureControlAttachedNow()) return@onMainThread
        processing = false
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.GONE
        binding.activeOverlayStatusGroup.visibility = View.VISIBLE
        binding.activeOverlayProgress.visibility = View.INVISIBLE
        binding.tvActiveOverlayStatus.setText(textRes)
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnActiveOverlaySettings.isEnabled = true
        updateCompactStatus(textRes, showProgress = false)
        mainHandler.postDelayed(::showReadyNow, TERMINAL_STATUS_DURATION_MS)
    }

    fun restoreAfterSkippedCapture() = onMainThread {
        processing = false
        binding.root.visibility = View.VISIBLE
        translationView.setPatchesVisible(
            translationVisibility.visible,
            animateChange = false
        )
        binding.activeOverlayProgress.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnActiveOverlaySettings.isEnabled = true
        binding.btnToggleActiveTranslation.isEnabled = hasTranslationResult
        if (hasTranslationResult) {
            binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_compact_translated)
            updateCompactPerformance()
        }
    }

    fun clearTranslations() = onMainThread {
        clearTranslationsNow()
    }

    private fun clearTranslationsNow() {
        hasTranslationResult = false
        latestPerformanceSummary = null
        latestCompactPerformance = null
        binding.tvActiveOverlayPerformance.visibility = View.GONE
        binding.btnToggleActiveTranslation.isEnabled = false
        removeTranslationLayersNow()
    }

    fun showProjectionRevoked(interruptedByRecorder: Boolean) = onMainThread {
        if (!ensureControlAttachedNow()) return@onMainThread
        processing = false
        sessionActive = false
        hasTranslationResult = false
        latestPerformanceSummary = null
        latestCompactPerformance = null
        detachTranslationLayerNow()
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.VISIBLE
        // These views share a FrameLayout; showing the status would cover the action that
        // the user must tap to obtain a fresh MediaProjection token.
        binding.activeOverlayStatusGroup.visibility = View.GONE
        binding.activeOverlayProgress.visibility = View.GONE
        binding.tvActiveOverlayStatus.setText(R.string.active_screenshot_projection_revoked)
        binding.tvActiveOverlayPerformance.setText(
            if (interruptedByRecorder) {
                R.string.active_screenshot_recorder_interrupted_banner
            } else {
                R.string.active_screenshot_capture_interrupted_banner
            }
        )
        binding.tvActiveOverlayPerformance.visibility = View.VISIBLE
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnActiveOverlaySettings.isEnabled = true
        binding.btnToggleActiveTranslation.isEnabled = false
        binding.btnToggleActiveTranslation.isChecked = true
        updateCompactStatus(
            if (interruptedByRecorder) {
                R.string.active_screenshot_compact_recorder_interrupted
            } else {
                R.string.active_screenshot_compact_projection_revoked
            },
            showProgress = false
        )
        expandNow()
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
        latestPerformanceSummary = null
        latestCompactPerformance = null
        translationVisibility.resetForCaptureGeneration()
        removeTranslationLayersNow()
        if (!ensureControlAttachedNow()) return
        binding.root.visibility = View.VISIBLE
        binding.btnActiveOverlayCapture.visibility = View.VISIBLE
        binding.activeOverlayStatusGroup.visibility = View.GONE
        binding.tvActiveOverlayPerformance.visibility = View.GONE
        binding.btnCancelActivePreview.visibility = View.GONE
        binding.btnActiveOverlayMode.isEnabled = true
        binding.btnActiveOverlaySettings.isEnabled = true
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
        if (binding.root.parent != null) return ensureTranslationAttachedNow()
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
        if (binding.root.parent != null) {
            attachedWindowManager = windowManager
            controlLayerAttachOrder = nextLayerAttachOrder()
        }
        if (binding.root.parent != null) animateControlMaterialization()
        return binding.root.parent != null
    }

    private fun showTranslationPatchesNow(
        patches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int,
        attemptCount: Int
    ): OverlayPresentationResult {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return failedPresentation(
                attemptCount,
                OverlayPresentationFailure.INVALID_SOURCE_GEOMETRY
            )
        }
        if (!ensureControlAttachedNow()) {
            val failure = if (binding.root.parent == null) {
                OverlayPresentationFailure.CONTROL_LAYER_ATTACH_FAILED
            } else {
                OverlayPresentationFailure.TRANSLATION_LAYER_ATTACH_FAILED
            }
            return failedPresentation(attemptCount, failure)
        }
        removeTranslationLayersNow()
        translationView.replacePatches(patches, sourceWidth, sourceHeight)
        translationView.setPatchesVisible(
            translationVisibility.visible,
            animateChange = false
        )
        val state = translationView.overlayState()
        return OverlayPresentationResult(
            attemptCount = attemptCount,
            controlAttached = binding.root.parent != null,
            translationLayerAttached = state.attached,
            translationVisible = state.translationVisible,
            acceptedPatchCount = state.acceptedPatchCount,
            visiblePatchCount = state.visiblePatchCount,
            failure = when {
                patches.isNotEmpty() && state.acceptedPatchCount != patches.size ->
                    OverlayPresentationFailure.PATCHES_NOT_ACCEPTED
                patches.isNotEmpty() &&
                    (!state.translationVisible ||
                        state.visiblePatchCount != state.acceptedPatchCount) ->
                    OverlayPresentationFailure.PATCHES_NOT_VISIBLE
                else -> null
            }
        )
    }

    private fun failedPresentation(
        attemptCount: Int,
        failure: OverlayPresentationFailure
    ): OverlayPresentationResult {
        val state = translationView.overlayState()
        return OverlayPresentationResult(
            attemptCount = attemptCount,
            controlAttached = binding.root.parent != null,
            translationLayerAttached = state.attached,
            translationVisible = state.translationVisible,
            acceptedPatchCount = state.acceptedPatchCount,
            visiblePatchCount = state.visiblePatchCount,
            failure = failure
        )
    }

    private fun ensureTranslationAttachedNow(): Boolean {
        if (!canAttachOverlay()) return false
        if (translationView.parent != null) return true
        val bounds = windowBounds()
        val params = createLayoutParams(
            width = bounds.first,
            height = bounds.second,
            flags = TranslationOverlayTouchPolicy.PASSTHROUGH_WINDOW_FLAGS,
            x = 0,
            y = 0
        ).apply {
            alpha = translationPatchWindowAlpha(overlapCount = 1)
        }
        val windowManager = activeWindowManager() ?: return false
        translationParams = params
        return runCatching {
            windowManager.addView(translationView, params)
            if (translationView.parent != null) {
                attachedWindowManager = windowManager
                translationLayerAttached = true
                translationLayerAttachOrder = nextLayerAttachOrder()
                if (binding.root.parent != null &&
                    !reattachControlAboveTranslationNow(windowManager)
                ) {
                    detachTranslationLayerNow()
                    return@runCatching false
                }
            }
            translationView.parent != null
        }.onFailure {
            translationParams = null
            translationLayerAttached = false
        }.getOrDefault(false)
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
        translationLayerAttached = false
        controlLayerAttachOrder = 0L
        translationLayerAttachOrder = 0L
        controlParams = null
        translationParams = null
        attachedWindowManager = null
    }

    private fun removeTranslationLayersNow() {
        translationView.clearPatches()
    }

    private fun detachTranslationLayerNow() {
        translationView.clearPatches()
        val windowManager = attachedWindowManager ?: applicationWindowManager
        if (translationView.parent != null) {
            runCatching { windowManager.removeViewImmediate(translationView) }
                .onFailure { Log.w(TAG, "Unable to remove stale translation surface", it) }
        }
        translationLayerAttached = false
        translationLayerAttachOrder = 0L
        translationParams = null
    }

    private fun reattachControlAboveTranslationNow(windowManager: WindowManager): Boolean {
        val params = controlParams ?: return false
        if (binding.root.parent == null) return true
        binding.root.animate().cancel()
        return runCatching {
            windowManager.removeViewImmediate(binding.root)
            windowManager.addView(binding.root, params)
            attachedWindowManager = windowManager
            controlLayerAttachOrder = nextLayerAttachOrder()
            Log.i(TAG, "Reattached OCR control above the translation overlay")
            true
        }.onFailure { error ->
            controlLayerAttachOrder = 0L
            controlParams = null
            Log.e(TAG, "Unable to keep OCR control above the translation overlay", error)
        }.getOrDefault(false)
    }

    private fun nextLayerAttachOrder(): Long {
        layerAttachSequence += 1L
        return layerAttachSequence
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
                MENU_HEADER_EXPERIMENTAL_TRANSLATION,
                3,
                R.string.active_screenshot_experimental_translation_group
            ).isEnabled = false
            menu.add(
                EXPERIMENTAL_TRANSLATION_MENU_GROUP,
                EXPERIMENTAL_TRANSLATION_DISABLED,
                4,
                R.string.active_screenshot_experimental_translation_disabled
            )
            menu.add(
                EXPERIMENTAL_TRANSLATION_MENU_GROUP,
                EXPERIMENTAL_TRANSLATION_MARIAN,
                5,
                R.string.active_screenshot_experimental_translation_marian
            )
            menu.add(
                EXPERIMENTAL_TRANSLATION_MENU_GROUP,
                EXPERIMENTAL_TRANSLATION_GEMMA,
                6,
                R.string.active_screenshot_experimental_translation_gemma
            )
            menu.setGroupCheckable(EXPERIMENTAL_TRANSLATION_MENU_GROUP, true, true)
            menu.findItem(
                when (experimentalTranslationEngine) {
                    ExperimentalTranslationEngine.DISABLED -> EXPERIMENTAL_TRANSLATION_DISABLED
                    ExperimentalTranslationEngine.MARIAN_INT8 -> EXPERIMENTAL_TRANSLATION_MARIAN
                    ExperimentalTranslationEngine.TRANSLATEGEMMA_4B -> EXPERIMENTAL_TRANSLATION_GEMMA
                }
            ).isChecked = true
            menu.add(
                EXPERIMENTAL_TRANSLATION_ACTION_GROUP,
                EXPERIMENTAL_TRANSLATION_MANAGE,
                7,
                R.string.active_screenshot_experimental_translation_manage
            )
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
            menu.add(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_OPEN,
                20,
                R.string.active_screenshot_capture_settings
            )
            setOnMenuItemClickListener { item ->
                if (item.itemId == EXPERIMENTAL_TRANSLATION_MANAGE) {
                    listener.onExperimentalModelManagerRequested(experimentalTranslationEngine)
                    return@setOnMenuItemClickListener true
                }
                val selectedExperimentalEngine = when (item.itemId) {
                    EXPERIMENTAL_TRANSLATION_DISABLED -> ExperimentalTranslationEngine.DISABLED
                    EXPERIMENTAL_TRANSLATION_MARIAN -> ExperimentalTranslationEngine.MARIAN_INT8
                    EXPERIMENTAL_TRANSLATION_GEMMA -> ExperimentalTranslationEngine.TRANSLATEGEMMA_4B
                    else -> null
                }
                if (selectedExperimentalEngine != null) {
                    if (selectedExperimentalEngine != experimentalTranslationEngine) {
                        experimentalTranslationEngine = selectedExperimentalEngine
                        listener.onExperimentalTranslationEngineChanged(selectedExperimentalEngine)
                    }
                    return@setOnMenuItemClickListener true
                }
                if (item.itemId == CAPTURE_SETTINGS_MENU_OPEN) {
                    mainHandler.post(::showCaptureSettingsMenu)
                    return@setOnMenuItemClickListener true
                }
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

    private fun showCaptureSettingsMenu() {
        PopupMenu(themedContext, binding.btnActiveOverlaySettings).apply {
            menu.add(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_SUMMARY,
                0,
                appContext.getString(
                    R.string.active_screenshot_capture_settings_current,
                    sceneLabel(captureSettings.scenePreset),
                    frequencyLabel(captureSettings.frequency),
                    bufferLabel(captureSettings.bufferMode),
                    segmentationLabel(captureSettings.segmentation)
                )
            ).isEnabled = false

            val translationProviderMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_TRANSLATION_PROVIDER,
                1,
                appContext.getString(
                    R.string.active_screenshot_translation_provider_current,
                    translationBackendLabel(translationBackend)
                )
            )
            val translationBackendOptions = translationBackendMenuOptions(
                selectedBackend = translationBackend,
                networkConfigured = networkTranslationConfigured,
                selfHostedConfigured = selfHostedTranslationConfigured,
                embeddedConfigured = TranslationBackendSettings.isEdgeConfigured(appContext)
            )
            translationBackendOptions.forEachIndexed { index, option ->
                val backend = option.backend
                translationProviderMenu.add(
                    TRANSLATION_PROVIDER_MENU_GROUP,
                    translationBackendMenuId(backend),
                    index,
                    when {
                        backend == TranslationBackend.NETWORK &&
                            !networkTranslationConfigured ->
                            R.string.active_screenshot_translation_provider_network_unavailable
                        backend == TranslationBackend.NETWORK ->
                            R.string.active_screenshot_translation_provider_network
                        backend == TranslationBackend.SELF_HOSTED &&
                            !selfHostedTranslationConfigured ->
                            R.string.active_screenshot_translation_provider_self_hosted_unavailable
                        backend == TranslationBackend.SELF_HOSTED_V4 &&
                            !selfHostedTranslationConfigured ->
                            R.string.active_screenshot_translation_provider_self_hosted_v4_unavailable
                        backend == TranslationBackend.SELF_HOSTED ->
                            R.string.active_screenshot_translation_provider_self_hosted
                        backend == TranslationBackend.SELF_HOSTED_V4 ->
                            R.string.active_screenshot_translation_provider_self_hosted_v4
                        backend == TranslationBackend.EMBEDDED_V4 ->
                            R.string.active_screenshot_translation_provider_embedded_v4
                        else -> R.string.active_screenshot_translation_provider_local
                    }
                ).isEnabled = option.enabled
            }
            translationProviderMenu.setGroupCheckable(
                TRANSLATION_PROVIDER_MENU_GROUP,
                true,
                true
            )
            translationBackendOptions.firstOrNull(TranslationBackendMenuOption::selected)
                ?.let { selected ->
                    translationProviderMenu.findItem(
                        translationBackendMenuId(selected.backend)
                    )?.isChecked = true
                }

            val liveEngineMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_LIVE_ENGINE,
                2,
                R.string.active_screenshot_live_engine_group
            )
            val liveEngineOptions = liveOcrTranslationEngineMenuOptions(
                selectedEngine = liveOcrTranslationEngine,
                paddleNetworkConfigured = paddleNetworkConfigured
            )
            liveEngineOptions.forEachIndexed { index, option ->
                liveEngineMenu.add(
                    LIVE_ENGINE_MENU_GROUP,
                    liveOcrTranslationEngineMenuId(option.engine),
                    index,
                    when {
                        option.engine == LiveOcrTranslationEngineType.PADDLE_NETWORK &&
                            !paddleNetworkConfigured ->
                            R.string.active_screenshot_live_engine_paddle_network_unavailable
                        option.engine == LiveOcrTranslationEngineType.PADDLE_NETWORK ->
                            R.string.active_screenshot_live_engine_paddle_network
                        else -> R.string.active_screenshot_live_engine_local
                    }
                ).isEnabled = option.enabled
            }
            liveEngineMenu.setGroupCheckable(LIVE_ENGINE_MENU_GROUP, true, true)
            liveEngineOptions.firstOrNull(LiveOcrTranslationEngineMenuOption::selected)
                ?.let { selected ->
                    liveEngineMenu.findItem(
                        liveOcrTranslationEngineMenuId(selected.engine)
                    )?.isChecked = true
                }

            val ocrEngineMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_OCR_ENGINE,
                2,
                R.string.active_screenshot_ocr_engine_group
            )
            OcrEngineType.entries.forEachIndexed { index, engine ->
                ocrEngineMenu.add(
                    OCR_ENGINE_MENU_GROUP,
                    ocrEngineMenuId(engine),
                    index,
                    ocrEngineLabel(engine)
                )
            }
            ocrEngineMenu.setGroupCheckable(OCR_ENGINE_MENU_GROUP, true, true)
            ocrEngineMenu.findItem(ocrEngineMenuId(ocrEngine))?.isChecked = true

            val recognitionMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_OCR_MODE,
                3,
                R.string.active_screenshot_ocr_mode_group
            )
            OcrRecognitionMode.entries.forEachIndexed { index, mode ->
                recognitionMenu.add(
                    OCR_MODE_MENU_GROUP,
                    ocrModeMenuId(mode),
                    index,
                    ocrModeLabel(mode)
                )
            }
            recognitionMenu.setGroupCheckable(OCR_MODE_MENU_GROUP, true, true)
            recognitionMenu.findItem(ocrModeMenuId(recognitionMode))?.isChecked = true

            if (ocrEngine == OcrEngineType.ML_KIT) {
                val modelMenu = menu.addSubMenu(
                    CAPTURE_SETTINGS_MENU_GROUP,
                    CAPTURE_SETTINGS_MENU_OCR_MODELS,
                    4,
                    R.string.active_screenshot_ocr_models_group
                )
                OcrModel.entries.forEachIndexed { index, model ->
                    modelMenu.add(
                        OCR_MODEL_MENU_GROUP,
                        ocrModelMenuId(model),
                        index,
                        appContext.getString(
                            R.string.active_screenshot_ocr_model_status,
                            ocrModelLabel(model),
                            ocrModelStateLabel(ocrModelStates.getValue(model))
                        )
                    ).isEnabled = ocrModelStates[model] != OcrModelState.DOWNLOADING
                }
            }

            val sceneMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_SCENE,
                4,
                R.string.active_screenshot_scene_group
            )
            LiveCaptureScenePreset.entries
                .filterNot { it == LiveCaptureScenePreset.CUSTOM }
                .forEachIndexed { index, preset ->
                    sceneMenu.add(
                        SCENE_MENU_GROUP,
                        sceneMenuId(preset),
                        index,
                        sceneLabel(preset)
                    )
                }
            sceneMenu.setGroupCheckable(SCENE_MENU_GROUP, true, true)
            if (captureSettings.scenePreset != LiveCaptureScenePreset.CUSTOM) {
                sceneMenu.findItem(sceneMenuId(captureSettings.scenePreset))?.isChecked = true
            }

            val frequencyMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_FREQUENCY,
                5,
                R.string.active_screenshot_frequency_group
            )
            LiveCaptureFrequency.entries.forEachIndexed { index, frequency ->
                frequencyMenu.add(
                    FREQUENCY_MENU_GROUP,
                    frequencyMenuId(frequency),
                    index,
                    frequencyLabel(frequency)
                )
            }
            frequencyMenu.setGroupCheckable(FREQUENCY_MENU_GROUP, true, true)
            frequencyMenu.findItem(frequencyMenuId(captureSettings.frequency))?.isChecked = true

            val bufferMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_BUFFER,
                6,
                R.string.active_screenshot_buffer_group
            )
            LiveFrameBufferMode.entries.forEachIndexed { index, mode ->
                bufferMenu.add(
                    BUFFER_MENU_GROUP,
                    bufferMenuId(mode),
                    index,
                    bufferLabel(mode)
                )
            }
            bufferMenu.setGroupCheckable(BUFFER_MENU_GROUP, true, true)
            bufferMenu.findItem(bufferMenuId(captureSettings.bufferMode))?.isChecked = true

            val segmentationMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_SEGMENTATION,
                7,
                R.string.active_screenshot_segmentation_group
            )
            LiveRecognitionSegmentation.entries.forEachIndexed { index, segmentation ->
                segmentationMenu.add(
                    SEGMENTATION_MENU_GROUP,
                    segmentationMenuId(segmentation),
                    index,
                    segmentationLabel(segmentation)
                )
            }
            segmentationMenu.setGroupCheckable(SEGMENTATION_MENU_GROUP, true, true)
            segmentationMenu.findItem(
                segmentationMenuId(captureSettings.segmentation)
            )?.isChecked = true

            val backgroundExperienceMenu = menu.addSubMenu(
                CAPTURE_SETTINGS_MENU_GROUP,
                CAPTURE_SETTINGS_MENU_BACKGROUND_EXPERIENCE,
                8,
                R.string.active_screenshot_background_experience_group
            )
            LivePatchBackgroundExperienceMode.entries.forEachIndexed { index, mode ->
                backgroundExperienceMenu.add(
                    BACKGROUND_EXPERIENCE_MENU_GROUP,
                    backgroundExperienceMenuId(mode),
                    index,
                    backgroundExperienceLabel(mode)
                )
            }
            backgroundExperienceMenu.setGroupCheckable(
                BACKGROUND_EXPERIENCE_MENU_GROUP,
                true,
                true
            )
            backgroundExperienceMenu.findItem(
                backgroundExperienceMenuId(backgroundExperienceMode)
            )?.isChecked = true

            menu.add(
                SMART_ASSIST_MENU_GROUP,
                SMART_ASSIST_MENU_ENABLED,
                9,
                R.string.active_screenshot_smart_assist
            ).apply {
                isCheckable = true
                isChecked = smartAssistEnabled
            }

            setOnMenuItemClickListener { item ->
                val selectedLiveEngine = LiveOcrTranslationEngineType.entries.firstOrNull {
                    liveOcrTranslationEngineMenuId(it) == item.itemId
                }
                if (selectedLiveEngine != null) {
                    if (selectedLiveEngine != liveOcrTranslationEngine) {
                        liveOcrTranslationEngine = selectedLiveEngine
                        listener.onLiveOcrTranslationEngineChanged(selectedLiveEngine)
                    }
                    return@setOnMenuItemClickListener true
                }
                val selectedTranslationBackend = TranslationBackend.entries.firstOrNull {
                    translationBackendMenuId(it) == item.itemId
                }
                if (selectedTranslationBackend != null) {
                    if (selectedTranslationBackend != translationBackend) {
                        translationBackend = selectedTranslationBackend
                        listener.onTranslationBackendChanged(selectedTranslationBackend)
                    }
                    return@setOnMenuItemClickListener true
                }
                if (item.itemId == SMART_ASSIST_MENU_ENABLED) {
                    smartAssistEnabled = !smartAssistEnabled
                    item.isChecked = smartAssistEnabled
                    listener.onSmartAssistEnabledChanged(smartAssistEnabled)
                    return@setOnMenuItemClickListener true
                }
                val selectedOcrEngine = OcrEngineType.entries.firstOrNull {
                    ocrEngineMenuId(it) == item.itemId
                }
                if (selectedOcrEngine != null) {
                    if (selectedOcrEngine != ocrEngine) {
                        ocrEngine = selectedOcrEngine
                        listener.onOcrEngineChanged(selectedOcrEngine)
                    }
                    return@setOnMenuItemClickListener true
                }
                val selectedRecognitionMode = OcrRecognitionMode.entries.firstOrNull {
                    ocrModeMenuId(it) == item.itemId
                }
                if (selectedRecognitionMode != null) {
                    if (selectedRecognitionMode != recognitionMode) {
                        recognitionMode = selectedRecognitionMode
                        listener.onOcrRecognitionModeChanged(selectedRecognitionMode)
                    }
                    return@setOnMenuItemClickListener true
                }
                val selectedModel = OcrModel.entries.firstOrNull {
                    ocrModelMenuId(it) == item.itemId
                }
                if (selectedModel != null) {
                    listener.onOcrModelDownloadRequested(selectedModel)
                    return@setOnMenuItemClickListener true
                }
                val selectedBackgroundExperience =
                    LivePatchBackgroundExperienceMode.entries.firstOrNull {
                        backgroundExperienceMenuId(it) == item.itemId
                    }
                if (selectedBackgroundExperience != null) {
                    if (selectedBackgroundExperience != backgroundExperienceMode) {
                        backgroundExperienceMode = selectedBackgroundExperience
                        listener.onBackgroundExperienceModeChanged(selectedBackgroundExperience)
                    }
                    return@setOnMenuItemClickListener true
                }
                val preset = LiveCaptureScenePreset.entries.firstOrNull {
                    sceneMenuId(it) == item.itemId
                }
                if (preset != null && preset != LiveCaptureScenePreset.CUSTOM) {
                    updateCaptureSettings(LiveCaptureSettingsPolicy.forPreset(preset))
                    return@setOnMenuItemClickListener true
                }
                val frequency = LiveCaptureFrequency.entries.firstOrNull {
                    frequencyMenuId(it) == item.itemId
                }
                if (frequency != null) {
                    updateCaptureSettings(
                        LiveCaptureSettingsPolicy.customize(
                            captureSettings,
                            frequency = frequency
                        )
                    )
                    return@setOnMenuItemClickListener true
                }
                val bufferMode = LiveFrameBufferMode.entries.firstOrNull {
                    bufferMenuId(it) == item.itemId
                }
                if (bufferMode != null) {
                    updateCaptureSettings(
                        LiveCaptureSettingsPolicy.customize(
                            captureSettings,
                            bufferMode = bufferMode
                        )
                    )
                    return@setOnMenuItemClickListener true
                }
                val segmentation = LiveRecognitionSegmentation.entries.firstOrNull {
                    segmentationMenuId(it) == item.itemId
                }
                if (segmentation != null) {
                    updateCaptureSettings(
                        LiveCaptureSettingsPolicy.customize(
                            captureSettings,
                            segmentation = segmentation
                        )
                    )
                    return@setOnMenuItemClickListener true
                }
                false
            }
            show()
        }
    }

    private fun updateCaptureSettings(settings: LiveCaptureSettings) {
        if (settings == captureSettings) return
        captureSettings = settings
        listener.onCaptureSettingsChanged(settings)
    }

    private fun sceneLabel(preset: LiveCaptureScenePreset): String = appContext.getString(
        when (preset) {
            LiveCaptureScenePreset.ADAPTIVE -> R.string.active_screenshot_scene_adaptive
            LiveCaptureScenePreset.READING -> R.string.active_screenshot_scene_reading
            LiveCaptureScenePreset.DENSE_TEXT -> R.string.active_screenshot_scene_dense_text
            LiveCaptureScenePreset.CODE -> R.string.active_screenshot_scene_code
            LiveCaptureScenePreset.DYNAMIC -> R.string.active_screenshot_scene_dynamic
            LiveCaptureScenePreset.CUSTOM -> R.string.active_screenshot_scene_custom
        }
    )

    private fun frequencyLabel(frequency: LiveCaptureFrequency): String = appContext.getString(
        when (frequency) {
            LiveCaptureFrequency.LOW -> R.string.active_screenshot_frequency_low
            LiveCaptureFrequency.MEDIUM -> R.string.active_screenshot_frequency_medium
            LiveCaptureFrequency.NORMAL -> R.string.active_screenshot_frequency_normal
            LiveCaptureFrequency.HIGH -> R.string.active_screenshot_frequency_high
            LiveCaptureFrequency.VERY_HIGH -> R.string.active_screenshot_frequency_very_high
        }
    )

    private fun bufferLabel(mode: LiveFrameBufferMode): String = appContext.getString(
        when (mode) {
            LiveFrameBufferMode.SINGLE -> R.string.active_screenshot_buffer_single
            LiveFrameBufferMode.MULTI -> R.string.active_screenshot_buffer_multi
        }
    )

    private fun segmentationLabel(segmentation: LiveRecognitionSegmentation): String =
        appContext.getString(
            when (segmentation) {
                LiveRecognitionSegmentation.ADAPTIVE ->
                    R.string.active_screenshot_segmentation_adaptive
                LiveRecognitionSegmentation.FULL_FRAME ->
                    R.string.active_screenshot_segmentation_full
                LiveRecognitionSegmentation.VERTICAL_BANDS ->
                    R.string.active_screenshot_segmentation_bands
            }
        )

    private fun sceneMenuId(preset: LiveCaptureScenePreset): Int =
        SCENE_MENU_ID_BASE + preset.ordinal

    private fun frequencyMenuId(frequency: LiveCaptureFrequency): Int =
        FREQUENCY_MENU_ID_BASE + frequency.ordinal

    private fun bufferMenuId(mode: LiveFrameBufferMode): Int = BUFFER_MENU_ID_BASE + mode.ordinal

    private fun segmentationMenuId(segmentation: LiveRecognitionSegmentation): Int =
        SEGMENTATION_MENU_ID_BASE + segmentation.ordinal

    private fun ocrModeMenuId(mode: OcrRecognitionMode): Int =
        OCR_MODE_MENU_ID_BASE + mode.ordinal

    private fun ocrEngineMenuId(engine: OcrEngineType): Int =
        OCR_ENGINE_MENU_ID_BASE + engine.ordinal

    private fun ocrModelMenuId(model: OcrModel): Int = OCR_MODEL_MENU_ID_BASE + model.ordinal

    private fun backgroundExperienceMenuId(mode: LivePatchBackgroundExperienceMode): Int =
        BACKGROUND_EXPERIENCE_MENU_ID_BASE + mode.ordinal

    private fun translationBackendMenuId(backend: TranslationBackend): Int =
        TRANSLATION_PROVIDER_MENU_ID_BASE + backend.ordinal

    private fun liveOcrTranslationEngineMenuId(engine: LiveOcrTranslationEngineType): Int =
        LIVE_ENGINE_MENU_ID_BASE + engine.ordinal

    private fun translationBackendLabel(backend: TranslationBackend): String =
        appContext.getString(
            when (backend) {
                TranslationBackend.LOCAL ->
                    R.string.active_screenshot_translation_provider_local_short
                TranslationBackend.NETWORK ->
                    R.string.active_screenshot_translation_provider_network_short
                TranslationBackend.SELF_HOSTED ->
                    R.string.active_screenshot_translation_provider_self_hosted_short
                TranslationBackend.SELF_HOSTED_V4 ->
                    R.string.active_screenshot_translation_provider_self_hosted_v4_short
                TranslationBackend.EMBEDDED_V4 ->
                    R.string.active_screenshot_translation_provider_embedded_v4_short
            }
        )

    private fun backgroundExperienceLabel(mode: LivePatchBackgroundExperienceMode): String =
        appContext.getString(
            when (mode) {
                LivePatchBackgroundExperienceMode.OFF ->
                    R.string.active_screenshot_background_experience_off
                LivePatchBackgroundExperienceMode.THEME_COLOR ->
                    R.string.active_screenshot_background_experience_theme
                LivePatchBackgroundExperienceMode.GAUSSIAN_BLUR ->
                    R.string.active_screenshot_background_experience_blur
            }
        )

    private fun ocrModeLabel(mode: OcrRecognitionMode): String = appContext.getString(
        when (mode) {
            OcrRecognitionMode.AUTO -> R.string.active_screenshot_ocr_mode_auto
            OcrRecognitionMode.CHINESE -> R.string.active_screenshot_ocr_mode_chinese
            OcrRecognitionMode.ENGLISH -> R.string.active_screenshot_ocr_mode_english
        }
    )

    private fun ocrEngineLabel(engine: OcrEngineType): String = appContext.getString(
        when (engine) {
            OcrEngineType.ML_KIT -> R.string.active_screenshot_ocr_engine_ml_kit
            OcrEngineType.PADDLE -> R.string.active_screenshot_ocr_engine_paddle
        }
    )

    private fun ocrModelLabel(model: OcrModel): String = appContext.getString(
        when (model) {
            OcrModel.CHINESE -> R.string.active_screenshot_ocr_model_chinese
            OcrModel.ENGLISH -> R.string.active_screenshot_ocr_model_english
        }
    )

    private fun ocrModelStateLabel(state: OcrModelState): String = appContext.getString(
        when (state) {
            OcrModelState.UNKNOWN -> R.string.active_screenshot_ocr_model_tap_to_check
            OcrModelState.CHECKING -> R.string.active_screenshot_ocr_model_checking
            OcrModelState.NOT_DOWNLOADED -> R.string.active_screenshot_ocr_model_tap_to_download
            OcrModelState.DOWNLOADING -> R.string.active_screenshot_ocr_model_downloading
            OcrModelState.READY -> R.string.active_screenshot_ocr_model_ready
            OcrModelState.FAILED -> R.string.active_screenshot_ocr_model_retry
        }
    )

    private fun updateCompactStatus(textRes: Int, showProgress: Boolean) {
        binding.tvCollapsedOverlayStatus.setText(textRes)
        binding.collapsedOverlayProgress.visibility =
            if (showProgress) View.VISIBLE else View.INVISIBLE
    }

    private fun updateCompactPerformance() {
        binding.tvCollapsedOverlayStatus.text = latestCompactPerformance
            ?: appContext.getString(R.string.active_screenshot_compact_translated)
        binding.collapsedOverlayProgress.visibility = View.INVISIBLE
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
        }
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
        const val FRAME_HEARTBEAT_CONTROL_ALPHA = 0.996f
        const val TAG = "ActiveCaptureOverlay"
        const val OVERLAY_PRESENTATION_MAX_ATTEMPTS = 3
        const val OVERLAY_PRESENTATION_RETRY_DELAY_MS = 120L
        const val EXPANDED_BOTTOM_MARGIN_DP = 44
        const val DEFAULT_MAXIMUM_OBSCURING_ALPHA = 0.8f
        const val CONTROL_PRESS_DURATION_MS = 90L
        const val CONTROL_MATERIALIZE_DURATION_MS = 150L
        const val TERMINAL_STATUS_DURATION_MS = 1_800L
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
        const val MENU_HEADER_EXPERIMENTAL_TRANSLATION = 201
        const val EXPERIENCE_MENU_GROUP = 3
        const val EXPERIENCE_MENU_DEFAULT = 301
        const val EXPERIENCE_MENU_ENHANCED = 302
        const val CAPTURE_SETTINGS_MENU_GROUP = 4
        const val CAPTURE_SETTINGS_MENU_SUMMARY = 400
        const val CAPTURE_SETTINGS_MENU_SCENE = 401
        const val CAPTURE_SETTINGS_MENU_FREQUENCY = 402
        const val CAPTURE_SETTINGS_MENU_BUFFER = 403
        const val CAPTURE_SETTINGS_MENU_SEGMENTATION = 404
        const val CAPTURE_SETTINGS_MENU_OPEN = 405
        const val CAPTURE_SETTINGS_MENU_OCR_MODE = 406
        const val CAPTURE_SETTINGS_MENU_OCR_MODELS = 407
        const val CAPTURE_SETTINGS_MENU_BACKGROUND_EXPERIENCE = 408
        const val CAPTURE_SETTINGS_MENU_TRANSLATION_PROVIDER = 409
        const val CAPTURE_SETTINGS_MENU_OCR_ENGINE = 410
        const val CAPTURE_SETTINGS_MENU_LIVE_ENGINE = 411
        const val SCENE_MENU_GROUP = 5
        const val SCENE_MENU_ID_BASE = 500
        const val FREQUENCY_MENU_GROUP = 6
        const val FREQUENCY_MENU_ID_BASE = 600
        const val BUFFER_MENU_GROUP = 7
        const val BUFFER_MENU_ID_BASE = 700
        const val SEGMENTATION_MENU_GROUP = 8
        const val SEGMENTATION_MENU_ID_BASE = 800
        const val OCR_MODE_MENU_GROUP = 9
        const val OCR_MODE_MENU_ID_BASE = 900
        const val OCR_MODEL_MENU_GROUP = 10
        const val OCR_MODEL_MENU_ID_BASE = 1000
        const val SMART_ASSIST_MENU_GROUP = 11
        const val SMART_ASSIST_MENU_ENABLED = 1101
        const val BACKGROUND_EXPERIENCE_MENU_GROUP = 12
        const val BACKGROUND_EXPERIENCE_MENU_ID_BASE = 1200
        const val EXPERIMENTAL_TRANSLATION_MENU_GROUP = 13
        const val EXPERIMENTAL_TRANSLATION_DISABLED = 1301
        const val EXPERIMENTAL_TRANSLATION_MARIAN = 1302
        const val EXPERIMENTAL_TRANSLATION_GEMMA = 1303
        const val EXPERIMENTAL_TRANSLATION_ACTION_GROUP = 14
        const val EXPERIMENTAL_TRANSLATION_MANAGE = 1401
        const val TRANSLATION_PROVIDER_MENU_GROUP = 15
        const val TRANSLATION_PROVIDER_MENU_ID_BASE = 1500
        const val OCR_ENGINE_MENU_GROUP = 16
        const val OCR_ENGINE_MENU_ID_BASE = 1600
        const val LIVE_ENGINE_MENU_GROUP = 17
        const val LIVE_ENGINE_MENU_ID_BASE = 1700
    }
}
