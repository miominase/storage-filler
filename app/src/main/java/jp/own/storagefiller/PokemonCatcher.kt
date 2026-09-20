package jp.own.storagefiller

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * ポケモンのアイコン画像をランダムに1枚取ってきて、端末のギャラリーに保存する。
 * LINE のプロフィール画像を選ぶときにそのまま選べるようにするのが目的。
 *
 * 取得元: https://img.yakkun.com/poke/icon96/n{1..1022}.gif
 * いずれも 96x96・1フレームの静止画。範囲外の番号も 200 を返すので上限は決め打ちする。
 */
object PokemonCatcher {

    private const val MIN_NO = 1
    private const val MAX_NO = 1022
    /** 96px はアイコンには小さくLINE側で引き伸ばされるので、ドットを保ったまま拡大しておく */
    private const val SCALE = 4
    private const val TIMEOUT_MS = 15000
    private const val DIR_NAME = "StorageFiller"

    data class Caught(val number: Int, val image: Bitmap, val savedTo: String)

    /** @return 捕まえた1匹。通信・保存のどこかで失敗したら null */
    fun catchOne(context: Context): Caught? {
        val no = Random.nextInt(MIN_NO, MAX_NO + 1)
        val raw = download("https://img.yakkun.com/poke/icon96/n$no.gif") ?: return null
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null

        val image = enlarge(src)
        src.recycle()
        val name = "poke_%04d_%d.png".format(no, System.currentTimeMillis())
        val savedTo = save(context, image, name) ?: return null
        return Caught(no, image, savedTo)
    }

    /**
     * ドット絵なので補間なし（ニアレストネイバー）で拡大し、背景は白で塗る。
     * 元が透過GIFのため、そのまま保存するとアイコンにしたとき背景が環境依存で変わってしまう。
     */
    private fun enlarge(src: Bitmap): Bitmap {
        val w = src.width * SCALE
        val h = src.height * SCALE
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val scaled = Bitmap.createScaledBitmap(src, w, h, false)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        if (scaled != src) scaled.recycle()
        return out
    }

    private fun download(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StorageFiller")
            }
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** @return 保存先の表示用パス。失敗したら null */
    private fun save(context: Context, bitmap: Bitmap, fileName: String): String? =
        if (Build.VERSION.SDK_INT >= 29) saveViaMediaStore(context, bitmap, fileName)
        else saveViaFile(context, bitmap, fileName)

    /** Android 10 以降。MediaStore に入れるので書き込み権限は要らない */
    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, fileName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + DIR_NAME
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "Pictures/$DIR_NAME/$fileName"
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Android 9 以前。直接書いてからメディアスキャンを促さないとギャラリーに出てこない */
    private fun saveViaFile(context: Context, bitmap: Bitmap, fileName: String): String? {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            DIR_NAME
        )
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        return try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("image/png"), null
            )
            "Pictures/$DIR_NAME/$fileName"
        } catch (_: Exception) {
            file.delete()
            null
        }
    }
}
