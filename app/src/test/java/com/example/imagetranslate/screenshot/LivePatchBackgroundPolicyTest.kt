package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePatchBackgroundPolicyTest {
    @Test
    fun standardBackgroundPassesThroughWithoutEnhancement() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.STANDARD,
            profile = LivePatchTextureProfile(0.1f, 60f, 50),
            blurAvailable = true
        )

        assertEquals(LivePatchBackgroundMode.STANDARD, result)
    }

    @Test
    fun uniformWebSurfaceUsesExactThemeFill() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.ADAPTIVE,
            profile = LivePatchTextureProfile(
                dominantColorRatio = 0.82f,
                luminanceStandardDeviation = 41f,
                sampledColorBucketCount = 5
            ),
            blurAvailable = true
        )

        assertEquals(LivePatchBackgroundMode.THEME_SURFACE, result)
    }

    @Test
    fun complexImageSurfaceUsesBlurTint() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.ADAPTIVE,
            profile = LivePatchTextureProfile(
                dominantColorRatio = 0.12f,
                luminanceStandardDeviation = 53f,
                sampledColorBucketCount = 46
            ),
            blurAvailable = true
        )

        assertEquals(LivePatchBackgroundMode.BLUR_TINT, result)
    }

    @Test
    fun unavailableBlurAlwaysFallsBackToThemeSurface() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.ADAPTIVE,
            profile = LivePatchTextureProfile(0.1f, 60f, 50),
            blurAvailable = false
        )

        assertEquals(LivePatchBackgroundMode.THEME_SURFACE, result)
    }

    @Test
    fun explicitlyRequestedBlurFallsBackToThemeSurfaceWhenUnavailable() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.BLUR_TINT,
            profile = LivePatchTextureProfile(0.1f, 60f, 50),
            blurAvailable = false
        )

        assertEquals(LivePatchBackgroundMode.THEME_SURFACE, result)
    }

    @Test
    fun featheredBlurFallsBackToFeatheredThemeWhenUnavailable() {
        val result = LivePatchBackgroundPolicy.resolve(
            requested = LivePatchBackgroundMode.FEATHERED_BLUR_TINT,
            profile = LivePatchTextureProfile(0.1f, 60f, 50),
            blurAvailable = false
        )

        assertEquals(LivePatchBackgroundMode.FEATHERED_THEME_SURFACE, result)
    }
}
