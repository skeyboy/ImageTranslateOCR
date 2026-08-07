package com.example.imagetranslate.ui

import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShapeAwareTextLayoutInstrumentedTest {
    @Test
    fun flowsActualImageWrappedParagraphAcrossThreeSlots() {
        val slots = listOf(
            Rect(176, 461, 373, 511),
            Rect(130, 517, 373, 569),
            Rect(7, 573, 373, 642)
        )
        val result = ShapeAwareTextLayout.layout(
            text = "加拿大政府已拨款 2,000 万加元（合 1940 万美元），在未来四年内资助非洲数学科学研究所的五个中心。" +
                "这些中心遍布整个大陆，由 AIMS-Next 爱因斯坦计划运营。它们将培训有才华的年轻非洲研究生从事数学科学研究工作。",
            paint = TextPaint(Paint.ANTI_ALIAS_FLAG),
            renderSlots = slots,
            preferredTextSizePx = 14f,
            minimumTextSizePx = 10f,
            maximumLines = 10,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            horizontalPadding = 2,
            allowOverflowMore = true
        )

        assertNotNull(result)
        recordLayout("news-shape-layout.json", result!!)
        assertTrue(result!!.outcome != ShapeAwareTextOutcome.OVERFLOW_MORE)
        assertTrue(!result.displayedText.endsWith("更多"))
        assertTrue(result.segments.isNotEmpty())
        result.segments.forEach { segment ->
            assertTrue(slots.any { slot -> slot == segment.bounds })
        }
        assertTrue(result.segments.first().bounds.left >= 176)
        assertTrue(result.segments.size >= 2)
    }

    @Test
    fun fitsActualSeventeenLineChatTranslationWithoutMore() {
        val result = ShapeAwareTextLayout.layout(
            text = "Web3 行业曾尝试过，我们也做得相当不错。直到该国决定投资一些既不符合国家利益也不符合 Web3 社区的事物时，" +
                "一切才彻底失控。剩下的都是历史了。在那之后，我们突然全部转向人工智能（AI），那真的非常危险且风险比 Web3 更大。" +
                "很高兴最近我们正在重新回归理智。如今，美国仅有的几个扎实的 Web3 项目都来自新加坡。确实，这些年轻人让我们感到自豪。" +
                "他们离开是有充分理由的，而他们肯定会回来的，因为这个地方对于那些成功的人来说是极好的地方。",
            paint = TextPaint(Paint.ANTI_ALIAS_FLAG),
            renderSlots = listOf(Rect(153, 807, 905, 1769)),
            preferredTextSizePx = 41f,
            minimumTextSizePx = 35.26f,
            maximumLines = 17,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            horizontalPadding = 5,
            allowOverflowMore = true
        )

        assertNotNull(result)
        assertTrue(result!!.outcome != ShapeAwareTextOutcome.OVERFLOW_MORE)
        assertEquals(1, result.segments.size)
        recordLayout("chat-shape-layout.json", result)
    }

    @Test
    fun usesMoreOnlyForAnExplicitlyEligibleOverflowBody() {
        val text = "这是一个需要完整上下文才能正确理解的长段落。".repeat(20)
        val arguments = LayoutArguments(text)

        val overflow = arguments.layout(allowOverflowMore = true)
        val preserved = arguments.layout(allowOverflowMore = false)

        assertEquals(ShapeAwareTextOutcome.OVERFLOW_MORE, overflow?.outcome)
        assertTrue(overflow!!.displayedText.endsWith("更多"))
        assertEquals(null, preserved)
    }

    @Test
    fun skipsAnUnusableLeadingSlotInsteadOfRejectingTheWholeServerPlan() {
        val usable = Rect(10, 40, 420, 420)
        val result = ShapeAwareTextLayout.layout(
            text = "服务端成功返回的长正文应继续在其余可用区域中完成排版。",
            paint = TextPaint(Paint.ANTI_ALIAS_FLAG),
            renderSlots = listOf(Rect(10, 10, 300, 11), usable),
            preferredTextSizePx = 30f,
            minimumTextSizePx = 22f,
            maximumLines = 8,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            horizontalPadding = 2,
            allowOverflowMore = true
        )

        assertNotNull(result)
        assertEquals(usable, result!!.segments.first().bounds)
        assertEquals("服务端成功返回的长正文应继续在其余可用区域中完成排版。", result.displayedText)
    }

    @Test
    fun keepsActualPageFlowInTheLeadingSlotByCompactingBeforeSkippingIt() {
        val leading = Rect(34, 2041, 1008, 2109)
        val result = ShapeAwareTextLayout.layout(
            text = "与此同时，老挝农业与环境部下属的气象与水文局于周三发出警告称，" +
                "部分地区预计将持续出现大范围雷暴、中到大雨以及偶尔的强风。" +
                "该局已确定 30 个地区（跨越 10 个省）存在高风险……",
            paint = TextPaint(Paint.ANTI_ALIAS_FLAG),
            renderSlots = listOf(leading, Rect(27, 2154, 1402, 2986)),
            preferredTextSizePx = 74f,
            minimumTextSizePx = 56.8f,
            maximumLines = 9,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            horizontalPadding = 8,
            allowOverflowMore = true,
            lineSpacingMultipliers = listOf(0.92f, 1f, 0.86f)
        )

        assertNotNull(result)
        assertEquals(leading, result!!.segments.first().bounds)
        assertTrue(result.segments.size >= 2)
        assertTrue(result.outcome != ShapeAwareTextOutcome.OVERFLOW_MORE)
        assertEquals(
            "与此同时，老挝农业与环境部下属的气象与水文局于周三发出警告称，" +
                "部分地区预计将持续出现大范围雷暴、中到大雨以及偶尔的强风。" +
                "该局已确定 30 个地区（跨越 10 个省）存在高风险……",
            result.displayedText
        )
    }

    private data class LayoutArguments(val text: String) {
        fun layout(allowOverflowMore: Boolean) = ShapeAwareTextLayout.layout(
            text = text,
            paint = TextPaint(Paint.ANTI_ALIAS_FLAG),
            renderSlots = listOf(Rect(10, 10, 220, 90)),
            preferredTextSizePx = 24f,
            minimumTextSizePx = 16f,
            maximumLines = 4,
            alignment = Layout.Alignment.ALIGN_NORMAL,
            horizontalPadding = 2,
            allowOverflowMore = allowOverflowMore
        )
    }

    private fun recordLayout(fileName: String, result: ShapeAwareTextResult) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.filesDir, "validation/$fileName")
        output.parentFile?.mkdirs()
        output.writeText(
            JSONObject()
                .put("outcome", result.outcome.name)
                .put("displayedText", result.displayedText)
                .put("displayedCharacterCount", result.displayedText.length)
                .put("textSizePx", result.textSizePx.toDouble())
                .put("lineSpacingMultiplier", result.lineSpacingMultiplier.toDouble())
                .put("segments", JSONArray(result.segments.map { segment ->
                    JSONObject()
                        .put("left", segment.bounds.left)
                        .put("top", segment.bounds.top)
                        .put("right", segment.bounds.right)
                        .put("bottom", segment.bounds.bottom)
                        .put("lineCount", segment.layout.lineCount)
                }))
                .toString(2)
        )
    }
}
