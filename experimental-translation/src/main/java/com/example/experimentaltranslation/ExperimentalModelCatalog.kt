package com.example.experimentaltranslation

data class ExperimentalModelDefinition(
    val engine: ExperimentalTranslationEngine,
    val displayName: String,
    val requiredFiles: Set<String>,
    val suggestedArchiveName: String,
    val defaultDownloadUrl: String? = null
)

object ExperimentalModelCatalog {
    val marian = ExperimentalModelDefinition(
        engine = ExperimentalTranslationEngine.MARIAN_INT8,
        displayName = "Marian INT8",
        requiredFiles = setOf(
            "zh-en/encoder_model_quantized.onnx",
            "zh-en/decoder_model_quantized.onnx",
            "zh-en/source.spm",
            "zh-en/target.spm",
            "zh-en/vocab.json",
            "zh-en/tokenizer.properties",
            "en-zh/encoder_model_quantized.onnx",
            "en-zh/decoder_model_quantized.onnx",
            "en-zh/source.spm",
            "en-zh/target.spm",
            "en-zh/vocab.json",
            "en-zh/tokenizer.properties"
        ),
        suggestedArchiveName = "marian-int8.zip"
    )

    val translateGemma = ExperimentalModelDefinition(
        engine = ExperimentalTranslationEngine.TRANSLATEGEMMA_4B,
        displayName = "TranslateGemma 4B",
        requiredFiles = setOf("translategemma-4b.task"),
        suggestedArchiveName = "translategemma-4b.task or translategemma-4b.zip",
        defaultDownloadUrl =
            "https://huggingface.co/litert-community/TranslateGemma-4B-IT/resolve/main/" +
                "translategemma-4b-it-int8-web.task?download=true"
    )

    fun definition(engine: ExperimentalTranslationEngine): ExperimentalModelDefinition = when (engine) {
        ExperimentalTranslationEngine.MARIAN_INT8 -> marian
        ExperimentalTranslationEngine.TRANSLATEGEMMA_4B -> translateGemma
        ExperimentalTranslationEngine.DISABLED -> error("Disabled has no model definition")
    }
}
