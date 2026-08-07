package com.example.imagetranslate.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTranslationTextNormalizerTest {
    @Test
    fun recoversAuditableCurrencySymbolsBeforeSendingToTheService() {
        val result = OcrTranslationTextNormalizer.normalize(
            "has awarded CS20 million (USS19.4 million)"
        )

        assertEquals("has awarded C$20 million (US$19.4 million)", result.text)
        assertEquals(2, result.corrections.size)
        assertEquals(
            setOf("CS20", "USS19.4"),
            result.corrections.map(OcrTextCorrection::original).toSet()
        )
    }

    @Test
    fun leavesUnrelatedIdentifiersUntouched() {
        val result = OcrTranslationTextNormalizer.normalize("CS20 module USS19 release")

        assertEquals("CS20 module USS19 release", result.text)
        assertEquals(emptyList<OcrTextCorrection>(), result.corrections)
    }
}
