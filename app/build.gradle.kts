plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.cap.livetr"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.cap.livetr"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 빌드마다 같은 열쇠로 서명 → 앱을 지우지 않고 덮어쓰기 설치 가능 (설정·모델 유지)
    signingConfigs {
        getByName("debug") {
            storeFile = file("livetr-debug.keystore")
            storePassword = "livetr123"
            keyAlias = "livetr"
            keyPassword = "livetr123"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources { noCompress += listOf("onnx", "txt") }

    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
