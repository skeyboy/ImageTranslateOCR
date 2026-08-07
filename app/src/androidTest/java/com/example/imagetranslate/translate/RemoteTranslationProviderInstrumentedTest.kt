package com.example.imagetranslate.translate

import androidx.test.ext.junit.runners.AndroidJUnit4
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
class RemoteTranslationProviderInstrumentedTest {
    @Test
    fun batchRequestParsesPartialResponseAndFallsBackOnlyFailedRegion() = runBlocking {
        val server = OneShotHttpServer(
            responseBody = """
                {
                  "requestId": "network-request-1",
                  "results": [
                    {
                      "regionId": "region-0",
                      "status": "TRANSLATED",
                      "translatedText": "Hello",
                      "provider": "mock-server",
                      "detectedSourceLanguage": "zh",
                      "targetLanguage": "en"
                    },
                    {
                      "regionId": "region-1",
                      "status": "FAILED",
                      "error": {
                        "code": "PROVIDER_TIMEOUT",
                        "message": "provider timed out",
                        "retryable": true
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        val remoteProvider = RemoteTranslationProvider(server.baseUrl)
        val localRequests = mutableListOf<String>()
        val fallbackCodes = mutableListOf<String>()
        val localProvider = object : TranslationProvider {
            override val id = "local-test"

            override suspend fun translate(request: TranslationRequest): TranslationResult {
                localRequests += request.regionId
                return TranslationResult(
                    regionId = request.regionId,
                    translatedText = "local:${request.text}",
                    provider = id,
                    targetLanguage = request.targetLanguage
                )
            }
        }
        val switchingProvider = SwitchingTranslationProvider(
            selectedBackend = { TranslationBackend.NETWORK },
            localProvider = localProvider,
            networkProvider = { remoteProvider },
            resultValidator = { request, result ->
                result.regionId == request.regionId && result.translatedText.isNotBlank()
            },
            onNetworkFallback = { failures ->
                fallbackCodes += failures.map(TranslationFailure::code)
            }
        )
        val requests = listOf(
            TranslationRequest(
                requestId = "network-request-1",
                regionId = "region-0",
                text = "\u4f60\u597d",
                mode = TranslationMode.CHINESE_TO_ENGLISH,
                sourceLanguage = "zh",
                targetLanguage = "en"
            ),
            TranslationRequest(
                requestId = "network-request-1",
                regionId = "region-1",
                text = "\u6b22\u8fce",
                mode = TranslationMode.CHINESE_TO_ENGLISH,
                sourceLanguage = "zh",
                targetLanguage = "en"
            )
        )

        try {
            val batch = switchingProvider.translateBatch(requests)
            val captured = server.awaitRequest()

            assertEquals(listOf("region-0", "region-1"), batch.results.map { it.regionId })
            assertEquals(listOf("Hello", "local:\u6b22\u8fce"), batch.results.map { it.translatedText })
            assertTrue(batch.failures.isEmpty())
            assertEquals(listOf("region-1"), localRequests)
            assertEquals(listOf("PROVIDER_TIMEOUT"), fallbackCodes)

            assertEquals("POST /api/v1/translate/regions HTTP/1.1", captured.requestLine)
            assertEquals("application/json; charset=utf-8", captured.headers["content-type"])
            assertEquals("network-request-1", captured.headers["x-request-id"])
            assertEquals("network-request-1", captured.headers["idempotency-key"])
            val requestBody = JSONObject(captured.body)
            assertEquals(1, requestBody.getInt("schemaVersion"))
            assertEquals("network-request-1", requestBody.getString("requestId"))
            assertEquals("CHINESE_TO_ENGLISH", requestBody.getJSONObject("translation").getString("mode"))
            assertEquals(2, requestBody.getJSONArray("regions").length())
            assertEquals("\u4f60\u597d", requestBody.getJSONArray("regions").getJSONObject(0).getString("text"))
            assertEquals("\u6b22\u8fce", requestBody.getJSONArray("regions").getJSONObject(1).getString("text"))
        } finally {
            switchingProvider.close()
            remoteProvider.close()
            server.close()
        }
    }

    private data class CapturedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class OneShotHttpServer(
        private val responseBody: String
    ) : Closeable {
        private val serverSocket = ServerSocket(
            0,
            1,
            InetAddress.getByName("127.0.0.1")
        )
        private val executor = Executors.newSingleThreadExecutor()
        private val requestFuture = executor.submit<CapturedRequest> {
            serverSocket.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val headerText = readHeaders(input)
                val headerLines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
                val headers = headerLines.drop(1).associate { line ->
                    val separator = line.indexOf(':')
                    check(separator > 0) { "Malformed HTTP header: $line" }
                    line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
                }
                val contentLength = headers["content-length"]?.toInt()
                    ?: error("Mock server requires Content-Length")
                val bodyBytes = ByteArray(contentLength)
                var offset = 0
                while (offset < bodyBytes.size) {
                    val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
                    check(count >= 0) { "Unexpected end of HTTP request body" }
                    offset += count
                }
                val captured = CapturedRequest(
                    requestLine = headerLines.first(),
                    headers = headers,
                    body = bodyBytes.toString(Charsets.UTF_8)
                )

                val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
                val responseHeaders = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: application/json; charset=utf-8\r\n")
                    append("Content-Length: ${responseBytes.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                socket.getOutputStream().use { output ->
                    output.write(responseHeaders)
                    output.write(responseBytes)
                    output.flush()
                }
                captured
            }
        }

        val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

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
                check(next >= 0) { "Unexpected end of HTTP headers" }
                bytes.write(next)
                matched = if (next == HEADER_TERMINATOR[matched].toInt()) {
                    matched + 1
                } else if (next == HEADER_TERMINATOR[0].toInt()) {
                    1
                } else {
                    0
                }
                check(bytes.size() <= MAX_HEADER_BYTES) { "HTTP headers are too large" }
            }
            return bytes.toString(Charsets.ISO_8859_1.name())
        }

        private companion object {
            val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            const val MAX_HEADER_BYTES = 16 * 1024
        }
    }
}
