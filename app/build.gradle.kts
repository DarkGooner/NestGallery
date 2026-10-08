plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nestgallery.viewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nestgallery.viewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk {
            // 64-bit only: the ONNX Runtime build with Qualcomm's QNN (NPU / GPU) ships arm64-v8a libraries only.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("keystore/release.keystore")
            storePassword = "nestgallery123"
            keyAlias = "nestgallery"
            keyPassword = "nestgallery123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Extract native libraries at install: the Hexagon DSP loads QNN's libQnnHtpV*Skel.so from a real file path.
            useLegacyPackaging = true
            // QNN's old-DSP backend (Hexagon V66) is not used: the NPU path is the HTP backend.
            excludes += listOf("**/libQnnDsp.so", "**/libQnnDspV66Skel.so", "**/libQnnDspV66Stub.so")
        }
    }

    aaptOptions {
        noCompress += listOf("onnx")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    implementation("org.videolan.android:libvlc-all:3.7.6")

    // On-device face + NSFW models (100% offline), run by ONNX Runtime. This is the same ONNX Runtime 1.22 with the
    // Qualcomm QNN execution provider added (NSFW scan on the Snapdragon NPU / GPU); it pulls in
    // com.qualcomm.qti:qnn-runtime (Qualcomm's QNN libraries, Qualcomm AI Hub licence).
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.22.0")

    testImplementation("junit:junit:4.13.2")
    // Desktop ONNX Runtime so OnnxKnn can be unit-tested on the JVM (the Android AAR's natives don't load there).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}
