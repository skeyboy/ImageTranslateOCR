package com.example.imagetranslate.screenshot

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.imagetranslate.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class ScreenshotMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detectedScreenshots = LinkedHashMap<String, DetectedScreenshot>()
    private val translationMutex = Mutex()
    private val screenshotJobs = mutableMapOf<String, Job>()
    private val handoffFallbackJobs = mutableMapOf<String, Job>()
    private val resultNotificationFactory by lazy {
        ScreenshotResultNotificationFactory(this, RESULT_CHANNEL_ID)
    }
    private lateinit var overlayController: ScreenshotOverlayController
    private var backgroundPollingJob: Job? = null
    private var observerRegistered = false
    private var sessionStartedAtSeconds = 0L
    private var userRequestedStop = false

    override fun onCreate() {
        super.onCreate()
        overlayController = ScreenshotOverlayController(
            this,
            object : ScreenshotOverlayController.Listener {
                override fun onIgnore(item: ScreenshotOverlayItem) {
                    cancelScreenshotJob(item.key)
                    cancelResultNotification(item.notificationId)
                    overlayController.dismiss(item.key)
                }

                override fun onTranslate(item: ScreenshotOverlayItem) {
                    retryBackgroundTranslation(item.toServiceIntent(ACTION_TRANSLATE_IN_BACKGROUND))
                }

                override fun onTranslateAndView(item: ScreenshotOverlayItem) {
                    openTranslationFromOverlay(item)
                }

                override fun onTranslateAndSave(item: ScreenshotOverlayItem) {
                    translateAndSaveInBackground(
                        item.toServiceIntent(ACTION_TRANSLATE_AND_SAVE_IN_BACKGROUND)
                    )
                }

                override fun onAutoTranslateChanged(
                    item: ScreenshotOverlayItem,
                    enabled: Boolean
                ) {
                    getSharedPreferences(OVERLAY_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREFERENCE_AUTO_TRANSLATE, enabled)
                        .apply()
                    if (enabled) {
                        retryBackgroundTranslation(
                            item.toServiceIntent(ACTION_TRANSLATE_IN_BACKGROUND)
                        )
                    }
                }
            }
        )
    }

    private val imageObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            inspectMediaChange(null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            inspectMediaChange(uri)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userRequestedStop = true
                cancelScheduledRestart()
                ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(this, false)
                ScreenshotMonitorPreferences.setStartOnBootEnabled(this, false)
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_IGNORE_SCREENSHOT -> {
                ignoreScreenshotNotification(intent)
                return START_NOT_STICKY
            }
            ACTION_TRANSLATE_IN_BACKGROUND -> {
                retryBackgroundTranslation(intent)
                return START_NOT_STICKY
            }
            ACTION_TRANSLATE_AND_SAVE_IN_BACKGROUND -> {
                translateAndSaveInBackground(intent)
                return START_NOT_STICKY
            }
            ACTION_PREPARE_FOR_CAPTURE -> {
                overlayController.dismiss(immediate = true)
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_HANDLE_CAPTURED_SCREENSHOT -> {
                intent.data?.let { uri -> processScreenshot(uri, isPending = false) }
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_CONFIRM_APP_HANDOFF -> {
                confirmAppHandoff(intent)
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_DISMISS_OVERLAY -> {
                overlayController.dismiss(immediate = true)
                if (!isRunning) stopSelf(startId)
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_START) {
            userRequestedStop = false
            cancelScheduledRestart()
        }
        val shouldStart = intent?.action == ACTION_START ||
            (intent == null &&
                ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(this))
        if (!shouldStart || isRunning) {
            return if (isRunning) START_STICKY else START_NOT_STICKY
        }

        createNotificationChannels()
        startForeground(MONITOR_NOTIFICATION_ID, buildMonitorNotification())
        if (!hasFullImageAccess()) {
            ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(this, false)
            stopMonitoring()
            return START_NOT_STICKY
        }
        sessionStartedAtSeconds = System.currentTimeMillis() / 1000L
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            imageObserver
        )
        observerRegistered = true
        isRunning = true
        startBackgroundPollingFallback()
        ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(this, true)
        return START_STICKY
    }

    override fun onDestroy() {
        val shouldRestart = shouldScheduleRestart()
        stopMonitoring(stopService = false)
        if (shouldRestart) scheduleRestart()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (shouldScheduleRestart()) scheduleRestart()
    }

    private fun inspectMediaChange(changedUri: Uri?) {
        if (!isRunning) return
        serviceScope.launch {
            repeat(ScreenshotDetectionTimingPolicy.MAX_ATTEMPTS) { attempt ->
                val retryDelay = ScreenshotDetectionTimingPolicy.delayBeforeAttempt(attempt)
                if (retryDelay > 0) delay(retryDelay)
                val candidate = queryCandidate(changedUri)
                if (candidate != null && processIfScreenshot(candidate)) {
                    return@launch
                }
            }
        }
    }

    private fun startBackgroundPollingFallback() {
        if (backgroundPollingJob?.isActive == true) return
        backgroundPollingJob = serviceScope.launch {
            while (isActive) {
                delay(ScreenshotDetectionTimingPolicy.BACKGROUND_POLL_INTERVAL_MS)
                if (!isRunning) continue
                queryCandidate(null)?.let(::processIfScreenshot)
            }
        }
    }

    private fun queryCandidate(changedUri: Uri?): MediaCandidate? {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
                add(MediaStore.Images.Media.IS_PENDING)
            }
        }.toTypedArray()

        val exactItemUri = changedUri?.takeIf {
            it.lastPathSegment?.toLongOrNull() != null
        }
        val queryUri = exactItemUri ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val selection = if (exactItemUri == null) {
            "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        } else {
            null
        }
        val selectionArgs = if (selection == null) {
            null
        } else {
            arrayOf((sessionStartedAtSeconds - 2L).toString())
        }
        val sortOrder = if (exactItemUri == null) {
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        } else {
            null
        }

        return runCatching {
            contentResolver.query(queryUri, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    var inspectedCandidates = 0
                    while (cursor.moveToNext()) {
                        if (exactItemUri == null &&
                            inspectedCandidates++ >= MAX_QUERY_CANDIDATES
                        ) {
                            break
                        }
                        val id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        )
                        val name = cursor.getString(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        ).orEmpty()
                        val dateAdded = cursor.getLong(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        )
                        val mimeType = cursor.getString(
                            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                        ).orEmpty()
                        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                            ).orEmpty()
                        } else {
                            ""
                        }
                        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.getInt(
                                cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)
                            ) != 0
                        } else {
                            false
                        }
                        val itemUri = exactItemUri ?: ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        val candidate = MediaCandidate(
                            uri = itemUri,
                            displayName = name,
                            relativePath = relativePath,
                            mimeType = mimeType,
                            dateAddedSeconds = dateAdded,
                            isPending = pending
                        )
                        if (exactItemUri != null || candidate.looksLikeScreenshot()) {
                            return@use candidate
                        }
                    }
                    null
                }
        }.getOrNull()
    }

    private fun processIfScreenshot(candidate: MediaCandidate): Boolean {
        if (!ScreenshotMediaPolicy.shouldNotify(
                displayName = candidate.displayName,
                relativePath = candidate.relativePath,
                mimeType = candidate.mimeType,
                dateAddedSeconds = candidate.dateAddedSeconds,
                sessionStartedAtSeconds = sessionStartedAtSeconds
            )
        ) {
            return true
        }
        return processScreenshot(candidate.uri, candidate.isPending)
    }

    private fun processScreenshot(uri: Uri, isPending: Boolean): Boolean {
        createNotificationChannels()
        val key = uri.toString()
        val detected = synchronized(detectedScreenshots) {
            val existing = detectedScreenshots[key]
            if (existing != null) {
                existing
            } else {
                val notificationId = resultNotificationId(key)
                val detected = DetectedScreenshot(
                    item = ScreenshotOverlayItem(uri, key, notificationId)
                )
                detectedScreenshots[key] = detected
                while (detectedScreenshots.size > MAX_SEEN_ITEMS) {
                    detectedScreenshots.remove(detectedScreenshots.keys.first())
                }
                detected
            }
        }
        if (isPending) return false

        val shouldStartProcessing = synchronized(detectedScreenshots) {
            if (detected.processingStarted) {
                false
            } else {
                detected.processingStarted = true
                true
            }
        }
        if (!shouldStartProcessing) return true

        if (ScreenshotMonitorPreferences.isOverlayEnabled(this)) {
            showHandoffFallback(detected.item)
        } else if (!openScreenshotInApp(detected.item)) {
            showHandoffFallback(detected.item)
        }
        return true
    }

    private suspend fun translateScreenshot(uri: Uri, key: String, notificationId: Int) {
        val translator = BackgroundScreenshotTranslator(applicationContext)
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Unable to decode screenshot")
            val result = try {
                withTimeout(BACKGROUND_TRANSLATION_TIMEOUT_MS) {
                    translator.translate(bitmap)
                }
            } finally {
                bitmap.recycle()
            }
            postTranslationResult(uri, key, notificationId, result)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            postTranslationFailure(uri, key, notificationId)
        }
    }

    private fun postTranslationResult(
        uri: Uri,
        key: String,
        notificationId: Int,
        result: BackgroundTranslationResult
    ) {
        overlayController.showTranslationResult(key, result)
        getSystemService(NotificationManager::class.java)
            .notify(
                notificationId,
                resultNotificationFactory.completed(uri, key, notificationId, result)
            )
    }

    private fun postTranslationFailure(uri: Uri, key: String, notificationId: Int) {
        overlayController.showFailure(key)
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            resultNotificationFactory.failed(uri, key, notificationId)
        )
    }

    private fun postProcessingNotification(notificationId: Int) {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            resultNotificationFactory.processing()
        )
    }

    private fun postWaitingNotification(uri: Uri, key: String, notificationId: Int) {
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            resultNotificationFactory.waiting(uri, key, notificationId)
        )
    }

    private fun retryBackgroundTranslation(intent: Intent) {
        val uri = intent.data ?: return
        val key = uri.toString()
        val notificationId = intent.getIntExtra(
            EXTRA_RESULT_NOTIFICATION_ID,
            resultNotificationId(key)
        )
        createNotificationChannels()
        postProcessingNotification(notificationId)
        overlayController.showProcessing(key)
        launchScreenshotJob(key) {
            translationMutex.withLock {
                translateScreenshot(uri, key, notificationId)
            }
        }
    }

    private fun translateAndSaveInBackground(intent: Intent) {
        val uri = intent.data ?: return
        val key = uri.toString()
        val notificationId = intent.getIntExtra(
            EXTRA_RESULT_NOTIFICATION_ID,
            resultNotificationId(key)
        )
        createNotificationChannels()
        overlayController.showSaving(key)
        getSystemService(NotificationManager::class.java).notify(
            notificationId,
            resultNotificationFactory.saving()
        )
        launchScreenshotJob(key) {
            translationMutex.withLock {
                translateAndSaveScreenshot(uri, key, notificationId)
            }
        }
    }

    private suspend fun translateAndSaveScreenshot(
        uri: Uri,
        key: String,
        notificationId: Int
    ) {
        try {
            val sourceBitmap = contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("Unable to decode screenshot")
            try {
                val result = withTimeout(BACKGROUND_IMAGE_TRANSLATION_TIMEOUT_MS) {
                    BackgroundTranslatedImageProcessor(applicationContext).translate(sourceBitmap)
                }
                val savedUri = try {
                    TranslatedImageGallerySaver(this).save(result.bitmap)
                } finally {
                    result.bitmap.recycle()
                }
                getSystemService(NotificationManager::class.java).notify(
                    notificationId,
                    resultNotificationFactory.saved(
                        savedUri,
                        key,
                        notificationId,
                        result.replacedCount
                    )
                )
                overlayController.showSaved(key, result.replacedCount)
            } finally {
                sourceBitmap.recycle()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            overlayController.showFailure(key)
            getSystemService(NotificationManager::class.java).notify(
                notificationId,
                resultNotificationFactory.saveFailed(uri, key, notificationId)
            )
        }
    }

    private fun ignoreScreenshotNotification(intent: Intent) {
        val key = intent.data?.toString().orEmpty()
        if (key.isNotEmpty()) cancelScreenshotJob(key)
        val fallbackId = if (key.isEmpty()) 0 else resultNotificationId(key)
        val notificationId = intent.getIntExtra(EXTRA_RESULT_NOTIFICATION_ID, fallbackId)
        if (notificationId != 0) {
            cancelResultNotification(notificationId)
        }
        if (key.isNotEmpty()) overlayController.dismiss(key)
    }

    private fun launchScreenshotJob(key: String, block: suspend () -> Unit) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                synchronized(screenshotJobs) {
                    if (screenshotJobs[key] === coroutineContext[Job]) {
                        screenshotJobs.remove(key)
                    }
                }
            }
        }
        synchronized(screenshotJobs) {
            screenshotJobs.put(key, job)?.cancel()
        }
        job.start()
    }

    private fun cancelScreenshotJob(key: String) {
        synchronized(screenshotJobs) {
            screenshotJobs.remove(key)?.cancel()
        }
    }

    private fun cancelResultNotification(notificationId: Int) {
        getSystemService(NotificationManager::class.java).cancel(notificationId)
    }

    private fun openTranslationFromOverlay(item: ScreenshotOverlayItem) {
        openScreenshotInApp(item)
    }

    private fun openScreenshotInApp(item: ScreenshotOverlayItem): Boolean {
        cancelResultNotification(item.notificationId)
        overlayController.dismiss(item.key)
        scheduleHandoffFallback(item)
        val started = runCatching {
            startActivity(ScreenshotAppHandoffIntentFactory.translate(this, item))
        }.isSuccess
        if (!started) cancelHandoffFallback(item.key)
        return started
    }

    private fun scheduleHandoffFallback(item: ScreenshotOverlayItem) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            delay(APP_HANDOFF_ACK_TIMEOUT_MS)
            synchronized(handoffFallbackJobs) {
                handoffFallbackJobs.remove(item.key)
            }
            showHandoffFallback(item)
        }
        synchronized(handoffFallbackJobs) {
            handoffFallbackJobs.put(item.key, job)?.cancel()
        }
        job.start()
    }

    private fun confirmAppHandoff(intent: Intent) {
        val key = intent.data?.toString().orEmpty()
        if (key.isEmpty()) return
        cancelHandoffFallback(key)
        synchronized(detectedScreenshots) {
            detectedScreenshots[key]?.item
        }?.let { item ->
            cancelResultNotification(item.notificationId)
            overlayController.dismiss(key)
        }
    }

    private fun cancelHandoffFallback(key: String) {
        synchronized(handoffFallbackJobs) {
            handoffFallbackJobs.remove(key)?.cancel()
        }
    }

    private fun showHandoffFallback(item: ScreenshotOverlayItem) {
        if (ScreenshotMonitorPreferences.isOverlayEnabled(this)) {
            val autoTranslate = getSharedPreferences(OVERLAY_PREFERENCES, MODE_PRIVATE)
                .getBoolean(PREFERENCE_AUTO_TRANSLATE, false)
            overlayController.show(item, autoTranslate = autoTranslate)
            overlayController.showReady(item.key)
            serviceScope.launch {
                loadOverlayPreview(item.uri)?.let { preview ->
                    overlayController.showPreview(item.key, preview)
                }
            }
            if (autoTranslate) {
                retryBackgroundTranslation(item.toServiceIntent(ACTION_TRANSLATE_IN_BACKGROUND))
                return
            }
        }
        postWaitingNotification(item.uri, item.key, item.notificationId)
    }

    private fun ScreenshotOverlayItem.toServiceIntent(serviceAction: String): Intent =
        Intent(this@ScreenshotMonitorService, ScreenshotMonitorService::class.java).apply {
            action = serviceAction
            data = uri
            putExtra(EXTRA_RESULT_NOTIFICATION_ID, notificationId)
        }

    private fun loadOverlayPreview(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                contentResolver.loadThumbnail(
                    uri,
                    Size(OVERLAY_PREVIEW_MAX_SIDE, OVERLAY_PREVIEW_MAX_SIDE),
                    null
                )
            }.getOrNull()
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = LEGACY_PREVIEW_SAMPLE_SIZE }
                )
            }
        }.getOrNull()
    }

    private fun resultNotificationId(key: String): Int =
        RESULT_NOTIFICATION_BASE_ID + (key.hashCode() and 0x0FFF)

    private fun buildMonitorNotification() = NotificationCompat.Builder(this, MONITOR_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_translate)
        .setContentTitle(getString(R.string.screenshot_monitor_notification_title))
        .setContentText(getString(R.string.screenshot_monitor_notification_text))
        .setContentIntent(
            PendingIntent.getService(
                this,
                0,
                Intent(this, ScreenshotMonitorService::class.java).setAction(ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            R.drawable.ic_close,
            getString(R.string.screenshot_monitor_stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ScreenshotMonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    MONITOR_CHANNEL_ID,
                    getString(R.string.screenshot_monitor_channel),
                    NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    getString(R.string.screenshot_result_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
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

    private fun shouldScheduleRestart(): Boolean =
        ScreenshotMonitorRestartPolicy.shouldScheduleRestart(
            backgroundMonitoringEnabled =
                ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(this),
            userRequestedStop = userRequestedStop
        )

    private fun scheduleRestart() {
        val pendingIntent = restartPendingIntent(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching {
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pendingIntent
            )
        }
    }

    private fun cancelScheduledRestart() {
        val pendingIntent = restartPendingIntent(PendingIntent.FLAG_NO_CREATE) ?: return
        runCatching {
            getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun restartPendingIntent(flags: Int): PendingIntent? {
        val intent = Intent(this, ScreenshotMonitorService::class.java)
            .setAction(ACTION_START)
        val immutableFlags = flags or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                RESTART_REQUEST_CODE,
                intent,
                immutableFlags
            )
        } else {
            PendingIntent.getService(this, RESTART_REQUEST_CODE, intent, immutableFlags)
        }
    }

    private fun stopMonitoring(stopService: Boolean = true) {
        if (observerRegistered) {
            runCatching { contentResolver.unregisterContentObserver(imageObserver) }
            observerRegistered = false
        }
        isRunning = false
        backgroundPollingJob?.cancel()
        backgroundPollingJob = null
        synchronized(screenshotJobs) {
            screenshotJobs.values.forEach(Job::cancel)
            screenshotJobs.clear()
        }
        synchronized(handoffFallbackJobs) {
            handoffFallbackJobs.values.forEach(Job::cancel)
            handoffFallbackJobs.clear()
        }
        if (::overlayController.isInitialized) {
            overlayController.dismiss(immediate = true)
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (stopService) stopSelf()
    }

    private data class MediaCandidate(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val dateAddedSeconds: Long,
        val isPending: Boolean
    ) {
        fun looksLikeScreenshot(): Boolean = ScreenshotMediaPolicy.looksLikeScreenshot(
            displayName,
            relativePath
        )
    }

    private data class DetectedScreenshot(
        val item: ScreenshotOverlayItem,
        var processingStarted: Boolean = false
    )

    companion object {
        const val ACTION_START = "com.example.imagetranslate.screenshot.START"
        const val ACTION_STOP = "com.example.imagetranslate.screenshot.STOP"
        const val ACTION_OPEN_SCREENSHOT =
            "com.example.imagetranslate.screenshot.OPEN"
        const val ACTION_TRANSLATE_SCREENSHOT =
            "com.example.imagetranslate.screenshot.TRANSLATE"
        const val ACTION_TRANSLATE_IN_BACKGROUND =
            "com.example.imagetranslate.screenshot.TRANSLATE_IN_BACKGROUND"
        const val ACTION_TRANSLATE_AND_SAVE_IN_BACKGROUND =
            "com.example.imagetranslate.screenshot.TRANSLATE_AND_SAVE_IN_BACKGROUND"
        const val ACTION_IGNORE_SCREENSHOT =
            "com.example.imagetranslate.screenshot.IGNORE"
        const val ACTION_PREPARE_FOR_CAPTURE =
            "com.example.imagetranslate.screenshot.PREPARE_FOR_CAPTURE"
        const val ACTION_HANDLE_CAPTURED_SCREENSHOT =
            "com.example.imagetranslate.screenshot.HANDLE_CAPTURED_SCREENSHOT"
        const val ACTION_CONFIRM_APP_HANDOFF =
            "com.example.imagetranslate.screenshot.CONFIRM_APP_HANDOFF"
        const val ACTION_DISMISS_OVERLAY =
            "com.example.imagetranslate.screenshot.DISMISS_OVERLAY"
        const val EXTRA_RESULT_NOTIFICATION_ID =
            "com.example.imagetranslate.screenshot.RESULT_NOTIFICATION_ID"
        private const val MONITOR_CHANNEL_ID = "screenshot_monitor"
        private const val RESULT_CHANNEL_ID = "screenshot_detected"
        private const val MONITOR_NOTIFICATION_ID = 2201
        private const val RESULT_NOTIFICATION_BASE_ID = 2300
        private const val RESTART_REQUEST_CODE = 2299
        private const val RESTART_DELAY_MS = 1_500L
        private const val MAX_SEEN_ITEMS = 100
        private const val MAX_QUERY_CANDIDATES = 8
        private const val APP_HANDOFF_ACK_TIMEOUT_MS = 1_200L
        private const val BACKGROUND_TRANSLATION_TIMEOUT_MS = 120_000L
        private const val BACKGROUND_IMAGE_TRANSLATION_TIMEOUT_MS = 180_000L
        private const val OVERLAY_PREVIEW_MAX_SIDE = 720
        private const val LEGACY_PREVIEW_SAMPLE_SIZE = 4
        private const val OVERLAY_PREFERENCES = "screenshot_overlay"
        private const val PREFERENCE_AUTO_TRANSLATE = "auto_translate"
        @Volatile
        var isRunning: Boolean = false
            private set
    }

}
