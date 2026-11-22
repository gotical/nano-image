package ru.rybinsklab.nanoimage

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Оформление: светлая тема, всё на плавных скруглениях, без резких рамок.
 *
 * Рисуем программно (как в ~/helper-console), поэтому каждый помощник сразу
 * задаёт и фон, и отступы: иначе MainActivity превращается в простыню
 * setPadding/setTextSize.
 */
object Ui {

    val BG = Color.parseColor("#F5F7FA")
    val CARD = Color.WHITE
    val ACCENT = Color.parseColor("#6366F1")
    val ACCENT2 = Color.parseColor("#8B5CF6")
    val ACCENT_DARK = Color.parseColor("#4F46E5")
    val ACCENT_SOFT = Color.parseColor("#EEF0FE")
    val PRESSED = Color.parseColor("#E8EAF9")
    val TEXT = Color.parseColor("#1F2430")
    val SUB = Color.parseColor("#6B7280")
    val LINE = Color.parseColor("#ECEFF4")
    val DISABLED = Color.parseColor("#D8DBE6")

    // Бейджи: подписочная модель / без цензуры / с цензурой
    val OK = Color.parseColor("#10B981")
    val OK_BG = Color.parseColor("#E4F7EF")
    val OK_TEXT = Color.parseColor("#06714F")
    val UNC = Color.parseColor("#EC4899")
    val UNC_BG = Color.parseColor("#FDE9F2")
    val UNC_TEXT = Color.parseColor("#9D174D")
    val CEN = Color.parseColor("#94A3B8")
    val CEN_BG = Color.parseColor("#F1F5F9")
    val CEN_TEXT = Color.parseColor("#475569")
    val WARN_BG = Color.parseColor("#FEF3E2")
    val WARN_TEXT = Color.parseColor("#92400E")
    val WARN = Color.parseColor("#F59E0B")
    val BAD = Color.parseColor("#EF4444")
    val BAD_BG = Color.parseColor("#FDECEC")
    val BAD_TEXT = Color.parseColor("#B42318")

    enum class Kind { PRIMARY, PLAIN, SOFT, DANGER }

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    /** Скруглённый прямоугольник: основа всех фонов в приложении. */
    fun round(c: Context, color: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(c, radiusDp).toFloat()
            if (stroke != null) setStroke(dp(c, strokeDp), stroke)
        }

    /** Плавный переход акцентных цветов — для главной кнопки и выбранных чипов. */
    fun gradient(c: Context, radiusDp: Int, from: Int, to: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(from, to)).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(c, radiusDp).toFloat()
        }

    /** Белая карточка со скруглением 18 dp и мягкой тенью. */
    fun card(c: Context, padDp: Int = 16): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, padDp)
        setPadding(p, p, p, p)
        background = round(c, CARD, 18)
        elevation = dp(c, 2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 12) }
    }

    /** Кликабельная карточка: нажатие подсвечивает фон вместо ripple поверх тени. */
    fun pressableCard(c: Context, padDp: Int = 14, onClick: () -> Unit): LinearLayout =
        card(c, padDp).apply {
            isClickable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), round(c, PRESSED, 18))
                addState(intArrayOf(), round(c, CARD, 18))
            }
            setOnClickListener { onClick() }
        }

    fun button(c: Context, text: String, kind: Kind = Kind.PLAIN, onClick: () -> Unit): Button =
        Button(c).apply {
            this.text = text
            setTextSize(15f)
            isAllCaps = false
            setTypeface(typeface, if (kind == Kind.PRIMARY) Typeface.BOLD else Typeface.NORMAL)
            val r = 16
            background = when (kind) {
                Kind.PRIMARY -> StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), round(c, DISABLED, r))
                    addState(intArrayOf(android.R.attr.state_pressed), gradient(c, r, ACCENT_DARK, ACCENT))
                    addState(intArrayOf(), gradient(c, r, ACCENT, ACCENT2))
                }
                Kind.DANGER -> StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), round(c, Color.parseColor("#F8C9C9"), r))
                    addState(intArrayOf(), round(c, BAD_BG, r, BAD))
                }
                Kind.SOFT -> StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), round(c, PRESSED, r))
                    addState(intArrayOf(), round(c, ACCENT_SOFT, r))
                }
                Kind.PLAIN -> StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), round(c, PRESSED, r))
                    addState(intArrayOf(), round(c, CARD, r, LINE))
                }
            }
            setTextColor(
                when (kind) {
                    Kind.PRIMARY -> Color.WHITE
                    Kind.DANGER -> BAD_TEXT
                    Kind.SOFT -> ACCENT_DARK
                    Kind.PLAIN -> TEXT
                }
            )
            val vh = dp(c, 12)
            setPadding(dp(c, 16), vh, dp(c, 16), vh)
            minHeight = dp(c, 48)
            minimumHeight = dp(c, 48)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(c, 4), 0, dp(c, 4)) }
            stateListAnimator = null
        }

    /** Пилюля-фильтр: выбранный вариант подсвечен акцентом. */
    fun chip(c: Context, text: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(c).apply {
            this.text = text
            setTextSize(13f)
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (selected) Color.WHITE else SUB)
            background = if (selected) gradient(c, 14, ACCENT, ACCENT2) else round(c, CARD, 14, LINE)
            val h = dp(c, 9)
            setPadding(dp(c, 14), h, dp(c, 14), h)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(c, 8) }
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** Маленький бейдж: SUB / U / C. */
    fun badge(c: Context, text: String, fg: Int, bg: Int): TextView = TextView(c).apply {
        this.text = text
        setTextSize(10f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(fg)
        background = round(c, bg, 8)
        gravity = Gravity.CENTER
        val h = dp(c, 3)
        setPadding(dp(c, 7), h, dp(c, 7), h)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            rightMargin = dp(c, 7)
        }
    }

    /** Баннер состояния: квота кончилась, нет ключа, ошибка. */
    fun banner(c: Context, text: String, bg: Int, fg: Int): TextView = TextView(c).apply {
        this.text = text
        setTextSize(13f)
        setTextColor(fg)
        setLineSpacing(dp(c, 2).toFloat(), 1f)
        background = round(c, bg, 14)
        val p = dp(c, 14)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 12) }
    }

    fun title(c: Context, text: String, sp: Float = 20f): TextView = TextView(c).apply {
        this.text = text
        setTextSize(sp)
        setTextColor(TEXT)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(c: Context, text: String, sp: Float = 14f, color: Int? = null): TextView =
        TextView(c).apply {
            this.text = text
            setTextSize(sp)
            setTextColor(color ?: SUB)
            setLineSpacing(dp(c, 2).toFloat(), 1f)
        }

    fun mono(c: Context, text: String, sp: Float = 12f, color: Int? = null): TextView =
        TextView(c).apply {
            this.text = text
            setTextSize(sp)
            setTypeface(Typeface.MONOSPACE)
            setTextColor(color ?: TEXT)
        }

    fun field(
        c: Context,
        hint: String,
        value: String = "",
        multiline: Boolean = false,
        secret: Boolean = false,
        number: Boolean = false
    ): EditText = EditText(c).apply {
        this.hint = hint
        setText(value)
        setTextSize(15f)
        setTextColor(TEXT)
        setHintTextColor(Color.parseColor("#A8AFBD"))
        inputType = when {
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (multiline) {
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        background = round(c, CARD, 14, LINE)
        val p = dp(c, 13)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 10) }
    }

    fun hScroll(c: Context, content: View): HorizontalScrollView = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        addView(content)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(c, 8) }
    }

    fun hRow(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun vRow(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
    }

    fun label(c: Context, text: String): TextView = body(c, text, 12f, SUB).apply {
        setPadding(0, dp(c, 4), 0, dp(c, 5))
        setTypeface(typeface, Typeface.BOLD)
    }

    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))
        ).apply { setMargins(0, dp(c, 10), 0, dp(c, 10)) }
    }

    /** Переключатель без material-зависимостей: пилюля «вкл/выкл». */
    fun toggle(c: Context, text: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = hRow(c)
        row.addView(body(c, text, 14f, TEXT), LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        row.addView(TextView(c).apply {
            this.text = if (checked) "вкл" else "выкл"
            setTextSize(12f)
            gravity = Gravity.CENTER
            setTextColor(if (checked) Color.WHITE else SUB)
            background = if (checked) round(c, OK, 12) else round(c, CEN_BG, 12, LINE)
            val h = dp(c, 7)
            setPadding(dp(c, 16), h, dp(c, 16), h)
            isClickable = true
            setOnClickListener { onToggle(!checked) }
        })
        return row
    }

    /** Кнопка-иконка в шапке (шестерёнка, обновление). */
    fun iconButton(c: Context, glyph: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = glyph
        setTextSize(17f)
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        background = round(c, CARD, 12, LINE)
        val size = dp(c, 40)
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = dp(c, 8)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
