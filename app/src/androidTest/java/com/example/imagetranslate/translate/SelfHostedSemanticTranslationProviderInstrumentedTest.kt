package com.example.imagetranslate.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SelfHostedSemanticTranslationProviderInstrumentedTest {
    @Test
    fun configuredServiceTranslatesSemanticGroupOverRealNetwork() = runBlocking {
        val baseUrl = InstrumentationRegistry.getArguments()
            .getString("selfHostedBaseUrl")
            ?.trim()
            .orEmpty()
        assumeTrue("selfHostedBaseUrl instrumentation argument is required", baseUrl.isNotEmpty())
        val provider = SelfHostedSemanticTranslationProvider(baseUrl, null)
        try {
            val result = provider.translate(
                request(
                    requestId = "android-${UUID.randomUUID()}",
                    sessionId = "android-real-network"
                )
            )

            assertTrue(result.failures.isEmpty())
            assertEquals(listOf("group-title"), result.results.map { it.groupId })
            assertEquals(TranslationResultStatus.TRANSLATED, result.results.single().status)
            assertTrue(!result.results.single().translatedText.isNullOrBlank())
            assertTrue(
                result.results.single().provider.startsWith("self-hosted-qwen-layout-plan-v3")
            )
            assertEquals(2, result.results.single().layoutHint?.preferredMaxLines)
        } finally {
            provider.close()
        }
    }

    @Test
    fun cancellingCoroutinePropagatesRequestIdToConfiguredService() = runBlocking {
        val baseUrl = InstrumentationRegistry.getArguments()
            .getString("selfHostedBaseUrl")
            ?.trim()
            .orEmpty()
        assumeTrue("selfHostedBaseUrl instrumentation argument is required", baseUrl.isNotEmpty())
        val requestId = InstrumentationRegistry.getArguments()
            .getString("cancellationRequestId")
            ?.trim()
            .takeUnless(String?::isNullOrEmpty)
            ?: "android-cancel-${UUID.randomUUID()}"
        val provider = SelfHostedSemanticTranslationProvider(baseUrl, null)
        try {
            val job = launch {
                provider.translate(
                    request(
                        requestId = requestId,
                        sessionId = "android-cancel-session"
                    )
                )
            }
            delay(500)
            job.cancelAndJoin()
            delay(1_000)

            assertTrue(job.isCancelled)
        } finally {
            provider.close()
        }
    }

    @Test
    fun requestPreservesViewportGroupMembersCoordinatesAndGeneration() {
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", "demo-token")
        try {
            val body = JSONObject(provider.requestBodyForTest(request()))

            assertEquals(3, body.getInt("schemaVersion"))
            assertEquals(42L, body.getLong("generation"))
            assertEquals(1440, body.getJSONObject("viewport").getInt("width"))
            assertEquals("group-title", body.getJSONArray("groups").getJSONObject(0).getString("groupId"))
            assertEquals(
                "region-title-2",
                body.getJSONArray("groups").getJSONObject(0)
                    .getJSONArray("memberRegionIds").getString(1)
            )
            assertEquals(
                2280,
                body.getJSONArray("regions").getJSONObject(1)
                    .getJSONObject("bounds").getInt("top")
            )
            assertEquals(
                "RECT",
                body.getJSONArray("groups").getJSONObject(0).getString("layoutShape")
            )
            assertEquals(
                1,
                body.getJSONArray("groups").getJSONObject(0)
                    .getJSONArray("renderSlots").length()
            )
        } finally {
            provider.close()
        }
    }

    @Test
    fun requestIncludesDebugCaptureOnlyWhenCallerProvidesOne() {
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null)
        try {
            val withoutCapture = JSONObject(provider.requestBodyForTest(request()))
            val withCapture = JSONObject(
                provider.requestBodyForTest(
                    request().copy(
                        debugCapture = SemanticDebugCapture(
                            mimeType = "image/jpeg",
                            dataBase64 = "base64-image",
                            pixelWidth = 1080,
                            pixelHeight = 2400
                        )
                    )
                )
            )

            assertTrue(!withoutCapture.has("debugCapture"))
            assertEquals(
                "base64-image",
                withCapture.getJSONObject("debugCapture").getString("dataBase64")
            )
            assertEquals(1080, withCapture.getJSONObject("debugCapture").getInt("pixelWidth"))
        } finally {
            provider.close()
        }
    }

    @Test
    fun responseRequiresSameBindingAndParsesLayoutHints() {
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null)
        try {
            val result = provider.parseResponseForTest(
                """
                    {
                      "schemaVersion": 3,
                      "requestId": "request-1",
                      "sessionId": "session-1",
                      "generation": 42,
                      "translationRevision": 1,
                      "provider": "self-hosted-qwen-layout-plan-v3",
                      "results": [{
                        "groupId": "group-title",
                        "sourceGroupIds": ["group-title"],
                        "role": "TITLE",
                        "groupingConfidence": 0.96,
                        "status": "TRANSLATED",
                        "renderMode": "GROUP",
                        "translatedText": "习近平在北京会见斯洛伐克总统",
                        "memberRegionIds": ["region-title-1", "region-title-2"],
                        "detectedSourceLanguage": "en",
                        "targetLanguage": "zh",
                        "anchorBounds": {"left": 70, "top": 2200, "right": 1385, "bottom": 2350},
                        "layoutHint": {
                          "preferredMaxLines": 3,
                          "minimumTextScale": 0.72,
                          "alignment": "START",
                          "overflowStrategy": "REFLOW_THEN_SCALE"
                        }
                      }]
                    }
                """.trimIndent(),
                request()
            )

            assertTrue(result.failures.isEmpty())
            assertEquals("group-title", result.results.single().groupId)
            assertEquals(3, result.results.single().layoutHint?.preferredMaxLines)
            assertEquals(0.72f, result.results.single().layoutHint?.minimumTextScale)
            assertEquals(
                listOf(TranslationBounds(70, 2200, 1385, 2350)),
                result.results.single().layoutHint?.renderSlots
            )
        } finally {
            provider.close()
        }
    }

    private fun request(
        requestId: String = "request-1",
        sessionId: String = "session-1"
    ): SemanticTranslationRequest {
        val members = listOf(
            SemanticTranslationRegion(
                regionId = "region-title-1",
                groupId = "group-title",
                sourceRevision = 1,
                text = "Xi holds talks with",
                sourceLanguage = "en",
                targetLanguage = "zh",
                readingOrder = 0,
                blockId = "block-1",
                lineIndex = 0,
                confidence = 0.94f,
                bounds = TranslationBounds(70, 2200, 800, 2260)
            ),
            SemanticTranslationRegion(
                regionId = "region-title-2",
                groupId = "group-title",
                sourceRevision = 1,
                text = "Slovak president in Beijing",
                sourceLanguage = "en",
                targetLanguage = "zh",
                readingOrder = 1,
                blockId = "block-1",
                lineIndex = 1,
                confidence = 0.95f,
                bounds = TranslationBounds(70, 2280, 1100, 2350)
            )
        )
        val source = SemanticTranslationSource(
            groupId = "group-title",
            role = "TITLE",
            translationUnit = "GROUP",
            sourceText = "Xi holds talks with\nSlovak president in Beijing",
            memberRegionIds = members.map(SemanticTranslationRegion::regionId),
            readingOrder = 0,
            groupingConfidence = 0.96f,
            groupingEvidence = listOf("OCR_BLOCK"),
            bounds = TranslationBounds(70, 2200, 1385, 2350),
            regions = members
        )
        return SemanticTranslationRequest(
            requestId = requestId,
            sessionId = sessionId,
            generation = 42,
            translationRevision = 1,
            scene = "LIVE_SCREEN",
            viewportWidth = 1440,
            viewportHeight = 3200,
            mode = TranslationMode.ENGLISH_TO_CHINESE,
            documentText = source.sourceText,
            sources = listOf(source)
        )
    }
}
