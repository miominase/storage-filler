package jp.own.storagefiller

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * チャレンジ1件分。送信前に端末内へ保存し、成功するまで同じ内容・同じIDで送り直す。
 *
 * `at` は記録ボタンを押した時点で固定する。通信が遅れても実施日時は動かさない。
 */
data class ChallengeRecord(
    val id: String,
    val at: String,
    val deviceId: String,
    val hours: Double,
    val result: String,
    val service: String,
    val memo: String,
    val status: String,
    val lastError: String
) {
    /** APIへ送る形。サーバー側の validateRecord_ が読むキーと一致させる。 */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", at)
        .put("deviceId", deviceId)
        .put("hours", hours)
        .put("result", result)
        .put("service", service)
        .put("memo", memo)
        .put("status", status)
        .put("lastError", lastError)

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ATTENTION = "attention"

        const val RESULT_SUCCESS = "成功"
        const val RESULT_FAILURE = "失敗"
        val SERVICES = listOf("LINE", "Google")

        fun fromJson(o: JSONObject): ChallengeRecord = ChallengeRecord(
            id = o.optString("id"),
            at = o.optString("at"),
            deviceId = o.optString("deviceId"),
            hours = o.optDouble("hours", 0.0),
            result = o.optString("result"),
            service = o.optString("service"),
            memo = o.optString("memo"),
            status = o.optString("status", STATUS_PENDING),
            lastError = o.optString("lastError")
        )

        /** minSdk 24 では java.time が使えないため SimpleDateFormat を使う。 */
        fun isoNow(millis: Long): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(millis))
        }

        fun create(
            deviceId: String,
            hours: Double,
            result: String,
            service: String,
            memo: String
        ): ChallengeRecord = ChallengeRecord(
            // UUID は36文字なのでサーバーの ^[a-zA-Z0-9-]{16,80}$ を満たす
            id = UUID.randomUUID().toString(),
            at = isoNow(System.currentTimeMillis()),
            deviceId = deviceId,
            hours = hours,
            result = result,
            service = service,
            memo = memo,
            status = STATUS_PENDING,
            lastError = ""
        )
    }
}
