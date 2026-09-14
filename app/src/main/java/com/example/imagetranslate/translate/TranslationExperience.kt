package com.example.imagetranslate.translate

import android.content.Context

internal enum class TranslationExperience {
    MACHINE,
    AI
}

internal object TranslationExperienceSettings {
    private const val PREFERENCES = "translation_experience"
    private const val EXPERIENCE = "experience"

    fun get(context: Context): TranslationExperience {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(EXPERIENCE, null)
        return runCatching { TranslationExperience.valueOf(stored.orEmpty()) }
            .getOrDefault(TranslationExperience.AI)
    }

    fun set(context: Context, experience: TranslationExperience) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(EXPERIENCE, experience.name)
            .apply()
    }

    fun label(experience: TranslationExperience): String = when (experience) {
        TranslationExperience.MACHINE -> "机翻"
        TranslationExperience.AI -> "AI 智能翻译"
    }
}
