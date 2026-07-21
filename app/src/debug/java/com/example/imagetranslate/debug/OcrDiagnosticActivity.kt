package com.example.imagetranslate.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.imagetranslate.ocr.OCRManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OcrDiagnosticActivity : Activity() {
    private val scope = MainScope()
    private val ocrManager = OCRManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 14f
            text = "OCR diagnostic running..."
        }
        setContentView(ScrollView(this).apply { addView(output) })

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        val bitmap = imagePath?.let(BitmapFactory::decodeFile)
        if (bitmap == null) {
            output.text = "Unable to read diagnostic image: $imagePath"
            return
        }

        scope.launch {
            val maximumScale = intent.getFloatExtra(EXTRA_MAXIMUM_SCALE, 3f)
            val ocrBitmap = createOcrBitmap(bitmap, maximumScale)
            try {
                val results = ocrManager.recognize(ocrBitmap)
                val report = buildString {
                    appendLine(
                        "source=${bitmap.width}x${bitmap.height} " +
                            "ocr=${ocrBitmap.width}x${ocrBitmap.height}"
                    )
                    appendLine("count=${results.size}")
                    results.forEachIndexed { index, item ->
                        append(index)
                        append('\t')
                        append(item.bounds.flattenToString())
                        append('\t')
                        append("consensus=${"%.2f".format(item.consensusScore)}")
                        append('\t')
                        append("passes=${item.passCount}")
                        append('\t')
                        append("confidence=${"%.2f".format(item.modelConfidence)}")
                        append('\t')
                        append("script=${item.recognizerScript}")
                        append('\t')
                        appendLine(item.text.replace('\n', ' '))
                    }
                }
                output.text = report
                report.lineSequence().forEach { Log.i(LOG_TAG, it) }
            } catch (error: Exception) {
                output.text = "OCR failed: ${error.message}"
                Log.e(LOG_TAG, "OCR failed", error)
            } finally {
                if (ocrBitmap !== bitmap) ocrBitmap.recycle()
                bitmap.recycle()
            }
        }
    }

    private fun createOcrBitmap(bitmap: Bitmap, maximumScale: Float): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide >= 1600) return bitmap
        val scale = minOf(maximumScale, 3072f / longestSide)
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    override fun onDestroy() {
        scope.cancel()
        ocrManager.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_MAXIMUM_SCALE = "maximum_scale"
        const val LOG_TAG = "OCR_DIAGNOSTIC"
    }
}
