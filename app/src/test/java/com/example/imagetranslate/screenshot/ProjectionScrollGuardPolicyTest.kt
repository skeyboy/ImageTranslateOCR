package com.example.imagetranslate.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionScrollGuardPolicyTest {
    @Test
    fun forwardsScrollFromTheTranslatedApplication() {
        assertTrue(
            ProjectionScrollGuardPolicy.shouldForwardScroll(
                sourcePackage = "com.android.browser",
                ownPackage = "com.example.imagetranslate"
            )
        )
    }

    @Test
    fun ignoresOwnAndSystemUiScrollEvents() {
        assertFalse(
            ProjectionScrollGuardPolicy.shouldForwardScroll(
                sourcePackage = "com.example.imagetranslate",
                ownPackage = "com.example.imagetranslate"
            )
        )
        assertFalse(
            ProjectionScrollGuardPolicy.shouldForwardScroll(
                sourcePackage = "com.android.systemui",
                ownPackage = "com.example.imagetranslate"
            )
        )
        assertFalse(
            ProjectionScrollGuardPolicy.shouldForwardScroll(
                sourcePackage = null,
                ownPackage = "com.example.imagetranslate"
            )
        )
    }
}
