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
        versionCode = 11
        versionName = "0.8.1"

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
    // チャレンジ記録の紐づけ画面でQRを読むための Google Code Scanner（v0.8.1〜）。
    // 「外部ライブラリなし・androidxなし」の方針の唯一の例外。スキャン画面は Google Play
    // 開発者サービス側が出すため、このアプリにカメラ権限は要らない（SPEC.md F14）。
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // スキャン画面のモジュールが未取得のとき（APKを手動で入れた初回など）に取得を依頼する
    // ModuleInstall は play-services-base 18.2.0 から。code-scanner が引く 18.1.0 を上げる。
    implementation("com.google.android.gms:play-services-base:18.2.0")

    testImplementation("junit:junit:4.13.2")
    // JVM単体テストではandroid.jarのorg.jsonはスタブ（put()がnullを返す等）で動かないため、
    // テストのみ実体を使う。APKにはAndroid実機のorg.jsonが使われるため影響しない。
    testImplementation("org.json:json:20231013")
}
