package ru.rybinsklab.nanoimage

import android.content.Context
import android.util.Base64
import java.io.File

/** Дневная квота подписки: GET /api/subscription/v1/usage. */
data class Quota(
    val used: Int,
    val remaining: Int,
    val limit: Int,
    val resetAt: Long,
    val allowOverage: Boolean,
    val active: Boolean,
    val recommendedMode: String,
    val periodEnd: String
) {
    val exhausted: Boolean get() = remaining in 0 until limit && !allowOverage
}

/** Что просим нарисовать. */
data class GenReq(
    val model: String,
    val prompt: String,
    val n: Int,
    val resolution: String?,
    val aspect: String?,
    val seed: Long?,
    val refs: List<ByteArray>,
    val paygo: Boolean
)

/** Результат вызова генерации. */
data class GenOut(
    val ok: Boolean,
    val images: List<ByteArray>,
    val requestId: String,
    val code: Int,
    val error: String,
    val cost: Double?,
    val costScope: String?,
    val raw: String
)

/**
 * Вызовы Image API активного провайдера (по умолчанию nano-gpt, но адрес и
 * ключ берутся из настроек — провайдеров может быть несколько). Всё, что
 * нужно приложению:
 *
 *   GET  /api/v1/images/models             каталог (264 модели, ~350 КБ) → кэш 6 ч
 *   GET  /api/subscription/v1/usage        дневная квота картинок (может не быть)
 *   POST /api/v1/images                    генерация (ответ — base64 в data[i].b64_json)
 *   GET  /api/v1/usage/requests/{X-Request-ID}  фактическая цена запроса (хранится 24 ч)
 *
 * Точная цена после запроса важнее прайса из каталога: именно она показывает,
 * покрыла генерацию подписка (0) или деньги списались с баланса. Если
 * провайдер не отдаёт квоту (404), приложение считает все модели платными
 * и спрашивает подтверждение перед каждой генерацией.
 */
object Api {

    private const val CACHE_TTL_MS = 6 * 3600_000L

    private val B64_KEYS = setOf("b64_json", "b64", "base64", "image_base64", "imageBase64")
    private val URL_KEYS = setOf("url", "image_url", "src", "download_url")

    // ---------- каталог моделей ----------

    /** Возвращает список моделей и текст проблемы (или null, если всё хорошо). */
    fun catalog(ctx: Context, force: Boolean = false): Pair<List<ImgModel>, String?> {
        val cf = Store.cacheFile(ctx)
        val age = System.currentTimeMillis() - Store.cacheTs(ctx)

        if (!force && cf.exists() && age < CACHE_TTL_MS) {
            val cached = readCache(cf)
            if (cached.isNotEmpty()) return cached to null
        }

        val p = Store.active(ctx)
        if (p.key.isEmpty()) {
            val cached = readCache(cf)
            return if (cached.isNotEmpty()) cached to "нет API-ключа — показан кэш"
            else emptyList<ImgModel>() to "«${p.name}»: не задан API-ключ — откройте настройки"
        }

        val r = Net.get("${p.root}/api/v1/images/models", p.key, 90_000)
        if (!r.ok) {
            val cached = readCache(cf)
            return cached to ("«${p.name}»: каталог не загрузился: " + r.message() +
                if (cached.isNotEmpty()) " — показан кэш" else "")
        }
        val list = ImgModel.parseList(r.json())
        if (list.isEmpty()) return readCache(cf) to "пустой ответ каталога"

        try {
            cf.writeText(r.body)
            Store.setCacheTs(ctx, System.currentTimeMillis())
        } catch (_: Exception) {
        }
        return list to null
    }

    private fun readCache(cf: File): List<ImgModel> {
        if (!cf.exists()) return emptyList()
        return try {
            ImgModel.parseList(Js(cf.readText()).top())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun cacheAgeText(ctx: Context): String {
        val ts = Store.cacheTs(ctx)
        if (ts <= 0L) return "каталог не загружен"
        val min = (System.currentTimeMillis() - ts) / 60_000
        return when {
            min < 1 -> "каталог обновлён только что"
            min < 60 -> "каталог обновлён $min мин назад"
            else -> "каталог обновлён ${min / 60} ч назад"
        }
    }

    // ---------- квота подписки ----------

    fun quota(ctx: Context): Quota? {
        val p = Store.active(ctx)
        if (p.key.isEmpty()) return null
        val r = Net.get("${p.root}/api/subscription/v1/usage", p.key, 30_000)
        if (!r.ok) return null
        val j = r.json() ?: return null
        val di = j.jmap("dailyImages")
        return Quota(
            used = (di?.jlong("used") ?: 0L).toInt(),
            remaining = (di?.jlong("remaining") ?: -1L).toInt(),
            limit = (j.jmap("limits")?.jlong("dailyImages") ?: 100L).toInt(),
            resetAt = di?.jlong("resetAt") ?: 0L,
            allowOverage = j.jbool("allowOverage") ?: false,
            active = j.jbool("active") ?: false,
            recommendedMode = j.jmap("routing")?.jstr("recommendedMode") ?: "",
            periodEnd = j.jmap("period")?.jstr("currentPeriodEnd") ?: ""
        )
    }

    // ---------- генерация ----------

    fun generate(ctx: Context, req: GenReq): GenOut {
        val p = Store.active(ctx)
        if (p.key.isEmpty()) {
            return GenOut(false, emptyList(), "", 0, "«${p.name}»: не задан API-ключ", null, null, "")
        }

        val body = LinkedHashMap<String, Any?>()
        body["model"] = req.model
        body["prompt"] = req.prompt
        if (req.n > 0) body["n"] = req.n
        if (!req.resolution.isNullOrEmpty()) body["resolution"] = req.resolution
        if (!req.aspect.isNullOrEmpty()) body["aspect_ratio"] = req.aspect
        if (req.seed != null) body["seed"] = req.seed
        if (req.refs.isNotEmpty()) {
            body["input_references"] = req.refs.map { dataUrl(it) }
        }
        // Документированный способ nano-gpt списать с баланса, когда дневная
        // квота подписки кончилась, а картинка всё равно нужна.
        if (req.paygo) body["billing_mode"] = "paygo"

        val r = Net.post("${p.root}/api/v1/images", Js.encode(body), p.key, 300_000)
        if (!r.ok) {
            return GenOut(false, emptyList(), r.requestId, r.code, r.message(), null, null, r.body)
        }

        val b64 = ArrayList<String>()
        collect(r.json(), B64_KEYS, b64)
        val images = ArrayList<ByteArray>()
        for (s in b64) {
            try {
                val bytes = Base64.decode(s, Base64.DEFAULT)
                if (bytes.size > 100) images.add(bytes)
            } catch (_: Exception) {
            }
        }

        // Запасной путь: nano-gpt иногда отдаёт ссылку вместо base64.
        if (images.isEmpty()) {
            val urls = ArrayList<String>()
            collect(r.json(), URL_KEYS, urls)
            for (u in urls.take(8)) {
                Net.bytes(u)?.let { if (it.size > 100) images.add(it) }
            }
        }

        if (images.isEmpty()) {
            return GenOut(false, emptyList(), r.requestId, r.code,
                "ответ пришёл, но картинок в нём нет", null, null, r.body)
        }

        val (cost, scope) = requestInfo(ctx, r.requestId)
        return GenOut(true, images, r.requestId, r.code, "", cost, scope, "")
    }

    /**
     * Фактическая стоимость одного запроса и чем она объясняется.
     * cost 0 = покрыто подпиской; cost_scope nano-gpt отдаёт как "primary_charge".
     */
    fun requestInfo(ctx: Context, requestId: String): Pair<Double?, String?> {
        val p = Store.active(ctx)
        if (p.key.isEmpty() || requestId.isEmpty()) return null to null
        val r = Net.get("${p.root}/api/v1/usage/requests/$requestId", p.key, 30_000)
        if (!r.ok) return null to null
        val j = r.json() ?: return null to null
        val cost: Double? = j.jdbl("cost") ?: j.jdbl("costUsd") ?: j.jmap("usage")?.jdbl("cost")
        val scope: String? = j.jstr("cost_scope").takeIf { it.isNotEmpty() }
            ?: j.jstr("costScope").takeIf { it.isNotEmpty() }
        return cost to scope
    }

    private fun dataUrl(bytes: ByteArray): String {
        val mime = when (Gallery.extOf(bytes)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Рекурсивно собирает строки из нужных ключей по всему дереву ответа. */
    private fun collect(v: Any?, keys: Set<String>, out: MutableList<String>) {
        when (v) {
            is Map<*, *> -> for ((k, vv) in v) {
                val ks = k?.toString() ?: continue
                if (vv is String && ks in keys && vv.length > 60 &&
                    (ks !in URL_KEYS || vv.startsWith("http"))
                ) {
                    out.add(vv)
                } else {
                    collect(vv, keys, out)
                }
            }
            is List<*> -> for (item in v) collect(item, keys, out)
        }
    }
}
