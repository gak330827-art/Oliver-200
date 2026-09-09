/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/intro/IntroTimeline.kt
 *  Назначение : раскадровка заставки Animal Company · RU Steam Community —
 *               когда проявляется фон, когда вылетает белый диск логотипа,
 *               когда обводится рамка и набираются буквы, когда всходит
 *               подпись «Заботимся о вас» и где вступает диктор.
 *
 *  ВАРИАНТ    : БЕЗ ШИФРОВАНИЯ (чистая арифметика, секретов нет).
 *               Пара «с шифрованием / без» — у текстов бренда:
 *               core/intro/SealedIntroBrand.kt и core/intro/PlainIntroBrand.kt.
 *
 *  Почему логика ролика лежит в :core, а не рядом с View:
 *      · её можно прогнать покадрово обычным JUnit — без эмулятора и без
 *        Android SDK (CI собирает :core отдельно);
 *      · рисование становится тупым: View только читает готовый кадр,
 *        поэтому «моргнуло», «дёрнулось», «диктор заговорил дважды» ловится
 *        тестом, а не глазами на телефоне.
 *
 *  Три проверки логики (то же, что при сборке сцены в Blender):
 *      1) ни одно значение не выходит за 0..1 и не становится NaN —
 *         тест гоняет всю ленту с шагом 1 мс;
 *      2) реплики диктора не наезжают друг на друга и обе успевают
 *         прозвучать до затемнения — проверяется инвариантами ниже;
 *      3) пропуск (тап по экрану) не воспроизводит реплики и всегда
 *         доводит кадр до finished — иначе экран завис бы навсегда.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.intro

/** Крупные фазы ролика. Нужны для отладки и тестов, рисование ими не управляется. */
enum class IntroPhase { OPENING, DISC, TITLE, ROW, HOLD, OUTRO, DONE }

/**
 * Реплики диктора и их место на ленте.
 *
 * Момент реплики — часть раскадровки, а не настройка звука: голос обязан
 * попадать в кадр. BRAND звучит, когда логотип уже собрался, CARE — когда
 * подпись внизу начала проявляться.
 */
enum class IntroCue(val dueAtMs: Long) {
    BRAND(1_050L),
    CARE(2_760L),
}

/**
 * Готовый кадр. Все поля — множители 0..1, кроме [discScale] (масштаб)
 * и [elapsedMs].
 *
 * [master] — общая непрозрачность сцены: и финальное затемнение, и быстрый
 * уход по «Пропустить» делаются только через неё, чтобы ни один элемент
 * не остался висеть ярче остальных.
 */
data class IntroFrame(
    val elapsedMs: Long,
    val phase: IntroPhase,
    /** Проявление фона-виньетки. */
    val backdrop: Float,
    /** Белый диск аватара: непрозрачность и масштаб с лёгкой отдачей. */
    val discAlpha: Float,
    val discScale: Float,
    /** Доля обведённой рамки логотипа: 0 — рамки нет, 1 — обведена целиком. */
    val frameDraw: Float,
    /** Положение блика, скользящего по логотипу. */
    val shine: Float,
    /** Доля набранных букв «ANIMAL / COMPANY». */
    val titleReveal: Float,
    /** Нижняя строка «RU · STEAM COMMUNITY». */
    val rowAlpha: Float,
    val rowRise: Float,
    /** Подпись «Заботимся о вас» под диском. */
    val captionAlpha: Float,
    val captionRise: Float,
    val master: Float,
    val finished: Boolean,
)

/** Кривые сглаживания. Ручное умножение вместо pow(): быстрее и без импортов. */
object Ease {

    fun linear(t: Float): Float = clamp01(t)

    fun outCubic(t: Float): Float {
        val i = 1f - clamp01(t)
        return 1f - i * i * i
    }

    fun inCubic(t: Float): Float {
        val x = clamp01(t)
        return x * x * x
    }

    fun inOutCubic(t: Float): Float {
        val x = clamp01(t)
        if (x < 0.5f) return 4f * x * x * x
        val u = -2f * x + 2f
        return 1f - u * u * u / 2f
    }

    /**
     * Проскок за единицу и возврат — диск «прилетает» с лёгкой отдачей.
     * outBack(0) == 0 и outBack(1) == 1 при любом overshoot.
     */
    fun outBack(t: Float, overshoot: Float = 1.70158f): Float {
        val x = clamp01(t)
        val c3 = overshoot + 1f
        val i = x - 1f
        return 1f + c3 * i * i * i + overshoot * i * i
    }

    fun clamp01(v: Float): Float = when {
        v.isNaN() -> 0f
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }
}

/**
 * Сама лента. Все времена — миллисекунды от начала ролика.
 *
 *   0 ─── 420 ──── 1140 ─── 1900 ──── 2260 ──── 3220 ──────── 5900 ─── 6500
 *   │ фон  │ диск   │ рамка  │ буквы   │ RU·STEAM │ «Заботимся» │ пауза │ уход
 *                            ▲ диктор: 1050        ▲ диктор: 2760
 */
object IntroScript {

    const val BACKDROP_AT = 0L
    const val BACKDROP_MS = 420L

    const val DISC_AT = 200L
    const val DISC_MS = 780L
    const val DISC_SCALE_FROM = 0.72f

    /** Рамка логотипа обводится пером — как будто её только что нарисовали. */
    const val FRAME_AT = 620L
    const val FRAME_MS = 900L

    const val TITLE_AT = 1_020L
    const val TITLE_MS = 880L

    const val ROW_AT = 1_820L
    const val ROW_MS = 560L

    const val SHINE_AT = 2_150L
    const val SHINE_MS = 950L

    const val CAPTION_AT = 2_500L
    const val CAPTION_MS = 720L

    /*
     * Затемнение отодвинуто под живую речь, а не под догадку: обе реплики
     * синтезированы и замерены (2,50 с и 1,59 с в естественном темпе).
     * 1,05 + 2,50 + 1,59 = 5,14 с — плюс запас на движки, которые читают
     * медленнее. Раньше здесь было 5 400 мс, и на медленном синтезаторе
     * «Заботимся о вас» обрывалось на полуслове.
     */
    const val OUTRO_AT = 5_900L
    const val OUTRO_MS = 600L

    const val TOTAL_MS = OUTRO_AT + OUTRO_MS

    /** Сколько длится затемнение, если зритель нажал «Пропустить». */
    const val SKIP_FADE_MS = 220L

    /** Запас между репликой и затемнением: короткая фраза диктора ≈ 1,5 с. */
    const val SPEECH_TAIL_MS = 1_500L

    init {
        // Инварианты раскадровки. Если кто-то поправит одну константу и
        // сломает порядок — сборка упадёт здесь, а не «иногда на телефоне».
        require(BACKDROP_MS > 0 && DISC_MS > 0 && FRAME_MS > 0 && SHINE_MS > 0) {
            "Длительности сегментов должны быть положительными"
        }
        require(TITLE_MS > 0 && ROW_MS > 0 && CAPTION_MS > 0 && OUTRO_MS > 0) {
            "Длительности сегментов должны быть положительными"
        }
        require(DISC_AT < FRAME_AT && FRAME_AT < TITLE_AT && TITLE_AT < ROW_AT) {
            "Порядок сборки логотипа нарушен: диск → рамка → буквы → строка"
        }
        require(ROW_AT < CAPTION_AT) { "Подпись не может опережать логотип" }
        require(IntroCue.BRAND.dueAtMs >= TITLE_AT) {
            "Диктор называет бренд раньше, чем появились буквы"
        }
        require(IntroCue.CARE.dueAtMs >= CAPTION_AT) {
            "Диктор произносит подпись раньше, чем она появилась на экране"
        }
        require(IntroCue.CARE.dueAtMs - IntroCue.BRAND.dueAtMs >= SPEECH_TAIL_MS) {
            "Реплики диктора наезжают друг на друга"
        }
        require(OUTRO_AT >= IntroCue.CARE.dueAtMs + SPEECH_TAIL_MS) {
            "Экран гаснет раньше, чем диктор договорил"
        }
        require(CAPTION_AT + CAPTION_MS <= OUTRO_AT) {
            "Подпись не успевает проявиться до затемнения"
        }
    }

    /** Доля прогресса внутри сегмента [start; start+dur]. */
    private fun p(t: Long, start: Long, dur: Long): Float {
        if (dur <= 0L) return if (t >= start) 1f else 0f
        return Ease.clamp01((t - start).toFloat() / dur.toFloat())
    }

    fun frameAt(elapsedMs: Long): IntroFrame {
        val t = if (elapsedMs < 0L) 0L else elapsedMs

        val discP = p(t, DISC_AT, DISC_MS)
        val rowP = p(t, ROW_AT, ROW_MS)
        val capP = p(t, CAPTION_AT, CAPTION_MS)
        val outroP = p(t, OUTRO_AT, OUTRO_MS)

        val phase = when {
            t >= TOTAL_MS -> IntroPhase.DONE
            t >= OUTRO_AT -> IntroPhase.OUTRO
            t >= CAPTION_AT + CAPTION_MS -> IntroPhase.HOLD
            t >= ROW_AT -> IntroPhase.ROW
            t >= TITLE_AT -> IntroPhase.TITLE
            t >= DISC_AT -> IntroPhase.DISC
            else -> IntroPhase.OPENING
        }

        return IntroFrame(
            elapsedMs = t,
            phase = phase,
            backdrop = Ease.outCubic(p(t, BACKDROP_AT, BACKDROP_MS)),
            // Непрозрачность набирается быстрее геометрии: иначе диск
            // заметно «проявляется уже большим».
            discAlpha = Ease.outCubic(discP * 1.6f),
            discScale = DISC_SCALE_FROM + (1f - DISC_SCALE_FROM) * Ease.outBack(discP),
            frameDraw = Ease.inOutCubic(p(t, FRAME_AT, FRAME_MS)),
            shine = Ease.inOutCubic(p(t, SHINE_AT, SHINE_MS)),
            titleReveal = Ease.outCubic(p(t, TITLE_AT, TITLE_MS)),
            rowAlpha = Ease.outCubic(rowP),
            rowRise = 1f - Ease.outCubic(rowP),
            captionAlpha = Ease.outCubic(capP),
            captionRise = 1f - Ease.outCubic(capP),
            master = 1f - Ease.inCubic(outroP),
            finished = t >= TOTAL_MS,
        )
    }

    /**
     * Реплики, попавшие в интервал (fromExclusiveMs; toInclusiveMs].
     * Полуинтервал важен: кадры идут встык, и при закрытом интервале
     * с обеих сторон реплика на границе прозвучала бы дважды.
     */
    fun cuesBetween(fromExclusiveMs: Long, toInclusiveMs: Long): List<IntroCue> {
        if (toInclusiveMs <= fromExclusiveMs) return emptyList()
        // Быстрый выход: подавляющее большинство кадров лежит вне окон реплик.
        if (toInclusiveMs < IntroCue.BRAND.dueAtMs || fromExclusiveMs >= IntroCue.CARE.dueAtMs) {
            return emptyList()
        }
        var out: ArrayList<IntroCue>? = null
        for (cue in IntroCue.entries) {
            if (cue.dueAtMs > fromExclusiveMs && cue.dueAtMs <= toInclusiveMs) {
                val list = out ?: ArrayList<IntroCue>(2).also { out = it }
                list.add(cue)
            }
        }
        return out ?: emptyList()
    }
}

/**
 * Проигрыватель ленты: помнит ровно одно — просили ли пропустить.
 *
 * Пропуск сделан «заморозкой»: кадр остаётся тем, что был в момент тапа,
 * и гаснет за [IntroScript.SKIP_FADE_MS]. Так экран не дёргается назад
 * во времени, и не нужна отдельная раскадровка для быстрого ухода.
 */
class IntroPlayback {

    private var skipAtMs: Long = NOT_SKIPPED

    val isSkipping: Boolean get() = skipAtMs != NOT_SKIPPED

    fun requestSkip(atMs: Long) {
        if (skipAtMs == NOT_SKIPPED) skipAtMs = if (atMs < 0L) 0L else atMs
    }

    fun reset() {
        skipAtMs = NOT_SKIPPED
    }

    fun frameAt(elapsedMs: Long): IntroFrame {
        val t = if (elapsedMs < 0L) 0L else elapsedMs
        val skip = skipAtMs
        if (skip == NOT_SKIPPED) return IntroScript.frameAt(t)

        val frozen = IntroScript.frameAt(skip)
        val gone = Ease.clamp01((t - skip).toFloat() / IntroScript.SKIP_FADE_MS.toFloat())
        val k = 1f - gone
        return frozen.copy(
            elapsedMs = t,
            phase = if (k <= 0f) IntroPhase.DONE else IntroPhase.OUTRO,
            master = frozen.master * k,
            finished = frozen.finished || k <= 0f,
        )
    }

    /** После пропуска диктор молчит: зритель уже сказал «дальше». */
    fun cuesDue(fromExclusiveMs: Long, toInclusiveMs: Long): List<IntroCue> =
        if (isSkipping) emptyList() else IntroScript.cuesBetween(fromExclusiveMs, toInclusiveMs)

    private companion object {
        const val NOT_SKIPPED = -1L
    }
}
