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

    /** Ник локального профиля: показывает скин и подставляется в запуск без входа. */
    var offlineNick: String?
        get() = MinecraftName.normalize(prefs.getString(KEY_NICK, null))
        set(value) {
            val safe = MinecraftName.normalize(value)
            prefs.edit().apply {
                if (safe == null) remove(KEY_NICK) else putString(KEY_NICK, safe)
            }.apply()
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
    }
}
