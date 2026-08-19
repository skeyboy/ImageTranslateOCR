package com.example.imagetranslate.screenshot

internal enum class ScreenCaptureSessionState {
    IDLE,
    OVERLAY_ONLY,
    REQUESTING_PERMISSION,
    CAPTURING,
    PROJECTION_REVOKED,
    STOPPING
}

internal enum class ProjectionStopDecision {
    IGNORE_STALE,
    APP_REQUESTED,
    RECOVER_EXTERNAL
}

internal object ScreenCaptureProjectionLifecycle {
    fun stopDecision(
        activeSessionId: Long,
        callbackSessionId: Long,
        projectionMatches: Boolean,
        appRequestedStop: Boolean
    ): ProjectionStopDecision = when {
        activeSessionId != callbackSessionId || !projectionMatches ->
            ProjectionStopDecision.IGNORE_STALE
        appRequestedStop -> ProjectionStopDecision.APP_REQUESTED
        else -> ProjectionStopDecision.RECOVER_EXTERNAL
    }

    fun requiresManualReauthorization(error: Throwable?): Boolean =
        error is CaptureFrameUnavailableException
}

internal class CaptureFrameUnavailableException(message: String) :
    IllegalStateException(message)
