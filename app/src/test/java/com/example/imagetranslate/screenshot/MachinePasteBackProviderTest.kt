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
            RecognizedText("First line", rect(20, 100, 360, 132)),
            RecognizedText("Second line", rect(20, 138, 340, 170))
        )
        val paragraph = MachineParagraph(
            paragraphId = "paragraph-1",
            members = sourceLines,
            sourceText = "First line\nSecond line",
            bounds = rect(20, 100, 360, 170),
            renderSlots = sourceLines.map {
                rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
            },
            sourceCoverSlots = sourceLines.map {
                rect(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom)
            },
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

        assertEquals(1, region.renderSlots.size)
        assertEquals(2, region.sourceCoverSlots.size)
        assertEquals("合并后的段落", region.translation)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
