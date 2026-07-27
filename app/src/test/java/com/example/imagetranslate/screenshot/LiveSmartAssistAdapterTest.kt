package com.example.imagetranslate.screenshot

import com.example.smartassist.SmartAssistEngine
import com.example.smartassist.api.AssistCapabilities
import com.example.smartassist.api.AssistLayoutMode
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistResult
import com.example.smartassist.api.AssistScript
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSmartAssistAdapterTest {
    @Test
    fun defaultsToDisabledUntilTheUserEnablesIt() {
        assertFalse(LiveSmartAssistSettingsPolicy.DEFAULT_ENABLED)
    }

    @Test
    fun mapsOfflineProtectionAndLayoutSuggestions() = runBlocking {
        LiveSmartAssistAdapter().use { adapter ->
            val outcome = adapter.analyze(
                viewportWidth = 1080,
                viewportHeight = 2400,
                regions = listOf(
                    region(1, "https://example.com", "示例网站", 100),
                    region(
                        2,
                        "Save",
                        "保存当前屏幕中已经识别并翻译完成的全部内容",
                        220
                    )
                )
            )

            assertTrue(outcome.applied)
            assertEquals("deterministic", outcome.providerId)
            assertTrue(outcome.decisions.getValue(1).protectTranslation)
            assertTrue(outcome.groups.isNotEmpty())
            assertEquals(
                AssistLayoutMode.COMPACT,
                outcome.decisions.getValue(2).layoutMode
            )
        }
    }

    @Test
    fun exposesAdjacentBodyContextForTranslationGrouping() = runBlocking {
        LiveSmartAssistAdapter().use { adapter ->
            val outcome = adapter.analyze(
                viewportWidth = 1080,
                viewportHeight = 2400,
                regions = listOf(
                    region(
                        1,
                        "This paragraph explains how continuous screen translation works.",
                        "",
                        100
                    ),
                    region(
                        2,
                        "The following line continues the same reading context.",
                        "",
                        156
                    )
                )
            )

            assertTrue(outcome.groups.single().regionIds == listOf(1L, 2L))
            assertTrue(outcome.decisions.getValue(1).contextText?.contains("following") == true)
            assertTrue(outcome.decisions.getValue(2).contextText?.contains("continuous") == true)
        }
    }

    @Test
    fun adapterFailureReturnsNoDecisionsAndKeepsTheBaselineUsable() = runBlocking {
        LiveSmartAssistAdapter(ThrowingEngine).use { adapter ->
            val outcome = adapter.analyze(
                viewportWidth = 1080,
                viewportHeight = 2400,
                regions = listOf(region(1, "Settings", "设置", 100))
            )

            assertFalse(outcome.applied)
            assertTrue(outcome.decisions.isEmpty())
            assertEquals("adapter failed", outcome.failureMessage)
        }
    }

    private fun region(
        id: Long,
        source: String,
        translation: String,
        top: Int
    ) = LiveSmartAssistRegion(
        regionId = id,
        sourceText = source,
        translatedText = translation,
        left = 60,
        top = top,
        right = 900,
        bottom = top + 56,
        consensusScore = 0.9f,
        script = AssistScript.LATIN
    )

    private object ThrowingEngine : SmartAssistEngine {
        override fun capabilities() = AssistCapabilities(
            providerId = "throwing",
            providerVersion = "1",
            available = true,
            offline = true,
            supportsText = true,
            supportsImages = false,
            supportsBackgroundExecution = true,
            requiresModelDownload = false
        )

        override suspend fun analyze(request: AssistRequest): AssistResult =
            error("adapter failed")

        override fun close() = Unit
    }
}
