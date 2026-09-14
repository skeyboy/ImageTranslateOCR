package com.example.imagetranslate.translate

import com.example.imagetranslate.screenshot.MachineParagraphGroupingPolicy
import com.example.imagetranslate.screenshot.MachineTextLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalTranslationBucketReplayTest {
    private val cases = HistoricalSuccessfulTranslationFixture.cases

    @Test
    fun `successful history fixture covers every text length bucket`() {
        assertEquals(HistoricalTextLengthBucket.entries.toSet(), cases.map { it.bucket }.toSet())
        HistoricalTextLengthBucket.entries.forEach { bucket ->
            assertEquals(3, cases.count { it.bucket == bucket })
        }
        cases.forEach { item ->
            assertEquals(item.bucket, HistoricalTextLengthBucket.classify(item.source))
        }
    }

    @Test
    fun `ai archive replay keeps complete bindings and target script`() {
        assertEquals(cases.size, cases.map { it.requestId to it.source }.distinct().size)
        cases.forEach { item ->
            assertTrue(item.source.isNotBlank())
            assertTrue(item.aiTranslation.isNotBlank())
            assertEquals("en", TranslationScriptLanguagePolicy.sourceLanguage(item.source))
            assertTrue(item.aiTranslation.any(::isHanCharacter))
        }
    }

    @Test
    fun `machine paragraph grouping accepts all bucket fixtures as complete units`() {
        cases.forEachIndexed { caseIndex, item ->
            val lines = item.source.lines().mapIndexed { lineIndex, text ->
                MachineTextLine(
                    text = text,
                    left = 20,
                    top = 100 + lineIndex * 42,
                    right = 1000,
                    bottom = 134 + lineIndex * 42,
                    blockId = "case-$caseIndex",
                    lineIndex = lineIndex
                )
            }

            val groups = MachineParagraphGroupingPolicy.group(lines, 1440, 3200)

            assertEquals(1, groups.size)
            assertEquals(lines.indices.toList(), groups.single())
        }
    }

    @Test
    fun `machine local replay runs all buckets in parallel and returns complete ordered batch`() =
        runBlocking {
            val paragraphs = cases.mapIndexed { index, item ->
                MachineTranslationParagraph(
                    paragraphId = "history-$index",
                    text = item.source,
                    sourceLanguage = item.sourceLanguage,
                    targetLanguage = item.targetLanguage
                )
            }
            val expectedByText = cases.associate { it.source to it.aiTranslation }

            val results = translateMachineParagraphsInParallel(paragraphs, 4) { paragraph ->
                MachineTranslationParagraphResult(
                    paragraph = paragraph,
                    translatedText = expectedByText.getValue(paragraph.text)
                )
            }

            assertEquals(paragraphs.map(MachineTranslationParagraph::paragraphId), results.map {
                it.paragraph.paragraphId
            })
            assertEquals(cases.map(HistoricalTranslationCase::aiTranslation), results.map {
                it.translatedText
            })
            assertTrue(results.all(MachineTranslationParagraphResult::succeeded))
        }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
}
