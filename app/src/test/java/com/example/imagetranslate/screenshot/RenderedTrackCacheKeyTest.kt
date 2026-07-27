package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RenderedTrackCacheKeyTest {
    @Test
    fun reuseRequiresSemanticVisualAndRenderingIdentity() {
        val baseline = key()

        assertEquals(baseline, key())
        assertNotEquals(baseline, key(translation = "不同译文"))
        assertNotEquals(baseline, key(backgroundMode = LivePatchBackgroundMode.BLUR_TINT))
        assertNotEquals(baseline, key(width = 301))
        assertNotEquals(baseline, key(surfaceColor = 0xFFF5F5F5.toInt()))
    }

    private fun key(
        translation: String = "欢迎来到维基百科",
        backgroundMode: LivePatchBackgroundMode = LivePatchBackgroundMode.THEME_SURFACE,
        width: Int = 300,
        surfaceColor: Int = 0xFFFFFFFF.toInt()
    ) = RenderedTrackCacheKey(
        sourceText = "Welcome to Wikipedia",
        translation = translation,
        width = width,
        height = 64,
        backgroundMode = backgroundMode,
        surfaceColor = surfaceColor,
        overlayAlphaPercent = 720,
        displayHints = SmartAssistDisplayHints(2, 0.72f)
    )

    @Test
    fun visualFingerprintAllowsMinorSamplingNoiseButRejectsChangedBackground() {
        assertEquals(
            true,
            LiveRenderedTrackReusePolicy.hasMatchingVisualFingerprint(
                intArrayOf(240, 242, 32, 28),
                intArrayOf(241, 240, 34, 29)
            )
        )
        assertEquals(
            false,
            LiveRenderedTrackReusePolicy.hasMatchingVisualFingerprint(
                intArrayOf(240, 242, 32, 28),
                intArrayOf(180, 175, 92, 88)
            )
        )
    }
}
