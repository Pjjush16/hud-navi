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
        versionCode = 70
        versionName = "7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 协程（用于 Overpass API 异步请求）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 纯自研引擎，零地图SDK依赖
}
