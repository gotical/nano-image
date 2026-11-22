package ru.rybinsklab.nanoimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.util.LruCache
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Метаданные из sidecar-файла (пишет и CLI, и это приложение). */
data class ShotMeta(
    val model: String,
    val prompt: String,
    val cost: String,
    val created: String,
    val params: String
)

/** Картинка в папке .nano + её sidecar, если он есть. */
data class Shot(val file: File, val meta: ShotMeta?)

/**
 * Общая папка с результатами: /storage/emulated/0/Pictures/.nano
 *
 * Та же папка и тот же формат sidecar, что у CLI ~/bin/nano-image, поэтому
 * галерея общая: что нарисовал агент из Termux, видно в приложении, и наоборот.
 * Маркер .nomedia прячет папку от системной галереи и медиа-сканера.
 */
object Gallery {

    private val IMG_EXT = setOf("png", "jpg", "jpeg", "webp", "gif")
    private val TS = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** Кэш миниатюр: сетка галереи перечитывается при каждом возврате на вкладку. */
    private val thumbs = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun dir(ctx: Context): File = File(Store.outDir(ctx))

    /** Есть ли право писать в общую память (Android 11+ требует роль All files access). */
    fun canWriteShared(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /**
     * Создаёт каталог и .nomedia, проверяет, что туда действительно можно писать.
     * Возвращает null с причиной в `why` — молча падать при сохранении нельзя:
     * на HyperOS роль All files access выдаётся не с первого раза.
     */
    fun ensureDir(ctx: Context): Pair<File?, String> {
        val d = dir(ctx)
        if (!canWriteShared()) {
            return null to "нет доступа ко всей памяти: выдайте разрешение «Все файлы» в настройках Android"
        }
        return try {
            if (!d.exists() && !d.mkdirs()) {
                return null to "не удалось создать ${d.absolutePath}"
            }
            if (!d.isDirectory) return null to "${d.absolutePath} — не каталог"
            val marker = File(d, ".nomedia")
            if (!marker.exists()) marker.createNewFile()
            val probe = File(d, ".write-test")
            probe.writeText("ok")
            probe.delete()
            d to ""
        } catch (e: Exception) {
            null to "запись невозможна: ${e.javaClass.simpleName}"
        }
    }

    /** Расширение по magic bytes: сервер иногда отдаёт PNG с mime-типом jpeg. */
    fun extOf(b: ByteArray): String {
        fun at(i: Int): Int = if (i < b.size) b[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "jpg"
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "webp"
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "gif"
            else -> "bin"
        }
    }

    /**
     * Сохраняет серию картинок одной генерации + один sidecar на серию.
     * Имена как у CLI: YYYYMMDD-HHMMSS_модель[-N].ext
     */
    fun saveBatch(
        ctx: Context,
        model: String,
        prompt: String,
        params: Map<String, Any?>,
        images: List<ByteArray>,
        requestId: String,
        cost: Double?,
        costScope: String?
    ): Pair<List<File>, String> {
        val (d, why) = ensureDir(ctx)
        if (d == null) return emptyList<File>() to why

        val stamp = TS.format(Date())
        val slug = slugOf(model)
        val saved = ArrayList<File>()
        for ((i, bytes) in images.withIndex()) {
            val suffix = if (images.size > 1) "-${i + 1}" else ""
            val f = File(d, "${stamp}_${slug}${suffix}.${extOf(bytes)}")
            try {
                f.writeBytes(bytes)
                saved.add(f)
            } catch (e: Exception) {
                return saved to "не записался файл ${f.name}: ${e.javaClass.simpleName}"
            }
        }
        if (saved.isEmpty()) return saved to "нечего сохранять"

        if (Store.writeMeta(ctx)) {
            val side = LinkedHashMap<String, Any?>()
            side["model"] = model
            side["prompt"] = prompt
            side["n"] = images.size
            side["resolution"] = params["resolution"]
            side["aspect_ratio"] = params["aspect_ratio"]
            side["quality"] = params["quality"]
            side["seed"] = params["seed"]
            side["request_id"] = requestId
            side["cost_usd"] = cost?.toString()
            side["cost_note"] = costScope ?: "android-app"
            side["created"] = ISO.format(Date())
            side["files"] = saved.map { it.name }
            try {
                File(d, "${stamp}_${slug}.json").writeText(Js.encode(side))
            } catch (_: Exception) {
            }
        }
        return saved to ""
    }

    private fun slugOf(model: String): String {
        val sb = StringBuilder()
        for (c in model) sb.append(if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_')
        return sb.toString().take(48)
    }

    /** Список картинок в папке, новые сверху (имя начинается со времени). */
    fun list(ctx: Context): List<Shot> {
        val d = dir(ctx)
        val files = d.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.extension.lowercase(Locale.US) in IMG_EXT }
            .sortedByDescending { it.name }
            .map { Shot(it, metaFor(it)) }
    }

    /** Sidecar рядом с файлом: сначала точное имя, потом базовое без суффикса -N. */
    fun metaFor(f: File): ShotMeta? {
        val base = f.nameWithoutExtension
        for (name in listOf("$base.json", base.replace(Regex("-\\d+$"), "") + ".json")) {
            val side = File(f.parentFile, name)
            if (!side.exists()) continue
            val j = try {
                Js(side.readText()).top()
            } catch (_: Exception) {
                null
            } ?: continue
            val parts = ArrayList<String>()
            j.jstr("resolution").takeIf { it.isNotEmpty() && it != "null" }?.let { parts.add(it) }
            j.jstr("aspect_ratio").takeIf { it.isNotEmpty() && it != "null" }?.let { parts.add(it) }
            j.jstr("seed").takeIf { it.isNotEmpty() && it != "null" }?.let { parts.add("seed $it") }
            return ShotMeta(
                model = j.jstr("model"),
                prompt = j.jstr("prompt"),
                cost = j.jstr("cost_usd").takeIf { it.isNotEmpty() && it != "null" } ?: "",
                created = j.jstr("created"),
                params = parts.joinToString(" · ")
            )
        }
        return null
    }

    fun thumb(f: File, size: Int): Bitmap? {
        val key = f.absolutePath + "|" + f.length() + "|" + size
        thumbs.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= size) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        thumbs.put(key, bmp)
        return bmp
    }

    fun full(f: File): Bitmap? = BitmapFactory.decodeFile(f.absolutePath)

    /** Удаление картинки; sidecar убираем, если в серии больше не осталось файлов. */
    fun delete(f: File): Boolean {
        val side = sidecarFor(f)
        val ok = try {
            f.delete()
        } catch (_: Exception) {
            false
        }
        if (ok && side != null && side.exists()) {
            val j = try {
                Js(side.readText()).top()
            } catch (_: Exception) {
                null
            }
            val rest = (j?.jlist("files") ?: emptyList<Any?>())
                .mapNotNull { it?.toString() }
                .filter { it != f.name }
            if (rest.none { File(f.parentFile, it).exists() }) {
                runCatching { side.delete() }
            }
        }
        return ok
    }

    private fun sidecarFor(f: File): File? {
        val base = f.nameWithoutExtension
        for (name in listOf("$base.json", base.replace(Regex("-\\d+$"), "") + ".json")) {
            val side = File(f.parentFile, name)
            if (side.exists()) return side
        }
        return null
    }

    fun sizeText(ctx: Context): String {
        val files = dir(ctx).listFiles() ?: return "папка недоступна"
        val bytes = files.filter { it.isFile }.sumOf { it.length() }
        return "${files.count { it.isFile }} файлов · ${mb(bytes)}"
    }

    private fun mb(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format(Locale.US, "%.1f МБ", bytes / 1048576.0)
    }
}
