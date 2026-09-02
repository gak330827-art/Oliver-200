/*
 * Oliver-200 · тесты «скин по нику»: валидация, разбор профиля, геометрия атласа.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.skin.MinecraftName
import com.oliver200.launcher.core.skin.MinecraftUuid
import com.oliver200.launcher.core.skin.SkinAtlas
import com.oliver200.launcher.core.skin.SkinEndpoints
import com.oliver200.launcher.core.skin.SkinFace
import com.oliver200.launcher.core.skin.SkinModel
import com.oliver200.launcher.core.skin.SkinPart
import com.oliver200.launcher.core.skin.SkinProfileParser
import com.oliver200.launcher.core.skin.SkinRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

class SkinTest {

    /* ───────────────────────── Валидация ника ───────────────────────── */

    @Test
    fun `корректные ники принимаются`() {
        for (n in listOf("Notch", "jeb_", "Steve", "A_1", "x".repeat(16))) {
            assertTrue(MinecraftName.isValid(n), n)
        }
    }

    @Test
    @DisplayName("Ник с ../ или пробелом не попадёт в URL")
    fun `опасные ники отвергаются`() {
        val attacks = listOf(
            "../../admin", "Steve Jobs", "Стив", "ab", "x".repeat(17),
            "Steve/../../", "Steve%0d%0aHost:evil", "Steve?x=1", "Steve#frag", "",
        )
        for (a in attacks) {
            assertFalse(MinecraftName.isValid(a), "должен быть отвергнут: $a")
            assertNull(MinecraftName.normalize(a))
        }
        assertEquals("Notch", MinecraftName.normalize("  Notch  "))
    }

    @Test
    fun `построение адреса требует валидный ник`() {
        assertEquals(
            "https://api.mojang.com/users/profiles/minecraft/Notch",
            SkinEndpoints.nameToUuid("Notch"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SkinEndpoints.nameToUuid("../../evil")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SkinEndpoints.profile("не-uuid")
        }
    }

    /* ───────────────────────── UUID ───────────────────────── */

    @Test
    fun `UUID приводится к обоим форматам`() {
        val undashed = "069a79f444e94726a5befca90e38aaf5"
        val dashed = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        assertEquals(dashed, MinecraftUuid.dashed(undashed))
        assertEquals(undashed, MinecraftUuid.strip(dashed))
        assertEquals(undashed, MinecraftUuid.strip(undashed.uppercase()))
        assertNull(MinecraftUuid.strip("короткий"))
        assertNull(MinecraftUuid.dashed(null))
        assertTrue(MinecraftUuid.isValid(dashed))
        assertFalse(MinecraftUuid.isValid("069a79f4-44e9"))
    }

    /* ───────────────────────── Разбор профиля ───────────────────────── */

    @Test
    fun `ответ поиска по нику разбирается`() {
        val ok = SkinProfileParser.parseNameLookup(
            """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}""",
        )
        assertNotNull(ok)
        assertEquals("Notch", ok!!.name)
        assertEquals("069a79f444e94726a5befca90e38aaf5", ok.uuid)

        assertNull(SkinProfileParser.parseNameLookup("""{"errorMessage":"Not Found"}"""))
        assertNull(SkinProfileParser.parseNameLookup("не json"))
    }

    private fun texturesProperty(skinUrl: String, model: String? = null): String {
        val meta = if (model == null) "" else ""","metadata":{"model":"$model"}"""
        val payload = """
            {"timestamp":1700000000000,"profileId":"069a79f444e94726a5befca90e38aaf5",
             "profileName":"Notch","textures":{"SKIN":{"url":"$skinUrl"$meta}}}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(payload.toByteArray())
        return """{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch",
                   "properties":[{"name":"textures","value":"$encoded"}]}"""
    }

    @Test
    fun `текстуры распаковываются из base64`() {
        val json = texturesProperty("http://textures.minecraft.net/texture/abc123", "slim")
        val profile = SkinProfileParser.parseProfile(json)
        assertNotNull(profile)
        assertEquals("Notch", profile!!.profileName)
        assertEquals(SkinModel.SLIM, profile.skin!!.model)
        // http поднят до https автоматически
        assertEquals("https://textures.minecraft.net/texture/abc123", profile.skin!!.url)
    }

    @Test
    fun `без metadata модель считается классической`() {
        val profile = SkinProfileParser.parseProfile(
            texturesProperty("https://textures.minecraft.net/texture/abc"),
        )
        assertEquals(SkinModel.CLASSIC, profile!!.skin!!.model)
    }

    @Test
    @DisplayName("Ссылка на текстуру с чужого хоста отбрасывается")
    fun `подменённый CDN текстур не проходит`() {
        val profile = SkinProfileParser.parseProfile(
            texturesProperty("https://textures.minecraft.net.evil.tld/texture/abc"),
        )
        assertNotNull(profile)
        assertNull(profile!!.skin)
    }

    @Test
    fun `битый base64 и отсутствие свойства не роняют разбор`() {
        assertNull(
            SkinProfileParser.parseProfile(
                """{"id":"x","name":"y","properties":[{"name":"textures","value":"!!!не base64!!!"}]}""",
            ),
        )
        assertNull(SkinProfileParser.parseProfile("""{"id":"x","name":"y","properties":[]}"""))
        assertNull(SkinProfileParser.decodeTextures("A".repeat(100_000)))
    }

    /* ───────────────────────── Геометрия атласа ───────────────────────── */

    @Test
    fun `распознаются только настоящие размеры скина`() {
        assertNotNull(SkinAtlas.layoutFor(64, 64))
        assertNotNull(SkinAtlas.layoutFor(64, 32))
        assertNotNull(SkinAtlas.layoutFor(128, 128))
        assertNotNull(SkinAtlas.layoutFor(128, 64))
        assertNull(SkinAtlas.layoutFor(63, 64))
        assertNull(SkinAtlas.layoutFor(64, 48))
        assertNull(SkinAtlas.layoutFor(0, 0))
        assertNull(SkinAtlas.layoutFor(-64, 64))
        assertNull(SkinAtlas.layoutFor(4096, 4096))
        assertTrue(SkinAtlas.layoutFor(64, 32)!!.legacy)
        assertFalse(SkinAtlas.layoutFor(64, 64)!!.legacy)
    }

    @Test
    @DisplayName("Канонические координаты граней совпадают с раскладкой Mojang")
    fun `координаты частей тела верны`() {
        val l = SkinAtlas.layoutFor(64, 64)!!
        assertEquals(SkinRect(8, 8, 8, 8), l.base(SkinPart.HEAD))
        assertEquals(SkinRect(40, 8, 8, 8), l.overlay(SkinPart.HEAD))
        assertEquals(SkinRect(20, 20, 8, 12), l.base(SkinPart.BODY))
        assertEquals(SkinRect(20, 36, 8, 12), l.overlay(SkinPart.BODY))
        assertEquals(SkinRect(44, 20, 4, 12), l.base(SkinPart.RIGHT_ARM))
        assertEquals(SkinRect(44, 36, 4, 12), l.overlay(SkinPart.RIGHT_ARM))
        assertEquals(SkinRect(4, 20, 4, 12), l.base(SkinPart.RIGHT_LEG))
        assertEquals(SkinRect(4, 36, 4, 12), l.overlay(SkinPart.RIGHT_LEG))
        assertEquals(SkinRect(36, 52, 4, 12), l.base(SkinPart.LEFT_ARM))
        assertEquals(SkinRect(52, 52, 4, 12), l.overlay(SkinPart.LEFT_ARM))
        assertEquals(SkinRect(20, 52, 4, 12), l.base(SkinPart.LEFT_LEG))
        assertEquals(SkinRect(4, 52, 4, 12), l.overlay(SkinPart.LEFT_LEG))
    }

    @Test
    fun `грани головы разложены по схеме куба`() {
        val l = SkinAtlas.layoutFor(64, 64)!!
        assertEquals(SkinRect(8, 0, 8, 8), l.base(SkinPart.HEAD, SkinFace.TOP))
        assertEquals(SkinRect(16, 0, 8, 8), l.base(SkinPart.HEAD, SkinFace.BOTTOM))
        assertEquals(SkinRect(0, 8, 8, 8), l.base(SkinPart.HEAD, SkinFace.RIGHT))
        assertEquals(SkinRect(16, 8, 8, 8), l.base(SkinPart.HEAD, SkinFace.LEFT))
        assertEquals(SkinRect(24, 8, 8, 8), l.base(SkinPart.HEAD, SkinFace.BACK))
    }

    @Test
    fun `тонкая модель сужает руку до трёх пикселей`() {
        val slim = SkinAtlas.layoutFor(64, 64, SkinModel.SLIM)!!
        assertEquals(3, slim.armWidth)
        assertEquals(SkinRect(44, 20, 3, 12), slim.base(SkinPart.RIGHT_ARM))
        // Развёртка левой руки: right(4) | front(3) | left(4) | back(3) от u=32.
        assertEquals(SkinRect(36, 52, 3, 12), slim.base(SkinPart.LEFT_ARM))
        assertEquals(SkinRect(32, 52, 4, 12), slim.base(SkinPart.LEFT_ARM, SkinFace.RIGHT))
    }

    @Test
    fun `масштабированный скин даёт умноженные координаты`() {
        val hd = SkinAtlas.layoutFor(128, 128)!!
        assertEquals(2, hd.scale)
        assertEquals(SkinRect(16, 16, 16, 16), hd.base(SkinPart.HEAD))
    }

    @Test
    fun `у старого скина нет второго слоя и левых конечностей`() {
        val old = SkinAtlas.layoutFor(64, 32)!!
        assertNotNull(old.overlay(SkinPart.HEAD))
        assertNull(old.overlay(SkinPart.BODY))
        assertNull(old.overlay(SkinPart.LEFT_ARM))
        // левая рука зеркалит правую и остаётся в пределах 64x32
        val leftArm = old.base(SkinPart.LEFT_ARM)
        assertTrue(leftArm.bottom <= 32, "вышли за пределы атласа: $leftArm")
        assertTrue(leftArm.right <= 64)
    }

    @Test
    fun `все прямоугольники лежат внутри картинки`() {
        for ((w, h) in listOf(64 to 64, 64 to 32, 128 to 128)) {
            val l = SkinAtlas.layoutFor(w, h)!!
            for (part in SkinAtlas.BODY_DRAW_ORDER) {
                for (face in SkinFace.entries) {
                    val r = l.base(part, face)
                    assertTrue(r.x >= 0 && r.y >= 0 && r.right <= w && r.bottom <= h, "$part/$face -> $r ($w x $h)")
                    l.overlay(part, face)?.let { o ->
                        assertTrue(o.x >= 0 && o.y >= 0 && o.right <= w && o.bottom <= h, "overlay $part/$face -> $o")
                    }
                }
            }
        }
    }

    @Test
    fun `эвристика модели различает classic и slim`() {
        val l = SkinAtlas.layoutFor(64, 64)!!
        assertEquals(SkinModel.SLIM, SkinAtlas.guessModel(l) { _, _ -> 0 })
        assertEquals(SkinModel.CLASSIC, SkinAtlas.guessModel(l) { _, _ -> 255 })
        assertEquals(SkinModel.CLASSIC, SkinAtlas.guessModel(SkinAtlas.layoutFor(64, 32)!!) { _, _ -> 0 })
    }
}
