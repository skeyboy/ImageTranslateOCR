package com.example.imagetranslate.screenshot

import kotlin.math.sqrt

internal object TranslationOverlayTouchPolicy {
    internal data class WindowBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private const val PREFERRED_ALPHA = 0.55f
    private const val SAFETY_MARGIN = 0.02f

    fun windowAlpha(maximumObscuringAlpha: Float): Float {
        val safeCombinedAlpha = (maximumObscuringAlpha - SAFETY_MARGIN).coerceIn(0f, 1f)
        // Android combines overlapping window opacity as 1 - (1-a1) * (1-a2).
        val alphaForTwoOverlappingWindows = 1f - sqrt(1f - safeCombinedAlpha)
        return minOf(PREFERRED_ALPHA, alphaForTwoOverlappingWindows)
    }

    fun scalePatchBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): WindowBounds? {
        if (
            sourceWidth <= 0 || sourceHeight <= 0 ||
            screenWidth <= 0 || screenHeight <= 0 ||
            right <= left || bottom <= top
        ) return null

        val scaledLeft = (left.toLong() * screenWidth / sourceWidth).toInt()
            .coerceIn(0, screenWidth - 1)
        val scaledTop = (top.toLong() * screenHeight / sourceHeight).toInt()
            .coerceIn(0, screenHeight - 1)
        val scaledRight = ((right.toLong() * screenWidth + sourceWidth - 1) / sourceWidth)
            .toInt()
            .coerceIn(scaledLeft + 1, screenWidth)
        val scaledBottom = ((bottom.toLong() * screenHeight + sourceHeight - 1) / sourceHeight)
            .toInt()
            .coerceIn(scaledTop + 1, screenHeight)
        return WindowBounds(
            x = scaledLeft,
            y = scaledTop,
            width = scaledRight - scaledLeft,
            height = scaledBottom - scaledTop
        )
    }
}
