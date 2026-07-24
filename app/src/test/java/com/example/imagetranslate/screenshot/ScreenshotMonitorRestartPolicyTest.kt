package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotMonitorRestartPolicyTest {
    @Test
    fun taskRemovalCanRecoverAnEnabledBackgroundMonitor() {
        assertTrue(
            ScreenshotMonitorRestartPolicy.shouldScheduleRestart(
                backgroundMonitoringEnabled = true,
                userRequestedStop = false
            )
        )
    }

    @Test
    fun explicitStopAndDisabledMonitoringNeverScheduleRecovery() {
        assertFalse(
            ScreenshotMonitorRestartPolicy.shouldScheduleRestart(
                backgroundMonitoringEnabled = true,
                userRequestedStop = true
            )
        )
        assertFalse(
            ScreenshotMonitorRestartPolicy.shouldScheduleRestart(
                backgroundMonitoringEnabled = false,
                userRequestedStop = false
            )
        )
    }
}
