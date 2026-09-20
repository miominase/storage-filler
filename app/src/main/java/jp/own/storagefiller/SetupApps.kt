package jp.own.storagefiller

/**
 * 端末セットアップ時に入れる・更新するアプリの一覧。
 *
 * タップで Google Play のページを開くだけの導線で、インストール状況の判定はしない。
 * そのおかげでパッケージの可視性（`<queries>` や QUERY_ALL_PACKAGES）が不要になっている。
 * 増減はこのリストを書き換えるだけでよい。
 */
object SetupApps {

    data class Entry(val label: String, val pkg: String)

    val LIST = listOf(
        Entry("Google", "com.google.android.googlequicksearchbox"),
        Entry("Chrome", "com.android.chrome"),
        Entry("Google レンズ", "com.google.ar.lens"),
        Entry("Google Play開発者サービス", "com.google.android.gms"),
        Entry("LINE", "jp.naver.line.android"),
        Entry("TikTok Lite", "com.ss.android.ugc.tiktok.lite")
    )
}
