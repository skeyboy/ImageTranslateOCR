package com.example.imagetranslate.semantic

import android.graphics.Rect
import kotlin.math.abs

/**
 * Converts OCR line rectangles into consecutive render lanes. A lane change preserves the
 * non-rectangular footprint of text wrapped around an image instead of filling the union box.
 */
internal object SemanticRenderShape {
    fun slots(componentBounds: List<Rect>, unionBounds: Rect): List<Rect> {
        val ordered = componentBounds
            .filter { it.right > it.left && it.bottom > it.top }
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            .sortedWith(compareBy({ it.top }, { it.left }))
        if (ordered.isEmpty()) return listOf(copyOf(unionBounds))

        val heights = ordered.map { it.bottom - it.top }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(1)
        val laneTolerance = maxOf(MINIMUM_LANE_TOLERANCE_PX, medianHeight)
        val lanes = mutableListOf<MutableList<Rect>>()
        ordered.forEach { bounds ->
            val current = lanes.lastOrNull()
            val currentLeft = current?.map(Rect::left)?.sorted()?.let { it[it.size / 2] }
            if (current == null || currentLeft == null ||
                abs(bounds.left - currentLeft) > laneTolerance
            ) {
                lanes += mutableListOf(copyOf(bounds))
            } else {
                current += copyOf(bounds)
            }
        }

        val rightExtensionTolerance = maxOf(
            MINIMUM_RIGHT_EXTENSION_TOLERANCE_PX,
            medianHeight * 2
        )
        return lanes.map { lane ->
            rect(
                left = lane.minOf(Rect::left),
                top = lane.minOf(Rect::top),
                right = lane.maxOf(Rect::right).let { laneRight ->
                    if (unionBounds.right - laneRight <= rightExtensionTolerance) {
                        unionBounds.right
                    } else {
                        laneRight
                    }
                },
                bottom = lane.maxOf(Rect::bottom)
            )
        }
    }

    private const val MINIMUM_LANE_TOLERANCE_PX = 6
    private const val MINIMUM_RIGHT_EXTENSION_TOLERANCE_PX = 12

    private fun copyOf(bounds: Rect) = Rect().apply {
        this.left = bounds.left
        this.top = bounds.top
        this.right = bounds.right
        this.bottom = bounds.bottom
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
