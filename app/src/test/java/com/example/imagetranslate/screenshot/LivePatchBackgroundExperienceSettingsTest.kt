package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePatchBackgroundExperienceSettingsTest {
    @Test
    fun defaultExperienceIsOffAndUsesStandardBackground() {
        assertEquals(
            LivePatchBackgroundExperienceMode.OFF,
            LivePatchBackgroundExperiencePolicy.default
        )
        assertEquals(
            LivePatchBackgroundMode.STANDARD,
            LivePatchBackgroundExperiencePolicy.executionProfile(
                LivePatchBackgroundExperiencePolicy.default
            ).backgroundMode
        )
    }

    @Test
    fun themeColorExperienceUsesThemeSurface() {
        assertEquals(
            LivePatchBackgroundMode.THEME_SURFACE,
            LivePatchBackgroundExperiencePolicy.executionProfile(
                LivePatchBackgroundExperienceMode.THEME_COLOR
            ).backgroundMode
        )
    }

    @Test
    fun gaussianBlurExperienceUsesBlurTint() {
        assertEquals(
            LivePatchBackgroundMode.BLUR_TINT,
            LivePatchBackgroundExperiencePolicy.executionProfile(
                LivePatchBackgroundExperienceMode.GAUSSIAN_BLUR
            ).backgroundMode
        )
    }

    @Test
    fun invalidStoredExperienceFallsBackToOff() {
        assertEquals(
            LivePatchBackgroundExperienceMode.OFF,
            LivePatchBackgroundExperiencePolicy.fromStored("UNKNOWN")
        )
    }
}
