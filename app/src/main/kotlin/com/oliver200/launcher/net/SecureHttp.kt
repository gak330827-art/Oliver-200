/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : net/SecureHttp.kt
 *  Назначение : единственная точка выхода в сеть.
 *  Безопасность:
 *      1. Каждый URL проходит белый список UrlGuard ДО соединения.
 *      2. Редиректы обрабатываются ВРУЧНУЮ. Это не педантизм: при
 *         автоматическом следовании ответ 302 с чужого сервера уводит
 *         запрос вместе с заголовком Authorization на произвольный хост,
 *         и токен Microsoft утекает одним прыжком. Каждый Location
 *         проверяется тем же белым списком.
 *      3. Соединение обязано быть HttpsURLConnection — понижение до http
 *         через редирект невозможно.
 *      4. Ответ читается с жёстким потолком: сервер не сможет прислать
 *         бесконечный поток и съесть память телефона.
 *      5. Кэш и cookie выключены: незачем хранить ответы с токенами.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (транспорт защищает TLS)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.net

import com.oliver200.launcher.core.net.HostPolicy
import com.oliver200.launcher.core.net.UrlGuard
import com.oliver200.launcher.core.net.UrlRejectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

class HttpException(val code: Int, val bodySnippet: String) :
    IOException("HTTP $code" + if (bodySnippet.isEmpty()) "" else ": $bodySnippet")

object SecureHttp {

    const val USER_AGENT = "Oliver-200/1.0 (Android launcher)"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 3

    /** Потолок для «обычных» JSON-ответов. Скачивание файлов идёт другим путём. */
    const val DEFAULT_MAX_BODY = 24L * 1024 * 1024

    data class Response(val code: Int, val body: ByteArray) {
        fun text(): String = String(body, StandardCharsets.UTF_8)

        // data class с ByteArray: сравнение по содержимому, иначе equals бесполезен.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Response && code == other.code && body.contentEquals(other.body))

        override fun hashCode(): Int = 31 * code + body.contentHashCode()
    }

    suspend fun get(
        url: String,
        policy: HostPolicy,
        headers: Map<String, String> = emptyMap(),
        maxBody: Long = DEFAULT_MAX_BODY,
    ): Response = request("GET", url, policy, headers, null, null, maxBody)

    suspend fun postJson(
        url: String,
        policy: HostPolicy,
        json: String,
        headers: Map<String, String> = emptyMap(),
        maxBody: Long = DEFAULT_MAX_BODY,
    ): Response = request(
        "POST", url, policy, headers,
        json.toByteArray(StandardCharsets.UTF_8), "application/json; charset=utf-8", maxBody,
    )

    suspend fun postForm(
        url: String,
        policy: HostPolicy,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        maxBody: Long = DEFAULT_MAX_BODY,
    ): Response = request(
        "POST", url, policy, headers,
        encodeForm(form).toByteArray(StandardCharsets.UTF_8),
        "application/x-www-form-urlencoded; charset=utf-8", maxBody,
    )

    /**
     * Открывает поток для скачивания файла. Закрыть соединение — задача
     * вызывающего (см. [Downloader]).
     */
    @Throws(IOException::class, UrlRejectedException::class)
    fun openStream(url: String, policy: HostPolicy): Pair<HttpURLConnection, InputStream> {
        val conn = connectFollowingRedirects("GET", url, policy, emptyMap(), null, null)
        val code = conn.responseCode
        if (code !in 200..299) {
            val snippet = readSnippet(conn)
            conn.disconnect()
            throw HttpException(code, snippet)
        }
        return conn to conn.inputStream
    }

    private suspend fun request(
        method: String,
        url: String,
        policy: HostPolicy,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
        maxBody: Long,
    ): Response = withContext(Dispatchers.IO) {
        val conn = connectFollowingRedirects(method, url, policy, headers, body, contentType)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val bytes = stream?.use { readBounded(it, maxBody) } ?: ByteArray(0)
            Response(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Ручное следование за редиректами с повторной проверкой каждого адреса.
     */
    private fun connectFollowingRedirects(
        method: String,
        startUrl: String,
        policy: HostPolicy,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): HttpURLConnection {
        var current = startUrl
        var hops = 0
        while (true) {
            val uri = UrlGuard.require(current, policy)
            val conn = open(uri, method, headers, body, contentType)
            val code = conn.responseCode
            if (code !in listOf(301, 302, 303, 307, 308)) return conn

            if (hops++ >= MAX_REDIRECTS) {
                conn.disconnect()
                throw IOException("Слишком много перенаправлений")
            }
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IOException("Перенаправление без адреса")

            // Относительный Location разрешаем относительно текущего адреса,
            // затем прогоняем через тот же белый список.
            current = try {
                uri.resolve(location).toString()
            } catch (_: IllegalArgumentException) {
                throw IOException("Некорректный адрес перенаправления")
            }
            if (!UrlGuard.isAllowed(current, policy)) {
                throw UrlRejectedException("Перенаправление на запрещённый хост")
            }
        }
    }

    private fun open(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): HttpURLConnection {
        val conn = uri.toURL().openConnection() as? HttpsURLConnection
            ?: throw UrlRejectedException("Соединение не является HTTPS")
        conn.requestMethod = method
        conn.instanceFollowRedirects = false // ключевой момент, см. шапку файла
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", USER_AGENT)
        // Accept-Encoding намеренно НЕ задаём: HttpURLConnection на Android
        // сам добавляет gzip и прозрачно распаковывает ответ. Стоит указать
        // заголовок вручную — распаковка отключается, и мы получим бинарный мусор.
        for ((k, v) in headers) {
            if (isHeaderSafe(k, v)) conn.setRequestProperty(k, v)
        }
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            contentType?.let { conn.setRequestProperty("Content-Type", it) }
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    /** Значение заголовка с переводом строки — это инъекция ещё одного заголовка. */
    private fun isHeaderSafe(name: String, value: String): Boolean {
        if (name.isEmpty() || name.length > 128 || value.length > 8192) return false
        if (name.any { it.code < 0x21 || it.code > 0x7E || it == ':' }) return false
        if (value.any { it.code == 0 || it == '\n' || it == '\r' }) return false
        return true
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) throw IOException("Ответ сервера превысил допустимый размер")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readSnippet(conn: HttpURLConnection): String = try {
        conn.errorStream?.use { String(readBounded(it, 4096), StandardCharsets.UTF_8) }.orEmpty().take(300)
    } catch (_: IOException) {
        ""
    }

    private fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) ->
            urlEncode(k) + "=" + urlEncode(v)
        }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name())
}
