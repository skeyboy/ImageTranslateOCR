package com.example.imagetranslate.translate

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object TranslationRequestArchiveStore {
    private val lock = Mutex()

    suspend fun recordExchange(
        context: Context,
        requestJson: String,
        responseJson: String? = null,
        provider: String,
        model: String? = null,
        providerRequestJson: String? = null,
        providerResponseJson: String? = null,
        timings: JSONObject? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!TranslationBackendSettings.isRequestArchiveExportEnabled(context)) return@withContext
        lock.withLock {
            val request = JSONObject(requestJson)
            val requestId = request.getString("requestId")
            val stage = stagingDirectory(context, requestId)
            stage.mkdirs()
            writeText(stage, REQUEST_FILE, request.toString(2))
            responseJson?.let { writeJsonText(stage, RESPONSE_FILE, it) }
            providerRequestJson?.let { writeJsonText(stage, PROVIDER_REQUEST_FILE, it) }
            providerResponseJson?.let { writeJsonText(stage, PROVIDER_RESPONSE_FILE, it) }
            timings?.let { writeText(stage, TIMINGS_FILE, it.toString(2)) }
            errorMessage?.let {
                writeText(
                    stage,
                    ERROR_FILE,
                    JSONObject().put("message", it).put("retryable", true).toString(2)
                )
            }
            extractSourceCapture(request, stage)
            val manifest = JSONObject()
                .put("archiveSchemaVersion", ARCHIVE_SCHEMA_VERSION)
                .put("requestId", requestId)
                .put("sessionId", request.optString("sessionId"))
                .put("generation", request.optLong("generation"))
                .put("translationRevision", request.optLong("translationRevision"))
                .put("provider", provider)
                .put("model", model ?: JSONObject.NULL)
                .put("createdAt", utcTimestamp())
                .put("status", if (errorMessage == null) "COMPLETED" else "FAILED")
            writeText(stage, MANIFEST_FILE, manifest.toString(2))
            exportZip(context, stage, requestId)
        }
    }

    suspend fun recordRenderedCapture(
        context: Context,
        trace: SemanticTranslationTrace,
        capture: SemanticDebugCapture,
        audit: SemanticRenderedCaptureAudit
    ) = withContext(Dispatchers.IO) {
        if (!TranslationBackendSettings.isRequestArchiveExportEnabled(context)) return@withContext
        lock.withLock {
            val stage = stagingDirectory(context, trace.requestId)
            if (!File(stage, REQUEST_FILE).isFile) return@withLock
            val extension = imageExtension(capture.mimeType)
            File(stage, "$RENDERED_CAPTURE_BASENAME.$extension").writeBytes(
                Base64.decode(capture.dataBase64, Base64.DEFAULT)
            )
            writeText(
                stage,
                RENDER_AUDIT_FILE,
                JSONObject()
                    .put("outcome", audit.outcome)
                    .put("stage", audit.stage ?: JSONObject.NULL)
                    .put("failureCode", audit.failureCode ?: JSONObject.NULL)
                    .put("failureMessage", audit.failureMessage ?: JSONObject.NULL)
                    .put("layoutDiagnostics", audit.layoutDiagnostics ?: JSONObject.NULL)
                    .put("mimeType", capture.mimeType)
                    .put("pixelWidth", capture.pixelWidth)
                    .put("pixelHeight", capture.pixelHeight)
                    .toString(2)
            )
            val diagnostics = audit.layoutDiagnostics?.let { JSONObject(it.toString()) }
                ?: JSONObject()
            diagnostics.put("renderedCaptureEncodeMs", capture.encodeMs)
            mergeRenderedTimings(stage, diagnostics)
            exportZip(context, stage, trace.requestId)
        }
    }

    suspend fun recordProjectionLifecycle(
        context: Context,
        traces: List<SemanticTranslationTrace>,
        diagnostics: JSONObject
    ) = withContext(Dispatchers.IO) {
        if (!TranslationBackendSettings.isRequestArchiveExportEnabled(context)) return@withContext
        lock.withLock {
            traces.distinct().forEach { trace ->
                val stage = stagingDirectory(context, trace.requestId)
                if (!File(stage, REQUEST_FILE).isFile) return@forEach
                writeText(stage, PROJECTION_LIFECYCLE_FILE, diagnostics.toString(2))
                exportZip(context, stage, trace.requestId)
            }
        }
    }

    private fun extractSourceCapture(request: JSONObject, stage: File) {
        val capture = request.optJSONObject("debugCapture") ?: return
        val data = capture.optString("dataBase64")
        if (data.isBlank()) return
        val extension = imageExtension(capture.optString("mimeType", "image/jpeg"))
        File(stage, "$SOURCE_CAPTURE_BASENAME.$extension").writeBytes(
            Base64.decode(data, Base64.DEFAULT)
        )
    }

    private fun exportZip(context: Context, stage: File, requestId: String) {
        val archiveNameFile = File(stage, ARCHIVE_NAME_FILE)
        val archiveName = if (archiveNameFile.isFile) {
            archiveNameFile.readText().trim()
        } else {
            "${fileTimestamp()}_${safeRequestId(requestId)}.zip".also(archiveNameFile::writeText)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(context, stage, archiveName)
        } else {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                PUBLIC_DIRECTORY
            )
            directory.mkdirs()
            FileOutputStream(File(directory, archiveName), false).use { output ->
                writeZip(stage, output)
            }
        }
    }

    private fun exportWithMediaStore(context: Context, stage: File, archiveName: String) {
        val resolver = context.contentResolver
        val uriFile = File(stage, ARCHIVE_URI_FILE)
        val existing = uriFile.takeIf(File::isFile)?.readText()?.trim()
            ?.takeIf(String::isNotBlank)?.let(Uri::parse)
        val uri = existing ?: resolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, archiveName)
                put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME_TYPE)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/" + PUBLIC_DIRECTORY
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        ) ?: error("Unable to create request archive in Documents")
        resolver.openOutputStream(uri, "wt")?.use { output ->
            writeZip(stage, output)
        } ?: error("Unable to open request archive in Documents")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        if (existing == null) uriFile.writeText(uri.toString())
    }

    private fun writeZip(stage: File, output: java.io.OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            stage.listFiles().orEmpty()
                .filter { it.isFile && it.name !in INTERNAL_FILES }
                .sortedBy(File::getName)
                .forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name).apply { time = 0L })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    private fun stagingDirectory(context: Context, requestId: String) = File(
        context.cacheDir,
        "translation-request-archives/${safeRequestId(requestId)}"
    )

    private fun writeJsonText(stage: File, name: String, raw: String) {
        val pretty = runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }
        writeText(stage, name, pretty)
    }

    private fun mergeRenderedTimings(stage: File, diagnostics: JSONObject) {
        val timingFile = File(stage, TIMINGS_FILE)
        val timings = runCatching {
            if (timingFile.isFile) JSONObject(timingFile.readText()) else JSONObject()
        }.getOrElse { JSONObject() }
        listOf(
            "ocrMs", "translationMs", "ocrTranslateMs", "renderMs", "presentationMs",
            "captureToPresentationMs", "endToEndMs", "renderedCaptureEncodeMs"
        ).forEach { key ->
            if (diagnostics.has(key)) timings.put(key, diagnostics.opt(key))
        }
        timings.put("schemaVersion", ARCHIVE_SCHEMA_VERSION)
        writeText(stage, TIMINGS_FILE, timings.toString(2))
    }

    private fun writeText(stage: File, name: String, text: String) {
        File(stage, name).writeText(text, Charsets.UTF_8)
    }

    private fun safeRequestId(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .ifBlank { "request" }

    private fun imageExtension(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun utcTimestamp(): String = timestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private fun fileTimestamp(): String = timestampFormat("yyyyMMdd'T'HHmmss'Z'")

    private fun timestampFormat(pattern: String): String = SimpleDateFormat(pattern, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    private const val ARCHIVE_SCHEMA_VERSION = 2
    private const val PUBLIC_DIRECTORY = "ImageTranslateOCR"
    private const val ZIP_MIME_TYPE = "application/zip"
    private const val REQUEST_FILE = "request.json"
    private const val RESPONSE_FILE = "response.json"
    private const val PROVIDER_REQUEST_FILE = "provider-request.json"
    private const val PROVIDER_RESPONSE_FILE = "provider-response.json"
    private const val ERROR_FILE = "error.json"
    private const val MANIFEST_FILE = "manifest.json"
    private const val RENDER_AUDIT_FILE = "render-audit.json"
    private const val PROJECTION_LIFECYCLE_FILE = "projection-lifecycle.json"
    private const val TIMINGS_FILE = "timings.json"
    private const val SOURCE_CAPTURE_BASENAME = "source-capture"
    private const val RENDERED_CAPTURE_BASENAME = "rendered-capture"
    private const val ARCHIVE_NAME_FILE = ".archive-name"
    private const val ARCHIVE_URI_FILE = ".archive-uri"
    private val INTERNAL_FILES = setOf(ARCHIVE_NAME_FILE, ARCHIVE_URI_FILE)
}
