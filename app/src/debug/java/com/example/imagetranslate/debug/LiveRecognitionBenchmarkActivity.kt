package com.example.imagetranslate.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.screenshot.BackgroundTranslatedImageProcessor
import com.example.imagetranslate.screenshot.LiveRecognitionMetricsPolicy
import com.example.imagetranslate.screenshot.LiveRecognitionTelemetry
import com.example.imagetranslate.screenshot.LiveRecognitionSegmentation
import com.example.imagetranslate.screenshot.ScreenFrameSignature
import com.example.imagetranslate.screenshot.ScreenThemeColorEstimator
import com.example.imagetranslate.screenshot.ScrollCapturePlan
import com.example.imagetranslate.screenshot.ScrollFrameMotionEstimator
import com.example.imagetranslate.screenshot.recyclePatchBitmaps
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class LiveRecognitionBenchmarkActivity : Activity() {
    private val scope = MainScope()
    private var candidateProcessor: BackgroundTranslatedImageProcessor? = null
    private var referenceProcessor: BackgroundTranslatedImageProcessor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 13f
            text = "Live recognition A/B running..."
        }
        setContentView(ScrollView(this).apply { addView(output) })

        scope.launch {
            runCatching { runBenchmark() }
                .onSuccess { json ->
                    output.text = JSONObject(json).toString(2)
                    Log.i(LOG_TAG, json)
                }
                .onFailure { error ->
                    val json = JSONObject()
                        .put("schema", 1)
                        .put("event", "live_recognition_ab_failed")
                        .put("error", error.javaClass.simpleName)
                        .put("message", error.message)
                        .toString()
                    output.text = JSONObject(json).toString(2)
                    Log.e(LOG_TAG, json, error)
                }
        }
    }

    private suspend fun runBenchmark(): String = withTimeout(BENCHMARK_TIMEOUT_MS) {
        val currentPath = requireNotNull(intent.getStringExtra(EXTRA_CURRENT_IMAGE_PATH)) {
            "Missing $EXTRA_CURRENT_IMAGE_PATH"
        }
        val currentBitmap = requireNotNull(BitmapFactory.decodeFile(currentPath)) {
            "Unable to decode current image: $currentPath"
        }
        val baselinePath = intent.getStringExtra(EXTRA_BASELINE_IMAGE_PATH)
        val baselineBitmap = baselinePath?.let { path ->
            requireNotNull(BitmapFactory.decodeFile(path)) {
                "Unable to decode baseline image: $path"
            }
        }
        val candidateSegmentation = enumExtra(
            EXTRA_CANDIDATE_SEGMENTATION,
            LiveRecognitionSegmentation.ADAPTIVE
        )
        val recognitionMode = enumExtra(EXTRA_RECOGNITION_MODE, OcrRecognitionMode.ENGLISH)
        val translationMode = enumExtra(
            EXTRA_TRANSLATION_MODE,
            TranslationMode.ENGLISH_TO_CHINESE
        )
        val candidateRanFirst = intent.getBooleanExtra(EXTRA_CANDIDATE_FIRST, true)
        val overlayAlpha = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA
        val candidate = BackgroundTranslatedImageProcessor(applicationContext, reuseResources = true)
        val reference = BackgroundTranslatedImageProcessor(applicationContext, reuseResources = true)
        candidateProcessor = candidate
        referenceProcessor = reference
        try {
            candidate.prepareForLiveTranslation()
            reference.prepareForLiveTranslation()
            candidate.prepareOcrModels(recognitionMode)
            reference.prepareOcrModels(recognitionMode)

            val capturePlan = baselineBitmap?.let { baseline ->
                seedSnapshot(candidate, baseline, translationMode, recognitionMode, overlayAlpha)
                seedSnapshot(reference, baseline, translationMode, recognitionMode, overlayAlpha)
                BitmapSignatureSampler.estimateScroll(baseline, currentBitmap)
            }
            if (candidateSegmentation == LiveRecognitionSegmentation.ADAPTIVE) {
                requireNotNull(baselineBitmap) {
                    "Adaptive A/B requires a baseline image"
                }
                requireNotNull(capturePlan) {
                    "Unable to estimate a scroll plan from the supplied images"
                }
            }

            val candidateResult: com.example.imagetranslate.screenshot.BackgroundTranslatedOverlayResult
            val referenceResult: com.example.imagetranslate.screenshot.BackgroundTranslatedOverlayResult
            if (candidateRanFirst) {
                candidateResult = candidate.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    capturePlan = capturePlan,
                    overlayAlpha = overlayAlpha,
                    segmentation = candidateSegmentation
                )
                referenceResult = reference.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    overlayAlpha = overlayAlpha,
                    segmentation = LiveRecognitionSegmentation.FULL_FRAME
                )
            } else {
                referenceResult = reference.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    overlayAlpha = overlayAlpha,
                    segmentation = LiveRecognitionSegmentation.FULL_FRAME
                )
                candidateResult = candidate.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    capturePlan = capturePlan,
                    overlayAlpha = overlayAlpha,
                    segmentation = candidateSegmentation
                )
            }
            try {
                val coverage = LiveRecognitionMetricsPolicy.compare(
                    reference = referenceResult.translatedBounds,
                    candidate = candidateResult.translatedBounds,
                    viewportWidth = currentBitmap.width,
                    viewportHeight = currentBitmap.height
                )
                val report = LiveRecognitionMetricsPolicy.abReport(
                    reference = referenceResult.metrics(),
                    candidate = candidateResult.metrics(),
                    coverage = coverage,
                    capturePlan = capturePlan,
                    candidateRanFirst = candidateRanFirst
                )
                LiveRecognitionTelemetry.abReport(report)
            } finally {
                candidateResult.patches.recyclePatchBitmaps()
                referenceResult.patches.recyclePatchBitmaps()
            }
        } finally {
            baselineBitmap?.takeIf { !it.isRecycled }?.recycle()
            currentBitmap.takeIf { !it.isRecycled }?.recycle()
            candidate.close()
            reference.close()
            candidateProcessor = null
            referenceProcessor = null
        }
    }

    private suspend fun seedSnapshot(
        processor: BackgroundTranslatedImageProcessor,
        bitmap: Bitmap,
        translationMode: TranslationMode,
        recognitionMode: OcrRecognitionMode,
        overlayAlpha: Float
    ) {
        processor.translateForOverlay(
            bitmap = bitmap,
            mode = translationMode,
            recognitionMode = recognitionMode,
            overlayAlpha = overlayAlpha,
            segmentation = LiveRecognitionSegmentation.FULL_FRAME
        ).patches.recyclePatchBitmaps()
    }

    private inline fun <reified T : Enum<T>> enumExtra(name: String, fallback: T): T = runCatching {
        enumValueOf<T>(intent.getStringExtra(name).orEmpty())
    }.getOrDefault(fallback)

    override fun onDestroy() {
        scope.cancel()
        candidateProcessor?.close()
        referenceProcessor?.close()
        super.onDestroy()
    }

    companion object {
        const val LOG_TAG = "LIVE_OCR_AB"
        const val EXTRA_CURRENT_IMAGE_PATH = "current_image_path"
        const val EXTRA_BASELINE_IMAGE_PATH = "baseline_image_path"
        const val EXTRA_CANDIDATE_SEGMENTATION = "candidate_segmentation"
        const val EXTRA_RECOGNITION_MODE = "recognition_mode"
        const val EXTRA_TRANSLATION_MODE = "translation_mode"
        const val EXTRA_CANDIDATE_FIRST = "candidate_first"
        private const val BENCHMARK_TIMEOUT_MS = 120_000L
    }
}

private object BitmapSignatureSampler {
    fun estimateScroll(reference: Bitmap, current: Bitmap): ScrollCapturePlan? = PROFILES
        .firstNotNullOfOrNull { profile ->
            ScrollFrameMotionEstimator.estimate(
                sample(reference, profile),
                sample(current, profile),
                maximumShiftRatio = 0.68f
            )
        }

    private fun sample(bitmap: Bitmap, profile: SignatureProfile): ScreenFrameSignature {
        val top = (bitmap.height * profile.topRatio).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bitmap.height * profile.bottomRatio).toInt()
            .coerceIn(top + 1, bitmap.height)
        val left = (bitmap.width * profile.leftRatio).toInt().coerceIn(0, bitmap.width - 1)
        val right = (bitmap.width * profile.rightRatio).toInt().coerceIn(left + 1, bitmap.width)
        val samples = IntArray(profile.columns * profile.rows)
        var index = 0
        repeat(profile.rows) { row ->
            val y = top + (bottom - top - 1) * row / (profile.rows - 1).coerceAtLeast(1)
            repeat(profile.columns) { column ->
                val x = left + (right - left - 1) * column /
                    (profile.columns - 1).coerceAtLeast(1)
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                samples[index++] = (red * 54 + green * 183 + blue * 19) shr 8
            }
        }
        return ScreenFrameSignature(
            samples = samples,
            columns = profile.columns,
            rows = profile.rows,
            sampleTopPx = top,
            sampleBottomPx = bottom
        )
    }

    private data class SignatureProfile(
        val columns: Int,
        val rows: Int,
        val leftRatio: Float,
        val rightRatio: Float,
        val topRatio: Float,
        val bottomRatio: Float
    )

    private val PROFILES = listOf(
        SignatureProfile(48, 72, 0f, 1f, 0.08f, 0.94f),
        SignatureProfile(64, 96, 0.05f, 0.95f, 0.08f, 0.9f),
        SignatureProfile(64, 112, 0.1f, 0.9f, 0.08f, 0.88f)
    )
}
