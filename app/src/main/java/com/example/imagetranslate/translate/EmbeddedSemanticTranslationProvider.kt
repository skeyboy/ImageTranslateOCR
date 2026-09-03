package com.example.imagetranslate.translate

import android.content.Context
import android.util.Log
import com.example.imagetranslate.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import kotlin.random.Random

internal fun completionTranslationId(item: JSONObject): String? =
    item.optString("groupId").trim().ifBlank { item.optString("id").trim() }
        .takeIf(String::isNotBlank)

internal class EmbeddedSemanticTranslationProvider(
    private val context: Context,
    private val provider: String,
    private val model: String,
    baseUrl: String,
    private val apiKey: String,
    private val thinkingMode: ProviderThinkingControlMode = ProviderThinkingControlMode.NONE,
    private val thinkingLevel: String = "low",
    proxyUrl: String = "",
    private val cacheEnabled: Boolean = true
) : SemanticTranslationProvider {
    private val nativeGeminiApi = provider.trim().lowercase() in setOf("google", "gemini", "google-gemini")
    internal val endpointForTest = URL(
        if (nativeGeminiApi) {
            require(model.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid Gemini model ID" }
            baseUrl.trimEnd('/') + "/models/$model:generateContent"
        } else {
            normalizeOpenAiBaseUrl(baseUrl) + "/chat/completions"
        }
    ).also {
        require(it.protocol == "https" || BuildConfig.DEBUG && it.host in setOf("127.0.0.1", "localhost")) {
            "Embedded AI provider requires HTTPS"
        }
    }
    private val codec = SelfHostedSemanticTranslationProvider("https://127.0.0.1", null, 4)
    private val auditScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerProxy = proxyUrl.takeIf(String::isNotBlank)?.let(::configuredProxy)

    override suspend fun translate(request: SemanticTranslationRequest): SemanticTranslationBatchResult {
        val requestJson = codec.requestBodyForTest(request)
        return try {
            val overallStarted = System.nanoTime()
            val prepareStarted = System.nanoTime()
            val prepared = NativeEdgeTranslationBridge.prepare(requestJson, provider, model)
            val aiBody = providerRequestBody(prepared)
            val prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000
            val providerStarted = System.nanoTime()
            val cachedCompletion = if (cacheEnabled) {
                completionFromCache(prepared, request.directStructuredOutput)
            } else {
                null
            }
            val network = if (cachedCompletion == null) {
                postProviderJson(aiBody)
            } else {
                null
            }
            val completion = cachedCompletion ?: checkNotNull(network).body
            if (cacheEnabled && cachedCompletion == null) {
                cacheCompletion(prepared, completion, request.directStructuredOutput)
            }
            val providerMs = (System.nanoTime() - providerStarted) / 1_000_000
            val rustStarted = System.nanoTime()
            val response = NativeEdgeTranslationBridge.complete(prepared.toString(), completion, providerMs)
            val rustCompleteMs = (System.nanoTime() - rustStarted) / 1_000_000
            val totalMs = (System.nanoTime() - overallStarted) / 1_000_000
            val usage = providerUsage(completion)
            val timings = JSONObject()
            .put("schemaVersion", 2)
            .put("captureEncodeMs", request.debugCapture?.encodeMs ?: JSONObject.NULL)
            .put("prepareMs", prepareMs)
            .put("groupCount", request.sources.size)
            .put("cacheHit", cachedCompletion != null)
            .put("dnsMs", JSONObject.NULL)
            .put("tlsMs", JSONObject.NULL)
            .put("requestToHeadersMs", network?.requestToHeadersMs ?: JSONObject.NULL)
            .put("responseHeadersMs", network?.responseHeadersMs ?: JSONObject.NULL)
            .put("responseDownloadMs", network?.downloadMs ?: JSONObject.NULL)
            .put("providerTotalMs", providerMs)
            .put("providerRetryCount", network?.retryCount ?: 0)
            .put("providerRetryDelayMs", network?.retryDelayMs ?: 0)
            .put("thinkingControlMode", thinkingMode.name)
            .put("thinkingLevel", thinkingLevel)
            .put("actualThinkingParameter", actualThinkingParameter(aiBody))
            .put("thinkingParameterFallback", false)
            .put("reasoningParameterFallback", false)
            .put("proxyConfigured", providerProxy != null)
            .put("providerLatencyCheckpoint", providerLatencyCheckpoint(completion))
            .put("promptTokens", usage.optLongOrNull("prompt_tokens"))
            .put("completionTokens", usage.optLongOrNull("completion_tokens"))
            .put("totalTokens", usage.optLongOrNull("total_tokens"))
            .put(
                "reasoningTokens",
                usage.optJSONObject("completion_tokens_details")
                    ?.optLongOrNull("reasoning_tokens") ?: JSONObject.NULL
            )
            .put("rustCompleteMs", rustCompleteMs)
            .put("totalBeforeAuditMs", totalMs)
            enqueueAudit(requestJson, prepared, aiBody, completion, response, totalMs, timings)
            TranslationRequestArchiveStore.recordExchange(
                context = context,
                requestJson = requestJson,
                responseJson = response.toString(),
                provider = provider,
                model = model,
                providerRequestJson = aiBody.toString(),
                providerResponseJson = completion,
                timings = timings
            )
            codec.parseResponseForTest(response.toString(), request)
        } catch (error: Exception) {
            runCatching {
                TranslationRequestArchiveStore.recordExchange(
                    context = context,
                    requestJson = requestJson,
                    provider = provider,
                    model = model,
                    errorMessage = error.message ?: "Direct provider translation failed"
                )
            }
            throw error
        }
    }

    internal fun providerRequestBodyForTest(requestJson: String): JSONObject =
        providerRequestBody(NativeEdgeTranslationBridge.prepare(requestJson, provider, model))

    private fun providerRequestBody(prepared: JSONObject): JSONObject {
        val prompt = prepared.getJSONObject("modelPrompt")
        if (nativeGeminiApi) return nativeGeminiRequestBody(prompt)
        return JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt.getString("system")))
                    .put(JSONObject().put("role", "user").put("content", prompt.getString("user")))
            )
            .put("max_tokens", prompt.getInt("recommendedMaxTokens"))
            .put("response_format", prompt.getJSONObject("responseFormat"))
            .apply {
                if (!isGemini3Model(model)) {
                    put("temperature", 0.0)
                    put("seed", 0)
                }
                applyThinkingControl(this)
            }
    }

    private fun nativeGeminiRequestBody(prompt: JSONObject): JSONObject {
        val responseFormat = prompt.getJSONObject("responseFormat")
        val responseSchema = responseFormat.getJSONObject("json_schema").getJSONObject("schema")
        val generationConfig = JSONObject()
            .put("maxOutputTokens", prompt.getInt("recommendedMaxTokens"))
            .put("responseMimeType", "application/json")
            .put("responseJsonSchema", JSONObject(responseSchema.toString()))
        if (thinkingMode == ProviderThinkingControlMode.THINKING_LEVEL) {
            generationConfig.put(
                "thinkingConfig",
                JSONObject()
                    .put("includeThoughts", true)
                    .put("thinkingLevel", thinkingLevel.uppercase())
            )
        }
        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", prompt.getString("system")))
                )
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt.getString("user")))
                        )
                )
            )
            .put("generationConfig", generationConfig)
    }

    private suspend fun postProviderJson(body: JSONObject): HttpResult {
        var retryCount = 0
        var retryDelayMs = 0L
        while (true) {
            try {
                return postProviderJsonOnce(body).copy(
                    retryCount = retryCount,
                    retryDelayMs = retryDelayMs
                )
            } catch (error: ProviderHttpException) {
                val delayMs = EmbeddedProviderRetryPolicy.delayMs(
                    status = error.status,
                    completedRetryCount = retryCount,
                    jitterMs = Random.nextLong(
                        from = 0,
                        until = EmbeddedProviderRetryPolicy.MAXIMUM_JITTER_MS + 1
                    )
                ) ?: throw error
                retryCount++
                retryDelayMs += delayMs
                Log.w(TAG, "Provider HTTP ${error.status}; retrying request in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }

    private suspend fun postProviderJsonOnce(body: JSONObject): HttpResult {
        val result = postJson(
            url = endpointForTest,
            body = body.toString(),
            bearer = apiKey.takeUnless { nativeGeminiApi }.orEmpty(),
            googleApiKey = apiKey.takeIf { nativeGeminiApi }.orEmpty(),
            proxy = providerProxy
        )
        return if (nativeGeminiApi) result.copy(body = normalizeGeminiCompletion(result.body)) else result
    }

    private fun applyThinkingControl(body: JSONObject) {
        when (thinkingMode) {
            ProviderThinkingControlMode.NONE -> Unit
            ProviderThinkingControlMode.REASONING_EFFORT ->
                body.put("reasoning_effort", thinkingLevel)
            ProviderThinkingControlMode.THINKING_LEVEL -> {
                if (thinkingLevel != "none") {
                    body.put(
                        "google",
                        JSONObject().put(
                            "thinking_config",
                            JSONObject().put("thinking_level", thinkingLevel.uppercase())
                        )
                    )
                }
            }
        }
    }

    private fun actualThinkingParameter(body: JSONObject): String = when {
        body.has("reasoning_effort") -> "reasoning_effort"
        body.optJSONObject("google")?.optJSONObject("thinking_config")
            ?.has("thinking_level") == true ->
            "google.thinking_config.thinking_level"
        body.optJSONObject("generationConfig")?.optJSONObject("thinkingConfig")
            ?.has("thinkingLevel") == true ->
            "generationConfig.thinkingConfig.thinkingLevel"
        else -> "none"
    }

    private suspend fun postJson(
        url: URL,
        body: String,
        bearer: String,
        googleApiKey: String = "",
        proxy: Proxy? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val connection = ((proxy?.let(url::openConnection) ?: url.openConnection()) as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearer.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            if (googleApiKey.isNotBlank()) setRequestProperty("x-goog-api-key", googleApiKey)
        }
        var responseConsumed = false
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val headersAt = System.nanoTime()
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val completedAt = System.nanoTime()
            if (status !in 200..299) {
                throw ProviderHttpException(status, response.take(512))
            }
            HttpResult(
                body = response,
                requestToHeadersMs = (headersAt - started) / 1_000_000,
                responseHeadersMs = (headersAt - started) / 1_000_000,
                downloadMs = (completedAt - headersAt) / 1_000_000
            ).also { responseConsumed = true }
        } finally {
            // Fully consumed responses return the socket to HttpURLConnection's keep-alive pool.
            // Failed or partial responses must be disconnected to avoid leaking the connection.
            if (!responseConsumed) connection.disconnect()
        }
    }

    private fun configuredProxy(value: String): Proxy {
        val uri = URI(normalizeProxyUrl(value))
        val type = when (uri.scheme.lowercase()) {
            "http" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> error("Unsupported proxy scheme")
        }
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }

    private fun normalizeGeminiCompletion(raw: String): String {
        val response = JSONObject(raw)
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IllegalStateException("Gemini response contains no candidates")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini response contains no content parts")
        val content = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }.trim()
        if (content.isEmpty()) throw IllegalStateException("Gemini response contains no answer text")
        val nativeUsage = response.optJSONObject("usageMetadata") ?: JSONObject()
        val usage = JSONObject()
            .put("prompt_tokens", nativeUsage.optLongOrNull("promptTokenCount"))
            .put("completion_tokens", nativeUsage.optLongOrNull("candidatesTokenCount"))
            .put("total_tokens", nativeUsage.optLongOrNull("totalTokenCount"))
            .put(
                "completion_tokens_details",
                JSONObject().put(
                    "reasoning_tokens",
                    nativeUsage.optLongOrNull("thoughtsTokenCount")
                )
            )
        return JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("finish_reason", candidate.optString("finishReason", "STOP").lowercase())
                        .put("message", JSONObject().put("content", content))
                )
            )
            .put("usage", usage)
            .toString()
    }

    private fun enqueueAudit(
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
        val body = JSONObject()
            .put("request", JSONObject(requestJson))
            .put("prepared", JSONObject(prepared.toString()))
            .put("modelRequest", JSONObject(aiBody.toString()))
            .put("modelResponse", JSONObject(completion))
            .put("response", JSONObject(response.toString()))
            .put("provider", provider)
            .put("model", model)
            .put("thinkingControlMode", thinkingMode.name)
            .put("thinkingLevel", thinkingLevel)
            .put("durationMs", totalMs)
            .put("timings", JSONObject(timings.toString()))
        auditScope.launch {
            runCatching {
            val auditStarted = System.nanoTime()
            postJson(URL(baseUrl + "/api/v4/edge-audits"), body.toString(), "")
            Log.d(TAG, "Edge audit upload completed in ${(System.nanoTime() - auditStarted) / 1_000_000}ms")
            }.onFailure { Log.d(TAG, "Optional LAN edge audit unavailable: ${it.message}") }
        }
    }

    private fun completionFromCache(
        prepared: JSONObject,
        directStructuredOutput: Boolean
    ): String? = runCatching {
        val groups = prepared.getJSONArray("actionableGroups")
        val cacheNamespace = preparedCacheNamespace(prepared)
        val translations = JSONArray()
        synchronized(translationCache) {
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                val cached = translationCache[
                    semanticCacheKey(group, directStructuredOutput, cacheNamespace)
                ] ?: return null
                translations.put(JSONObject(cached.toString()).put("groupId", group.getString("groupId")))
            }
        }
        completionEnvelope(translations)
    }.getOrNull()

    private fun cacheCompletion(
        prepared: JSONObject,
        completion: String,
        directStructuredOutput: Boolean
    ) = runCatching {
        val groups = prepared.getJSONArray("actionableGroups")
        val cacheNamespace = preparedCacheNamespace(prepared)
        val byId = completionTranslations(completion).associateBy { item ->
            completionTranslationId(item)
                ?: error("Translation item has no groupId or compact id")
        }
        synchronized(translationCache) {
            for (index in 0 until groups.length()) {
                val group = groups.getJSONObject(index)
                val item = byId[group.getString("groupId")] ?: continue
                translationCache[semanticCacheKey(group, directStructuredOutput, cacheNamespace)] =
                    JSONObject(item.toString()).apply {
                        remove("groupId")
                        remove("id")
                    }
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

    private fun semanticCacheKey(
        group: JSONObject,
        directStructuredOutput: Boolean,
        cacheNamespace: String
    ): String {
        val material = listOf(
            provider, model, group.optString("sourceLanguage", "auto"),
            group.optString("targetLanguage", "auto"), directStructuredOutput.toString(),
            thinkingMode.name, thinkingLevel, cacheNamespace, group.optString("role"),
            group.getString("sourceText").trim()
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun preparedCacheNamespace(prepared: JSONObject): String {
        val translation = prepared.optJSONObject("request")?.optJSONObject("translation")
        return listOf(
            prepared.optString("promptVersion", "unknown"),
            translation?.optString("mode", "unknown").orEmpty(),
            translation?.optBoolean("compactProviderPrompt", true).toString()
        ).joinToString(":")
    }

    private fun providerLatencyCheckpoint(completion: String): Any = runCatching {
        val usage = JSONObject(completion).optJSONObject("usage") ?: return@runCatching JSONObject.NULL
        usage.optJSONObject("service_tier_details")?.opt("latency_checkpoint")
            ?: usage.opt("latency_checkpoint") ?: JSONObject.NULL
    }.getOrDefault(JSONObject.NULL)

    private fun providerUsage(completion: String): JSONObject = runCatching {
        JSONObject(completion).optJSONObject("usage") ?: JSONObject()
    }.getOrDefault(JSONObject())

    override fun close() {
        auditScope.cancel()
        codec.close()
    }

    private data class HttpResult(
        val body: String,
        val requestToHeadersMs: Long,
        val responseHeadersMs: Long,
        val downloadMs: Long,
        val thinkingParameterFallback: Boolean = false,
        val retryCount: Int = 0,
        val retryDelayMs: Long = 0L
    )

    private class ProviderHttpException(val status: Int, response: String) :
        IllegalStateException("AI provider returned HTTP $status: $response")

    private companion object {
        const val TAG = "EmbeddedTranslation"
        const val MAX_CACHE_ENTRIES = 256

        fun isGemini3Model(model: String): Boolean = model.lowercase().startsWith("gemini-3")

        fun JSONObject.optLongOrNull(name: String): Any =
            if (has(name) && !isNull(name)) optLong(name) else JSONObject.NULL
        val translationCache = object : LinkedHashMap<String, JSONObject>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    }
}
