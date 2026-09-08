package com.example.imagetranslate.translate

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import com.example.imagetranslate.semantic.SemanticTextGroup
import com.example.imagetranslate.semantic.SemanticTextRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticTranslationMapperTest {
    @Test
    fun expandsCompositeTextIntoLosslessAtomicRegions() {
        val components = listOf(
            rect(20, 100, 300, 130),
            rect(20, 140, 330, 170),
            rect(20, 180, 310, 210),
            rect(20, 220, 260, 250)
        )
        val source = RecognizedText(
            text = "Laos issues warning as\nprolonged rains trigger\n" +
                "flooding, landslides in\nseveral areas",
            bounds = rect(20, 100, 330, 250),
            modelConfidence = 0.9f,
            recognizerScript = RecognizerScript.LATIN,
            sourceBlockId = "title-block",
            sourceLineIndex = 0,
            componentBounds = components,
            componentTextHeightsPx = listOf(30f, 29f, 18f, 17f)
        )
        val mapped = SemanticTextGroup(
            groupId = "title",
            members = listOf(source),
            sourceText = source.text,
            unionBounds = copyRect(source.bounds),
            readingOrder = 2,
            role = SemanticTextRole.TITLE,
            groupingConfidence = 0.95f,
            evidence = emptySet(),
            renderSlots = listOf(copyRect(source.bounds))
        ).toSemanticTranslationSource()

        assertEquals(4, mapped.regions.size)
        assertEquals(4, mapped.sourceLineCount)
        assertEquals(
            components.map { listOf(it.left, it.top, it.right, it.bottom) },
            mapped.regions.map { region ->
                listOf(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom)
            }
        )
        assertEquals(listOf(0, 1, 2, 3), mapped.regions.map { it.lineIndex })
        assertEquals(listOf(30f, 29f, 18f, 17f), mapped.regions.map { it.estimatedTextHeightPx })
        assertEquals(mapped.regions.map { it.regionId }, mapped.memberRegionIds)
    }

    @Test
    fun preservesComponentGeometryWhenTextCannotBeSafelySplit() {
        val source = RecognizedText(
            text = "one line",
            bounds = rect(10, 20, 200, 80),
            componentBounds = listOf(rect(10, 20, 80, 45), rect(90, 50, 200, 80))
        )
        val mapped = SemanticTextGroup(
            groupId = "body",
            members = listOf(source),
            sourceText = source.text,
            unionBounds = copyRect(source.bounds),
            readingOrder = 0,
            role = SemanticTextRole.BODY,
            groupingConfidence = 1f,
            evidence = emptySet(),
            renderSlots = listOf(copyRect(source.bounds))
        ).toSemanticTranslationSource()

        assertEquals(1, mapped.regions.size)
        assertEquals(2, mapped.sourceLineCount)
        assertEquals(2, mapped.regions.single().componentBounds.size)
    }

    @Test
    fun makesMixedTemporalAndNumericTextTranslatable() {
        val mixedDate = group(
            text = "The March ended in 1956 but the consequences remained",
            role = SemanticTextRole.METADATA
        ).toSemanticTranslationSource()
        val numericSentence = group(
            text = "At 17:27 the meeting started",
            role = SemanticTextRole.TIMESTAMP
        ).toSemanticTranslationSource()
        val pureTime = group(
            text = "17:27",
            role = SemanticTextRole.TIMESTAMP
        ).toSemanticTranslationSource()

        assertEquals("GROUP", mixedDate.translationUnit)
        assertEquals("GROUP", numericSentence.translationUnit)
        assertEquals("PRESERVED", pureTime.translationUnit)
    }

    @Test
    fun usesTheDominantNaturalLanguageForMixedScriptText() {
        val englishDominant = group(
            text = "Join our AI workshop，地点在深圳，registration closes Friday.",
            role = SemanticTextRole.BODY
        ).toSemanticTranslationSource()
        val chineseDominant = group(
            text = "这是中文介绍 with a short phrase",
            role = SemanticTextRole.BODY
        ).toSemanticTranslationSource()
        val chineseWithUrl = group(
            text = "点击 https://example.com 查看详情",
            role = SemanticTextRole.BODY
        ).toSemanticTranslationSource()

        assertEquals("en", englishDominant.regions.single().sourceLanguage)
        assertEquals("zh", englishDominant.regions.single().targetLanguage)
        assertEquals("zh", chineseDominant.regions.single().sourceLanguage)
        assertEquals("en", chineseDominant.regions.single().targetLanguage)
        assertEquals("zh", chineseWithUrl.regions.single().sourceLanguage)
        assertEquals("en", chineseWithUrl.regions.single().targetLanguage)
    }

    private fun group(text: String, role: SemanticTextRole): SemanticTextGroup {
        val source = RecognizedText(
            text = text,
            bounds = rect(10, 20, 500, 80),
            recognizerScript = RecognizerScript.LATIN
        )
        return SemanticTextGroup(
            groupId = "test-${text.hashCode()}",
            members = listOf(source),
            sourceText = text,
            unionBounds = copyRect(source.bounds),
            readingOrder = 0,
            role = role,
            groupingConfidence = 1f,
            evidence = emptySet(),
            renderSlots = listOf(copyRect(source.bounds))
        )
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun copyRect(source: Rect) = rect(source.left, source.top, source.right, source.bottom)
}
