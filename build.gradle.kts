plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.own.storagefiller"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.own.storagefiller"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
