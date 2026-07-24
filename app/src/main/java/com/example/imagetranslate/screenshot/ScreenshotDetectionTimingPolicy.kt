package com.example.imagetranslate.screenshot

internal object ScreenshotDetectionTimingPolicy {
    const val MAX_ATTEMPTS = 8
    const val BACKGROUND_POLL_INTERVAL_MS = 750L
    private const val RETRY_DELAY_MS = 120L

    fun delayBeforeAttempt(attempt: Int): Long =
        if (attempt <= 0) 0L else RETRY_DELAY_MS
}
