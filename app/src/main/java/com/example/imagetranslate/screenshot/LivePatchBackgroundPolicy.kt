package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import kotlin.math.sqrt

internal data class LivePatchTextureProfile(
    val dominantColorRatio: Float,
    val luminanceStandardDeviation: Float,
    val sampledColorBucketCount: Int
)

internal object LivePatchBackgroundPolicy {
    fun resolve(
        requested: LivePatchBackgroundMode,
        profile: LivePatchTextureProfile,
        blurAvailable: Boolean
    ): LivePatchBackgroundMode = when (requested) {
        LivePatchBackgroundMode.THEME_SURFACE -> LivePatchBackgroundMode.THEME_SURFACE
        LivePatchBackgroundMode.BLUR_TINT -> if (blurAvailable) {
            LivePatchBackgroundMode.BLUR_TINT
        } else {
            LivePatchBackgroundMode.THEME_SURFACE
        }
        LivePatchBackgroundMode.ADAPTIVE -> if (
            blurAvailable &&
            profile.dominantColorRatio < MAXIMUM_UNIFORM_SURFACE_RATIO &&
            profile.luminanceStandardDeviation >= MINIMUM_TEXTURE_DEVIATION &&
            profile.sampledColorBucketCount >= MINIMUM_TEXTURE_COLOR_BUCKETS
        ) {
            LivePatchBackgroundMode.BLUR_TINT
        } else {
            LivePatchBackgroundMode.THEME_SURFACE
        }
    }

    fun profile(bitmap: Bitmap): LivePatchTextureProfile {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            return LivePatchTextureProfile(1f, 0f, 0)
        }
        val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / SAMPLE_GRID_LONG_SIDE)
        val buckets = HashMap<Int, Int>()
        var count = 0
        var luminanceSum = 0.0
        var luminanceSquareSum = 0.0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                val bucket = (red shr 5 shl 6) or (green shr 5 shl 3) or (blue shr 5)
                buckets[bucket] = (buckets[bucket] ?: 0) + 1
                val luminance = (red * 54 + green * 183 + blue * 19) / 256.0
                luminanceSum += luminance
                luminanceSquareSum += luminance * luminance
                count++
            }
        }
        val mean = luminanceSum / count.coerceAtLeast(1)
        val variance = luminanceSquareSum / count.coerceAtLeast(1) - mean * mean
        return LivePatchTextureProfile(
            dominantColorRatio = buckets.values.maxOrNull().orZero().toFloat() /
                count.coerceAtLeast(1),
            luminanceStandardDeviation = sqrt(variance.coerceAtLeast(0.0)).toFloat(),
            sampledColorBucketCount = buckets.size
        )
    }

    private fun Int?.orZero(): Int = this ?: 0

    private const val SAMPLE_GRID_LONG_SIDE = 32
    private const val MAXIMUM_UNIFORM_SURFACE_RATIO = 0.58f
    private const val MINIMUM_TEXTURE_DEVIATION = 24f
    private const val MINIMUM_TEXTURE_COLOR_BUCKETS = 8
}
