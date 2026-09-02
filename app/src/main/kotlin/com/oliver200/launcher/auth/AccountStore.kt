/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : auth/AccountStore.kt
 *  Назначение : хранение текущего аккаунта поверх Vault.
 *  Безопасность: сюда попадают refresh_token (живёт месяцами!) и
 *               access_token (сутки). Оба уходят в Vault, то есть в
 *               release — в AES-256-GCM с ключом из Keystore. В памяти
 *               токен живёт ровно столько, сколько нужно на запрос.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.auth

import com.oliver200.launcher.security.Vault

data class Account(
    val uuid: String,
    val name: String,
    val xuid: String,
    val accessToken: String,
    val expiresAtEpochSec: Long,
) {
    /** Минута запаса: не хочется получить 401 ровно в момент запуска. */
    fun isExpired(nowEpochSec: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSec >= expiresAtEpochSec - 60
}

class AccountStore(private val vault: Vault) {

    fun save(session: MinecraftSession, refreshToken: String?) {
        vault.putString(Vault.KEY_PROFILE_UUID, session.uuid)
        vault.putString(Vault.KEY_PROFILE_NAME, session.name)
        vault.putString(Vault.KEY_XUID, session.xuid)
        vault.putString(Vault.KEY_MC_ACCESS_TOKEN, session.accessToken)
        vault.putString(Vault.KEY_MC_TOKEN_EXPIRY, session.expiresAtEpochSec.toString())
        if (refreshToken != null) {
            vault.putString(Vault.KEY_MS_REFRESH_TOKEN, refreshToken)
        }
    }

    fun load(): Account? {
        val uuid = vault.getString(Vault.KEY_PROFILE_UUID) ?: return null
        val name = vault.getString(Vault.KEY_PROFILE_NAME) ?: return null
        val token = vault.getString(Vault.KEY_MC_ACCESS_TOKEN) ?: return null
        val expiry = vault.getString(Vault.KEY_MC_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L
        return Account(
            uuid = uuid,
            name = name,
            xuid = vault.getString(Vault.KEY_XUID).orEmpty(),
            accessToken = token,
            expiresAtEpochSec = expiry,
        )
    }

    fun refreshToken(): String? = vault.getString(Vault.KEY_MS_REFRESH_TOKEN)

    /** Выход: стираем всё. Полумеры вроде «оставим профиль» здесь неуместны. */
    fun clear() {
        vault.clear()
    }

    val storageDescription: String get() = vault.variant
    val storageEncrypted: Boolean get() = vault.encrypted
}
