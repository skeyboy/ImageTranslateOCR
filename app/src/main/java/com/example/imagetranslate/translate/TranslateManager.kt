package com.example.imagetranslate.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslateManager {
    private val languageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.34f)
            .build()
    )
    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val downloadedModels = mutableSetOf<Pair<String, String>>()

    suspend fun downloadModelIfNeeded(): Boolean {
        ensureModel(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)
        return true
    }

    private suspend fun ensureModel(sourceLanguage: String, targetLanguage: String) {
        val languagePair = sourceLanguage to targetLanguage
        if (languagePair in downloadedModels) return
        val conditions = DownloadConditions.Builder().build()
        suspendCancellableCoroutine { cont ->
            translatorFor(sourceLanguage, targetLanguage).downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        }
        downloadedModels.add(languagePair)
    }

    suspend fun translate(
        text: String,
        targetLanguage: String = TranslateLanguage.ENGLISH
    ): String {
        val inputText = sanitizeOcrText(text)
        val sourceLanguage = identifySourceLanguage(inputText, targetLanguage)
        if (sourceLanguage == targetLanguage) return inputText

        ensureModel(sourceLanguage, targetLanguage)
        val translator = translatorFor(sourceLanguage, targetLanguage)
        var result = translateWithModel(translator, inputText).trim()
        if (!isValidTranslation(result, targetLanguage) && inputText.length >= 16) {
            result = translateInSegments(translator, inputText)
        }
        require(isValidTranslation(result, targetLanguage)) { "翻译结果包含异常字符" }
        return result
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

    private suspend fun identifySourceLanguage(text: String, targetLanguage: String): String {
        if (text.any(::isHanCharacter)) return TranslateLanguage.CHINESE
        val detected = suspendCancellableCoroutine { cont ->
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { language -> if (cont.isActive) cont.resume(language) }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }
        if (detected == "und") {
            return if (text.any(::isHanCharacter)) TranslateLanguage.CHINESE else targetLanguage
        }
        return TranslateLanguage.fromLanguageTag(detected) ?: if (text.any(::isHanCharacter)) {
            TranslateLanguage.CHINESE
        } else {
            targetLanguage
        }
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

    private fun isValidTranslation(text: String, targetLanguage: String): Boolean {
        if (text.isBlank()) return false
        if (text.first() in charArrayOf('<', '>', '=', '|')) return false
        val visibleCharacters = text.count { !it.isWhitespace() }
        if (visibleCharacters == 0) return false
        val meaningfulCharacters = text.count { it.isLetterOrDigit() }
        if (meaningfulCharacters.toFloat() / visibleCharacters < 0.6f) return false
        if (targetLanguage == TranslateLanguage.ENGLISH) {
            val hanCharacters = text.count(::isHanCharacter)
            if (hanCharacters.toFloat() / visibleCharacters > 0.1f) return false
        }
        return true
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private suspend fun translateWithModel(
        translator: Translator,
        text: String
    ): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    fun close() {
        languageIdentifier.close()
        translators.values.forEach(Translator::close)
        translators.clear()
        downloadedModels.clear()
    }
}
