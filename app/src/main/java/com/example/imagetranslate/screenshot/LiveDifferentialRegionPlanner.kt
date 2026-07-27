package com.example.imagetranslate.screenshot

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class LiveDifferentialBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = (right - left).coerceAtLeast(0)
    val height: Int
        get() = (bottom - top).coerceAtLeast(0)
    val area: Long
        get() = width.toLong() * height

    fun intersects(other: LiveDifferentialBounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class LiveLuminanceGrid(
    val columns: Int,
    val rows: Int,
    val values: IntArray
) {
    init {
        require(columns > 0 && rows > 0 && values.size == columns * rows)
    }
}

internal data class LiveDirtyGridResult(
    val bounds: List<LiveDifferentialBounds>,
    val dirtyCellCount: Int,
    val comparedCellCount: Int
)

internal data class LiveDifferentialRegionPlan(
    val recognitionBounds: List<LiveDifferentialBounds>,
    val dirtyCellCount: Int,
    val comparedCellCount: Int,
    val boundaryTrackCount: Int,
    val recognitionAreaRatio: Float
)

internal object LiveDirtyGridPolicy {
    fun detect(
        previous: LiveLuminanceGrid,
        current: LiveLuminanceGrid,
        shiftY: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        contentTop: Int,
        contentBottom: Int
    ): LiveDirtyGridResult {
        if (previous.columns != current.columns || previous.rows != current.rows ||
            viewportWidth <= 0 || viewportHeight <= 0 || contentBottom <= contentTop
        ) return LiveDirtyGridResult(emptyList(), 0, 0)

        val cellColumns = min(DETECTION_COLUMNS, current.columns)
        val cellRows = min(DETECTION_ROWS, current.rows)
        val dirty = mutableListOf<LiveDifferentialBounds>()
        var compared = 0
        repeat(cellRows) { cellRow ->
            val top = viewportHeight * cellRow / cellRows
            val bottom = viewportHeight * (cellRow + 1) / cellRows
            if (top < contentTop || bottom > contentBottom) return@repeat
            repeat(cellColumns) { cellColumn ->
                val left = viewportWidth * cellColumn / cellColumns
                val right = viewportWidth * (cellColumn + 1) / cellColumns
                val error = alignedCellError(
                    previous = previous,
                    current = current,
                    cellColumn = cellColumn,
                    cellRow = cellRow,
                    cellColumns = cellColumns,
                    cellRows = cellRows,
                    shiftY = shiftY,
                    viewportHeight = viewportHeight
                ) ?: return@repeat
                compared++
                if (error >= DIRTY_LUMINANCE_ERROR) {
                    dirty += LiveDifferentialBounds(left, top, right, bottom)
                }
            }
        }
        return LiveDirtyGridResult(
            bounds = mergeCells(dirty),
            dirtyCellCount = dirty.size,
            comparedCellCount = compared
        )
    }

    private fun alignedCellError(
        previous: LiveLuminanceGrid,
        current: LiveLuminanceGrid,
        cellColumn: Int,
        cellRow: Int,
        cellColumns: Int,
        cellRows: Int,
        shiftY: Int,
        viewportHeight: Int
    ): Float? {
        val startX = current.columns * cellColumn / cellColumns
        val endX = current.columns * (cellColumn + 1) / cellColumns
        val startY = current.rows * cellRow / cellRows
        val endY = current.rows * (cellRow + 1) / cellRows
        val scaledShift = shiftY.toFloat() * current.rows / viewportHeight
        val alignedStart = (startY - scaledShift).toInt()
        val alignedEnd = (endY - 1 - scaledShift).toInt()
        if (alignedStart !in 0 until previous.rows || alignedEnd !in 0 until previous.rows) {
            return null
        }
        var bestError = Float.MAX_VALUE
        for (searchOffset in -ALIGNMENT_SEARCH_ROWS..ALIGNMENT_SEARCH_ROWS) {
            var total = 0L
            var count = 0
            for (y in startY until endY) {
                val previousY = (y - scaledShift + searchOffset).toInt()
                if (previousY !in 0 until previous.rows) continue
                for (x in startX until endX) {
                    total += abs(
                        current.values[y * current.columns + x] -
                            previous.values[previousY * previous.columns + x]
                    )
                    count++
                }
            }
            if (count > 0) bestError = min(bestError, total.toFloat() / count)
        }
        return bestError.takeIf { it != Float.MAX_VALUE }
    }

    private fun mergeCells(source: List<LiveDifferentialBounds>): List<LiveDifferentialBounds> {
        val pending = source.toMutableList()
        val merged = mutableListOf<LiveDifferentialBounds>()
        while (pending.isNotEmpty()) {
            var active = pending.removeAt(pending.lastIndex)
            var changed: Boolean
            do {
                changed = false
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (touches(active, candidate)) {
                        active = union(active, candidate)
                        iterator.remove()
                        changed = true
                    }
                }
            } while (changed)
            merged += active
        }
        return merged
    }

    private fun touches(first: LiveDifferentialBounds, second: LiveDifferentialBounds): Boolean =
        first.left <= second.right && second.left <= first.right &&
            first.top <= second.bottom && second.top <= first.bottom

    private const val DETECTION_COLUMNS = 12
    private const val DETECTION_ROWS = 20
    private const val ALIGNMENT_SEARCH_ROWS = 2
    private const val DIRTY_LUMINANCE_ERROR = 18f
}

internal object LiveDifferentialRegionPlanner {
    fun plan(
        viewportWidth: Int,
        viewportHeight: Int,
        shiftY: Int,
        dirtyGrid: LiveDirtyGridResult,
        shiftedTracks: List<LiveDifferentialBounds>,
        continuationBounds: List<LiveDifferentialBounds> = emptyList()
    ): LiveDifferentialRegionPlan? {
        if (viewportWidth <= 0 || viewportHeight <= 0 || shiftY == 0) return null
        val contentTop = (viewportHeight * CONTENT_TOP_RATIO).toInt()
        val contentBottom = (viewportHeight * CONTENT_BOTTOM_RATIO).toInt()
        val overlapMargin = max(MINIMUM_CONTEXT_PX, viewportHeight / CONTEXT_HEIGHT_DIVISOR)
        val incoming = if (shiftY < 0) {
            LiveDifferentialBounds(
                left = 0,
                top = (contentBottom + shiftY - overlapMargin).coerceAtLeast(contentTop),
                right = viewportWidth,
                bottom = contentBottom
            )
        } else {
            LiveDifferentialBounds(
                left = 0,
                top = contentTop,
                right = viewportWidth,
                bottom = (contentTop + shiftY + overlapMargin).coerceAtMost(contentBottom)
            )
        }
        if (incoming.area <= 0L) return null

        val expandedIncoming = expandToTrackContext(
            incoming,
            shiftedTracks,
            contentTop,
            contentBottom
        )
        val expandedContinuations = continuationBounds
            .take(MAXIMUM_CONTINUATION_REGIONS)
            .map { bounds ->
                expandToTrackContext(bounds, shiftedTracks, contentTop, contentBottom)
            }
        val source = buildList {
            add(expandedIncoming)
            addAll(expandedContinuations)
            dirtyGrid.bounds
                .filterNot { dirty ->
                    incoming.intersects(dirty) || expandedContinuations.any(dirty::intersects)
                }
                .sortedByDescending(LiveDifferentialBounds::area)
                .take(
                    (MAXIMUM_RECOGNITION_REGIONS - 1 - expandedContinuations.size)
                        .coerceAtLeast(0)
                )
                .forEach { bounds ->
                    add(expandToTrackContext(bounds, shiftedTracks, contentTop, contentBottom))
                }
        }
        val merged = mergeNearby(source, overlapMargin / 2)
            .sortedBy(LiveDifferentialBounds::top)
        return createPlan(
            bounds = merged,
            viewportWidth = viewportWidth,
            contentTop = contentTop,
            contentBottom = contentBottom,
            shiftedTracks = shiftedTracks,
            dirtyGrid = dirtyGrid
        ) ?: createPlan(
            bounds = mergeNearby(listOf(expandedIncoming) + expandedContinuations, overlapMargin / 2),
            viewportWidth = viewportWidth,
            contentTop = contentTop,
            contentBottom = contentBottom,
            shiftedTracks = shiftedTracks,
            dirtyGrid = dirtyGrid
        ) ?: createPlan(
            bounds = mergeNearby(listOf(incoming) + continuationBounds, overlapMargin / 2),
            viewportWidth = viewportWidth,
            contentTop = contentTop,
            contentBottom = contentBottom,
            shiftedTracks = shiftedTracks,
            dirtyGrid = dirtyGrid
        ) ?: createPlan(
            bounds = listOf(incoming),
            viewportWidth = viewportWidth,
            contentTop = contentTop,
            contentBottom = contentBottom,
            shiftedTracks = shiftedTracks,
            dirtyGrid = dirtyGrid
        )
    }

    private fun createPlan(
        bounds: List<LiveDifferentialBounds>,
        viewportWidth: Int,
        contentTop: Int,
        contentBottom: Int,
        shiftedTracks: List<LiveDifferentialBounds>,
        dirtyGrid: LiveDirtyGridResult
    ): LiveDifferentialRegionPlan? {
        if (bounds.isEmpty() || bounds.size > MAXIMUM_RECOGNITION_REGIONS) return null
        val contentArea = viewportWidth.toLong() * (contentBottom - contentTop)
        val areaRatio = bounds.sumOf(LiveDifferentialBounds::area).toFloat() /
            contentArea.coerceAtLeast(1L)
        if (areaRatio > MAXIMUM_RECOGNITION_AREA_RATIO) return null
        val boundaryTracks = shiftedTracks.count { track -> bounds.any(track::intersects) }
        return LiveDifferentialRegionPlan(
            recognitionBounds = bounds,
            dirtyCellCount = dirtyGrid.dirtyCellCount,
            comparedCellCount = dirtyGrid.comparedCellCount,
            boundaryTrackCount = boundaryTracks,
            recognitionAreaRatio = areaRatio
        )
    }

    private fun expandToTrackContext(
        source: LiveDifferentialBounds,
        tracks: List<LiveDifferentialBounds>,
        contentTop: Int,
        contentBottom: Int
    ): LiveDifferentialBounds {
        var expanded = source
        tracks.filter(source::intersects).forEach { expanded = union(expanded, it) }
        val trackHeight = tracks.filter(source::intersects)
            .map(LiveDifferentialBounds::height)
            .sorted()
            .let { heights -> heights.getOrNull(heights.size / 2) ?: MINIMUM_CONTEXT_PX }
        val verticalContext = max(MINIMUM_CONTEXT_PX, trackHeight / 2)
        return LiveDifferentialBounds(
            left = expanded.left,
            top = (expanded.top - verticalContext).coerceAtLeast(contentTop),
            right = expanded.right,
            bottom = (expanded.bottom + verticalContext).coerceAtMost(contentBottom)
        )
    }

    private fun mergeNearby(
        source: List<LiveDifferentialBounds>,
        gap: Int
    ): List<LiveDifferentialBounds> {
        val pending = source.toMutableList()
        val merged = mutableListOf<LiveDifferentialBounds>()
        while (pending.isNotEmpty()) {
            var active = pending.removeAt(pending.lastIndex)
            var changed: Boolean
            do {
                changed = false
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (nearby(active, candidate, gap)) {
                        active = union(active, candidate)
                        iterator.remove()
                        changed = true
                    }
                }
            } while (changed)
            merged += active
        }
        return merged
    }

    private fun nearby(
        first: LiveDifferentialBounds,
        second: LiveDifferentialBounds,
        gap: Int
    ): Boolean = first.left - gap <= second.right && second.left - gap <= first.right &&
        first.top - gap <= second.bottom && second.top - gap <= first.bottom

    private const val CONTENT_TOP_RATIO = 0.08f
    private const val CONTENT_BOTTOM_RATIO = 0.94f
    private const val MINIMUM_CONTEXT_PX = 72
    private const val CONTEXT_HEIGHT_DIVISOR = 18
    private const val MAXIMUM_CONTINUATION_REGIONS = 1
    private const val MAXIMUM_RECOGNITION_REGIONS = 3
    private const val MAXIMUM_RECOGNITION_AREA_RATIO = 0.72f
}

internal data class LiveTrackedRegion(
    val trackId: Long,
    val bounds: LiveDifferentialBounds
)

internal data class LiveTrackMergePlan(
    val candidateTrackIds: List<Long?>,
    val restoredTrackIds: Set<Long>
)

internal object LiveTrackMergePolicy {
    fun plan(
        boundaryTracks: List<LiveTrackedRegion>,
        candidates: List<LiveDifferentialBounds>
    ): LiveTrackMergePlan {
        val claimed = mutableSetOf<Long>()
        val candidateTrackIds = candidates.map { candidate ->
            boundaryTracks
                .asSequence()
                .filterNot { it.trackId in claimed }
                .map { track -> track to overlapRatio(track.bounds, candidate) }
                .filter { (_, overlap) -> overlap >= MINIMUM_TRACK_OVERLAP }
                .maxByOrNull { (_, overlap) -> overlap }
                ?.first
                ?.trackId
                ?.also(claimed::add)
        }
        return LiveTrackMergePlan(
            candidateTrackIds = candidateTrackIds,
            restoredTrackIds = boundaryTracks.mapTo(mutableSetOf()) { it.trackId } - claimed
        )
    }

    fun isDuplicate(first: LiveDifferentialBounds, second: LiveDifferentialBounds): Boolean =
        overlapRatio(first, second) >= MINIMUM_DUPLICATE_OVERLAP

    private fun overlapRatio(first: LiveDifferentialBounds, second: LiveDifferentialBounds): Float {
        if (!first.intersects(second)) return 0f
        val overlapWidth = min(first.right, second.right) - max(first.left, second.left)
        val overlapHeight = min(first.bottom, second.bottom) - max(first.top, second.top)
        val minimumArea = min(first.area, second.area).coerceAtLeast(1L)
        return overlapWidth.toLong() * overlapHeight / minimumArea.toFloat()
    }

    private const val MINIMUM_TRACK_OVERLAP = 0.35f
    private const val MINIMUM_DUPLICATE_OVERLAP = 0.35f
}

private fun union(
    first: LiveDifferentialBounds,
    second: LiveDifferentialBounds
): LiveDifferentialBounds = LiveDifferentialBounds(
    left = min(first.left, second.left),
    top = min(first.top, second.top),
    right = max(first.right, second.right),
    bottom = max(first.bottom, second.bottom)
)
