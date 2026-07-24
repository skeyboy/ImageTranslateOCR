package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun settledScrollCarriesDifferentialCapturePlan() {
        val scrollDetector = ScreenFrameChangeDetector(
            stableDelayMs = 300L,
            minimumCaptureIntervalMs = 100L,
            changedSampleRatio = 0.05f,
            luminanceDelta = 10
        )
        val reference = patternedFrame()
        val current = shiftedFrame(reference, shiftRows = -4)
        scrollDetector.onCaptureStarted(reference, 0L)

        assertEquals(ScreenFrameAction.MOVING, scrollDetector.onFrame(current, 100L))
        assertEquals(ScreenFrameAction.CAPTURE, scrollDetector.onFrame(current, 401L))
        val plan = scrollDetector.consumeCapturePlan()
        assertNotNull(plan)
        assertEquals(-160, plan?.contentShiftY)
    }

    private fun frame(value: Int) = ScreenFrameSignature(IntArray(100) { value })

    private fun patternedFrame(): ScreenFrameSignature {
        val columns = 12
        val rows = 31
        return ScreenFrameSignature(
            samples = IntArray(columns * rows) { index ->
                val row = index / columns
                val column = index % columns
                (row * row * 7 + row * 13 + column * 17) % 256
            },
            columns = columns,
            rows = rows,
            sampleTopPx = 100,
            sampleBottomPx = 1_300
        )
    }

    private fun shiftedFrame(
        reference: ScreenFrameSignature,
        shiftRows: Int
    ): ScreenFrameSignature {
        val samples = IntArray(reference.samples.size) { index ->
            val row = index / reference.columns
            val column = index % reference.columns
            val referenceRow = row - shiftRows
            if (referenceRow in 0 until reference.rows) {
                reference.samples[referenceRow * reference.columns + column]
            } else {
                (row * 29 + column * 31 + 47) % 256
            }
        }
        return reference.copy(samples = samples)
    }
}
