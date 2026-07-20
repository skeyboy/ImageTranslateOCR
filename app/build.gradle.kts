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
}

dependencies {
    implementation("androidx.core:core-ktx") {
        version { strictly("1.16.0") }
    }
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx") {
        version { strictly("2.9.2") }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android") {
        version { strictly("1.8.1") }
    }
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    //noinspection Aligned16KB
    implementation("com.quickbirdstudios:opencv:4.5.3.0")
}
