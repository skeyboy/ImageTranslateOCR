package com.example.smartassist.provider

import com.example.smartassist.api.AssistCapabilities
import com.example.smartassist.api.AssistLayoutHint
import com.example.smartassist.api.AssistLayoutMode
import com.example.smartassist.api.AssistProvider
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistSuggestion
import com.example.smartassist.api.AssistSuggestionEvidence
import com.example.smartassist.api.AssistTextGroup
import com.example.smartassist.api.AssistTextTrack
import com.example.smartassist.api.AssistTrackRole
import com.example.smartassist.api.ProviderAssistResult
import com.example.smartassist.engine.AssistTextHeuristics

class DeterministicAssistProvider : AssistProvider {
    override fun capabilities(): AssistCapabilities = AssistCapabilities(
        providerId = PROVIDER_ID,
        providerVersion = PROVIDER_VERSION,
        available = true,
        offline = true,
        supportsText = true,
        supportsImages = false,
        supportsBackgroundExecution = true,
        requiresModelDownload = false
    )

    override suspend fun analyze(request: AssistRequest): ProviderAssistResult {
        val tracks = request.tracks.sortedWith(
            compareBy<AssistTextTrack> { it.bounds.top }.thenBy { it.bounds.left }
        )
        val medianHeight = tracks.map { it.bounds.height }.sorted().let { heights ->
            heights[heights.size / 2]
        }
        val roles = tracks.associate { track ->
            track.trackId to AssistTextHeuristics.classifyRole(
                track,
                medianHeight,
                request.viewportHeight
            )
        }
        val groups = groupTracks(tracks, roles, request.viewportWidth)
        val suggestions = buildList {
            tracks.forEach { track ->
                val role = roles.getValue(track.trackId)
                if (role in PROTECTED_ROLES) add(protectionSuggestion(track))
                correctionSuggestion(track, request)?.let(::add)
                layoutSuggestion(track, request)?.let(::add)
            }
            if (request.options.enableContextGrouping) {
                groups.filter { it.trackIds.size > 1 }.forEach { group ->
                    group.trackIds.forEach { trackId ->
                        if (roles[trackId] !in PROTECTED_ROLES) {
                            add(
                                AssistSuggestion(
                                    trackId = trackId,
                                    translationHint = group.contextText,
                                    confidence = CONTEXT_CONFIDENCE,
                                    evidence = AssistSuggestionEvidence.CONTEXT_RULE
                                )
                            )
                        }
                    }
                }
            }
        }
        return ProviderAssistResult(
            scene = AssistTextHeuristics.classifyScene(tracks),
            groups = groups,
            suggestions = suggestions
        )
    }

    private fun correctionSuggestion(
        track: AssistTextTrack,
        request: AssistRequest
    ): AssistSuggestion? {
        if (!request.options.enableOcrReview) return null
        val currentConfidence = track.consensusScore ?: 0f
        if (currentConfidence >= request.options.minimumReviewConfidence) return null
        val bestAlternative = track.alternatives.maxByOrNull { it.confidence } ?: return null
        if (bestAlternative.confidence < request.options.minimumReviewConfidence) return null
        if (bestAlternative.confidence - currentConfidence <
            request.options.minimumAlternativeImprovement
        ) return null
        return AssistSuggestion(
            trackId = track.trackId,
            correctedText = bestAlternative.text,
            confidence = bestAlternative.confidence,
            evidence = AssistSuggestionEvidence.OCR_ALTERNATIVE
        )
    }

    private fun protectionSuggestion(track: AssistTextTrack): AssistSuggestion = AssistSuggestion(
        trackId = track.trackId,
        protectTranslation = true,
        confidence = PROTECTION_CONFIDENCE,
        evidence = AssistSuggestionEvidence.PROTECTED_CONTENT_RULE
    )

    private fun layoutSuggestion(
        track: AssistTextTrack,
        request: AssistRequest
    ): AssistSuggestion? {
        if (!request.options.enableDisplayOptimization) return null
        val translated = track.translatedText?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val sourceLength = track.text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val translatedLength = translated.count { !it.isWhitespace() }
        val ratio = translatedLength.toFloat() / sourceLength
        val hint = when {
            ratio >= 1.8f -> AssistLayoutHint(
                mode = AssistLayoutMode.COMPACT,
                preferredMaxLines = 3,
                minimumTextScale = 0.68f
            )
            ratio >= 1.25f -> AssistLayoutHint(
                mode = AssistLayoutMode.MULTI_LINE,
                preferredMaxLines = 2
            )
            track.bounds.width >= track.bounds.height * 4 -> AssistLayoutHint(
                mode = AssistLayoutMode.SINGLE_LINE,
                preferredMaxLines = 1
            )
            else -> return null
        }
        return AssistSuggestion(
            trackId = track.trackId,
            layoutHint = hint,
            confidence = LAYOUT_CONFIDENCE,
            evidence = AssistSuggestionEvidence.LAYOUT_RULE
        )
    }

    private fun groupTracks(
        tracks: List<AssistTextTrack>,
        roles: Map<Long, AssistTrackRole>,
        viewportWidth: Int
    ): List<AssistTextGroup> {
        val parents = IntArray(tracks.size) { it }

        fun find(index: Int): Int {
            var current = index
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }

        fun union(first: Int, second: Int) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot != secondRoot) parents[secondRoot] = firstRoot
        }

        tracks.indices.forEach { firstIndex ->
            ((firstIndex + 1) until tracks.size).forEach { secondIndex ->
                val first = tracks[firstIndex]
                val second = tracks[secondIndex]
                if (second.bounds.top - first.bounds.bottom >
                    maxOf(first.bounds.height, second.bounds.height) * 2
                ) return@forEach
                if (shouldGroup(
                        first,
                        second,
                        roles.getValue(first.trackId),
                        roles.getValue(second.trackId),
                        viewportWidth
                    )
                ) {
                    union(firstIndex, secondIndex)
                }
            }
        }

        return tracks.indices.groupBy(::find).values
            .map { indices -> indices.map(tracks::get) }
            .sortedBy { group -> group.minOf { it.bounds.top } }
            .mapIndexed { order, groupTracks ->
                val ordered = groupTracks.sortedWith(
                    compareBy<AssistTextTrack> { it.bounds.top }.thenBy { it.bounds.left }
                )
                AssistTextGroup(
                    groupId = "group-${ordered.first().trackId}",
                    trackIds = ordered.map(AssistTextTrack::trackId),
                    role = groupRole(ordered.map { roles.getValue(it.trackId) }),
                    readingOrder = order,
                    contextText = ordered.joinToString(separator = "\n") { it.text.trim() }
                        .take(MAXIMUM_CONTEXT_LENGTH)
                )
            }
    }

    private fun shouldGroup(
        first: AssistTextTrack,
        second: AssistTextTrack,
        firstRole: AssistTrackRole,
        secondRole: AssistTrackRole,
        viewportWidth: Int
    ): Boolean {
        val verticalOverlap = AssistTextHeuristics.verticalOverlap(first.bounds, second.bounds)
        val sameRow = verticalOverlap >= minOf(first.bounds.height, second.bounds.height) / 2
        val horizontalGap = when {
            first.bounds.right <= second.bounds.left -> second.bounds.left - first.bounds.right
            second.bounds.right <= first.bounds.left -> first.bounds.left - second.bounds.right
            else -> 0
        }
        if (sameRow && horizontalGap <= maxOf(32, viewportWidth / 14)) return true

        val verticalGap = second.bounds.top - first.bounds.bottom
        val alignedContinuation = verticalGap in 0..maxOf(first.bounds.height, second.bounds.height) &&
            AssistTextHeuristics.isLeftAligned(first.bounds, second.bounds)
        if (!alignedContinuation) return false

        val continuationRoles = setOf(AssistTrackRole.BODY, AssistTrackRole.CODE)
        return firstRole in continuationRoles && secondRole in continuationRoles
    }

    private fun groupRole(roles: List<AssistTrackRole>): AssistTrackRole = when {
        AssistTrackRole.CODE in roles -> AssistTrackRole.CODE
        AssistTrackRole.TITLE in roles -> AssistTrackRole.TITLE
        AssistTrackRole.BODY in roles -> AssistTrackRole.BODY
        AssistTrackRole.ACTION in roles -> AssistTrackRole.ACTION
        AssistTrackRole.URL in roles -> AssistTrackRole.URL
        AssistTrackRole.LABEL in roles -> AssistTrackRole.LABEL
        else -> AssistTrackRole.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "deterministic"
        const val PROVIDER_VERSION = "1"
        const val MAXIMUM_CONTEXT_LENGTH = 512
        const val PROTECTION_CONFIDENCE = 0.99f
        const val CONTEXT_CONFIDENCE = 0.76f
        const val LAYOUT_CONFIDENCE = 0.82f
        val PROTECTED_ROLES = setOf(
            AssistTrackRole.URL,
            AssistTrackRole.CODE,
            AssistTrackRole.BRAND
        )
    }
}
