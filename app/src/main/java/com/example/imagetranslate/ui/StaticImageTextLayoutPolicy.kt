package com.example.imagetranslate.ui

import android.graphics.Rect

internal data class StaticImageTextLayoutMetrics(
    val sourceLineHeightPx: Float,
    val preferredTextSizePx: Float,
    val minimumTextSizePx: Float,
    val maximumLines: Int
)

internal object StaticImageTextLayoutPolicy {
    private const val MINIMUM_TEXT_SIZE_PX = 8f
    private const val DEFAULT_MINIMUM_TEXT_SCALE = 0.68f

    fun resolve(
        groupBounds: Rect,
        componentBounds: List<Rect>,
        fontSizeMultiplier: Float,
        preferredMaxLines: Int?,
        minimumTextScale: Float?,
        sourceLineCount: Int? = null
    ): StaticImageTextLayoutMetrics {
        val lineHeights = componentBounds
            .map { bounds -> bounds.bottom - bounds.top }
            .filter { it > 0 }
            .ifEmpty {
                listOf((groupBounds.bottom - groupBounds.top).coerceAtLeast(1))
            }
            .sorted()
        val sourceLineHeight = lineHeights[lineHeights.size / 2].toFloat()
        val resolvedSourceLineCount = maxOf(
            lineHeights.size.coerceAtLeast(1),
            sourceLineCount?.coerceAtLeast(1) ?: 1
        )
        val maximumLines = maxOf(
            resolvedSourceLineCount,
            preferredMaxLines?.coerceAtLeast(1) ?: resolvedSourceLineCount
        )
        val preferredSize = maxOf(
            MINIMUM_TEXT_SIZE_PX,
            sourceLineHeight * fontSizeMultiplier
        )
        val scale = minimumTextScale
            ?.coerceIn(DEFAULT_MINIMUM_TEXT_SCALE, 1f)
            ?: DEFAULT_MINIMUM_TEXT_SCALE
        val minimumSize = maxOf(
            MINIMUM_TEXT_SIZE_PX,
            minOf(preferredSize, sourceLineHeight * scale)
        )
        return StaticImageTextLayoutMetrics(
            sourceLineHeightPx = sourceLineHeight,
            preferredTextSizePx = preferredSize,
            minimumTextSizePx = minimumSize,
            maximumLines = maximumLines
        )
    }
}
