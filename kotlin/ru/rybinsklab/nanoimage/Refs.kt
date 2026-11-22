package ru.rybinsklab.nanoimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Референсы для image-to-image.
 *
 * Выбранное через системный диалог фото живёт по content:// uri, право на него
 * временное, поэтому сразу делаем свою копию в приватном каталоге приложения
 * (filesDir/refs) и в запрос кладём её как data URL. Заодно уменьшаем длинную
 * сторону до 1024px: провайдер всё равно сжимает вход, а запрос с 12 МБ
 * оригинала по мобильной сети шёл бы минуты.
 */
object Refs {

    private const val MAX_EDGE = 1024

    /** Читает выбранный uri и кладёт сжатую копию в filesDir/refs. */
    fun import(ctx: Context, uri: Uri): Pair<File?, String> {
        val raw = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return null to "не удалось прочитать файл"

        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            ?: return null to "это не картинка (или формат не поддерживается)"

        return try {
            val longest = maxOf(src.width, src.height)
            val bmp = if (longest > MAX_EDGE) {
                val k = MAX_EDGE.toFloat() / longest
                Bitmap.createScaledBitmap(
                    src, (src.width * k).toInt().coerceAtLeast(1),
                    (src.height * k).toInt().coerceAtLeast(1), true
                )
            } else src

            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            val f = File(Store.refsDir(ctx), "ref-${System.currentTimeMillis()}-${raw.size / 1024}.jpg")
            f.writeBytes(out.toByteArray())
            f to "${bmp.width}×${bmp.height}, ${out.size() / 1024} КБ"
        } catch (e: Exception) {
            null to "ошибка обработки: ${e.javaClass.simpleName}"
        }
    }

    fun remove(ctx: Context, f: File) {
        runCatching { f.delete() }
    }
}
