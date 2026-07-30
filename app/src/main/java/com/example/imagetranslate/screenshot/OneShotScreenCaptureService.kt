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
import com.example.imagetranslate.R
import com.example.imagetranslate.ocr.OcrModel
import com.example.imagetranslate.ocr.OcrModelDownloadException
import com.example.imagetranslate.ocr.OcrModelState
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusCodes
import com.example.imagetranslate.ui.ImageTranslateActivity
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
    private val sessionStopping = AtomicBoolean(false)
    private val rotationPermissionRestart = AtomicBoolean(false)
    private val rotationCaptureRecoveryPending = AtomicBoolean(false)
    private val captureGeneration = AtomicInteger(0)
    private val firstMotionAtMs = AtomicLong(0L)
    private val lastMotionAtMs = AtomicLong(0L)
    private val captureTriggeredAtMs = AtomicLong(0L)
    private val changeDetector = ScreenFrameChangeDetector()
    private val initialStabilityGate = InitialViewportStabilityGate()
    private val initialCapturePending = AtomicBoolean(false)
    private val initialStabilityGeneration = AtomicInteger(0)
    private val pendingInitialFrame = AtomicReference<PendingInitialFrame?>()
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var timeoutJob: Job? = null
    private var processingJob: Job? = null
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
            captureInProgress.get() || initialCapturePending.get()
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
            experienceMode != LiveOverlayExperienceMode.ENHANCED ||
            !continuousTranslationEnabled.get() || projection == null
        ) {
            return@Runnable
        }
        requestScreenshot()
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
    private var recognitionMode = OcrRecognitionMode.AUTO
    @Volatile
    private var smartAssistEnabled = LiveSmartAssistSettingsPolicy.DEFAULT_ENABLED
    @Volatile
    private var backgroundExperienceMode = LivePatchBackgroundExperiencePolicy.default
    private val liveProcessorDelegate = lazy {
        BackgroundTranslatedImageProcessor(applicationContext, reuseResources = true)
    }
    private val liveProcessor by liveProcessorDelegate
    private lateinit var overlayController: ActiveScreenCaptureOverlayController
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            overlayController.onDisplayGeometryChanged(clearTranslations = false)
            captureHandler?.postDelayed(
                { reconfigureCaptureForDisplay() },
                DISPLAY_CHANGE_SETTLE_MS
            )
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureSession()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            captureHandler?.postDelayed(
                { reconfigureCaptureForDisplay(width, height) },
                MEDIA_PROJECTION_RESIZE_SETTLE_MS
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        experienceMode = resolvedExperienceMode()
        captureSettings = LiveCaptureSettingsPreferences.get(this)
        recognitionMode = LiveOcrRecognitionPreferences.get(this)
        smartAssistEnabled = LiveSmartAssistPreferences.isEnabled(this)
        backgroundExperienceMode = LivePatchBackgroundExperiencePreferences.get(this)
        overlayController = ActiveScreenCaptureOverlayController(
            this,
            experienceMode,
            captureSettings,
            recognitionMode,
            smartAssistEnabled,
            backgroundExperienceMode,
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
                    refreshOcrModelStates()
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
            }
        )
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            ACTION_ACCESSIBILITY_VIEW_SCROLLED -> {
                handleAccessibilityScroll()
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
        displayManager.unregisterDisplayListener(displayListener)
        val activeProcessingJob = processingJob
        releaseCaptureResources()
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

    private fun startOverlayOnly() {
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
            runCatching { liveProcessor.prepareForLiveTranslation() }
                .onFailure { Log.w(TAG, "Unable to prewarm live translation models", it) }
        }
    }

    private fun startSessionForeground(capturing: Boolean, requestedType: Int) {
        foregroundServiceTypes = foregroundServiceTypes or requestedType
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildSessionNotification(capturing),
            foregroundServiceTypes
        )
    }

    private fun beginCaptureFromUser() {
        if (projection == null) {
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
        runCatching {
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
            mediaProjection.registerCallback(projectionCallback, handler)

            configureCapturePipeline(resolveDisplayMetrics(), handler)
            if (startImmediately) {
                continuousTranslationEnabled.set(true)
                awaitStableViewport("initial permission return")
            } else {
                overlayController.showReadyExpanded()
            }
        }.onFailure(::failSession)
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
            virtualDisplay = projection?.createVirtualDisplay(
                "ImageTranslateScreenCaptureSession",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                handler
            ) ?: error("MediaProjection ended before the capture surface was created")
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
            captureHandler?.postDelayed(
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
        captureHandler?.postDelayed(
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
        cancelActiveCapture(keepContinuousMode = false)
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.let { mediaProjection ->
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            runCatching { mediaProjection.stop() }
        }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0
        overlayController.onDisplayGeometryChanged(clearTranslations = true)
        overlayController.hideForCapture()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        mainHandler.post { ScreenCapturePermissionActivity.request(this) }
    }

    private fun onImageAvailable(reader: ImageReader) {
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
            val signature = runCatching { sampleFrameSignature(image) }.getOrNull()
            if (signature != null) {
                latestObservedSignature = signature
                changeDetector.onCaptureStarted(signature, SystemClock.elapsedRealtime())
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
            sampleFrameSignature(image)
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
                overlayController.showWaitingForStable {
                    val hiddenAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.hiddenForMovement(
                            generation = captureGeneration.get(),
                            motionToHiddenMs = (hiddenAtMs - nowMs).coerceAtLeast(0L)
                        )
                    )
                }
                discardStaleCaptureForMovement()
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

    private fun handleAccessibilityScroll() {
        val handler = captureHandler ?: return
        if (experienceMode != LiveOverlayExperienceMode.ENHANCED ||
            !continuousTranslationEnabled.get() || projection == null
        ) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        lastMotionAtMs.set(nowMs)
        if (accessibilityScrollPending.compareAndSet(false, true)) {
            if (accessibilityScrollActive.compareAndSet(false, true)) {
                firstMotionAtMs.compareAndSet(0L, nowMs)
                overlayController.showWaitingForStable {
                    val hiddenAtMs = SystemClock.elapsedRealtime()
                    Log.i(
                        METRICS_TAG,
                        LiveRecognitionTelemetry.hiddenForMovement(
                            generation = captureGeneration.get(),
                            motionToHiddenMs = (hiddenAtMs - nowMs).coerceAtLeast(0L)
                        )
                    )
                }
            }
            discardStaleCaptureForMovement()
            changeDetector.reset()
        }
        handler.removeCallbacks(accessibilityScrollSettle)
        handler.postDelayed(accessibilityScrollSettle, ACCESSIBILITY_SCROLL_SETTLE_MS)
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
        overlayController.hideForCapture()
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
        processingJob = serviceScope.launch {
            var sourceBitmap: Bitmap? = null
            var translatedResult: BackgroundTranslatedOverlayResult? = null
            try {
                if (generation != captureGeneration.get()) {
                    image.close()
                    return@launch
                }
                sourceBitmap = image.use(::imageToBitmap)
                val bitmap = sourceBitmap
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
                val result = withTimeout(LiveCaptureTimingPolicy.TRANSLATION_TIMEOUT_MS) {
                    translationMutex.withLock {
                        if (generation != captureGeneration.get()) {
                            throw CancellationException("Stale live OCR frame")
                        }
                        overlayController.showProcessing()
                        canRestoreLastResult.set(false)
                        liveProcessor.translateForOverlay(
                            bitmap = bitmap,
                            mode = activeMode,
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
                            smartAssistEnabled = activeSmartAssistEnabled
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
                            "recognized=${result.recognizedCount}, patches=${result.patches.size}, " +
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failScreenshot(error, generation)
            } finally {
                translatedResult?.patches?.recyclePatchBitmaps()
                sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
        }
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
        captureTriggeredAtMs.set(SystemClock.elapsedRealtime())
        overlayController.hideForCapture()
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
                    IllegalStateException("Timed out waiting for a visible frame"),
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

    private fun sampleFrameSignature(image: Image): ScreenFrameSignature {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val samples = IntArray(SIGNATURE_COLUMNS * SIGNATURE_ROWS)
        val ignoredSamples = BooleanArray(samples.size)
        val overlayBounds = overlayController.signatureOcclusionBounds()
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
        if (generation != captureGeneration.get()) {
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
        captureHandler?.postDelayed(
            { resumeFrameObservation(generation) },
            LiveCaptureTimingPolicy.PRESENTATION_GATE_TIMEOUT_MS
        )
        val presentationStartedAtMs = SystemClock.elapsedRealtime()
        overlayController.showResult(
            patches = result.patches,
            sourceWidth = result.sourceWidth,
            sourceHeight = result.sourceHeight,
            recognizedCount = result.recognizedCount,
            onPresented = {
                captureHandler?.removeCallbacks(accessibilityScrollSettle)
                accessibilityScrollPending.set(false)
                accessibilityScrollActive.set(false)
                Log.i(
                    METRICS_TAG,
                    LiveRecognitionTelemetry.presented(
                        generation = generation,
                        patchCount = result.patches.size,
                        presentationMs = (
                            SystemClock.elapsedRealtime() - presentationStartedAtMs
                            ).coerceAtLeast(0L)
                    )
                )
                resumeFrameObservation(generation)
            }
        )
    }

    private fun failScreenshot(error: Throwable? = null, generation: Int? = null) {
        if (generation != null && generation != captureGeneration.get()) return
        if (!captureInProgress.compareAndSet(true, false)) return
        if (error != null) Log.e(TAG, "Screen capture failed", error)
        timeoutJob?.cancel()
        timeoutJob = null
        captureRequested.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
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
        overlayController.showCaptureFailed()
        postFailureNotification()
    }

    private fun cancelContinuousTranslation() {
        cancelActiveCapture(keepContinuousMode = false)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
    }

    private fun cancelActiveCapture(keepContinuousMode: Boolean) {
        captureGeneration.incrementAndGet()
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        canRestoreLastResult.set(false)
        lastAcceptedCaptureSignature = null
        latestObservedSignature = null
        initialCapturePending.set(false)
        initialStabilityGeneration.incrementAndGet()
        pendingInitialFrame.getAndSet(null)?.image?.close()
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
        processingJob?.cancel()
        processingJob = null
        captureHandler?.post(changeDetector::reset)
        overlayController.clearTranslations()
    }

    private fun discardStaleCaptureForMovement() {
        captureGeneration.incrementAndGet()
        captureInProgress.set(false)
        captureRequested.set(false)
        processingFrameCaptured.set(false)
        presentationInProgress.set(false)
        activeCapturePlan = null
        timeoutJob?.cancel()
        timeoutJob = null
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
                        IllegalStateException("Timed out waiting for an OCR retry frame"),
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
        if (!sessionStopping.compareAndSet(false, true)) return
        releaseCaptureResources()
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

    private fun releaseCaptureResources() {
        cancelActiveCapture(keepContinuousMode = false)
        rotationCaptureRecoveryPending.set(false)
        timeoutJob?.cancel()
        timeoutJob = null
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureWidth = 0
        captureHeight = 0
        captureDensityDpi = 0
        projection?.let { mediaProjection ->
            runCatching { mediaProjection.unregisterCallback(projectionCallback) }
            runCatching { mediaProjection.stop() }
        }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        if (::overlayController.isInitialized) overlayController.dismiss()
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        const val ACTION_ACCESSIBILITY_VIEW_SCROLLED =
            "com.example.imagetranslate.screenshot.ACCESSIBILITY_VIEW_SCROLLED"
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
        private const val ROTATION_FRAME_RECOVERY_TIMEOUT_MS = 3_500L
        private const val INITIAL_STABILITY_MAX_WAIT_MS = 1_800L
        private const val MOVEMENT_SETTLE_FALLBACK_MS = 650L
        private const val ACCESSIBILITY_SCROLL_SETTLE_MS = 700L
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
