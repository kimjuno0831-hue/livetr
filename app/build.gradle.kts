plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.cap.soopvod"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.cap.soopvod"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // 빌드마다 같은 열쇠로 서명 → 지우지 않고 덮어쓰기 설치 가능
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
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
