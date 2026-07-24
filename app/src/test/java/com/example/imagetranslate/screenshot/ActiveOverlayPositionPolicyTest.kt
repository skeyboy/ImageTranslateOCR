package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveOverlayPositionPolicyTest {
    @Test
    fun collapsingKeepsTheControlCenteredAtItsDraggedPosition() {
        val result = ActiveOverlayPositionPolicy.resizeAroundCenter(
            x = 120,
            y = 800,
            fromWidth = 306,
            fromHeight = 56,
            toWidth = 132,
            toHeight = 42,
            screenWidth = 1080,
            screenHeight = 2400,
            margin = 12
        )

        assertEquals(207, result.x)
        assertEquals(807, result.y)
    }

    @Test
    fun expandingNearAnEdgeStaysInsideTheScreen() {
        val result = ActiveOverlayPositionPolicy.resizeAroundCenter(
            x = 12,
            y = 12,
            fromWidth = 132,
            fromHeight = 42,
            toWidth = 306,
            toHeight = 56,
            screenWidth = 360,
            screenHeight = 800,
            margin = 12
        )

        assertEquals(12, result.x)
        assertEquals(12, result.y)
    }
}
