package com.example.imagetranslate.screenshot

import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenCaptureSessionNotificationTest {
    @Test
    fun activeSessionNotificationProvidesCaptureAndStopActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = ScreenCaptureSessionNotificationFactory(
            context,
            "screen_capture_session_test"
        ).build(capturing = false)

        assertNotNull(notification.contentIntent)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(
            listOf("开始翻译", "结束"),
            notification.actions.map { it.title.toString() }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(notification.contentIntent.isService)
            notification.actions.forEach { action ->
                assertTrue(action.actionIntent.isService)
            }
        }
    }

    @Test
    fun captureInProgressUsesIndeterminateProgressWithoutDroppingActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = ScreenCaptureSessionNotificationFactory(
            context,
            "screen_capture_session_test"
        ).build(capturing = true)

        assertEquals(2, notification.actions.size)
        assertTrue(notification.extras.getBoolean("android.progressIndeterminate"))
    }
}
