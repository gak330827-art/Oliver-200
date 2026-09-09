/*
 * Oliver-200 · тексты заставки: шифрованный и открытый варианты.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 *
 * Главная проверка здесь — «оба варианта говорят одно и то же».
 * Именно она не даёт молча разъехаться паре файлов
 * SealedIntroBrand.kt (★ с шифрованием) и PlainIntroBrand.kt (☆ без).
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.intro.IntroBrand
import com.oliver200.launcher.core.intro.IntroBrandCodec
import com.oliver200.launcher.core.intro.IntroBrandException
import com.oliver200.launcher.core.intro.IntroBrandSource
import com.oliver200.launcher.core.intro.IntroCue
import com.oliver200.launcher.core.intro.PlainIntroBrand
import com.oliver200.launcher.core.intro.SealedIntroBrand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroBrandTest {

    @Test
    fun `шифрованный контейнер открывается и содержит знак сообщества`() {
        val brand = SealedIntroBrand.load()
        assertEquals("ANIMAL", brand.wordmarkTop)
        assertEquals("COMPANY", brand.wordmarkBottom)
        assertEquals("RU", brand.badge)
        assertEquals("STEAM COMMUNITY", brand.platform)
        assertEquals("Заботимся о вас", brand.care)
    }

    @Test
    fun `открытый и шифрованный варианты совпадают слово в слово`() {
        assertEquals(PlainIntroBrand.load(), SealedIntroBrand.load())
    }

    @Test
    fun `контейнер привязан к контексту`() {
        // Тот же контейнер, тот же ключ, другая привязка — GCM не пускает.
        assertThrows(IntroBrandException::class.java) {
            SealedIntroBrand.load("чужая-сборка/v1")
        }
        assertThrows(IntroBrandException::class.java) {
            SealedIntroBrand.load(SealedIntroBrand.BINDING_DEFAULT + "1")
        }
    }

    @Test
    fun `реплики диктора выбираются по языку голоса`() {
        val brand = SealedIntroBrand.load()
        assertEquals(brand.voiceRuBrand, brand.voice(IntroCue.BRAND, russianVoice = true))
        assertEquals(brand.voiceRuCare, brand.voice(IntroCue.CARE, russianVoice = true))
        assertEquals(brand.voiceEnBrand, brand.voice(IntroCue.BRAND, russianVoice = false))
        assertEquals(brand.voiceEnCare, brand.voice(IntroCue.CARE, russianVoice = false))
        assertTrue(brand.voiceRuBrand.none { it in 'A'..'Z' }, "Русской реплике нечего делать с латиницей")
    }

    @Test
    fun `открытый вариант не пускается в release`() {
        assertThrows(IntroBrandException::class.java) { PlainIntroBrand.guardRelease(debugBuild = false) }
        PlainIntroBrand.guardRelease(debugBuild = true)
    }

    @Test
    fun `выбор варианта повторяет правило хранилища ключей`() {
        assertEquals(SealedIntroBrand.load(), IntroBrandSource.load(debugBuild = false))
        assertEquals(SealedIntroBrand.load(), IntroBrandSource.load(debugBuild = true))
        assertEquals(
            PlainIntroBrand.load(),
            IntroBrandSource.load(debugBuild = true, forcePlain = true),
        )
        assertThrows(IntroBrandException::class.java) {
            IntroBrandSource.load(debugBuild = false, forcePlain = true)
        }
    }

    @Test
    fun `запись бренда обратима`() {
        val brand = PlainIntroBrand.BRAND
        assertEquals(brand, IntroBrandCodec.decode(IntroBrandCodec.encode(brand)))
    }

    @Test
    fun `разбор записи отвергает мусор`() {
        val good = IntroBrandCodec.encode(PlainIntroBrand.BRAND)
        val sep = IntroBrandCodec.SEPARATOR

        // Чужая метка формата.
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.decode("XXXXX" + good.substring(5)) }
        // Лишнее поле.
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.decode(good + sep + "лишнее") }
        // Недостача полей.
        assertThrows(IntroBrandException::class.java) {
            IntroBrandCodec.decode(good.substringBeforeLast(sep))
        }
        // Пустое поле в хвосте.
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.decode(good + sep) }
        // Пустая строка и обрывки.
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.decode("") }
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.decode("OL2B1") }
    }

    @Test
    fun `поле не может быть опасным`() {
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.checkField("") }
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.checkField(" ANIMAL") }
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.checkField("ANIMAL ") }
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.checkField("A".repeat(65)) }
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.checkField("ANIMAL\nCOMPANY") }
        assertThrows(IntroBrandException::class.java) {
            IntroBrandCodec.checkField("ANIMAL" + IntroBrandCodec.SEPARATOR)
        }
        IntroBrandCodec.checkField("A".repeat(64))
        IntroBrandCodec.checkField("Заботимся о вас")
    }

    @Test
    fun `кодировщик не выпускает битую запись наружу`() {
        val broken = PlainIntroBrand.BRAND.copy(care = " Заботимся о вас")
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.encode(broken) }

        val tooLong: IntroBrand = PlainIntroBrand.BRAND.copy(platform = "S".repeat(65))
        assertThrows(IntroBrandException::class.java) { IntroBrandCodec.encode(tooLong) }
    }
}
