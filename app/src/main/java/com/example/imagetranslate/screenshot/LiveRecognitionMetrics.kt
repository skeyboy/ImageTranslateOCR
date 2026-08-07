package com.example.imagetranslate.screenshot

import kotlin.math.max
import kotlin.math.min

internal data class LiveCoverageBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val area: Long
        get() = (right - left).coerceAtLeast(0).toLong() *
            (bottom - top).coerceAtLeast(0).toLong()
}

internal data class LiveCoverageMetrics(
    val regionCount: Int,
    val coveredAreaPx: Long,
    val viewportAreaPx: Long,
    val coverageRatio: Float
)

internal data class LiveCoverageComparison(
    val referenceRegionCount: Int,
    val candidateRegionCount: Int,
    val matchedReferenceRegions: Int,
    val regionRecall: Float,
    val areaRecall: Float,
    val passesCoverageGate: Boolean
)

internal enum class LiveRecognitionAppliedStrategy {
    FULL_FRAME,
    VERTICAL_BANDS,
    DIFFERENTIAL
}

internal data class LiveRecognitionRunMetrics(
    val requestedSegmentation: LiveRecognitionSegmentation,
    val appliedStrategy: LiveRecognitionAppliedStrategy,
    val recognizedCount: Int,
    val translatedRegionCount: Int,
    val patchCount: Int,
    val failedCount: Int,
    val reusedRegionCount: Int,
    val recognitionRegionCount: Int = 0,
    val recognitionAreaRatio: Float = 1f,
    val dirtyCellCount: Int = 0,
    val boundaryTrackCount: Int = 0,
    val restoredBoundaryTrackCount: Int = 0,
    val differentialFallbackReason: String? = null,
    val contextProfile: LiveDifferentialContextProfile = LiveDifferentialContextProfile.ACCURACY,
    val renderingMode: LivePatchRenderingMode = LivePatchRenderingMode.SEQUENTIAL,
    val backgroundMode: LivePatchBackgroundMode = LivePatchBackgroundMode.THEME_SURFACE,
    val backgroundDetailRetentionRatio: Float = 0f,
    val renderedTrackCacheHitCount: Int = 0,
    val renderedTrackCacheMissCount: Int = 0,
    val themeSurfacePatchCount: Int = 0,
    val blurTintPatchCount: Int = 0,
    val sourceCoverage: LiveCoverageMetrics,
    val patchCoverage: LiveCoverageMetrics,
    val recognitionAndTranslationMs: Long,
    val renderingMs: Long,
    val ocrMs: Long = 0L,
    val translationMs: Long = 0L,
    val sourceLatinTokenCount: Int = 0,
    val retainedLatinTokenCount: Int = 0,
    val retainedLatinRatio: Float = 0f,
    val suspiciousJoinCount: Int = 0,
    val largestPatchAreaRatio: Float = 0f
) {
    val totalProcessingMs: Long
        get() = recognitionAndTranslationMs + renderingMs
}

internal enum class LiveRecognitionAbDecision {
    KEEP_CANDIDATE,
    ACCURACY_REGRESSION,
    NO_MEANINGFUL_SPEEDUP
}

internal data class LiveRecognitionAbReport(
    val reference: LiveRecognitionRunMetrics,
    val candidate: LiveRecognitionRunMetrics,
    val coverage: LiveCoverageComparison,
    val patchCoverage: LiveCoverageComparison,
    val speedupRatio: Float,
    val decision: LiveRecognitionAbDecision,
    val capturePlan: ScrollCapturePlan?,
    val candidateRanFirst: Boolean
)

internal object LiveRecognitionMetricsPolicy {
    fun measure(
        bounds: List<LiveCoverageBounds>,
        viewportWidth: Int,
        viewportHeight: Int
    ): LiveCoverageMetrics {
        val viewport = contentViewport(viewportWidth, viewportHeight)
        val area = unionArea(bounds, viewport)
        val viewportArea = viewport.area
        return LiveCoverageMetrics(
            regionCount = bounds.size,
            coveredAreaPx = area,
            viewportAreaPx = viewportArea,
            coverageRatio = if (viewportArea == 0L) 0f else area.toFloat() / viewportArea
        )
    }

    fun compare(
        reference: List<LiveCoverageBounds>,
        candidate: List<LiveCoverageBounds>,
        viewportWidth: Int,
        viewportHeight: Int
    ): LiveCoverageComparison {
        val viewport = contentViewport(viewportWidth, viewportHeight)
        val clippedReference = reference.mapNotNull { intersect(it, viewport) }
        val clippedCandidate = candidate.mapNotNull { intersect(it, viewport) }
        val matched = clippedReference.count { referenceBounds ->
            clippedCandidate.any { candidateBounds ->
                val overlap = intersect(referenceBounds, candidateBounds)?.area ?: 0L
                val smallerArea = min(referenceBounds.area, candidateBounds.area).coerceAtLeast(1L)
                overlap.toFloat() / smallerArea >= MINIMUM_REGION_OVERLAP_RATIO
            }
        }
        val referenceArea = unionArea(clippedReference, viewport)
        val overlapArea = unionArea(
            clippedReference.flatMap { referenceBounds ->
                clippedCandidate.mapNotNull { candidateBounds ->
                    intersect(referenceBounds, candidateBounds)
                }
            },
            viewport
        )
        val regionRecall = if (clippedReference.isEmpty()) {
            if (clippedCandidate.isEmpty()) 1f else 0f
        } else {
            matched.toFloat() / clippedReference.size
        }
        val areaRecall = if (referenceArea == 0L) {
            if (clippedCandidate.isEmpty()) 1f else 0f
        } else {
            overlapArea.toFloat() / referenceArea
        }
        return LiveCoverageComparison(
            referenceRegionCount = clippedReference.size,
            candidateRegionCount = clippedCandidate.size,
            matchedReferenceRegions = matched,
            regionRecall = regionRecall,
            areaRecall = areaRecall,
            passesCoverageGate = regionRecall >= MINIMUM_AB_REGION_RECALL &&
                areaRecall >= MINIMUM_AB_AREA_RECALL
        )
    }

    fun abReport(
        reference: LiveRecognitionRunMetrics,
        candidate: LiveRecognitionRunMetrics,
        coverage: LiveCoverageComparison,
        patchCoverage: LiveCoverageComparison = coverage,
        capturePlan: ScrollCapturePlan?,
        candidateRanFirst: Boolean
    ): LiveRecognitionAbReport {
        val referenceMs = reference.totalProcessingMs.coerceAtLeast(1L)
        val speedupRatio = (referenceMs - candidate.totalProcessingMs).toFloat() / referenceMs
        val decision = when {
            !coverage.passesCoverageGate || !patchCoverage.passesCoverageGate ->
                LiveRecognitionAbDecision.ACCURACY_REGRESSION
            speedupRatio >= MINIMUM_MEANINGFUL_SPEEDUP_RATIO ->
                LiveRecognitionAbDecision.KEEP_CANDIDATE
            else -> LiveRecognitionAbDecision.NO_MEANINGFUL_SPEEDUP
        }
        return LiveRecognitionAbReport(
            reference = reference,
            candidate = candidate,
            coverage = coverage,
            patchCoverage = patchCoverage,
            speedupRatio = speedupRatio,
            decision = decision,
            capturePlan = capturePlan,
            candidateRanFirst = candidateRanFirst
        )
    }

    private fun contentViewport(width: Int, height: Int): LiveCoverageBounds {
        val safeWidth = width.coerceAtLeast(0)
        val safeHeight = height.coerceAtLeast(0)
        return LiveCoverageBounds(
            left = 0,
            top = (safeHeight * CONTENT_TOP_RATIO).toInt(),
            right = safeWidth,
            bottom = (safeHeight * CONTENT_BOTTOM_RATIO).toInt()
        )
    }

    private fun unionArea(
        source: List<LiveCoverageBounds>,
        clip: LiveCoverageBounds
    ): Long {
        val rectangles = source.mapNotNull { intersect(it, clip) }.filter { it.area > 0L }
        if (rectangles.isEmpty()) return 0L
        val xCoordinates = rectangles.flatMap { listOf(it.left, it.right) }.distinct().sorted()
        var area = 0L
        for (index in 0 until xCoordinates.lastIndex) {
            val left = xCoordinates[index]
            val right = xCoordinates[index + 1]
            if (right <= left) continue
            val intervals = rectangles
                .filter { it.left < right && it.right > left }
                .map { it.top to it.bottom }
                .sortedBy { it.first }
            var coveredHeight = 0L
            var activeTop = Int.MIN_VALUE
            var activeBottom = Int.MIN_VALUE
            intervals.forEach { (top, bottom) ->
                if (activeTop == Int.MIN_VALUE) {
                    activeTop = top
                    activeBottom = bottom
                } else if (top <= activeBottom) {
                    activeBottom = max(activeBottom, bottom)
                } else {
                    coveredHeight += (activeBottom - activeTop).toLong()
                    activeTop = top
                    activeBottom = bottom
                }
            }
            if (activeTop != Int.MIN_VALUE) {
                coveredHeight += (activeBottom - activeTop).toLong()
            }
            area += (right - left).toLong() * coveredHeight
        }
        return area
    }

    private fun intersect(
        first: LiveCoverageBounds,
        second: LiveCoverageBounds
    ): LiveCoverageBounds? {
        val intersection = LiveCoverageBounds(
            left = max(first.left, second.left),
            top = max(first.top, second.top),
            right = min(first.right, second.right),
            bottom = min(first.bottom, second.bottom)
        )
        return intersection.takeIf { it.area > 0L }
    }

    private const val CONTENT_TOP_RATIO = 0.08f
    private const val CONTENT_BOTTOM_RATIO = 0.94f
    private const val MINIMUM_REGION_OVERLAP_RATIO = 0.35f
    private const val MINIMUM_AB_REGION_RECALL = 0.9f
    private const val MINIMUM_AB_AREA_RECALL = 0.9f
    private const val MINIMUM_MEANINGFUL_SPEEDUP_RATIO = 0.05f
}
