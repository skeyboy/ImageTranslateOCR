package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.semantic.SemanticTextGroup
import com.example.imagetranslate.semantic.SemanticTextRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranslationGroupSelectionPolicyTest {
    @Test
    fun laterVisibleParagraphsDisplaceEarlyLowValueMicroText() {
        val microText = (0 until 32).map { index ->
            group(
                id = "micro-$index",
                text = "label $index",
                readingOrder = index,
                bounds = rect(180, 500 + index * 8, 330, 520 + index * 8),
                confidence = 0.62f
            )
        }
        val paragraphs = (0 until 6).map { index ->
            group(
                id = "paragraph-$index",
                text = "This is a complete visible paragraph number $index with useful content.",
                readingOrder = 100 + index,
                bounds = rect(180, 1_400 + index * 180, 1_260, 1_540 + index * 180),
                confidence = 0.9f
            )
        }

        val result = LiveTranslationGroupSelectionPolicy.select(
            groups = microText + paragraphs,
            maximumGroups = 32,
            viewportWidth = 1_440,
            viewportHeight = 3_200
        )

        assertEquals(32, result.selected.size)
        assertTrue(paragraphs.all { paragraph -> paragraph in result.selected })
        assertEquals(result.selected.sortedBy(SemanticTextGroup::readingOrder), result.selected)
        assertTrue(result.dropped.all { it.groupId.startsWith("micro-") })
    }

    @Test
    fun selectionDoesNotChangeSmallInputs() {
        val groups = listOf(
            group("first", "First paragraph.", 0, rect(20, 100, 900, 180), 0.9f),
            group("second", "Second paragraph.", 1, rect(20, 220, 900, 300), 0.9f)
        )

        val result = LiveTranslationGroupSelectionPolicy.select(groups, 32, 1_080, 1_920)

        assertEquals(groups, result.selected)
        assertTrue(result.dropped.isEmpty())
    }

    @Test
    fun preservedStructuralGroupsDoNotConsumeTranslationBudget() {
        val control = group(
            "control",
            "https://example.com",
            0,
            rect(20, 20, 600, 80),
            0.9f,
            SemanticTextRole.IDENTIFIER
        )
        val paragraphs = (0 until 3).map { index ->
            group(
                "paragraph-$index",
                "Visible paragraph $index.",
                index + 1,
                rect(20, 120 + index * 100, 900, 200 + index * 100),
                0.9f
            )
        }

        val result = LiveTranslationGroupSelectionPolicy.select(
            listOf(control) + paragraphs,
            maximumGroups = 2,
            viewportWidth = 1_080,
            viewportHeight = 1_920
        )

        assertTrue(control in result.selected)
        assertEquals(3, result.selected.size)
        assertEquals(1, result.dropped.size)
    }

    private fun group(
        id: String,
        text: String,
        readingOrder: Int,
        bounds: Rect,
        confidence: Float,
        role: SemanticTextRole = SemanticTextRole.BODY
    ): SemanticTextGroup {
        val source = RecognizedText(
            text = text,
            bounds = copyRect(bounds),
            modelConfidence = confidence,
            componentBounds = listOf(copyRect(bounds))
        )
        return SemanticTextGroup(
            groupId = id,
            members = listOf(source),
            sourceText = text,
            unionBounds = copyRect(bounds),
            readingOrder = readingOrder,
            role = role,
            groupingConfidence = confidence,
            evidence = emptySet(),
            renderSlots = listOf(copyRect(bounds))
        )
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun copyRect(source: Rect) =
        rect(source.left, source.top, source.right, source.bottom)
}
