package ru.rybinsklab.nanoimage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Отдаёт наружу (в «Поделиться») файлы из папки .nano.
 *
 * Почему свой, а не androidx FileProvider: сборка идёт без Gradle и androidx,
 * а Uri.fromFile на targetSdk 34 бросает FileUriExposedException — получатель
 * не сможет прочитать файл. Провайдер не экспортируется, наружу уходит только
 * один uri с временным правом чтения, и только файлы из нашей папки.
 */
class ShareProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String {
        val name = uri.lastPathSegment ?: return "application/octet-stream"
        return when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (mode != "r") return null
        val name = uri.lastPathSegment ?: return null
        val root = Gallery.dir(ctx)
        val f = File(root, name)
        // Никаких ../ наружу из .nano: проверяем, что канонический путь внутри папки.
        return try {
            val fc = f.canonicalFile
            val rc = root.canonicalFile
            if (!fc.path.startsWith(rc.path + File.separator)) return null
            if (!fc.exists()) return null
            ParcelFileDescriptor.open(fc, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val AUTHORITY = "ru.rybinsklab.nanoimage.files"

        fun uriFor(ctx: Context, f: File): Uri =
            Uri.parse("content://$AUTHORITY/" + Uri.encode(f.name))
    }
}
