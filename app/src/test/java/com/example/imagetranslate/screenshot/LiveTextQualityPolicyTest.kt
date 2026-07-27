package com.example.imagetranslate.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTextQualityPolicyTest {
    @Test
    fun measuresUntranslatedLatinAndSuspiciousJoins() {
        val metrics = LiveTextQualityPolicy.measure(
            listOf(
                "Later chapters explain ownership" to "Later 章节解释所有权",
                "This is very fast" to "这IsVery快"
            )
        )

        assertEquals(8, metrics.sourceLatinTokenCount)
        assertEquals(1, metrics.retainedLatinTokenCount)
        assertEquals(0.125f, metrics.retainedLatinRatio)
        assertEquals(1, metrics.suspiciousJoinCount)
    }

    @Test
    fun ignoresExpectedTechnicalTerms() {
        val metrics = LiveTextQualityPolicy.measure(
            listOf("Rust Cargo API guide" to "Rust Cargo API guide 指南")
        )

        assertEquals(1, metrics.sourceLatinTokenCount)
        assertEquals(1, metrics.retainedLatinTokenCount)
        assertEquals(1f, metrics.retainedLatinRatio)
    }
}
