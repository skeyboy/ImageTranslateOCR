package com.example.imagetranslate.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteTranslationServiceInstrumentedTest {
    private var provider: RemoteTranslationProvider? = null

    @Before
    fun requireConfiguredService() {
        assumeTrue(BuildConfig.REMOTE_TRANSLATION_BASE_URL.isNotBlank())
        provider = RemoteTranslationProvider(BuildConfig.REMOTE_TRANSLATION_BASE_URL)
    }

    @After
    fun closeProvider() {
        provider?.close()
    }

    @Test
    fun configuredHyMt2ServiceTranslatesAndPreservesCode() = runBlocking {
        val requestId = UUID.randomUUID().toString()
        val requests = listOf(
            TranslationRequest(
                requestId = requestId,
                regionId = "region-text",
                text = "Visible completion is the real delivery point",
                mode = TranslationMode.ENGLISH_TO_CHINESE,
                sourceLanguage = "en",
                targetLanguage = "zh"
            ),
            TranslationRequest(
                requestId = requestId,
                regionId = "region-code",
                text = "suspend fun commit(frame: Frame) {\n  require(frame.generation == current)\n}",
                mode = TranslationMode.ENGLISH_TO_CHINESE,
                sourceLanguage = "en",
                targetLanguage = "zh"
            )
        )

        val result = checkNotNull(provider).translateBatch(requests)

        assertTrue(result.failures.isEmpty())
        assertEquals(listOf("region-text", "region-code"), result.results.map { it.regionId })
        assertTrue(result.results.first().translatedText.isNotBlank())
        assertEquals(requests[1].text, result.results[1].translatedText)
        assertEquals(TranslationResultStatus.PRESERVED, result.results[1].status)
        assertTrue(result.results.all { it.provider.startsWith("hy-mt2") })
    }

    @Test
    fun demoConfigurationRoutesMainTranslationManagerToHyMt2() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalBackend = TranslationBackendSettings.get(context)
        val originalBaseUrl = TranslationBackendSettings.networkBaseUrl(context)
        val manager = TranslateManager(context)

        try {
            TranslationBackendSettings.setNetworkBaseUrl(
                context,
                BuildConfig.REMOTE_TRANSLATION_BASE_URL
            )
            assertEquals(
                BuildConfig.REMOTE_TRANSLATION_BASE_URL.trimEnd('/'),
                TranslationBackendSettings.networkBaseUrl(context)
            )
            TranslationBackendSettings.set(context, TranslationBackend.NETWORK)

            val result = manager.translateBatch(
                texts = listOf("Good morning"),
                mode = TranslationMode.ENGLISH_TO_CHINESE
            ).single()

            assertTrue(result.succeeded)
            assertTrue(result.translatedText.isNotBlank())
            assertTrue(result.provider.startsWith("hy-mt2"))
        } finally {
            manager.close()
            TranslationBackendSettings.setNetworkBaseUrl(context, originalBaseUrl)
            TranslationBackendSettings.set(context, originalBackend)
        }
    }
}
