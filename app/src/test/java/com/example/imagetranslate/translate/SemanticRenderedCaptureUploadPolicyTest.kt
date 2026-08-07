package com.example.imagetranslate.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticRenderedCaptureUploadPolicyTest {
    @Test
    fun uploadsOnlyForEnabledDebugSelfHostedResultsWithPatchesAndTraces() {
        assertTrue(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 2,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = false,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 2,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.LOCAL,
                uploadEnabled = true,
                patchCount = 2,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 2,
                traceCount = 0
            )
        )
    }
}
