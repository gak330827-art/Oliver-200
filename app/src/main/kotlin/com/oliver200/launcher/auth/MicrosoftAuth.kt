/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : auth/MicrosoftAuth.kt
 *  Назначение : официальный вход Microsoft → Xbox Live → XSTS → Minecraft.
 *
 *  Почему Device Code Flow: у публичного мобильного клиента НЕТ и не может
 *  быть client_secret — любой секрет в APK извлекается за минуту. Device
 *  Code не требует секрета вовсе: пользователь подтверждает вход на
 *  microsoft.com/link, а приложение получает токен по опросу. Пароль
 *  Microsoft при этом никогда не проходит через наше приложение.
 *
 *  Чего здесь принципиально нет: «пиратского» входа по одному нику.
 *  Запуск игры возможен только с настоящей лицензией — это проверяется
 *  запросом entitlements. Локальный профиль в приложении есть, но он
 *  умеет только смотреть скины и управлять паками.
 *
 *  Безопасность: каждый адрес проверяется белым списком MICROSOFT_AUTH,
 *  редиректы обрабатываются вручную (см. SecureHttp), тела ответов
 *  ограничены по размеру, а токены нигде не попадают в лог.
 *
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (шифрование — в SecureVault, куда токены
 *               кладутся сразу после получения)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.auth

import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.json.JsonLimits
import com.oliver200.launcher.core.json.asObj
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.long
import com.oliver200.launcher.core.json.str
import com.oliver200.launcher.core.net.UrlGuard
import com.oliver200.launcher.core.skin.MinecraftUuid
import com.oliver200.launcher.net.SecureHttp
import kotlinx.coroutines.delay
import java.io.IOException

class AuthException(message: String, val userActionable: Boolean = true) : IOException(message)

data class DeviceCodePrompt(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
)

data class MicrosoftTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSec: Int,
)

data class MinecraftSession(
    val accessToken: String,
    val expiresAtEpochSec: Long,
    val uuid: String,
    val name: String,
    val xuid: String,
)

object MicrosoftAuth {

    private const val TENANT = "consumers"
    private const val DEVICE_CODE_URL =
        "https://login.microsoftonline.com/$TENANT/oauth2/v2.0/devicecode"
    private const val TOKEN_URL =
        "https://login.microsoftonline.com/$TENANT/oauth2/v2.0/token"
    private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
    private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
    private const val MC_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
    private const val MC_ENTITLEMENTS_URL = "https://api.minecraftservices.com/entitlements/mcstore"
    private const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

    private const val SCOPE = "XboxLive.signin offline_access"
    private const val MAX_BODY = 256L * 1024
    private val LIMITS = JsonLimits.SMALL

    private val POLICY = UrlGuard.MICROSOFT_AUTH

    /* ─────────────────────── Шаг 1: код устройства ─────────────────────── */

    suspend fun startDeviceCode(clientId: String): DeviceCodePrompt {
        requireClientId(clientId)
        val resp = SecureHttp.postForm(
            DEVICE_CODE_URL,
            POLICY,
            mapOf("client_id" to clientId, "scope" to SCOPE),
            maxBody = MAX_BODY,
        )
        val json = Json.parseOrNull(resp.text(), LIMITS)
            ?: throw AuthException("Сервер Microsoft вернул нечитаемый ответ")
        if (resp.code !in 200..299) {
            throw AuthException(describeOAuthError(json, resp.code))
        }
        val deviceCode = json.str("device_code")
            ?: throw AuthException("В ответе нет device_code")
        val userCode = json.str("user_code")
            ?: throw AuthException("В ответе нет user_code")
        val uri = json.str("verification_uri") ?: json.str("verification_url")
            ?: "https://microsoft.com/link"
        return DeviceCodePrompt(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSec = (json.field("expires_in").long() ?: 900L).toInt(),
            intervalSec = (json.field("interval").long() ?: 5L).toInt().coerceIn(1, 60),
        )
    }

    /* ─────────────────────── Шаг 2: опрос токена ─────────────────────── */

    /**
     * Опрашивает Microsoft, пока пользователь не подтвердит вход.
     * Прерывается отменой корутины — экран закрыли, опрос прекратился.
     */
    suspend fun awaitToken(
        clientId: String,
        prompt: DeviceCodePrompt,
        onTick: (secondsLeft: Int) -> Unit = {},
    ): MicrosoftTokens {
        requireClientId(clientId)
        var interval = prompt.intervalSec
        var remaining = prompt.expiresInSec

        while (remaining > 0) {
            delay(interval * 1000L)
            remaining -= interval
            onTick(remaining.coerceAtLeast(0))

            val resp = SecureHttp.postForm(
                TOKEN_URL,
                POLICY,
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to clientId,
                    "device_code" to prompt.deviceCode,
                ),
                maxBody = MAX_BODY,
            )
            val json = Json.parseOrNull(resp.text(), LIMITS)
                ?: throw AuthException("Сервер Microsoft вернул нечитаемый ответ")

            if (resp.code in 200..299) {
                return MicrosoftTokens(
                    accessToken = json.str("access_token")
                        ?: throw AuthException("В ответе нет access_token"),
                    refreshToken = json.str("refresh_token"),
                    expiresInSec = (json.field("expires_in").long() ?: 3600L).toInt(),
                )
            }

            when (json.str("error")) {
                "authorization_pending" -> Unit // норма: пользователь ещё вводит код
                "slow_down" -> interval += 5
                "expired_token" -> throw AuthException("Срок действия кода истёк, начните заново")
                "access_denied" -> throw AuthException("Вход отклонён пользователем")
                else -> throw AuthException(describeOAuthError(json, resp.code))
            }
        }
        throw AuthException("Срок действия кода истёк, начните заново")
    }

    suspend fun refresh(clientId: String, refreshToken: String): MicrosoftTokens {
        requireClientId(clientId)
        val resp = SecureHttp.postForm(
            TOKEN_URL,
            POLICY,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "scope" to SCOPE,
            ),
            maxBody = MAX_BODY,
        )
        val json = Json.parseOrNull(resp.text(), LIMITS)
            ?: throw AuthException("Сервер Microsoft вернул нечитаемый ответ")
        if (resp.code !in 200..299) {
            throw AuthException(describeOAuthError(json, resp.code))
        }
        return MicrosoftTokens(
            accessToken = json.str("access_token")
                ?: throw AuthException("В ответе нет access_token"),
            refreshToken = json.str("refresh_token") ?: refreshToken,
            expiresInSec = (json.field("expires_in").long() ?: 3600L).toInt(),
        )
    }

    /* ──────────── Шаги 3–6: Xbox Live → XSTS → Minecraft → профиль ──────────── */

    suspend fun toMinecraftSession(microsoftAccessToken: String): MinecraftSession {
        val (xblToken, _) = authenticateXbox(microsoftAccessToken)
        val (xstsToken, userHash, xuid) = authorizeXsts(xblToken)
        val mcToken = loginWithXbox(userHash, xstsToken)

        if (!hasEntitlement(mcToken)) {
            throw AuthException(
                "На этом аккаунте нет лицензии Minecraft: Java Edition",
                userActionable = true,
            )
        }
        val profile = fetchProfile(mcToken)
        return MinecraftSession(
            accessToken = mcToken.token,
            expiresAtEpochSec = mcToken.expiresAtEpochSec,
            uuid = profile.first,
            name = profile.second,
            xuid = xuid,
        )
    }

    private data class McToken(val token: String, val expiresAtEpochSec: Long)

    private suspend fun authenticateXbox(msAccessToken: String): Pair<String, String> {
        val body = """
            {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com",
             "RpsTicket":${Json.escape("d=$msAccessToken")}},
             "RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
        """.trimIndent()
        val resp = SecureHttp.postJson(
            XBL_URL, POLICY, body,
            headers = mapOf("Accept" to "application/json"),
            maxBody = MAX_BODY,
        )
        if (resp.code !in 200..299) {
            throw AuthException("Xbox Live отклонил вход (HTTP ${resp.code})")
        }
        val json = Json.parseOrNull(resp.text(), LIMITS)
            ?: throw AuthException("Xbox Live вернул нечитаемый ответ")
        val token = json.str("Token") ?: throw AuthException("Xbox Live не выдал токен")
        val hash = userHashOf(json) ?: throw AuthException("Xbox Live не вернул идентификатор пользователя")
        return token to hash
    }

    private suspend fun authorizeXsts(xblToken: String): Triple<String, String, String> {
        val body = """
            {"Properties":{"SandboxId":"RETAIL","UserTokens":[${Json.escape(xblToken)}]},
             "RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}
        """.trimIndent()
        val resp = SecureHttp.postJson(
            XSTS_URL, POLICY, body,
            headers = mapOf("Accept" to "application/json"),
            maxBody = MAX_BODY,
        )
        val json = Json.parseOrNull(resp.text(), LIMITS)

        if (resp.code == 401) {
            // XErr объясняет, почему именно отказ, — иначе пользователь
            // видит бессмысленное «401» и не понимает, что делать.
            val xerr = json.field("XErr").long()
            throw AuthException(
                when (xerr) {
                    2148916233L -> "К аккаунту Microsoft не привязан профиль Xbox. Создайте его на xbox.com и повторите вход."
                    2148916235L -> "Xbox Live недоступен в стране этого аккаунта"
                    2148916236L, 2148916237L -> "Для аккаунта требуется подтверждение личности на xbox.com"
                    2148916238L -> "Детский аккаунт: добавьте его в семейную группу взрослого"
                    else -> "XSTS отклонил вход (код ${xerr ?: "неизвестен"})"
                },
            )
        }
        if (resp.code !in 200..299 || json == null) {
            throw AuthException("XSTS отклонил вход (HTTP ${resp.code})")
        }
        val token = json.str("Token") ?: throw AuthException("XSTS не выдал токен")
        val hash = userHashOf(json) ?: throw AuthException("XSTS не вернул идентификатор пользователя")
        val xuid = json.field("DisplayClaims").field("xui").items()
            .firstOrNull().asObj().str("xid").orEmpty()
        return Triple(token, hash, xuid)
    }

    private suspend fun loginWithXbox(userHash: String, xstsToken: String): McToken {
        val identity = "XBL3.0 x=$userHash;$xstsToken"
        val body = """{"identityToken":${Json.escape(identity)}}"""
        val resp = SecureHttp.postJson(
            MC_LOGIN_URL, POLICY, body,
            headers = mapOf("Accept" to "application/json"),
            maxBody = MAX_BODY,
        )
        if (resp.code !in 200..299) {
            throw AuthException("Minecraft-сервис отклонил вход (HTTP ${resp.code})")
        }
        val json = Json.parseOrNull(resp.text(), LIMITS)
            ?: throw AuthException("Minecraft-сервис вернул нечитаемый ответ")
        val token = json.str("access_token")
            ?: throw AuthException("Minecraft-сервис не выдал токен")
        val ttl = json.field("expires_in").long() ?: 86_400L
        return McToken(token, System.currentTimeMillis() / 1000 + ttl)
    }

    private suspend fun hasEntitlement(token: McToken): Boolean {
        val resp = SecureHttp.get(
            MC_ENTITLEMENTS_URL, POLICY,
            headers = mapOf("Authorization" to "Bearer ${token.token}", "Accept" to "application/json"),
            maxBody = MAX_BODY,
        )
        if (resp.code !in 200..299) return false
        val json = Json.parseOrNull(resp.text(), LIMITS) ?: return false
        return json.field("items").items().any {
            val name = it.asObj().str("name")
            name == "product_minecraft" || name == "game_minecraft"
        }
    }

    private suspend fun fetchProfile(token: McToken): Pair<String, String> {
        val resp = SecureHttp.get(
            MC_PROFILE_URL, POLICY,
            headers = mapOf("Authorization" to "Bearer ${token.token}", "Accept" to "application/json"),
            maxBody = MAX_BODY,
        )
        if (resp.code !in 200..299) {
            throw AuthException("Не удалось получить профиль Minecraft (HTTP ${resp.code})")
        }
        val json = Json.parseOrNull(resp.text(), LIMITS)
            ?: throw AuthException("Профиль пришёл в нечитаемом виде")
        val uuid = MinecraftUuid.dashed(json.str("id"))
            ?: throw AuthException("В профиле нет корректного UUID")
        val name = json.str("name") ?: throw AuthException("В профиле нет имени")
        return uuid to name
    }

    /* ─────────────────────────── Вспомогательное ─────────────────────────── */

    private fun userHashOf(json: com.oliver200.launcher.core.json.JsonValue?): String? =
        json.field("DisplayClaims").field("xui").items()
            .firstOrNull().asObj().str("uhs")

    private fun describeOAuthError(
        json: com.oliver200.launcher.core.json.JsonValue?,
        code: Int,
    ): String {
        val error = json.str("error")
        val description = json.str("error_description")?.take(200)
        return when {
            description != null -> description
            error != null -> "$error (HTTP $code)"
            else -> "Ошибка Microsoft, HTTP $code"
        }
    }

    private fun requireClientId(clientId: String) {
        if (clientId.isBlank()) {
            throw AuthException(
                "Не задан OLIVER_MS_CLIENT_ID: без него вход в аккаунт Microsoft невозможен",
            )
        }
    }
}
