package com.example.imagetranslate.ui

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

    private data class TranslatedRegion(
        val source: RecognizedText,
        val translation: String,
        val translated: Boolean
    )

    private data class TextAppearance(
        val foregroundColor: Int,
        val isDarkBackground: Boolean
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
                val scaled = withContext(Dispatchers.Default) { scaleBitmap(bitmap, 2048) }
                val texts = ocrManager.recognize(scaled)

                if (texts.isEmpty()) {
                    binding.tvStatus.text = "未识别到文字"
                    return@launch
                }

                binding.tvStatus.text = "翻译 ${texts.size} 段文字..."
                val regions = texts.mapIndexed { index, item ->
                    binding.tvStatus.text = "翻译 ${index + 1}/${texts.size}..."
                    try {
                        TranslatedRegion(item, translateManager.translate(item.text), true)
                    } catch (e: Exception) {
                        TranslatedRegion(item, item.text, false)
                    }
                }

                binding.tvStatus.text = "擦除原文字..."
                val translatedRegions = regions.filter { it.translated }
                val usePrecise = binding.switchPreciseMask.isChecked
                val erased = withContext(Dispatchers.Default) {
                    if (translatedRegions.isEmpty()) {
                        scaled.copy(Bitmap.Config.ARGB_8888, true)
                    } else if (usePrecise) {
                        inpainter.eraseWithPreciseMask(
                            scaled, translatedRegions.map { it.source.bounds }
                        )
                    } else {
                        inpainter.eraseWithRectMask(
                            scaled, translatedRegions.map { it.source.bounds }
                        )
                    }
                }

                binding.tvStatus.text = "写入翻译..."
                withContext(Dispatchers.Default) {
                    drawTexts(Canvas(erased), scaled, regions)
                }

                processedBitmap = erased
                binding.ivResult.setImageBitmap(processedBitmap)
                val failedCount = regions.count { !it.translated }
                binding.tvStatus.text = if (failedCount == 0) {
                    "完成，共替换 ${regions.size} 段文字"
                } else {
                    "完成，$failedCount 段翻译失败并保留原文"
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
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
        }

        for (region in regions.filter { it.translated }) {
            val bounds = region.source.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val appearance = estimateTextAppearance(sourceBitmap, bounds)
            val isControlLabel = appearance.isDarkBackground &&
                bounds.height() < canvas.height / 10
            val layoutBounds = if (isControlLabel) {
                Rect(bounds)
            } else {
                findAvailableBounds(canvas, bounds, regions)
            }
            val horizontalPadding = if (isControlLabel) 0 else maxOf(2, bounds.height() / 8)
            val layoutWidth = maxOf(1, layoutBounds.width() - horizontalPadding * 2)
            val preferredSize = maxOf(8f, bounds.height() * 0.9f)
            val minimumSize = maxOf(8f, bounds.height() * 0.62f)
            paint.color = appearance.foregroundColor

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
        }
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

    private fun estimateTextAppearance(bitmap: Bitmap, bounds: Rect): TextAppearance {
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
            ?: return TextAppearance(Color.BLACK, false)
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
        return TextAppearance(
            foregroundColor = foreground,
            isDarkBackground = backgroundLuminance < 145
        )
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
        ocrManager.close()
        translateManager.close()
    }
}
