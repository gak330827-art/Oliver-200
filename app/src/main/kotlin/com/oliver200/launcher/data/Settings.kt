/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : data/Settings.kt
 *  Назначение : несекретные настройки — выбранная версия, фильтры списка,
 *               ник для локального профиля.
 *  Безопасность: здесь НЕТ и не должно быть ничего секретного. Токены
 *               живут только в Vault. Значения всё равно валидируются
 *               при чтении: файл настроек правится на рутованном
 *               устройстве, и «версия» вида ../../ туда попасть может.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (осознанно: секретов не хранит)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.data

import android.content.Context
import com.oliver200.launcher.core.auth.OfflinePlayer
import com.oliver200.launcher.core.auth.OfflineProfile
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.mojang.ReleaseChannel
import com.oliver200.launcher.core.skin.MinecraftName

class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("oliver_settings", Context.MODE_PRIVATE)

    var selectedVersion: String?
        get() = prefs.getString(KEY_VERSION, null)?.takeIf { SafePath.isSafeSegment(it) }
        set(value) {
            val safe = value?.takeIf { SafePath.isSafeSegment(it) }
            prefs.edit().apply {
                if (safe == null) remove(KEY_VERSION) else putString(KEY_VERSION, safe)
            }.apply()
        }

    /** Ник офлайн-профиля: показывает скин и подставляется в запуск без входа. */
    var offlineNick: String?
        get() = MinecraftName.normalize(prefs.getString(KEY_NICK, null))
        set(value) {
            val safe = MinecraftName.normalize(value)
            prefs.edit().apply {
                if (safe == null) remove(KEY_NICK) else putString(KEY_NICK, safe)
            }.apply()
        }

    /**
     * Готовый офлайн-профиль с посчитанным UUID.
     * Профиль собирается заново при каждом чтении: хранить UUID отдельно
     * незачем и опаснее — рассинхрон ника и UUID означал бы, что на сервере
     * игрок внезапно стал другим человеком и потерял свой мир.
     */
    val offlineProfile: OfflinePlayer?
        get() = OfflineProfile.of(offlineNick)

    /** Каким профилем запускать игру. */
    var profileMode: ProfileMode
        get() = ProfileMode.entries
            .firstOrNull { it.name == prefs.getString(KEY_PROFILE_MODE, null) }
            ?: ProfileMode.MICROSOFT
        set(value) {
            prefs.edit().putString(KEY_PROFILE_MODE, value.name).apply()
        }

    /**
     * Голос диктора на заставке. По умолчанию включён, но выключается
     * одним флагом — и тогда плеер вообще не создаётся: ни открытия
     * файла, ни запроса фокуса звука.
     */
    var introVoice: Boolean
        get() = prefs.getBoolean(KEY_INTRO_VOICE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_INTRO_VOICE, value).apply()
        }

    var channels: Set<ReleaseChannel>
        get() {
            val raw = prefs.getStringSet(KEY_CHANNELS, null)
                ?: return setOf(ReleaseChannel.RELEASE)
            val parsed = raw.mapNotNull { name ->
                ReleaseChannel.entries.firstOrNull { it.name == name }
            }.toSet()
            return parsed.ifEmpty { setOf(ReleaseChannel.RELEASE) }
        }
        set(value) {
            val safe = value.ifEmpty { setOf(ReleaseChannel.RELEASE) }
            prefs.edit().putStringSet(KEY_CHANNELS, safe.map { it.name }.toSet()).apply()
        }

    private companion object {
        const val KEY_VERSION = "selected_version"
        const val KEY_NICK = "offline_nick"
        const val KEY_CHANNELS = "channels"
        const val KEY_PROFILE_MODE = "profile_mode"
        const val KEY_INTRO_VOICE = "intro_voice"
    }
}

/** Чем представляется игрок при запуске. */
enum class ProfileMode { MICROSOFT, OFFLINE }
