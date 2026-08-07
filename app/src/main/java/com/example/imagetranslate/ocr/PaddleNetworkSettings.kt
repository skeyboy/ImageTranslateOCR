package com.example.imagetranslate.ocr

import android.content.Context
import java.net.URI

internal const val PADDLE_OCR_TRANSLATION_PATH = "/api/v1/ocr-translations"

internal data class PaddleNetworkConfiguration(
    val baseUrl: String,
    val bearerToken: String
) {
    val endpoint: String
        get() = baseUrl.takeIf(String::isNotBlank)
            ?.plus(PADDLE_OCR_TRANSLATION_PATH)
            .orEmpty()

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()
}

internal object PaddleNetworkSettings {
    private const val PREFERENCES = "paddle_network_service"
    private const val BASE_URL = "base_url"
    private const val BEARER_TOKEN = "bearer_token"

    fun get(context: Context): PaddleNetworkConfiguration {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return PaddleNetworkConfiguration(
            baseUrl = preferences.getString(BASE_URL, null).orEmpty(),
            bearerToken = preferences.getString(BEARER_TOKEN, null).orEmpty()
        )
    }

    fun isConfigured(context: Context): Boolean = get(context).isConfigured

    fun set(context: Context, baseUrl: String, bearerToken: String) {
        val normalizedBaseUrl = normalizePaddleNetworkBaseUrl(baseUrl)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(BASE_URL, normalizedBaseUrl)
            .putString(BEARER_TOKEN, bearerToken.trim())
            .apply()
    }
}

internal fun normalizePaddleNetworkBaseUrl(value: String): String {
    val endpointOrBase = value.trim().trimEnd('/')
    if (endpointOrBase.isBlank()) return ""
    val normalized = endpointOrBase.removeSuffix(PADDLE_OCR_TRANSLATION_PATH).trimEnd('/')
    val uri = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("PaddleOCR service URL is invalid", it) }
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "PaddleOCR service must be an HTTP or HTTPS URL"
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "PaddleOCR service URL cannot contain credentials, query, or fragment"
    }
    return normalized
}
