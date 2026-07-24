package com.example.imagetranslate.screenshot

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotResultNotificationTest {
    @Test
    fun exposesContentDismissAndTranslationCommands() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notification = ScreenshotResultNotificationFactory(
            context,
            "screenshot_result_test"
        ).completed(
            uri = Uri.parse("content://media/external/images/media/42"),
            key = "content://media/external/images/media/42",
            notificationId = 2342,
            result = BackgroundTranslationResult(
                recognizedCount = 1,
                translatedLines = listOf(BackgroundTranslationLine("Hello", "你好"))
            )
        )

        assertNotNull(notification.contentIntent)
        assertNotNull(notification.deleteIntent)
        assertPersistentUntilUserAction(notification)
        assertEquals(
            listOf("执行翻译", "翻译并查看", "翻译并保存"),
            notification.actions.map { it.title.toString() }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(notification.contentIntent.isService)
            assertTrue(notification.deleteIntent.isService)
            assertTrue(notification.actions[0].actionIntent.isService)
            assertTrue(notification.actions[1].actionIntent.isActivity)
            assertFalse(notification.actions[2].actionIntent.isActivity)
            assertTrue(notification.actions[2].actionIntent.isService)
        }
    }

    @Test
    fun terminalResultNotificationsRemainAvailableForLaterActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = ScreenshotResultNotificationFactory(context, "screenshot_result_test")
        val uri = Uri.parse("content://media/external/images/media/43")

        val notifications = listOf(
            factory.waiting(uri, uri.toString(), 2343),
            factory.failed(uri, uri.toString(), 2343),
            factory.saved(uri, uri.toString(), 2343, replacedCount = 2),
            factory.saveFailed(uri, uri.toString(), 2343)
        )

        notifications.forEach(::assertPersistentUntilUserAction)
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
    fun gallerySaverWritesAndFinalizesTranslatedImage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        var savedUri: Uri? = null
        try {
            val uri = TranslatedImageGallerySaver(context).save(bitmap)
            savedUri = uri

            val byteCount = context.contentResolver.openInputStream(uri).use { input ->
                input?.readBytes()?.size ?: 0
            }
            assertTrue(byteCount > 0)
        } finally {
            savedUri?.let { context.contentResolver.delete(it, null, null) }
            bitmap.recycle()
        }
    }

    private fun assertPersistentUntilUserAction(notification: Notification) {
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }
}
