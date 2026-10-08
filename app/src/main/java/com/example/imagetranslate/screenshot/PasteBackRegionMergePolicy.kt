package com.example.imagetranslate.screenshot

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.semantic.SemanticContentClassifier

internal data class PasteBackRenderCandidate(
    val region: BackgroundImageRegion,
    val fallbackRegions: List<BackgroundImageRegion>,
    val sourceRegionCount: Int
)

internal object PasteBackRegionMergePolicy {
    fun plan(regions: List<BackgroundImageRegion>): List<PasteBackRenderCandidate> {
        if (regions.size < 2) return regions.map(::singleCandidate)
        val output = mutableListOf<PasteBackRenderCandidate>()
        var index = 0
        while (index < regions.size) {
            val members = mutableListOf(regions[index])
            var candidate = regions[index]
            while (index + members.size < regions.size) {
                val next = regions[index + members.size]
                val merged = merge(candidate, next, members + next) ?: break
                members += next
                candidate = merged
            }
            output += if (members.size == 1) {
                singleCandidate(members.single())
            } else {
                PasteBackRenderCandidate(candidate, members.toList(), members.size)
            }
            index += members.size
        }
        return output
    }

    private fun merge(
        first: BackgroundImageRegion,
        second: BackgroundImageRegion,
        originals: List<BackgroundImageRegion>
    ): BackgroundImageRegion? {
        if (!eligible(first) || !eligible(second)) return null
        if (first.source.text.length + second.source.text.length > MAXIMUM_MERGED_CHARACTERS) {
            return null
        }
        val firstHeight = representativeLineHeight(first)
        val secondHeight = representativeLineHeight(second)
        val lineHeight = maxOf(firstHeight, secondHeight).coerceAtLeast(1f)
        val typographyRatio = minOf(firstHeight, secondHeight) / lineHeight
        if (typographyRatio < MINIMUM_TYPOGRAPHY_RATIO) return null
        val firstBounds = first.source.bounds
        val secondBounds = second.source.bounds
        val gap = secondBounds.top - firstBounds.bottom
        if (gap < -(lineHeight * MAXIMUM_OVERLAP_RATIO).toInt() ||
            gap > maxOf(
                MINIMUM_GAP_PX,
                (lineHeight * MAXIMUM_BACKGROUND_GAP_RATIO).toInt()
            )
        ) return null
        val horizontalOverlap = (minOf(firstBounds.right, secondBounds.right) -
            maxOf(firstBounds.left, secondBounds.left)).coerceAtLeast(0)
        val overlapRatio = horizontalOverlap.toFloat() /
            minOf(width(firstBounds), width(secondBounds)).coerceAtLeast(1)
        if (overlapRatio < MINIMUM_HORIZONTAL_OVERLAP_RATIO) return null
        if (kotlin.math.abs(firstBounds.left - secondBounds.left) > lineHeight) return null

        val sourceCoverSlots = originals.flatMap(::sourceCoverSlots)
        val background = BackgroundRegionMergePolicy.merge(
            source = originals.map(::backgroundEnvelope),
            representativeLineHeightPx = lineHeight,
            maximumGapRatio = MAXIMUM_BACKGROUND_GAP_RATIO
        )
        val mergedBounds = background.regions.singleOrNull() ?: return null
        if (background.shape != BackgroundRegionShape.CONTIGUOUS_RECT) return null
        val source = first.source.copy(
            text = originals.joinToString("\n\n") { it.source.text.trim() },
            bounds = copyRect(mergedBounds),
            sourceBlockId = null,
            sourceLineIndex = originals.mapNotNull { it.source.sourceLineIndex }.minOrNull(),
            componentBounds = sourceCoverSlots.map(::copyRect),
            componentTextHeightsPx = originals.flatMap {
                it.source.componentTextHeightsPx.ifEmpty {
                    listOf(representativeLineHeight(it))
                }
            },
            estimatedTextHeightPx = originals.map(::representativeLineHeight).average().toFloat()
        )
        return BackgroundImageRegion(
            source = source,
            translation = joinNaturalText(originals.map(BackgroundImageRegion::translation)),
            groupId = originals.mapNotNull(BackgroundImageRegion::groupId).joinToString("--")
                .takeIf(String::isNotEmpty),
            renderSlots = listOf(copyRect(mergedBounds)),
            sourceCoverSlots = sourceCoverSlots.map(::copyRect)
        )
    }

    private fun eligible(region: BackgroundImageRegion): Boolean {
        if (region.smartAssistDisplayHints != null) return false
        val text = region.source.text.trim()
        if (text.isEmpty() || SemanticContentClassifier.isStandaloneMetadata(text)) return false
        if (SemanticContentClassifier.shouldPreserve("BODY", text)) return false
        return visualLineCount(region.source) >= MINIMUM_BODY_LINES ||
            text.count { !it.isWhitespace() } >= MINIMUM_BODY_CHARACTERS
    }

    private fun visualLineCount(source: RecognizedText): Int = maxOf(
        source.text.lineSequence().count(),
        source.textEraseBounds().size
    )

    private fun representativeLineHeight(region: BackgroundImageRegion): Float {
        val heights = region.source.componentTextHeightsPx.filter { it > 0f }.sorted()
        return heights.getOrNull(heights.lastIndex / 2)
            ?: region.source.estimatedTextHeightPx?.takeIf { it > 0f }
            ?: (height(region.source.bounds).toFloat() /
                visualLineCount(region.source).coerceAtLeast(1))
    }

    private fun sourceCoverSlots(region: BackgroundImageRegion): List<Rect> =
        region.sourceCoverSlots.takeIf { it.isNotEmpty() }
            ?: region.source.textEraseBounds()

    private fun backgroundEnvelope(region: BackgroundImageRegion): Rect {
        val slots = region.renderSlots.takeIf { it.isNotEmpty() }
            ?: listOf(region.source.bounds)
        return Rect().apply {
            left = slots.minOf(Rect::left)
            top = slots.minOf(Rect::top)
            right = slots.maxOf(Rect::right)
            bottom = slots.maxOf(Rect::bottom)
        }
    }

    private fun singleCandidate(region: BackgroundImageRegion) =
        PasteBackRenderCandidate(region, listOf(region), 1)

    internal fun joinNaturalText(parts: List<String>): String = parts
        .flatMap { part -> part.lineSequence().map(String::trim).filter(String::isNotEmpty).toList() }
        .fold("") { accumulated, next ->
            if (accumulated.isEmpty()) {
                next
            } else {
                accumulated + naturalSeparator(accumulated.last(), next.first()) + next
            }
        }

    private fun naturalSeparator(previous: Char, next: Char): String {
        val previousScript = Character.UnicodeScript.of(previous.code)
        val nextScript = Character.UnicodeScript.of(next.code)
        return if (previousScript == Character.UnicodeScript.HAN &&
            nextScript == Character.UnicodeScript.HAN ||
            previous in HAN_PUNCTUATION && nextScript == Character.UnicodeScript.HAN
        ) {
            ""
        } else {
            " "
        }
    }

    private fun copyRect(source: Rect) = Rect().apply {
        left = source.left
        top = source.top
        right = source.right
        bottom = source.bottom
    }

    private fun width(bounds: Rect): Int = bounds.right - bounds.left
    private fun height(bounds: Rect): Int = bounds.bottom - bounds.top

    private const val MAXIMUM_BACKGROUND_GAP_RATIO = 1.25f
    private const val MAXIMUM_OVERLAP_RATIO = 0.35f
    private const val MINIMUM_HORIZONTAL_OVERLAP_RATIO = 0.7f
    private const val MINIMUM_TYPOGRAPHY_RATIO = 0.6f
    private const val MINIMUM_BODY_LINES = 2
    private const val MINIMUM_BODY_CHARACTERS = 48
    private const val MAXIMUM_MERGED_CHARACTERS = 2_000
    private const val MINIMUM_GAP_PX = 3
    private val HAN_PUNCTUATION = setOf('。', '，', '！', '？', '；', '：', '、', '”', '’')
}
