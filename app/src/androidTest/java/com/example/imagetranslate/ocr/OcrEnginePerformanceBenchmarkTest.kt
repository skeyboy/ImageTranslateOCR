package com.example.imagetranslate.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class OcrEnginePerformanceBenchmarkTest {
    @Test
    fun benchmarkSameScreen() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val input = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("cat $INPUT_FILE")
            val bitmap = input.use {
                requireNotNull(BitmapFactory.decodeFileDescriptor(it.fileDescriptor))
            }
            val originalEngine = OcrEngineSettings.get(context)

            try {
                val reports = JSONArray()
                OcrEngineSettings.set(context, OcrEngineType.ML_KIT)
                OCRManager(context).use { manager ->
                    manager.ensureModels(OcrRecognitionMode.AUTO.startupModels)
                    reports.put(
                        benchmark("mlkit", bitmap) {
                            val results = manager.recognizeFast(bitmap, OcrRecognitionMode.AUTO)
                            Sample(results.size, results.sumOf { it.text.length })
                        }
                    )
                }

                reports.put(benchmarkPaddle(context, bitmap, "paddle_parallel_1600_t6_b4", true, 1_600, 6, 4))
                reports.put(benchmarkPaddle(context, bitmap, "paddle_sequential_1600_t6_b4", false, 1_600, 6, 4))
                reports.put(benchmarkPaddle(context, bitmap, "paddle_sequential_1600_t4_b4", false, 1_600, 4, 4))
                reports.put(benchmarkPaddle(context, bitmap, "paddle_sequential_1600_t6_b8", false, 1_600, 6, 8))
                reports.put(benchmarkPaddle(context, bitmap, "paddle_sequential_1280_t6_b4", false, 1_280, 6, 4))

                Log.i(
                    TAG,
                    JSONObject()
                        .put("event", "ocr_engine_same_screen_benchmark")
                        .put("width", bitmap.width)
                        .put("height", bitmap.height)
                        .put("warmups", WARMUPS)
                        .put("runs", RUNS)
                        .put("reports", reports)
                        .toString()
                )
            } finally {
                OcrEngineSettings.set(context, originalEngine)
                bitmap.recycle()
            }
        }
    }

    private suspend fun benchmarkPaddle(
        context: android.content.Context,
        bitmap: Bitmap,
        name: String,
        parallel: Boolean,
        longEdge: Int,
        threads: Int,
        batchSize: Int
    ): JSONObject {
        val paddle = PaddleOCR.create(
            context,
            PaddleOCRConfig(
                detLimitSideLen = longEdge,
                detLimitType = "max",
                detMaxSideLimit = longEdge,
                recBatchSize = batchSize
            ),
            EngineConfig(
                numThreads = threads,
                interOpNumThreads = if (parallel) 2 else 1,
                parallelExecution = parallel
            )
        )
        return try {
            benchmark(name, bitmap) {
                val result = paddle.recognize(bitmap)
                Sample(result.results.size, result.results.sumOf { it.text.length })
            }
        } finally {
            paddle.release()
        }
    }

    private suspend fun benchmark(
        name: String,
        bitmap: Bitmap,
        operation: suspend () -> Sample
    ): JSONObject {
        repeat(WARMUPS) { operation() }
        val durations = mutableListOf<Long>()
        val samples = mutableListOf<Sample>()
        repeat(RUNS) {
            val startedAt = SystemClock.elapsedRealtime()
            samples += operation()
            durations += SystemClock.elapsedRealtime() - startedAt
        }
        val sorted = durations.sorted()
        return JSONObject()
            .put("name", name)
            .put("p50_ms", percentile(sorted, 0.50))
            .put("p90_ms", percentile(sorted, 0.90))
            .put("min_ms", sorted.first())
            .put("max_ms", sorted.last())
            .put("regions", samples.map(Sample::regions).sorted()[samples.size / 2])
            .put("characters", samples.map(Sample::characters).sorted()[samples.size / 2])
            .put("durations_ms", JSONArray(durations))
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long =
        sorted[(ceil(sorted.size * percentile).toInt() - 1).coerceIn(sorted.indices)]

    private data class Sample(val regions: Int, val characters: Int)

    private companion object {
        const val TAG = "OCR_ENGINE_BENCH"
        const val INPUT_FILE = "/sdcard/Download/ocr-engine-benchmark.png"
        const val WARMUPS = 2
        const val RUNS = 5
    }
}
