package com.example.imagetranslate.semantic

import com.example.imagetranslate.ocr.RecognizedText
import kotlin.math.abs

/**
 * Detects article-like static images and keeps the dominant left-aligned body column.
 * Non-article images are returned unchanged so labels, posters, and chats remain translatable.
 */
internal object StaticImageTextFilter {
    private const val MINIMUM_ARTICLE_SEED_LINES = 6
    private const val MINIMUM_SEED_WIDTH_RATIO = 0.55f
    private const val MAXIMUM_SEED_LEFT_RATIO = 0.12f
    private const val MINIMUM_SEED_CONFIDENCE = 0.72f
    private const val MINIMUM_KEPT_CONFIDENCE = 0.55f
    private const val MINIMUM_SEED_CHARACTERS = 18
    private const val MINIMUM_FLOW_HEIGHT_RATIO = 0.55f
    private const val MINIMUM_FLOW_HORIZONTAL_OVERLAP = 0.45f
    private const val MAXIMUM_FLOW_GAP_RATIO = 0.75f
    private const val MINIMUM_FLOW_GAP_PX = 5

    fun filter(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): List<RecognizedText> {
        if (recognized.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) {
            return emptyList()
        }
        val valid = recognized.filter { item ->
            item.text.isNotBlank() && width(item) > 0 && height(item) > 0
        }
        val seeds = valid.filter { item ->
            item.modelConfidence >= MINIMUM_SEED_CONFIDENCE &&
                item.text.count(Char::isLetterOrDigit) >= MINIMUM_SEED_CHARACTERS &&
                width(item) >= viewportWidth * MINIMUM_SEED_WIDTH_RATIO &&
                item.bounds.left <= viewportWidth * MAXIMUM_SEED_LEFT_RATIO &&
                height(item) <= viewportHeight * 0.08f
        }
        if (seeds.size < MINIMUM_ARTICLE_SEED_LINES) return recognized

        val medianHeight = median(seeds.map(::height)).coerceAtLeast(1)
        val medianLeft = median(seeds.map { it.bounds.left })
        val alignmentTolerance = maxOf(8, medianHeight)
        val contentTop = (seeds.minOf { it.bounds.top } - medianHeight * 2).coerceAtLeast(0)
        val contentBottom = (seeds.maxOf { it.bounds.bottom } + medianHeight * 2)
            .coerceAtMost(viewportHeight)

        val dominantColumn = valid.filter { item ->
            item.modelConfidence >= MINIMUM_KEPT_CONFIDENCE &&
                (item.bounds.top + height(item) / 2) in contentTop..contentBottom &&
                abs(item.bounds.left - medianLeft) <= alignmentTolerance &&
                item.text.any(Char::isLetterOrDigit)
        }
        if (dominantColumn.isEmpty()) return recognized

        val accepted = dominantColumn.toMutableList()
        var changed: Boolean
        do {
            changed = false
            valid.asSequence()
                .filter { candidate -> accepted.none { it === candidate } }
                .filter { candidate ->
                    candidate.modelConfidence >= MINIMUM_KEPT_CONFIDENCE &&
                        candidate.text.any(Char::isLetterOrDigit) &&
                        accepted.any { bodyLine -> connectedBodyFlow(candidate, bodyLine) }
                }
                .toList()
                .forEach { candidate ->
                    if (accepted.none { it === candidate }) {
                        accepted += candidate
                        changed = true
                    }
                }
        } while (changed)

        return valid.filter { candidate -> accepted.any { it === candidate } }
    }

    private fun connectedBodyFlow(first: RecognizedText, second: RecognizedText): Boolean {
        val firstHeight = height(first).coerceAtLeast(1)
        val secondHeight = height(second).coerceAtLeast(1)
        val minimumHeight = minOf(firstHeight, secondHeight)
        val maximumHeight = maxOf(firstHeight, secondHeight)
        if (minimumHeight.toFloat() / maximumHeight < MINIMUM_FLOW_HEIGHT_RATIO) return false

        val verticalGap = when {
            first.bounds.bottom <= second.bounds.top -> second.bounds.top - first.bounds.bottom
            second.bounds.bottom <= first.bounds.top -> first.bounds.top - second.bounds.bottom
            else -> 0
        }
        val maximumGap = maxOf(
            MINIMUM_FLOW_GAP_PX,
            (minimumHeight * MAXIMUM_FLOW_GAP_RATIO).toInt()
        )
        if (verticalGap > maximumGap) return false

        val horizontalOverlap = minOf(first.bounds.right, second.bounds.right) -
            maxOf(first.bounds.left, second.bounds.left)
        val minimumWidth = minOf(width(first), width(second)).coerceAtLeast(1)
        return horizontalOverlap.coerceAtLeast(0).toFloat() / minimumWidth >=
            MINIMUM_FLOW_HORIZONTAL_OVERLAP
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun width(item: RecognizedText): Int = item.bounds.right - item.bounds.left

    private fun height(item: RecognizedText): Int = item.bounds.bottom - item.bounds.top
}
