package com.example.imagetranslate.translate

import org.json.JSONArray
import org.json.JSONObject
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
        assertEquals(
            listOf(
                TranslationBounds(10, 20, 210, 50),
                TranslationBounds(10, 55, 210, 85)
            ),
            result.layoutHint?.sourceCoverSlots
        )
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

    @Test
    fun acceptsRoleDriftWhenOcrBlockAndLineageAreContinuous() {
        val driftedRequest = request(
            firstRole = "BODY",
            secondRole = "TITLE",
            firstBlockId = "body-block",
            secondBlockId = "body-block",
            firstLineIndex = 4,
            secondLineIndex = 5
        )

        val result = provider().parseResponseForTest(response(0.94f), driftedRequest)

        assertEquals(listOf("a", "b"), result.results.single().sourceGroupIds)
    }

    @Test
    fun rejectsRoleDriftWithoutContinuousOcrBlockEvidence() {
        val driftedRequest = request(
            firstRole = "BODY",
            secondRole = "TITLE",
            firstBlockId = "first-block",
            secondBlockId = "second-block",
            firstLineIndex = 4,
            secondLineIndex = 5
        )
        try {
            provider().parseResponseForTest(response(0.94f), driftedRequest)
            fail("Expected cross-role merge without OCR continuity to be rejected")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    @Test
    fun acceptsExplainedRoleDriftInsideLongerAuthoritativeFlow() {
        val first = source(
            "a", "silent struggle has been", TranslationBounds(120, 20, 230, 45), 0,
            "BODY", "wrapped-block", 0
        )
        val drifted = source(
            "b", "raging within our universities", TranslationBounds(110, 50, 230, 80), 1,
            "TITLE", "wrapped-block", 1
        )
        val fullWidth = source(
            "c", "is perceived as worthy work", TranslationBounds(10, 85, 210, 115), 2,
            "BODY", "next-block", 0
        )
        val request = request().copy(
            documentText = listOf(first, drifted, fullWidth).joinToString("\n") { it.sourceText },
            sources = listOf(first, drifted, fullWidth)
        )
        val responseJson = JSONObject(response(0.94f))
        val item = responseJson.getJSONArray("results").getJSONObject(0)
        item.put("groupId", "server-a--b--c")
        item.put("sourceGroupIds", JSONArray(listOf("a", "b", "c")))
        item.put("memberRegionIds", JSONArray(listOf("a-line", "b-line", "c-line")))
        item.put("anchorBounds", boundsJson(10, 20, 230, 115))
        item.getJSONObject("layoutHint").put(
            "renderSlots",
            JSONArray(
                listOf(
                    boundsJson(120, 20, 230, 45),
                    boundsJson(110, 50, 230, 80),
                    boundsJson(10, 85, 210, 115)
                )
            )
        )

        val result = provider().parseResponseForTest(responseJson.toString(), request)

        assertEquals(listOf("a", "b", "c"), result.results.single().sourceGroupIds)
    }

    private fun provider() = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null)

    private fun boundsJson(left: Int, top: Int, right: Int, bottom: Int) = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private fun request(
        firstRole: String = "BODY",
        secondRole: String = "BODY",
        firstBlockId: String = "body",
        secondBlockId: String = "body",
        firstLineIndex: Int = 0,
        secondLineIndex: Int = 1
    ): SemanticTranslationRequest {
        val first = source(
            "a", "First body line", TranslationBounds(10, 20, 210, 50), 0,
            firstRole, firstBlockId, firstLineIndex
        )
        val second = source(
            "b", "continues here", TranslationBounds(10, 55, 210, 85), 1,
            secondRole, secondBlockId, secondLineIndex
        )
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
        order: Int,
        role: String,
        blockId: String,
        lineIndex: Int
    ): SemanticTranslationSource {
        val region = SemanticTranslationRegion(
            regionId = "$id-line",
            groupId = id,
            sourceRevision = 1,
            text = text,
            sourceLanguage = "en",
            targetLanguage = "zh",
            readingOrder = order,
            blockId = blockId,
            lineIndex = lineIndex,
            confidence = 0.96f,
            bounds = bounds
        )
        return SemanticTranslationSource(
            groupId = id,
            role = role,
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
