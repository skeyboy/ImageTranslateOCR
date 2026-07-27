package com.example.smartassist.engine

import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistResult
import java.security.MessageDigest

internal class AssistResultCache(maximumEntries: Int) {
    private val maximumEntries = maximumEntries.coerceAtLeast(1)
    private val entries = object : LinkedHashMap<String, AssistResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AssistResult>?): Boolean =
            size > this@AssistResultCache.maximumEntries
    }

    @Synchronized
    fun get(key: String): AssistResult? = entries[key]

    @Synchronized
    fun put(key: String, result: AssistResult) {
        entries[key] = result
    }

    @Synchronized
    fun clear() = entries.clear()

    fun key(request: AssistRequest, providerId: String, providerVersion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        update(providerId)
        update(providerVersion)
        update(request.viewportSignature)
        update(request.viewportWidth.toString())
        update(request.viewportHeight.toString())
        update(request.options.toString())
        request.tracks.forEach { track ->
            update(track.trackId.toString())
            update(track.text)
            update(track.bounds.toString())
            update(track.script.name)
            update(track.consensusScore.toString())
            update(track.translatedText.orEmpty())
            track.alternatives.forEach { alternative -> update(alternative.toString()) }
        }
        request.evidence.forEach { evidence ->
            update(evidence.evidenceId)
            update(evidence.mimeType)
            update(evidence.width.toString())
            update(evidence.height.toString())
            digest.update(evidence.copyEncodedBytes())
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
