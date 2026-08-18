package com.example.imagetranslate.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProviderTest {
    @Test
    fun translationBackendDefaultsToLocalAndRejectsUnavailableNetwork() {
        assertEquals(TranslationBackend.LOCAL, resolveTranslationBackend(null, false))
        assertEquals(
            TranslationBackend.LOCAL,
            resolveTranslationBackend(TranslationBackend.NETWORK.name, false)
        )
        assertEquals(
            TranslationBackend.NETWORK,
            resolveTranslationBackend(TranslationBackend.NETWORK.name, true)
        )
        assertEquals(
            TranslationBackend.LOCAL,
            resolveTranslationBackend(TranslationBackend.SELF_HOSTED.name, true, false)
        )
        assertEquals(
            TranslationBackend.SELF_HOSTED,
            resolveTranslationBackend(TranslationBackend.SELF_HOSTED.name, false, true)
        )
        assertEquals(
            TranslationBackend.SELF_HOSTED_V4,
            resolveTranslationBackend(TranslationBackend.SELF_HOSTED_V4.name, false, true)
        )
        assertEquals(
            TranslationBackend.EMBEDDED_V4,
            resolveTranslationBackend(TranslationBackend.EMBEDDED_V4.name, false, false, true)
        )
    }

    @Test
    fun networkBaseUrlAcceptsBaseOrBatchEndpointAndStoresBaseOnly() {
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(" http://192.168.0.4:8090/ ")
        )
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(
                "http://192.168.0.4:8090/api/v1/translation-batches/"
            )
        )
        assertEquals(
            "https://api-dev.pnutsai.com",
            normalizeNetworkBaseUrl(
                "https://api-dev.pnutsai.com/api/v1/translate/regions/"
            )
        )
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(
                "http://192.168.0.4:8090/api/v2/translate/groups/"
            )
        )
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(
                "http://192.168.0.4:8090/api/v3/translate/layout-plan/"
            )
        )
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(
                "http://192.168.0.4:8090/api/v4/translate/layout-plan/"
            )
        )
        assertEquals(
            "http://192.168.0.4:8090",
            normalizeNetworkBaseUrl(
                "http://192.168.0.4:8090/api/v4/translate/gemini-native/layout-plan/"
            )
        )
    }

    @Test
    fun networkBaseUrlRejectsInvalidOrCredentialBearingValues() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNetworkBaseUrl("192.168.0.4:8090")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeNetworkBaseUrl("http://user:password@192.168.0.4:8090")
        }
    }

    @Test
    fun openAiCompatibleBaseUrlAcceptsBaseOrChatCompletionsEndpoint() {
        assertEquals(
            "https://api.openlux.ai/v1",
            normalizeOpenAiBaseUrl("https://api.openlux.ai/v1")
        )
        assertEquals(
            "https://api.openlux.ai/v1",
            normalizeOpenAiBaseUrl("https://api.openlux.ai/v1/chat/completions/")
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeOpenAiBaseUrl("http://api.openlux.ai/v1")
        }
    }

    @Test
    fun proxyUrlAcceptsExplicitHttpAndSocksEndpointsOrDirectMode() {
        assertEquals("", normalizeProxyUrl("  "))
        assertEquals("http://127.0.0.1:7897", normalizeProxyUrl(" http://127.0.0.1:7897/ "))
        assertEquals("socks5://192.168.0.2:1080", normalizeProxyUrl("socks5://192.168.0.2:1080"))
    }

    @Test
    fun proxyUrlRejectsMissingPortAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProxyUrl("http://127.0.0.1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProxyUrl("http://user:secret@127.0.0.1:7897")
        }
    }

    @Test
    fun defaultBatchPreservesOrderAndIsolatesFailures() = runBlocking {
        val provider = FakeProvider("test") { request ->
            if (request.regionId == "region-1") error("unavailable")
            result(request, "translated:${request.text}", "test")
        }

        val batch = provider.translateBatch(requests())

        assertEquals(listOf("region-0"), batch.results.map { it.regionId })
        assertEquals(listOf("translated:first"), batch.results.map { it.translatedText })
        assertEquals(listOf("region-1"), batch.failures.map { it.regionId })
    }

    @Test
    fun localBackendDoesNotCreateNetworkProvider() = runBlocking {
        var networkCreated = false
        val router = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.LOCAL },
            localProvider = FakeProvider("local") { request ->
                result(request, "local:${request.text}", "local")
            },
            networkProvider = {
                networkCreated = true
                FakeProvider("network") { request ->
                    result(request, "network:${request.text}", "network")
                }
            },
            resultValidator = { _, _ -> true }
        )

        val batch = router.translateBatch(requests())

        assertEquals(listOf("local:first", "local:second"), batch.results.map { it.translatedText })
        assertTrue(batch.failures.isEmpty())
        assertEquals(false, networkCreated)
    }

    @Test
    fun networkPartialResultFallsBackOnlyMissingRegion() = runBlocking {
        val localRequests = mutableListOf<String>()
        val network = object : TranslationProvider {
            override val id = "network"

            override suspend fun translate(request: TranslationRequest): TranslationResult =
                error("batch only")

            override suspend fun translateBatch(
                requests: List<TranslationRequest>
            ) = TranslationBatchResult(
                results = listOf(result(requests[0], "network:first", id)),
                failures = listOf(
                    TranslationFailure(
                        regionId = requests[1].regionId,
                        code = "PROVIDER_TIMEOUT",
                        message = "timed out",
                        retryable = true
                    )
                )
            )
        }
        val router = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.NETWORK },
            localProvider = FakeProvider("local") { request ->
                localRequests += request.regionId
                result(request, "local:${request.text}", "local")
            },
            networkProvider = { network },
            resultValidator = { _, _ -> true }
        )

        val batch = router.translateBatch(requests())

        assertEquals(listOf("network:first", "local:second"), batch.results.map { it.translatedText })
        assertEquals(listOf("region-1"), localRequests)
        assertTrue(batch.failures.isEmpty())
    }

    @Test
    fun invalidNetworkResultFallsBackToLocal() = runBlocking {
        val router = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.NETWORK },
            localProvider = FakeProvider("local") { request ->
                result(request, "local:${request.text}", "local")
            },
            networkProvider = {
                FakeProvider("network") { request ->
                    result(request, "invalid", "network")
                }
            },
            resultValidator = { _, candidate -> candidate.translatedText != "invalid" }
        )

        val batch = router.translateBatch(requests())

        assertEquals(listOf("local:first", "local:second"), batch.results.map { it.translatedText })
        assertTrue(batch.failures.isEmpty())
    }

    @Test
    fun networkResponseOrderDoesNotChangeRequestOrder() = runBlocking {
        val network = object : TranslationProvider {
            override val id = "network"

            override suspend fun translate(request: TranslationRequest): TranslationResult =
                error("batch only")

            override suspend fun translateBatch(
                requests: List<TranslationRequest>
            ) = TranslationBatchResult(
                requests.reversed().map { request ->
                    result(request, "network:${request.text}", id)
                }
            )
        }
        val router = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.NETWORK },
            localProvider = FakeProvider("local") { request ->
                result(request, "local:${request.text}", "local")
            },
            networkProvider = { network },
            resultValidator = { _, _ -> true }
        )

        val batch = router.translateBatch(requests())

        assertEquals(listOf("region-0", "region-1"), batch.results.map { it.regionId })
        assertEquals(listOf("network:first", "network:second"), batch.results.map { it.translatedText })
    }

    @Test
    fun cancellationDoesNotTriggerLocalFallback() = runBlocking {
        var localCalls = 0
        val router = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.NETWORK },
            localProvider = FakeProvider("local") { request ->
                localCalls++
                result(request, request.text, "local")
            },
            networkProvider = {
                object : TranslationProvider {
                    override val id = "network"
                    override suspend fun translate(request: TranslationRequest): TranslationResult {
                        throw CancellationException("stale generation")
                    }

                    override suspend fun translateBatch(
                        requests: List<TranslationRequest>
                    ): TranslationBatchResult = throw CancellationException("stale generation")
                }
            },
            resultValidator = { _, _ -> true }
        )

        var cancelled = false
        try {
            router.translateBatch(requests())
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(0, localCalls)
    }

    @Test
    fun openAiBaseUrlRequiresCredentialFreeHttps() {
        assertEquals(
            "https://api.openai.com/v1",
            normalizeOpenAiBaseUrl(" https://api.openai.com/v1/ ")
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeOpenAiBaseUrl("http://api.openai.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeOpenAiBaseUrl("https://secret@api.openai.com/v1")
        }
    }

    private fun requests() = listOf("first", "second").mapIndexed { index, text ->
        TranslationRequest(
            requestId = "request-1",
            regionId = "region-$index",
            text = text,
            mode = TranslationMode.AUTO_BIDIRECTIONAL,
            sourceLanguage = "en",
            targetLanguage = "zh"
        )
    }

    private fun result(
        request: TranslationRequest,
        text: String,
        provider: String
    ) = TranslationResult(
        regionId = request.regionId,
        translatedText = text,
        provider = provider,
        targetLanguage = request.targetLanguage
    )

    private class FakeProvider(
        override val id: String,
        private val handler: suspend (TranslationRequest) -> TranslationResult
    ) : TranslationProvider {
        override suspend fun translate(request: TranslationRequest): TranslationResult =
            handler(request)
    }
}
