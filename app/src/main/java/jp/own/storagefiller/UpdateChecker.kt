package jp.own.storagefiller

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases を見て新しいAPKがあるか調べ、あればダウンロードする。
 *
 * 公開リポジトリなので認証は不要。未認証のGitHub APIは 60回/時（IP単位）なので、
 * 手動のボタン操作で叩く前提なら問題にならない。
 */
object UpdateChecker {

    data class Release(
        val version: String,      // 例 "0.5.0"（タグの先頭の v は取り除く）
        val apkUrl: String,
        val sizeBytes: Long,
        val notes: String
    )

    private const val TIMEOUT_MS = 15000

    /** @return 最新リリース。見つからなければ null */
    fun fetchLatest(repo: String): Release? {
        val json = httpGet("https://api.github.com/repos/$repo/releases/latest") ?: return null
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return Release(
                    version = o.optString("tag_name").removePrefix("v"),
                    apkUrl = a.optString("browser_download_url"),
                    sizeBytes = a.optLong("size"),
                    notes = o.optString("name")
                )
            }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "StorageFiller")
            }
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * APKを端末にダウンロードする。保存先はアプリ専用領域なので権限は要らない。
     * @return 保存したファイル。失敗したら null
     */
    fun download(
        context: Context,
        release: Release,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 前回の残骸を消してから落とす
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "StorageFiller-v${release.version}.apk")

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StorageFiller")
            }
            if (conn.responseCode != 200) return null
            val total = if (conn.contentLength > 0) conn.contentLength.toLong() else release.sizeBytes

            conn.inputStream.use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                    fos.fd.sync()
                }
            }
            return if (out.length() > 0) out else null
        } catch (_: Exception) {
            out.delete()
            return null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * "0.5.0" 同士を数値で比較する。文字列比較だと 0.10.0 < 0.9.0 になってしまうため。
     * @return latest のほうが新しければ true
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
