package com.example.imagetranslate.screenshot

import com.example.smartassist.SmartAssistEngine
import com.example.smartassist.SmartAssistEngineFactory
import com.example.smartassist.api.AssistLayoutMode
import com.example.smartassist.api.AssistOptions
import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistScene
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistStatus
import com.example.smartassist.api.AssistTextTrack
import com.example.smartassist.api.AssistTrackRole
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal data class LiveSmartAssistRegion(
    val regionId: Long,
    val sourceText: String,
    val translatedText: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val consensusScore: Float?,
    val script: AssistScript
)

internal data class LiveSmartAssistDecision(
    val regionId: Long,
    val protectTranslation: Boolean,
    val contextText: String?,
    val layoutMode: AssistLayoutMode?,
    val preferredMaxLines: Int?,
    val minimumTextScale: Float?
)

internal data class LiveSmartAssistGroup(
    val regionIds: List<Long>,
    val role: AssistTrackRole,
    val readingOrder: Int,
    val contextText: String
)

internal data class LiveSmartAssistOutcome(
    val applied: Boolean,
    val scene: AssistScene = AssistScene.UNKNOWN,
    val groupCount: Int = 0,
    val groups: List<LiveSmartAssistGroup> = emptyList(),
    val decisions: Map<Long, LiveSmartAssistDecision> = emptyMap(),
    val providerId: String? = null,
    val failureMessage: String? = null
)

internal class LiveSmartAssistAdapter(
    private val engine: SmartAssistEngine = SmartAssistEngineFactory.create()
) : AutoCloseable {
    private val generation = AtomicLong(0)

    suspend fun analyze(
        viewportWidth: Int,
        viewportHeight: Int,
        regions: List<LiveSmartAssistRegion>
    ): LiveSmartAssistOutcome {
        if (viewportWidth <= 0 || viewportHeight <= 0 || regions.isEmpty()) {
            return LiveSmartAssistOutcome(applied = false)
        }
        val tracks = regions.mapNotNull { region ->
            if (region.regionId <= 0 || region.sourceText.isBlank() ||
                region.left < 0 || region.top < 0 ||
                region.right <= region.left || region.bottom <= region.top ||
                region.right > viewportWidth || region.bottom > viewportHeight
            ) {
                return@mapNotNull null
            }
            AssistTextTrack(
                trackId = region.regionId,
                text = region.sourceText,
                bounds = AssistRect(region.left, region.top, region.right, region.bottom),
                script = region.script,
                consensusScore = region.consensusScore,
                translatedText = region.translatedText
            )
        }.distinctBy(AssistTextTrack::trackId)
        if (tracks.isEmpty()) return LiveSmartAssistOutcome(applied = false)

        val requestGeneration = generation.incrementAndGet()
        val request = AssistRequest(
            requestId = "live-$requestGeneration",
            generation = requestGeneration,
            viewportSignature = viewportSignature(viewportWidth, viewportHeight, regions),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            tracks = tracks,
            options = AssistOptions(
                enabled = true,
                enableOcrReview = true,
                enableContextGrouping = true,
                enableDisplayOptimization = true
            )
        )
        return try {
            val result = engine.analyze(request)
            if (result.status != AssistStatus.COMPLETED) {
                LiveSmartAssistOutcome(
                    applied = false,
                    providerId = result.diagnostics.providerId,
                    failureMessage = result.diagnostics.failureMessage
                )
            } else {
                val decisions = result.suggestions.groupBy { it.trackId }.mapValues { entry ->
                    val suggestions = entry.value
                    val layout = suggestions.mapNotNull { it.layoutHint }.lastOrNull()
                    LiveSmartAssistDecision(
                        regionId = entry.key,
                        protectTranslation = suggestions.any { it.protectTranslation },
                        contextText = suggestions.mapNotNull { it.translationHint }.lastOrNull(),
                        layoutMode = layout?.mode,
                        preferredMaxLines = layout?.preferredMaxLines,
                        minimumTextScale = layout?.minimumTextScale
                    )
                }
                LiveSmartAssistOutcome(
                    applied = true,
                    scene = result.scene,
                    groupCount = result.groups.size,
                    groups = result.groups.map { group ->
                        LiveSmartAssistGroup(
                            regionIds = group.trackIds,
                            role = group.role,
                            readingOrder = group.readingOrder,
                            contextText = group.contextText
                        )
                    },
                    decisions = decisions,
                    providerId = result.diagnostics.providerId
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LiveSmartAssistOutcome(
                applied = false,
                providerId = runCatching { engine.capabilities().providerId }.getOrNull(),
                failureMessage = error.message
            )
        }
    }

    override fun close() = engine.close()

    private fun viewportSignature(
        width: Int,
        height: Int,
        regions: List<LiveSmartAssistRegion>
    ): String {
        var signature = 31 * width + height
        regions.forEach { region ->
            signature = 31 * signature + region.sourceText.hashCode()
            signature = 31 * signature + region.left
            signature = 31 * signature + region.top
            signature = 31 * signature + region.right
            signature = 31 * signature + region.bottom
        }
        return "live-$width-$height-${signature.toUInt().toString(16)}"
    }
}
