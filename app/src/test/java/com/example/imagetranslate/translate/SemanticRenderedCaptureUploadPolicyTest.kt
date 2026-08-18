package com.example.imagetranslate.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun v4DebugCaptureDoesNotDependOnTheOptionalV3UploadToggle() {
        assertTrue(
            SemanticRenderedCaptureUploadPolicy.shouldUpload(
                isDebugBuild = true,
                backend = TranslationBackend.SELF_HOSTED_V4,
                uploadEnabled = false,
                patchCount = 3,
                failedCount = 0,
                traceCount = 1
            )
        )
    }

    @Test
    fun embeddedV4UsesTheEdgeAuditEndpointForRenderedCaptures() {
        assertTrue(SemanticRenderedCaptureUploadPolicy.supportsBackend(TranslationBackend.EMBEDDED_V4))
        assertEquals(
            "http://127.0.0.1:8090",
            SemanticRenderedCaptureUploadPolicy.uploadBaseUrl(
                backend = TranslationBackend.EMBEDDED_V4,
                selfHostedBaseUrl = "http://self-hosted.invalid",
                edgeAuditBaseUrl = "http://127.0.0.1:8090/"
            )
        )
    }

    @Test
    fun selfHostedRenderedCapturesKeepUsingTheSelfHostedEndpoint() {
        assertEquals(
            "https://self-hosted.example",
            SemanticRenderedCaptureUploadPolicy.uploadBaseUrl(
                backend = TranslationBackend.SELF_HOSTED_V4,
                selfHostedBaseUrl = "https://self-hosted.example/",
                edgeAuditBaseUrl = "https://audit.example"
            )
        )
        assertFalse(SemanticRenderedCaptureUploadPolicy.supportsBackend(TranslationBackend.LOCAL))
        assertEquals(
            "",
            SemanticRenderedCaptureUploadPolicy.uploadBaseUrl(
                backend = TranslationBackend.LOCAL,
                selfHostedBaseUrl = "https://self-hosted.example",
                edgeAuditBaseUrl = "https://audit.example"
            )
        )
    }
}
