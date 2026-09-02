/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/shader/ShaderStore.kt
 *  Назначение : каталог shaderpacks/ — установка, список, удаление, выбор.
 *               Выбранный пак записывается в optionsshaders.txt — ровно тот
 *               файл, который читают Iris и OptiFine при старте игры.
 *  Безопасность: имя файла задаёт пользователь (или архив), поэтому оно
 *               проходит через SafePath.sanitizeFileName, а конечный путь
 *               дополнительно проверяется на нахождение внутри каталога.
 *               Установка идёт во временный файл с последующим переименованием:
 *               прерванное копирование не оставит «половину пака», которую
 *               игра примет за целый.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.shader

import com.oliver200.launcher.core.hash.Hashing
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.io.ZipLimits
import java.io.File
import java.io.IOException
import java.io.InputStream

data class InstalledShaderPack(
    val file: File,
    val info: ShaderPackInfo,
    val sizeBytes: Long,
    val sha256: String,
) {
    val id: String get() = file.name
}

class ShaderStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * @param gameDir корневой каталог игры (.minecraft). Пакет кладётся
 *                в gameDir/shaderpacks, выбор — в gameDir/optionsshaders.txt.
 */
class ShaderStore(private val gameDir: File) {

    companion object {
        const val PACKS_DIR = "shaderpacks"
        const val SELECTION_FILE = "optionsshaders.txt"
        const val SELECTION_KEY = "shaderPack"
        const val NONE = "(off)" // так Iris обозначает «шейдеры выключены»
        private const val MAX_PACK_BYTES = 512L * 1024 * 1024
    }

    val packsDir: File get() = File(gameDir, PACKS_DIR)

    fun ensureDirs() {
        if (!packsDir.isDirectory && !packsDir.mkdirs()) {
            throw ShaderStoreException("Не удалось создать ${packsDir.absolutePath}")
        }
    }

    fun list(limits: ZipLimits = ZipLimits.SHADER_PACK): List<InstalledShaderPack> {
        val dir = packsDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                try {
                    InstalledShaderPack(
                        file = file,
                        info = ShaderPackInspector.inspect(file, limits),
                        sizeBytes = file.length(),
                        sha256 = file.inputStream().use { Hashing.digestStream(Hashing.SHA256, it) },
                    )
                } catch (_: IOException) {
                    null // битый или опасный архив просто не показываем
                }
            }
            ?: emptyList()
    }

    /**
     * Копирует архив в каталог паков, предварительно проверив его целиком.
     * @param preferredName желаемое имя файла (например, из Uri документа).
     */
    @Throws(ShaderStoreException::class)
    fun install(
        source: InputStream,
        preferredName: String,
        limits: ZipLimits = ZipLimits.SHADER_PACK,
    ): InstalledShaderPack {
        ensureDirs()
        val safeName = safePackName(preferredName)
        val staging = File.createTempFile("olv-shader-", ".part", packsDir)
        try {
            val copied = staging.outputStream().use { out ->
                com.oliver200.launcher.core.io.SafeZip.copyBounded(source, out, MAX_PACK_BYTES)
            }
            if (copied == 0L) throw ShaderStoreException("Пустой файл")

            // Проверяем ДО того, как файл получит окончательное имя: игра
            // не должна ни секунды видеть непроверенный архив.
            val info = ShaderPackInspector.inspect(staging, limits)
            if (!info.usable) {
                throw ShaderStoreException("В архиве нет шейдеров: ${info.warnings.firstOrNull() ?: ""}")
            }

            val target = SafePath.resolveInside(packsDir, safeName)
                ?: throw ShaderStoreException("Недопустимое имя пака")
            if (target.exists() && !target.delete()) {
                throw ShaderStoreException("Не удалось заменить существующий пак")
            }
            if (!staging.renameTo(target)) {
                throw ShaderStoreException("Не удалось переместить пак на место")
            }
            return InstalledShaderPack(
                file = target,
                info = info.copy(fileName = target.name),
                sizeBytes = target.length(),
                sha256 = target.inputStream().use { Hashing.digestStream(Hashing.SHA256, it) },
            )
        } catch (e: ShaderStoreException) {
            throw e
        } catch (e: IOException) {
            throw ShaderStoreException(e.message ?: "Ошибка установки пака", e)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun remove(id: String): Boolean {
        val name = SafePath.sanitizeFileName(id) ?: return false
        val target = SafePath.resolveInside(packsDir, name) ?: return false
        if (!target.isFile) return false
        if (currentSelection() == target.name) select(null)
        return target.delete()
    }

    /* ─────────────────── Выбор пака: optionsshaders.txt ─────────────────── */

    fun selectionFile(): File = File(gameDir, SELECTION_FILE)

    fun currentSelection(): String? {
        val f = selectionFile()
        if (!f.isFile) return null
        return try {
            f.readLines()
                .firstOrNull { it.startsWith("$SELECTION_KEY=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != NONE }
        } catch (_: IOException) {
            null
        }
    }

    /** @param packId имя файла пака или null, чтобы выключить шейдеры. */
    @Throws(ShaderStoreException::class)
    fun select(packId: String?) {
        val value = if (packId == null) {
            NONE
        } else {
            val name = SafePath.sanitizeFileName(packId)
                ?: throw ShaderStoreException("Недопустимое имя пака")
            val file = SafePath.resolveInside(packsDir, name)
            if (file == null || !file.isFile) throw ShaderStoreException("Пак не установлен: $packId")
            file.name
        }
        if (!gameDir.isDirectory && !gameDir.mkdirs()) {
            throw ShaderStoreException("Нет каталога игры ${gameDir.absolutePath}")
        }
        val target = selectionFile()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            // Формат Iris/OptiFine: строки key=value. Значение уже безопасно
            // (прошло sanitize), поэтому перевода строки в нём быть не может.
            tmp.writeText("$SELECTION_KEY=$value\n")
            if (target.exists() && !target.delete()) {
                throw ShaderStoreException("Не удалось обновить $SELECTION_FILE")
            }
            if (!tmp.renameTo(target)) {
                throw ShaderStoreException("Не удалось записать $SELECTION_FILE")
            }
        } catch (e: IOException) {
            throw ShaderStoreException(e.message ?: "Ошибка записи выбора шейдера", e)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun safePackName(preferred: String): String {
        val base = SafePath.sanitizeFileName(preferred) ?: "shaderpack.zip"
        return if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
    }
}
