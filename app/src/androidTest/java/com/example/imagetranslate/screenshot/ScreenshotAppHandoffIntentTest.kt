package com.example.imagetranslate.screenshot

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.ui.ImageTranslateActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotAppHandoffIntentTest {
    @Test
    fun capturedScreenshotOpensTheFullTranslationWorkflow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://media/external/images/media/42")

        val intent = ScreenshotAppHandoffIntentFactory.translate(
            context,
            ScreenshotOverlayItem(uri, uri.toString(), notificationId = 2342)
        )

        assertEquals(ScreenshotMonitorService.ACTION_TRANSLATE_SCREENSHOT, intent.action)
        assertEquals(uri, intent.data)
        assertEquals(ImageTranslateActivity::class.java.name, intent.component?.className)
        assertEquals(
            2342,
            intent.getIntExtra(ScreenshotMonitorService.EXTRA_RESULT_NOTIFICATION_ID, 0)
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
