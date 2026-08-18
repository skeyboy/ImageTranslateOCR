package com.example.imagetranslate.translate

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.ocr.OCRManager
import com.example.imagetranslate.ocr.OcrRecognitionMode
import com.example.imagetranslate.semantic.SemanticTextGrouper
import com.example.imagetranslate.semantic.StaticImageTextFilter
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class EmbeddedEdgeInstrumentedTest {
    @Test
    fun configuresRuntimeProxyFromArguments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proxyUrl = InstrumentationRegistry.getArguments().getString("proxyUrl")
            ?.trim().orEmpty()
        assumeTrue("proxyUrl instrumentation argument is required", proxyUrl.isNotEmpty())

        TranslationBackendSettings.setEdgeProxyUrl(context, proxyUrl)

        assertEquals(proxyUrl, TranslationBackendSettings.edgeProxyUrl(context))
    }

    @Test
    fun primaryAndroidConfigurationUsesNativeGeminiV4Defaults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TranslationBackendSettings.setEdgeThinkingLevel(context, "medium")
        TranslationBackendSettings.setEdgeProxyUrl(context, "")

        assertEquals(TranslationBackend.EMBEDDED_V4, TranslationBackendSettings.get(context))
        assertEquals("google", TranslationBackendSettings.edgeProvider(context))
        assertEquals("gemini-3.5-flash-lite", TranslationBackendSettings.edgeModel(context))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            TranslationBackendSettings.edgeBaseUrl(context)
        )
        assertEquals(
            ProviderThinkingControlMode.THINKING_LEVEL,
            TranslationBackendSettings.edgeThinkingControlMode(context)
        )
        assertEquals("medium", TranslationBackendSettings.edgeThinkingLevel(context))
        assertTrue(TranslationBackendSettings.isDirectStructuredOutputEnabled(context))
        assertTrue(TranslationBackendSettings.isCompactProviderPromptEnabled(context))
    }

    @Test
    fun providerCanSwitchToOpenLuxAndBackToGoogle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        TranslationBackendSettings.setEdgeConfiguration(
            context = context,
            provider = "openlux",
            baseUrl = "https://api.openlux.ai/v1/chat/completions",
            apiKey = "instrumentation-test-key",
            models = "gpt-4o, gemini-3.5-flash-lite"
        )
        assertEquals("openlux", TranslationBackendSettings.edgeProvider(context))
        assertEquals("https://api.openlux.ai/v1", TranslationBackendSettings.edgeBaseUrl(context))
        assertEquals(
            listOf("gpt-4o", "gemini-3.5-flash-lite"),
            TranslationBackendSettings.edgeModels(context)
        )
        assertEquals("gpt-4o", TranslationBackendSettings.edgeModel(context))
        assertEquals("instrumentation-test-key", TranslationBackendSettings.edgeApiKey(context))
        assertTrue(TranslationBackendSettings.isEdgeConfigured(context))

        TranslationBackendSettings.setEdgeProvider(context, "google")
        assertEquals("google", TranslationBackendSettings.edgeProvider(context))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            TranslationBackendSettings.edgeBaseUrl(context)
        )
    }

    @Test
    fun comparesReasoningEffortsOnCapturedBrowserPage() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val imagePath = arguments.getString("validationImagePath")?.trim().orEmpty()
        assumeTrue("validationImagePath instrumentation argument is required", imagePath.isNotEmpty())
        val context = instrumentation.targetContext
        assumeTrue("Debug edge provider is not configured", TranslationBackendSettings.isEdgeConfigured(context))
        val originalAuditBaseUrl = TranslationBackendSettings.edgeAuditBaseUrl(context)
        arguments.getString("edgeAuditBaseUrl")?.takeIf(String::isNotBlank)
            ?.let { TranslationBackendSettings.setEdgeAuditBaseUrl(context, it) }
        val bitmap = checkNotNull(BitmapFactory.decodeFile(imagePath)) {
            "Unable to decode captured browser page: $imagePath"
        }
        val ocr = OCRManager(context)
        val providers = mutableListOf<EmbeddedSemanticTranslationProvider>()
        try {
            val ocrStarted = SystemClock.elapsedRealtime()
            val recognized = ocr.recognize(bitmap, OcrRecognitionMode.ENGLISH)
            val ocrMs = SystemClock.elapsedRealtime() - ocrStarted
            val filtered = StaticImageTextFilter.filter(recognized, bitmap.width, bitmap.height)
            val sources = SemanticTextGrouper.group(filtered, bitmap.width, bitmap.height)
                .map { it.toSemanticTranslationSource() }
            assertTrue("Browser capture produced no OCR groups", sources.isNotEmpty())
            val runs = JSONArray()
            val matrixId = UUID.randomUUID().toString()
            val requestedEfforts = arguments.getString("reasoningEfforts")
                ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?: listOf("none", "low", "medium")
            val rounds = arguments.getString("reasoningRounds")?.toIntOrNull()
                ?.coerceIn(1, 5) ?: 3
            val cases = listOf(
                "none" to ProviderThinkingControlMode.REASONING_EFFORT,
                "low" to ProviderThinkingControlMode.REASONING_EFFORT,
                "medium" to ProviderThinkingControlMode.REASONING_EFFORT
            ).filter { it.first in requestedEfforts }
            assertTrue("No requested reasoning effort is supported", cases.isNotEmpty())
            var generation = 0L
            repeat(rounds) { roundIndex ->
                val orderedCases = cases.drop(roundIndex % cases.size) +
                    cases.take(roundIndex % cases.size)
                for ((orderIndex, case) in orderedCases.withIndex()) {
                    val (level, mode) = case
                    val provider = EmbeddedSemanticTranslationProvider(
                        context = context,
                        provider = TranslationBackendSettings.edgeProvider(context),
                        model = "gemini-3.5-flash-lite",
                        baseUrl = TranslationBackendSettings.edgeBaseUrl(context),
                        apiKey = TranslationBackendSettings.edgeApiKey(context),
                        thinkingMode = mode,
                        thinkingLevel = level,
                        cacheEnabled = false
                    ).also(providers::add)
                    generation += 1
                    val requestId = "device-matrix-$matrixId-r${roundIndex + 1}-$level-${UUID.randomUUID()}"
                    val started = SystemClock.elapsedRealtime()
                    val result = provider.translate(
                        SemanticTranslationRequest(
                            requestId = requestId,
                            sessionId = "device-browser-reasoning-$matrixId",
                            generation = generation,
                            translationRevision = 1,
                            scene = "LIVE_SCREEN",
                            viewportWidth = bitmap.width,
                            viewportHeight = bitmap.height,
                            mode = TranslationMode.ENGLISH_TO_CHINESE,
                            documentText = sources.joinToString("\n") { "[${it.role}] ${it.sourceText}" },
                            sources = sources,
                            compactProviderPrompt = true,
                            thinkingControlMode = mode,
                            thinkingLevel = level
                        )
                    )
                    val elapsedMs = SystemClock.elapsedRealtime() - started
                    assertTrue("$level returned failures: ${result.failures}", result.failures.isEmpty())
                    assertEquals("$level omitted translation groups", sources.size, result.results.size)
                    assertTrue(
                        "$level returned a non-translated result",
                        result.results.all { it.status == TranslationResultStatus.TRANSLATED }
                    )
                    val translatedText = result.results.joinToString(" ") { it.translatedText.orEmpty() }
                    val preservesCargo = translatedText.contains("Cargo")
                    val preservesRust = translatedText.contains("Rust")
                    val containsChinese = translatedText.any { it in '\u4e00'..'\u9fff' }
                    assertTrue("$level did not preserve Cargo", preservesCargo)
                    assertTrue("$level did not preserve Rust", preservesRust)
                    assertTrue("$level produced no Chinese translation", containsChinese)
                    runs.put(JSONObject()
                        .put("requestId", requestId)
                        .put("round", roundIndex + 1)
                        .put("order", orderIndex + 1)
                        .put("effort", level)
                        .put("thinkingMode", mode.name)
                        .put("cacheEnabled", false)
                        .put("elapsedMs", elapsedMs)
                        .put("resultCount", result.results.size)
                        .put("failureCount", result.failures.size)
                        .put("preservesCargo", preservesCargo)
                        .put("preservesRust", preservesRust)
                        .put("containsChinese", containsChinese)
                        .put("translations", JSONArray(result.results.map { item ->
                            JSONObject().put("groupId", item.groupId)
                                .put("translatedText", item.translatedText)
                        })))
                }
            }
            val report = JSONObject()
                .put("schemaVersion", 2)
                .put("event", "device_browser_reasoning_ab")
                .put("matrixId", matrixId)
                .put("model", "gemini-3.5-flash-lite")
                .put("rounds", rounds)
                .put("cacheEnabled", false)
                .put("source", JSONObject()
                    .put("path", imagePath)
                    .put("width", bitmap.width)
                    .put("height", bitmap.height))
                .put("ocrElapsedMs", ocrMs)
                .put("ocrRegionCount", recognized.size)
                .put("filteredRegionCount", filtered.size)
                .put("semanticGroupCount", sources.size)
                .put("runs", runs)
            val output = File(context.filesDir, "validation/device-browser-reasoning-ab.json")
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2))
        } finally {
            // Give asynchronous LAN audit uploads time to complete before cancelling providers.
            kotlinx.coroutines.delay(2_000)
            providers.forEach(EmbeddedSemanticTranslationProvider::close)
            ocr.close()
            bitmap.recycle()
            TranslationBackendSettings.setEdgeAuditBaseUrl(context, originalAuditBaseUrl)
        }
    }

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
        assertTrue(!prompt.getString("user").contains("regionLines"))
        assertEquals(
            "COMPACT",
            JSONObject(prompt.getString("user")).getString("promptProfile")
        )
        assertEquals("json_schema", prompt.getJSONObject("responseFormat").getString("type"))
        assertEquals(4, JSONObject(request).getInt("schemaVersion"))
    }

    @Test
    fun performsDirectAiTranslationAndReturnsV4DslOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val proxyUrl = InstrumentationRegistry.getArguments().getString("proxyUrl")
            ?.trim()?.takeIf(String::isNotEmpty)
            ?: TranslationBackendSettings.edgeProxyUrl(context)
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
            apiKey = TranslationBackendSettings.edgeApiKey(context),
            thinkingMode = TranslationBackendSettings.edgeThinkingControlMode(context),
            thinkingLevel = TranslationBackendSettings.edgeThinkingLevel(context),
            proxyUrl = proxyUrl
        )
        try {
            val requestId = "android-edge-live-test-${UUID.randomUUID()}"
            val result = provider.translate(
                SemanticTranslationRequest(
                    requestId = requestId,
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
            // The edge audit is intentionally asynchronous; keep the provider alive long enough
            // for timing evidence to reach the local validation server.
            kotlinx.coroutines.delay(2_000)
        } finally {
            provider.close()
            TranslationBackendSettings.setEdgeAuditBaseUrl(context, originalAuditBaseUrl)
        }
    }

    @Test
    fun providerThinkingControlCasesAreMutuallyExclusive() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val request = """
            {
              "schemaVersion":4,"requestId":"thinking-case","sessionId":"thinking-session",
              "generation":1,"translationRevision":1,"scene":"LIVE_SCREEN",
              "viewport":{"width":1080,"height":2400,"rotationDegrees":0},
              "translation":{"mode":"AUTO_BIDIRECTIONAL","sourceLanguage":"auto","targetLanguage":"zh","preserveIdentifiers":true,"useDocumentContext":true},
              "documentContext":{"text":"Cargo builds Rust projects.","sourceLanguage":"auto","readingOrderRegionIds":["region-1"]},
              "groups":[],
              "regions":[{"regionId":"region-1","groupId":"client-1","sourceRevision":1,"text":"Cargo builds Rust projects.","sourceLanguage":"en","targetLanguage":"zh","readingOrder":0,"blockId":"block-1","lineIndex":0,"confidence":0.98,"bounds":{"left":60,"top":300,"right":1020,"bottom":390},"componentBounds":[]}]
            }
        """.trimIndent()
        fun body(mode: ProviderThinkingControlMode) = EmbeddedSemanticTranslationProvider(
            context = context,
            provider = "openlux",
            model = "gemini-3.5-flash-lite",
            baseUrl = "https://api.openlux.ai/v1",
            apiKey = "test",
            thinkingMode = mode,
            thinkingLevel = "minimal"
        ).use { it.providerRequestBodyForTest(request) }

        val baseline = body(ProviderThinkingControlMode.NONE)
        assertTrue(!baseline.has("reasoning_effort") && !baseline.has("google"))

        val reasoning = body(ProviderThinkingControlMode.REASONING_EFFORT)
        assertEquals("minimal", reasoning.getString("reasoning_effort"))
        assertTrue(!reasoning.has("google"))

        val native = body(ProviderThinkingControlMode.THINKING_LEVEL)
        assertEquals(
            "MINIMAL",
            native.getJSONObject("google").getJSONObject("thinking_config")
                .getString("thinking_level")
        )
        assertTrue(!native.has("reasoning_effort"))

        val googleNative = EmbeddedSemanticTranslationProvider(
            context = context,
            provider = "google",
            model = "gemini-3.5-flash-lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test",
            thinkingMode = ProviderThinkingControlMode.THINKING_LEVEL,
            thinkingLevel = "low"
        ).use { it.providerRequestBodyForTest(request) }
        assertEquals(
            "LOW",
            googleNative.getJSONObject("generationConfig")
                .getJSONObject("thinkingConfig").getString("thinkingLevel")
        )
        assertTrue(
            googleNative.getJSONObject("generationConfig")
                .getJSONObject("thinkingConfig").getBoolean("includeThoughts")
        )
        assertEquals(
            "application/json",
            googleNative.getJSONObject("generationConfig").getString("responseMimeType")
        )
        assertTrue(googleNative.has("systemInstruction"))
        assertTrue(googleNative.has("contents"))
        assertTrue(!googleNative.has("model") && !googleNative.has("messages"))

        val googleMedium = EmbeddedSemanticTranslationProvider(
            context = context,
            provider = "google",
            model = "gemini-3.5-flash-lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            apiKey = "test",
            thinkingMode = ProviderThinkingControlMode.THINKING_LEVEL,
            thinkingLevel = "medium"
        ).use { it.providerRequestBodyForTest(request) }
        assertEquals(
            "MEDIUM",
            googleMedium.getJSONObject("generationConfig")
                .getJSONObject("thinkingConfig").getString("thinkingLevel")
        )

        for (level in listOf("minimal", "low", "medium", "high")) {
            val openLux = EmbeddedSemanticTranslationProvider(
                context = context,
                provider = "openlux",
                model = "gemini-3.5-flash-lite",
                baseUrl = "https://api.openlux.ai/v1",
                apiKey = "test",
                thinkingMode = ProviderThinkingControlMode.THINKING_LEVEL,
                thinkingLevel = level
            ).use { it.providerRequestBodyForTest(request) }
            assertEquals(
                level.uppercase(),
                openLux.getJSONObject("google").getJSONObject("thinking_config")
                    .getString("thinking_level")
            )
        }
    }
}
