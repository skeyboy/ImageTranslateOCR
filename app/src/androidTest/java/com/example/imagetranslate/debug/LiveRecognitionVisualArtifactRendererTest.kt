package com.example.imagetranslate.debug

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.screenshot.ScreenTranslationPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRecognitionVisualArtifactRendererTest {
    @Test
    fun renderedPreviewChangesOnlyThePatchArea() {
        val source = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val patchBitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val patch = ScreenTranslationPatch(Rect(20, 30, 60, 60), patchBitmap)

        val evidence = LiveRecognitionVisualArtifactRenderer.render(
            source,
            listOf(patch),
            compositeAlpha = 1f
        )

        assertTrue(evidence.passesVisualGate)
        assertEquals(1, evidence.changedPatchCount)
        assertEquals(0, evidence.changedOutsidePatchCount)
        assertEquals(Color.BLACK, evidence.bitmap.getPixel(30, 40))
        assertEquals(Color.WHITE, evidence.bitmap.getPixel(90, 90))

        evidence.bitmap.recycle()
        patchBitmap.recycle()
        source.recycle()
    }
}
