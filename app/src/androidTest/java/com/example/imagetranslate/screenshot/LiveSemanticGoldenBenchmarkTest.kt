package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.smartassist.quality.QualityBounds
import com.example.smartassist.quality.SemanticGoldenSample
import com.example.smartassist.quality.SemanticQualityEvaluator
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveSemanticGoldenBenchmarkTest {
    @Test
    fun measuresRealEnglishOcrAndChineseTranslationAgainstGoldenText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ocr = OCRManager(context)
        val translator = TranslateManager()
        val samples = mutableListOf<SemanticGoldenSample>()
        try {
            ocr.ensureModels(OcrRecognitionMode.ENGLISH.requiredModels)
            translator.downloadModelIfNeeded()
            GOLDEN_CASES.forEach { golden ->
                val rendered = renderText(golden.source)
                try {
                    val recognized = ocr.recognizeFast(rendered.bitmap, OcrRecognitionMode.ENGLISH)
                    val actualOcr = recognized.joinToString(" ") { it.text.trim() }.trim()
                    val actualTranslation = if (actualOcr.isBlank()) {
                        ""
                    } else {
                        runCatching {
                            translator.translate(
                                actualOcr,
                                TranslationMode.ENGLISH_TO_CHINESE
                            )
                        }.getOrDefault("")
                    }
                    val actualBounds = recognized.map { it.bounds }.unionOrNull()?.toQualityBounds()
                    samples += SemanticGoldenSample(
                        id = golden.id,
                        expectedOcr = golden.source,
                        actualOcr = actualOcr,
                        acceptedTranslations = golden.acceptedTranslations,
                        actualTranslation = actualTranslation,
                        expectedBounds = rendered.expectedBounds.toQualityBounds(),
                        actualBounds = actualBounds
                    )
                } finally {
                    rendered.bitmap.recycle()
                }
            }
        } finally {
            ocr.close()
            translator.close()
        }

        val report = SemanticQualityEvaluator.evaluate(samples)
        val json = reportJson(report, samples)
        val output = File(context.filesDir, "benchmark/semantic-quality.json")
        output.parentFile?.mkdirs()
        output.writeText(json)
        Log.i(LOG_TAG, json)

        assertTrue("OCR CER=${report.meanCharacterErrorRate}", report.meanCharacterErrorRate <= 0.1f)
        assertTrue("OCR WER=${report.meanWordErrorRate}", report.meanWordErrorRate <= 0.2f)
        assertTrue(
            "translation similarity=${report.meanTranslationSimilarity}",
            report.meanTranslationSimilarity >= 0.55f
        )
        assertTrue(
            "bounds IoU=${report.meanBoundsIntersectionOverUnion}",
            checkNotNull(report.meanBoundsIntersectionOverUnion) >= 0.45f
        )
    }

    @Test
    fun measuresMultilineParagraphOcrCoverage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ocr = OCRManager(context)
        val rendered = renderParagraph(LONG_PARAGRAPH)
        val recognized = try {
            ocr.ensureModels(OcrRecognitionMode.ENGLISH.requiredModels)
            ocr.recognizeFast(rendered.bitmap, OcrRecognitionMode.ENGLISH)
        } finally {
            ocr.close()
        }
        rendered.bitmap.recycle()
        val actualOcr = recognized.joinToString(" ") { it.text.trim() }.trim()
        val report = SemanticQualityEvaluator.evaluate(
            listOf(
                SemanticGoldenSample(
                    id = "long-paragraph",
                    expectedOcr = LONG_PARAGRAPH,
                    actualOcr = actualOcr,
                    acceptedTranslations = listOf("not-measured"),
                    actualTranslation = "not-measured"
                )
            )
        )
        val sample = report.samples.single()
        val json = JSONObject()
            .put("schema", 1)
            .put("event", "long_paragraph_ocr_quality")
            .put("cer", sample.characterErrorRate.toDouble())
            .put("wer", sample.wordErrorRate.toDouble())
            .put("actual_ocr", actualOcr)
            .toString()
        val output = File(context.filesDir, "benchmark/long-paragraph-quality.json")
        output.parentFile?.mkdirs()
        output.writeText(json)
        Log.i(LOG_TAG, json)

        assertTrue("long paragraph CER=${sample.characterErrorRate}", sample.characterErrorRate <= 0.12f)
        assertTrue("long paragraph WER=${sample.wordErrorRate}", sample.wordErrorRate <= 0.22f)
    }

    private fun renderText(text: String): RenderedGoldenText {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE_PX
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        val measured = Rect()
        paint.getTextBounds(text, 0, text.length, measured)
        canvas.drawText(text, TEXT_LEFT_PX, TEXT_BASELINE_PX, paint)
        return RenderedGoldenText(
            bitmap,
            Rect(
                TEXT_LEFT_PX.toInt() + measured.left,
                TEXT_BASELINE_PX.toInt() + measured.top,
                TEXT_LEFT_PX.toInt() + measured.right,
                TEXT_BASELINE_PX.toInt() + measured.bottom
            )
        )
    }

    private fun renderParagraph(text: String): RenderedGoldenText {
        val bitmap = Bitmap.createBitmap(BITMAP_WIDTH, PARAGRAPH_BITMAP_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = PARAGRAPH_TEXT_SIZE_PX
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        val layout = StaticLayout.Builder.obtain(
            text,
            0,
            text.length,
            paint,
            BITMAP_WIDTH - PARAGRAPH_LEFT_PX * 2
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(8f, 1f)
            .build()
        canvas.save()
        canvas.translate(PARAGRAPH_LEFT_PX.toFloat(), PARAGRAPH_TOP_PX.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return RenderedGoldenText(
            bitmap,
            Rect(
                PARAGRAPH_LEFT_PX,
                PARAGRAPH_TOP_PX,
                PARAGRAPH_LEFT_PX + layout.width,
                PARAGRAPH_TOP_PX + layout.height
            )
        )
    }

    private fun List<Rect>.unionOrNull(): Rect? {
        val first = firstOrNull() ?: return null
        return Rect(first).also { union -> drop(1).forEach(union::union) }
    }

    private fun Rect.toQualityBounds() = QualityBounds(left, top, right, bottom)

    private fun reportJson(
        report: com.example.smartassist.quality.SemanticQualityReport,
        inputs: List<SemanticGoldenSample>
    ): String =
        JSONObject()
            .put("schema", 1)
            .put("event", "live_semantic_quality")
            .put("sample_count", report.sampleCount)
            .put("mean_cer", report.meanCharacterErrorRate.toDouble())
            .put("mean_wer", report.meanWordErrorRate.toDouble())
            .put("mean_translation_similarity", report.meanTranslationSimilarity.toDouble())
            .put("exact_translation_rate", report.exactTranslationRate.toDouble())
            .put("mean_bounds_iou", report.meanBoundsIntersectionOverUnion?.toDouble())
            .put("samples", JSONArray(report.samples.map { sample ->
                JSONObject()
                    .put("id", sample.id)
                    .put("cer", sample.characterErrorRate.toDouble())
                    .put("wer", sample.wordErrorRate.toDouble())
                    .put("translation_similarity", sample.translationSimilarity.toDouble())
                    .put("bounds_iou", sample.boundsIntersectionOverUnion?.toDouble())
                    .put("actual_ocr", inputs.first { it.id == sample.id }.actualOcr)
                    .put(
                        "actual_translation",
                        inputs.first { it.id == sample.id }.actualTranslation
                    )
            }))
            .toString()

    private data class RenderedGoldenText(val bitmap: Bitmap, val expectedBounds: Rect)

    private data class GoldenCase(
        val id: String,
        val source: String,
        val acceptedTranslations: List<String>
    )

    private companion object {
        const val LOG_TAG = "SEMANTIC_QUALITY"
        const val BITMAP_WIDTH = 1080
        const val BITMAP_HEIGHT = 280
        const val TEXT_SIZE_PX = 64f
        const val TEXT_LEFT_PX = 56f
        const val TEXT_BASELINE_PX = 166f
        const val PARAGRAPH_BITMAP_HEIGHT = 720
        const val PARAGRAPH_TEXT_SIZE_PX = 52f
        const val PARAGRAPH_LEFT_PX = 56
        const val PARAGRAPH_TOP_PX = 48
        const val LONG_PARAGRAPH = "Rust helps developers build reliable and efficient software. Its compiler catches many mistakes before programs run, which makes large systems easier to maintain."
        val GOLDEN_CASES = listOf(
            GoldenCase("wikipedia", "Welcome to Wikipedia", listOf("欢迎来到维基百科", "欢迎访问维基百科")),
            GoldenCase("save", "Save changes", listOf("保存更改", "保存修改")),
            GoldenCase("privacy", "Privacy and security", listOf("隐私和安全", "隐私与安全")),
            GoldenCase("open-source", "Open source software", listOf("开源软件", "开放源代码软件")),
            GoldenCase("scroll", "Scroll and translate", listOf("滚动并翻译", "滚动和翻译"))
        )
    }
}
