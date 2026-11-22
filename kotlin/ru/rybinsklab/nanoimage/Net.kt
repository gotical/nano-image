package ru.rybinsklab.nanoimage

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP-слой провайдера. Только HTTPS (незашифрованный трафик запрещён
 * манифестом), адрес и ключ подставляет вызывающий код из активного
 * провайдера; авторизация — заголовком Authorization: Bearer.
 *
 * Ответ генерации держит картинки в base64 (одна 1024px PNG ≈ 1.5 МБ текста),
 * поэтому таймаут чтения для POST большой, а тело читается целиком.
 */
object Net {

    private const val TAG = "NanoImage"

    class Resp(
        val code: Int,
        val body: String,
        val requestId: String,
        val transportError: String?
    ) {
        val ok: Boolean get() = code in 200..299 && transportError == null

        fun json(): Any? = try {
            Js(body).top()
        } catch (e: Exception) {
            null
        }

        /**
         * Человекочитаемая причина отказа. nano-gpt отдаёт {"error":{"message":...}},
         * но иногда и просто текст, поэтому разбираем оба случая.
         */
        fun message(): String {
            if (transportError != null) return transportError
            val j = json()
            val err = j.jmap("error")
            val m = err?.jstr("message") ?: ""
            if (m.isNotEmpty()) {
                val t = err.jstr("type")
                return if (t.isNotEmpty()) "$m ($t)" else m
            }
            if (j != null) return j.jstr("message").ifEmpty { j.jstr("error") }
            val short = body.trim().replace("\n", " ")
            return if (short.isEmpty()) "HTTP $code" else "HTTP $code: ${short.take(300)}"
        }
    }

    fun get(url: String, key: String? = null, timeoutMs: Int = 60_000): Resp =
        send(url, "GET", null, key, timeoutMs, emptyMap())

    fun post(
        url: String,
        payload: String,
        key: String? = null,
        timeoutMs: Int = 300_000,
        headers: Map<String, String> = emptyMap()
    ): Resp = send(url, "POST", payload, key, timeoutMs, headers)

    private fun send(
        url: String,
        method: String,
        payload: String?,
        key: String?,
        timeoutMs: Int,
        headers: Map<String, String>
    ): Resp {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "nano-image-android/1.0")
                if (!key.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer $key")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val rid = header(c, "X-Request-ID").orEmpty()
            val stream: InputStream? =
                if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = stream?.let { readAll(it) } ?: ""
            Resp(code, text, rid, null)
        } catch (e: Exception) {
            // Ключ и тело запроса в лог не пишем: в логе только класс ошибки и URL без query.
            Log.w(TAG, "$method ${url.takeWhile { it != '?' }}: ${e.javaClass.simpleName}")
            Resp(0, "", "", "${e.javaClass.simpleName}: ${e.message ?: "нет соединения"}")
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Заголовки HTTP регистронезависимы по спецификации, но на практике надёжнее перебрать. */
    private fun header(c: HttpURLConnection, name: String): String? {
        c.getHeaderField(name)?.let { return it }
        val lower = name.lowercase()
        for ((k, v) in c.headerFields) {
            if (k != null && k.lowercase() == lower && v.isNotEmpty()) return v[0]
        }
        return null
    }

    private fun readAll(s: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /** Скачивание картинки по URL — на случай, если nano-gpt вернул url вместо b64_json. */
    fun bytes(url: String, timeoutMs: Int = 120_000): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = timeoutMs
            }
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download: ${e.javaClass.simpleName}")
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }
}
