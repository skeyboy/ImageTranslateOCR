package com.example.imagetranslate.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationExperienceTest {
    @Test
    fun `resolves machine mode regardless of case and whitespace`() {
        assertEquals(
            TranslationExperience.MACHINE,
            resolveTranslationExperience(" machine ")
        )
    }

    @Test
    fun `defaults missing or invalid values to AI mode`() {
        assertEquals(TranslationExperience.AI, resolveTranslationExperience(null))
        assertEquals(TranslationExperience.AI, resolveTranslationExperience("unsupported"))
    }
}
