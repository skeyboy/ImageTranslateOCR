package com.example.imagetranslate.translate

import android.content.Context
import com.example.imagetranslate.BuildConfig
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

internal class SelfHostedSemanticTranslationProvider(
    baseUrl: String,
    private val bearerToken: String?,
    private val schemaVersion: Int = SELF_HOSTED_V3_SCHEMA_VERSION,
    endpointPath: String? = null,
    private val context: Context? = null
) : SemanticTranslationProvider {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val endpoint = URL(
        normalizedBaseUrl + (endpointPath ?: if (schemaVersion == SELF_HOSTED_V4_SCHEMA_VERSION) {
            SELF_HOSTED_REGIONS_FIRST_PATH
        } else {
            SELF_HOSTED_LAYOUT_PLAN_PATH
        })
    ).also {
        require(schemaVersion in setOf(SELF_HOSTED_V3_SCHEMA_VERSION, SELF_HOSTED_V4_SCHEMA_VERSION)) {
            "Unsupported self-hosted semantic schema version"
        }
        require(it.protocol == "https" || BuildConfig.DEBUG && isDebugHttpHost(it.host)) {
            "Self-hosted translation requires HTTPS outside local development"
        }
    }
    private val executor = Executors.newFixedThreadPool(MAXIMUM_CONCURRENT_REQUESTS)
    private val cancellationExecutor = Executors.newSingleThreadExecutor()
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val closed = AtomicBoolean(false)

    override suspend fun translate(
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult {
        check(!closed.get()) { "Self-hosted translation provider is closed" }
        val requestBody = buildRequestBody(request)
        var responseText: String? = null
        return try {
            responseText = executeRequest(requestBody, request.requestId)
            val parsed = parseResponse(JSONObject(responseText), request)
            context?.let {
                TranslationRequestArchiveStore.recordExchange(
                    context = it,
                    requestJson = requestBody,
                    responseJson = responseText,
                    provider = "local-server"
                )
            }
            parsed
        } catch (error: Exception) {
            context?.let {
                runCatching {
                    TranslationRequestArchiveStore.recordExchange(
                        context = it,
                        requestJson = requestBody,
                        responseJson = responseText,
                        provider = "local-server",
                        errorMessage = error.message ?: "Local server translation failed"
                    )
                }
            }
            throw error
        }
    }

    internal fun requestBodyForTest(request: SemanticTranslationRequest): String =
        buildRequestBody(request)

    internal fun endpointForTest(): String = endpoint.toString()

    internal fun parseResponseForTest(
        response: String,
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult = parseResponse(JSONObject(response), request)

    private fun buildRequestBody(request: SemanticTranslationRequest): String = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("requestId", request.requestId)
        .put("sessionId", request.sessionId)
        .put("generation", request.generation)
        .put("translationRevision", request.translationRevision)
        .put("scene", request.scene)
        .put(
            "viewport",
            JSONObject()
                .put("width", request.viewportWidth)
                .put("height", request.viewportHeight)
                .put("rotationDegrees", 0)
        )
        .put(
            "translation",
            JSONObject()
                .put("mode", request.mode.name)
                .put("sourceLanguage", "auto")
                .put("targetLanguage", targetLanguageForMode(request.mode))
                .put("preserveIdentifiers", true)
                .put("useDocumentContext", true)
                .put("directStructuredOutput", request.directStructuredOutput)
                .put("compactProviderPrompt", request.compactProviderPrompt)
                .apply {
                    request.thinkingControlMode?.let {
                        put("thinkingControlMode", it.name)
                    }
                    request.thinkingLevel?.let { put("thinkingLevel", it) }
                }
        )
        .put(
            "documentContext",
            JSONObject()
                .put("text", request.documentText)
                .put("sourceLanguage", "auto")
                .put(
                    "readingOrderRegionIds",
                    JSONArray().apply {
                        request.sources.sortedBy(SemanticTranslationSource::readingOrder)
                            .flatMap(SemanticTranslationSource::regions)
                            .sortedBy(SemanticTranslationRegion::readingOrder)
                            .forEach { put(it.regionId) }
                    }
                )
        )
        .put(
            "groups",
            JSONArray().apply {
                request.sources.sortedBy(SemanticTranslationSource::readingOrder).forEach { source ->
                    put(source.toJson())
                }
            }
        )
        .put(
            "regions",
            JSONArray().apply {
                request.sources.flatMap(SemanticTranslationSource::regions)
                    .sortedBy(SemanticTranslationRegion::readingOrder)
                    .forEach { put(it.toJson()) }
                }
        )
        .apply {
            request.debugCapture?.let { capture ->
                put(
                    "debugCapture",
                    JSONObject()
                        .put("mimeType", capture.mimeType)
                        .put("dataBase64", capture.dataBase64)
                        .put("pixelWidth", capture.pixelWidth)
                        .put("pixelHeight", capture.pixelHeight)
                )
            }
        }
        .toString()

    private suspend fun executeRequest(body: String, requestId: String): String =
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
                    setRequestProperty("X-Request-Id", requestId)
                    bearerToken?.takeIf(String::isNotBlank)?.let { token ->
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                connectionRef.set(connection)
                activeConnections += connection
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
                    val status = connection.responseCode
                    val responseText = (
                        if (status in 200..299) connection.inputStream else connection.errorStream
                        )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        val message = runCatching {
                            JSONObject(responseText).optJSONObject("error")?.optString("message")
                        }.getOrNull().takeUnless(String?::isNullOrBlank)
                        throw SelfHostedTranslationHttpException(status, message)
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
                if (!closed.get()) {
                    val scheduled = runCatching {
                        cancellationExecutor.execute {
                            sendCancellation(requestId)
                            connectionRef.getAndSet(null)?.disconnect()
                            futureRef.getAndSet(null)?.cancel(true)
                        }
                    }.isSuccess
                    if (!scheduled) {
                        connectionRef.getAndSet(null)?.disconnect()
                        futureRef.getAndSet(null)?.cancel(true)
                    }
                } else {
                    connectionRef.getAndSet(null)?.disconnect()
                    futureRef.getAndSet(null)?.cancel(true)
                }
            }
        }

    private fun sendCancellation(requestId: String) {
        val endpoint = URL(
            normalizedBaseUrl + CANCEL_REQUEST_PREFIX +
                java.net.URLEncoder.encode(requestId, Charsets.UTF_8.name()) + "/cancel"
        )
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CANCEL_TIMEOUT_MS
            readTimeout = CANCEL_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Request-Id", requestId)
            bearerToken?.takeIf(String::isNotBlank)?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        try {
            connection.responseCode
        } catch (_: Exception) {
            Unit
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(
        response: JSONObject,
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult {
        require(response.optInt("schemaVersion") == schemaVersion) {
            "Self-hosted response schemaVersion does not match"
        }
        require(response.optString("requestId") == request.requestId) {
            "Self-hosted response requestId does not match"
        }
        require(response.optString("sessionId") == request.sessionId) {
            "Self-hosted response sessionId does not match"
        }
        require(response.optLong("generation", -1) == request.generation) {
            "Self-hosted response generation does not match"
        }
        require(
            response.optLong("translationRevision", -1) == request.translationRevision
        ) { "Self-hosted response translationRevision does not match" }
        if (schemaVersion == SELF_HOSTED_V4_SCHEMA_VERSION) {
            val documentPlan = response.optJSONObject("documentPlan")
                ?: error("Self-hosted V4 response has no documentPlan")
            require(documentPlan.optString("mode") == "AUTHORITATIVE") {
                "Self-hosted V4 documentPlan must be authoritative"
            }
            require(documentPlan.optString("planVersion") == "server-regions-first-plan-v4") {
                "Self-hosted V4 documentPlan version does not match"
            }
            return parseRegionsFirstResponse(response, request)
        }
        val provider = response.optString("provider", "self-hosted-qwen-layout-plan-v3")
        val expected = request.sources.associateBy(SemanticTranslationSource::groupId)
        val results = response.optJSONArray("results")
            ?: error("Self-hosted response has no results")
        val parsed = mutableListOf<SemanticGroupTranslationResult>()
        val failures = mutableListOf<SemanticGroupTranslationFailure>()
        val seen = mutableSetOf<String>()
        val coveredSourceGroups = mutableSetOf<String>()
        val sourceOrder = request.sources.sortedBy(SemanticTranslationSource::readingOrder)
            .map(SemanticTranslationSource::groupId)
        val sourceOrderById = sourceOrder.withIndex().associate { it.value to it.index }
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val groupId = item.getString("groupId")
            check(seen.add(groupId)) { "Self-hosted response returned a duplicate groupId" }
            val sourceGroupIds = item.getJSONArray("sourceGroupIds").toStringList()
            require(sourceGroupIds.isNotEmpty() && sourceGroupIds.distinct().size == sourceGroupIds.size) {
                "Self-hosted result sourceGroupIds are invalid"
            }
            val sources = sourceGroupIds.map { sourceId ->
                expected[sourceId] ?: error("Self-hosted response returned an unknown sourceGroupId")
            }
            val sourceIndexes = sourceGroupIds.map { sourceId -> checkNotNull(sourceOrderById[sourceId]) }
            require(sourceIndexes.zipWithNext().all { (first, second) -> second == first + 1 }) {
                "Self-hosted result sourceGroupIds must be consecutive in reading order"
            }
            require(sourceGroupIds.all(coveredSourceGroups::add)) {
                "Self-hosted response assigned a source group more than once"
            }
            val source = combinedSource(groupId, sources)
            val confidence = item.optDouble("groupingConfidence", 0.0).toFloat()
            if (sources.size > 1) {
                require(confidence >= AUTHORITATIVE_GROUPING_CONFIDENCE) {
                    "Self-hosted merged group confidence is below the authority threshold"
                }
                require(isAuthoritativeMergeAllowed(sources)) {
                    "Self-hosted merged group crosses a protected semantic role without " +
                        "continuous OCR block evidence"
                }
            }
            validateBinding(item, source)
            when (val status = item.optString("status")) {
                "TRANSLATED" -> {
                    val translated = item.optString("translatedText").trim()
                    if (translated.isEmpty()) {
                        failures += invalidFailure(groupId, "Self-hosted translation was empty")
                    } else {
                        parsed += SemanticGroupTranslationResult(
                            groupId = groupId,
                            sourceGroupIds = sourceGroupIds,
                            memberRegionIds = source.memberRegionIds,
                            anchorBounds = source.bounds,
                            role = item.optString("role", source.role),
                            groupingConfidence = confidence,
                            translatedText = translated,
                            provider = provider,
                            status = TranslationResultStatus.TRANSLATED,
                            detectedSourceLanguage = item.optString("detectedSourceLanguage")
                                .takeIf(String::isNotBlank),
                            targetLanguage = item.optString("targetLanguage")
                                .takeIf(String::isNotBlank),
                            layoutHint = item.optJSONObject("layoutHint")?.toLayoutHint(source)
                        )
                    }
                }
                "PRESERVED" -> parsed += SemanticGroupTranslationResult(
                    groupId = groupId,
                    sourceGroupIds = sourceGroupIds,
                    memberRegionIds = source.memberRegionIds,
                    anchorBounds = source.bounds,
                    role = item.optString("role", source.role),
                    groupingConfidence = confidence,
                    translatedText = source.sourceText,
                    provider = provider,
                    status = TranslationResultStatus.PRESERVED,
                    detectedSourceLanguage = item.optString("detectedSourceLanguage")
                        .takeIf(String::isNotBlank),
                    targetLanguage = item.optString("targetLanguage").takeIf(String::isNotBlank),
                    layoutHint = item.optJSONObject("layoutHint")?.toLayoutHint(source)
                )
                "FAILED" -> failures += item.toFailure(groupId)
                else -> failures += invalidFailure(
                    groupId,
                    "Unsupported self-hosted result status: $status"
                )
            }
        }
        request.sources.filter { it.groupId !in coveredSourceGroups }.forEach { source ->
            failures += SemanticGroupTranslationFailure(
                groupId = source.groupId,
                code = "MISSING_RESULT",
                message = "Self-hosted translation omitted a group",
                retryable = true
            )
        }
        return SemanticTranslationBatchResult(parsed, failures)
    }

    private fun parseRegionsFirstResponse(
        response: JSONObject,
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult {
        val provider = response.optString("provider", "self-hosted-qwen-regions-first-v4")
        val sourceById = request.sources.associateBy(SemanticTranslationSource::groupId)
        val regionById = request.sources
            .flatMap(SemanticTranslationSource::regions)
            .associateBy(SemanticTranslationRegion::regionId)
        val results = response.optJSONArray("results")
            ?: error("Self-hosted response has no results")
        val parsed = mutableListOf<SemanticGroupTranslationResult>()
        val failures = mutableListOf<SemanticGroupTranslationFailure>()
        val seenGroups = mutableSetOf<String>()
        val coveredRegions = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val groupId = item.getString("groupId")
            require(seenGroups.add(groupId)) {
                "Self-hosted response returned a duplicate groupId"
            }
            val memberRegionIds = item.getJSONArray("memberRegionIds").toStringList()
            require(memberRegionIds.isNotEmpty() && memberRegionIds.distinct().size == memberRegionIds.size) {
                "Self-hosted V4 memberRegionIds are invalid"
            }
            require(memberRegionIds.all(coveredRegions::add)) {
                "Self-hosted V4 response assigned an OCR region more than once"
            }
            val memberRegions = memberRegionIds.map { regionId ->
                regionById[regionId]
                    ?: error("Self-hosted V4 response returned an unknown memberRegionId")
            }.sortedBy(SemanticTranslationRegion::readingOrder)
            val expectedSourceGroupIds = memberRegions.map(SemanticTranslationRegion::groupId)
                .filter(String::isNotBlank)
                .distinct()
            val sourceGroupIds = item.getJSONArray("sourceGroupIds").toStringList()
            require(sourceGroupIds == expectedSourceGroupIds) {
                "Self-hosted V4 sourceGroupIds do not match OCR region lineage"
            }
            require(sourceGroupIds.all(sourceById::containsKey)) {
                "Self-hosted V4 response returned an unknown sourceGroupId"
            }
            val source = regionsFirstSource(
                groupId = groupId,
                role = item.optString("role", "BODY"),
                regions = memberRegions,
                sources = sourceById
            )
            validateBinding(item, source)
            val confidence = item.optDouble("groupingConfidence", 0.0).toFloat()
            if (memberRegions.size > 1) {
                require(confidence >= AUTHORITATIVE_GROUPING_CONFIDENCE) {
                    "Self-hosted V4 merged group confidence is below the authority threshold"
                }
            }
            when (val status = item.optString("status")) {
                "TRANSLATED" -> {
                    val translated = item.optString("translatedText").trim()
                    if (translated.isEmpty()) {
                        failures += invalidFailure(groupId, "Self-hosted translation was empty")
                    } else {
                        parsed += SemanticGroupTranslationResult(
                            groupId = groupId,
                            sourceGroupIds = sourceGroupIds,
                            memberRegionIds = memberRegionIds,
                            anchorBounds = source.bounds,
                            role = source.role,
                            groupingConfidence = confidence,
                            translatedText = translated,
                            provider = provider,
                            status = TranslationResultStatus.TRANSLATED,
                            detectedSourceLanguage = item.optString("detectedSourceLanguage")
                                .takeIf(String::isNotBlank),
                            targetLanguage = item.optString("targetLanguage")
                                .takeIf(String::isNotBlank),
                            layoutHint = item.optJSONObject("layoutHint")?.toLayoutHint(
                                source,
                                allowAuthoritativeRenderSlots = true
                            )
                        )
                    }
                }
                "PRESERVED" -> parsed += SemanticGroupTranslationResult(
                    groupId = groupId,
                    sourceGroupIds = sourceGroupIds,
                    memberRegionIds = memberRegionIds,
                    anchorBounds = source.bounds,
                    role = source.role,
                    groupingConfidence = confidence,
                    translatedText = source.sourceText,
                    provider = provider,
                    status = TranslationResultStatus.PRESERVED,
                    detectedSourceLanguage = item.optString("detectedSourceLanguage")
                        .takeIf(String::isNotBlank),
                    targetLanguage = item.optString("targetLanguage").takeIf(String::isNotBlank),
                    layoutHint = item.optJSONObject("layoutHint")?.toLayoutHint(
                        source,
                        allowAuthoritativeRenderSlots = true
                    )
                )
                "FAILED" -> failures += item.toFailure(groupId)
                else -> failures += invalidFailure(
                    groupId,
                    "Unsupported self-hosted V4 result status: $status"
                )
            }
        }
        require(coveredRegions == regionById.keys) {
            "Self-hosted V4 response did not account for every OCR region"
        }
        return SemanticTranslationBatchResult(parsed, failures)
    }

    private fun regionsFirstSource(
        groupId: String,
        role: String,
        regions: List<SemanticTranslationRegion>,
        sources: Map<String, SemanticTranslationSource>
    ): SemanticTranslationSource {
        val ordered = regions.sortedBy(SemanticTranslationRegion::readingOrder)
        val bounds = ordered.map(SemanticTranslationRegion::bounds).reduce(::unionBounds)
        val sourceGroups = ordered.map(SemanticTranslationRegion::groupId).distinct()
            .mapNotNull(sources::get)
        return SemanticTranslationSource(
            groupId = groupId,
            role = role,
            translationUnit = if (sourceGroups.isNotEmpty() &&
                sourceGroups.all { it.translationUnit == "PRESERVED" }
            ) {
                "PRESERVED"
            } else {
                "GROUP"
            },
            sourceText = ordered.joinToString("\n") { it.text },
            memberRegionIds = ordered.map(SemanticTranslationRegion::regionId),
            readingOrder = ordered.minOf(SemanticTranslationRegion::readingOrder),
            groupingConfidence = ordered.minOf(SemanticTranslationRegion::confidence),
            groupingEvidence = listOf("SERVER_REGIONS_FIRST"),
            bounds = bounds,
            regions = ordered,
            sourceLineCount = ordered.sumOf { maxOf(1, it.componentBounds.size) },
            renderSlots = ordered.map(SemanticTranslationRegion::bounds),
            layoutShape = if (ordered.size > 1) "FLOW_SLOTS" else "RECT"
        )
    }

    private fun validateBinding(item: JSONObject, source: SemanticTranslationSource) {
        val memberIds = item.optJSONArray("memberRegionIds")
            ?: error("Self-hosted result has no memberRegionIds")
        val returnedMembers = (0 until memberIds.length()).map(memberIds::getString)
        require(returnedMembers == source.memberRegionIds) {
            "Self-hosted result memberRegionIds do not match"
        }
        val anchor = item.optJSONObject("anchorBounds")
            ?: error("Self-hosted result has no anchorBounds")
        require(anchor.toBounds() == source.bounds) {
            "Self-hosted result anchorBounds do not match"
        }
    }

    private fun combinedSource(
        groupId: String,
        sources: List<SemanticTranslationSource>
    ): SemanticTranslationSource {
        val ordered = sources.sortedBy(SemanticTranslationSource::readingOrder)
        val bounds = ordered.map(SemanticTranslationSource::bounds).reduce(::unionBounds)
        return SemanticTranslationSource(
            groupId = groupId,
            role = ordered.first().role,
            translationUnit = if (ordered.all { it.translationUnit == "PRESERVED" }) {
                "PRESERVED"
            } else {
                "GROUP"
            },
            sourceText = ordered.joinToString("\n") { it.sourceText },
            memberRegionIds = ordered.flatMap(SemanticTranslationSource::memberRegionIds),
            readingOrder = ordered.minOf(SemanticTranslationSource::readingOrder),
            groupingConfidence = ordered.minOf(SemanticTranslationSource::groupingConfidence),
            groupingEvidence = ordered.flatMap(SemanticTranslationSource::groupingEvidence).distinct(),
            bounds = bounds,
            regions = ordered.flatMap(SemanticTranslationSource::regions),
            sourceLineCount = ordered.sumOf(SemanticTranslationSource::sourceLineCount),
            renderSlots = ordered.flatMap(SemanticTranslationSource::renderSlots),
            layoutShape = if (ordered.sumOf { it.renderSlots.size } > 1) "FLOW_SLOTS" else "RECT"
        )
    }

    private fun isAuthoritativeMergeAllowed(
        sources: List<SemanticTranslationSource>
    ): Boolean {
        if (sources.any { it.role !in AUTHORITATIVE_MERGE_ROLES }) return false
        return sources.zipWithNext().all { (first, second) ->
            first.role == second.role ||
                hasContinuousOcrBoundary(first, second) ||
                hasVisualContinuationBoundary(first, second)
        }
    }

    private fun hasVisualContinuationBoundary(
        first: SemanticTranslationSource,
        second: SemanticTranslationSource
    ): Boolean {
        val firstLineHeight = first.regions.maxOfOrNull { it.bounds.bottom - it.bounds.top }
            ?: (first.bounds.bottom - first.bounds.top) / first.sourceLineCount.coerceAtLeast(1)
        val secondLineHeight = second.regions.maxOfOrNull { it.bounds.bottom - it.bounds.top }
            ?: (second.bounds.bottom - second.bounds.top) / second.sourceLineCount.coerceAtLeast(1)
        val lineHeight = maxOf(firstLineHeight, secondLineHeight, 1)
        val verticalGap = second.bounds.top - first.bounds.bottom
        if (verticalGap !in -(lineHeight / 3)..lineHeight) return false

        val overlap = (
            minOf(first.bounds.right, second.bounds.right) -
                maxOf(first.bounds.left, second.bounds.left)
            ).coerceAtLeast(0)
        val minimumWidth = minOf(
            first.bounds.right - first.bounds.left,
            second.bounds.right - second.bounds.left
        ).coerceAtLeast(1)
        if (overlap.toFloat() / minimumWidth < 0.72f) return false
        if (kotlin.math.abs(first.bounds.right - second.bounds.right) > lineHeight * 2) return false
        return !first.sourceText.trimEnd().endsWithAnySentenceTerminator()
    }

    private fun String.endsWithAnySentenceTerminator(): Boolean =
        endsWith('.') || endsWith('!') || endsWith('?') ||
            endsWith('。') || endsWith('！') || endsWith('？')

    private fun hasContinuousOcrBoundary(
        first: SemanticTranslationSource,
        second: SemanticTranslationSource
    ): Boolean {
        val firstRegion = first.regions.maxByOrNull(SemanticTranslationRegion::readingOrder)
            ?: return false
        val secondRegion = second.regions.minByOrNull(SemanticTranslationRegion::readingOrder)
            ?: return false
        val blockId = firstRegion.blockId?.takeIf(String::isNotBlank)
        return blockId != null &&
            blockId == secondRegion.blockId &&
            firstRegion.lineIndex != null &&
            secondRegion.lineIndex == firstRegion.lineIndex + 1
    }

    private fun JSONObject.toLayoutHint(
        source: SemanticTranslationSource,
        allowAuthoritativeRenderSlots: Boolean = false
    ): SemanticLayoutHint {
        val preferredMaxLines = optInt("preferredMaxLines", 1).coerceIn(1, 24)
        val minimumTextScale = optDouble("minimumTextScale", 0.6).toFloat().coerceIn(0.5f, 1f)
        val returnedSlots = optJSONArray("renderSlots")?.let { items ->
            (0 until items.length()).map { index -> items.getJSONObject(index).toBounds() }
        } ?: source.renderSlots
        require(
            if (allowAuthoritativeRenderSlots) {
                returnedSlots.isNotEmpty() &&
                    returnedSlots.all(TranslationBounds::hasPositiveArea) &&
                    returnedSlots.reduce(::unionBounds) == source.bounds
            } else {
                returnedSlots == source.renderSlots
            }
        ) {
            "Self-hosted result renderSlots do not match the permitted geometry"
        }
        val expectedCoverSlots = source.regions.flatMap { region ->
            region.componentBounds.ifEmpty { listOf(region.bounds) }
        }
        val returnedCoverSlots = optJSONArray("sourceCoverSlots")?.let { items ->
            (0 until items.length()).map { index -> items.getJSONObject(index).toBounds() }
        } ?: expectedCoverSlots
        require(returnedCoverSlots == expectedCoverSlots) {
            "Self-hosted result sourceCoverSlots do not match OCR member geometry"
        }
        return SemanticLayoutHint(
            preferredMaxLines = preferredMaxLines,
            minimumTextScale = minimumTextScale,
            maximumTextScale = optDouble("maximumTextScale", 1.0).toFloat().coerceIn(
                minimumTextScale,
                1f
            ),
            lineSpacingMultiplier = optDouble("lineSpacingMultiplier", 1.0).toFloat()
                .coerceIn(0.82f, 1.2f),
            alignment = optString("alignment", "START"),
            overflowStrategy = optString(
                "overflowStrategy",
                "REFLOW_THEN_SCALE_THEN_MORE"
            ),
            allowMore = optBoolean("allowMore", false),
            sourceLineCount = optInt("sourceLineCount", source.regions.size).coerceAtLeast(1),
            layoutShape = optString("layoutShape", source.layoutShape),
            renderSlots = returnedSlots,
            sourceCoverSlots = returnedCoverSlots
        )
    }

    private fun JSONObject.toFailure(groupId: String): SemanticGroupTranslationFailure {
        val error = optJSONObject("error")
        return SemanticGroupTranslationFailure(
            groupId = groupId,
            code = error?.optString("code")?.takeIf(String::isNotBlank)
                ?: "SELF_HOSTED_TRANSLATION_FAILED",
            message = error?.optString("message")?.takeIf(String::isNotBlank)
                ?: "Self-hosted translation failed",
            retryable = error?.optBoolean("retryable", true) ?: true
        )
    }

    private fun invalidFailure(groupId: String, message: String) =
        SemanticGroupTranslationFailure(
            groupId = groupId,
            code = "INVALID_SELF_HOSTED_RESULT",
            message = message,
            retryable = true
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnections.forEach(HttpURLConnection::disconnect)
        activeConnections.clear()
        executor.shutdownNow()
        cancellationExecutor.shutdownNow()
    }

    private class SelfHostedTranslationHttpException(status: Int, message: String?) :
        IllegalStateException(
            buildString {
                append("Self-hosted translation failed with HTTP ").append(status)
                message?.let { append(": ").append(it) }
            }
        )

    private companion object {
        const val SELF_HOSTED_V3_SCHEMA_VERSION = 3
        const val SELF_HOSTED_V4_SCHEMA_VERSION = 4
        const val CANCEL_REQUEST_PREFIX = "/api/v2/translate/requests/"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 220_000
        const val CANCEL_TIMEOUT_MS = 3_000
        const val AUTHORITATIVE_GROUPING_CONFIDENCE = 0.90f
        const val MAXIMUM_CONCURRENT_REQUESTS = 2
        val AUTHORITATIVE_MERGE_ROLES = setOf("BODY", "LIST_ITEM", "TITLE")
    }
}

private fun TranslationBounds.hasPositiveArea(): Boolean = right > left && bottom > top

private fun SemanticTranslationSource.toJson() = JSONObject()
    .put("groupId", groupId)
    .put("role", role)
    .put("translationUnit", translationUnit)
    .put("sourceText", sourceText)
    .put("memberRegionIds", JSONArray(memberRegionIds))
    .put("readingOrder", readingOrder)
    .put("groupingConfidence", groupingConfidence.toDouble())
    .put("groupingEvidence", JSONArray(groupingEvidence))
    .put("bounds", bounds.toJson())
    .put("sourceLineCount", sourceLineCount)
    .put("layoutShape", layoutShape)
    .put("renderSlots", JSONArray().apply { renderSlots.forEach { put(it.toJson()) } })

private fun SemanticTranslationRegion.toJson() = JSONObject()
    .put("regionId", regionId)
    .put("groupId", groupId)
    .put("sourceRevision", sourceRevision)
    .put("text", text)
    .put("rawText", rawText)
    .put("sourceLanguage", sourceLanguage ?: JSONObject.NULL)
    .put("targetLanguage", targetLanguage ?: JSONObject.NULL)
    .put("readingOrder", readingOrder)
    .put("blockId", blockId ?: JSONObject.NULL)
    .put("lineIndex", lineIndex ?: JSONObject.NULL)
    .put("confidence", confidence.toDouble())
    .put("bounds", bounds.toJson())
    .put(
        "componentBounds",
        JSONArray().apply { componentBounds.forEach { put(it.toJson()) } }
    )
    .put(
        "corrections",
        JSONArray().apply {
            corrections.forEach { correction ->
                put(
                    JSONObject()
                        .put("code", correction.code)
                        .put("original", correction.original)
                        .put("replacement", correction.replacement)
                )
            }
        }
    )

private fun TranslationBounds.toJson() = JSONObject()
    .put("left", left)
    .put("top", top)
    .put("right", right)
    .put("bottom", bottom)

private fun JSONObject.toBounds() = TranslationBounds(
    left = getInt("left"),
    top = getInt("top"),
    right = getInt("right"),
    bottom = getInt("bottom")
)

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map(::getString)

private fun unionBounds(first: TranslationBounds, second: TranslationBounds) = TranslationBounds(
    left = minOf(first.left, second.left),
    top = minOf(first.top, second.top),
    right = maxOf(first.right, second.right),
    bottom = maxOf(first.bottom, second.bottom)
)

private fun targetLanguageForMode(mode: TranslationMode): String = when (mode) {
    TranslationMode.CHINESE_TO_ENGLISH -> "en"
    TranslationMode.ENGLISH_TO_CHINESE -> "zh"
    TranslationMode.AUTO_BIDIRECTIONAL -> "auto"
}
