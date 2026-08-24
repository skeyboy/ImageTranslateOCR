package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOcrContentPolicyTest {
    private val viewport = LiveOcrContentViewport(rect(0, 138, 1440, 3120))

    @Test
    fun keepsVisibleTopAndBottomTextInsteadOfFilteringByCenter() {
        val result = LiveOcrContentPolicy.filter(
            listOf(
                text("status", 20, 40, 300, 100),
                text("visible top continuation", 60, 120, 1300, 180),
                text("middle", 60, 800, 1300, 900),
                text("visible bottom continuation", 60, 3080, 1300, 3150),
                text("navigation", 60, 3140, 1300, 3190)
            ),
            viewport
        )

        assertEquals(3, result.retained.size)
        assertEquals(2, result.edgeFilteredCount)
        assertTrue(result.retained[0].continuationAtTop)
        assertTrue(result.retained[2].continuationAtBottom)
    }

    @Test
    fun excludesTextMostlyCoveredByAnActualOverlay() {
        val result = LiveOcrContentPolicy.filter(
            listOf(text("floating control", 100, 400, 500, 500)),
            viewport.copy(obscuredBounds = listOf(rect(80, 380, 520, 510)))
        )

        assertTrue(result.retained.isEmpty())
        assertEquals(1, result.edgeFilteredCount)
    }

    @Test
    fun requestsOnlyTheMissingEdgeBand() {
        val recognized = listOf(text("top present", 60, 160, 1200, 220))

        val bands = LiveOcrContentPolicy.recoveryBands(recognized, viewport)

        assertEquals(1, bands.size)
        assertFalse(bands.single().second)
        assertEquals(viewport.bounds.bottom, bands.single().first.bottom)
    }

    @Test
    fun browserUrlDoesNotSuppressTopEdgeRecoveryWithoutAccessibilityBounds() {
        val bands = LiveOcrContentPolicy.recoveryBands(
            listOf(text("news.ycombinator.com/newcomments", 280, 150, 920, 240)),
            viewport
        )

        assertTrue(bands.any { (_, isTop) -> isTop })
    }

    private fun text(value: String, left: Int, top: Int, right: Int, bottom: Int) =
        RecognizedText(value, rect(left, top, right, bottom))

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
