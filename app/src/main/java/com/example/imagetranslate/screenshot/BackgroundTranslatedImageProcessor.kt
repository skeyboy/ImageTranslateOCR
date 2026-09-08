package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.example.imagetranslate.App
import com.example.imagetranslate.BuildConfig
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.OcrModel
import com.example.imagetranslate.ocr.OcrModelState
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import com.example.imagetranslate.semantic.SemanticTextGrouper
import com.example.imagetranslate.semantic.SemanticTextGroup
import com.example.imagetranslate.semantic.SemanticRenderShape
import com.example.imagetranslate.semantic.StaticImageTextFilter
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.SemanticDebugCaptureEncoder
import com.example.imagetranslate.translate.SemanticDebugCaptureUploadPolicy
import com.example.imagetranslate.translate.SemanticTranslationTrace
import com.example.imagetranslate.translate.TranslationBackend
import com.example.imagetranslate.translate.TranslationBackendSettings
import com.example.imagetranslate.translate.TranslationMode
import com.example.imagetranslate.translate.toSemanticTranslationSource
import com.example.imagetranslate.ui.ShapeAwareTextLayout
import com.example.imagetranslate.ui.ShapeAwareTextOutcome
import com.example.imagetranslate.ui.ShapeAwareTextResult
import com.example.imagetranslate.ui.ShapeAwareTextSegment
import com.example.imagetranslate.ui.StaticImageTextLayoutPolicy
import com.example.experimentaltranslation.ExperimentalTranslationEngine
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistTrackRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

internal data class BackgroundTranslatedImageResult(
    val bitmap: Bitmap,
    val recognizedCount: Int,
    val replacedCount: Int,
    val failedCount: Int,
    val renderedRegions: List<Rect> = emptyList()
)

internal object PostTranslationSmartAssistPolicy {
    fun shouldApply(
        enabled: Boolean,
        usesIntegratedNetworkEngine: Boolean,
        backend: TranslationBackend
    ): Boolean = enabled && !usesIntegratedNetworkEngine &&
        !backend.isSemanticV4
}

internal data class BackgroundTranslatedOverlayResult(
    val patches: List<ScreenTranslationPatch>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val recognizedCount: Int,
    val rawRecognizedCount: Int = recognizedCount,
    val edgeFilteredCount: Int = 0,
    val edgeRecoveredCount: Int = 0,
    val continuationCount: Int = 0,
    val translatedRegionCount: Int,
    val failedCount: Int,
    val requestedSegmentation: LiveRecognitionSegmentation,
    val appliedStrategy: LiveRecognitionAppliedStrategy,
    val translatedBounds: List<LiveCoverageBounds>,
    val sourceCoverage: LiveCoverageMetrics,
    val patchCoverage: LiveCoverageMetrics,
    val differentialApplied: Boolean = false,
    val reusedRegionCount: Int = 0,
    val recognitionRegionCount: Int = 0,
    val recognitionAreaRatio: Float = 1f,
    val dirtyCellCount: Int = 0,
    val boundaryTrackCount: Int = 0,
    val restoredBoundaryTrackCount: Int = 0,
    val contextProfile: LiveDifferentialContextProfile =
        LiveDifferentialContextProfile.ACCURACY,
    val renderingMode: LivePatchRenderingMode = LivePatchRenderingMode.SEQUENTIAL,
    val backgroundMode: LivePatchBackgroundMode = LivePatchBackgroundMode.THEME_SURFACE,
    val backgroundDetailRetentionRatio: Float = 0f,
    val differentialFallbackReason: String? = null,
    val ocrMs: Long = 0L,
    val translationMs: Long = 0L,
    val recognitionAndTranslationMs: Long = 0L,
    val renderingMs: Long = 0L,
    val smartAssistApplied: Boolean = false,
    val smartAssistScene: String? = null,
    val smartAssistGroupCount: Int = 0,
    val smartAssistProtectedCount: Int = 0,
    val smartAssistLayoutHintCount: Int = 0,
    val smartAssistMs: Long = 0L,
    val renderedTrackCacheHitCount: Int = 0,
    val renderedTrackCacheMissCount: Int = 0,
    val themeSurfacePatchCount: Int = 0,
    val blurTintPatchCount: Int = 0,
    val sourceLatinTokenCount: Int = 0,
    val retainedLatinTokenCount: Int = 0,
    val retainedLatinRatio: Float = 0f,
    val suspiciousJoinCount: Int = 0,
    val largestPatchAreaRatio: Float = 0f,
    val translationFailedCount: Int = 0,
    val renderFailedCount: Int = 0,
    val renderFailures: List<LiveRenderFailureDiagnostic> = emptyList(),
    val translationTraces: List<SemanticTranslationTrace> = emptyList()
) {
    fun metrics(): LiveRecognitionRunMetrics = LiveRecognitionRunMetrics(
        requestedSegmentation = requestedSegmentation,
        appliedStrategy = appliedStrategy,
        recognizedCount = recognizedCount,
        rawRecognizedCount = rawRecognizedCount,
        edgeFilteredCount = edgeFilteredCount,
        edgeRecoveredCount = edgeRecoveredCount,
        continuationCount = continuationCount,
        translatedRegionCount = translatedRegionCount,
        patchCount = patches.size,
        failedCount = failedCount,
        translationFailedCount = translationFailedCount,
        renderFailedCount = renderFailedCount,
        reusedRegionCount = reusedRegionCount,
        recognitionRegionCount = recognitionRegionCount,
        recognitionAreaRatio = recognitionAreaRatio,
        dirtyCellCount = dirtyCellCount,
        boundaryTrackCount = boundaryTrackCount,
        restoredBoundaryTrackCount = restoredBoundaryTrackCount,
        differentialFallbackReason = differentialFallbackReason,
        contextProfile = contextProfile,
        renderingMode = renderingMode,
        backgroundMode = backgroundMode,
        backgroundDetailRetentionRatio = backgroundDetailRetentionRatio,
        sourceCoverage = sourceCoverage,
        patchCoverage = patchCoverage,
        recognitionAndTranslationMs = recognitionAndTranslationMs,
        renderingMs = renderingMs,
        ocrMs = ocrMs,
        translationMs = translationMs,
        renderedTrackCacheHitCount = renderedTrackCacheHitCount,
        renderedTrackCacheMissCount = renderedTrackCacheMissCount,
        themeSurfacePatchCount = themeSurfacePatchCount,
        blurTintPatchCount = blurTintPatchCount,
        sourceLatinTokenCount = sourceLatinTokenCount,
        retainedLatinTokenCount = retainedLatinTokenCount,
        retainedLatinRatio = retainedLatinRatio,
        suspiciousJoinCount = suspiciousJoinCount,
        largestPatchAreaRatio = largestPatchAreaRatio
    )
}

internal data class BackgroundImageRegion(
    val source: RecognizedText,
    val translation: String,
    val groupId: String? = null,
    val trackId: Long? = null,
    val smartAssistDisplayHints: SmartAssistDisplayHints? = null,
    val renderSlots: List<Rect> = emptyList(),
    val sourceCoverSlots: List<Rect> = emptyList(),
    val safeHorizontalExpansionSlot: Rect? = null
)

internal fun BackgroundImageRegion.shiftedToMatchedBounds(
    matchedBounds: Rect
): BackgroundImageRegion {
    val offsetX = matchedBounds.left - source.bounds.left
    val offsetY = matchedBounds.top - source.bounds.top
    fun shifted(bounds: Rect): Rect = Rect(
        bounds.left + offsetX,
        bounds.top + offsetY,
        bounds.right + offsetX,
        bounds.bottom + offsetY
    )

    return copy(
        source = source.copy(
            bounds = Rect(
                matchedBounds.left,
                matchedBounds.top,
                matchedBounds.right,
                matchedBounds.bottom
            ),
            componentBounds = source.componentBounds.map(::shifted)
        ),
        renderSlots = renderSlots.map(::shifted),
        sourceCoverSlots = sourceCoverSlots.map(::shifted),
        safeHorizontalExpansionSlot = safeHorizontalExpansionSlot?.let(::shifted)
    )
}

internal data class SmartAssistDisplayHints(
    val preferredMaxLines: Int,
    val minimumTextScale: Float,
    val maximumTextScale: Float = 1f,
    val lineSpacingMultiplier: Float = 1f,
    val alignment: String = "START",
    val allowMore: Boolean = false,
    val sourceLineCount: Int = 1,
    val layoutShape: String = "RECT",
    val role: String? = null,
    val verticalAlignment: String = "AUTO"
)

private data class BackgroundTranslationBatch(
    val recognizedCount: Int,
    val rawRecognizedCount: Int = recognizedCount,
    val edgeFilteredCount: Int = 0,
    val edgeRecoveredCount: Int = 0,
    val continuationCount: Int = 0,
    val regions: List<BackgroundImageRegion>,
    val failedCount: Int,
    val ocrMs: Long = 0L,
    val translationMs: Long = 0L,
    val smartAssistApplied: Boolean = false,
    val smartAssistScene: String? = null,
    val smartAssistGroupCount: Int = 0,
    val smartAssistProtectedCount: Int = 0,
    val smartAssistMs: Long = 0L,
    val translationTraces: List<SemanticTranslationTrace> = emptyList()
)

private data class SemanticGroupingResult(
    val groups: List<SemanticTextGroup>,
    val edgeFilteredCount: Int = 0,
    val continuationCount: Int = 0
)

private data class SmartAssistApplication(
    val regions: List<BackgroundImageRegion>,
    val applied: Boolean = false,
    val scene: String? = null,
    val groupCount: Int = 0,
    val protectedCount: Int = 0,
    val layoutHintCount: Int = 0
)

private data class ContextualTranslationSources(
    val sources: List<RecognizedText>,
    val applied: Boolean = false,
    val scene: String? = null,
    val groupCount: Int = 0,
    val protectedCount: Int = 0
)

private data class CachedLiveRegion(
    val region: BackgroundImageRegion,
    val fingerprint: IntArray
)

private data class LiveOverlaySnapshot(
    val width: Int,
    val height: Int,
    val mode: TranslationMode,
    val recognitionMode: OcrRecognitionMode,
    val regions: List<CachedLiveRegion>,
    val luminanceGrid: LiveLuminanceGrid
)

private data class DifferentialTranslationBatch(
    val batch: BackgroundTranslationBatch,
    val reusedRegionCount: Int,
    val recognitionRegionCount: Int,
    val recognitionAreaRatio: Float,
    val dirtyCellCount: Int,
    val boundaryTrackCount: Int,
    val restoredBoundaryTrackCount: Int
)

private data class TranslationOutcome(
    val region: BackgroundImageRegion? = null,
    val failed: Boolean = false,
    val semanticTrace: SemanticTranslationTrace? = null
)

private data class RenderedOverlayPatch(
    val patch: ScreenTranslationPatch,
    val backgroundDetailRetentionRatio: Float,
    val cacheHit: Boolean,
    val backgroundMode: LivePatchBackgroundMode
)

internal data class LiveDeterministicTranslationRegion(
    val sourceText: String,
    val translation: String,
    val bounds: Rect,
    val sourceLineBounds: List<Rect> = emptyList(),
    val sourceTextHeightsPx: List<Float> = emptyList(),
    val renderSlots: List<Rect> = emptyList(),
    val sourceCoverSlots: List<Rect> = emptyList(),
    val displayHints: SmartAssistDisplayHints? = null,
    val groupId: String? = null
)

internal data class LiveRenderedTextEvidence(
    val bounds: Rect,
    val textSizePx: Float,
    val sourceLineHeightPx: Float,
    val textScale: Float,
    val lineSpacingMultiplier: Float,
    val lineCount: Int,
    val usedRenderSlotCount: Int,
    val layoutHeightPx: Int,
    val availableHeightPx: Int,
    val clipped: Boolean,
    val contrastRatio: Float
)

internal data class LiveRenderFailureDiagnostic(
    val groupId: String?,
    val reason: String,
    val layoutShape: String?,
    val renderSlots: List<Rect>,
    val sourceCoverSlots: List<Rect>,
    val sourceLineHeightPx: Float,
    val preferredTextSizePx: Float,
    val minimumAttemptedTextSizePx: Float,
    val lastAttemptedTextSizePx: Float,
    val maximumLines: Int,
    val requireAllSlots: Boolean,
    val allowMore: Boolean,
    val attemptedLineSpacingMultipliers: List<Float>
)

internal data class LiveDeterministicOverlayResult(
    val patches: List<ScreenTranslationPatch>,
    val renderedText: List<LiveRenderedTextEvidence>,
    val expectedRegionCount: Int,
    val renderedRegionCount: Int,
    val failedRegionCount: Int,
    val renderFailures: List<LiveRenderFailureDiagnostic>,
    val renderingMs: Long
)

internal data class RenderedTrackCacheKey(
    val sourceText: String,
    val translation: String,
    val width: Int,
    val height: Int,
    val backgroundMode: LivePatchBackgroundMode,
    val surfaceColor: Int,
    val overlayAlphaPercent: Int,
    val drawBackground: Boolean,
    val displayHints: SmartAssistDisplayHints?,
    val renderSlotSignature: List<Int> = emptyList(),
    val sourceCoverSlotSignature: List<Int> = emptyList()
)

private data class CachedRenderedTrack(
    val key: RenderedTrackCacheKey,
    val materialFingerprint: IntArray,
    val bitmap: Bitmap,
    val backgroundDetailRetentionRatio: Float,
    val backgroundMode: LivePatchBackgroundMode
)

internal object LiveRenderedTrackReusePolicy {
    fun hasMatchingVisualFingerprint(
        cached: IntArray,
        current: IntArray,
        maximumMeanError: Float = MAXIMUM_MEAN_ERROR
    ): Boolean {
        if (cached.size != current.size || cached.isEmpty()) return false
        val error = cached.indices.sumOf { index ->
            kotlin.math.abs(cached[index] - current[index])
        }.toFloat() / cached.size
        return error <= maximumMeanError
    }

    private const val MAXIMUM_MEAN_ERROR = 3f
}

private data class BackgroundTextStyle(
    val foregroundColor: Int,
    val isDarkBackground: Boolean,
    val typeface: Typeface,
    val fontSizeMultiplier: Float,
    val lineSpacingMultiplier: Float,
    val sourceLineCount: Int,
    val sourceGlyphHeightPx: Float
)

internal data class SourceTextStyleHint(
    val glyphHeightPx: Float,
    val lineHeightPx: Float,
    val strokeWidthPx: Float,
    val weight: Int,
    val familyClass: String,
    val letterSpacingPx: Float,
    val alignment: String,
    val foregroundColor: Int,
    val backgroundColor: Int,
    val confidence: Float
)

internal class BackgroundTranslatedImageProcessor(
    context: Context,
    private val reuseResources: Boolean = false
) {
    private val appContext = context.applicationContext
    private val ocrManagerDelegate = lazy { OCRManager(appContext) }
    private val ocrManager by ocrManagerDelegate
    private val translateManagerDelegate = lazy { TranslateManager(appContext) }
    private val translateManager by translateManagerDelegate
    private val smartAssistAdapterDelegate = lazy(::LiveSmartAssistAdapter)
    private val translationCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?
        ): Boolean = size > MAX_TRANSLATION_CACHE_ENTRIES
    }
    private val renderedTrackCache = object : LinkedHashMap<Long, CachedRenderedTrack>(
        32,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, CachedRenderedTrack>?
        ): Boolean {
            val remove = size > MAX_RENDERED_TRACK_CACHE_ENTRIES
            if (remove) eldest?.value?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            return remove
        }
    }
    private var closed = false
    private var liveOverlaySnapshot: LiveOverlaySnapshot? = null
    private var nextTrackId = 1L
    private var lastDifferentialFallbackReason: String? = null
    private var liveOcrObscuredBounds: List<Rect> = emptyList()
    private val liveNetworkSessionId = UUID.randomUUID().toString()
    private val integratedEngineRegistry = LiveOcrTranslationEngineRegistry(appContext)
    @Volatile
    private var experimentalTranslationEngine = ExperimentalTranslationEngine.DISABLED

    fun setExperimentalTranslationEngine(engine: ExperimentalTranslationEngine) {
        if (experimentalTranslationEngine == engine) return
        experimentalTranslationEngine = engine
        synchronized(translationCache) { translationCache.clear() }
        clearLiveOverlaySnapshot()
    }

    suspend fun prepareForLiveTranslation(
        engineType: LiveOcrTranslationEngineType = LiveOcrTranslationEngineType.LOCAL_PIPELINE
    ) {
        check(!closed) { "Image processor is closed" }
        if (engineType == LiveOcrTranslationEngineType.LOCAL_PIPELINE) {
            translateManager.downloadModelIfNeeded()
        }
    }

    suspend fun prepareOcrModels(
        recognitionMode: OcrRecognitionMode,
        onState: (OcrModel, OcrModelState) -> Unit = { _, _ -> }
    ) {
        check(!closed) { "Image processor is closed" }
        ocrManager.ensureModels(recognitionMode.startupModels, onState)
    }

    suspend fun ocrModelStates(): Map<OcrModel, OcrModelState> {
        check(!closed) { "Image processor is closed" }
        return ocrManager.modelStates()
    }

    suspend fun downloadOcrModel(
        model: OcrModel,
        onState: (OcrModel, OcrModelState) -> Unit = { _, _ -> }
    ) {
        check(!closed) { "Image processor is closed" }
        ocrManager.downloadModel(model, onState)
    }

    fun clearLiveOverlaySnapshot() {
        liveOverlaySnapshot = null
        clearRenderedTrackCache()
    }

    suspend fun translate(
        bitmap: Bitmap,
        mode: TranslationMode = TranslationMode.AUTO_BIDIRECTIONAL,
        fastOcr: Boolean = false,
        allowEmpty: Boolean = false,
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO
    ): BackgroundTranslatedImageResult {
        check(!closed) { "Image processor is closed" }
        return try {
            val batch = recognizeAndTranslate(bitmap, mode, fastOcr, recognitionMode)
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

            val requestedEraseBounds = batch.regions
                .flatMap { region -> region.source.textEraseBounds() }
                .distinct()
            val inpaintResult = ImageInpainter().eraseWithPreciseMask(bitmap, requestedEraseBounds)
            val output = inpaintResult.bitmap
            val erasedBounds = inpaintResult.erasedRegions.toSet()
            try {
                val renderedRegions = BackgroundTranslatedImageRenderer.render(
                    output,
                    batch.regions.filter { region ->
                        region.source.textEraseBounds().all(erasedBounds::contains)
                    },
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
        mode: TranslationMode,
        generation: Int = 0,
        engineType: LiveOcrTranslationEngineType =
            LiveOcrTranslationEngineType.LOCAL_PIPELINE,
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO,
        capturePlan: ScrollCapturePlan? = null,
        overlayAlpha: Float = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA,
        segmentation: LiveRecognitionSegmentation = LiveRecognitionSegmentation.ADAPTIVE,
        executionProfile: LiveRecognitionExecutionProfile = LiveRecognitionExecutionProfile.CURRENT,
        drawPatchBackgrounds: Boolean = true,
        smartAssistEnabled: Boolean = false,
        obscuredBoundsAtCapture: List<Rect> = emptyList(),
        onRecognitionSucceeded: (Int) -> Unit = {}
    ): BackgroundTranslatedOverlayResult {
        check(!closed) { "Image processor is closed" }
        return try {
            liveOcrObscuredBounds = obscuredBoundsAtCapture.map(::Rect)
            lastDifferentialFallbackReason = null
            val recognitionStartedAt = SystemClock.elapsedRealtime()
            val usesIntegratedNetworkEngine =
                engineType == LiveOcrTranslationEngineType.PADDLE_NETWORK
            val differential = if (!usesIntegratedNetworkEngine &&
                segmentation == LiveRecognitionSegmentation.ADAPTIVE
            ) {
                capturePlan?.let { plan ->
                    recognizeDifferentialViewport(
                        bitmap,
                        mode,
                        recognitionMode,
                        plan,
                        executionProfile.contextProfile,
                        smartAssistEnabled,
                        onRecognitionSucceeded
                    )
                }
            } else {
                null
            }
            val batch = if (usesIntegratedNetworkEngine) {
                recognizeAndTranslateWithIntegratedEngine(bitmap, mode, generation, engineType)
                    .also { onRecognitionSucceeded(it.recognizedCount) }
            } else {
                differential?.batch ?: when (segmentation) {
                    LiveRecognitionSegmentation.VERTICAL_BANDS ->
                        recognizeVerticalBandsAndTranslate(
                            bitmap,
                            mode,
                            recognitionMode,
                            smartAssistEnabled,
                            onRecognitionSucceeded
                        )
                    LiveRecognitionSegmentation.ADAPTIVE,
                    LiveRecognitionSegmentation.FULL_FRAME ->
                        recognizeAndTranslate(
                            bitmap,
                            mode,
                            fastOcr = true,
                            recognitionMode = recognitionMode,
                            smartAssistEnabled = smartAssistEnabled,
                            onRecognitionSucceeded = onRecognitionSucceeded
                        )
                }
            }
            val recognitionAndTranslationMs = SystemClock.elapsedRealtime() - recognitionStartedAt
            val smartAssistStartedAt = SystemClock.elapsedRealtime()
            val shouldApplyPostTranslationSmartAssist =
                PostTranslationSmartAssistPolicy.shouldApply(
                    enabled = smartAssistEnabled,
                    usesIntegratedNetworkEngine = usesIntegratedNetworkEngine,
                    backend = TranslationBackendSettings.get(appContext)
                )
            val smartAssistOutcome = if (shouldApplyPostTranslationSmartAssist) {
                applySmartAssist(bitmap.width, bitmap.height, batch.regions)
            } else {
                SmartAssistApplication(batch.regions)
            }
            val smartAssistMs = if (shouldApplyPostTranslationSmartAssist) {
                SystemClock.elapsedRealtime() - smartAssistStartedAt
            } else {
                0L
            }
            val displayRegions = ensureTrackIds(smartAssistOutcome.regions)
            val renderingStartedAt = SystemClock.elapsedRealtime()
            val fallbackSurface = ScreenThemeColorEstimator.compositableSurface(
                ScreenThemeColorEstimator.estimate(bitmap),
                overlayAlpha
            )
            val renderFailures = java.util.Collections.synchronizedList(
                mutableListOf<LiveRenderFailureDiagnostic>()
            )
            val renderedPatches = renderOverlayPatches(
                bitmap = bitmap,
                regions = displayRegions,
                fallbackSurface = fallbackSurface,
                overlayAlpha = overlayAlpha,
                renderingMode = executionProfile.renderingMode,
                backgroundMode = executionProfile.backgroundMode,
                drawPatchBackgrounds = drawPatchBackgrounds,
                failureSink = renderFailures::add
            )
            val patches = mergeOverlappingPatches(renderedPatches.map(RenderedOverlayPatch::patch))
            val renderedArea = renderedPatches.sumOf { rendered ->
                rendered.patch.bounds.width().toLong() * rendered.patch.bounds.height()
            }.coerceAtLeast(1L)
            val backgroundDetailRetentionRatio = renderedPatches.sumOf { rendered ->
                val area = rendered.patch.bounds.width().toLong() * rendered.patch.bounds.height()
                rendered.backgroundDetailRetentionRatio.toDouble() * area
            }.toFloat() / renderedArea.toFloat()
            val translatedBounds = displayRegions.map { region ->
                region.source.bounds.toCoverageBounds()
            }
            val textQuality = LiveTextQualityPolicy.measure(
                displayRegions.map { region -> region.source.text to region.translation }
            )
            val viewportArea = bitmap.width.toLong() * bitmap.height.toLong()
            val largestPatchAreaRatio = if (viewportArea <= 0L) {
                0f
            } else {
                patches.maxOfOrNull { patch ->
                    patch.bounds.width().toLong() * patch.bounds.height().toLong()
                }?.toFloat()?.div(viewportArea) ?: 0f
            }
            val translationFailedCount = batch.failedCount
            val renderFailedCount = (displayRegions.size - renderedPatches.size).coerceAtLeast(0)
            BackgroundTranslatedOverlayResult(
                patches = patches,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                recognizedCount = batch.recognizedCount,
                rawRecognizedCount = batch.rawRecognizedCount,
                edgeFilteredCount = batch.edgeFilteredCount,
                edgeRecoveredCount = batch.edgeRecoveredCount,
                continuationCount = batch.continuationCount,
                translatedRegionCount = displayRegions.size,
                failedCount = translationFailedCount + renderFailedCount,
                translationFailedCount = translationFailedCount,
                renderFailedCount = renderFailedCount,
                requestedSegmentation = segmentation,
                appliedStrategy = when {
                    usesIntegratedNetworkEngine -> LiveRecognitionAppliedStrategy.FULL_FRAME
                    differential != null -> LiveRecognitionAppliedStrategy.DIFFERENTIAL
                    segmentation == LiveRecognitionSegmentation.VERTICAL_BANDS ->
                        LiveRecognitionAppliedStrategy.VERTICAL_BANDS
                    else -> LiveRecognitionAppliedStrategy.FULL_FRAME
                },
                translatedBounds = translatedBounds,
                sourceCoverage = LiveRecognitionMetricsPolicy.measure(
                    translatedBounds,
                    bitmap.width,
                    bitmap.height
                ),
                patchCoverage = LiveRecognitionMetricsPolicy.measure(
                    patches.map { it.bounds.toCoverageBounds() },
                    bitmap.width,
                    bitmap.height
                ),
                differentialApplied = differential != null,
                reusedRegionCount = differential?.reusedRegionCount ?: 0,
                recognitionRegionCount = differential?.recognitionRegionCount ?: 1,
                recognitionAreaRatio = differential?.recognitionAreaRatio ?: 1f,
                dirtyCellCount = differential?.dirtyCellCount ?: 0,
                boundaryTrackCount = differential?.boundaryTrackCount ?: 0,
                restoredBoundaryTrackCount = differential?.restoredBoundaryTrackCount ?: 0,
                contextProfile = executionProfile.contextProfile,
                renderingMode = executionProfile.renderingMode,
                backgroundMode = executionProfile.backgroundMode,
                backgroundDetailRetentionRatio = backgroundDetailRetentionRatio,
                differentialFallbackReason = if (
                    !usesIntegratedNetworkEngine &&
                    segmentation == LiveRecognitionSegmentation.ADAPTIVE &&
                    capturePlan != null && differential == null
                ) {
                    lastDifferentialFallbackReason ?: "UNKNOWN"
                } else {
                    null
                },
                recognitionAndTranslationMs = recognitionAndTranslationMs,
                ocrMs = batch.ocrMs,
                translationMs = batch.translationMs,
                renderingMs = SystemClock.elapsedRealtime() - renderingStartedAt,
                smartAssistApplied = batch.smartAssistApplied || smartAssistOutcome.applied,
                smartAssistScene = smartAssistOutcome.scene ?: batch.smartAssistScene,
                smartAssistGroupCount = maxOf(
                    batch.smartAssistGroupCount,
                    smartAssistOutcome.groupCount
                ),
                smartAssistProtectedCount = batch.smartAssistProtectedCount +
                    smartAssistOutcome.protectedCount,
                smartAssistLayoutHintCount = smartAssistOutcome.layoutHintCount,
                smartAssistMs = batch.smartAssistMs + smartAssistMs,
                renderedTrackCacheHitCount = renderedPatches.count(RenderedOverlayPatch::cacheHit),
                renderedTrackCacheMissCount = renderedPatches.count { !it.cacheHit },
                themeSurfacePatchCount = renderedPatches.count {
                    it.backgroundMode == LivePatchBackgroundMode.THEME_SURFACE ||
                        it.backgroundMode == LivePatchBackgroundMode.FEATHERED_THEME_SURFACE
                },
                blurTintPatchCount = renderedPatches.count {
                    it.backgroundMode == LivePatchBackgroundMode.BLUR_TINT ||
                        it.backgroundMode == LivePatchBackgroundMode.FEATHERED_BLUR_TINT
                },
                sourceLatinTokenCount = textQuality.sourceLatinTokenCount,
                retainedLatinTokenCount = textQuality.retainedLatinTokenCount,
                retainedLatinRatio = textQuality.retainedLatinRatio,
                suspiciousJoinCount = textQuality.suspiciousJoinCount,
                largestPatchAreaRatio = largestPatchAreaRatio,
                renderFailures = renderFailures.toList(),
                translationTraces = batch.translationTraces
            ).also { result ->
                if (!usesIntegratedNetworkEngine &&
                    LiveCaptureTimingPolicy.shouldUpdateLiveSnapshot(
                        patchCount = result.patches.size,
                        translatedRegionCount = displayRegions.size
                    )
                ) {
                    updateLiveOverlaySnapshot(bitmap, mode, recognitionMode, displayRegions)
                }
            }
        } finally {
            liveOcrObscuredBounds = emptyList()
            if (!reuseResources) close()
        }
    }

    suspend fun renderDeterministicOverlay(
        bitmap: Bitmap,
        regions: List<LiveDeterministicTranslationRegion>,
        overlayAlpha: Float = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA,
        executionProfile: LiveRecognitionExecutionProfile = LiveRecognitionExecutionProfile.CURRENT,
        drawPatchBackgrounds: Boolean = true
    ): LiveDeterministicOverlayResult {
        check(!closed) { "Image processor is closed" }
        val renderEvidence = java.util.Collections.synchronizedList(
            mutableListOf<LiveRenderedTextEvidence>()
        )
        val renderFailures = java.util.Collections.synchronizedList(
            mutableListOf<LiveRenderFailureDiagnostic>()
        )
        val displayRegions = regions.map { region ->
            BackgroundImageRegion(
                source = RecognizedText(
                    text = region.sourceText,
                    bounds = Rect(region.bounds),
                    consensusScore = 1f,
                    passCount = 2,
                    modelConfidence = 1f,
                    recognizerScript = RecognizerScript.LATIN,
                    componentBounds = region.sourceLineBounds.map(::Rect),
                    componentTextHeightsPx = region.sourceTextHeightsPx
                ),
                translation = region.translation,
                groupId = region.groupId,
                smartAssistDisplayHints = region.displayHints,
                renderSlots = region.renderSlots.map(::Rect),
                sourceCoverSlots = region.sourceCoverSlots.map(::Rect)
            )
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val fallbackSurface = ScreenThemeColorEstimator.compositableSurface(
            ScreenThemeColorEstimator.estimate(bitmap),
            overlayAlpha
        )
        val rendered = renderOverlayPatches(
            bitmap = bitmap,
            regions = displayRegions,
            fallbackSurface = fallbackSurface,
            overlayAlpha = overlayAlpha,
            renderingMode = executionProfile.renderingMode,
            backgroundMode = executionProfile.backgroundMode,
            drawPatchBackgrounds = drawPatchBackgrounds,
            evidenceSink = renderEvidence::add,
            failureSink = renderFailures::add
        )
        val patches = mergeOverlappingPatches(rendered.map(RenderedOverlayPatch::patch))
        return LiveDeterministicOverlayResult(
            patches = patches,
            renderedText = renderEvidence.sortedWith(
                compareBy({ it.bounds.top }, { it.bounds.left })
            ),
            expectedRegionCount = regions.size,
            renderedRegionCount = rendered.size,
            failedRegionCount = (regions.size - rendered.size).coerceAtLeast(0),
            renderFailures = renderFailures.toList(),
            renderingMs = SystemClock.elapsedRealtime() - startedAtMs
        )
    }

    fun close() {
        if (closed) return
        closed = true
        if (ocrManagerDelegate.isInitialized()) ocrManager.close()
        if (translateManagerDelegate.isInitialized()) translateManager.close()
        integratedEngineRegistry.close()
        if (smartAssistAdapterDelegate.isInitialized()) smartAssistAdapterDelegate.value.close()
        synchronized(translationCache) { translationCache.clear() }
        clearRenderedTrackCache()
        liveOverlaySnapshot = null
    }

    private fun ensureTrackIds(regions: List<BackgroundImageRegion>): List<BackgroundImageRegion> =
        regions.map { region -> region.trackId?.let { region } ?: region.copy(trackId = nextTrackId++) }

    private suspend fun recognizeAndTranslateWithIntegratedEngine(
        bitmap: Bitmap,
        mode: TranslationMode,
        generation: Int,
        engineType: LiveOcrTranslationEngineType
    ): BackgroundTranslationBatch {
        val engine = integratedEngine(engineType)
        val result = engine.recognizeAndTranslate(
            LiveOcrTranslationRequest(
                bitmap = bitmap,
                mode = mode,
                sessionId = liveNetworkSessionId,
                generation = generation
            )
        )
        check(result.sourceWidth == bitmap.width && result.sourceHeight == bitmap.height) {
            "Integrated OCR response dimensions do not match the captured frame"
        }
        return BackgroundTranslationBatch(
            recognizedCount = result.recognizedCount,
            regions = result.regions.map { region ->
                BackgroundImageRegion(region.source, region.translation)
            },
            failedCount = result.failedCount,
            ocrMs = result.ocrMs,
            translationMs = result.translationMs
        )
    }

    private fun integratedEngine(
        engineType: LiveOcrTranslationEngineType
    ): LiveOcrTranslationEngine = integratedEngineRegistry.get(engineType)

    private fun clearRenderedTrackCache() {
        synchronized(renderedTrackCache) {
            renderedTrackCache.values.forEach { cached ->
                cached.bitmap.takeIf { !it.isRecycled }?.recycle()
            }
            renderedTrackCache.clear()
        }
    }

    private suspend fun applySmartAssist(
        viewportWidth: Int,
        viewportHeight: Int,
        regions: List<BackgroundImageRegion>
    ): SmartAssistApplication {
        if (regions.isEmpty()) return SmartAssistApplication(regions)
        val indexedRegions = regions.mapIndexed { index, region -> (index + 1L) to region }
        val outcome = smartAssistAdapterDelegate.value.analyze(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            regions = indexedRegions.map { (regionId, region) ->
                region.source.toSmartAssistRegion(regionId, region.translation)
            }
        )
        if (!outcome.applied) return SmartAssistApplication(regions)

        var protectedCount = 0
        var layoutHintCount = 0
        val assistedRegions = indexedRegions.mapNotNull { (regionId, region) ->
            val decision = outcome.decisions[regionId] ?: return@mapNotNull region
            if (decision.protectTranslation && region.smartAssistDisplayHints == null) {
                protectedCount++
                return@mapNotNull null
            }
            val displayHints = if (
                decision.layoutMode != null &&
                decision.preferredMaxLines != null &&
                decision.minimumTextScale != null
            ) {
                layoutHintCount++
                SmartAssistDisplayHints(
                    preferredMaxLines = decision.preferredMaxLines,
                    minimumTextScale = decision.minimumTextScale
                )
            } else {
                null
            }
            region.copy(
                smartAssistDisplayHints = region.smartAssistDisplayHints ?: displayHints
            )
        }
        return SmartAssistApplication(
            regions = assistedRegions,
            applied = true,
            scene = outcome.scene.name,
            groupCount = outcome.groupCount,
            protectedCount = protectedCount,
            layoutHintCount = layoutHintCount
        )
    }

    private suspend fun prepareContextualTranslationSources(
        viewportWidth: Int,
        viewportHeight: Int,
        recognized: List<RecognizedText>
    ): ContextualTranslationSources {
        if (recognized.isEmpty()) return ContextualTranslationSources(recognized)
        val indexed = recognized.mapIndexed { index, source -> (index + 1L) to source }
        val outcome = smartAssistAdapterDelegate.value.analyze(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            regions = indexed.map { (regionId, source) ->
                source.toSmartAssistRegion(regionId, translatedText = "")
            }
        )
        if (!outcome.applied) return ContextualTranslationSources(recognized)

        val protectedIds = outcome.decisions.values
            .filter(LiveSmartAssistDecision::protectTranslation)
            .mapTo(mutableSetOf(), LiveSmartAssistDecision::regionId)
        val sourcesById = indexed.toMap()
        val consumed = mutableSetOf<Long>()
        val contextual = mutableListOf<RecognizedText>()
        outcome.groups
            .sortedBy(LiveSmartAssistGroup::readingOrder)
            .filter { group ->
                group.role == AssistTrackRole.BODY &&
                    group.regionIds.size in 2..MAXIMUM_CONTEXTUAL_REGION_COUNT &&
                    group.contextText.length <= MAXIMUM_CONTEXTUAL_TEXT_LENGTH
            }
            .forEach { group ->
                val ids = group.regionIds.filter { it !in protectedIds && it !in consumed }
                val sources = ids.mapNotNull(sourcesById::get)
                if (sources.size < 2 || !hasCompatibleTypography(sources)) return@forEach
                contextual += mergeLiveTextLines(sources)
                consumed += ids
            }
        indexed.forEach { (id, source) ->
            if (id !in protectedIds && id !in consumed) contextual += source
        }
        return ContextualTranslationSources(
            sources = contextual.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })),
            applied = true,
            scene = outcome.scene.name,
            groupCount = outcome.groupCount,
            protectedCount = protectedIds.size
        )
    }

    private fun hasCompatibleTypography(sources: List<RecognizedText>): Boolean {
        val heights = sources.flatMap { source ->
            source.componentTextHeightsPx.ifEmpty {
                listOfNotNull(source.estimatedTextHeightPx)
            }
        }
            .filter { it > 0f }
        if (heights.size < 2) return true
        return heights.min() / heights.max().coerceAtLeast(1f) >=
            MINIMUM_CONTEXTUAL_FONT_SCALE_RATIO
    }

    private fun RecognizedText.toSmartAssistRegion(
        regionId: Long,
        translatedText: String
    ) = LiveSmartAssistRegion(
        regionId = regionId,
        sourceText = text,
        translatedText = translatedText,
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        consensusScore = consensusScore.coerceIn(0f, 1f),
        script = when (recognizerScript) {
            RecognizerScript.CHINESE -> AssistScript.HAN
            RecognizerScript.LATIN -> AssistScript.LATIN
            RecognizerScript.FUSED -> AssistScript.MIXED
        }
    )

    private suspend fun recognizeAndTranslate(
        bitmap: Bitmap,
        mode: TranslationMode,
        fastOcr: Boolean,
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO,
        recognitionBounds: Rect? = null,
        preferredRecognitionMode: OcrRecognitionMode? = null,
        smartAssistEnabled: Boolean = false,
        onRecognitionSucceeded: (Int) -> Unit = {}
    ): BackgroundTranslationBatch {
        val ocrStartedAt = SystemClock.elapsedRealtime()
        val croppedBitmap = recognitionBounds?.let { bounds ->
            Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        } ?: bitmap
        val sourceSize = LiveOcrInputSize(croppedBitmap.width, croppedBitmap.height)
        val inputSize = if (fastOcr) {
            LiveOcrScalePolicy.inputSize(croppedBitmap.width, croppedBitmap.height)
        } else {
            sourceSize
        }
        val ocrBitmap = if (inputSize != sourceSize) {
            Bitmap.createScaledBitmap(croppedBitmap, inputSize.width, inputSize.height, true)
        } else {
            croppedBitmap
        }
        val initialRawRecognized = try {
            val localRecognized = if (fastOcr) {
                ocrManager.recognizeFast(ocrBitmap, preferredRecognitionMode ?: recognitionMode)
            } else {
                ocrManager.recognize(ocrBitmap, recognitionMode)
            }
            val mapToSourceBounds: (Rect) -> Rect = { localBounds ->
                val sourceBounds = if (inputSize != sourceSize) {
                    Rect(
                        LiveOcrScalePolicy.mapX(localBounds.left, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapY(localBounds.top, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapX(localBounds.right, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapY(localBounds.bottom, inputSize, sourceSize)
                    )
                } else {
                    Rect(localBounds)
                }
                recognitionBounds?.let { bounds -> sourceBounds.offset(bounds.left, bounds.top) }
                sourceBounds
            }
            localRecognized.map { item ->
                item.copy(
                    bounds = mapToSourceBounds(item.bounds),
                    componentBounds = item.componentBounds.map(mapToSourceBounds)
                )
            }
        } finally {
            if (ocrBitmap !== croppedBitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            if (croppedBitmap !== bitmap && !croppedBitmap.isRecycled) croppedBitmap.recycle()
        }
        val contentViewport = resolveLiveContentViewport(bitmap)
        val edgeRecovered = if (fastOcr && recognitionBounds == null) {
            recoverMissingLiveEdgeText(
                bitmap = bitmap,
                recognized = initialRawRecognized,
                recognitionMode = preferredRecognitionMode ?: recognitionMode,
                viewport = contentViewport
            )
        } else {
            emptyList()
        }
        val rawRecognized = initialRawRecognized + edgeRecovered
        onRecognitionSucceeded(rawRecognized.size)
        val initialGrouping = groupSemanticText(
            recognized = rawRecognized,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            restrictToLiveContent = fastOcr,
            contentViewport = contentViewport
        )
        Log.i(
            TAG,
            "Live OCR filtering: rawRecognized=${initialRawRecognized.size}, " +
                "edgeRecovered=${edgeRecovered.size}, " +
                "edgeFiltered=${initialGrouping.edgeFilteredCount}, " +
                "continuations=${initialGrouping.continuationCount}, " +
                "retained=${initialGrouping.groups.sumOf { it.members.size }}, " +
                "contentBounds=${contentViewport.bounds}, " +
                "obscuredBounds=${contentViewport.obscuredBounds}"
        )
        val ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
        val smartAssistStartedAt = SystemClock.elapsedRealtime()
        val preparedSources = if (fastOcr && smartAssistEnabled) {
            prepareContextualTranslationSources(
                bitmap.width,
                bitmap.height,
                initialGrouping.groups.map(SemanticTextGroup::toRecognizedText)
            )
        } else {
            ContextualTranslationSources(
                initialGrouping.groups.map(SemanticTextGroup::toRecognizedText)
            )
        }
        val smartAssistMs = if (smartAssistEnabled) {
            SystemClock.elapsedRealtime() - smartAssistStartedAt
        } else {
            0L
        }
        val maximumTexts = if (fastOcr) {
            MAX_LIVE_TRANSLATION_TEXTS
        } else {
            MAX_IMAGE_TRANSLATION_TEXTS
        }
        val translationStartedAt = SystemClock.elapsedRealtime()
        val allTranslationGroups = SemanticTextGrouper.group(
            preparedSources.sources,
            bitmap.width,
            bitmap.height
        )
        val groupSelection = LiveTranslationGroupSelectionPolicy.select(
            groups = allTranslationGroups,
            maximumGroups = maximumTexts,
            viewportWidth = bitmap.width,
            viewportHeight = bitmap.height
        )
        if (groupSelection.dropped.isNotEmpty()) {
            Log.w(
                TAG,
                "Translation group budget applied: before=${allTranslationGroups.size}, " +
                    "selected=${groupSelection.selected.size}, " +
                    "dropped=${groupSelection.dropped.size}, " +
                    "droppedIds=${groupSelection.dropped.joinToString(",") { it.groupId }}"
            )
        }
        val translationGroups = groupSelection.selected
        val outcomes = translateRegions(
            translationGroups,
            bitmap,
            bitmap.width,
            bitmap.height,
            mode,
            if (fastOcr) "LIVE_SCREEN" else "STATIC_IMAGE"
        )
        val translationMs = SystemClock.elapsedRealtime() - translationStartedAt
        return BackgroundTranslationBatch(
            recognizedCount = rawRecognized.size,
            rawRecognizedCount = initialRawRecognized.size,
            edgeFilteredCount = initialGrouping.edgeFilteredCount,
            edgeRecoveredCount = edgeRecovered.size,
            continuationCount = initialGrouping.continuationCount,
            regions = outcomes.mapNotNull(TranslationOutcome::region),
            failedCount = outcomes.count(TranslationOutcome::failed),
            ocrMs = ocrMs,
            translationMs = translationMs,
            smartAssistApplied = preparedSources.applied,
            smartAssistScene = preparedSources.scene,
            smartAssistGroupCount = preparedSources.groupCount,
            smartAssistProtectedCount = preparedSources.protectedCount,
            smartAssistMs = smartAssistMs,
            translationTraces = outcomes.mapNotNull(TranslationOutcome::semanticTrace).distinct()
        )
    }

    private suspend fun recoverMissingLiveEdgeText(
        bitmap: Bitmap,
        recognized: List<RecognizedText>,
        recognitionMode: OcrRecognitionMode,
        viewport: LiveOcrContentViewport
    ): List<RecognizedText> {
        val recovered = mutableListOf<RecognizedText>()
        LiveOcrContentPolicy.recoveryBands(recognized, viewport).forEach { (band, _) ->
            val crop = Bitmap.createBitmap(
                bitmap,
                band.left,
                band.top,
                band.right - band.left,
                band.bottom - band.top
            )
            val local = try {
                ocrManager.recognize(crop, recognitionMode)
            } finally {
                if (!crop.isRecycled) crop.recycle()
            }
            local.map { item ->
                item.copy(
                    bounds = Rect(item.bounds).apply { offset(band.left, band.top) },
                    componentBounds = item.componentBounds.map { component ->
                        Rect(component).apply { offset(band.left, band.top) }
                    },
                    continuationAtTop = item.continuationAtTop,
                    continuationAtBottom = item.continuationAtBottom
                )
            }.forEach { candidate ->
                if ((recognized + recovered).none { existing ->
                        LiveOcrContentPolicy.isDuplicate(candidate, existing)
                    }
                ) {
                    recovered += candidate
                }
            }
        }
        return recovered
    }

    private suspend fun translateRegions(
        groups: List<SemanticTextGroup>,
        sourceBitmap: Bitmap,
        viewportWidth: Int,
        viewportHeight: Int,
        mode: TranslationMode,
        scene: String
    ): List<TranslationOutcome> {
        if (groups.isEmpty()) return emptyList()
        val activeExperimentalEngine = experimentalTranslationEngine
        val activeBackend = TranslationBackendSettings.get(appContext)
        val contextHash = groups.joinToString("|") { group ->
            "${group.groupId}:${normalizeCacheText(group.sourceText)}"
        }.hashCode()
        data class PreparedRegion(
            val group: SemanticTextGroup,
            val source: RecognizedText,
            val semanticSource: com.example.imagetranslate.translate.SemanticTranslationSource,
            val translationSource: String,
            val cacheKey: String,
            val cachedTranslation: String?
        )
        val prepared = groups.map { group ->
            val source = group.toRecognizedText()
            val semanticSource = group.toSemanticTranslationSource()
            val translationSource = normalizeCacheText(source.text)
            val cacheKey = "${activeBackend.name}:${activeExperimentalEngine.name}:" +
                "${mode.name}:$contextHash:$translationSource"
            PreparedRegion(
                group = group,
                source = source,
                semanticSource = semanticSource,
                translationSource = translationSource,
                cacheKey = cacheKey,
                cachedTranslation = if (activeBackend.isSelfHosted) {
                    null
                } else {
                    synchronized(translationCache) { translationCache[cacheKey] }
                }
            )
        }
        val missing = prepared.filter { it.cachedTranslation == null }
        val shouldArchiveRequest = missing.isNotEmpty() &&
            TranslationBackendSettings.isRequestArchiveExportEnabled(appContext)
        val debugCapture = if (shouldArchiveRequest || SemanticDebugCaptureUploadPolicy.shouldUpload(
                isDebugBuild = BuildConfig.DEBUG,
                scene = scene,
                backend = activeBackend,
                uploadEnabled = TranslationBackendSettings.isDebugCaptureUploadEnabled(appContext),
                missingGroupCount = missing.size
            )
        ) {
            SemanticDebugCaptureEncoder.encode(sourceBitmap)
        } else {
            null
        }
        val translatedMissing = try {
            translateManager.translateSemanticGroups(
                sources = missing.map(PreparedRegion::semanticSource),
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                mode = mode,
                scene = scene,
                experimentalEngine = activeExperimentalEngine,
                debugCapture = debugCapture
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val preparedByGroup = prepared.associateBy { it.group.groupId }
        val preparedByRegion = prepared.flatMap { item ->
            item.semanticSource.regions.map { region -> region.regionId to (item to region) }
        }.toMap()
        val cachedOutcomes = prepared.filter { it.cachedTranslation != null }.map { item ->
            val translation = checkNotNull(item.cachedTranslation).trim()
            TranslationOutcome(
                region = translation.takeIf { it.isNotEmpty() && it != item.translationSource }?.let {
                    BackgroundImageRegion(item.source, it, item.group.groupId)
                }
            )
        }
        val translatedOutcomes = translatedMissing.map { execution ->
            if (!execution.succeeded) {
                return@map TranslationOutcome(
                    failed = true,
                    semanticTrace = execution.semanticTrace
                )
            }
            val exactMembers = execution.memberRegionIds.mapNotNull(preparedByRegion::get)
            val usesAtomicMembers = exactMembers.size == execution.memberRegionIds.size &&
                exactMembers.isNotEmpty()
            val members = if (usesAtomicMembers) {
                exactMembers.map { it.first }.distinctBy { it.group.groupId }
            } else {
                execution.sourceGroupIds.mapNotNull(preparedByGroup::get)
            }
            if (members.isEmpty() || !usesAtomicMembers && members.size != execution.sourceGroupIds.size) {
                return@map TranslationOutcome(failed = true)
            }
            val atomicRegions = exactMembers.map { it.second }
                .sortedBy { it.readingOrder }
            val sourceText = if (usesAtomicMembers) {
                atomicRegions.joinToString("\n") { it.text }
            } else {
                members.joinToString("\n") { it.source.text }
            }
            val translation = execution.translatedText.trim()
            if (translation.isEmpty() || translation == sourceText.trim()) {
                return@map TranslationOutcome()
            }
            val renderSlots = execution.layoutHint?.renderSlots.orEmpty()
            val requestedRenderSlots = if (renderSlots.isNotEmpty()) {
                renderSlots.map { bounds ->
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
            } else {
                members.flatMap { it.source.textEraseBounds() }.map(::Rect)
            }
            val sourceLineBounds = if (usesAtomicMembers) {
                atomicRegions.flatMap { region ->
                    region.componentBounds.ifEmpty { listOf(region.bounds) }
                }.map { bounds -> Rect(bounds.left, bounds.top, bounds.right, bounds.bottom) }
            } else {
                members.flatMap { it.source.textEraseBounds() }.map(::Rect)
            }
            val anchor = execution.anchorBounds
            val union = anchor?.let { bounds ->
                Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
            } ?: requestedRenderSlots.drop(1).fold(Rect(requestedRenderSlots.first())) { result, bounds ->
                result.apply { union(bounds) }
            }
            val source = members.first().source.copy(
                text = sourceText,
                bounds = union,
                sourceBlockId = if (usesAtomicMembers) {
                    atomicRegions.mapNotNull { it.blockId }.distinct().singleOrNull()
                } else {
                    members.mapNotNull { it.source.sourceBlockId }.distinct().singleOrNull()
                },
                sourceLineIndex = if (usesAtomicMembers) {
                    atomicRegions.mapNotNull { it.lineIndex }.minOrNull()
                } else {
                    members.mapNotNull { it.source.sourceLineIndex }.minOrNull()
                },
                componentBounds = sourceLineBounds
            )
            BackgroundImageRegion(
                source = source,
                translation = translation,
                groupId = execution.regionId,
                smartAssistDisplayHints = execution.layoutHint?.let { hint ->
                    SmartAssistDisplayHints(
                        preferredMaxLines = maxOf(hint.preferredMaxLines, hint.sourceLineCount),
                        minimumTextScale = hint.minimumTextScale,
                        maximumTextScale = hint.maximumTextScale,
                        lineSpacingMultiplier = hint.lineSpacingMultiplier,
                        alignment = hint.alignment,
                        allowMore = hint.allowMore,
                        sourceLineCount = hint.sourceLineCount,
                        layoutShape = hint.layoutShape,
                        role = execution.role,
                        verticalAlignment = hint.verticalAlignment
                    )
                },
                renderSlots = requestedRenderSlots,
                sourceCoverSlots = execution.layoutHint?.sourceCoverSlots.orEmpty().map { bounds ->
                    Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                }.ifEmpty { sourceLineBounds }
            ).let {
                TranslationOutcome(region = it, semanticTrace = execution.semanticTrace)
            }
        }
        return cachedOutcomes + translatedOutcomes
    }

    private suspend fun recognizeVerticalBandsAndTranslate(
        bitmap: Bitmap,
        mode: TranslationMode,
        recognitionMode: OcrRecognitionMode,
        smartAssistEnabled: Boolean,
        onRecognitionSucceeded: (Int) -> Unit
    ): BackgroundTranslationBatch {
        val batches = LiveCaptureSettingsPolicy.verticalBands(bitmap.width, bitmap.height).map { band ->
            recognizeAndTranslate(
                bitmap = bitmap,
                mode = mode,
                fastOcr = true,
                recognitionMode = recognitionMode,
                recognitionBounds = Rect(band.left, band.top, band.right, band.bottom),
                smartAssistEnabled = smartAssistEnabled,
                onRecognitionSucceeded = onRecognitionSucceeded
            )
        }
        val mergedRegions = mutableListOf<BackgroundImageRegion>()
        batches.flatMap(BackgroundTranslationBatch::regions)
            .sortedWith(compareBy({ it.source.bounds.top }, { it.source.bounds.left }))
            .forEach { candidate ->
                if (mergedRegions.none { existing -> sameSegmentedRegion(existing, candidate) }) {
                    mergedRegions += candidate
                }
            }
        return BackgroundTranslationBatch(
            recognizedCount = batches.sumOf(BackgroundTranslationBatch::recognizedCount),
            rawRecognizedCount = batches.sumOf(BackgroundTranslationBatch::rawRecognizedCount),
            edgeFilteredCount = batches.sumOf(BackgroundTranslationBatch::edgeFilteredCount),
            edgeRecoveredCount = batches.sumOf(BackgroundTranslationBatch::edgeRecoveredCount),
            continuationCount = batches.sumOf(BackgroundTranslationBatch::continuationCount),
            regions = mergedRegions.take(MAX_LIVE_TRANSLATION_TEXTS),
            failedCount = batches.sumOf(BackgroundTranslationBatch::failedCount),
            ocrMs = batches.sumOf(BackgroundTranslationBatch::ocrMs),
            translationMs = batches.sumOf(BackgroundTranslationBatch::translationMs),
            smartAssistApplied = batches.any(BackgroundTranslationBatch::smartAssistApplied),
            smartAssistScene = batches.mapNotNull(BackgroundTranslationBatch::smartAssistScene)
                .firstOrNull(),
            smartAssistGroupCount = batches.sumOf(BackgroundTranslationBatch::smartAssistGroupCount),
            smartAssistProtectedCount = batches.sumOf(
                BackgroundTranslationBatch::smartAssistProtectedCount
            ),
            smartAssistMs = batches.sumOf(BackgroundTranslationBatch::smartAssistMs),
            translationTraces = batches
                .flatMap(BackgroundTranslationBatch::translationTraces)
                .distinct()
        )
    }

    private fun sameSegmentedRegion(
        first: BackgroundImageRegion,
        second: BackgroundImageRegion
    ): Boolean {
        val firstBounds = first.source.bounds
        val secondBounds = second.source.bounds
        val intersection = Rect()
        if (!intersection.setIntersect(firstBounds, secondBounds)) return false
        val minimumArea = minOf(
            firstBounds.width().toLong() * firstBounds.height(),
            secondBounds.width().toLong() * secondBounds.height()
        ).coerceAtLeast(1L)
        return intersection.width().toLong() * intersection.height() /
            minimumArea.toFloat() >= SEGMENTED_REGION_DUPLICATE_OVERLAP
    }

    private suspend fun recognizeDifferentialViewport(
        bitmap: Bitmap,
        mode: TranslationMode,
        recognitionMode: OcrRecognitionMode,
        capturePlan: ScrollCapturePlan,
        contextProfile: LiveDifferentialContextProfile,
        smartAssistEnabled: Boolean,
        onRecognitionSucceeded: (Int) -> Unit
    ): DifferentialTranslationBatch? {
        val snapshot = liveOverlaySnapshot ?: return rejectDifferential("NO_SNAPSHOT")
        if (snapshot.width != bitmap.width || snapshot.height != bitmap.height ||
            snapshot.mode != mode || snapshot.recognitionMode != recognitionMode
        ) return rejectDifferential("SNAPSHOT_MISMATCH")
        if (snapshot.regions.isEmpty()) return rejectDifferential("EMPTY_SNAPSHOT")
        if (!LiveDifferentialRecognitionPolicy.canAttempt(capturePlan, bitmap.height)) {
            return rejectDifferential("CAPTURE_PLAN_REJECTED")
        }

        val shiftY = capturePlan.contentShiftY
        val liveContentBounds = resolveLiveContentViewport(bitmap).bounds
        val contentTop = liveContentBounds.top
        val contentBottom = liveContentBounds.bottom
        val hasClippedContinuation = snapshot.regions.any { cached ->
            val shiftedBounds = Rect(cached.region.source.bounds).apply { offset(0, shiftY) }
            shiftedBounds.bottom > contentTop && shiftedBounds.top < contentBottom &&
                (shiftedBounds.top < contentTop || shiftedBounds.bottom > contentBottom)
        }
        val continuationBounds = if (hasClippedContinuation) {
            val continuationHeight = maxOf(
                DIFFERENTIAL_MINIMUM_CONTINUATION_PX,
                ((contentBottom - contentTop) * contextProfile.continuationHeightRatio).toInt()
            )
            listOf(
                if (shiftY < 0) {
                    LiveDifferentialBounds(
                        0,
                        contentTop,
                        bitmap.width,
                        (contentTop + continuationHeight).coerceAtMost(contentBottom)
                    )
                } else {
                    LiveDifferentialBounds(
                        0,
                        (contentBottom - continuationHeight).coerceAtLeast(contentTop),
                        bitmap.width,
                        contentBottom
                    )
                }
            )
        } else {
            emptyList()
        }
        val expectedSurvivors = snapshot.regions.mapNotNull { cached ->
            val shiftedBounds = Rect(cached.region.source.bounds).apply { offset(0, shiftY) }
            if (shiftedBounds.left < 0 || shiftedBounds.top < contentTop ||
                shiftedBounds.right > bitmap.width || shiftedBounds.bottom > contentBottom
            ) return@mapNotNull null
            cached to shiftedBounds
        }
        val shifted = expectedSurvivors.mapNotNull { (cached, shiftedBounds) ->
            val matchedBounds = findFingerprintMatch(
                bitmap = bitmap,
                expectedBounds = shiftedBounds,
                expectedFingerprint = cached.fingerprint,
                contentTop = contentTop,
                contentBottom = contentBottom
            ) ?: return@mapNotNull null
            cached.region.shiftedToMatchedBounds(matchedBounds)
        }
        if (!LiveDifferentialRecognitionPolicy.hasSufficientReuse(
                expectedSurvivorCount = expectedSurvivors.size,
                matchedCount = shifted.size
            )
        ) return rejectDifferential("INSUFFICIENT_TRACK_REUSE")
        val validationRatio = shifted.size.toFloat() / expectedSurvivors.size.coerceAtLeast(1)

        val dirtyGrid = LiveDirtyGridPolicy.detect(
            previous = snapshot.luminanceGrid,
            current = captureLuminanceGrid(bitmap),
            shiftY = shiftY,
            viewportWidth = bitmap.width,
            viewportHeight = bitmap.height,
            contentTop = contentTop,
            contentBottom = contentBottom
        )
        if (!LiveDifferentialRecognitionPolicy.hasReliableDirtyGrid(
                dirtyCellCount = dirtyGrid.dirtyCellCount,
                comparedCellCount = dirtyGrid.comparedCellCount
            )
        ) return rejectDifferential("EXCESSIVE_DIRTY_GRID")
        if (dirtyGrid.dirtyCellCount > contextProfile.maximumDirtyCellCount) {
            return rejectDifferential("ACCURACY_DIRTY_GRID_GUARD")
        }
        val regionPlan = LiveDifferentialRegionPlanner.plan(
            viewportWidth = bitmap.width,
            viewportHeight = bitmap.height,
            shiftY = shiftY,
            dirtyGrid = dirtyGrid,
            shiftedTracks = shifted.map { it.source.bounds.toDifferentialBounds() },
            continuationBounds = continuationBounds,
            maximumRecognitionAreaRatio = contextProfile.maximumRecognitionAreaRatio
        ) ?: return rejectDifferential("REGION_PLAN_REJECTED")
        if (!LiveDifferentialRecognitionPolicy.hasEfficientRecognitionArea(
                shiftY = shiftY,
                viewportHeight = bitmap.height,
                recognitionAreaRatio = regionPlan.recognitionAreaRatio
            )
        ) return rejectDifferential("INEFFICIENT_SHORT_SCROLL_ROI")
        if (regionPlan.recognitionBounds.size > contextProfile.maximumRecognitionRegionCount) {
            return rejectDifferential("MULTI_REGION_ACCURACY_GUARD")
        }
        val recognitionBounds = regionPlan.recognitionBounds.map { bounds ->
            Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

        val invalidOutsideRecognitionArea = expectedSurvivors.size - shifted.size > 0 &&
            shifted.none { region ->
                recognitionBounds.any { bounds -> Rect.intersects(region.source.bounds, bounds) }
            }
        if (invalidOutsideRecognitionArea && validationRatio < STRONG_REUSED_REGION_RATIO) {
            return rejectDifferential("UNVALIDATED_OUTSIDE_ROI")
        }
        val boundaryTracks = shifted.filter { region ->
            recognitionBounds.any { bounds -> Rect.intersects(region.source.bounds, bounds) }
        }
        val reused = shifted - boundaryTracks.toSet()
        val recognitionBatches = coroutineScope {
            recognitionBounds.map { bounds ->
                async {
                    recognizeAndTranslate(
                        bitmap = bitmap,
                        mode = mode,
                        fastOcr = true,
                        recognitionMode = recognitionMode,
                        recognitionBounds = bounds,
                        preferredRecognitionMode = preferredRecognitionMode(
                            snapshot,
                            recognitionMode
                        ),
                        smartAssistEnabled = smartAssistEnabled,
                        onRecognitionSucceeded = onRecognitionSucceeded
                    )
                }
            }.awaitAll()
        }
        val candidates = mutableListOf<BackgroundImageRegion>()
        recognitionBatches.flatMap(BackgroundTranslationBatch::regions)
            .sortedWith(compareBy({ it.source.bounds.top }, { it.source.bounds.left }))
            .forEach { candidate ->
                if (candidates.none { existing -> sameSegmentedRegion(existing, candidate) }) {
                    candidates += candidate
                }
            }
        val trackPlan = LiveTrackMergePolicy.plan(
            boundaryTracks = boundaryTracks.mapNotNull { region ->
                region.trackId?.let { trackId ->
                    LiveTrackedRegion(trackId, region.source.bounds.toDifferentialBounds())
                }
            },
            candidates = candidates.map { it.source.bounds.toDifferentialBounds() }
        )
        val trackedCandidates = candidates.mapIndexed { index, candidate ->
            candidate.copy(trackId = trackPlan.candidateTrackIds[index])
        }
        val restoredBoundary = boundaryTracks.filter { it.trackId in trackPlan.restoredTrackIds }
        val combined = mutableListOf<BackgroundImageRegion>()
        (trackedCandidates + reused + restoredBoundary).forEach { candidate ->
            if (combined.none { existing ->
                    LiveTrackMergePolicy.isDuplicate(
                        existing.source.bounds.toDifferentialBounds(),
                        candidate.source.bounds.toDifferentialBounds()
                    )
                }
            ) {
                combined += candidate
            }
        }
        if (!LiveDifferentialRecognitionPolicy.hasSufficientOutput(
                snapshotCount = snapshot.regions.size,
                outputCount = combined.size
            )
        ) return rejectDifferential("INSUFFICIENT_OUTPUT")
        return DifferentialTranslationBatch(
            batch = BackgroundTranslationBatch(
                recognizedCount = reused.size + restoredBoundary.size +
                    recognitionBatches.sumOf(BackgroundTranslationBatch::recognizedCount),
                rawRecognizedCount = reused.size + restoredBoundary.size +
                    recognitionBatches.sumOf(BackgroundTranslationBatch::rawRecognizedCount),
                edgeFilteredCount = recognitionBatches.sumOf(
                    BackgroundTranslationBatch::edgeFilteredCount
                ),
                edgeRecoveredCount = recognitionBatches.sumOf(
                    BackgroundTranslationBatch::edgeRecoveredCount
                ),
                continuationCount = recognitionBatches.sumOf(
                    BackgroundTranslationBatch::continuationCount
                ),
                regions = combined.sortedWith(
                    compareBy({ it.source.bounds.top }, { it.source.bounds.left })
                ).take(MAX_LIVE_TRANSLATION_TEXTS),
                failedCount = recognitionBatches.sumOf(BackgroundTranslationBatch::failedCount),
                ocrMs = recognitionBatches.maxOfOrNull(BackgroundTranslationBatch::ocrMs) ?: 0L,
                translationMs = recognitionBatches.maxOfOrNull(
                    BackgroundTranslationBatch::translationMs
                ) ?: 0L,
                smartAssistApplied = recognitionBatches.any(
                    BackgroundTranslationBatch::smartAssistApplied
                ),
                smartAssistScene = recognitionBatches.mapNotNull(
                    BackgroundTranslationBatch::smartAssistScene
                ).firstOrNull(),
                smartAssistGroupCount = recognitionBatches.sumOf(
                    BackgroundTranslationBatch::smartAssistGroupCount
                ),
                smartAssistProtectedCount = recognitionBatches.sumOf(
                    BackgroundTranslationBatch::smartAssistProtectedCount
                ),
                smartAssistMs = recognitionBatches.sumOf(BackgroundTranslationBatch::smartAssistMs),
                translationTraces = recognitionBatches
                    .flatMap(BackgroundTranslationBatch::translationTraces)
                    .distinct()
            ),
            reusedRegionCount = reused.size + restoredBoundary.size,
            recognitionRegionCount = recognitionBounds.size,
            recognitionAreaRatio = regionPlan.recognitionAreaRatio,
            dirtyCellCount = regionPlan.dirtyCellCount,
            boundaryTrackCount = regionPlan.boundaryTrackCount,
            restoredBoundaryTrackCount = restoredBoundary.size
        )
    }

    private fun rejectDifferential(reason: String): DifferentialTranslationBatch? {
        lastDifferentialFallbackReason = reason
        return null
    }

    private fun resolveLiveContentViewport(bitmap: Bitmap): LiveOcrContentViewport =
        LiveOcrContentViewportResolver.resolve(
            context = appContext,
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            obscuredBounds = liveOcrObscuredBounds
        )

    private suspend fun renderOverlayPatches(
        bitmap: Bitmap,
        regions: List<BackgroundImageRegion>,
        fallbackSurface: Int,
        overlayAlpha: Float,
        renderingMode: LivePatchRenderingMode,
        backgroundMode: LivePatchBackgroundMode,
        drawPatchBackgrounds: Boolean,
        evidenceSink: ((LiveRenderedTextEvidence) -> Unit)? = null,
        failureSink: ((LiveRenderFailureDiagnostic) -> Unit)? = null
    ): List<RenderedOverlayPatch> {
        val occupiedBoundsByRegion = regions.indices.map { currentIndex ->
            regions.asSequence()
                .filterIndexed { index, _ -> index != currentIndex }
                .flatMap { other -> resolvedSourceCoverSlots(other).asSequence() }
                .map(::Rect)
                .toList()
        }
        return when (renderingMode) {
        LivePatchRenderingMode.SEQUENTIAL -> regions.mapIndexedNotNull { index, region ->
            createOverlayPatch(
                bitmap,
                region,
                occupiedBoundsByRegion[index],
                fallbackSurface,
                overlayAlpha,
                backgroundMode,
                drawPatchBackgrounds,
                evidenceSink,
                failureSink
            )
        }
        LivePatchRenderingMode.PARALLEL -> coroutineScope {
            regions.mapIndexed { index, region ->
                async(Dispatchers.Default) {
                    createOverlayPatch(
                        bitmap,
                        region,
                        occupiedBoundsByRegion[index],
                        fallbackSurface,
                        overlayAlpha,
                        backgroundMode,
                        drawPatchBackgrounds,
                        evidenceSink,
                        failureSink
                    )
                }
            }.awaitAll().filterNotNull()
        }
        }
    }

    private fun preferredRecognitionMode(
        snapshot: LiveOverlaySnapshot,
        requestedMode: OcrRecognitionMode
    ): OcrRecognitionMode {
        if (requestedMode != OcrRecognitionMode.AUTO) return requestedMode
        val scripts = snapshot.regions.map { it.region.source.recognizerScript }.distinct()
        return when (scripts.singleOrNull()) {
            RecognizerScript.CHINESE -> OcrRecognitionMode.CHINESE
            RecognizerScript.LATIN -> OcrRecognitionMode.ENGLISH
            else -> OcrRecognitionMode.AUTO
        }
    }

    private fun updateLiveOverlaySnapshot(
        bitmap: Bitmap,
        mode: TranslationMode,
        recognitionMode: OcrRecognitionMode,
        regions: List<BackgroundImageRegion>
    ) {
        liveOverlaySnapshot = LiveOverlaySnapshot(
            width = bitmap.width,
            height = bitmap.height,
            mode = mode,
            recognitionMode = recognitionMode,
            regions = regions.mapNotNull { region ->
                val bounds = region.source.bounds.clampedTo(bitmap) ?: return@mapNotNull null
                val trackedRegion = if (region.trackId == null) {
                    region.copy(trackId = nextTrackId++)
                } else {
                    region
                }
                CachedLiveRegion(
                    region = trackedRegion.copy(
                        source = trackedRegion.source.copy(bounds = Rect(bounds))
                    ),
                    fingerprint = fingerprint(bitmap, bounds)
                )
            },
            luminanceGrid = captureLuminanceGrid(bitmap)
        )
    }

    private fun captureLuminanceGrid(bitmap: Bitmap): LiveLuminanceGrid {
        val values = IntArray(LUMINANCE_GRID_COLUMNS * LUMINANCE_GRID_ROWS)
        var index = 0
        repeat(LUMINANCE_GRID_ROWS) { row ->
            val y = ((row + 0.5f) * bitmap.height / LUMINANCE_GRID_ROWS)
                .toInt().coerceIn(0, bitmap.height - 1)
            repeat(LUMINANCE_GRID_COLUMNS) { column ->
                val x = ((column + 0.5f) * bitmap.width / LUMINANCE_GRID_COLUMNS)
                    .toInt().coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                values[index++] = (Color.red(color) * 54 + Color.green(color) * 183 +
                    Color.blue(color) * 19) shr 8
            }
        }
        return LiveLuminanceGrid(LUMINANCE_GRID_COLUMNS, LUMINANCE_GRID_ROWS, values)
    }

    private fun fingerprint(bitmap: Bitmap, bounds: Rect): IntArray {
        val samples = IntArray(FINGERPRINT_COLUMNS * FINGERPRINT_ROWS)
        var index = 0
        repeat(FINGERPRINT_ROWS) { row ->
            val y = bounds.top + (bounds.height() - 1) * row /
                (FINGERPRINT_ROWS - 1).coerceAtLeast(1)
            repeat(FINGERPRINT_COLUMNS) { column ->
                val x = bounds.left + (bounds.width() - 1) * column /
                    (FINGERPRINT_COLUMNS - 1).coerceAtLeast(1)
                val color = bitmap.getPixel(x, y)
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                samples[index++] = (red * 54 + green * 183 + blue * 19) shr 8
            }
        }
        return samples
    }

    private fun findFingerprintMatch(
        bitmap: Bitmap,
        expectedBounds: Rect,
        expectedFingerprint: IntArray,
        contentTop: Int,
        contentBottom: Int
    ): Rect? {
        var bestBounds: Rect? = null
        var bestError = Float.MAX_VALUE
        for (offsetY in -FINGERPRINT_VERTICAL_SEARCH_PX..FINGERPRINT_VERTICAL_SEARCH_PX step
            FINGERPRINT_VERTICAL_SEARCH_STEP_PX
        ) {
            val candidate = Rect(expectedBounds).apply { offset(0, offsetY) }
            if (candidate.top < contentTop || candidate.bottom > contentBottom) continue
            val error = fingerprintError(expectedFingerprint, fingerprint(bitmap, candidate))
            if (error < bestError) {
                bestError = error
                bestBounds = candidate
            }
        }
        return bestBounds?.takeIf { bestError <= MAXIMUM_FINGERPRINT_ERROR }
    }

    private fun fingerprintError(first: IntArray, second: IntArray): Float {
        if (first.size != second.size || first.isEmpty()) return Float.MAX_VALUE
        return first.indices.sumOf { index ->
            kotlin.math.abs(first[index] - second[index])
        }.toFloat() / first.size
    }

    private fun normalizeCacheText(text: String): String =
        text.trim().replace(CACHE_WHITESPACE_REGEX, " ")

    private fun createOverlayPatch(
        bitmap: Bitmap,
        region: BackgroundImageRegion,
        occupiedBounds: List<Rect>,
        fallbackSurface: Int,
        overlayAlpha: Float,
        backgroundMode: LivePatchBackgroundMode,
        drawPatchBackground: Boolean,
        evidenceSink: ((LiveRenderedTextEvidence) -> Unit)? = null,
        failureSink: ((LiveRenderFailureDiagnostic) -> Unit)? = null
    ): RenderedOverlayPatch? {
        val sourceBounds = region.source.bounds.clampedTo(bitmap) ?: run {
            failureSink?.invoke(
                basicRenderFailure(region, "INVALID_SOURCE_BOUNDS")
            )
            return null
        }
        val safeHorizontalExpansionSlot = requestedSafeHorizontalExpansion(
            bitmap = bitmap,
            region = region,
            sourceBounds = sourceBounds,
            occupiedBounds = occupiedBounds
        )
        val materialTextBounds = safeHorizontalExpansionSlot?.let { expansion ->
            Rect(sourceBounds).apply { union(expansion) }
        } ?: sourceBounds
        val material = LiveOverlayLayoutPolicy.translationMaterialBounds(
            textBounds = LivePatchBounds(
                index = 0,
                left = materialTextBounds.left,
                top = materialTextBounds.top,
                right = materialTextBounds.right,
                bottom = materialTextBounds.bottom
            ),
            sourceText = region.source.text,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height
        )
        val materialBounds = Rect(material.left, material.top, material.right, material.bottom)
        val usesDeclarativeCoverage = region.renderSlots.isNotEmpty() ||
            region.sourceCoverSlots.isNotEmpty()
        val localSurface = if (
            (backgroundMode == LivePatchBackgroundMode.STANDARD && !usesDeclarativeCoverage) ||
            !drawPatchBackground
        ) {
            fallbackSurface
        } else {
            estimateLocalSurface(
                bitmap,
                materialBounds,
                fallbackSurface,
                overlayAlpha
            )
        }
        val cropBounds = Rect(
            materialBounds.left,
            materialBounds.top,
            materialBounds.right,
            materialBounds.bottom
        )
        val crop = Bitmap.createBitmap(
            bitmap,
            cropBounds.left,
            cropBounds.top,
            cropBounds.width(),
            cropBounds.height()
        )
        val resolvedBackgroundMode = LivePatchBackgroundPolicy.resolve(
            requested = backgroundMode,
            profile = if (backgroundMode == LivePatchBackgroundMode.ADAPTIVE) {
                LivePatchBackgroundPolicy.profile(crop)
            } else {
                LivePatchTextureProfile(1f, 0f, 1)
            },
            blurAvailable = App.isOpenCVReady
        )
        val usesVisualFingerprint =
            resolvedBackgroundMode == LivePatchBackgroundMode.BLUR_TINT ||
            resolvedBackgroundMode == LivePatchBackgroundMode.FEATHERED_BLUR_TINT
        val materialFingerprint = if (usesVisualFingerprint) {
            fingerprint(bitmap, cropBounds)
        } else {
            IntArray(0)
        }
        val cacheKey = RenderedTrackCacheKey(
            sourceText = normalizeCacheText(region.source.text),
            translation = region.translation,
            width = cropBounds.width(),
            height = cropBounds.height(),
            backgroundMode = resolvedBackgroundMode,
            surfaceColor = localSurface,
            overlayAlphaPercent = (overlayAlpha.coerceIn(0f, 1f) * 1_000).toInt(),
            drawBackground = drawPatchBackground,
            displayHints = region.smartAssistDisplayHints,
            renderSlotSignature = resolvedRenderSlots(region, sourceBounds).flatMap { slot ->
                listOf(
                    slot.left - cropBounds.left,
                    slot.top - cropBounds.top,
                    slot.right - cropBounds.left,
                    slot.bottom - cropBounds.top
                )
            },
            sourceCoverSlotSignature = resolvedSourceCoverSlots(region).flatMap { slot ->
                listOf(
                    slot.left - cropBounds.left,
                    slot.top - cropBounds.top,
                    slot.right - cropBounds.left,
                    slot.bottom - cropBounds.top
                )
            }
        )
        val cached = region.trackId?.let { trackId ->
            synchronized(renderedTrackCache) {
                renderedTrackCache[trackId]?.takeIf { candidate ->
                    candidate.key == cacheKey && !candidate.bitmap.isRecycled &&
                        (!usesVisualFingerprint ||
                            LiveRenderedTrackReusePolicy.hasMatchingVisualFingerprint(
                                candidate.materialFingerprint,
                                materialFingerprint
                            ))
                }
            }
        }
        if (cached != null) {
            val cachedBitmap = cached.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            crop.recycle()
            return RenderedOverlayPatch(
                patch = ScreenTranslationPatch(Rect(cropBounds), cachedBitmap, region.groupId),
                backgroundDetailRetentionRatio = cached.backgroundDetailRetentionRatio,
                cacheHit = true,
                backgroundMode = cached.backgroundMode
            )
        }
        val localBounds = Rect(
            sourceBounds.left - cropBounds.left,
            sourceBounds.top - cropBounds.top,
            sourceBounds.right - cropBounds.left,
            sourceBounds.bottom - cropBounds.top
        )
        val localMaterialBounds = Rect(0, 0, cropBounds.width(), cropBounds.height())
        val hasMultipleRenderSlots = resolvedRenderSlots(region, sourceBounds).size > 1
        var output: Bitmap? = null
        var preparedBackground: LivePatchBackground? = null
        var hasPreparedBackground = false
        return try {
            val patchBitmap = Bitmap.createBitmap(
                cropBounds.width(),
                cropBounds.height(),
                Bitmap.Config.ARGB_8888
            )
            output = patchBitmap
            if (drawPatchBackground && !hasMultipleRenderSlots && !usesDeclarativeCoverage) {
                when (resolvedBackgroundMode) {
                    LivePatchBackgroundMode.BLUR_TINT,
                    LivePatchBackgroundMode.FEATHERED_BLUR_TINT -> {
                        preparedBackground = LivePatchBackgroundComposer.createBlurTintTarget(
                            source = crop,
                            themeSurface = localSurface,
                            overlayAlpha = overlayAlpha
                        ).also { background ->
                            LivePatchBackgroundComposer.drawCompensatedTarget(
                                output = patchBitmap,
                                target = background.bitmap,
                                source = crop,
                                overlayAlpha = overlayAlpha
                            )
                        }
                        hasPreparedBackground = true
                    }
                    LivePatchBackgroundMode.FEATHERED_THEME_SURFACE -> {
                        LivePatchBackgroundComposer.drawCompensatedColorTarget(
                            output = patchBitmap,
                            source = crop,
                            themeSurface = localSurface,
                            overlayAlpha = overlayAlpha
                        )
                        hasPreparedBackground = true
                    }
                    else -> Unit
                }
                if (
                    resolvedBackgroundMode == LivePatchBackgroundMode.FEATHERED_THEME_SURFACE ||
                    resolvedBackgroundMode == LivePatchBackgroundMode.FEATHERED_BLUR_TINT
                ) {
                    LivePatchBackgroundComposer.applyFeatheredAlpha(
                        output = patchBitmap,
                        opaqueCore = localBounds
                    )
                }
            }
            val localRegion = region.copy(
                source = region.source.copy(
                    bounds = localBounds,
                    componentBounds = region.source.textEraseBounds().map { component ->
                        Rect(
                            component.left - cropBounds.left,
                            component.top - cropBounds.top,
                            component.right - cropBounds.left,
                            component.bottom - cropBounds.top
                        )
                    }
                ),
                renderSlots = resolvedRenderSlots(region, sourceBounds).map { slot ->
                    Rect(slot).apply { offset(-cropBounds.left, -cropBounds.top) }
                },
                sourceCoverSlots = resolvedSourceCoverSlots(region).map { slot ->
                    Rect(slot).apply { offset(-cropBounds.left, -cropBounds.top) }
                },
                safeHorizontalExpansionSlot = safeHorizontalExpansionSlot?.let { slot ->
                    Rect(slot).apply { offset(-cropBounds.left, -cropBounds.top) }
                }
            )
            val rendered = BackgroundTranslatedImageRenderer.render(
                patchBitmap,
                listOf(localRegion),
                styleSourceBitmap = crop,
                overlayBackgroundColor = localSurface,
                overlayAlpha = overlayAlpha,
                overlayMaterialBounds = localMaterialBounds,
                drawOverlayBackground = drawPatchBackground && !hasPreparedBackground,
                evidenceSink = { evidence ->
                    evidenceSink?.invoke(
                        evidence.copy(
                            bounds = Rect(evidence.bounds).apply {
                                offset(cropBounds.left, cropBounds.top)
                            }
                        )
                    )
                },
                failureSink = { failure ->
                    failureSink?.invoke(
                        failure.copy(
                            renderSlots = failure.renderSlots.map { slot ->
                                Rect(slot).apply { offset(cropBounds.left, cropBounds.top) }
                            },
                            sourceCoverSlots = failure.sourceCoverSlots.map { slot ->
                                Rect(slot).apply { offset(cropBounds.left, cropBounds.top) }
                            }
                        )
                    )
                }
            )
            if (rendered.isEmpty()) {
                patchBitmap.recycle()
                output = null
                null
            } else {
                RenderedOverlayPatch(
                    patch = ScreenTranslationPatch(Rect(cropBounds), patchBitmap, region.groupId),
                    backgroundDetailRetentionRatio =
                        preparedBackground?.detailRetentionRatio ?: 0f,
                    cacheHit = false,
                    backgroundMode = resolvedBackgroundMode
                ).also { renderedPatch ->
                    output = null
                    region.trackId?.let { trackId ->
                        val cachedBitmap = patchBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        synchronized(renderedTrackCache) {
                            renderedTrackCache.put(
                                trackId,
                                CachedRenderedTrack(
                                    key = cacheKey,
                                    materialFingerprint = materialFingerprint.copyOf(),
                                    bitmap = cachedBitmap,
                                    backgroundDetailRetentionRatio =
                                        renderedPatch.backgroundDetailRetentionRatio,
                                    backgroundMode = resolvedBackgroundMode
                                )
                            )?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            failureSink?.invoke(
                basicRenderFailure(region, "RENDER_EXCEPTION")
            )
            Log.w(
                TAG,
                "Unable to render translated group " +
                    "id=${region.groupId ?: "unknown"}, " +
                    "slots=${region.renderSlots.size}, " +
                    "sourceLines=${region.smartAssistDisplayHints?.sourceLineCount ?: 1}",
                error
            )
            output?.takeIf { !it.isRecycled }?.recycle()
            null
        } finally {
            preparedBackground?.bitmap?.takeIf { !it.isRecycled }?.recycle()
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
    }

    private fun basicRenderFailure(
        region: BackgroundImageRegion,
        reason: String
    ) = LiveRenderFailureDiagnostic(
        groupId = region.groupId,
        reason = reason,
        layoutShape = region.smartAssistDisplayHints?.layoutShape,
        renderSlots = region.renderSlots.map(::Rect),
        sourceCoverSlots = region.sourceCoverSlots.map(::Rect),
        sourceLineHeightPx = 0f,
        preferredTextSizePx = 0f,
        minimumAttemptedTextSizePx = 0f,
        lastAttemptedTextSizePx = 0f,
        maximumLines = region.smartAssistDisplayHints?.preferredMaxLines ?: 0,
        requireAllSlots = false,
        allowMore = region.smartAssistDisplayHints?.allowMore == true,
        attemptedLineSpacingMultipliers = listOf(1f)
    )

    private fun groupSemanticText(
        recognized: List<RecognizedText>,
        sourceWidth: Int,
        sourceHeight: Int,
        restrictToLiveContent: Boolean,
        contentViewport: LiveOcrContentViewport? = null
    ): SemanticGroupingResult {
        val filtered = if (restrictToLiveContent) {
            LiveOcrContentPolicy.filter(
                recognized,
                requireNotNull(contentViewport) { "Live OCR requires a resolved content viewport" }
            )
        } else {
            LiveOcrContentFilterResult(
                retained = StaticImageTextFilter.filter(recognized, sourceWidth, sourceHeight),
                edgeFilteredCount = 0,
                continuationCount = 0
            )
        }
        return SemanticGroupingResult(
            groups = SemanticTextGrouper.group(filtered.retained, sourceWidth, sourceHeight),
            edgeFilteredCount = filtered.edgeFilteredCount,
            continuationCount = filtered.continuationCount
        )
    }

    private fun mergeLiveTextLines(lines: List<RecognizedText>): RecognizedText {
        val ordered = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bounds = Rect(ordered.first().bounds)
        ordered.drop(1).forEach { bounds.union(it.bounds) }
        val weights = ordered.map { item ->
            item.text.count { it.isLetterOrDigit() }.coerceAtLeast(1)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val sourceBlockIds = ordered.mapNotNull(RecognizedText::sourceBlockId).distinct()
        val componentBounds = ordered
            .flatMap(RecognizedText::textEraseBounds)
            .distinct()
            .map(::Rect)
        val componentTextHeights = ordered.flatMap { item ->
            val bounds = item.textEraseBounds()
            item.componentTextHeightsPx.takeIf { it.size == bounds.size }
                ?: List(bounds.size) {
                    item.estimatedTextHeightPx ?: item.bounds.height().toFloat()
                }
        }
        return RecognizedText(
            text = ordered.joinToString("\n") { it.text.trim() },
            bounds = bounds,
            consensusScore = ordered.minOf { it.consensusScore },
            passCount = ordered.minOf { it.passCount },
            modelConfidence = ordered.zip(weights).sumOf { (item, weight) ->
                (item.modelConfidence * weight).toDouble()
            }.toFloat() / totalWeight,
            recognizerScript = ordered.map { it.recognizerScript }.distinct().singleOrNull()
                ?: RecognizerScript.FUSED,
            sourceBlockId = sourceBlockIds.singleOrNull(),
            sourceLineIndex = ordered.mapNotNull(RecognizedText::sourceLineIndex).minOrNull(),
            componentBounds = componentBounds,
            componentTextHeightsPx = componentTextHeights,
            estimatedTextHeightPx = ordered.mapNotNull(RecognizedText::estimatedTextHeightPx)
                .sorted().let { values -> values.getOrNull(values.size / 2) },
            typographyConfidence = ordered.minOf(RecognizedText::typographyConfidence),
            continuationAtTop = ordered.any(RecognizedText::continuationAtTop),
            continuationAtBottom = ordered.any(RecognizedText::continuationAtBottom)
        )
    }

    private fun estimateLocalSurface(
        bitmap: Bitmap,
        bounds: Rect,
        fallbackSurface: Int,
        overlayAlpha: Float
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
                if (x in bounds.left until bounds.right && y in bounds.top until bounds.bottom) {
                    continue
                }
                samples += bitmap.getPixel(x, y)
            }
        }
        if (samples.size < LOCAL_SURFACE_MINIMUM_SAMPLES) return fallbackSurface
        return ScreenThemeColorEstimator.compositableSurface(
            ScreenThemeColorEstimator.estimate(samples.toIntArray()),
            overlayAlpha
        )
    }

    private fun mergeOverlappingPatches(
        patches: List<ScreenTranslationPatch>
    ): List<ScreenTranslationPatch> {
        if (patches.size < 2) return patches
        val groups = patches.indices.groupBy { index ->
            patches[index].groupId ?: "independent-$index"
        }.values.flatMap { sameSourceIndices ->
            LiveOverlayLayoutPolicy.groupIntersectingPatches(
                sameSourceIndices.map { index ->
                    val patch = patches[index]
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
        }.sortedBy { indices -> indices.minOrNull() ?: Int.MAX_VALUE }
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
            ScreenTranslationPatch(union, mergedBitmap, groupedPatches.first().groupId)
        }
    }

    private companion object {
        const val TAG = "BackgroundImageProcessor"
        const val MAX_IMAGE_TRANSLATION_TEXTS = 24
        const val MAX_LIVE_TRANSLATION_TEXTS = 32
        const val MINIMUM_CONTEXTUAL_FONT_SCALE_RATIO = 0.78f
        const val MAX_TRANSLATION_CACHE_ENTRIES = 256
        const val MAXIMUM_CONTEXTUAL_REGION_COUNT = 4
        const val MAXIMUM_CONTEXTUAL_TEXT_LENGTH = 512
        const val MAX_RENDERED_TRACK_CACHE_ENTRIES = 48
        const val PATCH_WINDOW_MERGE_GAP_PX = 3
        const val LOCAL_SURFACE_MINIMUM_PADDING_PX = 8
        const val LOCAL_SURFACE_MAXIMUM_PADDING_PX = 36
        const val LOCAL_SURFACE_SAMPLE_GRID = 48
        const val LOCAL_SURFACE_MINIMUM_SAMPLES = 16
        const val STRONG_REUSED_REGION_RATIO = 0.72f
        const val LUMINANCE_GRID_COLUMNS = 48
        const val LUMINANCE_GRID_ROWS = 80
        const val DIFFERENTIAL_MINIMUM_CONTINUATION_PX = 96
        const val FINGERPRINT_COLUMNS = 6
        const val FINGERPRINT_ROWS = 4
        const val FINGERPRINT_VERTICAL_SEARCH_PX = 32
        const val FINGERPRINT_VERTICAL_SEARCH_STEP_PX = 8
        const val MAXIMUM_FINGERPRINT_ERROR = 38f
        const val SEGMENTED_REGION_DUPLICATE_OVERLAP = 0.6f
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

private fun Rect.toCoverageBounds(): LiveCoverageBounds = LiveCoverageBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom
)

private fun Rect.toDifferentialBounds(): LiveDifferentialBounds = LiveDifferentialBounds(
    left = left,
    top = top,
    right = right,
    bottom = bottom
)

private fun resolvedRenderSlots(region: BackgroundImageRegion, bounds: Rect): List<Rect> =
    region.renderSlots.takeIf { it.isNotEmpty() }?.map(::Rect)
        ?: SemanticRenderShape.slots(region.source.textEraseBounds(), bounds)

private fun resolvedSourceCoverSlots(region: BackgroundImageRegion): List<Rect> =
    region.sourceCoverSlots.takeIf { it.isNotEmpty() }?.map(::Rect)
        ?: region.source.textEraseBounds().map(::Rect)

private const val TARGET_GLYPH_SAMPLE_CHARACTERS = 64
private const val MINIMUM_MERGED_FONT_SCALE_RATIO = 0.78f
private const val MINIMUM_TITLE_MERGED_FONT_SCALE_RATIO = 0.70f

internal fun denseBodyRectFallback(
    renderSlots: List<Rect>,
    layoutShape: String?,
    sourceLineCount: Int,
    role: String?,
    sourceTextHeightsPx: List<Float> = emptyList()
): Rect? {
    if (role != "BODY" || layoutShape != "FLOW_SLOTS" || renderSlots.size < 3 ||
        sourceLineCount < 3
    ) return null
    val union = renderSlots.drop(1).fold(Rect(renderSlots.first())) { result, slot ->
        result.apply { union(slot) }
    }
    if (union.width() <= 0) return null
    val heights = renderSlots.map(Rect::height).filter { it > 0 }.sorted()
    if (heights.isEmpty()) return null
    val typicalHeight = heights[heights.size / 2].coerceAtLeast(1)
    val validTextHeights = sourceTextHeightsPx.filter { it > 0f }
    val heightRatio = if (validTextHeights.size >= 2) {
        validTextHeights.min() / validTextHeights.max().coerceAtLeast(1f)
    } else {
        heights.first().toFloat() / heights.last().coerceAtLeast(1)
    }
    if (heightRatio < MINIMUM_MERGED_FONT_SCALE_RATIO) return null
    val maximumGap = renderSlots.zipWithNext().maxOfOrNull { (first, second) ->
        second.top - first.bottom
    } ?: 0
    if (maximumGap > typicalHeight) return null

    val leftRange = renderSlots.maxOf(Rect::left) - renderSlots.minOf(Rect::left)
    val leftTolerance = maxOf(typicalHeight * 2, union.width() * 12 / 100)
    if (leftRange > leftTolerance) return null
    val hasNarrowFinalLine = renderSlots.last().width() <= union.width() * 20 / 100
    if (!hasNarrowFinalLine) return null

    val nonFinal = renderSlots.dropLast(1)
    val mainColumnWidth = union.width() * 72 / 100
    val wideLineCount = nonFinal.count { it.width() >= mainColumnWidth }
    return union.takeIf { wideLineCount * 2 >= nonFinal.size }
}

internal fun safeFlowUnionRectFallback(
    renderSlots: List<Rect>,
    layoutShape: String?,
    sourceLineCount: Int,
    role: String?,
    sourceTextHeightsPx: List<Float> = emptyList()
): Rect? {
    val eligibleRole = role == "BODY" ||
        role == "TITLE" && renderSlots.size in 2..5 ||
        role == "LIST_ITEM" && renderSlots.size == 2
    if (!eligibleRole || layoutShape != "FLOW_SLOTS" || renderSlots.size < 2 ||
        sourceLineCount < 2
    ) return null
    val union = renderSlots.drop(1).fold(Rect(renderSlots.first())) { result, slot ->
        result.apply { union(slot) }
    }
    if (union.width() <= 0 || union.height() <= 0) return null

    val slotHeights = renderSlots.map(Rect::height).filter { it > 0 }.sorted()
    if (slotHeights.size != renderSlots.size) return null
    val typicalHeight = slotHeights[slotHeights.size / 2].coerceAtLeast(1)
    val textHeights = sourceTextHeightsPx.filter { it > 0f }
    val typographyRatio = if (textHeights.size >= 2) {
        textHeights.min() / textHeights.max().coerceAtLeast(1f)
    } else {
        slotHeights.first().toFloat() / slotHeights.last().coerceAtLeast(1)
    }
    val unreliableTwoLineTitleTypography = role == "TITLE" && renderSlots.size == 2
    val stableMultiLineTitle = role == "TITLE" && renderSlots.size in 3..5 &&
        typographyRatio >= MINIMUM_TITLE_MERGED_FONT_SCALE_RATIO
    if (typographyRatio < MINIMUM_MERGED_FONT_SCALE_RATIO &&
        !stableMultiLineTitle &&
        !unreliableTwoLineTitleTypography
    ) return null

    val maximumGap = renderSlots.zipWithNext().maxOf { (first, second) ->
        second.top - first.bottom
    }
    val maximumAllowedGap = if (role == "LIST_ITEM") typicalHeight else typicalHeight / 2
    if (maximumGap < 0 || maximumGap > maximumAllowedGap) return null
    val leftRange = renderSlots.maxOf(Rect::left) - renderSlots.minOf(Rect::left)
    val leftTolerance = if (role == "BODY" && renderSlots.size <= 4) {
        maxOf(typicalHeight * 5 / 2, union.width() * 12 / 100)
    } else {
        maxOf(typicalHeight, union.width() * 8 / 100)
    }
    if (leftRange > leftTolerance) return null

    val nonFinal = renderSlots.dropLast(1)
    val wideLineCount = nonFinal.count { slot -> slot.width() >= union.width() * 70 / 100 }
    val hasWideBody = wideLineCount * 2 >= nonFinal.size
    val maximumTailPercent = if (role == "TITLE" && renderSlots.size >= 3) {
        80
    } else if (role == "TITLE") {
        55
    } else {
        40
    }
    val uniformlyWideBody = role == "BODY" && renderSlots.all { slot ->
        slot.width() >= union.width() * 70 / 100
    }
    val uniformlyWideTitle = role == "TITLE" && renderSlots.size in 3..5 &&
        renderSlots.all { slot -> slot.width() >= union.width() * 70 / 100 }
    val tailShapeAccepted = role == "LIST_ITEM" ||
        renderSlots.last().width() <= union.width() * maximumTailPercent / 100 ||
        uniformlyWideBody || uniformlyWideTitle
    return union.takeIf { hasWideBody && tailShapeAccepted }
}

private val SAFE_HORIZONTAL_EXPANSION_ROLES = setOf("BODY", "METADATA", "TITLE", "LABEL")
private const val SAFE_HORIZONTAL_EXPANSION_VERTICAL_INSET_RATIO = 0.04f
private const val SAFE_HORIZONTAL_EXPANSION_MINIMUM_MARGIN_PX = 8
private const val SAFE_HORIZONTAL_EXPANSION_MINIMUM_COLLISION_PADDING_PX = 8
private const val SAFE_HORIZONTAL_EXPANSION_MAXIMUM_VIEWPORT_PERCENT = 75
private const val SAFE_HORIZONTAL_EXPANSION_MAXIMUM_SOURCE_MULTIPLIER = 3
private const val SAFE_HORIZONTAL_SOURCE_DOMINANT_RATIO = 0.5f
private const val SAFE_HORIZONTAL_EXTENSION_DOMINANT_RATIO = 0.78f
private const val SAFE_HORIZONTAL_MAXIMUM_LUMINANCE_DEVIATION = 14f
private const val SAFE_HORIZONTAL_MAXIMUM_EDGE_RATIO = 0.04f
private const val SAFE_HORIZONTAL_EDGE_LUMINANCE_DELTA = 24
private const val SAFE_HORIZONTAL_MINIMUM_TEXT_SIZE_PX = 8f
private const val SAFE_HORIZONTAL_DECLARATIVE_RETRY_SCALE = 0.82f

internal fun safeSingleLineHorizontalExpansion(
    bitmap: Bitmap,
    sourceSlot: Rect,
    occupiedBounds: List<Rect>,
    requiredWidthPx: Int,
    layoutShape: String?,
    sourceLineCount: Int,
    role: String?
): Rect? {
    if (layoutShape != "RECT" || sourceLineCount != 1 ||
        role !in SAFE_HORIZONTAL_EXPANSION_ROLES || requiredWidthPx <= sourceSlot.width() ||
        sourceSlot.width() <= 0 || sourceSlot.height() <= 0
    ) return null
    val verticalInset = (bitmap.height * SAFE_HORIZONTAL_EXPANSION_VERTICAL_INSET_RATIO).toInt()
    if (sourceSlot.top < verticalInset || sourceSlot.bottom > bitmap.height - verticalInset) {
        return null
    }
    val horizontalMargin = maxOf(
        SAFE_HORIZONTAL_EXPANSION_MINIMUM_MARGIN_PX,
        sourceSlot.height() / 4
    )
    val maximumWidth = minOf(
        bitmap.width * SAFE_HORIZONTAL_EXPANSION_MAXIMUM_VIEWPORT_PERCENT / 100,
        sourceSlot.width() * SAFE_HORIZONTAL_EXPANSION_MAXIMUM_SOURCE_MULTIPLIER
    )
    if (requiredWidthPx > maximumWidth) return null
    val candidateRight = sourceSlot.left + requiredWidthPx
    if (candidateRight > bitmap.width - horizontalMargin) return null
    val extension = Rect(sourceSlot.right, sourceSlot.top, candidateRight, sourceSlot.bottom)
    if (extension.width() <= 0) return null
    val collisionPadding = maxOf(
        SAFE_HORIZONTAL_EXPANSION_MINIMUM_COLLISION_PADDING_PX,
        sourceSlot.height() / 4
    )
    if (occupiedBounds.any { occupied ->
            val protected = Rect(
                occupied.left - collisionPadding,
                occupied.top - collisionPadding,
                occupied.right + collisionPadding,
                occupied.bottom + collisionPadding
            )
            Rect.intersects(extension, protected)
        }
    ) return null

    val sourceProfile = horizontalExpansionSurfaceProfile(bitmap, sourceSlot) ?: return null
    val extensionProfile = horizontalExpansionSurfaceProfile(bitmap, extension) ?: return null
    if (sourceProfile.dominantRatio < SAFE_HORIZONTAL_SOURCE_DOMINANT_RATIO ||
        extensionProfile.dominantRatio < SAFE_HORIZONTAL_EXTENSION_DOMINANT_RATIO ||
        extensionProfile.luminanceStandardDeviation > SAFE_HORIZONTAL_MAXIMUM_LUMINANCE_DEVIATION ||
        extensionProfile.edgeRatio > SAFE_HORIZONTAL_MAXIMUM_EDGE_RATIO ||
        !areNeighboringSurfaceBuckets(sourceProfile.dominantBucket, extensionProfile.dominantBucket)
    ) return null
    return Rect(sourceSlot.left, sourceSlot.top, candidateRight, sourceSlot.bottom)
}

private data class HorizontalExpansionSurfaceProfile(
    val dominantBucket: Int,
    val dominantRatio: Float,
    val luminanceStandardDeviation: Float,
    val edgeRatio: Float
)

private fun horizontalExpansionSurfaceProfile(
    bitmap: Bitmap,
    requestedBounds: Rect
): HorizontalExpansionSurfaceProfile? {
    val bounds = requestedBounds.clampedTo(bitmap) ?: return null
    if (bounds.width() <= 0 || bounds.height() <= 0) return null
    val step = maxOf(1, minOf(bounds.width(), bounds.height()) / 14)
    val buckets = HashMap<Int, Int>()
    var samples = 0
    var luminanceTotal = 0.0
    var luminanceSquaredTotal = 0.0
    var edgeSamples = 0
    var edgeCount = 0
    for (y in bounds.top until bounds.bottom step step) {
        var previousLuminance: Int? = null
        for (x in bounds.left until bounds.right step step) {
            val color = bitmap.getPixel(x, y)
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val bucket = (red shr 4 shl 8) or (green shr 4 shl 4) or (blue shr 4)
            buckets[bucket] = (buckets[bucket] ?: 0) + 1
            val value = (red * 54 + green * 183 + blue * 19) / 256
            luminanceTotal += value
            luminanceSquaredTotal += value.toDouble() * value
            previousLuminance?.let { previous ->
                if (kotlin.math.abs(value - previous) >= SAFE_HORIZONTAL_EDGE_LUMINANCE_DELTA) {
                    edgeCount++
                }
                edgeSamples++
            }
            previousLuminance = value
            samples++
        }
    }
    if (samples == 0) return null
    val dominant = buckets.maxByOrNull { it.value } ?: return null
    val mean = luminanceTotal / samples
    val variance = luminanceSquaredTotal / samples - mean * mean
    return HorizontalExpansionSurfaceProfile(
        dominantBucket = dominant.key,
        dominantRatio = dominant.value.toFloat() / samples,
        luminanceStandardDeviation = sqrt(variance.coerceAtLeast(0.0)).toFloat(),
        edgeRatio = edgeCount.toFloat() / edgeSamples.coerceAtLeast(1)
    )
}

private fun areNeighboringSurfaceBuckets(first: Int, second: Int): Boolean {
    val firstRed = first shr 8 and 0xF
    val firstGreen = first shr 4 and 0xF
    val firstBlue = first and 0xF
    val secondRed = second shr 8 and 0xF
    val secondGreen = second shr 4 and 0xF
    val secondBlue = second and 0xF
    return kotlin.math.abs(firstRed - secondRed) <= 1 &&
        kotlin.math.abs(firstGreen - secondGreen) <= 1 &&
        kotlin.math.abs(firstBlue - secondBlue) <= 1
}

private fun requestedSafeHorizontalExpansion(
    bitmap: Bitmap,
    region: BackgroundImageRegion,
    sourceBounds: Rect,
    occupiedBounds: List<Rect>
): Rect? {
    val hints = region.smartAssistDisplayHints ?: return null
    if (region.translation.isBlank() || '\n' in region.translation || hints.alignment != "START") {
        return null
    }
    val slots = resolvedRenderSlots(region, sourceBounds).mapNotNull { it.clampedTo(bitmap) }
    val sourceSlot = slots.singleOrNull() ?: return null
    val horizontalPadding = maxOf(2, sourceSlot.height() / 8)
    val minimumTextSize = maxOf(
        SAFE_HORIZONTAL_MINIMUM_TEXT_SIZE_PX,
        sourceSlot.height() * hints.minimumTextScale.coerceIn(0.5f, 1f) *
            SAFE_HORIZONTAL_DECLARATIVE_RETRY_SCALE
    )
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = minimumTextSize
        typeface = Typeface.SANS_SERIF
    }
    val requiredWidth = ceil(Layout.getDesiredWidth(region.translation, paint).toDouble())
        .toInt() + horizontalPadding * 2
    return safeSingleLineHorizontalExpansion(
        bitmap = bitmap,
        sourceSlot = sourceSlot,
        occupiedBounds = occupiedBounds,
        requiredWidthPx = requiredWidth,
        layoutShape = hints.layoutShape,
        sourceLineCount = hints.sourceLineCount,
        role = hints.role
    )
}

internal fun targetTextSizeForSourceGlyph(
    paint: TextPaint,
    text: String,
    preferredTextSizePx: Float,
    sourceGlyphHeightPx: Float
): Float {
    if (text.isBlank() || preferredTextSizePx <= 0f || sourceGlyphHeightPx <= 0f) {
        return preferredTextSizePx
    }
    paint.textSize = preferredTextSizePx
    val sample = text.filterNot(Char::isWhitespace).take(TARGET_GLYPH_SAMPLE_CHARACTERS)
    if (sample.isEmpty()) return preferredTextSizePx
    val inkBounds = Rect()
    paint.getTextBounds(sample, 0, sample.length, inkBounds)
    val targetInkHeight = inkBounds.height().toFloat().coerceAtLeast(1f)
    return minOf(
        preferredTextSizePx,
        preferredTextSizePx * sourceGlyphHeightPx / targetInkHeight
    )
}

internal fun resolvedVerticalTextOffset(
    availableHeightPx: Int,
    layoutHeightPx: Int,
    verticalAlignment: String,
    role: String?,
    sourceLineCount: Int,
    sourceLineHeightPx: Float,
    sourceGlyphHeightPx: Float
): Float {
    val centered = ((availableHeightPx - layoutHeightPx) / 2f).coerceAtLeast(0f)
    val resolved = when (verticalAlignment.uppercase()) {
        "TOP" -> "TOP"
        "CENTER" -> "CENTER"
        else -> if (sourceLineCount >= 3 && role in setOf("BODY", "CODE", "LIST_ITEM")) {
            "TOP"
        } else {
            "CENTER"
        }
    }
    if (resolved == "CENTER") return centered
    val sourceLeading = ((sourceLineHeightPx - sourceGlyphHeightPx.coerceAtLeast(0f)) / 2f)
        .coerceIn(0f, sourceLineHeightPx.coerceAtLeast(0f) / 3f)
    return minOf(centered, sourceLeading)
}

private object BackgroundTranslatedImageRenderer {
    private const val TAG = "BackgroundImageRenderer"

    fun render(
        bitmap: Bitmap,
        regions: List<BackgroundImageRegion>,
        styleSourceBitmap: Bitmap = bitmap,
        overlayBackgroundColor: Int? = null,
        overlayAlpha: Float = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA,
        overlayMaterialBounds: Rect? = null,
        drawOverlayBackground: Boolean = true,
        evidenceSink: ((LiveRenderedTextEvidence) -> Unit)? = null,
        failureSink: ((LiveRenderFailureDiagnostic) -> Unit)? = null
    ): List<Rect> {
        val canvas = Canvas(bitmap)
        val renderedRegions = mutableListOf<Rect>()
        regions.forEach { region ->
            val bounds = region.source.bounds.clampedTo(bitmap) ?: return@forEach
            val requestedRenderSlots = resolvedRenderSlots(region, bounds)
                .mapNotNull { it.clampedTo(bitmap) }
            val sourceCoverSlots = resolvedSourceCoverSlots(region)
                .mapNotNull { it.clampedTo(bitmap) }
            if (requestedRenderSlots.isEmpty()) return@forEach
            val estimatedStyle = estimateTextStyle(
                styleSourceBitmap,
                bounds,
                region.source.text,
                sourceCoverSlots
            )
            val style = if (overlayBackgroundColor == null) {
                estimatedStyle
            } else {
                val isDarkTheme = ScreenThemeColorEstimator.isDark(overlayBackgroundColor)
                estimatedStyle.copy(
                    foregroundColor = ScreenThemeColorEstimator.readableForeground(
                        estimatedStyle.foregroundColor,
                        overlayBackgroundColor
                    ),
                    isDarkBackground = isDarkTheme
                )
            }
            val isControlLabel = style.isDarkBackground &&
                region.source.text.filterNot(Char::isWhitespace).length <= 20
            val sourceLineCount = region.smartAssistDisplayHints?.sourceLineCount
                ?.coerceAtLeast(1) ?: region.source.textEraseBounds().size.coerceAtLeast(1)
            val mergedBodyRect = denseBodyRectFallback(
                renderSlots = requestedRenderSlots,
                layoutShape = region.smartAssistDisplayHints?.layoutShape,
                sourceLineCount = sourceLineCount,
                role = region.smartAssistDisplayHints?.role,
                sourceTextHeightsPx = region.source.componentTextHeightsPx.ifEmpty {
                    listOfNotNull(region.source.estimatedTextHeightPx)
                }
            )
            val renderSlots = mergedBodyRect?.let(::listOf) ?: requestedRenderSlots
            val layoutMetrics = StaticImageTextLayoutPolicy.resolve(
                groupBounds = bounds,
                componentBounds = region.source.textEraseBounds(),
                fontSizeMultiplier = style.fontSizeMultiplier,
                preferredMaxLines = region.smartAssistDisplayHints?.preferredMaxLines,
                minimumTextScale = region.smartAssistDisplayHints?.minimumTextScale,
                sourceLineCount = sourceLineCount
            )
            val sourceLineHeight = layoutMetrics.sourceLineHeightPx
            val horizontalPadding = if (isControlLabel) {
                0
            } else {
                maxOf(2, (sourceLineHeight / 8f).toInt())
            }
            val alignment = if (isControlLabel ||
                region.smartAssistDisplayHints?.alignment == "CENTER"
            ) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.foregroundColor
                typeface = style.typeface
            }
            val requestedPreferredSize = layoutMetrics.preferredTextSizePx *
                (region.smartAssistDisplayHints?.maximumTextScale ?: 1f)
            val preferredSize = targetTextSizeForSourceGlyph(
                paint = paint,
                text = region.translation,
                preferredTextSizePx = requestedPreferredSize,
                sourceGlyphHeightPx = style.sourceGlyphHeightPx
            )
            val minimumSize = minOf(layoutMetrics.minimumTextSizePx, preferredSize)
            val maximumLines = layoutMetrics.maximumLines
            val translatedCharacterCount = region.translation.count { character ->
                !character.isWhitespace()
            }
            val preserveFlowShape = region.smartAssistDisplayHints?.layoutShape == "FLOW_SLOTS" &&
                mergedBodyRect == null &&
                renderSlots.size > 1 &&
                translatedCharacterCount >= renderSlots.size * MINIMUM_CHARACTERS_PER_FLOW_SLOT
            val isMultiLineRect = renderSlots.size == 1 &&
                (region.smartAssistDisplayHints?.layoutShape == null ||
                    region.smartAssistDisplayHints.layoutShape == "RECT") &&
                sourceLineCount >= RECT_FALLBACK_MINIMUM_SOURCE_LINES
            val isCompactSingleLineRect = renderSlots.size == 1 &&
                region.smartAssistDisplayHints?.layoutShape == "RECT" &&
                sourceLineCount == 1 &&
                translatedCharacterCount <= COMPACT_RECT_MAXIMUM_CHARACTERS
            val safeHorizontalExpansionSlot = region.safeHorizontalExpansionSlot
            val initialLayout = ShapeAwareTextLayout.layout(
                text = region.translation,
                paint = paint,
                renderSlots = renderSlots,
                preferredTextSizePx = preferredSize,
                minimumTextSizePx = minimumSize,
                maximumLines = maximumLines,
                alignment = alignment,
                horizontalPadding = horizontalPadding,
                allowOverflowMore = region.smartAssistDisplayHints?.allowMore == true,
                lineSpacingMultipliers = region.smartAssistDisplayHints?.lineSpacingMultiplier
                    ?.let { preferred -> listOf(preferred, 1f, 0.92f, 0.86f).distinct() },
                requireAllSlots = preserveFlowShape
            )
            val leadingSlotRetry = initialLayout?.takeIf { layout ->
                renderSlots.size > 1 &&
                    layout.segments.firstOrNull()?.bounds != renderSlots.first() &&
                    renderSlots.first().height() >= sourceLineHeight * MINIMUM_LEADING_SLOT_RATIO
            }?.let {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                    ),
                    maximumLines = maximumLines,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = preserveFlowShape
                )?.takeIf { layout -> layout.segments.firstOrNull()?.bounds == renderSlots.first() }
            }
            val standardRetry = initialLayout
                ?.takeUnless { layout ->
                    (isMultiLineRect || safeHorizontalExpansionSlot != null) &&
                        layout.outcome == ShapeAwareTextOutcome.OVERFLOW_MORE
                }
                ?: region.smartAssistDisplayHints
                ?.let { hints ->
                    ShapeAwareTextLayout.layout(
                        text = region.translation,
                        paint = paint,
                        renderSlots = renderSlots,
                        preferredTextSizePx = preferredSize,
                        minimumTextSizePx = maxOf(
                            MINIMUM_TEXT_SIZE_PX,
                            minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                        ),
                        maximumLines = if (hints.allowMore) {
                            maxOf(maximumLines, hints.sourceLineCount + 2)
                        } else {
                            maximumLines
                        },
                        alignment = alignment,
                        horizontalPadding = horizontalPadding,
                        allowOverflowMore = hints.allowMore && safeHorizontalExpansionSlot == null,
                        lineSpacingMultipliers = listOf(1f),
                        requireAllSlots = preserveFlowShape
                    )
                }
            val safeHorizontalExpansionRetry = if (
                standardRetry == null && safeHorizontalExpansionSlot != null &&
                renderSlots.size == 1
            ) {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = listOf(safeHorizontalExpansionSlot),
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                    ),
                    maximumLines = 1,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )?.takeIf { layout ->
                    layout.displayedText == region.translation &&
                        layout.lineSpacingMultiplier >=
                        ShapeAwareTextLayout.MINIMUM_SAFE_LINE_SPACING_MULTIPLIER
                }
            } else {
                null
            }
            val safeHorizontalOverflowRetry = if (
                standardRetry == null && safeHorizontalExpansionRetry == null &&
                safeHorizontalExpansionSlot != null &&
                region.smartAssistDisplayHints?.allowMore == true
            ) {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                    ),
                    maximumLines = maximumLines,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = true,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )
            } else {
                null
            }
            val compactSingleLineRectRetry = if (
                standardRetry == null && safeHorizontalExpansionRetry == null &&
                safeHorizontalOverflowRetry == null && isCompactSingleLineRect
            ) {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        sourceLineHeight * if (
                            region.smartAssistDisplayHints?.role == "TITLE"
                        ) {
                            COMPACT_TITLE_MINIMUM_TEXT_SCALE
                        } else {
                            COMPACT_RECT_MINIMUM_TEXT_SCALE
                        }
                    ),
                    maximumLines = if (
                        region.smartAssistDisplayHints?.role == "TITLE"
                    ) {
                        maxOf(2, maximumLines)
                    } else {
                        1
                    },
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )?.takeIf { layout -> layout.displayedText == region.translation }
            } else {
                null
            }
            val flowSlotPrefixRetry = if (
                standardRetry == null &&
                preserveFlowShape &&
                region.smartAssistDisplayHints?.role == "BODY"
            ) {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                    ),
                    maximumLines = maximumLines,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )?.takeIf { layout ->
                    layout.displayedText == region.translation &&
                        layout.lineSpacingMultiplier >=
                        ShapeAwareTextLayout.MINIMUM_SAFE_LINE_SPACING_MULTIPLIER &&
                        layout.segments.firstOrNull()?.bounds == renderSlots.first()
                }
            } else {
                null
            }
            val safeFlowUnionRect = if (
                standardRetry == null && flowSlotPrefixRetry == null && preserveFlowShape
            ) {
                safeFlowUnionRectFallback(
                    renderSlots = renderSlots,
                    layoutShape = region.smartAssistDisplayHints?.layoutShape,
                    sourceLineCount = sourceLineCount,
                    role = region.smartAssistDisplayHints?.role,
                    sourceTextHeightsPx = region.source.componentTextHeightsPx.ifEmpty {
                        listOfNotNull(region.source.estimatedTextHeightPx)
                    }
                )
            } else {
                null
            }
            val safeFlowUnionRetry = safeFlowUnionRect?.let { union ->
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = listOf(union),
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                    ),
                    maximumLines = maxOf(
                        maximumLines,
                        sourceLineCount + FLOW_UNION_ADDITIONAL_LINES
                    ),
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )?.takeIf { layout ->
                    layout.displayedText == region.translation &&
                        layout.lineSpacingMultiplier >=
                        ShapeAwareTextLayout.MINIMUM_SAFE_LINE_SPACING_MULTIPLIER
                }
            }
            val rectRetry = if (standardRetry == null && isMultiLineRect) {
                val hints = checkNotNull(region.smartAssistDisplayHints)
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = maxOf(
                        MINIMUM_TEXT_SIZE_PX,
                        sourceLineHeight * RECT_FALLBACK_MINIMUM_TEXT_SCALE
                    ),
                    maximumLines = maximumLines + RECT_FALLBACK_ADDITIONAL_LINES,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    // The regions-first server has already returned the whole translation.
                    // Prefer a smaller complete block over silently replacing it with "more".
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )
            } else {
                null
            }
            val forcedRectRetry = if (
                rectRetry == null && standardRetry == null && isMultiLineRect
            ) {
                ShapeAwareTextLayout.layout(
                    text = region.translation,
                    paint = paint,
                    renderSlots = renderSlots,
                    preferredTextSizePx = preferredSize,
                    minimumTextSizePx = MINIMUM_TEXT_SIZE_PX,
                    maximumLines = FORCED_RECT_MAXIMUM_LINES,
                    alignment = alignment,
                    horizontalPadding = horizontalPadding,
                    allowOverflowMore = false,
                    lineSpacingMultipliers = listOf(1f),
                    requireAllSlots = false
                )
            } else {
                null
            }
            val shapedLayout = leadingSlotRetry ?: standardRetry ?: safeHorizontalExpansionRetry ?:
                safeHorizontalOverflowRetry ?: compactSingleLineRectRetry ?: flowSlotPrefixRetry ?:
                safeFlowUnionRetry ?: rectRetry ?: forcedRectRetry
            if (leadingSlotRetry != null) {
                Log.d(
                    TAG,
                    "Reflowed translated group into leading render slot " +
                        "id=${region.groupId ?: "unknown"}, slots=${renderSlots.size}"
                )
            }
            if (flowSlotPrefixRetry != null) {
                Log.i(
                    TAG,
                    "Applied safe FLOW_SLOTS prefix fallback " +
                        "id=${region.groupId ?: "unknown"}, " +
                        "usedSlots=${flowSlotPrefixRetry.segments.size}/${renderSlots.size}, " +
                        "lineSpacing=${flowSlotPrefixRetry.lineSpacingMultiplier}"
                )
            }
            if (safeFlowUnionRetry != null) {
                Log.i(
                    TAG,
                    "Applied safe FLOW_SLOTS union fallback " +
                        "id=${region.groupId ?: "unknown"}, slots=${renderSlots.size}, " +
                        "lines=${safeFlowUnionRetry.segments.sumOf { it.layout.lineCount }}, " +
                        "lineSpacing=${safeFlowUnionRetry.lineSpacingMultiplier}"
                )
            }
            if (safeHorizontalExpansionRetry != null) {
                Log.i(
                    TAG,
                    "Applied safe horizontal expansion " +
                        "id=${region.groupId ?: "unknown"}, " +
                        "width=${renderSlots.single().width()}->" +
                        "${safeHorizontalExpansionSlot?.width()}, " +
                        "scale=${safeHorizontalExpansionRetry.textSizePx / sourceLineHeight}"
                )
            }
            if (compactSingleLineRectRetry != null) {
                Log.i(
                    TAG,
                    "Applied compact single-line RECT fallback " +
                        "id=${region.groupId ?: "unknown"}, chars=$translatedCharacterCount, " +
                        "scale=${compactSingleLineRectRetry.textSizePx / sourceLineHeight}"
                )
            }
            val relaxedRectLayout = rectRetry ?: forcedRectRetry
            if (relaxedRectLayout != null) {
                Log.i(
                    TAG,
                    "Applied relaxed RECT fallback id=${region.groupId ?: "unknown"}, " +
                        "outcome=${relaxedRectLayout.outcome}, scale=" +
                        "${relaxedRectLayout.textSizePx / sourceLineHeight.coerceAtLeast(1f)}"
                )
            }
            if (shapedLayout == null) {
                failureSink?.invoke(
                    LiveRenderFailureDiagnostic(
                        groupId = region.groupId,
                        reason = "TEXT_DOES_NOT_FIT",
                        layoutShape = region.smartAssistDisplayHints?.layoutShape,
                        renderSlots = renderSlots.map(::Rect),
                        sourceCoverSlots = sourceCoverSlots.map(::Rect),
                        sourceLineHeightPx = sourceLineHeight,
                        preferredTextSizePx = preferredSize,
                        minimumAttemptedTextSizePx = when {
                            isMultiLineRect -> MINIMUM_TEXT_SIZE_PX
                            isCompactSingleLineRect -> maxOf(
                                MINIMUM_TEXT_SIZE_PX,
                                sourceLineHeight * COMPACT_RECT_MINIMUM_TEXT_SCALE
                            )
                            else -> maxOf(
                                MINIMUM_TEXT_SIZE_PX,
                                minimumSize * DECLARATIVE_LAYOUT_RETRY_SCALE
                            )
                        },
                        lastAttemptedTextSizePx = paint.textSize,
                        maximumLines = maximumLines,
                        requireAllSlots = preserveFlowShape,
                        allowMore = region.smartAssistDisplayHints?.allowMore == true,
                        attemptedLineSpacingMultipliers = listOf(1f)
                    )
                )
                Log.w(
                    TAG,
                    "Restoring source because translated text does not fit " +
                        "id=${region.groupId ?: "unknown"}, chars=${region.translation.length}, " +
                        "slots=${renderSlots.size}, maxLines=$maximumLines, " +
                        "minimumScale=${region.smartAssistDisplayHints?.minimumTextScale ?: 0f}"
                )
                renderSlots.forEach { sourceBounds ->
                    sourceBounds.clampedTo(bitmap)?.let { visible ->
                        canvas.drawBitmap(styleSourceBitmap, visible, visible, null)
                    }
                }
                return@forEach
            }
            if (overlayBackgroundColor != null && drawOverlayBackground) {
                val usedRenderSlots = shapedLayout.segments.map { segment -> segment.bounds }
                val backgroundSlots = (sourceCoverSlots + usedRenderSlots).distinct()
                backgroundSlots.forEach { slot ->
                    drawCompensatedBackground(
                        canvas,
                        bitmap,
                        styleSourceBitmap,
                        slot,
                        overlayBackgroundColor,
                        overlayAlpha,
                        slot
                    )
                }
            }
            val evidenceBackground = overlayBackgroundColor ?: if (style.isDarkBackground) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            evidenceSink?.invoke(
                LiveRenderedTextEvidence(
                    bounds = Rect(bounds),
                    textSizePx = shapedLayout.textSizePx,
                    sourceLineHeightPx = sourceLineHeight,
                    textScale = shapedLayout.textSizePx / sourceLineHeight.coerceAtLeast(1f),
                    lineSpacingMultiplier = shapedLayout.lineSpacingMultiplier,
                    lineCount = shapedLayout.segments.sumOf { it.layout.lineCount },
                    usedRenderSlotCount = shapedLayout.segments.size,
                    layoutHeightPx = shapedLayout.segments.sumOf { it.layout.height },
                    availableHeightPx = shapedLayout.segments.sumOf { it.bounds.height() },
                    clipped = false,
                    contrastRatio = contrastRatio(style.foregroundColor, evidenceBackground)
                )
            )
            paint.textSize = shapedLayout.textSizePx
            shapedLayout.segments.forEach { segment ->
                canvas.save()
                canvas.clipRect(segment.bounds)
                canvas.translate(
                    segment.bounds.left + segment.horizontalPadding.toFloat(),
                    segment.bounds.top + resolvedVerticalTextOffset(
                        availableHeightPx = segment.bounds.height(),
                        layoutHeightPx = segment.layout.height,
                        verticalAlignment = region.smartAssistDisplayHints?.verticalAlignment
                            ?: "AUTO",
                        role = region.smartAssistDisplayHints?.role,
                        sourceLineCount = sourceLineCount,
                        sourceLineHeightPx = sourceLineHeight,
                        sourceGlyphHeightPx = style.sourceGlyphHeightPx
                    )
                )
                segment.layout.draw(canvas)
                canvas.restore()
            }
            renderedRegions.add(Rect(bounds))
        }
            return renderedRegions
        }

    private fun drawCompensatedBackground(
        canvas: Canvas,
        bitmap: Bitmap,
        sourceBitmap: Bitmap,
        textBounds: Rect,
        themeColor: Int,
        overlayAlpha: Float,
        overrideBounds: Rect?
    ) {
        val materialBounds = overrideBounds?.clampedTo(bitmap)
            ?: overlayMaterialBounds(textBounds, bitmap)
            ?: return
        val alpha = overlayAlpha.coerceIn(0.01f, 1f)
        val sourceMultiplier = -(1f - alpha) / alpha
        val compensationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        sourceMultiplier, 0f, 0f, 0f, Color.red(themeColor) / alpha,
                        0f, sourceMultiplier, 0f, 0f, Color.green(themeColor) / alpha,
                        0f, 0f, sourceMultiplier, 0f, Color.blue(themeColor) / alpha,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        canvas.save()
        canvas.clipRect(materialBounds)
        canvas.drawBitmap(sourceBitmap, 0f, 0f, compensationPaint)
        canvas.restore()
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

    private fun fittingLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        height: Int,
        alignment: Layout.Alignment,
        style: BackgroundTextStyle,
        smartAssistHints: SmartAssistDisplayHints? = null
    ): StaticLayout {
        val sourceLineHeight = height.toFloat() / style.sourceLineCount.coerceAtLeast(1)
        if (smartAssistHints != null) {
            val minimumScale = smartAssistHints.minimumTextScale.coerceIn(0.5f, 1f)
            val constrainedLow = maxOf(MINIMUM_TEXT_SIZE_PX, sourceLineHeight * minimumScale)
            val constrainedHigh = maxOf(
                constrainedLow,
                sourceLineHeight * style.fontSizeMultiplier
            )
            for (step in LAYOUT_SEARCH_STEPS downTo 0) {
                val size = constrainedLow +
                    (constrainedHigh - constrainedLow) * step / LAYOUT_SEARCH_STEPS
                val candidate = createLayout(
                    text,
                    paint,
                    width,
                    size,
                    alignment,
                    style.lineSpacingMultiplier
                )
                if (candidate.height <= height &&
                    candidate.lineCount <= smartAssistHints.preferredMaxLines &&
                    !hasOrphanedLastLine(candidate, text)
                ) {
                    return candidate
                }
            }
        }
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
        sourceText: String,
        textSampleBounds: List<Rect>
    ): BackgroundTextStyle {
        val bounds = sourceBounds.clampedTo(bitmap)
            ?: return defaultStyle(isDarkBackground = false, sourceText = sourceText)
        val samples = textSampleBounds.mapNotNull { it.clampedTo(bitmap) }
            .filter { it.width() > 0 && it.height() > 0 }
            .ifEmpty { listOf(bounds) }
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
        val styleSampleStep = sqrt(
            bounds.width().toLong() * bounds.height() / TARGET_TEXT_STYLE_SAMPLES.toDouble()
        ).toInt().coerceAtLeast(1)
        for (y in outer.top until outer.bottom step styleSampleStep) {
            for (x in outer.left until outer.right step styleSampleStep) {
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
        samples.forEach { sample ->
            for (y in sample.top until sample.bottom step styleSampleStep) {
                for (x in sample.left until sample.right step styleSampleStep) {
                    val color = bitmap.getPixel(x, y)
                    maximumDistance = maxOf(
                        maximumDistance,
                        colorDistanceSquared(color, red, green, blue)
                    )
                }
            }
        }
        val foregroundThreshold = maxOf(1_600, (maximumDistance * 0.45f).toInt())
        val strokeThreshold = maxOf(900, (maximumDistance * 0.12f).toInt())
        var foregroundRed = 0L
        var foregroundGreen = 0L
        var foregroundBlue = 0L
        var foregroundSamples = 0
        var strokePixels = 0
        var sampledTextPixels = 0
        val glyphHeights = mutableListOf<Int>()
        samples.forEach { sample ->
            var glyphTop = sample.bottom
            var glyphBottom = sample.top
            for (y in sample.top until sample.bottom step styleSampleStep) {
                for (x in sample.left until sample.right step styleSampleStep) {
                    val color = bitmap.getPixel(x, y)
                    val distance = colorDistanceSquared(color, red, green, blue)
                    if (distance >= strokeThreshold) strokePixels++
                    sampledTextPixels++
                    if (distance >= foregroundThreshold) {
                        foregroundRed += Color.red(color)
                        foregroundGreen += Color.green(color)
                        foregroundBlue += Color.blue(color)
                        foregroundSamples++
                        glyphTop = minOf(glyphTop, y)
                        glyphBottom = maxOf(glyphBottom, y + styleSampleStep)
                    }
                }
            }
            if (glyphBottom > glyphTop) glyphHeights += glyphBottom - glyphTop
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
        val isBold = strokePixels.toFloat() / sampledTextPixels.coerceAtLeast(1) >=
            BOLD_STROKE_COVERAGE
        val sourceLineCount = sourceText.lineSequence().count().coerceAtLeast(1)
        val isCode = looksLikeCode(sourceText)
        val baseTypeface = if (isCode) {
            Typeface.MONOSPACE
        } else {
            Typeface.SANS_SERIF
        }
        val lineHeights = samples.map(Rect::height).sorted()
        val lineHeight = lineHeights[lineHeights.size / 2].toFloat()
        val glyphHeight = glyphHeights.sorted().let { values ->
            values.getOrNull(values.size / 2)?.toFloat() ?: lineHeight * 0.72f
        }
        val strokeCoverage = strokePixels.toFloat() / sampledTextPixels.coerceAtLeast(1)
        val foregroundCoverage = foregroundSamples.toFloat() /
            sampledTextPixels.coerceAtLeast(1)
        val styleHint = SourceTextStyleHint(
            glyphHeightPx = glyphHeight,
            lineHeightPx = lineHeight,
            strokeWidthPx = (glyphHeight * strokeCoverage * 0.25f)
                .coerceIn(1f, maxOf(1f, glyphHeight / 4f)),
            weight = if (isBold) 700 else 400,
            familyClass = if (isCode) "MONO" else "SANS",
            letterSpacingPx = 0f,
            alignment = "START",
            foregroundColor = foreground,
            backgroundColor = Color.rgb(red, green, blue),
            confidence = (foregroundCoverage * 4f).coerceIn(0.2f, 1f)
        )
        return BackgroundTextStyle(
            foregroundColor = styleHint.foregroundColor,
            isDarkBackground = backgroundLuminance < DARK_BACKGROUND_LUMINANCE,
            typeface = Typeface.create(
                baseTypeface,
                if (styleHint.weight >= 600) Typeface.BOLD else Typeface.NORMAL
            ),
            fontSizeMultiplier = 1f,
            lineSpacingMultiplier = if (isBold) 1.02f else 1.08f,
            sourceLineCount = sourceLineCount,
            sourceGlyphHeightPx = glyphHeight
        )
    }

    private fun defaultStyle(
        isDarkBackground: Boolean,
        sourceText: String
    ) = BackgroundTextStyle(
        foregroundColor = if (isDarkBackground) Color.WHITE else Color.BLACK,
        isDarkBackground = isDarkBackground,
        typeface = if (looksLikeCode(sourceText)) Typeface.MONOSPACE else Typeface.SANS_SERIF,
        fontSizeMultiplier = 1f,
        lineSpacingMultiplier = 1.06f,
        sourceLineCount = sourceText.lineSequence().count().coerceAtLeast(1),
        sourceGlyphHeightPx = 0f
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

    private fun contrastRatio(first: Int, second: Int): Float {
        val lighter = maxOf(relativeLuminance(first), relativeLuminance(second))
        val darker = minOf(relativeLuminance(first), relativeLuminance(second))
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun relativeLuminance(color: Int): Float {
        fun linear(channel: Int): Float {
            val value = channel / 255f
            return if (value <= 0.04045f) {
                value / 12.92f
            } else {
                ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
            }
        }
        return linear(Color.red(color)) * 0.2126f +
            linear(Color.green(color)) * 0.7152f +
            linear(Color.blue(color)) * 0.0722f
    }

    private const val MINIMUM_TEXT_SIZE_PX = 8f
    private const val MINIMUM_CHARACTERS_PER_FLOW_SLOT = 2
    private const val MINIMUM_FONT_HEIGHT_RATIO = 0.62f
    private const val DECLARATIVE_LAYOUT_RETRY_SCALE = 0.82f
    private const val RECT_FALLBACK_MINIMUM_SOURCE_LINES = 2
    private const val RECT_FALLBACK_MINIMUM_TEXT_SCALE = 0.72f
    private const val COMPACT_RECT_MINIMUM_TEXT_SCALE = 0.60f
    private const val COMPACT_TITLE_MINIMUM_TEXT_SCALE = 0.35f
    private const val COMPACT_RECT_MAXIMUM_CHARACTERS = 20
    private const val RECT_FALLBACK_ADDITIONAL_LINES = 6
    private const val FLOW_UNION_ADDITIONAL_LINES = 2
    private const val FORCED_RECT_MAXIMUM_LINES = 100
    private const val MINIMUM_LEADING_SLOT_RATIO = 0.72f
    private const val LAYOUT_SEARCH_STEPS = 16
    private const val DARK_BACKGROUND_LUMINANCE = 145
    private const val MINIMUM_CONTRAST_DELTA = 90
    private const val BOLD_STROKE_COVERAGE = 0.3f
    private const val SHORT_TEXT_LIMIT = 20
    private const val ORPHANED_LINE_CHARACTER_LIMIT = 1
    private const val TARGET_TEXT_STYLE_SAMPLES = 6_000
    private const val OVERLAY_MINIMUM_PADDING_PX = 3
    private const val OVERLAY_MAXIMUM_PADDING_PX = 5
}
