package com.example.imagetranslate.screenshot

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.example.imagetranslate.R
import com.example.imagetranslate.ui.ImageTranslateActivity

internal class ScreenshotResultNotificationFactory(
    private val context: Context,
    private val channelId: String
) {
    fun processing(): Notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_translate)
        .setContentTitle(context.getString(R.string.screenshot_processing_title))
        .setContentText(context.getString(R.string.screenshot_processing_text))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    fun waiting(
        uri: Uri,
        key: String,
        notificationId: Int
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(context.getString(R.string.screenshot_waiting_title))
            .setContentText(context.getString(R.string.screenshot_waiting_text))
            .setSubText(context.getString(R.string.screenshot_result_available_later))
            .setContentIntent(ignorePendingIntent(uri, key, notificationId))
            .setDeleteIntent(ignorePendingIntent(uri, key, notificationId))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        addResultActions(builder, uri, key, notificationId)
        return builder.build()
    }

    fun completed(
        uri: Uri,
        key: String,
        notificationId: Int,
        result: BackgroundTranslationResult
    ): Notification {
        val translatedCount = result.translatedLines.size
        val summary = if (result.recognizedCount == 0 || translatedCount == 0) {
            context.getString(R.string.screenshot_translation_no_text)
        } else {
            context.getString(
                R.string.screenshot_translation_summary,
                result.recognizedCount,
                translatedCount
            )
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(context.getString(R.string.screenshot_translation_complete_title))
            .setContentText(summary)
            .setSubText(context.getString(R.string.screenshot_result_available_later))
            .setContentIntent(ignorePendingIntent(uri, key, notificationId))
            .setDeleteIntent(ignorePendingIntent(uri, key, notificationId))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        addResultActions(builder, uri, key, notificationId)
        val bigText = BackgroundTranslationFormatter.bigText(result.translatedLines)
        if (bigText.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }
        return builder.build()
    }

    fun failed(uri: Uri, key: String, notificationId: Int): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle(context.getString(R.string.screenshot_translation_failed_title))
            .setContentText(context.getString(R.string.screenshot_translation_failed_text))
            .setSubText(context.getString(R.string.screenshot_result_available_later))
            .setContentIntent(ignorePendingIntent(uri, key, notificationId))
            .setDeleteIntent(ignorePendingIntent(uri, key, notificationId))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        addResultActions(builder, uri, key, notificationId)
        return builder.build()
    }

    fun saving(): Notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_save)
        .setContentTitle(context.getString(R.string.screenshot_background_saving_title))
        .setContentText(context.getString(R.string.screenshot_background_saving_text))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    fun saved(
        savedUri: Uri,
        key: String,
        notificationId: Int,
        replacedCount: Int
    ): Notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_save)
        .setContentTitle(context.getString(R.string.screenshot_background_saved_title))
        .setContentText(
            context.getString(R.string.screenshot_background_saved_summary, replacedCount)
        )
        .setSubText(context.getString(R.string.screenshot_result_available_later))
        .setContentIntent(ignorePendingIntent(savedUri, key, notificationId))
        .setDeleteIntent(ignorePendingIntent(savedUri, key, notificationId))
        .addAction(
            R.drawable.ic_image_placeholder,
            context.getString(R.string.screenshot_action_view_result),
            activityPendingIntent(
                savedUri,
                key,
                notificationId,
                ScreenshotMonitorService.ACTION_OPEN_SCREENSHOT,
                REQUEST_VIEW_OFFSET
            )
        )
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .build()

    fun saveFailed(
        uri: Uri,
        key: String,
        notificationId: Int
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_save)
            .setContentTitle(context.getString(R.string.screenshot_background_save_failed_title))
            .setContentText(context.getString(R.string.screenshot_background_save_failed_text))
            .setSubText(context.getString(R.string.screenshot_result_available_later))
            .setContentIntent(ignorePendingIntent(uri, key, notificationId))
            .setDeleteIntent(ignorePendingIntent(uri, key, notificationId))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        addResultActions(builder, uri, key, notificationId)
        return builder.build()
    }

    private fun addResultActions(
        builder: NotificationCompat.Builder,
        uri: Uri,
        key: String,
        notificationId: Int
    ) {
        ScreenshotNotificationInteractionPolicy.actionInteractions.forEach { interaction ->
            when (interaction) {
                ScreenshotResultInteraction.TRANSLATE -> builder.addAction(
                    R.drawable.ic_translate,
                    context.getString(R.string.screenshot_action_translate),
                    servicePendingIntent(
                        uri,
                        key,
                        notificationId,
                        ScreenshotMonitorService.ACTION_TRANSLATE_IN_BACKGROUND,
                        REQUEST_TRANSLATE_OFFSET
                    )
                )
                ScreenshotResultInteraction.TRANSLATE_AND_VIEW -> builder.addAction(
                    R.drawable.ic_image_placeholder,
                    context.getString(R.string.screenshot_action_translate_and_view),
                    activityPendingIntent(
                        uri,
                        key,
                        notificationId,
                        ScreenshotMonitorService.ACTION_TRANSLATE_SCREENSHOT,
                        REQUEST_VIEW_OFFSET
                    )
                )
                ScreenshotResultInteraction.TRANSLATE_AND_SAVE -> builder.addAction(
                    R.drawable.ic_save,
                    context.getString(R.string.screenshot_action_translate_and_save),
                    servicePendingIntent(
                        uri,
                        key,
                        notificationId,
                        ScreenshotMonitorService.ACTION_TRANSLATE_AND_SAVE_IN_BACKGROUND,
                        REQUEST_SAVE_OFFSET
                    )
                )
                ScreenshotResultInteraction.IGNORE -> error(
                    "Dismiss interaction cannot be a notification button"
                )
            }
        }
    }

    private fun activityPendingIntent(
        uri: Uri,
        key: String,
        notificationId: Int,
        activityAction: String,
        requestOffset: Int
    ): PendingIntent {
        val intent = Intent(context, ImageTranslateActivity::class.java).apply {
            action = activityAction
            data = uri
            putExtra(ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID, notificationId)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        return PendingIntent.getActivity(
            context,
            key.hashCode() + requestOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(
        uri: Uri,
        key: String,
        notificationId: Int,
        serviceAction: String,
        requestOffset: Int
    ): PendingIntent = PendingIntent.getService(
        context,
        key.hashCode() + requestOffset,
        Intent(context, ScreenshotMonitorService::class.java).apply {
            action = serviceAction
            data = uri
            putExtra(ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID, notificationId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ignorePendingIntent(
        uri: Uri,
        key: String,
        notificationId: Int
    ): PendingIntent = PendingIntent.getService(
        context,
        key.hashCode() + REQUEST_IGNORE_OFFSET,
        Intent(context, ScreenshotMonitorService::class.java).apply {
            action = ScreenshotMonitorService.ACTION_IGNORE_SCREENSHOT
            data = uri
            putExtra(ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID, notificationId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_TRANSLATE_OFFSET = 20_000
        const val REQUEST_VIEW_OFFSET = 30_000
        const val REQUEST_SAVE_OFFSET = 40_000
        const val REQUEST_IGNORE_OFFSET = 50_000
    }
}
