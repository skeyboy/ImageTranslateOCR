package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ScreenshotOverlayLayoutTest {
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
