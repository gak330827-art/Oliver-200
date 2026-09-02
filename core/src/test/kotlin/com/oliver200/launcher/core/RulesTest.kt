/*
 * Oliver-200 · тесты правил применимости и защиты от ReDoS.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.mojang.LaunchEnvironment
import com.oliver200.launcher.core.mojang.Rule
import com.oliver200.launcher.core.mojang.Rules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration

class RulesTest {

    private val android = LaunchEnvironment.android64("14")

    @Test
    fun `пустой список правил разрешает`() {
        assertTrue(Rules.isAllowed(emptyList(), android))
    }

    @Test
    fun `правило для другой ОС не срабатывает`() {
        val onlyOsx = listOf(Rule(allow = true, osName = "osx"))
        assertFalse(Rules.isAllowed(onlyOsx, android))
    }

    @Test
    fun `allow для linux пропускает Android`() {
        assertTrue(Rules.isAllowed(listOf(Rule(allow = true, osName = "linux")), android))
    }

    @Test
    @DisplayName("Последнее совпавшее правило побеждает")
    fun `порядок правил важен`() {
        val rules = listOf(
            Rule(allow = true),
            Rule(allow = false, osName = "linux"),
        )
        assertFalse(Rules.isAllowed(rules, android))
        assertTrue(Rules.isAllowed(rules.reversed(), android))
    }

    @Test
    fun `архитектура сравнивается по нормализованному имени`() {
        assertTrue(Rules.isAllowed(listOf(Rule(allow = true, osArch = "aarch64")), android))
        assertFalse(Rules.isAllowed(listOf(Rule(allow = true, osArch = "x86_64")), android))
        val arm32 = LaunchEnvironment.android32()
        assertTrue(Rules.isAllowed(listOf(Rule(allow = true, osArch = "arm")), arm32))
    }

    @Test
    fun `фичи учитываются`() {
        val demoOnly = listOf(Rule(allow = true, features = mapOf("is_demo_user" to true)))
        assertFalse(Rules.isAllowed(demoOnly, android))
        val withDemo = android.copy(features = mapOf("is_demo_user" to true))
        assertTrue(Rules.isAllowed(demoOnly, withDemo))
    }

    @Test
    fun `разбор правил из JSON`() {
        val json = Json.parse(
            """
            [
              {"action":"allow"},
              {"action":"disallow","os":{"name":"osx","version":"^10\\.","arch":"x86_64"}},
              {"action":"allow","features":{"has_custom_resolution":true}},
              {"action":"неизвестно"}
            ]
            """.trimIndent(),
        )
        val rules = Rules.parse(json)
        assertEquals(3, rules.size) // неизвестное действие отброшено
        assertEquals("osx", rules[1].osName)
        assertEquals(mapOf("has_custom_resolution" to true), rules[2].features)
    }

    @Test
    @DisplayName("ReDoS: злая регулярка из JSON не вешает поток")
    fun `катастрофический бэктрекинг невозможен`() {
        val evil = "^(a+)+$"
        assertFalse(Rules.isSafePattern(evil))
        val input = "a".repeat(40) + "!"
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertFalse(Rules.safeRegexMatches(evil, input))
            assertFalse(Rules.safeRegexMatches("(x+x+)+y", input))
            assertFalse(Rules.safeRegexMatches("(?=.*)(.*)*b", input))
        }
    }

    @Test
    fun `безопасные шаблоны версий работают`() {
        assertTrue(Rules.isSafePattern("^10\\."))
        assertTrue(Rules.safeRegexMatches("^10\\.", "10.15.7"))
        assertFalse(Rules.safeRegexMatches("^10\\.", "14"))
        assertFalse(Rules.isSafePattern("a".repeat(200)))
        assertFalse(Rules.isSafePattern(""))
        assertFalse(Rules.isSafePattern("(unbalanced"))
    }
}
