/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/crypto/Redact.kt
 *  Назначение : маскирование секретов перед выводом в лог.
 *  Безопасность: logcat на Android читают приложения с READ_LOGS, а на
 *               рутованном телефоне — вообще все. Access-token Minecraft
 *               живёт 24 часа: попал в лог — аккаунт угнан. Ни одна строка
 *               с токеном не должна уходить в лог без этой функции.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.crypto

object Redact {

    private const val MASK = "***"

    /** Оставляем только «хвост» — достаточно, чтобы отличить два токена в отчёте. */
    fun secret(value: String?): String {
        if (value.isNullOrEmpty()) return "<пусто>"
        if (value.length <= 8) return MASK
        return MASK + value.takeLast(4) + " (len=" + value.length + ")"
    }

    fun uuid(value: String?): String {
        if (value.isNullOrEmpty()) return "<пусто>"
        return if (value.length <= 8) MASK else value.take(8) + "…"
    }

    fun email(value: String?): String {
        if (value.isNullOrEmpty()) return "<пусто>"
        val at = value.indexOf('@')
        if (at <= 0) return MASK
        return value.first() + MASK + value.substring(at)
    }

    /** Вырезает Bearer-токены и значения token-полей из произвольного текста. */
    fun scrub(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var out = BEARER.replace(text) { m -> m.groupValues[1] + MASK }
        out = TOKEN_FIELD.replace(out) { m -> m.groupValues[1] + MASK }
        return out
    }

    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-+/=]{8,}")
    private val TOKEN_FIELD =
        Regex("(?i)(\"?(?:access_?token|refresh_?token|id_?token|client_?secret|password)\"?\\s*[:=]\\s*\"?)[^\"',\\s}]{4,}")
}
