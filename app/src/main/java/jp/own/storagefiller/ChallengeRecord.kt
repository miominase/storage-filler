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
data class ChallengeRecord @JvmOverloads constructor(
    val id: String,
    val at: String,
    val deviceId: String,
    val hours: Double,
    val result: String,
    val service: String,
    val memo: String,
    val status: String,
    val lastError: String,
    /** 記録ボタンを押した時点で固定するアカウントID。空は従来どおり（チェックなし）。 */
    val accountId: String = "",
    /** 使い切りと分かったうえで「このまま送る」を選んだ記録。 */
    val overUse: Boolean = false,
    /** 種別。[KIND_RECORD]=チャレンジ記録、[KIND_END_PARENT]=単独の親の終了。保存済みの古い記録は前者。 */
    val kind: String = KIND_RECORD,
    /** 当てた親の端末ID。チャレンジ記録ではアプリ側で必須（古い保存分は空）。 */
    val parentDeviceId: String = "",
    /** チェックイン報酬（円、0以上）。未入力は null。 */
    val checkinReward: Int? = null,
    /** 親に招待で即時に入ったポイント（0以上）。成功の記録だけ。未入力・失敗は null。 */
    val invitePoints: Int? = null,
    /** true なら、この記録のあと自端末の親を終了する。 */
    val endParent: Boolean = false,
    /** [KIND_END_PARENT] の理由（[REASON_RESET] / [REASON_GIVE_UP]）。それ以外の種別では空。 */
    val endReason: String = "",
    /** [KIND_LINK] の共有されたURL。それ以外の種別では空。文字列として保存するだけ。 */
    val sharedUrl: String = "",
    /** [KIND_LINK] の共有元テキスト（URLを含む全文）。 */
    val sharedText: String = ""
) {
    /** 保存用の形（端末内のキュー）。APIへ送る形は ChallengeApi.recordBody / endParentBody。 */
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("at", at)
            .put("deviceId", deviceId)
            .put("hours", hours)
            .put("result", result)
            .put("service", service)
            .put("memo", memo)
            .put("status", status)
            .put("lastError", lastError)
            .put("accountId", accountId)
            .put("overUse", overUse)
            .put("kind", kind)
            .put("parentDeviceId", parentDeviceId)
            .put("endParent", endParent)
            .put("endReason", endReason)
            .put("sharedUrl", sharedUrl)
            .put("sharedText", sharedText)
        // null は「未入力」。0 と区別するためキーごと省く
        if (checkinReward != null) o.put("checkinReward", checkinReward)
        if (invitePoints != null) o.put("invitePoints", invitePoints)
        return o
    }

    val isEndParent: Boolean get() = kind == KIND_END_PARENT

    val isLink: Boolean get() = kind == KIND_LINK

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ATTENTION = "attention"

        const val KIND_RECORD = "record"
        const val KIND_END_PARENT = "endParent"

        /** 共有メニューから受けたリンク（専用タブへ1件1行）。 */
        const val KIND_LINK = "link"

        const val REASON_RESET = "reset"
        const val REASON_GIVE_UP = "giveUp"

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
            lastError = o.optString("lastError"),
            accountId = o.optString("accountId"),
            overUse = o.optBoolean("overUse", false),
            kind = o.optString("kind").ifEmpty { KIND_RECORD },
            parentDeviceId = o.optString("parentDeviceId"),
            checkinReward = if (o.has("checkinReward") && !o.isNull("checkinReward")) o.optInt("checkinReward") else null,
            invitePoints = if (o.has("invitePoints") && !o.isNull("invitePoints")) o.optInt("invitePoints") else null,
            endParent = o.optBoolean("endParent", false),
            endReason = o.optString("endReason"),
            sharedUrl = o.optString("sharedUrl"),
            sharedText = o.optString("sharedText")
        )

        /** minSdk 24 では java.time が使えないため SimpleDateFormat を使う。 */
        fun isoNow(millis: Long): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(Date(millis))
        }

        @JvmOverloads
        fun create(
            deviceId: String,
            accountId: String,
            hours: Double,
            result: String,
            service: String,
            memo: String,
            parentDeviceId: String = "",
            checkinReward: Int? = null,
            invitePoints: Int? = null,
            endParent: Boolean = false
        ): ChallengeRecord = ChallengeRecord(
            // UUID は36文字なのでサーバーの ^[a-zA-Z0-9-]{16,80}$ を満たす
            id = UUID.randomUUID().toString(),
            at = isoNow(System.currentTimeMillis()),
            deviceId = deviceId,
            accountId = accountId,
            hours = hours,
            result = result,
            service = service,
            memo = memo,
            status = STATUS_PENDING,
            lastError = "",
            parentDeviceId = parentDeviceId,
            checkinReward = checkinReward,
            // 即時ポイントは成功のときだけ送る（失敗では入力があっても捨てる）
            invitePoints = if (result == RESULT_SUCCESS) invitePoints else null,
            endParent = endParent
        )

        /** 共有されたリンク。`at` は共有を受けて「送る」を押した時刻で固定し、再送でも同じ id・同じ内容で送る。 */
        fun createLink(deviceId: String, url: String, text: String): ChallengeRecord = ChallengeRecord(
            id = UUID.randomUUID().toString(),
            at = isoNow(System.currentTimeMillis()),
            deviceId = deviceId,
            hours = 0.0,
            result = "",
            service = "",
            memo = "",
            status = STATUS_PENDING,
            lastError = "",
            kind = KIND_LINK,
            sharedUrl = url,
            sharedText = text
        )

        /** 単独の親の終了。`at` を endedAt として使う。再送でも同じ id・同じ時刻で送る。 */
        fun createEndParent(deviceId: String, reason: String): ChallengeRecord = ChallengeRecord(
            id = UUID.randomUUID().toString(),
            at = isoNow(System.currentTimeMillis()),
            deviceId = deviceId,
            hours = 0.0,
            result = "",
            service = "",
            memo = "",
            status = STATUS_PENDING,
            lastError = "",
            kind = KIND_END_PARENT,
            endReason = reason
        )
    }
}
