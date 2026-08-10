package com.example.imagetranslate.translate

import android.content.Context
import com.example.imagetranslate.BuildConfig
import java.net.URI

private const val NETWORK_REGIONS_PATH = "/api/v1/translate/regions"
internal const val SELF_HOSTED_LAYOUT_PLAN_PATH = "/api/v3/translate/layout-plan"
internal const val SELF_HOSTED_REGIONS_FIRST_PATH = "/api/v4/translate/layout-plan"
private const val LEGACY_SELF_HOSTED_GROUPS_PATH = "/api/v2/translate/groups"
private val SUPPORTED_NETWORK_ENDPOINT_PATHS = listOf(
    NETWORK_REGIONS_PATH,
    "/api/v1/translation-batches",
    SELF_HOSTED_LAYOUT_PLAN_PATH,
    SELF_HOSTED_REGIONS_FIRST_PATH,
    LEGACY_SELF_HOSTED_GROUPS_PATH
)

object TranslationBackendSettings {
    private const val PREFERENCES = "translation_backend"
    private const val BACKEND = "backend"
    private const val NETWORK_BASE_URL = "network_base_url"
    private const val SELF_HOSTED_BASE_URL = "self_hosted_base_url"
    private const val SELF_HOSTED_BEARER_TOKEN = "self_hosted_bearer_token"
    private const val DEBUG_CAPTURE_UPLOAD = "debug_capture_upload"
    private const val DEBUG_RENDERED_CAPTURE_UPLOAD = "debug_rendered_capture_upload"

    fun networkBaseUrl(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(NETWORK_BASE_URL, null)
        ?.takeIf(String::isNotBlank)
        ?: BuildConfig.REMOTE_TRANSLATION_BASE_URL.trim().trimEnd('/')

    fun networkBatchEndpoint(context: Context): String = networkBaseUrl(context)
        .takeIf(String::isNotBlank)
        ?.plus(NETWORK_REGIONS_PATH)
        .orEmpty()

    fun isNetworkConfigured(context: Context): Boolean = networkBaseUrl(context).isNotBlank()

    fun selfHostedBaseUrl(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(SELF_HOSTED_BASE_URL, null)
        ?.takeIf(String::isNotBlank)
        .orEmpty()

    fun selfHostedTranslationEndpoint(context: Context): String = selfHostedBaseUrl(context)
        .takeIf(String::isNotBlank)
        ?.plus(SELF_HOSTED_LAYOUT_PLAN_PATH)
        .orEmpty()

    fun selfHostedTranslationEndpoint(context: Context, backend: TranslationBackend): String =
        selfHostedBaseUrl(context).takeIf(String::isNotBlank)?.plus(
            if (backend == TranslationBackend.SELF_HOSTED_V4) {
                SELF_HOSTED_REGIONS_FIRST_PATH
            } else {
                SELF_HOSTED_LAYOUT_PLAN_PATH
            }
        ).orEmpty()

    fun selfHostedBearerToken(context: Context): String? = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(SELF_HOSTED_BEARER_TOKEN, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)

    fun isSelfHostedConfigured(context: Context): Boolean = selfHostedBaseUrl(context).isNotBlank()

    fun isDebugCaptureUploadEnabled(context: Context): Boolean = BuildConfig.DEBUG && context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(DEBUG_CAPTURE_UPLOAD, false)

    fun setDebugCaptureUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DEBUG_CAPTURE_UPLOAD, BuildConfig.DEBUG && enabled)
            .apply()
    }

    fun isDebugRenderedCaptureUploadEnabled(context: Context): Boolean = BuildConfig.DEBUG &&
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(DEBUG_RENDERED_CAPTURE_UPLOAD, false)

    fun setDebugRenderedCaptureUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DEBUG_RENDERED_CAPTURE_UPLOAD, BuildConfig.DEBUG && enabled)
            .apply()
    }

    fun isConfigured(context: Context, backend: TranslationBackend): Boolean = when (backend) {
        TranslationBackend.LOCAL -> true
        TranslationBackend.NETWORK -> isNetworkConfigured(context)
        TranslationBackend.SELF_HOSTED -> isSelfHostedConfigured(context)
        TranslationBackend.SELF_HOSTED_V4 -> true
    }

    fun setNetworkBaseUrl(context: Context, value: String) {
        val normalized = normalizeNetworkBaseUrl(value)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(NETWORK_BASE_URL, normalized)
            .apply()
    }

    fun setSelfHosted(context: Context, baseUrl: String, bearerToken: String) {
        val normalized = normalizeNetworkBaseUrl(baseUrl)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(SELF_HOSTED_BASE_URL, normalized)
            .putString(SELF_HOSTED_BEARER_TOKEN, bearerToken.trim())
            .apply()
    }

    fun get(context: Context): TranslationBackend {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(BACKEND, null)
        return resolveTranslationBackend(
            stored,
            isNetworkConfigured(context),
            isSelfHostedConfigured(context)
        )
    }

    fun set(context: Context, backend: TranslationBackend) {
        require(isConfigured(context, backend)) {
            "Selected translation endpoint is not configured"
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(BACKEND, backend.name)
            .apply()
    }
}

internal fun normalizeNetworkBaseUrl(value: String): String {
    val endpointOrBase = value.trim().trimEnd('/')
    val normalized = SUPPORTED_NETWORK_ENDPOINT_PATHS.fold(endpointOrBase) { current, path ->
        current.removeSuffix(path).trimEnd('/')
    }
    require(normalized.isNotBlank()) { "Remote translation endpoint cannot be empty" }
    val uri = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("Remote translation endpoint is invalid", it) }
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
        "Remote translation endpoint must be an HTTP or HTTPS URL"
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Remote translation endpoint cannot contain credentials, query, or fragment"
    }
    return normalized
}

internal fun resolveTranslationBackend(
    storedValue: String?,
    networkConfigured: Boolean,
    selfHostedConfigured: Boolean = false
): TranslationBackend {
    val selected = storedValue
        ?.let { runCatching { TranslationBackend.valueOf(it) }.getOrNull() }
        ?: TranslationBackend.LOCAL
    return selected.takeIf { backend ->
        when (backend) {
            TranslationBackend.LOCAL -> true
            TranslationBackend.NETWORK -> networkConfigured
            TranslationBackend.SELF_HOSTED -> selfHostedConfigured
            TranslationBackend.SELF_HOSTED_V4 -> true
        }
    } ?: TranslationBackend.LOCAL
}
