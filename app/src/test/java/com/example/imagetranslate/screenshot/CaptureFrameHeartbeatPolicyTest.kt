package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFrameHeartbeatPolicyTest {
    @Test
    fun receivedFrameClearsPreviousMisses() {
        val outcome = CaptureFrameHeartbeatPolicy.evaluate(
            frameAdvanced = true,
            previousMissedCount = 1,
            maximumMisses = 2
        )

        assertEquals(0, outcome.missedCount)
        assertFalse(outcome.streamInvalid)
    }

    @Test
    fun firstMissRetriesAndSecondMissInvalidatesStream() {
        val first = CaptureFrameHeartbeatPolicy.evaluate(
            frameAdvanced = false,
            previousMissedCount = 0,
            maximumMisses = 2
        )
        val second = CaptureFrameHeartbeatPolicy.evaluate(
            frameAdvanced = false,
            previousMissedCount = first.missedCount,
            maximumMisses = 2
        )

        assertEquals(1, first.missedCount)
        assertFalse(first.streamInvalid)
        assertEquals(2, second.missedCount)
        assertTrue(second.streamInvalid)
    }
}
