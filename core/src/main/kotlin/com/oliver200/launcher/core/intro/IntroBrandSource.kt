/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/intro/IntroBrandSource.kt
 *  Назначение : выбор варианта хранения текстов заставки.
 *  Правило    : release — ВСЕГДА шифрованный контейнер. Отката на открытые
 *               строки при ошибке нет: лучше показать пустую заставку,
 *               чем чужой бренд из подменённого файла.
 *               Ровно тот же принцип, что в security/VaultFactory.kt.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.intro

object IntroBrandSource {

    /**
     * @param debugBuild BuildConfig.DEBUG
     * @param forcePlain взять открытый вариант (работает только в debug)
     * @param binding привязка шифрованного контейнера
     */
    @Throws(IntroBrandException::class)
    fun load(
        debugBuild: Boolean,
        forcePlain: Boolean = false,
        binding: String = SealedIntroBrand.BINDING_DEFAULT,
    ): IntroBrand {
        if (forcePlain) {
            // guardRelease внутри сам не пустит открытый вариант в release.
            PlainIntroBrand.guardRelease(debugBuild)
            return PlainIntroBrand.load()
        }
        return SealedIntroBrand.load(binding)
    }
}
