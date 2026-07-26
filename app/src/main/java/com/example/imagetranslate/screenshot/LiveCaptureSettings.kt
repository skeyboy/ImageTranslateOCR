package com.example.imagetranslate.screenshot

import android.content.Context

internal enum class LiveCaptureFrequency(val frameSampleIntervalMs: Long) {
    LOW(150L),
    MEDIUM(100L),
    NORMAL(75L),
    HIGH(50L),
    VERY_HIGH(32L)
}

internal enum class LiveFrameBufferMode(val imageReaderMaxImages: Int) {
    SINGLE(2),
    MULTI(3)
}

internal enum class LiveRecognitionSegmentation {
    ADAPTIVE,
    FULL_FRAME,
    VERTICAL_BANDS
}

internal enum class LiveCaptureScenePreset {
    ADAPTIVE,
    READING,
    DENSE_TEXT,
    CODE,
    DYNAMIC,
    CUSTOM
}

internal data class LiveCaptureSettings(
    val scenePreset: LiveCaptureScenePreset,
    val frequency: LiveCaptureFrequency,
    val bufferMode: LiveFrameBufferMode,
    val segmentation: LiveRecognitionSegmentation
)

internal object LiveCaptureSettingsPolicy {
    val default: LiveCaptureSettings = forPreset(LiveCaptureScenePreset.ADAPTIVE)

    fun forPreset(preset: LiveCaptureScenePreset): LiveCaptureSettings = when (preset) {
        LiveCaptureScenePreset.ADAPTIVE -> LiveCaptureSettings(
            preset,
            LiveCaptureFrequency.HIGH,
            LiveFrameBufferMode.SINGLE,
            LiveRecognitionSegmentation.ADAPTIVE
        )
        LiveCaptureScenePreset.READING -> LiveCaptureSettings(
            preset,
            LiveCaptureFrequency.NORMAL,
            LiveFrameBufferMode.SINGLE,
            LiveRecognitionSegmentation.ADAPTIVE
        )
        LiveCaptureScenePreset.DENSE_TEXT -> LiveCaptureSettings(
            preset,
            LiveCaptureFrequency.NORMAL,
            LiveFrameBufferMode.MULTI,
            LiveRecognitionSegmentation.VERTICAL_BANDS
        )
        LiveCaptureScenePreset.CODE -> LiveCaptureSettings(
            preset,
            LiveCaptureFrequency.HIGH,
            LiveFrameBufferMode.SINGLE,
            LiveRecognitionSegmentation.VERTICAL_BANDS
        )
        LiveCaptureScenePreset.DYNAMIC -> LiveCaptureSettings(
            preset,
            LiveCaptureFrequency.VERY_HIGH,
            LiveFrameBufferMode.MULTI,
            LiveRecognitionSegmentation.FULL_FRAME
        )
        LiveCaptureScenePreset.CUSTOM -> default.copy(scenePreset = LiveCaptureScenePreset.CUSTOM)
    }

    fun customize(
        current: LiveCaptureSettings,
        frequency: LiveCaptureFrequency = current.frequency,
        bufferMode: LiveFrameBufferMode = current.bufferMode,
        segmentation: LiveRecognitionSegmentation = current.segmentation
    ): LiveCaptureSettings = LiveCaptureSettings(
        scenePreset = LiveCaptureScenePreset.CUSTOM,
        frequency = frequency,
        bufferMode = bufferMode,
        segmentation = segmentation
    )

    fun verticalBands(width: Int, height: Int): List<LiveRecognitionBand> {
        if (width <= 0 || height <= 1) return emptyList()
        val middle = height / 2
        val overlap = maxOf(MINIMUM_BAND_OVERLAP_PX, height / BAND_OVERLAP_HEIGHT_DIVISOR)
            .coerceAtMost(middle)
        return listOf(
            LiveRecognitionBand(0, 0, width, (middle + overlap).coerceAtMost(height)),
            LiveRecognitionBand(0, (middle - overlap).coerceAtLeast(0), width, height)
        )
    }

    private const val MINIMUM_BAND_OVERLAP_PX = 48
    private const val BAND_OVERLAP_HEIGHT_DIVISOR = 24
}

internal data class LiveRecognitionBand(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal object LiveCaptureSettingsPreferences {
    private const val PREFERENCES = "live_capture_settings"
    private const val SCENE_PRESET = "scene_preset"
    private const val FREQUENCY = "frequency"
    private const val BUFFER_MODE = "buffer_mode"
    private const val SEGMENTATION = "segmentation"

    fun get(context: Context): LiveCaptureSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.contains(FREQUENCY)) return LiveCaptureSettingsPolicy.default
        return LiveCaptureSettings(
            scenePreset = enumValue(
                preferences.getString(SCENE_PRESET, null),
                LiveCaptureScenePreset.CUSTOM
            ),
            frequency = enumValue(
                preferences.getString(FREQUENCY, null),
                LiveCaptureSettingsPolicy.default.frequency
            ),
            bufferMode = enumValue(
                preferences.getString(BUFFER_MODE, null),
                LiveCaptureSettingsPolicy.default.bufferMode
            ),
            segmentation = enumValue(
                preferences.getString(SEGMENTATION, null),
                LiveCaptureSettingsPolicy.default.segmentation
            )
        )
    }

    fun set(context: Context, settings: LiveCaptureSettings) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SCENE_PRESET, settings.scenePreset.name)
            .putString(FREQUENCY, settings.frequency.name)
            .putString(BUFFER_MODE, settings.bufferMode.name)
            .putString(SEGMENTATION, settings.segmentation.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
}
