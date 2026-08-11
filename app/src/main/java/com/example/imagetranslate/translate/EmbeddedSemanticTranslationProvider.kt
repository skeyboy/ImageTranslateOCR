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
            .put("max_tokens", 8192)
            .put("response_format", prompt.getJSONObject("responseFormat"))
        val started = System.nanoTime()
        val completion = postJson(endpoint, aiBody.toString(), apiKey)
        val totalMs = (System.nanoTime() - started) / 1_000_000
        val response = NativeEdgeTranslationBridge.complete(prepared.toString(), completion, totalMs)
        auditBestEffort(requestJson, prepared, aiBody, completion, response, totalMs)
        return codec.parseResponseForTest(response.toString(), request)
    }

    private suspend fun postJson(url: URL, body: String, bearer: String): String = withContext(Dispatchers.IO) {
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
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) { "AI provider returned HTTP ${connection.responseCode}: ${response.take(512)}" }
            response
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
        totalMs: Long
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
            postJson(URL(baseUrl + "/api/v4/edge-audits"), body.toString(), "")
        }.onFailure { Log.d(TAG, "Optional LAN edge audit unavailable: ${it.message}") }
    }

    override fun close() = codec.close()

    private companion object { const val TAG = "EmbeddedTranslation" }
}
