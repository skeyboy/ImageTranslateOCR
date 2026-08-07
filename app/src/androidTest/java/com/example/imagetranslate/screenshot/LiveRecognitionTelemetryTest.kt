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
    fun movementAndPresentationEventsExposeAtomicTiming() {
        val hidden = JSONObject(
            LiveRecognitionTelemetry.hiddenForMovement(
                generation = 8,
                motionToHiddenMs = 24,
                translationLayerCleared = true
            )
        )
        assertEquals("overlay_translation_hidden_for_movement", hidden.getString("event"))
        assertEquals(8, hidden.getInt("generation"))
        assertEquals(24, hidden.getLong("motion_to_hidden_ms"))
        assertTrue(hidden.getBoolean("translation_layer_cleared"))

        val presented = JSONObject(
            LiveRecognitionTelemetry.presented(
                generation = 9,
                patchCount = 12,
                presentationMs = 17
            )
        )
        assertEquals("overlay_translation_presented", presented.getString("event"))
        assertEquals(9, presented.getInt("generation"))
        assertEquals(12, presented.getInt("patches"))
        assertEquals(17, presented.getLong("presentation_ms"))
        assertTrue(presented.getBoolean("atomic_group"))

        val visibility = JSONObject(
            LiveRecognitionTelemetry.translationVisibilityChanged(visible = false)
        )
        assertEquals("overlay_translation_visibility_changed", visibility.getString("event"))
        assertEquals(false, visibility.getBoolean("visible"))

        val dropped = JSONObject(
            LiveRecognitionTelemetry.stalePresentationDropped(
                resultGeneration = 8,
                currentGeneration = 9
            )
        )
        assertEquals(
            "overlay_translation_stale_presentation_dropped",
            dropped.getString("event")
        )
        assertEquals(8, dropped.getInt("result_generation"))
        assertEquals(9, dropped.getInt("current_generation"))
    }

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
            recognitionRegionCount = 2,
            recognitionAreaRatio = 0.42f,
            dirtyCellCount = 3,
            boundaryTrackCount = 5,
            restoredBoundaryTrackCount = 1,
            differentialFallbackReason = "INSUFFICIENT_TRACK_REUSE",
            contextProfile = LiveDifferentialContextProfile.ACCURACY,
            renderingMode = LivePatchRenderingMode.PARALLEL,
            backgroundMode = LivePatchBackgroundMode.BLUR_TINT,
            backgroundDetailRetentionRatio = 0.03f,
            renderedTrackCacheHitCount = 4,
            renderedTrackCacheMissCount = 3,
            themeSurfacePatchCount = 5,
            blurTintPatchCount = 2,
            sourceLatinTokenCount = 20,
            retainedLatinTokenCount = 2,
            retainedLatinRatio = 0.1f,
            suspiciousJoinCount = 1,
            largestPatchAreaRatio = 0.08f,
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
                metrics = metrics,
                interaction = LiveInteractionTimingMetrics(2_100L, 1_450L, 820L)
            )
        )

        assertEquals("overlay_translation_completed", json.getString("event"))
        assertEquals("DIFFERENTIAL", json.getString("applied_strategy"))
        assertEquals(10, json.getInt("translated_regions"))
        assertEquals(4, json.getInt("reused"))
        assertEquals(2, json.getInt("recognition_regions"))
        assertEquals(0.42, json.getDouble("recognition_area_ratio"), 0.0001)
        assertEquals(3, json.getInt("dirty_cells"))
        assertEquals(5, json.getInt("boundary_tracks"))
        assertEquals(1, json.getInt("restored_boundary_tracks"))
        assertEquals(
            "INSUFFICIENT_TRACK_REUSE",
            json.getString("differential_fallback_reason")
        )
        assertEquals(0.12, json.getDouble("source_coverage_ratio"), 0.0001)
        assertEquals("ACCURACY", json.getString("context_profile"))
        assertEquals("PARALLEL", json.getString("rendering_mode"))
        assertEquals("BLUR_TINT", json.getString("background_mode"))
        assertEquals(0.03, json.getDouble("background_detail_retention_ratio"), 0.0001)
        assertEquals(4, json.getInt("rendered_track_cache_hits"))
        assertEquals(3, json.getInt("rendered_track_cache_misses"))
        assertEquals(5, json.getInt("theme_surface_patches"))
        assertEquals(2, json.getInt("blur_tint_patches"))
        assertEquals(0.1, json.getDouble("retained_latin_ratio"), 0.0001)
        assertEquals(1, json.getInt("suspicious_joins"))
        assertEquals(0.08, json.getDouble("largest_patch_area_ratio"), 0.0001)
        assertEquals(1_450L, json.getLong("last_motion_to_commit_ms"))
        assertEquals(820L, json.getLong("capture_to_commit_ms"))
        assertTrue(json.has("registration_confidence"))
    }
}
