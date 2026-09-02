/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/mojang/Rules.kt
 *  Назначение : вычисление правил "rules" из version.json — какие библиотеки
 *               и аргументы применимы к текущей платформе.
 *  Безопасность: поле os.version — это РЕГУЛЯРНОЕ ВЫРАЖЕНИЕ из внешнего JSON.
 *               Подставив "(a+)+$", атакующий получает катастрофический
 *               бэктрекинг и вешает поток на минуты (ReDoS). Поэтому шаблон
 *               проходит через фильтр безопасного подмножества, а не
 *               компилируется как есть.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.mojang

import com.oliver200.launcher.core.json.JsonValue
import com.oliver200.launcher.core.json.asObj
import com.oliver200.launcher.core.json.bool
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.str
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Платформа, на которой считаем правила.
 *
 * Minecraft не знает про Android: и сама игра, и LWJGL опознают систему как
 * "linux". Держим это в одном месте, чтобы не размазывать притворство по коду.
 */
data class LaunchEnvironment(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val features: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        /** Android ARM64 — то, на чём работает подавляющее большинство телефонов. */
        fun android64(osVersion: String = "0.0"): LaunchEnvironment =
            LaunchEnvironment(osName = "linux", osVersion = osVersion, osArch = "arm64")

        fun android32(osVersion: String = "0.0"): LaunchEnvironment =
            LaunchEnvironment(osName = "linux", osVersion = osVersion, osArch = "arm32")
    }
}

data class Rule(
    val allow: Boolean,
    val osName: String? = null,
    val osVersion: String? = null,
    val osArch: String? = null,
    val features: Map<String, Boolean> = emptyMap(),
) {
    fun matches(env: LaunchEnvironment): Boolean {
        if (osName != null && !osName.equals(env.osName, ignoreCase = true)) return false
        if (osArch != null && !archMatches(osArch, env.osArch)) return false
        if (osVersion != null && !Rules.safeRegexMatches(osVersion, env.osVersion)) return false
        for ((key, expected) in features) {
            val actual = env.features[key] ?: false
            if (actual != expected) return false
        }
        return true
    }

    private fun archMatches(ruleArch: String, envArch: String): Boolean {
        val a = normalizeArch(ruleArch)
        val b = normalizeArch(envArch)
        return a == b
    }

    private fun normalizeArch(a: String): String = when (a.lowercase(Locale.ROOT)) {
        "x86", "i386", "i686", "x86_32" -> "x86"
        "x86_64", "amd64", "x64" -> "x86_64"
        "arm64", "aarch64" -> "arm64"
        "arm32", "arm", "armv7", "armhf", "aarch32" -> "arm32"
        else -> a.lowercase(Locale.ROOT)
    }
}

object Rules {

    const val MAX_PATTERN_LENGTH = 128

    fun parse(node: JsonValue?): List<Rule> = node.items().mapNotNull { parseOne(it) }

    private fun parseOne(node: JsonValue): Rule? {
        val obj = node.asObj() ?: return null
        val action = obj.str("action")?.lowercase(Locale.ROOT) ?: return null
        val allow = when (action) {
            "allow" -> true
            "disallow" -> false
            else -> return null // неизвестное действие игнорируем, а не «разрешаем»
        }
        val os = obj.field("os").asObj()
        val features = LinkedHashMap<String, Boolean>()
        (obj.field("features").asObj())?.fields?.forEach { (k, v) ->
            v.bool()?.let { features[k] = it }
        }
        return Rule(
            allow = allow,
            osName = os.str("name"),
            osVersion = os.str("version"),
            osArch = os.str("arch"),
            features = features,
        )
    }

    /**
     * Каноническая семантика Mojang: пустой список правил — разрешено;
     * иначе результат задаёт ПОСЛЕДНЕЕ совпавшее правило, а если не совпало
     * ни одно — запрещено.
     */
    fun isAllowed(rules: List<Rule>, env: LaunchEnvironment): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (rule.matches(env)) allowed = rule.allow
        }
        return allowed
    }

    /**
     * Сопоставление с регуляркой из недоверенного JSON.
     *
     * Пропускаем только заведомо безопасное подмножество: без вложенных
     * квантификаторов, без обратных ссылок, без look-around, с ограничением
     * длины. Всё остальное считаем несовпадением — потерять правило про
     * версию ОС не страшно, повесить UI на минуту — страшно.
     */
    fun safeRegexMatches(pattern: String, input: String): Boolean {
        if (!isSafePattern(pattern)) return false
        return try {
            Pattern.compile(pattern).matcher(input).find()
        } catch (_: PatternSyntaxException) {
            false
        }
    }

    fun isSafePattern(pattern: String): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_LENGTH) return false
        if (pattern.contains("(?")) return false // look-around и прочая магия
        if (pattern.contains("\\1") || pattern.contains("\\2")) return false // обратные ссылки
        // Вложенный квантификатор — ")" сразу перед "*", "+", "{" — главный
        // источник экспоненциального бэктрекинга.
        var depth = 0
        for (i in pattern.indices) {
            val c = pattern[i]
            if (i > 0 && pattern[i - 1] == '\\') continue
            when (c) {
                '(' -> depth++
                ')' -> {
                    depth--
                    val next = pattern.getOrNull(i + 1)
                    if (next == '*' || next == '+' || next == '{') return false
                }
            }
            if (depth < 0) return false
        }
        return depth == 0
    }
}
