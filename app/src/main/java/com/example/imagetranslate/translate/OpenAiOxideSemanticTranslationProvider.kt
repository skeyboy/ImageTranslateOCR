package com.example.imagetranslate.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class OpenAiOxideSemanticTranslationProvider(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : SemanticTranslationProvider {
    private val codec = SelfHostedSemanticTranslationProvider("https://127.0.0.1", null, 4)

    override suspend fun translate(
        request: SemanticTranslationRequest
    ): SemanticTranslationBatchResult = withContext(Dispatchers.IO) {
        val requestJson = codec.requestBodyForTest(request)
        val response = NativeEdgeTranslationBridge.translateOpenAi(
            context = context,
            request = requestJson,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model
        )
        TranslationRequestArchiveStore.recordExchange(
            context = context,
            requestJson = requestJson,
            responseJson = response.toString(),
            provider = TranslationBackendSettings.OPENAI_OXIDE_EDGE_PROVIDER,
            model = model
        )
        codec.parseResponseForTest(response.toString(), request)
    }

    override fun close() = codec.close()
}
