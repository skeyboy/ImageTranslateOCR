package com.example.imagetranslate.ui

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.imagetranslate.App
import com.example.imagetranslate.databinding.ActivityImageTranslateBinding
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.translate.TranslateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImageTranslateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageTranslateBinding
    private val ocrManager = OCRManager()
    private val translateManager = TranslateManager()
    private val inpainter = ImageInpainter()

    private var originalBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var replacementRegions = emptyList<ReplacementRegion>()
    private lateinit var resultGestureDetector: GestureDetector

    private data class TranslatedRegion(
        val source: RecognizedText,
        val translation: String,
        val translated: Boolean,
        val translationFailed: Boolean = false
    )

    private data class TextStyle(
        val foregroundColor: Int,
        val isDarkBackground: Boolean,
        val typeface: Typeface,
        val fontSizeMultiplier: Float
    )

    private data class ReplacementRegion(
        val bounds: Rect,
        val translatedPatch: Bitmap,
        var showingOriginal: Boolean = false
    )

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { loadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        downloadModel()
    }

    private fun setupListeners() {
        binding.replacementOverlay.attachTo(binding.ivResult)
        binding.btnPickImage.setOnClickListener { pickImage.launch("image/*") }

        binding.btnTranslate.setOnClickListener {
            if (!App.isOpenCVReady) {
                Toast.makeText(this, "OpenCV 未就绪，请稍后", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            originalBitmap?.let { translateImage(it) }
        }

        binding.btnSave.setOnClickListener {
            processedBitmap?.let { saveImage(it) }
        }

        binding.checkShowMarkers.setOnCheckedChangeListener { _, isChecked ->
            binding.replacementOverlay.visibility = if (isChecked) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        resultGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                    return toggleReplacementAt(event.x, event.y)
                }
            }
        )
        binding.ivResult.setOnTouchListener { _, event ->
            resultGestureDetector.onTouchEvent(event)
        }
    }

    private fun downloadModel() {
        lifecycleScope.launch {
            try {
                binding.tvStatus.text = "下载翻译模型中..."
                translateManager.downloadModelIfNeeded()
                binding.tvStatus.text = "就绪，请选择图片"
            } catch (e: Exception) {
                binding.tvStatus.text = "模型下载失败：${e.message}"
            }
        }
    }

    private fun loadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                binding.tvStatus.text = "加载图片中..."
                val bitmap = withContext(Dispatchers.IO) {
                    MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
                originalBitmap = bitmap
                binding.ivOriginal.setImageBitmap(bitmap)
                binding.ivResult.setImageBitmap(null)
                processedBitmap = null
                clearReplacementRegions()
                binding.tvStatus.text = "图片已加载"
            } catch (e: Exception) {
                binding.tvStatus.text = "图片加载失败"
                Toast.makeText(this@ImageTranslateActivity, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun translateImage(bitmap: Bitmap) {
        lifecycleScope.launch {
            binding.tvStatus.text = "识别中..."
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.btnTranslate.isEnabled = false

            try {
                val ocrBitmap = withContext(Dispatchers.Default) { createOcrBitmap(bitmap) }
                val texts = try {
                    val recognized = ocrManager.recognize(ocrBitmap)
                    mapRecognizedBounds(recognized, ocrBitmap, bitmap)
                } finally {
                    if (ocrBitmap !== bitmap) ocrBitmap.recycle()
                }

                if (texts.isEmpty()) {
                    binding.tvStatus.text = "未识别到文字"
                    return@launch
                }

                binding.tvStatus.text = "翻译 ${texts.size} 段文字..."
                val regions = texts.mapIndexed { index, item ->
                    binding.tvStatus.text = "翻译 ${index + 1}/${texts.size}..."
                    try {
                        val translatedText = translateManager.translate(item.text)
                        val changed = translatedText.trim() != item.text.trim()
                        TranslatedRegion(item, translatedText, changed)
                    } catch (e: Exception) {
                        TranslatedRegion(item, item.text, false, translationFailed = true)
                    }
                }

                binding.tvStatus.text = "擦除原文字..."
                val translatedRegions = regions.filter { it.translated }
                val usePrecise = binding.switchPreciseMask.isChecked
                val erased = withContext(Dispatchers.Default) {
                    if (translatedRegions.isEmpty()) {
                        bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    } else if (usePrecise) {
                        inpainter.eraseWithPreciseMask(
                            bitmap, translatedRegions.map { it.source.bounds }
                        )
                    } else {
                        inpainter.eraseWithRectMask(
                            bitmap, translatedRegions.map { it.source.bounds }
                        )
                    }
                }

                binding.tvStatus.text = "写入翻译..."
                val renderedRegions = withContext(Dispatchers.Default) {
                    drawTexts(Canvas(erased), bitmap, regions)
                }

                processedBitmap = erased
                clearReplacementRegions()
                replacementRegions = renderedRegions.map { bounds ->
                    ReplacementRegion(
                        bounds = bounds,
                        translatedPatch = createBitmapPatch(erased, bounds)
                    )
                }
                binding.ivResult.setImageBitmap(processedBitmap)
                updateReplacementMarkers()
                val failedCount = regions.count { it.translationFailed }
                val replacedCount = regions.count { it.translated }
                binding.tvStatus.text = if (failedCount == 0) {
                    "完成，共替换 $replacedCount 段文字；点击译文可切换原文"
                } else {
                    "完成，$failedCount 段翻译失败并保留原文；点击译文可切换"
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "失败：${e.message}"
                Toast.makeText(this@ImageTranslateActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnTranslate.isEnabled = true
            }
        }
    }

    private fun drawTexts(
        canvas: Canvas,
        sourceBitmap: Bitmap,
        regions: List<TranslatedRegion>
    ): List<Rect> {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val renderedRegions = mutableListOf<Rect>()

        for (region in regions.filter { it.translated }) {
            val bounds = region.source.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val style = estimateTextStyle(sourceBitmap, bounds, region.source.text)
            val isControlLabel = style.isDarkBackground &&
                bounds.height() < canvas.height / 10
            val layoutBounds = if (isControlLabel) {
                Rect(bounds)
            } else {
                findAvailableBounds(canvas, bounds, regions)
            }
            val horizontalPadding = if (isControlLabel) 0 else maxOf(2, bounds.height() / 8)
            val layoutWidth = maxOf(1, layoutBounds.width() - horizontalPadding * 2)
            val preferredSize = maxOf(8f, bounds.height() * style.fontSizeMultiplier)
            val minimumSize = maxOf(8f, bounds.height() * 0.68f)
            paint.color = style.foregroundColor
            paint.typeface = style.typeface

            var low = minimumSize
            var high = preferredSize
            val alignment = if (isControlLabel) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }
            var best = createTextLayout(region.translation, paint, layoutWidth, low, alignment)
            repeat(8) {
                val candidateSize = (low + high) / 2f
                val candidate = createTextLayout(
                    region.translation, paint, layoutWidth, candidateSize, alignment
                )
                if (candidate.height <= layoutBounds.height()) {
                    low = candidateSize
                    best = candidate
                } else {
                    high = candidateSize
                }
            }

            val x = layoutBounds.left + horizontalPadding.toFloat()
            val y = bounds.top + maxOf(0f, (bounds.height() - best.getLineBottom(0)) / 2f)
            canvas.save()
            canvas.clipRect(layoutBounds)
            canvas.translate(x, y)
            best.draw(canvas)
            canvas.restore()

            val widestLine = (0 until best.lineCount)
                .maxOfOrNull { best.getLineWidth(it) } ?: 0f
            val textLeft = if (alignment == Layout.Alignment.ALIGN_CENTER) {
                x + (layoutWidth - widestLine) / 2f
            } else {
                x
            }
            val restorePadding = maxOf(4, bounds.height() / 5)
            renderedRegions.add(
                Rect(
                    minOf(bounds.left - restorePadding, textLeft.toInt()).coerceAtLeast(0),
                    (bounds.top - restorePadding).coerceAtLeast(0),
                    maxOf(bounds.right + restorePadding, (textLeft + widestLine).toInt())
                        .coerceAtMost(canvas.width),
                    maxOf(bounds.bottom + restorePadding, (y + best.height).toInt())
                        .coerceAtMost(canvas.height)
                )
            )
        }
        return renderedRegions
    }

    private fun toggleReplacementAt(viewX: Float, viewY: Float): Boolean {
        val current = processedBitmap ?: return false
        val original = originalBitmap ?: return false
        if (current.width != original.width || current.height != original.height) return false

        val inverse = Matrix()
        if (!binding.ivResult.imageMatrix.invert(inverse)) return false
        val imagePoint = floatArrayOf(
            viewX - binding.ivResult.paddingLeft,
            viewY - binding.ivResult.paddingTop
        )
        inverse.mapPoints(imagePoint)
        val imageX = imagePoint[0].toInt()
        val imageY = imagePoint[1].toInt()
        val region = replacementRegions
            .filter { it.bounds.contains(imageX, imageY) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
            ?: return false

        val canvas = Canvas(current)
        if (region.showingOriginal) {
            canvas.drawBitmap(region.translatedPatch, null, region.bounds, null)
        } else {
            canvas.drawBitmap(original, region.bounds, region.bounds, null)
        }
        region.showingOriginal = !region.showingOriginal
        binding.ivResult.invalidate()
        updateReplacementMarkers()
        binding.tvStatus.text = if (region.showingOriginal) {
            "该区域已显示原文"
        } else {
            "该区域已恢复译文"
        }
        return true
    }

    private fun clearReplacementRegions() {
        replacementRegions.forEach { it.translatedPatch.recycle() }
        replacementRegions = emptyList()
        if (::binding.isInitialized) binding.replacementOverlay.setMarkers(emptyList())
    }

    private fun updateReplacementMarkers() {
        binding.replacementOverlay.setMarkers(
            replacementRegions.mapIndexed { index, region ->
                ReplacementOverlayView.Marker(
                    number = index + 1,
                    bounds = region.bounds,
                    showingOriginal = region.showingOriginal
                )
            }
        )
        binding.replacementOverlay.postInvalidate()
    }

    private fun createBitmapPatch(bitmap: Bitmap, bounds: Rect): Bitmap {
        val patch = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        Canvas(patch).drawBitmap(bitmap, -bounds.left.toFloat(), -bounds.top.toFloat(), null)
        return patch
    }

    private fun findAvailableBounds(
        canvas: Canvas,
        bounds: Rect,
        regions: List<TranslatedRegion>
    ): Rect {
        val gap = maxOf(3, bounds.height() / 6)
        var right = canvas.width - gap
        var bottom = minOf(canvas.height, bounds.bottom + bounds.height() * 3)

        for (other in regions) {
            val candidate = other.source.bounds
            if (candidate === bounds) continue

            val verticalOverlap = minOf(bounds.bottom, candidate.bottom) -
                maxOf(bounds.top, candidate.top)
            if (verticalOverlap > minOf(bounds.height(), candidate.height()) / 3 &&
                candidate.left >= bounds.right
            ) {
                right = minOf(right, candidate.left - gap)
            }

            val horizontalOverlap = minOf(bounds.right, candidate.right) -
                maxOf(bounds.left, candidate.left)
            if (horizontalOverlap > minOf(bounds.width(), candidate.width()) / 3 &&
                candidate.top >= bounds.bottom
            ) {
                bottom = minOf(bottom, candidate.top - gap)
            }
        }

        right = maxOf(bounds.right, right)
        bottom = maxOf(bounds.bottom, bottom)
        return Rect(bounds.left, bounds.top, right, bottom)
    }

    private fun estimateTextStyle(bitmap: Bitmap, bounds: Rect, sourceText: String): TextStyle {
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val histogram = IntArray(4096)
        val backgroundPadding = maxOf(3, (bottom - top) / 3)
        val outerLeft = (left - backgroundPadding).coerceAtLeast(0)
        val outerTop = (top - backgroundPadding).coerceAtLeast(0)
        val outerRight = (right + backgroundPadding).coerceAtMost(bitmap.width)
        val outerBottom = (bottom + backgroundPadding).coerceAtMost(bitmap.height)
        var backgroundSamples = 0

        for (y in outerTop until outerBottom) {
            for (x in outerLeft until outerRight) {
                if (x in left until right && y in top until bottom) continue
                val color = bitmap.getPixel(x, y)
                val bucket = (Color.red(color) / 16 shl 8) or
                    (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
                histogram[bucket]++
                backgroundSamples++
            }
        }
        if (backgroundSamples == 0) {
            for (y in top until bottom) {
                for (x in left until right) {
                    val color = bitmap.getPixel(x, y)
                    val bucket = (Color.red(color) / 16 shl 8) or
                        (Color.green(color) / 16 shl 4) or (Color.blue(color) / 16)
                    histogram[bucket]++
                }
            }
        }

        val backgroundBucket = histogram.indices.maxByOrNull { histogram[it] }
            ?: return TextStyle(Color.BLACK, false, Typeface.DEFAULT, 1.1f)
        val backgroundRed = ((backgroundBucket shr 8) and 0xF) * 16 + 8
        val backgroundGreen = ((backgroundBucket shr 4) and 0xF) * 16 + 8
        val backgroundBlue = (backgroundBucket and 0xF) * 16 + 8
        var maximumDistanceSquared = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                val redDifference = Color.red(color) - backgroundRed
                val greenDifference = Color.green(color) - backgroundGreen
                val blueDifference = Color.blue(color) - backgroundBlue
                maximumDistanceSquared = maxOf(
                    maximumDistanceSquared,
                    redDifference * redDifference + greenDifference * greenDifference +
                        blueDifference * blueDifference
                )
            }
        }

        var redTotal = 0L
        var greenTotal = 0L
        var blueTotal = 0L
        var count = 0
        val foregroundThreshold = maxOf(1600, (maximumDistanceSquared * 0.45f).toInt())
        val strokeThreshold = maxOf(900, (maximumDistanceSquared * 0.12f).toInt())
        var strokePixels = 0

        for (y in top until bottom) {
            for (x in left until right) {
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                val redDifference = red - backgroundRed
                val greenDifference = green - backgroundGreen
                val blueDifference = blue - backgroundBlue
                val distanceSquared = redDifference * redDifference +
                    greenDifference * greenDifference + blueDifference * blueDifference
                if (distanceSquared >= strokeThreshold) strokePixels++
                if (distanceSquared >= foregroundThreshold) {
                    redTotal += red
                    greenTotal += green
                    blueTotal += blue
                    count++
                }
            }
        }

        val backgroundLuminance = (backgroundRed * 299 + backgroundGreen * 587 +
            backgroundBlue * 114) / 1000
        val estimatedForeground = if (count == 0) {
            if (backgroundLuminance < 145) Color.WHITE else Color.BLACK
        } else {
            Color.rgb(
                (redTotal / count).toInt(),
                (greenTotal / count).toInt(),
                (blueTotal / count).toInt()
            )
        }
        val foregroundLuminance = (Color.red(estimatedForeground) * 299 +
            Color.green(estimatedForeground) * 587 + Color.blue(estimatedForeground) * 114) / 1000
        val foreground = if (kotlin.math.abs(foregroundLuminance - backgroundLuminance) < 90) {
            if (backgroundLuminance < 145) Color.WHITE else Color.BLACK
        } else {
            estimatedForeground
        }
        val area = maxOf(1, (right - left) * (bottom - top))
        val strokeCoverage = strokePixels.toFloat() / area
        val isBold = strokeCoverage >= 0.3f
        val baseTypeface = if (looksLikeCode(sourceText)) {
            Typeface.MONOSPACE
        } else {
            Typeface.SANS_SERIF
        }
        val typeface = Typeface.create(
            baseTypeface,
            if (isBold) Typeface.BOLD else Typeface.NORMAL
        )
        return TextStyle(
            foregroundColor = foreground,
            isDarkBackground = backgroundLuminance < 145,
            typeface = typeface,
            fontSizeMultiplier = if (isBold) 1.05f else 1.12f
        )
    }

    private fun looksLikeCode(text: String): Boolean {
        val compact = text.filterNot(Char::isWhitespace)
        if (compact.isEmpty()) return false
        if (compact.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) {
            return false
        }
        val hasAsciiContent = compact.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() }
        if (!hasAsciiContent) return false
        return text.contains('_') || text.contains("://") ||
            (compact.length >= 4 && compact.all {
                it.isLetterOrDigit() || it in charArrayOf('.', '/', '-', ':')
            })
    }

    private fun createTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textSize: Float,
        alignment: Layout.Alignment
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val scale = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height, 1f)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private fun createOcrBitmap(bitmap: Bitmap): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= 1600) return bitmap
        val scale = minOf(3f, 3072f / longestSide)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun mapRecognizedBounds(
        texts: List<RecognizedText>,
        ocrBitmap: Bitmap,
        processingBitmap: Bitmap
    ): List<RecognizedText> {
        if (ocrBitmap === processingBitmap) return texts
        val scaleX = processingBitmap.width.toFloat() / ocrBitmap.width
        val scaleY = processingBitmap.height.toFloat() / ocrBitmap.height
        return texts.map { item ->
            val source = item.bounds
            RecognizedText(
                text = item.text,
                bounds = Rect(
                    (source.left * scaleX).toInt().coerceIn(0, processingBitmap.width),
                    (source.top * scaleY).toInt().coerceIn(0, processingBitmap.height),
                    (source.right * scaleX).toInt().coerceIn(0, processingBitmap.width),
                    (source.bottom * scaleY).toInt().coerceIn(0, processingBitmap.height)
                )
            )
        }
    }

    private fun saveImage(bitmap: Bitmap) {
        val filename = "translated_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ImageTranslate")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearReplacementRegions()
        ocrManager.close()
        translateManager.close()
    }
}
