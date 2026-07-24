package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationOverlayTouchPolicyTest {
    @Test
    fun staysBelowTheStandardAndroidObscuringThreshold() {
        assertEquals(0.78f, TranslationOverlayTouchPolicy.windowAlpha(0.8f), 0.001f)
    }

    @Test
    fun adaptsToADeviceWithAStricterThreshold() {
        assertEquals(0.58f, TranslationOverlayTouchPolicy.windowAlpha(0.6f), 0.001f)
    }

    @Test
    fun touchThroughWinsWhenTheDeviceThresholdIsVeryLow() {
        assertEquals(0.03f, TranslationOverlayTouchPolicy.windowAlpha(0.05f), 0.001f)
    }
}
