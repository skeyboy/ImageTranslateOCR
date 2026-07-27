package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDifferentialRegionPlannerTest {
    @Test
    fun alignedScrollDoesNotMarkUnchangedCellsDirty() {
        val previous = stripedGrid(columns = 24, rows = 40)
        val current = shiftedGrid(previous, shiftRows = -10)

        val dirty = LiveDirtyGridPolicy.detect(
            previous = previous,
            current = current,
            shiftY = -800,
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            contentTop = 256,
            contentBottom = 3_008
        )

        assertEquals(0, dirty.dirtyCellCount)
        assertTrue(dirty.comparedCellCount > 0)
    }

    @Test
    fun localizedChangeProducesALocalDirtyRegion() {
        val previous = stripedGrid(columns = 24, rows = 40)
        val current = shiftedGrid(previous, shiftRows = -10).copy(
            values = shiftedGrid(previous, shiftRows = -10).values.copyOf().apply {
                for (row in 18..21) for (column in 8..11) {
                    this[row * 24 + column] = 255
                }
            }
        )

        val dirty = LiveDirtyGridPolicy.detect(
            previous,
            current,
            shiftY = -800,
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            contentTop = 256,
            contentBottom = 3_008
        )

        assertTrue(dirty.dirtyCellCount > 0)
        assertTrue(dirty.bounds.size <= 2)
    }

    @Test
    fun plannerExpandsIncomingRegionToWholeBoundaryTrack() {
        val plan = LiveDifferentialRegionPlanner.plan(
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            shiftY = -800,
            dirtyGrid = LiveDirtyGridResult(emptyList(), 0, 100),
            shiftedTracks = listOf(LiveDifferentialBounds(80, 2_050, 1_120, 2_400))
        )

        assertNotNull(plan)
        val incoming = requireNotNull(plan).recognitionBounds.single()
        assertTrue(incoming.top < 2_050)
        assertTrue(incoming.bottom >= 2_400)
        assertEquals(1, plan.boundaryTrackCount)
    }

    @Test
    fun plannerRejectsARecognitionAreaThatIsTooLarge() {
        val plan = LiveDifferentialRegionPlanner.plan(
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            shiftY = -1_900,
            dirtyGrid = LiveDirtyGridResult(emptyList(), 0, 100),
            shiftedTracks = emptyList()
        )

        assertNull(plan)
    }

    @Test
    fun plannerUsesBoundedContextWhenAGroupedTrackIsTooTall() {
        val plan = LiveDifferentialRegionPlanner.plan(
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            shiftY = -1_520,
            dirtyGrid = LiveDirtyGridResult(
                bounds = listOf(LiveDifferentialBounds(0, 400, 1_200, 800)),
                dirtyCellCount = 8,
                comparedCellCount = 180
            ),
            shiftedTracks = listOf(LiveDifferentialBounds(40, 500, 1_160, 2_200))
        )

        assertNotNull(plan)
        assertTrue(requireNotNull(plan).recognitionAreaRatio <= 0.72f)
        assertEquals(1, plan.recognitionBounds.size)
    }

    @Test
    fun plannerRetainsASeparateOutgoingEdgeContinuation() {
        val plan = LiveDifferentialRegionPlanner.plan(
            viewportWidth = 1_200,
            viewportHeight = 3_200,
            shiftY = -1_520,
            dirtyGrid = LiveDirtyGridResult(emptyList(), 0, 180),
            shiftedTracks = emptyList(),
            continuationBounds = listOf(LiveDifferentialBounds(0, 256, 1_200, 448))
        )

        assertNotNull(plan)
        assertEquals(2, requireNotNull(plan).recognitionBounds.size)
        assertTrue(plan.recognitionAreaRatio <= 0.72f)
    }

    @Test
    fun trackMergeKeepsCandidateAndRestoresOnlyUnmatchedBoundaryTrack() {
        val plan = LiveTrackMergePolicy.plan(
            boundaryTracks = listOf(
                LiveTrackedRegion(10L, LiveDifferentialBounds(20, 100, 980, 260)),
                LiveTrackedRegion(11L, LiveDifferentialBounds(20, 300, 980, 460))
            ),
            candidates = listOf(LiveDifferentialBounds(18, 96, 982, 268))
        )

        assertEquals(listOf(10L), plan.candidateTrackIds)
        assertEquals(setOf(11L), plan.restoredTrackIds)
    }

    private fun stripedGrid(columns: Int, rows: Int): LiveLuminanceGrid = LiveLuminanceGrid(
        columns = columns,
        rows = rows,
        values = IntArray(columns * rows) { index ->
            val row = index / columns
            (row * 17 + index % columns * 3) % 220
        }
    )

    private fun shiftedGrid(
        source: LiveLuminanceGrid,
        shiftRows: Int
    ): LiveLuminanceGrid = LiveLuminanceGrid(
        columns = source.columns,
        rows = source.rows,
        values = IntArray(source.values.size) { index ->
            val row = index / source.columns
            val column = index % source.columns
            val sourceRow = row - shiftRows
            if (sourceRow in 0 until source.rows) {
                source.values[sourceRow * source.columns + column]
            } else {
                0
            }
        }
    )
}
