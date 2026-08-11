plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.imagetranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.imagetranslate"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    val demoServerEnv = rootProject.file("demo-server/.env")
        .takeIf { it.isFile }
        ?.readLines()
        ?.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains('=')) null
            else trimmed.substringBefore('=').trim() to trimmed.substringAfter('=').trim().trim('"', '\'')
        }
        ?.toMap()
        .orEmpty()
    fun debugEdgeValue(property: String, envName: String, fallback: String = ""): String =
        providers.gradleProperty(property).orNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: demoServerEnv[envName].orEmpty().ifBlank { fallback }
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
            buildConfigField("String", "EDGE_AI_PROVIDER", buildConfigString(debugEdgeValue("EDGE_AI_PROVIDER", "TRANSLATION_PROVIDER", "openlux")))
            buildConfigField("String", "EDGE_AI_BASE_URL", buildConfigString(debugEdgeValue("EDGE_AI_BASE_URL", "OPENLUX_BASE_URL", "https://api.openlux.ai/v1")))
            buildConfigField("String", "EDGE_AI_API_KEY", buildConfigString(debugEdgeValue("EDGE_AI_API_KEY", "OPENLUX_API_KEY")))
            buildConfigField("String", "EDGE_AI_MODELS", buildConfigString(debugEdgeValue("EDGE_AI_MODELS", "OPENLUX_MODELS", "gemini-3.5-flash-lite,gpt-4.1,claude-sonnet-3.6")))
        }
        getByName("release") {
            buildConfigField(
                "String",
                "REMOTE_TRANSLATION_BASE_URL",
                buildConfigString(configuredRemoteTranslationBaseUrl)
            )
            buildConfigField("String", "EDGE_AI_PROVIDER", buildConfigString(""))
            buildConfigField("String", "EDGE_AI_BASE_URL", buildConfigString(""))
            buildConfigField("String", "EDGE_AI_API_KEY", buildConfigString(""))
            buildConfigField("String", "EDGE_AI_MODELS", buildConfigString(""))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
        rootProject.file("ocr-translation-edge/Cargo.toml")
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

tasks.matching { it.name == "preDebugBuild" }.configureEach {
    dependsOn(buildEmbeddedTranslationEdge)
}

dependencies {
    implementation(files("libs/ppocr-sdk-release.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation(project(":smart-assist-core"))
    implementation(project(":experimental-translation"))
    implementation("androidx.core:core-ktx") {
        version { strictly("1.16.0") }
    }
    implementation("androidx.appcompat:appcompat:1.7.1")
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
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
