package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import kotlin.math.abs
import kotlin.math.max

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
        bounds = Rect(bounds),
        sourceBlockId = members.mapNotNull(RecognizedText::sourceBlockId).distinct().singleOrNull(),
        sourceLineIndex = members.mapNotNull(RecognizedText::sourceLineIndex).minOrNull(),
        componentBounds = sourceCoverSlots.map(::Rect),
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
        if (recognized.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) return emptyList()
        val selected = mutableListOf<RecognizedText>()
        val candidates = recognized
            .asSequence()
            .filter { item ->
                item.text.isNotBlank() && item.bounds.width() > 0 && item.bounds.height() > 0 &&
                    item.bounds.left < viewportWidth && item.bounds.top < viewportHeight &&
                    item.bounds.right > 0 && item.bounds.bottom > 0
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .filter { candidate ->
                candidatesAreDistinct(candidate, selected).also { distinct ->
                    if (distinct) selected += candidate
                }
            }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        val paragraphs = mutableListOf<MutableList<RecognizedText>>()
        candidates.forEach { candidate ->
            val current = paragraphs.lastOrNull()
            if (current != null && canAppend(current, candidate)) {
                current += candidate
            } else {
                paragraphs += mutableListOf(candidate)
            }
        }
        return paragraphs.mapIndexed { index, members ->
            val ordered = members.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val slots = ordered.flatMap(RecognizedText::textEraseBounds).map(::Rect)
            val bounds = slots.drop(1).fold(Rect(slots.first())) { result, slot ->
                result.apply { union(slot) }
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

    private fun candidatesAreDistinct(
        candidate: RecognizedText,
        selected: List<RecognizedText>
    ): Boolean = selected.asSequence()
        .none { existing ->
            existing.text.trim() == candidate.text.trim() &&
                intersectionRatio(existing.bounds, candidate.bounds) >= DUPLICATE_OVERLAP
        }

    private fun canAppend(
        members: List<RecognizedText>,
        candidate: RecognizedText
    ): Boolean {
        if (members.sumOf { it.text.length } + candidate.text.length > MAX_PARAGRAPH_CHARACTERS) {
            return false
        }
        if (isProtected(candidate.text) || members.any { isProtected(it.text) }) return false
        val previous = members.last()
        val previousHeight = previous.bounds.height().coerceAtLeast(1)
        val candidateHeight = candidate.bounds.height().coerceAtLeast(1)
        val lineHeight = max(previousHeight, candidateHeight)
        val verticalGap = candidate.bounds.top - previous.bounds.bottom
        if (verticalGap < -lineHeight / 4 || verticalGap > max(4, (lineHeight * 1.2f).toInt())) {
            return false
        }
        val leftDelta = abs(previous.bounds.left - candidate.bounds.left)
        val overlap = horizontalOverlap(previous.bounds, candidate.bounds)
        val overlapRatio = overlap.toFloat() / minOf(previous.bounds.width(), candidate.bounds.width())
            .coerceAtLeast(1)
        val aligned = leftDelta <= max(6, lineHeight)
        val sameBlock = previous.sourceBlockId != null &&
            previous.sourceBlockId == candidate.sourceBlockId
        return sameBlock || aligned || overlapRatio >= MIN_HORIZONTAL_OVERLAP
    }

    private fun isProtected(text: String): Boolean =
        text.trim().matches(PROTECTED_TEXT) || text.trim().matches(URL_OR_EMAIL)

    private fun horizontalOverlap(first: Rect, second: Rect): Int =
        (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)

    private fun intersectionRatio(first: Rect, second: Rect): Float {
        val intersection = Rect(first)
        if (!intersection.intersect(second)) return 0f
        val smallerArea = minOf(first.width() * first.height(), second.width() * second.height())
            .coerceAtLeast(1)
        return intersection.width() * intersection.height() / smallerArea.toFloat()
    }

    private companion object {
        const val MAX_PARAGRAPH_CHARACTERS = 2_000
        const val MIN_HORIZONTAL_OVERLAP = 0.25f
        const val DUPLICATE_OVERLAP = 0.7f
        val URL_OR_EMAIL = Regex("(?i)(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}")
        val PROTECTED_TEXT = Regex("(?i)(?:\\d{1,2}:\\d{2}(?::\\d{2})?|[A-F0-9]{8,}|[\\w.-]+\\.(?:java|kt|json|xml|py|rs|js))")
    }
}
