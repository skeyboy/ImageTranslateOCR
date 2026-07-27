package com.example.smartassist.api

interface AssistProvider : AutoCloseable {
    fun capabilities(): AssistCapabilities

    suspend fun analyze(request: AssistRequest): ProviderAssistResult

    override fun close() = Unit
}

data class ProviderAssistResult(
    val scene: AssistScene,
    val groups: List<AssistTextGroup>,
    val suggestions: List<AssistSuggestion>
)
