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
    fun keepsDiscussionMetadataSeparateFromLargerBodyParagraph() {
        val metadata = line(
            text = "CrzyLngPwd 3 minutes ago parent context on:Why aren't smart people\n" +
                "happier?(2022)",
            left = 64,
            top = 1497,
            right = 1342,
            bottom = 1588,
            blockId = "metadata-block",
            lineIndex = 0
        ).copy(
            estimatedTextHeightPx = 37f,
            typographyConfidence = 0.82f,
            componentBounds = listOf(
                Rect().apply { left = 64; top = 1497; right = 1342; bottom = 1538 },
                Rect().apply { left = 64; top = 1543; right = 324; bottom = 1588 }
            )
        )
        val body = line(
            text = "My partner describes me as a professional problem\n" +
                "solver since I was a child and fixed things people throw away",
            left = 67,
            top = 1626,
            right = 1277,
            bottom = 1734,
            blockId = "body-block",
            lineIndex = 0
        ).copy(
            estimatedTextHeightPx = 43f,
            typographyConfidence = 0.82f,
            componentBounds = listOf(
                Rect().apply { left = 67; top = 1626; right = 1154; bottom = 1677 },
                Rect().apply { left = 64; top = 1690; right = 1232; bottom = 1734 }
            )
        )

        val groups = SemanticTextGrouper.group(listOf(metadata, body), 1440, 3200)

        assertEquals(2, groups.size)
        assertEquals(SemanticTextRole.METADATA, groups[0].role)
        assertEquals(SemanticTextRole.BODY, groups[1].role)
    }

    @Test
    fun preservesClippedContinuationEvidence() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line(
                    text = "visible paragraph continuation",
                    left = 20,
                    top = 140,
                    right = 600,
                    bottom = 190,
                    blockId = "edge-paragraph",
                    lineIndex = 0
                ).copy(continuationAtTop = true)
            ),
            viewportWidth = 720,
            viewportHeight = 1600
        )

        val group = groups.single()
        assertTrue(GroupingEvidence.TOP_CLIPPED_CONTINUATION in group.evidence)
        assertTrue(group.toRecognizedText().continuationAtTop)
    }

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
    fun mergesRelaxedShortFinalLineIntoSameBlockArticleParagraph() {
        val texts = listOf(
            "By leveraging big data and algorithms,the",
            "system analyzes basic blood test results to",
            "flag potential health risks,offering",
            "diagnostic support and enabling early",
            "medical intervention."
        )
        val bounds = listOf(
            intArrayOf(34, 224, 1386, 294),
            intArrayOf(30, 337, 1391, 402),
            intArrayOf(28, 446, 1123, 514),
            intArrayOf(30, 559, 1236, 631),
            intArrayOf(33, 673, 696, 727)
        )
        val estimatedHeights = listOf(63f, 59f, 62f, 65f, 48f)
        val lines = texts.indices.map { index ->
            line(
                text = texts[index],
                left = bounds[index][0],
                top = bounds[index][1],
                right = bounds[index][2],
                bottom = bounds[index][3],
                blockId = "mlkit-article-body",
                lineIndex = index
            ).copy(
                estimatedTextHeightPx = estimatedHeights[index],
                typographyConfidence = 0.82f
            )
        }

        val group = SemanticTextGrouper.group(lines, 1440, 3200).single()

        assertEquals(5, group.members.size)
        assertEquals(texts.joinToString("\n"), group.sourceText)
        assertTrue(GroupingEvidence.FONT_SCALE_RELAXED_SAME_BLOCK in group.evidence)
    }

    @Test
    fun mergesRelaxedTailAfterPreviouslyGroupedMultiLineCandidates() {
        fun multiLine(
            text: String,
            top: Int,
            bottom: Int,
            lineIndex: Int,
            estimatedHeight: Float,
            componentBounds: List<Rect>
        ) = line(
            text = text,
            left = componentBounds.minOf(Rect::left),
            top = top,
            right = componentBounds.maxOf(Rect::right),
            bottom = bottom,
            blockId = "mlkit-article-body",
            lineIndex = lineIndex
        ).copy(
            componentBounds = componentBounds,
            estimatedTextHeightPx = estimatedHeight,
            typographyConfidence = 0.82f
        )
        val first = multiLine(
            "By leveraging big data and algorithms,the\n" +
                "system analyzes basic blood test results to",
            152,
            344,
            0,
            73f,
            listOf(rect(33, 152, 1389, 233), rect(31, 276, 1390, 344))
        )
        val second = multiLine(
            "flag potential health risks,offering\n" +
                "diagnostic support and enabling early",
            387,
            569,
            2,
            62f,
            listOf(rect(27, 387, 1122, 449), rect(32, 500, 1238, 569))
        )
        val tail = line(
            "medical intervention.",
            33,
            612,
            696,
            666,
            "mlkit-article-body",
            4
        ).copy(estimatedTextHeightPx = 48f, typographyConfidence = 0.82f)

        val group = SemanticTextGrouper.group(listOf(first, second, tail), 1440, 3200).single()

        assertEquals(3, group.members.size)
        assertEquals(5, group.toRecognizedText().textEraseBounds().size)
        assertTrue(GroupingEvidence.FONT_SCALE_RELAXED_SAME_BLOCK in group.evidence)
    }

    @Test
    fun recoversArticleParagraphAcrossMlKitBlockFragmentsAndFontNoise() {
        val specs = listOf(
            arrayOf("This Al-driven transformation is taking", "block-a", 0, 31, 2156, 1248, 2218, 56f),
            arrayOf("place across multiple sectors.In", "block-a", 1, 33, 2268, 1048, 2330, 56f),
            arrayOf("classrooms at school affiliated with Inner", "block-b", 0, 32, 2381, 1394, 2434, 48f),
            arrayOf("Mongolia Normal University,smart", "block-b", 1, 66, 2483, 1132, 2562, 71f),
            arrayOf("blackboards respond to voice commands,", "block-c", 0, 33, 2604, 1350, 2671, 60f),
            arrayOf("while Al systems analyze student", "block-c", 1, 29, 2717, 1086, 2786, 62f),
            arrayOf("performance in real time and create", "block-c", 2, 33, 2824, 1179, 2887, 56f),
            arrayOf("personalized learning plans.", "block-d", 0, 33, 2941, 917, 3010, 62f)
        )
        val lines = specs.map { spec ->
            line(
                text = spec[0] as String,
                blockId = spec[1] as String,
                lineIndex = spec[2] as Int,
                left = spec[3] as Int,
                top = spec[4] as Int,
                right = spec[5] as Int,
                bottom = spec[6] as Int
            ).copy(
                estimatedTextHeightPx = spec[7] as Float,
                typographyConfidence = 0.82f
            )
        }

        val group = SemanticTextGrouper.group(lines, 1440, 3200).single()

        assertEquals(8, group.members.size)
        assertEquals(1, group.renderSlots.size)
        assertTrue(GroupingEvidence.PARAGRAPH_CONTINUATION_RECOVERY in group.evidence)
        assertTrue(GroupingEvidence.FONT_SCALE_RELAXED_SAME_BLOCK in group.evidence)
    }

    @Test
    fun keepsCompleteMultiLineParagraphCandidatesSeparatedByLineHeightGap() {
        val firstComponents = listOf(
            rect(31, 1099, 1236, 1161), rect(32, 1212, 1091, 1278),
            rect(59, 1322, 1301, 1390), rect(30, 1431, 1157, 1502),
            rect(28, 1537, 1224, 1618), rect(29, 1650, 1301, 1734),
            rect(31, 1764, 834, 1843)
        )
        val secondComponents = listOf(
            rect(30, 1952, 1248, 2018), rect(30, 2054, 1048, 2132),
            rect(32, 2178, 1394, 2232), rect(67, 2286, 1132, 2359),
            rect(33, 2402, 1350, 2469), rect(27, 2511, 1086, 2577),
            rect(34, 2627, 1179, 2691), rect(33, 2733, 917, 2802)
        )
        val first = line(
            "The Haidong Road Community Health\nCenter in Hohhot,capital of Inner\n" +
                "Mongolia Autonomous Region,is one of\nthe pioneering clinics showing how\n" +
                "technology is reshaping everyday life,\n" +
                "thanks to the region's burgeoning green\ncomputing infrastructure.",
            28,
            1099,
            1303,
            1843,
            "haidong-block",
            0
        ).copy(
            componentBounds = firstComponents,
            estimatedTextHeightPx = 64f,
            typographyConfidence = 0.82f
        )
        val second = line(
            "This Al-driven transformation is taking\nplace across multiple sectors.In\n" +
                "classrooms at school affiliated with Inner\n" +
                "Mongolia Normal University,smart\nblackboards respond to voice commands,\n" +
                "while Al systems analyze student\nperformance in real time and create\n" +
                "personalized learning plans.",
            27,
            1952,
            1394,
            2802,
            null,
            0
        ).copy(
            componentBounds = secondComponents,
            estimatedTextHeightPx = 60f,
            typographyConfidence = 0.82f
        )

        val groups = SemanticTextGrouper.group(listOf(first, second), 1440, 3200)

        assertEquals(2, groups.size)
        assertEquals(7, groups[0].toRecognizedText().textEraseBounds().size)
        assertEquals(8, groups[1].toRecognizedText().textEraseBounds().size)
    }

    @Test
    fun mergesIndentedSameBlockCompactQuoteTail() {
        val first = line(
            "Nearly a year of full time work at 40hrs/week went",
            109,
            2671,
            1220,
            2724,
            "quote-block",
            0
        ).copy(estimatedTextHeightPx = 48f, typographyConfidence = 0.82f)
        val tail = line(
            "into this",
            67,
            2742,
            233,
            2778,
            "quote-block",
            1
        ).copy(estimatedTextHeightPx = 32f, typographyConfidence = 0.82f)

        val group = SemanticTextGrouper.group(listOf(first, tail), 1440, 3200).single()

        assertEquals(2, group.members.size)
        assertEquals(1, group.renderSlots.size)
        assertTrue(GroupingEvidence.PARAGRAPH_CONTINUATION_RECOVERY in group.evidence)
    }

    @Test
    fun keepsQuotedListItemOutsidePrecedingParagraph() {
        val groups = SemanticTextGrouper.group(
            listOf(
                line("We can cross-check dictionary entries.The standard", 64, 1902, 1179, 1947, null, 0),
                line("dictionary fully backs this up:", 64, 1964, 1053, 2008, null, 1),
                line("> logos", 64, 2051, 227, 2100, null, 2)
            ),
            1440,
            3200
        )

        assertEquals(2, groups.size)
        assertEquals(2, groups[0].members.size)
        assertEquals(SemanticTextRole.LIST_ITEM, groups[1].role)
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
    fun keepsDifferentTypographyTiersSeparateInsideTheSameOcrBlock() {
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

        assertEquals(2, groups.size)
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
        assertEquals(
            listOf(28f, 28f, 28f),
            translatedSource.componentTextHeightsPx
        )
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

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
