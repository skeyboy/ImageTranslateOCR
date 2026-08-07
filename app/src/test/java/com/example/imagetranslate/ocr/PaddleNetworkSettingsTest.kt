package com.example.imagetranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PaddleNetworkSettingsTest {
    @Test
    fun normalizesBaseAndCombinedEndpoint() {
        assertEquals(
            "http://192.168.0.4:8090",
            normalizePaddleNetworkBaseUrl(" http://192.168.0.4:8090/ ")
        )
        assertEquals(
            "https://ocr.example.com/gateway",
            normalizePaddleNetworkBaseUrl(
                "https://ocr.example.com/gateway/api/v1/ocr-translations"
            )
        )
        assertEquals("", normalizePaddleNetworkBaseUrl("  "))
    }

    @Test
    fun rejectsCredentialsQueryAndUnsupportedScheme() {
        listOf(
            "ftp://ocr.example.com",
            "https://user:pass@ocr.example.com",
            "https://ocr.example.com?token=secret",
            "not-a-url"
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizePaddleNetworkBaseUrl(value)
            }
        }
    }
}
