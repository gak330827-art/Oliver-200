/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/skin/SkinProfile.kt
 *  Назначение : «скин по нику» — ник → UUID → профиль → ссылка на текстуру.
 *  Безопасность: ник вводит ПОЛЬЗОВАТЕЛЬ, и он попадает прямо в путь URL.
 *               Строка "../../admin" или "%0d%0aHost:" там означает подмену
 *               запрашиваемого ресурса и инъекцию в заголовки. Поэтому ник
 *               проверяется по строгой маске ДО построения адреса, а не
 *               экранируется после. Ссылка на текстуру приходит из ответа
 *               сервера — её хост тоже проверяется по белому списку.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (публичные данные)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.skin

import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.json.JsonLimits
import com.oliver200.launcher.core.json.JsonValue
import com.oliver200.launcher.core.json.asObj
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.str
import com.oliver200.launcher.core.net.UrlGuard
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

enum class SkinModel(val jsonName: String, val ruLabel: String) {
    CLASSIC("classic", "Стандартный (4 px)"),
    SLIM("slim", "Тонкие руки (3 px)"),
    ;

    companion object {
        fun from(raw: String?): SkinModel =
            if (raw?.lowercase(Locale.ROOT) == "slim") SLIM else CLASSIC
    }
}

data class SkinTexture(val url: String, val model: SkinModel)

data class ProfileTextures(
    val profileId: String?,
    val profileName: String?,
    val skin: SkinTexture?,
    val cape: SkinTexture?,
)

data class NameLookup(val uuid: String, val name: String)

/** Ник Minecraft: 3–16 символов, латиница/цифры/подчёркивание. Правило самой Mojang. */
object MinecraftName {

    private val PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

    fun isValid(name: String?): Boolean = name != null && PATTERN.matches(name)

    /** Обрезает пробелы по краям и проверяет. null — ник не годится, подставлять нечего. */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        return if (isValid(trimmed)) trimmed else null
    }
}

object MinecraftUuid {

    private val UNDASHED = Regex("^[0-9a-fA-F]{32}$")
    private val DASHED = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValid(value: String?): Boolean =
        value != null && (UNDASHED.matches(value) || DASHED.matches(value))

    fun strip(value: String?): String? {
        if (value == null) return null
        val v = value.replace("-", "")
        return if (UNDASHED.matches(v)) v.lowercase(Locale.ROOT) else null
    }

    fun dashed(value: String?): String? {
        val v = strip(value) ?: return null
        return v.substring(0, 8) + "-" + v.substring(8, 12) + "-" + v.substring(12, 16) +
            "-" + v.substring(16, 20) + "-" + v.substring(20, 32)
    }
}

/** Построение адресов API. Ник/UUID сюда попадают только после валидации. */
object SkinEndpoints {

    fun nameToUuid(name: String): String {
        require(MinecraftName.isValid(name)) { "Недопустимый ник" }
        return "https://api.mojang.com/users/profiles/minecraft/$name"
    }

    /** Резервный современный эндпоинт: используется, если основной ответил 429/5xx. */
    fun nameToUuidFallback(name: String): String {
        require(MinecraftName.isValid(name)) { "Недопустимый ник" }
        return "https://api.minecraftservices.com/minecraft/profile/lookup/name/$name"
    }

    fun profile(uuid: String): String {
        val id = MinecraftUuid.strip(uuid) ?: throw IllegalArgumentException("Недопустимый UUID")
        return "https://sessionserver.mojang.com/session/minecraft/profile/$id"
    }

    /** Аватар без обращения к сессионному серверу — полезен для списка аккаунтов. */
    fun isTextureUrlAllowed(url: String?): Boolean =
        UrlGuard.isAllowed(UrlGuard.upgradeTexturesUrl(url), UrlGuard.TEXTURES)
}

object SkinProfileParser {

    private val LIMITS = JsonLimits.SMALL

    /** Максимум для base64-блока текстур: реальный — сотни байт, берём с запасом. */
    private const val MAX_TEXTURES_BASE64 = 32 * 1024

    /** Ответ api.mojang.com/users/profiles/minecraft/<ник>. */
    fun parseNameLookup(text: String): NameLookup? {
        val root = Json.parseOrNull(text, LIMITS) ?: return null
        val id = MinecraftUuid.strip(root.str("id")) ?: return null
        val name = root.str("name") ?: return null
        if (!MinecraftName.isValid(name)) return null
        return NameLookup(id, name)
    }

    /** Ответ sessionserver: ищем свойство "textures" и разбираем его base64. */
    fun parseProfile(text: String): ProfileTextures? {
        val root = Json.parseOrNull(text, LIMITS) ?: return null
        val encoded = root.field("properties").items()
            .firstOrNull { it.asObj().str("name") == "textures" }
            ?.str("value")
            ?: return null
        return decodeTextures(encoded)
    }

    fun decodeTextures(base64: String): ProfileTextures? {
        if (base64.length > MAX_TEXTURES_BASE64) return null
        val decoded = try {
            String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val root = Json.parseOrNull(decoded, LIMITS) ?: return null
        val textures = root.field("textures")
        return ProfileTextures(
            profileId = MinecraftUuid.strip(root.str("profileId")),
            profileName = root.str("profileName"),
            skin = readTexture(textures.field("SKIN")),
            cape = readTexture(textures.field("CAPE")),
        )
    }

    private fun readTexture(node: JsonValue?): SkinTexture? {
        if (node == null) return null
        val url = UrlGuard.upgradeTexturesUrl(node.str("url")) ?: return null
        // Ссылка пришла от сервера, но доверять ей на слово нельзя:
        // сессионный сервер может быть подменён на устройстве через hosts/VPN.
        if (!UrlGuard.isAllowed(url, UrlGuard.TEXTURES)) return null
        val model = SkinModel.from(node.field("metadata").str("model"))
        return SkinTexture(url, model)
    }
}
