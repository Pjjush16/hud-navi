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
        versionCode = 81
        versionName = "11.0"
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

    // 强制原生库以 STORED 模式打包 + 页对齐（修复安装失败）
    // sherpa-onnx AAR 中的 .so 默认被 deflated 压缩，导致 Android 安装器无法 mmap
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

    // v11.0: MNN-LLM — 本地 LLM 推理（Qwen2.5-0.5B）
    // 阿里出品，对 Qwen 系列天然友好
    // 模型文件需放入 app/src/main/assets/llm/ 目录（MNN 格式）
    // Maven 仓库待确认，目前通过 Ollama HTTP API 兼容调用
    // implementation("com.alibaba:MNN-LLM:2.9.0")
}
