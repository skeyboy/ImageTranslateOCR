package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureProjectionLifecycleTest {
    @Test
    fun projectionCallbackDuringExplicitStopBelongsToAppShutdown() {
        assertEquals(
            ProjectionStopDecision.APP_REQUESTED,
            ScreenCaptureProjectionLifecycle.stopDecision(
                activeSessionId = 4L,
                callbackSessionId = 4L,
                projectionMatches = true,
                appRequestedStop = true
            )
        )
    }

    @Test
    fun unexpectedProjectionCallbackRequiresRecoverablePause() {
        assertEquals(
            ProjectionStopDecision.RECOVER_EXTERNAL,
            ScreenCaptureProjectionLifecycle.stopDecision(
                activeSessionId = 4L,
                callbackSessionId = 4L,
                projectionMatches = true,
                appRequestedStop = false
            )
        )
    }

    @Test
    fun callbackFromOldProjectionCannotTearDownReplacementSession() {
        assertEquals(
            ProjectionStopDecision.IGNORE_STALE,
            ScreenCaptureProjectionLifecycle.stopDecision(
                activeSessionId = 5L,
                callbackSessionId = 4L,
                projectionMatches = false,
                appRequestedStop = false
            )
        )
    }

    @Test
    fun frameUnavailableFailureIsDistinctFromOcrOrTranslationFailure() {
        val failure = CaptureFrameUnavailableException("frame stream stopped")

        assertTrue(ScreenCaptureProjectionLifecycle.requiresManualReauthorization(failure))
        assertFalse(
            ScreenCaptureProjectionLifecycle.requiresManualReauthorization(
                IllegalStateException("OCR failed")
            )
        )
    }
}
