package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotDetectionTimingPolicyTest {
    @Test
    fun firstMediaQueryHasNoArtificialDelay() {
        assertEquals(0L, ScreenshotDetectionTimingPolicy.delayBeforeAttempt(0))
    }

    @Test
    fun retriesUseShortBoundedIntervals() {
        assertEquals(8, ScreenshotDetectionTimingPolicy.MAX_ATTEMPTS)
        assertEquals(120L, ScreenshotDetectionTimingPolicy.delayBeforeAttempt(1))
        assertEquals(120L, ScreenshotDetectionTimingPolicy.delayBeforeAttempt(7))
    }

    @Test
    fun backgroundFallbackPollsWithinOneSecond() {
        assertEquals(750L, ScreenshotDetectionTimingPolicy.BACKGROUND_POLL_INTERVAL_MS)
    }
}
