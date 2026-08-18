import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        val cargoMetadata = providers.exec {
            workingDir = rootDir
            commandLine(
                "cargo",
                "metadata",
                "--format-version",
                "1",
                "--filter-platform",
                "aarch64-linux-android",
                "--manifest-path",
                "ocr-translation-edge/Cargo.toml"
            )
        }.standardOutput.asText.get()
        @Suppress("UNCHECKED_CAST")
        val packages = (JsonSlurper().parseText(cargoMetadata) as Map<String, Any>)["packages"]
            as List<Map<String, Any>>
        val verifierManifest = packages
            .first { it["name"] == "rustls-platform-verifier-android" }["manifest_path"]
            .toString()
        maven {
            name = "rustlsPlatformVerifierAndroid"
            url = uri(file(verifierManifest).parentFile.resolve("maven"))
            metadataSources {
                mavenPom()
                artifact()
            }
            content { includeGroup("rustls") }
        }
    }
}
rootProject.name = "ImageTranslateOCR"
include(":app")
include(":smart-assist-core")
include(":experimental-translation")
