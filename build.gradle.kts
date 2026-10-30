plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.candle.assistant"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.candle.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
