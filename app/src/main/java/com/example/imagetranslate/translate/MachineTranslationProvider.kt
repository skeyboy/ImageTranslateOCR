package com.example.imagetranslate.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class MachineTranslationParagraph(
    val paragraphId: String,
    val text: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

internal data class MachineTranslationParagraphResult(
    val paragraph: MachineTranslationParagraph,
    val translatedText: String?,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val succeeded: Boolean
        get() = !translatedText.isNullOrBlank()
}

internal interface MachineTranslationProvider : AutoCloseable {
    suspend fun translate(
        paragraphs: List<MachineTranslationParagraph>
    ): List<MachineTranslationParagraphResult>

    override fun close() = Unit
}

internal suspend fun <T, R> translateMachineParagraphsInParallel(
    paragraphs: List<T>,
    maxConcurrency: Int,
    translateOne: suspend (T) -> R
): List<R> {
    require(maxConcurrency > 0) { "Machine translation concurrency must be positive" }
    if (paragraphs.isEmpty()) return emptyList()
    val limiter = Semaphore(maxConcurrency)
    return coroutineScope {
        paragraphs.map { paragraph ->
            async {
                limiter.withPermit { translateOne(paragraph) }
            }
        }.awaitAll()
    }
}

internal class VolcMachineTranslationProvider(
    private val endpoint: String,
    private val bearerToken: String,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
) : MachineTranslationProvider {
    init {
        require(endpoint.isNotBlank()) { "Machine translation endpoint is empty" }
        require(bearerToken.isNotBlank()) { "Machine translation token is empty" }
        require(maxConcurrency > 0) { "Machine translation concurrency must be positive" }
    }

    override suspend fun translate(
        paragraphs: List<MachineTranslationParagraph>
    ): List<MachineTranslationParagraphResult> {
        if (paragraphs.isEmpty()) return emptyList()
        return translateMachineParagraphsInParallel(paragraphs, maxConcurrency, ::translateOne)
    }

    private suspend fun translateOne(
        paragraph: MachineTranslationParagraph
    ): MachineTranslationParagraphResult = try {
        val translated = withTimeout(timeoutMs) {
            withContext(Dispatchers.IO) {
                executeRequest(paragraph)
            }
        }
        MachineTranslationParagraphResult(paragraph, translated)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        MachineTranslationParagraphResult(
            paragraph = paragraph,
            translatedText = null,
            errorCode = "MACHINE_TRANSLATION_FAILED",
            errorMessage = error.message ?: "Machine translation failed"
        )
    }

    private fun executeRequest(paragraph: MachineTranslationParagraph): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            readTimeout = timeoutMs.toInt().coerceAtMost(Int.MAX_VALUE)
            doOutput = true
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Content-Type", "application/json-patch+json")
            setRequestProperty("request-from", "swagger")
        }
        return connection.useConnection { http ->
            val body = JSONObject()
                .put("text", paragraph.text)
                .put("sourceLanguage", paragraph.sourceLanguage)
                .put("targetLanguage", paragraph.targetLanguage)
                .toString()
            http.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val response = if (http.responseCode in 200..299) {
                http.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val detail = http.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                throw IllegalStateException(
                    "Machine translation HTTP ${http.responseCode}: ${detail.orEmpty().take(300)}"
                )
            }
            parseMachineTranslationResponse(response)
        }
    }

    private fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val DEFAULT_MAX_CONCURRENCY = 4
    }
}

internal fun parseMachineTranslationResponse(response: String): String {
    val normalized = response.trim()
    require(normalized.isNotEmpty()) { "Machine translation returned an empty response" }
    if (!normalized.startsWith('{')) return normalized

    val payload = runCatching { JSONObject(normalized) }.getOrElse { return normalized }
    val code = payload.optInt("code", 0)
    if (code != 0) {
        val message = payload.optString("message").trim().ifEmpty { "Unknown backend error" }
        throw IllegalStateException("Machine translation backend error $code: $message")
    }
    val translation = payload.optJSONObject("result")
        ?.optString("translation")
        ?.trim()
        .orEmpty()
    return translation.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("Machine translation response has no result.translation")
}
