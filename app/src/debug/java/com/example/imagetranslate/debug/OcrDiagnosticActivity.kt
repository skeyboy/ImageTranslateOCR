package com.example.imagetranslate.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.imagetranslate.App
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OcrDiagnosticActivity : Activity() {
    private val scope = MainScope()
    private val ocrManager by lazy { OCRManager(applicationContext) }
    private val translateManager = TranslateManager()
    private val inpainter = ImageInpainter()

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
                val runPipeline = intent.getBooleanExtra(EXTRA_RUN_PIPELINE, false)
                val translations = if (runPipeline) {
                    translateManager.downloadModelIfNeeded()
                    results.map { item ->
                        runCatching {
                            translateManager.translate(
                                item.text,
                                TranslationMode.CHINESE_TO_ENGLISH
                            )
                        }.getOrElse { item.text }
                    }
                } else {
                    emptyList()
                }
                val translatedBounds = results.mapIndexedNotNull { index, item ->
                    item.bounds.takeIf {
                        runPipeline && translations[index].trim() != item.text.trim()
                    }
                }
                val maskAnalyses = if (runPipeline && App.isOpenCVReady) {
                    val inpaintResult = inpainter.eraseWithPreciseMask(
                        ocrBitmap,
                        translatedBounds
                    )
                    inpaintResult.bitmap.recycle()
                    inpaintResult.maskAnalyses.associateBy { it.bounds }
                } else {
                    emptyMap()
                }
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
                        if (runPipeline) {
                            append('\t')
                            append("translation=${translations[index].replace('\n', ' ')}")
                            append('\t')
                            val maskAnalysis = maskAnalyses[item.bounds]
                            append("erased=${maskAnalysis?.accepted == true}")
                            if (maskAnalysis != null) {
                                append('\t')
                                append("darkRatio=${"%.3f".format(maskAnalysis.darkRatio)}")
                                append('\t')
                                append("lightRatio=${"%.3f".format(maskAnalysis.lightRatio)}")
                            }
                        }
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
        translateManager.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_MAXIMUM_SCALE = "maximum_scale"
        const val EXTRA_RUN_PIPELINE = "run_pipeline"
        const val LOG_TAG = "OCR_DIAGNOSTIC"
    }
}
