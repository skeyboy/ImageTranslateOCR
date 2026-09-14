package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import kotlin.math.abs
import kotlin.math.max

internal data class MachineTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val blockId: String? = null,
    val lineIndex: Int? = null
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
        if (previous.blockId != null && candidate.blockId != null &&
            previous.blockId != candidate.blockId
        ) return false
        val lineHeight = max(height(previous), height(candidate)).coerceAtLeast(1)
        val verticalGap = candidate.top - previous.bottom
        if (verticalGap < -lineHeight / 4 || verticalGap > max(4, (lineHeight * 1.2f).toInt())) {
            return false
        }
        val leftDelta = abs(previous.left - candidate.left)
        val overlap = (minOf(previous.right, candidate.right) -
            maxOf(previous.left, candidate.left)).coerceAtLeast(0)
        val overlapRatio = overlap.toFloat() / minOf(width(previous), width(candidate)).coerceAtLeast(1)
        val sameBlock = previous.blockId != null && previous.blockId == candidate.blockId
        return sameBlock || leftDelta <= max(6, lineHeight) || overlapRatio >= 0.25f
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

    private fun width(line: MachineTextLine): Int = line.right - line.left
    private fun height(line: MachineTextLine): Int = line.bottom - line.top

    private val URL_OR_EMAIL = Regex(
        "(?i)(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"
    )
    private val PROTECTED_TEXT = Regex(
        "(?i)(?:\\d{1,2}:\\d{2}(?::\\d{2})?|[A-F0-9]{8,}|" +
            "[\\w.-]+\\.(?:java|kt|json|xml|py|rs|js))"
    )
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
                    lineIndex = item.sourceLineIndex
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
