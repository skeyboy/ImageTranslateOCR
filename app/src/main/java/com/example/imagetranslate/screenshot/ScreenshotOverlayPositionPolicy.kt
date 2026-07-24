package com.example.imagetranslate.screenshot

internal data class OverlayPosition(val x: Int, val y: Int)

internal object ScreenshotOverlayPositionPolicy {
    fun initial(marginPx: Int): OverlayPosition = OverlayPosition(marginPx, marginPx)

    fun clamp(
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        marginPx: Int
    ): OverlayPosition {
        val maximumX = (windowWidth - overlayWidth - marginPx).coerceAtLeast(marginPx)
        val maximumY = (windowHeight - overlayHeight - marginPx).coerceAtLeast(marginPx)
        return OverlayPosition(
            x.coerceIn(marginPx, maximumX),
            y.coerceIn(marginPx, maximumY)
        )
    }
}
