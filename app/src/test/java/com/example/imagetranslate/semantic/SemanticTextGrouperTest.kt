package com.example.imagetranslate.semantic

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTextGrouperTest {
    @Test
    fun keepsACompleteFiveLineMlKitBlockAsOneTranslationUnit() {
        val lines = (0 until 5).map { index ->
            line(
                text = "paragraph line ${index + 1}",
                left = 80,
                top = 100 + index * 34,
                right = 620,
                bottom = 126 + index * 34,
                blockId = "article-body",
                lineIndex = index
            )
        }

        val groups = SemanticTextGrouper.group(lines, 1080, 1920)

        assertEquals(1, groups.size)
        assertEquals(5, groups.single().members.size)
        assertTrue(GroupingEvidence.OCR_BLOCK in groups.single().evidence)
        assertEquals(5, groups.single().toRecognizedText().textEraseBounds().size)
    }

    @Test
    fun doesNotMergeDifferentOcrBlocksEvenWhenTheirGeometryIsClose() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("first message", 80, 100, 620, 126, "bubble-1", 0),
                line("second message", 80, 132, 620, 158, "bubble-2", 0)
            ),
            1080,
            1920
        )

        assertEquals(2, groups.size)
    }

    @Test
    fun doesNotMergeAdjacentTextWhenEstimatedFontScalesAreClearlyDifferent() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("Large introductory copy", 80, 100, 620, 130, null, 0)
                    .copy(estimatedTextHeightPx = 30f, typographyConfidence = 0.9f),
                line("smaller continuation copy", 80, 136, 620, 166, null, 1)
                    .copy(estimatedTextHeightPx = 14f, typographyConfidence = 0.9f)
            ),
            1080,
            1920
        )

        assertEquals(2, groups.size)
    }

    @Test
    fun allowsSlightFontScaleVariationInsideTheSameOcrBlock() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("A paragraph can contain", 80, 100, 620, 126, "body", 0)
                    .copy(estimatedTextHeightPx = 20f, typographyConfidence = 0.8f),
                line("minor OCR height variation", 80, 132, 620, 158, "body", 1)
                    .copy(estimatedTextHeightPx = 14f, typographyConfidence = 0.8f)
            ),
            1080,
            1920
        )

        assertEquals(1, groups.size)
        assertTrue(GroupingEvidence.FONT_SCALE_RELAXED_SAME_BLOCK in groups.single().evidence)
    }

    @Test
    fun keepsTimestampOutsideAdjacentChatMessages() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("The release is ready", 80, 100, 620, 126, null, null),
                line("10:42", 540, 130, 620, 150, null, null),
                line("Please verify it", 80, 164, 620, 190, null, null)
            ),
            1080,
            1920
        )

        assertEquals(3, groups.size)
        assertEquals(SemanticTextRole.TIMESTAMP, groups[1].role)
    }

    @Test
    fun infersAParagraphForPaddleLinesAndPreservesMemberBounds() {
        val lines = listOf(
            line("Until the country decided to invest", 70, 100, 650, 128, null, null),
            line("in projects that fit neither the country", 70, 134, 650, 162, null, null),
            line("nor the Web3 community.", 70, 168, 560, 196, null, null)
        )

        val group = SemanticTextGrouper.group(lines, 1080, 1920).single()
        val translatedSource = group.toRecognizedText()

        assertTrue(GroupingEvidence.GEOMETRY_INFERRED in group.evidence)
        assertEquals(3, translatedSource.textEraseBounds().size)
        assertEquals(70, translatedSource.bounds.left)
        assertEquals(100, translatedSource.bounds.top)
        assertEquals(650, translatedSource.bounds.right)
        assertEquals(196, translatedSource.bounds.bottom)
    }

    @Test
    fun keepsActualArticleParagraphTogetherWithoutCrossingTheParagraphBreak() {
        val firstParagraph = listOf(
            line("During the Anhui inspection, Xi also", 8, 115, 316, 135, null, null),
            line("emphasized the need to strengthen the", 8, 149, 347, 167, null, null),
            line("protection of historical and cultural", 8, 178, 310, 196, null, null),
            line("heritage, as well as the promotion of", 8, 209, 322, 226, null, null),
            line("creative transformation and innovative", 8, 239, 338, 254, null, null),
            line("development of fine traditional culture.", 8, 269, 342, 286, null, null)
        )
        val nextParagraph = line(
            "Tongcheng has woven the values",
            7,
            318,
            292,
            336,
            null,
            null
        )

        val groups = SemanticTextGrouper.group(
            firstParagraph + nextParagraph,
            388,
            894
        )

        assertEquals(2, groups.size)
        assertEquals(6, groups.first().members.size)
        assertEquals(listOf(nextParagraph), groups.last().members)
    }

    @Test
    fun doesNotCrossColumns() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("left one", 50, 100, 440, 126, null, null),
                line("right one", 620, 100, 1010, 126, null, null),
                line("left two", 50, 132, 440, 158, null, null),
                line("right two", 620, 132, 1010, 158, null, null)
            ),
            1080,
            1920
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("left one", "left two"), groups[0].members.map { it.text })
        assertEquals(listOf("right one", "right two"), groups[1].members.map { it.text })
        assertFalse(groups.any { group ->
            group.members.any { it.text.startsWith("left") } &&
                group.members.any { it.text.startsWith("right") }
        })
    }

    @Test
    fun separatesChatSenderAndPhoneRowFromTheMessageBodyByRegionOccupancy() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("~ Prof", 154, 746, 259, 781, null, null),
                line("+65 9684 3435", 656, 751, 895, 776, null, null),
                line("The Web3 industry tried and we did", 153, 807, 887, 848, null, null),
                line("quite well. Until the country decided", 154, 865, 900, 908, null, null),
                line("to invest in something that did not", 153, 923, 867, 964, null, null),
                line("22:43", 825, 1000, 911, 1024, null, null)
            ),
            1080,
            2400
        )

        assertEquals(4, groups.size)
        assertEquals(listOf("~ Prof"), groups[0].members.map { it.text })
        assertEquals(listOf("+65 9684 3435"), groups[1].members.map { it.text })
        assertEquals(SemanticTextRole.METADATA, groups[0].role)
        assertEquals(SemanticTextRole.IDENTIFIER, groups[1].role)
        assertEquals(
            listOf(
                "The Web3 industry tried and we did",
                "quite well. Until the country decided",
                "to invest in something that did not"
            ),
            groups[2].members.map { it.text }
        )
        assertTrue(GroupingEvidence.REGION_OCCUPANCY in groups[2].evidence)
        assertEquals(SemanticTextRole.TIMESTAMP, groups[3].role)
    }

    @Test
    fun separatesTruncatedWideTitleFromCompactSubtitleMetadata() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("AI for Humanity - Com...", 294, 133, 830, 184, null, null),
                line("22AE", 289, 194, 386, 229, null, null)
            ),
            1080,
            2400
        )

        assertEquals(2, groups.size)
        assertEquals(SemanticTextRole.CONTROL, groups[0].role)
        assertEquals(SemanticTextRole.CONTROL, groups[1].role)
    }

    @Test
    fun preservesBareDomainAppBrandAndNameBesidePhoneAsStructure() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("instagram.com", 214, 387, 484, 425, null, null),
                line("Instagram", 276, 560, 477, 602, null, null),
                line("Lim CW", 185, 1952, 325, 1982, null, null),
                line("+65 9478 2794", 443, 1955, 678, 1980, null, null)
            ),
            1080,
            2400
        )

        assertEquals(4, groups.size)
        assertEquals(SemanticTextRole.IDENTIFIER, groups[0].role)
        assertEquals(SemanticTextRole.IDENTIFIER, groups[1].role)
        assertEquals(SemanticTextRole.METADATA, groups[2].role)
        assertEquals(SemanticTextRole.IDENTIFIER, groups[3].role)
    }

    @Test
    fun groupsImageWrappedParagraphAndBuildsThreeNonInvadingRenderSlots() {
        val lines = listOf(
            line("The Canadian government", 178, 461, 355, 475, null, null),
            line("has awarded C$20 million", 178, 479, 351, 491, null, null),
            line("(US$19.4 million) over the", 176, 497, 350, 511, null, null),
            line("next four years to five centres of the", 131, 517, 373, 530, null, null),
            line("African Institute for Mathematical", 130, 535, 361, 547, null, null),
            line("Sciences. The centres are spread", 130, 554, 347, 569, null, null),
            line("across the continent and run through the AIMS-Next", 8, 573, 359, 587, null, null),
            line("Einstein Initiative. They will train talented young", 7, 592, 337, 607, null, null),
            line("African postgraduate researchers in mathematical", 7, 610, 345, 626, null, null),
            line("sciences.", 7, 629, 65, 642, null, null)
        )

        val group = SemanticTextGrouper.group(lines, 387, 892).single()

        assertEquals(10, group.members.size)
        assertTrue(GroupingEvidence.WRAPPED_FLOW in group.evidence)
        assertEquals(
            listOf(
                listOf(176, 461, 373, 511),
                listOf(130, 517, 373, 569),
                listOf(7, 573, 373, 642)
            ),
            group.renderSlots.map { listOf(it.left, it.top, it.right, it.bottom) }
        )
    }

    @Test
    fun mergesTwoLineArticleTitleAndPreservesMetadataAndControls() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("AFRICA-CANADA: Boost for Next", 7, 344, 323, 364, null, null),
                line("Einstein centres", 8, 368, 160, 382, null, null),
                line("Munyaradzi Makoni 18 July 2010", 8, 399, 182, 410, null, null),
                line("Iweet Whatsapp", 6, 421, 130, 441, null, null)
            ),
            387,
            892
        )

        assertEquals(3, groups.size)
        assertEquals(SemanticTextRole.TITLE, groups[0].role)
        assertEquals(2, groups[0].members.size)
        assertEquals(SemanticTextRole.METADATA, groups[1].role)
        assertEquals(SemanticTextRole.CONTROL, groups[2].role)
    }

    @Test
    fun treatsDatesInsideArticleSentencesAsBodyText() {
        val samples = listOf(
            "City,Vietnam,Aug.4,2026.Chinese film Dear You",
            "The March ended in 1956 but,",
            "Center in Florida on Jan.17,2026,ahead of the historic Artemis"
        )

        samples.forEachIndexed { index, text ->
            val group = SemanticTextGrouper.group(
                listOf(line(text, 20, 100, 820, 140, "article-$index", 0)),
                1080,
                1920
            ).single()

            assertEquals(text, SemanticTextRole.BODY, group.role)
        }
    }

    @Test
    fun keepsPureTimeAndAuthorDateAsStructuralMetadata() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("22:43", 800, 100, 900, 130, null, null),
                line(
                    "Ngotho Gichuru and Byaruhanga Rukooko 06 August 2026",
                    20,
                    180,
                    780,
                    220,
                    null,
                    null
                )
            ),
            1080,
            1920
        )

        assertEquals(SemanticTextRole.TIMESTAMP, groups[0].role)
        assertEquals(SemanticTextRole.METADATA, groups[1].role)
    }

    @Test
    fun keepsTallChatParagraphAndItsShortFinalLineInOneBodyGroup() {
        val textLines = listOf(
            "Guys you will be surprise that most",
            "of the website and chatting on",
            "whatsapp is handled by chatbot Al",
            "nowadays. what if the scammer use",
            "Al chatbot to scam you?",
            "you dont know? cos the Al cannot",
            "differentiate what is real and fact,",
            "or not. they only answer what the",
            "owner feed them.",
            "they do not know how to lie, they",
            "speak about facts that is feed by",
            "the owners."
        )
        val bounds = listOf(
            intArrayOf(148, 1419, 879, 1470), intArrayOf(145, 1476, 794, 1527),
            intArrayOf(146, 1537, 865, 1583), intArrayOf(148, 1595, 908, 1641),
            intArrayOf(147, 1651, 659, 1701), intArrayOf(143, 1708, 877, 1761),
            intArrayOf(149, 1767, 854, 1816), intArrayOf(148, 1828, 861, 1874),
            intArrayOf(150, 1884, 520, 1930), intArrayOf(147, 1941, 850, 1991),
            intArrayOf(144, 1999, 834, 2049), intArrayOf(145, 2057, 395, 2108)
        )
        val recognized = textLines.mapIndexed { index, text ->
            val item = bounds[index]
            line(text, item[0], item[1], item[2], item[3], null, index)
        } + line("15:14", 827, 2078, 924, 2126, null, 0)

        val groups = SemanticTextGrouper.group(recognized, 1080, 2400)
        val paragraph = groups.first { it.sourceText.startsWith(textLines.first()) }

        assertEquals(SemanticTextRole.BODY, paragraph.role)
        assertEquals(12, paragraph.members.size)
        assertTrue(paragraph.sourceText.endsWith("the owners."))
        assertEquals(SemanticTextRole.TIMESTAMP, groups.first { it.sourceText == "15:14" }.role)
    }

    @Test
    fun classifiesTallMultiLineOcrBlockByMemberLineHeightAsBody() {
        val memberBounds = listOf(
            Rect(148, 1419, 879, 1470), Rect(145, 1476, 794, 1527),
            Rect(146, 1537, 865, 1583), Rect(148, 1595, 908, 1641),
            Rect(147, 1651, 659, 1701), Rect(143, 1708, 877, 1761),
            Rect(149, 1767, 854, 1816), Rect(148, 1828, 861, 1874),
            Rect(150, 1884, 520, 1930), Rect(147, 1941, 850, 1991),
            Rect(144, 1999, 834, 2049), Rect(145, 2057, 395, 2108)
        )
        val paragraph = line(
            listOf(
                "Guys you will be surprise that most", "of the website and chatting on",
                "whatsapp is handled by chatbot Al", "nowadays. what if the scammer use",
                "Al chatbot to scam you?", "you dont know? cos the Al cannot",
                "differentiate what is real and fact,", "or not. they only answer what the",
                "owner feed them.", "they do not know how to lie, they",
                "speak about facts that is feed by", "the owners."
            ).joinToString("\n"),
            143,
            1419,
            908,
            2108,
            "chat-paragraph",
            0
        ).copy(componentBounds = memberBounds)

        val groups = SemanticTextGrouper.group(
            listOf(paragraph, line("15:14", 827, 2180, 924, 2228, null, 0)),
            1080,
            2400
        )

        assertEquals(SemanticTextRole.BODY, groups.first().role)
        assertEquals(12, groups.first().members.single().text.lineSequence().count())
        assertEquals(SemanticTextRole.TIMESTAMP, groups.last().role)
    }

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        blockId: String?,
        lineIndex: Int?
    ) = RecognizedText(
        text = text,
        bounds = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        },
        consensusScore = 0.9f,
        passCount = 2,
        modelConfidence = 0.92f,
        recognizerScript = RecognizerScript.LATIN,
        sourceBlockId = blockId,
        sourceLineIndex = lineIndex
    )
}
