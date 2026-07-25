package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFrameMotionEstimatorTest {
    @Test
    fun upwardContentMotionProducesNegativePixelShift() {
        val reference = patternedFrame()
        val current = shiftedFrame(reference, shiftRows = -4)

        val plan = ScrollFrameMotionEstimator.estimate(reference, current)

        requireNotNull(plan)
        assertEquals(-160, plan.contentShiftY)
        assertTrue(plan.confidence >= 0.72f)
    }

    @Test
    fun downwardContentMotionProducesPositivePixelShift() {
        val reference = patternedFrame()
        val current = shiftedFrame(reference, shiftRows = 3)

        val plan = ScrollFrameMotionEstimator.estimate(reference, current)

        requireNotNull(plan)
        assertEquals(120, plan.contentShiftY)
    }

    @Test
    fun flatFramesDoNotProduceAmbiguousScrollPlan() {
        val reference = signature(IntArray(COLUMNS * ROWS) { 128 })
        val current = signature(IntArray(COLUMNS * ROWS) { 128 })

        assertNull(ScrollFrameMotionEstimator.estimate(reference, current))
    }

    @Test
    fun inconsistentMotionAcrossScreenBandsIsRejected() {
        val reference = patternedFrame()
        val up = shiftedFrame(reference, shiftRows = -4)
        val down = shiftedFrame(reference, shiftRows = 3)
        val current = signature(
            IntArray(COLUMNS * ROWS) { index ->
                val column = index % COLUMNS
                when {
                    column < COLUMNS / 3 -> up.samples[index]
                    column < COLUMNS * 2 / 3 -> down.samples[index]
                    else -> (index * 37 + 19) % 256
                }
            }
        )

        assertNull(ScrollFrameMotionEstimator.estimate(reference, current))
    }

    private fun patternedFrame(): ScreenFrameSignature = signature(
        IntArray(COLUMNS * ROWS) { index ->
            val row = index / COLUMNS
            val column = index % COLUMNS
            (row * row * 7 + row * 13 + column * 17) % 256
        }
    )

    private fun shiftedFrame(
        reference: ScreenFrameSignature,
        shiftRows: Int
    ): ScreenFrameSignature {
        val samples = IntArray(COLUMNS * ROWS) { index ->
            val row = index / COLUMNS
            val column = index % COLUMNS
            val referenceRow = row - shiftRows
            if (referenceRow in 0 until ROWS) {
                reference.samples[referenceRow * COLUMNS + column]
            } else {
                (row * 29 + column * 31 + 47) % 256
            }
        }
        return signature(samples)
    }

    private fun signature(samples: IntArray) = ScreenFrameSignature(
        samples = samples,
        columns = COLUMNS,
        rows = ROWS,
        sampleTopPx = 100,
        sampleBottomPx = 1_300
    )

    private companion object {
        const val COLUMNS = 12
        const val ROWS = 31
    }
}
