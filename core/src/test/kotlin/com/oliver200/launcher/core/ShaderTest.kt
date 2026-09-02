/*
 * Oliver-200 · тесты паков шейдеров: распознавание, установка, выбор.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.io.ZipSecurityException
import com.oliver200.launcher.core.shader.ShaderKind
import com.oliver200.launcher.core.shader.ShaderPackInspector
import com.oliver200.launcher.core.shader.ShaderStore
import com.oliver200.launcher.core.shader.ShaderStoreException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ShaderTest {

    /* ───────────────────────── Распознавание ───────────────────────── */

    @Test
    fun `пак Iris распознаётся`(@TempDir tmp: File) {
        val zip = TestZipBuilder.goodShaderPack(File(tmp, "BSL_v8.2.zip"), root = "BSL_v8.2")
        val info = ShaderPackInspector.inspect(zip)
        assertEquals(ShaderKind.IRIS_OPTIFINE, info.kind)
        assertEquals("BSL_v8.2", info.rootPrefix)
        assertEquals("BSL v8.2", info.displayName)
        assertEquals(4, info.fileCount)
        assertTrue(info.usable)
    }

    @Test
    fun `пак без общего корня тоже распознаётся`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "flat.zip"),
            listOf(
                TestZipBuilder.text("shaders/final.fsh", "void main(){}"),
                TestZipBuilder.text("shaders/final.vsh", "void main(){}"),
            ),
        )
        val info = ShaderPackInspector.inspect(zip)
        assertEquals(ShaderKind.IRIS_OPTIFINE, info.kind)
        assertNull(info.rootPrefix)
    }

    @Test
    fun `core-шейдеры и Canvas различаются`() {
        assertEquals(
            ShaderKind.VANILLA_CORE,
            ShaderPackInspector.detectKind(
                listOf("pack.mcmeta", "assets/minecraft/shaders/core/rendertype_solid.json"),
            ),
        )
        assertEquals(
            ShaderKind.CANVAS,
            ShaderPackInspector.detectKind(
                listOf("pack.mcmeta", "assets/canvas/pipelines/lumi.json"),
            ),
        )
        assertEquals(
            ShaderKind.UNKNOWN,
            ShaderPackInspector.detectKind(listOf("pack.mcmeta", "assets/minecraft/textures/x.png")),
        )
    }

    @Test
    fun `ресурспак без шейдеров помечается как непригодный`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "resource.zip"),
            listOf(
                TestZipBuilder.text("pack.mcmeta", """{"pack":{"pack_format":15}}"""),
                TestZipBuilder.text("assets/minecraft/textures/block/stone.png", "PNG"),
            ),
        )
        val info = ShaderPackInspector.inspect(zip)
        assertEquals(ShaderKind.UNKNOWN, info.kind)
        assertFalse(info.usable)
        assertTrue(info.warnings.any { it.contains("Не найдено ни одного шейдера") })
    }

    @Test
    @DisplayName("Пак с нативной библиотекой внутри не проходит вовсе")
    fun `исполняемый код в паке блокируется`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "trojan.zip"),
            listOf(
                TestZipBuilder.text("shaders/final.fsh", "void main(){}"),
                TestZipBuilder.text("shaders/../lib/libhook.so", "ELF"),
            ),
        )
        assertThrows(ZipSecurityException::class.java) { ShaderPackInspector.inspect(zip) }
    }

    @Test
    fun `необычные файлы отмечаются предупреждением`(@TempDir tmp: File) {
        val zip = TestZipBuilder.build(
            File(tmp, "odd.zip"),
            listOf(
                TestZipBuilder.text("shaders/final.fsh", "void main(){}"),
                TestZipBuilder.text("shaders/data.sqlite", "SQLite"),
            ),
        )
        val info = ShaderPackInspector.inspect(zip)
        assertEquals(ShaderKind.IRIS_OPTIFINE, info.kind)
        assertTrue(info.warnings.any { it.contains("data.sqlite") })
    }

    /* ───────────────────────── Хранилище ───────────────────────── */

    @Test
    fun `установка, список, выбор и удаление`(@TempDir tmp: File) {
        val gameDir = File(tmp, ".minecraft")
        val store = ShaderStore(gameDir)
        val source = TestZipBuilder.goodShaderPack(File(tmp, "src.zip"), root = "ComplementaryUnbound")

        val installed = source.inputStream().use { store.install(it, "Complementary Unbound.zip") }
        assertEquals("Complementary Unbound.zip", installed.file.name)
        assertEquals(ShaderKind.IRIS_OPTIFINE, installed.info.kind)
        assertEquals(64, installed.sha256.length)

        assertEquals(1, store.list().size)
        assertNull(store.currentSelection())

        store.select(installed.id)
        assertEquals("Complementary Unbound.zip", store.currentSelection())
        val optionsFile = File(gameDir, ShaderStore.SELECTION_FILE)
        assertTrue(optionsFile.isFile)
        assertEquals("shaderPack=Complementary Unbound.zip", optionsFile.readText().trim())

        store.select(null)
        assertNull(store.currentSelection())
        assertEquals("shaderPack=(off)", optionsFile.readText().trim())

        store.select(installed.id)
        assertTrue(store.remove(installed.id))
        assertEquals(0, store.list().size)
        // Удаление выбранного пака сбрасывает выбор — иначе игра стартует с ошибкой.
        assertNull(store.currentSelection())
    }

    @Test
    fun `имя пака очищается от опасных символов`(@TempDir tmp: File) {
        val store = ShaderStore(File(tmp, ".minecraft"))
        val source = TestZipBuilder.goodShaderPack(File(tmp, "src.zip"))
        val installed = source.inputStream().use { store.install(it, "../../../evil.zip") }
        assertEquals("evil.zip", installed.file.name)
        assertTrue(installed.file.parentFile.name == ShaderStore.PACKS_DIR)
    }

    @Test
    fun `без расширения zip добавляется автоматически`(@TempDir tmp: File) {
        val store = ShaderStore(File(tmp, ".minecraft"))
        val source = TestZipBuilder.goodShaderPack(File(tmp, "src.zip"))
        val installed = source.inputStream().use { store.install(it, "МойПак") }
        assertEquals("МойПак.zip", installed.file.name)
    }

    @Test
    @DisplayName("Непригодный архив не остаётся в каталоге даже частично")
    fun `неудачная установка не мусорит`(@TempDir tmp: File) {
        val gameDir = File(tmp, ".minecraft")
        val store = ShaderStore(gameDir)
        val bad = TestZipBuilder.build(
            File(tmp, "bad.zip"),
            listOf(TestZipBuilder.text("readme.txt", "просто текст")),
        )
        assertThrows(ShaderStoreException::class.java) {
            bad.inputStream().use { store.install(it, "bad.zip") }
        }
        assertEquals(0, store.packsDir.listFiles()!!.size)
    }

    @Test
    fun `выбор несуществующего пака запрещён`(@TempDir tmp: File) {
        val store = ShaderStore(File(tmp, ".minecraft"))
        store.ensureDirs()
        assertThrows(ShaderStoreException::class.java) { store.select("нет-такого.zip") }
        assertThrows(ShaderStoreException::class.java) { store.select("../../evil.zip") }
    }

    @Test
    fun `битый архив не ломает список`(@TempDir tmp: File) {
        val store = ShaderStore(File(tmp, ".minecraft"))
        store.ensureDirs()
        File(store.packsDir, "broken.zip").writeText("это вообще не zip")
        assertEquals(0, store.list().size)
    }
}
