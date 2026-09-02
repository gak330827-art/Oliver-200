/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : security/SecureVault.kt
 *
 *  ВАРИАНТ    : ★ С ШИФРОВАНИЕМ ★  (боевой, используется в release)
 *  Парный файл: security/PlainVault.kt — тот же контракт БЕЗ шифрования.
 *
 *  Схема (классическое envelope-шифрование, как в Tink и Jetpack Security):
 *
 *      Android Keystore ──► KEK (AES-256-GCM, НЕ извлекается из железа)
 *                              │  заворачивает
 *                              ▼
 *                            DEK (32 случайных байта)
 *                              │  шифрует записи через CryptoEnvelope
 *                              ▼
 *                     access_token, refresh_token, профиль
 *
 *  Почему не шифровать записи прямо ключом Keystore: Keystore по умолчанию
 *  требует, чтобы IV генерировал он сам (setRandomizedEncryptionRequired),
 *  и не принимает наш GCMParameterSpec. Пришлось бы отключить эту защиту.
 *  Вместо этого Keystore делает то, что умеет лучше всего — хранит один
 *  ключ, который физически невозможно вытащить, — а формат записей
 *  остаётся нашим, покрытым тестами CryptoEnvelope (включая AAD).
 *
 *  Что это даёт на практике: файл настроек, вытащенный с рутованного
 *  телефона или из бэкапа, бесполезен — KEK остался в TEE/StrongBox.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.oliver200.launcher.core.crypto.CryptoEnvelope
import com.oliver200.launcher.core.crypto.CryptoException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureVault private constructor(
    private val prefs: SharedPreferences,
    private val dek: SecretKey,
) : Vault {

    override val variant: String = "SecureVault (AES-256-GCM, Android Keystore)"
    override val encrypted: Boolean = true

    override fun put(key: String, value: ByteArray) {
        val sealed = CryptoEnvelope.seal(value, dek, Vault.contextFor(key), SECURE_RANDOM)
        prefs.edit().putString(key, encode(sealed)).apply()
    }

    override fun get(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        val sealed = decode(stored) ?: return null
        return try {
            CryptoEnvelope.open(sealed, dek, Vault.contextFor(key))
        } catch (_: CryptoException) {
            // Битую или подменённую запись держать нельзя: удаляем молча,
            // пользователь просто войдёт заново.
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

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {

        private const val PREFS_NAME = "oliver_vault"
        private const val KEY_WRAPPED_DEK = "__wrapped_dek_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEK_ALIAS = "oliver200.kek.v1"
        private const val GCM_TAG_BITS = 128
        private const val DEK_BYTES = 32

        private val SECURE_RANDOM = SecureRandom()

        /**
         * @throws VaultException когда Keystore недоступен — вызывающий обязан
         *         показать это пользователю, а НЕ откатиться на открытое хранение.
         */
        @Throws(VaultException::class)
        fun create(context: Context): SecureVault {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return try {
                val kek = loadOrCreateKek()
                val dek = loadOrCreateDek(prefs, kek)
                SecureVault(prefs, dek)
            } catch (e: KeyPermanentlyInvalidatedException) {
                // Пользователь сменил блокировку экрана — старый ключ мёртв.
                // Единственный корректный выход: начать с нуля и попросить войти снова.
                prefs.edit().clear().apply()
                deleteKek()
                try {
                    val kek = loadOrCreateKek()
                    val dek = loadOrCreateDek(prefs, kek)
                    SecureVault(prefs, dek)
                } catch (e2: GeneralSecurityException) {
                    throw VaultException("Не удалось пересоздать хранилище ключей", e2)
                }
            } catch (e: GeneralSecurityException) {
                throw VaultException("Хранилище ключей устройства недоступно", e)
            }
        }

        private fun keyStore(): KeyStore =
            KeyStore.getInstance(KEYSTORE).apply { load(null) }

        private fun deleteKek() {
            try {
                keyStore().deleteEntry(KEK_ALIAS)
            } catch (_: GeneralSecurityException) {
                // Нечего удалять — это не ошибка.
            }
        }

        @Throws(GeneralSecurityException::class)
        private fun loadOrCreateKek(): SecretKey {
            val ks = keyStore()
            (ks.getKey(KEK_ALIAS, null) as? SecretKey)?.let { return it }

            // StrongBox — отдельный защищённый чип. Есть далеко не на всех
            // устройствах, поэтому пробуем и откатываемся на обычный TEE.
            // StrongBoxUnavailableException наследуется от ProviderException,
            // поэтому ловим родителя: класс-наследник появился только в API 28,
            // и упоминать его в catch на более старых прошивках незачем.
            return try {
                generateKek(strongBox = true)
            } catch (_: java.security.ProviderException) {
                generateKek(strongBox = false)
            }
        }

        @Throws(GeneralSecurityException::class)
        private fun generateKek(strongBox: Boolean): SecretKey {
            val builder = KeyGenParameterSpec.Builder(
                KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Расшифровать можно только на разблокированном устройстве.
                // Приложение работает исключительно на переднем плане,
                // поэтому ограничение ничего не ломает, а украденный
                // заблокированный телефон защищает.
                builder.setUnlockedDeviceRequired(true)
                if (strongBox) builder.setIsStrongBoxBacked(true)
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(builder.build())
            return generator.generateKey()
        }

        @Throws(GeneralSecurityException::class)
        private fun loadOrCreateDek(prefs: SharedPreferences, kek: SecretKey): SecretKey {
            prefs.getString(KEY_WRAPPED_DEK, null)?.let { stored ->
                val raw = try {
                    Base64.getDecoder().decode(stored)
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (raw != null) {
                    unwrapDek(raw, kek)?.let { return it }
                }
                // Обёртка испорчена — старые записи всё равно не прочитать.
                prefs.edit().clear().apply()
            }

            val fresh = ByteArray(DEK_BYTES).also(SECURE_RANDOM::nextBytes)
            val wrapped = wrapDek(fresh, kek)
            prefs.edit().putString(KEY_WRAPPED_DEK, Base64.getEncoder().encodeToString(wrapped)).apply()
            val key = SecretKeySpec(fresh, KeyProperties.KEY_ALGORITHM_AES)
            java.util.Arrays.fill(fresh, 0)
            return key
        }

        /** Формат обёртки: [длина IV][IV][шифротекст+тег]. IV задаёт сам Keystore. */
        @Throws(GeneralSecurityException::class)
        private fun wrapDek(dek: ByteArray, kek: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek)
            val iv = cipher.iv
            val ct = cipher.doFinal(dek)
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            return out
        }

        private fun unwrapDek(wrapped: ByteArray, kek: SecretKey): SecretKey? {
            if (wrapped.isEmpty()) return null
            val ivLen = wrapped[0].toInt() and 0xFF
            if (ivLen !in 12..16 || wrapped.size <= 1 + ivLen) return null
            return try {
                val iv = wrapped.copyOfRange(1, 1 + ivLen)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
                val raw = cipher.doFinal(wrapped, 1 + ivLen, wrapped.size - 1 - ivLen)
                val key = SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES)
                java.util.Arrays.fill(raw, 0)
                key
            } catch (_: GeneralSecurityException) {
                null
            }
        }
    }
}
