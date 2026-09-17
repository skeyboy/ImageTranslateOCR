package com.example.imagetranslate.screenshot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object MachineTranslationMetricsStore {
    private val lock = Any()

    fun record(
        context: Context,
        generation: Int,
        result: BackgroundTranslatedOverlayResult,
        totalMs: Long
    ): File? {
        val metrics = result.machineMetrics ?: return null
        val effectiveTotalMs = result.ocrMs + metrics.groupingMs +
            metrics.maxResponseMs + result.renderingMs
        val payload = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("timestampEpochMs", System.currentTimeMillis())
            .put("generation", generation)
            .put("ocrMs", result.ocrMs)
            .put("groupingMs", metrics.groupingMs)
            .put("maxResponseMs", metrics.maxResponseMs)
            .put("requestWallMs", metrics.requestWallMs)
            .put("renderMs", result.renderingMs)
            .put("totalMs", effectiveTotalMs)
            .put("wallTotalMs", totalMs)
            .put("requestCount", metrics.requestCount)
            .put("successCount", metrics.successCount)
            .put("failureCount", metrics.failureCount)
            .put("patchCount", result.patches.size)
            .put("responses", JSONArray().apply {
                metrics.responses.forEach { response ->
                    put(
                        JSONObject()
                            .put("paragraphId", response.paragraphId)
                            .put("status", if (response.succeeded) "SUCCEEDED" else "FAILED")
                            .put("durationMs", response.durationMs)
                            .put("errorCode", response.errorCode ?: JSONObject.NULL)
                    )
                }
            })
        synchronized(lock) {
            val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
            val file = File(directory, FILE_NAME)
            if (file.length() >= MAX_FILE_BYTES) {
                val previous = File(directory, PREVIOUS_FILE_NAME)
                if (previous.exists()) previous.delete()
                file.renameTo(previous)
            }
            file.appendText(payload.toString() + "\n", Charsets.UTF_8)
            return file
        }
    }

    private const val SCHEMA_VERSION = 2
    private const val DIRECTORY = "metrics"
    private const val FILE_NAME = "machine-translation.jsonl"
    private const val PREVIOUS_FILE_NAME = "machine-translation.previous.jsonl"
    private const val MAX_FILE_BYTES = 1_048_576L
}
