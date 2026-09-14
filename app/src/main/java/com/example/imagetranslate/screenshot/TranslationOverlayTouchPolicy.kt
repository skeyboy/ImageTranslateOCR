package com.example.imagetranslate.screenshot

import android.view.WindowManager
import kotlin.math.pow

internal object TranslationOverlayTouchPolicy {
    const val PREFERRED_SINGLE_WINDOW_ALPHA = 0.72f
    val PASSTHROUGH_WINDOW_FLAGS: Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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

    fun expandTouchBounds(
        bounds: WindowBounds,
        minimumSize: Int,
        screenWidth: Int,
        screenHeight: Int
    ): WindowBounds? {
        if (minimumSize <= 0 || screenWidth <= 0 || screenHeight <= 0 ||
            bounds.width <= 0 || bounds.height <= 0
        ) return null
        val targetWidth = maxOf(bounds.width, minimumSize).coerceAtMost(screenWidth)
        val targetHeight = maxOf(bounds.height, minimumSize).coerceAtMost(screenHeight)
        val centerX = bounds.x + bounds.width / 2
        val centerY = bounds.y + bounds.height / 2
        val x = (centerX - targetWidth / 2).coerceIn(0, screenWidth - targetWidth)
        val y = (centerY - targetHeight / 2).coerceIn(0, screenHeight - targetHeight)
        return WindowBounds(x, y, targetWidth, targetHeight)
    }

    fun hasDisplayGeometryChanged(
        currentWidth: Int,
        currentHeight: Int,
        newWidth: Int,
        newHeight: Int
    ): Boolean = currentWidth > 0 && currentHeight > 0 &&
        (currentWidth != newWidth || currentHeight != newHeight)
}
