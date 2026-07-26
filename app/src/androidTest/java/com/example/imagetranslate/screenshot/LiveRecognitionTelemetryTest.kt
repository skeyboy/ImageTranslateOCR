package com.example.imagetranslate.screenshot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecognitionTelemetryTest {
    @Test
    fun completionLogIsStructuredAndIncludesCoverage() {
        val metrics = LiveRecognitionRunMetrics(
            requestedSegmentation = LiveRecognitionSegmentation.ADAPTIVE,
            appliedStrategy = LiveRecognitionAppliedStrategy.DIFFERENTIAL,
            recognizedCount = 18,
            translatedRegionCount = 10,
            patchCount = 8,
            failedCount = 1,
            reusedRegionCount = 4,
            sourceCoverage = LiveCoverageMetrics(10, 1_200L, 10_000L, 0.12f),
            patchCoverage = LiveCoverageMetrics(8, 2_000L, 10_000L, 0.2f),
            recognitionAndTranslationMs = 620L,
            renderingMs = 180L
        )

        val json = JSONObject(
            LiveRecognitionTelemetry.completion(
                generation = 7,
                totalMs = 850L,
                capturePlan = ScrollCapturePlan(-1_100, 0.8f, 0.6f, 18f, 1f),
                metrics = metrics
            )
        )

        assertEquals("overlay_translation_completed", json.getString("event"))
        assertEquals("DIFFERENTIAL", json.getString("applied_strategy"))
        assertEquals(10, json.getInt("translated_regions"))
        assertEquals(4, json.getInt("reused"))
        assertEquals(0.12, json.getDouble("source_coverage_ratio"), 0.0001)
        assertTrue(json.has("registration_confidence"))
    }
}
