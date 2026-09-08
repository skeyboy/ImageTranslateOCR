package com.example.imagetranslate.semantic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticContentClassifierTest {
    @Test
    fun preservesOnlyStandaloneTemporalValues() {
        assertTrue(SemanticContentClassifier.shouldPreserve("TIMESTAMP", "22:43"))
        assertTrue(SemanticContentClassifier.shouldPreserve("TIMESTAMP", "2026-08-04"))
        assertFalse(
            SemanticContentClassifier.shouldPreserve(
                "TIMESTAMP",
                "At 17:27 the meeting started"
            )
        )
    }

    @Test
    fun translatesSentencesContainingDatesEvenWhenLegacyRoleIsMetadata() {
        assertFalse(
            SemanticContentClassifier.shouldPreserve(
                "METADATA",
                "The March ended in 1956 but the consequences remained"
            )
        )
        assertFalse(
            SemanticContentClassifier.shouldPreserve(
                "METADATA",
                "City, Vietnam, Aug. 4, 2026. Chinese film Dear You"
            )
        )
        assertTrue(
            SemanticContentClassifier.shouldPreserve(
                "METADATA",
                "Ngotho Gichuru and Byaruhanga Rukooko 06 August 2026"
            )
        )
    }

    @Test
    fun translatesDiscussionMetadataButKeepsItsMetadataRole() {
        val text = "CrzyLngPwd 3 minutes ago | parent | context | " +
            "on: Why aren't smart people happier? (2022)"

        assertTrue(SemanticContentClassifier.isStandaloneMetadata(text))
        assertTrue(SemanticContentClassifier.isDiscussionThreadMetadata(text))
        assertFalse(SemanticContentClassifier.shouldPreserve("METADATA", text))

        val hoursOld = "logicallee 2 hours ago | parent | context | on: Human brains"
        assertTrue(SemanticContentClassifier.isDiscussionThreadMetadata(hoursOld))
        assertFalse(SemanticContentClassifier.shouldPreserve("METADATA", hoursOld))
    }

    @Test
    fun distinguishesNumericIdentifiersFromTranslatableNumericText() {
        assertTrue(SemanticContentClassifier.shouldPreserve("BODY", "M26-061"))
        assertTrue(SemanticContentClassifier.shouldPreserve("BODY", "W3000 t5"))
        assertFalse(SemanticContentClassifier.shouldPreserve("BODY", "Version 3 is ready"))
        assertFalse(SemanticContentClassifier.shouldPreserve("BODY", "Awarded C$20 million"))
        assertFalse(SemanticContentClassifier.shouldPreserve("BODY", "第3章已经发布"))
    }

    @Test
    fun preservesStandaloneUrlsButTranslatesNaturalLanguageContainingUrls() {
        assertTrue(
            SemanticContentClassifier.shouldPreserve(
                "BODY",
                "https://tinyurl.com/7nsf7ycy"
            )
        )
        assertFalse(
            SemanticContentClassifier.shouldPreserve(
                "BODY",
                "Spots are limited, so reserve here: https://tinyurl.com/7nsf7ycy"
            )
        )
        assertFalse(
            SemanticContentClassifier.shouldPreserve(
                "BODY",
                "Questions can be sent to team@example.com before Friday"
            )
        )
    }
}
