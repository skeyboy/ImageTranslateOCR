package com.example.imagetranslate.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

enum class RecognizerScript {
    CHINESE,
    LATIN,
    FUSED
}

data class RecognizedText(
    val text: String,
    val bounds: Rect,
    val consensusScore: Float = 0.5f,
    val passCount: Int = 1,
    val modelConfidence: Float = 0f,
    val recognizerScript: RecognizerScript = RecognizerScript.CHINESE
)

class OCRManager {
    private data class OcrCandidate(
        val result: RecognizedText,
        val pass: Int,
        val script: RecognizerScript,
        val reliability: Float
    )

    private data class ElementCandidate(
        val text: String,
        val bounds: Rect
    )

    private data class InkGroup(
        val left: Int,
        val right: Int
    )

    private val chineseRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val latinRecognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS
    )

    suspend fun recognize(bitmap: Bitmap): List<RecognizedText> {
        val candidates = mutableListOf<OcrCandidate>()
        addRecognitionPass(bitmap, PASS_ORIGINAL, 0.25f, candidates)

        val contrasted = createContrastedBitmap(bitmap)
        try {
            addRecognitionPass(contrasted, PASS_CONTRAST, 0.15f, candidates)
        } catch (_: Exception) {
            // The original pass remains usable if an enhancement pass fails.
        } finally {
            contrasted.recycle()
        }

        val inverted = createInvertedBitmap(bitmap)
        try {
            addRecognitionPass(
                inverted,
                PASS_INVERTED,
                0.1f,
                candidates
            ) { isDarkRegion(bitmap, it.bounds) }
        } catch (_: Exception) {
            // The original and contrast passes remain usable.
        } finally {
            inverted.recycle()
        }

        return mergeAdjacentLineFragments(fuseCandidates(candidates, bitmap))
            .map { refineShortLabelByInk(bitmap, it) }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private suspend fun addRecognitionPass(
        bitmap: Bitmap,
        pass: Int,
        baseReliability: Float,
        candidates: MutableList<OcrCandidate>,
        extraFilter: (RecognizedText) -> Boolean = { true }
    ) {
        recognizeWith(
            bitmap,
            chineseRecognizer,
            RecognizerScript.CHINESE,
            pass,
            baseReliability,
            candidates,
            extraFilter
        )
        recognizeWith(
            bitmap,
            latinRecognizer,
            RecognizerScript.LATIN,
            pass,
            baseReliability,
            candidates,
            extraFilter
        )
    }

    private suspend fun recognizeWith(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
        script: RecognizerScript,
        pass: Int,
        baseReliability: Float,
        candidates: MutableList<OcrCandidate>,
        extraFilter: (RecognizedText) -> Boolean
    ) {
        try {
            recognizeSingle(bitmap, recognizer, script)
                .filter(::isUsefulText)
                .filter(extraFilter)
                .mapTo(candidates) { result ->
                    OcrCandidate(
                        result = result,
                        pass = pass,
                        script = script,
                        reliability = baseReliability + scriptReliability(result, script)
                    )
                }
        } catch (_: Exception) {
            // The other script recognizer and enhancement passes remain usable.
        }
    }

    private fun scriptReliability(result: RecognizedText, script: RecognizerScript): Float {
        val hasHan = result.text.any(::isHanCharacter)
        val hasLatin = result.text.any { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            hasHan && script == RecognizerScript.CHINESE -> 0.18f
            hasHan && script == RecognizerScript.LATIN -> -0.12f
            hasLatin && script == RecognizerScript.LATIN -> 0.18f
            else -> 0f
        }
    }

    private fun refineShortLabelByInk(bitmap: Bitmap, item: RecognizedText): RecognizedText {
        val compact = item.text.filterNot(Char::isWhitespace)
        if (compact.length !in 2..10 || compact.none(::isHanCharacter)) return item
        val bounds = item.bounds
        if (bounds.width() < 4 || bounds.height() < 4) return item

        val background = estimateBackgroundColor(bitmap, bounds)
        val minimumInkPixels = maxOf(1, (bounds.height() * 0.08f).toInt())
        val activeColumns = mutableListOf<Int>()
        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(bitmap.width)) {
            var inkPixels = 0
            for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(bitmap.height)) {
                if (colorDistanceSquared(bitmap[x, y], background) >= 1600) inkPixels++
            }
            if (inkPixels >= minimumInkPixels) activeColumns.add(x)
        }
        if (activeColumns.size < 2) return item

        val gapTolerance = maxOf(2, (bounds.height() * 0.18f).toInt())
        val groups = mutableListOf<InkGroup>()
        var groupStart = activeColumns.first()
        var previous = groupStart
        for (column in activeColumns.drop(1)) {
            if (column - previous > gapTolerance + 1) {
                groups.add(InkGroup(groupStart, previous + 1))
                groupStart = column
            }
            previous = column
        }
        groups.add(InkGroup(groupStart, previous + 1))
        if (groups.size < 2) return item

        val selectedIndex = groups.indices.maxByOrNull { groups[it].right - groups[it].left }
            ?: return item
        val selected = groups[selectedIndex]
        val selectedWidth = selected.right - selected.left
        val nextWidest = groups.indices
            .filter { it != selectedIndex }
            .maxOfOrNull { groups[it].right - groups[it].left } ?: 0
        if (selectedWidth < nextWidest * 1.2f) return item

        val detectedLeadingGlyphs = groups.take(selectedIndex).sumOf {
            estimateGlyphCount(it.right - it.left, bounds.height())
        }
        val detectedTrailingGlyphs = groups.drop(selectedIndex + 1).sumOf {
            estimateGlyphCount(it.right - it.left, bounds.height())
        }
        val selectedGlyphCapacity = estimateGlyphCount(selectedWidth, bounds.height())
        val removableGlyphs = maxOf(0, compact.length - selectedGlyphCapacity)
        val leadingGlyphs = minOf(detectedLeadingGlyphs, removableGlyphs)
        val trailingGlyphs = minOf(
            detectedTrailingGlyphs,
            removableGlyphs - leadingGlyphs
        )
        if (leadingGlyphs + trailingGlyphs >= compact.length) return item
        val cleanedText = compact.drop(leadingGlyphs).dropLast(trailingGlyphs)
        if (cleanedText.isEmpty()) return item
        val originalMeaningfulCount = meaningfulCharacterCount(compact).coerceAtLeast(1)
        val retainedMeaningfulRatio = meaningfulCharacterCount(cleanedText).toFloat() /
            originalMeaningfulCount
        val discardedGlyphs = leadingGlyphs + trailingGlyphs
        val leadingGap = if (selectedIndex > 0) {
            selected.left - groups[selectedIndex - 1].right
        } else {
            0
        }
        val trailingGap = if (selectedIndex < groups.lastIndex) {
            groups[selectedIndex + 1].left - selected.right
        } else {
            0
        }
        val hasStrongDetachedEdge = maxOf(leadingGap, trailingGap) >=
            bounds.height() * STRONG_DETACHED_EDGE_RATIO
        if (retainedMeaningfulRatio < MIN_RETAINED_TEXT_RATIO &&
            !(discardedGlyphs == 1 && hasStrongDetachedEdge)
        ) {
            return item
        }
        val padding = maxOf(1, bounds.height() / 12)
        return RecognizedText(
            text = cleanedText,
            bounds = Rect(
                (selected.left - padding).coerceAtLeast(0),
                bounds.top,
                (selected.right + padding).coerceAtMost(bitmap.width),
                bounds.bottom
            ),
            consensusScore = item.consensusScore,
            passCount = item.passCount,
            modelConfidence = item.modelConfidence,
            recognizerScript = item.recognizerScript
        )
    }

    private fun estimateGlyphCount(width: Int, height: Int): Int =
        maxOf(1, kotlin.math.round(width / maxOf(1f, height * 0.8f)).toInt())

    private fun estimateBackgroundColor(bitmap: Bitmap, bounds: Rect): Int {
        val padding = maxOf(2, bounds.height() / 4)
        val left = (bounds.left - padding).coerceAtLeast(0)
        val top = (bounds.top - padding).coerceAtLeast(0)
        val right = (bounds.right + padding).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + padding).coerceAtMost(bitmap.height)
        val histogram = IntArray(4096)
        for (y in top until bottom) {
            for (x in left until right) {
                if (x in bounds.left until bounds.right && y in bounds.top until bounds.bottom) continue
                val color = bitmap[x, y]
                val bucket = (Color.red(color) / 16 shl 8) or
                    (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
                histogram[bucket]++
            }
        }
        val bucket = histogram.indices.maxByOrNull { histogram[it] } ?: return Color.WHITE
        return Color.rgb(
            ((bucket shr 8) and 0xF) * 16 + 8,
            ((bucket shr 4) and 0xF) * 16 + 8,
            (bucket and 0xF) * 16 + 8
        )
    }

    private fun colorDistanceSquared(first: Int, second: Int): Int {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return red * red + green * green + blue * blue
    }

    private fun fuseCandidates(
        candidates: List<OcrCandidate>,
        bitmap: Bitmap
    ): List<RecognizedText> {
        val clusters = mutableListOf<MutableList<OcrCandidate>>()
        for (candidate in candidates) {
            val cluster = clusters.firstOrNull { existing ->
                existing.any { overlapRatio(it.result.bounds, candidate.result.bounds) >= 0.45f }
            }
            if (cluster == null) {
                clusters.add(mutableListOf(candidate))
            } else {
                cluster.add(candidate)
            }
        }

        return clusters.mapNotNull { cluster ->
            val hasOriginal = cluster.any { it.pass == PASS_ORIGINAL }
            val hasMultiplePasses = cluster.map { it.pass }.distinct().size >= 2
            val isDark = cluster.any { isDarkRegion(bitmap, it.result.bounds) }
            if (!hasOriginal && !hasMultiplePasses && !isDark) return@mapNotNull null

            val maximumMeaningfulCharacters = cluster.maxOf {
                meaningfulCharacterCount(it.result.text)
            }.coerceAtLeast(1)
            val maximumWidth = cluster.maxOf { it.result.bounds.width() }.coerceAtLeast(1)
            val selected = cluster.maxByOrNull { candidate ->
                val agreement = cluster
                    .filterNot { it === candidate }
                    .sumOf { other ->
                        textSimilarity(candidate.result.text, other.result.text).toDouble()
                    }.toFloat()
                val completeness = meaningfulCharacterCount(candidate.result.text).toFloat() /
                    maximumMeaningfulCharacters
                val widthCoverage = candidate.result.bounds.width().toFloat() / maximumWidth
                textQuality(candidate.result.text) + candidate.reliability + agreement * 0.3f
                    + candidate.result.modelConfidence.coerceIn(0f, 1f) * 0.25f +
                    completeness * 0.25f + widthCoverage * 0.2f
            } ?: return@mapNotNull null
            val passCount = cluster.map { it.script to it.pass }.distinct().size
            val otherCandidates = cluster.filterNot { it === selected }
            val averageAgreement = if (otherCandidates.isEmpty()) {
                0f
            } else {
                otherCandidates.map {
                    textSimilarity(selected.result.text, it.result.text)
                }.average().toFloat()
            }
            val evidence = when {
                passCount >= 4 -> 0.82f
                passCount == 3 -> 0.76f
                passCount == 2 -> 0.66f
                hasOriginal -> 0.56f
                else -> 0.46f
            }
            val quality = textQuality(selected.result.text).coerceIn(0f, 1f)
            selected.result.copy(
                consensusScore = (evidence + averageAgreement * 0.16f + quality * 0.08f)
                    .coerceIn(0f, 1f),
                passCount = passCount,
                recognizerScript = if (cluster.map { it.script }.distinct().size > 1) {
                    RecognizerScript.FUSED
                } else {
                    selected.script
                }
            )
        }
    }

    private suspend fun recognizeSingle(
        bitmap: Bitmap,
        recognizer: TextRecognizer,
        script: RecognizerScript
    ): List<RecognizedText> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val results = mutableListOf<RecognizedText>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            refineLine(line, script)?.let(results::add)
                        }
                    }
                    continuation.resume(results)
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
        }

    private fun refineLine(line: Text.Line, script: RecognizerScript): RecognizedText? {
        val lineBounds = line.boundingBox ?: return null
        fun result(text: String, bounds: Rect) = RecognizedText(
            text = text,
            bounds = bounds,
            modelConfidence = line.confidence.coerceIn(0f, 1f),
            recognizerScript = script
        )
        val elements = line.elements.mapNotNull { element ->
            val bounds = element.boundingBox ?: return@mapNotNull null
            val compact = element.text.filterNot(Char::isWhitespace)
            if (compact.isEmpty()) return@mapNotNull null
            val meaningful = compact.count { it.isLetterOrDigit() || isHanCharacter(it) }
            if (meaningful.toFloat() / compact.length < 0.5f) return@mapNotNull null
            ElementCandidate(element.text.trim(), bounds)
        }.sortedBy { it.bounds.left }

        if (elements.size < 2) return result(line.text, lineBounds)
        val gapThreshold = maxOf(2, (lineBounds.height() * 0.4f).toInt())
        val groups = mutableListOf<MutableList<ElementCandidate>>()
        for (element in elements) {
            val current = groups.lastOrNull()
            if (current == null || element.bounds.left - current.last().bounds.right > gapThreshold) {
                groups.add(mutableListOf(element))
            } else {
                current.add(element)
            }
        }

        val refinedGroups = groups.map { group ->
            group.toMutableList().apply {
                trimDetachedEdgeGlyphs(this, lineBounds.height())
            }
        }.filter { it.isNotEmpty() }
        if (refinedGroups.isEmpty()) return result(line.text, lineBounds)

        val selectedGroups = refinedGroups.filter { group ->
            meaningfulCharacterCount(group.joinToString("") { it.text }) >=
                MIN_MEANINGFUL_GROUP_CHARACTERS
        }.ifEmpty {
            listOf(
                refinedGroups.maxBy { group ->
                    meaningfulCharacterCount(group.joinToString("") { it.text })
                }
            )
        }

        val selectedElements = selectedGroups.flatten()
        val bounds = Rect(selectedElements.first().bounds)
        selectedElements.drop(1).forEach { bounds.union(it.bounds) }
        val explicitSeparator = line.text.firstOrNull { it in EXPLICIT_GROUP_SEPARATORS }
        val text = buildString {
            selectedGroups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) {
                    val previousGroup = selectedGroups[groupIndex - 1]
                    when {
                        explicitSeparator != null -> append(" $explicitSeparator ")
                        needsWordSeparator(
                            previousGroup.last(),
                            group.first(),
                            lineBounds.height()
                        ) -> append(' ')
                    }
                }
                group.forEachIndexed { elementIndex, element ->
                    if (elementIndex > 0 && needsWordSeparator(
                            group[elementIndex - 1],
                            element,
                            lineBounds.height()
                        )
                    ) {
                        append(' ')
                    }
                    append(element.text)
                }
            }
        }
        return result(text, bounds)
    }

    private fun trimDetachedEdgeGlyphs(
        elements: MutableList<ElementCandidate>,
        lineHeight: Int
    ) {
        val detachedGap = maxOf(2, (lineHeight * 0.16f).toInt())
        while (elements.size > 1 && isSingleGlyph(elements.first().text) &&
            elements[1].bounds.left - elements[0].bounds.right > detachedGap
        ) {
            elements.removeAt(0)
        }
        while (elements.size > 1 && isSingleGlyph(elements.last().text) &&
            elements.last().bounds.left - elements[elements.lastIndex - 1].bounds.right > detachedGap
        ) {
            elements.removeAt(elements.lastIndex)
        }
    }

    private fun isSingleGlyph(text: String): Boolean =
        text.count { !it.isWhitespace() } == 1

    private fun needsWordSeparator(
        previous: ElementCandidate,
        current: ElementCandidate,
        lineHeight: Int
    ): Boolean {
        val containsLatin = previous.text.any { it.isLetterOrDigit() && !isHanCharacter(it) } ||
            current.text.any { it.isLetterOrDigit() && !isHanCharacter(it) }
        return containsLatin && current.bounds.left - previous.bounds.right > lineHeight * 0.08f
    }

    private fun createInvertedBitmap(bitmap: Bitmap): Bitmap {
        val output = createBitmap(bitmap.width, bitmap.height)
        val inversion = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Canvas(output).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(inversion)
            }
        )
        return output
    }

    private fun createContrastedBitmap(bitmap: Bitmap): Bitmap {
        val output = createBitmap(bitmap.width, bitmap.height)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = ColorMatrix(
            floatArrayOf(
                1.6f, 0f, 0f, 0f, -76.5f,
                0f, 1.6f, 0f, 0f, -76.5f,
                0f, 0f, 1.6f, 0f, -76.5f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayscale.postConcat(contrast)
        Canvas(output).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(grayscale)
            }
        )
        return output
    }

    private fun isUsefulText(item: RecognizedText): Boolean {
        val compact = item.text.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || item.bounds.width() < 2 || item.bounds.height() < 2) return false
        if (compact.length == 1 && !isHanCharacter(compact.first())) return false
        val meaningful = compact.count { it.isLetterOrDigit() || isHanCharacter(it) }
        return meaningful.toFloat() / compact.length >= 0.6f
    }

    private fun textQuality(text: String): Float {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return 0f
        val meaningful = compact.count { it.isLetterOrDigit() || isHanCharacter(it) }
        val replacementPenalty = compact.count { it == '\uFFFD' || it == '?' } * 0.2f
        val boundaryPenalty = listOf(compact.first(), compact.last())
            .count { !it.isLetterOrDigit() && !isHanCharacter(it) } * 0.2f
        val hanCount = compact.count(::isHanCharacter)
        val latinCount = compact.count { it in 'A'..'Z' || it in 'a'..'z' }
        val mixedScriptPenalty = if (hanCount > 0 && latinCount > 0) {
            minOf(hanCount, latinCount).toFloat() / compact.length * 0.3f
        } else {
            0f
        }
        return meaningful.toFloat() / compact.length + minOf(compact.length, 12) / 60f -
            replacementPenalty - boundaryPenalty - mixedScriptPenalty
    }

    private fun meaningfulCharacterCount(text: String): Int =
        text.count { it.isLetterOrDigit() || isHanCharacter(it) }

    private fun mergeAdjacentLineFragments(items: List<RecognizedText>): List<RecognizedText> {
        if (items.size < 2) return items
        val remaining = items.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .toMutableList()
        val merged = mutableListOf<RecognizedText>()

        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var mergedAnother: Boolean
            do {
                mergedAnother = false
                val nextIndex = remaining.indexOfFirst { canMergeOnSameLine(current, it) }
                if (nextIndex >= 0) {
                    current = mergeLineFragments(current, remaining.removeAt(nextIndex))
                    mergedAnother = true
                }
            } while (mergedAnother)
            merged.add(current)
        }
        return merged
    }

    private fun canMergeOnSameLine(first: RecognizedText, second: RecognizedText): Boolean {
        val left = if (first.bounds.left <= second.bounds.left) first else second
        val right = if (left === first) second else first
        val minimumHeight = minOf(left.bounds.height(), right.bounds.height()).coerceAtLeast(1)
        val maximumHeight = maxOf(left.bounds.height(), right.bounds.height()).coerceAtLeast(1)
        if (minimumHeight.toFloat() / maximumHeight < 0.65f) return false

        val verticalOverlap = minOf(left.bounds.bottom, right.bounds.bottom) -
            maxOf(left.bounds.top, right.bounds.top)
        if (verticalOverlap.toFloat() / minimumHeight < 0.65f) return false

        val horizontalGap = right.bounds.left - left.bounds.right
        val maximumGap = maxOf(2, (minimumHeight * 0.75f).toInt())
        return horizontalGap in 0..maximumGap
    }

    private fun mergeLineFragments(
        first: RecognizedText,
        second: RecognizedText
    ): RecognizedText {
        val left = if (first.bounds.left <= second.bounds.left) first else second
        val right = if (left === first) second else first
        val leftWeight = meaningfulCharacterCount(left.text).coerceAtLeast(1)
        val rightWeight = meaningfulCharacterCount(right.text).coerceAtLeast(1)
        val totalWeight = leftWeight + rightWeight
        val separator = if (needsFragmentSeparator(left.text, right.text)) " " else ""
        return RecognizedText(
            text = left.text.trimEnd() + separator + right.text.trimStart(),
            bounds = Rect(left.bounds).apply { union(right.bounds) },
            consensusScore = minOf(left.consensusScore, right.consensusScore),
            passCount = minOf(left.passCount, right.passCount),
            modelConfidence = (
                left.modelConfidence * leftWeight + right.modelConfidence * rightWeight
                ) / totalWeight,
            recognizerScript = if (left.recognizerScript == right.recognizerScript) {
                left.recognizerScript
            } else {
                RecognizerScript.FUSED
            }
        )
    }

    private fun needsFragmentSeparator(left: String, right: String): Boolean {
        val leftCharacter = left.lastOrNull { !it.isWhitespace() } ?: return false
        val rightCharacter = right.firstOrNull { !it.isWhitespace() } ?: return false
        val leftIsLatinOrDigit = leftCharacter.isDigit() ||
            leftCharacter in 'A'..'Z' || leftCharacter in 'a'..'z'
        val rightIsLatinOrDigit = rightCharacter.isDigit() ||
            rightCharacter in 'A'..'Z' || rightCharacter in 'a'..'z'
        return leftIsLatinOrDigit && rightIsLatinOrDigit
    }

    private fun textSimilarity(first: String, second: String): Float {
        val normalizedFirst = normalizeForComparison(first)
        val normalizedSecond = normalizeForComparison(second)
        val maximumLength = maxOf(normalizedFirst.length, normalizedSecond.length)
        if (maximumLength == 0) return 0f
        return 1f - editDistance(normalizedFirst, normalizedSecond).toFloat() / maximumLength
    }

    private fun normalizeForComparison(text: String): String = text
        .filterNot(Char::isWhitespace)
        .trim { !it.isLetterOrDigit() && !isHanCharacter(it) }
        .lowercase()

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (firstIndex in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = firstIndex + 1
            for (secondIndex in second.indices) {
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + if (first[firstIndex] == second[secondIndex]) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private fun overlapRatio(first: Rect, second: Rect): Float {
        val intersectionWidth = minOf(first.right, second.right) - maxOf(first.left, second.left)
        val intersectionHeight = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
        if (intersectionWidth <= 0 || intersectionHeight <= 0) return 0f
        val intersection = intersectionWidth * intersectionHeight
        return intersection.toFloat() / minOf(first.width() * first.height(), second.width() * second.height())
    }

    private fun isDarkRegion(bitmap: Bitmap, bounds: Rect): Boolean {
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val step = maxOf(1, minOf(right - left, bottom - top) / 12)
        var luminanceTotal = 0L
        var samples = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                val color = bitmap[x, y]
                luminanceTotal += (Color.red(color) * 299 + Color.green(color) * 587 +
                    Color.blue(color) * 114) / 1000
                samples++
            }
        }
        return samples > 0 && luminanceTotal / samples < 145
    }

    fun close() {
        chineseRecognizer.close()
        latinRecognizer.close()
    }

    private companion object {
        const val MIN_RETAINED_TEXT_RATIO = 0.8f
        const val STRONG_DETACHED_EDGE_RATIO = 0.28f
        const val MIN_MEANINGFUL_GROUP_CHARACTERS = 2
        val EXPLICIT_GROUP_SEPARATORS = charArrayOf('/', '／', '|', '｜', '·')
        const val PASS_ORIGINAL = 0
        const val PASS_CONTRAST = 1
        const val PASS_INVERTED = 2
    }
}
