package com.example.imagetranslate.screenshot

import android.content.Context

internal enum class LiveOverlayExperienceMode {
    DEFAULT,
    ENHANCED
}

internal object LiveOverlayExperiencePolicy {
    fun resolve(
        requested: LiveOverlayExperienceMode,
        accessibilityConnected: Boolean
    ): LiveOverlayExperienceMode = if (
        requested == LiveOverlayExperienceMode.ENHANCED && accessibilityConnected
    ) {
        LiveOverlayExperienceMode.ENHANCED
    } else {
        LiveOverlayExperienceMode.DEFAULT
    }

    fun translationWindowAlpha(
        mode: LiveOverlayExperienceMode,
        standardOverlayAlpha: Float
    ): Float = if (mode == LiveOverlayExperienceMode.ENHANCED) {
        1f
    } else {
        standardOverlayAlpha
    }

    fun canPresentTranslation(
        requested: LiveOverlayExperienceMode,
        resolved: LiveOverlayExperienceMode
    ): Boolean = requested != LiveOverlayExperienceMode.ENHANCED ||
        resolved == LiveOverlayExperienceMode.ENHANCED

    fun effectiveBackgroundExperienceMode(
        requested: LiveOverlayExperienceMode,
        resolved: LiveOverlayExperienceMode,
        selected: LivePatchBackgroundExperienceMode
    ): LivePatchBackgroundExperienceMode = if (
        requested == LiveOverlayExperienceMode.ENHANCED &&
        resolved != LiveOverlayExperienceMode.ENHANCED
    ) {
        LivePatchBackgroundExperienceMode.OFF
    } else {
        selected
    }
}

internal object LiveOverlayExperiencePreferences {
    private const val PREFERENCES = "live_overlay_experience"
    private const val REQUESTED_MODE = "requested_mode"

    fun requestedMode(context: Context): LiveOverlayExperienceMode {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(REQUESTED_MODE, null)
        return runCatching { LiveOverlayExperienceMode.valueOf(stored.orEmpty()) }
            .getOrDefault(LiveOverlayExperienceMode.DEFAULT)
    }

    fun setRequestedMode(context: Context, mode: LiveOverlayExperienceMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(REQUESTED_MODE, mode.name)
            .apply()
    }
}
