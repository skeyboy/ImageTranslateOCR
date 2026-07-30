package com.example.experimentaltranslation

import java.io.Closeable

enum class ExperimentalTranslationEngine(val storageId: String) {
    DISABLED("disabled"),
    MARIAN_INT8("marian-int8"),
    TRANSLATEGEMMA_4B("translategemma-4b")
}

enum class TranslationLanguage(val code: String) {
    CHINESE("zh"),
    ENGLISH("en")
}

data class ExperimentalTranslationRequest(
    val text: String,
    val sourceLanguage: TranslationLanguage,
    val targetLanguage: TranslationLanguage,
    val maximumOutputTokens: Int = 160
)

data class ExperimentalTranslationResult(
    val text: String,
    val engine: ExperimentalTranslationEngine,
    val inferenceMs: Long
)

enum class ExperimentalModelState {
    NOT_INSTALLED,
    INSTALLING,
    READY,
    INVALID
}

data class ExperimentalModelStatus(
    val engine: ExperimentalTranslationEngine,
    val state: ExperimentalModelState,
    val installedBytes: Long = 0,
    val message: String? = null
)

fun interface ModelDownloadProgressListener {
    fun onProgress(downloadedBytes: Long, totalBytes: Long?)
}

interface ExperimentalTranslationProvider : Closeable {
    val engine: ExperimentalTranslationEngine
    suspend fun translate(request: ExperimentalTranslationRequest): ExperimentalTranslationResult
}

class ExperimentalModelUnavailableException(message: String) : IllegalStateException(message)

class ExperimentalTranslationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
