package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Test

class DifferentialRegionGeometryTest {
    @Test
    fun matchedTrackShiftsEverySourceAndDeclarativeGeometry() {
        val original = BackgroundImageRegion(
            source = RecognizedText(
                text = "A translated paragraph",
                bounds = Rect(100, 500, 900, 760),
                componentBounds = listOf(
                    Rect(110, 510, 860, 560),
                    Rect(110, 590, 820, 640)
                )
            ),
            translation = "一段译文",
            groupId = "group-1",
            trackId = 7L,
            renderSlots = listOf(Rect(100, 500, 900, 760)),
            sourceCoverSlots = listOf(
                Rect(110, 510, 860, 560),
                Rect(110, 590, 820, 640)
            )
        )

        val shifted = original.shiftedToMatchedBounds(Rect(112, 260, 912, 520))

        assertRect(Rect(112, 260, 912, 520), shifted.source.bounds)
        assertRects(
            listOf(Rect(122, 270, 872, 320), Rect(122, 350, 832, 400)),
            shifted.source.componentBounds
        )
        assertRects(listOf(Rect(112, 260, 912, 520)), shifted.renderSlots)
        assertRects(
            listOf(Rect(122, 270, 872, 320), Rect(122, 350, 832, 400)),
            shifted.sourceCoverSlots
        )

        assertRect(Rect(100, 500, 900, 760), original.source.bounds)
        assertRects(listOf(Rect(100, 500, 900, 760)), original.renderSlots)
    }

    private fun assertRects(expected: List<Rect>, actual: List<Rect>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedRect, actualRect) ->
            assertRect(expectedRect, actualRect)
        }
    }

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right)
        assertEquals(expected.bottom, actual.bottom)
    }
}
