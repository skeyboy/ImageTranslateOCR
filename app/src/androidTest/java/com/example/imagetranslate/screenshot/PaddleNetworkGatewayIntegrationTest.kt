package com.example.imagetranslate.screenshot

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.ocr.PaddleNetworkConfiguration
import com.example.imagetranslate.ocr.PaddleNetworkSettings
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PaddleNetworkGatewayIntegrationTest {
    @Test
    fun currentDeviceReachesLanGatewayAndCompletesOcrTranslation() {
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val baseUrl = requireNotNull(arguments.getString(ARG_GATEWAY_BASE_URL)) {
                "Missing instrumentation argument $ARG_GATEWAY_BASE_URL"
            }
            val bearerToken = arguments.getString(ARG_GATEWAY_BEARER_TOKEN).orEmpty()
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            PaddleNetworkSettings.set(targetContext, baseUrl, bearerToken)
            LiveOcrTranslationEngineSettings.set(
                targetContext,
                LiveOcrTranslationEngineType.PADDLE_NETWORK
            )
            val storedConfiguration = PaddleNetworkSettings.get(targetContext)
            assertEquals(baseUrl, storedConfiguration.baseUrl)
            assertEquals(
                LiveOcrTranslationEngineType.PADDLE_NETWORK,
                LiveOcrTranslationEngineSettings.get(targetContext)
            )
            val displayMetrics = Resources.getSystem().displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = (width * 0.075f).coerceAtLeast(64f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("Hello Paddle OCR", width * 0.08f, height * 0.34f, paint)
                drawText("Image translation test", width * 0.08f, height * 0.46f, paint)
            }
            val engine = PaddleNetworkOcrTranslationEngine(
                PaddleNetworkConfiguration(baseUrl, bearerToken)
            )
            try {
                val clientStartedAt = SystemClock.elapsedRealtime()
                val result = withTimeout(60_000L) {
                    engine.recognizeAndTranslate(
                        LiveOcrTranslationRequest(
                            bitmap = bitmap,
                            mode = TranslationMode.ENGLISH_TO_CHINESE,
                            sessionId = "device-lan-integration",
                            generation = 1
                        )
                    )
                }
                val clientTotalMs = SystemClock.elapsedRealtime() - clientStartedAt

                assertEquals(width, result.sourceWidth)
                assertEquals(height, result.sourceHeight)
                assertTrue(
                    "PaddleOCR did not recognize the generated screen",
                    result.recognizedCount > 0
                )
                assertTrue("No translated regions were returned", result.regions.isNotEmpty())
                assertTrue(result.regions.all { region ->
                    validLiveOcrTranslationBounds(region.source.bounds, width, height) &&
                        region.translation.isNotBlank() &&
                        region.translation != region.source.text
                })

                Log.i(
                    LOG_TAG,
                    JSONObject()
                        .put("gateway", baseUrl)
                        .put("deviceWidth", width)
                        .put("deviceHeight", height)
                        .put("uploadWidth", paddleNetworkUploadSize(width, height).width)
                        .put("uploadHeight", paddleNetworkUploadSize(width, height).height)
                        .put("recognizedCount", result.recognizedCount)
                        .put("translatedRegionCount", result.regions.size)
                        .put("failedCount", result.failedCount)
                        .put("ocrMs", result.ocrMs)
                        .put("translationMs", result.translationMs)
                        .put("totalMs", result.totalMs)
                        .put("clientTotalMs", clientTotalMs)
                        .put("clientOverheadMs", (clientTotalMs - result.totalMs).coerceAtLeast(0L))
                        .put(
                            "minimumConfidencePercent",
                            ((result.regions.minOfOrNull { it.source.modelConfidence } ?: 0f) * 100)
                                .roundToInt()
                        )
                        .toString()
                )
            } finally {
                engine.close()
                bitmap.recycle()
            }
        }
    }

    private companion object {
        const val ARG_GATEWAY_BASE_URL = "paddleGatewayBaseUrl"
        const val ARG_GATEWAY_BEARER_TOKEN = "paddleGatewayBearerToken"
        const val LOG_TAG = "PaddleGatewayE2E"
    }
}
