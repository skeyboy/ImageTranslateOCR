package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

internal data class ScreenTranslationPatch(
    val bounds: Rect,
    val bitmap: Bitmap
)

internal fun Iterable<ScreenTranslationPatch>.recyclePatchBitmaps() {
    forEach { patch ->
        if (!patch.bitmap.isRecycled) patch.bitmap.recycle()
    }
}

internal class ScreenTranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var patches = emptyList<ScreenTranslationPatch>()
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var patchesVisible = true
    private var contentOffsetY = 0

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
        isLongClickable = false
        visibility = INVISIBLE
    }

    fun replacePatches(
        newPatches: List<ScreenTranslationPatch>,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        recyclePatches()
        patches = newPatches
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        contentOffsetY = 0
        animate().cancel()
        if (newPatches.isEmpty() || !patchesVisible) {
            alpha = 1f
            visibility = INVISIBLE
        } else {
            alpha = 0f
            visibility = VISIBLE
            animate()
                .alpha(1f)
                .setDuration(PATCH_FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        invalidate()
    }

    fun setPatchesVisible(visible: Boolean, animateChange: Boolean = true) {
        patchesVisible = visible
        animate().cancel()
        if (!visible || patches.isEmpty()) {
            alpha = 1f
            visibility = INVISIBLE
            return
        }
        visibility = VISIBLE
        if (animateChange) {
            alpha = 0f
            animate()
                .alpha(1f)
                .setDuration(VISIBILITY_FADE_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            alpha = 1f
        }
    }

    fun beginMovementPreview(estimatedShiftY: Int) {
        if (!patchesVisible || patches.isEmpty() || visibility != VISIBLE) return
        contentOffsetY = estimatedShiftY
        invalidate()
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(MOVEMENT_PREVIEW_FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (alpha == 0f) visibility = INVISIBLE
            }
            .start()
    }

    fun updateMovementPreview(estimatedShiftY: Int) {
        if (visibility != VISIBLE) return
        contentOffsetY = estimatedShiftY
        invalidate()
    }

    fun clearPatches() {
        animate().cancel()
        alpha = 1f
        recyclePatches()
        sourceWidth = 0
        sourceHeight = 0
        contentOffsetY = 0
        visibility = INVISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0 || patches.isEmpty()) return
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        canvas.save()
        canvas.scale(scaleX, scaleY)
        canvas.translate(0f, contentOffsetY.toFloat())
        patches.forEach { patch ->
            if (!patch.bitmap.isRecycled) {
                canvas.drawBitmap(patch.bitmap, null, patch.bounds, paint)
            }
        }
        canvas.restore()
    }

    private fun recyclePatches() {
        patches.recyclePatchBitmaps()
        patches = emptyList()
    }

    private companion object {
        const val PATCH_FADE_IN_MS = 140L
        const val VISIBILITY_FADE_IN_MS = 90L
        const val MOVEMENT_PREVIEW_FADE_MS = 140L
    }
}
