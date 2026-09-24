package jp.own.storagefiller

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 記録APIとの通信。verify（端末確認）と record（記録追加）だけを呼ぶ。
 *
 * Apps Script のウェブアプリは失敗でもHTTP 200を返すため、成否は応答JSONの ok と error.code で判断する。
 */
object ChallengeApi {

    private const val TIMEOUT_MS = 20000

    data class Result(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val deviceName: String = "",
        val duplicate: Boolean = false
    )

    fun verify(url: String, password: String, deviceId: String): Result {
        val body = JSONObject()
            .put("action", "verify")
            .put("deviceId", deviceId)
            .put("password", password)
        return post(url, body)
    }

    fun send(url: String, password: String, record: ChallengeRecord): Result {
        // 端末内の管理用フィールド（status/lastError）は送らない
        val payload = JSONObject()
            .put("id", record.id)
            .put("at", record.at)
            .put("deviceId", record.deviceId)
            .put("hours", record.hours)
            .put("result", record.result)
            .put("service", record.service)
            .put("memo", record.memo)
        val body = JSONObject()
            .put("action", "record")
            .put("password", password)
            .put("record", payload)
        return post(url, body)
    }

    private fun post(url: String, body: JSONObject): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                // Apps Script のウェブアプリは /exec から /echo へ転送されるので追従させる
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "StorageFiller")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            parse(text)
        } catch (e: Exception) {
            // 送れたかどうか分からない場合もここに来る。未送信として残し、再送で重複を防ぐ。
            Result(ok = false, code = "NETWORK", message = e.message ?: "通信に失敗しました")
        } finally {
            conn?.disconnect()
        }
    }

    /** 応答本文の解析だけを切り出した純関数。 */
    fun parse(bodyText: String): Result = try {
        val o = JSONObject(bodyText)
        if (o.optBoolean("ok")) {
            Result(
                ok = true,
                deviceName = o.optString("deviceName"),
                duplicate = o.optBoolean("duplicate")
            )
        } else {
            val error = o.optJSONObject("error")
            Result(
                ok = false,
                code = error?.optString("code") ?: "SERVER",
                message = error?.optString("message") ?: "サーバーから不明な応答が返りました"
            )
        }
    } catch (_: Exception) {
        Result(ok = false, code = "SERVER", message = "サーバーから不明な応答が返りました")
    }
}
