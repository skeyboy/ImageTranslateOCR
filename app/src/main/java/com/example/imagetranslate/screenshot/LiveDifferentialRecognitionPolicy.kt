package com.example.imagetranslate.screenshot

import kotlin.math.abs

internal object LiveDifferentialRecognitionPolicy {
    fun canAttempt(plan: ScrollCapturePlan, viewportHeight: Int): Boolean {
        val minimumShift = maxOf(MINIMUM_SHIFT_PX, viewportHeight / MINIMUM_SHIFT_HEIGHT_DIVISOR)
        return abs(plan.contentShiftY) >= minimumShift &&
            abs(plan.contentShiftY) <= viewportHeight * MAXIMUM_SHIFT_RATIO &&
            plan.confidence >= MINIMUM_CONFIDENCE &&
            plan.consensusRatio >= MINIMUM_CONSENSUS &&
            plan.registrationError <= MAXIMUM_REGISTRATION_ERROR
    }

    fun hasSufficientReuse(snapshotCount: Int, shiftedCount: Int): Boolean {
        if (snapshotCount <= 0 || shiftedCount < MINIMUM_REUSED_REGION_COUNT) return false
        return shiftedCount.toFloat() / snapshotCount >= MINIMUM_REUSED_REGION_RATIO
    }

    fun hasSufficientOutput(snapshotCount: Int, outputCount: Int): Boolean {
        if (snapshotCount <= 0) return false
        return outputCount.toFloat() / snapshotCount >= MINIMUM_OUTPUT_RETENTION_RATIO
    }

    private const val MINIMUM_SHIFT_PX = 80
    private const val MINIMUM_SHIFT_HEIGHT_DIVISOR = 32
    private const val MAXIMUM_SHIFT_RATIO = 0.62f
    private const val MINIMUM_CONFIDENCE = 0.62f
    private const val MINIMUM_CONSENSUS = 0.74f
    private const val MAXIMUM_REGISTRATION_ERROR = 36f
    private const val MINIMUM_REUSED_REGION_COUNT = 3
    private const val MINIMUM_REUSED_REGION_RATIO = 0.72f
    private const val MINIMUM_OUTPUT_RETENTION_RATIO = 0.8f
}
