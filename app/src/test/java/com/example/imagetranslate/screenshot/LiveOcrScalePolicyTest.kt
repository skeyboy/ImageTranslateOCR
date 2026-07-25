package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOcrScalePolicyTest {
    @Test
    fun scalesHighResolutionPortraitFramesWhilePreservingAspectRatio() {
        assertEquals(
            LiveOcrInputSize(1296, 2880),
            LiveOcrScalePolicy.inputSize(1440, 3200)
        )
    }

    @Test
    fun keepsAlreadyCompactDifferentialRegionsAtNativeResolution() {
        assertEquals(
            LiveOcrInputSize(1440, 1400),
            LiveOcrScalePolicy.inputSize(1440, 1400)
        )
    }

    @Test
    fun mapsRecognitionBoundsBackToTheCaptureCoordinateSpace() {
        val input = LiveOcrInputSize(1296, 2880)
        val source = LiveOcrInputSize(1440, 3200)

        assertEquals(100, LiveOcrScalePolicy.mapX(90, input, source))
        assertEquals(200, LiveOcrScalePolicy.mapY(180, input, source))
        assertEquals(600, LiveOcrScalePolicy.mapX(540, input, source))
        assertEquals(800, LiveOcrScalePolicy.mapY(720, input, source))
    }
}
