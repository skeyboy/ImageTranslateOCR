package com.example.smartassist.engine

import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistSuggestion
import com.example.smartassist.api.AssistSuggestionEvidence
import com.example.smartassist.api.AssistTextGroup
import com.example.smartassist.api.AssistTrackRole
import com.example.smartassist.api.ProviderAssistResult

internal data class ValidatedAssistResult(
    val groups: List<AssistTextGroup>,
    val suggestions: List<AssistSuggestion>,
    val rejectedSuggestionCount: Int
)

internal object AssistResultValidator {
    fun validate(
        request: AssistRequest,
        providerResult: ProviderAssistResult
    ): ValidatedAssistResult {
        val tracksById = request.tracks.associateBy { it.trackId }
        val assignedTrackIds = mutableSetOf<Long>()
        var rejected = 0

        val groups = providerResult.groups.mapNotNull { group ->
            val validIds = group.trackIds.filter { trackId ->
                trackId in tracksById && assignedTrackIds.add(trackId)
            }
            if (validIds.isEmpty()) {
                rejected++
                null
            } else {
                group.copy(
                    trackIds = validIds,
                    contextText = group.contextText.take(MAXIMUM_CONTEXT_LENGTH)
                )
            }
        }

        val suggestions = providerResult.suggestions.mapNotNull { suggestion ->
            val track = tracksById[suggestion.trackId]
            if (track == null || suggestion.confidence !in 0f..1f) {
                rejected++
                return@mapNotNull null
            }

            var correctedText = suggestion.correctedText?.trim()?.takeIf(String::isNotEmpty)
            var translationHint = suggestion.translationHint?.trim()?.takeIf(String::isNotEmpty)
            var layoutHint = suggestion.layoutHint
            var protectTranslation = suggestion.protectTranslation
            var rejectedFields = 0

            if (correctedText != null) {
                val currentConfidence = track.consensusScore ?: 0f
                val correctionIsAllowed = request.options.enableOcrReview &&
                    currentConfidence < request.options.protectedConfidence &&
                    suggestion.confidence >= request.options.minimumReviewConfidence &&
                    !correctedText.equals(track.text.trim(), ignoreCase = false) &&
                    AssistTextHeuristics.scriptCompatible(track.script, correctedText) &&
                    AssistTextHeuristics.editRatio(track.text, correctedText) <=
                    request.options.maximumCorrectionEditRatio &&
                    (suggestion.evidence != AssistSuggestionEvidence.OCR_ALTERNATIVE ||
                        track.alternatives.any { it.text.trim() == correctedText })
                if (!correctionIsAllowed) {
                    correctedText = null
                    rejectedFields++
                }
            }

            if (translationHint != null && !request.options.enableContextGrouping) {
                translationHint = null
                rejectedFields++
            } else {
                translationHint = translationHint?.take(MAXIMUM_CONTEXT_LENGTH)
            }

            if (layoutHint != null && !request.options.enableDisplayOptimization) {
                layoutHint = null
                rejectedFields++
            }

            if (protectTranslation) {
                val protectedRole = track.roleHint ?: AssistTextHeuristics.classifyRole(
                    track,
                    medianHeight = request.tracks.map { it.bounds.height }.sorted()
                        .let { it[it.size / 2] },
                    viewportHeight = request.viewportHeight
                )
                if (protectedRole !in PROTECTED_ROLES) {
                    protectTranslation = false
                    rejectedFields++
                }
            }

            rejected += rejectedFields
            if (correctedText == null && translationHint == null &&
                layoutHint == null && !protectTranslation
            ) {
                rejected++
                null
            } else {
                suggestion.copy(
                    correctedText = correctedText,
                    translationHint = translationHint,
                    layoutHint = layoutHint,
                    protectTranslation = protectTranslation
                )
            }
        }

        return ValidatedAssistResult(groups, suggestions, rejected)
    }

    private const val MAXIMUM_CONTEXT_LENGTH = 512
    private val PROTECTED_ROLES = setOf(
        AssistTrackRole.URL,
        AssistTrackRole.CODE,
        AssistTrackRole.BRAND
    )
}
