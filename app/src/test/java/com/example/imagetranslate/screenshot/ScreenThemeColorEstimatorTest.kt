package com.example.imagetranslate.screenshot

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenThemeColorEstimatorTest {
    @Test
    fun usesTheDominantPageColorInsteadOfTextAndAccentPixels() {
        val samples = IntArray(100) { index ->
            when {
                index < 82 -> 0xFFF7F8FA.toInt()
                index < 94 -> 0xFF202124.toInt()
                else -> 0xFF4285F4.toInt()
            }
        }

        assertEquals(0xFFF7F8FA.toInt(), ScreenThemeColorEstimator.estimate(samples))
    }

    @Test
    fun preservesASaturatedDarkThemeColor() {
        val samples = intArrayOf(
            0xFF173B57.toInt(),
            0xFF183C58.toInt(),
            0xFF173A56.toInt(),
            0xFFFFFFFF.toInt()
        )

        assertEquals(0xFF173B57.toInt(), ScreenThemeColorEstimator.estimate(samples))
    }

    @Test
    fun ignoresTransparentSamplesAndFallsBackToWhite() {
        assertEquals(
            0xFFFFFFFF.toInt(),
            ScreenThemeColorEstimator.estimate(intArrayOf(0x00123456, 0x7FABCDEF))
        )
    }

    @Test
    fun clampsALightThemeToAComponentRangeThatCanHideTheOriginalPixels() {
        assertEquals(
            0xFFB7B7B7.toInt(),
            ScreenThemeColorEstimator.compositableSurface(0xFFFFEABB.toInt())
        )
    }

    @Test
    fun clampsADarkThemeToTheSameCompositableRangeAndRetainsAvailableHue() {
        assertEquals(
            0xFF484857.toInt(),
            ScreenThemeColorEstimator.compositableSurface(0xFF173B57.toInt())
        )
    }

    @Test
    fun compensationMakesBlackAndWhiteSourcePixelsConvergeOnTheSameSurface() {
        val target = 0xFFB7B7B7.toInt()
        val blackResult = composite(
            ScreenThemeColorEstimator.compensationColor(target, 0xFF000000.toInt()),
            0xFF000000.toInt()
        )
        val whiteResult = composite(
            ScreenThemeColorEstimator.compensationColor(target, 0xFFFFFFFF.toInt()),
            0xFFFFFFFF.toInt()
        )

        assertTrue(colorDistance(blackResult, target) <= 3)
        assertTrue(colorDistance(whiteResult, target) <= 3)
    }

    private fun composite(overlay: Int, source: Int): Int {
        val alpha = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA
        fun component(shift: Int): Int {
            val overlayValue = overlay ushr shift and 0xFF
            val sourceValue = source ushr shift and 0xFF
            return (overlayValue * alpha + sourceValue * (1f - alpha)).roundToInt()
        }
        return 0xFF000000.toInt() or
            (component(16) shl 16) or (component(8) shl 8) or component(0)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs((first ushr 16 and 0xFF) - (second ushr 16 and 0xFF)) +
            kotlin.math.abs((first ushr 8 and 0xFF) - (second ushr 8 and 0xFF)) +
            kotlin.math.abs((first and 0xFF) - (second and 0xFF))
}
