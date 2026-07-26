package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptureSettingsPolicyTest {
    @Test
    fun adaptivePresetPreservesTheValidatedDefaultPath() {
        assertEquals(
            LiveCaptureSettings(
                LiveCaptureScenePreset.ADAPTIVE,
                LiveCaptureFrequency.HIGH,
                LiveFrameBufferMode.SINGLE,
                LiveRecognitionSegmentation.ADAPTIVE
            ),
            LiveCaptureSettingsPolicy.default
        )
    }

    @Test
    fun dynamicPresetFavoursFastDetectionWithoutReusingOldRegions() {
        val settings = LiveCaptureSettingsPolicy.forPreset(LiveCaptureScenePreset.DYNAMIC)

        assertEquals(LiveCaptureFrequency.VERY_HIGH, settings.frequency)
        assertEquals(LiveFrameBufferMode.MULTI, settings.bufferMode)
        assertEquals(LiveRecognitionSegmentation.FULL_FRAME, settings.segmentation)
    }

    @Test
    fun denseTextAndCodeUseHigherResolutionVerticalBands() {
        assertEquals(
            LiveRecognitionSegmentation.VERTICAL_BANDS,
            LiveCaptureSettingsPolicy.forPreset(LiveCaptureScenePreset.DENSE_TEXT).segmentation
        )
        assertEquals(
            LiveRecognitionSegmentation.VERTICAL_BANDS,
            LiveCaptureSettingsPolicy.forPreset(LiveCaptureScenePreset.CODE).segmentation
        )
    }

    @Test
    fun manualChangesBecomeACustomPreset() {
        val settings = LiveCaptureSettingsPolicy.customize(
            LiveCaptureSettingsPolicy.default,
            frequency = LiveCaptureFrequency.LOW
        )

        assertEquals(LiveCaptureScenePreset.CUSTOM, settings.scenePreset)
        assertEquals(LiveCaptureFrequency.LOW, settings.frequency)
    }

    @Test
    fun verticalBandsCoverTheViewportWithAnOverlap() {
        val bands = LiveCaptureSettingsPolicy.verticalBands(1_440, 3_200)

        assertEquals(2, bands.size)
        assertEquals(0, bands.first().top)
        assertEquals(3_200, bands.last().bottom)
        assertTrue(bands.first().bottom > bands.last().top)
    }
}
