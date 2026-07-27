package com.example.imagetranslate.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.example.imagetranslate.screenshot.ScreenTranslationPatch
import kotlin.math.max
import kotlin.math.min

internal data class LiveRecognitionVisualEvidence(
    val bitmap: Bitmap,
    val patchCount: Int,
    val changedPatchCount: Int,
    val changedPatchRatio: Float,
    val changedSampleRatio: Float,
    val changedOutsidePatchCount: Int,
    val passesVisualGate: Boolean
)

internal object LiveRecognitionVisualArtifactRenderer {
    fun render(
        source: Bitmap,
        patches: List<ScreenTranslationPatch>,
        compositeAlpha: Float
    ): LiveRecognitionVisualEvidence {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (compositeAlpha.coerceIn(0f, 1f) * 255).toInt()
        }
        patches.forEach { patch ->
            canvas.drawBitmap(patch.bitmap, null, patch.bounds, paint)
        }

        var changedPatchCount = 0
        var changedSamples = 0
        var patchSamples = 0
        patches.forEach { patch ->
            val left = patch.bounds.left.coerceIn(0, source.width)
            val top = patch.bounds.top.coerceIn(0, source.height)
            val right = patch.bounds.right.coerceIn(left, source.width)
            val bottom = patch.bounds.bottom.coerceIn(top, source.height)
            val step = max(1, min(right - left, bottom - top) / PATCH_SAMPLE_DIVISOR)
            var patchChanged = false
            for (y in top until bottom step step) {
                for (x in left until right step step) {
                    patchSamples++
                    if (source.getPixel(x, y) != output.getPixel(x, y)) {
                        changedSamples++
                        patchChanged = true
                    }
                }
            }
            if (patchChanged) changedPatchCount++
        }

        var changedOutsidePatchCount = 0
        for (y in 0 until source.height step OUTSIDE_SAMPLE_STEP_PX) {
            for (x in 0 until source.width step OUTSIDE_SAMPLE_STEP_PX) {
                if (patches.any { patch -> patch.bounds.contains(x, y) }) continue
                if (source.getPixel(x, y) != output.getPixel(x, y)) {
                    changedOutsidePatchCount++
                }
            }
        }

        val changedPatchRatio = if (patches.isEmpty()) {
            0f
        } else {
            changedPatchCount.toFloat() / patches.size
        }
        val changedSampleRatio = if (patchSamples == 0) {
            0f
        } else {
            changedSamples.toFloat() / patchSamples
        }
        return LiveRecognitionVisualEvidence(
            bitmap = output,
            patchCount = patches.size,
            changedPatchCount = changedPatchCount,
            changedPatchRatio = changedPatchRatio,
            changedSampleRatio = changedSampleRatio,
            changedOutsidePatchCount = changedOutsidePatchCount,
            passesVisualGate = patches.isNotEmpty() &&
                changedPatchRatio >= MINIMUM_CHANGED_PATCH_RATIO &&
                changedSampleRatio >= MINIMUM_CHANGED_SAMPLE_RATIO &&
                changedOutsidePatchCount == 0
        )
    }

    private const val PATCH_SAMPLE_DIVISOR = 32
    private const val OUTSIDE_SAMPLE_STEP_PX = 12
    private const val MINIMUM_CHANGED_PATCH_RATIO = 0.9f
    private const val MINIMUM_CHANGED_SAMPLE_RATIO = 0.01f
}
