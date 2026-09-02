/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/hash/Hashing.kt
 *  Назначение : контроль целостности загруженных артефактов.
 *  Безопасность: Mojang отдаёт SHA-1 для каждого файла. Без сверки любой
 *               MITM или битая докачка подсунет свой jar, который потом
 *               исполнится в JVM. Сравнение хешей — только constant-time.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (хеши не секрет)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.hash

import java.io.InputStream
import java.security.MessageDigest

object Hashing {

    const val SHA1 = "SHA-1"
    const val SHA256 = "SHA-256"

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0F]
        }
        return String(out)
    }

    fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    fun digest(algorithm: String, data: ByteArray): String =
        hex(MessageDigest.getInstance(algorithm).digest(data))

    fun sha1(data: ByteArray): String = digest(SHA1, data)

    fun sha256(data: ByteArray): String = digest(SHA256, data)

    /**
     * Потоковое хеширование: файл может быть на сотни мегабайт, целиком в память
     * его тянуть нельзя. Поток НЕ закрывается — этим управляет вызывающий.
     */
    fun digestStream(algorithm: String, input: InputStream, bufferSize: Int = 64 * 1024): String {
        val md = MessageDigest.getInstance(algorithm)
        val buf = ByteArray(bufferSize)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun sha1Stream(input: InputStream): String = digestStream(SHA1, input)

    /**
     * Сравнение за постоянное время. Для хеша файла тайминг-атака выглядит
     * надуманной, но эта же функция сравнивает MAC и токены — там она критична,
     * поэтому единая безопасная реализация без исключений из правила.
     */
    fun constantTimeEquals(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    fun constantTimeEqualsHex(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrEmpty() || actual.isNullOrEmpty()) return false
        val e = unhex(expected.lowercase()) ?: return false
        val a = unhex(actual.lowercase()) ?: return false
        return constantTimeEquals(e, a)
    }

    /** Формально валидный SHA-1 из манифеста: ровно 40 hex-символов. */
    fun isValidSha1(s: String?): Boolean =
        s != null && s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
