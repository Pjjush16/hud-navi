plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hud.navi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hud.navi"
        minSdk = 21
        targetSdk = 34
        versionCode = 75
        versionName = "10.24"
    }

    // ABI 分包：输出 arm64-v8a / armeabi-v7a / universal 三个 APK
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/hud-navi-release.jks")
            storePassword = "hudnavi2026release"
            keyAlias = "hud-navi"
            keyPassword = "hudnavi2026release"
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // sherpa-onnx: 离线关键词识别（中文唤醒词）
    // AAR 由 CI workflow 自动下载到 app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
