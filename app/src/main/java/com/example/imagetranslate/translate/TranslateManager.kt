package com.example.imagetranslate.translate

import android.content.Context
import android.util.Log
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import com.example.experimentaltranslation.ExperimentalTranslationLibrary
import com.example.experimentaltranslation.ExperimentalTranslationRequest
import com.example.experimentaltranslation.TranslationLanguage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TranslationMode {
    CHINESE_TO_ENGLISH,
    ENGLISH_TO_CHINESE,
    AUTO_BIDIRECTIONAL
}

class TranslateManager(context: Context? = null) {
    private companion object {
        const val TAG = "ExperimentalTranslate"
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 60_000L
        const val TRANSLATION_TIMEOUT_MS = 20_000L
    }

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val downloadedModels = mutableSetOf<Pair<String, String>>()
    private val modelDownloadMutex = Mutex()
    private val experimentalLibraryDelegate = context?.applicationContext?.let { appContext ->
        lazy { ExperimentalTranslationLibrary(appContext) }
    }

    val areModelsReady: Boolean
        get() = (TranslateLanguage.CHINESE to TranslateLanguage.ENGLISH) in downloadedModels &&
            (TranslateLanguage.ENGLISH to TranslateLanguage.CHINESE) in downloadedModels

    suspend fun downloadModelIfNeeded(): Boolean {
        ensureModel(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)
        ensureModel(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE)
        return true
    }

    private suspend fun ensureModel(sourceLanguage: String, targetLanguage: String) =
        modelDownloadMutex.withLock {
            val languagePair = sourceLanguage to targetLanguage
            if (languagePair in downloadedModels) return@withLock
            val conditions = DownloadConditions.Builder().build()
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    translatorFor(sourceLanguage, targetLanguage).downloadModelIfNeeded(conditions)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                        .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                }
            }
            downloadedModels.add(languagePair)
        }

    suspend fun translate(
        text: String,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        experimentalEngine: ExperimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED
    ): String {
        val inputText = sanitizeOcrText(text)
        if (shouldPreserveSourceText(inputText)) return inputText
        val sourceLanguage = identifySourceLanguageByScript(inputText) ?: return inputText
        val targetLanguage = targetLanguageFor(sourceLanguage, mode) ?: return inputText

        if (experimentalEngine != ExperimentalTranslationEngine.DISABLED) {
            translateWithExperimentalEngine(
                experimentalEngine,
                inputText,
                sourceLanguage,
                targetLanguage
            )?.let { return it }
        }

        ensureModel(sourceLanguage, targetLanguage)
        val translator = translatorFor(sourceLanguage, targetLanguage)
        var result = translateWithModel(translator, inputText).trim()
        val requiresCompleteEnglish = sourceLanguage == TranslateLanguage.CHINESE &&
            targetLanguage == TranslateLanguage.ENGLISH && inputText.length <= 12
        val requiresChineseOutput = sourceLanguage == TranslateLanguage.ENGLISH &&
            targetLanguage == TranslateLanguage.CHINESE && inputText.length <= 32
        if (!isValidTranslation(result, targetLanguage) && inputText.length >= 16) {
            result = translateInSegments(translator, inputText)
        }
        require(
            isValidTranslation(
                result,
                targetLanguage,
                requiresCompleteEnglish,
                requiresChineseOutput
            )
        ) {
            "翻译结果包含异常字符"
        }
        return result
    }

    private suspend fun translateWithExperimentalEngine(
        engine: ExperimentalTranslationEngine,
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String? {
        val library = experimentalLibraryDelegate?.value ?: return null
        val source = sourceLanguage.toExperimentalLanguage() ?: return null
        val target = targetLanguage.toExperimentalLanguage() ?: return null
        return try {
            val translation = library.translate(
                engine,
                ExperimentalTranslationRequest(
                    text = text,
                    sourceLanguage = source,
                    targetLanguage = target
                )
            )
            val result = translation.text.trim().takeIf { candidate ->
                isValidTranslation(
                    candidate,
                    targetLanguage,
                    requireNoHanCharacters = sourceLanguage == TranslateLanguage.CHINESE && text.length <= 12,
                    requireChineseCharacters = sourceLanguage == TranslateLanguage.ENGLISH && text.length <= 32
                )
            }
            if (result != null) {
                Log.i(
                    TAG,
                    "Experimental translation succeeded: engine=${engine.name}, " +
                        "inferenceMs=${translation.inferenceMs}, inputChars=${text.length}, " +
                        "outputChars=${result.length}"
                )
            } else {
                Log.w(TAG, "Experimental translation rejected; falling back to ML Kit: engine=${engine.name}")
            }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Experimental translation unavailable; falling back to ML Kit: " +
                    "engine=${engine.name}, cause=${error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun String.toExperimentalLanguage(): TranslationLanguage? = when (this) {
        TranslateLanguage.CHINESE -> TranslationLanguage.CHINESE
        TranslateLanguage.ENGLISH -> TranslationLanguage.ENGLISH
        else -> null
    }

    private fun shouldPreserveSourceText(text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        if (visible.isEmpty()) return true

        val hanCount = visible.count(::isHanCharacter)
        val latinCount = visible.count { it in 'A'..'Z' || it in 'a'..'z' }
        val digitCount = visible.count(Char::isDigit)
        val meaningfulCount = hanCount + latinCount + digitCount
        val isNumericIdentifier = digitCount >= 4 && hanCount <= 1 &&
            meaningfulCount > 0 && digitCount.toFloat() / meaningfulCount >= 0.65f
        if (isNumericIdentifier) return true

        val looksLikeCode = visible.contains("//") || visible.contains('_') ||
            visible.contains('@') || visible.matches(Regex("[A-Za-z]+://.*"))
        val looksLikeBrandGroup = hanCount == 0 && latinCount > 0 &&
            visible.any { it in charArrayOf('×', '©', '®', '™') }
        return looksLikeCode || looksLikeBrandGroup
    }

    private fun targetLanguageFor(sourceLanguage: String, mode: TranslationMode): String? =
        when (mode) {
            TranslationMode.CHINESE_TO_ENGLISH ->
                TranslateLanguage.ENGLISH.takeIf { sourceLanguage == TranslateLanguage.CHINESE }
            TranslationMode.ENGLISH_TO_CHINESE ->
                TranslateLanguage.CHINESE.takeIf { sourceLanguage == TranslateLanguage.ENGLISH }
            TranslationMode.AUTO_BIDIRECTIONAL -> when (sourceLanguage) {
                TranslateLanguage.CHINESE -> TranslateLanguage.ENGLISH
                TranslateLanguage.ENGLISH -> TranslateLanguage.CHINESE
                else -> null
            }
        }

    private suspend fun translateInSegments(translator: Translator, text: String): String {
        val segments = text.split(Regex("(?<=[，。；;！？!?])"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (segments.size <= 1) return translateWithModel(translator, text).trim()
        val translatedSegments = mutableListOf<String>()
        for (segment in segments) {
            translatedSegments.add(translateWithModel(translator, segment).trim())
        }
        return translatedSegments.joinToString(" ")
    }

    private fun sanitizeOcrText(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.any(::isHanCharacter) || trimmed.length > 10) return trimmed
        return trimmed.trim('<', '>', '=', '|', '·', '•')
    }

    private fun identifySourceLanguageByScript(text: String): String? = when {
        text.any(::isHanCharacter) -> TranslateLanguage.CHINESE
        text.any { it in 'A'..'Z' || it in 'a'..'z' } -> TranslateLanguage.ENGLISH
        else -> null
    }

    private fun translatorFor(sourceLanguage: String, targetLanguage: String): Translator {
        val languagePair = sourceLanguage to targetLanguage
        return translators.getOrPut(languagePair) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )
        }
    }

    private fun isValidTranslation(
        text: String,
        targetLanguage: String,
        requireNoHanCharacters: Boolean = false,
        requireChineseCharacters: Boolean = false
    ): Boolean {
        if (text.isBlank()) return false
        if (text.first() in charArrayOf('<', '>', '=', '|')) return false
        val visibleCharacters = text.count { !it.isWhitespace() }
        if (visibleCharacters == 0) return false
        val meaningfulCharacters = text.count { it.isLetterOrDigit() }
        if (meaningfulCharacters.toFloat() / visibleCharacters < 0.6f) return false
        if (targetLanguage == TranslateLanguage.ENGLISH) {
            val hanCharacters = text.count(::isHanCharacter)
            if (requireNoHanCharacters && hanCharacters > 0) return false
            if (hanCharacters.toFloat() / visibleCharacters > 0.1f) return false
        } else if (targetLanguage == TranslateLanguage.CHINESE && requireChineseCharacters) {
            val hanCharacters = text.count(::isHanCharacter)
            val latinCharacters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
            if (hanCharacters == 0 || latinCharacters > hanCharacters * 2) return false
        }
        return true
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private suspend fun translateWithModel(
        translator: Translator,
        text: String
    ): String = withTimeout(TRANSLATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }
    }

    fun close() {
        translators.values.forEach(Translator::close)
        translators.clear()
        downloadedModels.clear()
        experimentalLibraryDelegate?.takeIf { it.isInitialized() }?.value?.close()
    }
}
