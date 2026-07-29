package com.example.imagetranslate.screenshot

import android.content.Context

internal enum class LivePatchBackgroundExperienceMode {
    OFF,
    THEME_COLOR,
    GAUSSIAN_BLUR
}

internal object LivePatchBackgroundExperiencePolicy {
    val default = LivePatchBackgroundExperienceMode.OFF

    fun executionProfile(
        mode: LivePatchBackgroundExperienceMode,
        base: LiveRecognitionExecutionProfile = LiveRecognitionExecutionProfile.CURRENT
    ): LiveRecognitionExecutionProfile = base.copy(
        backgroundMode = when (mode) {
            LivePatchBackgroundExperienceMode.OFF -> LivePatchBackgroundMode.STANDARD
            LivePatchBackgroundExperienceMode.THEME_COLOR ->
                LivePatchBackgroundMode.THEME_SURFACE
            LivePatchBackgroundExperienceMode.GAUSSIAN_BLUR ->
                LivePatchBackgroundMode.BLUR_TINT
        }
    )

    fun fromStored(value: String?): LivePatchBackgroundExperienceMode =
        LivePatchBackgroundExperienceMode.entries.firstOrNull { it.name == value } ?: default
}

internal object LivePatchBackgroundExperiencePreferences {
    private const val PREFERENCES = "live_patch_background_experience"
    private const val MODE = "mode"

    fun get(context: Context): LivePatchBackgroundExperienceMode {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(MODE, null)
        return LivePatchBackgroundExperiencePolicy.fromStored(stored)
    }

    fun set(context: Context, mode: LivePatchBackgroundExperienceMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(MODE, mode.name)
            .apply()
    }
}
