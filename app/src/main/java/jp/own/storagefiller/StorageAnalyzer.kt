package jp.own.storagefiller

import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.ArrayDeque

/**
 * /storage/emulated/0 を再帰スキャンし、拡張子・配置場所でデータ種類別に集計する。
 * 全てのファイルアクセス権限が前提。読めないディレクトリはスキップ。
 */
object StorageAnalyzer {

    const val CAT_PHOTO = "写真"
    const val CAT_VIDEO = "動画"
    const val CAT_MUSIC = "音楽"
    const val CAT_DL = "DL"
    const val CAT_APP = "アプリ"
    const val CAT_OTHER = "その他"

    val CATEGORIES = listOf(CAT_PHOTO, CAT_VIDEO, CAT_MUSIC, CAT_DL, CAT_APP, CAT_OTHER)

    data class Result(
        val totalBytes: Long,
        val freeBytes: Long,
        val categoryBytes: Map<String, Long>,
        val scannedFiles: Long
    )

    private val photoExt = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "ts", "m2ts", "wmv", "flv")
    private val musicExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")

    @Volatile
    var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * @param excludeDir ダミーデータの保存先。円グラフでは既存データと別セグメントに分けるため集計から除く
     */
    fun scan(excludeDir: File?, onProgress: (scannedFiles: Long) -> Unit): Result {
        cancelled = false
        val root = Environment.getExternalStorageDirectory()
        val stat = StatFs(root.absolutePath)
        val excludePath = excludeDir?.absolutePath
        val counts = HashMap<String, Long>().also { m -> CATEGORIES.forEach { m[it] = 0L } }
        var scanned = 0L

        // 再帰（スタック方式）。walkTopDown より例外制御しやすい
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty() && !cancelled) {
            val dir = stack.poll() ?: break
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: continue
            for (f in children) {
                if (cancelled) break
                try {
                    if (f.isDirectory) {
                        if (f.absolutePath != excludePath) stack.add(f)
                    } else {
                        val cat = categorize(f, root)
                        counts[cat] = (counts[cat] ?: 0L) + f.length()
                        scanned++
                        if (scanned % 2000L == 0L) onProgress(scanned)
                    }
                } catch (_: Exception) {
                    // 読めないファイルはスキップ
                }
            }
        }
        onProgress(scanned)
        return Result(stat.totalBytes, stat.availableBytes, counts, scanned)
    }

    private fun categorize(file: File, root: File): String {
        val path = file.absolutePath
        val rel = path.removePrefix(root.absolutePath)
        return when {
            rel.startsWith("/Android/data") || rel.startsWith("/Android/obb") -> CAT_APP
            rel.startsWith("/Download") -> CAT_DL
            file.extension.lowercase() == "apk" -> CAT_APP
            photoExt.contains(file.extension.lowercase()) -> CAT_PHOTO
            videoExt.contains(file.extension.lowercase()) -> CAT_VIDEO
            musicExt.contains(file.extension.lowercase()) -> CAT_MUSIC
            else -> CAT_OTHER
        }
    }
}
