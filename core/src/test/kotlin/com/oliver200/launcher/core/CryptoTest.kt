/*
 * Oliver-200 · тесты обоих вариантов контейнера: с шифрованием и без.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.crypto.CryptoEnvelope
import com.oliver200.launcher.core.crypto.CryptoException
import com.oliver200.launcher.core.crypto.PlainEnvelope
import com.oliver200.launcher.core.crypto.Redact
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class CryptoTest {

    private val token = "eyJhbGciOiJIUzI1NiJ9.секретный_токен_доступа".toByteArray(StandardCharsets.UTF_8)
    private val context = "vault:9f1b2c:msa_access_token"

    /* ─────────────── Вариант С ШИФРОВАНИЕМ ─────────────── */

    @Test
    fun `шифрование и расшифровка ключом Keystore`() {
        val key = CryptoEnvelope.randomKey()
        val sealed = CryptoEnvelope.seal(token, key, context)
        assertTrue(CryptoEnvelope.isEnvelope(sealed))
        assertArrayEquals(token, CryptoEnvelope.open(sealed, key, context))
    }

    @Test
    @DisplayName("Открытый текст не виден в шифротексте")
    fun `шифротекст не содержит исходных данных`() {
        val key = CryptoEnvelope.randomKey()
        val sealed = CryptoEnvelope.seal(token, key, context)
        val asText = String(sealed, StandardCharsets.ISO_8859_1)
        assertFalse(asText.contains("секретный"))
        assertFalse(asText.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `чужой ключ не открывает контейнер`() {
        val sealed = CryptoEnvelope.seal(token, CryptoEnvelope.randomKey(), context)
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.open(sealed, CryptoEnvelope.randomKey(), context)
        }
    }

    @Test
    @DisplayName("AAD: контейнер нельзя переставить в другой слот")
    fun `подмена контекста не проходит`() {
        val key = CryptoEnvelope.randomKey()
        val sealed = CryptoEnvelope.seal(token, key, "vault:АККАУНТ_А:token")
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.open(sealed, key, "vault:АККАУНТ_Б:token")
        }
    }

    @Test
    fun `любая правка байта ломает проверку целостности`() {
        val key = CryptoEnvelope.randomKey()
        val sealed = CryptoEnvelope.seal(token, key, context)
        for (i in sealed.indices step 7) {
            val tampered = sealed.copyOf()
            tampered[i] = (tampered[i] + 1).toByte()
            assertThrows(CryptoException::class.java, {
                CryptoEnvelope.open(tampered, key, context)
            }, "байт $i должен был сломать контейнер")
        }
    }

    @Test
    @DisplayName("IV никогда не повторяется")
    fun `каждый вызов даёт новый шифротекст`() {
        val key = CryptoEnvelope.randomKey()
        val a = CryptoEnvelope.seal(token, key, context)
        val b = CryptoEnvelope.seal(token, key, context)
        assertNotEquals(String(a, StandardCharsets.ISO_8859_1), String(b, StandardCharsets.ISO_8859_1))
        assertArrayEquals(token, CryptoEnvelope.open(a, key, context))
        assertArrayEquals(token, CryptoEnvelope.open(b, key, context))
    }

    @Test
    fun `парольный контейнер работает и не открывается чужим паролем`() {
        val sealed = CryptoEnvelope.sealWithPassphrase(
            token,
            "Правильный-Пароль-42".toCharArray(),
            context,
            SecureRandom(),
            iterations = 100_000,
        )
        assertArrayEquals(
            token,
            CryptoEnvelope.openWithPassphrase(sealed, "Правильный-Пароль-42".toCharArray(), context),
        )
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.openWithPassphrase(sealed, "неверный".toCharArray(), context)
        }
    }

    @Test
    fun `слабые параметры и битые заголовки отвергаются`() {
        val key = CryptoEnvelope.randomKey()
        assertThrows(CryptoException::class.java) { CryptoEnvelope.open(ByteArray(4), key, context) }
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.open("НЕ КОНТЕЙНЕР ВООБЩЕ".toByteArray(), key, context)
        }
        // Контейнер с паролем нельзя открыть «сырым» ключом и наоборот.
        val withPass = CryptoEnvelope.sealWithPassphrase(
            token, "пароль123".toCharArray(), context, SecureRandom(), 100_000,
        )
        assertThrows(CryptoException::class.java) { CryptoEnvelope.open(withPass, key, context) }
        val withKey = CryptoEnvelope.seal(token, key, context)
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.openWithPassphrase(withKey, "пароль123".toCharArray(), context)
        }
    }

    @Test
    fun `пустой пароль запрещён`() {
        assertThrows(CryptoException::class.java) {
            CryptoEnvelope.sealWithPassphrase(token, CharArray(0), context, SecureRandom(), 100_000)
        }
    }

    /* ─────────────── Вариант БЕЗ ШИФРОВАНИЯ ─────────────── */

    @Test
    fun `открытый контейнер сохраняет данные и ловит порчу`() {
        val sealed = PlainEnvelope.seal(token, context)
        assertTrue(PlainEnvelope.isEnvelope(sealed))
        assertArrayEquals(token, PlainEnvelope.open(sealed, context))

        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertThrows(CryptoException::class.java) { PlainEnvelope.open(tampered, context) }
    }

    @Test
    @DisplayName("Честность: открытый вариант действительно НЕ шифрует")
    fun `открытый контейнер содержит исходный текст`() {
        val sealed = PlainEnvelope.seal(token, context)
        val asText = String(sealed, StandardCharsets.UTF_8)
        assertTrue(asText.contains("секретный_токен_доступа"))
    }

    @Test
    fun `контекст участвует в проверке и в открытом варианте`() {
        val sealed = PlainEnvelope.seal(token, "слот-А")
        assertThrows(CryptoException::class.java) { PlainEnvelope.open(sealed, "слот-Б") }
    }

    @Test
    @DisplayName("Предохранитель: открытый вариант не запускается в release")
    fun `guardRelease блокирует боевую сборку`() {
        PlainEnvelope.guardRelease(debugBuild = true) // не бросает
        assertThrows(CryptoException::class.java) { PlainEnvelope.guardRelease(debugBuild = false) }
    }

    @Test
    fun `магические числа вариантов не пересекаются`() {
        val enc = CryptoEnvelope.seal(token, CryptoEnvelope.randomKey(), context)
        val plain = PlainEnvelope.seal(token, context)
        assertFalse(CryptoEnvelope.isEnvelope(plain))
        assertFalse(PlainEnvelope.isEnvelope(enc))
    }

    /* ─────────────── Маскирование в логах ─────────────── */

    @Test
    fun `Redact прячет токены`() {
        assertEquals("<пусто>", Redact.secret(null))
        assertTrue(Redact.secret("abcdefghijklmnop").endsWith("mnop (len=16)"))
        assertTrue(Redact.secret("abcdefghijklmnop").startsWith("***"))
        assertEquals("***", Redact.secret("short"))
        assertEquals("i***@mail.ru", Redact.email("ivan@mail.ru"))

        val log = """{"access_token":"ya29.SUPERSECRETVALUE","expires":3600}"""
        val scrubbed = Redact.scrub(log)
        assertFalse(scrubbed.contains("SUPERSECRET"))

        val header = "Authorization: Bearer eyJ0eXAiOiJKV1QiLCJhbGciOiJI"
        assertFalse(Redact.scrub(header).contains("eyJ0eXAi"))
    }
}
