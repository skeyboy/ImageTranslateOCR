package com.example.imagetranslate.translate

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineTranslationBatchExecutorTest {
    @Test
    fun `extracts translation from wrapped backend response`() {
        val response = """
            {
              "code": 0,
              "type": "success",
              "message": "",
              "result": {"translation": "翻译后的段落"},
              "extras": null
            }
        """.trimIndent()

        assertEquals("翻译后的段落", parseMachineTranslationResponse(response))
    }

    @Test
    fun `keeps direct text backend response compatible`() {
        assertEquals("翻译后的段落", parseMachineTranslationResponse("  翻译后的段落  "))
    }

    @Test
    fun `rejects wrapped backend errors`() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseMachineTranslationResponse(
                """{"code":401,"message":"token expired","result":null}"""
            )
        }

        assertTrue(error.message.orEmpty().contains("token expired"))
    }

    @Test
    fun `archived paragraphs run concurrently and return in source order`() = runBlocking {
        val paragraphs = (0 until ARCHIVED_TRANSLATABLE_PARAGRAPH_COUNT).map { "paragraph-$it" }
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        val results = translateMachineParagraphsInParallel(
            paragraphs = paragraphs,
            maxConcurrency = 4
        ) { paragraph ->
            val running = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, running) }
            try {
                delay(if (paragraph.endsWith("0")) 40 else 5)
                "translated-$paragraph"
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals(paragraphs.map { "translated-$it" }, results)
        assertTrue(maximumActive.get() in 2..4)
        assertEquals(0, active.get())
    }

    @Test
    fun `all paragraph terminal results are collected before paste back`() = runBlocking {
        val paragraphs = listOf("success-1", "failed", "success-2")

        val results = translateMachineParagraphsInParallel(paragraphs, 3) { paragraph ->
            delay(5)
            if (paragraph == "failed") "FAILED" else "SUCCEEDED:$paragraph"
        }

        assertEquals(3, results.size)
        assertEquals(listOf("SUCCEEDED:success-1", "FAILED", "SUCCEEDED:success-2"), results)
    }

    private companion object {
        // The archive contains 19 groups: 15 translated and 4 preserved timestamps.
        const val ARCHIVED_TRANSLATABLE_PARAGRAPH_COUNT = 15
    }
}
