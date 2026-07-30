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
}

dependencies {
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
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
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
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
