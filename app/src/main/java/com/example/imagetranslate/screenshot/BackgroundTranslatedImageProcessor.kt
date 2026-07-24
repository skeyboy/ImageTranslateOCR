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

private data class BackgroundTextStyle(
    val foregroundColor: Int,
    val isDarkBackground: Boolean,
    val typeface: Typeface,
    val fontSizeMultiplier: Float,
    val lineSpacingMultiplier: Float,
    val sourceLineCount: Int
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
                    batch.regions.filter { it.source.bounds in erasedBounds },
                    styleSourceBitmap = bitmap
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
            val fallbackSurface = ScreenThemeColorEstimator.compositableSurface(
                ScreenThemeColorEstimator.estimate(bitmap)
            )
            val renderedPatches = batch.regions.mapNotNull { region ->
                createOverlayPatch(bitmap, region, fallbackSurface)
            }
            val patches = mergeOverlappingPatches(renderedPatches)
            BackgroundTranslatedOverlayResult(
                patches = patches,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                recognizedCount = batch.recognizedCount,
                failedCount = batch.failedCount + (batch.regions.size - renderedPatches.size)
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
        val rawRecognized = if (fastOcr) {
            ocrManager.recognizeFast(bitmap, recognizerFor(mode))
        } else {
            ocrManager.recognize(bitmap)
        }
        val recognized = if (fastOcr) {
            groupLiveTextBlocks(rawRecognized, bitmap.width, bitmap.height)
        } else {
            rawRecognized
        }
        val translated = mutableListOf<BackgroundImageRegion>()
        var failedCount = 0
        val maximumTexts = if (fastOcr) {
            MAX_LIVE_TRANSLATION_TEXTS
        } else {
            MAX_IMAGE_TRANSLATION_TEXTS
        }
        for (source in recognized.take(maximumTexts)) {
            val translationSource = normalizeCacheText(source.text)
            val translation = try {
                val cacheKey = "${mode.name}:$translationSource"
                synchronized(translationCache) { translationCache[cacheKey] }
                    ?: translateManager.translate(translationSource, mode).trim().also { translatedText ->
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
            if (translation.isNotEmpty() && translation != translationSource) {
                translated.add(BackgroundImageRegion(source, translation))
            }
        }
        return BackgroundTranslationBatch(
            recognizedCount = rawRecognized.size,
            regions = translated,
            failedCount = failedCount
        )
    }

    private fun normalizeCacheText(text: String): String =
        text.trim().replace(CACHE_WHITESPACE_REGEX, " ")

    private fun createOverlayPatch(
        bitmap: Bitmap,
        region: BackgroundImageRegion,
        fallbackSurface: Int
    ): ScreenTranslationPatch? {
        val sourceBounds = region.source.bounds.clampedTo(bitmap) ?: return null
        val localSurface = estimateLocalSurface(bitmap, sourceBounds, fallbackSurface)
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
            val patchBitmap = Bitmap.createBitmap(
                cropBounds.width(),
                cropBounds.height(),
                Bitmap.Config.ARGB_8888
            )
            output = patchBitmap
            val localRegion = region.copy(
                source = region.source.copy(bounds = localBounds)
            )
            val rendered = BackgroundTranslatedImageRenderer.render(
                patchBitmap,
                listOf(localRegion),
                styleSourceBitmap = crop,
                overlayBackgroundColor = localSurface
            )
            if (rendered.isEmpty()) {
                patchBitmap.recycle()
                output = null
                null
            } else {
                ScreenTranslationPatch(Rect(cropBounds), patchBitmap).also { output = null }
            }
        } catch (_: Exception) {
            output?.takeIf { !it.isRecycled }?.recycle()
            null
        } finally {
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
    }

    private fun groupLiveTextBlocks(
        recognized: List<RecognizedText>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<RecognizedText> {
        val contentTop = (sourceHeight * LIVE_CONTENT_TOP_RATIO).toInt()
        val contentBottom = (sourceHeight * LIVE_CONTENT_BOTTOM_RATIO).toInt()
        val contentLines = recognized.filter { item ->
            val centerY = item.bounds.centerY()
            centerY in contentTop until contentBottom
        }
        val groups = LiveOverlayLayoutPolicy.groupTextLines(
            contentLines.mapIndexed { index, item ->
                LiveTextLineBounds(
                    index = index,
                    left = item.bounds.left.coerceIn(0, sourceWidth),
                    top = item.bounds.top.coerceIn(0, sourceHeight),
                    right = item.bounds.right.coerceIn(0, sourceWidth),
                    bottom = item.bounds.bottom.coerceIn(0, sourceHeight),
                    text = item.text
                )
            }
        )
        return groups.mapNotNull { indices ->
            val lines = indices.mapNotNull(contentLines::getOrNull)
            if (lines.isEmpty()) null else mergeLiveTextLines(lines)
        }
    }

    private fun mergeLiveTextLines(lines: List<RecognizedText>): RecognizedText {
        val ordered = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bounds = Rect(ordered.first().bounds)
        ordered.drop(1).forEach { bounds.union(it.bounds) }
        val weights = ordered.map { item ->
            item.text.count { it.isLetterOrDigit() }.coerceAtLeast(1)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        return RecognizedText(
            text = ordered.joinToString("\n") { it.text.trim() },
            bounds = bounds,
            consensusScore = ordered.minOf { it.consensusScore },
            passCount = ordered.minOf { it.passCount },
            modelConfidence = ordered.zip(weights).sumOf { (item, weight) ->
                (item.modelConfidence * weight).toDouble()
            }.toFloat() / totalWeight,
            recognizerScript = ordered.map { it.recognizerScript }.distinct().singleOrNull()
                ?: RecognizerScript.FUSED
        )
    }

    private fun estimateLocalSurface(
        bitmap: Bitmap,
        bounds: Rect,
        fallbackSurface: Int
    ): Int {
        val padding = maxOf(
            LOCAL_SURFACE_MINIMUM_PADDING_PX,
            minOf(LOCAL_SURFACE_MAXIMUM_PADDING_PX, bounds.height() / 3)
        )
        val sampleBounds = Rect(
            (bounds.left - padding).coerceAtLeast(0),
            (bounds.top - padding).coerceAtLeast(0),
            (bounds.right + padding).coerceAtMost(bitmap.width),
            (bounds.bottom + padding).coerceAtMost(bitmap.height)
        )
        val sampleStep = maxOf(
            1,
            maxOf(sampleBounds.width(), sampleBounds.height()) / LOCAL_SURFACE_SAMPLE_GRID
        )
        val samples = ArrayList<Int>()
        for (y in sampleBounds.top until sampleBounds.bottom step sampleStep) {
            for (x in sampleBounds.left until sampleBounds.right step sampleStep) {
                samples += bitmap.getPixel(x, y)
            }
        }
        if (samples.size < LOCAL_SURFACE_MINIMUM_SAMPLES) return fallbackSurface
        return ScreenThemeColorEstimator.compositableSurface(
            ScreenThemeColorEstimator.estimate(samples.toIntArray())
        )
    }

    private fun mergeOverlappingPatches(
        patches: List<ScreenTranslationPatch>
    ): List<ScreenTranslationPatch> {
        if (patches.size < 2) return patches
        val groups = LiveOverlayLayoutPolicy.groupIntersectingPatches(
            patches.mapIndexed { index, patch ->
                LivePatchBounds(
                    index,
                    patch.bounds.left,
                    patch.bounds.top,
                    patch.bounds.right,
                    patch.bounds.bottom
                )
            },
            mergeGap = PATCH_WINDOW_MERGE_GAP_PX
        )
        return groups.map { indices ->
            val groupedPatches = indices.map(patches::get)
            if (groupedPatches.size == 1) return@map groupedPatches.first()

            val union = Rect(groupedPatches.first().bounds)
            groupedPatches.drop(1).forEach { union.union(it.bounds) }
            val mergedBitmap = Bitmap.createBitmap(
                union.width(),
                union.height(),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(mergedBitmap)
            groupedPatches.forEach { patch ->
                canvas.drawBitmap(
                    patch.bitmap,
                    (patch.bounds.left - union.left).toFloat(),
                    (patch.bounds.top - union.top).toFloat(),
                    null
                )
            }
            groupedPatches.recyclePatchBitmaps()
            ScreenTranslationPatch(union, mergedBitmap)
        }
    }

    private companion object {
        const val MAX_IMAGE_TRANSLATION_TEXTS = 24
        const val MAX_LIVE_TRANSLATION_TEXTS = 32
        const val MAX_TRANSLATION_CACHE_ENTRIES = 256
        const val OVERLAY_PATCH_PADDING_PX = 5
        const val PATCH_WINDOW_MERGE_GAP_PX = 3
        const val LOCAL_SURFACE_MINIMUM_PADDING_PX = 8
        const val LOCAL_SURFACE_MAXIMUM_PADDING_PX = 36
        const val LOCAL_SURFACE_SAMPLE_GRID = 48
        const val LOCAL_SURFACE_MINIMUM_SAMPLES = 16
        const val LIVE_CONTENT_TOP_RATIO = 0.08f
        const val LIVE_CONTENT_BOTTOM_RATIO = 0.94f
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
    fun render(
        bitmap: Bitmap,
        regions: List<BackgroundImageRegion>,
        styleSourceBitmap: Bitmap = bitmap,
        overlayBackgroundColor: Int? = null
    ): List<Rect> {
        val canvas = Canvas(bitmap)
        val renderedRegions = mutableListOf<Rect>()
        regions.forEach { region ->
            val bounds = region.source.bounds.clampedTo(bitmap) ?: return@forEach
            val estimatedStyle = estimateTextStyle(
                styleSourceBitmap,
                bounds,
                region.source.text
            )
            val style = if (overlayBackgroundColor == null) {
                estimatedStyle
            } else {
                val isDarkTheme = isDarkColor(overlayBackgroundColor)
                estimatedStyle.copy(
                    foregroundColor = readableForegroundColor(
                        estimatedStyle.foregroundColor,
                        overlayBackgroundColor
                    ),
                    isDarkBackground = isDarkTheme
                )
            }
            if (overlayBackgroundColor != null) {
                drawCompensatedBackground(
                    canvas,
                    bitmap,
                    styleSourceBitmap,
                    bounds,
                    overlayBackgroundColor
                )
            }
            val isControlLabel = style.isDarkBackground &&
                region.source.text.filterNot(Char::isWhitespace).length <= 20
            val horizontalPadding = if (isControlLabel) 0 else maxOf(2, bounds.height() / 8)
            val layoutWidth = (bounds.width() - horizontalPadding * 2).coerceAtLeast(1)
            val alignment = if (isControlLabel) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.foregroundColor
                typeface = style.typeface
            }
            val layout = fittingLayout(
                region.translation,
                paint,
                layoutWidth,
                bounds.height().coerceAtLeast(1),
                alignment,
                style
            )
            canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(
                bounds.left + horizontalPadding.toFloat(),
                bounds.top + ((bounds.height() - layout.height) / 2f).coerceAtLeast(0f)
            )
            layout.draw(canvas)
            canvas.restore()
            renderedRegions.add(Rect(bounds))
        }
        return renderedRegions
    }

    private fun drawCompensatedBackground(
        canvas: Canvas,
        bitmap: Bitmap,
        sourceBitmap: Bitmap,
        textBounds: Rect,
        themeColor: Int
    ) {
        val materialBounds = overlayMaterialBounds(textBounds, bitmap) ?: return
        val sourcePixels = IntArray(materialBounds.width() * materialBounds.height())
        sourceBitmap.getPixels(
            sourcePixels,
            0,
            materialBounds.width(),
            materialBounds.left,
            materialBounds.top,
            materialBounds.width(),
            materialBounds.height()
        )
        sourcePixels.indices.forEach { index ->
            sourcePixels[index] = ScreenThemeColorEstimator.compensationColor(
                targetSurface = themeColor,
                sourceColor = sourcePixels[index]
            )
        }
        val compensation = Bitmap.createBitmap(
            materialBounds.width(),
            materialBounds.height(),
            Bitmap.Config.ARGB_8888
        ).apply {
            setPixels(
                sourcePixels,
                0,
                materialBounds.width(),
                0,
                0,
                materialBounds.width(),
                materialBounds.height()
            )
        }
        try {
            canvas.drawBitmap(
                compensation,
                materialBounds.left.toFloat(),
                materialBounds.top.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            if (!compensation.isRecycled) compensation.recycle()
        }
    }

    private fun overlayMaterialBounds(textBounds: Rect, bitmap: Bitmap): Rect? {
        val materialPadding = minOf(
            OVERLAY_MAXIMUM_PADDING_PX,
            maxOf(OVERLAY_MINIMUM_PADDING_PX, textBounds.height() / 10)
        )
        return Rect(
            textBounds.left - materialPadding,
            textBounds.top - materialPadding,
            textBounds.right + materialPadding,
            textBounds.bottom + materialPadding
        ).clampedTo(bitmap)
    }

    private fun isDarkColor(color: Int): Boolean = luminance(
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    ) < DARK_BACKGROUND_LUMINANCE

    private fun readableForegroundColor(candidate: Int, surface: Int): Int {
        val candidateLuminance = luminance(
            Color.red(candidate),
            Color.green(candidate),
            Color.blue(candidate)
        )
        val surfaceLuminance = luminance(
            Color.red(surface),
            Color.green(surface),
            Color.blue(surface)
        )
        return if (kotlin.math.abs(candidateLuminance - surfaceLuminance) >=
            MINIMUM_CONTRAST_DELTA
        ) {
            candidate
        } else if (surfaceLuminance < DARK_BACKGROUND_LUMINANCE) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }

    private fun fittingLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        height: Int,
        alignment: Layout.Alignment,
        style: BackgroundTextStyle
    ): StaticLayout {
        val sourceLineHeight = height.toFloat() / style.sourceLineCount.coerceAtLeast(1)
        var low = maxOf(MINIMUM_TEXT_SIZE_PX, sourceLineHeight * MINIMUM_FONT_HEIGHT_RATIO)
        var high = maxOf(low, sourceLineHeight * style.fontSizeMultiplier)
        var best = createLayout(
            text,
            paint,
            width,
            low,
            alignment,
            style.lineSpacingMultiplier
        )
        repeat(8) {
            val size = (low + high) / 2f
            val candidate = createLayout(
                text,
                paint,
                width,
                size,
                alignment,
                style.lineSpacingMultiplier
            )
            if (candidate.height <= height && !hasOrphanedLastLine(candidate, text)) {
                low = size
                best = candidate
            } else {
                high = size
            }
        }
        return best
    }

    private fun hasOrphanedLastLine(layout: StaticLayout, text: String): Boolean {
        if (layout.lineCount <= 1 || text.count { !it.isWhitespace() } > SHORT_TEXT_LIMIT) {
            return false
        }
        val lastLine = layout.lineCount - 1
        val visibleCharacters = text
            .substring(layout.getLineStart(lastLine), layout.getLineEnd(lastLine))
            .count { !it.isWhitespace() }
        return visibleCharacters <= ORPHANED_LINE_CHARACTER_LIMIT
    }

    private fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textSize: Float,
        alignment: Layout.Alignment,
        lineSpacingMultiplier: Float
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .build()
    }

    private fun estimateTextStyle(
        bitmap: Bitmap,
        sourceBounds: Rect,
        sourceText: String
    ): BackgroundTextStyle {
        val bounds = sourceBounds.clampedTo(bitmap)
            ?: return defaultStyle(isDarkBackground = false, sourceText = sourceText)
        val padding = maxOf(3, bounds.height() / 3)
        val outer = Rect(
            (bounds.left - padding).coerceAtLeast(0),
            (bounds.top - padding).coerceAtLeast(0),
            (bounds.right + padding).coerceAtMost(bitmap.width),
            (bounds.bottom + padding).coerceAtMost(bitmap.height)
        )
        var backgroundRed = 0L
        var backgroundGreen = 0L
        var backgroundBlue = 0L
        var backgroundSamples = 0
        for (y in outer.top until outer.bottom) {
            for (x in outer.left until outer.right) {
                if (x in bounds.left until bounds.right && y in bounds.top until bounds.bottom) {
                    continue
                }
                val color = bitmap.getPixel(x, y)
                backgroundRed += Color.red(color)
                backgroundGreen += Color.green(color)
                backgroundBlue += Color.blue(color)
                backgroundSamples++
            }
        }
        if (backgroundSamples == 0) {
            return defaultStyle(isDarkBackground = false, sourceText = sourceText)
        }
        val red = (backgroundRed / backgroundSamples).toInt()
        val green = (backgroundGreen / backgroundSamples).toInt()
        val blue = (backgroundBlue / backgroundSamples).toInt()
        val backgroundLuminance = luminance(red, green, blue)

        var maximumDistance = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val color = bitmap.getPixel(x, y)
                maximumDistance = maxOf(
                    maximumDistance,
                    colorDistanceSquared(color, red, green, blue)
                )
            }
        }
        val foregroundThreshold = maxOf(1_600, (maximumDistance * 0.45f).toInt())
        val strokeThreshold = maxOf(900, (maximumDistance * 0.12f).toInt())
        var foregroundRed = 0L
        var foregroundGreen = 0L
        var foregroundBlue = 0L
        var foregroundSamples = 0
        var strokePixels = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val color = bitmap.getPixel(x, y)
                val distance = colorDistanceSquared(color, red, green, blue)
                if (distance >= strokeThreshold) strokePixels++
                if (distance >= foregroundThreshold) {
                    foregroundRed += Color.red(color)
                    foregroundGreen += Color.green(color)
                    foregroundBlue += Color.blue(color)
                    foregroundSamples++
                }
            }
        }
        val estimatedForeground = if (foregroundSamples == 0) {
            if (backgroundLuminance < DARK_BACKGROUND_LUMINANCE) Color.WHITE else Color.BLACK
        } else {
            Color.rgb(
                (foregroundRed / foregroundSamples).toInt(),
                (foregroundGreen / foregroundSamples).toInt(),
                (foregroundBlue / foregroundSamples).toInt()
            )
        }
        val foregroundLuminance = luminance(
            Color.red(estimatedForeground),
            Color.green(estimatedForeground),
            Color.blue(estimatedForeground)
        )
        val foreground = if (
            kotlin.math.abs(foregroundLuminance - backgroundLuminance) < MINIMUM_CONTRAST_DELTA
        ) {
            if (backgroundLuminance < DARK_BACKGROUND_LUMINANCE) Color.WHITE else Color.BLACK
        } else {
            estimatedForeground
        }
        val area = maxOf(1, bounds.width() * bounds.height())
        val isBold = strokePixels.toFloat() / area >= BOLD_STROKE_COVERAGE
        val sourceLineCount = sourceText.lineSequence().count().coerceAtLeast(1)
        val baseTypeface = if (looksLikeCode(sourceText)) {
            Typeface.MONOSPACE
        } else {
            Typeface.SANS_SERIF
        }
        return BackgroundTextStyle(
            foregroundColor = foreground,
            isDarkBackground = backgroundLuminance < DARK_BACKGROUND_LUMINANCE,
            typeface = Typeface.create(
                baseTypeface,
                if (isBold) Typeface.BOLD else Typeface.NORMAL
            ),
            fontSizeMultiplier = if (isBold) 1.05f else 1.12f,
            lineSpacingMultiplier = if (isBold) 1.02f else 1.08f,
            sourceLineCount = sourceLineCount
        )
    }

    private fun defaultStyle(
        isDarkBackground: Boolean,
        sourceText: String
    ) = BackgroundTextStyle(
        foregroundColor = if (isDarkBackground) Color.WHITE else Color.BLACK,
        isDarkBackground = isDarkBackground,
        typeface = if (looksLikeCode(sourceText)) Typeface.MONOSPACE else Typeface.SANS_SERIF,
        fontSizeMultiplier = 1.1f,
        lineSpacingMultiplier = 1.06f,
        sourceLineCount = sourceText.lineSequence().count().coerceAtLeast(1)
    )

    private fun looksLikeCode(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty() || compact.any(::isHanCharacter)) return false
        val hasAsciiContent = compact.any { it.isLetterOrDigit() }
        return hasAsciiContent && (
            text.contains('_') || text.contains("://") ||
                (compact.length >= 4 && compact.all {
                    it.isLetterOrDigit() || it in charArrayOf('.', '/', '-', ':')
                })
            )
    }

    private fun isHanCharacter(character: Char): Boolean =
        Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN

    private fun colorDistanceSquared(color: Int, red: Int, green: Int, blue: Int): Int {
        val redDifference = Color.red(color) - red
        val greenDifference = Color.green(color) - green
        val blueDifference = Color.blue(color) - blue
        return redDifference * redDifference + greenDifference * greenDifference +
            blueDifference * blueDifference
    }

    private fun luminance(red: Int, green: Int, blue: Int): Int =
        (red * 299 + green * 587 + blue * 114) / 1_000

    private const val MINIMUM_TEXT_SIZE_PX = 8f
    private const val MINIMUM_FONT_HEIGHT_RATIO = 0.62f
    private const val DARK_BACKGROUND_LUMINANCE = 145
    private const val MINIMUM_CONTRAST_DELTA = 90
    private const val BOLD_STROKE_COVERAGE = 0.3f
    private const val SHORT_TEXT_LIMIT = 20
    private const val ORPHANED_LINE_CHARACTER_LIMIT = 1
    private const val OVERLAY_MINIMUM_PADDING_PX = 3
    private const val OVERLAY_MAXIMUM_PADDING_PX = 5
}
