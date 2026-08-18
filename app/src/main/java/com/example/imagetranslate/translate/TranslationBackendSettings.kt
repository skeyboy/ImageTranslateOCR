package com.example.imagetranslate.translate

import android.content.Context
import com.example.imagetranslate.BuildConfig
import java.net.URI

private const val NETWORK_REGIONS_PATH = "/api/v1/translate/regions"
internal const val SELF_HOSTED_LAYOUT_PLAN_PATH = "/api/v3/translate/layout-plan"
internal const val SELF_HOSTED_REGIONS_FIRST_PATH = "/api/v4/translate/layout-plan"
internal const val SERVER_GEMINI_REGIONS_FIRST_PATH =
    "/api/v4/translate/gemini-native/layout-plan"
private const val LEGACY_SELF_HOSTED_GROUPS_PATH = "/api/v2/translate/groups"
private val SUPPORTED_NETWORK_ENDPOINT_PATHS = listOf(
    NETWORK_REGIONS_PATH,
    "/api/v1/translation-batches",
    SELF_HOSTED_LAYOUT_PLAN_PATH,
    SELF_HOSTED_REGIONS_FIRST_PATH,
    SERVER_GEMINI_REGIONS_FIRST_PATH,
    LEGACY_SELF_HOSTED_GROUPS_PATH
)

object TranslationBackendSettings {
    const val PRIMARY_EDGE_PROVIDER = "google"
    const val PRIMARY_EDGE_MODEL = "gemini-3.5-flash-lite"
    const val PRIMARY_EDGE_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_EDGE_THINKING_LEVEL = "medium"
    const val OPENLUX_EDGE_PROVIDER = "openlux"
    const val DEFAULT_OPENLUX_BASE_URL = "https://api.openlux.ai/v1"
    const val OPENAI_OXIDE_EDGE_PROVIDER = "openai-oxide"
    const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_OPENAI_MODEL = "gpt-4.1-mini"
    val edgeProviders: List<String> = listOf(
        PRIMARY_EDGE_PROVIDER,
        OPENLUX_EDGE_PROVIDER,
        OPENAI_OXIDE_EDGE_PROVIDER
    )
    private val SELECTABLE_THINKING_LEVELS = linkedSetOf("minimal", "low", "medium", "high")
    val thinkingLevels: List<String> = SELECTABLE_THINKING_LEVELS.toList()
    private const val PREFERENCES = "translation_backend"
    private const val BACKEND = "backend"
    private const val NETWORK_BASE_URL = "network_base_url"
    private const val SELF_HOSTED_BASE_URL = "self_hosted_base_url"
    private const val SELF_HOSTED_BEARER_TOKEN = "self_hosted_bearer_token"
    private const val DEBUG_CAPTURE_UPLOAD = "debug_capture_upload"
    private const val DEBUG_RENDERED_CAPTURE_UPLOAD = "debug_rendered_capture_upload"
    private const val REQUEST_ARCHIVE_EXPORT = "request_archive_export"
    private const val DIRECT_STRUCTURED_OUTPUT = "direct_structured_output"
    private const val COMPACT_PROVIDER_PROMPT = "compact_provider_prompt"
    private const val EDGE_PROVIDER = "edge_provider"
    private const val EDGE_MODEL = "edge_model"
    private const val OPENLUX_EDGE_API_KEY = "openlux_edge_api_key"
    private const val OPENLUX_EDGE_BASE_URL = "openlux_edge_base_url"
    private const val OPENLUX_EDGE_MODELS = "openlux_edge_models"
    private const val EDGE_THINKING_LEVEL = "edge_thinking_level"
    private const val SERVER_GEMINI_ENABLED = "server_gemini_enabled"
    private const val SERVER_GEMINI_BASE_URL = "server_gemini_base_url"
    private const val SERVER_GEMINI_V4_MIGRATED = "server_gemini_v4_migrated"
    private const val OPENAI_OXIDE_ENABLED = "openai_oxide_enabled"
    private const val OPENAI_OXIDE_BASE_URL = "openai_oxide_base_url"
    private const val OPENAI_OXIDE_API_KEY = "openai_oxide_api_key"
    private const val OPENAI_OXIDE_MODEL = "openai_oxide_model"
    @Volatile private var sessionEdgeProvider = BuildConfig.EDGE_AI_PROVIDER
        .trim().lowercase().takeIf { it in edgeProviders } ?: PRIMARY_EDGE_PROVIDER
    @Volatile private var sessionEdgeModel = PRIMARY_EDGE_MODEL
    @Volatile private var sessionThinkingLevel = BuildConfig.EDGE_AI_THINKING_LEVEL
        .trim().lowercase().takeIf { it in SELECTABLE_THINKING_LEVELS }
        ?: DEFAULT_EDGE_THINKING_LEVEL
    @Volatile private var sessionProxyUrl = normalizeProxyUrl(BuildConfig.EDGE_AI_PROXY_URL)
    @Volatile private var sessionAuditBaseUrl = ""

    fun migratePrimaryConfiguration(context: Context) {
        val packagedBaseUrl = BuildConfig.DEMO_SERVER_BASE_URL.trim()
        if (packagedBaseUrl.isBlank()) return
        val normalized = normalizeNetworkBaseUrl(packagedBaseUrl)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(SERVER_GEMINI_V4_MIGRATED, false)) return
        preferences.edit()
            .putBoolean(SERVER_GEMINI_ENABLED, true)
            .putString(SERVER_GEMINI_BASE_URL, normalized)
            .putBoolean(DEBUG_CAPTURE_UPLOAD, BuildConfig.DEBUG)
            .putBoolean(DEBUG_RENDERED_CAPTURE_UPLOAD, BuildConfig.DEBUG)
            .putBoolean(SERVER_GEMINI_V4_MIGRATED, true)
            .apply()
    }

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

    fun isServerGeminiEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(SERVER_GEMINI_ENABLED, serverGeminiBaseUrl(context).isNotBlank()) &&
        serverGeminiBaseUrl(context).isNotBlank()

    fun serverGeminiBaseUrl(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(SERVER_GEMINI_BASE_URL, null)
        ?.takeIf(String::isNotBlank)
        ?: BuildConfig.DEMO_SERVER_BASE_URL.trim().trimEnd('/').takeIf(String::isNotBlank)
        ?: selfHostedBaseUrl(context)

    fun serverGeminiTranslationEndpoint(context: Context): String = serverGeminiBaseUrl(context)
        .takeIf(String::isNotBlank)
        ?.plus(SERVER_GEMINI_REGIONS_FIRST_PATH)
        .orEmpty()

    fun setServerGemini(context: Context, enabled: Boolean, baseUrl: String) {
        val normalized = if (enabled || baseUrl.isNotBlank()) {
            normalizeNetworkBaseUrl(baseUrl)
        } else {
            ""
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SERVER_GEMINI_ENABLED, enabled)
            .putString(SERVER_GEMINI_BASE_URL, normalized)
            .apply()
    }

    fun edgeProvider(context: Context): String = if (isOpenAiOxideEnabled(context)) {
        OPENAI_OXIDE_EDGE_PROVIDER
    } else context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(EDGE_PROVIDER, null)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in edgeProviders }
        ?: sessionEdgeProvider

    fun edgeBaseUrl(context: Context): String = when (edgeProvider(context)) {
        OPENAI_OXIDE_EDGE_PROVIDER -> openAiOxideBaseUrl(context)
        OPENLUX_EDGE_PROVIDER -> context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(OPENLUX_EDGE_BASE_URL, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: normalizeOpenAiBaseUrl(
                BuildConfig.OPENLUX_BASE_URL.trim().ifBlank { DEFAULT_OPENLUX_BASE_URL }
            )
        else -> PRIMARY_EDGE_BASE_URL
    }

    fun edgeApiKey(context: Context): String = when (edgeProvider(context)) {
        OPENAI_OXIDE_EDGE_PROVIDER -> openAiOxideApiKey(context)
        OPENLUX_EDGE_PROVIDER -> context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(OPENLUX_EDGE_API_KEY, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: BuildConfig.OPENLUX_API_KEY.trim()
        else -> BuildConfig.EDGE_AI_API_KEY.trim()
    }

    fun hasPackagedEdgeApiKey(): Boolean = BuildConfig.EDGE_AI_API_KEY.isNotBlank()

    fun edgeModels(context: Context): List<String> = when (edgeProvider(context)) {
        OPENAI_OXIDE_EDGE_PROVIDER -> listOf(openAiOxideModel(context))
        OPENLUX_EDGE_PROVIDER -> context
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(OPENLUX_EDGE_MODELS, null)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.OPENLUX_MODELS
                .split(',').map(String::trim).filter(String::isNotEmpty).distinct()
                .ifEmpty { listOf(PRIMARY_EDGE_MODEL) }
        else -> listOf(PRIMARY_EDGE_MODEL)
    }

    fun edgeModel(context: Context): String {
        val models = edgeModels(context)
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(EDGE_MODEL, null)
            ?.trim()
        return stored?.takeIf { it in models }
            ?: sessionEdgeModel.takeIf { it in models }
            ?: models.first()
    }

    fun setEdgeProvider(context: Context, provider: String) {
        val normalized = provider.trim().lowercase()
        require(normalized in edgeProviders) { "Unsupported embedded provider" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(OPENAI_OXIDE_ENABLED, normalized == OPENAI_OXIDE_EDGE_PROVIDER)
            .apply()
        sessionEdgeProvider = normalized
        sessionEdgeModel = edgeModels(context).first()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(EDGE_PROVIDER, sessionEdgeProvider)
            .putString(EDGE_MODEL, sessionEdgeModel)
            .apply()
    }

    fun setEdgeModel(context: Context, model: String) {
        require(model in edgeModels(context)) { "Selected edge model is not configured" }
        sessionEdgeModel = model
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(EDGE_MODEL, model)
            .apply()
    }

    fun isDirectStructuredOutputEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    fun setDirectStructuredOutputEnabled(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") enabled: Boolean
    ) = Unit

    fun isCompactProviderPromptEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    fun setCompactProviderPromptEnabled(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") enabled: Boolean
    ) = Unit

    internal fun edgeThinkingControlMode(context: Context): ProviderThinkingControlMode {
        return ProviderThinkingControlMode.THINKING_LEVEL
    }

    internal fun setEdgeThinkingControlMode(
        @Suppress("UNUSED_PARAMETER") context: Context,
        mode: ProviderThinkingControlMode
    ) {
        require(mode == ProviderThinkingControlMode.THINKING_LEVEL) {
            "Gemini uses thinkingLevel"
        }
    }

    internal fun effectiveEdgeThinkingControlMode(
        context: Context,
        model: String
    ): ProviderThinkingControlMode {
        val selected = edgeThinkingControlMode(context)
        val normalizedModel = model.trim().lowercase()
        return when {
            normalizedModel.startsWith("gpt-4.1") -> ProviderThinkingControlMode.NONE
            normalizedModel.startsWith("gemini-3") -> selected
            selected == ProviderThinkingControlMode.THINKING_LEVEL ->
                ProviderThinkingControlMode.NONE
            else -> selected
        }
    }

    fun edgeThinkingLevel(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(EDGE_THINKING_LEVEL, null)
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in SELECTABLE_THINKING_LEVELS }
        ?: sessionThinkingLevel

    fun setEdgeThinkingLevel(context: Context, level: String) {
        val normalized = level.trim().lowercase()
        require(normalized in SELECTABLE_THINKING_LEVELS) {
            "Thinking level must be minimal, low, medium, or high"
        }
        sessionThinkingLevel = normalized
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(EDGE_THINKING_LEVEL, normalized)
            .apply()
    }

    fun edgeReasoningEffort(context: Context, @Suppress("UNUSED_PARAMETER") model: String): String? =
        edgeThinkingLevel(context).takeIf {
            edgeThinkingControlMode(context) == ProviderThinkingControlMode.REASONING_EFFORT
        }

    fun edgeProxyUrl(@Suppress("UNUSED_PARAMETER") context: Context): String = sessionProxyUrl

    fun setEdgeProxyUrl(context: Context, value: String) {
        val normalized = normalizeProxyUrl(value)
        sessionProxyUrl = normalized
    }

    fun isEdgeConfigured(context: Context): Boolean = edgeBaseUrl(context).isNotBlank() &&
        edgeApiKey(context).isNotBlank() && edgeModels(context).isNotEmpty()

    fun isOpenAiOxideEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(OPENAI_OXIDE_ENABLED, false)

    fun openAiOxideBaseUrl(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(OPENAI_OXIDE_BASE_URL, null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf(String::isNotEmpty)
        ?: DEFAULT_OPENAI_BASE_URL

    fun openAiOxideApiKey(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(OPENAI_OXIDE_API_KEY, null)
        ?.trim()
        .orEmpty()

    fun openAiOxideModel(context: Context): String = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(OPENAI_OXIDE_MODEL, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: DEFAULT_OPENAI_MODEL

    fun isOpenAiOxideConfigured(context: Context): Boolean =
        isOpenAiOxideEnabled(context) &&
            openAiOxideBaseUrl(context).isNotBlank() &&
            openAiOxideApiKey(context).isNotBlank() &&
            openAiOxideModel(context).isNotBlank()

    fun setOpenAiOxideConfiguration(
        context: Context,
        enabled: Boolean,
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val normalizedBaseUrl = normalizeOpenAiBaseUrl(baseUrl.ifBlank { DEFAULT_OPENAI_BASE_URL })
        val normalizedModel = model.trim().ifBlank { DEFAULT_OPENAI_MODEL }
        val normalizedKey = apiKey.trim().ifBlank {
            preferences.getString(OPENAI_OXIDE_API_KEY, null)?.trim().orEmpty()
        }
        if (enabled) {
            require(normalizedKey.isNotEmpty()) { "OpenAI API key cannot be empty" }
        }
        preferences.edit()
            .putBoolean(OPENAI_OXIDE_ENABLED, enabled)
            .putString(OPENAI_OXIDE_BASE_URL, normalizedBaseUrl)
            .putString(OPENAI_OXIDE_MODEL, normalizedModel)
            .apply {
                if (normalizedKey.isNotEmpty()) putString(OPENAI_OXIDE_API_KEY, normalizedKey)
            }
            .apply()
        if (enabled) {
            sessionEdgeProvider = OPENAI_OXIDE_EDGE_PROVIDER
            sessionEdgeModel = normalizedModel
        }
    }

    fun isServerGeminiConfigured(context: Context): Boolean =
        serverGeminiBaseUrl(context).isNotBlank()

    fun setEdgeConfiguration(
        context: Context,
        provider: String,
        baseUrl: String,
        apiKey: String,
        models: String
    ) {
        val normalizedProvider = provider.trim().lowercase()
        require(normalizedProvider in edgeProviders) { "Unsupported embedded provider" }
        if (normalizedProvider == OPENAI_OXIDE_EDGE_PROVIDER) {
            setOpenAiOxideConfiguration(
                context = context,
                enabled = true,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = models.split(',').firstOrNull().orEmpty()
            )
            return
        }
        val normalizedBaseUrl = if (normalizedProvider == OPENLUX_EDGE_PROVIDER) {
            normalizeOpenAiBaseUrl(baseUrl.ifBlank { DEFAULT_OPENLUX_BASE_URL })
        } else {
            normalizeNetworkBaseUrl(baseUrl)
        }
        val normalizedModels = models.split(',').map(String::trim)
            .filter(String::isNotEmpty).distinct()
        require(apiKey.trim().isNotEmpty() || edgeApiKey(context).isNotEmpty()) {
            "AI provider API key cannot be empty"
        }
        require(normalizedModels.isNotEmpty()) { "At least one AI model is required" }
        sessionEdgeProvider = normalizedProvider
        sessionEdgeModel = normalizedModels.first()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(EDGE_PROVIDER, sessionEdgeProvider)
            .putString(EDGE_MODEL, sessionEdgeModel)
            .apply {
                if (normalizedProvider == OPENLUX_EDGE_PROVIDER && apiKey.isNotBlank()) {
                    putString(OPENLUX_EDGE_API_KEY, apiKey.trim())
                }
                if (normalizedProvider == OPENLUX_EDGE_PROVIDER) {
                    putString(OPENLUX_EDGE_BASE_URL, normalizedBaseUrl)
                    putString(OPENLUX_EDGE_MODELS, normalizedModels.joinToString(","))
                }
            }
            .apply()
    }

    fun edgeAuditBaseUrl(context: Context): String = when {
        isServerGeminiEnabled(context) -> serverGeminiBaseUrl(context)
        sessionAuditBaseUrl.isNotBlank() -> sessionAuditBaseUrl
        else -> selfHostedBaseUrl(context)
    }

    fun setEdgeAuditBaseUrl(context: Context, value: String) {
        val normalized = value.trim().takeIf(String::isNotEmpty)?.let(::normalizeNetworkBaseUrl).orEmpty()
        sessionAuditBaseUrl = normalized
    }

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

    fun isRequestArchiveExportEnabled(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(REQUEST_ARCHIVE_EXPORT, false)

    fun setRequestArchiveExportEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(REQUEST_ARCHIVE_EXPORT, enabled)
            .apply()
    }

    fun isConfigured(context: Context, backend: TranslationBackend): Boolean = when (backend) {
        TranslationBackend.LOCAL -> true
        TranslationBackend.NETWORK -> isNetworkConfigured(context)
        TranslationBackend.SELF_HOSTED -> isSelfHostedConfigured(context)
        TranslationBackend.SELF_HOSTED_V4 -> isSelfHostedConfigured(context)
        TranslationBackend.EMBEDDED_V4 ->
            isServerGeminiEnabled(context) || isOpenAiOxideConfigured(context) ||
                isEdgeConfigured(context)
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

    fun get(@Suppress("UNUSED_PARAMETER") context: Context): TranslationBackend =
        TranslationBackend.EMBEDDED_V4

    fun set(context: Context, backend: TranslationBackend) {
        require(isConfigured(context, backend)) {
            "Selected translation endpoint is not configured"
        }
        require(backend == TranslationBackend.EMBEDDED_V4) {
            "The packaged app uses the embedded V4 translation backend"
        }
    }
}

internal fun normalizeOpenAiBaseUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
        .removeSuffix("/chat/completions")
        .trimEnd('/')
    val uri = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("OpenAI base URL is invalid", it) }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
        "OpenAI base URL must use HTTPS"
    }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "OpenAI base URL cannot contain credentials, query, or fragment"
    }
    return normalized
}

internal fun normalizeProxyUrl(value: String): String {
    val normalized = value.trim().trimEnd('/')
    if (normalized.isEmpty()) return ""
    val uri = runCatching { URI(normalized) }
        .getOrElse { throw IllegalArgumentException("Proxy address is invalid", it) }
    require(uri.scheme in setOf("http", "socks", "socks5") && !uri.host.isNullOrBlank()) {
        "Proxy must use http://, socks://, or socks5://"
    }
    require(uri.port in 1..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Proxy must include a valid port and cannot contain credentials, query, or fragment"
    }
    return normalized
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
    selfHostedConfigured: Boolean = false,
    embeddedConfigured: Boolean = false
): TranslationBackend {
    val selected = storedValue
        ?.let { runCatching { TranslationBackend.valueOf(it) }.getOrNull() }
        ?: TranslationBackend.LOCAL
    return selected.takeIf { backend ->
        when (backend) {
            TranslationBackend.LOCAL -> true
            TranslationBackend.NETWORK -> networkConfigured
            TranslationBackend.SELF_HOSTED -> selfHostedConfigured
            TranslationBackend.SELF_HOSTED_V4 -> selfHostedConfigured
            TranslationBackend.EMBEDDED_V4 -> embeddedConfigured
        }
    } ?: TranslationBackend.LOCAL
}
