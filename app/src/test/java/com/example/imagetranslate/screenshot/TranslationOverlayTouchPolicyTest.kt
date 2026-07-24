package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationOverlayTouchPolicyTest {
    @Test
    fun usesAReadableAlphaForANonOverlappingPatch() {
        assertEquals(
            0.72f,
            TranslationOverlayTouchPolicy.windowAlpha(0.8f, overlapCount = 1),
            0.001f
        )
    }

    @Test
    fun staysBelowTheThresholdWhenTwoPatchesOverlap() {
        val alpha = TranslationOverlayTouchPolicy.windowAlpha(0.8f, overlapCount = 2)

        assertEquals(0.531f, alpha, 0.001f)
        assertTrue(1f - (1f - alpha) * (1f - alpha) < 0.8f)
    }

    @Test
    fun adaptsToADeviceWithAStricterThreshold() {
        assertEquals(
            0.352f,
            TranslationOverlayTouchPolicy.windowAlpha(0.6f, overlapCount = 2),
            0.001f
        )
    }

    @Test
    fun touchThroughWinsWhenTheDeviceThresholdIsVeryLow() {
        assertEquals(
            0.015f,
            TranslationOverlayTouchPolicy.windowAlpha(0.05f, overlapCount = 2),
            0.001f
        )
    }

    @Test
    fun detectsOnlyWindowsWithAnActualSharedArea() {
        val first = TranslationOverlayTouchPolicy.WindowBounds(10, 10, 50, 30)

        assertTrue(
            TranslationOverlayTouchPolicy.overlaps(
                first,
                TranslationOverlayTouchPolicy.WindowBounds(40, 20, 30, 20)
            )
        )
        assertTrue(
            !TranslationOverlayTouchPolicy.overlaps(
                first,
                TranslationOverlayTouchPolicy.WindowBounds(60, 10, 20, 30)
            )
        )
    }

    @Test
    fun scalesPatchToItsOwnSmallWindowInsteadOfTheWholeScreen() {
        assertEquals(
            TranslationOverlayTouchPolicy.WindowBounds(100, 200, 300, 100),
            TranslationOverlayTouchPolicy.scalePatchBounds(
                left = 200,
                top = 400,
                right = 800,
                bottom = 600,
                sourceWidth = 2880,
                sourceHeight = 6400,
                screenWidth = 1440,
                screenHeight = 3200
            )
        )
    }

    @Test
    fun rejectsInvalidPatchDimensions() {
        assertEquals(
            null,
            TranslationOverlayTouchPolicy.scalePatchBounds(
                left = 10,
                top = 10,
                right = 10,
                bottom = 20,
                sourceWidth = 100,
                sourceHeight = 100,
                screenWidth = 100,
                screenHeight = 100
            )
        )
    }
}
