package com.example.imagetranslate.screenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.imagetranslate.ocr.PaddleNetworkConfiguration
import com.example.imagetranslate.translate.TranslationMode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PaddleNetworkOcrTranslationEngineInstrumentedTest {
    @Test
    fun sendsCompressedFrameAndParsesCorrelatedRegions() = runBlocking {
        val server = CorrelatedResponseServer()
        val engine = PaddleNetworkOcrTranslationEngine(
            PaddleNetworkConfiguration(server.baseUrl, "local-test-token")
        )
        val bitmap = Bitmap.createBitmap(3200, 1800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        try {
            val result = engine.recognizeAndTranslate(
                LiveOcrTranslationRequest(
                    bitmap = bitmap,
                    mode = TranslationMode.CHINESE_TO_ENGLISH,
                    sessionId = "live-session-1",
                    generation = 17
                )
            )
            val captured = server.awaitRequest()
            val body = JSONObject(captured.body)
            val image = body.getJSONObject("image")
            val decodedBitmap = BitmapFactory.decodeByteArray(
                Base64.decode(image.getString("data"), Base64.DEFAULT),
                0,
                Base64.decode(image.getString("data"), Base64.DEFAULT).size
            )

            assertEquals("POST /api/v1/ocr-translations HTTP/1.1", captured.requestLine)
            assertEquals("Bearer local-test-token", captured.headers["authorization"])
            assertEquals("live-session-1", body.getString("sessionId"))
            assertEquals(17, body.getInt("generation"))
            assertEquals("image/webp", image.getString("mediaType"))
            assertEquals(1600, decodedBitmap.width)
            assertEquals(900, decodedBitmap.height)
            assertEquals("CHINESE_TO_ENGLISH", body.getJSONObject("translation").getString("mode"))
            assertEquals(2, result.recognizedCount)
            assertEquals(1, result.regions.size)
            assertEquals("Hello", result.regions.single().translation)
            assertEquals(24, result.regions.single().source.bounds.left)
            assertEquals(40, result.regions.single().source.bounds.top)
            assertEquals(1, result.failedCount)
            assertEquals(410, result.ocrMs)
            assertEquals(125, result.translationMs)
            assertTrue(result.totalMs >= result.ocrMs + result.translationMs)
            decodedBitmap.recycle()
        } finally {
            bitmap.recycle()
            engine.close()
            server.close()
        }
    }

    private data class CapturedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class CorrelatedResponseServer : Closeable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val requestFuture = executor.submit<CapturedRequest> {
            serverSocket.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headerText = readHeaders(input)
                val lines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
                val headers = lines.drop(1).associate { line ->
                    val separator = line.indexOf(':')
                    check(separator > 0)
                    line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                }
                val bodyBytes = ByteArray(checkNotNull(headers["content-length"]).toInt())
                var offset = 0
                while (offset < bodyBytes.size) {
                    val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
                    check(count >= 0)
                    offset += count
                }
                val captured = CapturedRequest(
                    requestLine = lines.first(),
                    headers = headers,
                    body = bodyBytes.toString(Charsets.UTF_8)
                )
                val request = JSONObject(captured.body)
                val response = JSONObject()
                    .put("schemaVersion", 1)
                    .put("requestId", request.getString("requestId"))
                    .put("sessionId", request.getString("sessionId"))
                    .put("generation", request.getInt("generation"))
                    .put("sourceWidth", 1600)
                    .put("sourceHeight", 900)
                    .put("status", "PARTIAL")
                    .put("recognizedCount", 2)
                    .put(
                        "regions",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("regionId", "ocr-0")
                                    .put("status", "TRANSLATED")
                                    .put("sourceText", "\u4f60\u597d")
                                    .put("translatedText", "Hello")
                                    .put(
                                        "bounds",
                                        JSONObject()
                                            .put("left", 12)
                                            .put("top", 20)
                                            .put("right", 112)
                                            .put("bottom", 64)
                                    )
                                    .put("confidence", 0.96)
                                    .put("provider", "paddleocr+hy-mt2")
                                    .put("detectedSourceLanguage", "zh-Hans")
                            )
                            .put(
                                JSONObject()
                                    .put("regionId", "ocr-1")
                                    .put("status", "FAILED")
                            )
                    )
                    .put(
                        "timing",
                        JSONObject()
                            .put("ocrMs", 410)
                            .put("translationMs", 125)
                            .put("totalMs", 540)
                    )
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                val responseHeaders = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${response.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().use { output ->
                    output.write(responseHeaders)
                    output.write(response)
                    output.flush()
                }
                captured
            }
        }

        val baseUrl = "http://127.0.0.1:${serverSocket.localPort}"

        fun awaitRequest(): CapturedRequest = requestFuture.get(5, TimeUnit.SECONDS)

        override fun close() {
            serverSocket.close()
            executor.shutdownNow()
        }

        private fun readHeaders(input: java.io.InputStream): String {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (matched < HEADER_TERMINATOR.size) {
                val next = input.read()
                check(next >= 0)
                bytes.write(next)
                matched = if (next == HEADER_TERMINATOR[matched].toInt()) {
                    matched + 1
                } else if (next == HEADER_TERMINATOR[0].toInt()) {
                    1
                } else {
                    0
                }
                check(bytes.size() <= 16 * 1024)
            }
            return bytes.toString(Charsets.ISO_8859_1.name())
        }

        private companion object {
            val HEADER_TERMINATOR = byteArrayOf(
                '\r'.code.toByte(),
                '\n'.code.toByte(),
                '\r'.code.toByte(),
                '\n'.code.toByte()
            )
        }
    }
}
