package com.example.imagetranslate.screenshot

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRegionMergePolicyTest {
    @Test
    fun `aligned slots with small line gaps become one background rect`() {
        val result = merge(
            rect(64, 2541, 1277, 2592),
            rect(64, 2602, 1277, 2652),
            rect(64, 2667, 1277, 2717),
            lineHeight = 50f
        )

        assertEquals(BackgroundRegionShape.CONTIGUOUS_RECT, result.shape)
        assertEquals(1, result.regions.size)
        assertRect(64, 2541, 1277, 2717, result.regions.single())
    }

    @Test
    fun `stepped historical flow uses narrow bridges instead of a wide bounding box`() {
        val result = merge(
            rect(72, 200, 598, 247),
            rect(142, 248, 407, 296),
            rect(88, 304, 993, 408),
            lineHeight = 48f
        )

        assertEquals(BackgroundRegionShape.BRIDGED_FLOW, result.shape)
        assertTrue(result.regions.size > 3)
        assertTrue(result.areaInflationRatio > 0.25f)
        assertTrue(result.regions.none { it.left == 72 && it.right == 993 })
    }

    @Test
    fun `visible paragraph gap remains separate`() {
        val result = merge(
            rect(64, 1212, 1149, 1320),
            rect(63, 1363, 381, 1400),
            lineHeight = 50f
        )

        assertEquals(BackgroundRegionShape.SEPARATE_BLOCKS, result.shape)
        assertEquals(2, result.regions.size)
    }

    @Test
    fun `side by side columns never merge`() {
        val result = merge(
            rect(20, 100, 180, 140),
            rect(220, 144, 390, 184),
            lineHeight = 40f
        )

        assertEquals(BackgroundRegionShape.SEPARATE_BLOCKS, result.shape)
        assertEquals(2, result.regions.size)
    }

    @Test
    fun `single render rect absorbs contained source cover slots`() {
        val result = merge(
            rect(20, 100, 380, 240),
            rect(20, 100, 360, 132),
            rect(20, 138, 340, 170),
            rect(20, 176, 380, 208),
            lineHeight = 32f
        )

        assertEquals(1, result.regions.size)
        assertRect(20, 100, 380, 240, result.regions.single())
    }

    @Test
    fun `only safe body flow rect becomes a text layout slot`() {
        val original = listOf(
            rect(64, 2541, 1277, 2592),
            rect(64, 2602, 1277, 2652)
        )
        val merged = BackgroundRegionMergePolicy.merge(original, 50f)

        val preferred = BackgroundRegionMergePolicy.preferredTextSlots(
            mergeResult = merged,
            originalRenderSlots = original,
            role = "BODY",
            layoutShape = "FLOW_SLOTS"
        )

        assertEquals(1, preferred.size)
    }

    @Test
    fun `bridged flow and non body content keep original text slots`() {
        val original = listOf(
            rect(72, 200, 598, 247),
            rect(142, 248, 407, 296),
            rect(88, 304, 993, 408)
        )
        val merged = BackgroundRegionMergePolicy.merge(original, 48f)

        assertEquals(
            original.size,
            BackgroundRegionMergePolicy.preferredTextSlots(
                merged,
                original,
                role = "BODY",
                layoutShape = "FLOW_SLOTS"
            ).size
        )
        assertEquals(
            original.size,
            BackgroundRegionMergePolicy.preferredTextSlots(
                BackgroundRegionMergePolicy.merge(
                    listOf(rect(20, 100, 380, 140), rect(20, 144, 380, 184)),
                    40f
                ),
                originalRenderSlots = original,
                role = "TITLE",
                layoutShape = "FLOW_SLOTS"
            ).size
        )
    }

    private fun merge(vararg slots: Rect, lineHeight: Float) =
        BackgroundRegionMergePolicy.merge(slots.toList(), lineHeight)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun assertRect(left: Int, top: Int, right: Int, bottom: Int, actual: Rect) {
        assertEquals(left, actual.left)
        assertEquals(top, actual.top)
        assertEquals(right, actual.right)
        assertEquals(bottom, actual.bottom)
    }
}
