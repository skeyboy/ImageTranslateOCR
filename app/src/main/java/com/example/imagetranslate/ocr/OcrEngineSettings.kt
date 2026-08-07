package com.example.imagetranslate.ocr

import android.content.Context

internal enum class OcrEngineType {
    ML_KIT,
    PADDLE
}

internal object OcrEngineSettings {
    private const val PREFERENCES = "ocr_engine_settings"
    private const val ENGINE = "engine"

    fun get(context: Context): OcrEngineType = runCatching {
        OcrEngineType.valueOf(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(ENGINE, null).orEmpty()
        )
    }.getOrDefault(OcrEngineType.ML_KIT)

    fun set(context: Context, engine: OcrEngineType) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENGINE, engine.name)
            .apply()
    }
}
