package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MachineOcrTextProcessingProviderTest {
    private val provider = MachineOcrTextProcessingProvider()

    @Test
    fun `nearby aligned rows become one paragraph with original slots`() {
        val rows = listOf(
            RecognizedText("The first line", Rect(20, 100, 360, 132)),
            RecognizedText("continues the paragraph.", Rect(22, 138, 350, 170))
        )

        val paragraphs = provider.processParagraphs(rows, 400, 300)

        assertEquals(1, paragraphs.size)
        assertEquals(2, paragraphs.single().members.size)
        assertEquals(2, paragraphs.single().renderSlots.size)
        assertEquals("The first line\ncontinues the paragraph.", paragraphs.single().sourceText)
    }

    @Test
    fun `far apart rows remain separate paragraphs`() {
        val rows = listOf(
            RecognizedText("First paragraph.", Rect(20, 100, 360, 132)),
            RecognizedText("Second paragraph.", Rect(20, 220, 360, 252))
        )

        val paragraphs = provider.processParagraphs(rows, 400, 300)

        assertEquals(2, paragraphs.size)
        assertNotEquals(paragraphs[0].paragraphId, paragraphs[1].paragraphId)
    }

    @Test
    fun `timestamps are not merged into body text`() {
        val rows = listOf(
            RecognizedText("Message body", Rect(20, 100, 300, 132)),
            RecognizedText("12:30", Rect(20, 138, 100, 170))
        )

        val paragraphs = provider.processParagraphs(rows, 400, 300)

        assertEquals(2, paragraphs.size)
    }
}
