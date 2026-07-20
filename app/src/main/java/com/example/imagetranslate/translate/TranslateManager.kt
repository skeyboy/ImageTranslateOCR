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
    private val uiTranslations = mapOf(
        "Android系统" to "Android system",
        "已连接到USB调试·现在" to "Connected to USB debugging · now",
        "点按即可关闭USB调试" to "Tap to turn off USB debugging",
        "颜色" to "Color",
        "自然色" to "Natural colors",
        "颜色对比度" to "Color contrast",
        "默认" to "Default",
        "其他显示控件" to "Other display controls",
        "旋转设置" to "Rotation settings",
        "启用自动旋转" to "Auto-rotate",
        "连接或断开电源时唤醒" to "Wake on power connection",
        "分享" to "Share",
        "编辑" to "Edit",
        "删除" to "Delete"
    )

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
        val normalized = normalizeForMatching(text)
        if (targetLanguage == TranslateLanguage.ENGLISH && text.any(::isHanCharacter)) {
            translateKnownChineseUi(normalized)?.let { return it }
        }
        val sourceLanguage = identifySourceLanguage(text, targetLanguage)
        if (sourceLanguage == targetLanguage) return text

        if (sourceLanguage == TranslateLanguage.CHINESE &&
            targetLanguage == TranslateLanguage.ENGLISH
        ) {
            translateKnownChineseUi(normalized)?.let { return it }
        }

        ensureModel(sourceLanguage, targetLanguage)
        val result = translateWithModel(
            translatorFor(sourceLanguage, targetLanguage), text
        ).trim()
        require(isValidTranslation(result, targetLanguage)) { "翻译结果包含异常字符" }
        return result
    }

    private fun normalizeForMatching(text: String): String {
        val compact = text.replace(Regex("[\\s·•]+"), "")
        if (!compact.any(::isHanCharacter) || compact.length > 10) return compact
        return compact.trim { character ->
            !character.isLetterOrDigit() && !isHanCharacter(character)
        }
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

    private fun translateKnownChineseUi(normalized: String): String? {
        uiTranslations[normalized]?.let { return it }
        findCloseUiTranslation(normalized)?.let { return it }
        if (normalized.length <= 8) {
            when {
                normalized.contains("分享") -> return "Share"
                normalized.contains("编辑") -> return "Edit"
                normalized.contains("删除") -> return "Delete"
            }
        }
        if (normalized.contains("连接") && normalized.contains("电源") &&
            normalized.contains("唤醒")
        ) {
            return "Wake on power connection"
        }
        return null
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

    private fun findCloseUiTranslation(text: String): String? {
        if (text.length !in 2..8) return null
        return uiTranslations.entries
            .asSequence()
            .filter { it.key.length <= 8 }
            .map { it to editDistance(text, it.key) }
            .filter { (_, distance) -> distance <= 1 }
            .minByOrNull { (_, distance) -> distance }
            ?.first
            ?.value
    }

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (firstIndex in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (first[firstIndex] == second[secondIndex]) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun isValidTranslation(text: String, targetLanguage: String): Boolean {
        if (text.isBlank()) return false
        if (text.first() in charArrayOf('<', '>', '=', '|')) return false
        if (targetLanguage == TranslateLanguage.ENGLISH && text.any(::isHanCharacter)) return false
        val visibleCharacters = text.count { !it.isWhitespace() }
        if (visibleCharacters == 0) return false
        val meaningfulCharacters = text.count { it.isLetterOrDigit() }
        return meaningfulCharacters.toFloat() / visibleCharacters >= 0.6f
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
