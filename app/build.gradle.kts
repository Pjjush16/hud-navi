plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hud.navi"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hud.navi"
        minSdk = 24  // ONNX Runtime 1.18 requires API 24+
        targetSdk = 34
        versionCode = 79
        versionName = "14.6"
    }

    // ABI 分包
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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

    buildFeatures {
        buildConfig = true
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

    // MapLibre GL Native: 矢量瓦片 + OpenGL ES 渲染（45° 倾斜 HUD）
    implementation("org.maplibre.gl:android-sdk:11.5.2")

    // Gson: GeoJSON 构建（MapLibre 传递依赖，显式声明确保可用）
    implementation("com.google.code.gson:gson:2.10.1")

    // sherpa-onnx: 离线关键词识别（中文唤醒词）— 保留作为备用
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // NanoHTTPD: 轻量级嵌入式 HTTP 服务器（用于 API 端口）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ONNX Runtime: 自训练唤醒词模型推理
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
