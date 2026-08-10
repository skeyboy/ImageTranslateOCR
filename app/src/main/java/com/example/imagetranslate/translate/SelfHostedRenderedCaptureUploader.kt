package com.example.imagetranslate.translate

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class SelfHostedRenderedCaptureUploader(
    baseUrl: String,
    private val bearerToken: String?
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')

    fun upload(
        trace: SemanticTranslationTrace,
        capture: SemanticDebugCapture,
        audit: SemanticRenderedCaptureAudit = SemanticRenderedCaptureAudit.presented()
    ) {
        val requestId = URLEncoder.encode(trace.requestId, Charsets.UTF_8.name())
        val endpoint = URL(
            "$normalizedBaseUrl/api/v${trace.schemaVersion}/translate/requests/" +
                "$requestId/rendered-capture"
        )
        val body = JSONObject()
            .put("sessionId", trace.sessionId)
            .put("generation", trace.generation)
            .put("translationRevision", trace.translationRevision)
            .put("outcome", audit.outcome)
            .put("stage", audit.stage ?: JSONObject.NULL)
            .put("failureCode", audit.failureCode ?: JSONObject.NULL)
            .put("failureMessage", audit.failureMessage ?: JSONObject.NULL)
            .put("layoutDiagnostics", audit.layoutDiagnostics ?: JSONObject.NULL)
            .put(
                "capture",
                JSONObject()
                    .put("mimeType", capture.mimeType)
                    .put("dataBase64", capture.dataBase64)
                    .put("pixelWidth", capture.pixelWidth)
                    .put("pixelHeight", capture.pixelHeight)
            )
            .toString()
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Request-Id", trace.requestId)
            bearerToken?.takeIf(String::isNotBlank)?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val response = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                throw IllegalStateException(
                    "Rendered capture upload failed with HTTP $status: $response"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

internal data class SemanticRenderedCaptureAudit(
    val outcome: String,
    val stage: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val layoutDiagnostics: JSONObject? = null
) {
    companion object {
        fun presented(layoutDiagnostics: JSONObject? = null) = SemanticRenderedCaptureAudit(
            outcome = "PRESENTED",
            layoutDiagnostics = layoutDiagnostics
        )

        fun renderFailed(
            stage: String,
            failureCode: String,
            failureMessage: String,
            layoutDiagnostics: JSONObject? = null
        ) = SemanticRenderedCaptureAudit(
            outcome = "RENDER_FAILED",
            stage = stage,
            failureCode = failureCode,
            failureMessage = failureMessage,
            layoutDiagnostics = layoutDiagnostics
        )
    }
}

internal object SemanticRenderedCaptureUploadPolicy {
    fun shouldUpload(
        isDebugBuild: Boolean,
        backend: TranslationBackend,
        uploadEnabled: Boolean,
        patchCount: Int,
        failedCount: Int,
        traceCount: Int
    ): Boolean = isDebugBuild &&
        backend.usesRemoteSemanticService &&
        uploadEnabled &&
        (patchCount > 0 || failedCount > 0) &&
        traceCount > 0
}
