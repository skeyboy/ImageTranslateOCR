package com.example.imagetranslate.screenshot

import android.content.Context

internal object LiveSmartAssistSettingsPolicy {
    const val DEFAULT_ENABLED = false
}

internal object LiveSmartAssistPreferences {
    private const val PREFERENCES = "live_smart_assist"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, LiveSmartAssistSettingsPolicy.DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }
}
