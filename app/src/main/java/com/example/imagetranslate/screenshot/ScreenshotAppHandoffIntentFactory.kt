package com.example.imagetranslate.screenshot

import android.content.Context
import android.content.Intent
import com.example.imagetranslate.ui.ImageTranslateActivity

internal object ScreenshotAppHandoffIntentFactory {
    fun translate(context: Context, item: ScreenshotOverlayItem): Intent =
        Intent(context, ImageTranslateActivity::class.java).apply {
            action = ScreenshotMonitorService.ACTION_TRANSLATE_SCREENSHOT
            data = item.uri
            putExtra(
                ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID,
                item.notificationId
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
}
