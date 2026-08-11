package com.example.imagetranslate.translate

import org.json.JSONObject

internal object NativeEdgeTranslationBridge {
    init {
        System.loadLibrary("ocr_translation_edge")
    }

    private external fun nativePrepare(request: String, provider: String, model: String): String
    private external fun nativeComplete(prepared: String, completion: String, totalMs: Long): String

    fun prepare(request: String, provider: String, model: String): JSONObject =
        unwrap(nativePrepare(request, provider, model))

    fun complete(prepared: String, completion: String, totalMs: Long): JSONObject =
        unwrap(nativeComplete(prepared, completion, totalMs))

    private fun unwrap(raw: String): JSONObject {
        val envelope = JSONObject(raw)
        check(envelope.optBoolean("ok")) { envelope.optString("error", "Embedded translation core failed") }
        return envelope.getJSONObject("value")
    }
}
