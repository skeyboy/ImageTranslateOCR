package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecognitionExecutionProfileTest {
    @Test
    fun currentProfileUsesValidatedBlurTintBackground() {
        assertEquals(
            LivePatchBackgroundMode.BLUR_TINT,
            LiveRecognitionExecutionProfile.CURRENT.backgroundMode
        )
    }
}
