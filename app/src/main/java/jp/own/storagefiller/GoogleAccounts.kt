package jp.own.storagefiller

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端末で使っているGoogleアカウントの表示（F15）。
 *
 * Android 8 以降は GET_ACCOUNTS があっても他アプリ（Google）のアカウントは見えないため、
 * 標準のアカウント選択画面でユーザーに選んでもらい、選ばれたアカウントだけを覚える。権限は追加しない。
 * メールアドレスは端末内（SharedPreferences "google_accounts"）にだけ置き、外部へは送らない。
 *
 * 将来、記録送信時に「使ったGoogleアカウント／それに紐づくLINE」を選ぶ拡張を見込み、
 * 保存形式はアカウントの配列（JSON）にしてある。今のバージョンでは常に0件か1件。
 */
class GoogleAccounts(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 覚えているアカウント（今は0件か1件）。 */
    fun saved(): List<Entry> = decode(prefs.getString(KEY_ACCOUNTS, null))

    /** 選び直したアカウントで置き換える（今は1件だけ持つ）。 */
    fun replaceWith(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs.edit().putString(KEY_ACCOUNTS, encode(listOf(Entry(trimmed)))).apply()
    }

    /**
     * 端末に今あるGoogleアカウント名。確かめられないときは null。
     * 選択画面で選ばれたアカウントはこのアプリから見えるようになる（Android 8 以降）。
     * Android 7 系は権限なしでは他アプリのアカウントが返らないため確かめない。
     */
    fun presentNames(): Set<String>? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            AccountManager.get(context).getAccountsByType(TYPE_GOOGLE).map { it.name }.toSet()
        } catch (e: SecurityException) {
            null
        }
    }

    data class Entry(val name: String)

    companion object {
        const val TYPE_GOOGLE = "com.google"
        const val PREFS = "google_accounts"
        private const val KEY_ACCOUNTS = "accounts"

        /** 標準のアカウント選択画面を出す Intent。GET_ACCOUNTS は不要。 */
        fun chooseIntent(current: String?): Intent =
            AccountManager.newChooseAccountIntent(
                current?.let { android.accounts.Account(it, TYPE_GOOGLE) },
                null, arrayOf(TYPE_GOOGLE), null, null, null, null
            )

        /** 選択画面の結果からアカウント名を取り出す。キャンセル・不正なら null。 */
        fun nameFromResult(data: Intent?): String? =
            data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim()?.takeIf { it.isNotEmpty() }

        fun encode(list: List<Entry>): String {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name)) }
            return arr.toString()
        }

        fun decode(text: String?): List<Entry> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { Entry(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 画面に出す1行。present が null のときは端末にあるかを確かめない。 */
        fun label(saved: List<Entry>, present: Set<String>?): String {
            val first = saved.firstOrNull() ?: return "Googleアカウント: 未選択"
            val removed = present != null && first.name !in present
            return if (removed) {
                "Googleアカウント: ${first.name}（端末から削除されています）"
            } else {
                "Googleアカウント: ${first.name}"
            }
        }
    }
}
