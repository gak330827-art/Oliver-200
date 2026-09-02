/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/auth/OfflineProfile.kt
 *  Назначение : офлайн-профиль — игрок задаётся одним ником, без сети.
 *
 *  Что это за режим. Офлайн — штатная возможность самой игры, а не обход
 *  чего-либо: сервер с online-mode=false, игра по локальной сети,
 *  одиночный мир, свой сервер, а также ситуация, когда серверы Mojang
 *  лежат (это случается). В таком режиме клиент запускается с
 *  --accessToken 0 и --userType legacy, а UUID игрока вычисляется
 *  локально по нику.
 *
 *  Алгоритм UUID — ровно тот, что применяет ванильный сервер:
 *      UUID версии 3 (MD5) от строки "OfflinePlayer:<ник>", без
 *      пространства имён. Байт 6 получает версию 3, байт 8 — вариант IETF.
 *  Совпадение критично: если посчитать иначе, сервер выдаст игроку другой
 *  UUID, и он потеряет свой инвентарь и права на собственном мире.
 *
 *  Ник проверяется той же маской, что и сетевой: латиница, цифры и «_»,
 *  3–16 символов. Это не только совместимость с серверами, но и защита —
 *  ник уходит в аргументы запуска и в имена файлов.
 *
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (ник и офлайн-UUID секретами не являются:
 *               UUID детерминированно выводится из ника кем угодно)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.auth

import com.oliver200.launcher.core.skin.MinecraftName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Игрок без сетевого аккаунта. */
data class OfflinePlayer(
    val name: String,
    /** UUID в каноничном виде с дефисами. */
    val uuid: String,
) {
    /** Тот же UUID без дефисов — в таком виде его ждут некоторые API. */
    val uuidCompact: String get() = uuid.replace("-", "")
}

object OfflineProfile {

    /** Префикс, который добавляет сам сервер перед хешированием. */
    const val PREFIX = "OfflinePlayer:"

    /** Значение --accessToken для запуска без авторизации. */
    const val ACCESS_TOKEN = "0"

    /** Значение --userType для запуска без авторизации. */
    const val USER_TYPE = "legacy"

    /**
     * Собирает профиль из введённого ника.
     * @return null, если ник не проходит проверку — подставлять «похожий»
     *         ник нельзя: на сервере это будет уже другой игрок.
     */
    fun of(rawName: String?): OfflinePlayer? {
        val name = MinecraftName.normalize(rawName) ?: return null
        return OfflinePlayer(name, uuidFor(name))
    }

    /**
     * Офлайн-UUID по нику. Реализовано вручную, а не через
     * UUID.nameUUIDFromBytes, чтобы алгоритм был виден целиком и его можно
     * было сверить с сервером построчно. Результат идентичен — это
     * проверяется тестом.
     */
    fun uuidFor(name: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest((PREFIX + name).toByteArray(StandardCharsets.UTF_8))

        // Версия 3 (name-based, MD5) — старшие 4 бита седьмого байта.
        digest[6] = ((digest[6].toInt() and 0x0F) or 0x30).toByte()
        // Вариант IETF (RFC 4122) — старшие 2 бита девятого байта.
        digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()

        val hex = StringBuilder(36)
        for (i in digest.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) hex.append('-')
            val v = digest[i].toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    /** Проверка ника до того, как пользователь нажал «сохранить». */
    fun isValidName(name: String?): Boolean = MinecraftName.isValid(name?.trim())

    private val HEX = "0123456789abcdef".toCharArray()
}
