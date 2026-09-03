package com.example.imagetranslate.translate

internal object EmbeddedProviderRetryPolicy {
    const val MAXIMUM_RETRY_COUNT = 1
    const val BASE_DELAY_MS = 700L
    const val MAXIMUM_JITTER_MS = 250L

    fun delayMs(status: Int, completedRetryCount: Int, jitterMs: Long): Long? {
        if (status != 429 || completedRetryCount >= MAXIMUM_RETRY_COUNT) return null
        return BASE_DELAY_MS + jitterMs.coerceIn(0L, MAXIMUM_JITTER_MS)
    }
}
