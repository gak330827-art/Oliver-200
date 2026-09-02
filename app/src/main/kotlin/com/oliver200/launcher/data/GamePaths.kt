/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : data/GamePaths.kt
 *  Назначение : раскладка каталогов игры на устройстве.
 *  Безопасность: игровые данные лежат в каталоге, выделенном приложению
 *               (getExternalFilesDir) — это песочница, разрешения на
 *               «доступ к файлам» не нужны вовсе, а значит и просить их
 *               у пользователя не за чем. Секреты сюда НЕ попадают:
 *               токены живут только в зашифрованном SecureVault во
 *               внутренней памяти.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.data

import android.content.Context
import android.os.Environment
import com.oliver200.launcher.core.io.SafePath
import java.io.File

class GamePaths(context: Context) {

    private val app = context.applicationContext

    /**
     * Данные игры занимают гигабайты, поэтому предпочитаем внешний
     * каталог приложения — он больше. Если внешнее хранилище не
     * смонтировано, честно откатываемся во внутреннее.
     */
    val root: File = run {
        val external = app.getExternalFilesDir(null)
        val usable = external != null &&
            Environment.getExternalStorageState(external) == Environment.MEDIA_MOUNTED
        val base = if (usable) external!! else app.filesDir
        File(base, "minecraft").apply { mkdirs() }
    }

    val versionsDir: File get() = File(root, "versions")
    val librariesDir: File get() = File(root, "libraries")
    val assetsDir: File get() = File(root, "assets")
    val nativesRoot: File get() = File(root, "natives")
    val shaderpacksDir: File get() = File(root, "shaderpacks")
    val cacheDir: File = File(app.cacheDir, "oliver").apply { mkdirs() }

    fun versionDir(versionId: String): File? =
        SafePath.resolveInside(versionsDir, versionId)

    fun clientJar(versionId: String): File? =
        versionDir(versionId)?.let { File(it, "$versionId.jar") }

    fun versionJson(versionId: String): File? =
        versionDir(versionId)?.let { File(it, "$versionId.json") }

    fun nativesDir(versionId: String): File? =
        SafePath.resolveInside(nativesRoot, versionId)

    fun libraryFile(relativePath: String): File? =
        SafePath.resolveInside(librariesDir, relativePath)

    fun ensureDirs() {
        listOf(versionsDir, librariesDir, assetsDir, nativesRoot, shaderpacksDir)
            .forEach { it.mkdirs() }
    }

    /** Свободное место в байтах — показываем перед установкой версии. */
    fun freeSpaceBytes(): Long = try {
        root.usableSpace
    } catch (_: SecurityException) {
        0L
    }

    companion object {
        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes Б"
            val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
            var value = bytes.toDouble() / 1024
            var i = 0
            while (value >= 1024 && i < units.size - 1) {
                value /= 1024
                i++
            }
            return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[i])
        }
    }
}
