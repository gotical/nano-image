package ru.rybinsklab.nanoimage

import java.util.Locale

/**
 * Одна модель из каталога GET /api/v1/images/models.
 *
 * Форма ответа (проверено на 264 моделях, 2026-11-22):
 *   pricing.per_image — это КАРТА «разрешение → цена», а не одно число:
 *     {"1024x1024": 0.02, "2048x2048": 0.063, "webpage_1k": 0.549}
 *   pricing.per_image_with_references — отдельная цена, если приложен референс.
 *   supported_parameters.aspect_ratio есть только у 48 моделей,
 *   supported_parameters.seed — только у 3, поэтому поле seed показываем
 *   с оговоркой, а список aspect_ratio строим из данных конкретной модели.
 *   capabilities.nsfw: true у 37 моделей — это и есть «без цензуры».
 */
class ImgModel(
    val id: String,
    val name: String,
    val desc: String,
    val owner: String,
    val gen: Boolean,
    val i2i: Boolean,
    val inpaint: Boolean,
    val nsfw: Boolean,
    val prices: Map<String, Double>,
    val refPrice: Double?,
    val resolutions: List<String>,
    val aspects: List<String>,
    val maxImages: Int,
    val maxInputImages: Int,
    val maxPromptChars: Int,
    val hasSeed: Boolean,
    val modality: String,
    val tags: List<String>
) {
    val minPrice: Double? = prices.values.minOrNull()
    val maxPrice: Double? = prices.values.maxOrNull()

    /** Цена за картинку при выбранном разрешении; если его нет — минимальная из прайса. */
    fun priceFor(resolution: String?): Double? {
        if (!resolution.isNullOrEmpty()) prices[resolution]?.let { return it }
        return minPrice
    }

    /** Цена, которую покажет диалог подтверждения: с учётом референса, если он есть. */
    fun effectivePrice(resolution: String?, withRef: Boolean): Double? {
        if (withRef && refPrice != null) return refPrice
        return priceFor(resolution)
    }

    fun caps(): String = buildString {
        if (gen) append("text→image")
        if (i2i) append(if (isNotEmpty()) " · image→image" else "image→image")
        if (inpaint) append(if (isNotEmpty()) " · inpainting" else "inpainting")
        if (isEmpty()) append(modality.ifEmpty { "—" })
    }

    /** Одна строка о модели для списка: что умеет и сколько стоит. */
    fun summary(sub: Boolean): String {
        val price = if (sub) "$0 · подписка" else priceLine()
        return caps() + "   " + price
    }

    fun priceLine(): String {
        val lo = minPrice
        val hi = maxPrice
        return when {
            lo == null -> "цена не указана"
            hi == null || hi == lo -> fmtUsd(lo)
            else -> "${fmtUsd(lo)}…${fmtUsd(hi)}"
        }
    }

    companion object {

        /** Ответ каталога бывает и списком, и {"models":[...]} / {"data":[...]}. */
        fun parseList(top: Any?): List<ImgModel> {
            val items: List<Any?> = when (top) {
                is List<*> -> top
                is Map<*, *> -> (top["models"] ?: top["data"] ?: top["images"]) as? List<Any?>
                    ?: emptyList()
                else -> emptyList()
            }
            return items.mapNotNull { parseOne(it as? Map<*, *> ?: return@mapNotNull null) }
        }

        private fun parseOne(m: Map<*, *>): ImgModel? {
            val id = (m["id"] ?: m["slug"] ?: "").toString()
            if (id.isEmpty()) return null
            val caps = m.jmap("capabilities")
            val sp = m.jmap("supported_parameters")
            val pricing = m.jmap("pricing")

            val prices = LinkedHashMap<String, Double>()
            (pricing?.get("per_image") as? Map<*, *>)?.forEach { (k, v) ->
                val d = v.asDouble()
                if (k != null && d != null) prices[k.toString()] = d
            }

            val resolutions = (sp?.get("resolutions") as? List<*>)
                ?.mapNotNull { it?.toString() } ?: emptyList()
            val aspects = (sp?.get("aspect_ratio") as? List<*>)
                ?.mapNotNull { it?.toString() } ?: emptyList()

            return ImgModel(
                id = id,
                name = (m["name"] ?: id).toString(),
                desc = (m["description"] ?: "").toString(),
                owner = (m["owned_by"] ?: "").toString(),
                gen = caps?.jbool("image_generation") ?: true,
                i2i = caps?.jbool("image_to_image") ?: false,
                inpaint = caps?.jbool("inpainting") ?: false,
                nsfw = caps?.jbool("nsfw") ?: false,
                prices = prices,
                refPrice = pricing?.get("per_image_with_references").asDouble(),
                resolutions = resolutions,
                aspects = aspects,
                maxImages = (sp?.jlong("max_images") ?: sp?.jlong("max_output_images") ?: 1L).toInt(),
                maxInputImages = (sp?.jlong("max_input_images") ?: 0L).toInt(),
                maxPromptChars = (sp?.jlong("max_prompt_characters") ?: 0L).toInt(),
                hasSeed = sp?.containsKey("seed") ?: false,
                modality = m.jmap("architecture")?.jstr("modality") ?: "",
                tags = (m["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            )
        }
    }
}

/** Фильтры вкладки «Модели». */
enum class MFilter(val title: String) {
    ALL("Все"),
    SUB("В подписке · бесплатно"),
    UNCENS("Без цензуры"),
    CENS("С цензурой"),
    I2I("Редактирование")
}

fun ImgModel.matches(f: MFilter, sub: Boolean): Boolean = when (f) {
    MFilter.ALL -> true
    MFilter.SUB -> sub
    MFilter.UNCENS -> nsfw
    MFilter.CENS -> !nsfw
    MFilter.I2I -> i2i || inpaint
}

/**
 * Порядок в списке: сначала бесплатные подписочные (их четыре, и это то,
 * ради чего приложение ставили), затем uncensored, затем остальные —
 * внутри группы по возрастанию цены.
 */
fun sortModels(list: List<ImgModel>, allow: Set<String>): List<ImgModel> =
    list.sortedWith(compareBy(
        { if (it.id in allow) 0 else if (it.nsfw) 1 else 2 },
        { it.minPrice ?: Double.MAX_VALUE },
        { it.id }
    ))

fun filterModels(
    list: List<ImgModel>,
    allow: Set<String>,
    f: MFilter,
    search: String
): List<ImgModel> {
    val q = search.trim().lowercase(Locale.US)
    val matched = list.filter { m ->
        m.matches(f, m.id in allow) &&
            (q.isEmpty() || m.id.lowercase(Locale.US).contains(q) ||
                m.name.lowercase(Locale.US).contains(q) ||
                m.owner.lowercase(Locale.US).contains(q))
    }
    return sortModels(matched, allow)
}

/** $0.0119 / $0.02 / $0.3 — без лишних нулей, с разделителем-точкой. */
fun fmtUsd(v: Double?): String {
    if (v == null) return "—"
    if (v <= 0.0) return "$0"
    val s = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
    return "$$s"
}
