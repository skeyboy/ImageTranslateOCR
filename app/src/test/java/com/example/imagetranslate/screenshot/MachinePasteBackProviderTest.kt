package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.translate.MachineTranslationParagraph
import com.example.imagetranslate.translate.MachineTranslationParagraphResult
import org.junit.Assert.assertEquals
import org.junit.Test

class MachinePasteBackProviderTest {
    @Test
    fun `merged paragraph renders as one rectangle while covering every source line`() {
        val sourceLines = listOf(
            RecognizedText("First line", Rect(20, 100, 360, 132)),
            RecognizedText("Second line", Rect(20, 138, 340, 170))
        )
        val paragraph = MachineParagraph(
            paragraphId = "paragraph-1",
            members = sourceLines,
            sourceText = "First line\nSecond line",
            bounds = Rect(20, 100, 360, 170),
            renderSlots = sourceLines.map { Rect(it.bounds) },
            sourceCoverSlots = sourceLines.map { Rect(it.bounds) },
            readingOrder = 0
        )
        val request = MachineTranslationParagraph(
            paragraphId = paragraph.paragraphId,
            text = paragraph.sourceText,
            sourceLanguage = "en",
            targetLanguage = "zh"
        )

        val region = MachinePasteBackProvider().createRegions(
            paragraphs = listOf(paragraph),
            results = listOf(MachineTranslationParagraphResult(request, "合并后的段落"))
        ).single()

        assertRectCoordinates(
            listOf(Rect(20, 100, 360, 170)),
            region.renderSlots
        )
        assertRectCoordinates(
            listOf(Rect(20, 100, 360, 132), Rect(20, 138, 340, 170)),
            region.sourceCoverSlots
        )
    }

    private fun assertRectCoordinates(expected: List<Rect>, actual: List<Rect>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedRect, actualRect) ->
            assertEquals(expectedRect.left, actualRect.left)
            assertEquals(expectedRect.top, actualRect.top)
            assertEquals(expectedRect.right, actualRect.right)
            assertEquals(expectedRect.bottom, actualRect.bottom)
        }
    }
}
