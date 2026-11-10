import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// リリース署名鍵の設定。keystore.properties は gitignore してあるので、
// 手元に無い環境（CI や clone 直後）では署名なしのまま assembleDebug だけ通る。
// 設定ファイルの探し方（上が優先）:
//   1. 環境変数 KEYSTORE_PROPERTIES … properties ファイル自体のパス
//   2. 環境変数 STORAGE_FILLER_KEYSTORE_DIR … その中の keystore.properties
//   3. リポジトリ直下の keystore.properties（従来どおり。Windows 側はこれ）
// 環境変数で指した場合、storeFile が相対パスなら properties のあるフォルダ基準で解釈する。
val envPropsPath = System.getenv("KEYSTORE_PROPERTIES")?.takeIf { it.isNotBlank() }
val envDirPath = System.getenv("STORAGE_FILLER_KEYSTORE_DIR")?.takeIf { it.isNotBlank() }
val fromEnv = envPropsPath != null || envDirPath != null
val keystorePropsFile: File = when {
    envPropsPath != null -> File(envPropsPath)
    envDirPath != null -> File(envDirPath, "keystore.properties")
    else -> rootProject.file("keystore.properties")
}
val keystoreProps = Properties().apply {
    // 読めない・壊れているときは空のまま（鍵なし扱い）
    runCatching { if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) } }
}
// 鍵ファイルの場所。解釈できない書き方（Linux から見た Windows の C:/... など）なら null
val releaseStoreFile: File? = keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }?.let { path ->
    runCatching {
        val f = File(path)
        when {
            f.isAbsolute -> f
            fromEnv -> File(keystorePropsFile.absoluteFile.parentFile, path)
            else -> file(path)
        }.takeIf { it.isFile }
    }.getOrNull()
}
// 鍵ファイルがあり、パスワード・別名も揃っているときだけ署名する。それ以外は鍵なし扱い
val hasReleaseKey = releaseStoreFile != null &&
    listOf("storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrEmpty() }

android {
    namespace = "jp.own.storagefiller"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.own.storagefiller"
        minSdk = 24
        targetSdk = 35
        versionCode = 17
        versionName = "0.11.0"

        // アプリ内アップデートの取得元。リポジトリを移す場合はここだけ変える
        buildConfigField("String", "UPDATE_REPO", "\"miominase/storage-filler\"")
    }

    if (hasReleaseKey) {
        signingConfigs {
            create("release") {
                storeFile = releaseStoreFile
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
        // 試験版は別のアプリIDにして、端末の正式版（リリース鍵で署名）を消さずに横に入れられるようにする。
        // アプリ名は src/debug/res/values/strings.xml で「(debug)」付きに差し替える。release には影響しない
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 で使っていないコード・リソースを削って APK を小さくする（v0.8.2〜、SPEC 非機能要件）。
            // 縮小後のエラー記録を読むための対応表 build/outputs/mapping/release/mapping.txt は
            // リリースごとに保管する（KNOWLEDGE.md）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        // R8 を有効にした release と同じ中身を、debug の鍵で署名して正式版の横に入れて確かめる試験用。
        // 配布はしない。アプリIDは .r8test、表示名は src/r8test/res で「(R8試験)」付き。
        create("r8test") {
            initWith(getByName("release"))
            applicationIdSuffix = ".r8test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

// 鍵なしで正式版（release）をAPK/AABまで作ろうとしたら、署名なしの成果物を出さずに失敗させる。
// debug・単体テスト・r8test には影響しない。
gradle.taskGraph.whenReady {
    val releaseOutputs = setOf("packageRelease", "bundleRelease", "assembleRelease")
    if (!hasReleaseKey && allTasks.any { it.project == project && it.name in releaseOutputs }) {
        throw GradleException(
            "正式版(release)の署名鍵が見つからない、または解釈できないため中止します（署名なしAPKは作りません）。" +
                " 探した場所: ${keystorePropsFile.path}" +
                "（存在=${keystorePropsFile.isFile}）。" +
                " 環境変数 KEYSTORE_PROPERTIES か STORAGE_FILLER_KEYSTORE_DIR で keystore.properties を指し、" +
                " storeFile・storePassword・keyAlias・keyPassword が揃い、鍵ファイルがこのPCから読めることを確認してください。" +
                " 手順は KNOWLEDGE.md の「正式ビルドの署名設定」。"
        )
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
