package com.example.imagetranslate.translate

import com.example.experimentaltranslation.ExperimentalTranslationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExperimentalTranslationSettingsTest {
    @Test
    fun `experimental translation is disabled by default`() {
        assertFalse(ExperimentalTranslationSettings.DEFAULT_ENABLED)
        assertEquals(
            ExperimentalTranslationEngine.DISABLED,
            ExperimentalTranslationSettings.DEFAULT_ENGINE
        )
    }
}
