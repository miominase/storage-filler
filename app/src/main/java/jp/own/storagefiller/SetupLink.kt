package jp.own.storagefiller

import android.net.Uri

/**
 * QRコード経由の初回設定（URL・共通パスワード）を読み取るための共通ロジック。
 *
 * 受け取り方は2系統ある: `MainActivity` がVIEWインテント（`intent.data`）から読む経路と、
 * `ChallengePanel` がクリップボードのテキストから読む経路。どちらも同じ形を認識できるよう、
 * 判定・抽出のロジックをここに1本化する。
 *
 * 受け付ける形:
 * - `storagefiller://setup?url=...&password=...`
 *   カスタムスキームをそのまま開ける機種や adb 検証用
 * - `https://storagefiller.invalid/setup?url=...&password=...`
 *   標準カメラの多くはhttp/httpsしか「開く」候補を出さないためQR自体はこちらを使う。
 *   `storagefiller.invalid` はRFC 2606で予約された、絶対に名前解決されないTLD
 * - 裸のクエリ部分だけ（`?url=...&password=...` または `url=...&password=...`）
 *   カメラがURI全体ではなくデコード結果のテキストだけをクリップボードにコピーした場合や、
 *   ユーザーが一部だけ貼り付けた場合に対応する
 */
object SetupLink {

    fun isSetupUri(uri: Uri): Boolean = isSetupTarget(uri.scheme, uri.host, uri.path)

    /**
     * URIのscheme・hostは仕様上大文字小文字を区別しない（Androidのインテント解決自体も
     * 区別しない）ため、ここでも区別しない。pathも、手打ちやQR生成ツールで大文字になった
     * `/Setup` を弾かないよう区別しない（v0.8.2〜）。
     * android.net.Uri に触れない純ロジックなので JVM 単体テストで確かめる。
     */
    fun isSetupTarget(scheme: String?, host: String?, path: String?): Boolean {
        if (scheme == null || host == null) return false
        if (scheme.equals("storagefiller", ignoreCase = true) && host.equals("setup", ignoreCase = true)) {
            return true
        }
        return scheme.equals("https", ignoreCase = true) &&
            host.equals("storagefiller.invalid", ignoreCase = true) &&
            (path ?: "").startsWith("/setup", ignoreCase = true)
    }

    /**
     * クリップボードなどから受け取った生のテキストを解析し、(url, password) を返す。
     * 上記のどの形にも当てはまらなければ null を返す。
     * url・passwordのどちらか一方だけが見つかることもある（呼び出し側の `prefillFromUri`
     * が null/空文字のほうを無視する）。
     */
    fun parse(text: String): Pair<String?, String?>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val bareQuery = when {
            trimmed.startsWith("?") -> trimmed.substring(1)
            trimmed.startsWith("url=") -> trimmed
            else -> null
        }
        if (bareQuery != null) {
            return extract(Uri.Builder().encodedQuery(bareQuery).build())
        }
        return fromUri(Uri.parse(trimmed))
    }

    /**
     * VIEWインテントで受け取ったURI（`intent.data`）から (url, password) を取り出す。
     * 設定用のリンクでなければ null。`MainActivity.handleIncomingIntent` と [parse] の共通の入口。
     */
    fun fromUri(uri: Uri): Pair<String?, String?>? = if (isSetupUri(uri)) extract(uri) else null

    private fun extract(uri: Uri): Pair<String?, String?> =
        uri.getQueryParameter("url") to uri.getQueryParameter("password")
}
