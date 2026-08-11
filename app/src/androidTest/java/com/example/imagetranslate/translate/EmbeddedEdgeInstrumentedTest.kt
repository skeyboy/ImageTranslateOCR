package com.example.imagetranslate.translate

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EmbeddedEdgeInstrumentedTest {
    @Test
    fun loadsRustCoreAndPreparesV4PromptOnDevice() {
        val request = """
            {
              "schemaVersion":4,
              "requestId":"android-edge-test",
              "sessionId":"android-edge-session",
              "generation":1,
              "translationRevision":1,
              "scene":"LIVE_SCREEN",
              "viewport":{"width":1080,"height":2400,"rotationDegrees":0},
              "translation":{"mode":"AUTO_BIDIRECTIONAL","sourceLanguage":"auto","targetLanguage":"zh","preserveIdentifiers":true,"useDocumentContext":true},
              "documentContext":{"text":"Rust is a systems programming language.","sourceLanguage":"auto","readingOrderRegionIds":["region-1"]},
              "groups":[],
              "regions":[{"regionId":"region-1","groupId":"client-1","sourceRevision":1,"text":"Rust is a systems programming language.","sourceLanguage":"en","targetLanguage":"zh","readingOrder":0,"blockId":"block-1","lineIndex":0,"confidence":0.98,"bounds":{"left":60,"top":300,"right":1020,"bottom":390},"componentBounds":[]}]
            }
        """.trimIndent()

        val prepared = NativeEdgeTranslationBridge.prepare(request, "openlux", "gpt-4.1")

        assertEquals("openlux", prepared.getString("provider"))
        assertEquals("gpt-4.1", prepared.getString("model"))
        assertEquals(1, prepared.getJSONArray("executionGroups").length())
        val prompt = prepared.getJSONObject("modelPrompt")
        assertTrue(prompt.getString("user").contains("regionLines"))
        assertEquals("json_schema", prompt.getJSONObject("responseFormat").getString("type"))
        assertEquals(4, JSONObject(request).getInt("schemaVersion"))
    }

    @Test
    fun performsDirectAiTranslationAndReturnsV4DslOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue("Debug edge provider is not configured", TranslationBackendSettings.isEdgeConfigured(context))
        val originalAuditBaseUrl = TranslationBackendSettings.edgeAuditBaseUrl(context)
        InstrumentationRegistry.getArguments().getString("edgeAuditBaseUrl")
            ?.takeIf(String::isNotBlank)
            ?.let { TranslationBackendSettings.setEdgeAuditBaseUrl(context, it) }
        val bounds = TranslationBounds(60, 300, 1020, 390)
        val region = SemanticTranslationRegion(
            regionId = "region-1",
            groupId = "group-1",
            sourceRevision = 1,
            text = "Rust is a systems programming language.",
            sourceLanguage = "en",
            targetLanguage = "zh",
            readingOrder = 0,
            blockId = "block-1",
            lineIndex = 0,
            confidence = 0.98f,
            bounds = bounds
        )
        val source = SemanticTranslationSource(
            groupId = "group-1",
            role = "BODY",
            translationUnit = "GROUP",
            sourceText = region.text,
            memberRegionIds = listOf(region.regionId),
            readingOrder = 0,
            groupingConfidence = 0.98f,
            groupingEvidence = listOf("DEVICE_TEST"),
            bounds = bounds,
            regions = listOf(region)
        )
        val provider = EmbeddedSemanticTranslationProvider(
            context = context,
            provider = TranslationBackendSettings.edgeProvider(context),
            model = TranslationBackendSettings.edgeModel(context),
            baseUrl = TranslationBackendSettings.edgeBaseUrl(context),
            apiKey = TranslationBackendSettings.edgeApiKey(context)
        )
        try {
            val result = provider.translate(
                SemanticTranslationRequest(
                    requestId = "android-edge-live-test",
                    sessionId = "android-edge-live-session",
                    generation = 1,
                    translationRevision = 1,
                    scene = "LIVE_SCREEN",
                    viewportWidth = 1080,
                    viewportHeight = 2400,
                    mode = TranslationMode.AUTO_BIDIRECTIONAL,
                    documentText = source.sourceText,
                    sources = listOf(source)
                )
            )
            assertTrue(result.failures.isEmpty())
            assertEquals(1, result.results.size)
            assertEquals(TranslationResultStatus.TRANSLATED, result.results.single().status)
            assertTrue(result.results.single().translatedText.orEmpty().isNotBlank())
            assertEquals(1, result.results.single().layoutHint?.renderSlots?.size)
        } finally {
            provider.close()
            TranslationBackendSettings.setEdgeAuditBaseUrl(context, originalAuditBaseUrl)
        }
    }
}
