package jp.own.storagefiller

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * ダウンロードしたAPKをインストーラーに渡すための最小限の ContentProvider。
 *
 * API 24 以降は file:// をアプリ外に渡すと FileUriExposedException になるため content:// が要る。
 * 通常は androidx の FileProvider を使うが、このアプリは依存ライブラリを持たない方針なので自前で用意する。
 *
 * 公開するのは cacheDir/update 配下だけ。他のパスを要求されても拒否する。
 */
class ApkProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY_SUFFIX = ".apkprovider"

        fun uriFor(context: android.content.Context, file: File): Uri =
            Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX/${file.name}")
    }

    private fun updateDir(): File = File(ctx().cacheDir, "update")

    private fun ctx() = context ?: throw IllegalStateException("no context")

    /** パス指定で外に出られないよう、ファイル名だけを見て配下に限定する */
    private fun resolve(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        val f = File(updateDir(), name)
        return if (f.exists() && f.parentFile == updateDir()) f else null
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** インストーラーは表示名とサイズを問い合わせてくる */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    // 読み取り専用。書き込み系は一切受け付けない
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
