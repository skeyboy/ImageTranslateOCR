package com.example.imagetranslate.screenshot

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.imagetranslate.BuildConfig
import com.example.imagetranslate.R
import com.example.imagetranslate.ocr.OcrEngineSettings
import com.example.imagetranslate.ocr.OcrEngineType
import com.example.imagetranslate.ocr.OcrModel
import com.example.imagetranslate.ocr.OcrModelDownloadException
import com.example.imagetranslate.ocr.OcrModelState
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.ocr.PaddleNetworkSettings
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.example.imagetranslate.ui.ImageTranslateActivity
import com.example.imagetranslate.translate.TranslationBackend
import com.example.imagetranslate.translate.TranslationBackendSettings
import com.example.imagetranslate.translate.TranslationMode
import com.example.imagetranslate.translate.ExperimentalTranslationSettings
import com.example.imagetranslate.translate.SelfHostedRenderedCaptureUploader
import com.example.imagetranslate.translate.SemanticDebugCaptureEncoder
import com.example.imagetranslate.translate.SemanticRenderedCaptureAudit
import com.example.imagetranslate.translate.SemanticRenderedCaptureUploadPolicy
import com.example.imagetranslate.translate.SemanticTranslationTrace
import com.example.imagetranslate.translate.TranslationRequestArchiveStore
import com.example.experimentaltranslation.ExperimentalModelManagerActivity
import com.example.experimentaltranslation.ExperimentalModelRepository
import com.example.experimentaltranslation.ExperimentalModelState
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OneShotScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureRequested = AtomicBoolean(false)
    private val captureInProgress = AtomicBoolean(false)
    private val processingFrameCaptured = AtomicBoolean(false)
    private val presentationInProgress = AtomicBoolean(false)
    private val canRestoreLastResult = AtomicBoolean(false)
    private val emptyResultRetryCount = AtomicInteger(0)
    private val continuousTranslationEnabled = AtomicBoolean(false)
    private val sessionState = AtomicReference(ScreenCaptureSessionState.IDLE)
    private val projectionStopRequested = AtomicBoolean(false)
    private val projectionSessionId = AtomicLong(0L)
    private val frameSequence = AtomicLong(0L)
    private val lastFrameReceivedAtMs = AtomicLong(0L)
    private val frameHeartbeatEpoch = AtomicLong(0L)
    private val frameHeartbeatMisses = AtomicInteger(0)
    private val scrollFrameProbeEpoch = AtomicLong(0L)
    private val translationLayerPresented = AtomicBoolean(false)
    private val projectionStopReason = AtomicReference<String?>(null)
    private val virtualDisplayState = AtomicReference("IDLE")
    private val lastExternalDisplayAddedAtMs = AtomicLong(0L)
    private val interruptedByExternalRecorder = AtomicBoolean(false)
    private val rotationPermissionRestart = AtomicBoolean(false)
    private val rotationCaptureRecoveryPending = AtomicBoolean(false)
    private val captureGeneration = AtomicInteger(0)
    private val recognitionSucceededGeneration = AtomicInteger(-1)
    private val firstMotionAtMs = AtomicLong(0L)
    private val lastMotionAtMs = AtomicLong(0L)
    private val captureTriggeredAtMs = AtomicLong(0L)
    private val changeDetector = ScreenFrameChangeDetector()
    private val initialStabilityGate = InitialViewportStabilityGate()
    private val initialCapturePending = AtomicBoolean(false)
    private val initialStabilityGeneration = AtomicInteger(0)
    private val pendingInitialFrame = AtomicReference<PendingInitialFrame?>()
    private val pendingRenderedCapture = AtomicReference<PendingRenderedCapture?>()
    private val lastPresentedTranslationTraces =
        AtomicReference<List<SemanticTranslationTrace>>(emptyList())
    @Volatile
    private var projection: MediaProjection? = null
    @Volatile
    private var projectionCallback: MediaProjection.Callback? = null
    private var screenRecordingCallback: Consumer<Int>? = null
    private val screenRecordingState = AtomicReference("UNAVAILABLE")
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var timeoutJob: Job? = null
    private val processingJob = AtomicReference<Job?>(null)
    private var ocrPreparationJob: Job? = null
    private var activeCapturePlan: ScrollCapturePlan? = null
    @Volatile
    private var lastAcceptedCaptureSignature: ScreenFrameSignature? = null
    @Volatile
    private var latestObservedSignature: ScreenFrameSignature? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = 0
    private var lastSignatureSampleAt = Long.MIN_VALUE
    private val movementSettleFallback = Runnable {
        if (!continuousTranslationEnabled.get() || projection == null ||
            captureInProgress.get() || initialCapturePending.get() ||
            accessibilityScrollPending.get()
        ) {
            return@Runnable
        }
        when (changeDetector.forceActionAfterQuietPeriod(SystemClock.elapsedRealtime())) {
            ScreenFrameAction.NONE,
            ScreenFrameAction.MOVING,
            ScreenFrameAction.MOVING_UPDATE -> Unit
            ScreenFrameAction.RESTORE -> restoreUnchangedSettledViewport("quiet_period")
            ScreenFrameAction.CAPTURE -> {
                val capturePlan = changeDetector.consumeCapturePlan()
                val differenceRatio = changeDetector.consumeSettledDifferenceRatio()
                Log.i(
                    TAG,
                    "Settled viewport from quiet-period fallback: " +
                        "differenceRatio=${differenceRatio ?: -1f}"
                )
                requestScreenshot(capturePlan)
            }
        }
    }
    private val accessibilityScrollPending = AtomicBoolean(false)
    private val accessibilityScrollActive = AtomicBoolean(false)
    private val accessibilityScrollSettle = Runnable {
        if (!accessibilityScrollPending.compareAndSet(true, false) ||
            !continuousTranslationEnabled.get() || projection == null
        ) {
            return@Runnable
        }
        when (changeDetector.forceActionAfterQuietPeriod(SystemClock.elapsedRealtime())) {
            ScreenFrameAction.RESTORE -> restoreUnchangedSettledViewport("accessibility_scroll")
            ScreenFrameAction.CAPTURE -> {
                val capturePlan = changeDetector.consumeCapturePlan()
                val differenceRatio = changeDetector.consumeSettledDifferenceRatio()
                Log.i(
                    TAG,
                    "Accessibility scroll settled: " +
                        "shiftY=${capturePlan?.contentShiftY ?: 0}, " +
                        "confidence=${capturePlan?.confidence ?: 0f}, " +
                        "differenceRatio=${differenceRatio ?: -1f}"
                )
                requestScreenshot(capturePlan)
            }
            ScreenFrameAction.NONE,
            ScreenFrameAction.MOVING,
            ScreenFrameAction.MOVING_UPDATE -> {
                drainLatestImage()
                scheduleMovementSettleFallback()
            }
        }
    }
    private val translationMutex = Mutex()
    private var foregroundServiceTypes = 0
    @Volatile
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    @Volatile
    private var experienceMode = LiveOverlayExperienceMode.DEFAULT
    @Volatile
    private var captureSettings = LiveCaptureSettingsPolicy.default
    @Volatile
    private var ocrEngine = OcrEngineType.ML_KIT
    @Volatile
    private var liveOcrTranslationEngine = LiveOcrTranslationEngineType.LOCAL_PIPELINE
    @Volatile
    private var recognitionMode = OcrRecognitionMode.AUTO
    @Volatile
    private var smartAssistEnabled = LiveSmartAssistSettingsPolicy.DEFAULT_ENABLED
    @Volatile
    private var backgroundExperienceMode = LivePatchBackgroundExperiencePolicy.default
    @Volatile
    private var experimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED
    @Volatile
    private var translationBackend = TranslationBackend.LOCAL
    private val liveProcessorDelegate = lazy {
        BackgroundTranslatedImageProcessor(applicationContext, reuseResources = true).also {
            it.setExperimentalTranslationEngine(experimentalTranslationEngine)
        }
    }
    private val liveProcessor by liveProcessorDelegate
    private lateinit var overlayController: ActiveScreenCaptureOverlayController
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = runCatching { displayManager.getDisplay(displayId) }.getOrNull()
            if (sessionState.get() == ScreenCaptureSessionState.CAPTURING &&
                display?.name != CAPTURE_DISPLAY_NAME
            ) {
                lastExternalDisplayAddedAtMs.set(SystemClock.elapsedRealtime())
            }
            logDisplayTopology("added", displayId)
        }

        override fun onDisplayRemoved(displayId: Int) {
            logDisplayTopology("removed", displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            overlayController.onDisplayGeometryChanged(clearTranslations = false)
            captureHandler?.postDelayed(
                { reconfigureCaptureForDisplay() },
                DISPLAY_CHANGE_SETTLE_MS
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        experienceMode = resolvedExperienceMode()
        captureSettings = LiveCaptureSettingsPreferences.get(this)
        ocrEngine = OcrEngineSettings.get(this)
        liveOcrTranslationEngine = LiveOcrTranslationEngineSettings.get(this)
        recognitionMode = LiveOcrRecognitionPreferences.get(this)
        smartAssistEnabled = LiveSmartAssistPreferences.isEnabled(this)
        backgroundExperienceMode = LivePatchBackgroundExperiencePreferences.get(this)
        experimentalTranslationEngine = ExperimentalTranslationSettings.get(this)
        translationBackend = TranslationBackendSettings.get(this)
        overlayController = ActiveScreenCaptureOverlayController(
            this,
            experienceMode,
            captureSettings,
            ocrEngine,
            recognitionMode,
            smartAssistEnabled,
            backgroundExperienceMode,
            experimentalTranslationEngine,
            translationBackend,
            TranslationBackendSettings.isNetworkConfigured(this),
            TranslationBackendSettings.isSelfHostedConfigured(this),
            liveOcrTranslationEngine,
            PaddleNetworkSettings.isConfigured(this),
            object : ActiveScreenCaptureOverlayController.Listener {
                override fun onCapture() {
                    beginCaptureFromUser()
                }

                override fun onStop() {
                    stopCaptureSession()
                }

                override fun onCancelPreview() {
                    cancelContinuousTranslation()
                }

                override fun onTranslationModeChanged(mode: TranslationMode) {
                    translationMode = mode
                    if (continuousTranslationEnabled.get()) {
                        cancelActiveCapture(keepContinuousMode = true)
                        requestScreenshot()
                    }
                }

                override fun onTranslationBackendChanged(backend: TranslationBackend) {
                    applyTranslationBackend(backend)
                }

                override fun onLiveOcrTranslationEngineChanged(
                    engine: LiveOcrTranslationEngineType
                ) {
                    applyLiveOcrTranslationEngine(engine)
                }

                override fun onTranslationVisibilityChanged(visible: Boolean) {
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.translationVisibilityChanged(visible)
                    )
                    captureHandler?.post {
                        changeDetector.onTranslationRendered(SystemClock.elapsedRealtime())
                    }
                }

                override fun onExperienceModeRequested(mode: LiveOverlayExperienceMode) {
                    requestExperienceMode(mode)
                }

                override fun onCaptureSettingsChanged(settings: LiveCaptureSettings) {
                    applyCaptureSettings(settings)
                }

                override fun onOcrSettingsOpened() {
                    overlayController.setTranslationBackend(
                        TranslationBackendSettings.get(this@OneShotScreenCaptureService)
                    )
                    refreshOcrModelStates()
                }

                override fun onOcrEngineChanged(engine: OcrEngineType) {
                    ocrEngine = engine
                    OcrEngineSettings.set(this@OneShotScreenCaptureService, engine)
                    liveProcessor.clearLiveOverlaySnapshot()
                    if (continuousTranslationEnabled.get()) beginCaptureFromUser()
                }

                override fun onOcrRecognitionModeChanged(mode: OcrRecognitionMode) {
                    recognitionMode = mode
                    LiveOcrRecognitionPreferences.set(this@OneShotScreenCaptureService, mode)
                    liveProcessor.clearLiveOverlaySnapshot()
                    if (continuousTranslationEnabled.get()) beginCaptureFromUser()
                }

                override fun onOcrModelDownloadRequested(model: OcrModel) {
                    downloadOcrModel(model)
                }

                override fun onSmartAssistEnabledChanged(enabled: Boolean) {
                    applySmartAssistEnabled(enabled)
                }

                override fun onBackgroundExperienceModeChanged(
                    mode: LivePatchBackgroundExperienceMode
                ) {
                    applyBackgroundExperienceMode(mode)
                }

                override fun onExperimentalTranslationEngineChanged(
                    engine: ExperimentalTranslationEngine
                ) {
                    applyExperimentalTranslationEngine(engine)
                }

                override fun onExperimentalModelManagerRequested(
                    engine: ExperimentalTranslationEngine
                ) {
                    startActivity(
                        ExperimentalModelManagerActivity.intent(
                            this@OneShotScreenCaptureService,
                            engine.takeIf { it != ExperimentalTranslationEngine.DISABLED }
                                ?: ExperimentalTranslationEngine.MARIAN_INT8
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
        displayManager.registerDisplayListener(displayListener, mainHandler)
        registerScreenRecordingDiagnostics()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sessionState.get() == ScreenCaptureSessionState.STOPPING) {
            Log.w(TAG, "Ignoring ${intent?.action} while the capture service is stopping")
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> {
                startOverlayOnly()
                return START_NOT_STICKY
            }
            ACTION_TAKE_SCREENSHOT -> {
                beginCaptureFromUser()
                return START_NOT_STICKY
            }
            ACTION_STOP_SESSION -> {
                stopCaptureSession()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_OVERLAY_MODE -> {
                applyResolvedExperienceMode()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_PIPELINE_SETTINGS -> {
                refreshPipelineSettings()
                return START_NOT_STICKY
            }
            ACTION_ACCESSIBILITY_VIEW_SCROLLED -> {
                handleAccessibilityScroll(
                    sourcePackage = intent.getStringExtra(EXTRA_ACCESSIBILITY_SOURCE_PACKAGE),
                    sourceWindowId = intent.getIntExtra(EXTRA_ACCESSIBILITY_WINDOW_ID, -1)
                )
                return START_NOT_STICKY
            }
            ACTION_START_SESSION -> Unit
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (projection != null) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                buildSessionNotification(capturing = captureInProgress.get())
            )
            return START_NOT_STICKY
        }

        createNotificationChannels()
        startSessionForeground(
            capturing = false,
            requestedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            }
        )

        val resultData = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_DATA,
            Intent::class.java
        )
        if (intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) != Activity.RESULT_OK ||
            resultData == null
        ) {
            failSession(IllegalArgumentException("Missing screen capture permission data"))
            return START_NOT_STICKY
        }

        startCaptureSession(
            resultData = resultData,
            startImmediately = intent.getBooleanExtra(EXTRA_START_IMMEDIATELY, false)
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        sessionState.set(ScreenCaptureSessionState.STOPPING)
        unregisterScreenRecordingDiagnostics()
        displayManager.unregisterDisplayListener(displayListener)
        val activeProcessingJob = processingJob.getAndSet(null)
        releaseCaptureResources(
            stopProjection = true,
            dismissOverlay = true,
            removeForeground = true
        )
        serviceScope.cancel()
        if (liveProcessorDelegate.isInitialized()) {
            if (activeProcessingJob != null && !activeProcessingJob.isCompleted) {
                activeProcessingJob.invokeOnCompletion { liveProcessor.close() }
            } else {
                liveProcessor.close()
            }
        }
        super.onDestroy()
    }

    private fun registerScreenRecordingDiagnostics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val windowManager = getSystemService(WindowManager::class.java)
        val callback = Consumer<Int> { state ->
            val label = screenRecordingStateLabel(state)
            screenRecordingState.set(label)
            Log.i(
                TAG,
                "Screen recording visibility changed: state=$label, " +
                    "session=${projectionSessionId.get()}, generation=${captureGeneration.get()}"
            )
        }
        screenRecordingCallback = callback
        runCatching {
            val initialState = windowManager.addScreenRecordingCallback(mainExecutor, callback)
            val label = screenRecordingStateLabel(initialState)
            screenRecordingState.set(label)
            Log.i(TAG, "Screen recording visibility registered: initialState=$label")
        }.onFailure { error ->
            screenRecordingCallback = null
            screenRecordingState.set("UNAVAILABLE:${error.javaClass.simpleName}")
            Log.w(TAG, "Unable to register screen recording diagnostics", error)
        }
    }

    private fun unregisterScreenRecordingDiagnostics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val callback = screenRecordingCallback ?: return
        screenRecordingCallback = null
        runCatching {
            getSystemService(WindowManager::class.java).removeScreenRecordingCallback(callback)
        }.onFailure { error ->
            Log.w(TAG, "Unable to unregister screen recording diagnostics", error)
        }
    }

    private fun screenRecordingStateLabel(state: Int): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
        ) {
            "VISIBLE"
        } else {
            "NOT_VISIBLE"
        }

    private fun logDisplayTopology(change: String, displayId: Int) {
        Log.i(
            TAG,
            "Display topology changed: change=$change, displayId=$displayId, " +
                "topology=${displayTopologyDiagnostics()}"
        )
    }

    private fun displayTopologyDiagnostics(): JSONObject {
        val displays = runCatching { displayManager.displays.toList() }.getOrDefault(emptyList())
        val entries = JSONArray()
        displays.forEach { display ->
            entries.put(
                JSONObject()
                    .put("id", display.displayId)
                    .put("name", display.name)
                    .put("flags", display.flags)
                    .put("valid", display.isValid)
            )
        }
        return JSONObject()
            .put("accessibleDisplayCount", displays.size)
            .put("displays", entries)
    }

    private fun startOverlayOnly() {
        projectionStopRequested.set(false)
        sessionState.set(ScreenCaptureSessionState.OVERLAY_ONLY)
        createNotificationChannels()
        startSessionForeground(
            capturing = false,
            requestedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        overlayController.showReadyExpanded()
        serviceScope.launch {
            runCatching { liveProcessor.prepareForLiveTranslation(liveOcrTranslationEngine) }
                .onFailure { Log.w(TAG, "Unable to prewarm live translation models", it) }
        }
    }

    private fun startSessionForeground(
        capturing: Boolean,
        requestedType: Int,
        replaceTypes: Boolean = false
    ) {
        foregroundServiceTypes = if (replaceTypes) {
            requestedType
        } else {
            foregroundServiceTypes or requestedType
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildSessionNotification(capturing),
            foregroundServiceTypes
        )
    }

    private fun beginCaptureFromUser() {
        if (sessionState.get() == ScreenCaptureSessionState.STOPPING) return
        if (liveOcrTranslationEngine == LiveOcrTranslationEngineType.PADDLE_NETWORK) {
            if (projection == null) {
                sessionState.set(ScreenCaptureSessionState.REQUESTING_PERMISSION)
                overlayController.hideForCapture()
                ScreenCapturePermissionActivity.request(this)
            } else {
                continueCaptureFromUser()
            }
            return
        }
        if (projection == null) {
            sessionState.set(ScreenCaptureSessionState.REQUESTING_PERMISSION)
            prepareOcrModels(continueAfterPreparation = false)
            overlayController.hideForCapture()
            ScreenCapturePermissionActivity.request(this)
            return
        }
        prepareOcrModels(continueAfterPreparation = true)
    }

    private fun prepareOcrModels(continueAfterPreparation: Boolean) {
        if (ocrPreparationJob?.isActive == true) return
        overlayController.showPreparingOcrModels()
        ocrPreparationJob = serviceScope.launch {
            runCatching {
                liveProcessor.prepareOcrModels(recognitionMode, overlayController::updateOcrModelState)
            }.onSuccess {
                if (continueAfterPreparation) continueCaptureFromUser()
            }.onFailure { error ->
                Log.w(TAG, "Unable to prepare OCR models", error)
                if (projection == null) overlayController.showReadyExpanded()
                showOcrModelFailure("OCR 模型", error)
            }
        }
    }

    private fun continueCaptureFromUser() {
        if (projection == null) {
            overlayController.hideForCapture()
            ScreenCapturePermissionActivity.request(this)
            return
        }
        val wasContinuous = continuousTranslationEnabled.getAndSet(true)
        if (wasContinuous) cancelActiveCapture(keepContinuousMode = true)
        requestScreenshot()
    }

    private fun refreshOcrModelStates() {
        serviceScope.launch {
            OcrModel.entries.forEach {
                overlayController.updateOcrModelState(it, OcrModelState.CHECKING)
            }
            liveProcessor.ocrModelStates().forEach(overlayController::updateOcrModelState)
        }
    }

    private fun downloadOcrModel(model: OcrModel) {
        val label = ocrModelLabel(model)
        showToast(R.string.active_screenshot_ocr_model_download_started, label)
        serviceScope.launch {
            runCatching {
                liveProcessor.downloadOcrModel(model, overlayController::updateOcrModelState)
            }.onSuccess {
                showToast(R.string.active_screenshot_ocr_model_downloaded, label)
            }.onFailure { error ->
                Log.w(TAG, "Unable to download $model OCR model", error)
                showOcrModelFailure(label, error)
            }
        }
    }

    private fun ocrModelLabel(model: OcrModel): String = getString(
        when (model) {
            OcrModel.CHINESE -> R.string.active_screenshot_ocr_model_chinese
            OcrModel.ENGLISH -> R.string.active_screenshot_ocr_model_english
        }
    )

    private fun showToast(messageRes: Int, argument: String) {
        mainHandler.post {
            Toast.makeText(this, getString(messageRes, argument), Toast.LENGTH_LONG).show()
        }
    }

    private fun showOcrModelFailure(label: String, error: Throwable) {
        val messageRes = when ((error as? OcrModelDownloadException)?.statusCode) {
            ModuleInstallStatusCodes.INSUFFICIENT_STORAGE ->
                R.string.active_screenshot_ocr_model_download_failed_storage
            ModuleInstallStatusCodes.METERED_NETWORK_NOT_ALLOWED ->
                R.string.active_screenshot_ocr_model_download_failed_wifi
            ModuleInstallStatusCodes.UNKNOWN_MODULE,
            ModuleInstallStatusCodes.MODULE_NOT_FOUND,
            ModuleInstallStatusCodes.NOT_ALLOWED_MODULE ->
                R.string.active_screenshot_ocr_model_download_failed_unsupported
            else -> R.string.active_screenshot_ocr_model_download_failed
        }
        showToast(messageRes, label)
    }

    private fun startCaptureSession(resultData: Intent, startImmediately: Boolean) {
        val sessionId = projectionSessionId.incrementAndGet()
        runCatching {
            projectionStopRequested.set(false)
            projectionStopReason.set(null)
            lastExternalDisplayAddedAtMs.set(0L)
            interruptedByExternalRecorder.set(false)
            virtualDisplayState.set("CREATING")
            frameSequence.set(0L)
            lastFrameReceivedAtMs.set(SystemClock.elapsedRealtime())
            stopFrameHeartbeat()
            rotationPermissionRestart.set(false)
            rotationCaptureRecoveryPending.set(false)
            val thread = HandlerThread("screen-capture-session").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            val mediaProjection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, resultData)
                ?: error("Screen capture permission was not granted")
            projection = mediaProjection
            val callback = createProjectionCallback(sessionId, mediaProjection)
            projectionCallback = callback
            mediaProjection.registerCallback(callback, handler)
            sessionState.set(ScreenCaptureSessionState.CAPTURING)

            configureCapturePipeline(resolveDisplayMetrics(), handler)
            if (startImmediately) {
                continuousTranslationEnabled.set(true)
                awaitStableViewport("initial permission return")
            } else {
                overlayController.showReadyExpanded()
            }
        }.onFailure { error ->
            if (sessionState.get() == ScreenCaptureSessionState.PROJECTION_REVOKED) {
                Log.w(TAG, "Capture session was revoked while it was starting", error)
            } else if (error is SecurityException || error is IllegalStateException) {
                recoverFromProjectionFailure(sessionId, error)
            } else {
                failSession(error)
            }
        }
    }

    private fun createProjectionCallback(
        sessionId: Long,
        mediaProjection: MediaProjection
    ) = object : MediaProjection.Callback() {
        override fun onStop() {
            handleProjectionStopped(sessionId, mediaProjection)
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                sessionId != projectionSessionId.get() || projection !== mediaProjection
            ) {
                return
            }
            captureHandler?.postDelayed(
                { reconfigureCaptureForDisplay(width, height) },
                MEDIA_PROJECTION_RESIZE_SETTLE_MS
            )
        }
    }

    private fun handleProjectionStopped(sessionId: Long, mediaProjection: MediaProjection) {
        when (
            ScreenCaptureProjectionLifecycle.stopDecision(
                activeSessionId = projectionSessionId.get(),
                callbackSessionId = sessionId,
                projectionMatches = projection === mediaProjection,
                appRequestedStop = projectionStopRequested.get()
            )
        ) {
            ProjectionStopDecision.IGNORE_STALE -> {
                Log.i(TAG, "Ignoring MediaProjection.onStop from stale session=$sessionId")
                return
            }
            ProjectionStopDecision.APP_REQUESTED -> {
                Log.i(TAG, "MediaProjection stopped by the app for session=$sessionId")
                return
            }
            ProjectionStopDecision.RECOVER_EXTERNAL -> Unit
        }

        transitionToManualCaptureRecovery(
            sessionId = sessionId,
            mediaProjection = mediaProjection,
            stopProjection = false,
            reason = "MediaProjection was revoked externally"
        )
    }

    private fun recoverFromProjectionFailure(sessionId: Long, error: Throwable) {
        transitionToManualCaptureRecovery(
            sessionId = sessionId,
            mediaProjection = projection,
            stopProjection = true,
            reason = "MediaProjection became unusable",
            error = error
        )
    }

    private fun createVirtualDisplayCallback(
        sessionId: Long,
        mediaProjection: MediaProjection
    ) = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            if (sessionId != projectionSessionId.get() || projection !== mediaProjection) return
            virtualDisplayState.set("PAUSED")
            transitionToManualCaptureRecovery(
                sessionId = sessionId,
                mediaProjection = mediaProjection,
                stopProjection = true,
                reason = "VirtualDisplay was paused"
            )
        }

        override fun onResumed() {
            if (sessionId == projectionSessionId.get() && projection === mediaProjection) {
                virtualDisplayState.set("ACTIVE")
            }
        }

        override fun onStopped() {
            if (sessionId != projectionSessionId.get() || projection !== mediaProjection) return
            virtualDisplayState.set("STOPPED")
            transitionToManualCaptureRecovery(
                sessionId = sessionId,
                mediaProjection = mediaProjection,
                stopProjection = false,
                reason = "VirtualDisplay was stopped"
            )
        }
    }

    private fun transitionToManualCaptureRecovery(
        sessionId: Long,
        mediaProjection: MediaProjection?,
        stopProjection: Boolean,
        reason: String,
        error: Throwable? = null
    ) {
        if (sessionId != projectionSessionId.get() ||
            !sessionState.compareAndSet(
                ScreenCaptureSessionState.CAPTURING,
                ScreenCaptureSessionState.PROJECTION_REVOKED
            )
        ) {
            return
        }

        // Stop all automatic work before posting cleanup so no scroll/frame callback can enqueue
        // another capture while MediaProjection teardown is crossing threads.
        continuousTranslationEnabled.set(false)
        translationLayerPresented.set(false)
        scrollFrameProbeEpoch.incrementAndGet()
        stopFrameHeartbeat()
        projectionStopReason.set(reason)
        val externalDisplayAgeMs = lastExternalDisplayAddedAtMs.get().let { addedAtMs ->
            if (addedAtMs <= 0L) Long.MAX_VALUE else {
                (SystemClock.elapsedRealtime() - addedAtMs).coerceAtLeast(0L)
            }
        }
        val recorderInterrupted =
            reason == "MediaProjection was revoked externally" ||
                externalDisplayAgeMs <= EXTERNAL_DISPLAY_CONFLICT_WINDOW_MS
        interruptedByExternalRecorder.set(recorderInterrupted)
        val lifecycleDiagnostics = projectionLifecycleDiagnostics(reason).apply {
            put("interruptedByExternalRecorder", recorderInterrupted)
            put(
                "externalDisplayAddedAgeMs",
                if (externalDisplayAgeMs == Long.MAX_VALUE) JSONObject.NULL else externalDisplayAgeMs
            )
            if (error != null) {
                put("projectionFailureType", error.javaClass.simpleName)
                put("projectionFailureMessage", error.message ?: JSONObject.NULL)
            }
        }
        val presentedTraces = lastPresentedTranslationTraces.getAndSet(emptyList())
        if (presentedTraces.isNotEmpty()) {
            serviceScope.launch {
                runCatching {
                    TranslationRequestArchiveStore.recordProjectionLifecycle(
                        this@OneShotScreenCaptureService,
                        presentedTraces,
                        lifecycleDiagnostics
                    )
                }.onFailure { archiveError ->
                    Log.w(TAG, "Unable to archive projection lifecycle diagnostics", archiveError)
                }
            }
        }
        projection = null
        captureGeneration.incrementAndGet()
        Log.w(
            TAG,
            "Projection recovery diagnostics: $lifecycleDiagnostics"
        )
        if (error == null) {
            Log.w(TAG, "$reason for session=$sessionId")
        } else {
            Log.w(TAG, "$reason for session=$sessionId", error)
        }
        mainHandler.post {
            recoverFromProjectionLoss(
                sessionId = sessionId,
                mediaProjection = mediaProjection,
                stopProjection = stopProjection
            )
        }
    }

    private fun recoverFromProjectionLoss(
        sessionId: Long,
        mediaProjection: MediaProjection?,
        stopProjection: Boolean
    ) {
        if (sessionId != projectionSessionId.get() ||
            sessionState.get() != ScreenCaptureSessionState.PROJECTION_REVOKED
        ) {
            return
        }
        releaseProjectionResources(
            mediaProjection = mediaProjection,
            callback = projectionCallback,
            stopProjection = stopProjection
        )
        projectionCallback = null
        projectionStopRequested.set(false)
        if (liveProcessorDelegate.isInitialized()) {
            liveProcessor.clearLiveOverlaySnapshot()
        }
        startSessionForeground(
            capturing = false,
            requestedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
            replaceTypes = true
        )
        val recorderInterrupted = interruptedByExternalRecorder.get()
        overlayController.showProjectionRevoked(
            interruptedByRecorder = recorderInterrupted
        )
        Toast.makeText(
            applicationContext,
            if (recorderInterrupted) {
                R.string.active_screenshot_capture_interrupted_by_recorder_toast
            } else {
                R.string.active_screenshot_capture_interrupted_toast
            },
            Toast.LENGTH_LONG
        ).show()
    }

    private fun configureCapturePipeline(metrics: DisplayMetrics, handler: Handler) {
        val newReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            captureSettings.bufferMode.imageReaderMaxImages
        )
        newReader.setOnImageAvailableListener(::onImageAvailable, handler)
        val currentDisplay = virtualDisplay
        if (currentDisplay == null) {
            val mediaProjection = projection
                ?: error("MediaProjection ended before the capture surface was created")
            virtualDisplay = mediaProjection.createVirtualDisplay(
                CAPTURE_DISPLAY_NAME,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                createVirtualDisplayCallback(projectionSessionId.get(), mediaProjection),
                handler
            ) ?: error("MediaProjection ended before the capture surface was created")
            virtualDisplayState.set("ACTIVE")
        } else {
            val oldReader = imageReader
            currentDisplay.surface = null
            currentDisplay.resize(
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi
            )
            currentDisplay.surface = newReader.surface
            oldReader?.setOnImageAvailableListener(null, null)
            if (oldReader != null) {
                handler.postDelayed(
                    { runCatching(oldReader::close) },
                    CAPTURE_SURFACE_RETIRE_DELAY_MS
                )
            }
        }
        val oldReader = imageReader.takeIf { currentDisplay == null }
        imageReader = newReader
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDensityDpi = metrics.densityDpi
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
    }

    private fun reconfigureCaptureForDisplay(
        capturedWidth: Int? = null,
        capturedHeight: Int? = null
    ) {
        val handler = captureHandler ?: return
        if (projection == null) return
        val metrics = resolveDisplayMetrics().apply {
            if (capturedWidth != null && capturedHeight != null &&
                capturedWidth > 0 && capturedHeight > 0
            ) {
                widthPixels = capturedWidth
                heightPixels = capturedHeight
            }
        }
        if (metrics.widthPixels == captureWidth && metrics.heightPixels == captureHeight &&
            metrics.densityDpi == captureDensityDpi
        ) {
            overlayController.onDisplayGeometryChanged(clearTranslations = false)
            return
        }
        val resumeContinuousCapture = continuousTranslationEnabled.get()
        cancelActiveCapture(keepContinuousMode = resumeContinuousCapture)
        runCatching { configureCapturePipeline(metrics, handler) }
            .onSuccess {
                overlayController.onDisplayGeometryChanged(clearTranslations = true)
                liveProcessor.clearLiveOverlaySnapshot()
                Log.i(
                    TAG,
                    "Capture surface resized to ${metrics.widthPixels}x${metrics.heightPixels}"
                )
                if (resumeContinuousCapture) {
                    rotationCaptureRecoveryPending.set(true)
                    awaitStableViewport("display rotation")
                    handler.postDelayed(
                        {
                            if (initialCapturePending.get() && projection != null) {
                                restartProjectionPermissionAfterRotation()
                            }
                        },
                        ROTATION_FRAME_RECOVERY_TIMEOUT_MS
                    )
                } else {
                    overlayController.showReadyExpanded()
                }
            }
            .onFailure(::failSession)
    }

    private fun awaitStableViewport(reason: String) {
        val handler = captureHandler ?: return
        if (Looper.myLooper() == handler.looper) {
            awaitStableViewportOnCaptureThread(reason, handler)
        } else {
            handler.post {
                if (handler === captureHandler) {
                    awaitStableViewportOnCaptureThread(reason, handler)
                }
            }
        }
    }

    private fun awaitStableViewportOnCaptureThread(reason: String, handler: Handler) {
        if (!continuousTranslationEnabled.get() || projection == null) return
        val stabilityGeneration = initialStabilityGeneration.incrementAndGet()
        initialCapturePending.set(true)
        pendingInitialFrame.getAndSet(null)?.image?.close()
        latestObservedSignature = null
        initialStabilityGate.reset(SystemClock.elapsedRealtime())
        changeDetector.reset()
        overlayController.hideForCapture()
        Log.i(TAG, "Waiting for a stable viewport: $reason")
        drainLatestImage()
        CAPTURE_FRAME_PULSE_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed(
                {
                    if (stabilityGeneration == initialStabilityGeneration.get() &&
                        initialCapturePending.get() && projection != null
                    ) {
                        overlayController.pulseTransparentCaptureSurface()
                    }
                },
                delayMs
            )
        }
        handler.postDelayed(
            {
                processPendingInitialFrame(
                    reason = "stability deadline",
                    expectedGeneration = stabilityGeneration
                )
            },
            INITIAL_STABILITY_MAX_WAIT_MS
        )
    }

    private fun restartProjectionPermissionAfterRotation() {
        if (!rotationPermissionRestart.compareAndSet(false, true)) return
        rotationCaptureRecoveryPending.set(false)
        Log.w(TAG, "Capture stream did not stabilize after rotation; requiring renewed permission")
        projectionStopRequested.set(true)
        sessionState.set(ScreenCaptureSessionState.REQUESTING_PERMISSION)
        val mediaProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        releaseProjectionResources(mediaProjection, callback, stopProjection = true)
        overlayController.onDisplayGeometryChanged(clearTranslations = true)
        overlayController.hideForCapture()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        mainHandler.post { ScreenCapturePermissionActivity.request(this) }
    }

    private fun startFrameHeartbeat() {
        val handler = captureHandler ?: return
        val epoch = frameHeartbeatEpoch.incrementAndGet()
        frameHeartbeatMisses.set(0)
        handler.postDelayed(
            { runFrameHeartbeat(epoch, projectionSessionId.get()) },
            FRAME_HEARTBEAT_INTERVAL_MS
        )
    }

    private fun stopFrameHeartbeat() {
        frameHeartbeatEpoch.incrementAndGet()
        frameHeartbeatMisses.set(0)
    }

    private fun runFrameHeartbeat(epoch: Long, sessionId: Long) {
        val handler = captureHandler ?: return
        if (epoch != frameHeartbeatEpoch.get() ||
            sessionId != projectionSessionId.get() ||
            sessionState.get() != ScreenCaptureSessionState.CAPTURING ||
            projection == null ||
            !continuousTranslationEnabled.get() ||
            !translationLayerPresented.get()
        ) {
            return
        }
        val sequenceBeforePulse = frameSequence.get()
        overlayController.pulseFrameHeartbeat { pulsed ->
            val activeHandler = captureHandler ?: return@pulseFrameHeartbeat
            if (!pulsed || epoch != frameHeartbeatEpoch.get()) return@pulseFrameHeartbeat
            activeHandler.postDelayed(
                {
                    if (epoch != frameHeartbeatEpoch.get() ||
                        sessionId != projectionSessionId.get() ||
                        sessionState.get() != ScreenCaptureSessionState.CAPTURING
                    ) {
                        return@postDelayed
                    }
                    val heartbeatOutcome = CaptureFrameHeartbeatPolicy.evaluate(
                        frameAdvanced = frameSequence.get() > sequenceBeforePulse,
                        previousMissedCount = frameHeartbeatMisses.get(),
                        maximumMisses = MAX_FRAME_HEARTBEAT_MISSES
                    )
                    frameHeartbeatMisses.set(heartbeatOutcome.missedCount)
                    if (!heartbeatOutcome.streamInvalid &&
                        heartbeatOutcome.missedCount > 0
                    ) {
                        // A stale translation surface is visually harmful as soon as the page
                        // moves. Hide it on the first miss, while still requiring the second miss
                        // before declaring the projection unusable.
                        if (heartbeatOutcome.missedCount == 1) {
                            canRestoreLastResult.set(false)
                            if (liveProcessorDelegate.isInitialized()) {
                                liveProcessor.clearLiveOverlaySnapshot()
                            }
                            overlayController.clearTranslations()
                            Log.i(
                                METRICS_TAG,
                                JSONObject()
                                    .put("schema", 1)
                                    .put("event", "heartbeat_translation_guard_cleared")
                                    .put("session_id", sessionId)
                                    .put("generation", captureGeneration.get())
                                    .put("last_frame_age_ms", lastFrameAgeMs())
                                    .toString()
                            )
                        }
                        Log.w(
                            TAG,
                            "Capture frame heartbeat missed ${heartbeatOutcome.missedCount}/" +
                                "$MAX_FRAME_HEARTBEAT_MISSES; lastFrameAgeMs=${lastFrameAgeMs()}"
                        )
                    }
                    if (heartbeatOutcome.streamInvalid) {
                        recoverFromProjectionFailure(
                            sessionId,
                            CaptureFrameUnavailableException(
                                "Capture frame stream stopped responding to overlay heartbeat"
                            )
                        )
                        return@postDelayed
                    }
                    activeHandler.postDelayed(
                        { runFrameHeartbeat(epoch, sessionId) },
                        FRAME_HEARTBEAT_INTERVAL_MS
                    )
                },
                FRAME_HEARTBEAT_RESPONSE_TIMEOUT_MS
            )
        }
    }

    private fun lastFrameAgeMs(): Long {
        val lastFrameAt = lastFrameReceivedAtMs.get()
        return if (lastFrameAt <= 0L) -1L else {
            (SystemClock.elapsedRealtime() - lastFrameAt).coerceAtLeast(0L)
        }
    }

    private fun renderFailuresJson(
        failures: List<LiveRenderFailureDiagnostic>
    ) = JSONArray().apply {
        failures.forEach { failure ->
            put(
                JSONObject()
                    .put("groupId", failure.groupId ?: JSONObject.NULL)
                    .put("reason", failure.reason)
                    .put("layoutShape", failure.layoutShape ?: JSONObject.NULL)
                    .put("sourceLineHeightPx", failure.sourceLineHeightPx.toDouble())
                    .put("preferredTextSizePx", failure.preferredTextSizePx.toDouble())
                    .put(
                        "minimumAttemptedTextSizePx",
                        failure.minimumAttemptedTextSizePx.toDouble()
                    )
                    .put("lastAttemptedTextSizePx", failure.lastAttemptedTextSizePx.toDouble())
                    .put("maximumLines", failure.maximumLines)
                    .put("requireAllSlots", failure.requireAllSlots)
                    .put("allowMore", failure.allowMore)
                    .put(
                        "attemptedLineSpacingMultipliers",
                        JSONArray().apply {
                            failure.attemptedLineSpacingMultipliers.forEach { put(it.toDouble()) }
                        }
                    )
                    .put("renderSlots", layoutBoundsJson(failure.renderSlots))
                    .put("sourceCoverSlots", layoutBoundsJson(failure.sourceCoverSlots))
            )
        }
    }

    private fun layoutBoundsJson(bounds: List<android.graphics.Rect>) = JSONArray().apply {
        bounds.forEach { item ->
            put(
                JSONObject()
                    .put("left", item.left)
                    .put("top", item.top)
                    .put("right", item.right)
                    .put("bottom", item.bottom)
            )
        }
    }

    private fun projectionLifecycleDiagnostics(reason: String?): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("projectionStopReason", reason ?: JSONObject.NULL)
        .put("lastFrameAgeMs", lastFrameAgeMs())
        .put("virtualDisplayState", virtualDisplayState.get())
        .put("screenRecordingState", screenRecordingState.get())
        .put("displayTopology", displayTopologyDiagnostics())
        .put("overlayInstanceId", overlayController.translationOverlayInstanceId())
        .put(
            "controlLayerAboveTranslation",
            overlayController.isControlLayerAboveTranslation()
        )
        .put(
            "attachedFullscreenLayerCount",
            overlayController.attachedFullscreenTranslationLayerCount()
        )
        .put("sessionId", projectionSessionId.get())
        .put("generation", captureGeneration.get())
        .put("recordedAtElapsedRealtimeMs", SystemClock.elapsedRealtime())

    private fun onImageAvailable(reader: ImageReader) {
        frameSequence.incrementAndGet()
        lastFrameReceivedAtMs.set(SystemClock.elapsedRealtime())
        val renderedCapture = pendingRenderedCapture.get()
        if (renderedCapture != null) {
            val image = reader.acquireLatestImage() ?: return
            if (!pendingRenderedCapture.compareAndSet(renderedCapture, null)) {
                image.close()
                return
            }
            if (renderedCapture.generation != captureGeneration.get() ||
                !continuousTranslationEnabled.get()
            ) {
                image.close()
                return
            }
            processRenderedCapture(image, renderedCapture)
            return
        }
        if (LiveCaptureTimingPolicy.shouldHoldImageQueue(
                captureInProgress = captureInProgress.get(),
                captureRequested = captureRequested.get(),
                processingFrameCaptured = processingFrameCaptured.get(),
                presentationInProgress = presentationInProgress.get()
            )
        ) {
            return
        }
        val image = reader.acquireLatestImage() ?: return
        if (captureRequested.compareAndSet(true, false)) {
            val nowMs = SystemClock.elapsedRealtime()
            val signature = runCatching {
                sampleFrameSignature(image, maskTranslationPatches = false)
            }.getOrNull()
            if (signature != null) {
                latestObservedSignature = signature
                changeDetector.onCaptureStarted(signature, nowMs)
                val accepted = lastAcceptedCaptureSignature
                if (accepted != null && canRestoreLastResult.get() &&
                    ScreenFrameSignaturePolicy.isDuplicateCapture(accepted, signature)
                ) {
                    image.close()
                    finishDuplicateCapture(captureGeneration.get())
                    return
                }
            }
            processingFrameCaptured.set(true)
            processCapturedImage(
                image = image,
                generation = captureGeneration.get(),
                capturePlan = activeCapturePlan,
                captureSignature = signature
            )
            return
        }
        if (!continuousTranslationEnabled.get() ||
            (captureInProgress.get() && !processingFrameCaptured.get())
        ) {
            image.close()
            return
        }
        if (accessibilityScrollPending.get()) {
            val nowMs = SystemClock.elapsedRealtime()
            runCatching {
                sampleFrameSignature(image, maskTranslationPatches = false)
            }.onSuccess { signature ->
                latestObservedSignature = signature
                changeDetector.observeExternalScrollFrame(signature, nowMs)
            }.onFailure { error ->
                Log.w(TAG, "Unable to sample accessibility scroll frame", error)
            }
            image.close()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (lastSignatureSampleAt != Long.MIN_VALUE &&
            nowMs - lastSignatureSampleAt < captureSettings.frequency.frameSampleIntervalMs
        ) {
            image.close()
            return
        }
        lastSignatureSampleAt = nowMs
        val signature = try {
            sampleFrameSignature(
                image,
                maskTranslationPatches = changeDetector.shouldMaskTranslationPatches(nowMs)
            )
        } catch (error: Exception) {
            image.close()
            Log.w(TAG, "Unable to sample screen frame", error)
            return
        }
        latestObservedSignature = signature
        if (initialCapturePending.get()) {
            pendingInitialFrame.getAndSet(PendingInitialFrame(image, signature))
                ?.image
                ?.close()
            if (initialStabilityGate.onFrame(signature, nowMs)) {
                processPendingInitialFrame("stable viewport")
            }
            return
        }
        image.close()
        when (changeDetector.onFrame(signature, nowMs)) {
            ScreenFrameAction.NONE -> Unit
            ScreenFrameAction.MOVING -> {
                if (experienceMode == LiveOverlayExperienceMode.ENHANCED) return
                firstMotionAtMs.compareAndSet(0L, nowMs)
                lastMotionAtMs.set(nowMs)
                discardStaleCaptureForMovement()
                overlayController.showWaitingForStable { translationLayerCleared ->
                    val hiddenAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.hiddenForMovement(
                            generation = captureGeneration.get(),
                            motionToHiddenMs = (hiddenAtMs - nowMs).coerceAtLeast(0L),
                            translationLayerCleared = translationLayerCleared
                        )
                    )
                }
                scheduleMovementSettleFallback()
            }
            ScreenFrameAction.MOVING_UPDATE -> {
                if (experienceMode == LiveOverlayExperienceMode.ENHANCED) return
                lastMotionAtMs.set(nowMs)
                scheduleMovementSettleFallback()
            }
            ScreenFrameAction.RESTORE -> restoreUnchangedSettledViewport("stable_frames")
            ScreenFrameAction.CAPTURE -> {
                captureHandler?.removeCallbacks(movementSettleFallback)
                val capturePlan = changeDetector.consumeCapturePlan()
                val differenceRatio = changeDetector.consumeSettledDifferenceRatio()
                Log.i(
                    TAG,
                    "Settled viewport: shiftY=${capturePlan?.contentShiftY ?: 0}, " +
                        "confidence=${capturePlan?.confidence ?: 0f}, " +
                        "overlap=${capturePlan?.overlapRatio ?: 0f}, " +
                        "error=${capturePlan?.registrationError ?: 0f}, " +
                        "consensus=${capturePlan?.consensusRatio ?: 0f}, " +
                        "differenceRatio=${differenceRatio ?: -1f}"
                )
                requestScreenshot(capturePlan)
            }
        }
    }

    private fun scheduleMovementSettleFallback() {
        val handler = captureHandler ?: return
        handler.removeCallbacks(movementSettleFallback)
        handler.postDelayed(movementSettleFallback, MOVEMENT_SETTLE_FALLBACK_MS)
    }

    private fun handleAccessibilityScroll(sourcePackage: String?, sourceWindowId: Int) {
        val handler = captureHandler ?: return
        val eventAtMs = SystemClock.elapsedRealtime()
        handler.post {
            if (handler !== captureHandler) return@post
            handleAccessibilityScrollOnCaptureThread(
                handler = handler,
                eventAtMs = eventAtMs,
                sourcePackage = sourcePackage,
                sourceWindowId = sourceWindowId
            )
        }
    }

    private fun handleAccessibilityScrollOnCaptureThread(
        handler: Handler,
        eventAtMs: Long,
        sourcePackage: String?,
        sourceWindowId: Int
    ) {
        if (!ProjectionScrollGuardPolicy.shouldForwardScroll(sourcePackage, packageName) ||
            !continuousTranslationEnabled.get() || projection == null
        ) {
            return
        }
        lastMotionAtMs.set(eventAtMs)
        if (accessibilityScrollPending.compareAndSet(false, true)) {
            discardStaleCaptureForMovement()
            if (accessibilityScrollActive.compareAndSet(false, true)) {
                firstMotionAtMs.compareAndSet(0L, eventAtMs)
                overlayController.showWaitingForStable { translationLayerCleared ->
                    val hiddenAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.hiddenForMovement(
                            generation = captureGeneration.get(),
                            motionToHiddenMs = (hiddenAtMs - eventAtMs).coerceAtLeast(0L),
                            translationLayerCleared = translationLayerCleared
                        )
                    )
                }
            }
            changeDetector.beginExternalScroll(eventAtMs)
            startScrollFrameProbe(
                handler = handler,
                sourcePackage = sourcePackage.orEmpty(),
                sourceWindowId = sourceWindowId
            )
        }
        handler.removeCallbacks(accessibilityScrollSettle)
        handler.postDelayed(accessibilityScrollSettle, ACCESSIBILITY_SCROLL_SETTLE_MS)
    }

    private fun startScrollFrameProbe(
        handler: Handler,
        sourcePackage: String,
        sourceWindowId: Int
    ) {
        val epoch = scrollFrameProbeEpoch.incrementAndGet()
        val sessionId = projectionSessionId.get()
        val sequenceBeforePulse = frameSequence.get()
        overlayController.pulseFrameHeartbeat { pulsed ->
            val activeHandler = captureHandler ?: return@pulseFrameHeartbeat
            if (handler !== activeHandler || epoch != scrollFrameProbeEpoch.get()) {
                return@pulseFrameHeartbeat
            }
            if (!pulsed) {
                Log.w(TAG, "Unable to pulse the capture surface after accessibility scroll")
            }
            activeHandler.postDelayed(
                {
                    if (epoch != scrollFrameProbeEpoch.get() ||
                        sessionId != projectionSessionId.get() ||
                        sessionState.get() != ScreenCaptureSessionState.CAPTURING ||
                        !continuousTranslationEnabled.get()
                    ) {
                        return@postDelayed
                    }
                    val frameAdvanced = frameSequence.get() > sequenceBeforePulse
                    Log.i(
                        METRICS_TAG,
                        JSONObject()
                            .put("schema", 1)
                            .put("event", "accessibility_scroll_frame_probe")
                            .put("session_id", sessionId)
                            .put("generation", captureGeneration.get())
                            .put("source_package", sourcePackage)
                            .put("source_window_id", sourceWindowId)
                            .put("frame_advanced", frameAdvanced)
                            .put("last_frame_age_ms", lastFrameAgeMs())
                            .toString()
                    )
                    if (!frameAdvanced) {
                        recoverFromProjectionFailure(
                            sessionId,
                            CaptureFrameUnavailableException(
                                "Capture frame stream did not respond after accessibility scroll"
                            )
                        )
                    }
                },
                SCROLL_FRAME_PROBE_TIMEOUT_MS
            )
        }
    }

    private fun restoreUnchangedSettledViewport(trigger: String) {
        captureHandler?.removeCallbacks(movementSettleFallback)
        val differenceRatio = changeDetector.consumeSettledDifferenceRatio()
        if (!canRestoreLastResult.get()) {
            Log.i(TAG, "No previous translation to restore; capturing the settled viewport")
            requestScreenshot()
            return
        }
        overlayController.restoreAfterSkippedCapture()
        changeDetector.onTranslationRendered(SystemClock.elapsedRealtime())
        resetInteractionTiming()
        Log.i(
            TAG,
            "Restored unchanged settled viewport: trigger=$trigger, " +
                "differenceRatio=${differenceRatio ?: -1f}"
        )
        Log.i(
            METRICS_TAG,
            "{\"schema\":4,\"event\":\"settled_viewport_restored\"," +
                "\"generation\":${captureGeneration.get()},\"trigger\":\"$trigger\"," +
                "\"difference_ratio\":${differenceRatio ?: -1f}}"
        )
    }

    private fun processInitialStableFrame(image: Image, signature: ScreenFrameSignature) {
        if (!canPresentTranslation()) {
            image.close()
            pauseForUnavailableEnhancedExperience()
            return
        }
        if (!captureInProgress.compareAndSet(false, true)) {
            image.close()
            return
        }
        activeCapturePlan = null
        emptyResultRetryCount.set(0)
        processingFrameCaptured.set(true)
        val generation = captureGeneration.incrementAndGet()
        captureTriggeredAtMs.set(SystemClock.elapsedRealtime())
        changeDetector.onCaptureStarted(signature, SystemClock.elapsedRealtime())
        overlayController.beginCaptureGeneration(generation)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = true)
        )
        processCapturedImage(
            image = image,
            generation = generation,
            capturePlan = null,
            captureSignature = signature
        )
    }

    private fun processPendingInitialFrame(
        reason: String,
        expectedGeneration: Int = initialStabilityGeneration.get()
    ) {
        if (expectedGeneration != initialStabilityGeneration.get()) return
        val pending = pendingInitialFrame.getAndSet(null) ?: return
        if (!initialCapturePending.compareAndSet(true, false)) {
            pending.image.close()
            return
        }
        Log.i(TAG, "Initial capture acquired from $reason")
        processInitialStableFrame(pending.image, pending.signature)
    }

    private fun processCapturedImage(
        image: Image,
        generation: Int,
        capturePlan: ScrollCapturePlan?,
        captureSignature: ScreenFrameSignature?
    ) {
        val processingStartedAt = SystemClock.elapsedRealtime()
        val imageReleased = AtomicBoolean(false)
        val releaseImage = {
            if (imageReleased.compareAndSet(false, true)) runCatching(image::close)
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var sourceBitmap: Bitmap? = null
            var translatedResult: BackgroundTranslatedOverlayResult? = null
            try {
                if (generation != captureGeneration.get()) {
                    releaseImage()
                    return@launch
                }
                sourceBitmap = try {
                    imageToBitmap(image)
                } finally {
                    releaseImage()
                }
                val bitmap = sourceBitmap
                val obscuredBoundsAtCapture =
                    overlayController.visibleOcrOcclusionBounds() +
                        ScreenTranslationAccessibilityService.visibleBrowserToolbarBounds()
                if (!hasVisiblePixels(bitmap)) {
                    bitmap.recycle()
                    sourceBitmap = null
                    processingFrameCaptured.set(false)
                    captureRequested.set(true)
                    drainLatestImage()
                    return@launch
                }
                timeoutJob?.cancel()
                timeoutJob = null
                val activeMode = translationMode
                val activeRecognitionMode = recognitionMode
                val activeLiveOcrTranslationEngine = liveOcrTranslationEngine
                val activeTranslationBackend = translationBackend
                val activeExperienceMode = experienceMode
                val activeSmartAssistEnabled = smartAssistEnabled
                val requestedExperienceMode =
                    LiveOverlayExperiencePreferences.requestedMode(
                        this@OneShotScreenCaptureService
                    )
                if (!LiveOverlayExperiencePolicy.canPresentTranslation(
                        requested = requestedExperienceMode,
                        resolved = activeExperienceMode
                    )
                ) {
                    pauseForUnavailableEnhancedExperience()
                    return@launch
                }
                val activeBackgroundExperienceMode =
                    LiveOverlayExperiencePolicy.effectiveBackgroundExperienceMode(
                        requested = requestedExperienceMode,
                        resolved = activeExperienceMode,
                        selected = backgroundExperienceMode
                    )
                val translationTimeoutMs = LiveCaptureTimingPolicy.translationTimeoutMs(
                    backend = activeTranslationBackend,
                    engine = activeLiveOcrTranslationEngine
                )
                Log.i(
                    TAG,
                    "Overlay translation started: generation=$generation, " +
                        "backend=${activeTranslationBackend.name}, " +
                        "engine=${activeLiveOcrTranslationEngine.name}, " +
                        "timeoutMs=$translationTimeoutMs"
                )
                val result = withTimeout(translationTimeoutMs) {
                    translationMutex.withLock {
                        if (generation != captureGeneration.get()) {
                            throw CancellationException("Stale live OCR frame")
                        }
                        overlayController.showProcessing {
                            LiveCaptureTimingPolicy.shouldPresentResult(
                                resultGeneration = generation,
                                currentGeneration = captureGeneration.get(),
                                continuousTranslationEnabled = continuousTranslationEnabled.get()
                            )
                        }
                        canRestoreLastResult.set(false)
                        liveProcessor.translateForOverlay(
                            bitmap = bitmap,
                            mode = activeMode,
                            generation = generation,
                            engineType = activeLiveOcrTranslationEngine,
                            recognitionMode = activeRecognitionMode,
                            capturePlan = capturePlan,
                            overlayAlpha = LiveOverlayExperiencePolicy.translationWindowAlpha(
                                activeExperienceMode,
                                ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA
                            ),
                            segmentation = captureSettings.segmentation,
                            executionProfile =
                                LivePatchBackgroundExperiencePolicy.executionProfile(
                                    activeBackgroundExperienceMode
                                ),
                            smartAssistEnabled = activeSmartAssistEnabled,
                            obscuredBoundsAtCapture = obscuredBoundsAtCapture,
                            onRecognitionSucceeded = { recognizedCount ->
                                if (generation == captureGeneration.get()) {
                                    recognitionSucceededGeneration.set(generation)
                                    Log.i(
                                        TAG,
                                        "OCR recognition succeeded: generation=$generation, " +
                                            "recognized=$recognizedCount"
                                    )
                                    if (recognizedCount > 0) {
                                        overlayController.showRecognitionSucceeded()
                                    } else {
                                        overlayController.showRecognitionFailed()
                                    }
                                }
                            }
                        )
                    }
                }
                translatedResult = result
                if (isActive && generation == captureGeneration.get()) {
                    val latestSignature = latestObservedSignature
                    if (captureSignature != null && latestSignature != null &&
                        ScreenFrameSignaturePolicy.hasViewportChanged(
                            captureSignature,
                            latestSignature
                        )
                    ) {
                        Log.i(TAG, "Discarded OCR result because the viewport changed")
                        result.patches.recyclePatchBitmaps()
                        translatedResult = null
                        discardStaleCaptureForMovement()
                        awaitStableViewport("result invalidation")
                        return@launch
                    }
                    if (LiveCaptureTimingPolicy.shouldRetryEmptyResult(
                            patchCount = result.patches.size,
                            recognizedCount = result.recognizedCount,
                            failedCount = result.failedCount,
                            retryCount = emptyResultRetryCount.get()
                        ) &&
                        emptyResultRetryCount.compareAndSet(0, 1)
                    ) {
                        Log.i(TAG, "Empty live OCR result; retrying the settled viewport once")
                        translatedResult = null
                        scheduleEmptyResultRetry(generation)
                        return@launch
                    }
                    val completedAt = SystemClock.elapsedRealtime()
                    val totalMs = completedAt - processingStartedAt
                    Log.i(
                        TAG,
                        "Overlay translation completed: totalMs=$totalMs, " +
                            "differential=${result.differentialApplied}, " +
                            "shiftY=${capturePlan?.contentShiftY ?: 0}, " +
                            "reused=${result.reusedRegionCount}, " +
                            "recognized=${result.recognizedCount}, " +
                            "rawRecognized=${result.rawRecognizedCount}, " +
                            "edgeFiltered=${result.edgeFilteredCount}, " +
                            "edgeRecovered=${result.edgeRecoveredCount}, " +
                            "continuations=${result.continuationCount}, " +
                            "patches=${result.patches.size}, " +
                            "ocrTranslateMs=${result.recognitionAndTranslationMs}, " +
                            "renderMs=${result.renderingMs}, " +
                            "smartAssist=${result.smartAssistApplied}, " +
                            "assistScene=${result.smartAssistScene}, " +
                            "assistGroups=${result.smartAssistGroupCount}, " +
                            "assistProtected=${result.smartAssistProtectedCount}, " +
                            "assistLayout=${result.smartAssistLayoutHintCount}, " +
                            "assistMs=${result.smartAssistMs}, " +
                            "trackCacheHits=${result.renderedTrackCacheHitCount}, " +
                            "trackCacheMisses=${result.renderedTrackCacheMissCount}, " +
                            "themePatches=${result.themeSurfacePatchCount}, " +
                            "blurPatches=${result.blurTintPatchCount}"
                    )
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.completion(
                            generation = generation,
                            totalMs = totalMs,
                            capturePlan = capturePlan,
                            metrics = result.metrics(),
                            interaction = interactionTiming(completedAt)
                        )
                    )
                    finishScreenshot(result, generation, captureSignature)
                    translatedResult = null
                }
            } catch (error: TimeoutCancellationException) {
                failScreenshot(error, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failScreenshot(error, generation)
            } finally {
                translatedResult?.patches?.recyclePatchBitmaps()
                sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        }
        processingJob.getAndSet(job)?.cancel()
        job.invokeOnCompletion {
            releaseImage()
            processingJob.compareAndSet(job, null)
        }
        job.start()
    }

    private fun requestScreenshot(capturePlan: ScrollCapturePlan? = null) {
        if (projection == null) {
            stopSelf()
            return
        }
        if (!canPresentTranslation()) {
            pauseForUnavailableEnhancedExperience()
            return
        }
        if (!captureInProgress.compareAndSet(false, true)) return
        initialCapturePending.set(false)
        activeCapturePlan = capturePlan
        emptyResultRetryCount.set(0)
        processingFrameCaptured.set(false)
        val generation = captureGeneration.incrementAndGet()
        recognitionSucceededGeneration.set(-1)
        captureTriggeredAtMs.set(SystemClock.elapsedRealtime())
        overlayController.beginCaptureGeneration(generation)
        if (ScreenshotMonitorService.isRunning) {
            startService(
                Intent(this, ScreenshotMonitorService::class.java)
                    .setAction(ScreenshotMonitorService.ACTION_PREPARE_FOR_CAPTURE)
            )
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = true)
        )
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(CAPTURE_ACTION_SETTLE_MS)
            if (generation != captureGeneration.get() || !continuousTranslationEnabled.get()) {
                return@launch
            }
            captureRequested.set(true)
            drainLatestImage()
            kotlinx.coroutines.delay(CAPTURE_TIMEOUT_MS)
            if (generation == captureGeneration.get()) {
                failScreenshot(
                    CaptureFrameUnavailableException("Timed out waiting for a visible frame"),
                    generation
                )
            }
        }
    }

    private fun hasVisiblePixels(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / PIXEL_SAMPLE_COLUMNS).coerceAtLeast(1)
        val stepY = (bitmap.height / PIXEL_SAMPLE_ROWS).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                if (red > BLACK_PIXEL_THRESHOLD ||
                    green > BLACK_PIXEL_THRESHOLD ||
                    blue > BLACK_PIXEL_THRESHOLD
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (result !== padded) padded.recycle()
        return result
    }

    private fun sampleFrameSignature(
        image: Image,
        maskTranslationPatches: Boolean
    ): ScreenFrameSignature {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val samples = IntArray(SIGNATURE_COLUMNS * SIGNATURE_ROWS)
        val ignoredSamples = BooleanArray(samples.size)
        val overlayBounds = overlayController.signatureOcclusionBounds(maskTranslationPatches)
        val top = (image.height * SIGNATURE_TOP_CROP_RATIO).toInt()
        val bottom = (image.height * SIGNATURE_BOTTOM_RATIO).toInt().coerceAtLeast(top + 1)
        var sampleIndex = 0
        repeat(SIGNATURE_ROWS) { row ->
            val y = top + ((bottom - top - 1) * row / (SIGNATURE_ROWS - 1).coerceAtLeast(1))
            repeat(SIGNATURE_COLUMNS) { column ->
                val x = (image.width - 1) * column / (SIGNATURE_COLUMNS - 1).coerceAtLeast(1)
                ignoredSamples[sampleIndex] = overlayBounds.any { it.contains(x, y) }
                val offset = y * plane.rowStride + x * plane.pixelStride
                if (offset + 2 < buffer.limit()) {
                    val red = buffer.get(offset).toInt() and 0xFF
                    val green = buffer.get(offset + 1).toInt() and 0xFF
                    val blue = buffer.get(offset + 2).toInt() and 0xFF
                    samples[sampleIndex] = (red * 54 + green * 183 + blue * 19) shr 8
                }
                sampleIndex++
            }
        }
        return ScreenFrameSignature(
            samples = samples,
            columns = SIGNATURE_COLUMNS,
            rows = SIGNATURE_ROWS,
            sampleTopPx = top,
            sampleBottomPx = bottom,
            ignoredSamples = ignoredSamples
        )
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        }
        return metrics
    }

    private fun finishScreenshot(
        result: BackgroundTranslatedOverlayResult,
        generation: Int,
        captureSignature: ScreenFrameSignature?
    ) {
        val handler = captureHandler
        if (handler == null) {
            result.patches.recyclePatchBitmaps()
            return
        }
        if (Looper.myLooper() == handler.looper) {
            finishScreenshotOnCaptureThread(result, generation, captureSignature, handler)
            return
        }
        val posted = handler.post {
            if (handler !== captureHandler) {
                result.patches.recyclePatchBitmaps()
                return@post
            }
            finishScreenshotOnCaptureThread(result, generation, captureSignature, handler)
        }
        if (!posted) result.patches.recyclePatchBitmaps()
    }

    private fun finishScreenshotOnCaptureThread(
        result: BackgroundTranslatedOverlayResult,
        generation: Int,
        captureSignature: ScreenFrameSignature?,
        handler: Handler
    ) {
        val currentGeneration = captureGeneration.get()
        if (!LiveCaptureTimingPolicy.shouldPresentResult(
                resultGeneration = generation,
                currentGeneration = currentGeneration,
                continuousTranslationEnabled = continuousTranslationEnabled.get()
            )
        ) {
            Log.i(
                METRICS_TAG,
                LiveRecognitionTelemetry.stalePresentationDropped(
                    resultGeneration = generation,
                    currentGeneration = currentGeneration
                )
            )
            result.patches.recyclePatchBitmaps()
            return
        }
        timeoutJob?.cancel()
        timeoutJob = null
        presentationInProgress.set(true)
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        activeCapturePlan = null
        val captureToPresentationStartMs = elapsedSince(
            captureTriggeredAtMs.get(),
            SystemClock.elapsedRealtime()
        )
        resetInteractionTiming()
        if (result.patches.isNotEmpty()) {
            rotationCaptureRecoveryPending.set(false)
            lastAcceptedCaptureSignature = captureSignature
            canRestoreLastResult.set(true)
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        handler.postDelayed(
            { resumeFrameObservation(generation) },
            LiveCaptureTimingPolicy.PRESENTATION_GATE_TIMEOUT_MS
        )
        val presentationStartedAtMs = SystemClock.elapsedRealtime()
        overlayController.showResult(
            generation = generation,
            patches = result.patches,
            sourceWidth = result.sourceWidth,
            sourceHeight = result.sourceHeight,
            recognizedCount = result.recognizedCount,
            ocrMs = result.ocrMs,
            translationMs = result.translationMs,
            renderingMs = result.renderingMs,
            shouldPresent = {
                val currentGeneration = captureGeneration.get()
                val shouldPresent = LiveCaptureTimingPolicy.shouldPresentResult(
                    resultGeneration = generation,
                    currentGeneration = currentGeneration,
                    continuousTranslationEnabled = continuousTranslationEnabled.get()
                )
                if (!shouldPresent) {
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.stalePresentationDropped(
                            resultGeneration = generation,
                            currentGeneration = currentGeneration
                        )
                    )
                }
                shouldPresent
            },
            onPresented = { presentation ->
                val presentationMs = (
                    SystemClock.elapsedRealtime() - presentationStartedAtMs
                    ).coerceAtLeast(0L)
                captureHandler?.removeCallbacks(accessibilityScrollSettle)
                accessibilityScrollPending.set(false)
                accessibilityScrollActive.set(false)
                Log.i(
                    METRICS_TAG,
                    LiveRecognitionTelemetry.presented(
                        generation = generation,
                        patchCount = result.patches.size,
                        presentationMs = presentationMs
                    )
                )
                scheduleRenderedCaptureUpload(
                    result,
                    generation,
                    presentation,
                    presentationMs,
                    captureToPresentationStartMs
                )
                lastPresentedTranslationTraces.set(result.translationTraces.distinct())
                translationLayerPresented.set(
                    presentation.presented && result.patches.isNotEmpty()
                )
                if (translationLayerPresented.get()) startFrameHeartbeat()
                resumeFrameObservation(generation)
            },
            onPresentationFailed = { presentation ->
                val presentationMs = (
                    SystemClock.elapsedRealtime() - presentationStartedAtMs
                    ).coerceAtLeast(0L)
                captureHandler?.removeCallbacks(accessibilityScrollSettle)
                accessibilityScrollPending.set(false)
                accessibilityScrollActive.set(false)
                presentationInProgress.set(false)
                translationLayerPresented.set(false)
                stopFrameHeartbeat()
                Log.e(
                    TAG,
                    "Translation overlay was not presented: $presentation"
                )
                scheduleRenderedCaptureUpload(
                    result,
                    generation,
                    presentation,
                    presentationMs,
                    captureToPresentationStartMs
                )
                resumeFrameObservation(generation)
            }
        )
    }

    private fun scheduleRenderedCaptureUpload(
        result: BackgroundTranslatedOverlayResult,
        generation: Int,
        presentation: OverlayPresentationResult,
        presentationMs: Long,
        captureToPresentationStartMs: Long
    ) {
        val shouldUpload = SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = BuildConfig.DEBUG,
                backend = translationBackend,
                uploadEnabled = TranslationBackendSettings
                    .isDebugRenderedCaptureUploadEnabled(this),
                patchCount = result.patches.size,
                failedCount = result.failedCount,
                traceCount = result.translationTraces.size
            )
        val shouldArchive = TranslationBackendSettings.isRequestArchiveExportEnabled(this)
        if (!shouldUpload && !shouldArchive) {
            return
        }
        val traces = result.translationTraces.distinct()
        val diagnostics = JSONObject()
            .put("schemaVersion", 1)
            .put("recognizedCount", result.recognizedCount)
            .put("rawRecognizedCount", result.rawRecognizedCount)
            .put("edgeFilteredCount", result.edgeFilteredCount)
            .put("edgeRecoveredCount", result.edgeRecoveredCount)
            .put("continuationCount", result.continuationCount)
            .put("translatedRegionCount", result.translatedRegionCount)
            .put("renderedPatchCount", result.patches.size)
            .put("failedRegionCount", result.failedCount)
            .put("translationFailedCount", result.translationFailedCount)
            .put("renderFailedCount", result.renderFailedCount)
            .put("renderFailures", renderFailuresJson(result.renderFailures))
            .put("sourceRegionCount", result.sourceCoverage.regionCount)
            .put("sourceCoverageRatio", result.sourceCoverage.coverageRatio.toDouble())
            .put("patchRegionCount", result.patchCoverage.regionCount)
            .put("patchCoverageRatio", result.patchCoverage.coverageRatio.toDouble())
            .put("renderingMode", result.renderingMode.name)
            .put("backgroundMode", result.backgroundMode.name)
            .put("controlAttached", presentation.controlAttached)
            .put("translationLayerAttached", presentation.translationLayerAttached)
            .put("translationVisible", presentation.translationVisible)
            .put("acceptedPatchCount", presentation.acceptedPatchCount)
            .put("visiblePatchCount", presentation.visiblePatchCount)
            .put("presentationAttemptCount", presentation.attemptCount)
            .put("presentationOutcome", presentation.failure?.name ?: "PRESENTED")
            .put("renderedCaptureMode", "PRESENTED_SCREEN_FRAME")
            .put("ocrMs", result.ocrMs)
            .put("translationMs", result.translationMs)
            .put("ocrTranslateMs", result.recognitionAndTranslationMs)
            .put("renderMs", result.renderingMs)
            .put("presentationMs", presentationMs)
            .put("captureToPresentationMs", captureToPresentationStartMs)
            .put("endToEndMs", captureToPresentationStartMs + presentationMs)
        val projectionDiagnostics = projectionLifecycleDiagnostics(projectionStopReason.get())
        projectionDiagnostics.keys().forEach { key ->
            diagnostics.put(key, projectionDiagnostics.get(key))
        }
        val audit = if (!presentation.presented) {
            SemanticRenderedCaptureAudit.renderFailed(
                stage = "OVERLAY_ATTACH",
                failureCode = presentation.failure?.name ?: "OVERLAY_PRESENTATION_FAILED",
                failureMessage = "Translation overlay did not accept and display the generated patches",
                layoutDiagnostics = diagnostics
            )
        } else if (result.failedCount > 0) {
            SemanticRenderedCaptureAudit.renderFailed(
                stage = if (result.patches.isEmpty()) "OVERLAY_LAYOUT" else "OVERLAY_PARTIAL_DRAW",
                failureCode = if (result.patches.isEmpty()) {
                    "NO_RENDERABLE_PATCH"
                } else {
                    "PARTIAL_RENDER"
                },
                failureMessage = "${result.failedCount} translated region(s) were not pasted back",
                layoutDiagnostics = diagnostics
            )
        } else {
            SemanticRenderedCaptureAudit.presented(diagnostics)
        }
        captureHandler?.postDelayed(
            {
                if (generation != captureGeneration.get() ||
                    !continuousTranslationEnabled.get() ||
                    !SemanticRenderedCaptureUploadPolicy.supportsBackend(translationBackend)
                ) {
                    return@postDelayed
                }
                pendingRenderedCapture.set(
                    PendingRenderedCapture(
                        generation = generation,
                        traces = traces,
                        audit = audit
                    )
                )
                drainLatestImage()
            },
            RENDERED_CAPTURE_SETTLE_MS
        )
    }

    private fun processRenderedCapture(image: Image, pending: PendingRenderedCapture) {
        serviceScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = imageToBitmap(image)
                val capture = SemanticDebugCaptureEncoder.encode(bitmap)
                pending.traces.forEach { trace ->
                    runCatching {
                        TranslationRequestArchiveStore.recordRenderedCapture(
                            this@OneShotScreenCaptureService,
                            trace,
                            capture,
                            pending.audit
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to update request archive for ${trace.requestId}", error)
                    }
                }
                val uploadBaseUrl = SemanticRenderedCaptureUploadPolicy.uploadBaseUrl(
                    backend = translationBackend,
                    selfHostedBaseUrl = TranslationBackendSettings.selfHostedBaseUrl(
                        this@OneShotScreenCaptureService
                    ),
                    edgeAuditBaseUrl = TranslationBackendSettings.edgeAuditBaseUrl(
                        this@OneShotScreenCaptureService
                    )
                )
                if (uploadBaseUrl.isBlank()) return@launch
                val uploader = SelfHostedRenderedCaptureUploader(
                    baseUrl = uploadBaseUrl,
                    bearerToken = TranslationBackendSettings.selfHostedBearerToken(
                        this@OneShotScreenCaptureService
                    )
                )
                pending.traces.forEach { trace ->
                    runCatching { uploader.upload(trace, capture, pending.audit) }
                        .onSuccess {
                            Log.i(TAG, "Uploaded rendered capture for request ${trace.requestId}")
                        }
                        .onFailure { error ->
                            Log.w(
                                TAG,
                                "Unable to upload rendered capture for request ${trace.requestId}",
                                error
                            )
                        }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to encode rendered screen capture", error)
            } finally {
                runCatching(image::close)
                bitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        }
    }

    private fun failScreenshot(error: Throwable? = null, generation: Int? = null) {
        val handler = captureHandler
        if (handler == null) {
            if (error != null) Log.w(TAG, "Ignoring capture failure after capture thread release", error)
            return
        }
        if (Looper.myLooper() == handler.looper) {
            failScreenshotOnCaptureThread(error, generation)
            return
        }
        handler.post {
            if (handler === captureHandler) failScreenshotOnCaptureThread(error, generation)
        }
    }

    private fun failScreenshotOnCaptureThread(error: Throwable?, generation: Int?) {
        if (generation != null && generation != captureGeneration.get()) return
        if (ScreenCaptureProjectionLifecycle.requiresManualReauthorization(error)) {
            checkNotNull(error)
            recoverFromProjectionFailure(projectionSessionId.get(), error)
            return
        }
        if (!captureInProgress.compareAndSet(true, false)) return
        if (error != null) Log.e(TAG, "Screen capture failed", error)
        val translationTimedOut = error is TimeoutCancellationException
        timeoutJob?.cancel()
        timeoutJob = null
        captureRequested.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
        lastPresentedTranslationTraces.set(emptyList())
        activeCapturePlan = null
        continuousTranslationEnabled.set(false)
        captureHandler?.removeCallbacks(accessibilityScrollSettle)
        accessibilityScrollPending.set(false)
        accessibilityScrollActive.set(false)
        resetInteractionTiming()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        if (recognitionSucceededGeneration.get() == generation) {
            overlayController.showCaptureFailed()
        } else {
            overlayController.showRecognitionFailed()
        }
        if (translationTimedOut) {
            mainHandler.post {
                Toast.makeText(
                    applicationContext,
                    R.string.active_screenshot_translation_timeout,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        postFailureNotification()
    }

    private fun cancelContinuousTranslation() {
        cancelActiveCapture(keepContinuousMode = false)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
    }

    private fun cancelActiveCapture(
        keepContinuousMode: Boolean,
        showCancellationStatus: Boolean = true
    ) {
        val invalidatedGeneration = captureGeneration.incrementAndGet()
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
        translationLayerPresented.set(false)
        scrollFrameProbeEpoch.incrementAndGet()
        stopFrameHeartbeat()
        lastAcceptedCaptureSignature = null
        latestObservedSignature = null
        initialCapturePending.set(false)
        initialStabilityGeneration.incrementAndGet()
        pendingInitialFrame.getAndSet(null)?.image?.close()
        pendingRenderedCapture.set(null)
        activeCapturePlan = null
        lastSignatureSampleAt = Long.MIN_VALUE
        resetInteractionTiming()
        captureHandler?.removeCallbacks(movementSettleFallback)
        captureHandler?.removeCallbacks(accessibilityScrollSettle)
        accessibilityScrollPending.set(false)
        accessibilityScrollActive.set(false)
        if (!keepContinuousMode) continuousTranslationEnabled.set(false)
        timeoutJob?.cancel()
        timeoutJob = null
        val activeJob = processingJob.getAndSet(null)
        activeJob?.cancel()
        captureHandler?.post(changeDetector::reset)
        overlayController.invalidatePresentationGeneration(invalidatedGeneration)
        if (showCancellationStatus && !keepContinuousMode && activeJob?.isCompleted == false) {
            overlayController.showRecognitionCancelled()
        }
    }

    private fun discardStaleCaptureForMovement() {
        val invalidatedGeneration = captureGeneration.incrementAndGet()
        captureInProgress.set(false)
        captureRequested.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
        translationLayerPresented.set(false)
        scrollFrameProbeEpoch.incrementAndGet()
        stopFrameHeartbeat()
        lastAcceptedCaptureSignature = null
        activeCapturePlan = null
        pendingRenderedCapture.set(null)
        timeoutJob?.cancel()
        timeoutJob = null
        processingJob.getAndSet(null)?.cancel()
        overlayController.invalidatePresentationGeneration(invalidatedGeneration)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
    }

    private fun drainLatestImage() {
        captureHandler?.post {
            imageReader?.let(::onImageAvailable)
        }
    }

    private fun scheduleEmptyResultRetry(generation: Int) {
        processingFrameCaptured.set(false)
        captureRequested.set(false)
        overlayController.hideForCapture()
        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(EMPTY_RESULT_RETRY_DELAY_MS)
            if (generation != captureGeneration.get() ||
                !continuousTranslationEnabled.get() ||
                !captureInProgress.get()
            ) {
                return@launch
            }
            captureRequested.set(true)
            overlayController.pulseTransparentCaptureSurface()
            drainLatestImage()
            kotlinx.coroutines.delay(CAPTURE_TIMEOUT_MS)
            if (generation == captureGeneration.get()) {
                if (rotationCaptureRecoveryPending.get()) {
                    restartProjectionPermissionAfterRotation()
                } else {
                    failScreenshot(
                        CaptureFrameUnavailableException(
                            "Timed out waiting for an OCR retry frame"
                        ),
                        generation
                    )
                }
            }
        }
    }

    private fun finishDuplicateCapture(generation: Int) {
        if (generation != captureGeneration.get()) return
        timeoutJob?.cancel()
        timeoutJob = null
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        activeCapturePlan = null
        presentationInProgress.set(true)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        overlayController.restoreAfterSkippedCapture()
        resetInteractionTiming()
        captureHandler?.postDelayed(
            { resumeFrameObservation(generation) },
            LiveCaptureTimingPolicy.PRESENTATION_GATE_TIMEOUT_MS
        )
        Log.i(TAG, "Skipped duplicate settled viewport before OCR")
    }

    private fun interactionTiming(completedAtMs: Long): LiveInteractionTimingMetrics =
        LiveInteractionTimingMetrics(
            firstMotionToCommitMs = elapsedSince(firstMotionAtMs.get(), completedAtMs),
            lastMotionToCommitMs = elapsedSince(lastMotionAtMs.get(), completedAtMs),
            captureToCommitMs = elapsedSince(captureTriggeredAtMs.get(), completedAtMs)
        )

    private fun elapsedSince(startedAtMs: Long, completedAtMs: Long): Long =
        if (startedAtMs <= 0L || completedAtMs < startedAtMs) -1L else completedAtMs - startedAtMs

    private fun resetInteractionTiming() {
        firstMotionAtMs.set(0L)
        lastMotionAtMs.set(0L)
        captureTriggeredAtMs.set(0L)
    }

    private fun resumeFrameObservation(generation: Int) {
        val handler = captureHandler
        if (handler == null) {
            presentationInProgress.set(false)
            return
        }
        handler.post {
            if (generation != captureGeneration.get() ||
                !presentationInProgress.compareAndSet(true, false)
            ) {
                return@post
            }
            changeDetector.onTranslationRendered(SystemClock.elapsedRealtime())
            drainLatestImage()
        }
    }

    private fun requestExperienceMode(mode: LiveOverlayExperienceMode) {
        LiveOverlayExperiencePreferences.setRequestedMode(this, mode)
        if (mode == LiveOverlayExperienceMode.ENHANCED &&
            !ScreenTranslationAccessibilityService.isConnected
        ) {
            applyResolvedExperienceMode()
            Toast.makeText(
                this,
                R.string.active_screenshot_enhanced_permission_required,
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        }
        applyResolvedExperienceMode()
    }

    private fun applyCaptureSettings(settings: LiveCaptureSettings) {
        val previous = captureSettings
        if (previous == settings) return
        captureSettings = settings
        LiveCaptureSettingsPreferences.set(this, settings)
        Log.i(
            TAG,
            "Live capture settings changed: scene=${settings.scenePreset}, " +
                "frequency=${settings.frequency}, buffer=${settings.bufferMode}, " +
                "segmentation=${settings.segmentation}"
        )
        if (projection == null) return
        if (previous.bufferMode != settings.bufferMode) {
            captureHandler?.post(::reconfigureCaptureForSettings)
        } else if (continuousTranslationEnabled.get()) {
            liveProcessor.clearLiveOverlaySnapshot()
            cancelActiveCapture(keepContinuousMode = true)
            requestScreenshot()
        }
    }

    private fun applySmartAssistEnabled(enabled: Boolean) {
        if (smartAssistEnabled == enabled) return
        smartAssistEnabled = enabled
        LiveSmartAssistPreferences.setEnabled(this, enabled)
        Log.i(TAG, "Offline smart assist enabled=$enabled")
        if (projection == null || !continuousTranslationEnabled.get()) return
        liveProcessor.clearLiveOverlaySnapshot()
        cancelActiveCapture(keepContinuousMode = true)
        requestScreenshot()
    }

    private fun applyBackgroundExperienceMode(mode: LivePatchBackgroundExperienceMode) {
        if (backgroundExperienceMode == mode) return
        backgroundExperienceMode = mode
        LivePatchBackgroundExperiencePreferences.set(this, mode)
        Log.i(TAG, "Live patch background experience changed: ${mode.name}")
        overlayController.clearTranslations()
        if (liveProcessorDelegate.isInitialized()) liveProcessor.clearLiveOverlaySnapshot()
        if (projection == null || !continuousTranslationEnabled.get()) return
        cancelActiveCapture(keepContinuousMode = true)
        requestScreenshot()
    }

    private fun applyExperimentalTranslationEngine(engine: ExperimentalTranslationEngine) {
        if (experimentalTranslationEngine == engine) return
        experimentalTranslationEngine = engine
        ExperimentalTranslationSettings.set(this, engine)
        Log.i(TAG, "Experimental translation engine changed: ${engine.name}")
        if (
            engine != ExperimentalTranslationEngine.DISABLED &&
            ExperimentalModelRepository(this).status(engine).state != ExperimentalModelState.READY
        ) {
            Toast.makeText(
                this,
                R.string.active_screenshot_experimental_translation_not_ready,
                Toast.LENGTH_LONG
            ).show()
        }
        if (liveProcessorDelegate.isInitialized()) {
            liveProcessor.setExperimentalTranslationEngine(engine)
        }
        if (projection == null || !continuousTranslationEnabled.get()) return
        cancelActiveCapture(keepContinuousMode = true)
        requestScreenshot()
    }

    private fun applyTranslationBackend(backend: TranslationBackend) {
        if (translationBackend == backend) return
        if (!TranslationBackendSettings.isConfigured(this, backend)) {
            Toast.makeText(
                this,
                R.string.network_translation_not_configured,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        TranslationBackendSettings.set(this, backend)
        translationBackend = backend
        Log.i(TAG, "Translation backend changed: ${backend.name}")
        if (liveProcessorDelegate.isInitialized()) liveProcessor.clearLiveOverlaySnapshot()
        if (projection == null || !continuousTranslationEnabled.get()) return
        cancelActiveCapture(keepContinuousMode = true)
        requestScreenshot()
    }

    private fun applyLiveOcrTranslationEngine(engine: LiveOcrTranslationEngineType) {
        if (liveOcrTranslationEngine == engine) return
        if (engine == LiveOcrTranslationEngineType.PADDLE_NETWORK &&
            !PaddleNetworkSettings.isConfigured(this)
        ) {
            Toast.makeText(
                this,
                R.string.paddle_network_not_configured,
                Toast.LENGTH_LONG
            ).show()
            overlayController.setLiveOcrTranslationEngine(
                liveOcrTranslationEngine,
                PaddleNetworkSettings.isConfigured(this)
            )
            return
        }
        LiveOcrTranslationEngineSettings.set(this, engine)
        liveOcrTranslationEngine = engine
        Log.i(TAG, "Live OCR translation engine changed: ${engine.name}")
        overlayController.setLiveOcrTranslationEngine(
            engine,
            PaddleNetworkSettings.isConfigured(this)
        )
        if (liveProcessorDelegate.isInitialized()) liveProcessor.clearLiveOverlaySnapshot()
        if (projection == null || !continuousTranslationEnabled.get()) return
        cancelActiveCapture(keepContinuousMode = true)
        beginCaptureFromUser()
    }

    private fun refreshPipelineSettings() {
        val configured = PaddleNetworkSettings.isConfigured(this)
        val resolvedEngine = LiveOcrTranslationEngineSettings.get(this)
        liveOcrTranslationEngine = resolvedEngine
        overlayController.setLiveOcrTranslationEngine(resolvedEngine, configured)
        if (liveProcessorDelegate.isInitialized()) liveProcessor.clearLiveOverlaySnapshot()
        if (projection == null || !continuousTranslationEnabled.get()) return
        cancelActiveCapture(keepContinuousMode = true)
        beginCaptureFromUser()
    }

    private fun reconfigureCaptureForSettings() {
        val handler = captureHandler ?: return
        if (projection == null) return
        val resumeContinuousCapture = continuousTranslationEnabled.get()
        cancelActiveCapture(keepContinuousMode = resumeContinuousCapture)
        runCatching { configureCapturePipeline(resolveDisplayMetrics(), handler) }
            .onSuccess {
                overlayController.onDisplayGeometryChanged(clearTranslations = true)
                liveProcessor.clearLiveOverlaySnapshot()
                if (resumeContinuousCapture) awaitStableViewport("capture settings")
            }
            .onFailure(::failSession)
    }

    private fun applyResolvedExperienceMode() {
        val requested = LiveOverlayExperiencePreferences.requestedMode(this)
        val resolved = resolvedExperienceMode()
        if (experienceMode == resolved) {
            if (!LiveOverlayExperiencePolicy.canPresentTranslation(requested, resolved)) {
                pauseForUnavailableEnhancedExperience()
            }
            return
        }
        val resumeContinuousCapture = continuousTranslationEnabled.get()
        cancelActiveCapture(keepContinuousMode = resumeContinuousCapture)
        experienceMode = resolved
        Log.i(TAG, "Live overlay experience changed: ${resolved.name}")
        overlayController.setExperienceMode(resolved)
        if (projection != null && resumeContinuousCapture) {
            liveProcessor.clearLiveOverlaySnapshot()
            if (LiveOverlayExperiencePolicy.canPresentTranslation(requested, resolved)) {
                awaitStableViewport("overlay experience mode")
            } else {
                pauseForUnavailableEnhancedExperience()
            }
        }
    }

    private fun canPresentTranslation(): Boolean =
        LiveOverlayExperiencePolicy.canPresentTranslation(
            requested = LiveOverlayExperiencePreferences.requestedMode(this),
            resolved = experienceMode
        )

    private fun pauseForUnavailableEnhancedExperience() {
        timeoutJob?.cancel()
        timeoutJob = null
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
        activeCapturePlan = null
        resetInteractionTiming()
        overlayController.clearTranslations()
        overlayController.showReadyExpanded()
        Log.i(TAG, "Live translation paused: Enhanced accessibility overlay unavailable")
    }

    private fun resolvedExperienceMode(): LiveOverlayExperienceMode =
        LiveOverlayExperiencePolicy.resolve(
            requested = LiveOverlayExperiencePreferences.requestedMode(this),
            accessibilityConnected = ScreenTranslationAccessibilityService.isConnected
        )

    private fun failSession(error: Throwable? = null) {
        if (error != null) Log.e(TAG, "Screen capture session failed", error)
        postFailureNotification()
        stopCaptureSession()
    }

    private fun stopCaptureSession() {
        if (sessionState.getAndSet(ScreenCaptureSessionState.STOPPING) ==
            ScreenCaptureSessionState.STOPPING
        ) {
            return
        }
        projectionStopRequested.set(true)
        releaseCaptureResources(
            stopProjection = true,
            dismissOverlay = true,
            removeForeground = true
        )
        stopSelf()
    }

    private fun postFailureNotification() {
        val openFailure = Intent(this, ImageTranslateActivity::class.java).apply {
            action = ACTION_CAPTURE_FAILED
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(getString(R.string.active_screenshot_failed))
            .setContentText(getString(R.string.active_screenshot_failed_text))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    RESULT_NOTIFICATION_ID,
                    openFailure,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun releaseCaptureResources(
        stopProjection: Boolean,
        dismissOverlay: Boolean,
        removeForeground: Boolean
    ) {
        rotationCaptureRecoveryPending.set(false)
        timeoutJob?.cancel()
        timeoutJob = null
        val mediaProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        releaseProjectionResources(mediaProjection, callback, stopProjection)
        if (dismissOverlay && ::overlayController.isInitialized) overlayController.dismiss()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun releaseProjectionResources(
        mediaProjection: MediaProjection?,
        callback: MediaProjection.Callback?,
        stopProjection: Boolean
    ) {
        cancelActiveCapture(
            keepContinuousMode = false,
            showCancellationStatus = false
        )
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplayState.set("RELEASING")
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        virtualDisplayState.set("RELEASED")
        runCatching { imageReader?.close() }
        imageReader = null
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0
        if (mediaProjection != null && callback != null) {
            runCatching { mediaProjection.unregisterCallback(callback) }
        }
        if (stopProjection && mediaProjection != null) {
            runCatching { mediaProjection.stop() }
        }
        captureHandler?.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun buildSessionNotification(capturing: Boolean) =
        ScreenCaptureSessionNotificationFactory(this, CHANNEL_ID).build(
            capturing = capturing,
            projectionActive = projection != null
        )

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.active_screenshot_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    getString(R.string.active_screenshot_result_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        )
    }

    private data class PendingInitialFrame(
        val image: Image,
        val signature: ScreenFrameSignature
    )

    private data class PendingRenderedCapture(
        val generation: Int,
        val traces: List<SemanticTranslationTrace>,
        val audit: SemanticRenderedCaptureAudit
    )

    companion object {
        const val ACTION_SHOW_OVERLAY =
            "com.example.imagetranslate.screenshot.SHOW_TRANSLATION_OVERLAY"
        const val ACTION_START_SESSION =
            "com.example.imagetranslate.screenshot.START_CAPTURE_SESSION"
        const val ACTION_TAKE_SCREENSHOT =
            "com.example.imagetranslate.screenshot.TAKE_SCREENSHOT"
        const val ACTION_STOP_SESSION =
            "com.example.imagetranslate.screenshot.STOP_CAPTURE_SESSION"
        const val ACTION_REFRESH_OVERLAY_MODE =
            "com.example.imagetranslate.screenshot.REFRESH_OVERLAY_MODE"
        const val ACTION_REFRESH_PIPELINE_SETTINGS =
            "com.example.imagetranslate.screenshot.REFRESH_PIPELINE_SETTINGS"
        const val ACTION_ACCESSIBILITY_VIEW_SCROLLED =
            "com.example.imagetranslate.screenshot.ACCESSIBILITY_VIEW_SCROLLED"
        const val EXTRA_ACCESSIBILITY_SOURCE_PACKAGE =
            "accessibility_source_package"
        const val EXTRA_ACCESSIBILITY_WINDOW_ID =
            "accessibility_window_id"
        const val ACTION_CAPTURE_FAILED =
            "com.example.imagetranslate.screenshot.CAPTURE_FAILED"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_START_IMMEDIATELY = "start_immediately"
        private const val CHANNEL_ID = "active_screen_capture_controls_v2"
        private const val RESULT_CHANNEL_ID = "active_screen_capture_result"
        private const val NOTIFICATION_ID = 2401
        private const val RESULT_NOTIFICATION_ID = 2402
        private const val CAPTURE_ACTION_SETTLE_MS = 120L
        private const val CAPTURE_TIMEOUT_MS = 8_000L
        private const val EMPTY_RESULT_RETRY_DELAY_MS = 360L
        private const val PIXEL_SAMPLE_COLUMNS = 32
        private const val PIXEL_SAMPLE_ROWS = 48
        private const val BLACK_PIXEL_THRESHOLD = 8
        private const val SIGNATURE_COLUMNS = 48
        private const val SIGNATURE_ROWS = 72
        private const val SIGNATURE_TOP_CROP_RATIO = 0.12f
        private const val SIGNATURE_BOTTOM_RATIO = 0.92f
        private const val DISPLAY_CHANGE_SETTLE_MS = 900L
        private const val MEDIA_PROJECTION_RESIZE_SETTLE_MS = 120L
        private const val CAPTURE_SURFACE_RETIRE_DELAY_MS = 500L
        private const val FRAME_HEARTBEAT_INTERVAL_MS = 350L
        private const val FRAME_HEARTBEAT_RESPONSE_TIMEOUT_MS = 300L
        private const val MAX_FRAME_HEARTBEAT_MISSES = 2
        private const val EXTERNAL_DISPLAY_CONFLICT_WINDOW_MS = 5_000L
        private const val CAPTURE_DISPLAY_NAME = "ImageTranslateScreenCaptureSession"
        private const val SCROLL_FRAME_PROBE_TIMEOUT_MS = 500L
        private const val ROTATION_FRAME_RECOVERY_TIMEOUT_MS = 3_500L
        private const val INITIAL_STABILITY_MAX_WAIT_MS = 1_800L
        private const val MOVEMENT_SETTLE_FALLBACK_MS = 650L
        private const val ACCESSIBILITY_SCROLL_SETTLE_MS = 700L
        private const val RENDERED_CAPTURE_SETTLE_MS = 280L
        private val CAPTURE_FRAME_PULSE_DELAYS_MS = longArrayOf(250L, 650L, 1_050L)
        private const val TAG = "ScreenCaptureSession"
        private const val METRICS_TAG = "LiveOcrMetrics"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun showOverlay(context: android.content.Context) {
            val serviceIntent = Intent(context, OneShotScreenCaptureService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun refreshPipelineSettings(context: android.content.Context) {
            if (!isRunning) return
            val serviceIntent = Intent(context, OneShotScreenCaptureService::class.java).apply {
                action = ACTION_REFRESH_PIPELINE_SETTINGS
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        fun start(
            context: android.content.Context,
            resultCode: Int,
            resultData: Intent,
            startImmediately: Boolean = false
        ) {
            val serviceIntent = Intent(context, OneShotScreenCaptureService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_START_IMMEDIATELY, startImmediately)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
