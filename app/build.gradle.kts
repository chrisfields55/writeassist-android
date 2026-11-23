plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.writeassist"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.writeassist"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.13"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
