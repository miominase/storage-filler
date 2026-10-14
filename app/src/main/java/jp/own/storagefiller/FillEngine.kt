package jp.own.storagefiller

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * ダミーファイル生成エンジン。
 * 選んだカテゴリ（Pictures/Movies/Music/Download/Apps）に均等配分し、
 * 目標容量に応じた大きめのファイル（128MB/512MB/1GB単位）を生成する。
 *
 * 保存先（v0.9.0〜）:
 * - 写真・動画・音楽・DL … Android 10 以降は MediaStore に登録して `<公開フォルダ>/StorageFillTest` に書く。
 *   Android 9 以下は同じ場所へ直接書いてメディアスキャンを促す
 * - APP … アプリ専用領域 `Android/data/<パッケージ>/files/StorageFillTest`（アンインストールで消える）
 */
class FillEngine(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onProgress(writtenBytes: Long, targetBytes: Long, speedMBps: Double, fileCount: Int)
        fun onLog(message: String)
        fun onFinished(writtenBytes: Long, fileCount: Int, cancelled: Boolean)
        fun onError(message: String)
    }

    companion object {
        const val APPS = "Apps"
        val CATEGORIES = listOf("Pictures", "Movies", "Music", "Download", APPS)
        /** カテゴリ別のダミーファイル拡張子（円グラフの種類分類に自然反映させる） */
        val CATEGORY_EXT = mapOf(
            "Pictures" to "jpg",
            "Movies" to "mp4",
            "Music" to "mp3",
            "Download" to "bin",
            APPS to "bin"
        )
        private val CATEGORY_MIME = mapOf(
            "Pictures" to "image/jpeg",
            "Movies" to "video/mp4",
            "Music" to "audio/mpeg",
            "Download" to "application/octet-stream"
        )
        /** 各保存先の下に作るフォルダ名 */
        const val DIR_NAME = "StorageFillTest"
        private const val CHUNK_SIZE = 1024 * 1024 // 1MB
        /** 進捗通知の最小間隔。1MBごとに通知するとUIスレッドが溢れるため間引く */
        private const val PROGRESS_INTERVAL_MS = 100L

        /**
         * 目標容量に応じた1ファイルあたりのサイズ。
         * 細かいファイルを大量に作るより、大きいファイルを少数作るほうが速く一覧も見やすい。
         */
        fun unitSize(targetBytes: Long): Long = when {
            targetBytes < 1L * 1024 * 1024 * 1024 -> 128L * CHUNK_SIZE
            targetBytes < 10L * 1024 * 1024 * 1024 -> 512L * CHUNK_SIZE
            else -> 1024L * CHUNK_SIZE
        }

        /** v0.8.2 までの保存先。新しくは書かないが、残っているダミーの削除と円グラフの除外に使う */
        fun legacyBaseDir(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

        /** 公開フォルダの種類（Environment.DIRECTORY_*）。APP は null */
        private fun publicDirType(category: String): String? = when (category) {
            "Pictures" -> Environment.DIRECTORY_PICTURES
            "Movies" -> Environment.DIRECTORY_MOVIES
            "Music" -> Environment.DIRECTORY_MUSIC
            "Download" -> Environment.DIRECTORY_DOWNLOADS
            else -> null
        }
    }

    @Volatile
    private var cancelled = false
    private var thread: Thread? = null
    private var lastProgressAt = 0L

    val isRunning: Boolean
        get() = thread?.isAlive == true

    fun cancel() {
        cancelled = true
    }

    /** カテゴリの保存先フォルダ。APP は共有ストレージが使えない（getExternalFilesDir が null）とき null */
    fun categoryDir(category: String): File? {
        val type = publicDirType(category)
        return if (type == null) {
            context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(type), DIR_NAME)
        }
    }

    /** ダミーを置く（置いていた）フォルダ全部。円グラフの走査から除く */
    fun dummyDirs(): List<File> = CATEGORIES.mapNotNull { categoryDir(it) } + legacyBaseDir()

    private fun usesMediaStore(category: String): Boolean =
        Build.VERSION.SDK_INT >= 29 && publicDirType(category) != null

    fun start(targetBytes: Long, categories: List<String> = CATEGORIES) {
        if (isRunning || categories.isEmpty()) return
        cancelled = false
        thread = Thread { run(targetBytes, categories) }.also { it.start() }
    }

    private fun run(targetBytes: Long, categories: List<String>) {
        val log = FillLog(context)
        val chunk = ByteArray(CHUNK_SIZE)
        Random().nextBytes(chunk)
        val unit = unitSize(targetBytes)
        val stamp = FillPlan.stamp(System.currentTimeMillis())
        val root = Environment.getExternalStorageDirectory().absolutePath

        var totalWritten = 0L
        var fileCount = 0
        var seq = 0
        val startTime = SystemClock.elapsedRealtime()

        try {
            listener.onLog("保存先: 各フォルダの $DIR_NAME（APP はアプリ専用領域）")

            val perCategory = targetBytes / categories.size
            for ((catIndex, category) in categories.withIndex()) {
                if (cancelled) break
                // 最終カテゴリは端数を全て引き受ける
                var remaining =
                    if (catIndex == categories.size - 1) targetBytes - totalWritten
                    else perCategory
                val dir = categoryDir(category)
                if (dir == null) {
                    // APP でアプリ専用領域が取れない（共有ストレージが使えない）とき
                    listener.onError("フォルダ作成失敗: アプリ専用領域を取得できません")
                    return
                }
                if (!usesMediaStore(category) && !dir.exists() && !dir.mkdirs()) {
                    listener.onError("フォルダ作成失敗: ${dir.absolutePath}")
                    return
                }
                val ext = CATEGORY_EXT[category] ?: "bin"

                while (remaining > 0 && !cancelled) {
                    // 書き始める時点で、書き終えても 200MB 残る大きさにする
                    val free = StatFs(root).availableBytes
                    val fileSize = FillPlan.nextFileSize(unit, remaining, free)
                    if (fileSize <= 0) {
                        listener.onLog("空き容量が下限（200MB）に達したため停止")
                        listener.onFinished(totalWritten, fileCount, false)
                        return
                    }

                    seq++
                    val name = FillPlan.fileName(stamp, seq, ext)
                    val progress = Progress(startTime, totalWritten, targetBytes, fileCount)
                    val (written, entry) =
                        if (usesMediaStore(category)) writeMedia(category, name, fileSize, chunk, progress)
                        else writeFile(category, File(dir, name), fileSize, chunk, progress)
                    totalWritten += written
                    remaining -= written
                    if (entry != null) {
                        fileCount++
                        log.add(entry)
                    }
                    if (written < fileSize) break // キャンセル or 書込エラー
                }
            }
            listener.onFinished(totalWritten, fileCount, cancelled)
        } catch (e: Exception) {
            listener.onError("書込エラー: ${e.message}")
        }
    }

    /** 進捗通知に使う、このファイルを書き始めた時点の値 */
    private class Progress(
        val startTime: Long,
        val writtenSoFar: Long,
        val targetBytes: Long,
        val fileCount: Int
    )

    /** ファイルとして直接書く（APP、または Android 9 以下の公開フォルダ） */
    private fun writeFile(
        category: String,
        file: File,
        fileSize: Long,
        chunk: ByteArray,
        progress: Progress
    ): Pair<Long, FillPlan.LogEntry?> {
        val written = try {
            FileOutputStream(file).use { pump(it, file.name, fileSize, chunk, progress) }
        } catch (e: Exception) {
            listener.onLog("書込失敗: ${file.name} (${e.message})")
            0L
        }
        if (written <= 0) {
            file.delete()
            return 0L to null
        }
        // 中止時も書きかけファイルは残す（大ボタンの「中止して保存」どおりの挙動）
        if (category != APPS) {
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf(CATEGORY_MIME[category]), null
            )
        }
        return written to FillPlan.LogEntry(category, file.absolutePath, null)
    }

    /**
     * MediaStore に登録して書く（Android 10 以降の写真・動画・音楽・DL）。
     * 書き終わるまでは IS_PENDING=1 で他のアプリから見えないようにし、終わったら公開する。
     */
    private fun writeMedia(
        category: String,
        name: String,
        fileSize: Long,
        chunk: ByteArray,
        progress: Progress
    ): Pair<Long, FillPlan.LogEntry?> {
        val resolver = context.contentResolver
        val relativePath = publicDirType(category) + "/" + DIR_NAME
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, CATEGORY_MIME[category])
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collectionUri(category), values)
        } catch (e: Exception) {
            listener.onLog("MediaStore 登録失敗: $name (${e.message})")
            null
        } ?: return 0L to null

        var written = 0L
        try {
            val pfd = resolver.openFileDescriptor(uri, "w")
            if (pfd == null) {
                listener.onLog("書込先を開けません: $name")
            } else {
                ParcelFileDescriptor.AutoCloseOutputStream(pfd).use {
                    written = pump(it, name, fileSize, chunk, progress)
                }
            }
        } catch (e: Exception) {
            listener.onLog("書込失敗: $name (${e.message})")
        }
        if (written <= 0) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            return 0L to null
        }
        // 中止時も書きかけを公開して残す
        try {
            resolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
        } catch (e: Exception) {
            listener.onLog("公開失敗: $name (${e.message})")
        }
        val path = queryPath(uri)
            ?: File(Environment.getExternalStorageDirectory(), "$relativePath/$name").absolutePath
        return written to FillPlan.LogEntry(category, path, uri.toString())
    }

    private fun collectionUri(category: String): Uri = when (category) {
        "Pictures" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        "Movies" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "Music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    /**
     * 公開後の実ファイルのパス。名前が重なると MediaStore が付け替えることがあるので問い合わせる。
     * DATA 列は非推奨だが、全ファイルアクセス／レガシー保存のこのアプリでは読める
     */
    @Suppress("DEPRECATION")
    private fun queryPath(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    /** 1MB ずつ書いて同期する。@return 実際に書き込んだバイト数 */
    private fun pump(
        out: FileOutputStream,
        name: String,
        fileSize: Long,
        chunk: ByteArray,
        p: Progress
    ): Long {
        var written = 0L
        try {
            while (written < fileSize && !cancelled) {
                val n = minOf(CHUNK_SIZE.toLong(), fileSize - written).toInt()
                out.write(chunk, 0, n)
                written += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || written >= fileSize) {
                    lastProgressAt = now
                    val elapsedSec = (now - p.startTime) / 1000.0
                    val speed =
                        if (elapsedSec > 0) (p.writtenSoFar + written) / 1024.0 / 1024.0 / elapsedSec
                        else 0.0
                    listener.onProgress(p.writtenSoFar + written, p.targetBytes, speed, p.fileCount)
                }
            }
            out.fd.sync()
        } catch (e: Exception) {
            listener.onLog("書込中断: $name (${e.message})")
        }
        return written
    }

    // ---- 削除 ----

    /**
     * 作成ログの1件を消す。URI があれば MediaStore 経由、消せなければファイルとして消す。
     * @return 実体が無くなったら true
     */
    fun delete(entry: FillPlan.LogEntry): Boolean {
        val file = File(entry.path)
        entry.uri?.let { uri ->
            try {
                context.contentResolver.delete(Uri.parse(uri), null, null)
            } catch (_: Exception) {
                // 再インストール後など、持ち主でなくなった項目は消せない。下でファイルとして消す
            }
        }
        if (file.exists() && file.delete()) {
            // ファイルとして消したときは MediaStore の行が残ることがあるので反映させる
            val appDir = categoryDir(APPS)
            if (appDir == null || !file.absolutePath.startsWith(appDir.absolutePath)) {
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        }
        return !file.exists()
    }

    /** 全削除のあと、空になった保存先フォルダ（旧フォルダとその下のカテゴリフォルダも）を消す */
    fun removeEmptyDirs() {
        for (dir in dummyDirs()) {
            // 旧フォルダと v0.8.2 の Android 10 用フォルダはカテゴリ別のサブフォルダを持っていた
            for (category in CATEGORIES) File(dir, category).delete()
            dir.delete()
        }
    }
}
