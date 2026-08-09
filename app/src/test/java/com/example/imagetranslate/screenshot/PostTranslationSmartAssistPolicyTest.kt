package com.example.imagetranslate.screenshot

import com.example.imagetranslate.translate.TranslationBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostTranslationSmartAssistPolicyTest {
    @Test
    fun preservesAuthoritativeV4GroupsAndRenderSlots() {
        assertFalse(
            PostTranslationSmartAssistPolicy.shouldApply(
                enabled = true,
                usesIntegratedNetworkEngine = false,
                backend = TranslationBackend.SELF_HOSTED_V4
            )
        )
    }

    @Test
    fun keepsPostTranslationAssistForLegacyLocalPipeline() {
        assertTrue(
            PostTranslationSmartAssistPolicy.shouldApply(
                enabled = true,
                usesIntegratedNetworkEngine = false,
                backend = TranslationBackend.LOCAL
            )
        )
    }
}
