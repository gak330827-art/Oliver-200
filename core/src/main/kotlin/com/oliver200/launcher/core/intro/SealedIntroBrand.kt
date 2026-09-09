/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/intro/SealedIntroBrand.kt
 *  Назначение : тексты заставки, лежащие в приложении зашифрованными.
 *
 *  ВАРИАНТ    : ★ С ШИФРОВАНИЕМ ★  (боевой, используется в release)
 *  Парный файл: core/intro/PlainIntroBrand.kt — тот же API БЕЗ шифрования.
 *
 *  Криптография:
 *      AES-256-GCM (шифрование и аутентификация одним проходом),
 *      контейнер формата OL2E (core/crypto/CryptoEnvelope.kt),
 *      ключ = SHA-256(осколок A ⊕ осколок B ⊕ осколок C),
 *      AAD = заголовок контейнера + "intro:brand:<привязка>".
 *
 *  ЧЕСТНО О ГРАНИЦАХ ЗАЩИТЫ. Ключ едет в том же APK, поэтому от того,
 *  кто сядет с отладчиком, это не спасает — и не должно: тексты бренда
 *  не секрет. Что это реально даёт:
 *      1) строк нет ни в strings.xml, ни открытым текстом в APK —
 *         автоматический граббер ресурсов не найдёт их вообще;
 *      2) GCM-тег ловит любую правку контейнера: перепакованный APK
 *         с подменённым вордмарком не покажет чужой бренд молча,
 *         а не покажет ничего (см. fail-closed в SplashActivity);
 *      3) AAD привязывает контейнер к контексту: контейнер, вынутый из
 *         этой сборки, не открывается в чужой с другой привязкой.
 *      Ровно этого мы и хотим: APK лаунчера расходится файлом, а не
 *      через магазин, и «официальная заставка» на чужой сборке —
 *      готовый инструмент фишинга.
 *
 *  Перегенерация (контейнер и осколки меняются только вместе):
 *      java tools/SealBrand.java
 *      java tools/SealBrand.java --binding <своя-привязка>
 *  Вывод инструмента вклеивается в константы ниже. Тест
 *  core/src/test/.../IntroBrandTest.kt проверяет, что распакованное
 *  совпадает с открытым вариантом слово в слово — рассинхрон файлов
 *  роняет сборку.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.intro

import com.oliver200.launcher.core.crypto.CryptoException
import com.oliver200.launcher.core.crypto.CryptoEnvelope
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object SealedIntroBrand {

    /** Привязка контейнера. Меняется только вместе с перегенерацией блоба. */
    const val BINDING_DEFAULT = "oliver200/intro/v1"

    private const val CONTEXT_PREFIX = "intro:brand:"

    // Три осколка ключа. По отдельности ключом не является ни один:
    // поиск по APK строки ключа не даёт ничего, потому что её там нет.
    private const val SHARD_A = "ce3d6b847a59ff9a10dafc3618cca31afc92cb4b208e6c26cfbea2de9e43e3ee"
    private const val SHARD_B = "e769986fb69b5dc45c6c596846af91eeda867692d101e045055e83686a2f557d"
    private const val SHARD_C = "92245323672f68ba4efe625cfa34a0488a788f4d4c8ad4503bbb71cc30b72b88"

    private const val SEALED_B64 =
        "T0wyRQEAAAAAAAAMQwIA6CIJCa+SYGWBfIMIItJXEZFeux5ckCJDgrerYCqk82k4wooDBJKj" +
        "JI1+qoEnPcwj0PwyaYI9/wHUVFVLBSttorsqBjTYLorMWT9zfPok6pAcvhFY6pAzPS03A8Eg"

    /**
     * Открывает контейнер и разбирает запись.
     *
     * @param binding привязка, с которой контейнер был запечатан.
     * @throws IntroBrandException при любой ошибке — без деталей:
     *         «не тот ключ», «подделан тег» и «не та привязка» снаружи
     *         неразличимы, иначе получился бы оракул для подбора.
     */
    @Throws(IntroBrandException::class)
    fun load(binding: String = BINDING_DEFAULT): IntroBrand {
        val sealed = try {
            Base64.getDecoder().decode(SEALED_B64)
        } catch (e: IllegalArgumentException) {
            throw IntroBrandException("Контейнер бренда повреждён", e)
        }

        val key = key()
        val plain = try {
            CryptoEnvelope.open(sealed, key, CONTEXT_PREFIX + binding)
        } catch (e: CryptoException) {
            throw IntroBrandException("Контейнер бренда не открывается", e)
        }

        try {
            return IntroBrandCodec.decode(String(plain, StandardCharsets.UTF_8))
        } finally {
            // Строка уже скопирована; байты затираем, чтобы запись не
            // висела в куче до ближайшей сборки мусора.
            Arrays.fill(plain, 0)
        }
    }

    /** Ключ = SHA-256(A ⊕ B ⊕ C). Собирается на месте и живёт один вызов. */
    private fun key(): SecretKey {
        val a = unhex(SHARD_A)
        val b = unhex(SHARD_B)
        val c = unhex(SHARD_C)
        try {
            for (i in a.indices) a[i] = (a[i].toInt() xor b[i].toInt() xor c[i].toInt()).toByte()
            val raw = MessageDigest.getInstance("SHA-256").digest(a)
            try {
                return SecretKeySpec(raw, "AES")
            } finally {
                Arrays.fill(raw, 0)
            }
        } finally {
            Arrays.fill(a, 0)
            Arrays.fill(b, 0)
            Arrays.fill(c, 0)
        }
    }

    private fun unhex(text: String): ByteArray {
        require(text.length == 64) { "Осколок ключа должен быть 32 байта" }
        val out = ByteArray(32)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Осколок ключа: не hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
