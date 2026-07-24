package com.example.imagetranslate.screenshot

internal data class ScreenFrameSignature(
    val samples: IntArray
)

internal enum class ScreenFrameAction {
    NONE,
    MOVING,
    CAPTURE
}

internal class ScreenFrameChangeDetector(
    private val stableDelayMs: Long = DEFAULT_STABLE_DELAY_MS,
    private val minimumCaptureIntervalMs: Long = DEFAULT_MINIMUM_CAPTURE_INTERVAL_MS,
    private val changedSampleRatio: Float = DEFAULT_CHANGED_SAMPLE_RATIO,
    private val luminanceDelta: Int = DEFAULT_LUMINANCE_DELTA
) {
    private var previous: ScreenFrameSignature? = null
    private var dirty = false
    private var movementReported = false
    private var lastMovementAt = 0L
    private var lastCaptureAt = Long.MIN_VALUE
    private var ignoreUntil = 0L

    fun onFrame(signature: ScreenFrameSignature, nowMs: Long): ScreenFrameAction {
        val prior = previous
        previous = signature
        if (prior == null || prior.samples.size != signature.samples.size) return ScreenFrameAction.NONE
        if (nowMs < ignoreUntil) return ScreenFrameAction.NONE

        if (differenceRatio(prior, signature) >= changedSampleRatio) {
            dirty = true
            lastMovementAt = nowMs
            return if (movementReported) {
                ScreenFrameAction.NONE
            } else {
                movementReported = true
                ScreenFrameAction.MOVING
            }
        }

        val captureIntervalSatisfied = lastCaptureAt == Long.MIN_VALUE ||
            nowMs - lastCaptureAt >= minimumCaptureIntervalMs
        if (dirty &&
            nowMs - lastMovementAt >= stableDelayMs &&
            captureIntervalSatisfied
        ) {
            dirty = false
            movementReported = false
            lastCaptureAt = nowMs
            return ScreenFrameAction.CAPTURE
        }
        return ScreenFrameAction.NONE
    }

    fun onTranslationRendered(nowMs: Long) {
        previous = null
        dirty = false
        movementReported = false
        lastCaptureAt = nowMs
        ignoreUntil = nowMs + POST_RENDER_IGNORE_MS
    }

    fun onCaptureStarted(signature: ScreenFrameSignature, nowMs: Long) {
        previous = signature
        dirty = false
        movementReported = false
        lastMovementAt = nowMs
        lastCaptureAt = nowMs
        ignoreUntil = 0L
    }

    fun reset() {
        previous = null
        dirty = false
        movementReported = false
        lastMovementAt = 0L
        lastCaptureAt = Long.MIN_VALUE
        ignoreUntil = 0L
    }

    private fun differenceRatio(
        first: ScreenFrameSignature,
        second: ScreenFrameSignature
    ): Float {
        if (first.samples.isEmpty()) return 0f
        var changed = 0
        first.samples.indices.forEach { index ->
            if (kotlin.math.abs(first.samples[index] - second.samples[index]) >= luminanceDelta) {
                changed++
            }
        }
        return changed.toFloat() / first.samples.size
    }

    private companion object {
        const val DEFAULT_STABLE_DELAY_MS = 420L
        const val DEFAULT_MINIMUM_CAPTURE_INTERVAL_MS = 900L
        const val DEFAULT_CHANGED_SAMPLE_RATIO = 0.075f
        const val DEFAULT_LUMINANCE_DELTA = 24
        const val POST_RENDER_IGNORE_MS = 650L
    }
}
