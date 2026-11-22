package ru.rybinsklab.nanoimage

/**
 * Минимальный JSON без внешних зависимостей.
 *
 * Сборка идёт без Gradle, поэтому ни Gson, ни org.json здесь нет, а контракт
 * nano-gpt нужно и разбирать (каталог из 264 моделей, квоты, ответ генерации),
 * и собирать (тело POST /api/v1/images, sidecar с метаданными).
 *
 * Значения чисел возвращаются как Long или Double — при разборе цен это важно:
 * pricing.per_image приходит как {"1024x1024": 0.02}, и 0.02 обязан остаться
 * Double, иначе цена округлится до нуля.
 */
class Js(private val s: String) {

    private var p = 0

    fun top(): Any? = value()

    private fun ws() {
        while (p < s.length) {
            val c = s[p]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++ else break
        }
    }

    private fun value(): Any? {
        ws()
        if (p >= s.length) return null
        return when (s[p]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { lit("true"); true }
            'f' -> { lit("false"); false }
            'n' -> { lit("null"); null }
            else -> num()
        }
    }

    private fun lit(want: String) {
        if (s.startsWith(want, p)) p += want.length
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        p++ // {
        ws()
        if (p < s.length && s[p] == '}') { p++; return m }
        while (p < s.length) {
            ws()
            val k = str() ?: return m
            ws()
            if (p < s.length && s[p] == ':') p++
            m[k] = value()
            ws()
            if (p < s.length && s[p] == ',') { p++; continue }
            if (p < s.length && s[p] == '}') { p++; return m }
            return m
        }
        return m
    }

    private fun arr(): List<Any?> {
        val a = ArrayList<Any?>()
        p++ // [
        ws()
        if (p < s.length && s[p] == ']') { p++; return a }
        while (p < s.length) {
            a.add(value())
            ws()
            if (p < s.length && s[p] == ',') { p++; continue }
            if (p < s.length && s[p] == ']') { p++; return a }
            return a
        }
        return a
    }

    private fun str(): String? {
        if (p >= s.length || s[p] != '"') return null
        p++
        val sb = StringBuilder()
        while (p < s.length) {
            val c = s[p]
            if (c == '"') { p++; return sb.toString() }
            if (c == '\\') {
                p++
                if (p >= s.length) break
                when (s[p]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\u0008')
                    'f' -> sb.append('\u000C')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        // суррогатная пара: \uD83D\uDE00 — эмодзи в промптах встречаются
                        if (p + 4 < s.length) {
                            val code = s.substring(p + 1, p + 5).toIntOrNull(16)
                            if (code != null) { sb.append(code.toChar()); p += 4 }
                        }
                    }
                    else -> sb.append(s[p])
                }
                p++
            } else {
                sb.append(c)
                p++
            }
        }
        return sb.toString()
    }

    private fun num(): Any {
        val start = p
        while (p < s.length) {
            val c = s[p]
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c in '0'..'9')) p++ else break
        }
        if (p == start) { p++; return 0L }
        val raw = s.substring(start, p)
        return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
    }

    companion object {

        /** Экранирование значения для сборки JSON-строки. */
        fun esc(v: String?): String {
            if (v == null) return ""
            val sb = StringBuilder(v.length + 16)
            for (c in v) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\u0008' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else ->
                        if (c < ' ' || c.code == 0x2028 || c.code == 0x2029) {
                            sb.append(String.format("\\u%04x", c.code))
                        } else sb.append(c)
                }
            }
            return sb.toString()
        }

        /**
         * Сборка JSON из обычных Map/List/String/Number/Boolean.
         * Нужна для тела запроса и sidecar-файла рядом с картинкой.
         */
        fun encode(v: Any?): String = when (v) {
            null -> "null"
            is Boolean -> v.toString()
            is Number -> v.toString()
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") { e ->
                "\"" + esc(e.key?.toString()) + "\":" + encode(e.value)
            }
            is List<*> -> v.joinToString(",", "[", "]") { encode(it) }
            is Array<*> -> v.joinToString(",", "[", "]") { encode(it) }
            else -> "\"" + esc(v.toString()) + "\""
        }
    }
}

/** Достаёт поле из распарсенной карты как строку. */
fun Any?.jstr(key: String): String {
    val v = (this as? Map<*, *>)?.get(key)
    return if (v == null) "" else v.toString()
}

/** Достаёт поле как Long (числа в JSON приходят и как Long, и как Double). */
fun Any?.jlong(key: String): Long? {
    return when (val v = (this as? Map<*, *>)?.get(key)) {
        is Long -> v
        is Double -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }
}

/** Достаёт поле как Double. */
fun Any?.jdbl(key: String): Double? {
    return when (val v = (this as? Map<*, *>)?.get(key)) {
        is Double -> v
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}

fun Any?.jbool(key: String): Boolean? = (this as? Map<*, *>)?.get(key) as? Boolean

fun Any?.jmap(key: String): Map<String, Any?>? = (this as? Map<*, *>)?.get(key) as? Map<String, Any?>

fun Any?.jlist(key: String): List<Any?>? = (this as? Map<*, *>)?.get(key) as? List<Any?>

/** Любое число в Double — для цен, которые приходят то Long, то Double. */
fun Any?.asDouble(): Double? = when (this) {
    is Double -> this
    is Long -> this.toDouble()
    is Int -> this.toDouble()
    is String -> this.toDoubleOrNull()
    else -> null
}
