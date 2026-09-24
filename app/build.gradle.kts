import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// リリース署名鍵の設定。keystore.properties は gitignore してあるので、
// 手元に無い環境（CI や clone 直後）では署名なしのまま assembleDebug だけ通る。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "jp.own.storagefiller"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.own.storagefiller"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "0.8.0"

        // アプリ内アップデートの取得元。リポジトリを移す場合はここだけ変える
        buildConfigField("String", "UPDATE_REPO", "\"miominase/storage-filler\"")
    }

    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // 純ロジックだけを対象にした JVM 単体テスト。APKには含まれない。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM単体テストではandroid.jarのorg.jsonはスタブ（put()がnullを返す等）で動かないため、
    // テストのみ実体を使う。APKにはAndroid実機のorg.jsonが使われるため影響しない。
    testImplementation("org.json:json:20231013")
}
