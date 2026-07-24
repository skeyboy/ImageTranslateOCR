package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.CancellationException

internal data class BackgroundTranslationLine(
    val source: String,
    val translation: String
)

internal data class BackgroundTranslationResult(
    val recognizedCount: Int,
    val translatedLines: List<BackgroundTranslationLine>
)

internal object BackgroundTextTranslationEngine {
    private const val MAX_BACKGROUND_TEXTS = 24

    suspend fun translate(
        recognizedTexts: List<String>,
        translateText: suspend (String) -> String
    ): BackgroundTranslationResult {
        val uniqueTexts = recognizedTexts
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val translatedLines = mutableListOf<BackgroundTranslationLine>()
        for (source in uniqueTexts.take(MAX_BACKGROUND_TEXTS)) {
            val translated = try {
                translateText(source).trim()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            if (translated.isNotEmpty() && translated != source) {
                translatedLines.add(BackgroundTranslationLine(source, translated))
            }
        }
        return BackgroundTranslationResult(
            recognizedCount = uniqueTexts.size,
            translatedLines = translatedLines
        )
    }
}

internal object BackgroundTranslationFormatter {
    private const val MAX_NOTIFICATION_LINES = 6
    private const val MAX_NOTIFICATION_CHARACTERS = 700

    fun bigText(lines: List<BackgroundTranslationLine>): String = lines
        .take(MAX_NOTIFICATION_LINES)
        .joinToString("\n") { line -> "${line.source} -> ${line.translation}" }
        .take(MAX_NOTIFICATION_CHARACTERS)
}

internal class BackgroundScreenshotTranslator {
    suspend fun translate(bitmap: Bitmap): BackgroundTranslationResult {
        val ocrManager = OCRManager()
        val translateManager = TranslateManager()
        return try {
            val recognizedTexts = ocrManager.recognize(bitmap).map { it.text }
            BackgroundTextTranslationEngine.translate(recognizedTexts) { source ->
                translateManager.translate(source, TranslationMode.AUTO_BIDIRECTIONAL)
            }
        } finally {
            ocrManager.close()
            translateManager.close()
        }
    }
}
