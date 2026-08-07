package com.example.imagetranslate.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticFallbackPolicyTest {
    @Test
    fun preventsFragmentFallbackForLongChatBody() {
        val source = source(
            role = "BODY",
            text = "The Web3 industry tried and we did quite well. Until the country decided " +
                "to invest in something that did not fit into the community.",
            regionCount = 6,
            bounds = TranslationBounds(130, 690, 890, 1510)
        )

        assertFalse(SemanticFallbackPolicy.allowsLocalFallback(source, 1080, 2400))
    }

    @Test
    fun stillAllowsShortLabelFallbackAndPreservedMetadata() {
        assertTrue(
            SemanticFallbackPolicy.allowsLocalFallback(
                source("TITLE", "Settings", 1, TranslationBounds(20, 30, 180, 70)),
                1080,
                2400
            )
        )
        assertTrue(
            SemanticFallbackPolicy.allowsLocalFallback(
                source("METADATA", "~ Prof", 1, TranslationBounds(150, 700, 260, 740), true),
                1080,
                2400
            )
        )
    }

    private fun source(
        role: String,
        text: String,
        regionCount: Int,
        bounds: TranslationBounds,
        preserved: Boolean = false
    ): SemanticTranslationSource {
        val regions = (0 until regionCount).map { index ->
            SemanticTranslationRegion(
                regionId = "region-$index",
                groupId = "group",
                sourceRevision = 1,
                text = "line-$index",
                sourceLanguage = "en",
                targetLanguage = "zh",
                readingOrder = index,
                blockId = null,
                lineIndex = index,
                confidence = 1f,
                bounds = bounds
            )
        }
        return SemanticTranslationSource(
            groupId = "group",
            role = role,
            translationUnit = if (preserved) "PRESERVED" else "GROUP",
            sourceText = text,
            memberRegionIds = regions.map(SemanticTranslationRegion::regionId),
            readingOrder = 0,
            groupingConfidence = 1f,
            groupingEvidence = emptyList(),
            bounds = bounds,
            regions = regions
        )
    }
}
