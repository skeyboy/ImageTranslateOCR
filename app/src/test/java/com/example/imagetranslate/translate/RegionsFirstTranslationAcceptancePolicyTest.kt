package com.example.imagetranslate.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionsFirstTranslationAcceptancePolicyTest {
    @Test
    fun acceptsLongChineseBodyThatPreservesRequiredLatinIdentifiers() {
        val source = source(
            listOf(
                "Hundreds of companies, large and small, use Rust in production",
                "including command line tools, web services, DevOps tooling",
                "cryptocurrencies and parts of the Firefox web browser."
            )
        )
        val result = result(
            source,
            "数百家公司，无论大小，都在生产环境中使用 Rust 来完成各种任务，包括命令行工具、" +
                "网络服务、DevOps 工具链、加密货币，甚至 Firefox 网页浏览器的主要部分。"
        )

        val decision = RegionsFirstTranslationAcceptancePolicy.evaluate(listOf(source), result)

        assertTrue(decision.accepted)
        assertEquals(null, decision.reason)
    }

    @Test
    fun rejectsEnglishSourceReturnedWithoutAnyChineseTranslation() {
        val source = source(listOf("A paragraph that was not translated at all."))
        val result = result(source, "A paragraph that was not translated at all.")

        val decision = RegionsFirstTranslationAcceptancePolicy.evaluate(listOf(source), result)

        assertEquals(false, decision.accepted)
        assertEquals("MISSING_CHINESE_OUTPUT", decision.reason)
    }

    private fun source(lines: List<String>): SemanticTranslationSource {
        val regions = lines.mapIndexed { index, text ->
            SemanticTranslationRegion(
                regionId = "region-$index",
                groupId = "source-group",
                sourceRevision = 1,
                text = text,
                sourceLanguage = "en",
                targetLanguage = "zh",
                readingOrder = index,
                blockId = "block-1",
                lineIndex = index,
                confidence = 0.95f,
                bounds = TranslationBounds(10, 20 + index * 30, 600, 45 + index * 30)
            )
        }
        return SemanticTranslationSource(
            groupId = "source-group",
            role = "BODY",
            translationUnit = "GROUP",
            sourceText = lines.joinToString("\n"),
            memberRegionIds = regions.map(SemanticTranslationRegion::regionId),
            readingOrder = 0,
            groupingConfidence = 1f,
            groupingEvidence = emptyList(),
            bounds = TranslationBounds(10, 20, 600, 45 + (lines.lastIndex * 30)),
            regions = regions,
            sourceLineCount = lines.size
        )
    }

    private fun result(
        source: SemanticTranslationSource,
        translatedText: String
    ) = SemanticGroupTranslationResult(
        groupId = "server-v4-0",
        sourceGroupIds = listOf(source.groupId),
        memberRegionIds = source.memberRegionIds,
        anchorBounds = source.bounds,
        role = "BODY",
        groupingConfidence = 0.98f,
        translatedText = translatedText,
        provider = "self-hosted-qwen-regions-first-v4",
        status = TranslationResultStatus.TRANSLATED,
        detectedSourceLanguage = "en",
        targetLanguage = "zh",
        layoutHint = null
    )
}
