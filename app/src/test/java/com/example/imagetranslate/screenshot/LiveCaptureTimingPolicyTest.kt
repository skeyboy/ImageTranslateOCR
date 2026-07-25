package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptureTimingPolicyTest {
    @Test
    fun captureSettlementKeepsLatestFrameQueuedUntilCaptureIsArmed() {
        assertTrue(
            LiveCaptureTimingPolicy.shouldHoldImageQueue(
                captureInProgress = true,
                captureRequested = false,
                processingFrameCaptured = false,
                presentationInProgress = false
            )
        )
        assertFalse(
            LiveCaptureTimingPolicy.shouldHoldImageQueue(
                captureInProgress = true,
                captureRequested = true,
                processingFrameCaptured = false,
                presentationInProgress = false
            )
        )
    }

    @Test
    fun translationPresentationIsNotObservedAsPageMovement() {
        assertTrue(
            LiveCaptureTimingPolicy.shouldHoldImageQueue(
                captureInProgress = false,
                captureRequested = false,
                processingFrameCaptured = false,
                presentationInProgress = true
            )
        )
    }

    @Test
    fun liveTranslationTimeoutIsBounded() {
        assertTrue(LiveCaptureTimingPolicy.TRANSLATION_TIMEOUT_MS <= 35_000L)
    }

    @Test
    fun presentationGateHasABoundedFallback() {
        assertTrue(LiveCaptureTimingPolicy.PRESENTATION_GATE_TIMEOUT_MS <= 500L)
    }
}
