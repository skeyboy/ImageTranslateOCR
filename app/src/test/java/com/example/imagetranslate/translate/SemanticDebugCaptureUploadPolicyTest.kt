package com.example.imagetranslate.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticDebugCaptureUploadPolicyTest {
    @Test
    fun uploadsOnlyDebugLiveScreenFramesToSelfHostedService() {
        assertTrue(
            SemanticDebugCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                scene = "LIVE_SCREEN",
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                missingGroupCount = 3
            )
        )
        assertFalse(eligible(scene = "STATIC_IMAGE"))
        assertFalse(eligible(isDebugBuild = false))
        assertFalse(eligible(backend = TranslationBackend.NETWORK))
        assertFalse(eligible(uploadEnabled = false))
        assertFalse(eligible(missingGroupCount = 0))
    }

    @Test
    fun embeddedV4ServerForwardingUploadsTheSourceFrame() {
        assertTrue(eligible(backend = TranslationBackend.EMBEDDED_V4))
    }

    private fun eligible(
        isDebugBuild: Boolean = true,
        scene: String = "LIVE_SCREEN",
        backend: TranslationBackend = TranslationBackend.SELF_HOSTED,
        uploadEnabled: Boolean = true,
        missingGroupCount: Int = 1
    ) = SemanticDebugCaptureUploadPolicy.shouldUpload(
        isDebugBuild,
        scene,
        backend,
        uploadEnabled,
        missingGroupCount
    )
}
