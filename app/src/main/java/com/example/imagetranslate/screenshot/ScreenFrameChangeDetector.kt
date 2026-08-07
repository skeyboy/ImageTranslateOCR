package com.example.imagetranslate.screenshot

import kotlin.math.abs

internal data class ScreenFrameSignature(
    val samples: IntArray,
    val columns: Int = samples.size,
    val rows: Int = 1,
    val sampleTopPx: Int = 0,
    val sampleBottomPx: Int = rows,
    val ignoredSamples: BooleanArray? = null
)

internal enum class ScreenFrameAction {
    NONE,
    MOVING,
    MOVING_UPDATE,
    RESTORE,
    CAPTURE
}

internal object ScreenFrameSignaturePolicy {
    fun differenceRatio(
        first: ScreenFrameSignature,
        second: ScreenFrameSignature,
        luminanceDelta: Int
    ): Float {
        if (first.samples.isEmpty() || first.samples.size != second.samples.size) return 1f
        var changed = 0
        var compared = 0
        first.samples.indices.forEach { index ->
            if (first.ignoredSamples.isIgnored(index) || second.ignoredSamples.isIgnored(index)) {
                return@forEach
            }
            compared++
            if (abs(first.samples[index] - second.samples[index]) >= luminanceDelta) changed++
        }
        return if (compared == 0) 1f else changed.toFloat() / compared
    }

    fun isDuplicateCapture(
        first: ScreenFrameSignature,
        second: ScreenFrameSignature
    ): Boolean = differenceRatio(first, second, DUPLICATE_LUMINANCE_DELTA) <
        DUPLICATE_CHANGED_SAMPLE_RATIO

    fun hasViewportChanged(
        captured: ScreenFrameSignature,
        latest: ScreenFrameSignature
    ): Boolean = differenceRatio(captured, latest, VIEWPORT_LUMINANCE_DELTA) >=
        VIEWPORT_CHANGED_SAMPLE_RATIO

    private const val DUPLICATE_LUMINANCE_DELTA = 10
    private const val DUPLICATE_CHANGED_SAMPLE_RATIO = 0.012f
    private const val VIEWPORT_LUMINANCE_DELTA = 18
    private const val VIEWPORT_CHANGED_SAMPLE_RATIO = 0.045f

    private fun BooleanArray?.isIgnored(index: Int): Boolean =
        this != null && index < size && this[index]
}

internal class InitialViewportStabilityGate(
    private val minimumSessionAgeMs: Long = DEFAULT_MINIMUM_SESSION_AGE_MS,
    private val stableDurationMs: Long = DEFAULT_STABLE_DURATION_MS,
    private val minimumStableSamples: Int = DEFAULT_MINIMUM_STABLE_SAMPLES,
    private val maximumWaitMs: Long = DEFAULT_MAXIMUM_WAIT_MS,
    private val changedSampleRatio: Float = DEFAULT_CHANGED_SAMPLE_RATIO,
    private val luminanceDelta: Int = DEFAULT_LUMINANCE_DELTA
) {
    private var startedAt = Long.MIN_VALUE
    private var lastChangedAt = Long.MIN_VALUE
    private var previous: ScreenFrameSignature? = null
    private var stableSamples = 0

    fun reset(nowMs: Long) {
        startedAt = nowMs
        lastChangedAt = nowMs
        previous = null
        stableSamples = 0
    }

    fun onFrame(signature: ScreenFrameSignature, nowMs: Long): Boolean {
        if (startedAt == Long.MIN_VALUE) reset(nowMs)
        if (nowMs - startedAt >= maximumWaitMs) {
            previous = signature
            stableSamples++
            return true
        }
        val prior = previous
        previous = signature
        if (prior == null || prior.samples.size != signature.samples.size) {
            lastChangedAt = nowMs
            stableSamples = 1
            return false
        }
        val changed = ScreenFrameSignaturePolicy.differenceRatio(
            prior,
            signature,
            luminanceDelta
        ) >= changedSampleRatio
        if (changed) {
            lastChangedAt = nowMs
            stableSamples = 1
            return false
        }
        stableSamples++
        return nowMs - startedAt >= minimumSessionAgeMs &&
            nowMs - lastChangedAt >= stableDurationMs &&
            stableSamples >= minimumStableSamples
    }

    private companion object {
        const val DEFAULT_MINIMUM_SESSION_AGE_MS = 750L
        const val DEFAULT_STABLE_DURATION_MS = 350L
        const val DEFAULT_MINIMUM_STABLE_SAMPLES = 3
        const val DEFAULT_MAXIMUM_WAIT_MS = 1_800L
        const val DEFAULT_CHANGED_SAMPLE_RATIO = 0.045f
        const val DEFAULT_LUMINANCE_DELTA = 18
    }
}

internal class ScreenFrameChangeDetector(
    private val stableDelayMs: Long = DEFAULT_STABLE_DELAY_MS,
    private val minimumCaptureIntervalMs: Long = DEFAULT_MINIMUM_CAPTURE_INTERVAL_MS,
    private val changedSampleRatio: Float = DEFAULT_CHANGED_SAMPLE_RATIO,
    private val luminanceDelta: Int = DEFAULT_LUMINANCE_DELTA,
    private val slowMovementStableDelayMs: Long = DEFAULT_SLOW_MOVEMENT_STABLE_DELAY_MS,
    private val fastMovementRatio: Float = DEFAULT_FAST_MOVEMENT_RATIO,
    private val minimumStableFrameSamples: Int = DEFAULT_MINIMUM_STABLE_FRAME_SAMPLES
) {
    private var previous: ScreenFrameSignature? = null
    private var dirty = false
    private var movementReported = false
    private var lastMovementAt = 0L
    private var lastCaptureAt = Long.MIN_VALUE
    private var presentationPatchMaskUntil = 0L
    private var peakMovementRatio = 0f
    private var captureBaseline: ScreenFrameSignature? = null
    private var pendingCapturePlan: ScrollCapturePlan? = null
    private var latestMotionPlan: ScrollCapturePlan? = null
    private var pendingSettledDifferenceRatio: Float? = null
    private val stableFrames = ArrayDeque<ScreenFrameSignature>(STABLE_FRAME_BUFFER_CAPACITY)

    fun onFrame(signature: ScreenFrameSignature, nowMs: Long): ScreenFrameAction {
        val prior = previous
        previous = signature
        if (prior == null || prior.samples.size != signature.samples.size) {
            rememberStableFrame(signature, reset = true)
            return ScreenFrameAction.NONE
        }
        val differenceRatio = ScreenFrameSignaturePolicy.differenceRatio(
            prior,
            signature,
            luminanceDelta
        )
        if (differenceRatio >= changedSampleRatio) {
            stableFrames.clear()
            dirty = true
            lastMovementAt = nowMs
            peakMovementRatio = maxOf(peakMovementRatio, differenceRatio)
            latestMotionPlan = if (shouldMaskTranslationPatches(nowMs)) {
                null
            } else {
                captureBaseline?.let { baseline ->
                    ScrollFrameMotionEstimator.estimate(baseline, signature)
                }
            }
            return if (movementReported) {
                ScreenFrameAction.MOVING_UPDATE
            } else {
                movementReported = true
                ScreenFrameAction.MOVING
            }
        }
        rememberStableFrame(signature)

        val captureIntervalSatisfied = lastCaptureAt == Long.MIN_VALUE ||
            nowMs - lastCaptureAt >= minimumCaptureIntervalMs
        val requiredStableDelay = if (peakMovementRatio >= fastMovementRatio) {
            stableDelayMs
        } else {
            minOf(stableDelayMs, slowMovementStableDelayMs)
        }
        if (dirty &&
            nowMs - lastMovementAt >= requiredStableDelay &&
            captureIntervalSatisfied &&
            stableFrames.size >= minimumStableFrameSamples
        ) {
            return settleMovement(nowMs, bufferedCapturePlan())
        }
        return ScreenFrameAction.NONE
    }

    fun onTranslationRendered(nowMs: Long) {
        previous = null
        dirty = false
        movementReported = false
        peakMovementRatio = 0f
        latestMotionPlan = null
        stableFrames.clear()
        lastCaptureAt = nowMs
        presentationPatchMaskUntil = nowMs + POST_RENDER_PATCH_MASK_MS
    }

    fun onCaptureStarted(signature: ScreenFrameSignature, nowMs: Long) {
        previous = signature
        captureBaseline = signature
        dirty = false
        movementReported = false
        peakMovementRatio = 0f
        pendingCapturePlan = null
        latestMotionPlan = null
        pendingSettledDifferenceRatio = null
        rememberStableFrame(signature, reset = true)
        lastMovementAt = nowMs
        lastCaptureAt = nowMs
        presentationPatchMaskUntil = 0L
    }

    fun reset() {
        previous = null
        dirty = false
        movementReported = false
        lastMovementAt = 0L
        lastCaptureAt = Long.MIN_VALUE
        presentationPatchMaskUntil = 0L
        peakMovementRatio = 0f
        captureBaseline = null
        pendingCapturePlan = null
        latestMotionPlan = null
        pendingSettledDifferenceRatio = null
        stableFrames.clear()
    }

    fun consumeCapturePlan(): ScrollCapturePlan? = pendingCapturePlan.also {
        pendingCapturePlan = null
    }

    fun shouldMaskTranslationPatches(nowMs: Long): Boolean =
        nowMs < presentationPatchMaskUntil

    fun isAwaitingStableFrames(): Boolean = dirty

    fun forceActionAfterQuietPeriod(nowMs: Long): ScreenFrameAction =
        if (dirty) settleMovement(nowMs, latestMotionPlan) else ScreenFrameAction.NONE

    fun consumeSettledDifferenceRatio(): Float? = pendingSettledDifferenceRatio.also {
        pendingSettledDifferenceRatio = null
    }

    fun currentMotionPlan(): ScrollCapturePlan? = latestMotionPlan

    private fun rememberStableFrame(signature: ScreenFrameSignature, reset: Boolean = false) {
        if (reset) stableFrames.clear()
        if (stableFrames.size == STABLE_FRAME_BUFFER_CAPACITY) stableFrames.removeFirst()
        stableFrames.addLast(signature)
    }

    private fun bufferedCapturePlan(): ScrollCapturePlan? {
        val baseline = captureBaseline ?: return null
        val plans = stableFrames.mapNotNull { signature ->
            ScrollFrameMotionEstimator.estimate(baseline, signature)
        }
        if (plans.size < MINIMUM_TEMPORAL_PLAN_SAMPLES) return null
        val medianShift = plans.map(ScrollCapturePlan::contentShiftY).sorted()[plans.size / 2]
        val tolerance = maxOf(MINIMUM_TEMPORAL_SHIFT_TOLERANCE_PX, abs(medianShift) / 8)
        val agreeing = plans.filter { abs(it.contentShiftY - medianShift) <= tolerance }
        if (agreeing.size < MINIMUM_TEMPORAL_PLAN_SAMPLES) return null
        return agreeing.last()
    }

    private fun settleMovement(
        nowMs: Long,
        capturePlan: ScrollCapturePlan?
    ): ScreenFrameAction {
        val baseline = captureBaseline
        val settled = stableFrames.lastOrNull() ?: previous
        val baselineDifference = if (baseline != null && settled != null) {
            ScreenFrameSignaturePolicy.differenceRatio(baseline, settled, luminanceDelta)
        } else {
            null
        }
        pendingSettledDifferenceRatio = baselineDifference
        dirty = false
        movementReported = false
        peakMovementRatio = 0f
        lastCaptureAt = nowMs

        if (baselineDifference != null &&
            baselineDifference < changedSampleRatio * RETURN_TO_BASELINE_RATIO_FACTOR
        ) {
            pendingCapturePlan = null
            latestMotionPlan = null
            stableFrames.clear()
            return ScreenFrameAction.RESTORE
        }

        pendingCapturePlan = capturePlan
        return ScreenFrameAction.CAPTURE
    }

    private companion object {
        const val DEFAULT_STABLE_DELAY_MS = 520L
        const val DEFAULT_SLOW_MOVEMENT_STABLE_DELAY_MS = 420L
        const val DEFAULT_MINIMUM_CAPTURE_INTERVAL_MS = 900L
        const val DEFAULT_CHANGED_SAMPLE_RATIO = 0.055f
        const val DEFAULT_LUMINANCE_DELTA = 20
        const val DEFAULT_FAST_MOVEMENT_RATIO = 0.28f
        const val POST_RENDER_PATCH_MASK_MS = 420L
        const val STABLE_FRAME_BUFFER_CAPACITY = 5
        const val DEFAULT_MINIMUM_STABLE_FRAME_SAMPLES = 4
        const val RETURN_TO_BASELINE_RATIO_FACTOR = 0.5f
        const val MINIMUM_TEMPORAL_PLAN_SAMPLES = 2
        const val MINIMUM_TEMPORAL_SHIFT_TOLERANCE_PX = 72
    }
}
