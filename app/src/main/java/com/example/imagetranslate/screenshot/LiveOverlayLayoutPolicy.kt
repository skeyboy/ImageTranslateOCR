package com.example.imagetranslate.screenshot

import kotlin.math.abs

internal data class LiveTextLineBounds(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val quality: Float = 0f
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
}

internal data class LivePatchBounds(
    val index: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal object LiveOverlayLayoutPolicy {
    private const val MAXIMUM_LINES_PER_BLOCK = 3
    private const val MINIMUM_HEIGHT_RATIO = 0.72f
    private const val MINIMUM_HORIZONTAL_OVERLAP = 0.55f
    private const val MAXIMUM_VERTICAL_GAP_RATIO = 0.68f
    private const val DUPLICATE_MINIMUM_AXIS_OVERLAP = 0.7f

    fun selectDistinctTextLines(lines: List<LiveTextLineBounds>): List<Int> {
        val selected = mutableListOf<LiveTextLineBounds>()
        lines.asSequence()
            .filter { it.width > 0 && it.height > 0 && it.text.isNotBlank() }
            .sortedWith(
                compareByDescending<LiveTextLineBounds> { it.quality }
                    .thenByDescending { it.text.count(Char::isLetterOrDigit) }
                    .thenByDescending { it.width * it.height }
            )
            .forEach { candidate ->
                if (selected.none { existing -> sameVisualLine(existing, candidate) }) {
                    selected += candidate
                }
            }
        return selected.sortedWith(compareBy({ it.top }, { it.left })).map { it.index }
    }

    fun groupTextLines(lines: List<LiveTextLineBounds>): List<List<Int>> {
        val remaining = lines
            .filter { it.width > 0 && it.height > 0 && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .toMutableList()
        val blocks = mutableListOf<List<LiveTextLineBounds>>()

        while (remaining.isNotEmpty()) {
            val block = mutableListOf(remaining.removeAt(0))
            while (block.size < MAXIMUM_LINES_PER_BLOCK) {
                val previous = block.last()
                val nextIndex = remaining.indices
                    .filter { canAppend(previous, remaining[it]) }
                    .minByOrNull { index ->
                        val candidate = remaining[index]
                        (candidate.top - previous.bottom).coerceAtLeast(0) * 10_000 +
                            abs(candidate.left - previous.left)
                    }
                    ?: break
                block += remaining.removeAt(nextIndex)
            }
            blocks += block
        }

        return blocks
            .sortedWith(compareBy({ block -> block.minOf { it.top } }, { block -> block.minOf { it.left } }))
            .map { block -> block.map { it.index } }
    }

    fun groupIntersectingPatches(
        patches: List<LivePatchBounds>,
        mergeGap: Int
    ): List<List<Int>> {
        val remaining = patches
            .filter { it.right > it.left && it.bottom > it.top }
            .toMutableList()
        val groups = mutableListOf<List<Int>>()

        while (remaining.isNotEmpty()) {
            val group = mutableListOf(remaining.removeAt(0))
            var changed: Boolean
            do {
                changed = false
                val union = union(group)
                val matches = remaining.filter { intersectsOrNear(union, it, mergeGap) }
                if (matches.isNotEmpty()) {
                    group += matches
                    remaining.removeAll(matches.toSet())
                    changed = true
                }
            } while (changed)
            groups += group.map { it.index }
        }
        return groups
    }

    fun translationMaterialBounds(
        textBounds: LivePatchBounds,
        sourceText: String,
        sourceWidth: Int,
        sourceHeight: Int
    ): LivePatchBounds {
        val lineCount = sourceText.lineSequence().count().coerceAtLeast(1)
        val lineHeight = (textBounds.bottom - textBounds.top).coerceAtLeast(1) / lineCount
        val compactLength = sourceText.count { !it.isWhitespace() }
        val prominentSingleLine = lineCount == 1 && compactLength <= 80
        val horizontalPadding = if (prominentSingleLine) {
            minOf(24, maxOf(8, lineHeight / 3))
        } else {
            maxOf(4, lineHeight / 8)
        }
        val verticalPadding = maxOf(4, minOf(12, lineHeight / 7))
        return LivePatchBounds(
            index = textBounds.index,
            left = (textBounds.left - horizontalPadding).coerceAtLeast(0),
            top = (textBounds.top - verticalPadding).coerceAtLeast(0),
            right = (textBounds.right + horizontalPadding).coerceAtMost(sourceWidth),
            bottom = (textBounds.bottom + verticalPadding).coerceAtMost(sourceHeight)
        )
    }

    private fun canAppend(first: LiveTextLineBounds, second: LiveTextLineBounds): Boolean {
        val minimumHeight = minOf(first.height, second.height).coerceAtLeast(1)
        val maximumHeight = maxOf(first.height, second.height).coerceAtLeast(1)
        if (minimumHeight.toFloat() / maximumHeight < MINIMUM_HEIGHT_RATIO) return false

        val verticalGap = second.top - first.bottom
        if (verticalGap < -minimumHeight / 4 ||
            verticalGap > maxOf(4, (maximumHeight * MAXIMUM_VERTICAL_GAP_RATIO).toInt())
        ) return false

        if (startsListItem(second.text)) return false

        val overlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
        val minimumWidth = minOf(first.width, second.width).coerceAtLeast(1)
        val overlapRatio = overlap.coerceAtLeast(0).toFloat() / minimumWidth
        val alignmentTolerance = maxOf(6, minimumHeight)
        val leftAligned = abs(first.left - second.left) <= alignmentTolerance
        val centerAligned = abs(first.centerX - second.centerX) <= maxOf(
            alignmentTolerance,
            (minimumWidth * 0.18f).toInt()
        )
        val sentenceBoundary = first.text.trimEnd().lastOrNull() in SENTENCE_ENDINGS
        val paragraphGap = verticalGap > maxOf(4, minimumHeight / 3)
        return overlapRatio >= MINIMUM_HORIZONTAL_OVERLAP &&
            (leftAligned || centerAligned) &&
            !(sentenceBoundary && paragraphGap)
    }

    private fun sameVisualLine(
        first: LiveTextLineBounds,
        second: LiveTextLineBounds
    ): Boolean {
        val horizontalOverlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
        val verticalOverlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        if (horizontalOverlap <= 0 || verticalOverlap <= 0) return false
        val horizontalRatio = horizontalOverlap.toFloat() /
            minOf(first.width, second.width).coerceAtLeast(1)
        val verticalRatio = verticalOverlap.toFloat() /
            minOf(first.height, second.height).coerceAtLeast(1)
        return horizontalRatio >= DUPLICATE_MINIMUM_AXIS_OVERLAP &&
            verticalRatio >= DUPLICATE_MINIMUM_AXIS_OVERLAP
    }

    private fun startsListItem(text: String): Boolean {
        val compact = text.trimStart()
        if (compact.firstOrNull() in listOf('•', '·', '‣', '◦', '-', '*')) return true
        return NUMBERED_LIST_PREFIX.matches(compact)
    }

    private fun union(items: List<LivePatchBounds>): LivePatchBounds = LivePatchBounds(
        index = items.first().index,
        left = items.minOf { it.left },
        top = items.minOf { it.top },
        right = items.maxOf { it.right },
        bottom = items.maxOf { it.bottom }
    )

    private fun intersectsOrNear(
        first: LivePatchBounds,
        second: LivePatchBounds,
        gap: Int
    ): Boolean = first.left <= second.right + gap && second.left <= first.right + gap &&
        first.top <= second.bottom + gap && second.top <= first.bottom + gap

    private val NUMBERED_LIST_PREFIX = Regex("^(?:\\d+[.)]|[A-Za-z][.)])\\s+.*")
    private val SENTENCE_ENDINGS = setOf('.', '!', '?', '。', '！', '？')
}
