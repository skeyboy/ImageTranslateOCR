package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotOverlayPositionPolicyTest {
    @Test
    fun startsInsideTopLeftSafeMargin() {
        assertEquals(OverlayPosition(24, 24), ScreenshotOverlayPositionPolicy.initial(24))
    }

    @Test
    fun clampsDraggingToVisibleWindowBounds() {
        assertEquals(
            OverlayPosition(24, 24),
            ScreenshotOverlayPositionPolicy.clamp(
                x = -100,
                y = -50,
                windowWidth = 1080,
                windowHeight = 2400,
                overlayWidth = 720,
                overlayHeight = 900,
                marginPx = 24
            )
        )
        assertEquals(
            OverlayPosition(336, 1476),
            ScreenshotOverlayPositionPolicy.clamp(
                x = 2_000,
                y = 3_000,
                windowWidth = 1080,
                windowHeight = 2400,
                overlayWidth = 720,
                overlayHeight = 900,
                marginPx = 24
            )
        )
    }

    @Test
    fun keepsMarginWhenOverlayIsLargerThanWindow() {
        assertEquals(
            OverlayPosition(12, 12),
            ScreenshotOverlayPositionPolicy.clamp(
                x = 200,
                y = 300,
                windowWidth = 320,
                windowHeight = 320,
                overlayWidth = 400,
                overlayHeight = 500,
                marginPx = 12
            )
        )
    }
}
