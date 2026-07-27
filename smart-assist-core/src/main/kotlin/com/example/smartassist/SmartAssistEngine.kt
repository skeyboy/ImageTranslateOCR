package com.example.smartassist

import com.example.smartassist.api.AssistCapabilities
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistResult

interface SmartAssistEngine : AutoCloseable {
    fun capabilities(): AssistCapabilities

    suspend fun analyze(request: AssistRequest): AssistResult

    override fun close()
}
