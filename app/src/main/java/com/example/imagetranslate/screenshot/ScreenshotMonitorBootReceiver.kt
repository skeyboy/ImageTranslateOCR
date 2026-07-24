package com.example.imagetranslate.screenshot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class ScreenshotMonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ScreenshotMonitorStartupPolicy.shouldRestore(
                action = intent.action,
                backgroundMonitoringEnabled =
                    ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(context),
                startOnBootEnabled = ScreenshotMonitorPreferences.isStartOnBootEnabled(context),
                permissionsGranted = hasRequiredPermissions(context)
            )
        ) {
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenshotMonitorService::class.java)
                    .setAction(ScreenshotMonitorService.ACTION_START)
            )
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val mediaGranted = ContextCompat.checkSelfPermission(context, mediaPermission) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val overlayGranted = !ScreenshotMonitorPreferences.isOverlayEnabled(context) ||
            Settings.canDrawOverlays(context)
        return mediaGranted && notificationsGranted && overlayGranted
    }
}
