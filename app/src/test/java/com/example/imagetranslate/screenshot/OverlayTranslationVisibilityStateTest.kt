package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTranslationVisibilityStateTest {
    @Test
    fun newCaptureGenerationRestoresTranslationVisibility() {
        val state = OverlayTranslationVisibilityState()
        state.update(false)

        assertFalse(state.visible)

        state.resetForCaptureGeneration()

        assertTrue(state.visible)
    }
}
