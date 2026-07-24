package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.imagetranslate.App
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.CancellationException

internal data class BackgroundTranslatedImageResult(
    val bitmap: Bitmap,
    val recognizedCount: Int,
    val replacedCount: Int,
    val failedCount: Int,
    val renderedRegions: List<Rect> = emptyList()
)

internal data class BackgroundTranslatedOverlayResult(
    val patches: List<ScreenTranslationPatch>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val recognizedCount: Int,
    val failedCount: Int
)

private data class BackgroundImageRegion(
    val source: RecognizedText,
    val translation: String
)

private data class BackgroundTranslationBatch(
    val recognizedCount: Int,
    val regions: List<BackgroundImageRegion>,
    val failedCount: Int
)

internal class BackgroundTranslatedImageProcessor(
    private val reuseResources: Boolean = false
) {
    private val ocrManager = OCRManager()
    private val translateManager = TranslateManager()
    private val translationCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?
        ): Boolean = size > MAX_TRANSLATION_CACHE_ENTRIES
    }
    private var closed = false

    suspend fun translate(
        bitmap: Bitmap,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        fastOcr: Boolean = false,
        allowEmpty: Boolean = false
    ): BackgroundTranslatedImageResult {
        check(!closed) { "Image processor is closed" }
        return try {
            val batch = recognizeAndTranslate(bitmap, mode, fastOcr)
            if (batch.regions.isEmpty()) {
                require(allowEmpty) { "No translatable text found" }
                return BackgroundTranslatedImageResult(
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true),
                    recognizedCount = batch.recognizedCount,
                    replacedCount = 0,
                    failedCount = batch.failedCount
                )
            }

            check(App.isOpenCVReady) { "OpenCV is not ready" }

            val inpaintResult = ImageInpainter().eraseWithPreciseMask(
                bitmap,
                batch.regions.map { it.source.bounds }
            )
            val output = inpaintResult.bitmap
            val erasedBounds = inpaintResult.erasedRegions.toSet()
            try {
                val renderedRegions = BackgroundTranslatedImageRenderer.render(
                    output,
                    batch.regions.filter { it.source.bounds in erasedBounds }
                )
                BackgroundTranslatedImageResult(
                    bitmap = output,
                    recognizedCount = batch.recognizedCount,
                    replacedCount = renderedRegions.size,
                    failedCount = batch.failedCount,
                    renderedRegions = renderedRegions
                )
            } catch (error: Exception) {
                output.recycle()
                throw error
            }
        } finally {
            if (!reuseResources) close()
        }
    }

    suspend fun translateForOverlay(
        bitmap: Bitmap,
        mode: TranslationMode
    ): BackgroundTranslatedOverlayResult {
        check(!closed) { "Image processor is closed" }
        return try {
            val batch = recognizeAndTranslate(bitmap, mode, fastOcr = true)
            if (batch.regions.isNotEmpty()) {
                check(App.isOpenCVReady) { "OpenCV is not ready" }
            }
            val patches = batch.regions.mapNotNull { region ->
                createOverlayPatch(bitmap, region)
            }
            BackgroundTranslatedOverlayResult(
                patches = patches,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                recognizedCount = batch.recognizedCount,
                failedCount = batch.failedCount + (batch.regions.size - patches.size)
            )
        } finally {
            if (!reuseResources) close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        ocrManager.close()
        translateManager.close()
        synchronized(translationCache) { translationCache.clear() }
    }

    private fun recognizerFor(mode: TranslationMode): RecognizerScript = when (mode) {
        TranslationMode.ENGLISH_TO_CHINESE -> RecognizerScript.LATIN
        TranslationMode.CHINESE_TO_ENGLISH -> RecognizerScript.CHINESE
        TranslationMode.AUTO_BIDIRECTIONAL -> RecognizerScript.FUSED
    }

    private suspend fun recognizeAndTranslate(
        bitmap: Bitmap,
        mode: TranslationMode,
        fastOcr: Boolean
    ): BackgroundTranslationBatch {
        val recognized = if (fastOcr) {
            ocrManager.recognizeFast(bitmap, recognizerFor(mode))
        } else {
            ocrManager.recognize(bitmap)
        }
        val translated = mutableListOf<BackgroundImageRegion>()
        var failedCount = 0
        val maximumTexts = if (fastOcr) {
            MAX_LIVE_TRANSLATION_TEXTS
        } else {
            MAX_IMAGE_TRANSLATION_TEXTS
        }
        for (source in recognized.take(maximumTexts)) {
            val translation = try {
                val cacheKey = "${mode.name}:${normalizeCacheText(source.text)}"
                synchronized(translationCache) { translationCache[cacheKey] }
                    ?: translateManager.translate(source.text, mode).trim().also { translatedText ->
                        synchronized(translationCache) {
                            translationCache[cacheKey] = translatedText
                        }
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failedCount++
                continue
            }
            if (translation.isNotEmpty() && translation != source.text.trim()) {
                translated.add(BackgroundImageRegion(source, translation))
            }
        }
        return BackgroundTranslationBatch(
            recognizedCount = recognized.size,
            regions = translated,
            failedCount = failedCount
        )
    }

    private fun normalizeCacheText(text: String): String =
        text.trim().replace(CACHE_WHITESPACE_REGEX, " ")

    private fun createOverlayPatch(
        bitmap: Bitmap,
        region: BackgroundImageRegion
    ): ScreenTranslationPatch? {
        val sourceBounds = region.source.bounds.clampedTo(bitmap) ?: return null
        val cropBounds = Rect(
            (sourceBounds.left - OVERLAY_PATCH_PADDING_PX).coerceAtLeast(0),
            (sourceBounds.top - OVERLAY_PATCH_PADDING_PX).coerceAtLeast(0),
            (sourceBounds.right + OVERLAY_PATCH_PADDING_PX).coerceAtMost(bitmap.width),
            (sourceBounds.bottom + OVERLAY_PATCH_PADDING_PX).coerceAtMost(bitmap.height)
        )
        val crop = Bitmap.createBitmap(
            bitmap,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        val localBounds = Rect(
            sourceBounds.left - cropBounds.left,
            sourceBounds.top - cropBounds.top,
            sourceBounds.right - cropBounds.left,
            sourceBounds.bottom - cropBounds.top
        )
        var output: Bitmap? = null
        return try {
            val inpaintResult = ImageInpainter().eraseWithPreciseMask(crop, listOf(localBounds))
            val patchBitmap = inpaintResult.bitmap
            output = patchBitmap
            if (localBounds !in inpaintResult.erasedRegions) {
                patchBitmap.recycle()
                output = null
                null
            } else {
                val localRegion = region.copy(
                    source = region.source.copy(bounds = localBounds)
                )
                val rendered = BackgroundTranslatedImageRenderer.render(
                    patchBitmap,
                    listOf(localRegion)
                )
                if (rendered.isEmpty()) {
                    patchBitmap.recycle()
                    output = null
                    null
                } else {
                    ScreenTranslationPatch(Rect(cropBounds), patchBitmap).also { output = null }
                }
            }
        } catch (_: Exception) {
            output?.takeIf { !it.isRecycled }?.recycle()
            null
        } finally {
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
    }

    private companion object {
        const val MAX_IMAGE_TRANSLATION_TEXTS = 24
        const val MAX_LIVE_TRANSLATION_TEXTS = 32
        const val MAX_TRANSLATION_CACHE_ENTRIES = 256
        const val OVERLAY_PATCH_PADDING_PX = 5
        val CACHE_WHITESPACE_REGEX = Regex("\\s+")
    }
}

private fun Rect.clampedTo(bitmap: Bitmap): Rect? {
    val clamped = Rect(
        left.coerceIn(0, bitmap.width),
        top.coerceIn(0, bitmap.height),
        right.coerceIn(0, bitmap.width),
        bottom.coerceIn(0, bitmap.height)
    )
    return clamped.takeIf { it.width() > 0 && it.height() > 0 }
}

private object BackgroundTranslatedImageRenderer {
    fun render(bitmap: Bitmap, regions: List<BackgroundImageRegion>): List<Rect> {
        val canvas = Canvas(bitmap)
        val renderedRegions = mutableListOf<Rect>()
        regions.forEach { region ->
            val bounds = region.source.bounds.clampedTo(bitmap) ?: return@forEach
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = readableTextColor(bitmap, bounds)
                typeface = Typeface.DEFAULT
            }
            val layoutWidth = bounds.width().coerceAtLeast(1)
            val layout = fittingLayout(
                region.translation,
                paint,
                layoutWidth,
                bounds.height().coerceAtLeast(1)
            )
            canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(
                bounds.left.toFloat(),
                bounds.top + ((bounds.height() - layout.height) / 2f).coerceAtLeast(0f)
            )
            layout.draw(canvas)
            canvas.restore()
            renderedRegions.add(Rect(bounds))
        }
        return renderedRegions
    }

    private fun fittingLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        height: Int
    ): StaticLayout {
        var low = MINIMUM_TEXT_SIZE_PX
        var high = height.toFloat().coerceAtLeast(low)
        var best = createLayout(text, paint, width, low)
        repeat(8) {
            val size = (low + high) / 2f
            val candidate = createLayout(text, paint, width, size)
            if (candidate.height <= height) {
                low = size
                best = candidate
            } else {
                high = size
            }
        }
        return best
    }

    private fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textSize: Float
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun readableTextColor(bitmap: Bitmap, bounds: Rect): Int {
        val stepX = (bounds.width() / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        val stepY = (bounds.height() / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        var luminanceSum = 0.0
        var sampleCount = 0
        var y = bounds.top
        while (y < bounds.bottom) {
            var x = bounds.left
            while (x < bounds.right) {
                val color = bitmap.getPixel(x, y)
                luminanceSum += 0.2126 * Color.red(color) +
                    0.7152 * Color.green(color) +
                    0.0722 * Color.blue(color)
                sampleCount++
                x += stepX
            }
            y += stepY
        }
        val average = if (sampleCount == 0) 255.0 else luminanceSum / sampleCount
        return if (average >= LIGHT_BACKGROUND_THRESHOLD) Color.rgb(24, 24, 24) else Color.WHITE
    }

    private const val MINIMUM_TEXT_SIZE_PX = 8f
    private const val SAMPLE_GRID_SIZE = 6
    private const val LIGHT_BACKGROUND_THRESHOLD = 150.0
}
