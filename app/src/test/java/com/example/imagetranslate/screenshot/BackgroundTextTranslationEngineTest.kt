package com.example.imagetranslate.screenshot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTextTranslationEngineTest {
    @Test
    fun notificationInteractionsCoverEveryRequestedCommand() {
        assertEquals(
            ScreenshotResultInteraction.IGNORE,
            ScreenshotNotificationInteractionPolicy.contentInteraction
        )
        assertEquals(
            ScreenshotResultInteraction.IGNORE,
            ScreenshotNotificationInteractionPolicy.dismissInteraction
        )
        assertEquals(
            ScreenshotResultInteraction.entries.toSet(),
            ScreenshotNotificationInteractionPolicy.allInteractions
        )
        assertEquals(
            listOf(
                ScreenshotResultInteraction.TRANSLATE,
                ScreenshotResultInteraction.TRANSLATE_AND_VIEW,
                ScreenshotResultInteraction.TRANSLATE_AND_SAVE
            ),
            ScreenshotNotificationInteractionPolicy.actionInteractions
        )
    }

    @Test
    fun translatesDistinctTextsAndIsolatesIndividualFailures() = runBlocking {
        val requestedTexts = mutableListOf<String>()

        val result = BackgroundTextTranslationEngine.translate(
            listOf("  你好  ", "你好", "12345", "失败")
        ) { source ->
            requestedTexts.add(source)
            when (source) {
                "你好" -> "Hello"
                "失败" -> error("translation unavailable")
                else -> source
            }
        }

        assertEquals(listOf("你好", "12345", "失败"), requestedTexts)
        assertEquals(3, result.recognizedCount)
        assertEquals(
            listOf(BackgroundTranslationLine("你好", "Hello")),
            result.translatedLines
        )
    }

    @Test
    fun limitsBackgroundTranslationWork() = runBlocking {
        var calls = 0
        val inputs = (1..40).map { "source-$it" }

        val result = BackgroundTextTranslationEngine.translate(inputs) { source ->
            calls++
            "translated-$source"
        }

        assertEquals(40, result.recognizedCount)
        assertEquals(24, calls)
        assertEquals(24, result.translatedLines.size)
    }

    @Test
    fun batchTranslationSendsAllDistinctTextsOnce() = runBlocking {
        var requested = emptyList<String>()

        val result = BackgroundTextTranslationEngine.translateBatch(
            listOf("  你好  ", "你好", "12345", "失败")
        ) { sources ->
            requested = sources
            listOf("Hello", "12345", null)
        }

        assertEquals(listOf("你好", "12345", "失败"), requested)
        assertEquals(3, result.recognizedCount)
        assertEquals(
            listOf(BackgroundTranslationLine("你好", "Hello")),
            result.translatedLines
        )
    }

    @Test
    fun notificationFormatterLimitsSensitiveTextVolume() {
        val lines = (1..10).map { index ->
            BackgroundTranslationLine(
                "source-$index-${"x".repeat(120)}",
                "translation-$index"
            )
        }

        val summary = BackgroundTranslationFormatter.bigText(lines)

        assertTrue(summary.contains("source-1-"))
        assertFalse(summary.contains("source-7"))
        assertTrue(summary.length <= 700)
    }
}
