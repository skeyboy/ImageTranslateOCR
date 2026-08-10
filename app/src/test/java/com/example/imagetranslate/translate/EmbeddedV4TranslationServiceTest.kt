package com.example.imagetranslate.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedV4TranslationServiceTest {
    @Test
    fun splitsTimestampFromOvermergedClientGroup() {
        val body = region(
            id = "body",
            text = "The Web3 industry tried and we did quite well",
            order = 0,
            top = 100,
            blockId = "chat"
        )
        val timestamp = region(
            id = "time",
            text = "22:43",
            order = 1,
            top = 132,
            blockId = "chat"
        )

        val plan = EmbeddedV4RegionsFirstPlanner.build(
            request(source("client-overmerged", listOf(body, timestamp)))
        )

        assertEquals(2, plan.groups.size)
        assertEquals(listOf("body"), plan.groups[0].memberRegionIds)
        assertEquals("TIMESTAMP", plan.groups[1].role)
        assertEquals("PRESERVED", plan.groups[1].translationUnit)
    }

    @Test
    fun mergesParagraphAndTranslatesEntireGroupWithoutHttp() = runBlocking {
        val first = region(
            id = "line-1",
            text = "The Canadian government has awarded funding over the",
            order = 0,
            top = 100,
            blockId = "article",
            lineIndex = 0
        )
        val second = region(
            id = "line-2",
            text = "next four years to five research centres.",
            order = 1,
            top = 132,
            blockId = "article",
            lineIndex = 1
        )
        val translatedRequests = mutableListOf<TranslationRequest>()
        val service = EmbeddedV4TranslationService { requests ->
            translatedRequests += requests
            TranslationBatchResult(
                results = requests.map { item ->
                    TranslationResult(
                        regionId = item.regionId,
                        translatedText = "加拿大政府将在未来四年资助五个研究中心。",
                        provider = "fake-local",
                        detectedSourceLanguage = "en",
                        targetLanguage = "zh"
                    )
                }
            )
        }

        val result = service.translate(request(source("client-article", listOf(first, second))))

        assertEquals(1, translatedRequests.size)
        assertEquals("${first.text}\n${second.text}", translatedRequests.single().text)
        assertTrue(result.failures.isEmpty())
        val translated = result.results.single()
        assertEquals(listOf("line-1", "line-2"), translated.memberRegionIds)
        assertEquals(listOf(first.bounds, second.bounds), translated.layoutHint?.sourceCoverSlots)
        assertEquals("embedded-regions-first-v4:fake-local", translated.provider)
        assertFalse(translated.translatedText.isNullOrBlank())
    }

    @Test
    fun preservesApiPathWithoutCallingTranslator() = runBlocking {
        val code = region(
            id = "api",
            text = "POST /api/v4/translate/layout-plan",
            order = 0,
            top = 100,
            blockId = null
        )
        var translatorCalled = false
        val service = EmbeddedV4TranslationService {
            translatorCalled = true
            TranslationBatchResult(emptyList())
        }

        val result = service.translate(request(source("client-code", listOf(code))))

        assertFalse(translatorCalled)
        assertEquals(TranslationResultStatus.PRESERVED, result.results.single().status)
        assertEquals(code.text, result.results.single().translatedText)
    }

    @Test
    fun rejectsEmptyLocalTranslationInsteadOfRenderingIt() = runBlocking {
        val body = region(
            id = "body",
            text = "Translate this sentence.",
            order = 0,
            top = 100,
            blockId = "body"
        )
        val service = EmbeddedV4TranslationService { requests ->
            TranslationBatchResult(
                requests.map { item ->
                    TranslationResult(item.regionId, "", "broken-local")
                }
            )
        }

        val result = service.translate(request(source("client-body", listOf(body))))

        assertTrue(result.results.isEmpty())
        assertEquals("EMBEDDED_V4_INVALID_TRANSLATION", result.failures.single().code)
    }

    private fun request(source: SemanticTranslationSource) = SemanticTranslationRequest(
        requestId = "embedded-test",
        sessionId = "session",
        generation = 1,
        translationRevision = 1,
        scene = "ANDROID_CLIENT",
        viewportWidth = 1080,
        viewportHeight = 2400,
        mode = TranslationMode.ENGLISH_TO_CHINESE,
        documentText = source.sourceText,
        sources = listOf(source)
    )

    private fun source(
        id: String,
        regions: List<SemanticTranslationRegion>
    ): SemanticTranslationSource {
        val bounds = regions.map(SemanticTranslationRegion::bounds).reduce(::unionForTest)
        return SemanticTranslationSource(
            groupId = id,
            role = "BODY",
            translationUnit = "GROUP",
            sourceText = regions.joinToString("\n", transform = SemanticTranslationRegion::text),
            memberRegionIds = regions.map(SemanticTranslationRegion::regionId),
            readingOrder = regions.minOf(SemanticTranslationRegion::readingOrder),
            groupingConfidence = 0.95f,
            groupingEvidence = listOf("OCR_BLOCK_CONTINUATION"),
            bounds = bounds,
            regions = regions,
            sourceLineCount = regions.size,
            renderSlots = regions.map(SemanticTranslationRegion::bounds),
            layoutShape = if (regions.size > 1) "FLOW_SLOTS" else "RECT"
        )
    }

    private fun region(
        id: String,
        text: String,
        order: Int,
        top: Int,
        blockId: String?,
        lineIndex: Int? = order
    ) = SemanticTranslationRegion(
        regionId = id,
        groupId = "client-group",
        sourceRevision = 1,
        text = text,
        sourceLanguage = "en",
        targetLanguage = "zh",
        readingOrder = order,
        blockId = blockId,
        lineIndex = lineIndex,
        confidence = 0.97f,
        bounds = TranslationBounds(40, top, 900, top + 24)
    )

    private fun unionForTest(first: TranslationBounds, second: TranslationBounds) =
        TranslationBounds(
            minOf(first.left, second.left),
            minOf(first.top, second.top),
            maxOf(first.right, second.right),
            maxOf(first.bottom, second.bottom)
        )
}
