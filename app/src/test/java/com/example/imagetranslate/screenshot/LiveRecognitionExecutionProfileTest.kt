package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRecognitionExecutionProfileTest {
    @Test
    fun currentProfileSelectsBackgroundFromLocalTexture() {
        assertEquals(
            LiveDifferentialContextProfile.ACCURACY,
            LiveRecognitionExecutionProfile.CURRENT.contextProfile
        )
        assertEquals(
            LivePatchBackgroundMode.ADAPTIVE,
            LiveRecognitionExecutionProfile.CURRENT.backgroundMode
        )
    }
}
