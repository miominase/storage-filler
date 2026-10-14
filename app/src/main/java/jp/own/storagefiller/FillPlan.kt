package jp.own.storagefiller

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 容量埋めの純ロジック（Android API に依存しない部分）。JVM の単体テストで確かめる。
 * ファイル名・作成ログの1行・残り時間・埋め量の上限を扱う。
 */
object FillPlan {

    /** 書き込み後も必ず残す空き容量。これを割りそうなら書かない（v0.9.0〜 200MB 固定） */
    const val RESERVE_BYTES = 200L * 1024 * 1024

    private const val MB = 1024.0 * 1024.0

    // ---- 埋め量の上限 ----

    /** 埋めてよい量 = 空き − 200MB（0未満は0） */
    fun usableBytes(freeBytes: Long): Long = (freeBytes - RESERVE_BYTES).coerceAtLeast(0)

    /** 割合指定（30/60/70% など）の目標。「空き − 200MB」に対する割合 */
    fun ratioTarget(freeBytes: Long, ratio: Double): Long =
        (usableBytes(freeBytes) * ratio).toLong()

    /** 固定サイズ指定の目標。「空き − 200MB」を上限に切る */
    fun capTarget(requested: Long, freeBytes: Long): Long =
        requested.coerceAtMost(usableBytes(freeBytes)).coerceAtLeast(0)

    /** 次に作るファイルのサイズ。書き始める時点で下限を割らない大きさにする */
    fun nextFileSize(unit: Long, remaining: Long, freeBytes: Long): Long =
        minOf(unit, remaining, usableBytes(freeBytes)).coerceAtLeast(0)

    // ---- ファイル名 ----

    /** 埋め開始時刻の文字列（yyyyMMdd_HHmmss_SSS、端末のタイムゾーン）。同じ秒に2回埋めても名前が重ならないようミリ秒まで入れる */
    fun stamp(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply { this.timeZone = timeZone }
            .format(millis)

    /** dummy_yyyyMMdd_HHmmss_SSS_NNN.拡張子。NNN は1回の埋め込みの中の通し番号 */
    fun fileName(stamp: String, seq: Int, ext: String): String =
        "dummy_%s_%03d.%s".format(Locale.US, stamp, seq, ext)

    // ---- 作成ログの1行 ----

    /**
     * 作成ログの1件。
     * @param category カテゴリのフォルダ名（Pictures など）。旧形式の行では null
     * @param uri MediaStore の content URI。MediaStore に登録していなければ null
     */
    data class LogEntry(val category: String?, val path: String, val uri: String?)

    /** `カテゴリ<TAB>パス<TAB>URI`（URI が無ければ空） */
    fun encode(entry: LogEntry): String =
        listOf(entry.category ?: "", entry.path, entry.uri ?: "").joinToString("\t")

    /** 新形式と旧形式（パスだけ）の両方を読む。空行は null */
    fun decode(line: String): LogEntry? {
        if (line.isBlank()) return null
        if (!line.contains('\t')) return LogEntry(null, line.trim(), null)
        val parts = line.split('\t')
        val path = parts.getOrNull(1)?.trim().orEmpty()
        if (path.isEmpty()) return null
        return LogEntry(
            parts[0].trim().ifEmpty { null },
            path,
            parts.getOrNull(2)?.trim()?.ifEmpty { null }
        )
    }

    // ---- 残り時間 ----

    /** 残り秒数（切り上げ）。速度が 0 以下なら null */
    fun etaSeconds(writtenBytes: Long, targetBytes: Long, speedMBps: Double): Long? {
        if (speedMBps <= 0.0 || speedMBps.isNaN()) return null
        val remaining = (targetBytes - writtenBytes).coerceAtLeast(0)
        return Math.ceil(remaining / MB / speedMBps).toLong()
    }

    /** 「あと約◯分◯秒」。速度が 0 以下なら null */
    fun etaText(writtenBytes: Long, targetBytes: Long, speedMBps: Double): String? {
        val sec = etaSeconds(writtenBytes, targetBytes, speedMBps) ?: return null
        return "あと約${sec / 60}分${sec % 60}秒"
    }
}
