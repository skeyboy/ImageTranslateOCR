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
import com.example.imagetranslate.App
import com.example.imagetranslate.inpaint.ImageInpainter
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.OcrModel
import com.example.imagetranslate.ocr.OcrModelState
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import com.example.imagetranslate.translate.TranslateManager
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.sqrt

internal data class BackgroundTranslatedImageResult(
    val bitmap: Bitmap,
    val recognizedCount: Int,
    val replacedCount: Int,
    val failedCount: Int,
    val renderedRegions: List<Rect> = emptyList()
)

internal data class BackgroundTranslatedOverlayResult(
    val patches: List<ScreenTranslationPatch>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val recognizedCount: Int,
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
        LiveDifferentialContextProfile.BALANCED,
    val renderingMode: LivePatchRenderingMode = LivePatchRenderingMode.SEQUENTIAL,
    val differentialFallbackReason: String? = null,
    val recognitionAndTranslationMs: Long = 0L,
    val renderingMs: Long = 0L
) {
    fun metrics(): LiveRecognitionRunMetrics = LiveRecognitionRunMetrics(
        requestedSegmentation = requestedSegmentation,
        appliedStrategy = appliedStrategy,
        recognizedCount = recognizedCount,
        translatedRegionCount = translatedRegionCount,
        patchCount = patches.size,
        failedCount = failedCount,
        reusedRegionCount = reusedRegionCount,
        recognitionRegionCount = recognitionRegionCount,
        recognitionAreaRatio = recognitionAreaRatio,
        dirtyCellCount = dirtyCellCount,
        boundaryTrackCount = boundaryTrackCount,
        restoredBoundaryTrackCount = restoredBoundaryTrackCount,
        differentialFallbackReason = differentialFallbackReason,
        contextProfile = contextProfile,
        renderingMode = renderingMode,
        sourceCoverage = sourceCoverage,
        patchCoverage = patchCoverage,
        recognitionAndTranslationMs = recognitionAndTranslationMs,
        renderingMs = renderingMs
    )
}

private data class BackgroundImageRegion(
    val source: RecognizedText,
    val translation: String,
    val trackId: Long? = null
)

private data class BackgroundTranslationBatch(
    val recognizedCount: Int,
    val regions: List<BackgroundImageRegion>,
    val failedCount: Int
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
    val failed: Boolean = false
)

private data class BackgroundTextStyle(
    val foregroundColor: Int,
    val isDarkBackground: Boolean,
    val typeface: Typeface,
    val fontSizeMultiplier: Float,
    val lineSpacingMultiplier: Float,
    val sourceLineCount: Int
)

internal class BackgroundTranslatedImageProcessor(
    context: Context,
    private val reuseResources: Boolean = false
) {
    private val ocrManager = OCRManager(context.applicationContext)
    private val translateManager = TranslateManager()
    private val translationCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, String>?
        ): Boolean = size > MAX_TRANSLATION_CACHE_ENTRIES
    }
    private var closed = false
    private var liveOverlaySnapshot: LiveOverlaySnapshot? = null
    private var nextTrackId = 1L
    private var lastDifferentialFallbackReason: String? = null

    suspend fun prepareForLiveTranslation() {
        check(!closed) { "Image processor is closed" }
        translateManager.downloadModelIfNeeded()
    }

    suspend fun prepareOcrModels(
        recognitionMode: OcrRecognitionMode,
        onState: (OcrModel, OcrModelState) -> Unit = { _, _ -> }
    ) {
        check(!closed) { "Image processor is closed" }
        ocrManager.ensureModels(recognitionMode.requiredModels, onState)
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

            val inpaintResult = ImageInpainter().eraseWithPreciseMask(
                bitmap,
                batch.regions.map { it.source.bounds }
            )
            val output = inpaintResult.bitmap
            val erasedBounds = inpaintResult.erasedRegions.toSet()
            try {
                val renderedRegions = BackgroundTranslatedImageRenderer.render(
                    output,
                    batch.regions.filter { it.source.bounds in erasedBounds },
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
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO,
        capturePlan: ScrollCapturePlan? = null,
        overlayAlpha: Float = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA,
        segmentation: LiveRecognitionSegmentation = LiveRecognitionSegmentation.ADAPTIVE,
        executionProfile: LiveRecognitionExecutionProfile = LiveRecognitionExecutionProfile.CURRENT
    ): BackgroundTranslatedOverlayResult {
        check(!closed) { "Image processor is closed" }
        return try {
            lastDifferentialFallbackReason = null
            val recognitionStartedAt = SystemClock.elapsedRealtime()
            val differential = if (segmentation == LiveRecognitionSegmentation.ADAPTIVE) {
                capturePlan?.let { plan ->
                    recognizeDifferentialViewport(
                        bitmap,
                        mode,
                        recognitionMode,
                        plan,
                        executionProfile.contextProfile
                    )
                }
            } else {
                null
            }
            val batch = differential?.batch
                ?: when (segmentation) {
                    LiveRecognitionSegmentation.VERTICAL_BANDS ->
                        recognizeVerticalBandsAndTranslate(bitmap, mode, recognitionMode)
                    LiveRecognitionSegmentation.ADAPTIVE,
                    LiveRecognitionSegmentation.FULL_FRAME ->
                        recognizeAndTranslate(
                            bitmap,
                            mode,
                            fastOcr = true,
                            recognitionMode = recognitionMode
                        )
                }
            val recognitionAndTranslationMs = SystemClock.elapsedRealtime() - recognitionStartedAt
            val renderingStartedAt = SystemClock.elapsedRealtime()
            val fallbackSurface = ScreenThemeColorEstimator.compositableSurface(
                ScreenThemeColorEstimator.estimate(bitmap),
                overlayAlpha
            )
            val renderedPatches = renderOverlayPatches(
                bitmap = bitmap,
                regions = batch.regions,
                fallbackSurface = fallbackSurface,
                overlayAlpha = overlayAlpha,
                renderingMode = executionProfile.renderingMode
            )
            val patches = mergeOverlappingPatches(renderedPatches)
            val translatedBounds = batch.regions.map { region ->
                region.source.bounds.toCoverageBounds()
            }
            BackgroundTranslatedOverlayResult(
                patches = patches,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                recognizedCount = batch.recognizedCount,
                translatedRegionCount = batch.regions.size,
                failedCount = batch.failedCount + (batch.regions.size - renderedPatches.size),
                requestedSegmentation = segmentation,
                appliedStrategy = when {
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
                differentialFallbackReason = if (
                    segmentation == LiveRecognitionSegmentation.ADAPTIVE &&
                    capturePlan != null && differential == null
                ) {
                    lastDifferentialFallbackReason ?: "UNKNOWN"
                } else {
                    null
                },
                recognitionAndTranslationMs = recognitionAndTranslationMs,
                renderingMs = SystemClock.elapsedRealtime() - renderingStartedAt
            ).also { result ->
                if (LiveCaptureTimingPolicy.shouldUpdateLiveSnapshot(
                        patchCount = result.patches.size,
                        translatedRegionCount = batch.regions.size
                    )
                ) {
                    updateLiveOverlaySnapshot(bitmap, mode, recognitionMode, batch.regions)
                }
            }
        } finally {
            if (!reuseResources) close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        ocrManager.close()
        translateManager.close()
        synchronized(translationCache) { translationCache.clear() }
        liveOverlaySnapshot = null
    }

    private suspend fun recognizeAndTranslate(
        bitmap: Bitmap,
        mode: TranslationMode,
        fastOcr: Boolean,
        recognitionMode: OcrRecognitionMode = OcrRecognitionMode.AUTO,
        recognitionBounds: Rect? = null,
        preferredRecognitionMode: OcrRecognitionMode? = null
    ): BackgroundTranslationBatch {
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
        val rawRecognized = try {
            val localRecognized = if (fastOcr) {
                ocrManager.recognizeFast(ocrBitmap, preferredRecognitionMode ?: recognitionMode)
            } else {
                ocrManager.recognize(ocrBitmap, recognitionMode)
            }
            localRecognized.map { item ->
                val sourceBounds = if (inputSize != sourceSize) {
                    Rect(
                        LiveOcrScalePolicy.mapX(item.bounds.left, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapY(item.bounds.top, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapX(item.bounds.right, inputSize, sourceSize),
                        LiveOcrScalePolicy.mapY(item.bounds.bottom, inputSize, sourceSize)
                    )
                } else {
                    Rect(item.bounds)
                }
                recognitionBounds?.let { bounds -> sourceBounds.offset(bounds.left, bounds.top) }
                item.copy(bounds = sourceBounds)
            }
        } finally {
            if (ocrBitmap !== croppedBitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
            if (croppedBitmap !== bitmap && !croppedBitmap.isRecycled) croppedBitmap.recycle()
        }
        val recognized = if (fastOcr) {
            groupLiveTextBlocks(rawRecognized, bitmap.width, bitmap.height)
        } else {
            rawRecognized
        }
        val maximumTexts = if (fastOcr) {
            MAX_LIVE_TRANSLATION_TEXTS
        } else {
            MAX_IMAGE_TRANSLATION_TEXTS
        }
        val translationSemaphore = Semaphore(
            if (fastOcr) MAXIMUM_CONCURRENT_LIVE_TRANSLATIONS else MAXIMUM_CONCURRENT_TRANSLATIONS
        )
        val outcomes = coroutineScope {
            recognized.take(maximumTexts).map { source ->
                async {
                    translationSemaphore.withPermit {
                        translateRegion(source, mode)
                    }
                }
            }.awaitAll()
        }
        return BackgroundTranslationBatch(
            recognizedCount = rawRecognized.size,
            regions = outcomes.mapNotNull(TranslationOutcome::region),
            failedCount = outcomes.count(TranslationOutcome::failed)
        )
    }

    private suspend fun translateRegion(
        source: RecognizedText,
        mode: TranslationMode
    ): TranslationOutcome {
        val translationSource = normalizeCacheText(source.text)
        val translation = try {
            val cacheKey = "${mode.name}:$translationSource"
            synchronized(translationCache) { translationCache[cacheKey] }
                ?: translateManager.translate(translationSource, mode).trim().also { translatedText ->
                    synchronized(translationCache) {
                        translationCache[cacheKey] = translatedText
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return TranslationOutcome(failed = true)
        }
        val region = if (translation.isNotEmpty() && translation != translationSource) {
            BackgroundImageRegion(source, translation)
        } else {
            null
        }
        return TranslationOutcome(region = region)
    }

    private suspend fun recognizeVerticalBandsAndTranslate(
        bitmap: Bitmap,
        mode: TranslationMode,
        recognitionMode: OcrRecognitionMode
    ): BackgroundTranslationBatch {
        val batches = LiveCaptureSettingsPolicy.verticalBands(bitmap.width, bitmap.height).map { band ->
            recognizeAndTranslate(
                bitmap = bitmap,
                mode = mode,
                fastOcr = true,
                recognitionMode = recognitionMode,
                recognitionBounds = Rect(band.left, band.top, band.right, band.bottom)
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
            regions = mergedRegions.take(MAX_LIVE_TRANSLATION_TEXTS),
            failedCount = batches.sumOf(BackgroundTranslationBatch::failedCount)
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
        contextProfile: LiveDifferentialContextProfile
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
        val contentTop = (bitmap.height * LIVE_CONTENT_TOP_RATIO).toInt()
        val contentBottom = (bitmap.height * LIVE_CONTENT_BOTTOM_RATIO).toInt()
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
            cached.region.copy(
                source = cached.region.source.copy(bounds = matchedBounds)
            )
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
                        )
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
                regions = combined.sortedWith(
                    compareBy({ it.source.bounds.top }, { it.source.bounds.left })
                ).take(MAX_LIVE_TRANSLATION_TEXTS),
                failedCount = recognitionBatches.sumOf(BackgroundTranslationBatch::failedCount)
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

    private suspend fun renderOverlayPatches(
        bitmap: Bitmap,
        regions: List<BackgroundImageRegion>,
        fallbackSurface: Int,
        overlayAlpha: Float,
        renderingMode: LivePatchRenderingMode
    ): List<ScreenTranslationPatch> = when (renderingMode) {
        LivePatchRenderingMode.SEQUENTIAL -> regions.mapNotNull { region ->
            createOverlayPatch(bitmap, region, fallbackSurface, overlayAlpha)
        }
        LivePatchRenderingMode.PARALLEL -> coroutineScope {
            regions.map { region ->
                async(Dispatchers.Default) {
                    createOverlayPatch(bitmap, region, fallbackSurface, overlayAlpha)
                }
            }.awaitAll().filterNotNull()
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
        fallbackSurface: Int,
        overlayAlpha: Float
    ): ScreenTranslationPatch? {
        val sourceBounds = region.source.bounds.clampedTo(bitmap) ?: return null
        val material = LiveOverlayLayoutPolicy.translationMaterialBounds(
            textBounds = LivePatchBounds(
                index = 0,
                left = sourceBounds.left,
                top = sourceBounds.top,
                right = sourceBounds.right,
                bottom = sourceBounds.bottom
            ),
            sourceText = region.source.text,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height
        )
        val materialBounds = Rect(material.left, material.top, material.right, material.bottom)
        val localSurface = estimateLocalSurface(
            bitmap,
            materialBounds,
            fallbackSurface,
            overlayAlpha
        )
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
        val localBounds = Rect(
            sourceBounds.left - cropBounds.left,
            sourceBounds.top - cropBounds.top,
            sourceBounds.right - cropBounds.left,
            sourceBounds.bottom - cropBounds.top
        )
        val localMaterialBounds = Rect(0, 0, cropBounds.width(), cropBounds.height())
        var output: Bitmap? = null
        return try {
            val patchBitmap = Bitmap.createBitmap(
                cropBounds.width(),
                cropBounds.height(),
                Bitmap.Config.ARGB_8888
            )
            output = patchBitmap
            val localRegion = region.copy(
                source = region.source.copy(bounds = localBounds)
            )
            val rendered = BackgroundTranslatedImageRenderer.render(
                patchBitmap,
                listOf(localRegion),
                styleSourceBitmap = crop,
                overlayBackgroundColor = localSurface,
                overlayAlpha = overlayAlpha,
                overlayMaterialBounds = localMaterialBounds
            )
            if (rendered.isEmpty()) {
                patchBitmap.recycle()
                output = null
                null
            } else {
                ScreenTranslationPatch(Rect(cropBounds), patchBitmap).also { output = null }
            }
        } catch (_: Exception) {
            output?.takeIf { !it.isRecycled }?.recycle()
            null
        } finally {
            if (crop !== bitmap && !crop.isRecycled) crop.recycle()
        }
    }

    private fun groupLiveTextBlocks(
        recognized: List<RecognizedText>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<RecognizedText> {
        val contentTop = (sourceHeight * LIVE_CONTENT_TOP_RATIO).toInt()
        val contentBottom = (sourceHeight * LIVE_CONTENT_BOTTOM_RATIO).toInt()
        val contentLines = recognized.filter { item ->
            val centerY = item.bounds.centerY()
            centerY in contentTop until contentBottom
        }
        val lineBounds = contentLines.mapIndexed { index, item ->
                LiveTextLineBounds(
                    index = index,
                    left = item.bounds.left.coerceIn(0, sourceWidth),
                    top = item.bounds.top.coerceIn(0, sourceHeight),
                    right = item.bounds.right.coerceIn(0, sourceWidth),
                    bottom = item.bounds.bottom.coerceIn(0, sourceHeight),
                    text = item.text,
                    quality = item.modelConfidence * 0.45f +
                        item.consensusScore * 0.35f +
                        item.passCount.coerceAtMost(2) * 0.1f
                )
            }
        val distinctLines = LiveOverlayLayoutPolicy.selectDistinctTextLines(lineBounds)
            .map(lineBounds::get)
        val groups = LiveOverlayLayoutPolicy.groupTextLines(distinctLines)
        return groups.mapNotNull { indices ->
            val lines = indices.mapNotNull(contentLines::getOrNull)
            if (lines.isEmpty()) null else mergeLiveTextLines(lines)
        }
    }

    private fun mergeLiveTextLines(lines: List<RecognizedText>): RecognizedText {
        val ordered = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bounds = Rect(ordered.first().bounds)
        ordered.drop(1).forEach { bounds.union(it.bounds) }
        val weights = ordered.map { item ->
            item.text.count { it.isLetterOrDigit() }.coerceAtLeast(1)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        return RecognizedText(
            text = ordered.joinToString("\n") { it.text.trim() },
            bounds = bounds,
            consensusScore = ordered.minOf { it.consensusScore },
            passCount = ordered.minOf { it.passCount },
            modelConfidence = ordered.zip(weights).sumOf { (item, weight) ->
                (item.modelConfidence * weight).toDouble()
            }.toFloat() / totalWeight,
            recognizerScript = ordered.map { it.recognizerScript }.distinct().singleOrNull()
                ?: RecognizerScript.FUSED
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
        val groups = LiveOverlayLayoutPolicy.groupIntersectingPatches(
            patches.mapIndexed { index, patch ->
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
            ScreenTranslationPatch(union, mergedBitmap)
        }
    }

    private companion object {
        const val MAX_IMAGE_TRANSLATION_TEXTS = 24
        const val MAX_LIVE_TRANSLATION_TEXTS = 32
        const val MAX_TRANSLATION_CACHE_ENTRIES = 256
        const val MAXIMUM_CONCURRENT_TRANSLATIONS = 2
        const val MAXIMUM_CONCURRENT_LIVE_TRANSLATIONS = 3
        const val PATCH_WINDOW_MERGE_GAP_PX = 3
        const val LOCAL_SURFACE_MINIMUM_PADDING_PX = 8
        const val LOCAL_SURFACE_MAXIMUM_PADDING_PX = 36
        const val LOCAL_SURFACE_SAMPLE_GRID = 48
        const val LOCAL_SURFACE_MINIMUM_SAMPLES = 16
        const val LIVE_CONTENT_TOP_RATIO = 0.08f
        const val LIVE_CONTENT_BOTTOM_RATIO = 0.94f
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

private object BackgroundTranslatedImageRenderer {
    fun render(
        bitmap: Bitmap,
        regions: List<BackgroundImageRegion>,
        styleSourceBitmap: Bitmap = bitmap,
        overlayBackgroundColor: Int? = null,
        overlayAlpha: Float = ScreenThemeColorEstimator.DEFAULT_OVERLAY_ALPHA,
        overlayMaterialBounds: Rect? = null
    ): List<Rect> {
        val canvas = Canvas(bitmap)
        val renderedRegions = mutableListOf<Rect>()
        regions.forEach { region ->
            val bounds = region.source.bounds.clampedTo(bitmap) ?: return@forEach
            val estimatedStyle = estimateTextStyle(
                styleSourceBitmap,
                bounds,
                region.source.text
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
            if (overlayBackgroundColor != null) {
                drawCompensatedBackground(
                    canvas,
                    bitmap,
                    styleSourceBitmap,
                    bounds,
                    overlayBackgroundColor,
                    overlayAlpha,
                    overlayMaterialBounds
                )
            }
            val isControlLabel = style.isDarkBackground &&
                region.source.text.filterNot(Char::isWhitespace).length <= 20
            val horizontalPadding = if (isControlLabel) 0 else maxOf(2, bounds.height() / 8)
            val layoutWidth = (bounds.width() - horizontalPadding * 2).coerceAtLeast(1)
            val alignment = if (isControlLabel) {
                Layout.Alignment.ALIGN_CENTER
            } else {
                Layout.Alignment.ALIGN_NORMAL
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.foregroundColor
                typeface = style.typeface
            }
            val layout = fittingLayout(
                region.translation,
                paint,
                layoutWidth,
                bounds.height().coerceAtLeast(1),
                alignment,
                style
            )
            canvas.save()
            canvas.clipRect(bounds)
            canvas.translate(
                bounds.left + horizontalPadding.toFloat(),
                bounds.top + ((bounds.height() - layout.height) / 2f).coerceAtLeast(0f)
            )
            layout.draw(canvas)
            canvas.restore()
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
        style: BackgroundTextStyle
    ): StaticLayout {
        val sourceLineHeight = height.toFloat() / style.sourceLineCount.coerceAtLeast(1)
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
        sourceText: String
    ): BackgroundTextStyle {
        val bounds = sourceBounds.clampedTo(bitmap)
            ?: return defaultStyle(isDarkBackground = false, sourceText = sourceText)
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
        for (y in bounds.top until bounds.bottom step styleSampleStep) {
            for (x in bounds.left until bounds.right step styleSampleStep) {
                val color = bitmap.getPixel(x, y)
                maximumDistance = maxOf(
                    maximumDistance,
                    colorDistanceSquared(color, red, green, blue)
                )
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
        for (y in bounds.top until bounds.bottom step styleSampleStep) {
            for (x in bounds.left until bounds.right step styleSampleStep) {
                val color = bitmap.getPixel(x, y)
                val distance = colorDistanceSquared(color, red, green, blue)
                if (distance >= strokeThreshold) strokePixels++
                sampledTextPixels++
                if (distance >= foregroundThreshold) {
                    foregroundRed += Color.red(color)
                    foregroundGreen += Color.green(color)
                    foregroundBlue += Color.blue(color)
                    foregroundSamples++
                }
            }
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
        val baseTypeface = if (looksLikeCode(sourceText)) {
            Typeface.MONOSPACE
        } else {
            Typeface.SANS_SERIF
        }
        return BackgroundTextStyle(
            foregroundColor = foreground,
            isDarkBackground = backgroundLuminance < DARK_BACKGROUND_LUMINANCE,
            typeface = Typeface.create(
                baseTypeface,
                if (isBold) Typeface.BOLD else Typeface.NORMAL
            ),
            fontSizeMultiplier = if (isBold) 1.05f else 1.12f,
            lineSpacingMultiplier = if (isBold) 1.02f else 1.08f,
            sourceLineCount = sourceLineCount
        )
    }

    private fun defaultStyle(
        isDarkBackground: Boolean,
        sourceText: String
    ) = BackgroundTextStyle(
        foregroundColor = if (isDarkBackground) Color.WHITE else Color.BLACK,
        isDarkBackground = isDarkBackground,
        typeface = if (looksLikeCode(sourceText)) Typeface.MONOSPACE else Typeface.SANS_SERIF,
        fontSizeMultiplier = 1.1f,
        lineSpacingMultiplier = 1.06f,
        sourceLineCount = sourceText.lineSequence().count().coerceAtLeast(1)
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

    private const val MINIMUM_TEXT_SIZE_PX = 8f
    private const val MINIMUM_FONT_HEIGHT_RATIO = 0.62f
    private const val DARK_BACKGROUND_LUMINANCE = 145
    private const val MINIMUM_CONTRAST_DELTA = 90
    private const val BOLD_STROKE_COVERAGE = 0.3f
    private const val SHORT_TEXT_LIMIT = 20
    private const val ORPHANED_LINE_CHARACTER_LIMIT = 1
    private const val TARGET_TEXT_STYLE_SAMPLES = 6_000
    private const val OVERLAY_MINIMUM_PADDING_PX = 3
    private const val OVERLAY_MAXIMUM_PADDING_PX = 5
}
