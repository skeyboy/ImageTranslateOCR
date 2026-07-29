package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePatchBackgroundComposerTest {
    @Test
    fun bitmapSamplingUsesTheDominantPageTheme() {
        val pageColor = Color.rgb(247, 248, 250)
        val source = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(pageColor)
            for (y in 0 until height step 8) {
                for (x in 0 until width / 5) {
                    setPixel(x, y, Color.rgb(32, 33, 36))
                }
            }
            for (y in 3 until height step 19) {
                for (x in width * 3 / 4 until width) {
                    setPixel(x, y, Color.rgb(66, 133, 244))
                }
            }
        }

        assertEquals(pageColor, ScreenThemeColorEstimator.estimate(source))

        source.recycle()
    }

    @Test
    fun blurTintReducesSourceDetailAndPreservesDimensions() {
        val source = stripedSource(240, 120)
        val alpha = TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
        val theme = ScreenThemeColorEstimator.compositableSurface(Color.WHITE, alpha)

        val background = LivePatchBackgroundComposer.createBlurTintTarget(source, theme, alpha)

        assertEquals(source.width, background.bitmap.width)
        assertEquals(source.height, background.bitmap.height)
        assertTrue(background.detailRetentionRatio in 0f..0.2f)

        background.bitmap.recycle()
        source.recycle()
    }

    @Test
    fun compensatedPatchReconstructsBlurTargetThroughTransparentWindow() {
        val source = stripedSource(160, 80)
        val alpha = TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
        val theme = ScreenThemeColorEstimator.compositableSurface(Color.rgb(232, 238, 244), alpha)
        val background = LivePatchBackgroundComposer.createBlurTintTarget(source, theme, alpha)
        val patch = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)

        LivePatchBackgroundComposer.drawCompensatedTarget(
            output = patch,
            target = background.bitmap,
            source = source,
            overlayAlpha = alpha
        )

        for (y in 0 until source.height step 7) {
            for (x in 0 until source.width step 7) {
                val reconstructed = composite(patch.getPixel(x, y), source.getPixel(x, y), alpha)
                val target = background.bitmap.getPixel(x, y)
                assertTrue(channelDistance(reconstructed, target) <= 3)
            }
        }

        patch.recycle()
        background.bitmap.recycle()
        source.recycle()
    }

    @Test
    fun blurTintMovesTheBackgroundTowardTheThemeSurface() {
        val sourceColor = Color.rgb(210, 72, 64)
        val theme = ScreenThemeColorEstimator.compositableSurface(
            Color.rgb(62, 128, 214),
            TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
        )
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(sourceColor)
        }

        val background = LivePatchBackgroundComposer.createBlurTintTarget(
            source,
            theme,
            TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
        )
        val outputColor = background.bitmap.getPixel(60, 40)

        assertTrue(colorDistance(outputColor, theme) < colorDistance(outputColor, sourceColor))

        background.bitmap.recycle()
        source.recycle()
    }

    @Test
    fun fullPageBlurUsesAThickerMaterialWhilePreservingTheDarkTheme() {
        val theme = Color.rgb(18, 20, 22)
        val source = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(theme)
            for (y in 16 until height step 24) {
                for (x in 12 until width - 12) {
                    setPixel(x, y, Color.rgb(185, 190, 196))
                }
            }
        }

        val background = LivePatchBackgroundComposer.createFullPageBlurTintTarget(
            source,
            theme,
            1f
        )
        val center = background.bitmap.getPixel(source.width / 2, source.height / 2)

        assertTrue(ScreenThemeColorEstimator.isDark(center))
        assertTrue(background.detailRetentionRatio in 0f..0.2f)

        background.bitmap.recycle()
        source.recycle()
    }

    @Test
    fun featheredMaterialKeepsTheTextCoreOpaqueAndRemovesHardPatchEdges() {
        val source = stripedSource(160, 80)
        val alpha = TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
        val theme = ScreenThemeColorEstimator.compositableSurface(Color.WHITE, alpha)
        val patch = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        LivePatchBackgroundComposer.drawCompensatedColorTarget(
            output = patch,
            source = source,
            themeSurface = theme,
            overlayAlpha = alpha
        )

        LivePatchBackgroundComposer.applyFeatheredAlpha(
            output = patch,
            opaqueCore = android.graphics.Rect(20, 12, 140, 68)
        )

        assertEquals(0, Color.alpha(patch.getPixel(0, 0)))
        assertEquals(255, Color.alpha(patch.getPixel(80, 40)))
        val reconstructedCore = composite(
            patch.getPixel(80, 40),
            source.getPixel(80, 40),
            alpha
        )
        assertTrue(channelDistance(reconstructedCore, theme) <= 3)

        patch.recycle()
        source.recycle()
    }

    private fun stripedSource(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                when {
                    y % 24 in 7..12 && x % 52 in 4..42 -> Color.rgb(22, 28, 34)
                    else -> Color.rgb(242 + x % 6, 244 + y % 5, 248)
                }
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun composite(foreground: Int, background: Int, alpha: Float): Int = Color.rgb(
        (Color.red(foreground) * alpha + Color.red(background) * (1f - alpha)).toInt(),
        (Color.green(foreground) * alpha + Color.green(background) * (1f - alpha)).toInt(),
        (Color.blue(foreground) * alpha + Color.blue(background) * (1f - alpha)).toInt()
    )

    private fun channelDistance(first: Int, second: Int): Int = maxOf(
        kotlin.math.abs(Color.red(first) - Color.red(second)),
        kotlin.math.abs(Color.green(first) - Color.green(second)),
        kotlin.math.abs(Color.blue(first) - Color.blue(second))
    )

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))
}
