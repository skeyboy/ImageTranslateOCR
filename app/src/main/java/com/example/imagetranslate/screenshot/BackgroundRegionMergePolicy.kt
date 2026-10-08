package com.example.imagetranslate.screenshot

import android.graphics.Rect
import kotlin.math.max

internal enum class BackgroundRegionShape {
    CONTIGUOUS_RECT,
    BRIDGED_FLOW,
    SEPARATE_BLOCKS
}

internal data class BackgroundRegionMergeResult(
    val regions: List<Rect>,
    val shape: BackgroundRegionShape,
    val areaInflationRatio: Float
)

internal object BackgroundRegionMergePolicy {
    fun preferredTextSlots(
        mergeResult: BackgroundRegionMergeResult,
        originalRenderSlots: List<Rect>,
        role: String?,
        layoutShape: String?
    ): List<Rect> {
        val merged = mergeResult.regions.singleOrNull()
        return if (role == "BODY" && layoutShape == "FLOW_SLOTS" &&
            originalRenderSlots.size > 1 &&
            mergeResult.shape == BackgroundRegionShape.CONTIGUOUS_RECT &&
            merged != null
        ) {
            listOf(copyRect(merged))
        } else {
            originalRenderSlots.map(::copyRect)
        }
    }

    fun merge(
        source: List<Rect>,
        representativeLineHeightPx: Float,
        maximumGapRatio: Float = DEFAULT_MAXIMUM_GAP_RATIO
    ): BackgroundRegionMergeResult {
        val slots = normalize(source)
        if (slots.size < 2) {
            return BackgroundRegionMergeResult(
                regions = slots,
                shape = BackgroundRegionShape.SEPARATE_BLOCKS,
                areaInflationRatio = 0f
            )
        }
        val lineHeight = representativeLineHeightPx.takeIf { it > 0f }
            ?: slots.map(::height).filter { it > 0 }.sorted()
                .let { values -> values[values.lastIndex / 2].toFloat() }
        val clusters = mutableListOf<MutableList<Rect>>()
        slots.forEach { slot ->
            val current = clusters.lastOrNull()
            if (current != null && adjacent(
                    current.last(),
                    slot,
                    lineHeight,
                    maximumGapRatio
                )
            ) {
                current += slot
            } else {
                clusters += mutableListOf(slot)
            }
        }
        val output = mutableListOf<Rect>()
        var usedBridge = false
        var usedUnion = false
        var maximumInflation = 0f
        clusters.forEach { cluster ->
            if (cluster.size == 1) {
                output += copyRect(cluster.single())
                return@forEach
            }
            val union = union(cluster)
            val sourceArea = unionArea(cluster).coerceAtLeast(1L)
            val inflation = ((width(union).toLong() * height(union) - sourceArea)
                .coerceAtLeast(0L)).toFloat() / sourceArea
            maximumInflation = max(maximumInflation, inflation)
            if (inflation <= MAXIMUM_RECT_INFLATION_RATIO) {
                output += union
                usedUnion = true
            } else {
                output += cluster.map(::copyRect)
                cluster.zipWithNext().mapNotNullTo(output) { (first, second) ->
                    bridge(first, second)?.also { usedBridge = true }
                }
            }
        }
        return BackgroundRegionMergeResult(
            regions = normalize(output, removeContained = false),
            shape = when {
                usedBridge -> BackgroundRegionShape.BRIDGED_FLOW
                usedUnion -> BackgroundRegionShape.CONTIGUOUS_RECT
                else -> BackgroundRegionShape.SEPARATE_BLOCKS
            },
            areaInflationRatio = maximumInflation
        )
    }

    private fun adjacent(
        first: Rect,
        second: Rect,
        lineHeight: Float,
        maximumGapRatio: Float
    ): Boolean {
        val gap = second.top - first.bottom
        if (gap < -(lineHeight * MAXIMUM_OVERLAP_RATIO).toInt() ||
            gap > max(MINIMUM_GAP_PX, (lineHeight * maximumGapRatio).toInt())
        ) return false
        val overlap = (minOf(first.right, second.right) - maxOf(first.left, second.left))
            .coerceAtLeast(0)
        val overlapRatio = overlap.toFloat() /
            minOf(width(first), width(second)).coerceAtLeast(1)
        return overlapRatio >= MINIMUM_HORIZONTAL_OVERLAP_RATIO
    }

    private fun bridge(first: Rect, second: Rect): Rect? {
        if (second.top <= first.bottom) return null
        val left = maxOf(first.left, second.left)
        val right = minOf(first.right, second.right)
        if (right <= left) return null
        return rect(left, first.bottom, right, second.top)
    }

    private fun normalize(
        source: List<Rect>,
        removeContained: Boolean = true
    ): List<Rect> {
        val distinct = source.filter { width(it) > 0 && height(it) > 0 }
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        val selected = if (removeContained) {
            distinct.filterIndexed { index, candidate ->
                distinct.indices.none { otherIndex ->
                    otherIndex != index && contains(distinct[otherIndex], candidate)
                }
            }
        } else {
            distinct
        }
        return selected.sortedWith(compareBy({ it.top }, { it.left }, { it.bottom }, { it.right }))
            .map(::copyRect)
    }

    private fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    private fun union(source: List<Rect>): Rect = rect(
        left = source.minOf(Rect::left),
        top = source.minOf(Rect::top),
        right = source.maxOf(Rect::right),
        bottom = source.maxOf(Rect::bottom)
    )

    private fun copyRect(source: Rect): Rect = rect(
        source.left,
        source.top,
        source.right,
        source.bottom
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun width(rect: Rect): Int = rect.right - rect.left

    private fun height(rect: Rect): Int = rect.bottom - rect.top

    private fun unionArea(source: List<Rect>): Long {
        val xValues = source.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var area = 0L
        for (index in 0 until xValues.lastIndex) {
            val left = xValues[index]
            val right = xValues[index + 1]
            if (right <= left) continue
            val intervals = source.filter { it.left < right && it.right > left }
                .map { it.top to it.bottom }
                .sortedBy { it.first }
            var covered = 0
            var top = Int.MIN_VALUE
            var bottom = Int.MIN_VALUE
            intervals.forEach { interval ->
                if (top == Int.MIN_VALUE) {
                    top = interval.first
                    bottom = interval.second
                } else if (interval.first <= bottom) {
                    bottom = maxOf(bottom, interval.second)
                } else {
                    covered += bottom - top
                    top = interval.first
                    bottom = interval.second
                }
            }
            if (top != Int.MIN_VALUE) covered += bottom - top
            area += (right - left).toLong() * covered
        }
        return area
    }

    private const val DEFAULT_MAXIMUM_GAP_RATIO = 0.45f
    private const val MAXIMUM_OVERLAP_RATIO = 0.35f
    private const val MINIMUM_HORIZONTAL_OVERLAP_RATIO = 0.65f
    private const val MAXIMUM_RECT_INFLATION_RATIO = 0.25f
    private const val MINIMUM_GAP_PX = 3
}
