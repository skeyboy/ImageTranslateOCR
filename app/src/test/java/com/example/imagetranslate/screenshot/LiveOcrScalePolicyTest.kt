package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOcrScalePolicyTest {
    @Test
    fun scalesHighResolutionPortraitFramesWhilePreservingAspectRatio() {
        assertEquals(
            LiveOcrInputSize(1152, 2560),
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
        val input = LiveOcrInputSize(1152, 2560)
        val source = LiveOcrInputSize(1440, 3200)

        assertEquals(100, LiveOcrScalePolicy.mapX(80, input, source))
        assertEquals(200, LiveOcrScalePolicy.mapY(160, input, source))
        assertEquals(600, LiveOcrScalePolicy.mapX(480, input, source))
        assertEquals(800, LiveOcrScalePolicy.mapY(640, input, source))
    }
}
