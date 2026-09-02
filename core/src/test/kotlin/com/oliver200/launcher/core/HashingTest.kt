/*
 * Oliver-200 · тесты контроля целостности. Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.hash.Hashing
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HashingTest {

    @Test
    fun `известные контрольные векторы`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Hashing.sha1(ByteArray(0)))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashing.sha1("abc".toByteArray()))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun `потоковый и обычный хеш совпадают`() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(Hashing.sha1(data), Hashing.sha1Stream(data.inputStream()))
    }

    @Test
    fun `hex и unhex обратимы`() {
        val data = ByteArray(64) { (it * 7).toByte() }
        assertArrayEquals(data, Hashing.unhex(Hashing.hex(data)))
        assertNull(Hashing.unhex("abc"))
        assertNull(Hashing.unhex("zz"))
    }

    @Test
    fun `сравнение хешей не зависит от регистра и устойчиво к мусору`() {
        val upper = "A9993E364706816ABA3E25717850C26C9CD0D89D"
        val lower = "a9993e364706816aba3e25717850c26c9cd0d89d"
        assertTrue(Hashing.constantTimeEqualsHex(upper, lower))
        assertFalse(Hashing.constantTimeEqualsHex(upper, lower.dropLast(1) + "e"))
        assertFalse(Hashing.constantTimeEqualsHex(null, lower))
        assertFalse(Hashing.constantTimeEqualsHex("", ""))
        assertFalse(Hashing.constantTimeEqualsHex("нехекс", lower))
    }

    @Test
    fun `валидация формата SHA-1`() {
        assertTrue(Hashing.isValidSha1("a9993e364706816aba3e25717850c26c9cd0d89d"))
        assertFalse(Hashing.isValidSha1("a9993e36"))
        assertFalse(Hashing.isValidSha1(null))
        assertFalse(Hashing.isValidSha1("g9993e364706816aba3e25717850c26c9cd0d89d"))
    }
}
