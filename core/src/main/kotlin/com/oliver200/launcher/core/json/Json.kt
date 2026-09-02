/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/json/Json.kt
 *  Назначение : минимальный JSON-парсер без внешних зависимостей.
 *  Безопасность: манифесты Mojang и метаданные пакетов — НЕДОВЕРЕННЫЙ ввод.
 *               Парсер жёстко ограничен по глубине вложенности (защита от
 *               StackOverflow), по длине входа, длине строки и числу
 *               элементов — иначе один подменённый JSON кладёт приложение.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (структурные данные, секретов не хранит)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.json

/** Разобранное JSON-значение. */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val raw: String) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

class JsonException(message: String, val offset: Int = -1) : Exception(
    if (offset >= 0) "$message (позиция $offset)" else message,
)

/** Пределы разбора. Значения по умолчанию рассчитаны на манифесты Mojang с запасом. */
data class JsonLimits(
    val maxInputChars: Int = 16 * 1024 * 1024,
    val maxDepth: Int = 64,
    val maxStringChars: Int = 1 * 1024 * 1024,
    val maxCollectionSize: Int = 200_000,
) {
    companion object {
        val DEFAULT = JsonLimits()

        /** Для мелких ответов API (профиль, ник) — заведомо тесные рамки. */
        val SMALL = JsonLimits(
            maxInputChars = 256 * 1024,
            maxDepth = 24,
            maxStringChars = 128 * 1024,
            maxCollectionSize = 4_096,
        )
    }
}

object Json {

    fun parse(text: String, limits: JsonLimits = JsonLimits.DEFAULT): JsonValue {
        if (text.length > limits.maxInputChars) {
            throw JsonException("JSON слишком большой: ${text.length} > ${limits.maxInputChars}")
        }
        val p = Parser(text, limits)
        p.skipWs()
        val v = p.parseValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Мусор после JSON-значения", p.pos)
        return v
    }

    fun parseOrNull(text: String, limits: JsonLimits = JsonLimits.DEFAULT): JsonValue? =
        try {
            parse(text, limits)
        } catch (_: JsonException) {
            null
        }

    /** Кодирование строки по RFC 8259 — единственный безопасный способ склеить JSON руками. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                // U+2028/U+2029 валидны в JSON, но ломают JS-парсеры — экранируем.
                ch < ' ' || ch == '\u2028' || ch == '\u2029' ->
                    sb.append("\\u").append(hex4(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun hex4(code: Int): String {
        val h = Integer.toHexString(code)
        return "0000".substring(h.length) + h
    }

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(value, it) }.toString()

    private fun writeTo(value: JsonValue, sb: StringBuilder) {
        when (value) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(value.raw)
            is JsonValue.Str -> sb.append(escape(value.value))
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeTo(item, sb)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(escape(k)).append(':')
                    writeTo(v, sb)
                }
                sb.append('}')
            }
        }
    }

    private class Parser(val src: String, val limits: JsonLimits) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWs() {
            while (pos < src.length) {
                when (src[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > limits.maxDepth) {
                throw JsonException("Превышена глубина вложенности ${limits.maxDepth}", pos)
            }
            if (atEnd()) throw JsonException("Неожиданный конец ввода", pos)
            return when (val c = src[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else ->
                    if (c == '-' || c in '0'..'9') {
                        parseNumber()
                    } else {
                        throw JsonException("Непонятный символ '$c'", pos)
                    }
            }
        }

        private fun expect(word: String) {
            if (!src.startsWith(word, pos)) throw JsonException("Ожидалось '$word'", pos)
            pos += word.length
        }

        private fun parseObject(depth: Int): JsonValue {
            pos++ // '{'
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (!atEnd() && src[pos] == '}') {
                pos++
                return JsonValue.Obj(map)
            }
            while (true) {
                skipWs()
                if (atEnd() || src[pos] != '"') throw JsonException("Ожидался ключ-строка", pos)
                val key = parseString()
                skipWs()
                if (atEnd() || src[pos] != ':') throw JsonException("Ожидалось ':'", pos)
                pos++
                skipWs()
                val v = parseValue(depth + 1)
                // Дубли ключей — типовой приём обхода валидаторов (один парсер берёт
                // первое значение, другой последнее). Считаем это ошибкой формата.
                if (map.containsKey(key)) throw JsonException("Повторяющийся ключ '$key'", pos)
                if (map.size >= limits.maxCollectionSize) {
                    throw JsonException("Слишком много полей в объекте", pos)
                }
                map[key] = v
                skipWs()
                if (atEnd()) throw JsonException("Незакрытый объект", pos)
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(map)
                    }
                    else -> throw JsonException("Ожидалось ',' или '}'", pos)
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue {
            pos++ // '['
            val list = ArrayList<JsonValue>()
            skipWs()
            if (!atEnd() && src[pos] == ']') {
                pos++
                return JsonValue.Arr(list)
            }
            while (true) {
                skipWs()
                if (list.size >= limits.maxCollectionSize) {
                    throw JsonException("Слишком много элементов массива", pos)
                }
                list.add(parseValue(depth + 1))
                skipWs()
                if (atEnd()) throw JsonException("Незакрытый массив", pos)
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(list)
                    }
                    else -> throw JsonException("Ожидалось ',' или ']'", pos)
                }
            }
        }

        private fun parseString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Незакрытая строка", pos)
                val c = src[pos]
                when {
                    c == '"' -> {
                        pos++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos++
                        if (atEnd()) throw JsonException("Оборванная escape-последовательность", pos)
                        when (val e = src[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) throw JsonException("Обрезанный \\u", pos)
                                val hex = src.substring(pos + 1, pos + 5)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    throw JsonException("Плохой \\u$hex", pos)
                                }
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Недопустимый escape '\\$e'", pos)
                        }
                        pos++
                    }
                    c < ' ' -> throw JsonException("Сырой управляющий символ в строке", pos)
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
                if (sb.length > limits.maxStringChars) {
                    throw JsonException("Строка длиннее ${limits.maxStringChars}", pos)
                }
            }
        }

        private fun parseNumber(): JsonValue {
            val start = pos
            if (!atEnd() && src[pos] == '-') pos++
            if (atEnd()) throw JsonException("Оборванное число", pos)
            if (src[pos] == '0') {
                pos++
            } else {
                if (src[pos] !in '1'..'9') throw JsonException("Оборванное число", pos)
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && src[pos] == '.') {
                pos++
                if (atEnd() || src[pos] !in '0'..'9') throw JsonException("Нет цифр после точки", pos)
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                if (atEnd() || src[pos] !in '0'..'9') throw JsonException("Нет цифр в экспоненте", pos)
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            return JsonValue.Num(src.substring(start, pos))
        }
    }
}

/* ─────────────────── Удобные типобезопасные аксессоры ─────────────────── */

fun JsonValue?.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

fun JsonValue?.asArr(): JsonValue.Arr? = this as? JsonValue.Arr

fun JsonValue?.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

fun JsonValue?.string(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.long(): Long? = (this as? JsonValue.Num)?.raw?.let { raw ->
    raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
}

fun JsonValue?.int(): Int? =
    long()?.let { if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else null }

fun JsonValue?.bool(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue?.items(): List<JsonValue> = (this as? JsonValue.Arr)?.items ?: emptyList()

fun JsonValue?.str(name: String): String? = field(name).string()

fun JsonValue?.num(name: String): Long? = field(name).long()

/** Обязательное строковое поле: отсутствие — сразу ошибка формата, а не тихий null. */
fun JsonValue?.requireStr(name: String, where: String): String =
    str(name) ?: throw JsonException("В $where нет строкового поля '$name'")
