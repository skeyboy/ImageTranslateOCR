package com.example.imagetranslate.screenshot

import kotlin.math.abs

internal object LiveDifferentialRecognitionPolicy {
    fun canAttempt(plan: ScrollCapturePlan, viewportHeight: Int): Boolean {
        val minimumShift = maxOf(MINIMUM_SHIFT_PX, viewportHeight / MINIMUM_SHIFT_HEIGHT_DIVISOR)
        val shift = abs(plan.contentShiftY)
        if (shift < minimumShift || shift > viewportHeight * MAXIMUM_SHIFT_RATIO) return false
        val stronglyRegistered = plan.confidence >= MINIMUM_CONFIDENCE &&
            plan.consensusRatio >= MINIMUM_CONSENSUS &&
            plan.registrationError <= MAXIMUM_REGISTRATION_ERROR
        val largeConsistentScroll = shift >= viewportHeight * LARGE_SCROLL_MINIMUM_RATIO &&
            plan.overlapRatio >= LARGE_SCROLL_MINIMUM_OVERLAP &&
            plan.confidence >= LARGE_SCROLL_MINIMUM_CONFIDENCE &&
            plan.consensusRatio >= LARGE_SCROLL_MINIMUM_CONSENSUS &&
            plan.registrationError <= LARGE_SCROLL_MAXIMUM_REGISTRATION_ERROR
        return stronglyRegistered || largeConsistentScroll
    }

    fun hasSufficientReuse(expectedSurvivorCount: Int, matchedCount: Int): Boolean {
        if (expectedSurvivorCount <= 0 || matchedCount < MINIMUM_REUSED_REGION_COUNT) return false
        return matchedCount.toFloat() / expectedSurvivorCount >= MINIMUM_REUSED_REGION_RATIO
    }

    fun hasSufficientOutput(snapshotCount: Int, outputCount: Int): Boolean {
        if (snapshotCount <= 0) return false
        return outputCount.toFloat() / snapshotCount >= MINIMUM_OUTPUT_RETENTION_RATIO
    }

    fun hasEfficientRecognitionArea(
        shiftY: Int,
        viewportHeight: Int,
        recognitionAreaRatio: Float
    ): Boolean {
        if (viewportHeight <= 0) return false
        val isShortScroll = abs(shiftY) < viewportHeight * SHORT_SCROLL_MAXIMUM_RATIO
        return !isShortScroll || recognitionAreaRatio <= SHORT_SCROLL_MAXIMUM_AREA_RATIO
    }

    fun hasReliableDirtyGrid(dirtyCellCount: Int, comparedCellCount: Int): Boolean {
        if (comparedCellCount <= 0 || dirtyCellCount < 0) return false
        return dirtyCellCount.toFloat() / comparedCellCount <= MAXIMUM_DIRTY_CELL_RATIO
    }

    private const val MINIMUM_SHIFT_PX = 80
    private const val MINIMUM_SHIFT_HEIGHT_DIVISOR = 32
    private const val MAXIMUM_SHIFT_RATIO = 0.62f
    private const val MINIMUM_CONFIDENCE = 0.62f
    private const val MINIMUM_CONSENSUS = 0.74f
    private const val MAXIMUM_REGISTRATION_ERROR = 36f
    private const val LARGE_SCROLL_MINIMUM_RATIO = 0.18f
    private const val LARGE_SCROLL_MINIMUM_OVERLAP = 0.35f
    private const val LARGE_SCROLL_MINIMUM_CONFIDENCE = 0.5f
    private const val LARGE_SCROLL_MINIMUM_CONSENSUS = 0.66f
    private const val LARGE_SCROLL_MAXIMUM_REGISTRATION_ERROR = 38f
    private const val MINIMUM_REUSED_REGION_COUNT = 3
    private const val MINIMUM_REUSED_REGION_RATIO = 0.72f
    private const val MINIMUM_OUTPUT_RETENTION_RATIO = 0.8f
    private const val SHORT_SCROLL_MAXIMUM_RATIO = 0.2f
    private const val SHORT_SCROLL_MAXIMUM_AREA_RATIO = 0.58f
    private const val MAXIMUM_DIRTY_CELL_RATIO = 0.4f
}
