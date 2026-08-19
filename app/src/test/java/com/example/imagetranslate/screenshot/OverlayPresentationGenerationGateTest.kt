package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresentationGenerationGateTest {
    @Test
    fun onlyActiveCaptureGenerationCanRender() {
        val gate = OverlayPresentationGenerationGate()

        assertTrue(gate.begin(7))
        assertTrue(gate.accepts(7))
        assertFalse(gate.accepts(6))
        assertFalse(gate.accepts(8))
    }

    @Test
    fun newerCapturePermanentlyRejectsLateOlderResult() {
        val gate = OverlayPresentationGenerationGate()

        assertTrue(gate.begin(7))
        assertTrue(gate.begin(8))
        assertFalse(gate.accepts(7))
        assertTrue(gate.accepts(8))
        assertFalse(gate.begin(7))
        assertTrue(gate.accepts(8))
    }
}
