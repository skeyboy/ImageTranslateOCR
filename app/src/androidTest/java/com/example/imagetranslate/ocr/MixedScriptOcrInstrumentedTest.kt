package com.example.imagetranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedScriptOcrInstrumentedTest {
    @Test
    fun autoFastRecognitionKeepsEnglishAndChineseFromTheSameScreen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(1_600, 260, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(
                "Join our AI workshop, 地点在深圳",
                48f,
                150f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 64f
                }
            )
        }
        try {
            OCRManager(context).use { manager ->
                val recognized = manager.recognizeFast(bitmap, OcrRecognitionMode.AUTO)
                val text = recognized.joinToString(" ") { it.text }

                assertTrue("English text was not recognized: $text", "workshop" in text.lowercase())
                assertTrue(
                    "Chinese text was not recognized: $text",
                    text.count { character -> character in '\u4e00'..'\u9fff' } >= 3
                )
                assertTrue(
                    "The selected fused candidate lost OCR block identity: $text",
                    recognized.any { result ->
                        result.sourceBlockId != null &&
                            ("workshop" in result.text.lowercase() ||
                                result.text.any { it in '\u4e00'..'\u9fff' })
                    }
                )
            }
        } finally {
            bitmap.recycle()
        }
    }
}
