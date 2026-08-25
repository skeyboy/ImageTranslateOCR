import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun localSecret(name: String): String = localProperties.getProperty(name)?.trim().orEmpty()

fun configuredValue(gradleProperty: String, environmentVariable: String): String =
    providers.gradleProperty(gradleProperty).orNull?.trim().orEmpty()
        .ifBlank { providers.environmentVariable(environmentVariable).orNull?.trim().orEmpty() }

val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
val configuredVersionCode = configuredValue("VERSION_CODE", "ANDROID_VERSION_CODE")
val androidVersionCode = configuredVersionCode.takeIf(String::isNotBlank)?.let { value ->
    requireNotNull(value.toIntOrNull()?.takeIf { it > 0 }) {
        "VERSION_CODE must be a positive integer"
    }
} ?: gitCommitCount
val androidVersionName = configuredValue("VERSION_NAME", "ANDROID_VERSION_NAME")
    .ifBlank { "1.0.$androidVersionCode" }
val apkBuildDate = configuredValue("APK_BUILD_DATE", "ANDROID_APK_BUILD_DATE")
    .ifBlank { LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) }
    .also { value ->
        require(value.matches(Regex("\\d{8}"))) {
            "APK_BUILD_DATE must use yyyyMMdd format"
        }
    }
val apkSafeVersionName = androidVersionName.replace(Regex("[^A-Za-z0-9._-]"), "-")

val excludeEdgeAiSecrets = providers.gradleProperty("EXCLUDE_EDGE_AI_SECRETS").orNull
    ?.toBooleanStrictOrNull()
    ?: localSecret("EXCLUDE_EDGE_AI_SECRETS").toBooleanStrictOrNull()
    ?: true
val configuredGeminiApiKey = providers.gradleProperty("EDGE_AI_API_KEY").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("EDGE_AI_API_KEY") }
    .ifBlank { localSecret("GEMINI_API_KEY") }
val configuredOpenLuxApiKey = providers.gradleProperty("OPENLUX_API_KEY").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("OPENLUX_API_KEY") }
val packagedGeminiApiKey = configuredGeminiApiKey.takeUnless { excludeEdgeAiSecrets }.orEmpty()
val packagedOpenLuxApiKey = configuredOpenLuxApiKey.takeUnless { excludeEdgeAiSecrets }.orEmpty()
val packagedGeminiModels = providers.gradleProperty("EDGE_AI_MODELS").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("GEMINI_MODELS") }
    .ifBlank { "gemini-3.5-flash-lite" }
val packagedGeminiThinkingLevel = providers.gradleProperty("EDGE_AI_THINKING_LEVEL").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("GEMINI_THINKING_LEVEL") }
    .ifBlank { "medium" }
val packagedGeminiProxyUrl = providers.gradleProperty("EDGE_AI_PROXY_URL").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("GEMINI_PROXY_URL") }
val configuredDemoServerBaseUrl = providers.gradleProperty("DEMO_SERVER_BASE_URL").orNull
    ?.trim().orEmpty()
    .ifBlank { localSecret("DEMO_SERVER_BASE_URL") }

android {
    namespace = "com.example.imagetranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.imagetranslate"
        minSdk = 24
        targetSdk = 34
        versionCode = androidVersionCode
        versionName = androidVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    val configuredRemoteTranslationBaseUrl = providers
        .gradleProperty("REMOTE_TRANSLATION_BASE_URL")
        .orNull
        ?.trim()
        .orEmpty()
    fun buildConfigString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .let { "\"$it\"" }

    buildTypes {
        getByName("debug") {
            val endpoint = configuredRemoteTranslationBaseUrl.ifBlank {
                "https://api-dev.pnutsai.com"
            }
            buildConfigField(
                "String",
                "REMOTE_TRANSLATION_BASE_URL",
                buildConfigString(endpoint)
            )
            buildConfigField("String", "EDGE_AI_PROVIDER", buildConfigString("openlux"))
            buildConfigField("String", "EDGE_AI_BASE_URL", buildConfigString("https://generativelanguage.googleapis.com/v1beta"))
            // The native V4 client reads its key only from an explicit Gradle property or
            // the untracked local.properties file; server-side .env secrets are never copied.
            buildConfigField("String", "EDGE_AI_API_KEY", buildConfigString(packagedGeminiApiKey))
            buildConfigField("String", "OPENLUX_API_KEY", buildConfigString(packagedOpenLuxApiKey))
            buildConfigField("String", "OPENLUX_BASE_URL", buildConfigString("https://api.openlux.ai/v1"))
            buildConfigField("String", "OPENLUX_MODELS", buildConfigString("gemini-3.5-flash-lite"))
            buildConfigField("String", "EDGE_AI_MODELS", buildConfigString(packagedGeminiModels))
            buildConfigField("String", "EDGE_AI_REASONING_EFFORT", buildConfigString(""))
            buildConfigField("String", "EDGE_AI_THINKING_MODE", buildConfigString("THINKING_LEVEL"))
            buildConfigField("String", "EDGE_AI_THINKING_LEVEL", buildConfigString(packagedGeminiThinkingLevel))
            buildConfigField("String", "EDGE_AI_PROXY_URL", buildConfigString(packagedGeminiProxyUrl))
            buildConfigField("String", "DEMO_SERVER_BASE_URL", buildConfigString(configuredDemoServerBaseUrl))
        }
        getByName("release") {
            buildConfigField(
                "String",
                "REMOTE_TRANSLATION_BASE_URL",
                buildConfigString(configuredRemoteTranslationBaseUrl)
            )
            buildConfigField("String", "EDGE_AI_PROVIDER", buildConfigString("openlux"))
            buildConfigField("String", "EDGE_AI_BASE_URL", buildConfigString("https://generativelanguage.googleapis.com/v1beta"))
            buildConfigField("String", "EDGE_AI_API_KEY", buildConfigString(packagedGeminiApiKey))
            buildConfigField("String", "OPENLUX_API_KEY", buildConfigString(packagedOpenLuxApiKey))
            buildConfigField("String", "OPENLUX_BASE_URL", buildConfigString("https://api.openlux.ai/v1"))
            buildConfigField("String", "OPENLUX_MODELS", buildConfigString("gemini-3.5-flash-lite"))
            buildConfigField("String", "EDGE_AI_MODELS", buildConfigString(packagedGeminiModels))
            buildConfigField("String", "EDGE_AI_REASONING_EFFORT", buildConfigString(""))
            buildConfigField("String", "EDGE_AI_THINKING_MODE", buildConfigString("THINKING_LEVEL"))
            buildConfigField("String", "EDGE_AI_THINKING_LEVEL", buildConfigString(packagedGeminiThinkingLevel))
            buildConfigField("String", "EDGE_AI_PROXY_URL", buildConfigString(packagedGeminiProxyUrl))
            buildConfigField("String", "DEMO_SERVER_BASE_URL", buildConfigString(configuredDemoServerBaseUrl))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // androidx.lifecycle's detector is binary-incompatible with this AGP/Kotlin lint runtime.
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        jniLibs {
            // Experimental models are validated on arm64. Keep baseline OpenCV ABIs unchanged.
            excludes += setOf(
                "**/armeabi-v7a/libllm_inference_engine_jni.so",
                "**/x86/libllm_inference_engine_jni.so",
                "**/x86_64/libllm_inference_engine_jni.so",
                "**/armeabi-v7a/libonnxruntime.so",
                "**/armeabi-v7a/libonnxruntime4j_jni.so",
                "**/x86/libonnxruntime.so",
                "**/x86/libonnxruntime4j_jni.so",
                "**/x86_64/libonnxruntime.so",
                "**/x86_64/libonnxruntime4j_jni.so"
            )
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/rustJniLibs"))

    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "ImageTranslateOCR-$apkBuildDate-v$apkSafeVersionName-${buildType.name}.apk"
        }
    }
}

val buildEmbeddedTranslationEdge by tasks.registering(Exec::class) {
    val ndkRoot = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/darwin-x86_64")
    val linker = ndkRoot.resolve("bin/aarch64-linux-android24-clang")
    workingDir(rootProject.projectDir)
    environment("CC_aarch64_linux_android", linker.absolutePath)
    environment("AR_aarch64_linux_android", ndkRoot.resolve("bin/llvm-ar").absolutePath)
    environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
    commandLine("cargo", "build", "-p", "ocr-translation-edge", "--target", "aarch64-linux-android")
    inputs.files(
        fileTree(rootProject.file("ocr-translation-core/src")),
        fileTree(rootProject.file("ocr-translation-edge/src")),
        rootProject.file("ocr-translation-core/Cargo.toml"),
        rootProject.file("ocr-translation-edge/Cargo.toml"),
        rootProject.file("Cargo.lock")
    )
    outputs.files(
        rootProject.file("target/aarch64-linux-android/debug/libocr_translation_edge.so"),
        layout.buildDirectory.file("generated/rustJniLibs/arm64-v8a/libocr_translation_edge.so")
    )
    doLast {
        copy {
            from(rootProject.file("target/aarch64-linux-android/debug/libocr_translation_edge.so"))
            into(layout.buildDirectory.dir("generated/rustJniLibs/arm64-v8a"))
        }
    }
}

tasks.matching { it.name == "preDebugBuild" || it.name == "preReleaseBuild" }.configureEach {
    dependsOn(buildEmbeddedTranslationEdge)
}

dependencies {
    implementation("rustls:rustls-platform-verifier:0.1.1")
    implementation(files("libs/ppocr-sdk-release.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation(project(":smart-assist-core"))
    implementation(project(":experimental-translation"))
    implementation("androidx.core:core-ktx") {
        version { strictly("1.16.0") }
    }
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.gms:play-services-base:18.10.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx") {
        version { strictly("2.9.2") }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android") {
        version { strictly("1.8.1") }
    }
    implementation("com.google.mlkit:translate:17.0.3")
    //noinspection Aligned16KB
    implementation("com.quickbirdstudios:opencv:4.5.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
