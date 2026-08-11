package com.example.imagetranslate.translate

import android.content.Context
import android.util.Log
import com.example.imagetranslate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap

internal class EmbeddedSemanticTranslationProvider(
    private val context: Context,
    private val provider: String,
    private val model: String,
    baseUrl: String,
    private val apiKey: String
) : SemanticTranslationProvider {
    private val endpoint = URL(baseUrl.trimEnd('/') + "/chat/completions").also {
        require(it.protocol == "https" || BuildConfig.DEBUG && it.host in setOf("127.0.0.1", "localhost")) {
            "Embedded AI provider requires HTTPS"
        }
    }
    private val codec = SelfHostedSemanticTranslationProvider("https://127.0.0.1", null, 4)

    override suspend fun translate(request: SemanticTranslationRequest): SemanticTranslationBatchResult {
        val requestJson = codec.requestBodyForTest(request)
        val prepared = NativeEdgeTranslationBridge.prepare(requestJson, provider, model)
        val prompt = prepared.getJSONObject("modelPrompt")
        val aiBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt.getString("system")))
                .put(JSONObject().put("role", "user").put("content", prompt.getString("user"))))
            .put("temperature", 0.0)
            .put("seed", 0)
            .put("max_tokens", prompt.getInt("recommendedMaxTokens"))
            .put("response_format", prompt.getJSONObject("responseFormat"))
        val started = System.nanoTime()
        val cachedCompletion = completionFromCache(prepared)
        val network = if (cachedCompletion == null) postJson(endpoint, aiBody.toString(), apiKey) else null
        val completion = cachedCompletion ?: checkNotNull(network).body
        if (cachedCompletion == null) cacheCompletion(prepared, completion)
        val providerMs = (System.nanoTime() - started) / 1_000_000
        val rustStarted = System.nanoTime()
        val response = NativeEdgeTranslationBridge.complete(prepared.toString(), completion, providerMs)
        val rustCompleteMs = (System.nanoTime() - rustStarted) / 1_000_000
        val totalMs = (System.nanoTime() - started) / 1_000_000
        val timings = JSONObject()
            .put("cacheHit", cachedCompletion != null)
            .put("dnsMs", JSONObject.NULL)
            .put("tlsMs", JSONObject.NULL)
            .put("requestToHeadersMs", network?.requestToHeadersMs ?: JSONObject.NULL)
            .put("responseHeadersMs", network?.responseHeadersMs ?: JSONObject.NULL)
            .put("responseDownloadMs", network?.downloadMs ?: JSONObject.NULL)
            .put("providerTotalMs", providerMs)
            .put("providerLatencyCheckpoint", providerLatencyCheckpoint(completion))
            .put("rustCompleteMs", rustCompleteMs)
            .put("totalBeforeAuditMs", totalMs)
        auditBestEffort(requestJson, prepared, aiBody, completion, response, totalMs, timings)
        return codec.parseResponseForTest(response.toString(), request)
    }

    private suspend fun postJson(url: URL, body: String, bearer: String): HttpResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 220_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearer.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val headersAt = System.nanoTime()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val completedAt = System.nanoTime()
            check(status in 200..299) { "AI provider returned HTTP $status: ${response.take(512)}" }
            HttpResult(
                body = response,
                requestToHeadersMs = (headersAt - started) / 1_000_000,
                responseHeadersMs = (headersAt - started) / 1_000_000,
                downloadMs = (completedAt - headersAt) / 1_000_000
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun auditBestEffort(
        requestJson: String,
        prepared: JSONObject,
        aiBody: JSONObject,
        completion: String,
        response: JSONObject,
        totalMs: Long,
        timings: JSONObject
    ) {
        val baseUrl = TranslationBackendSettings.edgeAuditBaseUrl(context)
        if (!BuildConfig.DEBUG || baseUrl.isBlank()) return
        runCatching {
            val body = JSONObject()
                .put("request", JSONObject(requestJson))
                .put("prepared", prepared)
                .put("modelRequest", aiBody)
                .put("modelResponse", JSONObject(completion))
                .put("response", response)
                .put("provider", provider)
                .put("model", model)
                .put("durationMs", totalMs)
                .put("timings", timings)
            val auditStarted = System.nanoTime()
            postJson(URL(baseUrl + "/api/v4/edge-audits"), body.toString(), "")
            Log.d(TAG, "Edge audit upload completed in ${(System.nanoTime() - auditStarted) / 1_000_000}ms")
        }.onFailure { Log.d(TAG, "Optional LAN edge audit unavailable: ${it.message}") }
    }

    private fun completionFromCache(prepared: JSONObject): String? = runCatching {
        val groups = prepared.getJSONArray("actionableGroups")
        val translations = JSONArray()
        synchronized(translationCache) {
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                val cached = translationCache[semanticCacheKey(group)] ?: return null
                translations.put(JSONObject(cached.toString()).put("groupId", group.getString("groupId")))
            }
        }
        completionEnvelope(translations)
    }.getOrNull()

    private fun cacheCompletion(prepared: JSONObject, completion: String) = runCatching {
        val groups = prepared.getJSONArray("actionableGroups")
        val byId = completionTranslations(completion).associateBy { it.getString("groupId") }
        synchronized(translationCache) {
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                val item = byId[group.getString("groupId")] ?: continue
                translationCache[semanticCacheKey(group)] = JSONObject(item.toString()).apply { remove("groupId") }
            }
        }
    }.onFailure { Log.d(TAG, "Semantic translation cache skipped: ${it.message}") }

    private fun completionTranslations(completion: String): List<JSONObject> {
        val envelope = JSONObject(completion)
        val content = envelope.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val translations = JSONObject(content).get("translations")
        return when (translations) {
            is JSONArray -> (0 until translations.length()).map { translations.getJSONObject(it) }
            is JSONObject -> translations.keys().asSequence().map { id ->
                JSONObject(translations.getJSONObject(id).toString()).put("groupId", id)
            }.toList()
            else -> emptyList()
        }
    }

    private fun completionEnvelope(translations: JSONArray): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject()
            .put("finish_reason", "stop")
            .put("message", JSONObject()
                .put("content", JSONObject().put("translations", translations).toString()))))
        .toString()

    private fun semanticCacheKey(group: JSONObject): String {
        val material = listOf(
            provider, model, group.optString("sourceLanguage", "auto"),
            group.optString("targetLanguage", "auto"), group.getString("sourceText").trim()
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun providerLatencyCheckpoint(completion: String): Any = runCatching {
        val usage = JSONObject(completion).optJSONObject("usage") ?: return@runCatching JSONObject.NULL
        usage.optJSONObject("service_tier_details")?.opt("latency_checkpoint")
            ?: usage.opt("latency_checkpoint") ?: JSONObject.NULL
    }.getOrDefault(JSONObject.NULL)

    override fun close() = codec.close()

    private data class HttpResult(
        val body: String,
        val requestToHeadersMs: Long,
        val responseHeadersMs: Long,
        val downloadMs: Long
    )

    private companion object {
        const val TAG = "EmbeddedTranslation"
        const val MAX_CACHE_ENTRIES = 256
        val translationCache = object : LinkedHashMap<String, JSONObject>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    }
}
