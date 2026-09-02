/*
 * Oliver-200 · тесты офлайн-профиля. Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.auth.OfflineProfile
import com.oliver200.launcher.core.skin.MinecraftUuid
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

class OfflineProfileTest {

    /**
     * Эталон получен двумя независимыми способами: ручной реализацией
     * MD5-UUID на Python и java.util.UUID.nameUUIDFromBytes. Оба совпали.
     */
    private val vectors = mapOf(
        "Notch" to "b50ad385-829d-3141-a216-7e7d7539ba7f",
        "jeb_" to "a762f560-4fce-3236-812a-b80efff0b62b",
        "Steve" to "5627dd98-e6be-3c21-b8a8-e92344183641",
        "Player" to "a01e3843-e521-3998-958a-f459800e4d11",
        "Oliver200" to "269dcbd6-986a-325c-b2d5-5a076d0fc194",
        "aaa" to "a97d7027-1ae1-3ce0-86e3-0fd135c5661e",
    )

    @Test
    @DisplayName("UUID совпадает с тем, что вычисляет ванильный сервер")
    fun `контрольные векторы`() {
        for ((name, expected) in vectors) {
            assertEquals(expected, OfflineProfile.uuidFor(name), "ник $name")
        }
    }

    @Test
    @DisplayName("Наша реализация совпадает с UUID.nameUUIDFromBytes на любом нике")
    fun `сверка с реализацией JDK`() {
        val names = vectors.keys + setOf("A_1", "zzzzzzzzzzzzzzzz", "Test_123", "abc")
        for (name in names) {
            val jdk = UUID.nameUUIDFromBytes(
                (OfflineProfile.PREFIX + name).toByteArray(StandardCharsets.UTF_8),
            ).toString()
            assertEquals(jdk, OfflineProfile.uuidFor(name), "ник $name")
        }
    }

    @Test
    fun `UUID имеет версию 3 и вариант IETF`() {
        for (name in vectors.keys) {
            val parsed = UUID.fromString(OfflineProfile.uuidFor(name))
            assertEquals(3, parsed.version(), "ник $name")
            assertEquals(2, parsed.variant(), "ник $name") // 2 = IETF RFC 4122
        }
    }

    @Test
    @DisplayName("Один и тот же ник всегда даёт один UUID — иначе игрок теряет мир")
    fun `результат детерминирован`() {
        repeat(5) {
            assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", OfflineProfile.uuidFor("Notch"))
        }
    }

    @Test
    fun `регистр ника меняет UUID`() {
        // Так же ведёт себя и сервер: "Steve" и "steve" — разные игроки.
        assertNotEquals(OfflineProfile.uuidFor("Steve"), OfflineProfile.uuidFor("steve"))
    }

    @Test
    fun `профиль собирается только из корректного ника`() {
        val ok = OfflineProfile.of("  Steve  ")
        assertNotNull(ok)
        assertEquals("Steve", ok!!.name)
        assertEquals("5627dd98-e6be-3c21-b8a8-e92344183641", ok.uuid)
        assertEquals("5627dd98e6be3c21b8a8e92344183641", ok.uuidCompact)

        for (bad in listOf("ab", "x".repeat(17), "Стив", "Steve Jobs", "../../evil", "", "  ")) {
            assertNull(OfflineProfile.of(bad), "должен быть отвергнут: $bad")
            assertFalse(OfflineProfile.isValidName(bad), bad)
        }
        assertNull(OfflineProfile.of(null))
    }

    @Test
    fun `полученный UUID проходит общую проверку формата`() {
        for (name in vectors.keys) {
            val uuid = OfflineProfile.uuidFor(name)
            assertTrue(MinecraftUuid.isValid(uuid), uuid)
            assertEquals(uuid, MinecraftUuid.dashed(MinecraftUuid.strip(uuid)))
        }
    }

    @Test
    fun `константы запуска соответствуют формату игры`() {
        assertEquals("0", OfflineProfile.ACCESS_TOKEN)
        assertEquals("legacy", OfflineProfile.USER_TYPE)
        assertEquals("OfflinePlayer:", OfflineProfile.PREFIX)
    }
}
