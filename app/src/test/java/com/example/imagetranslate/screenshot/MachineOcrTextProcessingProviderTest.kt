package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

        assertEquals(18, groups.size)
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
        bottom: Int
    ) = MachineTextLine(text, left, top, right, bottom)
}
