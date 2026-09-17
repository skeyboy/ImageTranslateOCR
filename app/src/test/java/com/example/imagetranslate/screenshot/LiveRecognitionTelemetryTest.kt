package com.example.imagetranslate.screenshot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecognitionTelemetryTest {
    @Test
    fun `completion includes machine translation stage and response metrics`() {
        val emptyCoverage = LiveCoverageMetrics(0, 0L, 1L, 0f)
        val metrics = LiveRecognitionRunMetrics(
            requestedSegmentation = LiveRecognitionSegmentation.ADAPTIVE,
            appliedStrategy = LiveRecognitionAppliedStrategy.FULL_FRAME,
            recognizedCount = 12,
            translatedRegionCount = 4,
            patchCount = 4,
            failedCount = 1,
            reusedRegionCount = 0,
            sourceCoverage = emptyCoverage,
            patchCoverage = emptyCoverage,
            recognitionAndTranslationMs = 140L,
            renderingMs = 30L,
            ocrMs = 50L,
            translationMs = 80L,
            machineGroupingMs = 10L,
            machineRequestMs = 80L,
            machineRequestWallMs = 84L,
            machineRequestCount = 4,
            machineSuccessCount = 3,
            machineFailureCount = 1
        )

        val payload = JSONObject(
            LiveRecognitionTelemetry.completion(
                generation = 7,
                totalMs = 180L,
                capturePlan = null,
                metrics = metrics
            )
        )

        assertEquals(10L, payload.getLong("machine_grouping_ms"))
        assertEquals(80L, payload.getLong("machine_request_ms"))
        assertEquals(84L, payload.getLong("machine_request_wall_ms"))
        assertEquals(170L, payload.getLong("machine_effective_total_ms"))
        assertEquals(4, payload.getInt("machine_request_count"))
        assertEquals(3, payload.getInt("machine_success_count"))
        assertEquals(1, payload.getInt("machine_failure_count"))
    }
}
