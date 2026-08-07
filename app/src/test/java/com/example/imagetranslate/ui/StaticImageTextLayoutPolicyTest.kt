package com.example.imagetranslate.ui

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticImageTextLayoutPolicyTest {
    @Test
    fun sizesAParagraphFromItsMedianLineHeightInsteadOfItsUnionHeight() {
        val metrics = StaticImageTextLayoutPolicy.resolve(
            groupBounds = rect(8, 115, 347, 286),
            componentBounds = listOf(
                rect(8, 115, 316, 135),
                rect(8, 149, 347, 167),
                rect(8, 178, 310, 196),
                rect(8, 209, 322, 226),
                rect(8, 239, 338, 254),
                rect(8, 269, 342, 286)
            ),
            fontSizeMultiplier = 1.12f,
            preferredMaxLines = 6,
            minimumTextScale = 0.86f
        )

        assertEquals(18f, metrics.sourceLineHeightPx, 0.01f)
        assertEquals(20.16f, metrics.preferredTextSizePx, 0.01f)
        assertEquals(15.48f, metrics.minimumTextSizePx, 0.01f)
        assertEquals(6, metrics.maximumLines)
        assertTrue(metrics.preferredTextSizePx < 30f)
    }

    @Test
    fun fallsBackToTheSingleRegionHeightWithoutAServiceHint() {
        val metrics = StaticImageTextLayoutPolicy.resolve(
            groupBounds = rect(20, 40, 180, 64),
            componentBounds = emptyList(),
            fontSizeMultiplier = 1.05f,
            preferredMaxLines = null,
            minimumTextScale = null
        )

        assertEquals(24f, metrics.sourceLineHeightPx, 0.01f)
        assertEquals(1, metrics.maximumLines)
        assertEquals(16.32f, metrics.minimumTextSizePx, 0.01f)
    }

    @Test
    fun neverLetsTheServiceReduceASeventeenLineBodyToSixLines() {
        val components = (0 until 17).map { index ->
            rect(130, 690 + index * 44, 890, 726 + index * 44)
        }

        val metrics = StaticImageTextLayoutPolicy.resolve(
            groupBounds = rect(130, 690, 890, 1430),
            componentBounds = components,
            fontSizeMultiplier = 1.05f,
            preferredMaxLines = 6,
            minimumTextScale = 0.6f
        )

        assertEquals(17, metrics.maximumLines)
        assertTrue(metrics.minimumTextSizePx >= metrics.sourceLineHeightPx * 0.68f)
    }

    @Test
    fun serviceLineCountAndScaleCannotForceMinimumAbovePreferredSize() {
        val metrics = StaticImageTextLayoutPolicy.resolve(
            groupBounds = rect(29, 1206, 1402, 2272),
            componentBounds = listOf(
                rect(64, 1206, 1008, 1278),
                rect(29, 1323, 1402, 2272)
            ),
            fontSizeMultiplier = 0.78f,
            preferredMaxLines = 10,
            minimumTextScale = 0.86f,
            sourceLineCount = 10
        )

        assertEquals(10, metrics.maximumLines)
        assertTrue(metrics.minimumTextSizePx <= metrics.preferredTextSizePx)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
