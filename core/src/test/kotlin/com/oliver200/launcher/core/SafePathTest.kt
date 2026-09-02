/*
 * Oliver-200 · тесты защиты от выхода за пределы каталога.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.io.SafePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SafePathTest {

    @Test
    fun `подъём вверх запрещён в любом виде`() {
        val attacks = listOf(
            "../evil",
            "a/../../evil",
            "..",
            "../",
            "a/b/../../../c",
            "..\\evil",
            "a\\..\\..\\evil",
        )
        for (a in attacks) {
            assertNull(SafePath.normalizeRelative(a), "должен быть отвергнут: $a")
        }
    }

    @Test
    fun `абсолютные пути и диски запрещены`() {
        assertNull(SafePath.normalizeRelative("/etc/passwd"))
        assertNull(SafePath.normalizeRelative("C:/Windows/system32"))
        assertNull(SafePath.normalizeRelative("\\\\server\\share"))
    }

    @Test
    fun `нормальные пути проходят и схлопывают лишние разделители`() {
        assertEquals("a/b/c.jar", SafePath.normalizeRelative("a/b/c.jar"))
        assertEquals("a/b/c.jar", SafePath.normalizeRelative("a//b/./c.jar"))
        assertEquals(
            "com/mojang/blocklist/1.0.10/blocklist-1.0.10.jar",
            SafePath.normalizeRelative("com/mojang/blocklist/1.0.10/blocklist-1.0.10.jar"),
        )
    }

    @Test
    fun `слишком глубокие и длинные пути отвергаются`() {
        val deep = (1..40).joinToString("/") { "d$it" }
        assertNull(SafePath.normalizeRelative(deep))
        assertNull(SafePath.normalizeRelative("a".repeat(1000)))
    }

    @Test
    fun `зарезервированные и опасные имена не проходят`() {
        assertFalse(SafePath.isSafeSegment("CON"))
        assertFalse(SafePath.isSafeSegment("nul.txt"))
        assertFalse(SafePath.isSafeSegment("file."))
        assertFalse(SafePath.isSafeSegment(" file"))
        assertFalse(SafePath.isSafeSegment("a:b"))
        assertFalse(SafePath.isSafeSegment(""))
        assertTrue(SafePath.isSafeSegment("1.21.4"))
        assertTrue(SafePath.isSafeSegment("Мой пак.zip"))
    }

    @Test
    fun `sanitizeFileName вычищает опасное и сохраняет читаемое`() {
        assertEquals("BSL_v8.2.zip", SafePath.sanitizeFileName("BSL_v8.2.zip"))
        assertEquals("etc_passwd", SafePath.sanitizeFileName("../etc/passwd"))
        assertEquals("a_b.zip", SafePath.sanitizeFileName("a:b.zip"))
        assertNull(SafePath.sanitizeFileName("   "))
        assertNull(SafePath.sanitizeFileName("..."))
        val long = SafePath.sanitizeFileName("x".repeat(400) + ".zip")
        assertNotNull(long)
        assertTrue(long!!.length <= SafePath.MAX_NAME_LENGTH)
        assertTrue(long.endsWith(".zip"))
    }

    @Test
    fun `resolveInside не выпускает за корень`(@TempDir tmp: File) {
        val root = File(tmp, "game").apply { mkdirs() }
        assertNotNull(SafePath.resolveInside(root, "versions/1.21/1.21.jar"))
        assertNull(SafePath.resolveInside(root, "../outside.txt"))
        assertNull(SafePath.resolveInside(root, "/absolute"))
    }

    @Test
    fun `isInside учитывает канонический путь`(@TempDir tmp: File) {
        val root = File(tmp, "root").apply { mkdirs() }
        assertTrue(SafePath.isInside(root, File(root, "sub/file.txt")))
        assertFalse(SafePath.isInside(root, File(tmp, "other/file.txt")))
        assertFalse(SafePath.isInside(root, root))
    }

    @Test
    fun `исполняемые расширения распознаются`() {
        assertTrue(SafePath.isExecutableName("payload.so"))
        assertTrue(SafePath.isExecutableName("classes.DEX"))
        assertTrue(SafePath.isExecutableName("mod.jar"))
        assertFalse(SafePath.isExecutableName("gbuffers_water.fsh"))
        assertFalse(SafePath.isExecutableName("LICENSE"))
    }
}
