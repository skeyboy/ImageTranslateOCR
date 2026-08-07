package com.example.imagetranslate.ui

import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.floor

internal enum class ShapeAwareTextOutcome {
    FULL,
    COMPACT,
    OVERFLOW_MORE
}

internal data class ShapeAwareTextSegment(
    val bounds: Rect,
    val layout: StaticLayout,
    val horizontalPadding: Int
)

internal data class ShapeAwareTextResult(
    val segments: List<ShapeAwareTextSegment>,
    val outcome: ShapeAwareTextOutcome,
    val displayedText: String,
    val textSizePx: Float,
    val lineSpacingMultiplier: Float
)

internal object ShapeAwareTextLayout {
    fun layout(
        text: String,
        paint: TextPaint,
        renderSlots: List<Rect>,
        preferredTextSizePx: Float,
        minimumTextSizePx: Float,
        maximumLines: Int,
        alignment: Layout.Alignment,
        horizontalPadding: Int,
        allowOverflowMore: Boolean,
        lineSpacingMultipliers: List<Float>? = null
    ): ShapeAwareTextResult? {
        val slots = renderSlots.filter { it.right > it.left && it.bottom > it.top }
        if (text.isBlank() || slots.isEmpty()) return null
        val preferred = maxOf(preferredTextSizePx, minimumTextSizePx)
        val minimum = minOf(preferred, minimumTextSizePx)
        val lineSpacings = lineSpacingMultipliers ?: LINE_SPACING_STEPS.toList()

        lineSpacings.forEach { lineSpacing ->
            var low = minimum
            var high = preferred
            val minimumCandidate = flow(
                text,
                paint,
                slots,
                low,
                lineSpacing,
                maximumLines,
                alignment,
                horizontalPadding
            )
            val requireLeadingSlot = minimumCandidate?.firstUsedSlotIndex == 0
            var best: FlowCandidate? = minimumCandidate?.takeIf { candidate ->
                candidate.complete && (!requireLeadingSlot || candidate.firstUsedSlotIndex == 0)
            }
            repeat(LAYOUT_SEARCH_STEPS) {
                val size = (low + high) / 2f
                val candidate = flow(
                    text,
                    paint,
                    slots,
                    size,
                    lineSpacing,
                    maximumLines,
                    alignment,
                    horizontalPadding
                )
                if (candidate?.complete == true &&
                    (!requireLeadingSlot || candidate.firstUsedSlotIndex == 0)
                ) {
                    low = size
                    best = candidate
                } else {
                    high = size
                }
            }
            if (best != null) {
                paint.textSize = best!!.textSizePx
                return ShapeAwareTextResult(
                    segments = best!!.segments,
                    outcome = if (lineSpacing == 1f && best!!.textSizePx >= preferred * 0.98f) {
                        ShapeAwareTextOutcome.FULL
                    } else {
                        ShapeAwareTextOutcome.COMPACT
                    },
                    displayedText = text,
                    textSizePx = best!!.textSizePx,
                    lineSpacingMultiplier = lineSpacing
                )
            }
        }

        if (!allowOverflowMore) return null
        val lineSpacing = lineSpacings.last()
        var low = 1
        var high = text.length
        var bestText: String? = null
        var best: FlowCandidate? = null
        repeat(OVERFLOW_SEARCH_STEPS) {
            if (low > high) return@repeat
            val midpoint = (low + high) / 2
            val prefix = wordBoundaryPrefix(text, midpoint)
            val displayed = prefix.trimEnd() + OVERFLOW_SUFFIX
            val candidate = flow(
                displayed,
                paint,
                slots,
                minimum,
                lineSpacing,
                maximumLines,
                alignment,
                horizontalPadding
            )
            if (candidate?.complete == true) {
                bestText = displayed
                best = candidate
                low = midpoint + 1
            } else {
                high = midpoint - 1
            }
        }
        val overflow = best ?: return null
        paint.textSize = overflow.textSizePx
        return ShapeAwareTextResult(
            segments = overflow.segments,
            outcome = ShapeAwareTextOutcome.OVERFLOW_MORE,
            displayedText = checkNotNull(bestText),
            textSizePx = overflow.textSizePx,
            lineSpacingMultiplier = lineSpacing
        )
    }

    private fun flow(
        text: String,
        paint: TextPaint,
        slots: List<Rect>,
        textSizePx: Float,
        lineSpacingMultiplier: Float,
        maximumLines: Int,
        alignment: Layout.Alignment,
        horizontalPadding: Int
    ): FlowCandidate? {
        paint.textSize = textSizePx
        var cursor = skipWhitespace(text, 0)
        var remainingLines = maximumLines.coerceAtLeast(1)
        val segments = mutableListOf<ShapeAwareTextSegment>()
        var firstUsedSlotIndex: Int? = null
        for ((slotIndex, slot) in slots.withIndex()) {
            if (cursor >= text.length || remainingLines <= 0) break
            val width = (slot.right - slot.left - horizontalPadding * 2).coerceAtLeast(1)
            val fontMetrics = paint.fontMetrics
            val lineHeight = (fontMetrics.descent - fontMetrics.ascent)
                .coerceAtLeast(1f) * lineSpacingMultiplier
            val slotLines = minOf(
                remainingLines,
                floor((slot.bottom - slot.top) / lineHeight).toInt().coerceAtLeast(1)
            )
            val remainingText = text.substring(cursor)
            val fitted = fitPrefix(
                text = remainingText,
                paint = paint,
                width = width,
                alignment = alignment,
                lineSpacingMultiplier = lineSpacingMultiplier,
                maximumLines = slotLines,
                maximumHeight = slot.bottom - slot.top
            ) ?: continue
            val accepted = fitted.layout
            if (accepted.lineCount <= 0) return null
            val end = cursor + fitted.consumedCharacters
            if (end <= cursor) return null
            segments += ShapeAwareTextSegment(copyRect(slot), accepted, horizontalPadding)
            if (firstUsedSlotIndex == null) firstUsedSlotIndex = slotIndex
            cursor = skipWhitespace(text, end)
            remainingLines -= accepted.lineCount
        }
        if (segments.isEmpty()) return null
        return FlowCandidate(
            segments = segments,
            complete = cursor >= text.length,
            textSizePx = textSizePx,
            firstUsedSlotIndex = checkNotNull(firstUsedSlotIndex)
        )
    }

    private fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        lineSpacingMultiplier: Float
    ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(alignment)
        .setIncludePad(false)
        .setLineSpacing(0f, lineSpacingMultiplier)
        .build()

    private fun fitPrefix(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        lineSpacingMultiplier: Float,
        maximumLines: Int,
        maximumHeight: Int
    ): FittedPrefix? {
        fun layout(end: Int) = createLayout(
            text = text.substring(0, end),
            paint = paint,
            width = width,
            alignment = alignment,
            lineSpacingMultiplier = lineSpacingMultiplier
        )

        val complete = layout(text.length)
        if (complete.lineCount <= maximumLines && complete.height <= maximumHeight) {
            return FittedPrefix(text.length, complete)
        }

        var low = 1
        var high = text.length - 1
        var bestEnd = 0
        while (low <= high) {
            val midpoint = (low + high) / 2
            val candidate = layout(midpoint)
            if (candidate.lineCount <= maximumLines && candidate.height <= maximumHeight) {
                bestEnd = midpoint
                low = midpoint + 1
            } else {
                high = midpoint - 1
            }
        }
        if (bestEnd <= 0) return null
        val boundedEnd = wordBoundaryEnd(text, bestEnd)
        val fittedLayout = layout(boundedEnd)
        return FittedPrefix(boundedEnd, fittedLayout)
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var cursor = start.coerceIn(0, text.length)
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun wordBoundaryPrefix(text: String, requestedEnd: Int): String {
        return text.substring(0, wordBoundaryEnd(text, requestedEnd))
    }

    private fun wordBoundaryEnd(text: String, requestedEnd: Int): Int {
        val end = requestedEnd.coerceIn(1, text.length)
        if (end == text.length || !text[end - 1].isLetterOrDigit() ||
            !text[end].isLetterOrDigit()
        ) return end
        return (end downTo maxOf(1, end - WORD_BOUNDARY_SEARCH)).firstOrNull { index ->
            text[index - 1].isWhitespace() || text[index - 1] in WORD_BOUNDARY_PUNCTUATION
        } ?: end
    }

    private fun copyRect(bounds: Rect) = Rect().apply {
        this.left = bounds.left
        this.top = bounds.top
        this.right = bounds.right
        this.bottom = bounds.bottom
    }

    private data class FlowCandidate(
        val segments: List<ShapeAwareTextSegment>,
        val complete: Boolean,
        val textSizePx: Float,
        val firstUsedSlotIndex: Int
    )

    private data class FittedPrefix(
        val consumedCharacters: Int,
        val layout: StaticLayout
    )

    private val LINE_SPACING_STEPS = floatArrayOf(1f, 0.92f, 0.86f)
    private const val LAYOUT_SEARCH_STEPS = 8
    private const val OVERFLOW_SEARCH_STEPS = 12
    private const val WORD_BOUNDARY_SEARCH = 24
    private const val OVERFLOW_SUFFIX = "… 更多"
    private val WORD_BOUNDARY_PUNCTUATION = setOf(',', '.', ';', ':', '，', '。', '；', '：')
}
