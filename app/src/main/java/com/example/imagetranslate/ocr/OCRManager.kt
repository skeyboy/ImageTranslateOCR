package com.example.imagetranslate.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

data class RecognizedText(
    val text: String,
    val bounds: Rect
)

class OCRManager {
    private data class OcrCandidate(
        val result: RecognizedText,
        val pass: Int,
        val reliability: Float
    )

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognize(bitmap: Bitmap): List<RecognizedText> {
        val candidates = recognizeSingle(bitmap)
            .filter(::isUsefulText)
            .mapTo(mutableListOf()) { OcrCandidate(it, PASS_ORIGINAL, 0.25f) }

        val contrasted = createContrastedBitmap(bitmap)
        try {
            recognizeSingle(contrasted)
                .filter(::isUsefulText)
                .mapTo(candidates) { OcrCandidate(it, PASS_CONTRAST, 0.15f) }
        } catch (_: Exception) {
            // The original pass remains usable if an enhancement pass fails.
        } finally {
            contrasted.recycle()
        }

        val inverted = createInvertedBitmap(bitmap)
        try {
            recognizeSingle(inverted)
                .filter(::isUsefulText)
                .filter { isDarkRegion(bitmap, it.bounds) }
                .mapTo(candidates) { OcrCandidate(it, PASS_INVERTED, 0.1f) }
        } catch (_: Exception) {
            // The original and contrast passes remain usable.
        } finally {
            inverted.recycle()
        }

        return fuseCandidates(candidates, bitmap)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
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

            cluster.maxByOrNull { candidate ->
                val agreement = cluster
                    .filterNot { it === candidate }
                    .sumOf { other ->
                        textSimilarity(candidate.result.text, other.result.text).toDouble()
                    }.toFloat()
                textQuality(candidate.result.text) + candidate.reliability + agreement * 0.3f
            }?.result
        }
    }

    private suspend fun recognizeSingle(bitmap: Bitmap): List<RecognizedText> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    val results = mutableListOf<RecognizedText>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            line.boundingBox?.let { bounds ->
                                results.add(RecognizedText(line.text, bounds))
                            }
                        }
                    }
                    continuation.resume(results)
                }
                .addOnFailureListener { e ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
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
        recognizer.close()
    }

    private companion object {
        const val PASS_ORIGINAL = 0
        const val PASS_CONTRAST = 1
        const val PASS_INVERTED = 2
    }
}
