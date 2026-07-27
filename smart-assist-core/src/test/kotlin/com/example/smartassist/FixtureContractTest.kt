package com.example.smartassist

import com.example.smartassist.api.AssistOptions
import com.example.smartassist.api.AssistRect
import com.example.smartassist.api.AssistRequest
import com.example.smartassist.api.AssistScene
import com.example.smartassist.api.AssistScript
import com.example.smartassist.api.AssistStatus
import com.example.smartassist.api.AssistTextTrack
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureContractTest {
    @Test
    fun deterministicProviderMatchesSanitizedGoldenFixtures() {
        FIXTURES.forEach { fixturePath ->
            val fixture = loadFixture(fixturePath)
            SmartAssistEngineFactory.create().use { engine ->
                val result = runSuspend { engine.analyze(fixture.request) }

                assertEquals(fixture.name, AssistStatus.COMPLETED, result.status)
                assertEquals(fixture.name, fixture.expectedScene, result.scene)
                assertTrue(
                    "${fixture.name}: group count ${result.groups.size}",
                    result.groups.size in fixture.minimumGroupCount..fixture.maximumGroupCount
                )
                val protectedIds = result.suggestions
                    .filter { it.protectTranslation }
                    .map { it.trackId }
                    .toSet()
                assertEquals(fixture.name, fixture.protectedTrackIds, protectedIds)
            }
        }
    }

    @Test
    fun invalidFixtureIsRejectedAtTheLibraryBoundary() {
        INVALID_FIXTURES.forEach { fixturePath ->
            assertThrows(fixturePath, IllegalArgumentException::class.java) {
                loadFixture(fixturePath)
            }
        }
    }

    private fun loadFixture(path: String): Fixture {
        val jsonText = checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing fixture: $path"
        }.bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(jsonText).asJsonObject
        val viewport = root.objectValue("viewport")
        val width = viewport.intValue("width")
        val height = viewport.intValue("height")
        val tracks = root.getAsJsonArray("tracks").map { element ->
            val track = element.asJsonObject
            val bounds = track.objectValue("bounds")
            AssistTextTrack(
                trackId = track.longValue("id"),
                text = track.stringValue("text"),
                bounds = AssistRect(
                    left = bounds.intValue("left"),
                    top = bounds.intValue("top"),
                    right = bounds.intValue("right"),
                    bottom = bounds.intValue("bottom")
                ),
                script = AssistScript.valueOf(track.stringValue("script")),
                consensusScore = track.get("consensusScore").asFloat
            )
        }
        val expected = root.objectValue("expected")
        val name = root.stringValue("name")
        return Fixture(
            name = name,
            request = AssistRequest(
                requestId = name,
                generation = 1,
                viewportSignature = "fixture-$name",
                viewportWidth = width,
                viewportHeight = height,
                tracks = tracks,
                options = AssistOptions(enabled = true)
            ),
            expectedScene = AssistScene.valueOf(expected.stringValue("scene")),
            minimumGroupCount = expected.intValue("minimumGroupCount"),
            maximumGroupCount = expected.intValue("maximumGroupCount"),
            protectedTrackIds = expected.getAsJsonArray("protectedTrackIds")
                .map { it.asLong }
                .toSet()
        )
    }

    private fun JsonObject.objectValue(name: String): JsonObject = getAsJsonObject(name)
    private fun JsonObject.stringValue(name: String): String = get(name).asString
    private fun JsonObject.intValue(name: String): Int = get(name).asInt
    private fun JsonObject.longValue(name: String): Long = get(name).asLong

    private data class Fixture(
        val name: String,
        val request: AssistRequest,
        val expectedScene: AssistScene,
        val minimumGroupCount: Int,
        val maximumGroupCount: Int,
        val protectedTrackIds: Set<Long>
    )

    private companion object {
        val FIXTURES = listOf(
            "/fixtures/reading/article.json",
            "/fixtures/settings/menu.json",
            "/fixtures/mixed-language/navigation.json",
            "/fixtures/code/kotlin.json"
        )
        val INVALID_FIXTURES = listOf(
            "/fixtures/invalid/out-of-bounds.json"
        )
    }
}
