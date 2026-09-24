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

    /**
     * URIのscheme・hostは仕様上大文字小文字を区別しない（Androidのインテント解決自体も
     * 区別しない）ため、ここでも区別しない。pathはそのまま（大文字小文字を区別して）比較する。
     */
    fun isSetupUri(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        val host = uri.host ?: return false
        if (scheme.equals("storagefiller", ignoreCase = true) && host.equals("setup", ignoreCase = true)) {
            return true
        }
        return scheme.equals("https", ignoreCase = true) &&
            host.equals("storagefiller.invalid", ignoreCase = true) &&
            (uri.path ?: "").startsWith("/setup")
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
            val uri = Uri.Builder().encodedQuery(bareQuery).build()
            return uri.getQueryParameter("url") to uri.getQueryParameter("password")
        }

        val uri = Uri.parse(trimmed)
        if (!isSetupUri(uri)) return null
        return uri.getQueryParameter("url") to uri.getQueryParameter("password")
    }
}
