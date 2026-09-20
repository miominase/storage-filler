package jp.own.storagefiller

import android.content.Context
import java.io.File

/**
 * アプリが作成したダミーファイルのパスを内部ストレージに記録し、
 * 全削除時にログの内容だけを削除対象にする（誤削除防止）。
 */
class FillLog(context: Context) {

    private val logFile = File(context.filesDir, "fill_log.txt")

    @Synchronized
    fun add(path: String) {
        logFile.appendText(path + "\n")
    }

    /**
     * 実在するダミーファイルのパス一覧。
     * 同名ファイルを作り直すとログに古い行が残るため、重複と実体の無いパスを除く。
     */
    fun paths(): List<String> =
        if (!logFile.exists()) emptyList()
        else logFile.readLines()
            .filter { it.isNotBlank() }
            .distinct()
            .filter { File(it).exists() }

    fun count(): Int = paths().size

    @Synchronized
    fun remove(path: String) {
        if (!logFile.exists()) return
        val remaining = logFile.readLines().filter { it.isNotBlank() && it != path }
        if (remaining.isEmpty()) {
            logFile.delete()
        } else {
            logFile.writeText(remaining.joinToString("\n") + "\n")
        }
    }

    @Synchronized
    fun clear() {
        logFile.delete()
    }
}
