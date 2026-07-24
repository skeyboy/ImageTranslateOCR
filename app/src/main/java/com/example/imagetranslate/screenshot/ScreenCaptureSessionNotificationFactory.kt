package com.example.imagetranslate.screenshot

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.imagetranslate.R

internal class ScreenCaptureSessionNotificationFactory(
    private val context: Context,
    private val channelId: String
) {
    fun build(capturing: Boolean): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_screenshot)
            .setContentTitle(context.getString(R.string.active_screenshot_notification_title))
            .setContentText(
                context.getString(
                    if (capturing) {
                        R.string.active_screenshot_notification_capturing
                    } else {
                        R.string.active_screenshot_notification_text
                    }
                )
            )
            .setContentIntent(
                servicePendingIntent(
                    OneShotScreenCaptureService.ACTION_TAKE_SCREENSHOT,
                    REQUEST_CAPTURE_FROM_CONTENT
                )
            )
            .addAction(
                R.drawable.ic_screenshot,
                context.getString(R.string.active_screenshot_action_capture),
                servicePendingIntent(
                    OneShotScreenCaptureService.ACTION_TAKE_SCREENSHOT,
                    REQUEST_CAPTURE
                )
            )
            .addAction(
                R.drawable.ic_close,
                context.getString(R.string.active_screenshot_action_stop),
                servicePendingIntent(
                    OneShotScreenCaptureService.ACTION_STOP_SESSION,
                    REQUEST_STOP
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (capturing) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, OneShotScreenCaptureService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private companion object {
        const val REQUEST_CAPTURE_FROM_CONTENT = 2400
        const val REQUEST_CAPTURE = 2401
        const val REQUEST_STOP = 2402
    }
}
