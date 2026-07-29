package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveContentViewportPolicyTest {
    @Test
    fun lightBrowserChromeIsExcludedFromTheScrollingContentBand() {
        val scores = FloatArray(200)
        for (index in 31..184) scores[index] = 28f

        val result = LiveContentViewportPolicy.resolve(scores, 3_200, 16)

        assertEquals(480, result.top)
        assertEquals(2_976, result.bottom)
        assertFalse(result.usedFallback)
        assertTrue(result.confidence > 0.6f)
    }

    @Test
    fun darkBrowserWithShorterHeaderKeepsMoreContent() {
        val scores = FloatArray(200)
        for (index in 17..185) scores[index] = 24f

        val result = LiveContentViewportPolicy.resolve(scores, 3_200, 16)

        assertEquals(256, result.top)
        assertEquals(2_992, result.bottom)
        assertFalse(result.usedFallback)
    }

    @Test
    fun isolatedMotionFallsBackToTheConservativeViewport() {
        val scores = FloatArray(200)
        scores[90] = 40f

        val result = LiveContentViewportPolicy.resolve(scores, 3_200, 16)

        assertEquals(256, result.top)
        assertEquals(3_008, result.bottom)
        assertTrue(result.usedFallback)
    }
}
