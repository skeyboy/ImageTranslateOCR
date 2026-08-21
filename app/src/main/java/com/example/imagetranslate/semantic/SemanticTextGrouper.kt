package com.example.imagetranslate.semantic

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import kotlin.math.abs

internal enum class SemanticTextRole {
    TITLE,
    BODY,
    LIST_ITEM,
    METADATA,
    CONTROL,
    CODE,
    IDENTIFIER,
    TIMESTAMP
}

internal enum class GroupingEvidence {
    OCR_BLOCK,
    LEFT_ALIGNED,
    CENTER_ALIGNED,
    HORIZONTAL_OVERLAP,
    LINE_GAP,
    PUNCTUATION_CONTINUATION,
    WRAPPED_FLOW,
    GEOMETRY_INFERRED,
    REGION_OCCUPANCY
}

internal data class SemanticTextGroup(
    val groupId: String,
    val members: List<RecognizedText>,
    val sourceText: String,
    val unionBounds: Rect,
    val readingOrder: Int,
    val role: SemanticTextRole,
    val groupingConfidence: Float,
    val evidence: Set<GroupingEvidence>,
    val renderSlots: List<Rect>
) {
    fun toRecognizedText(): RecognizedText {
        val weights = members.map { member ->
            member.text.count(Char::isLetterOrDigit).coerceAtLeast(1)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val blockIds = members.mapNotNull(RecognizedText::sourceBlockId).distinct()
        val scripts = members.map(RecognizedText::recognizerScript).distinct()
        val memberBounds = members
            .flatMap(RecognizedText::textEraseBounds)
            .distinctBy { bounds ->
                listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
            .map(::copyRect)
        return RecognizedText(
            text = sourceText,
            bounds = copyRect(unionBounds),
            consensusScore = minOf(
                groupingConfidence,
                members.minOf(RecognizedText::consensusScore)
            ),
            passCount = members.minOf(RecognizedText::passCount),
            modelConfidence = members.zip(weights).sumOf { (member, weight) ->
                (member.modelConfidence * weight).toDouble()
            }.toFloat() / totalWeight,
            recognizerScript = scripts.singleOrNull() ?: RecognizerScript.FUSED,
            sourceBlockId = blockIds.singleOrNull(),
            sourceLineIndex = members.mapNotNull(RecognizedText::sourceLineIndex).minOrNull(),
            componentBounds = memberBounds
        )
    }
}

internal object SemanticTextGrouper {
    private const val MAXIMUM_GROUP_CHARACTERS = 2_000
    private const val MULTI_LINE_BODY_MINIMUM_LINES = 3
    private const val MULTI_LINE_BODY_MINIMUM_CHARACTERS = 24
    private const val TITLE_MAXIMUM_LINES = 2
    private const val MINIMUM_HEIGHT_RATIO = 0.65f
    private const val MINIMUM_HORIZONTAL_OVERLAP = 0.45f
    private const val MAXIMUM_BLOCK_GAP_RATIO = 1.05f
    private const val MAXIMUM_GEOMETRY_GAP_RATIO = 1.05f
    private const val DUPLICATE_AXIS_OVERLAP = 0.7f

    fun group(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): List<SemanticTextGroup> {
        if (recognized.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) {
            return emptyList()
        }
        val distinct = selectDistinctLines(recognized, viewportWidth, viewportHeight)
        if (distinct.isEmpty()) return emptyList()
        val medianHeight = distinct.map(::representativeLineHeight).sorted()
            .let { heights -> heights[(heights.size - 1) / 2].coerceAtLeast(1) }
        val undecoratedCandidates = distinct.map { item ->
            Candidate(item, roleFor(item, viewportWidth, medianHeight))
        }
        val candidatesWithCompanions = undecoratedCandidates.mapIndexed { index, candidate ->
            candidate.copy(
                hasHorizontalCompanion = undecoratedCandidates.indices.any { otherIndex ->
                    otherIndex != index && horizontalCompanions(
                        candidate.source,
                        undecoratedCandidates[otherIndex].source
                    )
                }
            )
        }
        val remaining = candidatesWithCompanions.mapIndexed { index, candidate ->
            val phoneCompanion = candidatesWithCompanions.indices.any { otherIndex ->
                otherIndex != index && PHONE_NUMBER.matches(
                    candidatesWithCompanions[otherIndex].source.text.trim()
                ) && horizontalCompanions(
                    candidate.source,
                    candidatesWithCompanions[otherIndex].source
                )
            }
            if (candidate.role == SemanticTextRole.BODY && phoneCompanion &&
                compactCharacterCount(candidate.source.text) <= MAXIMUM_COMPACT_METADATA_CHARACTERS
            ) {
                candidate.copy(role = SemanticTextRole.METADATA)
            } else {
                candidate
            }
        }.toMutableList()
        val groups = mutableListOf<SemanticTextGroup>()

        while (remaining.isNotEmpty()) {
            val members = mutableListOf(remaining.removeAt(0))
            val evidence = mutableSetOf<GroupingEvidence>()
            while (members.sumOf { it.source.text.length } < MAXIMUM_GROUP_CHARACTERS) {
                val previous = members.last()
                val next = remaining.indices.mapNotNull { index ->
                    val candidate = remaining[index]
                    val pairEvidence = appendEvidence(previous, candidate)
                        ?: return@mapNotNull null
                    val regionEvidence = regionAssociationEvidence(
                        members,
                        candidate,
                        viewportWidth
                    ) ?: return@mapNotNull null
                    AppendCandidate(index, candidate, pairEvidence + regionEvidence)
                }.minWithOrNull(
                    compareBy<AppendCandidate>(
                        { candidate ->
                            (candidate.candidate.source.bounds.top -
                                previous.source.bounds.bottom).coerceAtLeast(0)
                        },
                        { candidate ->
                            abs(candidate.candidate.source.bounds.left -
                                previous.source.bounds.left)
                        }
                    )
                ) ?: break
                if (members.sumOf { it.source.text.length } + next.candidate.source.text.length >
                    MAXIMUM_GROUP_CHARACTERS
                ) {
                    break
                }
                members += remaining.removeAt(next.index)
                evidence += next.evidence
            }
            groups += createGroup(groups.size, members, evidence)
        }
        return groups
    }

    private fun selectDistinctLines(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): List<RecognizedText> {
        val selected = mutableListOf<RecognizedText>()
        recognized.asSequence()
            .filter { item ->
                item.text.isNotBlank() && rectWidth(item.bounds) > 0 &&
                    rectHeight(item.bounds) > 0 &&
                    item.bounds.left < viewportWidth && item.bounds.top < viewportHeight &&
                    item.bounds.right > 0 && item.bounds.bottom > 0
            }
            .sortedWith(
                compareByDescending<RecognizedText> { item ->
                    item.modelConfidence * 0.45f + item.consensusScore * 0.4f +
                        item.passCount.coerceAtMost(2) * 0.075f
                }.thenByDescending { item -> item.text.count(Char::isLetterOrDigit) }
            )
            .forEach { candidate ->
                if (selected.none { existing -> sameVisualLine(existing, candidate) }) {
                    selected += candidate
                }
            }
        return selected.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun appendEvidence(
        first: Candidate,
        second: Candidate
    ): Set<GroupingEvidence>? {
        if (first.role.isStandalone || second.role.isStandalone) return null
        val titleContinuation = first.role == SemanticTextRole.TITLE &&
            second.role == SemanticTextRole.BODY &&
            first.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS
        if ((first.role == SemanticTextRole.TITLE || second.role == SemanticTextRole.TITLE) &&
            !titleContinuation
        ) return null
        if (second.role == SemanticTextRole.LIST_ITEM) return null

        val firstBlock = first.source.sourceBlockId
        val secondBlock = second.source.sourceBlockId
        if (firstBlock != null && secondBlock != null && firstBlock != secondBlock) return null
        val sameBlock = firstBlock != null && firstBlock == secondBlock
        if (sameBlock) {
            val firstLine = first.source.sourceLineIndex
            val secondLine = second.source.sourceLineIndex
            if (firstLine != null && secondLine != null && secondLine - firstLine > 1) return null
        }

        val firstBounds = first.source.bounds
        val secondBounds = second.source.bounds
        val minimumHeight = minOf(rectHeight(firstBounds), rectHeight(secondBounds)).coerceAtLeast(1)
        val maximumHeight = maxOf(rectHeight(firstBounds), rectHeight(secondBounds)).coerceAtLeast(1)
        if (minimumHeight.toFloat() / maximumHeight < MINIMUM_HEIGHT_RATIO) return null

        val verticalGap = secondBounds.top - firstBounds.bottom
        val maximumGapRatio = if (sameBlock) {
            MAXIMUM_BLOCK_GAP_RATIO
        } else {
            MAXIMUM_GEOMETRY_GAP_RATIO
        }
        if (verticalGap < -minimumHeight / 4 ||
            verticalGap > maxOf(4, (maximumHeight * maximumGapRatio).toInt())
        ) {
            return null
        }

        val horizontalOverlap = minOf(firstBounds.right, secondBounds.right) -
            maxOf(firstBounds.left, secondBounds.left)
        val minimumWidth = minOf(rectWidth(firstBounds), rectWidth(secondBounds)).coerceAtLeast(1)
        val overlapRatio = horizontalOverlap.coerceAtLeast(0).toFloat() / minimumWidth
        val alignmentTolerance = maxOf(6, minimumHeight)
        val leftAligned = abs(firstBounds.left - secondBounds.left) <= alignmentTolerance
        val firstCenter = firstBounds.left + rectWidth(firstBounds) / 2
        val secondCenter = secondBounds.left + rectWidth(secondBounds) / 2
        val centerAligned = abs(firstCenter - secondCenter) <= maxOf(
            alignmentTolerance,
            (minimumWidth * 0.18f).toInt()
        )
        val expandsLeftAfterWrap = secondBounds.left < firstBounds.left - alignmentTolerance &&
            secondBounds.right >= firstBounds.right - maximumHeight * 2
        if (overlapRatio < MINIMUM_HORIZONTAL_OVERLAP ||
            !(leftAligned || centerAligned || expandsLeftAfterWrap)
        ) {
            return null
        }

        val sentenceBoundary = first.source.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS
        if (!sameBlock && sentenceBoundary && verticalGap > maxOf(4, minimumHeight / 3)) {
            return null
        }

        return buildSet {
            if (sameBlock) add(GroupingEvidence.OCR_BLOCK)
            else add(GroupingEvidence.GEOMETRY_INFERRED)
            if (leftAligned) add(GroupingEvidence.LEFT_ALIGNED)
            if (centerAligned) add(GroupingEvidence.CENTER_ALIGNED)
            if (expandsLeftAfterWrap) add(GroupingEvidence.WRAPPED_FLOW)
            if (overlapRatio >= MINIMUM_HORIZONTAL_OVERLAP) {
                add(GroupingEvidence.HORIZONTAL_OVERLAP)
            }
            add(GroupingEvidence.LINE_GAP)
            if (!sentenceBoundary) add(GroupingEvidence.PUNCTUATION_CONTINUATION)
        }
    }

    /**
     * Checks whether a line belongs to the occupied text region of the whole candidate group.
     * This prevents compact labels, metadata, and side-by-side fields from being absorbed by a
     * nearby paragraph merely because their left edges and vertical gaps happen to be similar.
     */
    private fun regionAssociationEvidence(
        members: List<Candidate>,
        next: Candidate,
        viewportWidth: Int
    ): Set<GroupingEvidence>? {
        val previous = members.last()
        val previousWidth = rectWidth(previous.source.bounds).coerceAtLeast(1)
        val nextWidth = rectWidth(next.source.bounds).coerceAtLeast(1)
        val compactPrevious = compactCharacterCount(previous.source.text) <=
            MAXIMUM_COMPACT_METADATA_CHARACTERS
        val compactNext = compactCharacterCount(next.source.text) <=
            MAXIMUM_COMPACT_METADATA_CHARACTERS

        if (members.size == 1) {
            val previousIsNarrow = previousWidth <= viewportWidth * NARROW_REGION_WIDTH_RATIO
            val nextOccupiesMainColumn = nextWidth >= viewportWidth * MAIN_COLUMN_WIDTH_RATIO ||
                nextWidth >= previousWidth * OCCUPANCY_EXPANSION_RATIO
            val compactRowLabel = compactPrevious &&
                (previous.hasHorizontalCompanion || previous.role == SemanticTextRole.LIST_ITEM)
            if (previousIsNarrow && nextOccupiesMainColumn && compactRowLabel) return null

            val previousOccupiesMainColumn =
                previousWidth >= viewportWidth * MAIN_COLUMN_WIDTH_RATIO
            val nextIsMetadataSized = nextWidth <= previousWidth * METADATA_WIDTH_RATIO &&
                nextWidth <= viewportWidth * NARROW_REGION_WIDTH_RATIO
            if (previousOccupiesMainColumn && nextIsMetadataSized && compactNext &&
                (endsWithEllipsis(previous.source.text) || next.hasHorizontalCompanion)
            ) {
                return null
            }
        } else {
            val dominantWidth = median(members.map { member ->
                rectWidth(member.source.bounds).coerceAtLeast(1)
            })
            val nextIsMetadataSized = nextWidth <= dominantWidth * METADATA_WIDTH_RATIO
            val unfinishedParagraphContinuation = members.size >=
                MULTI_LINE_BODY_MINIMUM_LINES - 1 &&
                previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS &&
                abs(previous.source.bounds.left - next.source.bounds.left) <=
                    maxOf(6, rectHeight(next.source.bounds))
            if (compactNext && next.hasHorizontalCompanion && nextIsMetadataSized &&
                !unfinishedParagraphContinuation
            ) return null
        }

        return setOf(GroupingEvidence.REGION_OCCUPANCY)
    }

    private fun horizontalCompanions(
        first: RecognizedText,
        second: RecognizedText
    ): Boolean {
        val verticalOverlap = minOf(first.bounds.bottom, second.bounds.bottom) -
            maxOf(first.bounds.top, second.bounds.top)
        if (verticalOverlap <= 0) return false
        val minimumHeight = minOf(rectHeight(first.bounds), rectHeight(second.bounds))
            .coerceAtLeast(1)
        if (verticalOverlap.toFloat() / minimumHeight < MINIMUM_ROW_OVERLAP_RATIO) return false

        val horizontalGap = maxOf(
            second.bounds.left - first.bounds.right,
            first.bounds.left - second.bounds.right
        )
        return horizontalGap >= maxOf(MINIMUM_COMPANION_GAP_PX, minimumHeight / 3)
    }

    private fun compactCharacterCount(text: String): Int =
        text.count { character -> character.isLetterOrDigit() }

    private fun endsWithEllipsis(text: String): Boolean {
        val trimmed = text.trimEnd()
        return trimmed.endsWith("...") || trimmed.endsWith('…')
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun createGroup(
        readingOrder: Int,
        candidates: List<Candidate>,
        evidence: Set<GroupingEvidence>
    ): SemanticTextGroup {
        val members = candidates.map(Candidate::source)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bounds = unionBounds(members.map(RecognizedText::bounds))
        val roles = candidates.map(Candidate::role).distinct()
        val role = when {
            candidates.size >= MULTI_LINE_BODY_MINIMUM_LINES &&
                evidence.any { it in PARAGRAPH_EVIDENCE } -> SemanticTextRole.BODY
            roles.size == 1 -> roles.single()
            SemanticTextRole.TITLE in roles && roles.all {
                it == SemanticTextRole.TITLE || it == SemanticTextRole.BODY
            } -> SemanticTextRole.TITLE
            else -> SemanticTextRole.BODY
        }
        val confidence = when {
            members.size == 1 -> 1f
            GroupingEvidence.OCR_BLOCK in evidence -> 0.95f
            GroupingEvidence.PUNCTUATION_CONTINUATION in evidence -> 0.82f
            else -> 0.72f
        }
        val identity = members.joinToString("|") { member ->
            "${member.sourceBlockId}:${member.sourceLineIndex}:" +
                "${member.bounds.left},${member.bounds.top},${member.bounds.right}," +
                "${member.bounds.bottom}:${member.text.trim()}"
        }
        return SemanticTextGroup(
            groupId = "semantic-${Integer.toHexString(identity.hashCode())}",
            members = members,
            sourceText = members.joinToString("\n") { member -> member.text.trim() },
            unionBounds = bounds,
            readingOrder = readingOrder,
            role = role,
            groupingConfidence = confidence,
            evidence = evidence,
            renderSlots = SemanticRenderShape.slots(
                members.flatMap(RecognizedText::textEraseBounds),
                bounds
            )
        )
    }

    private fun roleFor(
        item: RecognizedText,
        viewportWidth: Int,
        medianHeight: Int
    ): SemanticTextRole {
        val text = item.text.trim()
        val lineCount = memberLineCount(item)
        val representativeHeight = representativeLineHeight(item)
        return when {
            SemanticContentClassifier.isStandaloneTemporalValue(text) ->
                SemanticTextRole.TIMESTAMP
            URL_OR_EMAIL.containsMatchIn(text) || PHONE_NUMBER.matches(text) ||
                APP_BRAND.matches(text) -> SemanticTextRole.IDENTIFIER
            SemanticContentClassifier.isStandaloneMetadata(text) ->
                SemanticTextRole.METADATA
            CONTROL_LABEL.containsMatchIn(text) || STATUS_LABEL.matches(text) ||
                endsWithEllipsis(text) -> SemanticTextRole.CONTROL
            looksLikeCode(text) -> SemanticTextRole.CODE
            LIST_PREFIX.containsMatchIn(text) -> SemanticTextRole.LIST_ITEM
            lineCount >= MULTI_LINE_BODY_MINIMUM_LINES &&
                compactCharacterCount(text) >= MULTI_LINE_BODY_MINIMUM_CHARACTERS ->
                SemanticTextRole.BODY
            lineCount <= TITLE_MAXIMUM_LINES &&
                representativeHeight >= medianHeight * 1.35f &&
                rectWidth(item.bounds) <= viewportWidth * 0.85f -> SemanticTextRole.TITLE
            else -> SemanticTextRole.BODY
        }
    }

    private fun memberLineCount(item: RecognizedText): Int = maxOf(
        1,
        item.componentBounds.size,
        item.text.lineSequence().count { it.isNotBlank() }
    )

    private fun representativeLineHeight(item: RecognizedText): Int {
        val componentHeights = item.componentBounds.map(::rectHeight).filter { it > 0 }.sorted()
        if (componentHeights.isNotEmpty()) {
            return componentHeights[(componentHeights.size - 1) / 2]
        }
        return (rectHeight(item.bounds) / memberLineCount(item)).coerceAtLeast(1)
    }

    private fun sameVisualLine(first: RecognizedText, second: RecognizedText): Boolean {
        val horizontalOverlap = minOf(first.bounds.right, second.bounds.right) -
            maxOf(first.bounds.left, second.bounds.left)
        val verticalOverlap = minOf(first.bounds.bottom, second.bounds.bottom) -
            maxOf(first.bounds.top, second.bounds.top)
        if (horizontalOverlap <= 0 || verticalOverlap <= 0) return false
        val horizontalRatio = horizontalOverlap.toFloat() /
            minOf(rectWidth(first.bounds), rectWidth(second.bounds)).coerceAtLeast(1)
        val verticalRatio = verticalOverlap.toFloat() /
            minOf(rectHeight(first.bounds), rectHeight(second.bounds)).coerceAtLeast(1)
        return horizontalRatio >= DUPLICATE_AXIS_OVERLAP &&
            verticalRatio >= DUPLICATE_AXIS_OVERLAP
    }

    private fun unionBounds(items: List<Rect>): Rect = rect(
        left = items.minOf(Rect::left),
        top = items.minOf(Rect::top),
        right = items.maxOf(Rect::right),
        bottom = items.maxOf(Rect::bottom)
    )

    private fun rectWidth(bounds: Rect): Int = bounds.right - bounds.left

    private fun rectHeight(bounds: Rect): Int = bounds.bottom - bounds.top

    private fun looksLikeCode(text: String): Boolean = text.contains("::") ||
        text.contains("->") || text.contains("=>") ||
        (text.contains('_') && text.none(Char::isWhitespace)) ||
        (text.contains('{') && text.contains('}'))

    private val SemanticTextRole.isStandalone: Boolean
        get() = this == SemanticTextRole.CODE || this == SemanticTextRole.IDENTIFIER ||
            this == SemanticTextRole.TIMESTAMP || this == SemanticTextRole.METADATA ||
            this == SemanticTextRole.CONTROL

    private data class Candidate(
        val source: RecognizedText,
        val role: SemanticTextRole,
        val hasHorizontalCompanion: Boolean = false
    )

    private data class AppendCandidate(
        val index: Int,
        val candidate: Candidate,
        val evidence: Set<GroupingEvidence>
    )

    private val URL_OR_EMAIL = Regex(
        "(?i)(?:https?://|www\\.|[\\w.+-]+@[\\w.-]+\\.|" +
            "(?:[\\p{L}\\p{N}-]+\\.)+(?:com|org|net|io|ai|cn)\\b)"
    )
    private val PHONE_NUMBER = Regex("^\\+?\\d(?:[\\d ()-]{5,}\\d)$")
    private val APP_BRAND = Regex("(?i)^(?:instagram|whatsapp|facebook|telegram)$")
    private val LIST_PREFIX = Regex("^(?:[•·‣◦*-]|\\d+[.)]|[A-Za-z][.)])\\s+")
    private val CONTROL_LABEL = Regex(
        "(?i)^(?:(?:tweet|iweet)\\s+)?whats?app$|^privacy(?: policy)?$"
    )
    private val STATUS_LABEL = Regex(
        "(?i)^(?:\\d{1,2}:\\d{2}\\s*[|·]\\s*[\\d.]+[KMG]?/s|(?:HD\\s*)?[345]G|" +
            "\\d+\\s*[A-Z]{1,3})$"
    )
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？')
    private val PARAGRAPH_EVIDENCE = setOf(
        GroupingEvidence.OCR_BLOCK,
        GroupingEvidence.GEOMETRY_INFERRED,
        GroupingEvidence.WRAPPED_FLOW,
        GroupingEvidence.PUNCTUATION_CONTINUATION
    )

    private const val MINIMUM_ROW_OVERLAP_RATIO = 0.55f
    private const val MINIMUM_COMPANION_GAP_PX = 8
    private const val MAXIMUM_COMPACT_METADATA_CHARACTERS = 18
    private const val NARROW_REGION_WIDTH_RATIO = 0.35f
    private const val MAIN_COLUMN_WIDTH_RATIO = 0.45f
    private const val OCCUPANCY_EXPANSION_RATIO = 2f
    private const val METADATA_WIDTH_RATIO = 0.45f
}

private fun copyRect(bounds: Rect): Rect = rect(
    bounds.left,
    bounds.top,
    bounds.right,
    bounds.bottom
)

private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
    this.left = left
    this.top = top
    this.right = right
    this.bottom = bottom
}
