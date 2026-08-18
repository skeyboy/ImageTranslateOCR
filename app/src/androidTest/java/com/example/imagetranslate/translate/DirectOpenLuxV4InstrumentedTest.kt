package com.example.imagetranslate.translate

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DirectOpenLuxV4InstrumentedTest {
    @Test
    fun directOpenLuxPreservesCcpaWithoutDisablingAuditRouting() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val apiKey = InstrumentationRegistry.getArguments()
            .getString("openLuxApiKey")?.trim().orEmpty()
        assumeTrue("openLuxApiKey instrumentation argument is required", apiKey.isNotEmpty())
        val context = instrumentation.targetContext
        val originalServerEnabled = TranslationBackendSettings.isServerGeminiEnabled(context)
        val originalServerUrl = TranslationBackendSettings.serverGeminiBaseUrl(context)
        val originalProvider = TranslationBackendSettings.edgeProvider(context)
        val originalAuditUrl = TranslationBackendSettings.edgeAuditBaseUrl(context)
        val originalArchiveEnabled =
            TranslationBackendSettings.isRequestArchiveExportEnabled(context)
        val manager = TranslateManager(context)
        try {
            TranslationBackendSettings.setRequestArchiveExportEnabled(context, false)
            TranslationBackendSettings.setEdgeConfiguration(
                context = context,
                provider = TranslationBackendSettings.OPENLUX_EDGE_PROVIDER,
                baseUrl = "https://api.openlux.ai/v1/chat/completions",
                apiKey = apiKey,
                models = "gpt-4o"
            )
            TranslationBackendSettings.setServerGemini(context, false, "")
            TranslationBackendSettings.setEdgeAuditBaseUrl(context, originalAuditUrl)
            val bounds = TranslationBounds(40, 120, 1040, 420)
            val source = SemanticTranslationSource(
                groupId = "ccpa-direct-openlux",
                role = "BODY",
                translationUnit = "GROUP",
                sourceText = "California Consumer Privacy Act, as amended from time to time, \"CCPA\".",
                memberRegionIds = listOf("ccpa-direct-openlux-region"),
                readingOrder = 0,
                groupingConfidence = 0.99f,
                groupingEvidence = listOf("ANDROID_INSTRUMENTED_TEST"),
                bounds = bounds,
                regions = listOf(
                    SemanticTranslationRegion(
                        regionId = "ccpa-direct-openlux-region",
                        groupId = "ccpa-direct-openlux",
                        sourceRevision = 1,
                        text = "California Consumer Privacy Act, as amended from time to time, \"CCPA\".",
                        sourceLanguage = "en",
                        targetLanguage = "zh",
                        readingOrder = 0,
                        blockId = "ccpa-direct-openlux-block",
                        lineIndex = 0,
                        confidence = 0.99f,
                        bounds = bounds,
                        componentBounds = listOf(bounds)
                    )
                )
            )
            val result = manager.translateSemanticGroups(
                sources = listOf(source),
                viewportWidth = 1080,
                viewportHeight = 2400,
                mode = TranslationMode.ENGLISH_TO_CHINESE,
                scene = "ANDROID_DIRECT_OPENLUX_TEST"
            ).single()

            assertTrue("Direct OpenLux translation failed: ${result.failure}", result.succeeded)
            assertEquals("embedded-openlux-v4", result.provider)
            assertTrue(result.translatedText.contains("CCPA"))
            assertEquals(originalAuditUrl, TranslationBackendSettings.edgeAuditBaseUrl(context))
        } finally {
            manager.close()
            TranslationBackendSettings.setEdgeProvider(context, originalProvider)
            TranslationBackendSettings.setServerGemini(
                context,
                originalServerEnabled,
                originalServerUrl
            )
            TranslationBackendSettings.setEdgeAuditBaseUrl(context, originalAuditUrl)
            TranslationBackendSettings.setRequestArchiveExportEnabled(
                context,
                originalArchiveEnabled
            )
        }
    }
}
