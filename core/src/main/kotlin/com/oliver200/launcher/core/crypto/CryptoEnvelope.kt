/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/crypto/CryptoEnvelope.kt
 *  Назначение : формат защищённого контейнера для токенов и профилей.
 *
 *  ВАРИАНТ    : ★ С ШИФРОВАНИЕМ ★  (боевой, используется в release)
 *  Парный файл: core/crypto/PlainEnvelope.kt — тот же API БЕЗ шифрования.
 *
 *  Криптография:
 *      AES-256-GCM (шифрование + аутентификация одним проходом),
 *      IV 96 бит из SecureRandom — НИКОГДА не переиспользуется,
 *      PBKDF2-HMAC-SHA256, 210 000 итераций (рекомендация OWASP),
 *      AAD связывает шифротекст с его контекстом.
 *
 *  Почему AAD обязателен: без него злоумышленник с доступом к файлу
 *  переставляет контейнер из слота "чужой аккаунт" в слот "мой аккаунт".
 *  Данные валидные, подпись сходится — и приложение отдаёт чужой токен.
 *  Контекст вида "vault:<accountId>:<purpose>" делает такую подмену
 *  математически невозможной.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.crypto

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Единственный тип ошибки наружу: без деталей, чтобы не строить padding-оракул. */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

object CryptoEnvelope {

    val MAGIC = byteArrayOf('O'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte(), 'E'.code.toByte())

    const val VERSION: Byte = 1

    const val KDF_RAW_KEY: Byte = 0 // ключ из Android Keystore, деривация не нужна
    const val KDF_PBKDF2: Byte = 1

    const val KEY_BITS = 256
    const val GCM_TAG_BITS = 128
    const val IV_BYTES = 12
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 210_000

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** Заголовок фиксированной части: magic+version+kdf+iterations+saltLen. */
    private const val FIXED_HEADER = 4 + 1 + 1 + 4 + 1

    fun isEnvelope(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > FIXED_HEADER && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /* ───────────────────────── Ключ из Keystore ───────────────────────── */

    @Throws(CryptoException::class)
    fun seal(
        plaintext: ByteArray,
        key: SecretKey,
        context: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray = sealInternal(plaintext, key, context, KDF_RAW_KEY, 0, ByteArray(0), random)

    @Throws(CryptoException::class)
    fun open(sealed: ByteArray, key: SecretKey, context: String): ByteArray {
        val h = parseHeader(sealed)
        if (h.kdf != KDF_RAW_KEY) throw CryptoException("Контейнер требует парольной деривации")
        return openInternal(sealed, h, key, context)
    }

    /* ───────────────────────── Ключ из пароля ───────────────────────── */

    @Throws(CryptoException::class)
    fun sealWithPassphrase(
        plaintext: ByteArray,
        passphrase: CharArray,
        context: String,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(iterations >= 100_000) { "Слишком мало итераций PBKDF2" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        try {
            return sealInternal(plaintext, key, context, KDF_PBKDF2, iterations, salt, random)
        } finally {
            destroy(key)
        }
    }

    @Throws(CryptoException::class)
    fun openWithPassphrase(sealed: ByteArray, passphrase: CharArray, context: String): ByteArray {
        val h = parseHeader(sealed)
        if (h.kdf != KDF_PBKDF2) throw CryptoException("Контейнер не запаролен")
        if (h.iterations < 100_000) throw CryptoException("Недопустимо слабые параметры контейнера")
        val key = deriveKey(passphrase, h.salt, h.iterations)
        try {
            return openInternal(sealed, h, key, context)
        } finally {
            destroy(key)
        }
    }

    @Throws(CryptoException::class)
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        if (passphrase.isEmpty()) throw CryptoException("Пустой пароль недопустим")
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
            try {
                return SecretKeySpec(raw, KEY_ALGORITHM)
            } finally {
                Arrays.fill(raw, 0)
            }
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Не удалось получить ключ из пароля", e)
        } finally {
            spec.clearPassword()
        }
    }

    fun randomKey(random: SecureRandom = SecureRandom()): SecretKey {
        val raw = ByteArray(KEY_BITS / 8).also(random::nextBytes)
        try {
            return SecretKeySpec(raw, KEY_ALGORITHM)
        } finally {
            Arrays.fill(raw, 0)
        }
    }

    /* ───────────────────────────── Внутреннее ───────────────────────────── */

    private data class Header(
        val kdf: Byte,
        val iterations: Int,
        val salt: ByteArray,
        val iv: ByteArray,
        val payloadOffset: Int,
        val aad: ByteArray,
    )

    private fun sealInternal(
        plaintext: ByteArray,
        key: SecretKey,
        context: String,
        kdf: Byte,
        iterations: Int,
        salt: ByteArray,
        random: SecureRandom,
    ): ByteArray {
        if (salt.size > 255) throw CryptoException("Слишком длинная соль")
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val head = ByteArrayOutputStream(FIXED_HEADER + salt.size + 1 + IV_BYTES)
        head.write(MAGIC)
        head.write(VERSION.toInt())
        head.write(kdf.toInt())
        head.write(iterations ushr 24 and 0xFF)
        head.write(iterations ushr 16 and 0xFF)
        head.write(iterations ushr 8 and 0xFF)
        head.write(iterations and 0xFF)
        head.write(salt.size)
        head.write(salt)
        head.write(IV_BYTES)
        head.write(iv)
        val header = head.toByteArray()

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadOf(header, context))
            val ct = cipher.doFinal(plaintext)
            val out = ByteArray(header.size + ct.size)
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(ct, 0, out, header.size, ct.size)
            return out
        } catch (e: GeneralSecurityException) {
            throw CryptoException("Ошибка шифрования", e)
        }
    }

    private fun openInternal(sealed: ByteArray, h: Header, key: SecretKey, context: String): ByteArray {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, h.iv))
            cipher.updateAAD(aadOf(h.aad, context))
            return cipher.doFinal(sealed, h.payloadOffset, sealed.size - h.payloadOffset)
        } catch (e: GeneralSecurityException) {
            // Единое сообщение для «неверный ключ», «подделан тег», «не тот контекст».
            throw CryptoException("Контейнер повреждён или ключ неверный", e)
        }
    }

    private fun aadOf(header: ByteArray, context: String): ByteArray {
        val ctx = context.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(header.size + ctx.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(ctx, 0, out, header.size, ctx.size)
        return out
    }

    private fun parseHeader(sealed: ByteArray): Header {
        if (!isEnvelope(sealed)) throw CryptoException("Это не контейнер Oliver-200")
        if (sealed[4] != VERSION) throw CryptoException("Неизвестная версия контейнера: ${sealed[4]}")
        val kdf = sealed[5]
        if (kdf != KDF_RAW_KEY && kdf != KDF_PBKDF2) throw CryptoException("Неизвестный KDF")
        val iterations = ((sealed[6].toInt() and 0xFF) shl 24) or
            ((sealed[7].toInt() and 0xFF) shl 16) or
            ((sealed[8].toInt() and 0xFF) shl 8) or
            (sealed[9].toInt() and 0xFF)
        if (iterations < 0) throw CryptoException("Повреждённый заголовок")
        val saltLen = sealed[10].toInt() and 0xFF
        var p = FIXED_HEADER
        if (sealed.size < p + saltLen + 1) throw CryptoException("Обрезанный контейнер")
        val salt = sealed.copyOfRange(p, p + saltLen)
        p += saltLen
        val ivLen = sealed[p].toInt() and 0xFF
        p += 1
        if (ivLen != IV_BYTES) throw CryptoException("Недопустимая длина IV")
        if (sealed.size < p + ivLen + GCM_TAG_BITS / 8) throw CryptoException("Обрезанный контейнер")
        val iv = sealed.copyOfRange(p, p + ivLen)
        p += ivLen
        val aad = sealed.copyOfRange(0, p)
        return Header(kdf, iterations, salt, iv, p, aad)
    }

    private fun destroy(key: SecretKey) {
        try {
            key.javaClass.getMethod("destroy").invoke(key)
        } catch (_: Exception) {
            // SecretKeySpec.destroy() не реализован в большинстве JDK/ART — это нормально.
        }
    }
}
