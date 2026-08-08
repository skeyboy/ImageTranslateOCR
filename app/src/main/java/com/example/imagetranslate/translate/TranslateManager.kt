package com.example.imagetranslate.translate

import android.content.Context
import android.util.Log
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import com.example.experimentaltranslation.ExperimentalTranslationLibrary
import com.example.experimentaltranslation.ExperimentalTranslationRequest
import com.example.experimentaltranslation.TranslationLanguage
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TranslationMode {
    CHINESE_TO_ENGLISH,
    ENGLISH_TO_CHINESE,
    AUTO_BIDIRECTIONAL
}

internal data class TranslationExecutionResult(
    val regionId: String,
    val sourceGroupIds: List<String> = listOf(regionId),
    val memberRegionIds: List<String> = emptyList(),
    val anchorBounds: TranslationBounds? = null,
    val translatedText: String,
    val provider: String,
    val succeeded: Boolean,
    val failure: TranslationFailure? = null,
    val layoutHint: SemanticLayoutHint? = null,
    val semanticTrace: SemanticTranslationTrace? = null
)

internal class TranslateManager(context: Context? = null) {
    private companion object {
        const val TAG = "ExperimentalTranslate"
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 60_000L
        const val TRANSLATION_TIMEOUT_MS = 20_000L
        const val MAXIMUM_DOCUMENT_CONTEXT_CHARACTERS = 12_000
    }

    private val translators = mutableMapOf<Pair<String, String>, Translator>()
    private val downloadedModels = mutableSetOf<Pair<String, String>>()
    private val modelDownloadMutex = Mutex()
    private val experimentalLibraryDelegate = context?.applicationContext?.let { appContext ->
        lazy { ExperimentalTranslationLibrary(appContext) }
    }
    private val appContext = context?.applicationContext
    private val remoteProviderLock = Any()
    private var remoteProviderBaseUrl: String? = null
    private var remoteProvider: RemoteTranslationProvider? = null
    private val selfHostedProviderLock = Any()
    private var selfHostedProviderConfiguration: Pair<String, String?>? = null
    private var selfHostedProvider: SelfHostedSemanticTranslationProvider? = null
    private val semanticSessionId = UUID.randomUUID().toString()
    private val semanticGeneration = AtomicLong(0L)
    private val localProvider = LocalTranslationProvider(translateOne = ::translateLocally)
    private val switchingProvider = SwitchingTranslationProvider(
        selectedBackend = {
            appContext?.let(TranslationBackendSettings::get)
                ?.takeUnless { it == TranslationBackend.SELF_HOSTED }
                ?: TranslationBackend.LOCAL
        },
        localProvider = localProvider,
        networkProvider = appContext?.let { { remoteProviderForCurrentSettings() } },
        resultValidator = ::isValidProviderResult,
        onNetworkFallback = { failures ->
            Log.w(
                TAG,
                "Network translation fallback: count=${failures.size}, " +
                    "codes=${failures.map(TranslationFailure::code).distinct()}"
            )
        }
    )

    val areModelsReady: Boolean
        get() = (TranslateLanguage.CHINESE to TranslateLanguage.ENGLISH) in downloadedModels &&
            (TranslateLanguage.ENGLISH to TranslateLanguage.CHINESE) in downloadedModels

    suspend fun downloadModelIfNeeded(): Boolean {
        ensureModel(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)
        ensureModel(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE)
        return true
    }

    suspend fun translate(
        text: String,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        experimentalEngine: ExperimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED
    ): String {
        val result = translateBatch(listOf(text), mode, experimentalEngine).single()
        if (!result.succeeded) {
            throw TranslationProviderException(
                result.failure
                    ?: TranslationFailure(
                        regionId = result.regionId,
                        code = "TRANSLATION_FAILED",
                        message = "Translation failed",
                        retryable = false
                    )
            )
        }
        return result.translatedText
    }

    suspend fun translateBatch(
        texts: List<String>,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        experimentalEngine: ExperimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED
    ): List<TranslationExecutionResult> {
        if (texts.isEmpty()) return emptyList()
        val requestId = UUID.randomUUID().toString()
        val prepared = texts.mapIndexed { index, text ->
            val inputText = sanitizeOcrText(text)
            val regionId = "region-$index-$requestId"
            val sourceLanguage = identifySourceLanguageByScript(inputText)
            val targetLanguage = sourceLanguage?.let { targetLanguageFor(it, mode) }
            val request = if (
                !shouldPreserveSourceText(inputText) &&
                sourceLanguage != null &&
                targetLanguage != null
            ) {
                TranslationRequest(
                    requestId = requestId,
                    regionId = regionId,
                    text = inputText,
                    mode = mode,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    experimentalEngine = experimentalEngine
                )
            } else {
                null
            }
            PreparedTranslation(regionId, inputText, request)
        }
        val actionable = prepared.mapNotNull(PreparedTranslation::request)
        val batch = switchingProvider.translateBatch(actionable)
        val resultsById = batch.results.associateBy(TranslationResult::regionId)
        val failuresById = batch.failures.associateBy(TranslationFailure::regionId)
        return prepared.map { item ->
            val request = item.request
            if (request == null) {
                TranslationExecutionResult(
                    regionId = item.regionId,
                    translatedText = item.inputText,
                    provider = "preserve",
                    succeeded = true
                )
            } else {
                val result = resultsById[item.regionId]
                if (result != null) {
                    TranslationExecutionResult(
                        regionId = item.regionId,
                        translatedText = result.translatedText.trim(),
                        provider = result.provider,
                        succeeded = true
                    )
                } else {
                    val failure = failuresById[item.regionId]
                        ?: TranslationFailure(
                            regionId = item.regionId,
                            code = "MISSING_RESULT",
                            message = "Translation provider returned no result",
                            retryable = false
                        )
                    TranslationExecutionResult(
                        regionId = item.regionId,
                        translatedText = item.inputText,
                        provider = "none",
                        succeeded = false,
                        failure = failure
                    )
                }
            }
        }
    }

    suspend fun translateSemanticGroups(
        sources: List<SemanticTranslationSource>,
        viewportWidth: Int,
        viewportHeight: Int,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        scene: String = "ANDROID_CLIENT",
        experimentalEngine: ExperimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED,
        debugCapture: SemanticDebugCapture? = null
    ): List<TranslationExecutionResult> {
        if (sources.isEmpty()) return emptyList()
        require(viewportWidth > 0 && viewportHeight > 0) {
            "Semantic translation viewport must be non-empty"
        }
        val backend = appContext?.let(TranslationBackendSettings::get) ?: TranslationBackend.LOCAL
        if (backend != TranslationBackend.SELF_HOSTED) {
            val translated = translateBatch(
                texts = sources.map(SemanticTranslationSource::sourceText),
                mode = mode,
                experimentalEngine = experimentalEngine
            )
            return sources.zip(translated).map { (source, result) ->
                result.copy(regionId = source.groupId)
            }
        }

        val requestId = UUID.randomUUID().toString()
        val preparedSources = sources
            .sortedBy(SemanticTranslationSource::readingOrder)
            .map { source -> source.preparedFor(mode, viewportWidth, viewportHeight) }
        val generation = semanticGeneration.incrementAndGet()
        val request = SemanticTranslationRequest(
            requestId = requestId,
            sessionId = semanticSessionId,
            generation = generation,
            translationRevision = mode.ordinal.toLong(),
            scene = scene,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            mode = mode,
            documentText = preparedSources.joinToString("\n") { source ->
                "[${source.role}] ${source.sourceText}"
            }.take(MAXIMUM_DOCUMENT_CONTEXT_CHARACTERS),
            sources = preparedSources,
            debugCapture = debugCapture
        )
        val semanticTrace = SemanticTranslationTrace(
            requestId = request.requestId,
            sessionId = request.sessionId,
            generation = request.generation,
            translationRevision = request.translationRevision
        )
        val remoteBatch = try {
            selfHostedProviderForCurrentSettings().translate(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SemanticTranslationBatchResult(
                results = emptyList(),
                failures = preparedSources.map { source ->
                    SemanticGroupTranslationFailure(
                        groupId = source.groupId,
                        code = "SELF_HOSTED_REQUEST_FAILED",
                        message = error.message ?: "Self-hosted translation failed",
                        retryable = true,
                        cause = error
                    )
                }
            )
        }
        val sourcesById = preparedSources.associateBy(SemanticTranslationSource::groupId)
        val acceptedRemote = remoteBatch.results.filter { result ->
            val resultSources = result.sourceGroupIds.mapNotNull(sourcesById::get)
            resultSources.size == result.sourceGroupIds.size &&
                isValidSemanticResult(resultSources, result)
        }
        val remotelyCoveredSourceIds = acceptedRemote
            .flatMap(SemanticGroupTranslationResult::sourceGroupIds)
            .toSet()
        val fallbackSources = preparedSources.filter { it.groupId !in remotelyCoveredSourceIds }
        val localFallbackSources = fallbackSources.filter { source ->
            SemanticFallbackPolicy.allowsLocalFallback(source, viewportWidth, viewportHeight)
        }
        val remoteRequiredSources = fallbackSources.filter { it !in localFallbackSources }
        if (fallbackSources.isNotEmpty()) {
            Log.w(
                TAG,
                "Self-hosted semantic fallback: count=${fallbackSources.size}, " +
                    "local=${localFallbackSources.size}, " +
                    "preservedLongBody=${remoteRequiredSources.size}, " +
                    "codes=${remoteBatch.failures.map { it.code }.distinct()}"
            )
        }
        val localFallback = translateSemanticLocally(
            localFallbackSources,
            mode,
            experimentalEngine,
            requestId
        ).associateBy(TranslationExecutionResult::regionId)
        val remoteFailures = remoteBatch.failures.associateBy(SemanticGroupTranslationFailure::groupId)
        val remoteExecutions = acceptedRemote.map { result ->
            val resultSources = result.sourceGroupIds.mapNotNull(sourcesById::get)
            TranslationExecutionResult(
                regionId = result.groupId,
                sourceGroupIds = result.sourceGroupIds,
                memberRegionIds = result.memberRegionIds,
                anchorBounds = result.anchorBounds,
                translatedText = result.translatedText
                    ?: resultSources.joinToString("\n") { it.sourceText },
                provider = result.provider,
                succeeded = true,
                layoutHint = result.layoutHint,
                semanticTrace = semanticTrace
            )
        }
        val fallbackExecutions = fallbackSources.map { source ->
            localFallback[source.groupId]?.copy(
                sourceGroupIds = listOf(source.groupId),
                memberRegionIds = source.memberRegionIds,
                anchorBounds = source.bounds
            ) ?: TranslationExecutionResult(
                regionId = source.groupId,
                sourceGroupIds = listOf(source.groupId),
                memberRegionIds = source.memberRegionIds,
                anchorBounds = source.bounds,
                translatedText = source.sourceText,
                provider = "none",
                succeeded = false,
                semanticTrace = semanticTrace,
                failure = remoteFailures[source.groupId]?.let { failure ->
                    TranslationFailure(
                        regionId = source.groupId,
                        code = failure.code,
                        message = failure.message,
                        retryable = failure.retryable,
                        cause = failure.cause
                    )
                } ?: if (source in remoteRequiredSources) {
                    TranslationFailure(
                        regionId = source.groupId,
                        code = "REMOTE_REQUIRED_FOR_LONG_BODY",
                        message = "Long body translation was preserved because semantic translation failed",
                        retryable = true
                    )
                } else null
            )
        }
        return (remoteExecutions + fallbackExecutions).sortedBy { execution ->
            execution.sourceGroupIds.mapNotNull(sourcesById::get)
                .minOfOrNull(SemanticTranslationSource::readingOrder) ?: Int.MAX_VALUE
        }
    }

    private data class PreparedTranslation(
        val regionId: String,
        val inputText: String,
        val request: TranslationRequest?
    )

    private fun SemanticTranslationSource.preparedFor(
        mode: TranslationMode,
        viewportWidth: Int,
        viewportHeight: Int
    ): SemanticTranslationSource {
        val preparedRegions = regions.map { region ->
            val sourceLanguage = identifySourceLanguageByScript(region.text)
            val targetLanguage = sourceLanguage?.let { targetLanguageFor(it, mode) }
            region.copy(
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                bounds = region.bounds.clampedTo(viewportWidth, viewportHeight)
            )
        }
        val shouldPreserve = translationUnit == "PRESERVED" ||
            shouldPreserveSourceText(sourceText) ||
            preparedRegions.none { it.sourceLanguage != null && it.targetLanguage != null }
        return copy(
            translationUnit = if (shouldPreserve) "PRESERVED" else "GROUP",
            bounds = bounds.clampedTo(viewportWidth, viewportHeight),
            regions = preparedRegions
        )
    }

    private fun TranslationBounds.clampedTo(
        viewportWidth: Int,
        viewportHeight: Int
    ): TranslationBounds {
        val visibleLeft = left.coerceIn(0, viewportWidth - 1)
        val visibleTop = top.coerceIn(0, viewportHeight - 1)
        return TranslationBounds(
            left = visibleLeft,
            top = visibleTop,
            right = right.coerceIn(visibleLeft + 1, viewportWidth),
            bottom = bottom.coerceIn(visibleTop + 1, viewportHeight)
        )
    }

    private suspend fun translateSemanticLocally(
        sources: List<SemanticTranslationSource>,
        mode: TranslationMode,
        experimentalEngine: ExperimentalTranslationEngine,
        requestId: String
    ): List<TranslationExecutionResult> {
        if (sources.isEmpty()) return emptyList()
        val prepared = sources.map { source ->
            val inputText = sanitizeOcrText(source.sourceText)
            val sourceLanguage = identifySourceLanguageByScript(inputText)
            val targetLanguage = sourceLanguage?.let { targetLanguageFor(it, mode) }
            val request = if (
                source.translationUnit != "PRESERVED" &&
                !shouldPreserveSourceText(inputText) &&
                sourceLanguage != null && targetLanguage != null
            ) {
                TranslationRequest(
                    requestId = requestId,
                    regionId = source.groupId,
                    text = inputText,
                    mode = mode,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    experimentalEngine = experimentalEngine
                )
            } else {
                null
            }
            PreparedTranslation(source.groupId, inputText, request)
        }
        val batch = localProvider.translateBatch(prepared.mapNotNull(PreparedTranslation::request))
        val results = batch.results.associateBy(TranslationResult::regionId)
        val failures = batch.failures.associateBy(TranslationFailure::regionId)
        return prepared.map { item ->
            val request = item.request
            val translated = results[item.regionId]
            when {
                request == null -> TranslationExecutionResult(
                    regionId = item.regionId,
                    translatedText = item.inputText,
                    provider = "preserve",
                    succeeded = true
                )
                translated != null -> TranslationExecutionResult(
                    regionId = item.regionId,
                    translatedText = translated.translatedText.trim(),
                    provider = translated.provider,
                    succeeded = true
                )
                else -> TranslationExecutionResult(
                    regionId = item.regionId,
                    translatedText = item.inputText,
                    provider = "none",
                    succeeded = false,
                    failure = failures[item.regionId]
                )
            }
        }
    }

    private fun isValidSemanticResult(
        sources: List<SemanticTranslationSource>,
        result: SemanticGroupTranslationResult
    ): Boolean {
        if (sources.isEmpty() || sources.map { it.groupId } != result.sourceGroupIds) return false
        if (sources.size > 1 && result.groupingConfidence < 0.90f) return false
        val translated = result.translatedText?.trim() ?: return false
        if (result.status == TranslationResultStatus.PRESERVED) {
            return sources.size == 1 && translated == sources.single().sourceText.trim()
        }
        val sourceText = sources.joinToString("\n") { it.sourceText }
        val targetLanguage = sources.flatMap(SemanticTranslationSource::regions)
            .mapNotNull(SemanticTranslationRegion::targetLanguage)
            .distinct().singleOrNull() ?: return false
        if (result.targetLanguage != null &&
            result.targetLanguage.substringBefore('-').lowercase() !=
            targetLanguage.substringBefore('-').lowercase()
        ) return false
        return isValidTranslation(
            translated,
            targetLanguage,
            requireNoHanCharacters = targetLanguage == TranslateLanguage.ENGLISH &&
                sourceText.length <= 12,
            requireChineseCharacters = targetLanguage == TranslateLanguage.CHINESE &&
                sourceText.length <= 32
        )
    }

    private fun remoteProviderForCurrentSettings(): RemoteTranslationProvider {
        val context = checkNotNull(appContext) { "Remote translation requires an app context" }
        val baseUrl = TranslationBackendSettings.networkBaseUrl(context)
        require(baseUrl.isNotBlank()) { "Remote translation endpoint is not configured" }
        return synchronized(remoteProviderLock) {
            if (remoteProvider == null || remoteProviderBaseUrl != baseUrl) {
                remoteProvider?.close()
                remoteProvider = RemoteTranslationProvider(baseUrl)
                remoteProviderBaseUrl = baseUrl
            }
            checkNotNull(remoteProvider)
        }
    }

    private fun selfHostedProviderForCurrentSettings(): SelfHostedSemanticTranslationProvider {
        val context = checkNotNull(appContext) {
            "Self-hosted translation requires an app context"
        }
        val baseUrl = TranslationBackendSettings.selfHostedBaseUrl(context)
        require(baseUrl.isNotBlank()) { "Self-hosted translation endpoint is not configured" }
        val token = TranslationBackendSettings.selfHostedBearerToken(context)
        val configuration = baseUrl to token
        return synchronized(selfHostedProviderLock) {
            if (selfHostedProvider == null || selfHostedProviderConfiguration != configuration) {
                selfHostedProvider?.close()
                selfHostedProvider = SelfHostedSemanticTranslationProvider(baseUrl, token)
                selfHostedProviderConfiguration = configuration
            }
            checkNotNull(selfHostedProvider)
        }
    }

    private suspend fun ensureModel(sourceLanguage: String, targetLanguage: String) =
        modelDownloadMutex.withLock {
            val languagePair = sourceLanguage to targetLanguage
            if (languagePair in downloadedModels) return@withLock
            val conditions = DownloadConditions.Builder().build()
            withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    translatorFor(sourceLanguage, targetLanguage).downloadModelIfNeeded(conditions)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                        .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
                }
            }
            downloadedModels.add(languagePair)
        }

    private suspend fun translateLocally(request: TranslationRequest): TranslationResult {
        val sourceLanguage = request.sourceLanguage
            ?: identifySourceLanguageByScript(request.text)
            ?: throw IllegalArgumentException("Unable to identify source language")
        val targetLanguage = request.targetLanguage
            ?: targetLanguageFor(sourceLanguage, request.mode)
            ?: throw IllegalArgumentException("Translation direction does not match source text")

        if (request.experimentalEngine != ExperimentalTranslationEngine.DISABLED) {
            translateWithExperimentalEngine(
                request.experimentalEngine,
                request.text,
                sourceLanguage,
                targetLanguage
            )?.let { translated ->
                return TranslationResult(
                    regionId = request.regionId,
                    translatedText = translated,
                    provider = "experimental-${request.experimentalEngine.name.lowercase()}",
                    detectedSourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
            }
        }

        ensureModel(sourceLanguage, targetLanguage)
        val translator = translatorFor(sourceLanguage, targetLanguage)
        var result = translateWithModel(translator, request.text).trim()
        val requiresCompleteEnglish = sourceLanguage == TranslateLanguage.CHINESE &&
            targetLanguage == TranslateLanguage.ENGLISH && request.text.length <= 12
        val requiresChineseOutput = sourceLanguage == TranslateLanguage.ENGLISH &&
            targetLanguage == TranslateLanguage.CHINESE && request.text.length <= 32
        if (!isValidTranslation(result, targetLanguage) && request.text.length >= 16) {
            result = translateInSegments(translator, request.text)
        }
        require(
            isValidTranslation(
                result,
                targetLanguage,
                requiresCompleteEnglish,
                requiresChineseOutput
            )
        ) { "翻译结果包含异常字符" }
        return TranslationResult(
            regionId = request.regionId,
            translatedText = result,
            provider = "local-ml-kit",
            detectedSourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    private fun isValidProviderResult(
        request: TranslationRequest,
        result: TranslationResult
    ): Boolean {
        if (result.regionId != request.regionId) return false
        if (result.status == TranslationResultStatus.PRESERVED) {
            return result.translatedText.trim() == request.text.trim()
        }
        val sourceLanguage = request.sourceLanguage ?: return false
        val targetLanguage = request.targetLanguage ?: return false
        if (result.targetLanguage != null &&
            result.targetLanguage.substringBefore('-').lowercase() !=
            targetLanguage.substringBefore('-').lowercase()
        ) return false
        return isValidTranslation(
            result.translatedText.trim(),
            targetLanguage,
            requireNoHanCharacters = sourceLanguage == TranslateLanguage.CHINESE &&
                request.text.length <= 12,
            requireChineseCharacters = sourceLanguage == TranslateLanguage.ENGLISH &&
                request.text.length <= 32
        )
    }

    private suspend fun translateWithExperimentalEngine(
        engine: ExperimentalTranslationEngine,
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String? {
        val library = experimentalLibraryDelegate?.value ?: return null
        val source = sourceLanguage.toExperimentalLanguage() ?: return null
        val target = targetLanguage.toExperimentalLanguage() ?: return null
        return try {
            val translation = library.translate(
                engine,
                ExperimentalTranslationRequest(
                    text = text,
                    sourceLanguage = source,
                    targetLanguage = target
                )
            )
            val result = translation.text.trim().takeIf { candidate ->
                isValidTranslation(
                    candidate,
                    targetLanguage,
                    requireNoHanCharacters = sourceLanguage == TranslateLanguage.CHINESE &&
                        text.length <= 12,
                    requireChineseCharacters = sourceLanguage == TranslateLanguage.ENGLISH &&
                        text.length <= 32
                )
            }
            if (result != null) {
                Log.i(
                    TAG,
                    "Experimental translation succeeded: engine=${engine.name}, " +
                        "inferenceMs=${translation.inferenceMs}, inputChars=${text.length}, " +
                        "outputChars=${result.length}"
                )
            } else {
                Log.w(
                    TAG,
                    "Experimental translation rejected; falling back to ML Kit: " +
                        "engine=${engine.name}"
                )
            }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "Experimental translation unavailable; falling back to ML Kit: " +
                    "engine=${engine.name}, cause=${error.javaClass.simpleName}"
            )
            null
        }
    }

    private fun String.toExperimentalLanguage(): TranslationLanguage? = when (this) {
        TranslateLanguage.CHINESE -> TranslationLanguage.CHINESE
        TranslateLanguage.ENGLISH -> TranslationLanguage.ENGLISH
        else -> null
    }

    private fun shouldPreserveSourceText(text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        if (visible.isEmpty()) return true

        val hanCount = visible.count(::isHanCharacter)
        val latinCount = visible.count { it in 'A'..'Z' || it in 'a'..'z' }
        val digitCount = visible.count(Char::isDigit)
        val meaningfulCount = hanCount + latinCount + digitCount
        val isNumericIdentifier = digitCount >= 4 && hanCount <= 1 &&
            meaningfulCount > 0 && digitCount.toFloat() / meaningfulCount >= 0.65f
        if (isNumericIdentifier) return true

        val looksLikeCode = visible.contains("//") || visible.contains('_') ||
            visible.contains('@') || visible.matches(Regex("[A-Za-z]+://.*"))
        val looksLikeBrandGroup = hanCount == 0 && latinCount > 0 &&
            visible.any { it in charArrayOf('×', '©', '®', '™') }
        return looksLikeCode || looksLikeBrandGroup
    }

    private fun targetLanguageFor(sourceLanguage: String, mode: TranslationMode): String? =
        when (mode) {
            TranslationMode.CHINESE_TO_ENGLISH ->
                TranslateLanguage.ENGLISH.takeIf { sourceLanguage == TranslateLanguage.CHINESE }
            TranslationMode.ENGLISH_TO_CHINESE ->
                TranslateLanguage.CHINESE.takeIf { sourceLanguage == TranslateLanguage.ENGLISH }
            TranslationMode.AUTO_BIDIRECTIONAL -> when (sourceLanguage) {
                TranslateLanguage.CHINESE -> TranslateLanguage.ENGLISH
                TranslateLanguage.ENGLISH -> TranslateLanguage.CHINESE
                else -> null
            }
        }

    private suspend fun translateInSegments(translator: Translator, text: String): String {
        val segments = text.split(Regex("(?<=[，。；;！？!?])"))
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (segments.size <= 1) return translateWithModel(translator, text).trim()
        val translatedSegments = mutableListOf<String>()
        for (segment in segments) {
            translatedSegments.add(translateWithModel(translator, segment).trim())
        }
        return translatedSegments.joinToString(" ")
    }

    private fun sanitizeOcrText(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.any(::isHanCharacter) || trimmed.length > 10) return trimmed
        return trimmed.trim('<', '>', '=', '|', '·', '•')
    }

    private fun identifySourceLanguageByScript(text: String): String? = when {
        text.any(::isHanCharacter) -> TranslateLanguage.CHINESE
        text.any { it in 'A'..'Z' || it in 'a'..'z' } -> TranslateLanguage.ENGLISH
        else -> null
    }

    private fun translatorFor(sourceLanguage: String, targetLanguage: String): Translator {
        val languagePair = sourceLanguage to targetLanguage
        return synchronized(translators) {
            translators.getOrPut(languagePair) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                )
            }
        }
    }

    private fun isValidTranslation(
        text: String,
        targetLanguage: String,
        requireNoHanCharacters: Boolean = false,
        requireChineseCharacters: Boolean = false
    ): Boolean {
        if (text.isBlank()) return false
        if (text.first() in charArrayOf('<', '>', '=', '|')) return false
        val visibleCharacters = text.count { !it.isWhitespace() }
        if (visibleCharacters == 0) return false
        val meaningfulCharacters = text.count { it.isLetterOrDigit() }
        if (meaningfulCharacters.toFloat() / visibleCharacters < 0.6f) return false
        if (targetLanguage == TranslateLanguage.ENGLISH) {
            val hanCharacters = text.count(::isHanCharacter)
            if (requireNoHanCharacters && hanCharacters > 0) return false
            if (hanCharacters.toFloat() / visibleCharacters > 0.1f) return false
        } else if (targetLanguage == TranslateLanguage.CHINESE && requireChineseCharacters) {
            val hanCharacters = text.count(::isHanCharacter)
            val latinCharacters = text.count { it in 'A'..'Z' || it in 'a'..'z' }
            if (hanCharacters == 0 || latinCharacters > hanCharacters * 2) return false
        }
        return true
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private suspend fun translateWithModel(
        translator: Translator,
        text: String
    ): String = withTimeout(TRANSLATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }
    }

    fun close() {
        switchingProvider.close()
        synchronized(translators) {
            translators.values.forEach(Translator::close)
            translators.clear()
        }
        downloadedModels.clear()
        experimentalLibraryDelegate?.takeIf { it.isInitialized() }?.value?.close()
        synchronized(remoteProviderLock) {
            remoteProvider?.close()
            remoteProvider = null
            remoteProviderBaseUrl = null
        }
        synchronized(selfHostedProviderLock) {
            selfHostedProvider?.close()
            selfHostedProvider = null
            selfHostedProviderConfiguration = null
        }
    }
}
