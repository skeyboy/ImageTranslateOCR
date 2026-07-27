package com.example.smartassist

import com.example.smartassist.api.AssistOptions
import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.max

class LibraryQualityReportTest {
    @Test
    fun writesDeterministicRuntimeSmokeReport() {
        val tracks = listOf(
            track(1, "Offline assistance groups nearby reading lines.", AssistRect(60, 100, 900, 156)),
            track(2, "It never replaces the baseline translation path.", AssistRect(60, 166, 900, 222)),
            track(3, "https://example.com/docs", AssistRect(60, 260, 620, 316)),
            track(
                4,
                "Save",
                AssistRect(60, 350, 260, 406),
                translatedText = "保存当前屏幕中已经识别并翻译完成的全部内容"
            )
        )
        val baseRequest = request(
            tracks = tracks,
            options = AssistOptions(enabled = true, forceAnalysis = true)
        )
        val runtime = Runtime.getRuntime()

        SmartAssistEngineFactory.create(maximumCacheEntries = 64).use { engine ->
            repeat(WARMUP_ITERATIONS) { iteration ->
                runSuspend {
                    engine.analyze(
                        baseRequest.copy(
                            requestId = "warmup-$iteration",
                            generation = iteration.toLong(),
                            viewportSignature = "warmup-$iteration"
                        )
                    )
                }
            }

            forceGc()
            val heapBefore = usedHeap(runtime)
            val durations = LongArray(MEASURED_ITERATIONS)
            repeat(MEASURED_ITERATIONS) { iteration ->
                val startedAt = System.nanoTime()
                val result = runSuspend {
                    engine.analyze(
                        baseRequest.copy(
                            requestId = "measure-$iteration",
                            generation = iteration.toLong(),
                            viewportSignature = "measure-$iteration"
                        )
                    )
                }
                durations[iteration] = System.nanoTime() - startedAt
                assertEquals(AssistStatus.COMPLETED, result.status)
            }
            forceGc()
            val retainedHeapBytes = max(0, usedHeap(runtime) - heapBefore)
            durations.sort()

            val report = buildString {
                appendLine("Smart Assist Core runtime smoke report")
                appendLine("iterations=$MEASURED_ITERATIONS")
                appendLine("p50Micros=${percentileMicros(durations, 50)}")
                appendLine("p90Micros=${percentileMicros(durations, 90)}")
                appendLine("p99Micros=${percentileMicros(durations, 99)}")
                appendLine("retainedHeapBytes=$retainedHeapBytes")
                appendLine("cacheLimit=64")
                appendLine("scope=JVM smoke measurement; not an Android device SLA")
            }
            val reportPath = Paths.get("build", "reports", "smart-assist-core", "runtime-smoke.txt")
            Files.createDirectories(reportPath.parent)
            Files.writeString(reportPath, report)

            assertTrue(durations.first() > 0)
        }
    }

    private fun forceGc() {
        System.gc()
    }

    private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun percentileMicros(sortedNanos: LongArray, percentile: Int): Long {
        val index = ((sortedNanos.size - 1) * percentile / 100.0).toInt()
        return sortedNanos[index] / 1_000
    }

    private companion object {
        const val WARMUP_ITERATIONS = 100
        const val MEASURED_ITERATIONS = 1_000
    }
}
