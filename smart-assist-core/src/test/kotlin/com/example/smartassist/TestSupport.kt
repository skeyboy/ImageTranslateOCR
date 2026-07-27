package com.example.smartassist

import com.example.smartassist.api.AssistOptions
import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistTextTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

internal fun request(
    requestId: String = "request-1",
    generation: Long = 1,
    tracks: List<AssistTextTrack> = listOf(track()),
    options: AssistOptions = AssistOptions(enabled = true, forceAnalysis = true)
): AssistRequest = AssistRequest(
    requestId = requestId,
    generation = generation,
    viewportSignature = "viewport-stable",
    viewportWidth = 1080,
    viewportHeight = 2400,
    tracks = tracks,
    options = options
)

internal fun track(
    trackId: Long = 1,
    text: String = "Settings",
    bounds: AssistRect = AssistRect(60, 100, 420, 160),
    script: AssistScript = AssistScript.LATIN,
    consensusScore: Float? = 0.9f,
    translatedText: String? = null
): AssistTextTrack = AssistTextTrack(
    trackId = trackId,
    text = text,
    bounds = bounds,
    script = script,
    consensusScore = consensusScore,
    translatedText = translatedText
)

internal fun <T> runSuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    val outcome = AtomicReference<Result<T>>()
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
            outcome.set(result)
            latch.countDown()
        }
    })
    latch.await()
    return outcome.get().getOrThrow()
}
