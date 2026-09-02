/*
 * Oliver-200 · тесты защиты ZIP: zip-slip, zip-бомба, исполняемый код в паке.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.io.SafeZip
import com.oliver200.launcher.core.io.ZipLimits
import com.oliver200.launcher.core.io.ZipSecurityException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SafeZipTest {

    @Test
    @DisplayName("Zip Slip: запись ../../ не проходит проверку")
    fun `zip slip отвергается`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "slip.zip"),
            listOf(
                TestZipBuilder.text("shaders/ok.fsh", "void main(){}"),
                TestZipBuilder.text("../../../shared_prefs/vault.xml", "украдено"),
            ),
        )
        val ex = assertThrows(ZipSecurityException::class.java) { SafeZip.inspect(zip) }
        assertTrue(ex.message!!.contains("Небезопасное имя"), ex.message)
    }

    @Test
    fun `абсолютный путь в записи отвергается`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "abs.zip"),
            listOf(TestZipBuilder.text("/etc/passwd", "root:x:0:0")),
        )
        assertThrows(ZipSecurityException::class.java) { SafeZip.inspect(zip) }
    }

    @Test
    @DisplayName("Zip-бомба: подозрительная степень сжатия ловится до распаковки")
    fun `zip бомба отвергается`(@TempDir tmp: File) {
        // 8 МБ нулей сжимаются в единицы килобайт — коэффициент много больше 300:1.
        val zeros = ByteArray(8 * 1024 * 1024)
        val zip = TestZipBuilder.build(
            File(tmp, "bomb.zip"),
            listOf("bomb.txt" to zeros),
        )
        val ex = assertThrows(ZipSecurityException::class.java) { SafeZip.inspect(zip) }
        assertTrue(ex.message!!.contains("степень сжатия"), ex.message)
    }

    @Test
    fun `слишком много записей отвергается`(@TempDir tmp: File) {
        val entries = (1..50).map { TestZipBuilder.text("f$it.txt", "x") }
        val zip = TestZipBuilder.build(File(tmp, "many.zip"), entries)
        assertThrows(ZipSecurityException::class.java) {
            SafeZip.inspect(zip, ZipLimits(maxEntries = 10))
        }
    }

    @Test
    @DisplayName("Нативный код внутри пака шейдеров запрещён")
    fun `исполняемый файл в паке отвергается`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "evil.zip"),
            listOf(
                TestZipBuilder.text("shaders/ok.fsh", "void main(){}"),
                TestZipBuilder.text("shaders/libpayload.so", "ELF"),
            ),
        )
        val ex = assertThrows(ZipSecurityException::class.java) { SafeZip.inspect(zip) }
        assertTrue(ex.message!!.contains("Исполняемый файл"), ex.message)
        // Для natives то же содержимое легально — там .so это и есть цель.
        assertNotNull(SafeZip.inspect(zip, ZipLimits.NATIVES))
    }

    @Test
    fun `два имени с одинаковым нормализованным путём отвергаются`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "dup.zip"),
            listOf(
                TestZipBuilder.text("shaders/a.fsh", "первый"),
                TestZipBuilder.text("shaders//a.fsh", "второй"),
            ),
        )
        val ex = assertThrows(ZipSecurityException::class.java) { SafeZip.inspect(zip) }
        assertTrue(ex.message!!.contains("Дубликат"), ex.message)
    }

    @Test
    fun `корректный пак распаковывается со срезанием общего корня`(@TempDir tmp: File) {
        val zip = TestZipBuilder.goodShaderPack(File(tmp, "good.zip"))
        val inspection = SafeZip.inspect(zip)
        assertEquals("SuperShaders-1.0", inspection.commonRootDirectory())

        val dest = File(tmp, "out")
        val result = SafeZip.extract(zip, dest, stripRoot = "SuperShaders-1.0")
        assertEquals(4, result.filesWritten)
        assertTrue(File(dest, "shaders/gbuffers_basic.fsh").isFile)
        assertTrue(File(dest, "README.md").isFile)
        assertFalse(File(dest, "SuperShaders-1.0").exists())
    }

    @Test
    fun `фильтр accept ограничивает распаковку`(@TempDir tmp: File) {
        val zip = TestZipBuilder.goodShaderPack(File(tmp, "good.zip"))
        val dest = File(tmp, "only-shaders")
        val result = SafeZip.extract(zip, dest, stripRoot = "SuperShaders-1.0") {
            it.startsWith("shaders/")
        }
        assertEquals(3, result.filesWritten)
        assertFalse(File(dest, "README.md").exists())
    }

    @Test
    fun `copyBounded останавливается на лимите`() {
        val input = ByteArray(1000).inputStream()
        val out = java.io.ByteArrayOutputStream()
        assertThrows(ZipSecurityException::class.java) { SafeZip.copyBounded(input, out, 100) }
    }
}
