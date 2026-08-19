package com.example.imagetranslate.screenshot

internal data class CaptureFrameHeartbeatOutcome(
    val missedCount: Int,
    val streamInvalid: Boolean
)

internal object CaptureFrameHeartbeatPolicy {
    fun evaluate(
        frameAdvanced: Boolean,
        previousMissedCount: Int,
        maximumMisses: Int
    ): CaptureFrameHeartbeatOutcome {
        require(maximumMisses > 0)
        if (frameAdvanced) return CaptureFrameHeartbeatOutcome(0, streamInvalid = false)
        val missedCount = (previousMissedCount + 1).coerceAtMost(maximumMisses)
        return CaptureFrameHeartbeatOutcome(
            missedCount = missedCount,
            streamInvalid = missedCount >= maximumMisses
        )
    }
}
