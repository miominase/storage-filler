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
     * 0以上の整数の入力を読む（チェックイン報酬・親の即時ポイント）。半角数字だけを受け付け、
     * 符号・小数・空白だけ・Int に収まらない値は null。空欄を許すかどうかは呼び出し側で先に判断する。
     */
    fun parseCount(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || !t.all { it in '0'..'9' }) return null
        return t.toIntOrNull()
    }

    private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,40}$")

    /** 端末IDとして送ってよい形か（前後の空白は呼び出し側で除く）。存在するかどうかはサーバーが判断する。 */
    fun isValidDeviceId(text: String): Boolean = DEVICE_ID_PATTERN.matches(text)

    /** 未送信一覧・破棄確認で見せる1行の要約。 */
    fun summary(r: ChallengeRecord): String {
        if (r.isEndParent) {
            val why = when (r.endReason) {
                ChallengeRecord.REASON_RESET -> "初期化"
                ChallengeRecord.REASON_GIVE_UP -> "失敗して続けない"
                else -> r.endReason
            }
            return "親の終了（$why）"
        }
        val base = "${r.result} / ${r.service} / ${formatHours(r.hours)}h"
        val parent = if (r.parentDeviceId.isEmpty()) "" else " / 親:${r.parentDeviceId}"
        val end = if (r.endParent) " / 親を終了" else ""
        return base + parent + end
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
