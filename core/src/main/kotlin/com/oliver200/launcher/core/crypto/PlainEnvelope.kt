/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/crypto/PlainEnvelope.kt
 *
 *  ВАРИАНТ    : ☆ БЕЗ ШИФРОВАНИЯ ☆  (отладочный, ТОЛЬКО debug-сборка)
 *  Парный файл: core/crypto/CryptoEnvelope.kt — тот же API С шифрованием.
 *
 *  Зачем он существует: когда лаунчер отдаёт «контейнер повреждён»,
 *  надо уметь посмотреть, что реально легло в хранилище, не подбирая ключ
 *  из Keystore. Формат и API совпадают с боевым один в один, поэтому
 *  переключение — это одна строка в фабрике, а не переписывание слоя.
 *
 *  ЧЕГО ЗДЕСЬ НЕТ: конфиденциальности. Payload лежит открытым текстом.
 *  Целостность есть — SHA-256 по контексту и данным ловит случайную порчу,
 *  но НЕ защищает от намеренной подмены: кто правит данные, тот пересчитает
 *  и хеш. Это не «слабое шифрование», это его осознанное отсутствие.
 *
 *  Защита от «случайно уехало в прод»: guardRelease() бросает исключение,
 *  если этот вариант позвали не в отладочной сборке.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.crypto

import com.oliver200.launcher.core.hash.Hashing
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PlainEnvelope {

    val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte())

    const val VERSION: Byte = 1
    const val DIGEST_BYTES = 32

    /** magic(4) + version(1) + digest(32) */
    private const val HEADER = 4 + 1 + DIGEST_BYTES

    fun isEnvelope(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= HEADER && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * Предохранитель. Вызывать из фабрики хранилища ДО того, как этот вариант
     * что-то запишет. [debugBuild] должен приходить из BuildConfig.DEBUG.
     */
    @Throws(CryptoException::class)
    fun guardRelease(debugBuild: Boolean) {
        if (!debugBuild) {
            throw CryptoException(
                "PlainEnvelope запрещён в release-сборке: секреты нельзя хранить открытым текстом",
            )
        }
    }

    /** Упаковка без шифрования. Сигнатура намеренно совпадает с боевой. */
    fun seal(plaintext: ByteArray, context: String): ByteArray {
        val digest = digestOf(context, plaintext)
        val out = ByteArrayOutputStream(HEADER + plaintext.size)
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(digest)
        out.write(plaintext)
        return out.toByteArray()
    }

    @Throws(CryptoException::class)
    fun open(sealed: ByteArray, context: String): ByteArray {
        if (!isEnvelope(sealed)) throw CryptoException("Это не открытый контейнер Oliver-200")
        if (sealed[4] != VERSION) throw CryptoException("Неизвестная версия контейнера: ${sealed[4]}")
        val stored = sealed.copyOfRange(5, 5 + DIGEST_BYTES)
        val payload = sealed.copyOfRange(HEADER, sealed.size)
        val actual = digestOf(context, payload)
        if (!Hashing.constantTimeEquals(stored, actual)) {
            throw CryptoException("Контейнер повреждён или контекст не совпадает")
        }
        return payload
    }

    private fun digestOf(context: String, payload: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(Hashing.SHA256)
        md.update(MAGIC)
        md.update(VERSION)
        md.update(context.toByteArray(StandardCharsets.UTF_8))
        md.update(0) // разделитель: иначе context+payload склеиваются неоднозначно
        md.update(payload)
        return md.digest()
    }
}
