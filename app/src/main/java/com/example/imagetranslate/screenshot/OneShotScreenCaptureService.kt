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
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.imagetranslate.R
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

class OneShotScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val captureRequested = AtomicBoolean(false)
    private val captureInProgress = AtomicBoolean(false)
    private val processingFrameCaptured = AtomicBoolean(false)
    private val continuousTranslationEnabled = AtomicBoolean(false)
    private val sessionStopping = AtomicBoolean(false)
    private val captureGeneration = AtomicInteger(0)
    private val changeDetector = ScreenFrameChangeDetector()
    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var timeoutJob: Job? = null
    private var processingJob: Job? = null
    private var activeCapturePlan: ScrollCapturePlan? = null
    private var lastSignatureSampleAt = Long.MIN_VALUE
    private val translationMutex = Mutex()
    private var foregroundServiceTypes = 0
    @Volatile
    private var translationMode = TranslationMode.AUTO_BIDIRECTIONAL
    @Volatile
    private var experienceMode = LiveOverlayExperienceMode.DEFAULT
    private val liveProcessorDelegate = lazy {
        BackgroundTranslatedImageProcessor(reuseResources = true)
    }
    private val liveProcessor by liveProcessorDelegate
    private lateinit var overlayController: ActiveScreenCaptureOverlayController

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCaptureSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        experienceMode = resolvedExperienceMode()
        overlayController = ActiveScreenCaptureOverlayController(
            this,
            experienceMode,
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

                override fun onTranslationVisibilityChanged() {
                    captureHandler?.post {
                        changeDetector.onTranslationRendered(SystemClock.elapsedRealtime())
                    }
                }

                override fun onExperienceModeRequested(mode: LiveOverlayExperienceMode) {
                    requestExperienceMode(mode)
                }
            }
        )
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
            overlayController.hideForCapture()
            ScreenCapturePermissionActivity.request(this)
            return
        }
        continuousTranslationEnabled.set(true)
        requestScreenshot()
    }

    private fun startCaptureSession(resultData: Intent, startImmediately: Boolean) {
        runCatching {
            val thread = HandlerThread("screen-capture-session").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler

            val mediaProjection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(Activity.RESULT_OK, resultData)
                ?: error("Screen capture permission was not granted")
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, handler)

            val metrics = resolveDisplayMetrics()
            val reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader
            reader.setOnImageAvailableListener(::onImageAvailable, handler)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ImageTranslateScreenCaptureSession",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            if (startImmediately) {
                continuousTranslationEnabled.set(true)
                requestScreenshot()
            } else {
                overlayController.showReadyExpanded()
            }
        }.onFailure(::failSession)
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (captureRequested.compareAndSet(true, false)) {
            runCatching { sampleFrameSignature(image) }
                .onSuccess { signature ->
                    changeDetector.onCaptureStarted(signature, SystemClock.elapsedRealtime())
                }
            processingFrameCaptured.set(true)
            processCapturedImage(
                image = image,
                generation = captureGeneration.get(),
                capturePlan = activeCapturePlan
            )
            return
        }
        if (!continuousTranslationEnabled.get() ||
            (captureInProgress.get() && !processingFrameCaptured.get())
        ) {
            image.close()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        if (lastSignatureSampleAt != Long.MIN_VALUE &&
            nowMs - lastSignatureSampleAt < FRAME_SIGNATURE_INTERVAL_MS
        ) {
            image.close()
            return
        }
        lastSignatureSampleAt = nowMs
        val signature = try {
            image.use(::sampleFrameSignature)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to sample screen frame", error)
            return
        }
        when (changeDetector.onFrame(signature, nowMs)) {
            ScreenFrameAction.NONE -> Unit
            ScreenFrameAction.MOVING -> {
                overlayController.showWaitingForStable(
                    changeDetector.currentMotionPlan()?.contentShiftY
                )
                discardStaleCaptureForMovement()
            }
            ScreenFrameAction.MOVING_UPDATE -> {
                overlayController.updateMovementPreview(
                    changeDetector.currentMotionPlan()?.contentShiftY
                )
            }
            ScreenFrameAction.CAPTURE -> {
                val capturePlan = changeDetector.consumeCapturePlan()
                Log.i(
                    TAG,
                    "Settled viewport: shiftY=${capturePlan?.contentShiftY ?: 0}, " +
                        "confidence=${capturePlan?.confidence ?: 0f}, " +
                        "overlap=${capturePlan?.overlapRatio ?: 0f}, " +
                        "error=${capturePlan?.registrationError ?: 0f}, " +
                        "consensus=${capturePlan?.consensusRatio ?: 0f}"
                )
                requestScreenshot(capturePlan)
            }
        }
    }

    private fun processCapturedImage(
        image: Image,
        generation: Int,
        capturePlan: ScrollCapturePlan?
    ) {
        val processingStartedAt = SystemClock.elapsedRealtime()
        processingJob?.cancel()
        processingJob = serviceScope.launch {
            var sourceBitmap: Bitmap? = null
            var translatedResult: BackgroundTranslatedOverlayResult? = null
            try {
                sourceBitmap = image.use(::imageToBitmap)
                val bitmap = sourceBitmap
                if (!hasVisiblePixels(bitmap)) {
                    bitmap.recycle()
                    sourceBitmap = null
                    captureRequested.set(true)
                    return@launch
                }
                timeoutJob?.cancel()
                timeoutJob = null
                overlayController.showProcessing()
                val activeMode = translationMode
                val activeExperienceMode = experienceMode
                val result = withTimeout(TRANSLATION_TIMEOUT_MS) {
                    translationMutex.withLock {
                        liveProcessor.translateForOverlay(
                            bitmap = bitmap,
                            mode = activeMode,
                            capturePlan = capturePlan,
                            overlayAlpha = LiveOverlayExperiencePolicy.translationWindowAlpha(
                                activeExperienceMode,
                                ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA
                            )
                        )
                    }
                }
                translatedResult = result
                if (isActive && generation == captureGeneration.get()) {
                    Log.i(
                        TAG,
                        "Overlay translation completed: totalMs=" +
                            "${SystemClock.elapsedRealtime() - processingStartedAt}, " +
                            "differential=${result.differentialApplied}, " +
                            "shiftY=${capturePlan?.contentShiftY ?: 0}, " +
                            "reused=${result.reusedRegionCount}, " +
                            "recognized=${result.recognizedCount}, patches=${result.patches.size}"
                    )
                    finishScreenshot(result, generation)
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
        if (!captureInProgress.compareAndSet(false, true)) return
        activeCapturePlan = capturePlan
        processingFrameCaptured.set(false)
        val generation = captureGeneration.incrementAndGet()
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
        val top = (image.height * SIGNATURE_TOP_CROP_RATIO).toInt()
        val bottom = (image.height * SIGNATURE_BOTTOM_RATIO).toInt().coerceAtLeast(top + 1)
        var sampleIndex = 0
        repeat(SIGNATURE_ROWS) { row ->
            val y = top + ((bottom - top - 1) * row / (SIGNATURE_ROWS - 1).coerceAtLeast(1))
            repeat(SIGNATURE_COLUMNS) { column ->
                val x = (image.width - 1) * column / (SIGNATURE_COLUMNS - 1).coerceAtLeast(1)
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
            sampleBottomPx = bottom
        )
    }

    private fun resolveDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun finishScreenshot(result: BackgroundTranslatedOverlayResult, generation: Int) {
        if (generation != captureGeneration.get()) {
            result.patches.recyclePatchBitmaps()
            return
        }
        timeoutJob?.cancel()
        timeoutJob = null
        captureRequested.set(false)
        captureInProgress.set(false)
        processingFrameCaptured.set(false)
        activeCapturePlan = null
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
        captureHandler?.post {
            changeDetector.onTranslationRendered(SystemClock.elapsedRealtime())
        }
        overlayController.showResult(
            patches = result.patches,
            sourceWidth = result.sourceWidth,
            sourceHeight = result.sourceHeight,
            recognizedCount = result.recognizedCount
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
        activeCapturePlan = null
        continuousTranslationEnabled.set(false)
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
        activeCapturePlan = null
        lastSignatureSampleAt = Long.MIN_VALUE
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
        activeCapturePlan = null
        timeoutJob?.cancel()
        timeoutJob = null
        processingJob?.cancel()
        processingJob = null
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildSessionNotification(capturing = false)
        )
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

    private fun applyResolvedExperienceMode() {
        val resolved = resolvedExperienceMode()
        if (experienceMode == resolved) return
        cancelActiveCapture(keepContinuousMode = false)
        experienceMode = resolved
        Log.i(TAG, "Live overlay experience changed: ${resolved.name}")
        overlayController.setExperienceMode(resolved)
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
        timeoutJob?.cancel()
        timeoutJob = null
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
        private const val TRANSLATION_TIMEOUT_MS = 90_000L
        private const val PIXEL_SAMPLE_COLUMNS = 32
        private const val PIXEL_SAMPLE_ROWS = 48
        private const val BLACK_PIXEL_THRESHOLD = 8
        private const val SIGNATURE_COLUMNS = 48
        private const val SIGNATURE_ROWS = 72
        private const val SIGNATURE_TOP_CROP_RATIO = 0.08f
        private const val SIGNATURE_BOTTOM_RATIO = 0.94f
        private const val FRAME_SIGNATURE_INTERVAL_MS = 75L
        private const val TAG = "ScreenCaptureSession"

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
