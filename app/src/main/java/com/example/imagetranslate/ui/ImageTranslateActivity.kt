package com.example.imagetranslate.ui

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.imagetranslate.App
import com.example.imagetranslate.databinding.ActivityImageTranslateBinding
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
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
        try {
            originalBitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
            binding.ivOriginal.setImageBitmap(originalBitmap)
            binding.ivResult.setImageBitmap(null)
            processedBitmap = null
            binding.tvStatus.text = "图片已加载"
        } catch (e: Exception) {
            Toast.makeText(this, "加载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun translateImage(bitmap: Bitmap) {
        lifecycleScope.launch {
            binding.tvStatus.text = "识别中..."
            binding.progressBar.visibility = android.view.View.VISIBLE
            binding.btnTranslate.isEnabled = false

            try {
                val scaled = scaleBitmap(bitmap, 1024)
                val texts = ocrManager.recognize(scaled)

                if (texts.isEmpty()) {
                    binding.tvStatus.text = "未识别到文字"
                    return@launch
                }

                binding.tvStatus.text = "翻译 ${texts.size} 段文字..."
                val translations = mutableMapOf<String, String>()
                for (item in texts) {
                    translations[item.text] = try {
                        translateManager.translate(item.text)
                    } catch (e: Exception) {
                        item.text
                    }
                }

                binding.tvStatus.text = "擦除原文字..."
                val usePrecise = binding.switchPreciseMask.isChecked
                val erased = if (usePrecise) {
                    inpainter.eraseWithPreciseMask(scaled, texts.map { it.bounds })
                } else {
                    inpainter.eraseWithRectMask(scaled, texts.map { it.bounds })
                }

                binding.tvStatus.text = "写入翻译..."
                val canvas = Canvas(erased)
                drawTexts(canvas, texts, translations)

                processedBitmap = erased
                binding.ivResult.setImageBitmap(processedBitmap)
                binding.tvStatus.text = "完成！"
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
        texts: List<com.example.imagetranslate.ocr.RecognizedText>,
        translations: Map<String, String>
    ) {
        val paint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        for (item in texts) {
            val translated = translations[item.text] ?: continue
            val bounds = item.bounds

            var size = 30f
            paint.textSize = size
            while (paint.measureText(translated) > bounds.width() && size > 8f) {
                size -= 1f
                paint.textSize = size
            }

            val x = bounds.centerX().toFloat()
            val y = bounds.centerY().toFloat() - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(translated, x, y, paint)
        }
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

