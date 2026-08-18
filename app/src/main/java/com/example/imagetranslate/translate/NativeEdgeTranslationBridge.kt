package com.example.imagetranslate.translate

import android.content.Context
import org.json.JSONObject

internal object NativeEdgeTranslationBridge {
    init {
        System.loadLibrary("ocr_translation_edge")
    }

    private external fun nativePrepare(request: String, provider: String, model: String): String
    private external fun nativeComplete(prepared: String, completion: String, totalMs: Long): String
    private external fun nativeInitialize(context: Context): Boolean
    private external fun nativeTranslateOpenAi(
        request: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): String

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                check(nativeInitialize(context.applicationContext)) {
                    "Android TLS verifier initialization failed"
                }
                initialized = true
            }
        }
    }

    fun prepare(request: String, provider: String, model: String): JSONObject =
        unwrap(nativePrepare(request, provider, model))

    fun complete(prepared: String, completion: String, totalMs: Long): JSONObject =
        unwrap(nativeComplete(prepared, completion, totalMs))

    fun translateOpenAi(
        context: Context,
        request: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ): JSONObject {
        initialize(context)
        return unwrap(nativeTranslateOpenAi(request, baseUrl, apiKey, model))
    }

    private fun unwrap(raw: String): JSONObject {
        val envelope = JSONObject(raw)
        check(envelope.optBoolean("ok")) { envelope.optString("error", "Embedded translation core failed") }
        return envelope.getJSONObject("value")
    }
}
