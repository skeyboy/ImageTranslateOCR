package com.example.imagetranslate.translate

import com.example.imagetranslate.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class RemoteTranslationProvider(
    baseUrl: String
) : TranslationProvider {
    override val id: String = "remote"
    private val endpoint = URL(baseUrl.trimEnd('/') + "/api/v1/translate/regions").also {
        require(
            it.protocol == "https" ||
                BuildConfig.DEBUG && isDebugHttpHost(it.host)
        ) {
            "Remote translation requires HTTPS outside local development"
        }
    }
    private val executor = Executors.newFixedThreadPool(MAXIMUM_CONCURRENT_REQUESTS)
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val closed = AtomicBoolean(false)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val batch = translateBatch(listOf(request))
        return batch.results.singleOrNull()
            ?: throw TranslationProviderException(
                batch.failures.singleOrNull()
                    ?: TranslationFailure(
                        regionId = request.regionId,
                        code = "REMOTE_TRANSLATION_FAILED",
                        message = "Remote translation returned no result",
                        retryable = true
                    )
            )
    }

    override suspend fun translateBatch(
        requests: List<TranslationRequest>
    ): TranslationBatchResult {
        if (requests.isEmpty()) return TranslationBatchResult(emptyList())
        check(!closed.get()) { "Remote translation provider is closed" }
        require(requests.map(TranslationRequest::requestId).distinct().size == 1) {
            "All regions in a translation batch must share one request ID"
        }
        require(requests.map(TranslationRequest::regionId).distinct().size == requests.size) {
            "Translation batch contains duplicate region IDs"
        }
        require(requests.map(TranslationRequest::mode).distinct().size == 1) {
            "Translation batch contains different translation modes"
        }
        val batchId = requests.first().requestId
        val responseText = executeRequest(buildRequestBody(requests), batchId)
        return parseResults(JSONObject(responseText), requests)
    }

    private fun buildRequestBody(requests: List<TranslationRequest>): String {
        val first = requests.first()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("requestId", first.requestId)
            .put("generation", 0)
            .put("scene", "ANDROID_CLIENT")
            .put(
                "translation",
                JSONObject()
                    .put("mode", first.mode.name)
                    .put("sourceLanguage", "auto")
                    .put("targetLanguage", "auto")
                    .put("preserveIdentifiers", true)
                    .put("useContext", requests.size > 1)
            )
            .put(
                "regions",
                JSONArray().apply {
                    requests.forEach { request ->
                        put(
                            JSONObject()
                                .put("regionId", request.regionId)
                                .put("text", request.text)
                                .put(
                                    "sourceLanguage",
                                    request.sourceLanguage ?: JSONObject.NULL
                                )
                                .put(
                                    "targetLanguage",
                                    request.targetLanguage ?: JSONObject.NULL
                                )
                        )
                    }
                }
            )
            .toString()
    }

    private suspend fun executeRequest(body: String, batchId: String): String =
        suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>()
            val futureRef = AtomicReference<Future<*>?>()
            val future = executor.submit {
                if (!continuation.isActive) return@submit
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Request-Id", batchId)
                    setRequestProperty("Idempotency-Key", batchId)
                }
                connectionRef.set(connection)
                activeConnections += connection
                if (!continuation.isActive) {
                    activeConnections -= connection
                    connection.disconnect()
                    return@submit
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body)
                    }
                    val status = connection.responseCode
                    val responseText = (
                        if (status in 200..299) connection.inputStream else connection.errorStream
                        )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        throw RemoteTranslationHttpException(status)
                    }
                    if (continuation.isActive) continuation.resume(responseText)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    activeConnections -= connection
                    connectionRef.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
            futureRef.set(future)
            continuation.invokeOnCancellation {
                connectionRef.getAndSet(null)?.disconnect()
                futureRef.getAndSet(null)?.cancel(true)
            }
        }

    private fun parseResults(
        response: JSONObject,
        requests: List<TranslationRequest>
    ): TranslationBatchResult {
        val requestId = response.optString("requestId")
        if (requestId.isNotBlank() && requestId != requests.first().requestId) {
            error("Remote translation response requestId does not match")
        }
        val expected = requests.associateBy(TranslationRequest::regionId)
        val results = response.optJSONArray("results")
            ?: error("Remote translation response has no results")
        val parsed = mutableListOf<TranslationResult>()
        val failures = mutableListOf<TranslationFailure>()
        val seen = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val regionId = item.getString("regionId")
            val request = expected[regionId]
                ?: error("Remote translation returned an unknown region ID")
            check(seen.add(regionId)) { "Remote translation returned a duplicate region ID" }
            when (val status = item.optString("status", "TRANSLATED")) {
                "TRANSLATED" -> {
                    val translatedText = item.optString("translatedText").trim()
                    if (translatedText.isEmpty()) {
                        failures += invalidResultFailure(regionId, "Remote translation was empty")
                    } else {
                        parsed += item.toTranslationResult(
                            regionId = regionId,
                            translatedText = translatedText,
                            status = TranslationResultStatus.TRANSLATED
                        )
                    }
                }
                "PRESERVED", "SKIPPED" -> parsed += item.toTranslationResult(
                    regionId = regionId,
                    translatedText = request.text,
                    status = TranslationResultStatus.PRESERVED
                )
                "FAILED" -> failures += item.toTranslationFailure(regionId)
                else -> failures += invalidResultFailure(
                    regionId,
                    "Unsupported remote result status: $status"
                )
            }
        }
        requests.filter { it.regionId !in seen }.forEach { request ->
            failures += TranslationFailure(
                regionId = request.regionId,
                code = "MISSING_RESULT",
                message = "Remote translation omitted a requested region",
                retryable = true
            )
        }
        return TranslationBatchResult(parsed, failures)
    }

    private fun JSONObject.toTranslationResult(
        regionId: String,
        translatedText: String,
        status: TranslationResultStatus
    ) = TranslationResult(
        regionId = regionId,
        translatedText = translatedText,
        provider = optString("provider", id),
        status = status,
        detectedSourceLanguage = optString("detectedSourceLanguage")
            .takeIf(String::isNotBlank),
        targetLanguage = optString("targetLanguage").takeIf(String::isNotBlank)
    )

    private fun JSONObject.toTranslationFailure(regionId: String): TranslationFailure {
        val error = optJSONObject("error")
        return TranslationFailure(
            regionId = regionId,
            code = error?.optString("code")?.takeIf(String::isNotBlank)
                ?: "REMOTE_TRANSLATION_FAILED",
            message = error?.optString("message")?.takeIf(String::isNotBlank)
                ?: "Remote translation failed",
            retryable = error?.optBoolean("retryable", true) ?: true
        )
    }

    private fun invalidResultFailure(regionId: String, message: String) = TranslationFailure(
        regionId = regionId,
        code = "INVALID_REMOTE_RESULT",
        message = message,
        retryable = true
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnections.forEach(HttpURLConnection::disconnect)
        activeConnections.clear()
        executor.shutdownNow()
    }

    private class RemoteTranslationHttpException(status: Int) :
        IllegalStateException("Remote translation failed with HTTP $status")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 130_000
        const val MAXIMUM_CONCURRENT_REQUESTS = 2
    }
}

internal fun isDebugHttpHost(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized == "::1") return true
    val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
        octets[0] == 10 ||
        octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 172 && octets[1] in 16..31
}
