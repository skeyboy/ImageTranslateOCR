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
}
