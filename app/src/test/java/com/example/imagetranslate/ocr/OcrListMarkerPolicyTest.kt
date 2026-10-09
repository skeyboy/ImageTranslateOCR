package com.example.imagetranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrListMarkerPolicyTest {
    @Test
    fun restoresLeadingBulletsRemovedByElementFiltering() {
        assertEquals("• First item", OcrListMarkerPolicy.restore("• First item", "First item"))
        assertEquals("· 第二项", OcrListMarkerPolicy.restore("·第二项", "第二项"))
    }

    @Test
    fun restoresNumberedMarkersWithoutTreatingPlainHyphensAsMarkers() {
        assertEquals("2. Second item", OcrListMarkerPolicy.restore("2. Second item", "Second item"))
        assertEquals("state-of-the-art", OcrListMarkerPolicy.restore(
            "state-of-the-art",
            "state-of-the-art"
        ))
    }

    @Test
    fun doesNotDuplicateMarkersAlreadyPresentInRefinedText() {
        assertEquals("• First item", OcrListMarkerPolicy.restore("• First item", "• First item"))
    }

    @Test
    fun recognizesStandaloneMarkerElements() {
        assertEquals("•", OcrListMarkerPolicy.markerToken("•"))
        assertEquals("3.", OcrListMarkerPolicy.markerToken("3."))
        assertEquals(null, OcrListMarkerPolicy.markerToken("ordinary"))
    }
}
