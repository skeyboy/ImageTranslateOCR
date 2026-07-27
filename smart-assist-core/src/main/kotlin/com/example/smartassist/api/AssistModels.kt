package com.example.smartassist.api

enum class AssistScript {
    HAN,
    LATIN,
    MIXED,
    NUMERIC,
    UNKNOWN
}

enum class AssistScene {
    READING,
    SETTINGS,
    CHAT,
    COMMERCE,
    CODE,
    DYNAMIC,
    UNKNOWN
}

enum class AssistTrackRole {
    TITLE,
    BODY,
    LABEL,
    VALUE,
    ACTION,
    NAVIGATION,
    CODE,
    URL,
    BRAND,
    UNKNOWN
}

enum class AssistLayoutMode {
    KEEP,
    SINGLE_LINE,
    MULTI_LINE,
    COMPACT
}

enum class AssistStatus {
    COMPLETED,
    SKIPPED,
    FAILED
}

enum class AssistDecisionReason {
    USER_REQUESTED,
    LOW_CONFIDENCE_TEXT,
    CONTEXT_GROUPING,
    DISPLAY_OPTIMIZATION,
    DISABLED,
    EMPTY_INPUT,
    NO_ELIGIBLE_WORK,
    PROVIDER_UNAVAILABLE,
    SUPERSEDED,
    ENGINE_CLOSED,
    PROVIDER_FAILED
}

enum class AssistSuggestionEvidence {
    OCR_ALTERNATIVE,
    PROTECTED_CONTENT_RULE,
    CONTEXT_RULE,
    LAYOUT_RULE,
    MODEL
}

data class AssistRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(left >= 0 && top >= 0) { "AssistRect coordinates must be non-negative" }
        require(right > left && bottom > top) { "AssistRect must have positive size" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun horizontalOverlap(other: AssistRect): Int =
        (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
}

data class AssistTextAlternative(
    val text: String,
    val confidence: Float
) {
    init {
        require(text.isNotBlank()) { "Alternative text must not be blank" }
        require(confidence in 0f..1f) { "Alternative confidence must be between 0 and 1" }
    }
}

data class AssistTextTrack(
    val trackId: Long,
    val text: String,
    val bounds: AssistRect,
    val script: AssistScript = AssistScript.UNKNOWN,
    val consensusScore: Float? = null,
    val translatedText: String? = null,
    val alternatives: List<AssistTextAlternative> = emptyList(),
    val roleHint: AssistTrackRole? = null
) {
    init {
        require(trackId > 0) { "Track id must be positive" }
        require(text.isNotBlank()) { "Track text must not be blank" }
        require(consensusScore == null || consensusScore in 0f..1f) {
            "Consensus score must be between 0 and 1"
        }
    }
}

class AssistImageEvidence(
    val evidenceId: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    encodedBytes: ByteArray
) {
    private val content = encodedBytes.copyOf()

    init {
        require(evidenceId.isNotBlank()) { "Evidence id must not be blank" }
        require(mimeType in SUPPORTED_MIME_TYPES) { "Unsupported evidence MIME type: $mimeType" }
        require(width > 0 && height > 0) { "Evidence dimensions must be positive" }
        require(content.isNotEmpty()) { "Evidence content must not be empty" }
    }

    val byteSize: Int get() = content.size

    fun copyEncodedBytes(): ByteArray = content.copyOf()

    private companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    }
}

data class AssistOptions(
    val enabled: Boolean = false,
    val forceAnalysis: Boolean = false,
    val enableOcrReview: Boolean = true,
    val enableContextGrouping: Boolean = true,
    val enableDisplayOptimization: Boolean = true,
    val minimumReviewConfidence: Float = 0.72f,
    val protectedConfidence: Float = 0.88f,
    val minimumAlternativeImprovement: Float = 0.08f,
    val maximumCorrectionEditRatio: Float = 0.45f
) {
    init {
        require(minimumReviewConfidence in 0f..1f)
        require(protectedConfidence in 0f..1f)
        require(minimumAlternativeImprovement in 0f..1f)
        require(maximumCorrectionEditRatio in 0f..1f)
        require(protectedConfidence >= minimumReviewConfidence) {
            "Protected confidence must not be lower than review confidence"
        }
    }
}

data class AssistRequest(
    val requestId: String,
    val generation: Long,
    val viewportSignature: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val tracks: List<AssistTextTrack>,
    val evidence: List<AssistImageEvidence> = emptyList(),
    val options: AssistOptions = AssistOptions()
) {
    init {
        require(requestId.isNotBlank()) { "Request id must not be blank" }
        require(generation >= 0) { "Generation must not be negative" }
        require(viewportSignature.isNotBlank()) { "Viewport signature must not be blank" }
        require(viewportWidth > 0 && viewportHeight > 0) { "Viewport size must be positive" }
        require(tracks.map(AssistTextTrack::trackId).distinct().size == tracks.size) {
            "Track ids must be unique within one request"
        }
        require(tracks.all { it.bounds.right <= viewportWidth && it.bounds.bottom <= viewportHeight }) {
            "Track bounds must fit inside the viewport"
        }
    }
}

data class AssistLayoutHint(
    val mode: AssistLayoutMode,
    val preferredMaxLines: Int,
    val minimumTextScale: Float = 0.72f
) {
    init {
        require(preferredMaxLines in 1..6) { "Preferred max lines must be between 1 and 6" }
        require(minimumTextScale in 0.5f..1f) { "Minimum text scale must be between 0.5 and 1" }
    }
}

data class AssistTextGroup(
    val groupId: String,
    val trackIds: List<Long>,
    val role: AssistTrackRole,
    val readingOrder: Int,
    val contextText: String
) {
    init {
        require(groupId.isNotBlank()) { "Group id must not be blank" }
        require(trackIds.isNotEmpty()) { "A group must contain at least one track" }
        require(trackIds.distinct().size == trackIds.size) { "Group track ids must be unique" }
        require(readingOrder >= 0) { "Reading order must not be negative" }
    }
}

data class AssistSuggestion(
    val trackId: Long,
    val correctedText: String? = null,
    val translationHint: String? = null,
    val layoutHint: AssistLayoutHint? = null,
    val protectTranslation: Boolean = false,
    val confidence: Float,
    val evidence: AssistSuggestionEvidence
) {
    init {
        require(confidence in 0f..1f) { "Suggestion confidence must be between 0 and 1" }
        require(
            correctedText != null || translationHint != null ||
                layoutHint != null || protectTranslation
        ) { "A suggestion must contain at least one proposed change" }
    }
}

data class AssistCapabilities(
    val providerId: String,
    val providerVersion: String,
    val available: Boolean,
    val offline: Boolean,
    val supportsText: Boolean,
    val supportsImages: Boolean,
    val supportsBackgroundExecution: Boolean,
    val requiresModelDownload: Boolean,
    val unavailableReason: String? = null
)

data class AssistDiagnostics(
    val triggered: Boolean,
    val providerId: String,
    val cacheHit: Boolean,
    val inputTrackCount: Int,
    val acceptedSuggestionCount: Int,
    val rejectedSuggestionCount: Int,
    val reasons: List<AssistDecisionReason>,
    val failureMessage: String? = null
)

data class AssistResult(
    val requestId: String,
    val generation: Long,
    val status: AssistStatus,
    val scene: AssistScene,
    val groups: List<AssistTextGroup>,
    val suggestions: List<AssistSuggestion>,
    val diagnostics: AssistDiagnostics
)
