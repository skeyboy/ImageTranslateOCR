package com.example.imagetranslate.translate

import com.example.experimentaltranslation.ExperimentalTranslationEngine
import kotlinx.coroutines.CancellationException

enum class TranslationBackend {
    LOCAL,
    NETWORK,
    SELF_HOSTED
}

enum class TranslationResultStatus {
    TRANSLATED,
    PRESERVED
}

data class TranslationRequest(
    val requestId: String,
    val regionId: String,
    val text: String,
    val mode: TranslationMode,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val experimentalEngine: ExperimentalTranslationEngine =
        ExperimentalTranslationEngine.DISABLED
)

data class TranslationResult(
    val regionId: String,
    val translatedText: String,
    val provider: String,
    val status: TranslationResultStatus = TranslationResultStatus.TRANSLATED,
    val detectedSourceLanguage: String? = null,
    val targetLanguage: String? = null
)

data class TranslationFailure(
    val regionId: String,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val cause: Throwable? = null
)

data class TranslationBatchResult(
    val results: List<TranslationResult>,
    val failures: List<TranslationFailure> = emptyList()
) {
    init {
        require(results.map(TranslationResult::regionId).distinct().size == results.size) {
            "Translation results contain duplicate region IDs"
        }
        require(failures.map(TranslationFailure::regionId).distinct().size == failures.size) {
            "Translation failures contain duplicate region IDs"
        }
        require(
            results.mapTo(mutableSetOf(), TranslationResult::regionId)
                .intersect(failures.mapTo(mutableSetOf(), TranslationFailure::regionId))
                .isEmpty()
        ) { "A translation region cannot be both successful and failed" }
    }
}

class TranslationProviderException(
    val failure: TranslationFailure
) : IllegalStateException(failure.message, failure.cause)

interface TranslationProvider : AutoCloseable {
    val id: String

    suspend fun translate(request: TranslationRequest): TranslationResult

    /**
     * Batch providers may return partial success. Every omitted request must have a failure entry.
     */
    suspend fun translateBatch(requests: List<TranslationRequest>): TranslationBatchResult {
        val results = mutableListOf<TranslationResult>()
        val failures = mutableListOf<TranslationFailure>()
        requests.forEach { request ->
            try {
                results += translate(request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationProviderException) {
                failures += error.failure.copy(regionId = request.regionId)
            } catch (error: Exception) {
                failures += TranslationFailure(
                    regionId = request.regionId,
                    code = "PROVIDER_ERROR",
                    message = error.message ?: "Translation provider failed",
                    retryable = true,
                    cause = error
                )
            }
        }
        return TranslationBatchResult(results, failures)
    }

    override fun close() = Unit
}
