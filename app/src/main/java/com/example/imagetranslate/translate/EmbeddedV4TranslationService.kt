package com.example.imagetranslate.translate

import java.security.MessageDigest
import kotlin.math.abs

/**
 * In-process Android implementation of demo-server's regions-first v4 planner.
 * It intentionally has no HTTP, database, filesystem, or Android Service dependency.
 */
internal class EmbeddedV4TranslationService(
    private val translateBatch: suspend (List<TranslationRequest>) -> TranslationBatchResult
) : SemanticTranslationProvider {
    override suspend fun translate(
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult {
        val plan = EmbeddedV4RegionsFirstPlanner.build(request)
        val translationRequests = plan.groups
            .filterNot(EmbeddedV4PlannedGroup::isPreserved)
            .mapNotNull { group -> group.toTranslationRequest(request) }
        val translated = if (translationRequests.isEmpty()) {
            TranslationBatchResult(emptyList())
        } else {
            translateBatch(translationRequests)
        }
        val translatedById = translated.results.associateBy(TranslationResult::regionId)
        val failuresById = translated.failures.associateBy(TranslationFailure::regionId)
        val results = mutableListOf<SemanticGroupTranslationResult>()
        val failures = mutableListOf<SemanticGroupTranslationFailure>()

        plan.groups.forEach { group ->
            val localResult = translatedById[group.groupId]
            when {
                group.isPreserved -> results += group.toResult(
                    translatedText = group.sourceText,
                    status = TranslationResultStatus.PRESERVED,
                    provider = EMBEDDED_V4_PROVIDER
                )
                localResult != null && localResult.translatedText.isNotBlank() -> {
                    results += group.toResult(
                        translatedText = localResult.translatedText.trim(),
                        status = TranslationResultStatus.TRANSLATED,
                        provider = "$EMBEDDED_V4_PROVIDER:${localResult.provider}",
                        detectedSourceLanguage = localResult.detectedSourceLanguage,
                        targetLanguage = localResult.targetLanguage
                    )
                }
                else -> {
                    val failure = failuresById[group.groupId]
                    failures += SemanticGroupTranslationFailure(
                        groupId = group.groupId,
                        code = failure?.code ?: if (localResult != null) {
                            "EMBEDDED_V4_INVALID_TRANSLATION"
                        } else {
                            "EMBEDDED_V4_LANGUAGE_UNSUPPORTED"
                        },
                        message = failure?.message ?: if (localResult != null) {
                            "The embedded v4 translator returned empty text"
                        } else {
                            "The embedded v4 group has no supported translation direction"
                        },
                        retryable = failure?.retryable ?: false,
                        cause = failure?.cause
                    )
                }
            }
        }
        return SemanticTranslationBatchResult(results = results, failures = failures)
    }
}

internal data class EmbeddedV4DocumentPlan(
    val groups: List<EmbeddedV4PlannedGroup>
)

internal data class EmbeddedV4PlannedGroup(
    val groupId: String,
    val sourceGroupIds: List<String>,
    val memberRegionIds: List<String>,
    val role: String,
    val translationUnit: String,
    val sourceText: String,
    val readingOrder: Int,
    val groupingConfidence: Float,
    val groupingEvidence: List<String>,
    val bounds: TranslationBounds,
    val renderSlots: List<TranslationBounds>,
    val sourceCoverSlots: List<TranslationBounds>,
    val sourceLineCount: Int,
    val regions: List<SemanticTranslationRegion>
) {
    val isPreserved: Boolean
        get() = translationUnit == "PRESERVED"

    fun toTranslationRequest(request: SemanticTranslationRequest): TranslationRequest? {
        val sourceLanguage = regions.mapNotNull(SemanticTranslationRegion::sourceLanguage)
            .distinct()
            .singleOrNull()
        val targetLanguage = regions.mapNotNull(SemanticTranslationRegion::targetLanguage)
            .distinct()
            .singleOrNull()
        if (sourceLanguage == null || targetLanguage == null) return null
        return TranslationRequest(
            requestId = request.requestId,
            regionId = groupId,
            text = sourceText,
            mode = request.mode,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            experimentalEngine = request.experimentalEngine
        )
    }

    fun toResult(
        translatedText: String,
        status: TranslationResultStatus,
        provider: String,
        detectedSourceLanguage: String? = regions.mapNotNull(
            SemanticTranslationRegion::sourceLanguage
        ).distinct().singleOrNull(),
        targetLanguage: String? = regions.mapNotNull(
            SemanticTranslationRegion::targetLanguage
        ).distinct().singleOrNull()
    ) = SemanticGroupTranslationResult(
        groupId = groupId,
        sourceGroupIds = sourceGroupIds,
        memberRegionIds = memberRegionIds,
        anchorBounds = bounds,
        role = role,
        groupingConfidence = groupingConfidence,
        translatedText = translatedText,
        provider = provider,
        status = status,
        detectedSourceLanguage = detectedSourceLanguage,
        targetLanguage = targetLanguage,
        layoutHint = layoutHint(translatedText)
    )

    private fun layoutHint(translatedText: String): SemanticLayoutHint {
        val textLines = sourceText.lineSequence().count(String::isNotBlank).coerceAtLeast(1)
        val lines = sourceLineCount.coerceAtLeast(memberRegionIds.size.coerceAtLeast(1))
            .coerceAtLeast(textLines)
        val expansion = visualWidthUnits(translatedText) / visualWidthUnits(sourceText)
        val preferredLines = when {
            expansion <= 1.15f -> lines
            expansion <= 1.8f -> lines + 1
            else -> lines + 2
        }.coerceIn(1, 24)
        return SemanticLayoutHint(
            preferredMaxLines = preferredLines,
            minimumTextScale = when {
                expansion <= 1.2f -> 0.86f
                expansion <= 1.8f -> 0.72f
                else -> 0.68f
            },
            maximumTextScale = 1f,
            lineSpacingMultiplier = if (lines >= 8) 0.92f else 1f,
            alignment = if (bounds.width < bounds.height * 3 && lines == 1) {
                "CENTER"
            } else {
                "START"
            },
            overflowStrategy = if (role == "BODY" && lines >= 4) {
                "REFLOW_THEN_SCALE_THEN_MORE"
            } else {
                "REFLOW_THEN_SCALE"
            },
            allowMore = role == "BODY" && lines >= 4,
            sourceLineCount = lines,
            layoutShape = if (renderSlots.size > 1) "FLOW_SLOTS" else "RECT",
            renderSlots = renderSlots,
            sourceCoverSlots = sourceCoverSlots
        )
    }
}

internal object EmbeddedV4RegionsFirstPlanner {
    private const val AUTHORITATIVE_CONFIDENCE = 0.90f

    fun build(request: SemanticTranslationRequest): EmbeddedV4DocumentPlan {
        require(request.viewportWidth > 0 && request.viewportHeight > 0) {
            "Embedded v4 viewport must be non-empty"
        }
        request.sources.forEach { source ->
            require(source.groupId.isNotBlank()) { "Embedded v4 source group IDs must be non-empty" }
            val memberIds = source.memberRegionIds
            val regionIds = source.regions.map(SemanticTranslationRegion::regionId)
            require(
                memberIds.size == memberIds.distinct().size &&
                    memberIds.toSet() == regionIds.toSet()
            ) {
                "Embedded v4 source members must match its atomic OCR regions"
            }
        }
        val advisoryByRegion = request.sources.flatMap { source ->
            source.memberRegionIds.map { regionId -> regionId to source }
        }.toMap()
        val orderedRegions = request.sources
            .flatMap(SemanticTranslationSource::regions)
            .sortedWith(
                compareBy<SemanticTranslationRegion>(SemanticTranslationRegion::readingOrder)
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
        require(orderedRegions.isNotEmpty()) { "Embedded v4 requires atomic OCR regions" }
        val distinctRegionIdCount = orderedRegions.map(SemanticTranslationRegion::regionId)
            .distinct()
            .size
        require(distinctRegionIdCount == orderedRegions.size) {
            "Embedded v4 region IDs must be unique"
        }
        require(orderedRegions.all { region ->
            region.bounds.left >= 0 && region.bounds.top >= 0 &&
                region.bounds.right <= request.viewportWidth &&
                region.bounds.bottom <= request.viewportHeight
        }) { "Embedded v4 region bounds must stay inside the viewport" }

        val groups = mutableListOf<MutableRegionGroup>()
        orderedRegions.forEach { region ->
            val next = MutableRegionGroup.from(region, advisoryByRegion[region.regionId])
            val previous = groups.lastOrNull()
            val decision = previous?.let { mergeDecision(it, next, request.viewportWidth) }
            if (previous != null && decision != null) {
                previous.merge(next, decision)
            } else {
                groups += next
            }
        }
        return EmbeddedV4DocumentPlan(groups.mapIndexed { index, group -> group.freeze(index) })
    }

    private fun mergeDecision(
        previous: MutableRegionGroup,
        next: MutableRegionGroup,
        viewportWidth: Int
    ): MergeDecision? {
        if (
            previous.translationUnit == "PRESERVED" || next.translationUnit == "PRESERVED" ||
            isProtectedRole(previous.role) || isProtectedRole(next.role) ||
            isStrongTextBoundary(previous.sourceText, next.sourceText)
        ) return null
        val first = previous.regions.last()
        val second = next.regions.first()
        val height = maxOf(first.bounds.height, second.bounds.height, 1)
        val gap = second.bounds.top - first.bounds.bottom
        if (gap < -(height / 3) || gap > (height * 1.25f).toInt()) return null
        val heightRatio = maxOf(first.bounds.height, second.bounds.height).toFloat() /
            minOf(first.bounds.height, second.bounds.height).coerceAtLeast(1)
        if (heightRatio > 1.85f) return null

        val sameBlock = sameBlockContinuation(first, second)
        val sameAdvisory = previous.sourceGroupIds.any(next.sourceGroupIds::contains)
        val overlap = horizontalOverlap(first.bounds, second.bounds).coerceAtLeast(0).toFloat() /
            minOf(first.bounds.width, second.bounds.width).coerceAtLeast(1)
        val leftDelta = abs(first.bounds.left - second.bounds.left)
        val rightDelta = abs(first.bounds.right - second.bounds.right)
        val sameColumn = overlap >= 0.68f && leftDelta <= height * 2
        val wrappedStep = overlap >= 0.25f && rightDelta <= height * 4 &&
            leftDelta <= (viewportWidth * 0.42f).toInt()
        if (!sameBlock && !sameColumn && !wrappedStep) return null
        if (previous.role != next.role && !sameBlock) return null
        val firstNextCharacter = next.sourceText.trimStart().firstOrNull()
        val continuation = !endsSentence(previous.sourceText) ||
            firstNextCharacter?.isLowerCase() == true || sameBlock
        if (!continuation) return null
        return when {
            sameBlock -> MergeDecision(0.98f, "OCR_BLOCK_CONTINUATION")
            sameAdvisory -> MergeDecision(0.96f, "CLIENT_GROUP_GEOMETRY_CONFIRMED")
            wrappedStep -> MergeDecision(0.92f, "WRAPPED_MEDIA_FLOW")
            else -> MergeDecision(0.94f, "VISUAL_LINE_CONTINUATION")
        }
    }

    private data class MergeDecision(val confidence: Float, val evidence: String)

    private class MutableRegionGroup(
        val sourceGroupIds: MutableList<String>,
        val memberRegionIds: MutableList<String>,
        var role: String,
        var translationUnit: String,
        var sourceText: String,
        val readingOrder: Int,
        var confidence: Float,
        val evidence: MutableList<String>,
        var bounds: TranslationBounds,
        val sourceSlots: MutableList<TranslationBounds>,
        var forceFlowShape: Boolean,
        val regions: MutableList<SemanticTranslationRegion>
    ) {
        fun merge(next: MutableRegionGroup, decision: MergeDecision) {
            sourceGroupIds += next.sourceGroupIds
            sourceGroupIds.deduplicate()
            memberRegionIds += next.memberRegionIds
            sourceText = "${sourceText.trim()}\n${next.sourceText.trim()}"
            bounds = union(bounds, next.bounds)
            sourceSlots += next.sourceSlots
            forceFlowShape = forceFlowShape || next.forceFlowShape
            confidence = minOf(confidence, next.confidence, decision.confidence)
            evidence += next.evidence
            evidence += decision.evidence
            evidence += "SERVER_V4_MERGE"
            evidence.deduplicate()
            if (role != next.role && (role == "BODY" || next.role == "BODY")) {
                role = "BODY"
                evidence += "ROLE_DRIFT_NORMALIZED"
            }
            translationUnit = if (
                translationUnit == "PRESERVED" && next.translationUnit == "PRESERVED"
            ) "PRESERVED" else "GROUP"
            regions += next.regions
        }

        fun freeze(index: Int): EmbeddedV4PlannedGroup {
            val slots = layoutSlots(sourceSlots, bounds, forceFlowShape)
            return EmbeddedV4PlannedGroup(
                groupId = stableGroupId(index, memberRegionIds),
                sourceGroupIds = sourceGroupIds.toList(),
                memberRegionIds = memberRegionIds.toList(),
                role = role,
                translationUnit = translationUnit,
                sourceText = sourceText,
                readingOrder = readingOrder,
                groupingConfidence = confidence,
                groupingEvidence = evidence.toList(),
                bounds = bounds,
                renderSlots = slots,
                sourceCoverSlots = regions.flatMap { region ->
                    region.componentBounds.ifEmpty { listOf(region.bounds) }
                },
                sourceLineCount = sourceSlots.size.coerceAtLeast(1),
                regions = regions.toList()
            )
        }

        companion object {
            fun from(
                region: SemanticTranslationRegion,
                advisory: SemanticTranslationSource?
            ): MutableRegionGroup {
                val role = inferredRole(region, advisory)
                val evidence = mutableListOf("SERVER_REGIONS_FIRST", "ATOMIC_REGION")
                if (advisory != null) evidence += "CLIENT_GROUP_ADVISORY"
                if (region.confidence < AUTHORITATIVE_CONFIDENCE) {
                    evidence += "LOW_OCR_TEXT_CONFIDENCE"
                }
                val sourceGroupId = advisory?.groupId?.trim().orEmpty()
                    .ifEmpty { region.groupId.trim() }
                return MutableRegionGroup(
                    sourceGroupIds = sourceGroupId
                        .takeIf(String::isNotEmpty)
                        ?.let { mutableListOf(it) } ?: mutableListOf(),
                    memberRegionIds = mutableListOf(region.regionId),
                    role = role,
                    translationUnit = if (isProtectedRole(role)) {
                        "PRESERVED"
                    } else {
                        advisory?.takeIf { it.memberRegionIds.size == 1 }?.translationUnit ?: "GROUP"
                    },
                    sourceText = region.text.trim(),
                    readingOrder = region.readingOrder,
                    confidence = 1f,
                    evidence = evidence,
                    bounds = region.bounds,
                    sourceSlots = mutableListOf(region.bounds),
                    forceFlowShape = advisory?.layoutShape == "FLOW_SLOTS",
                    regions = mutableListOf(region)
                )
            }
        }
    }

    private fun inferredRole(
        region: SemanticTranslationRegion,
        advisory: SemanticTranslationSource?
    ): String {
        val text = region.text.trim()
        return when {
            isStandaloneTimestamp(text) -> "TIMESTAMP"
            looksLikeCode(text) -> "CODE"
            looksLikeIdentifier(text) -> "IDENTIFIER"
            advisory?.role !in setOf(null, "TIMESTAMP", "IDENTIFIER", "CONTROL") ->
                checkNotNull(advisory).role
            else -> "BODY"
        }
    }

    private fun isProtectedRole(role: String): Boolean = role in setOf(
        "TIMESTAMP", "IDENTIFIER", "CONTROL", "CODE"
    )

    private fun layoutSlots(
        sourceSlots: List<TranslationBounds>,
        groupBounds: TranslationBounds,
        forceFlowShape: Boolean
    ): List<TranslationBounds> = if (
        forceFlowShape || sourceSlots.size <= 1 ||
        !isDenseRectangularTextFlow(sourceSlots, groupBounds)
    ) sourceSlots.toList() else listOf(groupBounds)

    private fun isDenseRectangularTextFlow(
        slots: List<TranslationBounds>,
        groupBounds: TranslationBounds
    ): Boolean {
        if (slots.size < 2 || groupBounds.width <= 0) return false
        val typicalHeight = slots.map { it.height.coerceAtLeast(1) }.sorted()[slots.size / 2]
        val maximumGap = slots.zipWithNext { first, second -> second.top - first.bottom }
            .maxOrNull() ?: 0
        if (maximumGap > typicalHeight) return false
        val nonFinal = slots.dropLast(1).ifEmpty { slots.take(1) }
        val leftRange = slots.maxOf { it.left } - slots.minOf { it.left }
        val rightRange = nonFinal.maxOf { it.right } - nonFinal.minOf { it.right }
        val leftTolerance = maxOf(typicalHeight * 2, groupBounds.width * 12 / 100)
        val rightTolerance = maxOf(typicalHeight * 3, groupBounds.width * 25 / 100)
        return leftRange <= leftTolerance && rightRange <= rightTolerance
    }

    private fun sameBlockContinuation(
        first: SemanticTranslationRegion,
        second: SemanticTranslationRegion
    ): Boolean = first.blockId != null && first.blockId == second.blockId &&
        (first.lineIndex == null || second.lineIndex == null ||
            second.lineIndex == first.lineIndex + 1)

    private fun isStrongTextBoundary(previous: String, next: String): Boolean =
        looksLikeShortLabel(previous) || looksLikeTitleLabel(previous) ||
            (looksLikeShortLabel(next) && endsSentence(previous)) ||
            (looksLikeTitleLabel(next) && endsSentence(previous)) ||
            isStandaloneTimestamp(previous) || isStandaloneTimestamp(next)

    private fun looksLikeTitleLabel(text: String): Boolean {
        val trimmed = text.trim()
        if (
            trimmed.isEmpty() || trimmed.last() in ".!?。！？，;" ||
            ',' in trimmed || ';' in trimmed
        ) return false
        val words = trimmed.split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty() || words.size > 6 || trimmed.length > 48) return false
        val connectors = setOf("a", "an", "and", "for", "in", "of", "the", "to")
        var meaningful = 0
        return words.all { word ->
            val normalized = word.trim { !it.isLetter() }
            when {
                normalized.isEmpty() -> false
                normalized.lowercase() in connectors -> true
                else -> {
                    meaningful++
                    normalized.first().isUpperCase()
                }
            }
        } && meaningful > 0
    }

    private fun looksLikeShortLabel(text: String): Boolean {
        val compact = text.count(Char::isLetterOrDigit)
        val letters = text.filter(Char::isLetter)
        return compact in 1..24 && text.split(Regex("\\s+")).size <= 4 &&
            letters.isNotEmpty() && letters.all(Char::isUpperCase)
    }

    private fun isStandaloneTimestamp(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length <= 32 && trimmed.any(Char::isDigit) && trimmed.all { character ->
            character.isDigit() || character.isWhitespace() ||
                character in charArrayOf(':', '-', '/', '.', 'A', 'P', 'M', 'a', 'p', 'm')
        }
    }

    private fun looksLikeIdentifier(text: String): Boolean {
        val trimmed = text.trim()
        return "://" in trimmed || trimmed.startsWith("www.") ||
            ('@' in trimmed && ' ' !in trimmed)
    }

    private fun looksLikeCode(text: String): Boolean {
        val tokens = text.split(Regex("\\s+")).filter(String::isNotEmpty)
        val methodAndPath = tokens.getOrNull(0) in setOf(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
        ) && tokens.getOrNull(1)?.startsWith('/') == true
        return methodAndPath || "/api/" in text || "//" in text || "::" in text
    }

    private fun endsSentence(text: String): Boolean =
        text.trimEnd().lastOrNull()?.let { it in ".!?。！？" } == true

    private fun stableGroupId(index: Int, regionIds: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(regionIds.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "embedded-v4-$index-$digest"
    }
}

private val TranslationBounds.width: Int
    get() = right - left

private val TranslationBounds.height: Int
    get() = bottom - top

private fun union(first: TranslationBounds, second: TranslationBounds) = TranslationBounds(
    left = minOf(first.left, second.left),
    top = minOf(first.top, second.top),
    right = maxOf(first.right, second.right),
    bottom = maxOf(first.bottom, second.bottom)
)

private fun horizontalOverlap(first: TranslationBounds, second: TranslationBounds): Int =
    minOf(first.right, second.right) - maxOf(first.left, second.left)

private fun MutableList<String>.deduplicate() {
    val seen = mutableSetOf<String>()
    retainAll { seen.add(it) }
}

private fun visualWidthUnits(text: String): Float = text.sumOf { character ->
    when {
        character.isWhitespace() -> 0.3
        character.code in 0x2E80..0x9FFF || character.code in 0xAC00..0xD7AF ||
            character.code in 0xF900..0xFAFF || character.code in 0xFF01..0xFF60 -> 1.0
        character.isAsciiPunctuation() -> 0.45
        character.isDigit() && character.code < 128 -> 0.58
        else -> 0.56
    }
}.toFloat().coerceAtLeast(1f)

private fun Char.isAsciiPunctuation(): Boolean = code in 0x21..0x2f || code in 0x3a..0x40 ||
    code in 0x5b..0x60 || code in 0x7b..0x7e

private const val EMBEDDED_V4_PROVIDER = "embedded-regions-first-v4"
