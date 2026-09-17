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
        if (isProtected(candidate.text) || members.any { isProtected(it.value.text) }) return false
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
        if (verticalGap < -lineHeight / 4 ||
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
        text.trim().matches(PROTECTED_TEXT) || text.trim().matches(URL_OR_EMAIL)

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

    private val URL_OR_EMAIL = Regex(
        "(?i)(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"
    )
    private val PROTECTED_TEXT = Regex(
        "(?i)(?:\\d{1,2}:\\d{2}(?::\\d{2})?|[A-F0-9]{8,}|" +
            "[\\w.-]+\\.(?:java|kt|json|xml|py|rs|js))"
    )
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？', '…')
    private val WHITESPACE = Regex("\\s+")
    private const val SAME_BLOCK_GAP_RATIO = 1.2f
    private const val CROSS_BLOCK_GAP_RATIO = 0.45f
    private const val CROSS_BLOCK_LEFT_RATIO = 0.6f
    private const val MINIMUM_CROSS_BLOCK_OVERLAP = 0.7f
    private const val MINIMUM_TEXT_HEIGHT_RATIO = 0.72f
    private const val MINIMUM_ESTABLISHED_BODY_LINES = 3
    private const val MINIMUM_ESTABLISHED_BODY_CHARACTERS = 72
    private const val MINIMUM_CONTINUATION_CHARACTERS = 12
    private const val MAXIMUM_COMPACT_LABEL_CHARACTERS = 40
    private const val MAXIMUM_COMPACT_LABEL_TOKENS = 4
}

internal data class MachineParagraph(
    val paragraphId: String,
    val members: List<RecognizedText>,
    val sourceText: String,
    val bounds: Rect,
    val renderSlots: List<Rect>,
    val sourceCoverSlots: List<Rect>,
    val readingOrder: Int
) {
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
        val lineGroups = MachineParagraphGroupingPolicy.group(
            lines = recognized.map { item ->
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
        )
        return lineGroups.mapIndexed { index, memberIndices ->
            val members = memberIndices.map(recognized::get)
            val ordered = members.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val slots = ordered.flatMap(RecognizedText::textEraseBounds).map(::copyBounds)
            val bounds = slots.drop(1).fold(copyBounds(slots.first())) { result, slot ->
                unionBounds(result, slot)
            }
            MachineParagraph(
                paragraphId = "machine-paragraph-$index",
                members = ordered,
                sourceText = ordered.joinToString("\n") { it.text.trim() },
                bounds = bounds,
                renderSlots = slots,
                sourceCoverSlots = slots.map(::Rect),
                readingOrder = index
            )
        }
    }

}

private fun rectWidth(bounds: Rect): Int = bounds.right - bounds.left

private fun rectHeight(bounds: Rect): Int = bounds.bottom - bounds.top

private fun copyBounds(bounds: Rect): Rect =
    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)

private fun unionBounds(target: Rect, other: Rect): Rect = target.apply {
    left = minOf(left, other.left)
    top = minOf(top, other.top)
    right = maxOf(right, other.right)
    bottom = maxOf(bottom, other.bottom)
}
