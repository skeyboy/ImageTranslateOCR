package com.example.imagetranslate.screenshot

internal object ActiveOverlayPositionPolicy {
    fun resizeAroundCenter(
        x: Int,
        y: Int,
        fromWidth: Int,
        fromHeight: Int,
        toWidth: Int,
        toHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        margin: Int
    ): OverlayPosition = ScreenshotOverlayPositionPolicy.clamp(
        x = x + (fromWidth - toWidth) / 2,
        y = y + (fromHeight - toHeight) / 2,
        windowWidth = screenWidth,
        windowHeight = screenHeight,
        overlayWidth = toWidth,
        overlayHeight = toHeight,
        marginPx = margin
    )
}
