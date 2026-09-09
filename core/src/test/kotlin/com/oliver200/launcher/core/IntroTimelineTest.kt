/*
 * Oliver-200 · покадровая проверка заставки. Подпись: OLIVER-200 · см. SIGNATURES.txt
 *
 * Ролик проверяется целиком с шагом 1 мс: на телефоне такой прогон занял бы
 * шесть секунд на каждый прогон и требовал бы глаз, здесь — миллисекунды
 * и точные утверждения.
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.intro.Ease
import com.oliver200.launcher.core.intro.IntroCue
import com.oliver200.launcher.core.intro.IntroFrame
import com.oliver200.launcher.core.intro.IntroPhase
import com.oliver200.launcher.core.intro.IntroPlayback
import com.oliver200.launcher.core.intro.IntroScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IntroTimelineTest {

    private fun unitFields(f: IntroFrame): List<Pair<String, Float>> = listOf(
        "backdrop" to f.backdrop,
        "discAlpha" to f.discAlpha,
        "frameDraw" to f.frameDraw,
        "shine" to f.shine,
        "titleReveal" to f.titleReveal,
        "rowAlpha" to f.rowAlpha,
        "rowRise" to f.rowRise,
        "captionAlpha" to f.captionAlpha,
        "captionRise" to f.captionRise,
        "master" to f.master,
    )

    @Test
    fun `ни одно значение кадра не выходит за границы 0—1 и не становится NaN`() {
        var t = -200L
        while (t <= IntroScript.TOTAL_MS + 500L) {
            val f = IntroScript.frameAt(t)
            for ((name, v) in unitFields(f)) {
                assertFalse(v.isNaN(), "NaN в $name на $t мс")
                assertTrue(v in 0f..1f, "$name = $v вне 0..1 на $t мс")
            }
            assertTrue(f.discScale in 0.7f..1.08f, "discScale = ${f.discScale} на $t мс")
            t++
        }
    }

    @Test
    fun `фазы идут только вперёд и заканчиваются на DONE`() {
        var previous = IntroPhase.OPENING
        var t = 0L
        while (t <= IntroScript.TOTAL_MS + 100L) {
            val phase = IntroScript.frameAt(t).phase
            assertTrue(phase.ordinal >= previous.ordinal, "Фаза откатилась на $t мс: $previous -> $phase")
            previous = phase
            t += 1
        }
        assertEquals(IntroPhase.DONE, previous)
        assertEquals(IntroPhase.OPENING, IntroScript.frameAt(0).phase)
    }

    @Test
    fun `сцена гаснет только в конце и гаснет полностью`() {
        assertEquals(1f, IntroScript.frameAt(IntroScript.OUTRO_AT - 1).master)
        assertEquals(0f, IntroScript.frameAt(IntroScript.TOTAL_MS).master)

        var previous = 1f
        var t = IntroScript.OUTRO_AT
        while (t <= IntroScript.TOTAL_MS) {
            val m = IntroScript.frameAt(t).master
            assertTrue(m <= previous + 1e-6f, "Яркость выросла посреди затемнения на $t мс")
            previous = m
            t++
        }
    }

    @Test
    fun `подпись проявляется монотонно и полностью`() {
        var previous = 0f
        var t = 0L
        while (t <= IntroScript.OUTRO_AT) {
            val a = IntroScript.frameAt(t).captionAlpha
            assertTrue(a >= previous - 1e-6f, "Подпись мигнула на $t мс")
            previous = a
            t++
        }
        assertEquals(1f, IntroScript.frameAt(IntroScript.CAPTION_AT + IntroScript.CAPTION_MS).captionAlpha)
        assertEquals(0f, IntroScript.frameAt(IntroScript.CAPTION_AT).captionAlpha)
    }

    @Test
    fun `флаг завершения поднимается ровно в конце ленты`() {
        assertFalse(IntroScript.frameAt(IntroScript.TOTAL_MS - 1).finished)
        assertTrue(IntroScript.frameAt(IntroScript.TOTAL_MS).finished)
        assertTrue(IntroScript.frameAt(IntroScript.TOTAL_MS + 10_000).finished)
    }

    @Test
    fun `диктор произносит каждую реплику ровно один раз`() {
        for (step in listOf(8L, 16L, 33L, 50L, 120L)) {
            val playback = IntroPlayback()
            val heard = ArrayList<IntroCue>()
            var last = -1L
            var t = 0L
            while (t <= IntroScript.TOTAL_MS) {
                heard.addAll(playback.cuesDue(last, t))
                last = t
                t += step
            }
            assertEquals(listOf(IntroCue.BRAND, IntroCue.CARE), heard, "Шаг кадра $step мс")
        }
    }

    @Test
    fun `реплики попадают в кадр и успевают договорить`() {
        val brandFrame = IntroScript.frameAt(IntroCue.BRAND.dueAtMs)
        assertTrue(brandFrame.discAlpha > 0.9f, "Бренд назван, а логотипа ещё не видно")

        val careFrame = IntroScript.frameAt(IntroCue.CARE.dueAtMs)
        assertTrue(careFrame.captionAlpha > 0.5f, "Подпись произнесена раньше, чем видна")

        assertTrue(
            IntroScript.OUTRO_AT - IntroCue.CARE.dueAtMs >= IntroScript.SPEECH_TAIL_MS,
            "Экран гаснет раньше, чем диктор договорил",
        )
    }

    @Test
    fun `пропуск гасит сцену и обрывает диктора`() {
        val playback = IntroPlayback()
        val skipAt = 1_500L
        playback.requestSkip(skipAt)
        assertTrue(playback.isSkipping)

        assertTrue(playback.cuesDue(skipAt, IntroScript.TOTAL_MS).isEmpty(), "После пропуска диктор молчит")

        val atSkip = playback.frameAt(skipAt)
        assertEquals(1f, atSkip.master)
        assertFalse(atSkip.finished)

        val mid = playback.frameAt(skipAt + IntroScript.SKIP_FADE_MS / 2)
        assertTrue(mid.master in 0.2f..0.8f, "Затемнение по пропуску идёт не плавно: ${mid.master}")

        val end = playback.frameAt(skipAt + IntroScript.SKIP_FADE_MS)
        assertEquals(0f, end.master)
        assertTrue(end.finished)
        assertEquals(IntroPhase.DONE, end.phase)
    }

    @Test
    fun `пропуск замораживает картинку, а не отматывает её`() {
        val playback = IntroPlayback()
        val skipAt = 2_000L
        val before = IntroScript.frameAt(skipAt)
        playback.requestSkip(skipAt)
        val after = playback.frameAt(skipAt)
        assertEquals(before.titleReveal, after.titleReveal)
        assertEquals(before.discScale, after.discScale)
        assertEquals(before.frameDraw, after.frameDraw)
    }

    @Test
    fun `повторный пропуск не продлевает затемнение`() {
        val playback = IntroPlayback()
        playback.requestSkip(1_000L)
        playback.requestSkip(5_000L)
        assertTrue(playback.frameAt(1_000L + IntroScript.SKIP_FADE_MS).finished)
    }

    @Test
    fun `пропуск после конца ролика не воскрешает кадр`() {
        val playback = IntroPlayback()
        playback.requestSkip(IntroScript.TOTAL_MS + 100L)
        assertTrue(playback.frameAt(IntroScript.TOTAL_MS + 100L).finished)
    }

    @Test
    fun `отрицательное и нулевое время не ломают ленту`() {
        val first = IntroScript.frameAt(0)
        assertEquals(first, IntroScript.frameAt(-1_000).copy(elapsedMs = 0))
        assertEquals(0L, IntroScript.frameAt(-5).elapsedMs)
        assertEquals(0f, IntroScript.frameAt(-5).backdrop)
    }

    @Test
    fun `кривые сглаживания закреплены на краях`() {
        // Каждая кривая обязана начинаться в 0, заканчиваться в 1 и не
        // пропускать наружу мусор со входа — иначе кадр «прыгнет».
        assertEquals(0f, Ease.linear(0f), 1e-6f)
        assertEquals(1f, Ease.linear(1f), 1e-6f)
        assertEquals(0f, Ease.outCubic(0f), 1e-6f)
        assertEquals(1f, Ease.outCubic(1f), 1e-6f)
        assertEquals(0f, Ease.inCubic(0f), 1e-6f)
        assertEquals(1f, Ease.inCubic(1f), 1e-6f)
        assertEquals(0f, Ease.inOutCubic(0f), 1e-6f)
        assertEquals(1f, Ease.inOutCubic(1f), 1e-6f)
        assertEquals(0.5f, Ease.inOutCubic(0.5f), 1e-6f)

        assertEquals(0f, Ease.outCubic(-3f), 1e-6f)
        assertEquals(1f, Ease.outCubic(3f), 1e-6f)
        assertEquals(0f, Ease.inOutCubic(Float.NaN), 1e-6f)
        assertEquals(0f, Ease.clamp01(Float.NaN), 1e-6f)

        assertEquals(0f, Ease.outBack(0f), 1e-6f)
        assertEquals(1f, Ease.outBack(1f), 1e-6f)
        // Отдача обязана быть: без проскока логотип «подъезжает», а не «прилетает».
        assertTrue(Ease.outBack(0.75f) > 1f, "Проскок пропал — анимация станет вялой")
    }

    @Test
    fun `окно реплик считается по полуинтервалу`() {
        val at = IntroCue.BRAND.dueAtMs
        assertTrue(IntroScript.cuesBetween(at, at + 5).isEmpty(), "Левая граница обязана быть открытой")
        assertEquals(listOf(IntroCue.BRAND), IntroScript.cuesBetween(at - 5, at))
        assertTrue(IntroScript.cuesBetween(at, at).isEmpty())
        assertTrue(IntroScript.cuesBetween(at + 10, at).isEmpty())
    }
}
