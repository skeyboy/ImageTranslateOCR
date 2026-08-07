package com.example.imagetranslate.translate

import kotlinx.coroutines.CancellationException

internal class SwitchingTranslationProvider(
    private val selectedBackend: () -> TranslationBackend,
    private val localProvider: TranslationProvider,
    private val networkProvider: (() -> TranslationProvider)?,
    private val resultValidator: (TranslationRequest, TranslationResult) -> Boolean,
    private val onNetworkFallback: (List<TranslationFailure>) -> Unit = {}
) : TranslationProvider {
    override val id: String = "switching"

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val batch = translateBatch(listOf(request))
        return batch.results.singleOrNull()
            ?: throw TranslationProviderException(
                batch.failures.singleOrNull()
                    ?: TranslationFailure(
                        regionId = request.regionId,
                        code = "TRANSLATION_FAILED",
                        message = "No translation result was returned",
                        retryable = false
                    )
            )
    }

    override suspend fun translateBatch(
        requests: List<TranslationRequest>
    ): TranslationBatchResult {
        if (requests.isEmpty()) return TranslationBatchResult(emptyList())
        require(requests.map(TranslationRequest::regionId).distinct().size == requests.size) {
            "Translation requests contain duplicate region IDs"
        }
        if (selectedBackend() == TranslationBackend.LOCAL || networkProvider == null) {
            return validatedLocalResult(requests)
        }

        val networkBatch = try {
            networkProvider.invoke().translateBatch(requests)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            TranslationBatchResult(
                results = emptyList(),
                failures = requests.map { request ->
                    TranslationFailure(
                        regionId = request.regionId,
                        code = "NETWORK_TRANSLATION_FAILED",
                        message = error.message ?: "Network translation failed",
                        retryable = true,
                        cause = error
                    )
                }
            )
        }
        val expectedIds = requests.mapTo(mutableSetOf(), TranslationRequest::regionId)
        if (networkBatch.results.any { it.regionId !in expectedIds } ||
            networkBatch.failures.any { it.regionId !in expectedIds }
        ) {
            return validatedLocalResult(requests)
        }

        val requestsById = requests.associateBy(TranslationRequest::regionId)
        val acceptedNetworkResults = networkBatch.results
            .filter { result ->
                requestsById[result.regionId]?.let { request ->
                    resultValidator(request, result)
                } == true
            }
            .associateBy(TranslationResult::regionId)
        val fallbackRequests = requests.filter { it.regionId !in acceptedNetworkResults }
        val networkFailures = networkBatch.failures.associateBy(TranslationFailure::regionId)
        if (fallbackRequests.isNotEmpty()) {
            onNetworkFallback(
                fallbackRequests.map { request ->
                    networkFailures[request.regionId]
                        ?: TranslationFailure(
                            regionId = request.regionId,
                            code = "INVALID_PROVIDER_RESULT",
                            message = "Network translation result was missing or invalid",
                            retryable = true
                        )
                }
            )
        }
        val localBatch = validatedLocalResult(fallbackRequests)
        val localResults = localBatch.results.associateBy(TranslationResult::regionId)
        val merged = requests.mapNotNull { request ->
            acceptedNetworkResults[request.regionId] ?: localResults[request.regionId]
        }
        val mergedIds = merged.mapTo(mutableSetOf(), TranslationResult::regionId)
        val localFailures = localBatch.failures.associateBy(TranslationFailure::regionId)
        val failures = requests.filter { it.regionId !in mergedIds }.map { request ->
            localFailures[request.regionId]
                ?: networkFailures[request.regionId]
                ?: TranslationFailure(
                    regionId = request.regionId,
                    code = "INVALID_PROVIDER_RESULT",
                    message = "Translation result was missing or invalid",
                    retryable = false
                )
        }
        return TranslationBatchResult(merged, failures)
    }

    private suspend fun validatedLocalResult(
        requests: List<TranslationRequest>
    ): TranslationBatchResult {
        if (requests.isEmpty()) return TranslationBatchResult(emptyList())
        val batch = localProvider.translateBatch(requests)
        val requestsById = requests.associateBy(TranslationRequest::regionId)
        val accepted = batch.results.filter { result ->
            requestsById[result.regionId]?.let { request ->
                resultValidator(request, result)
            } == true
        }
        val acceptedIds = accepted.mapTo(mutableSetOf(), TranslationResult::regionId)
        val failuresById = batch.failures.associateBy(TranslationFailure::regionId)
        val failures = requests.filter { it.regionId !in acceptedIds }.map { request ->
            failuresById[request.regionId]
                ?: TranslationFailure(
                    regionId = request.regionId,
                    code = "INVALID_LOCAL_RESULT",
                    message = "Local translation result was missing or invalid",
                    retryable = false
                )
        }
        return TranslationBatchResult(accepted, failures)
    }

    override fun close() {
        runCatching(localProvider::close)
    }
}
