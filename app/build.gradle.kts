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
        versionCode = 21
        versionName = "0.21"
    }
    signingConfigs {
        create("stableRelease") {
            val path = System.getenv("WRITEASSIST_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("WRITEASSIST_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WRITEASSIST_KEY_ALIAS")
                keyPassword = System.getenv("WRITEASSIST_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stableRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
