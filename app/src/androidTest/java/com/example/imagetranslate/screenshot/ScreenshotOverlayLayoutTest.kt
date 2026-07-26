package com.example.imagetranslate.screenshot

import android.content.Context
import android.graphics.Bitmap
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

@RunWith(AndroidJUnit4::class)
class ScreenshotOverlayLayoutTest {
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
            assertEquals(View.VISIBLE, view.visibility)

            view.hideForViewportMovement()
            assertEquals(View.INVISIBLE, view.visibility)
            assertFalse(patchBitmap.isRecycled)

            view.clearPatches()
            assertEquals(View.INVISIBLE, view.visibility)
            assertTrue(patchBitmap.isRecycled)
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
