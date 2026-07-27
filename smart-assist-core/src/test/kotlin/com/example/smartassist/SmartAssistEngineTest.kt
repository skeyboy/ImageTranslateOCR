package com.example.smartassist

import com.example.smartassist.api.AssistCapabilities
import com.example.smartassist.api.AssistDecisionReason
import com.example.smartassist.api.AssistLayoutMode
import com.example.smartassist.api.AssistOptions
import com.example.smartassist.api.AssistProvider
import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistResult
import com.example.smartassist.api.AssistScene
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistStatus
import com.example.smartassist.api.AssistSuggestion
import com.example.smartassist.api.AssistSuggestionEvidence
import com.example.smartassist.api.AssistTextAlternative
import com.example.smartassist.api.AssistTextTrack
import com.example.smartassist.api.ProviderAssistResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmartAssistEngineTest {
    @Test
    fun disabledRequestIsFullyBypassed() {
        SmartAssistEngineFactory.create().use { engine ->
            val result = runSuspend {
                engine.analyze(request(options = AssistOptions(enabled = false)))
            }

            assertEquals(AssistStatus.SKIPPED, result.status)
            assertEquals(listOf(AssistDecisionReason.DISABLED), result.diagnostics.reasons)
            assertFalse(result.diagnostics.triggered)
            assertTrue(result.groups.isEmpty())
            assertTrue(result.suggestions.isEmpty())
        }
    }

    @Test
    fun deterministicProviderCorrectsOnlyLowConfidenceAlternative() {
        val uncertain = AssistTextTrack(
            trackId = 1,
            text = "Sett1ngs",
            bounds = AssistRect(60, 100, 420, 160),
            script = AssistScript.LATIN,
            consensusScore = 0.51f,
            alternatives = listOf(AssistTextAlternative("Settings", 0.86f))
        )

        SmartAssistEngineFactory.create().use { engine ->
            val result = runSuspend { engine.analyze(request(tracks = listOf(uncertain))) }

            assertEquals(AssistStatus.COMPLETED, result.status)
            val correction = result.suggestions.single { it.correctedText != null }
            assertEquals("Settings", correction.correctedText)
            assertEquals(AssistSuggestionEvidence.OCR_ALTERNATIVE, correction.evidence)
        }
    }

    @Test
    fun highConfidenceTextCannotBeRewritten() {
        val confident = AssistTextTrack(
            trackId = 1,
            text = "Settings",
            bounds = AssistRect(60, 100, 420, 160),
            script = AssistScript.LATIN,
            consensusScore = 0.96f,
            alternatives = listOf(AssistTextAlternative("Sett1ngs", 0.99f))
        )
        val provider = StaticProvider(
            suggestions = listOf(
                AssistSuggestion(
                    trackId = 1,
                    correctedText = "Sett1ngs",
                    confidence = 0.99f,
                    evidence = AssistSuggestionEvidence.MODEL
                )
            )
        )

        SmartAssistEngineFactory.create(provider).use { engine ->
            val result = runSuspend {
                engine.analyze(request(
                    tracks = listOf(confident),
                    options = AssistOptions(enabled = true, forceAnalysis = true)
                ))
            }

            assertEquals(AssistStatus.COMPLETED, result.status)
            assertTrue(result.suggestions.isEmpty())
            assertTrue(result.diagnostics.rejectedSuggestionCount > 0)
        }
    }

    @Test
    fun urlIsProtectedAndLongTranslationGetsLayoutHint() {
        val url = track(
            trackId = 1,
            text = "https://example.com/docs",
            bounds = AssistRect(40, 100, 700, 160)
        )
        val translated = track(
            trackId = 2,
            text = "Save",
            bounds = AssistRect(40, 260, 260, 320),
            translatedText = "保存当前页面中的全部更改"
        )

        SmartAssistEngineFactory.create().use { engine ->
            val result = runSuspend { engine.analyze(request(tracks = listOf(url, translated))) }

            assertTrue(result.suggestions.any { it.trackId == 1L && it.protectTranslation })
            assertTrue(result.suggestions.any {
                it.trackId == 2L && it.layoutHint?.mode == AssistLayoutMode.COMPACT
            })
        }
    }

    @Test
    fun repeatedViewportUsesBoundedResultCacheWithoutReusingRequestIdentity() {
        SmartAssistEngineFactory.create().use { engine ->
            val first = runSuspend { engine.analyze(request(requestId = "first", generation = 1)) }
            val second = runSuspend { engine.analyze(request(requestId = "second", generation = 2)) }

            assertFalse(first.diagnostics.cacheHit)
            assertTrue(second.diagnostics.cacheHit)
            assertEquals("second", second.requestId)
            assertEquals(2L, second.generation)
        }
    }

    @Test
    fun unavailableProviderFallsBackWithoutFailure() {
        SmartAssistEngineFactory.create(UnavailableProvider).use { engine ->
            val result = runSuspend { engine.analyze(request()) }

            assertEquals(AssistStatus.SKIPPED, result.status)
            assertTrue(AssistDecisionReason.PROVIDER_UNAVAILABLE in result.diagnostics.reasons)
        }
    }

    @Test
    fun providerFailureIsTypedAndDoesNotEscape() {
        SmartAssistEngineFactory.create(FailingProvider).use { engine ->
            val result = runSuspend { engine.analyze(request()) }

            assertEquals(AssistStatus.FAILED, result.status)
            assertTrue(AssistDecisionReason.PROVIDER_FAILED in result.diagnostics.reasons)
            assertEquals("offline provider failed", result.diagnostics.failureMessage)
        }
    }

    @Test
    fun olderConcurrentRequestIsMarkedSuperseded() {
        val provider = BlockingProvider()
        val engine = SmartAssistEngineFactory.create(provider)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstFuture = executor.submit(Callable<AssistResult> {
                runSuspend { engine.analyze(request(requestId = "first", generation = 1)) }
            })
            assertTrue(provider.firstRequestStarted.await(2, TimeUnit.SECONDS))

            val second = runSuspend {
                engine.analyze(request(requestId = "second", generation = 2))
            }
            provider.releaseFirstRequest.countDown()
            val first = firstFuture.get(2, TimeUnit.SECONDS)

            assertEquals(AssistStatus.COMPLETED, second.status)
            assertEquals(AssistStatus.SKIPPED, first.status)
            assertTrue(AssistDecisionReason.SUPERSEDED in first.diagnostics.reasons)
        } finally {
            provider.releaseFirstRequest.countDown()
            executor.shutdownNow()
            engine.close()
        }
    }

    @Test
    fun skippedNewRequestStillSupersedesOlderWork() {
        val provider = BlockingProvider()
        val engine = SmartAssistEngineFactory.create(provider)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstFuture = executor.submit(Callable<AssistResult> {
                runSuspend { engine.analyze(request(requestId = "first", generation = 1)) }
            })
            assertTrue(provider.firstRequestStarted.await(2, TimeUnit.SECONDS))

            val disabled = runSuspend {
                engine.analyze(
                    request(
                        requestId = "disabled",
                        generation = 2,
                        options = AssistOptions(enabled = false)
                    )
                )
            }
            provider.releaseFirstRequest.countDown()
            val first = firstFuture.get(2, TimeUnit.SECONDS)

            assertEquals(AssistStatus.SKIPPED, disabled.status)
            assertTrue(AssistDecisionReason.DISABLED in disabled.diagnostics.reasons)
            assertEquals(AssistStatus.SKIPPED, first.status)
            assertTrue(AssistDecisionReason.SUPERSEDED in first.diagnostics.reasons)
        } finally {
            provider.releaseFirstRequest.countDown()
            executor.shutdownNow()
            engine.close()
        }
    }

    private class StaticProvider(
        private val suggestions: List<AssistSuggestion>
    ) : AssistProvider {
        override fun capabilities(): AssistCapabilities = capabilities("static")

        override suspend fun analyze(request: AssistRequest): ProviderAssistResult =
            ProviderAssistResult(AssistScene.UNKNOWN, emptyList(), suggestions)
    }

    private object UnavailableProvider : AssistProvider {
        override fun capabilities(): AssistCapabilities = capabilities(
            providerId = "unavailable",
            available = false
        )

        override suspend fun analyze(request: AssistRequest): ProviderAssistResult =
            error("Unavailable provider must not be called")
    }

    private object FailingProvider : AssistProvider {
        override fun capabilities(): AssistCapabilities = capabilities("failing")

        override suspend fun analyze(request: AssistRequest): ProviderAssistResult =
            error("offline provider failed")
    }

    private class BlockingProvider : AssistProvider {
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)

        override fun capabilities(): AssistCapabilities = capabilities("blocking")

        override suspend fun analyze(request: AssistRequest): ProviderAssistResult {
            if (request.requestId == "first") {
                firstRequestStarted.countDown()
                releaseFirstRequest.await(2, TimeUnit.SECONDS)
            }
            return ProviderAssistResult(AssistScene.UNKNOWN, emptyList(), emptyList())
        }
    }

    private companion object {
        fun capabilities(
            providerId: String,
            available: Boolean = true
        ): AssistCapabilities = AssistCapabilities(
            providerId = providerId,
            providerVersion = "1",
            available = available,
            offline = true,
            supportsText = true,
            supportsImages = false,
            supportsBackgroundExecution = true,
            requiresModelDownload = false,
            unavailableReason = if (available) null else "not installed"
        )
    }
}
