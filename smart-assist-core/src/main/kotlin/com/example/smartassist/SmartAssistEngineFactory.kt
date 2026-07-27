package com.example.smartassist

import com.example.smartassist.api.AssistProvider
import com.example.smartassist.engine.DefaultSmartAssistEngine
import com.example.smartassist.provider.DeterministicAssistProvider

object SmartAssistEngineFactory {
    fun create(
        provider: AssistProvider = DeterministicAssistProvider(),
        maximumCacheEntries: Int = 64
    ): SmartAssistEngine = DefaultSmartAssistEngine(provider, maximumCacheEntries)
}
