package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineOcrTextProcessingProviderTest {
    @Test
    fun `nearby aligned rows become one paragraph`() {
        val rows = listOf(
            line("The first line", 20, 100, 360, 132),
            line("continues the paragraph.", 22, 138, 350, 170)
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
        assertEquals(
            listOf("The first line", "continues the paragraph."),
            groups.single().map { rows[it].text }
        )
    }

    @Test
    fun `far apart rows remain separate paragraphs`() {
        val rows = listOf(
            line("First paragraph.", 20, 100, 360, 132),
            line("Second paragraph.", 20, 220, 360, 252)
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(2, groups.size)
        assertNotEquals(groups[0], groups[1])
    }

    @Test
    fun `timestamps are not merged into body text`() {
        val rows = listOf(
            line("Message body", 20, 100, 300, 132),
            line("12:30", 20, 138, 100, 170)
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(2, groups.size)
    }

    @Test
    fun `tightly stacked multiline blocks merge as one visual paragraph`() {
        val rows = listOf(
            line(
                "First established body line.\nSecond body line.\nThird body line.",
                20, 100, 380, 196, blockId = "block-a"
            ),
            line(
                "The continuation starts without extra paragraph spacing.\nAnd keeps flowing.",
                21, 202, 378, 266, blockId = "block-b"
            )
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 400)

        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1), groups.single())
    }

    @Test
    fun `visible paragraph spacing keeps multiline blocks separate`() {
        val rows = listOf(
            line(
                "First established body line.\nSecond body line.\nThird body line.",
                20, 100, 380, 196, blockId = "block-a"
            ),
            line(
                "A visually separate paragraph starts here.\nIt has its own spacing.",
                20, 230, 380, 294, blockId = "block-b"
            )
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 400)

        assertEquals(2, groups.size)
    }

    @Test
    fun `short completed neighboring blocks remain independent`() {
        val rows = listOf(
            line("Independent status.", 20, 100, 360, 132, blockId = "block-a"),
            line("Another status.", 20, 138, 360, 170, blockId = "block-b")
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(2, groups.size)
    }

    @Test
    fun `unfinished wrapped text can continue across an OCR block boundary`() {
        val rows = listOf(
            line(
                "A sentence that clearly continues onto",
                20, 100, 380, 132, blockId = "block-a"
            ),
            line("the next tightly aligned line.", 20, 138, 370, 170, blockId = "block-b")
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(1, groups.size)
    }

    @Test
    fun `slightly overlapping OCR blocks remain one continuous paragraph`() {
        val rows = listOf(
            line(
                "First body line.\nSecond body line.\nThird body line.",
                20, 100, 380, 196, blockId = "block-a"
            ),
            line(
                "The OCR boundary overlaps even though the text continues.",
                20, 180, 380, 212, blockId = "block-b"
            )
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(1, groups.size)
    }

    @Test
    fun `moderate OCR text height drift does not split continuous blocks`() {
        val rows = listOf(
            MachineTextLine(
                text = "First body line.\nSecond body line.\nThird body line.",
                left = 20,
                top = 100,
                right = 380,
                bottom = 196,
                blockId = "block-a",
                estimatedTextHeightPx = 32f
            ),
            MachineTextLine(
                text = "The same visual paragraph continues here.",
                left = 20,
                top = 202,
                right = 380,
                bottom = 224,
                blockId = "block-b",
                estimatedTextHeightPx = 20f
            )
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(1, groups.size)
    }

    @Test
    fun `a structural value between bodies prevents cross container merging`() {
        val rows = listOf(
            line(
                "First established body line.\nSecond body line.\nThird body line.",
                20, 100, 380, 196, blockId = "block-a"
            ),
            line("12:30", 20, 202, 90, 234, blockId = "metadata"),
            line(
                "A different body begins after the boundary.",
                20, 240, 380, 272, blockId = "block-b"
            )
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 400)

        assertEquals(3, groups.size)
    }

    @Test
    fun `wrapped url continuation is grouped and reconstructed without translation whitespace`() {
        val recognized = listOf(
            recognized(
                "https://ourworldindata.org/grapher/augmented-human-",
                20, 100, 380, 132, "url-a"
            ),
            recognized("development", 20, 135, 145, 167, "url-b")
        )

        val paragraphs = MachineOcrTextProcessingProvider().processParagraphs(
            recognized,
            viewportWidth = 400,
            viewportHeight = 300
        )

        assertEquals(1, paragraphs.size)
        assertEquals(
            "https://ourworldindata.org/grapher/augmented-human-development",
            paragraphs.single().sourceText
        )
        assertEquals(2, paragraphs.single().sourceCoverSlots.size)
    }

    @Test
    fun `wrapped numeric url suffix is preserved but a separated word is not attached`() {
        val rows = listOf(
            line(
                "https://x.com/Osinttechnical/status/2108023322156098",
                20, 100, 380, 132, blockId = "url-a"
            ),
            line("034", 20, 135, 70, 167, blockId = "url-b"),
            line("development", 20, 230, 180, 262, blockId = "body")
        )

        val groups = MachineParagraphGroupingPolicy.group(rows, 400, 300)

        assertEquals(listOf(listOf(0, 1), listOf(2)), groups)
    }

    @Test
    fun `repeated external bullets create independent list item paragraphs`() {
        val recognized = listOf(
            recognized("•", 20, 100, 32, 132, "marker-1"),
            recognized("First item begins here", 52, 96, 360, 128, "list"),
            recognized("and continues on the next line.", 52, 137, 350, 169, "list"),
            recognized("•", 20, 180, 32, 212, "marker-2"),
            recognized("Second item stays independent.", 52, 180, 365, 212, "list")
        )

        val paragraphs = MachineOcrTextProcessingProvider().processParagraphs(
            recognized,
            viewportWidth = 400,
            viewportHeight = 400
        )
        val detected = MachineListStructurePolicy.detect(recognized, 400)

        assertEquals(
            "detected=${detected.map { it.memberIndices }}; " +
                paragraphs.joinToString(" || ") { "${it.kind}:${it.sourceText}" },
            2,
            paragraphs.size
        )
        assertTrue(paragraphs.all { it.kind == MachineParagraphKind.LIST_ITEM })
        assertTrue(paragraphs.all(MachineParagraph::listMarkerIsExternal))
        assertEquals("•", paragraphs.first().listMarker)
        assertEquals(
            "First item begins here\nand continues on the next line.",
            paragraphs.first().sourceText
        )
        assertFalse(paragraphs.any { "•" in it.sourceText })
    }

    @Test
    fun `repeated inline bullets retain markers outside translation text`() {
        val recognized = listOf(
            recognized("• First item", 20, 100, 360, 132, "list"),
            recognized("continues here.", 48, 137, 350, 169, "list"),
            recognized("• Second item", 20, 180, 360, 212, "list")
        )

        val paragraphs = MachineOcrTextProcessingProvider().processParagraphs(
            recognized,
            viewportWidth = 400,
            viewportHeight = 400
        )
        val detected = MachineListStructurePolicy.detect(recognized, 400)

        assertEquals(
            "detected=${detected.map { it.memberIndices }}; " +
                paragraphs.joinToString(" || ") { "${it.kind}:${it.sourceText}" },
            2,
            paragraphs.size
        )
        assertTrue(paragraphs.all { it.kind == MachineParagraphKind.LIST_ITEM })
        assertTrue(paragraphs.none(MachineParagraph::listMarkerIsExternal))
        assertEquals("First item\ncontinues here.", paragraphs.first().sourceText)
        assertEquals("• 译文", paragraphs.first().displayTranslation("译文"))
    }

    @Test
    fun `a single dash paragraph uses the existing body fallback`() {
        val paragraphs = MachineOcrTextProcessingProvider().processParagraphs(
            listOf(recognized("- One isolated thought", 20, 100, 360, 132, "body")),
            viewportWidth = 400,
            viewportHeight = 300
        )

        assertEquals(1, paragraphs.size)
        assertEquals(MachineParagraphKind.BODY, paragraphs.single().kind)
        assertEquals("- One isolated thought", paragraphs.single().sourceText)
    }

    @Test
    fun `distant dash labels from different blocks are not treated as one list`() {
        val paragraphs = MachineOcrTextProcessingProvider().processParagraphs(
            listOf(
                recognized("- Alice", 20, 100, 180, 132, "sender-1"),
                recognized("- Bob", 20, 500, 180, 532, "sender-2")
            ),
            viewportWidth = 400,
            viewportHeight = 800
        )

        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs.all { it.kind == MachineParagraphKind.BODY })
    }

    @Test
    fun `archived whatsapp rows preserve OCR block paragraph boundaries`() {
        val rows = ArchivedWhatsAppMachineFixture.rows
        val groups = MachineParagraphGroupingPolicy.group(
            rows,
            ArchivedWhatsAppMachineFixture.VIEWPORT_WIDTH,
            ArchivedWhatsAppMachineFixture.VIEWPORT_HEIGHT
        )
        val paragraphTexts = groups.map { group ->
            group.joinToString("\n") { rows[it].text }
        }

        assertEquals(paragraphTexts.joinToString(" || "), 18, groups.size)
        assertEquals(listOf(2, 2, 2, 3, 6, 3), groups.map { it.size }.filter { it > 1 })
        assertEquals("Vero Tonti", paragraphTexts[2])
        assertEquals(
            "Alla prossima lezione@Mattia potresti\ninventarti la condotta al passeggino",
            paragraphTexts[3]
        )
        assertEquals("13:10", paragraphTexts[5])
        assertEquals(6, groups[paragraphTexts.indexOfFirst { it.startsWith("Buondi") }].size)
    }

    @Test
    fun `archived whatsapp timestamps and input control remain standalone`() {
        val rows = ArchivedWhatsAppMachineFixture.rows
        val groups = MachineParagraphGroupingPolicy.group(
            rows,
            ArchivedWhatsAppMachineFixture.VIEWPORT_WIDTH,
            ArchivedWhatsAppMachineFixture.VIEWPORT_HEIGHT
        )
        val paragraphTexts = groups.map { group ->
            group.joinToString("\n") { rows[it].text }
        }

        assertEquals(4, paragraphTexts.count { it.matches(Regex("13:(10|26|30)")) })
        assertEquals(1, groups[paragraphTexts.indexOf("Messaggio")].size)
    }

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockId: String? = null
    ) = MachineTextLine(text, left, top, right, bottom, blockId = blockId)

    private fun recognized(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockId: String
    ) = RecognizedText(
        text = text,
        bounds = rect(left, top, right, bottom),
        sourceBlockId = blockId,
        componentBounds = listOf(rect(left, top, right, bottom)),
        estimatedTextHeightPx = (bottom - top).toFloat()
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
