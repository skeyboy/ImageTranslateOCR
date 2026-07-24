package com.example.imagetranslate.screenshot

import android.content.Context

internal object ScreenshotMonitorPreferences {
    private const val PREFERENCES = "screenshot_monitor"
    private const val BACKGROUND_MONITORING = "background_monitoring"
    private const val START_ON_BOOT = "start_on_boot"
    private const val SHOW_OVERLAY = "show_overlay"
    const val DISCLOSURE_ACCEPTED = "disclosure_accepted"

    fun isBackgroundMonitoringEnabled(context: Context): Boolean =
        preferences(context).getBoolean(BACKGROUND_MONITORING, false)

    fun setBackgroundMonitoringEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(BACKGROUND_MONITORING, enabled).apply()
    }

    fun isStartOnBootEnabled(context: Context): Boolean =
        preferences(context).getBoolean(START_ON_BOOT, false)

    fun setStartOnBootEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(START_ON_BOOT, enabled).apply()
    }

    fun isOverlayEnabled(context: Context): Boolean =
        preferences(context).getBoolean(SHOW_OVERLAY, false)

    fun setOverlayEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(SHOW_OVERLAY, enabled).apply()
    }

    fun isDisclosureAccepted(context: Context): Boolean =
        preferences(context).getBoolean(DISCLOSURE_ACCEPTED, false)

    fun setDisclosureAccepted(context: Context) {
        preferences(context).edit().putBoolean(DISCLOSURE_ACCEPTED, true).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
