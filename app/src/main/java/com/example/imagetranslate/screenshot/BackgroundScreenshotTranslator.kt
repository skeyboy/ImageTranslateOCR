package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.content.Context
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.semantic.SemanticTextGrouper
import com.example.imagetranslate.semantic.StaticImageTextFilter
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import com.example.imagetranslate.translate.toSemanticTranslationSource
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
        val uniqueTexts = preparedTexts(recognizedTexts)
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

    suspend fun translateBatch(
        recognizedTexts: List<String>,
        translateTexts: suspend (List<String>) -> List<String?>
    ): BackgroundTranslationResult {
        val uniqueTexts = preparedTexts(recognizedTexts)
        val requested = uniqueTexts.take(MAX_BACKGROUND_TEXTS)
        val translations = try {
            translateTexts(requested)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val translatedLines = if (translations.size == requested.size) {
            requested.zip(translations).mapNotNull { (source, translated) ->
                translated?.trim()?.takeIf { it.isNotEmpty() && it != source }?.let {
                    BackgroundTranslationLine(source, it)
                }
            }
        } else {
            emptyList()
        }
        return BackgroundTranslationResult(
            recognizedCount = uniqueTexts.size,
            translatedLines = translatedLines
        )
    }

    private fun preparedTexts(recognizedTexts: List<String>): List<String> = recognizedTexts
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

internal object BackgroundTranslationFormatter {
    private const val MAX_NOTIFICATION_LINES = 6
    private const val MAX_NOTIFICATION_CHARACTERS = 700

    fun bigText(lines: List<BackgroundTranslationLine>): String = lines
        .take(MAX_NOTIFICATION_LINES)
        .joinToString("\n") { line -> "${line.source} -> ${line.translation}" }
        .take(MAX_NOTIFICATION_CHARACTERS)
}

internal class BackgroundScreenshotTranslator(context: Context) {
    private val appContext = context.applicationContext

    suspend fun translate(bitmap: Bitmap): BackgroundTranslationResult {
        val ocrManager = OCRManager(appContext)
        val translateManager = TranslateManager(appContext)
        return try {
            val recognized = StaticImageTextFilter.filter(
                recognized = ocrManager.recognize(bitmap),
                viewportWidth = bitmap.width,
                viewportHeight = bitmap.height
            )
            val groups = SemanticTextGrouper.group(
                recognized = recognized,
                viewportWidth = bitmap.width,
                viewportHeight = bitmap.height
            )
            val recognizedTexts = groups.map { group -> group.sourceText }
            BackgroundTextTranslationEngine.translateBatch(recognizedTexts) { sources ->
                val requestedGroups = groups.filter { it.sourceText in sources }
                val executions = translateManager.translateSemanticGroups(
                    sources = requestedGroups.map { it.toSemanticTranslationSource() },
                    viewportWidth = bitmap.width,
                    viewportHeight = bitmap.height,
                    mode = TranslationMode.AUTO_BIDIRECTIONAL,
                    scene = "SCREENSHOT_NOTIFICATION"
                )
                requestedGroups.map { group ->
                    val matching = executions.filter { result ->
                        group.groupId in result.sourceGroupIds
                    }
                    matching.takeIf { results ->
                        results.isNotEmpty() && results.all { it.succeeded }
                    }?.joinToString("\n") { it.translatedText }
                }
            }
        } finally {
            ocrManager.close()
            translateManager.close()
        }
    }
}
