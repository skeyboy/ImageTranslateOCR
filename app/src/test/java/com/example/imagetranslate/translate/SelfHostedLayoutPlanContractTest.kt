package com.example.imagetranslate.translate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SelfHostedLayoutPlanContractTest {
    @Test
    fun v4AcceptsRegionFirstAuthoritativeGroupAndUsesSchemaFour() {
        val request = request()
        val response = JSONObject(response(0.94f))
            .put("schemaVersion", 4)
            .put("provider", "self-hosted-qwen-regions-first-v4")
            .put(
                "documentPlan",
                JSONObject()
                    .put("mode", "AUTHORITATIVE")
                    .put("planVersion", "server-regions-first-plan-v4")
            )
            .toString()
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null, 4)

        val result = provider.parseResponseForTest(response, request).results.single()

        assertEquals(4, JSONObject(provider.requestBodyForTest(request)).getInt("schemaVersion"))
        assertEquals(listOf("a-line", "b-line"), result.memberRegionIds)
        assertEquals("self-hosted-qwen-regions-first-v4", result.provider)
    }

    @Test
    fun v4AcceptsAuthoritativeUnionRenderSlotWhilePreservingOcrCoverSlots() {
        val request = request()
        val response = JSONObject(response(0.94f))
            .put("schemaVersion", 4)
            .put("provider", "self-hosted-qwen-regions-first-v4")
            .put(
                "documentPlan",
                JSONObject()
                    .put("mode", "AUTHORITATIVE")
                    .put("planVersion", "server-regions-first-plan-v4")
            )
        val layoutHint = response.getJSONArray("results").getJSONObject(0)
            .getJSONObject("layoutHint")
        layoutHint.put(
            "renderSlots",
            JSONArray().put(boundsJson(10, 20, 210, 85))
        )
        layoutHint.put(
            "sourceCoverSlots",
            JSONArray()
                .put(boundsJson(10, 20, 210, 50))
                .put(boundsJson(10, 55, 210, 85))
        )
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null, 4)

        val result = provider.parseResponseForTest(response.toString(), request).results.single()

        assertEquals(listOf(TranslationBounds(10, 20, 210, 85)), result.layoutHint?.renderSlots)
        assertEquals(
            listOf(
                TranslationBounds(10, 20, 210, 50),
                TranslationBounds(10, 55, 210, 85)
            ),
            result.layoutHint?.sourceCoverSlots
        )
    }

    @Test
    fun v4AcceptsServerSplitOfOneAdvisoryClientGroup() {
        val first = source(
            "a", "First paragraph ends here.", TranslationBounds(10, 20, 210, 50), 0,
            "BODY", "block-a", 0
        ).regions.single()
        val second = source(
            "b", "Second card starts here.", TranslationBounds(10, 100, 210, 130), 1,
            "BODY", "block-b", 0
        ).regions.single().copy(groupId = "a")
        val combined = SemanticTranslationSource(
            groupId = "a",
            role = "BODY",
            translationUnit = "GROUP",
            sourceText = "${first.text}\n${second.text}",
            memberRegionIds = listOf(first.regionId, second.regionId),
            readingOrder = 0,
            groupingConfidence = 0.96f,
            groupingEvidence = listOf("CLIENT_ADVISORY"),
            bounds = TranslationBounds(10, 20, 210, 130),
            regions = listOf(first, second),
            sourceLineCount = 2,
            renderSlots = listOf(first.bounds, second.bounds),
            layoutShape = "FLOW_SLOTS"
        )
        val request = request().copy(
            documentText = combined.sourceText,
            sources = listOf(combined)
        )
        val response = JSONObject()
            .put("schemaVersion", 4)
            .put("requestId", "request")
            .put("sessionId", "session")
            .put("generation", 1)
            .put("translationRevision", 0)
            .put("provider", "self-hosted-qwen-regions-first-v4")
            .put(
                "documentPlan",
                JSONObject()
                    .put("mode", "AUTHORITATIVE")
                    .put("planVersion", "server-regions-first-plan-v4")
            )
            .put(
                "results",
                JSONArray().put(
                    v4Result("server-v4-0", "a-line", first.bounds, "第一段到此结束。")
                ).put(
                    v4Result("server-v4-1", "b-line", second.bounds, "第二张卡片从这里开始。")
                )
            )
        val provider = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null, 4)

        val results = provider.parseResponseForTest(response.toString(), request).results

        assertEquals(2, results.size)
        assertEquals(listOf(listOf("a"), listOf("a")), results.map { it.sourceGroupIds })
        assertEquals(listOf("a-line", "b-line"), results.flatMap { it.memberRegionIds })
    }

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
    fun rejectsRoleDriftWithoutOcrOrVisualContinuationEvidence() {
        val baseRequest = request(
            firstRole = "BODY",
            secondRole = "TITLE",
            firstBlockId = "first-block",
            secondBlockId = "second-block",
            firstLineIndex = 4,
            secondLineIndex = 5
        )
        val displacedSecond = baseRequest.sources[1].copy(
            bounds = TranslationBounds(260, 55, 460, 85),
            regions = baseRequest.sources[1].regions.map { region ->
                region.copy(bounds = TranslationBounds(260, 55, 460, 85))
            },
            renderSlots = listOf(TranslationBounds(260, 55, 460, 85))
        )
        val driftedRequest = baseRequest.copy(sources = listOf(baseRequest.sources[0], displacedSecond))
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

    @Test
    fun acceptsRoleDriftAcrossVisuallyContinuousWrappedArticleLines() {
        val first = source(
            "a", "African Institute for Mathematical", TranslationBounds(484, 1064, 1387, 1176), 0,
            "TITLE", "title-block", 1
        ).copy(sourceLineCount = 2)
        val second = source(
            "b", "Sciences. The centres are spread", TranslationBounds(487, 1200, 1291, 1254), 1,
            "BODY", "body-block", 0
        )
        val request = request().copy(
            viewportWidth = 1440,
            viewportHeight = 3200,
            documentText = "${first.sourceText}\n${second.sourceText}",
            sources = listOf(first, second)
        )
        val responseJson = JSONObject(response(0.94f))
        val item = responseJson.getJSONArray("results").getJSONObject(0)
        item.put("memberRegionIds", JSONArray(listOf("a-line", "b-line")))
        item.put("anchorBounds", boundsJson(484, 1064, 1387, 1254))
        item.getJSONObject("layoutHint").put(
            "renderSlots",
            JSONArray(listOf(first.bounds, second.bounds).map {
                boundsJson(it.left, it.top, it.right, it.bottom)
            })
        )

        val result = provider().parseResponseForTest(responseJson.toString(), request)

        assertEquals(listOf("a", "b"), result.results.single().sourceGroupIds)
    }

    private fun provider() = SelfHostedSemanticTranslationProvider("http://127.0.0.1:8090", null)

    private fun boundsJson(left: Int, top: Int, right: Int, bottom: Int) = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private fun v4Result(
        groupId: String,
        memberRegionId: String,
        bounds: TranslationBounds,
        translation: String
    ) = JSONObject()
        .put("groupId", groupId)
        .put("sourceGroupIds", JSONArray(listOf("a")))
        .put("role", "BODY")
        .put("groupingConfidence", 0.96)
        .put("status", "TRANSLATED")
        .put("translatedText", translation)
        .put("memberRegionIds", JSONArray(listOf(memberRegionId)))
        .put("detectedSourceLanguage", "en")
        .put("targetLanguage", "zh")
        .put("anchorBounds", boundsJson(bounds.left, bounds.top, bounds.right, bounds.bottom))
        .put(
            "layoutHint",
            JSONObject()
                .put("preferredMaxLines", 1)
                .put("minimumTextScale", 0.72)
                .put("maximumTextScale", 1.0)
                .put("lineSpacingMultiplier", 1.0)
                .put("alignment", "START")
                .put("overflowStrategy", "REFLOW_THEN_SCALE")
                .put("allowMore", false)
                .put("sourceLineCount", 1)
                .put("layoutShape", "RECT")
                .put(
                    "renderSlots",
                    JSONArray().put(
                        boundsJson(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    )
                )
                .put(
                    "sourceCoverSlots",
                    JSONArray().put(
                        boundsJson(bounds.left, bounds.top, bounds.right, bounds.bottom)
                    )
                )
        )

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
