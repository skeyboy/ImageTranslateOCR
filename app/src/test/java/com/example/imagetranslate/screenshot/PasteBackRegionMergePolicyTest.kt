package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteBackRegionMergePolicyTest {
    @Test
    fun `close body regions become one rollback capable render candidate`() {
        val first = region(
            source = "First line continues.\nSecond line remains in the same visual body.",
            translation = "第一段译文。",
            bounds = rect(20, 100, 380, 180),
            lineHeight = 36f
        )
        val second = region(
            source = "The following lines have normal line spacing.\nThey share the same column.",
            translation = "第二段译文。",
            bounds = rect(20, 190, 380, 270),
            lineHeight = 36f
        )

        val candidates = PasteBackRegionMergePolicy.plan(listOf(first, second))
        assertEquals(
            "first=${first.source.text.length}, second=${second.source.text.length}",
            1,
            candidates.size
        )
        val candidate = candidates.single()

        assertEquals(2, candidate.sourceRegionCount)
        assertEquals(2, candidate.fallbackRegions.size)
        assertEquals(first.groupId, candidate.fallbackRegions[0].groupId)
        assertEquals(second.groupId, candidate.fallbackRegions[1].groupId)
        assertEquals(1, candidate.region.renderSlots.size)
        assertTrue(candidate.region.translation.contains("第一段译文。第二段译文。"))
        assertTrue('\n' !in candidate.region.translation)
    }

    @Test
    fun `visible gap and different columns retain original candidates`() {
        val first = region("Long body text in the first region that spans lines.", "译文一", rect(20, 100, 380, 180))
        val far = region("Long body text after a visible paragraph gap.", "译文二", rect(20, 230, 380, 300))
        val side = region("Long body text in another visual column.", "译文三", rect(410, 190, 700, 270))

        assertEquals(2, PasteBackRegionMergePolicy.plan(listOf(first, far)).size)
        assertEquals(2, PasteBackRegionMergePolicy.plan(listOf(first, side)).size)
    }

    @Test
    fun `one glyph height gap can merge but a visible paragraph gap cannot`() {
        val first = region(
            "Established multiline body text that fills the first approved region.",
            "译文一",
            rect(20, 100, 380, 180),
            lineHeight = 36f
        )
        val normalLineGap = region(
            "Continuation text follows at approximately one glyph height.",
            "译文二",
            rect(20, 216, 380, 296),
            lineHeight = 36f
        )
        val paragraphGap = normalLineGap.copy(
            source = normalLineGap.source.copy(bounds = rect(20, 230, 380, 310)),
            renderSlots = listOf(rect(20, 230, 380, 310)),
            sourceCoverSlots = listOf(rect(20, 230, 380, 310))
        )

        assertEquals(1, PasteBackRegionMergePolicy.plan(listOf(first, normalLineGap)).size)
        assertEquals(2, PasteBackRegionMergePolicy.plan(listOf(first, paragraphGap)).size)
    }

    @Test
    fun `declarative provider regions are never rewritten`() {
        val hints = SmartAssistDisplayHints(
            preferredMaxLines = 3,
            minimumTextScale = 0.8f,
            layoutShape = "FLOW_SLOTS",
            role = "BODY"
        )
        val first = region("Long declarative body with multiple lines.", "译文一", rect(20, 100, 380, 180))
            .copy(smartAssistDisplayHints = hints)
        val second = region("Another declarative body continuation.", "译文二", rect(20, 190, 380, 270))
            .copy(smartAssistDisplayHints = hints)

        assertEquals(2, PasteBackRegionMergePolicy.plan(listOf(first, second)).size)
    }

    @Test
    fun `irregular source lines use approved region envelopes for merge safety`() {
        val first = region(
            "First multiline body uses irregular OCR line widths across several visible words.",
            "第一段译文",
            rect(20, 100, 380, 180)
        ).copy(
            sourceCoverSlots = listOf(
                rect(20, 100, 380, 132),
                rect(60, 138, 250, 170)
            )
        )
        val second = region(
            "Second multiline body is vertically close in the same approved column.",
            "第二段译文",
            rect(20, 204, 380, 284)
        ).copy(
            sourceCoverSlots = listOf(
                rect(20, 204, 280, 236),
                rect(20, 242, 380, 274)
            )
        )

        val candidates = PasteBackRegionMergePolicy.plan(listOf(first, second))
        assertEquals(
            "first=${first.source.text.length}, second=${second.source.text.length}",
            1,
            candidates.size
        )
        val candidate = candidates.single()

        assertEquals(2, candidate.sourceRegionCount)
        assertEquals(4, candidate.region.sourceCoverSlots.size)
        assertEquals(1, candidate.region.renderSlots.size)
    }

    @Test
    fun `natural text join collapses OCR line breaks without breaking scripts`() {
        assertEquals(
            "这是连续的中文段落。下一句继续。",
            PasteBackRegionMergePolicy.joinNaturalText(
                listOf("这是连续的\n中文段落。", "下一句\n继续。")
            )
        )
        assertEquals(
            "A wrapped English sentence continues here.",
            PasteBackRegionMergePolicy.joinNaturalText(
                listOf("A wrapped English", "sentence\ncontinues here.")
            )
        )
    }

    private fun region(
        source: String,
        translation: String,
        bounds: Rect,
        lineHeight: Float = 36f
    ) = BackgroundImageRegion(
        source = RecognizedText(
            text = source,
            bounds = bounds,
            componentBounds = listOf(bounds),
            componentTextHeightsPx = listOf(lineHeight),
            estimatedTextHeightPx = lineHeight
        ),
        translation = translation,
        groupId = source.take(8),
        renderSlots = listOf(bounds),
        sourceCoverSlots = listOf(bounds)
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
