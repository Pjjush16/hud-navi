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
        versionCode = 84
        versionName = "11.3"
    }

    // ========== 双版本：lite（关键词识别）+ full（本地LLM） ==========
    flavorDimensions += "model"

    productFlavors {
        create("lite") {
            dimension = "model"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "IS_LLM_ENABLED", "false")
            resValue("string", "app_name", "HUD 导航 Lite")
        }
        create("full") {
            dimension = "model"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "IS_LLM_ENABLED", "true")
            resValue("string", "app_name", "HUD 导航")
        }
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

// ========== Lite 版本：排除 LLM 模型资源（MNN .so 本身不在 lite 构建中） ==========
android.applicationVariants.all {
    val variant = this
    if (variant.flavorName == "lite") {
        variant.mergeAssetsProvider.configure {
            doLast {
                val assetsDir = outputDir.get().asFile
                val llmDir = File(assetsDir, "llm")
                if (llmDir.exists()) {
                    llmDir.deleteRecursively()
                    logger.lifecycle("Lite: removed assets/llm/")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // sherpa-onnx: 离线关键词识别（中文唤醒词）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
