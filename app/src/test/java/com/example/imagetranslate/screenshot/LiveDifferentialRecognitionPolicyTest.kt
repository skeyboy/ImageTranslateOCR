package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDifferentialRecognitionPolicyTest {
    @Test
    fun rejectsTheLowConfidenceTinyShiftSeenOnTheDevice() {
        val plan = ScrollCapturePlan(
            contentShiftY = -39,
            confidence = 0.5559564f,
            overlapRatio = 0.9861111f,
            registrationError = 29.926937f,
            consensusRatio = 0.6666667f
        )

        assertFalse(LiveDifferentialRecognitionPolicy.canAttempt(plan, viewportHeight = 3_200))
    }

    @Test
    fun acceptsAWellRegisteredScroll() {
        val plan = ScrollCapturePlan(
            contentShiftY = -240,
            confidence = 0.82f,
            overlapRatio = 0.9f,
            registrationError = 18f,
            consensusRatio = 1f
        )

        assertTrue(LiveDifferentialRecognitionPolicy.canAttempt(plan, viewportHeight = 3_200))
    }

    @Test
    fun acceptsTheLargeConsistentScrollMeasuredOnTheDevice() {
        val plan = ScrollCapturePlan(
            contentShiftY = -1_434,
            confidence = 0.52847135f,
            overlapRatio = 0.4861111f,
            registrationError = 34.71488f,
            consensusRatio = 0.6666667f
        )

        assertTrue(LiveDifferentialRecognitionPolicy.canAttempt(plan, viewportHeight = 3_200))
    }

    @Test
    fun rejectsLargeScrollWhenHorizontalBandsDisagree() {
        val plan = ScrollCapturePlan(
            contentShiftY = -1_434,
            confidence = 0.58f,
            overlapRatio = 0.48f,
            registrationError = 32f,
            consensusRatio = 0.5f
        )

        assertFalse(LiveDifferentialRecognitionPolicy.canAttempt(plan, viewportHeight = 3_200))
    }

    @Test
    fun rejectsSparseReuseAndSparseOutput() {
        assertFalse(LiveDifferentialRecognitionPolicy.hasSufficientReuse(13, 7))
        assertTrue(LiveDifferentialRecognitionPolicy.hasSufficientReuse(6, 5))
        assertFalse(LiveDifferentialRecognitionPolicy.hasSufficientOutput(13, 9))
        assertTrue(LiveDifferentialRecognitionPolicy.hasSufficientReuse(13, 10))
        assertTrue(LiveDifferentialRecognitionPolicy.hasSufficientOutput(13, 11))
    }
}
