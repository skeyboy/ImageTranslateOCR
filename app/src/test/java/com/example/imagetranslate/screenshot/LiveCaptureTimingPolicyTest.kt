package com.example.imagetranslate.screenshot

import com.example.imagetranslate.translate.TranslationBackend
import org.junit.Assert.assertEquals
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
    fun liveTranslationTimeoutMatchesTheSelectedPipeline() {
        assertEquals(
            35_000L,
            LiveCaptureTimingPolicy.translationTimeoutMs(
                TranslationBackend.LOCAL,
                LiveOcrTranslationEngineType.LOCAL_PIPELINE
            )
        )
        assertEquals(
            75_000L,
            LiveCaptureTimingPolicy.translationTimeoutMs(
                TranslationBackend.SELF_HOSTED,
                LiveOcrTranslationEngineType.LOCAL_PIPELINE
            )
        )
        assertEquals(
            90_000L,
            LiveCaptureTimingPolicy.translationTimeoutMs(
                TranslationBackend.NETWORK,
                LiveOcrTranslationEngineType.LOCAL_PIPELINE
            )
        )
        assertEquals(
            40_000L,
            LiveCaptureTimingPolicy.translationTimeoutMs(
                TranslationBackend.SELF_HOSTED,
                LiveOcrTranslationEngineType.PADDLE_NETWORK
            )
        )
        assertTrue(LiveCaptureTimingPolicy.NETWORK_TRANSLATION_TIMEOUT_MS <= 90_000L)
    }

    @Test
    fun presentationGateHasABoundedFallback() {
        assertTrue(LiveCaptureTimingPolicy.PRESENTATION_GATE_TIMEOUT_MS <= 500L)
    }

    @Test
    fun emptyResultRetriesOnlyOnce() {
        assertTrue(LiveCaptureTimingPolicy.shouldRetryEmptyResult(0, 0))
        assertFalse(LiveCaptureTimingPolicy.shouldRetryEmptyResult(0, 1))
        assertFalse(LiveCaptureTimingPolicy.shouldRetryEmptyResult(1, 0))
    }

    @Test
    fun emptyResultDoesNotReplaceTheLastUsefulSnapshot() {
        assertFalse(LiveCaptureTimingPolicy.shouldUpdateLiveSnapshot(0, 0))
        assertFalse(LiveCaptureTimingPolicy.shouldUpdateLiveSnapshot(0, 4))
        assertTrue(LiveCaptureTimingPolicy.shouldUpdateLiveSnapshot(3, 4))
    }

    @Test
    fun onlyTheLatestActiveGenerationCanReachTheOverlay() {
        assertTrue(LiveCaptureTimingPolicy.shouldPresentResult(9, 9, true))
        assertFalse(LiveCaptureTimingPolicy.shouldPresentResult(8, 9, true))
        assertFalse(LiveCaptureTimingPolicy.shouldPresentResult(9, 9, false))
    }
}
