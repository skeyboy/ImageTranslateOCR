package com.example.smartassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

class OfflineDependencyBoundaryTest {
    @Test
    fun mainSourcesDoNotImportAndroidHostModelOrNetworkApis() {
        val sourceRoot = modulePath("src/main/kotlin")
        assertTrue("Missing main source root: $sourceRoot", Files.isDirectory(sourceRoot))

        val violations = Files.walk(sourceRoot).use { paths ->
            paths.filter { path -> path.extension == "kt" }
                .flatMap { path ->
                    val relative = sourceRoot.relativize(path)
                    path.readText().lineSequence()
                        .filter { line -> FORBIDDEN_IMPORTS.any(line.trim()::startsWith) }
                        .map { line -> "$relative: ${line.trim()}" }
                        .toList()
                        .stream()
                }
                .toList()
        }

        assertTrue("Forbidden Library imports:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun productionDependencyGraphHasNoModelOrNetworkSdk() {
        val buildFile = modulePath("build.gradle.kts")
        val buildText = buildFile.readText()

        assertFalse(
            "Core Library must not declare production implementation dependencies",
            Regex("(?m)^\\s*implementation\\(").containsMatchIn(buildText)
        )
        assertTrue("Fixture JSON dependency must remain test-only", "testImplementation" in buildText)
    }

    private fun modulePath(relative: String): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir"))
        val nestedModule = workingDirectory.resolve("smart-assist-core")
        val moduleRoot = if (Files.isDirectory(nestedModule)) nestedModule else workingDirectory
        return moduleRoot.resolve(relative)
    }

    private companion object {
        val FORBIDDEN_IMPORTS = listOf(
            "import android.",
            "import androidx.",
            "import com.example.imagetranslate.",
            "import com.google.mlkit.",
            "import okhttp3.",
            "import retrofit2.",
            "import java.net."
        )
    }
}
