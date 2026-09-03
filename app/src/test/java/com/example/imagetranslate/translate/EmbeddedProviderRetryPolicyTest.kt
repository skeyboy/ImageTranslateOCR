package com.example.imagetranslate.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedProviderRetryPolicyTest {
    @Test
    fun rateLimitRetriesOnceWithBoundedJitter() {
        assertEquals(700L, EmbeddedProviderRetryPolicy.delayMs(429, 0, -10))
        assertEquals(950L, EmbeddedProviderRetryPolicy.delayMs(429, 0, 900))
        assertNull(EmbeddedProviderRetryPolicy.delayMs(429, 1, 0))
        assertNull(EmbeddedProviderRetryPolicy.delayMs(500, 0, 0))
    }
}
