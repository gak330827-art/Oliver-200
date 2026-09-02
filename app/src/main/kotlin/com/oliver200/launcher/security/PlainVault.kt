/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : security/PlainVault.kt
 *
 *  ВАРИАНТ    : ☆ БЕЗ ШИФРОВАНИЯ ☆  (отладочный, ТОЛЬКО debug-сборка)
 *  Парный файл: security/SecureVault.kt — тот же контракт С шифрованием.
 *
 *  Зачем: когда SecureVault отвечает «контейнер повреждён», надо увидеть,
 *  что именно легло в хранилище, не подбирая ключ из Keystore. Контракт
 *  совпадает один в один, поэтому переключение — одна строка в фабрике.
 *
 *  ЧЕГО ЗДЕСЬ НЕТ: конфиденциальности. Токены лежат открытым текстом
 *  (base64 — это кодирование, а не шифрование). Целостность есть: SHA-256
 *  по контексту и данным ловит случайную порчу и перестановку записей
 *  между ячейками, но не защищает от намеренной подмены.
 *
 *  Предохранитель: конструктор бросает исключение, если сборка не debug.
 *  Файл настроек называется иначе, чем боевой, — перепутать нельзя.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.security

import android.content.Context
import android.content.SharedPreferences
import com.oliver200.launcher.core.crypto.CryptoException
import com.oliver200.launcher.core.crypto.PlainEnvelope
import java.util.Base64

class PlainVault private constructor(private val prefs: SharedPreferences) : Vault {

    override val variant: String = "PlainVault (БЕЗ ШИФРОВАНИЯ, только отладка)"
    override val encrypted: Boolean = false

    override fun put(key: String, value: ByteArray) {
        val sealed = PlainEnvelope.seal(value, Vault.contextFor(key))
        prefs.edit().putString(key, Base64.getEncoder().encodeToString(sealed)).apply()
    }

    override fun get(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        val raw = try {
            Base64.getDecoder().decode(stored)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            PlainEnvelope.open(raw, Vault.contextFor(key))
        } catch (_: CryptoException) {
            prefs.edit().remove(key).apply()
            null
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "oliver_vault_plain_debug"

        /**
         * @param debugBuild передавать BuildConfig.DEBUG и ничего иного.
         * @throws VaultException если вызвано не в отладочной сборке.
         */
        @Throws(VaultException::class)
        fun create(context: Context, debugBuild: Boolean): PlainVault {
            try {
                PlainEnvelope.guardRelease(debugBuild)
            } catch (e: CryptoException) {
                throw VaultException(e.message ?: "Открытое хранилище запрещено", e)
            }
            return PlainVault(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
        }
    }
}
