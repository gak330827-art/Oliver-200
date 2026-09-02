/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : security/VaultFactory.kt
 *  Назначение : выбор реализации хранилища.
 *  Правило    : release — ВСЕГДА SecureVault. Отката на открытое хранение
 *               при ошибке Keystore нет и быть не может: лучше честно
 *               сказать «вход недоступен», чем тихо положить токен
 *               открытым текстом.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.security

import android.content.Context

object VaultFactory {

    @Volatile
    private var instance: Vault? = null

    /**
     * @param debugBuild BuildConfig.DEBUG
     * @param forcePlain включить открытый вариант (работает только в debug)
     */
    @Throws(VaultException::class)
    fun get(context: Context, debugBuild: Boolean, forcePlain: Boolean = false): Vault {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val created = if (forcePlain) {
                // guardRelease внутри сам не пустит это в боевую сборку.
                PlainVault.create(context, debugBuild)
            } else {
                SecureVault.create(context)
            }
            instance = created
            return created
        }
    }

    /** Для тестов и смены аккаунта. */
    fun reset() {
        synchronized(this) { instance = null }
    }
}
