package com.example.imagetranslate.semantic

import android.graphics.Rect
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaticImageTextFilterTest {
    @Test
    fun excludesBrowserChromeOverlayNoiseAndBottomControlFromActualArticlePage() {
        val chrome = listOf(
            line("23113RKC6C", 85, 12, 167, 21, 0.85f),
            line("9:42", 21, 43, 48, 54, 0.81f),
            line("Xi Story: Six-foot alley revea...", 112, 79, 296, 92, 0.83f)
        )
        val body = listOf(
            line("During the Anhui inspection, Xi also", 8, 115, 316, 135),
            line("emphasized the need to strengthen the", 8, 149, 347, 167),
            line("protection of historical and cultural", 8, 178, 310, 196),
            line("heritage, as well as the promotion of", 8, 209, 322, 226),
            line("creative transformation and innovative", 8, 239, 338, 254),
            line("development of fine traditional culture.", 8, 269, 342, 286),
            line("Tongcheng has woven the values", 7, 318, 292, 336),
            line("associated with the alley into community", 8, 348, 359, 366),
            line("work and grassroots governance, using", 7, 378, 344, 398),
            line("persuasion, empathy and compromise to", 9, 409, 358, 428),
            line("help resolve disputes before they escalate.", 9, 440, 373, 457),
            line("That emphasis on accommodation, Zhang", 7, 488, 367, 506),
            line("stressed, does not mean asking people to", 8, 518, 366, 535),
            line("yield without principle.", 8, 547, 334, 567),
            line("giving way, still less forcing someone to do", 8, 578, 375, 596),
            line("so, he said. It is a gesture the stronger", 12, 606, 372, 626),
            line("side takes the first step, while morality and", 8, 638, 375, 656),
            line("law work together.", 8, 668, 165, 687),
            line("The approach is also gaining broader", 8, 718, 326, 737),
            line("institutional backing. On July 24, the", 8, 748, 322, 767),
            line("Standing Committee of the Anhui", 9, 779, 296, 798),
            line("Provincial People's Congress adopted", 8, 806, 328, 827)
        )
        val overlayNoise = line("XA EEM", 181, 678, 263, 695, 0.36f)
        val bottomControl = line("Ai", 181, 845, 209, 868, 0.71f)

        val filtered = StaticImageTextFilter.filter(
            chrome + body + overlayNoise + bottomControl,
            388,
            894
        )

        assertEquals(body.map(RecognizedText::text), filtered.map(RecognizedText::text))
    }

    @Test
    fun preservesAllTextWhenTheImageIsNotArticleLike() {
        val labels = listOf(
            line("Settings", 20, 40, 120, 64),
            line("Translate", 20, 90, 150, 116),
            line("Cancel", 180, 90, 260, 116)
        )

        val filtered = StaticImageTextFilter.filter(labels, 360, 720)

        assertEquals(labels, filtered)
        assertTrue(filtered.indices.all { filtered[it] === labels[it] })
    }

    @Test
    fun restoresArticleTextThatTemporarilyWrapsAroundAnImage() {
        val wrappedParagraph = listOf(
            line("The Canadian government", 178, 461, 355, 475),
            line("has awarded CS20 million", 178, 479, 351, 491),
            line("(USS19.4million)over the", 176, 497, 350, 511),
            line("next four years to five centres of the", 131, 517, 373, 530),
            line("African Institute for Mathematical", 130, 535, 361, 547),
            line("Sciences.The centres are spread", 130, 554, 347, 569),
            line("across the continent and run through the AIMS-Next", 8, 573, 359, 587),
            line("Einstein Initiative.They will train talented young", 7, 592, 337, 607),
            line("African postgraduate researchers in mathematical", 7, 610, 345, 626),
            line("sciences.", 7, 629, 65, 642)
        )
        val nextParagraph = listOf(
            line("The funding for AIMS was championed by the Ontario", 8, 666, 374, 682),
            line("based non-profit Perimeter Institute for Theoretical", 8, 686, 352, 701),
            line("Physics new global outreach programme headed by", 8, 705, 357, 720),
            line("South African-born scientist Dr Neil Turok, who is also", 7, 723, 373, 737),
            line("the founder of AIMS and initiator of the AIMS-Next", 8, 742, 349, 754),
            line("Einstein Initiative.", 7, 761, 130, 773)
        )
        val noise = listOf(
            line("Munyaradzi Makoni 18 July 2010", 8, 399, 182, 410),
            line("Tweet Whatsapp", 6, 421, 130, 441),
            line("Privacy", 325, 809, 373, 828)
        )

        val filtered = StaticImageTextFilter.filter(
            noise + wrappedParagraph + nextParagraph,
            387,
            892
        )

        assertEquals(
            (wrappedParagraph + nextParagraph).map(RecognizedText::text),
            filtered.map(RecognizedText::text)
        )
    }

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidence: Float = 0.9f
    ) = RecognizedText(
        text = text,
        bounds = rect(left, top, right, bottom),
        consensusScore = 0.9f,
        passCount = 2,
        modelConfidence = confidence,
        recognizerScript = RecognizerScript.LATIN
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
