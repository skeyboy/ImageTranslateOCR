package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenFrameChangeDetectorTest {
    private val detector = ScreenFrameChangeDetector(
        stableDelayMs = 400L,
        minimumCaptureIntervalMs = 800L,
        changedSampleRatio = 0.2f,
        luminanceDelta = 20
    )

    @Test
    fun movingScreenCapturesOnlyAfterItHasSettled() {
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(10), 0L))
        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(80), 100L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(140), 250L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(140), 600L))
        assertEquals(ScreenFrameAction.CAPTURE, detector.onFrame(frame(140), 651L))
    }

    @Test
    fun smallVisualNoiseDoesNotTriggerCapture() {
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 0L))
        val noisy = IntArray(100) { index -> if (index < 10) 135 else 100 }
        assertEquals(
            ScreenFrameAction.NONE,
            detector.onFrame(ScreenFrameSignature(noisy), 500L)
        )
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 1_000L))
    }

    @Test
    fun renderedOverlayIsIgnoredBeforeWatchingResumes() {
        detector.onFrame(frame(10), 0L)
        detector.onTranslationRendered(100L)
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(200), 300L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(200), 800L))
    }

    @Test
    fun movementAfterCaptureStartedInvalidatesTheCapturedViewport() {
        detector.onFrame(frame(10), 0L)
        detector.onCaptureStarted(frame(40), 100L)

        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(100), 250L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 700L))
        assertEquals(ScreenFrameAction.CAPTURE, detector.onFrame(frame(100), 1_001L))
    }

    private fun frame(value: Int) = ScreenFrameSignature(IntArray(100) { value })
}
