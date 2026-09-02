/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : data/ShaderRepository.kt
 *  Назначение : импорт паков шейдеров из файлового пикера и управление ими.
 *  Безопасность: файл приходит по content-Uri из произвольного источника
 *               (мессенджер, браузер, флешка). Имя, которое отдаёт
 *               провайдер, — недоверенная строка, поэтому оно чистится
 *               SafePath. Содержимое проверяется ShaderPackInspector:
 *               zip-slip, zip-бомба и нативный код внутри «шейдеров»
 *               отсекаются до того, как файл получит своё имя.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.oliver200.launcher.core.shader.InstalledShaderPack
import com.oliver200.launcher.core.shader.ShaderStore
import com.oliver200.launcher.core.shader.ShaderStoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ShaderRepository(context: Context, paths: GamePaths) {

    private val app = context.applicationContext
    private val store = ShaderStore(paths.root)

    suspend fun list(): List<InstalledShaderPack> = withContext(Dispatchers.IO) {
        store.list()
    }

    fun currentSelection(): String? = store.currentSelection()

    suspend fun select(packId: String?) = withContext(Dispatchers.IO) {
        store.select(packId)
    }

    suspend fun remove(packId: String): Boolean = withContext(Dispatchers.IO) {
        store.remove(packId)
    }

    @Throws(ShaderStoreException::class)
    suspend fun import(uri: Uri): InstalledShaderPack = withContext(Dispatchers.IO) {
        val name = displayNameOf(uri) ?: "shaderpack.zip"
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                store.install(input, name)
            } ?: throw ShaderStoreException("Не удалось открыть выбранный файл")
        } catch (e: SecurityException) {
            throw ShaderStoreException("Нет доступа к выбранному файлу", e)
        } catch (e: IOException) {
            if (e is ShaderStoreException) throw e
            throw ShaderStoreException(e.message ?: "Ошибка чтения файла", e)
        }
    }

    /** Имя из провайдера — подсказка, не более. Очистка происходит в ShaderStore. */
    private fun displayNameOf(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    cursor.getString(0)?.take(200)
                } else {
                    null
                }
            }
    } catch (_: Exception) {
        // Провайдер может не поддерживать запрос имени — не повод падать.
        null
    }
}
