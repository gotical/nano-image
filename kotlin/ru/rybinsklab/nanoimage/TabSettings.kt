package ru.rybinsklab.nanoimage

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Настройки: провайдеры (адрес + ключ), бесплатные модели, папка сохранения.
 *
 * Ключи хранятся только в SharedPreferences приватного каталога приложения и
 * нигде не показываются целиком — ни в списке провайдеров, ни в sidecar-файлах
 * рядом с картинками, ни в логах.
 */
internal fun MainActivity.buildSettings() {
    val act = this
    val active = Store.active(act)
    val all = Store.providers(act)

    body.addView(Ui.button(act, "‹ Назад", Ui.Kind.PLAIN) { backToTabs(MainActivity.TAB_CREATE) })

    // ---------- провайдеры ----------

    val pCard = Ui.card(act, 14)
    pCard.addView(Ui.label(act, "Провайдеры"))
    pCard.addView(Ui.body(act,
        "Рисует тот провайдер, который помечен активным.\n" +
            "nano-gpt настроен из коробки; своим адресом может быть любой сервер с таким же " +
            "Image API — например, собственный прокси. Адрес и ключ хранятся у каждого свои.", 12f))

    all.forEach { p ->
        val isActive = p.id == active.id
        val row = Ui.pressableCard(act, 12) { openProviderForm(p.id) }

        val head = Ui.hRow(act)
        head.addView(Ui.body(act, p.name, 15f, Ui.TEXT).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        if (isActive) {
            val b = Ui.badge(act, "активный", Ui.OK_TEXT, Ui.OK_BG)
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(act, 8) }
            head.addView(b)
        }
        row.addView(head)

        row.addView(Ui.mono(act, p.root, 11f, Ui.SUB))
        row.addView(Ui.body(act, buildString {
            append(p.mask())
            when {
                p.allow.isNotEmpty() -> append(" · бесплатно ${p.allow.size} модел" +
                    (if (p.allow.size == 1) "ь" else "и"))
                p.key.isNotEmpty() -> append(" · всё платно")
            }
        }, 12f))

        // «Сделать активным» — отдельной строкой: в трёхколоночном ряду
        // подпись переносится по буквам.
        if (!isActive) {
            row.addView(Ui.button(act, "Сделать активным", Ui.Kind.SOFT) { switchProvider(p.id) })
        }
        val acts = Ui.hRow(act)
        val pair = ArrayList<android.widget.Button>()
        pair.add(Ui.button(act, "Изменить", Ui.Kind.PLAIN) { openProviderForm(p.id) })
        if (all.size > 1) {
            pair.add(Ui.button(act, "Удалить", Ui.Kind.DANGER) { confirmDeleteProvider(p) })
        }
        pair.forEach { b ->
            b.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { rightMargin = Ui.dp(act, 6) }
            acts.addView(b)
        }
        acts.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.addView(acts)

        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Ui.dp(act, 8) }
        pCard.addView(row)
    }

    pCard.addView(Ui.button(act, "＋ Добавить провайдера", Ui.Kind.PRIMARY) { openProviderForm(null) })
    body.addView(pCard)

    // ---------- бесплатные модели активного провайдера ----------

    val subCard = Ui.card(act, 14)
    subCard.addView(Ui.label(act, "Бесплатные модели — ${active.name}"))
    subCard.addView(Ui.body(act,
        if (active.id == Store.NANO_ID) {
            "Подписка Pro nano-gpt даёт ${quota?.limit ?: 100} картинок в день бесплатно — " +
                "но только на этих моделях.\nСписок взят из данных nano-gpt на 2026-11-22 и проверен " +
                "замером: генерация вернула цену \$0 и потратила одну картинку из дневной квоты. " +
                "Если сервис его поменяет — впишите свой, пересборка приложения не потребуется."
        } else {
            "Модели, которые этот провайдер отдаёт по подписке, то есть бесплатно.\n" +
                "Пусто — значит подписки нет и всё платно: приложение покажет цену и спросит " +
                "подтверждение перед каждой генерацией."
        }, 12f))
    val allowField = Ui.field(act, "через запятую; пусто = всё платно", Store.allowlistRaw(act))
    subCard.addView(allowField)
    subCard.addView(Ui.body(act,
        "Сейчас в приложении: " + Store.allowlist(act).sorted().joinToString(", ").ifEmpty { "—" }, 11f))
    body.addView(subCard)

    // ---------- сохранение ----------

    val outCard = Ui.card(act, 14)
    outCard.addView(Ui.label(act, "Куда сохранять"))
    val dirField = Ui.field(act, "/storage/emulated/0/Pictures/.nano", Store.outDir(act))
    outCard.addView(dirField)
    outCard.addView(Ui.body(act,
        "Папка в общей памяти устройства: картинки видны из любого файлового менеджера.\n" +
            "Маркер .nomedia прячет её от системной галереи.\n" +
            "Сейчас: ${Gallery.sizeText(act)}" +
            (if (Gallery.canWriteShared()) "" else "\n⚠ нет доступа ко всей памяти"), 12f))
    outCard.addView(Ui.toggle(act, "Писать рядом .json с промптом и ценой",
        Store.writeMeta(act)) { v ->
        Store.setWriteMeta(act, v)
        show()
    })
    body.addView(outCard)

    // ---------- кнопки ----------

    body.addView(Ui.button(act, "Сохранить и проверить ${active.name}", Ui.Kind.PRIMARY) {
        Store.setAllowlist(act, allowField.text.toString())
        Store.setOutDir(act, dirField.text.toString().trim())
        if (!Store.configured(act)) {
            toast("У провайдера «${active.name}» не задан API-ключ")
            show()
            return@button
        }
        toast("Проверяю провайдера…")
        Thread {
            val q = Api.quota(act)
            val (list, note) = Api.catalog(act, force = true)
            ui.post {
                models = list
                modelsNote = note
                quota = q
                toast(
                    when {
                        q == null && list.isEmpty() -> "Ключ не подошёл, адрес недоступен или нет связи"
                        q == null -> "Каталог загружен (${list.size}), но квоту провайдер не отдаёт — модели платные"
                        !q.active -> "Ключ принят, но подписка не активна"
                        else -> "Ключ принят · подписка активна · осталось ${q.remaining} из ${q.limit} сегодня"
                    }
                )
                show()
            }
        }.start()
    })

    body.addView(Ui.button(act, "Обновить каталог моделей", Ui.Kind.PLAIN) {
        refreshAll(force = true)
        toast("Обновляю каталог…")
    })

    if (!Gallery.canWriteShared()) {
        body.addView(Ui.button(act, "Выдать доступ ко всей памяти", Ui.Kind.DANGER) {
            requestStorageAccess()
        })
    }

    // ---------- справка ----------

    val info = Ui.card(act, 14)
    info.addView(Ui.label(act, "Как это работает"))
    info.addView(Ui.body(act, buildString {
        append("• Бесплатные картинки даёт подписка провайдера: ")
        if (active.id == Store.NANO_ID) {
            append("у nano-gpt это 100 в день на моделях из списка выше.\n")
        } else {
            append("у «${active.name}» это модели из списка выше.\n")
        }
        append("• Остальные модели списывают деньги с баланса — ")
        append("приложение показывает цену до генерации и спрашивает подтверждение.\n")
        append("• Фактическую цену каждого запроса провайдер отдаёт отдельно: ")
        append("её видно под результатом и в .json рядом с файлом.\n")
        if (active.id == Store.NANO_ID) {
            append("• У nano-gpt все 4 подписочные модели — с цензурой. Моделей без цензуры ")
            append("в каталоге ${models.count { it.nsfw }}, и в подписку они не входят.\n")
        } else {
            append("• Моделей без цензуры в каталоге ${models.count { it.nsfw }} — ")
            append("они помечены бейджем U и почти всегда платные.\n")
        }
        append("• ${Api.cacheAgeText(act)}, всего моделей в каталоге: ${models.size}.")
    }, 12f))
    info.addView(Ui.body(act,
        "Nano Image 1.0 · rybinsklab.ru · трафик только HTTPS, на адрес активного провайдера", 11f))
    body.addView(info)
}

/**
 * Экран-форма провайдера: название, адрес API, ключ, бесплатные модели.
 *
 * Адрес принимаем в любом виде (с хвостом /api/v1 или без него) — Prov.root
 * приводит его к корню. Ключ показываем звёздочками: в открытом виде его нет
 * ни здесь, ни в списке провайдеров.
 */
internal fun MainActivity.buildProviderForm() {
    val act = this
    val editing = editProvId?.let { id -> Store.providers(act).firstOrNull { it.id == id } }

    body.addView(Ui.button(act, "‹ Назад к настройкам", Ui.Kind.PLAIN) { openSettings() })

    if (provError.isNotEmpty()) {
        body.addView(Ui.banner(act, provError, Ui.BAD_BG, Ui.BAD_TEXT))
    }

    val card = Ui.card(act, 14)
    card.addView(Ui.label(act,
        if (editing == null) "Новый провайдер" else "Провайдер «${editing.name}»"))
    card.addView(Ui.body(act,
        "Нужен сервер с Image API в формате nano-gpt: каталог /api/v1/images/models, " +
            "генерация POST /api/v1/images.\nТолько HTTPS — незашифрованный трафик приложением " +
            "запрещён (android:usesCleartextTraffic=\"false\").", 12f))

    card.addView(Ui.label(act, "Название"))
    val nameField = Ui.field(act, "Например: nano-gpt", editing?.name ?: "")
    card.addView(nameField)

    card.addView(Ui.label(act, "Адрес API"))
    val urlField = Ui.field(act, "https://api.nano-gpt.com", editing?.baseUrl ?: "")
    card.addView(urlField)

    card.addView(Ui.label(act, "API-ключ"))
    val keyField = Ui.field(act, "sk-…", editing?.key ?: "", secret = true)
    card.addView(keyField)
    card.addView(Ui.body(act,
        if (editing == null) "Ключ хранится только на этом устройстве, в приватном каталоге приложения."
        else "Сейчас: ${editing.mask()}", 11f))

    card.addView(Ui.label(act, "Бесплатные модели (через запятую)"))
    val allowField = Ui.field(act, "пусто = всё платно",
        editing?.allow?.joinToString(", ") ?: "")
    card.addView(allowField)

    body.addView(card)

    body.addView(Ui.button(act, "Сохранить и сделать активным", Ui.Kind.PRIMARY) {
        val url = urlField.text.toString().trim()
        val name = nameField.text.toString().trim()
        val probeRoot = Prov("check", name.ifEmpty { url }, url, "", emptyList()).root
        when {
            url.isEmpty() -> {
                provError = "Укажите адрес API"
                show()
                return@button
            }
            !probeRoot.startsWith("https://") -> {
                provError = "Адрес должен начинаться с https:// — другой трафик приложение не отправляет"
                show()
                return@button
            }
        }
        provError = ""
        val saved = Store.upsert(act, Prov(
            id = editing?.id ?: "",
            name = name,
            baseUrl = url,
            key = keyField.text.toString().trim(),
            allow = allowField.text.toString()
                .split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
        ))
        toast("Провайдер «${saved.name}» сохранён и выбран активным")
        switchProvider(saved.id)
        openSettings()
    })

    if (editing != null && Store.providers(act).size > 1) {
        body.addView(Ui.button(act, "Удалить провайдера", Ui.Kind.DANGER) {
            confirmDeleteProvider(editing)
        })
    }

    body.addView(Ui.button(act, "Отмена", Ui.Kind.PLAIN) {
        provError = ""
        openSettings()
    })
}

/** Удаление провайдера — вместе с его ключом, поэтому спрашиваем отдельно. */
private fun MainActivity.confirmDeleteProvider(p: Prov) {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        .setTitle("Удалить «${p.name}»?")
        .setMessage("${p.root}\n\nАдрес и ключ этого провайдера будут стёрты с устройства. Отменить действие нельзя.")
        .setNegativeButton("Отмена", null)
        .setPositiveButton("Удалить") { _, _ ->
            val ok = Store.delete(this, p.id)
            toast(if (ok) "Провайдер удалён" else "Нельзя удалить единственного провайдера")
            provError = ""
            if (ok) refreshAll(force = true)
            openSettings()
        }
        .show()
}
