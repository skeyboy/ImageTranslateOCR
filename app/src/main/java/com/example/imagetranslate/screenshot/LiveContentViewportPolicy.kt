package com.example.imagetranslate.screenshot

import kotlin.math.ceil

internal data class LiveContentViewportEstimate(
    val top: Int,
    val bottom: Int,
    val confidence: Float,
    val usedFallback: Boolean
) {
    val height: Int get() = bottom - top
}

internal object LiveContentViewportPolicy {
    fun resolve(
        rowMotionScores: FloatArray,
        viewportHeight: Int,
        rowBlockHeight: Int
    ): LiveContentViewportEstimate {
        if (rowMotionScores.isEmpty() || viewportHeight <= 0 || rowBlockHeight <= 0) {
            return fallback(viewportHeight)
        }
        val sorted = rowMotionScores.sorted()
        val upperQuartile = sorted[((sorted.size - 1) * 3 / 4).coerceAtLeast(0)]
        val threshold = maxOf(MINIMUM_MOTION_SCORE, upperQuartile * ADAPTIVE_THRESHOLD_RATIO)
        val active = BooleanArray(rowMotionScores.size) { index ->
            rowMotionScores[index] >= threshold
        }
        val bridged = BooleanArray(active.size) { index ->
            active[index] ||
                (index > 0 && index + 1 < active.size && active[index - 1] && active[index + 1])
        }
        val firstActive = bridged.indexOfFirst { it }
        val lastActive = bridged.indexOfLast { it }
        if (firstActive < 0 || lastActive < firstActive) return fallback(viewportHeight)

        val firstBlock = (firstActive - EDGE_PADDING_BLOCKS).coerceAtLeast(0)
        val lastBlockExclusive = (lastActive + EDGE_PADDING_BLOCKS + 1)
            .coerceAtMost(rowMotionScores.size)
        val top = (firstBlock * rowBlockHeight).coerceIn(0, viewportHeight)
        val bottom = (lastBlockExclusive * rowBlockHeight).coerceIn(top, viewportHeight)
        val heightRatio = (bottom - top).toFloat() / viewportHeight
        val topRatio = top.toFloat() / viewportHeight
        val bottomRatio = bottom.toFloat() / viewportHeight
        if (
            heightRatio < MINIMUM_CONTENT_HEIGHT_RATIO ||
            topRatio > MAXIMUM_CONTENT_TOP_RATIO ||
            bottomRatio < MINIMUM_CONTENT_BOTTOM_RATIO
        ) {
            return fallback(viewportHeight)
        }

        val activeInside = (firstBlock until lastBlockExclusive).count { bridged[it] }
        val density = activeInside.toFloat() /
            (lastBlockExclusive - firstBlock).coerceAtLeast(1)
        val excludedRatio = 1f - heightRatio
        val confidence = (density * DENSITY_WEIGHT +
            (excludedRatio / EXPECTED_CHROME_RATIO).coerceIn(0f, 1f) * CHROME_WEIGHT)
            .coerceIn(0f, 1f)
        return LiveContentViewportEstimate(
            top = top,
            bottom = bottom,
            confidence = confidence,
            usedFallback = false
        )
    }

    private fun fallback(viewportHeight: Int): LiveContentViewportEstimate {
        val safeHeight = viewportHeight.coerceAtLeast(1)
        val top = (safeHeight * FALLBACK_TOP_RATIO).toInt()
        val bottom = ceil(safeHeight * FALLBACK_BOTTOM_RATIO).toInt()
            .coerceIn(top + 1, safeHeight)
        return LiveContentViewportEstimate(
            top = top,
            bottom = bottom,
            confidence = 0f,
            usedFallback = true
        )
    }

    private const val MINIMUM_MOTION_SCORE = 6f
    private const val ADAPTIVE_THRESHOLD_RATIO = 0.35f
    private const val EDGE_PADDING_BLOCKS = 1
    private const val MINIMUM_CONTENT_HEIGHT_RATIO = 0.45f
    private const val MAXIMUM_CONTENT_TOP_RATIO = 0.38f
    private const val MINIMUM_CONTENT_BOTTOM_RATIO = 0.68f
    private const val EXPECTED_CHROME_RATIO = 0.22f
    private const val DENSITY_WEIGHT = 0.65f
    private const val CHROME_WEIGHT = 0.35f
    private const val FALLBACK_TOP_RATIO = 0.08f
    private const val FALLBACK_BOTTOM_RATIO = 0.94f
}
