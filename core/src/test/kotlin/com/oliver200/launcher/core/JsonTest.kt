/*
 * Oliver-200 · тесты укреплённого JSON-парсера. Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.json.JsonException
import com.oliver200.launcher.core.json.JsonLimits
import com.oliver200.launcher.core.json.JsonValue
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.int
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.str
import com.oliver200.launcher.core.json.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class JsonTest {

    @Test
    fun `разбирает вложенные структуры`() {
        val v = Json.parse("""{"a":[1,2,{"b":"текст"}],"c":true,"d":null,"e":-1.5e3}""")
        assertEquals("текст", v.field("a").items()[2].str("b"))
        assertEquals(true, (v.field("c") as JsonValue.Bool).value)
        assertTrue(v.field("d") is JsonValue.Null)
        assertEquals("-1.5e3", (v.field("e") as JsonValue.Num).raw)
    }

    @Test
    @DisplayName("глубокая вложенность не роняет стек, а даёт ошибку")
    fun `защита от переполнения стека`() {
        val bomb = "[".repeat(5000) + "]".repeat(5000)
        val ex = assertThrows(JsonException::class.java) { Json.parse(bomb) }
        assertTrue(ex.message!!.contains("глубин"), ex.message)
    }

    @Test
    fun `слишком длинный вход отвергается`() {
        val limits = JsonLimits(maxInputChars = 10)
        assertThrows(JsonException::class.java) { Json.parse("""{"aaa":"bbbb"}""", limits) }
    }

    @Test
    @DisplayName("дублирующийся ключ — ошибка, а не тихий выбор одного из значений")
    fun `дубли ключей запрещены`() {
        assertThrows(JsonException::class.java) { Json.parse("""{"id":"good","id":"evil"}""") }
    }

    @Test
    fun `сырой управляющий символ в строке запрещён`() {
        val withNewline = "{\"a\":\"line\nbreak\"}"
        val withNul = "{\"a\":\"nul\u0000here\"}"
        assertThrows(JsonException::class.java) { Json.parse(withNewline) }
        assertThrows(JsonException::class.java) { Json.parse(withNul) }
    }

    @Test
    fun `мусор после значения запрещён`() {
        assertThrows(JsonException::class.java) { Json.parse("""{"a":1} {"b":2}""") }
    }

    @Test
    fun `неполные литералы отвергаются`() {
        assertThrows(JsonException::class.java) { Json.parse("tru") }
        assertThrows(JsonException::class.java) { Json.parse("01") }
        assertThrows(JsonException::class.java) { Json.parse("""{"a":}""") }
    }

    @Test
    fun `escape-последовательности разбираются`() {
        val v = Json.parse("""{"a":"A\n\t\"\\\/"}""")
        assertEquals("A\n\t\"\\/", v.str("a"))
    }

    @Test
    fun `escape при записи не даёт вырваться из строки`() {
        val evil = "\";alert(1);//"
        val encoded = Json.escape(evil)
        val back = Json.parse("""{"x":$encoded}""")
        assertEquals(evil, back.str("x"))
    }

    @Test
    fun `экранирование управляющих символов обратимо`() {
        val evil = "a\u0000b\u001Fc\u2028d"
        val back = Json.parse("""{"x":${Json.escape(evil)}}""")
        assertEquals(evil, back.str("x"))
    }

    @Test
    fun `parseOrNull не бросает`() {
        assertNull(Json.parseOrNull("{"))
        assertNotNull(Json.parseOrNull("{}"))
    }

    @Test
    fun `аксессоры возвращают null вместо падения на неверном типе`() {
        val v = Json.parse("""{"n":5,"s":"x"}""")
        assertEquals(5, v.field("n").int())
        assertNull(v.field("s").int())
        assertNull(v.field("n").string())
        assertNull(v.field("нет").string())
    }
}
