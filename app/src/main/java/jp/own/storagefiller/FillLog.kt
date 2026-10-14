package jp.own.storagefiller

import android.content.Context
import java.io.File

/**
 * アプリが作成したダミーファイルを内部ストレージに記録し、
 * 全削除時にログの内容だけを削除対象にする（誤削除防止）。
 *
 * 1行の形式は `カテゴリ<TAB>パス<TAB>content URI`（v0.9.0〜。URI は MediaStore のときだけ）。
 * v0.8.2 までのパスだけの行も読み、旧フォルダに残ったダミーも一覧・削除できるようにする。
 */
class FillLog(context: Context) {

    private val logFile = File(context.filesDir, "fill_log.txt")

    @Synchronized
    fun add(entry: FillPlan.LogEntry) {
        logFile.appendText(FillPlan.encode(entry) + "\n")
    }

    /**
     * 実在するダミーファイルの一覧。
     * 同名ファイルを作り直すとログに古い行が残るため、同じパスは後の行を残し、実体の無いものを除く。
     */
    fun entries(): List<FillPlan.LogEntry> =
        if (!logFile.exists()) emptyList()
        else logFile.readLines()
            .mapNotNull { FillPlan.decode(it) }
            .asReversed()
            .distinctBy { it.path }
            .asReversed()
            .filter { File(it.path).exists() }

    @Synchronized
    fun remove(path: String) {
        if (!logFile.exists()) return
        val remaining = logFile.readLines().filter { line ->
            val e = FillPlan.decode(line)
            e != null && e.path != path
        }
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
