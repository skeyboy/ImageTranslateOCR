package com.example.smartassist.engine

import com.example.smartassist.SmartAssistEngine
import com.example.smartassist.api.AssistCapabilities
import com.example.smartassist.api.AssistDecisionReason
import com.example.smartassist.api.AssistDiagnostics
import com.example.smartassist.api.AssistProvider
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistResult
import com.example.smartassist.api.AssistScene
import com.example.smartassist.api.AssistStatus
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DefaultSmartAssistEngine(
    private val provider: AssistProvider,
    maximumCacheEntries: Int = 64
) : SmartAssistEngine {
    private val closed = AtomicBoolean(false)
    private val requestSequence = AtomicLong(0)
    private val cache = AssistResultCache(maximumCacheEntries)

    override fun capabilities(): AssistCapabilities = provider.capabilities()

    override suspend fun analyze(request: AssistRequest): AssistResult {
        if (closed.get()) return skipped(request, AssistDecisionReason.ENGINE_CLOSED)
        val sequence = requestSequence.incrementAndGet()

        val trigger = AssistTriggerPolicy.evaluate(request)
        if (!trigger.shouldAnalyze) return skipped(request, trigger.reasons)

        val capabilities = provider.capabilities()
        if (!capabilities.available) {
            return skipped(request, AssistDecisionReason.PROVIDER_UNAVAILABLE)
        }

        val cacheKey = cache.key(request, capabilities.providerId, capabilities.providerVersion)
        cache.get(cacheKey)?.let { cached ->
            return cached.copy(
                requestId = request.requestId,
                generation = request.generation,
                diagnostics = cached.diagnostics.copy(
                    cacheHit = true,
                    reasons = trigger.reasons
                )
            )
        }

        val providerResult = try {
            provider.analyze(request)
        } catch (error: Throwable) {
            if (sequence != requestSequence.get() || closed.get()) {
                return skipped(request, AssistDecisionReason.SUPERSEDED)
            }
            return AssistResult(
                requestId = request.requestId,
                generation = request.generation,
                status = AssistStatus.FAILED,
                scene = AssistScene.UNKNOWN,
                groups = emptyList(),
                suggestions = emptyList(),
                diagnostics = AssistDiagnostics(
                    triggered = true,
                    providerId = capabilities.providerId,
                    cacheHit = false,
                    inputTrackCount = request.tracks.size,
                    acceptedSuggestionCount = 0,
                    rejectedSuggestionCount = 0,
                    reasons = trigger.reasons + AssistDecisionReason.PROVIDER_FAILED,
                    failureMessage = error.message ?: error::class.simpleName
                )
            )
        }

        if (sequence != requestSequence.get() || closed.get()) {
            return skipped(request, AssistDecisionReason.SUPERSEDED)
        }

        val validated = AssistResultValidator.validate(request, providerResult)
        val result = AssistResult(
            requestId = request.requestId,
            generation = request.generation,
            status = AssistStatus.COMPLETED,
            scene = providerResult.scene,
            groups = validated.groups,
            suggestions = validated.suggestions,
            diagnostics = AssistDiagnostics(
                triggered = true,
                providerId = capabilities.providerId,
                cacheHit = false,
                inputTrackCount = request.tracks.size,
                acceptedSuggestionCount = validated.suggestions.size,
                rejectedSuggestionCount = validated.rejectedSuggestionCount,
                reasons = trigger.reasons
            )
        )
        cache.put(cacheKey, result)
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requestSequence.incrementAndGet()
        cache.clear()
        provider.close()
    }

    private fun skipped(
        request: AssistRequest,
        reason: AssistDecisionReason
    ): AssistResult = skipped(request, listOf(reason))

    private fun skipped(
        request: AssistRequest,
        reasons: List<AssistDecisionReason>
    ): AssistResult = AssistResult(
        requestId = request.requestId,
        generation = request.generation,
        status = AssistStatus.SKIPPED,
        scene = AssistScene.UNKNOWN,
        groups = emptyList(),
        suggestions = emptyList(),
        diagnostics = AssistDiagnostics(
            triggered = false,
            providerId = provider.capabilities().providerId,
            cacheHit = false,
            inputTrackCount = request.tracks.size,
            acceptedSuggestionCount = 0,
            rejectedSuggestionCount = 0,
            reasons = reasons
        )
    )
}
