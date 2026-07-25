package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOverlayExperiencePolicyTest {
    @Test
    fun enhancedModeFallsBackUntilAccessibilityIsConnected() {
        assertEquals(
            LiveOverlayExperienceMode.DEFAULT,
            LiveOverlayExperiencePolicy.resolve(
                LiveOverlayExperienceMode.ENHANCED,
                accessibilityConnected = false
            )
        )
    }

    @Test
    fun enhancedModeUsesAnOpaqueTrustedWindow() {
        assertEquals(
            LiveOverlayExperienceMode.ENHANCED,
            LiveOverlayExperiencePolicy.resolve(
                LiveOverlayExperienceMode.ENHANCED,
                accessibilityConnected = true
            )
        )
        assertEquals(
            1f,
            LiveOverlayExperiencePolicy.translationWindowAlpha(
                LiveOverlayExperienceMode.ENHANCED,
                standardOverlayAlpha = 0.72f
            ),
            0.001f
        )
        assertEquals(
            0xFFF4F4F4.toInt(),
            ScreenThemeColorEstimator.compositableSurface(
                0xFFF4F4F4.toInt(),
                overlayAlpha = 1f
            )
        )
    }

    @Test
    fun defaultModeKeepsTheTouchThroughAlpha() {
        assertEquals(
            0.72f,
            LiveOverlayExperiencePolicy.translationWindowAlpha(
                LiveOverlayExperienceMode.DEFAULT,
                standardOverlayAlpha = 0.72f
            ),
            0.001f
        )
    }
}
