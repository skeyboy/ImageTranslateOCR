package com.example.imagetranslate.screenshot

internal object ScreenshotMonitorRestartPolicy {
    fun shouldScheduleRestart(
        backgroundMonitoringEnabled: Boolean,
        userRequestedStop: Boolean
    ): Boolean = backgroundMonitoringEnabled && !userRequestedStop
}
