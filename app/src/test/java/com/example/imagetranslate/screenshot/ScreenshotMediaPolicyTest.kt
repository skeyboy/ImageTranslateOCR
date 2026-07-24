package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotMediaPolicyTest {
    @Test
    fun recognizesCommonScreenshotNamesAndDirectories() {
        assertTrue(
            ScreenshotMediaPolicy.looksLikeScreenshot(
                "Screenshot_20260723-102834_App.png",
                "Pictures/Screenshots/"
            )
        )
        assertTrue(
            ScreenshotMediaPolicy.looksLikeScreenshot(
                "屏幕截图_20260723.png",
                "Pictures/"
            )
        )
        assertTrue(
            ScreenshotMediaPolicy.looksLikeScreenshot(
                "20260723.png",
                "DCIM/截屏/"
            )
        )
    }

    @Test
    fun rejectsOrdinaryImages() {
        assertFalse(
            ScreenshotMediaPolicy.looksLikeScreenshot(
                "PXL_20260723_102834.jpg",
                "DCIM/Camera/"
            )
        )
        assertFalse(
            ScreenshotMediaPolicy.looksLikeScreenshot(
                "translated_1234.jpg",
                "Pictures/ImageTranslate/"
            )
        )
    }

    @Test
    fun activeCaptureNameIsStableAndExcludedFromMonitorNotifications() {
        val displayName = ScreenshotMediaPolicy.activeCaptureDisplayName(123456789L)

        assertTrue(displayName == "ImageTranslate_capture_123456789.png")
        assertTrue(ScreenshotMediaPolicy.isActiveCapture(displayName))
        assertFalse(
            ScreenshotMediaPolicy.shouldNotify(
                displayName = displayName,
                relativePath = "Pictures/Screenshots/",
                mimeType = "image/png",
                dateAddedSeconds = 101L,
                sessionStartedAtSeconds = 100L
            )
        )
    }

    @Test
    fun notificationRequiresRecentScreenshotImage() {
        assertTrue(
            ScreenshotMediaPolicy.shouldNotify(
                displayName = "Screenshot_1.png",
                relativePath = "Pictures/Screenshots/",
                mimeType = "image/png",
                dateAddedSeconds = 100L,
                sessionStartedAtSeconds = 100L
            )
        )
        assertFalse(
            ScreenshotMediaPolicy.shouldNotify(
                displayName = "Screenshot_1.png",
                relativePath = "Pictures/Screenshots/",
                mimeType = "application/octet-stream",
                dateAddedSeconds = 100L,
                sessionStartedAtSeconds = 100L
            )
        )
        assertFalse(
            ScreenshotMediaPolicy.shouldNotify(
                displayName = "Screenshot_old.png",
                relativePath = "Pictures/Screenshots/",
                mimeType = "image/png",
                dateAddedSeconds = 90L,
                sessionStartedAtSeconds = 100L
            )
        )
    }
}
