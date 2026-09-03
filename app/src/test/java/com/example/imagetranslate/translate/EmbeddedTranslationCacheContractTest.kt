package com.example.imagetranslate.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedTranslationCacheContractTest {
    @Test
    fun acceptsCompactAndCanonicalTranslationIdentifiers() {
        assertEquals("g0", completionTranslationId(JSONObject().put("id", "g0")))
        assertEquals(
            "server-group",
            completionTranslationId(JSONObject().put("groupId", "server-group"))
        )
        assertNull(completionTranslationId(JSONObject()))
    }
}
