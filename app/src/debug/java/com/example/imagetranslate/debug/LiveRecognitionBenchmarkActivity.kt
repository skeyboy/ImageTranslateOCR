package com.example.imagetranslate.debug

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import com.example.imagetranslate.App
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.screenshot.BackgroundTranslatedImageProcessor
import com.example.imagetranslate.screenshot.LiveCoverageBounds
import com.example.imagetranslate.screenshot.LiveContentViewportPolicy
import com.example.imagetranslate.screenshot.LiveDifferentialContextProfile
import com.example.imagetranslate.screenshot.LivePatchBackgroundComposer
import com.example.imagetranslate.screenshot.LivePatchBackgroundMode
import com.example.imagetranslate.screenshot.LivePatchRenderingMode
import com.example.imagetranslate.screenshot.LiveRecognitionExecutionProfile
import com.example.imagetranslate.screenshot.LiveRecognitionMetricsPolicy
import com.example.imagetranslate.screenshot.LiveRecognitionSegmentation
import com.example.imagetranslate.screenshot.LiveRecognitionTelemetry
import com.example.imagetranslate.screenshot.ScreenFrameSignature
import com.example.imagetranslate.screenshot.ScreenThemeColorEstimator
import com.example.imagetranslate.screenshot.ScreenTranslationPatch
import com.example.imagetranslate.screenshot.ScrollCapturePlan
import com.example.imagetranslate.screenshot.ScrollFrameMotionEstimator
import com.example.imagetranslate.screenshot.recyclePatchBitmaps
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class LiveRecognitionBenchmarkActivity : Activity() {
    private val scope = MainScope()
    private var candidateProcessor: BackgroundTranslatedImageProcessor? = null
    private var referenceProcessor: BackgroundTranslatedImageProcessor? = null
    private var displayedPreview: Bitmap? = null

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
                    if (intent.getBooleanExtra(EXTRA_VISUAL_PREVIEW, false)) {
                        showVisualPreview()
                        window.decorView.postDelayed({
                            Log.i(LOG_TAG, json)
                            finishBenchmarkTask(PREVIEW_HOLD_MS)
                        }, PREVIEW_DRAW_SETTLE_MS)
                    } else {
                        output.text = JSONObject(json).toString(2)
                        Log.i(LOG_TAG, json)
                        finishBenchmarkTask(0L)
                    }
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
                    finishBenchmarkTask(0L)
                }
        }
    }

    private fun finishBenchmarkTask(delayMs: Long) {
        window.decorView.postDelayed({
            if (!isFinishing) finishAndRemoveTask()
        }, delayMs)
    }

    private fun showVisualPreview() {
        displayedPreview = BitmapFactory.decodeFile(visualArtifactFile(CANDIDATE_PREVIEW_FILE).path)
        setContentView(ImageView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setImageBitmap(displayedPreview)
            contentDescription = "Candidate OCR translation preview"
        })
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
        val referenceSegmentation = enumExtra(
            EXTRA_REFERENCE_SEGMENTATION,
            LiveRecognitionSegmentation.FULL_FRAME
        )
        val candidateExecutionProfile = LiveRecognitionExecutionProfile(
            contextProfile = enumExtra(
                EXTRA_CANDIDATE_CONTEXT_PROFILE,
                LiveDifferentialContextProfile.BALANCED
            ),
            renderingMode = enumExtra(
                EXTRA_CANDIDATE_RENDERING_MODE,
                LivePatchRenderingMode.SEQUENTIAL
            ),
            backgroundMode = enumExtra(
                EXTRA_CANDIDATE_BACKGROUND_MODE,
                LivePatchBackgroundMode.THEME_SURFACE
            )
        )
        val referenceExecutionProfile = LiveRecognitionExecutionProfile(
            contextProfile = enumExtra(
                EXTRA_REFERENCE_CONTEXT_PROFILE,
                LiveDifferentialContextProfile.BALANCED
            ),
            renderingMode = enumExtra(
                EXTRA_REFERENCE_RENDERING_MODE,
                LivePatchRenderingMode.SEQUENTIAL
            ),
            backgroundMode = enumExtra(
                EXTRA_REFERENCE_BACKGROUND_MODE,
                LivePatchBackgroundMode.THEME_SURFACE
            )
        )
        val recognitionMode = enumExtra(EXTRA_RECOGNITION_MODE, OcrRecognitionMode.ENGLISH)
        val translationMode = enumExtra(
            EXTRA_TRANSLATION_MODE,
            TranslationMode.ENGLISH_TO_CHINESE
        )
        val candidateRanFirst = intent.getBooleanExtra(EXTRA_CANDIDATE_FIRST, true)
        val candidateSmartAssist = intent.getBooleanExtra(EXTRA_CANDIDATE_SMART_ASSIST, false)
        val referenceSmartAssist = intent.getBooleanExtra(EXTRA_REFERENCE_SMART_ASSIST, false)
        val fullPageBackground = intent.getBooleanExtra(EXTRA_FULL_PAGE_BACKGROUND, false)
        val fullPageScope = enumExtra(
            EXTRA_FULL_PAGE_BACKGROUND_SCOPE,
            FullPageBackgroundScope.FULL_SCREEN
        )
        val overlayAlpha = intent.getFloatExtra(
            EXTRA_OVERLAY_ALPHA,
            ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA
        ).coerceIn(0.01f, 1f)
        val contentViewport = if (
            fullPageBackground && fullPageScope == FullPageBackgroundScope.CONTENT_VIEWPORT
        ) {
            ScrollingContentViewportEstimator.estimate(baselineBitmap, currentBitmap)
        } else {
            ContentViewport(
                bounds = Rect(0, 0, currentBitmap.width, currentBitmap.height),
                confidence = 1f,
                usedFallback = false
            )
        }
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
            if (candidateSegmentation == LiveRecognitionSegmentation.ADAPTIVE ||
                referenceSegmentation == LiveRecognitionSegmentation.ADAPTIVE
            ) {
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
                    segmentation = candidateSegmentation,
                    executionProfile = candidateExecutionProfile,
                    drawPatchBackgrounds = !fullPageBackground,
                    smartAssistEnabled = candidateSmartAssist
                )
                referenceResult = reference.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    capturePlan = capturePlan,
                    overlayAlpha = overlayAlpha,
                    segmentation = referenceSegmentation,
                    executionProfile = referenceExecutionProfile,
                    drawPatchBackgrounds = !fullPageBackground,
                    smartAssistEnabled = referenceSmartAssist
                )
            } else {
                referenceResult = reference.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    capturePlan = capturePlan,
                    overlayAlpha = overlayAlpha,
                    segmentation = referenceSegmentation,
                    executionProfile = referenceExecutionProfile,
                    drawPatchBackgrounds = !fullPageBackground,
                    smartAssistEnabled = referenceSmartAssist
                )
                candidateResult = candidate.translateForOverlay(
                    bitmap = currentBitmap,
                    mode = translationMode,
                    recognitionMode = recognitionMode,
                    capturePlan = capturePlan,
                    overlayAlpha = overlayAlpha,
                    segmentation = candidateSegmentation,
                    executionProfile = candidateExecutionProfile,
                    drawPatchBackgrounds = !fullPageBackground,
                    smartAssistEnabled = candidateSmartAssist
                )
            }
            try {
                val candidatePresentation = if (fullPageBackground) {
                    createFullPagePresentation(
                        currentBitmap,
                        candidateResult.patches,
                        candidateExecutionProfile.backgroundMode,
                        overlayAlpha,
                        contentViewport.bounds
                    )
                } else {
                    null
                }
                val referencePresentation = if (fullPageBackground) {
                    createFullPagePresentation(
                        currentBitmap,
                        referenceResult.patches,
                        referenceExecutionProfile.backgroundMode,
                        overlayAlpha,
                        contentViewport.bounds
                    )
                } else {
                    null
                }
                val candidateDisplayPatches = candidatePresentation?.patches
                    ?: candidateResult.patches
                val referenceDisplayPatches = referencePresentation?.patches
                    ?: referenceResult.patches
                val coverage = LiveRecognitionMetricsPolicy.compare(
                    reference = referenceResult.translatedBounds,
                    candidate = candidateResult.translatedBounds,
                    viewportWidth = currentBitmap.width,
                    viewportHeight = currentBitmap.height
                )
                val candidateVisual = LiveRecognitionVisualArtifactRenderer.render(
                    currentBitmap,
                    candidateDisplayPatches,
                    overlayAlpha
                )
                val referenceVisual = LiveRecognitionVisualArtifactRenderer.render(
                    currentBitmap,
                    referenceDisplayPatches,
                    overlayAlpha
                )
                try {
                    val candidateBytes = saveVisualArtifact(
                        CANDIDATE_PREVIEW_FILE,
                        candidateVisual.bitmap
                    )
                    val referenceBytes = saveVisualArtifact(
                        REFERENCE_PREVIEW_FILE,
                        referenceVisual.bitmap
                    )
                    val report = LiveRecognitionMetricsPolicy.abReport(
                        reference = referenceResult.metrics(),
                        candidate = candidateResult.metrics(),
                        coverage = coverage,
                        patchCoverage = LiveRecognitionMetricsPolicy.compare(
                            reference = referenceResult.patches.map { patch ->
                                patch.bounds.run { LiveCoverageBounds(left, top, right, bottom) }
                            },
                            candidate = candidateResult.patches.map { patch ->
                                patch.bounds.run { LiveCoverageBounds(left, top, right, bottom) }
                            },
                            viewportWidth = currentBitmap.width,
                            viewportHeight = currentBitmap.height
                        ),
                        capturePlan = capturePlan,
                        candidateRanFirst = candidateRanFirst
                    )
                    val backgroundOverhead = if (
                        candidatePresentation != null && referencePresentation != null
                    ) {
                        (candidatePresentation.composeMs - referencePresentation.composeMs)
                            .toFloat() / referencePresentation.composeMs.coerceAtLeast(1L)
                    } else {
                        renderOverheadRatio(candidateResult, referenceResult)
                    }
                    val candidateDetail = candidatePresentation?.detailRetentionRatio
                        ?: candidateResult.backgroundDetailRetentionRatio
                    JSONObject(LiveRecognitionTelemetry.abReport(report))
                        .put(
                            "visual_render_pass",
                            candidateVisual.passesVisualGate &&
                                referenceVisual.passesVisualGate &&
                                candidateBytes > 0L && referenceBytes > 0L
                        )
                        .put("semantic_quality_evaluated", false)
                        .put("candidate_smart_assist", candidateSmartAssist)
                        .put("reference_smart_assist", referenceSmartAssist)
                        .put("full_page_background", fullPageBackground)
                        .put("full_page_background_scope", fullPageScope.name)
                        .put("overlay_alpha", overlayAlpha.toDouble())
                        .put("content_viewport", contentViewportJson(contentViewport, currentBitmap))
                        .put(
                            "candidate_full_page",
                            fullPageJson(candidatePresentation, candidateResult.patches.size)
                        )
                        .put(
                            "reference_full_page",
                            fullPageJson(referencePresentation, referenceResult.patches.size)
                        )
                        .put(
                            "background_render_overhead_ratio",
                            backgroundOverhead.toDouble()
                        )
                        .put(
                            "background_detail_gate_pass",
                            candidateDetail <= MAXIMUM_BACKGROUND_DETAIL_RETENTION_RATIO
                        )
                        .put(
                            "background_performance_gate_pass",
                            backgroundOverhead <= MAXIMUM_BACKGROUND_RENDER_OVERHEAD_RATIO
                        )
                        .put("candidate_visual", visualJson(candidateVisual, candidateBytes))
                        .put("reference_visual", visualJson(referenceVisual, referenceBytes))
                        .toString()
                } finally {
                    candidateVisual.bitmap.recycle()
                    referenceVisual.bitmap.recycle()
                    candidatePresentation?.patches?.recyclePatchBitmaps()
                    referencePresentation?.patches?.recyclePatchBitmaps()
                }
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

    private fun visualJson(
        evidence: LiveRecognitionVisualEvidence,
        artifactBytes: Long
    ): JSONObject = JSONObject()
        .put("patches", evidence.patchCount)
        .put("changed_patches", evidence.changedPatchCount)
        .put("changed_patch_ratio", evidence.changedPatchRatio.toDouble())
        .put("changed_sample_ratio", evidence.changedSampleRatio.toDouble())
        .put("changed_outside_patch_samples", evidence.changedOutsidePatchCount)
        .put("artifact_bytes", artifactBytes)
        .put("render_pass", evidence.passesVisualGate && artifactBytes > 0L)

    private fun fullPageJson(
        presentation: FullPagePresentation?,
        textPatchCount: Int
    ): JSONObject = JSONObject()
        .put("enabled", presentation != null)
        .put("text_patches", textPatchCount)
        .put("compose_ms", presentation?.composeMs ?: 0L)
        .put("detail_retention_ratio", presentation?.detailRetentionRatio?.toDouble() ?: 0.0)
        .put("pss_delta_kb", presentation?.pssDeltaKb ?: 0)
        .put("bitmap_bytes", presentation?.bitmapBytes ?: 0L)

    private fun contentViewportJson(
        viewport: ContentViewport,
        source: Bitmap
    ): JSONObject = JSONObject()
        .put("left", viewport.bounds.left)
        .put("top", viewport.bounds.top)
        .put("right", viewport.bounds.right)
        .put("bottom", viewport.bounds.bottom)
        .put(
            "area_ratio",
            viewport.bounds.width().toDouble() * viewport.bounds.height() /
                (source.width.toDouble() * source.height).coerceAtLeast(1.0)
        )
        .put("confidence", viewport.confidence.toDouble())
        .put("used_fallback", viewport.usedFallback)

    private fun createFullPagePresentation(
        source: Bitmap,
        textPatches: List<ScreenTranslationPatch>,
        backgroundMode: LivePatchBackgroundMode,
        overlayAlpha: Float,
        contentBounds: Rect
    ): FullPagePresentation {
        val startedAt = SystemClock.elapsedRealtime()
        val pssBeforeKb = Debug.getPss()
        val scopedSource = if (
            contentBounds.left == 0 && contentBounds.top == 0 &&
            contentBounds.right == source.width && contentBounds.bottom == source.height
        ) {
            source
        } else {
            Bitmap.createBitmap(
                source,
                contentBounds.left,
                contentBounds.top,
                contentBounds.width(),
                contentBounds.height()
            )
        }
        val themeSurface = ScreenThemeColorEstimator.compositableSurface(
            ScreenThemeColorEstimator.estimate(scopedSource),
            overlayAlpha
        )
        val output = Bitmap.createBitmap(
            scopedSource.width,
            scopedSource.height,
            Bitmap.Config.ARGB_8888
        )
        return try {
            var detailRetentionRatio = 0f
            if (backgroundMode == LivePatchBackgroundMode.BLUR_TINT && App.isOpenCVReady) {
                val background = LivePatchBackgroundComposer.createFullPageBlurTintTarget(
                    scopedSource,
                    themeSurface,
                    overlayAlpha
                )
                try {
                    detailRetentionRatio = background.detailRetentionRatio
                    LivePatchBackgroundComposer.drawCompensatedTarget(
                        output,
                        background.bitmap,
                        scopedSource,
                        overlayAlpha
                    )
                } finally {
                    background.bitmap.recycle()
                }
            } else {
                if (overlayAlpha >= 0.999f) {
                    output.eraseColor(themeSurface)
                } else {
                    drawCompensatedTheme(output, scopedSource, themeSurface, overlayAlpha)
                }
            }
            val canvas = Canvas(output)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            textPatches.forEach { patch ->
                val destination = Rect(patch.bounds).apply {
                    offset(-contentBounds.left, -contentBounds.top)
                }
                canvas.drawBitmap(patch.bitmap, null, destination, textPaint)
            }
            FullPagePresentation(
                patches = listOf(
                    ScreenTranslationPatch(Rect(contentBounds), output)
                ),
                composeMs = SystemClock.elapsedRealtime() - startedAt,
                detailRetentionRatio = detailRetentionRatio,
                pssDeltaKb = (Debug.getPss() - pssBeforeKb).coerceAtLeast(0),
                bitmapBytes = output.allocationByteCount.toLong()
            )
        } catch (error: Exception) {
            output.recycle()
            throw error
        } finally {
            if (scopedSource !== source) scopedSource.recycle()
        }
    }

    private fun drawCompensatedTheme(
        output: Bitmap,
        source: Bitmap,
        themeSurface: Int,
        overlayAlpha: Float
    ) {
        val alpha = overlayAlpha.coerceIn(0.01f, 1f)
        val sourceMultiplier = -(1f - alpha) / alpha
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        sourceMultiplier, 0f, 0f, 0f, Color.red(themeSurface) / alpha,
                        0f, sourceMultiplier, 0f, 0f, Color.green(themeSurface) / alpha,
                        0f, 0f, sourceMultiplier, 0f, Color.blue(themeSurface) / alpha,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
    }

    private fun renderOverheadRatio(
        candidate: com.example.imagetranslate.screenshot.BackgroundTranslatedOverlayResult,
        reference: com.example.imagetranslate.screenshot.BackgroundTranslatedOverlayResult
    ): Float = (candidate.renderingMs - reference.renderingMs).toFloat() /
        reference.renderingMs.coerceAtLeast(1L).toFloat()

    private fun saveVisualArtifact(name: String, bitmap: Bitmap): Long {
        val file = visualArtifactFile(name)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Unable to encode visual artifact: $name"
            }
        }
        return file.length()
    }

    private fun visualArtifactFile(name: String): File = File(filesDir, "benchmark/$name")

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
        displayedPreview?.takeIf { !it.isRecycled }?.recycle()
        displayedPreview = null
        super.onDestroy()
    }

    companion object {
        const val LOG_TAG = "LIVE_OCR_AB"
        const val EXTRA_CURRENT_IMAGE_PATH = "current_image_path"
        const val EXTRA_BASELINE_IMAGE_PATH = "baseline_image_path"
        const val EXTRA_CANDIDATE_SEGMENTATION = "candidate_segmentation"
        const val EXTRA_REFERENCE_SEGMENTATION = "reference_segmentation"
        const val EXTRA_CANDIDATE_CONTEXT_PROFILE = "candidate_context_profile"
        const val EXTRA_REFERENCE_CONTEXT_PROFILE = "reference_context_profile"
        const val EXTRA_CANDIDATE_RENDERING_MODE = "candidate_rendering_mode"
        const val EXTRA_REFERENCE_RENDERING_MODE = "reference_rendering_mode"
        const val EXTRA_CANDIDATE_BACKGROUND_MODE = "candidate_background_mode"
        const val EXTRA_REFERENCE_BACKGROUND_MODE = "reference_background_mode"
        const val EXTRA_RECOGNITION_MODE = "recognition_mode"
        const val EXTRA_TRANSLATION_MODE = "translation_mode"
        const val EXTRA_CANDIDATE_FIRST = "candidate_first"
        const val EXTRA_CANDIDATE_SMART_ASSIST = "candidate_smart_assist"
        const val EXTRA_REFERENCE_SMART_ASSIST = "reference_smart_assist"
        const val EXTRA_VISUAL_PREVIEW = "visual_preview"
        const val EXTRA_FULL_PAGE_BACKGROUND = "full_page_background"
        const val EXTRA_FULL_PAGE_BACKGROUND_SCOPE = "full_page_background_scope"
        const val EXTRA_OVERLAY_ALPHA = "overlay_alpha"
        const val CANDIDATE_PREVIEW_FILE = "candidate-preview.png"
        const val REFERENCE_PREVIEW_FILE = "reference-preview.png"
        private const val BENCHMARK_TIMEOUT_MS = 120_000L
        private const val PREVIEW_DRAW_SETTLE_MS = 500L
        private const val PREVIEW_HOLD_MS = 5_000L
        private const val MAXIMUM_BACKGROUND_DETAIL_RETENTION_RATIO = 0.2f
        private const val MAXIMUM_BACKGROUND_RENDER_OVERHEAD_RATIO = 0.2f
    }
}

private data class FullPagePresentation(
    val patches: List<ScreenTranslationPatch>,
    val composeMs: Long,
    val detailRetentionRatio: Float,
    val pssDeltaKb: Long,
    val bitmapBytes: Long
)

private enum class FullPageBackgroundScope {
    FULL_SCREEN,
    CONTENT_VIEWPORT
}

private data class ContentViewport(
    val bounds: Rect,
    val confidence: Float,
    val usedFallback: Boolean
)

private object ScrollingContentViewportEstimator {
    fun estimate(reference: Bitmap?, current: Bitmap): ContentViewport {
        if (reference == null || reference.width != current.width ||
            reference.height != current.height
        ) {
            return fallback(current)
        }
        val blockHeight = ROW_BLOCK_HEIGHT_PX.coerceAtMost(current.height)
        val blockCount = (current.height + blockHeight - 1) / blockHeight
        val scores = FloatArray(blockCount)
        repeat(blockCount) { block ->
            val top = block * blockHeight
            val bottom = minOf(current.height, top + blockHeight)
            var totalDifference = 0L
            var samples = 0
            var y = top + (bottom - top) / 2
            while (y < bottom) {
                var x = SAMPLE_OFFSET_PX.coerceAtMost(current.width - 1)
                while (x < current.width) {
                    totalDifference += kotlin.math.abs(
                        luminance(reference.getPixel(x, y)) -
                            luminance(current.getPixel(x, y))
                    )
                    samples++
                    x += SAMPLE_STEP_PX
                }
                y += SAMPLE_ROW_STEP_PX
            }
            scores[block] = totalDifference.toFloat() / samples.coerceAtLeast(1)
        }
        val estimate = LiveContentViewportPolicy.resolve(
            rowMotionScores = scores,
            viewportHeight = current.height,
            rowBlockHeight = blockHeight
        )
        val topSeparator = horizontalSeparator(
            bitmap = current,
            firstY = (current.height * TOP_SEPARATOR_START_RATIO).toInt(),
            lastY = (current.height * TOP_SEPARATOR_END_RATIO).toInt(),
            preferLast = true
        )
        val bottomSeparator = horizontalSeparator(
            bitmap = current,
            firstY = (current.height * BOTTOM_SEPARATOR_START_RATIO).toInt(),
            lastY = (current.height * BOTTOM_SEPARATOR_END_RATIO).toInt(),
            preferLast = false
        )
        val refinedTop = maxOf(estimate.top, topSeparator ?: 0)
        val refinedBottom = minOf(estimate.bottom, bottomSeparator ?: current.height)
        val useRefinedBounds = refinedBottom - refinedTop >=
            current.height * MINIMUM_REFINED_HEIGHT_RATIO
        return ContentViewport(
            bounds = if (useRefinedBounds) {
                Rect(0, refinedTop, current.width, refinedBottom)
            } else {
                Rect(0, estimate.top, current.width, estimate.bottom)
            },
            confidence = if (topSeparator != null && bottomSeparator != null) {
                maxOf(estimate.confidence, STRUCTURAL_BOUNDARY_CONFIDENCE)
            } else {
                estimate.confidence
            },
            usedFallback = estimate.usedFallback &&
                (topSeparator == null || bottomSeparator == null)
        )
    }

    private fun horizontalSeparator(
        bitmap: Bitmap,
        firstY: Int,
        lastY: Int,
        preferLast: Boolean
    ): Int? {
        val candidates = mutableListOf<RowSeparator>()
        val safeFirst = firstY.coerceIn(EDGE_SAMPLE_RADIUS_PX, bitmap.height - 1)
        val safeLast = lastY.coerceIn(safeFirst, bitmap.height - EDGE_SAMPLE_RADIUS_PX - 1)
        var y = safeFirst
        while (y <= safeLast) {
            var changed = 0
            var totalDifference = 0L
            var samples = 0
            var x = SAMPLE_OFFSET_PX.coerceAtMost(bitmap.width - 1)
            while (x < bitmap.width) {
                val difference = kotlin.math.abs(
                    luminance(bitmap.getPixel(x, y - EDGE_SAMPLE_RADIUS_PX)) -
                        luminance(bitmap.getPixel(x, y + EDGE_SAMPLE_RADIUS_PX))
                )
                if (difference >= MINIMUM_EDGE_DIFFERENCE) changed++
                totalDifference += difference
                samples++
                x += SAMPLE_STEP_PX
            }
            val coverage = changed.toFloat() / samples.coerceAtLeast(1)
            val meanDifference = totalDifference.toFloat() / samples.coerceAtLeast(1)
            if (coverage >= MINIMUM_SEPARATOR_COVERAGE &&
                meanDifference >= MINIMUM_SEPARATOR_MEAN_DIFFERENCE
            ) {
                candidates += RowSeparator(y, coverage * meanDifference)
            }
            y += SEPARATOR_SEARCH_STEP_PX
        }
        val bestScore = candidates.maxOfOrNull(RowSeparator::score) ?: return null
        val strong = candidates.filter { it.score >= bestScore * STRONG_SEPARATOR_SCORE_RATIO }
        return if (preferLast) {
            strong.maxOfOrNull(RowSeparator::y)
        } else {
            strong.minOfOrNull(RowSeparator::y)
        }
    }

    private fun fallback(bitmap: Bitmap): ContentViewport {
        val estimate = LiveContentViewportPolicy.resolve(
            rowMotionScores = floatArrayOf(),
            viewportHeight = bitmap.height,
            rowBlockHeight = ROW_BLOCK_HEIGHT_PX
        )
        return ContentViewport(
            bounds = Rect(0, estimate.top, bitmap.width, estimate.bottom),
            confidence = estimate.confidence,
            usedFallback = true
        )
    }

    private fun luminance(color: Int): Int {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        return (red * 54 + green * 183 + blue * 19) shr 8
    }

    private data class RowSeparator(val y: Int, val score: Float)

    private const val ROW_BLOCK_HEIGHT_PX = 16
    private const val SAMPLE_STEP_PX = 16
    private const val SAMPLE_ROW_STEP_PX = 8
    private const val SAMPLE_OFFSET_PX = 8
    private const val TOP_SEPARATOR_START_RATIO = 0.06f
    private const val TOP_SEPARATOR_END_RATIO = 0.17f
    private const val BOTTOM_SEPARATOR_START_RATIO = 0.88f
    private const val BOTTOM_SEPARATOR_END_RATIO = 0.985f
    private const val MINIMUM_REFINED_HEIGHT_RATIO = 0.45f
    private const val STRUCTURAL_BOUNDARY_CONFIDENCE = 0.8f
    private const val EDGE_SAMPLE_RADIUS_PX = 3
    private const val SEPARATOR_SEARCH_STEP_PX = 2
    private const val MINIMUM_EDGE_DIFFERENCE = 8
    private const val MINIMUM_SEPARATOR_COVERAGE = 0.55f
    private const val MINIMUM_SEPARATOR_MEAN_DIFFERENCE = 4f
    private const val STRONG_SEPARATOR_SCORE_RATIO = 0.72f
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
