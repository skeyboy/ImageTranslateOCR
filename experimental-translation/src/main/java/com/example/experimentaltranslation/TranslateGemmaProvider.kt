package com.example.experimentaltranslation

import android.content.Context
import android.os.SystemClock
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal class TranslateGemmaProvider(
    context: Context,
    private val modelDirectory: File
) : ExperimentalTranslationProvider {
    override val engine = ExperimentalTranslationEngine.TRANSLATEGEMMA_4B
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var inference: LlmInference? = null

    override suspend fun translate(
        request: ExperimentalTranslationRequest
    ): ExperimentalTranslationResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val response = inference().generateResponse(prompt(request)).trim()
            check(response.isNotBlank()) { "TranslateGemma produced an empty translation" }
            ExperimentalTranslationResult(
                text = stripResponseEnvelope(response),
                engine = engine,
                inferenceMs = SystemClock.elapsedRealtime() - startedAt
            )
        }
    }

    private fun inference(): LlmInference = inference ?: run {
        val model = File(modelDirectory, MODEL_FILE)
        check(model.isFile) { "TranslateGemma model is not installed" }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            .setMaxTokens(MAXIMUM_CONTEXT_TOKENS)
            .setMaxTopK(1)
            .build()
        LlmInference.createFromOptions(appContext, options).also { inference = it }
    }

    private fun prompt(request: ExperimentalTranslationRequest): String {
        val templateFile = File(modelDirectory, PROMPT_TEMPLATE_FILE)
        val template = templateFile.takeIf(File::isFile)?.readText() ?: DEFAULT_PROMPT_TEMPLATE
        return template
            .replace("{source_language}", request.sourceLanguage.code)
            .replace("{target_language}", request.targetLanguage.code)
            .replace("{source_language_name}", request.sourceLanguage.displayName)
            .replace("{target_language_name}", request.targetLanguage.displayName)
            .replace("{text}", request.text)
    }

    private val TranslationLanguage.displayName: String
        get() = when (this) {
            TranslationLanguage.CHINESE -> "Chinese"
            TranslationLanguage.ENGLISH -> "English"
        }

    private fun stripResponseEnvelope(response: String): String = response
        .substringBefore("<end_of_turn>")
        .removePrefix("model\n")
        .trim()

    override fun close() {
        inference?.close()
        inference = null
    }

    private companion object {
        const val MODEL_FILE = "translategemma-4b.task"
        const val PROMPT_TEMPLATE_FILE = "prompt_template.txt"
        const val MAXIMUM_CONTEXT_TOKENS = 512
        const val DEFAULT_PROMPT_TEMPLATE =
            "<start_of_turn>user\nYou are a professional {source_language_name} " +
                "({source_language}) to {target_language_name} ({target_language}) " +
                "translator. Your goal is to accurately convey the meaning and nuances of the " +
                "original {source_language_name} text while adhering to " +
                "{target_language_name} grammar, vocabulary, and " +
                "cultural sensitivities. Produce only the translation, without any additional " +
                "explanations or commentary. Please translate the following text from " +
                "{source_language_name} to {target_language_name}:\n{text}<end_of_turn>\n" +
                "<start_of_turn>model\n"
    }
}
