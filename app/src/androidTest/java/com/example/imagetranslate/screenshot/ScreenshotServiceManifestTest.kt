package com.example.imagetranslate.screenshot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenshotServiceManifestTest {
    @Test
    fun appDeclaresOverlayPermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )

        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.SYSTEM_ALERT_WINDOW)
        )
        assertTrue(
            packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED)
        )
    }

    @Test
    fun appRegistersBootReceiver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiverInfo = context.packageManager.getReceiverInfo(
            ComponentName(context, ScreenshotMonitorBootReceiver::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(receiverInfo.enabled)
        assertTrue(receiverInfo.exported)
    }

    @Test
    fun monitorPreferencesPersistBackgroundAndBootChoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalBackground =
            ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(context)
        val originalBoot = ScreenshotMonitorPreferences.isStartOnBootEnabled(context)
        val originalOverlay = ScreenshotMonitorPreferences.isOverlayEnabled(context)
        try {
            ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(context, true)
            ScreenshotMonitorPreferences.setStartOnBootEnabled(context, true)
            ScreenshotMonitorPreferences.setOverlayEnabled(context, true)

            assertTrue(ScreenshotMonitorPreferences.isBackgroundMonitoringEnabled(context))
            assertTrue(ScreenshotMonitorPreferences.isStartOnBootEnabled(context))
            assertTrue(ScreenshotMonitorPreferences.isOverlayEnabled(context))
        } finally {
            ScreenshotMonitorPreferences.setBackgroundMonitoringEnabled(context, originalBackground)
            ScreenshotMonitorPreferences.setStartOnBootEnabled(context, originalBoot)
            ScreenshotMonitorPreferences.setOverlayEnabled(context, originalOverlay)
        }
    }

    @Test
    fun captureServiceDeclaresOverlayAndMediaProjectionTypes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, OneShotScreenCaptureService::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(
            serviceInfo.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertTrue(
                serviceInfo.foregroundServiceType and
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
            )
        }
    }

    @Test
    fun screenCapturePermissionActivityIsInternal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, ScreenCapturePermissionActivity::class.java),
            PackageManager.GET_META_DATA
        )

        assertFalse(activityInfo.exported)
    }

    @Test
    fun screenshotMonitorIsNotStoppedWithTheAppTask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, ScreenshotMonitorService::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(serviceInfo.flags and ServiceInfo.FLAG_STOP_WITH_TASK == 0)
    }
}
