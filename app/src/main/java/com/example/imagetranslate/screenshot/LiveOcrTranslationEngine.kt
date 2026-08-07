package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.translate.TranslationMode

internal enum class LiveOcrTranslationEngineType {
    LOCAL_PIPELINE,
    PADDLE_NETWORK
}

internal object LiveOcrTranslationEngineSettings {
    private const val PREFERENCES = "live_ocr_translation_engine"
    private const val ENGINE = "engine"

    fun get(context: Context): LiveOcrTranslationEngineType = resolveLiveOcrTranslationEngine(
        storedValue = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENGINE, null),
        networkConfigured = com.example.imagetranslate.ocr.PaddleNetworkSettings.isConfigured(
            context
        )
    )

    fun set(context: Context, engine: LiveOcrTranslationEngineType) {
        require(
            engine != LiveOcrTranslationEngineType.PADDLE_NETWORK ||
                com.example.imagetranslate.ocr.PaddleNetworkSettings.isConfigured(context)
        ) { "PaddleOCR network service is not configured" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENGINE, engine.name)
            .apply()
    }
}

internal fun resolveLiveOcrTranslationEngine(
    storedValue: String?,
    networkConfigured: Boolean
): LiveOcrTranslationEngineType {
    val selected = storedValue
        ?.let { runCatching { LiveOcrTranslationEngineType.valueOf(it) }.getOrNull() }
        ?: LiveOcrTranslationEngineType.LOCAL_PIPELINE
    return selected.takeIf {
        it != LiveOcrTranslationEngineType.PADDLE_NETWORK || networkConfigured
    } ?: LiveOcrTranslationEngineType.LOCAL_PIPELINE
}

internal data class LiveOcrTranslationRequest(
    val bitmap: Bitmap,
    val mode: TranslationMode,
    val sessionId: String,
    val generation: Int
)

internal data class LiveOcrTranslatedRegion(
    val source: RecognizedText,
    val translation: String,
    val provider: String
)

internal data class LiveOcrTranslationResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val recognizedCount: Int,
    val regions: List<LiveOcrTranslatedRegion>,
    val failedCount: Int,
    val ocrMs: Long,
    val translationMs: Long,
    val totalMs: Long
)

internal interface LiveOcrTranslationEngine : AutoCloseable {
    val id: String

    suspend fun recognizeAndTranslate(
        request: LiveOcrTranslationRequest
    ): LiveOcrTranslationResult
}

internal fun validLiveOcrTranslationBounds(
    bounds: Rect,
    sourceWidth: Int,
    sourceHeight: Int
): Boolean = bounds.left >= 0 &&
    bounds.top >= 0 &&
    bounds.left < bounds.right &&
    bounds.top < bounds.bottom &&
    bounds.right <= sourceWidth &&
    bounds.bottom <= sourceHeight
