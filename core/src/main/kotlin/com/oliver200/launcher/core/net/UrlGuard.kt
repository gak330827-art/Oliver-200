/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/net/UrlGuard.kt
 *  Назначение : белые списки хостов и проверка каждого URL перед запросом.
 *  Безопасность: САМАЯ НЕДООЦЕНЁННАЯ ДЫРА ЛАУНЧЕРОВ. Ссылки на jar-файлы,
 *               ассеты и текстуры лаунчер берёт ИЗ САМОГО ЗАГРУЖЕННОГО JSON.
 *               Кто подменил манифест — тот выбирает, откуда скачается код,
 *               который через минуту исполнится в JVM. Плюс токен Microsoft
 *               в заголовке легко утечёт на чужой хост (SSRF/эксфильтрация).
 *               Поэтому: только https, только хост из списка, никакого
 *               userinfo, никаких нестандартных портов и IP-литералов.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.net

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Именованный набор разрешённых хостов. */
data class HostPolicy(
    val name: String,
    val exactHosts: Set<String>,
) {
    operator fun plus(other: HostPolicy): HostPolicy =
        HostPolicy("$name+${other.name}", exactHosts + other.exactHosts)

    fun allows(host: String): Boolean = host.lowercase(Locale.ROOT) in exactHosts
}

sealed class UrlCheck {
    data class Ok(val uri: URI, val host: String) : UrlCheck()
    data class Rejected(val reason: String) : UrlCheck()
}

class UrlRejectedException(val reason: String) : SecurityException(reason)

object UrlGuard {

    const val MAX_URL_LENGTH = 2048

    /** Метаданные версий: манифест и per-version JSON. */
    val MOJANG_META = HostPolicy(
        "mojang-meta",
        setOf(
            "launchermeta.mojang.com",
            "piston-meta.mojang.com",
        ),
    )

    /** Бинарники: клиентский jar, библиотеки, ассеты. */
    val MOJANG_CONTENT = HostPolicy(
        "mojang-content",
        setOf(
            "piston-data.mojang.com",
            "launcher.mojang.com",
            "libraries.minecraft.net",
            "resources.download.minecraft.net",
        ),
    )

    /** Публичные профили: ник -> UUID -> текстуры. */
    val PROFILE_API = HostPolicy(
        "profile-api",
        setOf(
            "api.mojang.com",
            "sessionserver.mojang.com",
            "api.minecraftservices.com",
        ),
    )

    /** CDN скинов и плащей. */
    val TEXTURES = HostPolicy(
        "textures",
        setOf(
            "textures.minecraft.net",
        ),
    )

    /** Официальная авторизация Microsoft / Xbox Live. Ничего лишнего. */
    val MICROSOFT_AUTH = HostPolicy(
        "microsoft-auth",
        setOf(
            "login.microsoftonline.com",
            "user.auth.xboxlive.com",
            "xsts.auth.xboxlive.com",
            "api.minecraftservices.com",
        ),
    )

    /** Всё, что лаунчер вправе скачивать при установке версии. */
    val GAME_DOWNLOADS = MOJANG_META + MOJANG_CONTENT

    /** Полный список для диагностики и pinning-конфига. */
    val ALL: HostPolicy =
        GAME_DOWNLOADS + PROFILE_API + TEXTURES + MICROSOFT_AUTH

    fun check(raw: String?, policy: HostPolicy): UrlCheck {
        if (raw.isNullOrEmpty()) return UrlCheck.Rejected("Пустой URL")
        if (raw.length > MAX_URL_LENGTH) return UrlCheck.Rejected("URL длиннее $MAX_URL_LENGTH символов")
        for (ch in raw) {
            if (ch.code <= 0x20 || ch.code == 0x7F) {
                return UrlCheck.Rejected("Пробел или управляющий символ в URL")
            }
            if (ch.code > 0x7E) {
                // Не-ASCII в URL — путь к homograph-атаке на имя хоста.
                return UrlCheck.Rejected("Не-ASCII символ в URL")
            }
        }
        if (raw.contains('\\')) return UrlCheck.Rejected("Обратный слэш в URL")

        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return UrlCheck.Rejected("Некорректный синтаксис URL")
        }
        if (!uri.isAbsolute) return UrlCheck.Rejected("Относительный URL")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") return UrlCheck.Rejected("Схема '$scheme' запрещена, нужен https")
        if (uri.rawUserInfo != null) return UrlCheck.Rejected("userinfo в URL запрещён")
        if (uri.isOpaque) return UrlCheck.Rejected("Opaque-URL запрещён")

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return UrlCheck.Rejected("Не удалось разобрать имя хоста")
        if (host.isEmpty()) return UrlCheck.Rejected("Пустое имя хоста")
        if (isIpLiteral(host)) return UrlCheck.Rejected("IP-адрес вместо имени хоста")

        val port = uri.port
        if (port != -1 && port != 443) return UrlCheck.Rejected("Нестандартный порт $port")

        if (!policy.allows(host)) {
            return UrlCheck.Rejected("Хост '$host' не входит в список '${policy.name}'")
        }
        return UrlCheck.Ok(uri, host)
    }

    fun isAllowed(raw: String?, policy: HostPolicy): Boolean = check(raw, policy) is UrlCheck.Ok

    @Throws(UrlRejectedException::class)
    fun require(raw: String?, policy: HostPolicy): URI = when (val r = check(raw, policy)) {
        is UrlCheck.Ok -> r.uri
        is UrlCheck.Rejected -> throw UrlRejectedException(r.reason)
    }

    /**
     * Mojang исторически отдаёт ссылки на текстуры по http. Молча ходить по
     * http нельзя, но и терять скины не хочется: поднимаем схему до https —
     * тот же хост её поддерживает — и дальше идём обычной проверкой.
     */
    fun upgradeTexturesUrl(raw: String?): String? {
        if (raw == null) return null
        return if (raw.startsWith("http://")) "https://" + raw.substring("http://".length) else raw
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[")) return true // IPv6
        if (host.isEmpty()) return false
        // IPv4 состоит только из цифр и точек; настоящих доменов такого вида нет.
        return host.all { it in '0'..'9' || it == '.' }
    }
}
