package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFrameChangeDetectorTest {
    @Test
    fun reportsWhenMovementStillNeedsStableFrames() {
        val detector = ScreenFrameChangeDetector()

        assertFalse(detector.isAwaitingStableFrames())
        detector.onFrame(frame(10), 0L)
        detector.onFrame(frame(180), 100L)
        assertTrue(detector.isAwaitingStableFrames())

        detector.reset()
        assertFalse(detector.isAwaitingStableFrames())
    }

    @Test
    fun quietPeriodFallbackCapturesChangedViewportOnce() {
        val detector = ScreenFrameChangeDetector()
        detector.onFrame(frame(10), 0L)
        detector.onFrame(frame(180), 100L)

        assertEquals(ScreenFrameAction.CAPTURE, detector.forceActionAfterQuietPeriod(1_000L))
        assertFalse(detector.isAwaitingStableFrames())
        assertEquals(ScreenFrameAction.NONE, detector.forceActionAfterQuietPeriod(1_100L))
    }

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
        assertEquals(ScreenFrameAction.MOVING_UPDATE, detector.onFrame(frame(140), 250L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(140), 600L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(140), 651L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(140), 720L))
        assertEquals(ScreenFrameAction.CAPTURE, detector.onFrame(frame(140), 800L))
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
    fun presentedTranslationDoesNotTriggerAnotherRefresh() {
        detector.onCaptureStarted(frame(10), 0L)
        detector.onTranslationRendered(500L)

        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(180), 501L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(180), 900L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(180), 1_300L))
        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(40), 1_400L))
    }

    @Test
    fun presentationChangeReturningToCapturedViewportRestoresExistingResult() {
        detector.onCaptureStarted(frame(40), 0L)
        detector.onTranslationRendered(500L)
        detector.onFrame(frame(180), 501L)
        detector.onFrame(frame(180), 900L)

        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(210), 1_000L))
        assertEquals(ScreenFrameAction.MOVING_UPDATE, detector.onFrame(frame(40), 1_450L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(40), 1_550L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(40), 1_650L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(40), 1_750L))
        assertEquals(ScreenFrameAction.RESTORE, detector.onFrame(frame(40), 1_850L))
        assertEquals(0f, detector.consumeSettledDifferenceRatio())
    }

    @Test
    fun quietPeriodFallbackRestoresViewportThatReturnedToCaptureBaseline() {
        detector.onCaptureStarted(frame(40), 0L)
        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(180), 100L))
        assertEquals(ScreenFrameAction.MOVING_UPDATE, detector.onFrame(frame(40), 300L))

        assertEquals(ScreenFrameAction.RESTORE, detector.forceActionAfterQuietPeriod(1_200L))
        assertEquals(0f, detector.consumeSettledDifferenceRatio())
        assertFalse(detector.isAwaitingStableFrames())
    }

    @Test
    fun movementAfterCaptureStartedInvalidatesTheCapturedViewport() {
        detector.onFrame(frame(10), 0L)
        detector.onCaptureStarted(frame(40), 100L)

        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(100), 250L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 700L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 1_001L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(100), 1_100L))
        assertEquals(ScreenFrameAction.CAPTURE, detector.onFrame(frame(100), 1_200L))
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
        assertEquals(-160, scrollDetector.currentMotionPlan()?.contentShiftY)
        assertEquals(ScreenFrameAction.NONE, scrollDetector.onFrame(current, 401L))
        assertEquals(ScreenFrameAction.NONE, scrollDetector.onFrame(current, 477L))
        assertEquals(ScreenFrameAction.NONE, scrollDetector.onFrame(current, 550L))
        assertEquals(ScreenFrameAction.CAPTURE, scrollDetector.onFrame(current, 625L))
        val plan = scrollDetector.consumeCapturePlan()
        assertNotNull(plan)
        assertEquals(-160, plan?.contentShiftY)
    }

    @Test
    fun transientSettledFramesDoNotStartBufferedCapture() {
        detector.onFrame(frame(10), 0L)
        assertEquals(ScreenFrameAction.MOVING, detector.onFrame(frame(90), 100L))

        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(90), 501L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(90), 601L))
        assertEquals(ScreenFrameAction.NONE, detector.onFrame(frame(90), 701L))
        assertEquals(ScreenFrameAction.CAPTURE, detector.onFrame(frame(90), 801L))
    }

    @Test
    fun duplicateCapturePolicyIgnoresMinorSamplingNoise() {
        val baseline = frame(100)
        val minorNoise = baseline.copy(samples = baseline.samples.copyOf().apply { this[0] = 112 })
        val changed = baseline.copy(samples = baseline.samples.copyOf().apply {
            this[0] = 112
            this[1] = 112
        })

        assertEquals(true, ScreenFrameSignaturePolicy.isDuplicateCapture(baseline, minorNoise))
        assertEquals(false, ScreenFrameSignaturePolicy.isDuplicateCapture(baseline, changed))
    }

    @Test
    fun signatureDifferenceIgnoresControlOverlayAtEitherPosition() {
        val baseline = frame(100).copy(
            ignoredSamples = BooleanArray(100).apply { fill(true, 80, 90) }
        )
        val current = frame(100).copy(
            samples = IntArray(100) { index -> if (index in 80 until 100) 240 else 100 },
            ignoredSamples = BooleanArray(100).apply { fill(true, 90, 100) }
        )

        assertEquals(
            0f,
            ScreenFrameSignaturePolicy.differenceRatio(baseline, current, luminanceDelta = 10)
        )
    }

    @Test
    fun initialCaptureWaitsForTheDestinationViewportToRemainStable() {
        val gate = InitialViewportStabilityGate(
            minimumSessionAgeMs = 600L,
            stableDurationMs = 300L,
            minimumStableSamples = 3,
            changedSampleRatio = 0.2f,
            luminanceDelta = 20
        )
        gate.reset(0L)

        assertEquals(false, gate.onFrame(frame(20), 100L))
        assertEquals(false, gate.onFrame(frame(20), 350L))
        assertEquals(false, gate.onFrame(frame(160), 500L))
        assertEquals(false, gate.onFrame(frame(160), 700L))
        assertEquals(true, gate.onFrame(frame(160), 850L))
    }

    @Test
    fun initialCaptureUsesLatestFrameWhenTheSourceStopsProducingFrames() {
        val gate = InitialViewportStabilityGate(
            minimumSessionAgeMs = 600L,
            stableDurationMs = 300L,
            minimumStableSamples = 3,
            maximumWaitMs = 1_000L
        )
        gate.reset(0L)

        assertEquals(false, gate.onFrame(frame(20), 100L))
        assertEquals(true, gate.onFrame(frame(160), 1_050L))
    }

    @Test
    fun viewportChangePolicyInvalidatesAnOcrResultFromAnOldPage() {
        assertEquals(false, ScreenFrameSignaturePolicy.hasViewportChanged(frame(100), frame(102)))
        assertEquals(true, ScreenFrameSignaturePolicy.hasViewportChanged(frame(100), frame(180)))
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
