package com.example.imagetranslate.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerGeminiV4InstrumentedTest {
    @Test
    fun optionalServerGeminiPathReturnsValidatedV4Dsl() = runBlocking {
        val baseUrl = InstrumentationRegistry.getArguments()
            .getString("serverGeminiBaseUrl")
            ?.trim()
            .orEmpty()
        assumeTrue("serverGeminiBaseUrl instrumentation argument is required", baseUrl.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalEnabled = TranslationBackendSettings.isServerGeminiEnabled(context)
        val originalBaseUrl = TranslationBackendSettings.serverGeminiBaseUrl(context)
        val manager = TranslateManager(context)
        try {
            TranslationBackendSettings.setServerGemini(context, true, baseUrl)
            assertTrue(TranslationBackendSettings.isServerGeminiEnabled(context))
            assertEquals(
                baseUrl.trimEnd('/') + SERVER_GEMINI_REGIONS_FIRST_PATH,
                TranslationBackendSettings.serverGeminiTranslationEndpoint(context)
            )

            val bounds = TranslationBounds(48, 180, 1032, 300)
            val source = SemanticTranslationSource(
                groupId = "android-server-gemini-body",
                role = "BODY",
                translationUnit = "GROUP",
                sourceText = "Cargo builds Rust projects and downloads dependencies.",
                memberRegionIds = listOf("android-server-gemini-region"),
                readingOrder = 0,
                groupingConfidence = 0.99f,
                groupingEvidence = listOf("ANDROID_INSTRUMENTED_TEST"),
                bounds = bounds,
                regions = listOf(
                    SemanticTranslationRegion(
                        regionId = "android-server-gemini-region",
                        groupId = "android-server-gemini-body",
                        sourceRevision = 1,
                        text = "Cargo builds Rust projects and downloads dependencies.",
                        sourceLanguage = "en",
                        targetLanguage = "zh",
                        readingOrder = 0,
                        blockId = "android-server-gemini-block",
                        lineIndex = 0,
                        confidence = 0.99f,
                        bounds = bounds,
                        componentBounds = listOf(bounds)
                    )
                ),
                renderSlots = listOf(bounds)
            )

            val result = manager.translateSemanticGroups(
                sources = listOf(source),
                viewportWidth = 1080,
                viewportHeight = 2400,
                mode = TranslationMode.ENGLISH_TO_CHINESE,
                scene = "ANDROID_SERVER_GEMINI_APK_TEST"
            ).single()

            assertTrue("Server Gemini translation failed: ${result.failure}", result.succeeded)
            assertEquals("self-hosted-gemini-native-regions-first-v4", result.provider)
            assertTrue(result.translatedText.any { it in '\u4e00'..'\u9fff' })
            assertTrue(result.translatedText.contains("Cargo"))
            assertTrue(result.translatedText.contains("Rust"))
            assertNotNull(result.layoutHint)
            assertTrue(result.layoutHint?.renderSlots?.isNotEmpty() == true)
            assertTrue(result.layoutHint?.sourceCoverSlots?.isNotEmpty() == true)
        } finally {
            manager.close()
            TranslationBackendSettings.setServerGemini(
                context,
                originalEnabled,
                originalBaseUrl
            )
        }
    }
}
