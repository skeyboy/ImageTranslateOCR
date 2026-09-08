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
    REGION_OCCUPANCY,
    FONT_SCALE_COMPATIBLE,
    FONT_SCALE_RELAXED_SAME_BLOCK,
    FONT_SCALE_RELAXED_PARAGRAPH,
    PARAGRAPH_CONTINUATION_RECOVERY,
    ROLE_DRIFT_SAME_BLOCK,
    DECORATED_CENTERED_CONTINUATION,
    URL_CONTINUATION,
    TOP_CLIPPED_CONTINUATION,
    BOTTOM_CLIPPED_CONTINUATION
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
        val memberGeometry = members.flatMap { member ->
            val bounds = member.textEraseBounds()
            val textHeights = member.componentTextHeightsPx.takeIf { it.size == bounds.size }
                ?: List(bounds.size) {
                    member.estimatedTextHeightPx
                        ?: (member.bounds.bottom - member.bounds.top).toFloat()
                }
            bounds.zip(textHeights)
        }.distinctBy { (bounds, _) ->
            listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        val memberBounds = memberGeometry.map { (bounds, _) -> copyRect(bounds) }
        val memberTextHeights = memberGeometry.map { (_, textHeight) -> textHeight }
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
            componentBounds = memberBounds,
            componentTextHeightsPx = memberTextHeights,
            estimatedTextHeightPx = medianFloatOrNull(
                members.mapNotNull(RecognizedText::estimatedTextHeightPx)
            ),
            typographyConfidence = members.minOf(RecognizedText::typographyConfidence),
            continuationAtTop = members.any(RecognizedText::continuationAtTop),
            continuationAtBottom = members.any(RecognizedText::continuationAtBottom)
        )
    }
}

internal object SemanticTextGrouper {
    private const val MAXIMUM_GROUP_CHARACTERS = 2_000
    private const val DENSE_SCREEN_MINIMUM_LINES = 12
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
            .let { heights ->
                val percentile = if (heights.size >= DENSE_SCREEN_MINIMUM_LINES) 7 else 5
                heights[((heights.size - 1) * percentile / 10).coerceIn(0, heights.lastIndex)]
                    .coerceAtLeast(1)
            }
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
                    val pairEvidence = appendEvidence(members, candidate)
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
            if (members.any { it.source.continuationAtTop }) {
                evidence += GroupingEvidence.TOP_CLIPPED_CONTINUATION
            }
            if (members.any { it.source.continuationAtBottom }) {
                evidence += GroupingEvidence.BOTTOM_CLIPPED_CONTINUATION
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
        members: List<Candidate>,
        second: Candidate
    ): Set<GroupingEvidence>? {
        val first = members.last()
        val urlContinuation = isUrlContinuation(members, second)
        if ((first.role.isStandalone || second.role.isStandalone) && !urlContinuation) return null
        val firstBlock = first.source.sourceBlockId
        val secondBlock = second.source.sourceBlockId
        val sameBlock = firstBlock != null && firstBlock == secondBlock
        val sameBlockRoleDriftContinuation =
            isSameBlockRoleDriftContinuation(members, second)
        val decoratedCenteredContinuation =
            isDecoratedCenteredContinuation(members, second)
        val titleContinuation = first.role == SemanticTextRole.TITLE &&
            second.role == SemanticTextRole.BODY &&
            first.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS
        if ((first.role == SemanticTextRole.TITLE || second.role == SemanticTextRole.TITLE) &&
            !titleContinuation && !sameBlockRoleDriftContinuation
        ) return null
        if (second.role == SemanticTextRole.LIST_ITEM) return null

        val establishedParagraphContinuation =
            isEstablishedParagraphContinuation(members, second)
        val differentKnownBlocks = firstBlock != null && secondBlock != null &&
            firstBlock != secondBlock
        val compactCrossBlockTail = isCompactCrossBlockTail(members, second)
        if (differentKnownBlocks && !establishedParagraphContinuation && !compactCrossBlockTail &&
            !decoratedCenteredContinuation && !urlContinuation
        ) {
            return null
        }
        if (sameBlock) {
            val firstLine = first.source.sourceLineIndex
            val secondLine = second.source.sourceLineIndex
            val firstEndLine = firstLine?.let { it + memberLineCount(first.source) - 1 }
            if (firstEndLine != null && secondLine != null && secondLine - firstEndLine > 1) {
                return null
            }
        }

        val firstBounds = first.source.bounds
        val secondBounds = second.source.bounds
        val fontCompatibility = fontCompatibility(first.source, second.source)
        val compactSameBlockTail = isCompactSameBlockTail(
            members,
            second,
            fontCompatibility
        )
        val relaxedSameBlockTail = fontCompatibility == FontCompatibility.RELAXED &&
            isRelaxedSameBlockParagraphTail(members, second)
        val relaxedSameBlockContinuation = fontCompatibility == FontCompatibility.RELAXED &&
            isRelaxedSameBlockWideContinuation(members, second)
        val relaxedParagraphContinuation = fontCompatibility == FontCompatibility.RELAXED &&
            establishedParagraphContinuation
        if (fontCompatibility != FontCompatibility.STRONG &&
            !relaxedSameBlockTail && !relaxedSameBlockContinuation &&
            !relaxedParagraphContinuation && !compactSameBlockTail && !compactCrossBlockTail &&
            !decoratedCenteredContinuation && !urlContinuation
        ) return null
        val firstLineHeight = representativeLineHeight(first.source)
        val secondLineHeight = representativeLineHeight(second.source)
        val minimumHeight = minOf(firstLineHeight, secondLineHeight).coerceAtLeast(1)
        val maximumHeight = maxOf(firstLineHeight, secondLineHeight).coerceAtLeast(1)

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
        val alignmentTolerance = maxOf(
            6,
            if (compactSameBlockTail) maximumHeight else minimumHeight
        )
        val leftAligned = abs(firstBounds.left - secondBounds.left) <= alignmentTolerance
        val firstCenter = firstBounds.left + rectWidth(firstBounds) / 2
        val secondCenter = secondBounds.left + rectWidth(secondBounds) / 2
        val centerAligned = abs(firstCenter - secondCenter) <= maxOf(
            alignmentTolerance,
            (minimumWidth * 0.18f).toInt()
        )
        val expandsLeftAfterWrap = secondBounds.left < firstBounds.left - alignmentTolerance &&
            secondBounds.right >= firstBounds.right - maximumHeight * 2
        if (!decoratedCenteredContinuation && !urlContinuation &&
            (overlapRatio < MINIMUM_HORIZONTAL_OVERLAP ||
            !(leftAligned || centerAligned || expandsLeftAfterWrap)
        )) {
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
            if (differentKnownBlocks || relaxedParagraphContinuation || compactSameBlockTail ||
                compactCrossBlockTail || decoratedCenteredContinuation
                    || urlContinuation
            ) {
                add(GroupingEvidence.PARAGRAPH_CONTINUATION_RECOVERY)
            }
            if (sameBlockRoleDriftContinuation) {
                add(GroupingEvidence.ROLE_DRIFT_SAME_BLOCK)
            }
            if (decoratedCenteredContinuation) {
                add(GroupingEvidence.DECORATED_CENTERED_CONTINUATION)
            }
            if (urlContinuation) add(GroupingEvidence.URL_CONTINUATION)
            add(
                if (fontCompatibility == FontCompatibility.RELAXED && sameBlock) {
                    GroupingEvidence.FONT_SCALE_RELAXED_SAME_BLOCK
                } else if (fontCompatibility == FontCompatibility.RELAXED) {
                    GroupingEvidence.FONT_SCALE_RELAXED_PARAGRAPH
                } else {
                    GroupingEvidence.FONT_SCALE_COMPATIBLE
                }
            )
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
        val memberTextHeights = members.mapNotNull { it.source.estimatedTextHeightPx }
            .filter { it > 0f }
        val nextTextHeight = next.source.estimatedTextHeightPx?.takeIf { it > 0f }
        val groupFontCompatibility = if (memberTextHeights.isEmpty() || nextTextHeight == null) {
            FontCompatibility.STRONG
        } else {
            fontCompatibility(medianFloat(memberTextHeights), nextTextHeight)
        }
        val relaxedSameBlockTail = groupFontCompatibility == FontCompatibility.RELAXED &&
            isRelaxedSameBlockParagraphTail(members, next)
        val relaxedSameBlockContinuation = groupFontCompatibility == FontCompatibility.RELAXED &&
            isRelaxedSameBlockWideContinuation(members, next)
        val relaxedParagraphContinuation = groupFontCompatibility == FontCompatibility.RELAXED &&
            fontCompatibility(previous.source, next.source) == FontCompatibility.RELAXED &&
            isEstablishedParagraphContinuation(members, next)
        val compactSameBlockTail = groupFontCompatibility == FontCompatibility.RELAXED &&
            isCompactSameBlockTail(
                members,
                next,
                fontCompatibility(previous.source, next.source)
            )
        val compactCrossBlockTail = groupFontCompatibility == FontCompatibility.RELAXED &&
            isCompactCrossBlockTail(members, next)
        val decoratedCenteredContinuation = isDecoratedCenteredContinuation(members, next)
        val urlContinuation = isUrlContinuation(members, next)
        if (groupFontCompatibility != FontCompatibility.STRONG &&
            !relaxedSameBlockTail && !relaxedSameBlockContinuation &&
            !relaxedParagraphContinuation && !compactSameBlockTail && !compactCrossBlockTail &&
            !decoratedCenteredContinuation && !urlContinuation
        ) return null

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
            val unfinishedParagraphContinuation = members.sumOf {
                memberLineCount(it.source)
            } >= MULTI_LINE_BODY_MINIMUM_LINES - 1 &&
                previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS &&
                abs(previous.source.bounds.left - next.source.bounds.left) <=
                    maxOf(6, representativeLineHeight(next.source))
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

    private fun fontCompatibility(first: RecognizedText, second: RecognizedText): FontCompatibility {
        val firstHeight = first.estimatedTextHeightPx?.takeIf { it > 0f }
        val secondHeight = second.estimatedTextHeightPx?.takeIf { it > 0f }
        if (firstHeight == null || secondHeight == null) return FontCompatibility.STRONG
        return fontCompatibility(firstHeight, secondHeight)
    }

    private fun fontCompatibility(first: Float, second: Float): FontCompatibility {
        val minimum = minOf(first, second).coerceAtLeast(1f)
        val maximum = maxOf(first, second).coerceAtLeast(1f)
        val ratio = minimum / maximum
        return when {
            ratio >= STRONG_FONT_SCALE_RATIO -> FontCompatibility.STRONG
            ratio >= MINIMUM_HEIGHT_RATIO -> FontCompatibility.RELAXED
            else -> FontCompatibility.INCOMPATIBLE
        }
    }

    private fun isRelaxedSameBlockParagraphTail(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        if (members.sumOf { memberLineCount(it.source) } <
            RELAXED_TAIL_MINIMUM_PREVIOUS_LINES ||
            memberLineCount(next.source) != 1 ||
            members.any { it.role != SemanticTextRole.BODY } ||
            next.role != SemanticTextRole.BODY ||
            members.last().source.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS
        ) return false
        val blockId = members.first().source.sourceBlockId ?: return false
        if (next.source.sourceBlockId != blockId ||
            members.any { it.source.sourceBlockId != blockId }
        ) return false
        val ranges = members.mapNotNull { sourceLineRange(it.source) }
        val nextRange = sourceLineRange(next.source) ?: return false
        if (ranges.size != members.size ||
            ranges.zipWithNext().any { (first, second) -> second.first != first.last + 1 } ||
            nextRange.first != ranges.last().last + 1
        ) return false
        return true
    }

    private fun isRelaxedSameBlockWideContinuation(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        val previous = members.last()
        val previousBlock = previous.source.sourceBlockId ?: return false
        val previousLine = previous.source.sourceLineIndex ?: return false
        val nextLine = next.source.sourceLineIndex ?: return false
        val previousWidth = rectWidth(previous.source.bounds).coerceAtLeast(1)
        val nextWidth = rectWidth(next.source.bounds).coerceAtLeast(1)
        return previous.role == SemanticTextRole.BODY &&
            next.role == SemanticTextRole.BODY &&
            next.source.sourceBlockId == previousBlock &&
            nextLine == previousLine + memberLineCount(previous.source) &&
            next.source.text.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() == true &&
            previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS &&
            minOf(previousWidth, nextWidth).toFloat() /
            maxOf(previousWidth, nextWidth).toFloat() >= WIDE_CONTINUATION_WIDTH_RATIO
    }

    private fun isEstablishedParagraphContinuation(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        if (members.sumOf { memberLineCount(it.source) } <
            ESTABLISHED_PARAGRAPH_MINIMUM_LINES ||
            members.any { it.role != SemanticTextRole.BODY } ||
            next.role != SemanticTextRole.BODY ||
            members.last().source.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS
        ) return false
        val previousBlock = members.last().source.sourceBlockId
        val nextBlock = next.source.sourceBlockId
        if (previousBlock != null && previousBlock == nextBlock) return true
        return next.source.text.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() == true
    }

    private fun isCompactSameBlockTail(
        members: List<Candidate>,
        next: Candidate,
        compatibility: FontCompatibility
    ): Boolean {
        val previous = members.last()
        return compatibility == FontCompatibility.RELAXED &&
            previous.source.sourceBlockId != null &&
            previous.source.sourceBlockId == next.source.sourceBlockId &&
            memberLineCount(next.source) == 1 &&
            compactCharacterCount(next.source.text) <= MAXIMUM_COMPACT_TAIL_CHARACTERS &&
            isTextualOrAcronymNumberContinuation(previous.source.text, next.source.text) &&
            previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS
    }

    private fun isCompactCrossBlockTail(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        val previous = members.last()
        val previousBlock = previous.source.sourceBlockId
        val nextBlock = next.source.sourceBlockId
        val previousHeight = representativeLineHeight(previous.source)
        val nextHeight = representativeLineHeight(next.source)
        val gap = next.source.bounds.top - previous.source.bounds.bottom
        val previousWidth = rectWidth(previous.source.bounds).coerceAtLeast(1)
        val nextWidth = rectWidth(next.source.bounds).coerceAtLeast(1)
        return members.isNotEmpty() &&
            previous.role == SemanticTextRole.BODY &&
            next.role == SemanticTextRole.BODY &&
            previousBlock != null && nextBlock != null && previousBlock != nextBlock &&
            fontCompatibility(previous.source, next.source) == FontCompatibility.RELAXED &&
            compactCharacterCount(next.source.text) <= MAXIMUM_COMPACT_TAIL_CHARACTERS &&
            isTextualOrAcronymNumberContinuation(previous.source.text, next.source.text) &&
            previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS &&
            previousWidth >= nextWidth * COMPACT_CROSS_BLOCK_MINIMUM_WIDTH_RATIO &&
            abs(previous.source.bounds.left - next.source.bounds.left) <= nextHeight &&
            gap >= 0 && gap <= maxOf(6, maxOf(previousHeight, nextHeight) / 2)
    }

    private fun isSameBlockRoleDriftContinuation(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        val previous = members.last()
        val startsBodyRoleDrift = previous.role == SemanticTextRole.BODY &&
            next.role == SemanticTextRole.TITLE
        val continuesBodyRoleDrift = previous.role == SemanticTextRole.TITLE &&
            next.role == SemanticTextRole.TITLE &&
            members.first().role == SemanticTextRole.BODY &&
            members.drop(1).all { it.role == SemanticTextRole.TITLE }
        if (!startsBodyRoleDrift && !continuesBodyRoleDrift) {
            return false
        }
        val previousBlock = previous.source.sourceBlockId ?: return false
        val previousLine = previous.source.sourceLineIndex ?: return false
        val nextLine = next.source.sourceLineIndex ?: return false
        return next.source.sourceBlockId == previousBlock &&
            nextLine == previousLine + memberLineCount(previous.source) &&
            previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS &&
            fontCompatibility(previous.source, next.source) != FontCompatibility.INCOMPATIBLE
    }

    private fun isDecoratedCenteredContinuation(
        members: List<Candidate>,
        next: Candidate
    ): Boolean {
        if (members.size != 1) return false
        val previous = members.single()
        val previousBlock = previous.source.sourceBlockId ?: return false
        val nextBlock = next.source.sourceBlockId ?: return false
        if (previousBlock == nextBlock || previous.role != SemanticTextRole.BODY ||
            next.role != SemanticTextRole.BODY || memberLineCount(previous.source) != 1 ||
            memberLineCount(next.source) != 1
        ) return false
        val previousText = previous.source.text.trim()
        val nextText = next.source.text.trim()
        val previousCharacters = compactCharacterCount(previousText)
        val nextCharacters = compactCharacterCount(nextText)
        if (previousCharacters !in 8..48 || nextCharacters !in 4..32 ||
            previousCharacters + nextCharacters > 64 ||
            previousText.lastOrNull() in SENTENCE_ENDINGS ||
            nextText.lastOrNull() !in SENTENCE_ENDINGS ||
            nextText.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() != true ||
            fontCompatibility(previous.source, next.source) == FontCompatibility.INCOMPATIBLE
        ) return false
        val previousBounds = previous.source.bounds
        val nextBounds = next.source.bounds
        val previousHeight = representativeLineHeight(previous.source)
        val nextHeight = representativeLineHeight(next.source)
        val maximumHeight = maxOf(previousHeight, nextHeight)
        val gap = nextBounds.top - previousBounds.bottom
        val overlap = minOf(previousBounds.right, nextBounds.right) -
            maxOf(previousBounds.left, nextBounds.left)
        val overlapRatio = overlap.coerceAtLeast(0).toFloat() /
            minOf(rectWidth(previousBounds), rectWidth(nextBounds)).coerceAtLeast(1)
        return gap in 0..maximumHeight && overlapRatio >= 0.25f &&
            nextBounds.left < previousBounds.left - minOf(previousHeight, nextHeight) &&
            rectWidth(nextBounds) <= rectWidth(previousBounds) * 0.8f
    }

    private fun isUrlContinuation(members: List<Candidate>, next: Candidate): Boolean {
        val previous = members.last()
        val attachesStandaloneUrl = previous.role == SemanticTextRole.BODY &&
            next.role == SemanticTextRole.IDENTIFIER &&
            STANDALONE_URL_OR_EMAIL.matches(next.source.text.trim()) &&
            previous.source.text.trimEnd().lastOrNull() !in SENTENCE_ENDINGS
        val continuesWrappedUrl = previous.role == SemanticTextRole.BODY &&
            next.role == SemanticTextRole.BODY &&
            URL_AT_END.containsMatchIn(previous.source.text.trim()) &&
            next.source.text.trimStart().firstOrNull() in URL_CONTINUATION_PREFIXES
        if ((!attachesStandaloneUrl && !continuesWrappedUrl) ||
            fontCompatibility(previous.source, next.source) == FontCompatibility.INCOMPATIBLE
        ) return false
        val previousBounds = previous.source.bounds
        val nextBounds = next.source.bounds
        val maximumHeight = maxOf(
            representativeLineHeight(previous.source),
            representativeLineHeight(next.source)
        )
        val gap = nextBounds.top - previousBounds.bottom
        return gap in 0..maximumHeight &&
            abs(previousBounds.left - nextBounds.left) <= maximumHeight * 2
    }

    private fun isTextualOrAcronymNumberContinuation(previous: String, next: String): Boolean {
        if (next.firstOrNull { it.isLetterOrDigit() }?.isLowerCase() == true) return true
        val compactNext = next.filter(Char::isLetterOrDigit)
        val previousToken = previous.trimEnd().takeLastWhile(Char::isLetterOrDigit)
        return compactNext.isNotEmpty() && compactNext.length <= MAXIMUM_ACRONYM_NUMBER_CHARACTERS &&
            compactNext.all(Char::isDigit) && previousToken.length in 2..8 &&
            previousToken.any(Char::isLetter) && previousToken.all { character ->
                !character.isLetter() || character.isUpperCase()
            }
    }

    private fun sourceLineRange(item: RecognizedText): IntRange? =
        item.sourceLineIndex?.let { start -> start..(start + memberLineCount(item) - 1) }

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
            looksLikeLowConfidenceIconGlyph(item, text) -> SemanticTextRole.CONTROL
            looksLikeLowConfidenceImageTextArtifact(item, text) -> SemanticTextRole.CONTROL
            SemanticContentClassifier.isStandaloneTemporalValue(text) ->
                SemanticTextRole.TIMESTAMP
            STANDALONE_URL_OR_EMAIL.matches(text) || PHONE_NUMBER.matches(text) ||
                APP_BRAND.matches(text) -> SemanticTextRole.IDENTIFIER
            SemanticContentClassifier.isStandaloneMetadata(text) ->
                SemanticTextRole.METADATA
            looksLikeTruncatedNaturalLanguageTitle(item, text, viewportWidth) ->
                SemanticTextRole.TITLE
            CONTROL_LABEL.containsMatchIn(text) || STATUS_LABEL.matches(text) ||
                endsWithEllipsis(text) -> SemanticTextRole.CONTROL
            looksLikeCode(text) -> SemanticTextRole.CODE
            LIST_PREFIX.containsMatchIn(text) -> SemanticTextRole.LIST_ITEM
            lineCount >= MULTI_LINE_BODY_MINIMUM_LINES &&
                compactCharacterCount(text) >= MULTI_LINE_BODY_MINIMUM_CHARACTERS ->
                SemanticTextRole.BODY
            lineCount <= TITLE_MAXIMUM_LINES &&
                looksLikeTitleText(text) &&
                representativeHeight >= medianHeight * 1.35f &&
                rectWidth(item.bounds) <= viewportWidth * 0.85f -> SemanticTextRole.TITLE
            else -> SemanticTextRole.BODY
        }
    }

    private fun looksLikeLowConfidenceIconGlyph(item: RecognizedText, text: String): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        val width = rectWidth(item.bounds).coerceAtLeast(1)
        val height = rectHeight(item.bounds).coerceAtLeast(1)
        val aspectRatio = width.toFloat() / height.toFloat()
        return visible.codePointCount(0, visible.length) in 1..ICON_GLYPH_MAXIMUM_CHARACTERS &&
            item.modelConfidence < ICON_GLYPH_MAXIMUM_MODEL_CONFIDENCE &&
            item.typographyConfidence < ICON_GLYPH_MAXIMUM_TYPOGRAPHY_CONFIDENCE &&
            aspectRatio in ICON_GLYPH_MINIMUM_ASPECT_RATIO..ICON_GLYPH_MAXIMUM_ASPECT_RATIO
    }

    private fun looksLikeLowConfidenceImageTextArtifact(
        item: RecognizedText,
        text: String
    ): Boolean {
        val visible = text.filterNot(Char::isWhitespace)
        val estimatedHeight = item.estimatedTextHeightPx?.takeIf { it > 0f } ?: return false
        val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
        return visible.length >= IMAGE_TEXT_ARTIFACT_MINIMUM_CHARACTERS &&
            tokens.size <= IMAGE_TEXT_ARTIFACT_MAXIMUM_TOKEN_COUNT &&
            (tokens.maxOfOrNull(String::length) ?: 0) >=
            IMAGE_TEXT_ARTIFACT_MINIMUM_CONTIGUOUS_CHARACTERS &&
            visible.all { character ->
                character.isLetterOrDigit() || character in "._-:/?=&%"
            } &&
            item.modelConfidence < IMAGE_TEXT_ARTIFACT_MAXIMUM_MODEL_CONFIDENCE &&
            rectHeight(item.bounds) >= estimatedHeight * IMAGE_TEXT_ARTIFACT_MINIMUM_HEIGHT_RATIO &&
            item.componentBounds.size <= 1
    }

    private fun looksLikeTitleText(text: String): Boolean {
        val trimmed = text.trim()
        val interior = trimmed.dropLastWhile { character ->
            character.isWhitespace() || character in SENTENCE_ENDINGS
        }
        return compactCharacterCount(trimmed) <= TITLE_MAXIMUM_CHARACTERS &&
            interior.none { it in SENTENCE_ENDINGS }
    }

    private fun looksLikeTruncatedNaturalLanguageTitle(
        item: RecognizedText,
        text: String,
        viewportWidth: Int
    ): Boolean {
        if (!endsWithEllipsis(text) || memberLineCount(item) != 1) return false
        if (rectWidth(item.bounds) > viewportWidth * 0.85f) return false
        val visiblePrefix = text.trimEnd().removeSuffix("...").removeSuffix("…").trimEnd()
        val latinWords = LATIN_WORD.findAll(visiblePrefix).map(MatchResult::value).toList()
        if (latinWords.size < TRUNCATED_TITLE_MINIMUM_LATIN_WORDS) return false
        val titleLikeWords = latinWords.count { word ->
            word.length >= 2 && (word.all(Char::isUpperCase) || word.first().isUpperCase())
        }
        return visiblePrefix.count(Char::isLetter) >= TRUNCATED_TITLE_MINIMUM_LETTERS &&
            titleLikeWords >= TRUNCATED_TITLE_MINIMUM_TITLE_CASE_WORDS
    }

    private fun memberLineCount(item: RecognizedText): Int = maxOf(
        1,
        item.componentBounds.size,
        item.text.lineSequence().count { it.isNotBlank() }
    )

    private fun representativeLineHeight(item: RecognizedText): Int {
        item.estimatedTextHeightPx?.takeIf { it > 0f }?.let { return it.toInt().coerceAtLeast(1) }
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

    private enum class FontCompatibility {
        STRONG,
        RELAXED,
        INCOMPATIBLE
    }

    private val STANDALONE_URL_OR_EMAIL = Regex(
        "(?i)^(?:(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}|" +
            "(?:[\\p{L}\\p{N}-]+\\.)+(?:com|org|net|io|ai|cn)(?:/\\S*)?)$"
    )
    private val URL_AT_END = Regex("(?i)(?:https?://|www\\.)\\S+$")
    private val URL_CONTINUATION_PREFIXES = setOf('/', '?', '#', '&')
    private val PHONE_NUMBER = Regex("^\\+?\\d(?:[\\d ()-]{5,}\\d)$")
    private val APP_BRAND = Regex("(?i)^(?:instagram|whatsapp|facebook|telegram)$")
    private val LIST_PREFIX = Regex(
        "^(?:>\\s*|[•·‣◦*-]\\s*|(?:\\d+[.)]|[A-Za-z][.)])\\s+)"
    )
    private val CONTROL_LABEL = Regex(
        "(?i)^(?:(?:tweet|iweet)\\s+)?whats?app$|^privacy(?: policy)?$"
    )
    private val STATUS_LABEL = Regex(
        "(?i)^(?:\\d{1,2}:\\d{2}\\s*[|·]\\s*[\\d.]+[KMG]?/s|(?:HD\\s*)?[345]G|" +
            "\\d+\\s*[A-Z]{1,3}|\\d+\\s*人(?:在线|上線))$"
    )
    private val LATIN_WORD = Regex("[A-Za-z]+")
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？')
    private val PARAGRAPH_EVIDENCE = setOf(
        GroupingEvidence.OCR_BLOCK,
        GroupingEvidence.GEOMETRY_INFERRED,
        GroupingEvidence.WRAPPED_FLOW,
        GroupingEvidence.PUNCTUATION_CONTINUATION,
        GroupingEvidence.PARAGRAPH_CONTINUATION_RECOVERY
    )

    private const val MINIMUM_ROW_OVERLAP_RATIO = 0.55f
    private const val STRONG_FONT_SCALE_RATIO = 0.78f
    private const val RELAXED_TAIL_MINIMUM_PREVIOUS_LINES = 3
    private const val ESTABLISHED_PARAGRAPH_MINIMUM_LINES = 2
    private const val MINIMUM_COMPANION_GAP_PX = 8
    private const val MAXIMUM_COMPACT_METADATA_CHARACTERS = 18
    private const val MAXIMUM_COMPACT_TAIL_CHARACTERS = 20
    private const val COMPACT_CROSS_BLOCK_MINIMUM_WIDTH_RATIO = 3
    private const val MAXIMUM_ACRONYM_NUMBER_CHARACTERS = 6
    private const val WIDE_CONTINUATION_WIDTH_RATIO = 0.70f
    private const val TITLE_MAXIMUM_CHARACTERS = 64
    private const val TRUNCATED_TITLE_MINIMUM_LATIN_WORDS = 3
    private const val TRUNCATED_TITLE_MINIMUM_TITLE_CASE_WORDS = 2
    private const val TRUNCATED_TITLE_MINIMUM_LETTERS = 12
    private const val ICON_GLYPH_MAXIMUM_CHARACTERS = 3
    private const val ICON_GLYPH_MAXIMUM_MODEL_CONFIDENCE = 0.70f
    private const val ICON_GLYPH_MAXIMUM_TYPOGRAPHY_CONFIDENCE = 0.70f
    private const val ICON_GLYPH_MINIMUM_ASPECT_RATIO = 0.40f
    private const val ICON_GLYPH_MAXIMUM_ASPECT_RATIO = 1.60f
    private const val IMAGE_TEXT_ARTIFACT_MINIMUM_CHARACTERS = 32
    private const val IMAGE_TEXT_ARTIFACT_MINIMUM_CONTIGUOUS_CHARACTERS = 32
    private const val IMAGE_TEXT_ARTIFACT_MAXIMUM_TOKEN_COUNT = 4
    private const val IMAGE_TEXT_ARTIFACT_MAXIMUM_MODEL_CONFIDENCE = 0.80f
    private const val IMAGE_TEXT_ARTIFACT_MINIMUM_HEIGHT_RATIO = 3f

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

private fun medianFloat(values: List<Float>): Float = values.sorted().let { it[it.size / 2] }

private fun medianFloatOrNull(values: List<Float>): Float? =
    values.takeIf { it.isNotEmpty() }?.let(::medianFloat)

private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect = Rect().apply {
    this.left = left
    this.top = top
    this.right = right
    this.bottom = bottom
}
