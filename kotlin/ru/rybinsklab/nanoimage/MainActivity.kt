package ru.rybinsklab.nanoimage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Nano Image — клиент генерации картинок по текстовому промпту.
 *
 * Работает с Image API nano-gpt.com и любым совместимым провайдером: адрес и
 * ключ задаются в настройках, провайдеров можно держать несколько и
 * переключаться между ними.
 *
 * Главное, ради чего приложение существует: видно, какие модели покрывает
 * подписка (у nano-gpt это 100 картинок в день бесплатно), а какие списывают
 * деньги с баланса, и почём. Поэтому цена показывается и в списке моделей, и на
 * кнопке генерации, и в подтверждении перед платным запросом, и в итоге — уже
 * фактическая, из GET /api/v1/usage/requests/{id}.
 *
 * Три вкладки: Создать / Галерея / Модели. Всё рисуется программно,
 * без XML-разметки и androidx — сборка идёт в Termux без Gradle.
 */
class MainActivity : Activity() {

    internal val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    internal lateinit var chrome: LinearLayout
    internal lateinit var body: LinearLayout
    internal lateinit var tabsBar: LinearLayout
    internal lateinit var headTitle: TextView
    internal lateinit var headSub: TextView

    /**
     * Тестовый крюк: если приложение запустили с extra "probe"=имя_файла,
     * текущий экран после каждой перерисовки рендерится в PNG в filesDir.
     * Так вёрстку можно разглядеть, не разблокируя телефон: View умеет
     * measure/layout/draw на Canvas без присоединения к окну.
     * В обычной жизни extra не передаётся и код не исполняется.
     */
    internal var probeName: String? = null

    /** Прокрутка тела в probe-рендере, px — чтобы снять нижнюю часть экрана. */
    internal var probeScroll = 0

    internal var screen = SCREEN_TABS
    internal var tab = TAB_CREATE

    // ---------- данные ----------
    internal var models: List<ImgModel> = emptyList()
    internal var modelsNote: String? = null
    internal var loading = false
    internal var quota: Quota? = null
    internal var detailId: String? = null

    // ---------- вкладка «Создать» ----------
    internal var promptText = ""
    internal var resolution = ""
    internal var aspect = ""
    internal var count = 1
    internal var seedText = ""
    internal var refs = ArrayList<File>()
    internal var busy = false
    internal var busyNote = ""
    internal var resultFiles = ArrayList<File>()
    internal var resultIndex = 0
    internal var resultNote = ""
    internal var errorNote = ""

    /** Ошибка экрана-формы провайдера (пустой или не-HTTPS адрес). */
    internal var provError = ""

    // ---------- галерея ----------
    internal var viewerFile: File? = null

    // ---------- модели ----------
    internal var modelFilter = MFilter.ALL
    internal var searchText = ""
    internal var modelListBox: LinearLayout? = null
    internal var modelCountText: TextView? = null
    internal var modelsShown = 60

    internal var resultZoom: ZoomView? = null

    /** Какого провайдера правим на экране-форме; null — добавляем нового. */
    internal var editProvId: String? = null

    companion object {
        internal const val SCREEN_TABS = 0
        internal const val SCREEN_SETTINGS = 1
        internal const val SCREEN_MODEL = 2
        internal const val SCREEN_PROVIDER = 3

        internal const val TAB_CREATE = 0
        internal const val TAB_GALLERY = 1
        internal const val TAB_MODELS = 2

        private const val REQ_REF = 4242
        private const val REQ_STORAGE = 4243
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)

        promptText = Store.prompt(this)
        resolution = Store.resolution(this)
        aspect = Store.aspect(this)
        count = Store.count(this)
        seedText = Store.seed(this)

        probeName = intent?.getStringExtra("probe")
        probeScroll = intent?.getIntExtra("probe_scroll", 0) ?: 0
        tab = (intent?.getIntExtra("probe_tab", TAB_CREATE) ?: TAB_CREATE)
            .coerceIn(TAB_CREATE, TAB_MODELS)
        when (intent?.getIntExtra("probe_screen", SCREEN_TABS) ?: SCREEN_TABS) {
            SCREEN_SETTINGS -> screen = SCREEN_SETTINGS
            SCREEN_PROVIDER -> screen = SCREEN_PROVIDER
        }
        intent?.getStringExtra("probe_model")?.let {
            detailId = it
            screen = SCREEN_MODEL
        }
        intent?.getStringExtra("probe_provider")?.let {
            editProvId = it
            screen = SCREEN_PROVIDER
        }

        buildChrome()
        refreshAll(force = false)
        show()
    }

    /** Повторный am start с другими probe-extra: перерисовать нужный экран. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val extra = intent?.getStringExtra("probe") ?: return
        probeName = extra
        probeScroll = intent.getIntExtra("probe_scroll", 0)
        intent.getIntExtra("probe_tab", -1).takeIf { it >= 0 }?.let {
            tab = it.coerceIn(TAB_CREATE, TAB_MODELS)
            screen = SCREEN_TABS
        }
        when (intent.getIntExtra("probe_screen", SCREEN_TABS)) {
            SCREEN_SETTINGS -> screen = SCREEN_SETTINGS
            SCREEN_PROVIDER -> screen = SCREEN_PROVIDER
        }
        intent.getStringExtra("probe_model")?.let {
            detailId = it
            screen = SCREEN_MODEL
        }
        intent.getStringExtra("probe_provider")?.let {
            editProvId = it
            screen = SCREEN_PROVIDER
        }
        show()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    // ================= каркас =================

    private fun buildChrome() {
        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        chrome.addView(buildHeader(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        chrome.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        tabsBar = LinearLayout(this)
        chrome.addView(tabsBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(chrome)
    }

    /** Шапка: название, счётчик квоты, кнопки «обновить» и «настройки». */
    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.CARD)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val row = Ui.hRow(this)
        val titles = Ui.vRow(this)
        headTitle = Ui.title(this, "Nano Image", 19f)
        headSub = Ui.body(this, "…", 12f)
        titles.addView(headTitle)
        titles.addView(headSub)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.iconButton(this, "⟳") { refreshAll(force = false); toast("Обновляю…") })
        row.addView(Ui.iconButton(this, "⚙") { openSettings() })
        bar.addView(row)
        return bar
    }

    private fun drawTabs() {
        tabsBar.removeAllViews()
        tabsBar.setBackgroundColor(Ui.CARD)
        tabsBar.elevation = dp(3).toFloat()
        tabsBar.setPadding(dp(8), dp(6), dp(8), dp(8))
        val names = listOf("Создать", "Галерея", "Модели")
        names.forEachIndexed { i, n ->
            val selected = tab == i && screen == SCREEN_TABS
            val t = TextView(this).apply {
                text = n
                setTextSize(13f)
                gravity = Gravity.CENTER
                setTextColor(if (selected) Ui.ACCENT_DARK else Ui.SUB)
                setTypeface(typeface,
                    if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                background = if (selected) Ui.round(this@MainActivity, Ui.ACCENT_SOFT, 12) else null
                setPadding(dp(6), dp(9), dp(6), dp(9))
                setOnClickListener { tab = i; screen = SCREEN_TABS; show() }
            }
            tabsBar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Перерисовать экран целиком. */
    internal fun show() {
        hideViewer()
        body.removeAllViews()
        when {
            screen == SCREEN_SETTINGS -> buildSettings()
            screen == SCREEN_MODEL -> buildModelDetail()
            screen == SCREEN_PROVIDER -> buildProviderForm()
            tab == TAB_CREATE -> buildCreate()
            tab == TAB_GALLERY -> buildGallery()
            else -> buildModels()
        }
        tabsBar.visibility = if (screen == SCREEN_TABS) View.VISIBLE else View.GONE
        drawTabs()
        updateHeader()
        probeName?.let { p -> ui.post { renderProbeToFile(p) } }
    }

    /** Отрисовка текущего экрана в PNG — только при запуске с extra "probe". */
    private fun renderProbeToFile(name: String) {
        try {
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            chrome.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            chrome.layout(0, 0, w, h)
            // Прокрутка для съёмки нижней части экрана (параметры, кнопка).
            if (probeScroll > 0) {
                (chrome.getChildAt(1) as? ScrollView)?.scrollTo(0, probeScroll)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Ui.BG)
            chrome.draw(canvas)
            val f = File(filesDir, "probe-$name.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            // Дамп для отладки вёрстки: что реально лежит в теле экрана.
            val sb = StringBuilder()
            sb.append("screen=").append(screen).append(" tab=").append(tab)
                .append(" chrome=").append(chrome.width).append("x").append(chrome.height)
                .append(" bodyChildren=").append(body.childCount).append('\n')
            for (i in 0 until body.childCount.coerceAtMost(12)) {
                val v = body.getChildAt(i)
                sb.append("  [").append(i).append("] ")
                    .append(v.javaClass.simpleName)
                    .append(" ").append(v.width).append("x").append(v.height)
                    .append(" top=").append(v.top)
                    .append(" vis=").append(v.visibility)
                    .append(" txt=").append((v as? TextView)?.text?.take(60) ?: "")
                    .append('\n')
            }
            File(filesDir, "probe-dump.txt").writeText(sb.toString())
        } catch (e: Exception) {
            runCatching {
                File(filesDir, "probe-dump.txt").appendText("ERR ${e.javaClass.simpleName} ${e.message}\n")
            }
        }
    }

    private fun updateHeader() {
        val p = Store.active(this)
        headTitle.text = when {
            screen == SCREEN_SETTINGS -> "Настройки"
            screen == SCREEN_MODEL -> (models.firstOrNull { it.id == detailId }?.name ?: "Модель")
            screen == SCREEN_PROVIDER -> if (editProvId == null) "Новый провайдер" else "Провайдер"
            tab == TAB_GALLERY -> "Галерея"
            tab == TAB_MODELS -> "Модели"
            // Свой провайдер подписываем в шапке, чтобы было видно, куда уйдёт запрос.
            p.id != Store.NANO_ID -> p.name
            else -> "Nano Image"
        }
        headSub.text = quotaLine()
        headSub.setTextColor(if (quota == null) Ui.SUB else Ui.OK_TEXT)
    }

    /** Строка квоты в шапке: сколько бесплатных картинок осталось сегодня. */
    internal fun quotaLine(): String {
        val p = Store.active(this)
        val prefix = if (p.id == Store.NANO_ID) "" else "${p.name} · "
        val q = quota ?: return when {
            !Store.configured(this) -> prefix + "не задан API-ключ"
            loading -> "загружаю данные…"
            p.id != Store.NANO_ID -> prefix + "дневной квоты нет — модели платные"
            else -> "квота неизвестна"
        }
        if (!q.active) return prefix + "подписка не активна — все модели платные"
        val left = q.remaining.coerceAtLeast(0)
        val base = if (left <= 0) "0 из ${q.limit} сегодня — бесплатные кончились"
        else "осталось $left из ${q.limit} сегодня"
        return prefix + base + (if (q.resetAt > 0) " · сброс ${fmtUtc(q.resetAt)} UTC" else "")
    }

    private fun fmtUtc(ms: Long): String {
        val f = SimpleDateFormat("dd.MM HH:mm", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(java.util.Date(ms))
    }

    internal fun openSettings() {
        screen = SCREEN_SETTINGS
        show()
    }

    /** Экран-форма провайдера: id == null — добавляем нового. */
    internal fun openProviderForm(id: String?) {
        editProvId = id
        screen = SCREEN_PROVIDER
        show()
    }

    /**
     * Сделать провайдера активным. Каталог, цены, подписочные модели и квота у
     * каждого свои, поэтому старое состояние сбрасываем и тянем данные заново.
     */
    internal fun switchProvider(id: String) {
        if (Store.activeId(this) != id) {
            Store.setActive(this, id)
            models = emptyList()
            modelsNote = null
            quota = null
            errorNote = ""
            resultFiles = ArrayList()
            modelFilter = MFilter.ALL
            searchText = ""
            modelsShown = 60
        }
        refreshAll(force = true)
        show()
    }

    internal fun backToTabs(t: Int = tab) {
        screen = SCREEN_TABS
        tab = t
        show()
    }

    internal fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ================= загрузка данных =================

    internal fun refreshAll(force: Boolean) {
        if (loading) return
        loading = true
        updateHeader()
        Thread {
            val q = if (Store.configured(this)) Api.quota(this) else null
            val (list, note) = Api.catalog(this, force)
            val allow = Store.allowlist(this)
            ui.post {
                loading = false
                if (list.isNotEmpty()) models = list
                modelsNote = note
                // Квота прежнего провайдера не должна оставаться в шапке после переключения.
                quota = q
                // выбрана несуществующая модель — берём первую подписочную или первую вообще
                val cur = Store.model(this)
                if (models.none { it.id == cur }) {
                    val fallback = models.firstOrNull { it.id in allow }?.id
                        ?: models.firstOrNull()?.id ?: Store.DEFAULT_MODEL
                    Store.setModel(this, fallback)
                }
                if (resolution.isEmpty() && models.isNotEmpty()) syncParamsWithModel()
                show()
            }
        }.start()
    }

    internal fun refreshQuota() {
        if (!Store.configured(this)) return
        Thread {
            val q = Api.quota(this) ?: return@Thread
            ui.post { quota = q; updateHeader() }
        }.start()
    }

    /** Разрешение/соотношение/число картинок подгоняем под выбранную модель. */
    internal fun syncParamsWithModel() {
        val m = selectedModel() ?: return
        if (resolution.isNotEmpty() && resolution !in m.resolutions) resolution = ""
        if (aspect.isNotEmpty() && aspect !in m.aspects) aspect = ""
        count = count.coerceIn(1, m.maxImages.coerceAtLeast(1))
    }

    internal fun selectedModel(): ImgModel? {
        val id = Store.model(this)
        return models.firstOrNull { it.id == id }
    }

    internal fun isSub(m: ImgModel): Boolean = Store.isSub(this, m.id)

    // ================= генерация и деньги =================

    /**
     * Точка входа кнопки «Нарисовать».
     *
     * Бесплатный путь (модель из подписки и дневная квота не кончилась) идёт
     * сразу, без диалога: цена на кнопке и так $0. Всё, что спишет деньги с
     * баланса, обязательно показываем с суммой и подтверждаем.
     */
    internal fun onGenerateClick() {
        if (busy) return
        val m = selectedModel()
        if (m == null) { toast("Сначала выберите модель"); backToTabs(TAB_MODELS); return }
        if (!Store.configured(this)) { openSettings(); return }

        val prompt = promptText.trim()
        if (prompt.isEmpty()) { errorNote = "Введите промпт — что нарисовать"; show(); return }
        val maxChars = m.maxPromptChars
        if (maxChars > 0 && prompt.length > maxChars) {
            errorNote = "Промпт ${prompt.length} символов, а $m.id принимает не больше $maxChars"
            show()
            return
        }

        val q = quota
        val sub = isSub(m)
        val quotaLeft = q?.remaining ?: -1
        val quotaGone = q != null && q.active && quotaLeft <= 0 && !q.allowOverage

        if (sub && !quotaGone) {
            startGenerate(paygo = false)
            return
        }

        val withRef = refs.isNotEmpty()
        val price = m.effectivePrice(resolution.ifEmpty { null }, withRef)
        val total = if (price == null) null else price * count
        val paygo = sub && quotaGone

        val provName = Store.active(this).name

        val msg = buildString {
            append("Модель: ").append(m.name).append("  (").append(m.id).append(")\n")
            append(if (m.nsfw) "Без цензуры" else "С цензурой")
            append(" · ").append(m.caps()).append("\n\n")
            if (sub && quotaGone) {
                append("Сегодняшние ").append(q?.limit ?: 100)
                    .append(" бесплатных картинок израсходованы.\n")
                append("Сброс квоты: ").append(fmtUtc(q?.resetAt ?: 0L)).append(" UTC.\n\n")
                append("Эта модель входит в подписку, но сейчас генерация пойдёт платно")
                append(" (billing_mode=paygo).\n\n")
            } else {
                append("Подписка эту модель НЕ покрывает.\n\n")
            }
            append("Цена: ")
            if (price == null) append("не указана сервисом\n")
            else {
                append(fmtUsd(price)).append(" за картинку")
                if (count > 1) append(" × ").append(count.toString())
                    .append(" = ").append(fmtUsd(total))
                append("\n")
            }
            if (withRef) append("Учтён референс: цена может быть выше базовой.\n")
            append("\nСпишется с баланса ").append(provName).append(".")
            if (q != null && q.active && !quotaGone) {
                append("\nКвота подписки сегодня: ").append(q.remaining.toString())
                    .append(" из ").append(q.limit.toString())
                    .append(" — эта генерация её не изменит.")
            }
        }

        val label = if (total == null) "Нарисовать (цена неизвестна)"
        else "Нарисовать за ${fmtUsd(total)}"

        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Генерация спишет деньги")
            .setMessage(msg)
            .setNegativeButton("Отмена", null)
            .setPositiveButton(label) { _, _ -> startGenerate(paygo) }
            .show()
    }

    internal fun startGenerate(paygo: Boolean) {
        val m = selectedModel() ?: return
        val prompt = promptText.trim()
        Store.setPrompt(this, prompt)
        Store.setResolution(this, resolution)
        Store.setAspect(this, aspect)
        Store.setCount(this, count)
        Store.setSeed(this, seedText)

        val seed = seedText.trim().toLongOrNull()
        val refBytes = refs.mapNotNull { runCatching { it.readBytes() }.getOrNull() }

        busy = true
        errorNote = ""
        resultNote = ""
        resultFiles = ArrayList()
        busyNote = "Рисую: ${m.id}${if (count > 1) " × $count" else ""}…\nМожет занять до минуты."
        show()

        Thread {
            val req = GenReq(
                model = m.id,
                prompt = prompt,
                n = count,
                resolution = resolution.ifEmpty { null },
                aspect = aspect.ifEmpty { null },
                seed = seed,
                refs = refBytes,
                paygo = paygo
            )
            val out = Api.generate(this, req)
            if (!out.ok) {
                ui.post {
                    busy = false
                    busyNote = ""
                    errorNote = "Не получилось: " + out.error +
                        (if (out.code > 0) " (HTTP ${out.code})" else "")
                    refreshQuota()
                    show()
                }
                return@Thread
            }
            val params = mapOf<String, Any?>(
                "resolution" to resolution.ifEmpty { null },
                "aspect_ratio" to aspect.ifEmpty { null },
                "quality" to null,
                "seed" to seed
            )
            val (files, saveErr) = Gallery.saveBatch(
                this, m.id, prompt, params, out.images, out.requestId, out.cost, out.costScope
            )
            ui.post {
                busy = false
                busyNote = ""
                resultFiles = ArrayList(files)
                resultIndex = 0
                resultNote = resultText(m, out, files, saveErr)
                if (saveErr.isNotEmpty()) errorNote = saveErr
                refreshQuota()
                show()
            }
        }.start()
    }

    /** Что пишем под картинкой: фактическая цена важнее прайса из каталога. */
    private fun resultText(m: ImgModel, out: GenOut, files: List<File>, saveErr: String): String {
        val sb = StringBuilder()
        when {
            out.cost == null -> sb.append("Цена запроса неизвестна (сервер не отдал биллинг).")
            out.cost <= 0.0 -> sb.append("$0 — покрыто подпиской, с баланса ничего не списано.")
            else -> sb.append("Списано с баланса: ").append(fmtUsd(out.cost)).append(".")
        }
        sb.append("\nМодель: ").append(m.id)
        if (out.requestId.isNotEmpty()) sb.append(" · request ").append(out.requestId.take(24)).append("…")
        if (files.isNotEmpty()) {
            sb.append("\nСохранено: ").append(files.size).append(" → ")
                .append(files[0].parentFile?.absolutePath ?: Store.outDir(this))
        }
        if (saveErr.isNotEmpty()) sb.append("\n").append(saveErr)
        return sb.toString()
    }

    internal fun showResultBitmap(index: Int) {
        val f = resultFiles.getOrNull(index) ?: return
        resultIndex = index
        val bmp: Bitmap = Gallery.full(f) ?: return
        resultZoom?.setImageDrawable(BitmapDrawable(resources, bmp))
        updateResultPager()
    }

    internal var resultPager: TextView? = null

    internal fun updateResultPager() {
        val total = resultFiles.size
        resultPager?.text = if (total <= 1) "" else "${resultIndex + 1} из $total"
    }

    // ================= референсы =================

    internal fun pickRef() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQ_REF)
        } catch (e: Exception) {
            toast("Нет приложения для выбора файла")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_REF || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val m = selectedModel()
        val limit = (m?.maxInputImages ?: 1).coerceAtLeast(1)
        if (refs.size >= limit) {
            toast("${m?.id ?: "Модель"} принимает не больше $limit референсов")
            return
        }
        val (f, note) = Refs.import(this, uri)
        if (f == null) {
            toast(note)
            return
        }
        refs.add(f)
        show()
    }

    internal fun dropRef(f: File) {
        refs.remove(f)
        Refs.remove(this, f)
        show()
    }

    // ================= шаринг =================

    internal fun share(f: File) {
        val uri = ShareProvider.uriFor(this, f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Поделиться картинкой"))
        } catch (e: Exception) {
            toast("Не удалось поделиться: ${e.javaClass.simpleName}")
        }
    }

    internal fun copyPath(f: File) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", f.absolutePath))
        toast("Путь скопирован: ${f.name}")
    }

    // ================= доступ к памяти =================

    /**
     * Без роли «Все файлы» в общую Pictures/.nano не записать (scoped storage),
     * поэтому показываем явную инструкцию, а не молча падаем при сохранении.
     */
    internal fun requestStorageAccess() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivityForResult(intent, REQ_STORAGE)
        } catch (e: Exception) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_STORAGE)
            } catch (e2: Exception) {
                toast("Не удалось открыть настройки разрешений")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из системных настроек разрешений — перерисовать и обновить квоту.
        if (Store.configured(this)) refreshQuota()
        show()
    }

    override fun onBackPressed() {
        when {
            viewerFile != null -> hideViewer()
            screen == SCREEN_MODEL -> backToTabs(TAB_MODELS)
            screen == SCREEN_PROVIDER -> openSettings()
            screen == SCREEN_SETTINGS -> backToTabs(TAB_CREATE)
            else -> super.onBackPressed()
        }
    }

    // ================= полноэкранный просмотр =================

    internal fun openViewer(f: File) {
        viewerFile = f
        showViewer()
    }

    /** Полупрозрачная кнопка поверх чёрного просмотра. */
    private fun glassButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextSize(14f)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = Ui.round(this@MainActivity, Color.parseColor("#33FFFFFF"), 14,
            Color.parseColor("#55FFFFFF"))
        val v = dp(11)
        setPadding(dp(10), v, dp(10), v)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { rightMargin = dp(8) }
    }

    private fun showViewer() {        val f = viewerFile ?: return
        hideViewerViews()
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            tag = "viewer"
        }
        val bmp = Gallery.full(f)
        if (bmp != null) {
            overlay.addView(ZoomView(this).apply {
                setImageDrawable(BitmapDrawable(resources, bmp))
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val bottom = Ui.vRow(this).apply {
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val meta = Gallery.metaFor(f)
        bottom.addView(Ui.body(this, f.name, 12f, Color.WHITE))
        if (meta != null) {
            bottom.addView(Ui.body(this, meta.model + (if (meta.cost.isNotEmpty()) " · $" + meta.cost else ""),
                12f, Color.parseColor("#C7CBD4")))
            if (meta.prompt.isNotEmpty()) {
                bottom.addView(Ui.body(this, meta.prompt.take(300), 13f, Color.WHITE))
            }
        }
        val actions = Ui.hRow(this)
        actions.addView(glassButton("Закрыть") { hideViewer() })
        actions.addView(glassButton("Поделиться") { share(f) })
        actions.addView(glassButton("Удалить") { confirmDelete(f) })
        bottom.addView(actions)
        overlay.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        overlay.setOnClickListener { }
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun hideViewerViews() {
        val v = root.findViewWithTag<View>("viewer")
        if (v != null) root.removeView(v)
    }

    internal fun hideViewer() {
        viewerFile = null
        hideViewerViews()
    }

    internal fun confirmDelete(f: File) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Удалить файл?")
            .setMessage("${f.name}\n\nФайл будет удалён из ${f.parentFile?.absolutePath}")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                val ok = Gallery.delete(f)
                if (viewerFile == f) hideViewer()
                toast(if (ok) "Удалено" else "Не удалось удалить")
                show()
            }
            .show()
    }
}
