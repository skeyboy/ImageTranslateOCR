package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.R
import com.example.imagetranslate.databinding.OverlayActiveScreenCaptureBinding
import com.example.imagetranslate.databinding.OverlayScreenshotActionsBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ScreenshotOverlayLayoutTest {
    @Test
    fun compactSingleLineRectUsesNarrowCompleteTextFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(500, 200, Bitmap.Config.ARGB_8888)
        val slot = Rect(87, 75, 322, 126)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "Sure,buddy.",
                slot.left.toFloat(),
                slot.bottom - 6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 41f
                }
            )
        }
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "Sure,buddy.",
                        translation = "当然啦，兄弟。",
                        bounds = slot,
                        sourceLineBounds = listOf(slot),
                        renderSlots = listOf(slot),
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 2,
                            minimumTextScale = 0.86f,
                            lineSpacingMultiplier = 1f,
                            allowMore = false,
                            sourceLineCount = 1,
                            layoutShape = "RECT",
                            role = "BODY"
                        ),
                        groupId = "compact-single-line-regression"
                    )
                )
            )

            assertEquals(1, result.renderedRegionCount)
            assertEquals(0, result.failedRegionCount)
            assertEquals(1, result.renderedText.single().lineCount)
            assertTrue(result.renderedText.single().textScale <= 0.65f)
            assertEquals(1, result.patches.size)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun titleLikeParagraphDoesNotUseBodyRectFallback() {
        val slots = listOf(
            Rect(148, 1419, 879, 1470), Rect(145, 1476, 794, 1527),
            Rect(146, 1537, 865, 1583), Rect(148, 1595, 908, 1641),
            Rect(147, 1651, 659, 1701), Rect(143, 1708, 877, 1761),
            Rect(149, 1767, 854, 1816), Rect(148, 1828, 861, 1874),
            Rect(150, 1884, 520, 1930), Rect(147, 1941, 850, 1991),
            Rect(144, 1999, 834, 2049), Rect(145, 2057, 395, 2108)
        )
        val merged = denseBodyRectFallback(
            renderSlots = slots,
            layoutShape = "FLOW_SLOTS",
            sourceLineCount = slots.size,
            role = "TITLE",
            sourceTextHeightsPx = List(slots.size) { 43f }
        )

        assertNull(merged)
    }

    @Test
    fun targetInkHeightIsCappedByTheMeasuredSourceGlyph() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val size = targetTextSizeForSourceGlyph(
            paint = paint,
            text = "大家会感到惊讶的是",
            preferredTextSizePx = 56f,
            sourceGlyphHeightPx = 32f
        )
        paint.textSize = size
        val ink = Rect()
        val sample = "大家会感到惊讶的是"
        paint.getTextBounds(sample, 0, sample.length, ink)

        assertTrue(ink.height() <= 33)
        assertTrue(size < 56f)
    }

    @Test
    fun unsafeTitleGeometryRestoresSourceInsteadOfCrowdingText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val sourceLines = listOf(
            "Guys you will be surprise that most", "of the website and chatting on",
            "whatsapp is handled by chatbot AI", "nowadays. what if the scammer use",
            "AI chatbot to scam you?", "you dont know? cos the AI cannot",
            "differentiate what is real and fact,", "or not. they only answer what the",
            "owner feed them.", "they do not know how to lie, they",
            "speak about facts that is feed by", "the owners."
        )
        val slots = listOf(
            Rect(148, 1419, 879, 1470), Rect(145, 1476, 794, 1527),
            Rect(146, 1537, 865, 1583), Rect(148, 1595, 908, 1641),
            Rect(147, 1651, 659, 1701), Rect(143, 1708, 877, 1761),
            Rect(149, 1767, 854, 1816), Rect(148, 1828, 861, 1874),
            Rect(150, 1884, 520, 1930), Rect(147, 1941, 850, 1991),
            Rect(144, 1999, 834, 2049), Rect(145, 2057, 395, 2108)
        )
        Canvas(bitmap).apply {
            drawColor(Color.rgb(190, 190, 190))
            val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 35, 35)
                textSize = 43f
            }
            sourceLines.zip(slots).forEach { (text, slot) ->
                drawText(text, slot.left.toFloat(), slot.bottom - 5f, sourcePaint)
            }
        }
        val translation = "大家会感到惊讶的是，如今大多数网站和 WhatsApp 上的聊天都是由 AI " +
            "聊天机器人处理的。如果骗子使用 AI 聊天机器人来诈骗你怎么办？你不知道吗？" +
            "因为 AI 无法区分什么是真实和事实，他们只回答所有者提供给他们的内容。" +
            "他们不知道如何撒谎，只会复述所有者提供的信息。"
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = sourceLines.joinToString("\n"),
                        translation = translation,
                        bounds = Rect(143, 1419, 908, 2108),
                        sourceLineBounds = slots,
                        renderSlots = slots,
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 12,
                            minimumTextScale = 0.86f,
                            lineSpacingMultiplier = 0.92f,
                            allowMore = false,
                            sourceLineCount = 12,
                            layoutShape = "FLOW_SLOTS",
                            role = "TITLE"
                        ),
                        groupId = "unsafe-title-regression"
                    )
                )
            )

            assertEquals(0, result.renderedRegionCount)
            assertEquals(1, result.failedRegionCount)
            assertTrue(result.renderedText.isEmpty())
            assertTrue(result.patches.isEmpty())
            val failure = result.renderFailures.single()
            assertEquals("unsafe-title-regression", failure.groupId)
            assertEquals("TEXT_DOES_NOT_FIT", failure.reason)
            assertEquals("FLOW_SLOTS", failure.layoutShape)
            assertEquals(slots, failure.renderSlots)
            assertTrue(failure.preferredTextSizePx > 0f)
            assertTrue(failure.lastAttemptedTextSizePx > 0f)
            assertEquals(listOf(1f), failure.attemptedLineSpacingMultipliers)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun imageWrappedFlowDoesNotUseBodyRectFallback() {
        val slots = listOf(
            Rect(176, 461, 373, 511),
            Rect(130, 517, 373, 569),
            Rect(7, 573, 373, 642)
        )

        assertNull(
            denseBodyRectFallback(slots, "FLOW_SLOTS", sourceLineCount = 3, role = "BODY")
        )
    }

    @Test
    fun homogeneousBodyParagraphCanUseOneContinuousRect() {
        val slots = listOf(
            Rect(40, 100, 600, 140),
            Rect(40, 148, 600, 188),
            Rect(40, 196, 140, 236)
        )

        assertEquals(
            Rect(40, 100, 600, 236),
            denseBodyRectFallback(
                slots,
                "FLOW_SLOTS",
                sourceLineCount = 3,
                role = "BODY",
                sourceTextHeightsPx = listOf(30f, 29f, 30f)
            )
        )
    }

    @Test
    fun mixedTypographyBodyParagraphDoesNotUseOneContinuousRect() {
        val slots = listOf(
            Rect(40, 100, 600, 148),
            Rect(40, 156, 600, 196),
            Rect(40, 204, 260, 234)
        )

        assertNull(
            denseBodyRectFallback(
                slots,
                "FLOW_SLOTS",
                sourceLineCount = 3,
                role = "BODY",
                sourceTextHeightsPx = listOf(42f, 30f, 22f)
            )
        )
    }

    @Test
    fun narrowTailFlowCanUseSafeUnionOnlyAfterStrictLayoutFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1440, 500, Bitmap.Config.ARGB_8888)
        val slots = listOf(
            Rect(60, 100, 1113, 145),
            Rect(60, 152, 258, 191)
        )
        assertEquals(
            Rect(60, 100, 1113, 191),
            safeFlowUnionRectFallback(
                renderSlots = slots,
                layoutShape = "FLOW_SLOTS",
                sourceLineCount = 2,
                role = "BODY",
                sourceTextHeightsPx = listOf(39f, 38f)
            )
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 39f
            }.also { paint ->
                drawText(
                    "But the concept is employed in the Meta-Circular /",
                    slots[0].left.toFloat(),
                    slots[0].bottom - 4f,
                    paint
                )
                drawText(
                    "Evaluator.",
                    slots[1].left.toFloat(),
                    slots[1].bottom - 4f,
                    paint
                )
            }
        }
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val translation = "但这一概念应用于 Meta-Circular Evaluator（元循环求值器）中。"
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "But the concept is employed in the Meta-Circular /\n" +
                            "Evaluator.",
                        translation = translation,
                        bounds = Rect(60, 100, 1113, 191),
                        sourceLineBounds = slots,
                        sourceTextHeightsPx = listOf(39f, 38f),
                        renderSlots = slots,
                        sourceCoverSlots = slots,
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 2,
                            minimumTextScale = 0.86f,
                            maximumTextScale = 1f,
                            lineSpacingMultiplier = 1f,
                            allowMore = false,
                            sourceLineCount = 2,
                            layoutShape = "FLOW_SLOTS",
                            role = "BODY",
                            verticalAlignment = "TOP"
                        ),
                        groupId = "server-v4-narrow-tail-regression"
                    )
                )
            )

            assertEquals(1, result.renderedRegionCount)
            assertEquals(0, result.failedRegionCount)
            assertTrue(result.renderFailures.isEmpty())
            val evidence = result.renderedText.single()
            assertEquals(1f, evidence.lineSpacingMultiplier)
            assertEquals(1, evidence.usedRenderSlotCount)
            assertFalse(evidence.clipped)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun twoLineTitleCanUseSafeUnionWhenItsTypographyAndGeometryAreContinuous() {
        val slots = listOf(
            Rect(58, 660, 1260, 761),
            Rect(61, 799, 597, 880)
        )

        assertEquals(
            Rect(58, 660, 1260, 880),
            safeFlowUnionRectFallback(
                renderSlots = slots,
                layoutShape = "FLOW_SLOTS",
                sourceLineCount = 2,
                role = "TITLE",
                sourceTextHeightsPx = listOf(100f, 73f)
            )
        )
        assertNull(
            safeFlowUnionRectFallback(
                renderSlots = slots + Rect(60, 930, 700, 1010),
                layoutShape = "FLOW_SLOTS",
                sourceLineCount = 3,
                role = "TITLE",
                sourceTextHeightsPx = listOf(100f, 73f, 80f)
            )
        )
    }

    @Test
    fun twoLineListItemCanUseSafeUnionWithoutCrossingTheNextBullet() {
        val slots = listOf(
            Rect(154, 2966, 1272, 3024),
            Rect(199, 3068, 1360, 3121)
        )

        assertEquals(
            Rect(154, 2966, 1360, 3121),
            safeFlowUnionRectFallback(
                renderSlots = slots,
                layoutShape = "FLOW_SLOTS",
                sourceLineCount = 2,
                role = "LIST_ITEM",
                sourceTextHeightsPx = listOf(53f, 48f)
            )
        )
    }

    @Test
    fun wrappedOrMixedTypographyFlowCannotUseSafeUnion() {
        val wrapped = listOf(
            Rect(176, 461, 373, 511),
            Rect(130, 517, 373, 569),
            Rect(7, 573, 373, 642)
        )
        assertNull(
            safeFlowUnionRectFallback(
                wrapped,
                "FLOW_SLOTS",
                sourceLineCount = 3,
                role = "BODY",
                sourceTextHeightsPx = listOf(42f, 41f, 40f)
            )
        )
        assertNull(
            safeFlowUnionRectFallback(
                listOf(Rect(40, 100, 600, 145), Rect(40, 152, 220, 188)),
                "FLOW_SLOTS",
                sourceLineCount = 2,
                role = "BODY",
                sourceTextHeightsPx = listOf(42f, 30f)
            )
        )
    }

    @Test
    fun singleLineTranslationUsesVerifiedBlankSpaceToExpandSafely() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
        val slot = Rect(32, 120, 180, 163)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 34f
            }.also { paint ->
                save()
                clipRect(slot)
                drawText("is an email prov...", slot.left.toFloat(), slot.bottom - 5f, paint)
                restore()
            }
        }
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "is an email prov...",
                        translation = "是一个电子邮件提供商...",
                        bounds = slot,
                        sourceLineBounds = listOf(slot),
                        sourceTextHeightsPx = listOf(34f),
                        renderSlots = listOf(slot),
                        sourceCoverSlots = listOf(slot),
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 2,
                            minimumTextScale = 0.86f,
                            maximumTextScale = 1f,
                            lineSpacingMultiplier = 1f,
                            allowMore = false,
                            sourceLineCount = 1,
                            layoutShape = "RECT",
                            role = "BODY",
                            verticalAlignment = "CENTER"
                        ),
                        groupId = "single-line-safe-expansion"
                    )
                )
            )

            assertEquals(1, result.renderedRegionCount)
            assertEquals(0, result.failedRegionCount)
            assertTrue(result.renderFailures.isEmpty())
            assertTrue(result.patches.single().bounds.right > slot.right)
            val evidence = result.renderedText.single()
            assertEquals(1f, evidence.lineSpacingMultiplier)
            assertEquals(1, evidence.lineCount)
            assertFalse(evidence.clipped)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun horizontalExpansionRejectsOccupiedOrExcessivelyWideSpace() {
        val bitmap = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
        val slot = Rect(32, 120, 180, 163)
        Canvas(bitmap).drawColor(Color.WHITE)
        try {
            assertNull(
                safeSingleLineHorizontalExpansion(
                    bitmap = bitmap,
                    sourceSlot = slot,
                    occupiedBounds = listOf(Rect(220, 115, 300, 170)),
                    requiredWidthPx = 360,
                    layoutShape = "RECT",
                    sourceLineCount = 1,
                    role = "BODY"
                )
            )
            assertNull(
                safeSingleLineHorizontalExpansion(
                    bitmap = bitmap,
                    sourceSlot = slot,
                    occupiedBounds = emptyList(),
                    requiredWidthPx = 600,
                    layoutShape = "RECT",
                    sourceLineCount = 1,
                    role = "BODY"
                )
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun excessivelyLongSingleLineRestoresSourceWithoutEmergencyTinyText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(720, 400, Bitmap.Config.ARGB_8888)
        val slot = Rect(32, 120, 180, 163)
        Canvas(bitmap).drawColor(Color.WHITE)
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "short source",
                        translation = "这是一段无法安全放入单行区域的超长译文".repeat(8),
                        bounds = slot,
                        sourceLineBounds = listOf(slot),
                        sourceTextHeightsPx = listOf(34f),
                        renderSlots = listOf(slot),
                        sourceCoverSlots = listOf(slot),
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 2,
                            minimumTextScale = 0.86f,
                            maximumTextScale = 1f,
                            lineSpacingMultiplier = 1f,
                            allowMore = false,
                            sourceLineCount = 1,
                            layoutShape = "RECT",
                            role = "BODY",
                            verticalAlignment = "CENTER"
                        ),
                        groupId = "single-line-overlong"
                    )
                )
            )

            assertEquals(0, result.renderedRegionCount)
            assertEquals(1, result.failedRegionCount)
            val failure = result.renderFailures.single()
            assertEquals("TEXT_DOES_NOT_FIT", failure.reason)
            assertTrue(failure.minimumAttemptedTextSizePx >= 24f)
            assertTrue(
                failure.lastAttemptedTextSizePx >= failure.minimumAttemptedTextSizePx
            )
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun shorterTranslationUsesSafeFlowSlotPrefixWhenStrictThreeSlotLayoutFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1440, 1000, Bitmap.Config.ARGB_8888)
        val slots = listOf(
            Rect(67, 143, 1217, 192),
            Rect(64, 208, 1284, 251),
            Rect(63, 270, 528, 306)
        )
        val sourceLines = listOf(
            "Replacing governments,hard sanctions perhaps yes.",
            "Throwing out an entire people?Why Why not throw out",
            "the Palestinians then?"
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 43f
            }.also { paint ->
                sourceLines.zip(slots).forEach { (text, slot) ->
                    drawText(text, slot.left.toFloat(), slot.bottom - 4f, paint)
                }
            }
        }
        val translation = "更替政府，实施严厉制裁，或许可以。但驱逐整个民族？为什么？" +
            "那为什么不把巴勒斯坦人也赶走呢？"
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = sourceLines.joinToString("\n"),
                        translation = translation,
                        bounds = Rect(63, 143, 1284, 306),
                        sourceLineBounds = slots,
                        sourceTextHeightsPx = listOf(44f, 39f, 32f),
                        renderSlots = slots,
                        sourceCoverSlots = slots,
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 3,
                            minimumTextScale = 0.86f,
                            maximumTextScale = 1f,
                            lineSpacingMultiplier = 1f,
                            allowMore = false,
                            sourceLineCount = 3,
                            layoutShape = "FLOW_SLOTS",
                            role = "BODY",
                            verticalAlignment = "TOP"
                        ),
                        groupId = "server-v4-flow-prefix-regression"
                    )
                )
            )

            assertEquals(1, result.renderedRegionCount)
            assertEquals(0, result.failedRegionCount)
            assertTrue(result.renderFailures.isEmpty())
            val evidence = result.renderedText.single()
            assertEquals(1f, evidence.lineSpacingMultiplier)
            assertTrue(evidence.usedRenderSlotCount < slots.size)
            assertFalse(evidence.clipped)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun sourceCoverSlotsEraseMemberTextOutsideTheUsedTranslationSlot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(420, 260, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            Paint().apply { color = Color.BLACK }.also { paint ->
                drawRect(20f, 40f, 390f, 80f, paint)
                drawRect(20f, 150f, 390f, 190f, paint)
            }
        }
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "first source line\nsecond source line",
                        translation = "译",
                        bounds = Rect(20, 40, 390, 190),
                        sourceLineBounds = listOf(
                            Rect(20, 40, 390, 80),
                            Rect(20, 150, 390, 190)
                        ),
                        renderSlots = listOf(Rect(20, 40, 390, 80)),
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 1,
                            minimumTextScale = 0.86f,
                            sourceLineCount = 2
                        )
                    )
                )
            )

            val patch = result.patches.single()
            val localX = 200 - patch.bounds.left
            val localY = 170 - patch.bounds.top
            assertTrue(
                "The second OCR member line must be painted even when translation fits earlier",
                Color.red(patch.bitmap.getPixel(localX, localY)) > 32
            )
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun authoritativeTwoSlotLongBodyProducesAnOverlayPatch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1440, 3200, Bitmap.Config.ARGB_8888)
        val lines = listOf(
            "Meanwhile,the Department of" to Rect(64, 1206, 1008, 1278),
            "Meteorology and Hydrology under the Lao" to Rect(34, 1323, 1384, 1391),
            "Ministry of Agriculture and Environment" to Rect(64, 1424, 1314, 1500),
            "issued warning on Wednesday that" to Rect(33, 1546, 1210, 1608),
            "widespread thunderstorms,moderate to" to Rect(29, 1658, 1317, 1723),
            "heavy rainfall,and occasional strong winds" to Rect(53, 1762, 1398, 1833),
            "are expected to continue in sonme areas." to Rect(31, 1883, 1301, 1944),
            "The department identified 30districts" to Rect(31, 1983, 1246, 2059),
            "across 10 provinces as being at high risk of" to Rect(32, 2106, 1402, 2173),
            "flash floods and landslides." to Rect(30, 2218, 889, 2272)
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 62f
            }
            lines.forEach { (text, bounds) ->
                drawText(text, bounds.left.toFloat(), bounds.bottom - 6f, paint)
            }
        }
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = lines.joinToString("\n") { it.first },
                        translation = "与此同时，老挝农业与环境部下属的气象与水文局于周三发出警告称，" +
                            "部分地区预计将持续出现大范围雷暴、中到大雨以及偶尔的强风。" +
                            "该局已确定全国10个省共30个地区面临山洪和泥石流的高风险。",
                        bounds = Rect(29, 1206, 1402, 2272),
                        sourceLineBounds = lines.map { Rect(it.second) },
                        renderSlots = listOf(
                            Rect(64, 1206, 1008, 1278),
                            Rect(29, 1323, 1402, 2272)
                        ),
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 10,
                            minimumTextScale = 0.86f,
                            lineSpacingMultiplier = 0.92f,
                            allowMore = true,
                            sourceLineCount = 10
                        )
                    )
                )
            )

            assertEquals(1, result.expectedRegionCount)
            assertEquals(1, result.renderedRegionCount)
            assertEquals(0, result.failedRegionCount)
            assertEquals(1, result.patches.size)
            assertEquals(1, result.renderedText.size)
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun heterogeneousBodyFlowDoesNotMergeIntoOneRect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1440, 2600, Bitmap.Config.ARGB_8888)
        val slots = listOf(
            Rect(57, 1914, 1371, 1971),
            Rect(59, 2009, 921, 2066),
            Rect(97, 2097, 1094, 2161),
            Rect(59, 2198, 1303, 2256),
            Rect(58, 2291, 1292, 2351),
            Rect(58, 2387, 190, 2430)
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            Paint().apply { color = Color.BLACK }.also { paint ->
                slots.forEach { slot -> drawRect(slot, paint) }
                drawRect(1332f, 2310f, 1350f, 2345f, paint)
            }
        }
        val translation = "公钥是你之前生成的那一个，看起来应该类似于 did:key:string。" +
            "一旦此命令完成，你就大功告成了。你现在已经将自己的轮换密钥附加到了你的账户上。看一看！"
        assertNull(
            denseBodyRectFallback(slots, "FLOW_SLOTS", sourceLineCount = 6, role = "CAPTION")
        )
        val processor = BackgroundTranslatedImageProcessor(context)
        try {
            val result = processor.renderDeterministicOverlay(
                bitmap = bitmap,
                regions = listOf(
                    LiveDeterministicTranslationRegion(
                        sourceText = "The public key is the one you generated earlier\n" +
                            "and should look something like\ndid:key:string.Once this command\n" +
                            "completes you're done.You've now attached\n" +
                            "your own rotation key to your account.Take\nlook!",
                        translation = translation,
                        bounds = Rect(57, 1914, 1371, 2430),
                        sourceLineBounds = slots,
                        renderSlots = slots,
                        displayHints = SmartAssistDisplayHints(
                            preferredMaxLines = 6,
                            minimumTextScale = 0.86f,
                            lineSpacingMultiplier = 1f,
                            allowMore = true,
                            sourceLineCount = 6,
                            layoutShape = "FLOW_SLOTS",
                            role = "BODY"
                        )
                    )
                )
            )

            assertEquals(1, result.renderedRegionCount)
            val evidence = result.renderedText.single()
            assertTrue(evidence.availableHeightPx < 516)
            assertEquals(1f, evidence.lineSpacingMultiplier)
            val patch = result.patches.single()
            val residualPixel = patch.bitmap.getPixel(
                1340 - patch.bounds.left,
                2325 - patch.bounds.top
            )
            assertTrue(
                "A rejected heterogeneous merge must not erase pixels outside OCR ownership",
                Color.red(residualPixel) < 96
            )
        } finally {
            processor.close()
            bitmap.recycle()
        }
    }

    @Test
    fun activeCaptureOverlayProvidesContinuousTranslationControls() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
        val binding = OverlayActiveScreenCaptureBinding.inflate(
            LayoutInflater.from(themedContext)
        )

        assertEquals(View.VISIBLE, binding.expandedCaptureControls.visibility)
        assertEquals(View.GONE, binding.collapsedCaptureHandle.visibility)
        assertTrue(binding.btnActiveOverlayCapture.isClickable)
        assertTrue(binding.btnActiveOverlayStop.isClickable)
        assertTrue(binding.btnCancelActivePreview.isClickable)
        assertTrue(binding.btnActiveOverlayMode.isClickable)
        assertTrue(binding.btnActiveOverlaySettings.isClickable)
        assertTrue(binding.btnCollapseActiveOverlay.isClickable)
        assertTrue(binding.btnExpandActiveOverlay.isClickable)
        assertEquals("开始识别", binding.btnActiveOverlayCapture.text.toString())
        assertEquals("中英互译", binding.btnActiveOverlayMode.text.toString())
        assertEquals(
            "识别策略设置",
            binding.btnActiveOverlaySettings.contentDescription.toString()
        )
        assertEquals("录屏识别", binding.tvCollapsedOverlayStatus.text.toString())
        assertEquals(View.INVISIBLE, binding.collapsedOverlayProgress.visibility)
    }

    @Test
    fun translationLayerOwnsAndRecyclesDisplayedPatches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val patchBitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = ScreenTranslationOverlayView(context)
            assertEquals(View.INVISIBLE, view.visibility)
            assertFalse(view.isClickable)
            assertFalse(view.isFocusable)
            assertFalse(view.isLongClickable)
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                view.importantForAccessibility
            )

            view.replacePatches(
                listOf(ScreenTranslationPatch(Rect(4, 6, 16, 14), patchBitmap)),
                sourceWidth = 100,
                sourceHeight = 200
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 200, 400)
            assertEquals(View.VISIBLE, view.visibility)
            assertEquals(listOf(Rect(8, 12, 32, 28)), view.signatureOcclusionBounds())

            assertTrue(view.clearForViewportMovement())
            assertEquals(View.INVISIBLE, view.visibility)
            assertTrue(patchBitmap.isRecycled)
            assertTrue(view.signatureOcclusionBounds().isEmpty())

            view.setPatchesVisible(false, animateChange = false)
            assertTrue(view.signatureOcclusionBounds().isEmpty())

            view.clearPatches()
            assertEquals(View.INVISIBLE, view.visibility)
        }
    }

    @Test
    fun overlayProvidesPreviewStatusPreferenceAndActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = ContextThemeWrapper(context, R.style.Theme_ImageTranslate)
        val binding = OverlayScreenshotActionsBinding.inflate(
            LayoutInflater.from(themedContext)
        )

        assertEquals(View.VISIBLE, binding.ivScreenshotPreview.visibility)
        assertEquals(View.VISIBLE, binding.screenshotPreviewLoading.visibility)
        assertTrue(
            binding.screenshotPreviewContainer.layoutParams.height >=
                (220 * context.resources.displayMetrics.density).toInt()
        )
        assertEquals(View.VISIBLE, binding.tvOverlayStatus.visibility)
        assertEquals(View.VISIBLE, binding.switchOverlayAutoTranslate.visibility)
        assertEquals("执行翻译", binding.btnOverlayTranslate.text.toString())
        assertEquals("翻译并查看", binding.btnOverlayView.text.toString())
        assertEquals("翻译并保存", binding.btnOverlaySave.text.toString())
        assertTrue(binding.btnOverlayDismiss.isClickable)
    }

    @Test
    fun controllerInflatesWithServiceLikeApplicationContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        ScreenshotOverlayController(
            context,
            object : ScreenshotOverlayController.Listener {
                override fun onIgnore(item: ScreenshotOverlayItem) = Unit
                override fun onTranslate(item: ScreenshotOverlayItem) = Unit
                override fun onTranslateAndView(item: ScreenshotOverlayItem) = Unit
                override fun onTranslateAndSave(item: ScreenshotOverlayItem) = Unit
                override fun onAutoTranslateChanged(
                    item: ScreenshotOverlayItem,
                    enabled: Boolean
                ) = Unit
            }
        )

    }
}
