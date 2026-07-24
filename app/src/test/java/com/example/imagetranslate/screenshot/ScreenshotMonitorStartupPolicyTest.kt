package com.example.imagetranslate.screenshot

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotMonitorStartupPolicyTest {
    @Test
    fun restoresForBootWhenUserEnabledBothSettingsAndPermissionsRemain() {
        assertTrue(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_BOOT_COMPLETED,
                backgroundMonitoringEnabled = true,
                startOnBootEnabled = true,
                permissionsGranted = true
            )
        )
        assertTrue(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_MY_PACKAGE_REPLACED,
                backgroundMonitoringEnabled = true,
                startOnBootEnabled = true,
                permissionsGranted = true
            )
        )
    }

    @Test
    fun doesNotRestoreAfterEitherUserSettingIsDisabled() {
        assertFalse(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_BOOT_COMPLETED,
                backgroundMonitoringEnabled = false,
                startOnBootEnabled = true,
                permissionsGranted = true
            )
        )
        assertFalse(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_BOOT_COMPLETED,
                backgroundMonitoringEnabled = true,
                startOnBootEnabled = false,
                permissionsGranted = true
            )
        )
    }

    @Test
    fun rejectsMissingPermissionsAndUnrelatedBroadcasts() {
        assertFalse(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_BOOT_COMPLETED,
                backgroundMonitoringEnabled = true,
                startOnBootEnabled = true,
                permissionsGranted = false
            )
        )
        assertFalse(
            ScreenshotMonitorStartupPolicy.shouldRestore(
                Intent.ACTION_SCREEN_ON,
                backgroundMonitoringEnabled = true,
                startOnBootEnabled = true,
                permissionsGranted = true
            )
        )
    }
}
