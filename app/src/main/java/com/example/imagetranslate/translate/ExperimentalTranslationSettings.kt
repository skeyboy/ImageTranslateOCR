package com.example.imagetranslate.translate

import android.content.Context
import com.example.experimentaltranslation.ExperimentalTranslationEngine

object ExperimentalTranslationSettings {
    const val DEFAULT_ENABLED = false
    val DEFAULT_ENGINE = ExperimentalTranslationEngine.DISABLED

    private const val PREFERENCES = "experimental_translation"
    private const val ENGINE = "engine"

    fun get(context: Context): ExperimentalTranslationEngine {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENGINE, null)
        return stored?.let { runCatching { ExperimentalTranslationEngine.valueOf(it) }.getOrNull() }
            ?: DEFAULT_ENGINE
    }

    fun set(context: Context, engine: ExperimentalTranslationEngine) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENGINE, engine.name)
            .apply()
    }
}
