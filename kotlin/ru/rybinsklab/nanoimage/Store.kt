package ru.rybinsklab.nanoimage

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Один провайдер Image API: как его зовёт пользователь, где он живёт и чем
 * авторизуемся. Приложение умеет держать несколько провайдеров и работает
 * с тем, который выбран в настройках.
 *
 * Ожидается API в формате nano-gpt: каталог /api/v1/images/models, генерация
 * POST /api/v1/images, дневная квота /api/subscription/v1/usage (её может не
 * быть — тогда все модели считаются платными).
 */
data class Prov(
    val id: String,
    val name: String,
    val baseUrl: String,
    val key: String,
    /** Модели, которые этот провайдер отдаёт бесплатно (покрывает подписка). */
    val allow: List<String>
) {
    /**
     * Корень API. Адрес вводят по-разному: кто-то копирует из документации
     * «https://api.example.com/api/v1», кто-то просто хост. Хвост /api/v1 и
     * /v1 срезаем, чтобы пути не удваивались.
     */
    val root: String
        get() = baseUrl.trim().trimEnd('/')
            .removeSuffix("/api/v1").removeSuffix("/v1")
            .trimEnd('/')

    val usable: Boolean get() = root.startsWith("http") && key.isNotEmpty()

    /** Ключ для показа: целиком не светим нигде, даже в настройках. */
    fun mask(): String = when {
        key.isEmpty() -> "ключ не задан"
        key.length <= 8 -> "•".repeat(key.length)
        else -> key.take(7) + "…" + key.takeLast(4)
    }
}

/**
 * Всё, что приложение помнит между запусками. Только приватный каталог
 * приложения (SharedPreferences) — API-ключи никуда не уходят: ни в логи,
 * ни в общую память, ни в sidecar-файлы рядом с картинками.
 */
object Store {

    private const val PREFS = "nanoimage"

    /** Провайдер, который приложение знает «из коробки». */
    const val NANO_ID = "nano-gpt"
    const val NANO_NAME = "nano-gpt"
    const val NANO_URL = "https://api.nano-gpt.com"

    /**
     * Модели, которые покрывает подписка Pro nano-gpt (100 картинок в день, cost 0).
     *
     * Данные на 2026-11-22. Источник — SUBSCRIPTION_IMAGE_ALLOWLIST в веб-бандле
     * nano-gpt (/_next/static/chunks/13zkwmuuhs6bg.js, функция
     * isImageModelIncludedInSubscriptionUI), подтверждено замером: генерация
     * z-image-turbo вернула cost 0 и сдвинула dailyImages.used, а шесть других
     * моделей списали деньги с баланса и счётчик не тронули.
     *
     * Если nano-gpt поменяет список — он переопределяется в настройках
     * провайдера без пересборки APK. У своего провайдера список свой
     * (пустой = подписки нет, всё платно).
     */
    val DEFAULT_ALLOWLIST = listOf("qwen-image", "chroma", "hidream", "z-image-turbo")

    const val DEFAULT_MODEL = "z-image-turbo"
    const val DEFAULT_DIR = "/storage/emulated/0/Pictures/.nano"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- провайдеры ----------

    /**
     * Список провайдеров. Первый вызов после обновления со старой версии
     * переносит одиночный ключ (prefs api_key + sub_allowlist) в провайдер
     * nano-gpt, чтобы ничего не потерялось и ключ не пришлось вводить заново.
     */
    fun providers(ctx: Context): List<Prov> {
        val raw = sp(ctx).getString("providers_json", "") ?: ""
        val parsed = parseProviders(raw)
        if (parsed.isNotEmpty()) return normalizeActive(ctx, parsed)

        val legacyKey = (sp(ctx).getString("api_key", "") ?: "").trim()
        val legacyAllow = splitList(sp(ctx).getString("sub_allowlist", "") ?: "")
        val nano = Prov(
            id = NANO_ID,
            name = NANO_NAME,
            baseUrl = NANO_URL,
            key = legacyKey,
            allow = if (legacyAllow.isEmpty()) DEFAULT_ALLOWLIST else legacyAllow
        )
        save(ctx, listOf(nano), NANO_ID)

        // Кэш каталога старой версии переезжает в per-provider имя файла.
        try {
            val old = File(ctx.filesDir, "models.json")
            if (old.exists()) old.renameTo(cacheFile(ctx, NANO_ID))
            val ts = sp(ctx).getLong("models_ts", 0L)
            if (ts > 0L) sp(ctx).edit().putLong(tsKey(NANO_ID), ts).remove("models_ts").apply()
        } catch (_: Exception) {
        }
        return listOf(nano)
    }

    /**
     * Активный id обязан существовать в списке, иначе active() молча откатится
     * к первому провайдеру, а per-provider кэш уедет в файл несуществующего id
     * и отравит его чужим каталогом. Битый id чиним сразу.
     */
    private fun normalizeActive(ctx: Context, list: List<Prov>): List<Prov> {
        val id = sp(ctx).getString("active_provider", "") ?: ""
        if (id.isNotEmpty() && list.none { it.id == id }) {
            sp(ctx).edit().putString("active_provider", list.first().id).apply()
        }
        return list
    }

    fun activeId(ctx: Context): String {
        val id = sp(ctx).getString("active_provider", "") ?: ""
        return id.ifEmpty { NANO_ID }
    }

    /** Активный провайдер. Список никогда не бывает пустым — nano-gpt создаётся сам. */
    fun active(ctx: Context): Prov {
        val list = providers(ctx)
        return list.firstOrNull { it.id == activeId(ctx) } ?: list.first()
    }

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString("active_provider", id).apply()
    }

    /**
     * Добавить или обновить провайдер и сделать его активным. Пустой id
     * означает «новый»: подбираем читаемый идентификатор из имени.
     */
    fun upsert(ctx: Context, p: Prov): Prov {
        val list = providers(ctx).toMutableList()
        val id = p.id.ifEmpty { uniqueId(list, p.name) }
        val saved = p.copy(
            id = id,
            name = p.name.trim().ifEmpty { hostOf(p.baseUrl) },
            baseUrl = p.baseUrl.trim(),
            key = p.key.trim(),
            allow = p.allow
        )
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = saved else list.add(saved)
        save(ctx, list, id)
        return saved
    }

    /**
     * Удалить провайдер. Единственный удалить нельзя: приложению нужен активный,
     * иначе некуда вводить ключ.
     */
    fun delete(ctx: Context, id: String): Boolean {
        val list = providers(ctx).toMutableList()
        if (list.size <= 1) return false
        if (list.none { it.id == id }) return false
        list.removeAll { it.id == id }
        val active = if (activeId(ctx) == id) list.first().id else activeId(ctx)
        save(ctx, list, active)
        runCatching { cacheFile(ctx, id).delete() }
        sp(ctx).edit().remove(tsKey(id)).apply()
        return true
    }

    private fun save(ctx: Context, list: List<Prov>, activeId: String) {
        sp(ctx).edit()
            .putString("providers_json", Js.encode(list.map { p ->
                linkedMapOf<String, Any?>(
                    "id" to p.id,
                    "name" to p.name,
                    "base" to p.baseUrl,
                    "key" to p.key,
                    "allow" to p.allow
                )
            }))
            .putString("active_provider", activeId)
            .apply()
    }

    private fun parseProviders(raw: String): List<Prov> {
        if (raw.isBlank()) return emptyList()
        return try {
            val top = Js(raw).top() as? List<*> ?: return emptyList()
            val out = ArrayList<Prov>()
            for (el in top) {
                val m = el as? Map<*, *> ?: continue
                val id = m.jstr("id").trim()
                if (id.isEmpty()) continue
                out.add(Prov(
                    id = id,
                    name = m.jstr("name").ifEmpty { id },
                    baseUrl = m.jstr("base").ifEmpty { NANO_URL },
                    key = m.jstr("key"),
                    allow = (m.jlist("allow") ?: emptyList())
                        .map { it?.toString()?.trim() ?: "" }
                        .filter { it.isNotEmpty() }
                ))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun splitList(raw: String): List<String> =
        raw.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Идентификатор нового провайдера: латиница и цифры из имени, иначе «prov». */
    private fun uniqueId(list: List<Prov>, name: String): String {
        val base = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifEmpty { "prov" }
        var id = base
        var n = 2
        while (list.any { it.id == id }) {
            id = "$base-$n"
            n++
        }
        return id
    }

    private fun hostOf(url: String): String =
        url.trim().removePrefix("https://").removePrefix("http://")
            .trimEnd('/').substringBefore('/').ifEmpty { "провайдер" }

    // ---------- ключ активного провайдера ----------

    fun apiKey(ctx: Context): String = active(ctx).key

    fun setApiKey(ctx: Context, v: String) {
        val list = providers(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == activeId(ctx) }
        if (i < 0) return
        list[i] = list[i].copy(key = v.trim())
        save(ctx, list, list[i].id)
    }

    fun configured(ctx: Context): Boolean = apiKey(ctx).isNotEmpty()

    /** Ключ для показа в настройках: только хвост, целиком не светим. */
    fun maskedKey(ctx: Context): String = active(ctx).mask()

    // ---------- подписочные модели ----------

    fun allowlist(ctx: Context): Set<String> = active(ctx).allow.toSet()

    fun setAllowlist(ctx: Context, v: String) {
        val list = providers(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == activeId(ctx) }
        if (i < 0) return
        list[i] = list[i].copy(allow = splitList(v))
        save(ctx, list, list[i].id)
    }

    fun allowlistRaw(ctx: Context): String = active(ctx).allow.joinToString(", ")

    fun isSub(ctx: Context, modelId: String): Boolean = modelId in allowlist(ctx)

    // ---------- выбранная модель и параметры ----------

    fun model(ctx: Context): String =
        sp(ctx).getString("gen_model", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(ctx: Context, v: String) {
        sp(ctx).edit().putString("gen_model", v).apply()
    }

    fun resolution(ctx: Context): String = sp(ctx).getString("gen_resolution", "") ?: ""
    fun setResolution(ctx: Context, v: String) {
        sp(ctx).edit().putString("gen_resolution", v).apply()
    }

    fun aspect(ctx: Context): String = sp(ctx).getString("gen_aspect", "") ?: ""
    fun setAspect(ctx: Context, v: String) {
        sp(ctx).edit().putString("gen_aspect", v).apply()
    }

    fun count(ctx: Context): Int = sp(ctx).getInt("gen_n", 1)
    fun setCount(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("gen_n", v).apply()
    }

    fun seed(ctx: Context): String = sp(ctx).getString("gen_seed", "") ?: ""
    fun setSeed(ctx: Context, v: String) {
        sp(ctx).edit().putString("gen_seed", v).apply()
    }

    fun prompt(ctx: Context): String = sp(ctx).getString("gen_prompt", "") ?: ""
    fun setPrompt(ctx: Context, v: String) {
        sp(ctx).edit().putString("gen_prompt", v).apply()
    }

    // ---------- сохранение ----------

    fun outDir(ctx: Context): String =
        sp(ctx).getString("out_dir", DEFAULT_DIR) ?: DEFAULT_DIR

    fun setOutDir(ctx: Context, v: String) {
        sp(ctx).edit().putString("out_dir", v.trim().ifEmpty { DEFAULT_DIR }).apply()
    }

    fun writeMeta(ctx: Context): Boolean = sp(ctx).getBoolean("write_meta", true)
    fun setWriteMeta(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("write_meta", v).apply()
    }

    // ---------- кэш каталога (свой у каждого провайдера) ----------

    /** Кэш активного провайдера: id берём из active(), а не из сырого prefs. */
    fun cacheFile(ctx: Context): File = cacheFile(ctx, active(ctx).id)

    fun cacheFile(ctx: Context, id: String): File = File(ctx.filesDir, "models-$id.json")

    private fun tsKey(id: String) = "models_ts_$id"

    fun cacheTs(ctx: Context): Long = sp(ctx).getLong(tsKey(active(ctx).id), 0L)
    fun setCacheTs(ctx: Context, v: Long) {
        sp(ctx).edit().putLong(tsKey(active(ctx).id), v).apply()
    }

    /** Файл выбранных референсов (копия выбранного фото в приватном каталоге). */
    fun refsDir(ctx: Context): File = File(ctx.filesDir, "refs").apply { mkdirs() }

    fun clearRefs(ctx: Context) {
        refsDir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
    }
}
