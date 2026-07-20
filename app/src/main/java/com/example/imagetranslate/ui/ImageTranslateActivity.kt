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
                val usePrecise = binding.switchPreciseMask.isChecked
                val erased = withContext(Dispatchers.Default) {
                    if (usePrecise) {
                        inpainter.eraseWithPreciseMask(scaled, texts.map { it.bounds })
                    } else {
                        inpainter.eraseWithRectMask(scaled, texts.map { it.bounds })
                    }
                }

                binding.tvStatus.text = "写入翻译..."
                withContext(Dispatchers.Default) {
                    drawTexts(Canvas(erased), regions)
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
        regions: List<TranslatedRegion>
    ) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
        }

        for (region in regions) {
            val bounds = region.source.bounds
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val horizontalPadding = maxOf(2, bounds.width() / 30)
            val layoutWidth = maxOf(1, bounds.width() - horizontalPadding * 2)
            var low = 6f
            var high = maxOf(8f, bounds.height() * 1.1f)
            var best = createTextLayout(region.translation, paint, layoutWidth, low)
            repeat(10) {
                val size = (low + high) / 2f
                val candidate = createTextLayout(region.translation, paint, layoutWidth, size)
                if (candidate.height <= bounds.height()) {
                    low = size
                    best = candidate
                } else {
                    high = size
                }
            }

            val x = bounds.left + horizontalPadding.toFloat()
            val y = bounds.top + (bounds.height() - best.height) / 2f
            canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(x, y)
            best.draw(canvas)
            canvas.restore()
        }
    }

    private fun createTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        textSize: Float
    ): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.92f)
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
