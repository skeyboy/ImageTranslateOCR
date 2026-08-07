package com.example.imagetranslate.ocr

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.imagetranslate.semantic.SemanticTextGrouper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CurrentPageOcrCaptureInstrumentedTest {
    @Test
    fun capturesLineAndSemanticGroupEvidence() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            val inputPath = arguments.getString("input_file") ?: DEFAULT_INPUT_FILE
            val outputName = arguments.getString("output_name") ?: DEFAULT_OUTPUT_NAME
            val recognitionMode = arguments.getString("recognition_mode")
                ?.let(OcrRecognitionMode::valueOf)
                ?: OcrRecognitionMode.ENGLISH
            val requestedEngine = arguments.getString("ocr_engine")
                ?.let(OcrEngineType::valueOf)
                ?: OcrEngineType.ML_KIT
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val bitmap = instrumentation.uiAutomation.executeShellCommand("cat $inputPath")
                .use { input ->
                    requireNotNull(BitmapFactory.decodeFileDescriptor(input.fileDescriptor)) {
                        "Unable to decode OCR input: $inputPath"
                    }
                }
            val originalEngine = OcrEngineSettings.get(context)

            try {
                OcrEngineSettings.set(context, requestedEngine)
                val recognized: List<RecognizedText>
                val elapsedMs: Long
                OCRManager(context).use { manager ->
                    manager.ensureModels(recognitionMode.startupModels)
                    val startedAt = SystemClock.elapsedRealtime()
                    recognized = manager.recognizeFast(bitmap, recognitionMode)
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt
                }
                val groups = SemanticTextGrouper.group(recognized, bitmap.width, bitmap.height)
                val report = JSONObject()
                    .put("schemaVersion", 1)
                    .put("inputPath", inputPath)
                    .put("width", bitmap.width)
                    .put("height", bitmap.height)
                    .put("engine", requestedEngine.name)
                    .put("recognitionMode", recognitionMode.name)
                    .put("elapsedMs", elapsedMs)
                    .put("lineCount", recognized.size)
                    .put("groupCount", groups.size)
                    .put(
                        "lines",
                        JSONArray(recognized.mapIndexed { index, line ->
                            JSONObject()
                                .put("lineId", "line-${index + 1}")
                                .put("text", line.text)
                                .put("bounds", line.bounds.toJson())
                                .put("modelConfidence", line.modelConfidence.toDouble())
                                .put("consensusScore", line.consensusScore.toDouble())
                                .put("passCount", line.passCount)
                                .put("recognizerScript", line.recognizerScript.name)
                                .put("sourceBlockId", line.sourceBlockId ?: JSONObject.NULL)
                                .put("sourceLineIndex", line.sourceLineIndex ?: JSONObject.NULL)
                        })
                    )
                    .put(
                        "groups",
                        JSONArray(groups.map { group ->
                            JSONObject()
                                .put("groupId", group.groupId)
                                .put("sourceText", group.sourceText)
                                .put("bounds", group.unionBounds.toJson())
                                .put("role", group.role.name)
                                .put("groupingConfidence", group.groupingConfidence.toDouble())
                                .put("evidence", JSONArray(group.evidence.map { it.name }.sorted()))
                                .put(
                                    "memberTexts",
                                    JSONArray(group.members.map(RecognizedText::text))
                                )
                        })
                    )
                val output = File(context.filesDir, "validation/$outputName")
                output.parentFile?.mkdirs()
                output.writeText(report.toString(2))
                Log.i(
                    LOG_TAG,
                    JSONObject()
                        .put("event", "current_page_ocr_captured")
                        .put("output", output.absolutePath)
                        .put("lineCount", recognized.size)
                        .put("groupCount", groups.size)
                        .put("elapsedMs", elapsedMs)
                        .toString()
                )
            } finally {
                OcrEngineSettings.set(context, originalEngine)
                bitmap.recycle()
            }
        }
    }

    private fun android.graphics.Rect.toJson() = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)

    private companion object {
        const val LOG_TAG = "CURRENT_PAGE_OCR"
        const val DEFAULT_INPUT_FILE = "/sdcard/Download/imagetranslate-current-page.png"
        const val DEFAULT_OUTPUT_NAME = "current-page-ocr.json"
    }
}
