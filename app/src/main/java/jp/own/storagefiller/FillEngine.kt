package jp.own.storagefiller

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * ダミーファイル生成エンジン。
 * 5カテゴリ（Pictures/Movies/Music/Download/Apps）に均等配分し、
 * 目標容量に応じた大きめのファイル（128MB/512MB/1GB単位）を生成する。
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
        val CATEGORIES = listOf("Pictures", "Movies", "Music", "Download", "Apps")
        /** カテゴリ別のダミーファイル拡張子（円グラフの種類分類に自然反映させる） */
        val CATEGORY_EXT = mapOf(
            "Pictures" to "jpg",
            "Movies" to "mp4",
            "Music" to "mp3",
            "Download" to "bin",
            "Apps" to "bin"
        )
        private const val CHUNK_SIZE = 1024 * 1024 // 1MB
        private const val MIN_FREE_MARGIN = 8L * 1024 * 1024 // 枯渇防止マージン 8MB
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

    /** 保存先のベースディレクトリ。Android 10 (API 29) のみアプリ専用外部領域。 */
    fun baseDir(): File {
        return if (Build.VERSION.SDK_INT == 29) {
            File(context.getExternalFilesDir(null), "StorageFillTest")
        } else {
            File(Environment.getExternalStorageDirectory(), "StorageFillTest")
        }
    }

    fun start(targetBytes: Long, categories: List<String> = CATEGORIES) {
        if (isRunning || categories.isEmpty()) return
        cancelled = false
        thread = Thread { run(targetBytes, categories) }.also { it.start() }
    }

    private fun run(targetBytes: Long, categories: List<String>) {
        val base = baseDir()
        val log = FillLog(context)
        val chunk = ByteArray(CHUNK_SIZE)
        Random().nextBytes(chunk)
        val unit = unitSize(targetBytes)

        var totalWritten = 0L
        var fileCount = 0
        val startTime = SystemClock.elapsedRealtime()

        try {
            if (!base.exists() && !base.mkdirs()) {
                listener.onError("フォルダ作成失敗: ${base.absolutePath}")
                return
            }
            listener.onLog("保存先: ${base.absolutePath}")

            val perCategory = targetBytes / categories.size
            for ((catIndex, category) in categories.withIndex()) {
                if (cancelled) break
                // 最終カテゴリは端数を全て引き受ける
                var remaining =
                    if (catIndex == categories.size - 1) targetBytes - totalWritten
                    else perCategory
                val dir = File(base, category)
                if (!dir.exists() && !dir.mkdirs()) {
                    listener.onError("フォルダ作成失敗: ${dir.absolutePath}")
                    return
                }
                val ext = CATEGORY_EXT[category] ?: "bin"

                var fileIndex = 0
                while (remaining > 0 && !cancelled) {
                    // 空き容量チェック（枯渇防止）
                    val free = StatFs(base.absolutePath).availableBytes
                    if (free < MIN_FREE_MARGIN) {
                        listener.onLog("空き容量が下限に達したため停止")
                        listener.onFinished(totalWritten, fileCount, false)
                        return
                    }

                    val fileSize = minOf(unit, remaining)
                    var file = File(dir, "dummy_%03d_%dMB.%s".format(fileIndex, fileSize / CHUNK_SIZE, ext))
                    val written = writeFile(file, fileSize, chunk, startTime, totalWritten, targetBytes, fileCount)
                    totalWritten += written
                    remaining -= written
                    if (written in 1 until fileSize) {
                        // 中止時の書きかけファイル。実サイズに名前を合わせて保存する
                        val actual =
                            File(dir, "dummy_%03d_%dMB.%s".format(fileIndex, written / CHUNK_SIZE, ext))
                        if (file.renameTo(actual)) file = actual
                    }
                    if (written > 0) {
                        fileCount++
                        log.add(file.absolutePath)
                    }
                    fileIndex++
                    if (written < fileSize) break // キャンセル or 書込エラー
                }
            }
            listener.onFinished(totalWritten, fileCount, cancelled)
        } catch (e: Exception) {
            listener.onError("書込エラー: ${e.message}")
        }
    }

    /** @return 実際に書き込んだバイト数 */
    private fun writeFile(
        file: File,
        fileSize: Long,
        chunk: ByteArray,
        startTime: Long,
        writtenSoFar: Long,
        targetBytes: Long,
        fileCount: Int
    ): Long {
        var written = 0L
        try {
            FileOutputStream(file).use { out ->
                while (written < fileSize && !cancelled) {
                    val n = minOf(CHUNK_SIZE.toLong(), fileSize - written).toInt()
                    out.write(chunk, 0, n)
                    written += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS || written >= fileSize) {
                        lastProgressAt = now
                        val elapsedSec = (now - startTime) / 1000.0
                        val speed =
                            if (elapsedSec > 0) (writtenSoFar + written) / 1024.0 / 1024.0 / elapsedSec
                            else 0.0
                        listener.onProgress(writtenSoFar + written, targetBytes, speed, fileCount)
                    }
                }
                out.fd.sync()
            }
        } catch (e: Exception) {
            listener.onLog("書込中断: ${file.name} (${e.message})")
        }
        // 中止時も書きかけファイルは残す（大ボタンの「中止して保存」どおりの挙動）
        return written
    }
}
