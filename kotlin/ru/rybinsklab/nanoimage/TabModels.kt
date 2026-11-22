package ru.rybinsklab.nanoimage

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Вкладка «Модели» — то, ради чего приложение затевалось.
 *
 * По каждой из 264 моделей видно: входит ли она в подписку (значит бесплатна
 * в пределах 100 картинок в день), с цензурой она или без, что умеет
 * (text→image / image→image / inpainting) и сколько стоит за картинку.
 *
 * Список показываем порциями по 60 штук: 264 карточки в одном ScrollView —
 * это больше тысячи View, прокрутка будет дёрганой.
 */
internal fun MainActivity.buildModels() {
    val act = this
    val allow = Store.allowlist(act)

    if (!Store.configured(act) && models.isEmpty()) {
        body.addView(Ui.banner(act,
            "Без API-ключа каталог не загрузить: цены и список моделей провайдер отдаёт только авторизованным.",
            Ui.WARN_BG, Ui.WARN_TEXT))
        body.addView(Ui.button(act, "Открыть настройки", Ui.Kind.PRIMARY) { openSettings() })
        return
    }

    // ---------- поиск ----------
    val search = Ui.field(act, "Поиск: id, название или провайдер", searchText)
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            searchText = s?.toString() ?: ""
            modelsShown = PAGE
            renderModelList()
        }
    })
    body.addView(search)

    // ---------- фильтры ----------
    val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    MFilter.entries.forEach { f ->
        chips.addView(Ui.chip(act, f.title, modelFilter == f) {
            modelFilter = f
            modelsShown = PAGE
            show()
        })
    }
    body.addView(Ui.hScroll(act, chips))

    // ---------- счётчик ----------
    modelCountText = Ui.body(act, "", 12f)
    body.addView(modelCountText)

    modelListBox = Ui.vRow(act)
    body.addView(modelListBox)

    if (loading && models.isEmpty()) {
        body.addView(Ui.banner(act, "Загружаю каталог…", Ui.ACCENT_SOFT, Ui.ACCENT_DARK))
    }
    modelsNote?.let { body.addView(Ui.banner(act, it, Ui.WARN_BG, Ui.WARN_TEXT)) }

    body.addView(Ui.button(act, "Обновить каталог с сервера", Ui.Kind.PLAIN) {
        refreshAll(force = true)
        toast("Обновляю каталог…")
    })
    body.addView(Ui.body(act, Api.cacheAgeText(act), 11f))

    renderModelList()
}

private const val PAGE = 60

/** Перерисовать только список моделей — чтобы не сбрасывать поле поиска. */
internal fun MainActivity.renderModelList() {
    val box = modelListBox ?: return
    val act = this
    val allow = Store.allowlist(act)
    box.removeAllViews()

    val all = filterModels(models, allow, modelFilter, searchText)
    val subCount = models.count { it.id in allow }
    val uncCount = models.count { it.nsfw }
    modelCountText?.text = when {
        models.isEmpty() -> if (loading) "загружаю…" else "моделей нет"
        searchText.isNotEmpty() || modelFilter != MFilter.ALL ->
            "найдено ${all.size} из ${models.size} · всего в подписке $subCount · без цензуры $uncCount"
        else -> "${models.size} моделей · в подписке $subCount (бесплатно) · без цензуры $uncCount (платно)"
    }

    val shown = all.take(modelsShown)
    for (m in shown) box.addView(modelRow(m, m.id in allow))

    if (all.size > shown.size) {
        box.addView(Ui.button(act, "Показать ещё ${minOf(PAGE, all.size - shown.size)}", Ui.Kind.PLAIN) {
            modelsShown += PAGE
            renderModelList()
        })
    }
    if (all.isEmpty() && models.isNotEmpty()) {
        box.addView(Ui.banner(act, "Ничего не найдено. Попробуйте другой фильтр.", Ui.CEN_BG, Ui.CEN_TEXT))
    }
}

private fun MainActivity.modelRow(m: ImgModel, sub: Boolean): android.view.View =
    Ui.pressableCard(this, 13) {
        detailId = m.id
        screen = MainActivity.SCREEN_MODEL
        show()
    }.apply {
        val row = Ui.hRow(this@modelRow)
        row.addView(badgeFor(m, sub))
        val col = Ui.vRow(this@modelRow)
        col.addView(Ui.body(this@modelRow, m.name, 15f, Ui.TEXT).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(Ui.mono(this@modelRow, m.id, 11f, Ui.SUB))
        col.addView(Ui.body(this@modelRow, priceCaption(m, sub), 12f,
            if (sub) Ui.OK_TEXT else if (m.nsfw) Ui.UNC_TEXT else Ui.SUB))
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.body(this@modelRow, "›", 20f, Ui.SUB))
        addView(row)
    }

/** Цена в одну строку: для подписочных — «$0 · подписка», иначе прайс. */
internal fun MainActivity.priceCaption(m: ImgModel, sub: Boolean): String {
    val caps = m.caps()
    return if (sub) "$caps   $0 · подписка (обычно ${fmtUsd(m.minPrice)})"
    else "$caps   ${m.priceLine()}"
}

// ---------------- карточка модели ----------------

internal fun MainActivity.buildModelDetail() {
    val act = this
    val m = models.firstOrNull { it.id == detailId }
    if (m == null) {
        body.addView(Ui.banner(act, "Модель не найдена в каталоге.", Ui.BAD_BG, Ui.BAD_TEXT))
        body.addView(Ui.button(act, "‹ К списку моделей", Ui.Kind.PLAIN) { backToTabs(MainActivity.TAB_MODELS) })
        return
    }
    val allow = Store.allowlist(act)
    val sub = m.id in allow

    body.addView(Ui.button(act, "‹ Все модели", Ui.Kind.PLAIN) { backToTabs(MainActivity.TAB_MODELS) })

    val head = Ui.card(act)
    val row = Ui.hRow(act)
    row.addView(badgeFor(m, sub))
    row.addView(Ui.title(act, m.name, 19f), LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(row)
    head.addView(Ui.mono(act, m.id, 12f, Ui.SUB))
    if (m.owner.isNotEmpty()) head.addView(Ui.body(act, "провайдер: ${m.owner}", 12f))
    head.addView(Ui.body(act, m.caps(), 13f, Ui.TEXT))
    body.addView(head)

    // ---------- деньги ----------
    val q = quota
    val quotaGone = q != null && q.active && q.remaining <= 0 && !q.allowOverage
    when {
        sub && !quotaGone -> body.addView(Ui.banner(act,
            (if (Store.active(act).id == Store.NANO_ID) "Входит в подписку Pro: 100 картинок в день бесплатно.\n"
                else "Провайдер отдаёт эту модель бесплатно (входит в подписку).\n") +
                (if (q != null) "Сейчас доступно ${q.remaining} из ${q.limit}." else "Дневной лимит провайдер не сообщает.") +
                (if (q != null && q.resetAt > 0) "\nСброс в ${fmtDay(q.resetAt)} UTC." else ""),
            Ui.OK_BG, Ui.OK_TEXT))
        sub -> body.addView(Ui.banner(act,
            "Модель входит в подписку, но бесплатные ${q?.limit ?: 100} картинок на сегодня кончились.\n" +
                "До сброса генерация будет стоить ${fmtUsd(m.minPrice)} и спишется с баланса.",
            Ui.WARN_BG, Ui.WARN_TEXT))
        else -> body.addView(Ui.banner(act,
            (if (m.nsfw) "Без цензуры. " else "С цензурой. ") +
                "В подписку НЕ входит: ${fmtUsd(m.minPrice)}…${fmtUsd(m.maxPrice)} за картинку, " +
                "спишется с баланса ${Store.active(act).name}. Приложение спросит подтверждение перед генерацией.",
            if (m.nsfw) Ui.UNC_BG else Ui.CEN_BG,
            if (m.nsfw) Ui.UNC_TEXT else Ui.CEN_TEXT))
    }

    // ---------- прайс по разрешениям ----------
    val priceCard = Ui.card(act, 14)
    priceCard.addView(Ui.label(act, "Цена за картинку"))
    if (m.prices.isEmpty()) {
        priceCard.addView(Ui.body(act, "Сервер не отдал прайс для этой модели.", 13f))
    } else {
        val keys = m.prices.keys.take(12)
        for (k in keys) {
            val line = Ui.hRow(act)
            line.addView(Ui.mono(act, k, 12f), LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val cost = m.prices[k]
            val text = if (sub && !quotaGone) "$0 · подписка" else fmtUsd(cost)
            line.addView(Ui.body(act, text, 12f,
                if (sub && !quotaGone) Ui.OK_TEXT else Ui.TEXT))
            priceCard.addView(line)
        }
        if (m.prices.size > keys.size) {
            priceCard.addView(Ui.body(act, "…и ещё ${m.prices.size - keys.size} вариантов разрешений", 11f))
        }
        m.refPrice?.let {
            priceCard.addView(Ui.body(act,
                "С референсом (image-to-image): ${fmtUsd(it)} за картинку", 12f, Ui.WARN_TEXT))
        }
    }
    body.addView(priceCard)

    // ---------- возможности и лимиты ----------
    val lim = Ui.card(act, 14)
    lim.addView(Ui.label(act, "Возможности и лимиты"))
    lim.addView(kv(act, "text→image", if (m.gen) "да" else "нет"))
    lim.addView(kv(act, "image→image (референс)", if (m.i2i) "да" else "нет"))
    lim.addView(kv(act, "inpainting", if (m.inpaint) "да" else "нет"))
    lim.addView(kv(act, "цензура", if (m.nsfw) "снята (nsfw: true)" else "есть"))
    lim.addView(kv(act, "картинок за запрос", if (m.maxImages > 0) m.maxImages.toString() else "—"))
    if (m.maxInputImages > 0) lim.addView(kv(act, "референсов максимум", m.maxInputImages.toString()))
    lim.addView(kv(act, "символов в промпте",
        if (m.maxPromptChars > 0) m.maxPromptChars.toString() else "не указано"))
    lim.addView(kv(act, "seed", if (m.hasSeed) "поддерживается" else "не объявлен"))
    if (m.modality.isNotEmpty()) lim.addView(kv(act, "модальность", m.modality))
    if (m.tags.isNotEmpty()) lim.addView(kv(act, "теги", m.tags.joinToString(", ")))
    body.addView(lim)

    if (m.resolutions.isNotEmpty()) {
        val rc = Ui.card(act, 14)
        rc.addView(Ui.label(act, "Разрешения (${m.resolutions.size})"))
        rc.addView(Ui.body(act, m.resolutions.joinToString("  ·  "), 12f))
        body.addView(rc)
    }
    if (m.aspects.isNotEmpty()) {
        val ac = Ui.card(act, 14)
        ac.addView(Ui.label(act, "Соотношения сторон (${m.aspects.size})"))
        ac.addView(Ui.body(act, m.aspects.joinToString("  ·  "), 12f))
        body.addView(ac)
    }

    if (m.desc.isNotEmpty()) {
        val dc = Ui.card(act, 14)
        dc.addView(Ui.label(act, "Описание от провайдера"))
        dc.addView(Ui.body(act, m.desc, 13f, Ui.TEXT))
        body.addView(dc)
    }

    val label = if (sub && !quotaGone) "Рисовать этой моделью · \$0"
    else "Рисовать этой моделью · ${fmtUsd(m.minPrice)}"
    body.addView(Ui.button(act, label, Ui.Kind.PRIMARY) { selectModel(m.id) })
    if (Store.model(act) == m.id) {
        body.addView(Ui.body(act, "Эта модель уже выбрана на вкладке «Создать».", 12f))
    }
}

internal fun MainActivity.selectModel(id: String) {
    Store.setModel(this, id)
    resolution = ""
    aspect = ""
    count = 1
    seedText = ""
    refs = ArrayList()
    resultFiles = ArrayList()
    resultNote = ""
    errorNote = ""
    backToTabs(MainActivity.TAB_CREATE)
    toast("Модель $id выбрана")
}

private fun kv(act: android.content.Context, k: String, v: String): android.view.View {
    val row = Ui.hRow(act)
    row.addView(Ui.body(act, k, 13f), LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(Ui.body(act, v, 13f, Ui.TEXT))
    row.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3))
    return row
}

private fun fmtDay(ms: Long): String {
    val f = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(ms))
}
