package jp.own.storagefiller

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * 送信できていないチャレンジ記録の保管庫。
 *
 * 送信前に必ずここへ入れ、成功したら消す。アプリを閉じても残るよう filesDir のファイルに置く。
 * 認証失敗や入力エラーは自動再送しても直らないので「要確認」に印を付けて残す。
 *
 * 画面の回転などで Activity が作り直されると、同じファイルを指すインスタンスが同時に2つ存在しうる。
 * インスタンス単位のロック（@Synchronized）では互いを止められず、読んで・直して・書く処理が
 * 交互に走って記録が消えるため、ロックはファイルの絶対パスごとにプロセス全体で1つ共有する。
 *
 * 書き込み系（add / remove / markAttention）は端末の空き不足などで IOException を投げる。
 * UIスレッドから呼ぶ側は必ず捕まえること（このアプリは空き容量を埋めるのが仕事なので現実に起きる）。
 */
class PendingRecords(private val file: File) {

    /** 実機での使い方。ファイルの場所は filesDir 配下に固定する。 */
    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    private val lock: Any = lockFor(file)

    /**
     * 表示や再送対象の取得用。読めなければ空として扱う（表示が消えるだけで、ファイルには触れない）。
     */
    fun all(): List<ChallengeRecord> = synchronized(lock) {
        try {
            read()
        } catch (_: IOException) {
            emptyList()
        }
    }

    @Throws(IOException::class)
    fun add(record: ChallengeRecord) = synchronized(lock) {
        // 読み込み失敗を空として扱うと既存のキューを上書きしてしまうため、read() は例外をそのまま通す
        val list = read().toMutableList()
        // 同じIDが既にあれば置き換える（再送中の状態更新）
        val index = list.indexOfFirst { it.id == record.id }
        if (index >= 0) list[index] = record else list.add(record)
        write(list)
    }

    @Throws(IOException::class)
    fun remove(id: String) = synchronized(lock) {
        write(read().filter { it.id != id })
    }

    /** 自動再送では直らない失敗。内容とIDは変えずに残す。 */
    @Throws(IOException::class)
    fun markAttention(id: String, message: String) = synchronized(lock) {
        write(read().map {
            if (it.id == id) it.copy(status = ChallengeRecord.STATUS_ATTENTION, lastError = message) else it
        })
    }

    fun clearAll() = synchronized(lock) {
        file.delete()
        Unit
    }

    private fun read(): List<ChallengeRecord> =
        if (!file.exists()) emptyList() else decode(file.readText())

    /**
     * 一時ファイルに書いてから本体へ置き換える。
     * 直接 file に書くと、途中で落ちたときに空や壊れた内容が残り、キュー全体を失う。
     * 同じディレクトリ内の rename は原子的なので、置き換え中に落ちても
     * 古い内容か新しい内容のどちらかがそのまま残る。
     *
     * 一時ファイルは毎回 createTempFile で別名にする。固定名だと、万一ロックの外から
     * 2つの書き手が同時に来たときに同じ一時ファイルへ交互に書き込み、壊れたJSONが本体になりうる。
     *
     * java.nio.file.Files（Files.move）はAPI26未満では使えず、minSdkは24。
     * そのためAPI1から使える File.renameTo を使う。Android(Linux)上ではrename(2)そのもので、
     * 置き換え先が既にあっても含めて原子的に上書きされる。
     * Windowsのrename(2)相当は置き換え先が既にあるとrenameToがfalseを返すだけで
     * 上書きしないため、そのときだけ置き換え先を消してからもう一度試す。
     * Android実機では最初のrenameToが必ず成功するので、このフォールバックは通らない。
     */
    private fun write(list: List<ChallengeRecord>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        val dir = file.absoluteFile.parentFile ?: throw IOException("no parent directory for $file")
        var tmp: File? = null
        try {
            tmp = File.createTempFile(file.name, ".tmp", dir)
            tmp.writeText(encode(list))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    throw IOException("failed to replace $file")
                }
            }
        } catch (e: Exception) {
            tmp?.delete()
            throw e
        }
    }

    companion object {
        const val FILE_NAME = "pending_records.json"

        /** ファイルの絶対パスごとに1つ。どのインスタンスからでも同じロックを使う。 */
        private val locks = HashMap<String, Any>()

        private fun lockFor(file: File): Any = synchronized(locks) {
            locks.getOrPut(file.absolutePath) { Any() }
        }

        fun encode(list: List<ChallengeRecord>): String {
            val array = JSONArray()
            list.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /** 壊れたファイルで起動できなくならないよう、読めなければ空にする。 */
        fun decode(text: String): List<ChallengeRecord> = try {
            val array = JSONArray(text)
            (0 until array.length()).map { ChallengeRecord.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }

        /**
         * 自動再送を止めるべき失敗かどうか。
         * 認証・端末状態・入力・競合は送り直しても同じ結果になるため要確認にする。
         * サーバー一時エラーと通信失敗は再送対象として残す。
         */
        fun needsAttention(code: String): Boolean =
            code == "AUTH" || code == "DEVICE_NOT_ACTIVE" || code == "VALIDATION" || code == "CONFLICT"
    }
}
