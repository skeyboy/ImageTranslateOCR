package com.example.imagetranslate.screenshot

import java.util.Locale

internal object ScreenshotMediaPolicy {
    const val ACTIVE_CAPTURE_PREFIX = "ImageTranslate_capture_"

    private val screenshotMarkers = listOf(
        "screenshot",
        "screen_shot",
        "screen-shot",
        "screen capture",
        "截屏",
        "截图"
    )

    fun activeCaptureDisplayName(timestampMillis: Long): String =
        "$ACTIVE_CAPTURE_PREFIX$timestampMillis.png"

    fun looksLikeScreenshot(displayName: String, relativePath: String): Boolean {
        val description = "$relativePath/$displayName".lowercase(Locale.ROOT)
        return screenshotMarkers.any(description::contains)
    }

    fun isActiveCapture(displayName: String): Boolean =
        displayName.startsWith(ACTIVE_CAPTURE_PREFIX, ignoreCase = true)

    fun shouldNotify(
        displayName: String,
        relativePath: String,
        mimeType: String,
        dateAddedSeconds: Long,
        sessionStartedAtSeconds: Long
    ): Boolean =
        dateAddedSeconds >= sessionStartedAtSeconds - 2L &&
            mimeType.startsWith("image/") &&
            !isActiveCapture(displayName) &&
            looksLikeScreenshot(displayName, relativePath)
}
