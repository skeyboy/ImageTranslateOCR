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
                failedCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = false,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 2,
                failedCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.LOCAL,
                uploadEnabled = true,
                patchCount = 2,
                failedCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED_V4,
                uploadEnabled = true,
                patchCount = 2,
                failedCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 0,
                failedCount = 0,
                traceCount = 1
            )
        )
        assertFalse(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 2,
                failedCount = 0,
                traceCount = 0
            )
        )
    }

    @Test
    fun uploadsFailedRenderWithoutPatchesWhenRequestTraceExists() {
        assertTrue(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED,
                uploadEnabled = true,
                patchCount = 0,
                failedCount = 2,
                traceCount = 1
            )
        )
    }
}
