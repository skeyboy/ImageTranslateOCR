package com.example.imagetranslate.screenshot

internal object LiveCaptureTimingPolicy {
    const val TRANSLATION_TIMEOUT_MS = 35_000L
    const val PRESENTATION_GATE_TIMEOUT_MS = 400L

    fun shouldHoldImageQueue(
        captureInProgress: Boolean,
        captureRequested: Boolean,
        processingFrameCaptured: Boolean,
        presentationInProgress: Boolean
    ): Boolean = presentationInProgress || (
        captureInProgress && !captureRequested && !processingFrameCaptured
    )
}
