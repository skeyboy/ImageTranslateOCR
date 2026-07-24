package com.example.imagetranslate.screenshot

import kotlin.math.pow

internal object TranslationOverlayTouchPolicy {
    const val PREFERRED_SINGLE_WINDOW_ALPHA = 0.72f

    internal data class WindowBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private const val SAFETY_MARGIN = 0.02f

    fun windowAlpha(maximumObscuringAlpha: Float, overlapCount: Int): Float {
        val safeCombinedAlpha = (maximumObscuringAlpha - SAFETY_MARGIN).coerceIn(0f, 1f)
        val windowsAtOnePoint = overlapCount.coerceAtLeast(1)
        // Android combines opacity as 1 - (1-a1) * ... * (1-an).
        val safeWindowAlpha = 1f - (1f - safeCombinedAlpha)
            .toDouble()
            .pow(1.0 / windowsAtOnePoint)
            .toFloat()
        return minOf(PREFERRED_SINGLE_WINDOW_ALPHA, safeWindowAlpha)
    }

    fun overlaps(first: WindowBounds, second: WindowBounds): Boolean =
        first.x < second.x + second.width && second.x < first.x + first.width &&
            first.y < second.y + second.height && second.y < first.y + first.height

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
