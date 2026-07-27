package com.example.imagetranslate.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrWordSpacingPolicyTest {
    @Test
    fun insertsSeparatorBetweenLatinWordElementsWithVisibleGap() {
        assertTrue(
            OcrWordSpacingPolicy.shouldInsertSeparator(
                previousText = "Open",
                currentText = "source",
                horizontalGap = 2,
                lineHeight = 64
            )
        )
    }

    @Test
    fun doesNotInsertSeparatorBeforePunctuationOrInsideTouchingText() {
        assertFalse(
            OcrWordSpacingPolicy.shouldInsertSeparator(
                previousText = "source",
                currentText = ".",
                horizontalGap = 3,
                lineHeight = 64
            )
        )
        assertFalse(
            OcrWordSpacingPolicy.shouldInsertSeparator(
                previousText = "Open",
                currentText = "source",
                horizontalGap = 0,
                lineHeight = 64
            )
        )
    }
}
