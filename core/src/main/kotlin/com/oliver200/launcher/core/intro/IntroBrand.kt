/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/intro/IntroBrand.kt
 *  Назначение : тексты заставки (две строки вордмарка, плашка «RU»,
 *               «STEAM COMMUNITY», подпись «Заботимся о вас» и реплики
 *               диктора) плюс строгий формат их записи.
 *
 *  ВАРИАНТ    : БЕЗ ШИФРОВАНИЯ (общий формат для обоих вариантов хранения).
 *  Пара файлов, которые этот формат наполняют:
 *      ★ core/intro/SealedIntroBrand.kt — С ШИФРОВАНИЕМ (AES-256-GCM);
 *      ☆ core/intro/PlainIntroBrand.kt  — БЕЗ ШИФРОВАНИЯ (отладка, тесты).
 *
 *  Почему у текстов вообще есть формат и валидация:
 *      строки заставки попадают прямо на экран и прямо в синтезатор речи.
 *      APK лаунчера расходится файлом, а не через магазин — значит его
 *      будут перепаковывать. Подменённый вордмарк («официальный клиент
 *      такой-то») — это готовый фишинг, а управляющие символы в строке
 *      для TTS — способ заставить движок читать что угодно. Поэтому
 *      запись разбирается строго: фиксированное число полей, лимит
 *      длины, никаких управляющих символов.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.intro

/** Единственный тип ошибки наружу: заставка обязана падать тихо и целиком. */
class IntroBrandException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Тексты заставки. Раскладка повторяет знак сообщества:
 *
 *     ┌───────────────────────────┐
 *     │  ANIMAL                   │   ← wordmarkTop
 *     │  COMPANY                  │   ← wordmarkBottom
 *     │  ▐RU▌ STEAM COMMUNITY     │   ← badge + platform
 *     └───────────────────────────┘
 *
 * Реплики диктора хранятся отдельно от того, что нарисовано на экране,
 * и по-русски записаны «как слышится»: русский голос TTS читает
 * «Animal Company» как набор латинских букв, а «Энимал Компани» — как
 * название. Латинский набор нужен, когда русского голоса на устройстве нет.
 */
data class IntroBrand(
    /** Верхняя строка вордмарка. */
    val wordmarkTop: String,
    /** Нижняя строка вордмарка. */
    val wordmarkBottom: String,
    /** Текст в инверсной плашке нижней строки. */
    val badge: String,
    /** Текст рядом с плашкой. */
    val platform: String,
    /** Подпись под логотипом. */
    val care: String,
    /** Реплика 1 для русского голоса. */
    val voiceRuBrand: String,
    /** Реплика 2 для русского голоса. */
    val voiceRuCare: String,
    /** Реплика 1 для нерусского голоса. */
    val voiceEnBrand: String,
    /** Реплика 2 для нерусского голоса. */
    val voiceEnCare: String,
) {
    /**
     * Текст реплики. Диктор умеет произносить ТОЛЬКО то, что вернёт этот
     * метод: на входе enum, а не строка, поэтому передать в озвучку ник,
     * токен или пришедший из сети текст физически невозможно.
     */
    fun voice(cue: IntroCue, russianVoice: Boolean): String = when (cue) {
        IntroCue.BRAND -> if (russianVoice) voiceRuBrand else voiceEnBrand
        IntroCue.CARE -> if (russianVoice) voiceRuCare else voiceEnCare
    }
}

/**
 * Сериализация записи бренда.
 *
 * Формат: `OL2B1 <US> поле1 <US> … <US> поле9`, где `<US>` — U+001F.
 * Разделитель взят из управляющей зоны намеренно: внутри полей
 * управляющие символы запрещены, поэтому склеить два поля в одно
 * нельзя ни случайно, ни специально.
 */
object IntroBrandCodec {

    const val MAGIC = "OL2B1"

    /** U+001F, разделитель полей (записан кодом, чтобы не прятаться в тексте). */
    val SEPARATOR: Char = Char(31)

    const val FIELDS = 9
    const val MAX_FIELD_CHARS = 64

    @Throws(IntroBrandException::class)
    fun encode(brand: IntroBrand): String {
        val parts = fieldsOf(brand)
        parts.forEach { checkField(it) }
        return MAGIC + SEPARATOR + parts.joinToString(SEPARATOR.toString())
    }

    @Throws(IntroBrandException::class)
    fun decode(record: String): IntroBrand {
        val parts = record.split(SEPARATOR)
        if (parts.size != FIELDS + 1) {
            throw IntroBrandException("Запись бренда: полей ${parts.size - 1}, ожидалось $FIELDS")
        }
        if (parts[0] != MAGIC) throw IntroBrandException("Запись бренда: чужая метка формата")
        for (i in 1..FIELDS) checkField(parts[i])
        return IntroBrand(
            wordmarkTop = parts[1],
            wordmarkBottom = parts[2],
            badge = parts[3],
            platform = parts[4],
            care = parts[5],
            voiceRuBrand = parts[6],
            voiceRuCare = parts[7],
            voiceEnBrand = parts[8],
            voiceEnCare = parts[9],
        )
    }

    fun fieldsOf(brand: IntroBrand): List<String> = listOf(
        brand.wordmarkTop,
        brand.wordmarkBottom,
        brand.badge,
        brand.platform,
        brand.care,
        brand.voiceRuBrand,
        brand.voiceRuCare,
        brand.voiceEnBrand,
        brand.voiceEnCare,
    )

    /**
     * Поле годится, если оно непустое, не длиннее лимита, без краевых
     * пробелов и без управляющих символов. Проверка одна на оба варианта
     * хранения — значит в открытый вариант нельзя «нечаянно» положить то,
     * что не пролезло бы через шифрованный.
     */
    @Throws(IntroBrandException::class)
    fun checkField(value: String) {
        if (value.isEmpty()) throw IntroBrandException("Запись бренда: пустое поле")
        if (value.length > MAX_FIELD_CHARS) {
            throw IntroBrandException("Запись бренда: поле длиннее $MAX_FIELD_CHARS символов")
        }
        if (value.trim() != value) throw IntroBrandException("Запись бренда: краевые пробелы")
        for (ch in value) {
            if (ch.isISOControl()) throw IntroBrandException("Запись бренда: управляющий символ")
        }
    }
}
