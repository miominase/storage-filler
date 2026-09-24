package jp.own.storagefiller

import java.net.URI
import java.util.Locale

/**
 * チャレンジ記録画面の入力の整形・検証。Android に依存しない純ロジックなので JVM 単体テストで確かめる。
 */
object ChallengeInput {

    /**
     * 寝かせ時間の表示用。端末の言語設定に関係なく小数点は '.' にする。
     * 既定ロケールで書くと独仏などでは "37,52" になり、そのまま記録しようとすると弾かれる。
     */
    fun formatHours(hours: Double): String = String.format(Locale.US, "%.2f", hours)

    /**
     * 寝かせ時間の入力を読む。小数点は '.' と ',' のどちらでもよい。
     * 0以上の有限な数値でなければ null。
     */
    fun parseHours(text: String): Double? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (value.isFinite() && value >= 0) value else null
    }

    /**
     * 記録APIとして受け付けるURLか。パスワードを送る先なので、Apps Script のウェブアプリ
     * （https://script.google.com/macros/s/...）以外には送らない。
     * 文字列の前方一致ではなく URI として解析して判定する（"https://script.google.com@evil.example/"
     * のような userinfo 付きの偽装を通さないため）。
     */
    fun isValidApiUrl(url: String): Boolean {
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return false
        }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (!"script.google.com".equals(uri.host, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        return (uri.path ?: "").startsWith("/macros/s/")
    }
}
