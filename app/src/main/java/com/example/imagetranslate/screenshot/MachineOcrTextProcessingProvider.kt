package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.semantic.SemanticContentClassifier
import kotlin.math.abs
import kotlin.math.max

internal data class MachineTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val blockId: String? = null,
    val lineIndex: Int? = null,
    val estimatedTextHeightPx: Float? = null
)

internal enum class MachineParagraphKind {
    BODY,
    LIST_ITEM
}

private data class MachineListAnchor(
    val contentIndex: Int,
    val markerIndex: Int?,
    val marker: String,
    val markerIsExternal: Boolean,
    val markerBounds: Rect,
    val contentLeft: Int,
    val lineHeight: Int,
    val blockId: String?
)

internal data class MachineDetectedListItem(
    val memberIndices: List<Int>,
    val markerIndex: Int?,
    val marker: String,
    val markerIsExternal: Boolean,
    val markerBounds: Rect
)

internal object MachineListStructurePolicy {
    fun startsListItem(text: String): Boolean =
        standaloneMarker(text) != null || inlineMarker(text) != null

    fun isStandaloneMarker(text: String): Boolean = standaloneMarker(text) != null

    fun detect(
        recognized: List<RecognizedText>,
        viewportHeight: Int
    ): List<MachineDetectedListItem> {
        // Resolve marker/content anchors before normal paragraph grouping so row-order drift
        // cannot attach an item body to the heading above it.
        val valid = recognized.withIndex().filter { (_, item) ->
            item.text.isNotBlank() && item.bounds.right > item.bounds.left &&
                item.bounds.bottom > item.bounds.top
        }
        val standaloneMarkerIndices = valid.mapNotNull { indexed ->
            standaloneMarker(indexed.value.text)?.let { indexed.index to it }
        }.toMap()
        val inlineAnchors = valid.mapNotNull { indexed ->
            inlineMarker(indexed.value.text)?.let { (marker, _) ->
                MachineListAnchor(
                    contentIndex = indexed.index,
                    markerIndex = null,
                    marker = marker,
                    markerIsExternal = false,
                    markerBounds = copyBounds(indexed.value.bounds),
                    contentLeft = indexed.value.bounds.left +
                        estimatedInlineMarkerWidth(indexed.value, marker),
                    lineHeight = representativeHeight(indexed.value),
                    blockId = indexed.value.sourceBlockId
                )
            }
        }
        val externalAnchors = standaloneMarkerIndices.mapNotNull { (markerIndex, marker) ->
            val markerItem = recognized[markerIndex]
            val markerHeight = representativeHeight(markerItem)
            val contentEntry = valid.asSequence()
                .filter { indexed ->
                    indexed.index !in standaloneMarkerIndices &&
                        inlineMarker(indexed.value.text) == null &&
                        indexed.value.bounds.left >= markerItem.bounds.left
                }
                .mapNotNull { indexed ->
                    val content = indexed.value
                    val contentHeight = representativeHeight(content)
                    val maximumHeight = maxOf(markerHeight, contentHeight)
                    val centerDelta = kotlin.math.abs(
                        (markerItem.bounds.top + markerItem.bounds.bottom) -
                            (content.bounds.top + content.bounds.bottom)
                    ) / 2
                    val horizontalGap = content.bounds.left - markerItem.bounds.right
                    if (centerDelta > maximumHeight || horizontalGap < -maximumHeight / 2 ||
                        horizontalGap > maximumHeight * MAXIMUM_MARKER_TO_TEXT_GAP_RATIO
                    ) {
                        null
                    } else {
                        indexed to centerDelta * 1_000 + horizontalGap.coerceAtLeast(0)
                    }
                }
                .minByOrNull { it.second }
                ?.first
                ?: return@mapNotNull null
            val content = contentEntry.value
            val contentHeight = representativeHeight(content)
            MachineListAnchor(
                contentIndex = contentEntry.index,
                markerIndex = markerIndex,
                marker = marker,
                markerIsExternal = true,
                markerBounds = copyBounds(markerItem.bounds),
                contentLeft = content.bounds.left,
                lineHeight = maxOf(markerHeight, contentHeight),
                blockId = content.sourceBlockId
            )
        }
        val candidates = (inlineAnchors + externalAnchors)
            .distinctBy { anchor -> anchor.contentIndex }
        val anchors = candidates.filter { candidate ->
            candidates.any { other ->
                other !== candidate && sameListRun(candidate, other, viewportHeight)
            }
        }.sortedWith(compareBy({ it.markerBounds.top }, { it.markerBounds.left }))
        val markerIndices = anchors.mapNotNull(MachineListAnchor::markerIndex).toSet()
        val anchorContentIndices = anchors.map(MachineListAnchor::contentIndex).toSet()
        val occupied = mutableSetOf<Int>()
        return anchors.mapIndexedNotNull { anchorIndex, anchor ->
            if (!occupied.add(anchor.contentIndex)) return@mapIndexedNotNull null
            val seed = recognized[anchor.contentIndex]
            val nextMarkerTop = anchors.getOrNull(anchorIndex + 1)?.markerBounds?.top
                ?: Int.MAX_VALUE
            val members = mutableListOf(anchor.contentIndex)
            var previous = seed
            valid.asSequence()
                .filter { indexed ->
                    indexed.index !in occupied && indexed.index !in markerIndices &&
                        indexed.index !in anchorContentIndices &&
                        indexed.value.bounds.top >= seed.bounds.top - anchor.lineHeight / 2 &&
                        indexed.value.bounds.top < nextMarkerTop
                }
                .sortedWith(compareBy({ it.value.bounds.top }, { it.value.bounds.left }))
                .forEach { indexed ->
                    val item = indexed.value
                    val itemHeight = representativeHeight(item)
                    val lineHeight = maxOf(anchor.lineHeight, itemHeight).coerceAtLeast(1)
                    val gap = item.bounds.top - previous.bounds.bottom
                    val aligned = kotlin.math.abs(item.bounds.left - anchor.contentLeft) <=
                        lineHeight * MAXIMUM_CONTINUATION_LEFT_RATIO
                    val sameBlock = seed.sourceBlockId != null &&
                        seed.sourceBlockId == item.sourceBlockId
                    val followsSameBlock = sameBlock &&
                        item.bounds.top >= previous.bounds.top
                    val followsGeometry = aligned &&
                        gap in -(lineHeight / 3)..maxOf(4, lineHeight * 5 / 4)
                    if (!startsListItem(item.text) &&
                        (followsSameBlock || followsGeometry)
                    ) {
                        members += indexed.index
                        occupied += indexed.index
                        previous = item
                    }
                }
            MachineDetectedListItem(
                memberIndices = members,
                markerIndex = anchor.markerIndex,
                marker = anchor.marker,
                markerIsExternal = anchor.markerIsExternal,
                markerBounds = copyBounds(anchor.markerBounds)
            )
        }
    }

    fun contentText(text: String): String = inlineMarker(text)?.second ?: text.trim()

    private fun sameListRun(
        first: MachineListAnchor,
        second: MachineListAnchor,
        viewportHeight: Int
    ): Boolean {
        if (markerFamily(first.marker) != markerFamily(second.marker)) return false
        val lineHeight = maxOf(first.lineHeight, second.lineHeight).coerceAtLeast(1)
        val markerDistance = kotlin.math.abs(first.markerBounds.top - second.markerBounds.top)
        if (first.blockId != null && second.blockId != null &&
            first.blockId != second.blockId &&
            markerDistance > lineHeight * MAXIMUM_CROSS_BLOCK_LIST_DISTANCE_LINES
        ) return false
        return kotlin.math.abs(first.markerBounds.left - second.markerBounds.left) <= lineHeight &&
            kotlin.math.abs(first.contentLeft - second.contentLeft) <= lineHeight * 2 &&
            markerDistance <=
            maxOf(lineHeight * MAXIMUM_LIST_ITEM_DISTANCE_LINES, viewportHeight / 2)
    }

    private fun markerFamily(marker: String): String = when {
        marker.firstOrNull() in BULLET_MARKERS -> "BULLET"
        marker.firstOrNull()?.isDigit() == true -> "NUMBERED"
        else -> "LETTERED"
    }

    private fun standaloneMarker(text: String): String? = text.trim().takeIf { marker ->
        (marker.length == 1 && marker.single() in BULLET_MARKERS) ||
            NUMBERED_MARKER.matches(marker)
    }

    private fun inlineMarker(text: String): Pair<String, String>? {
        val compact = text.trim()
        val bullet = INLINE_BULLET.find(compact)
        if (bullet != null) {
            return bullet.groupValues[1] to bullet.groupValues[2].trim()
        }
        val numbered = INLINE_NUMBERED.find(compact) ?: return null
        return numbered.groupValues[1] to numbered.groupValues[2].trim()
    }

    private fun estimatedInlineMarkerWidth(item: RecognizedText, marker: String): Int {
        val textLength = item.text.trim().length.coerceAtLeast(1)
        return ((item.bounds.right - item.bounds.left) * (marker.length + 1) / textLength)
            .coerceAtMost(representativeHeight(item) * 2)
    }

    private fun representativeHeight(item: RecognizedText): Int =
        item.estimatedTextHeightPx?.takeIf { it > 0f }?.toInt()
            ?: (item.bounds.bottom - item.bounds.top).coerceAtLeast(1)

    private val BULLET_MARKERS = setOf('•', '·', '‣', '◦', '▪', '▫', '-', '*')
    private val NUMBERED_MARKER = Regex("^(?:\\d+|[A-Za-z])[.)]$")
    private val INLINE_BULLET = Regex("^([•·‣◦▪▫])\\s*(\\S.*)$")
    private val INLINE_NUMBERED = Regex("^((?:\\d+|[A-Za-z])[.)]|[-*])\\s+(\\S.*)$")
    private const val MAXIMUM_MARKER_TO_TEXT_GAP_RATIO = 3
    private const val MAXIMUM_LIST_ITEM_DISTANCE_LINES = 12
    private const val MAXIMUM_CROSS_BLOCK_LIST_DISTANCE_LINES = 5
    private const val MAXIMUM_CONTINUATION_LEFT_RATIO = 2
}

internal object MachineParagraphGroupingPolicy {
    fun group(
        lines: List<MachineTextLine>,
        viewportWidth: Int,
        viewportHeight: Int
    ): List<List<Int>> {
        if (lines.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return emptyList()
        val selected = mutableListOf<IndexedValue<MachineTextLine>>()
        lines.withIndex()
            .filter { (_, line) ->
                line.text.isNotBlank() && width(line) > 0 && height(line) > 0 &&
                    line.left < viewportWidth && line.top < viewportHeight &&
                    line.right > 0 && line.bottom > 0
            }
            .sortedWith(compareBy({ it.value.top }, { it.value.left }))
            .forEach { candidate ->
                if (selected.none { existing -> duplicate(existing.value, candidate.value) }) {
                    selected += candidate
                }
            }
        val paragraphs = mutableListOf<MutableList<IndexedValue<MachineTextLine>>>()
        selected.forEach { candidate ->
            val current = paragraphs.lastOrNull()
            if (current != null && canAppend(current, candidate.value)) {
                current += candidate
            } else {
                paragraphs += mutableListOf(candidate)
            }
        }
        return paragraphs.map { paragraph -> paragraph.map(IndexedValue<MachineTextLine>::index) }
    }

    private fun canAppend(
        members: List<IndexedValue<MachineTextLine>>,
        candidate: MachineTextLine
    ): Boolean {
        if (members.sumOf { it.value.text.length } + candidate.text.length > 2_000) return false
        if (MachineListStructurePolicy.startsListItem(candidate.text) ||
            members.any { MachineListStructurePolicy.isStandaloneMarker(it.value.text) }
        ) return false
        val previous = members.last().value
        val previousLineHeight = representativeLineHeight(previous)
        val candidateLineHeight = representativeLineHeight(candidate)
        val lineHeight = max(previousLineHeight, candidateLineHeight).coerceAtLeast(1)
        val verticalGap = candidate.top - previous.bottom
        val leftDelta = abs(previous.left - candidate.left)
        val overlap = (minOf(previous.right, candidate.right) -
            maxOf(previous.left, candidate.left)).coerceAtLeast(0)
        val overlapRatio = overlap.toFloat() / minOf(width(previous), width(candidate)).coerceAtLeast(1)
        val sameBlock = previous.blockId != null && previous.blockId == candidate.blockId
        if (isWrappedUrlContinuation(
                members = members,
                candidate = candidate,
                verticalGap = verticalGap,
                lineHeight = lineHeight,
                previousLineHeight = previousLineHeight,
                candidateLineHeight = candidateLineHeight,
                leftDelta = leftDelta
            )
        ) {
            return true
        }
        if (isProtected(candidate.text) || members.any { isProtected(it.value.text) }) return false
        if (!sameBlock && previous.blockId != null && candidate.blockId != null) {
            return tightCrossBlockContinuation(
                members = members,
                previous = previous,
                candidate = candidate,
                verticalGap = verticalGap,
                lineHeight = lineHeight,
                previousLineHeight = previousLineHeight,
                candidateLineHeight = candidateLineHeight,
                leftDelta = leftDelta,
                overlapRatio = overlapRatio
            )
        }
        if (verticalGap < -lineHeight / 4 ||
            verticalGap > max(4, (lineHeight * SAME_BLOCK_GAP_RATIO).toInt())
        ) return false
        return sameBlock || leftDelta <= max(6, lineHeight) || overlapRatio >= 0.25f
    }

    private fun isWrappedUrlContinuation(
        members: List<IndexedValue<MachineTextLine>>,
        candidate: MachineTextLine,
        verticalGap: Int,
        lineHeight: Int,
        previousLineHeight: Int,
        candidateLineHeight: Int,
        leftDelta: Int
    ): Boolean {
        val existingParts = members.map { it.value.text }
        val existingUrl = SemanticContentClassifier.reconstructedStandaloneUrlOrEmail(existingParts)
            ?: return false
        if (existingUrl.length < MINIMUM_WRAPPED_URL_PREFIX_CHARACTERS) return false
        val continuation = candidate.text.trim()
        if (!URL_CONTINUATION_PART.matches(continuation)) return false
        if (SemanticContentClassifier.reconstructedStandaloneUrlOrEmail(
                existingParts + continuation
            ) == null
        ) return false
        if (verticalGap < -(lineHeight * MAXIMUM_URL_OVERLAP_RATIO).toInt() ||
            verticalGap > max(3, (lineHeight * URL_CONTINUATION_GAP_RATIO).toInt()) ||
            leftDelta > max(6, (lineHeight * URL_CONTINUATION_LEFT_RATIO).toInt())
        ) return false
        val heightRatio = minOf(previousLineHeight, candidateLineHeight).toFloat() /
            maxOf(previousLineHeight, candidateLineHeight).coerceAtLeast(1)
        return heightRatio >= MINIMUM_TEXT_HEIGHT_RATIO
    }

    private fun tightCrossBlockContinuation(
        members: List<IndexedValue<MachineTextLine>>,
        previous: MachineTextLine,
        candidate: MachineTextLine,
        verticalGap: Int,
        lineHeight: Int,
        previousLineHeight: Int,
        candidateLineHeight: Int,
        leftDelta: Int,
        overlapRatio: Float
    ): Boolean {
        if (isStructuralBoundary(previous.text) || isStructuralBoundary(candidate.text)) return false
        if (verticalGap < -(lineHeight * MAXIMUM_CROSS_BLOCK_OVERLAP_RATIO).toInt() ||
            verticalGap > max(3, (lineHeight * CROSS_BLOCK_GAP_RATIO).toInt())
        ) return false
        val heightRatio = minOf(previousLineHeight, candidateLineHeight).toFloat() /
            maxOf(previousLineHeight, candidateLineHeight).coerceAtLeast(1)
        if (heightRatio < MINIMUM_TEXT_HEIGHT_RATIO) return false
        val aligned = leftDelta <= max(6, (lineHeight * CROSS_BLOCK_LEFT_RATIO).toInt())
        if (!aligned || overlapRatio < MINIMUM_CROSS_BLOCK_OVERLAP) return false

        val establishedBody = members.sumOf { visualLineCount(it.value) } >=
            MINIMUM_ESTABLISHED_BODY_LINES ||
            members.sumOf { compactLength(it.value.text) } >= MINIMUM_ESTABLISHED_BODY_CHARACTERS
        val unfinishedContinuation = compactLength(previous.text) >=
            MINIMUM_CONTINUATION_CHARACTERS && !endsSentence(previous.text)
        return establishedBody || unfinishedContinuation
    }

    private fun duplicate(first: MachineTextLine, second: MachineTextLine): Boolean {
        if (first.text.trim() != second.text.trim()) return false
        val intersectionWidth = (minOf(first.right, second.right) -
            maxOf(first.left, second.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(first.bottom, second.bottom) -
            maxOf(first.top, second.top)).coerceAtLeast(0)
        val smallerArea = minOf(width(first) * height(first), width(second) * height(second))
            .coerceAtLeast(1)
        return intersectionWidth * intersectionHeight / smallerArea.toFloat() >= 0.7f
    }

    private fun isProtected(text: String): Boolean =
        text.trim().matches(PROTECTED_TEXT) ||
            SemanticContentClassifier.isStandaloneUrlOrEmail(text)

    private fun isStructuralBoundary(text: String): Boolean =
        isProtected(text) || SemanticContentClassifier.isStandaloneMetadata(text) ||
            looksLikeCompactLabel(text)

    private fun looksLikeCompactLabel(text: String): Boolean {
        if (visualLineCount(text) != 1 || compactLength(text) > MAXIMUM_COMPACT_LABEL_CHARACTERS) {
            return false
        }
        val tokens = text.trim().split(WHITESPACE).filter(String::isNotBlank)
        if (tokens.size !in 1..MAXIMUM_COMPACT_LABEL_TOKENS) return false
        return tokens.all { token ->
            val letters = token.filter(Char::isLetter)
            letters.length >= 2 && letters.first().isUpperCase() &&
                letters.drop(1).all { it.isLowerCase() }
        }
    }

    private fun representativeLineHeight(line: MachineTextLine): Int =
        line.estimatedTextHeightPx?.takeIf { it > 0f }?.toInt()
            ?: (height(line) / visualLineCount(line)).coerceAtLeast(1)

    private fun visualLineCount(line: MachineTextLine): Int =
        visualLineCount(line.text)

    private fun visualLineCount(text: String): Int =
        text.lineSequence().count().coerceAtLeast(1)

    private fun compactLength(text: String): Int = text.count { !it.isWhitespace() }

    private fun endsSentence(text: String): Boolean =
        text.trimEnd().lastOrNull() in SENTENCE_ENDINGS

    private fun width(line: MachineTextLine): Int = line.right - line.left
    private fun height(line: MachineTextLine): Int = line.bottom - line.top

    private val URL_CONTINUATION_PART = Regex(
        "[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{1,160}"
    )
    private val PROTECTED_TEXT = Regex(
        "(?i)(?:\\d{1,2}:\\d{2}(?::\\d{2})?|[A-F0-9]{8,}|" +
            "[\\w.-]+\\.(?:java|kt|json|xml|py|rs|js))"
    )
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？', '…')
    private val WHITESPACE = Regex("\\s+")
    private const val SAME_BLOCK_GAP_RATIO = 1.2f
    private const val CROSS_BLOCK_GAP_RATIO = 0.5f
    private const val MAXIMUM_CROSS_BLOCK_OVERLAP_RATIO = 0.6f
    private const val CROSS_BLOCK_LEFT_RATIO = 0.6f
    private const val MINIMUM_CROSS_BLOCK_OVERLAP = 0.7f
    private const val MINIMUM_TEXT_HEIGHT_RATIO = 0.6f
    private const val MINIMUM_ESTABLISHED_BODY_LINES = 3
    private const val MINIMUM_ESTABLISHED_BODY_CHARACTERS = 72
    private const val MINIMUM_CONTINUATION_CHARACTERS = 12
    private const val MAXIMUM_COMPACT_LABEL_CHARACTERS = 40
    private const val MAXIMUM_COMPACT_LABEL_TOKENS = 4
    private const val MINIMUM_WRAPPED_URL_PREFIX_CHARACTERS = 24
    private const val URL_CONTINUATION_GAP_RATIO = 0.55f
    private const val URL_CONTINUATION_LEFT_RATIO = 0.75f
    private const val MAXIMUM_URL_OVERLAP_RATIO = 0.35f
}

internal data class MachineParagraph(
    val paragraphId: String,
    val members: List<RecognizedText>,
    val sourceText: String,
    val bounds: Rect,
    val renderSlots: List<Rect>,
    val sourceCoverSlots: List<Rect>,
    val readingOrder: Int,
    val kind: MachineParagraphKind = MachineParagraphKind.BODY,
    val listMarker: String? = null,
    val listMarkerIsExternal: Boolean = false,
    val listMarkerBounds: Rect? = null,
    val continuationAtTop: Boolean = false,
    val continuationAtBottom: Boolean = false
) {
    fun displayTranslation(translatedText: String): String =
        if (kind == MachineParagraphKind.LIST_ITEM && !listMarkerIsExternal) {
            listOfNotNull(listMarker, translatedText).joinToString(" ")
        } else {
            translatedText
        }

    fun asRecognizedText(): RecognizedText = members.first().copy(
        text = sourceText,
        bounds = copyBounds(bounds),
        sourceBlockId = members.mapNotNull(RecognizedText::sourceBlockId).distinct().singleOrNull(),
        sourceLineIndex = members.mapNotNull(RecognizedText::sourceLineIndex).minOrNull(),
        componentBounds = sourceCoverSlots.map(::copyBounds),
        estimatedTextHeightPx = members.mapNotNull(RecognizedText::estimatedTextHeightPx)
            .average()
            .toFloat()
            .takeIf { it.isFinite() }
    )
}

internal class MachineOcrTextProcessingProvider : OcrTextProcessingProvider {
    override fun process(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): OcrTextProcessingResult = OcrTextProcessingResult(
        sourceItems = recognized,
        units = processParagraphs(recognized, viewportWidth, viewportHeight)
            .map(::MachineOcrTextProcessingUnit)
    )

    fun processParagraphs(
        recognized: List<RecognizedText>,
        viewportWidth: Int,
        viewportHeight: Int
    ): List<MachineParagraph> {
        val listItems = MachineListStructurePolicy.detect(recognized, viewportHeight)
        val occupiedIndices = listItems.flatMap { item ->
            item.memberIndices + listOfNotNull(item.markerIndex)
        }.toSet()
        val remaining = recognized.withIndex().filter { it.index !in occupiedIndices }
        val lineGroups = MachineParagraphGroupingPolicy.group(
            lines = remaining.map { (_, item) ->
                MachineTextLine(
                    text = item.text,
                    left = item.bounds.left,
                    top = item.bounds.top,
                    right = item.bounds.right,
                    bottom = item.bounds.bottom,
                    blockId = item.sourceBlockId,
                    lineIndex = item.sourceLineIndex,
                    estimatedTextHeightPx = item.estimatedTextHeightPx
                )
            },
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        ).map { group -> group.map { relativeIndex -> remaining[relativeIndex].index } }
        val paragraphSources = lineGroups.map { indices -> indices to null } +
            listItems.map { item -> item.memberIndices to item }
        val drafts = paragraphSources.mapNotNull { (memberIndices, listItem) ->
            if (memberIndices.isEmpty()) return@mapNotNull null
            val members = memberIndices.map(recognized::get)
            val ordered = members.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val slots = ordered.flatMap(RecognizedText::textEraseBounds).map(::copyBounds)
            val bounds = slots.drop(1).fold(copyBounds(slots.first())) { result, slot ->
                unionBounds(result, slot)
            }
            val sourceParts = ordered.mapIndexed { memberIndex, item ->
                if (listItem != null && memberIndex == 0) {
                    MachineListStructurePolicy.contentText(item.text)
                } else {
                    item.text.trim()
                }
            }
            val sourceText = SemanticContentClassifier.reconstructedStandaloneUrlOrEmail(
                sourceParts
            ) ?: sourceParts.joinToString("\n")
            MachineParagraph(
                paragraphId = "",
                members = ordered,
                sourceText = sourceText,
                bounds = bounds,
                renderSlots = slots,
                sourceCoverSlots = slots.map(::Rect),
                readingOrder = 0,
                kind = if (listItem != null) {
                    MachineParagraphKind.LIST_ITEM
                } else {
                    MachineParagraphKind.BODY
                },
                listMarker = listItem?.marker,
                listMarkerIsExternal = listItem?.markerIsExternal == true,
                listMarkerBounds = listItem?.markerBounds?.let(::Rect),
                continuationAtTop = ordered.any(RecognizedText::continuationAtTop),
                continuationAtBottom = ordered.any(RecognizedText::continuationAtBottom)
            )
        }
        return drafts.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .mapIndexed { index, paragraph ->
                paragraph.copy(
                    paragraphId = "machine-paragraph-$index",
                    readingOrder = index
                )
            }
    }

}

private fun rectWidth(bounds: Rect): Int = bounds.right - bounds.left

private fun rectHeight(bounds: Rect): Int = bounds.bottom - bounds.top

private fun copyBounds(bounds: Rect): Rect = Rect().apply {
    left = bounds.left
    top = bounds.top
    right = bounds.right
    bottom = bounds.bottom
}

private fun unionBounds(target: Rect, other: Rect): Rect = target.apply {
    left = minOf(left, other.left)
    top = minOf(top, other.top)
    right = maxOf(right, other.right)
    bottom = maxOf(bottom, other.bottom)
}
