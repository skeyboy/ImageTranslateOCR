package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOverlayLayoutPolicyTest {
    @Test
    fun groupsAlignedBodyLinesIntoOneTranslationBlock() {
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            listOf(
                line(0, 40, 100, 340, 130, "First line of a paragraph"),
                line(1, 42, 138, 338, 168, "continues on the next line"),
                line(2, 41, 176, 250, 206, "and ends here.")
            )
        )

        assertEquals(listOf(listOf(0, 1, 2)), groups)
    }

    @Test
    fun splitsLongParagraphsIntoReadableTranslationBlocks() {
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            (0 until 6).map { index ->
                line(
                    index = index,
                    left = 40,
                    top = 100 + index * 38,
                    right = 340,
                    bottom = 130 + index * 38,
                    text = "Paragraph line $index"
                )
            }
        )

        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4, 5)), groups)
    }

    @Test
    fun keepsAHeadingSeparateFromSmallerBodyCopy() {
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            listOf(
                line(0, 40, 80, 300, 124, "Featured article"),
                line(1, 40, 134, 340, 160, "The first body line"),
                line(2, 40, 168, 330, 194, "continues below")
            )
        )

        assertEquals(listOf(listOf(0), listOf(1, 2)), groups)
    }

    @Test
    fun doesNotCrossColumnsOrMergeNewListItems() {
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            listOf(
                line(0, 20, 100, 180, 126, "Left column"),
                line(1, 220, 102, 380, 128, "Right column"),
                line(2, 20, 134, 180, 160, "Left continuation"),
                line(3, 220, 136, 380, 162, "Right continuation"),
                line(4, 20, 170, 180, 196, "• New list item")
            )
        )

        assertEquals(listOf(listOf(0, 2), listOf(1, 3), listOf(4)), groups)
    }

    @Test
    fun transitivelyMergesTouchingPatchWindows() {
        val groups = LiveOverlayLayoutPolicy.groupIntersectingPatches(
            listOf(
                patch(0, 10, 10, 80, 40),
                patch(1, 78, 12, 140, 42),
                patch(2, 138, 14, 200, 44),
                patch(3, 10, 100, 80, 130)
            ),
            mergeGap = 2
        )

        assertEquals(listOf(listOf(0, 1, 2), listOf(3)), groups)
    }

    @Test
    fun removesOverlappingRecognizerDuplicatesBeforeGrouping() {
        val lines = listOf(
            line(0, 40, 100, 340, 132, "Welcome to Wikipedia", quality = 0.5f),
            line(1, 42, 101, 339, 133, "Welcome to Wikipedla", quality = 0.9f),
            line(2, 40, 142, 320, 174, "A separate line", quality = 0.7f)
        )

        assertEquals(listOf(1, 2), LiveOverlayLayoutPolicy.selectDistinctTextLines(lines))
    }

    @Test
    fun keepsParagraphsSeparateAfterAVisibleSentenceGap() {
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            listOf(
                line(0, 40, 100, 340, 130, "The first paragraph ends."),
                line(1, 40, 145, 340, 175, "The next paragraph starts here")
            )
        )

        assertEquals(listOf(listOf(0), listOf(1)), groups)
    }

    @Test
    fun expandsSingleLineTitlesWithoutChangingTheirTextBounds() {
        val expanded = LiveOverlayLayoutPolicy.translationMaterialBounds(
            textBounds = patch(0, 100, 200, 500, 264),
            sourceText = "Running the book",
            sourceWidth = 1080,
            sourceHeight = 2400
        )

        assertEquals(LivePatchBounds(0, 68, 191, 532, 273), expanded)
    }

    private fun line(
        index: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        text: String,
        quality: Float = 0f
    ) = LiveTextLineBounds(index, left, top, right, bottom, text, quality)

    private fun patch(index: Int, left: Int, top: Int, right: Int, bottom: Int) =
        LivePatchBounds(index, left, top, right, bottom)
}
