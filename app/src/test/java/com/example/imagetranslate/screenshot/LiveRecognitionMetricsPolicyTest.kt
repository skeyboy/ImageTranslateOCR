package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRecognitionMetricsPolicyTest {
    @Test
    fun coverageUsesTheUnionOfOverlappingBounds() {
        val metrics = LiveRecognitionMetricsPolicy.measure(
            bounds = listOf(
                LiveCoverageBounds(0, 8, 50, 58),
                LiveCoverageBounds(25, 33, 75, 83)
            ),
            viewportWidth = 100,
            viewportHeight = 100
        )

        assertEquals(4_375L, metrics.coveredAreaPx)
        assertEquals(8_600L, metrics.viewportAreaPx)
        assertEquals(4_375f / 8_600f, metrics.coverageRatio, 0.0001f)
    }

    @Test
    fun comparisonAcceptsSmallPositionDifferencesForTheSameTextRegions() {
        val reference = listOf(
            LiveCoverageBounds(10, 20, 90, 50),
            LiveCoverageBounds(10, 60, 90, 90)
        )
        val candidate = listOf(
            LiveCoverageBounds(8, 18, 92, 52),
            LiveCoverageBounds(12, 62, 88, 92)
        )

        val comparison = LiveRecognitionMetricsPolicy.compare(reference, candidate, 100, 100)

        assertEquals(1f, comparison.regionRecall, 0.0001f)
        assertTrue(comparison.areaRecall >= 0.8f)
        assertTrue(comparison.passesCoverageGate)
    }

    @Test
    fun comparisonRejectsAResultThatMissesHalfTheReferenceRegions() {
        val reference = listOf(
            LiveCoverageBounds(10, 20, 90, 50),
            LiveCoverageBounds(10, 60, 90, 90)
        )
        val candidate = listOf(LiveCoverageBounds(10, 20, 90, 50))

        val comparison = LiveRecognitionMetricsPolicy.compare(reference, candidate, 100, 100)

        assertEquals(0.5f, comparison.regionRecall, 0.0001f)
        assertFalse(comparison.passesCoverageGate)
    }

    @Test
    fun abDecisionRejectsAreaRecallBelowTheProductAccuracyTarget() {
        val comparison = LiveCoverageComparison(
            referenceRegionCount = 10,
            candidateRegionCount = 10,
            matchedReferenceRegions = 10,
            regionRecall = 1f,
            areaRecall = 0.89f,
            passesCoverageGate = false
        )

        val report = LiveRecognitionMetricsPolicy.abReport(
            reference = runMetrics(100L),
            candidate = runMetrics(40L),
            coverage = comparison,
            capturePlan = null,
            candidateRanFirst = true
        )

        assertEquals(LiveRecognitionAbDecision.ACCURACY_REGRESSION, report.decision)
    }

    @Test
    fun abDecisionKeepsOnlyAFasterCandidateThatPassesCoverage() {
        val coverage = LiveCoverageComparison(10, 10, 10, 1f, 1f, true)
        val report = LiveRecognitionMetricsPolicy.abReport(
            reference = runMetrics(100L),
            candidate = runMetrics(80L),
            coverage = coverage,
            capturePlan = null,
            candidateRanFirst = true
        )

        assertEquals(LiveRecognitionAbDecision.KEEP_CANDIDATE, report.decision)
        assertEquals(0.2f, report.speedupRatio, 0.0001f)
    }

    @Test
    fun abDecisionPrioritizesCoverageOverSpeed() {
        val coverage = LiveCoverageComparison(10, 6, 6, 0.6f, 0.55f, false)
        val report = LiveRecognitionMetricsPolicy.abReport(
            reference = runMetrics(100L),
            candidate = runMetrics(40L),
            coverage = coverage,
            capturePlan = null,
            candidateRanFirst = false
        )

        assertEquals(LiveRecognitionAbDecision.ACCURACY_REGRESSION, report.decision)
    }

    @Test
    fun abDecisionRejectsMissingRenderedPatchesEvenWhenOcrCoveragePasses() {
        val sourceCoverage = LiveCoverageComparison(10, 10, 10, 1f, 1f, true)
        val patchCoverage = LiveCoverageComparison(10, 7, 7, 0.7f, 0.6f, false)

        val report = LiveRecognitionMetricsPolicy.abReport(
            reference = runMetrics(100L),
            candidate = runMetrics(40L),
            coverage = sourceCoverage,
            patchCoverage = patchCoverage,
            capturePlan = null,
            candidateRanFirst = true
        )

        assertEquals(LiveRecognitionAbDecision.ACCURACY_REGRESSION, report.decision)
    }

    private fun runMetrics(totalMs: Long): LiveRecognitionRunMetrics =
        LiveRecognitionRunMetrics(
            requestedSegmentation = LiveRecognitionSegmentation.FULL_FRAME,
            appliedStrategy = LiveRecognitionAppliedStrategy.FULL_FRAME,
            recognizedCount = 10,
            translatedRegionCount = 10,
            patchCount = 10,
            failedCount = 0,
            reusedRegionCount = 0,
            sourceCoverage = LiveCoverageMetrics(10, 1_000L, 10_000L, 0.1f),
            patchCoverage = LiveCoverageMetrics(10, 2_000L, 10_000L, 0.2f),
            recognitionAndTranslationMs = totalMs,
            renderingMs = 0L
        )
}
