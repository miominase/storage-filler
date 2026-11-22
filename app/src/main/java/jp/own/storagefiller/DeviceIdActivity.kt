package jp.own.storagefiller

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * TikTok 端末管理のアプリ（jp.own.tiktokdevice）に、この端末の端末番号だけを渡す（v0.13.0〜）。
 * 呼べるのは startActivityForResult で呼んだ TikTok 端末管理だけ。記録先のURL・パスワード・アカウントは渡さない。
 * 画面は出さずにすぐ閉じる。
 */
class DeviceIdActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = ChallengeConfig(this).deviceId
        val result = DeviceIdShare.answer(callingPackage, intent?.action, id)
        if (result != null) setResult(RESULT_OK, Intent().putExtra(DeviceIdShare.EXTRA_DEVICE_ID, result))
        else setResult(RESULT_CANCELED)
        finish()
    }
}

/** 渡してよいかの決まり（テストのため画面から分ける）。 */
object DeviceIdShare {
    const val ACTION = "jp.own.storagefiller.action.DEVICE_ID"
    const val EXTRA_DEVICE_ID = "device_id"
    private val ALLOWED = setOf("jp.own.tiktokdevice")

    /** 渡す端末番号。呼び手が違う・決まった呼び方でない・番号が未設定・形が違うなら null。 */
    fun answer(caller: String?, action: String?, deviceId: String): String? {
        if (caller == null || caller !in ALLOWED || action != ACTION) return null
        return deviceId.trim().takeIf { ChallengeInput.isValidDeviceId(it) }
    }
}
