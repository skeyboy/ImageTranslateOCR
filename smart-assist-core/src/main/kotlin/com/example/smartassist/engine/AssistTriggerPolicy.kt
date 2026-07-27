package com.example.smartassist.engine

import com.example.smartassist.api.AssistDecisionReason
import com.example.smartassist.api.AssistRequest

internal data class AssistTriggerDecision(
    val shouldAnalyze: Boolean,
    val reasons: List<AssistDecisionReason>
)

internal object AssistTriggerPolicy {
    fun evaluate(request: AssistRequest): AssistTriggerDecision {
        if (!request.options.enabled) {
            return AssistTriggerDecision(false, listOf(AssistDecisionReason.DISABLED))
        }
        if (request.tracks.isEmpty()) {
            return AssistTriggerDecision(false, listOf(AssistDecisionReason.EMPTY_INPUT))
        }
        if (request.options.forceAnalysis) {
            return AssistTriggerDecision(true, listOf(AssistDecisionReason.USER_REQUESTED))
        }

        val reasons = buildList {
            if (request.options.enableOcrReview && request.tracks.any { track ->
                    track.consensusScore == null ||
                        track.consensusScore < request.options.minimumReviewConfidence
                }
            ) {
                add(AssistDecisionReason.LOW_CONFIDENCE_TEXT)
            }
            if (request.options.enableContextGrouping && request.tracks.size >= 2) {
                add(AssistDecisionReason.CONTEXT_GROUPING)
            }
            if (request.options.enableDisplayOptimization && request.tracks.any { track ->
                    !track.translatedText.isNullOrBlank()
                }
            ) {
                add(AssistDecisionReason.DISPLAY_OPTIMIZATION)
            }
        }
        return if (reasons.isEmpty()) {
            AssistTriggerDecision(false, listOf(AssistDecisionReason.NO_ELIGIBLE_WORK))
        } else {
            AssistTriggerDecision(true, reasons)
        }
    }
}
