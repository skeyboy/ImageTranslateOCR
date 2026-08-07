package com.example.imagetranslate.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class LocalTranslationProvider(
    private val maximumConcurrency: Int = DEFAULT_MAXIMUM_CONCURRENCY,
    private val translateOne: suspend (TranslationRequest) -> TranslationResult
) : TranslationProvider {
    init {
        require(maximumConcurrency > 0) { "maximumConcurrency must be positive" }
    }

    override val id: String = "local"

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        translateOne(request)

    override suspend fun translateBatch(
        requests: List<TranslationRequest>
    ): TranslationBatchResult = coroutineScope {
        if (requests.isEmpty()) return@coroutineScope TranslationBatchResult(emptyList())
        val semaphore = Semaphore(maximumConcurrency)
        val outcomes = requests.map { request ->
            async {
                semaphore.withPermit {
                    try {
                        LocalOutcome.Success(translate(request))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: TranslationProviderException) {
                        LocalOutcome.Failed(error.failure.copy(regionId = request.regionId))
                    } catch (error: Exception) {
                        LocalOutcome.Failed(
                            TranslationFailure(
                                regionId = request.regionId,
                                code = "LOCAL_TRANSLATION_FAILED",
                                message = error.message ?: "Local translation failed",
                                retryable = false,
                                cause = error
                            )
                        )
                    }
                }
            }
        }.awaitAll()
        TranslationBatchResult(
            results = outcomes.mapNotNull { (it as? LocalOutcome.Success)?.result },
            failures = outcomes.mapNotNull { (it as? LocalOutcome.Failed)?.failure }
        )
    }

    private sealed interface LocalOutcome {
        data class Success(val result: TranslationResult) : LocalOutcome
        data class Failed(val failure: TranslationFailure) : LocalOutcome
    }

    private companion object {
        const val DEFAULT_MAXIMUM_CONCURRENCY = 3
    }
}
