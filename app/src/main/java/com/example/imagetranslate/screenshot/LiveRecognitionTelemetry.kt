package com.example.imagetranslate.screenshot

import org.json.JSONObject

internal object LiveRecognitionTelemetry {
    fun completion(
        generation: Int,
        totalMs: Long,
        capturePlan: ScrollCapturePlan?,
        metrics: LiveRecognitionRunMetrics
    ): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("event", "overlay_translation_completed")
        .put("generation", generation)
        .put("requested_segmentation", metrics.requestedSegmentation.name)
        .put("applied_strategy", metrics.appliedStrategy.name)
        .put("total_ms", totalMs)
        .put("ocr_translate_ms", metrics.recognitionAndTranslationMs)
        .put("render_ms", metrics.renderingMs)
        .put("shift_y", capturePlan?.contentShiftY ?: 0)
        .put("registration_confidence", capturePlan?.confidence?.toDouble())
        .put("registration_error", capturePlan?.registrationError?.toDouble())
        .put("registration_consensus", capturePlan?.consensusRatio?.toDouble())
        .put("recognized", metrics.recognizedCount)
        .put("translated_regions", metrics.translatedRegionCount)
        .put("patches", metrics.patchCount)
        .put("failed", metrics.failedCount)
        .put("reused", metrics.reusedRegionCount)
        .put("source_coverage_ratio", metrics.sourceCoverage.coverageRatio.toDouble())
        .put("source_covered_area_px", metrics.sourceCoverage.coveredAreaPx)
        .put("patch_coverage_ratio", metrics.patchCoverage.coverageRatio.toDouble())
        .put("patch_covered_area_px", metrics.patchCoverage.coveredAreaPx)
        .toString()

    fun abReport(report: LiveRecognitionAbReport): String = JSONObject()
        .put("schema", SCHEMA_VERSION)
        .put("event", "live_recognition_ab")
        .put("candidate_ran_first", report.candidateRanFirst)
        .put("candidate_strategy", report.candidate.appliedStrategy.name)
        .put("reference_strategy", report.reference.appliedStrategy.name)
        .put("candidate_total_ms", report.candidate.totalProcessingMs)
        .put("reference_total_ms", report.reference.totalProcessingMs)
        .put("speedup_ratio", report.speedupRatio.toDouble())
        .put("region_recall", report.coverage.regionRecall.toDouble())
        .put("area_recall", report.coverage.areaRecall.toDouble())
        .put("matched_reference_regions", report.coverage.matchedReferenceRegions)
        .put("reference_regions", report.coverage.referenceRegionCount)
        .put("candidate_regions", report.coverage.candidateRegionCount)
        .put("coverage_pass", report.coverage.passesCoverageGate)
        .put("decision", report.decision.name)
        .put("shift_y", report.capturePlan?.contentShiftY ?: 0)
        .put("registration_confidence", report.capturePlan?.confidence?.toDouble())
        .put("registration_error", report.capturePlan?.registrationError?.toDouble())
        .put("registration_consensus", report.capturePlan?.consensusRatio?.toDouble())
        .put("candidate", runMetrics(report.candidate))
        .put("reference", runMetrics(report.reference))
        .toString()

    private fun runMetrics(metrics: LiveRecognitionRunMetrics): JSONObject = JSONObject()
        .put("requested_segmentation", metrics.requestedSegmentation.name)
        .put("applied_strategy", metrics.appliedStrategy.name)
        .put("ocr_translate_ms", metrics.recognitionAndTranslationMs)
        .put("render_ms", metrics.renderingMs)
        .put("recognized", metrics.recognizedCount)
        .put("translated_regions", metrics.translatedRegionCount)
        .put("patches", metrics.patchCount)
        .put("failed", metrics.failedCount)
        .put("reused", metrics.reusedRegionCount)
        .put("source_coverage_ratio", metrics.sourceCoverage.coverageRatio.toDouble())
        .put("patch_coverage_ratio", metrics.patchCoverage.coverageRatio.toDouble())

    private const val SCHEMA_VERSION = 1
}
