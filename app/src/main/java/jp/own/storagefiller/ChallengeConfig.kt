package jp.own.storagefiller

import android.content.Context

/**
 * 記録APIの接続先と端末の紐づけ。
 *
 * API URL・共通パスワード・端末IDはAPKにも公開リポジトリにも埋め込まない。
 * 初回設定で3点とも入力してもらい、このアプリ専用の SharedPreferences にだけ置く。
 */
class ChallengeConfig(context: Context) {

    private val prefs = context.getSharedPreferences("challenge", Context.MODE_PRIVATE)

    val apiUrl: String get() = prefs.getString(KEY_URL, "") ?: ""
    val password: String get() = prefs.getString(KEY_PASSWORD, "") ?: ""
    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
    val deviceName: String get() = prefs.getString(KEY_DEVICE_NAME, "") ?: ""

    fun isBound(): Boolean = apiUrl.isNotEmpty() && password.isNotEmpty() && deviceId.isNotEmpty()

    fun save(url: String, password: String, deviceId: String, deviceName: String) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putString(KEY_PASSWORD, password)
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .putString(KEY_DEVICE_NAME, deviceName)
            .apply()
    }

    /** 端末を手放すときなどに紐づけを消す。未送信記録には触れない。 */
    fun unbind() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_URL = "api_url"
        const val KEY_PASSWORD = "password"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
    }
}
