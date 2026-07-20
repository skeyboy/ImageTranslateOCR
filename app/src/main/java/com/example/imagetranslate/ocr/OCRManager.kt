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

data class RecognizedText(
    val text: String,
    val bounds: Rect
)

class OCRManager {
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun recognize(bitmap: Bitmap): List<RecognizedText> {
        val primary = recognizeSingle(bitmap).filter(::isUsefulText).toMutableList()
        val inverted = createInvertedBitmap(bitmap)
        val enhanced = try {
            recognizeSingle(inverted).filter(::isUsefulText)
        } catch (_: Exception) {
            emptyList()
        } finally {
            inverted.recycle()
        }

        for (candidate in enhanced) {
            val overlappingIndex = primary.indexOfFirst {
                overlapRatio(it.bounds, candidate.bounds) >= 0.45f
            }
            if (overlappingIndex < 0) {
                if (isDarkRegion(bitmap, candidate.bounds)) primary.add(candidate)
                continue
            }

            val existing = primary[overlappingIndex]
            val preferEnhanced = isDarkRegion(bitmap, candidate.bounds) ||
                textQuality(candidate.text) > textQuality(existing.text) + 0.15f
            if (preferEnhanced) primary[overlappingIndex] = candidate
        }

        return primary.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
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
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
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

    private fun isUsefulText(item: RecognizedText): Boolean {
        val compact = item.text.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || item.bounds.width() < 2 || item.bounds.height() < 2) return false
        val meaningful = compact.count { it.isLetterOrDigit() || isHanCharacter(it) }
        return meaningful.toFloat() / compact.length >= 0.6f
    }

    private fun textQuality(text: String): Float {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return 0f
        val meaningful = compact.count { it.isLetterOrDigit() || isHanCharacter(it) }
        val replacementPenalty = compact.count { it == '\uFFFD' || it == '?' } * 0.2f
        return meaningful.toFloat() / compact.length + minOf(compact.length, 12) / 60f - replacementPenalty
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
                val color = bitmap.getPixel(x, y)
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
}
