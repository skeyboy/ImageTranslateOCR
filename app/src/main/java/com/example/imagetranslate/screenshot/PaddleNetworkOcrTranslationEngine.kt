package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import com.example.imagetranslate.BuildConfig
import com.example.imagetranslate.ocr.PADDLE_OCR_TRANSLATION_PATH
import com.example.imagetranslate.ocr.PaddleNetworkConfiguration
import com.example.imagetranslate.ocr.RecognizedText
import com.example.imagetranslate.ocr.RecognizerScript
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PaddleNetworkOcrTranslationEngine(
    private val configuration: PaddleNetworkConfiguration
) : LiveOcrTranslationEngine {
    override val id: String = "paddle-network"
    private val endpoint = URL(configuration.baseUrl.trimEnd('/') + PADDLE_OCR_TRANSLATION_PATH)
        .also { url ->
            require(
                url.protocol == "https" ||
                    BuildConfig.DEBUG && isPrivateDevelopmentHost(url.host)
            ) { "PaddleOCR network service requires HTTPS outside local development" }
        }
    private val executor = Executors.newSingleThreadExecutor()
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val closed = AtomicBoolean(false)

    override suspend fun recognizeAndTranslate(
        request: LiveOcrTranslationRequest
    ): LiveOcrTranslationResult {
        check(!closed.get()) { "PaddleOCR network engine is closed" }
        val encoded = encodeBitmap(request.bitmap)
        val requestId = UUID.randomUUID().toString()
        val body = JSONObject()
            .put("schemaVersion", 1)
            .put("requestId", requestId)
            .put("sessionId", request.sessionId)
            .put("generation", request.generation)
            .put(
                "image",
                JSONObject()
                    .put("data", encoded.base64)
                    .put("mediaType", encoded.mediaType)
                    .put("width", encoded.width)
                    .put("height", encoded.height)
            )
            .put(
                "translation",
                JSONObject()
                    .put("mode", request.mode.name)
                    .put("preserveIdentifiers", true)
            )
            .put(
                "ocr",
                JSONObject()
                    .put("textDetLimitSideLen", DETECTION_LONG_EDGE_PX)
                    .put("textRecScoreThresh", MINIMUM_RECOGNITION_SCORE)
            )
            .toString()
        val response = executeRequest(body, requestId)
        val result = parseResponse(
            response = JSONObject(response),
            expectedRequestId = requestId,
            expectedSessionId = request.sessionId,
            expectedGeneration = request.generation,
            expectedWidth = encoded.width,
            expectedHeight = encoded.height
        )
        return result.mapToSourceSize(request.bitmap.width, request.bitmap.height)
    }

    private suspend fun executeRequest(body: String, requestId: String): String =
        suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>()
            val futureRef = AtomicReference<Future<*>?>()
            val future = executor.submit {
                if (!continuation.isActive) return@submit
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Request-Id", requestId)
                    setRequestProperty("Idempotency-Key", requestId)
                    configuration.bearerToken.takeIf(String::isNotBlank)?.let { token ->
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                connectionRef.set(connection)
                activeConnections += connection
                if (!continuation.isActive) {
                    activeConnections -= connection
                    connection.disconnect()
                    return@submit
                }
                try {
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        writer.write(body)
                    }
                    val status = connection.responseCode
                    val responseText = (
                        if (status in 200..299) connection.inputStream else connection.errorStream
                        )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        throw IllegalStateException("PaddleOCR service failed with HTTP $status")
                    }
                    if (continuation.isActive) continuation.resume(responseText)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    activeConnections -= connection
                    connectionRef.compareAndSet(connection, null)
                    connection.disconnect()
                }
            }
            futureRef.set(future)
            continuation.invokeOnCancellation {
                connectionRef.getAndSet(null)?.disconnect()
                futureRef.getAndSet(null)?.cancel(true)
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeConnections.forEach(HttpURLConnection::disconnect)
        activeConnections.clear()
        executor.shutdownNow()
    }

    private data class EncodedImage(
        val base64: String,
        val mediaType: String,
        val width: Int,
        val height: Int
    )

    private data class UploadEncoding(
        val format: Bitmap.CompressFormat,
        val quality: Int,
        val mediaType: String,
        val maximumLongEdge: Int
    )

    private companion object {
        const val DETECTION_LONG_EDGE_PX = 1_280
        const val MINIMUM_RECOGNITION_SCORE = 0.35
        const val MAXIMUM_BASE64_BYTES = 7 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 34_000

        fun encodeBitmap(bitmap: Bitmap): EncodedImage {
            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val attempts = listOf(
                UploadEncoding(webpFormat, 100, "image/webp", 1_600),
                UploadEncoding(webpFormat, 100, "image/webp", 1_280),
                UploadEncoding(Bitmap.CompressFormat.JPEG, 92, "image/jpeg", 1_280)
            )
            attempts.forEach { encoding ->
                val uploadSize = paddleNetworkUploadSize(
                    bitmap.width,
                    bitmap.height,
                    encoding.maximumLongEdge
                )
                val uploadBitmap = if (
                    uploadSize.width != bitmap.width || uploadSize.height != bitmap.height
                ) {
                    Bitmap.createScaledBitmap(bitmap, uploadSize.width, uploadSize.height, true)
                } else {
                    bitmap
                }
                val output = ByteArrayOutputStream()
                try {
                    check(uploadBitmap.compress(encoding.format, encoding.quality, output)) {
                        "Unable to encode captured frame"
                    }
                } finally {
                    if (uploadBitmap !== bitmap && !uploadBitmap.isRecycled) {
                        uploadBitmap.recycle()
                    }
                }
                val bytes = output.toByteArray()
                if (base64Size(bytes.size) <= MAXIMUM_BASE64_BYTES) {
                    return EncodedImage(
                        base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                        mediaType = encoding.mediaType,
                        width = uploadSize.width,
                        height = uploadSize.height
                    )
                }
            }
            error("Captured frame exceeds PaddleOCR service upload limit")
        }

        fun base64Size(byteCount: Int): Long = ((byteCount.toLong() + 2L) / 3L) * 4L

        fun parseResponse(
            response: JSONObject,
            expectedRequestId: String,
            expectedSessionId: String,
            expectedGeneration: Int,
            expectedWidth: Int,
            expectedHeight: Int
        ): LiveOcrTranslationResult {
            check(response.getInt("schemaVersion") == 1) {
                "Unsupported PaddleOCR response schema"
            }
            check(response.getString("requestId") == expectedRequestId) {
                "PaddleOCR response requestId does not match"
            }
            check(response.getString("sessionId") == expectedSessionId) {
                "PaddleOCR response sessionId does not match"
            }
            check(response.getInt("generation") == expectedGeneration) {
                "PaddleOCR response generation does not match"
            }
            val sourceWidth = response.getInt("sourceWidth")
            val sourceHeight = response.getInt("sourceHeight")
            check(sourceWidth == expectedWidth && sourceHeight == expectedHeight) {
                "PaddleOCR response dimensions do not match"
            }
            val regions = mutableListOf<LiveOcrTranslatedRegion>()
            var invalidCount = 0
            var failedCount = 0
            val items = response.getJSONArray("regions")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                when (item.getString("status")) {
                    "FAILED" -> {
                        failedCount++
                        continue
                    }
                    "PRESERVED" -> continue
                    "TRANSLATED" -> Unit
                    else -> {
                        invalidCount++
                        continue
                    }
                }
                val sourceText = item.getString("sourceText").trim()
                val translatedText = item.optString("translatedText").trim()
                val boundsJson = item.getJSONObject("bounds")
                val bounds = Rect(
                    boundsJson.getInt("left"),
                    boundsJson.getInt("top"),
                    boundsJson.getInt("right"),
                    boundsJson.getInt("bottom")
                )
                if (sourceText.isEmpty() || translatedText.isEmpty() ||
                    sourceText == translatedText ||
                    !validLiveOcrTranslationBounds(bounds, sourceWidth, sourceHeight)
                ) {
                    invalidCount++
                    continue
                }
                val detectedLanguage = item.optString("detectedSourceLanguage")
                regions += LiveOcrTranslatedRegion(
                    source = RecognizedText(
                        text = sourceText,
                        bounds = bounds,
                        consensusScore = item.optDouble("confidence", 0.5).toFloat()
                            .coerceIn(0f, 1f),
                        modelConfidence = item.optDouble("confidence", 0.5).toFloat()
                            .coerceIn(0f, 1f),
                        recognizerScript = when (detectedLanguage) {
                            "zh", "zh-Hans", "zh-Hant" -> RecognizerScript.CHINESE
                            "en" -> RecognizerScript.LATIN
                            else -> RecognizerScript.FUSED
                        }
                    ),
                    translation = translatedText,
                    provider = item.optString("provider", "paddle-network")
                )
            }
            val timing = response.getJSONObject("timing")
            return LiveOcrTranslationResult(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                recognizedCount = response.getInt("recognizedCount"),
                regions = regions,
                failedCount = failedCount + invalidCount,
                ocrMs = timing.optLong("ocrMs"),
                translationMs = timing.optLong("translationMs"),
                totalMs = timing.optLong("totalMs")
            )
        }
    }
}

internal fun paddleNetworkUploadSize(
    width: Int,
    height: Int,
    maximumLongEdge: Int = 1_600
): LiveOcrInputSize {
    if (width <= 0 || height <= 0) return LiveOcrInputSize(width, height)
    val longEdge = maxOf(width, height)
    if (longEdge <= maximumLongEdge) return LiveOcrInputSize(width, height)
    val scale = maximumLongEdge.toFloat() / longEdge
    return LiveOcrInputSize(
        width = (width * scale).toInt().coerceAtLeast(1),
        height = (height * scale).toInt().coerceAtLeast(1)
    )
}

private fun LiveOcrTranslationResult.mapToSourceSize(
    sourceWidth: Int,
    sourceHeight: Int
): LiveOcrTranslationResult {
    if (this.sourceWidth == sourceWidth && this.sourceHeight == sourceHeight) return this
    val uploadSize = LiveOcrInputSize(this.sourceWidth, this.sourceHeight)
    val sourceSize = LiveOcrInputSize(sourceWidth, sourceHeight)
    return copy(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        regions = regions.map { region ->
            val bounds = region.source.bounds
            region.copy(
                source = region.source.copy(
                    bounds = Rect(
                        LiveOcrScalePolicy.mapX(bounds.left, uploadSize, sourceSize),
                        LiveOcrScalePolicy.mapY(bounds.top, uploadSize, sourceSize),
                        LiveOcrScalePolicy.mapX(bounds.right, uploadSize, sourceSize),
                        LiveOcrScalePolicy.mapY(bounds.bottom, uploadSize, sourceSize)
                    )
                )
            )
        }
    )
}

private fun isPrivateDevelopmentHost(host: String): Boolean {
    val normalized = host.lowercase()
    if (normalized == "localhost" || normalized == "::1") return true
    val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
        octets[0] == 10 ||
        octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 172 && octets[1] in 16..31
}
