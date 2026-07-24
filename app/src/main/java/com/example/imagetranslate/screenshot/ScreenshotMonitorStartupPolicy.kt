package com.example.imagetranslate.screenshot

import android.content.Intent

internal object ScreenshotMonitorStartupPolicy {
    fun shouldRestore(
        action: String?,
        backgroundMonitoringEnabled: Boolean,
        startOnBootEnabled: Boolean,
        permissionsGranted: Boolean
    ): Boolean =
        action in supportedActions &&
            backgroundMonitoringEnabled &&
            startOnBootEnabled &&
            permissionsGranted

    private val supportedActions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED
    )
}
