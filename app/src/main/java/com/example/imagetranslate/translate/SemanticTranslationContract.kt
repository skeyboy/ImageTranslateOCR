package com.example.imagetranslate.translate

internal data class TranslationBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(right > left && bottom > top) { "Translation bounds must be non-empty" }
    }
}

internal data class SemanticTranslationRegion(
    val regionId: String,
    val groupId: String,
    val sourceRevision: Long,
    val text: String,
    val sourceLanguage: String?,
    val targetLanguage: String?,
    val readingOrder: Int,
    val blockId: String?,
    val lineIndex: Int?,
    val confidence: Float,
    val bounds: TranslationBounds,
    val componentBounds: List<TranslationBounds> = emptyList(),
    val rawText: String = text,
    val corrections: List<OcrTextCorrection> = emptyList()
)

internal data class SemanticTranslationSource(
    val groupId: String,
    val role: String,
    val translationUnit: String,
    val sourceText: String,
    val memberRegionIds: List<String>,
    val readingOrder: Int,
    val groupingConfidence: Float,
    val groupingEvidence: List<String>,
    val bounds: TranslationBounds,
    val regions: List<SemanticTranslationRegion>,
    val sourceLineCount: Int = regions.size.coerceAtLeast(1),
    val renderSlots: List<TranslationBounds> = listOf(bounds),
    val layoutShape: String = if (renderSlots.size > 1) "FLOW_SLOTS" else "RECT"
)

internal data class SemanticDebugCapture(
    val mimeType: String,
    val dataBase64: String,
    val pixelWidth: Int,
    val pixelHeight: Int
)

internal data class SemanticTranslationTrace(
    val requestId: String,
    val sessionId: String,
    val generation: Long,
    val translationRevision: Long,
    val schemaVersion: Int = 3
)

internal data class SemanticTranslationRequest(
    val requestId: String,
    val sessionId: String,
    val generation: Long,
    val translationRevision: Long,
    val scene: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val mode: TranslationMode,
    val documentText: String,
    val sources: List<SemanticTranslationSource>,
    val debugCapture: SemanticDebugCapture? = null
)

internal data class SemanticLayoutHint(
    val preferredMaxLines: Int,
    val minimumTextScale: Float,
    val maximumTextScale: Float = 1f,
    val lineSpacingMultiplier: Float = 1f,
    val alignment: String,
    val overflowStrategy: String,
    val allowMore: Boolean = false,
    val sourceLineCount: Int = 1,
    val layoutShape: String = "RECT",
    val renderSlots: List<TranslationBounds> = emptyList(),
    val sourceCoverSlots: List<TranslationBounds> = emptyList()
)

internal data class SemanticGroupTranslationResult(
    val groupId: String,
    val sourceGroupIds: List<String> = listOf(groupId),
    val memberRegionIds: List<String> = emptyList(),
    val anchorBounds: TranslationBounds? = null,
    val role: String? = null,
    val groupingConfidence: Float = 1f,
    val translatedText: String?,
    val provider: String,
    val status: TranslationResultStatus,
    val detectedSourceLanguage: String?,
    val targetLanguage: String?,
    val layoutHint: SemanticLayoutHint?
)

internal data class SemanticGroupTranslationFailure(
    val groupId: String,
    val code: String,
    val message: String,
    val retryable: Boolean,
    val cause: Throwable? = null
)

internal data class SemanticTranslationBatchResult(
    val results: List<SemanticGroupTranslationResult>,
    val failures: List<SemanticGroupTranslationFailure> = emptyList()
)

internal interface SemanticTranslationProvider : AutoCloseable {
    suspend fun translate(request: SemanticTranslationRequest): SemanticTranslationBatchResult

    override fun close() = Unit
}
