package com.example.v4translation

import org.json.JSONObject

data class EmbeddedV4Config(
    val baseUrl: String,
    val apiKey: String,
    val model: String = "qwen3.5-plus",
    val reasoningEffort: String? = "none",
    val maxTokens: Int = 4096,
    val timeoutSeconds: Long = 210
) {
    init {
        require(baseUrl.startsWith("https://")) { "The embedded v4 upstream must use HTTPS" }
        require(apiKey.isNotBlank()) { "The embedded v4 API key must be supplied at runtime" }
    }

    internal fun toJson(): String = JSONObject()
        .put("qwenBaseUrl", baseUrl)
        .put("qwenApiKey", apiKey)
        .put("qwenModel", model)
        .put("qwenReasoningEffort", reasoningEffort)
        .put("qwenMaxTokens", maxTokens)
        .put("qwenTimeoutSeconds", timeoutSeconds)
        .toString()
}

class EmbeddedV4TranslationService(private val config: EmbeddedV4Config) {
    /** Executes a schema-v4 request. Call this blocking method from a worker dispatcher. */
    fun translate(requestJson: String): String {
        val envelope = JSONObject(nativeTranslate(config.toJson(), requestJson))
        if (!envelope.optBoolean("ok")) {
            throw EmbeddedV4TranslationException(
                envelope.optString("error", "Embedded v4 translation failed")
            )
        }
        return envelope.getJSONObject("response").toString()
    }

    private external fun nativeTranslate(configJson: String, requestJson: String): String

    companion object {
        init {
            System.loadLibrary("image_translate_v4_service")
        }
    }
}

class EmbeddedV4TranslationException(message: String) : RuntimeException(message)
