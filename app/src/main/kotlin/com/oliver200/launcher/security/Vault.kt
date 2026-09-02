/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : security/Vault.kt
 *  Назначение : общий контракт хранилища секретов. Две реализации:
 *                 · SecureVault.kt — ★ С ШИФРОВАНИЕМ ★ (release)
 *                 · PlainVault.kt  — ☆ БЕЗ ШИФРОВАНИЯ ☆ (только debug)
 *               Обе подписаны и взаимозаменяемы: фабрика решает, какая
 *               включится, по типу сборки.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.security

import java.nio.charset.StandardCharsets

/**
 * Ключ записи одновременно служит контекстом (AAD) — благодаря этому
 * шифротекст, переставленный из одной ячейки в другую, не расшифруется.
 */
interface Vault {

    /** Человекочитаемое имя варианта — выводится в отладочной информации. */
    val variant: String

    /** true, если данные реально зашифрованы. */
    val encrypted: Boolean

    fun put(key: String, value: ByteArray)

    fun get(key: String): ByteArray?

    fun remove(key: String)

    /** Полная очистка: используется при выходе из аккаунта. */
    fun clear()

    fun putString(key: String, value: String) =
        put(key, value.toByteArray(StandardCharsets.UTF_8))

    fun getString(key: String): String? =
        get(key)?.let { String(it, StandardCharsets.UTF_8) }

    fun contains(key: String): Boolean = get(key) != null

    companion object {
        const val KEY_MS_REFRESH_TOKEN = "ms_refresh_token"
        const val KEY_MC_ACCESS_TOKEN = "mc_access_token"
        const val KEY_MC_TOKEN_EXPIRY = "mc_token_expiry"
        const val KEY_PROFILE_UUID = "profile_uuid"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_XUID = "profile_xuid"

        /**
         * Отметка, что на этом устройстве хотя бы раз успешно вошли
         * в аккаунт Microsoft с подтверждённой лицензией. Открывает
         * офлайн-запуск. Лежит в том же зашифрованном хранилище, что и
         * токены: в открытом файле настроек её переставил бы кто угодно.
         */
        const val KEY_LICENCE_VERIFIED = "licence_verified"

        /** Контекст для AAD: привязывает шифротекст к конкретной ячейке. */
        fun contextFor(key: String): String = "oliver200:vault:v1:$key"
    }
}

/** Ошибка хранилища, понятная пользователю. */
class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
