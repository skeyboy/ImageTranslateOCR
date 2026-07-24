package com.example.imagetranslate.screenshot

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class ScrollCapturePlan(
    val contentShiftY: Int,
    val confidence: Float,
    val overlapRatio: Float
)

internal object ScrollFrameMotionEstimator {
    fun estimate(
        reference: ScreenFrameSignature,
        current: ScreenFrameSignature,
        maximumShiftRatio: Float = DEFAULT_MAXIMUM_SHIFT_RATIO
    ): ScrollCapturePlan? {
        if (
            reference.columns != current.columns || reference.rows != current.rows ||
            reference.columns < 2 || reference.rows < MINIMUM_ROWS ||
            reference.samples.size != reference.columns * reference.rows ||
            current.samples.size != current.columns * current.rows
        ) return null
        if (textureScore(reference) < MINIMUM_TEXTURE_SCORE) return null

        val maximumShiftRows = (reference.rows * maximumShiftRatio)
            .toInt()
            .coerceIn(1, reference.rows - MINIMUM_OVERLAP_ROWS)
        val scores = (-maximumShiftRows..maximumShiftRows)
            .filter { it != 0 }
            .mapNotNull { shiftRows -> scoreShift(reference, current, shiftRows) }
            .sortedBy(ShiftScore::error)
        val best = scores.firstOrNull() ?: return null
        val secondBest = scores.firstOrNull { abs(it.shiftRows - best.shiftRows) > 1 }
        val quality = (1f - best.error / MAXIMUM_USEFUL_ERROR).coerceIn(0f, 1f)
        val distinctness = if (secondBest == null || secondBest.error <= 0.001f) {
            0f
        } else {
            ((secondBest.error - best.error) / secondBest.error * DISTINCTNESS_SCALE)
                .coerceIn(0f, 1f)
        }
        val confidence = quality * QUALITY_WEIGHT + distinctness * DISTINCTNESS_WEIGHT
        if (confidence < MINIMUM_CONFIDENCE) return null

        val sampledHeight = reference.sampleBottomPx - reference.sampleTopPx
        if (sampledHeight <= 0) return null
        val rowSpacing = sampledHeight.toFloat() / (reference.rows - 1)
        val contentShiftY = (best.shiftRows * rowSpacing).roundToInt()
        if (contentShiftY == 0) return null
        return ScrollCapturePlan(
            contentShiftY = contentShiftY,
            confidence = confidence,
            overlapRatio = best.overlapRatio
        )
    }

    private fun scoreShift(
        reference: ScreenFrameSignature,
        current: ScreenFrameSignature,
        shiftRows: Int
    ): ShiftScore? {
        val firstReferenceRow = maxOf(0, -shiftRows)
        val lastReferenceRow = minOf(reference.rows, reference.rows - shiftRows)
        val comparedRows = lastReferenceRow - firstReferenceRow
        if (comparedRows < MINIMUM_OVERLAP_ROWS) return null

        var totalError = 0L
        var comparedSamples = 0
        for (referenceRow in firstReferenceRow until lastReferenceRow) {
            val currentRow = referenceRow + shiftRows
            val referenceOffset = referenceRow * reference.columns
            val currentOffset = currentRow * current.columns
            repeat(reference.columns) { column ->
                totalError += abs(
                    reference.samples[referenceOffset + column] -
                        current.samples[currentOffset + column]
                )
                comparedSamples++
            }
        }
        return ShiftScore(
            shiftRows = shiftRows,
            error = totalError.toFloat() / comparedSamples.coerceAtLeast(1),
            overlapRatio = comparedRows.toFloat() / reference.rows
        )
    }

    private fun textureScore(signature: ScreenFrameSignature): Float {
        var totalDifference = 0L
        var comparisons = 0
        for (row in 1 until signature.rows) {
            val previousOffset = (row - 1) * signature.columns
            val currentOffset = row * signature.columns
            repeat(signature.columns) { column ->
                totalDifference += abs(
                    signature.samples[currentOffset + column] -
                        signature.samples[previousOffset + column]
                )
                comparisons++
            }
        }
        return totalDifference.toFloat() / comparisons.coerceAtLeast(1)
    }

    private data class ShiftScore(
        val shiftRows: Int,
        val error: Float,
        val overlapRatio: Float
    )

    private const val DEFAULT_MAXIMUM_SHIFT_RATIO = 0.62f
    private const val MINIMUM_ROWS = 8
    private const val MINIMUM_OVERLAP_ROWS = 4
    private const val MAXIMUM_USEFUL_ERROR = 96f
    private const val DISTINCTNESS_SCALE = 4f
    private const val QUALITY_WEIGHT = 0.68f
    private const val DISTINCTNESS_WEIGHT = 0.32f
    private const val MINIMUM_CONFIDENCE = 0.2f
    private const val MINIMUM_TEXTURE_SCORE = 0.1f
}
