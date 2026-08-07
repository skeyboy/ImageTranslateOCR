package com.example.imagetranslate.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SelfHostedLayoutPlanContractTest {
    @Test
    fun acceptsHighConfidenceAuthoritativeGroupWithExactLineageAndGeometry() {
        val request = request()
        val result = provider().parseResponseForTest(response(0.94f), request).results.single()

        assertEquals("server-a--b", result.groupId)
        assertEquals(listOf("a", "b"), result.sourceGroupIds)
        assertEquals(listOf("a-line", "b-line"), result.memberRegionIds)
        assertEquals(2, result.layoutHint?.sourceLineCount)
        assertEquals(true, result.layoutHint?.allowMore)
    }

    @Test
    fun rejectsMergedGroupBelowAuthorityThreshold() {
        try {
            provider().parseResponseForTest(response(0.89f), request())
            fail("Expected low-confidence authoritative group to be rejected")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    @Test
    fun rejectsSourceGroupsOutsideReadingOrder() {
        val reversed = response(0.94f).replace(
            "\"sourceGroupIds\": [\"a\", \"b\"]",
            "\"sourceGroupIds\": [\"b\", \"a\"]"
        )
        try {
            provider().parseResponseForTest(reversed, request())
            fail("Expected reversed source lineage to be rejected")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private fun provider() = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null)

    private fun request(): SemanticTranslationRequest {
        val first = source("a", "First body line", TranslationBounds(10, 20, 210, 50), 0)
        val second = source("b", "continues here", TranslationBounds(10, 55, 210, 85), 1)
        return SemanticTranslationRequest(
            requestId = "request",
            sessionId = "session",
            generation = 1,
            translationRevision = 0,
            scene = "LIVE_SCREEN",
            viewportWidth = 240,
            viewportHeight = 120,
            mode = TranslationMode.ENGLISH_TO_CHINESE,
            documentText = "First body line\ncontinues here",
            sources = listOf(first, second)
        )
    }

    private fun source(
        id: String,
        text: String,
        bounds: TranslationBounds,
        order: Int
    ): SemanticTranslationSource {
        val region = SemanticTranslationRegion(
            regionId = "$id-line",
            groupId = id,
            sourceRevision = 1,
            text = text,
            sourceLanguage = "en",
            targetLanguage = "zh",
            readingOrder = order,
            blockId = "body",
            lineIndex = order,
            confidence = 0.96f,
            bounds = bounds
        )
        return SemanticTranslationSource(
            groupId = id,
            role = "BODY",
            translationUnit = "GROUP",
            sourceText = text,
            memberRegionIds = listOf(region.regionId),
            readingOrder = order,
            groupingConfidence = 0.96f,
            groupingEvidence = listOf("OCR_BLOCK"),
            bounds = bounds,
            regions = listOf(region),
            sourceLineCount = 1,
            renderSlots = listOf(bounds)
        )
    }

    private fun response(confidence: Float) = """
        {
          "schemaVersion": 3,
          "requestId": "request",
          "sessionId": "session",
          "generation": 1,
          "translationRevision": 0,
          "provider": "self-hosted-qwen-layout-plan-v3",
          "results": [{
            "groupId": "server-a--b",
            "sourceGroupIds": ["a", "b"],
            "role": "BODY",
            "groupingConfidence": $confidence,
            "status": "TRANSLATED",
            "translatedText": "第一行正文继续到这里",
            "memberRegionIds": ["a-line", "b-line"],
            "detectedSourceLanguage": "en",
            "targetLanguage": "zh",
            "anchorBounds": {"left": 10, "top": 20, "right": 210, "bottom": 85},
            "layoutHint": {
              "preferredMaxLines": 3,
              "minimumTextScale": 0.72,
              "maximumTextScale": 1.0,
              "lineSpacingMultiplier": 0.92,
              "alignment": "START",
              "overflowStrategy": "REFLOW_THEN_SCALE_THEN_MORE",
              "allowMore": true,
              "sourceLineCount": 2,
              "layoutShape": "FLOW_SLOTS",
              "renderSlots": [
                {"left": 10, "top": 20, "right": 210, "bottom": 50},
                {"left": 10, "top": 55, "right": 210, "bottom": 85}
              ]
            }
          }]
        }
    """.trimIndent()
}
