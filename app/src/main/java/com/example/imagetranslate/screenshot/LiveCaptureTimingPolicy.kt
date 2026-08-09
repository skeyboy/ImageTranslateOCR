package com.example.imagetranslate.screenshot

import com.example.imagetranslate.translate.TranslationBackend

internal object LiveCaptureTimingPolicy {
    const val LOCAL_TRANSLATION_TIMEOUT_MS = 35_000L
    const val PADDLE_NETWORK_TRANSLATION_TIMEOUT_MS = 40_000L
    const val SELF_HOSTED_TRANSLATION_TIMEOUT_MS = 105_000L
    const val NETWORK_TRANSLATION_TIMEOUT_MS = 90_000L
    const val PRESENTATION_GATE_TIMEOUT_MS = 400L

    fun translationTimeoutMs(
        backend: TranslationBackend,
        engine: LiveOcrTranslationEngineType
    ): Long = when {
        engine == LiveOcrTranslationEngineType.PADDLE_NETWORK ->
            PADDLE_NETWORK_TRANSLATION_TIMEOUT_MS
        backend.isSelfHosted -> SELF_HOSTED_TRANSLATION_TIMEOUT_MS
        backend == TranslationBackend.NETWORK -> NETWORK_TRANSLATION_TIMEOUT_MS
        else -> LOCAL_TRANSLATION_TIMEOUT_MS
    }

    fun shouldHoldImageQueue(
        captureInProgress: Boolean,
        captureRequested: Boolean,
        processingFrameCaptured: Boolean,
        presentationInProgress: Boolean
    ): Boolean = presentationInProgress || (
        captureInProgress && !captureRequested && !processingFrameCaptured
    )

    fun shouldRetryEmptyResult(patchCount: Int, retryCount: Int): Boolean =
        patchCount == 0 && retryCount == 0

    fun shouldUpdateLiveSnapshot(patchCount: Int, translatedRegionCount: Int): Boolean =
        patchCount > 0 && translatedRegionCount > 0

    fun shouldPresentResult(
        resultGeneration: Int,
        currentGeneration: Int,
        continuousTranslationEnabled: Boolean
    ): Boolean = continuousTranslationEnabled && resultGeneration == currentGeneration
}
