package ru.rybinsklab.nanoimage

import android.app.AlertDialog
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Вкладка «Галерея»: всё, что лежит в /storage/emulated/0/Pictures/.nano.
 *
 * Папка общая с CLI ~/bin/nano-image, поэтому здесь видны и картинки,
 * которые нарисовал агент из Termux. Sidecar-файлы (.json рядом с картинкой)
 * читаются те же — промпт, модель и фактическая цена показываются в просмотре.
 */
internal fun MainActivity.buildGallery() {
    val act = this
    val dir = Gallery.dir(act)

    body.addView(Ui.mono(act, dir.absolutePath, 11f, Ui.SUB))
    body.addView(Ui.body(act, Gallery.sizeText(act), 12f))

    if (!Gallery.canWriteShared()) {
        body.addView(Ui.banner(act,
            "Нет доступа ко всей памяти: папка не читается и не пишется.\n" +
                "Выдайте приложению разрешение «Все файлы».",
            Ui.BAD_BG, Ui.BAD_TEXT))
        body.addView(Ui.button(act, "Выдать доступ", Ui.Kind.DANGER) { requestStorageAccess() })
        return
    }
    if (!dir.isDirectory) {
        body.addView(Ui.banner(act,
            "Папки ещё нет — она создастся при первой сохранённой картинке.\n" +
                "Внутри появится маркер .nomedia, поэтому системная галерея эти файлы не показывает.",
            Ui.CEN_BG, Ui.CEN_TEXT))
        return
    }

    val shots = Gallery.list(act)
    if (shots.isEmpty()) {
        body.addView(Ui.banner(act, "Пока пусто. Нарисуйте первую картинку на вкладке «Создать».",
            Ui.ACCENT_SOFT, Ui.ACCENT_DARK))
        body.addView(Ui.button(act, "К созданию", Ui.Kind.PRIMARY) { backToTabs(MainActivity.TAB_CREATE) })
        return
    }

    val cols = 3
    val pad = Ui.dp(act, 16) * 2
    val gap = Ui.dp(act, 6)
    val cell = ((resources.displayMetrics.widthPixels - pad) - gap * (cols - 1)) / cols

    val grid = GridLayout(act).apply {
        columnCount = cols
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    for (s in shots) {
        val iv = ImageView(act).apply {
            setImageBitmap(Gallery.thumb(s.file, cell))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.round(act, Ui.CARD, 12)
            setOnClickListener { openViewer(s.file) }
            setOnLongClickListener {
                fileMenu(s.file, s.meta)
                true
            }
        }
        val lp = GridLayout.LayoutParams().apply {
            width = cell
            height = cell
            setMargins(0, 0, gap, gap)
        }
        grid.addView(iv, lp)
    }
    body.addView(grid)

    val free = shots.count { it.meta?.cost?.let { c -> c == "0" || c == "0.0" } == true }
    if (free > 0) {
        body.addView(Ui.body(act,
            "$free из ${shots.size} картинок помечены как \$0 — их покрыла подписка.", 12f, Ui.OK_TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(act, 10), 0, 0)
        })
    }
    body.addView(Ui.button(act, "Обновить список", Ui.Kind.PLAIN) { show() })
}

private fun MainActivity.fileMenu(f: java.io.File, meta: ShotMeta?) {
    val items = arrayOf("Открыть", "Поделиться", "Скопировать путь", "Удалить")
    AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
        .setTitle(f.name)
        .setItems(items) { _, which ->
            when (which) {
                0 -> openViewer(f)
                1 -> share(f)
                2 -> copyPath(f)
                3 -> confirmDelete(f)
            }
        }
        .show()
}
