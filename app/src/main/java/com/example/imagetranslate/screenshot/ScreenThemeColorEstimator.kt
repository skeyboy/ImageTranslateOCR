package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object ScreenThemeColorEstimator {
    const val DEFAULT_OVERLAY_ALPHA =
        TranslationOverlayTouchPolicy.PREFERRED_SINGLE_WINDOW_ALPHA
    private const val BUCKET_COUNT = 4_096
    private const val TARGET_SAMPLE_COUNT = 12_000
    private const val MINIMUM_VISIBLE_ALPHA = 128

    fun estimate(bitmap: Bitmap): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return OPAQUE_WHITE
        val area = bitmap.width.toLong() * bitmap.height
        val sampleStep = sqrt(area.toDouble() / TARGET_SAMPLE_COUNT)
            .toInt()
            .coerceAtLeast(1)
        val samples = ArrayList<Int>((area / sampleStep / sampleStep).toInt().coerceAtLeast(1))
        val offset = sampleStep / 2
        for (y in offset until bitmap.height step sampleStep) {
            for (x in offset until bitmap.width step sampleStep) {
                samples += bitmap.getPixel(x, y)
            }
        }
        return estimate(samples.toIntArray())
    }

    fun estimate(samples: IntArray): Int {
        val counts = IntArray(BUCKET_COUNT)
        val redTotals = LongArray(BUCKET_COUNT)
        val greenTotals = LongArray(BUCKET_COUNT)
        val blueTotals = LongArray(BUCKET_COUNT)
        samples.forEach { color ->
            if (color ushr 24 < MINIMUM_VISIBLE_ALPHA) return@forEach
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            val bucket = (red / 16 shl 8) or (green / 16 shl 4) or (blue / 16)
            counts[bucket]++
            redTotals[bucket] += red
            greenTotals[bucket] += green
            blueTotals[bucket] += blue
        }
        val dominantBucket = counts.indices.maxByOrNull { counts[it] }
            ?.takeIf { counts[it] > 0 }
            ?: return OPAQUE_WHITE
        val count = counts[dominantBucket]
        return OPAQUE_ALPHA or
            ((redTotals[dominantBucket] / count).toInt() shl 16) or
            ((greenTotals[dominantBucket] / count).toInt() shl 8) or
            (blueTotals[dominantBucket] / count).toInt()
    }

    fun compositableSurface(
        themeColor: Int,
        overlayAlpha: Float = DEFAULT_OVERLAY_ALPHA
    ): Int {
        val red = themeColor ushr 16 and 0xFF
        val green = themeColor ushr 8 and 0xFF
        val blue = themeColor and 0xFF
        val alpha = overlayAlpha.coerceIn(0.01f, 1f)
        val minimumComponent = kotlin.math.ceil((1f - alpha) * 255f).toInt()
        val maximumComponent = kotlin.math.floor(alpha * 255f).toInt()
            .coerceAtLeast(minimumComponent)
        return rgb(
            red.coerceIn(minimumComponent, maximumComponent),
            green.coerceIn(minimumComponent, maximumComponent),
            blue.coerceIn(minimumComponent, maximumComponent)
        )
    }

    fun compensationColor(
        targetSurface: Int,
        sourceColor: Int,
        overlayAlpha: Float = DEFAULT_OVERLAY_ALPHA
    ): Int {
        val alpha = overlayAlpha.coerceIn(0.01f, 1f)
        return rgb(
            compensate(
                targetSurface ushr 16 and 0xFF,
                sourceColor ushr 16 and 0xFF,
                alpha
            ),
            compensate(
                targetSurface ushr 8 and 0xFF,
                sourceColor ushr 8 and 0xFF,
                alpha
            ),
            compensate(targetSurface and 0xFF, sourceColor and 0xFF, alpha)
        )
    }

    private fun compensate(target: Int, source: Int, alpha: Float): Int =
        ((target - (1f - alpha) * source) / alpha)
            .roundToInt()
            .coerceIn(0, 255)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue

    private const val OPAQUE_ALPHA = -0x1000000
    private const val OPAQUE_WHITE = -0x1
}
