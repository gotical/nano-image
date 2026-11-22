package ru.rybinsklab.nanoimage

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Вкладка «Создать»: промпт, модель с ценой, параметры, референс, результат.
 *
 * Параметры берутся из supported_parameters именно выбранной модели: у 264
 * моделей разные списки разрешений, aspect_ratio объявлен только у 48, а
 * max_images — от 1 до 20. Поэтому ничего не выдумываем и не подставляем
 * «типовые» значения — иначе сервер вернёт 400, а деньги могут уже списаться.
 */
internal fun MainActivity.buildCreate() {
    val act = this

    // ---------- предупреждения сверху ----------

    if (!Store.configured(act)) {
        body.addView(Ui.banner(act,
            "Не задан API-ключ провайдера «${Store.active(act).name}».\n" +
                "Без него приложение не знает ни цен, ни остатка бесплатных картинок.",
            Ui.WARN_BG, Ui.WARN_TEXT))
        body.addView(Ui.button(act, "Открыть настройки", Ui.Kind.PRIMARY) { openSettings() })
        return
    }

    if (!Gallery.canWriteShared()) {
        body.addView(Ui.banner(act,
            "Нет доступа ко всей памяти — результаты некуда сохранять.\n" +
                "Выдайте приложению разрешение «Все файлы» в настройках Android.",
            Ui.BAD_BG, Ui.BAD_TEXT))
        body.addView(Ui.button(act, "Выдать доступ", Ui.Kind.DANGER) { requestStorageAccess() })
    }

    quota?.let { q ->
        if (q.active && q.remaining <= 0 && !q.allowOverage) {
            body.addView(Ui.banner(act,
                "Бесплатные ${q.limit} картинок на сегодня израсходованы.\n" +
                    "Подписочные модели станут бесплатными снова после сброса" +
                    (if (q.resetAt > 0) " (${fmtReset(q.resetAt)} UTC)" else "") +
                    ".\nСейчас любая генерация спишет деньги с баланса — приложение спросит подтверждение.",
                Ui.WARN_BG, Ui.WARN_TEXT))
        }
    }

    if (errorNote.isNotEmpty()) {
        body.addView(Ui.banner(act, errorNote, Ui.BAD_BG, Ui.BAD_TEXT))
    }

    val m = selectedModel()

    // ---------- промпт ----------

    val promptCard = Ui.card(act)
    promptCard.addView(Ui.label(act, "Промпт — что нарисовать"))
    val counter = Ui.body(act, "", 11f)
    val et = Ui.field(act, "Например: закат над Волгой, акварель, тёплые тона",
        promptText, multiline = true)
    et.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            promptText = s?.toString() ?: ""
            counter.text = promptCounter(promptText.length, m?.maxPromptChars ?: 0)
            val over = (m?.maxPromptChars ?: 0) > 0 && promptText.length > (m?.maxPromptChars ?: 0)
            counter.setTextColor(if (over) Ui.BAD else Ui.SUB)
        }
    })
    promptCard.addView(et)
    counter.text = promptCounter(promptText.length, m?.maxPromptChars ?: 0)
    promptCard.addView(counter)
    body.addView(promptCard)

    if (m == null) {
        body.addView(Ui.banner(act,
            if (loading) "Загружаю каталог моделей…"
            else "Каталог моделей пуст" + (modelsNote?.let { ": $it" } ?: ""),
            Ui.WARN_BG, Ui.WARN_TEXT))
        body.addView(Ui.button(act, "Обновить каталог", Ui.Kind.PRIMARY) { refreshAll(force = true) })
        return
    }

    // ---------- модель ----------

    val sub = isSub(m)
    val modelCard = Ui.pressableCard(act, 14) { backToTabs(MainActivity.TAB_MODELS) }
    val mrow = Ui.hRow(act)
    mrow.addView(badgeFor(m, sub))
    val mtext = Ui.vRow(act)
    mtext.addView(Ui.body(act, m.name, 15f, Ui.TEXT).apply {
        setTypeface(typeface, Typeface.BOLD)
    })
    mtext.addView(Ui.body(act, m.id, 11f))
    mtext.addView(Ui.body(act, modelPriceLine(m, sub), 12f,
        if (sub) Ui.OK_TEXT else Ui.SUB))
    mrow.addView(mtext, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    mrow.addView(Ui.body(act, "›", 20f, Ui.SUB))
    modelCard.addView(mrow)
    body.addView(modelCard)

    // ---------- параметры ----------

    val pCard = Ui.card(act, 14)
    pCard.addView(Ui.label(act, "Параметры (из данных модели)"))

    // Разрешение
    if (m.resolutions.isNotEmpty()) {
        pCard.addView(Ui.body(act, "Разрешение", 12f))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.chip(act, "по умолчанию", resolution.isEmpty()) {
            resolution = ""; syncParamsWithModel(); show()
        })
        m.resolutions.forEach { r ->
            row.addView(Ui.chip(act, r, resolution == r) {
                resolution = r; syncParamsWithModel(); show()
            })
        }
        pCard.addView(Ui.hScroll(act, row))
    }

    // Соотношение сторон — только если модель его объявляет
    if (m.aspects.isNotEmpty()) {
        pCard.addView(Ui.body(act, "Соотношение сторон", 12f))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.chip(act, "auto", aspect.isEmpty()) {
            aspect = ""; show()
        })
        m.aspects.forEach { a ->
            row.addView(Ui.chip(act, a, aspect == a) { aspect = a; show() })
        }
        pCard.addView(Ui.hScroll(act, row))
    }

    // Сколько картинок
    val maxN = m.maxImages.coerceIn(1, 20)
    if (maxN > 1) {
        pCard.addView(Ui.body(act, "Количество (максимум $maxN)", 12f))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 1..maxN) {
            row.addView(Ui.chip(act, i.toString(), count == i) {
                count = i; show()
            })
        }
        pCard.addView(Ui.hScroll(act, row))
    } else {
        pCard.addView(Ui.body(act, "Количество: 1 (модель рисует по одной картинке)", 12f))
    }

    // Seed
    val seedRow = Ui.hRow(act)
    val seedField = Ui.field(act, if (m.hasSeed) "seed" else "seed (модель может игнорировать)",
        seedText, number = true)
    seedField.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            seedText = s?.toString() ?: ""
        }
    })
    seedField.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = Ui.dp(act, 8)
        bottomMargin = Ui.dp(act, 8)
    }
    seedRow.addView(seedField)
    seedRow.addView(Ui.iconButton(act, "🎲") {
        seedText = (1..999_999_999).random().toString()
        show()
    })
    seedRow.addView(Ui.iconButton(act, "✕") { seedText = ""; show() })
    seedRow.layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    pCard.addView(seedRow)
    if (!m.hasSeed) {
        pCard.addView(Ui.body(act,
            "Эта модель не объявляет поддержку seed — повторить кадр один в один может не выйти.", 11f))
    }
    body.addView(pCard)

    // ---------- референсы (image-to-image) ----------

    if (m.i2i) {
        val rCard = Ui.card(act, 14)
        val limit = m.maxInputImages.coerceAtLeast(1)
        rCard.addView(Ui.label(act, "Референс (image-to-image, до $limit)"))
        if (refs.isEmpty()) {
            rCard.addView(Ui.body(act, "Не приложен — модель нарисует с нуля.", 12f))
        } else {
            val thumbs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            refs.forEach { f ->
                val cell = android.widget.FrameLayout(act)
                val iv = ImageView(act).apply {
                    setImageBitmap(Gallery.thumb(f, Ui.dp(act, 72)))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = Ui.round(act, Ui.BG, 10)
                }
                val size = Ui.dp(act, 72)
                cell.addView(iv, android.widget.FrameLayout.LayoutParams(size, size))
                val x = TextView(act).apply {
                    text = "✕"
                    setTextSize(11f)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Ui.round(act, Color.parseColor("#CC374151"), 8)
                    setPadding(Ui.dp(act, 5), Ui.dp(act, 2), Ui.dp(act, 5), Ui.dp(act, 2))
                    setOnClickListener { dropRef(f) }
                }
                cell.addView(x, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END))
                cell.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = Ui.dp(act, 8) }
                thumbs.addView(cell)
            }
            rCard.addView(thumbs)
        }
        if (refs.size < limit) {
            rCard.addView(Ui.button(act, "📎 Приложить фото", Ui.Kind.PLAIN) { pickRef() })
        }
        body.addView(rCard)
    } else if (refs.isNotEmpty()) {
        body.addView(Ui.banner(act,
            "Выбранная модель не умеет image-to-image — приложенные фото будут проигнорированы.",
            Ui.WARN_BG, Ui.WARN_TEXT))
    }

    // ---------- кнопка ----------

    val genBtn = Ui.button(act, generateLabel(), Ui.Kind.PRIMARY) { onGenerateClick() }
    genBtn.isEnabled = !busy
    genBtn.textSize = 16f
    body.addView(genBtn)

    if (busy) {
        body.addView(Ui.banner(act, busyNote.ifEmpty { "Рисую…" }, Ui.ACCENT_SOFT, Ui.ACCENT_DARK))
    }

    // ---------- результат ----------

    if (resultFiles.isNotEmpty()) {
        val rCard = Ui.card(act, 14)
        resultPager = Ui.body(act, "", 12f, Ui.SUB).apply { gravity = Gravity.CENTER }
        rCard.addView(resultPager)

        val zoom = ZoomView(act).apply {
            background = Ui.round(act, Ui.BG, 14)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 340)
            ).apply { bottomMargin = Ui.dp(act, 10) }
        }
        resultZoom = zoom
        rCard.addView(zoom)
        updateResultPager()

        if (resultFiles.size > 1) {
            val nav = Ui.hRow(act)
            val prev = Ui.button(act, "‹ предыдущая", Ui.Kind.PLAIN) {
                showResultBitmap((resultIndex - 1 + resultFiles.size) % resultFiles.size)
            }
            val next = Ui.button(act, "следующая ›", Ui.Kind.PLAIN) {
                showResultBitmap((resultIndex + 1) % resultFiles.size)
            }
            for (b in listOf(prev, next)) {
                b.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { rightMargin = Ui.dp(act, 8) }
                nav.addView(b)
            }
            rCard.addView(nav)
        }

        rCard.addView(Ui.body(act, resultNote, 12f))
        val current = resultFiles[resultIndex.coerceIn(0, resultFiles.size - 1)]
        rCard.addView(Ui.mono(act, current.absolutePath, 11f, Ui.SUB))

        val acts = Ui.vRow(act)
        acts.addView(Ui.button(act, "Поделиться", Ui.Kind.PLAIN) { share(current) })
        acts.addView(Ui.button(act, "Открыть в галерее", Ui.Kind.PLAIN) { backToTabs(MainActivity.TAB_GALLERY) })
        acts.addView(Ui.button(act, "Нарисовать ещё раз", Ui.Kind.SOFT) { onGenerateClick() })
        rCard.addView(acts)

        body.addView(rCard)
        showResultBitmap(resultIndex)
    }
}

/** Подпись главной кнопки: цена видна ДО нажатия. */
internal fun MainActivity.generateLabel(): String {
    val m = selectedModel() ?: return "Нарисовать"
    if (busy) return "Рисую…"
    val sub = isSub(m)
    val q = quota
    val quotaGone = q != null && q.active && q.remaining <= 0 && !q.allowOverage
    val price = m.effectivePrice(resolution.ifEmpty { null }, refs.isNotEmpty())
    return when {
        sub && !quotaGone ->
            if (count > 1) "Нарисовать $count · \$0 (подписка)"
            else "Нарисовать · \$0 (подписка)"
        price == null -> "Нарисовать · цена неизвестна"
        count > 1 -> "Нарисовать · ${fmtUsd(price)} × $count = ${fmtUsd(price * count)}"
        else -> "Нарисовать · ${fmtUsd(price)}"
    }
}

/** Строка о цене под названием модели: подписка / платно / без цензуры. */
internal fun MainActivity.modelPriceLine(m: ImgModel, sub: Boolean): String {
    val q = quota
    val quotaGone = q != null && q.active && q.remaining <= 0 && !q.allowOverage
    return when {
        sub && !quotaGone -> "$0 · входит в подписку (${q?.remaining ?: "?"} осталось сегодня)"
        sub -> "в подписке, но сегодня квота кончилась → ${fmtUsd(m.minPrice)} с баланса"
        m.nsfw -> "${m.priceLine()} · без цензуры, платно"
        else -> "${m.priceLine()} · с цензурой, платно"
    }
}

/** SUB — покрыта подпиской и квота ещё есть, U — без цензуры, C — с цензурой. */
internal fun MainActivity.badgeFor(m: ImgModel, sub: Boolean): TextView {
    val q = quota
    val subNow = sub && (q == null || q.remaining > 0 || q.allowOverage)
    return when {
        subNow -> Ui.badge(this, "SUB", Ui.OK_TEXT, Ui.OK_BG)
        m.nsfw -> Ui.badge(this, "U", Ui.UNC_TEXT, Ui.UNC_BG)
        else -> Ui.badge(this, "C", Ui.CEN_TEXT, Ui.CEN_BG)
    }
}

private fun promptCounter(len: Int, max: Int): String =
    if (max > 0) "$len / $max символов" else "$len символов"

private fun fmtReset(ms: Long): String {
    val f = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
    f.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return f.format(java.util.Date(ms))
}
