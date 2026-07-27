package com.example.smartassist

import com.example.smartassist.quality.QualityBounds
import com.example.smartassist.quality.SemanticGoldenSample
import com.example.smartassist.quality.SemanticQualityEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticQualityEvaluatorTest {
    @Test
    fun evaluatesOcrTranslationAndGeometryAgainstGoldenSamples() {
        val report = SemanticQualityEvaluator.evaluate(
            listOf(
                SemanticGoldenSample(
                    id = "reading-title",
                    expectedOcr = "Welcome to Wikipedia",
                    actualOcr = "Welcome to Wikipedia",
                    acceptedTranslations = listOf("欢迎来到维基百科", "欢迎访问维基百科"),
                    actualTranslation = "欢迎来到维基百科",
                    expectedBounds = QualityBounds(40, 100, 440, 160),
                    actualBounds = QualityBounds(42, 101, 438, 159)
                ),
                SemanticGoldenSample(
                    id = "settings-label",
                    expectedOcr = "Privacy and security",
                    actualOcr = "Privacy & security",
                    acceptedTranslations = listOf("隐私和安全"),
                    actualTranslation = "隐私与安全",
                    expectedBounds = QualityBounds(40, 220, 360, 276),
                    actualBounds = QualityBounds(44, 222, 356, 274)
                )
            )
        )

        assertEquals(2, report.sampleCount)
        assertTrue(report.meanCharacterErrorRate in 0f..0.2f)
        assertTrue(report.meanWordErrorRate in 0f..0.3f)
        assertTrue(report.meanTranslationSimilarity >= 0.8f)
        assertEquals(0.5f, report.exactTranslationRate, 0.001f)
        assertTrue(checkNotNull(report.meanBoundsIntersectionOverUnion) >= 0.85f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateGoldenIds() {
        val sample = SemanticGoldenSample("duplicate", "a", "a", listOf("甲"), "甲")
        SemanticQualityEvaluator.evaluate(listOf(sample, sample))
    }
}
