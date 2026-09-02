/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/io/SafePath.kt
 *  Назначение : нормализация имён и защита от выхода за корень (path traversal).
 *  Безопасность: поля "path" в манифесте Mojang и имена записей в ZIP —
 *               внешние данные. Строка "../../../databases/vault.db"
 *               в такой позиции = перезапись чужих файлов приложения.
 *               Здесь единственная точка, где внешняя строка превращается
 *               в File, и она обязана быть параноидальной.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.io

import java.io.File
import java.io.IOException
import java.util.Locale

object SafePath {

    const val MAX_NAME_LENGTH = 120
    const val MAX_RELATIVE_LENGTH = 800
    const val MAX_DEPTH = 24

    /** Имена, зарезервированные в Windows: попадают в проект через кросс-платформенные архивы. */
    private val RESERVED = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9",
    )

    /** Расширения, которых в паке ресурсов/шейдеров быть не должно ни при каких условиях. */
    val EXECUTABLE_EXTENSIONS = setOf(
        "so", "dex", "apk", "jar", "dll", "dylib", "exe", "sh", "bat", "cmd",
        "class", "elf", "bin", "msi", "aar", "aab", "oat", "vdex", "odex",
    )

    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    fun isExecutableName(name: String): Boolean = extensionOf(name) in EXECUTABLE_EXTENSIONS

    /**
     * Проверка ОДНОГО сегмента пути. Возвращает false для всего, что может
     * увести нас из каталога или сломать файловую систему.
     */
    fun isSafeSegment(segment: String): Boolean {
        if (segment.isEmpty() || segment.length > MAX_NAME_LENGTH) return false
        if (segment == "." || segment == "..") return false
        if (segment.startsWith(" ") || segment.endsWith(" ") || segment.endsWith(".")) return false
        for (ch in segment) {
            if (ch.code < 0x20 || ch.code == 0x7F) return false // управляющие и NUL
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?' ||
                ch == '"' || ch == '<' || ch == '>' || ch == '|'
            ) {
                return false
            }
        }
        val base = segment.substringBefore('.').lowercase(Locale.ROOT)
        if (base in RESERVED) return false
        return true
    }

    /**
     * Приводит произвольную строку к безопасному имени файла.
     * Возвращает null, если спасать нечего — молча подставлять "file" опаснее,
     * чем честно отказать.
     */
    fun sanitizeFileName(raw: String): String? {
        val trimmed = raw.trim().replace('\\', '_').replace('/', '_')
        val cleaned = buildString(trimmed.length) {
            for (ch in trimmed) {
                if (ch.code < 0x20 || ch.code == 0x7F) continue
                append(
                    when (ch) {
                        ':', '*', '?', '"', '<', '>', '|' -> '_'
                        else -> ch
                    },
                )
            }
        }
            // Любой прогон из двух и более точек — это остаток "..": схлопываем,
            // чтобы имя физически не могло начинаться с элемента подъёма вверх.
            .replace(Regex("\\.{2,}"), "_")
            .trim()
            .trimEnd('.')
            // Ведущие точки дают скрытый файл, ведущие подчёркивания — мусор
            // от вычистки разделителей. Ни то, ни другое пользователю не нужно.
            .trimStart('.', '_')
        if (cleaned.isEmpty()) return null
        val limited = if (cleaned.length > MAX_NAME_LENGTH) {
            val ext = extensionOf(cleaned)
            val stem = cleaned.substringBeforeLast('.', cleaned)
            val keep = (MAX_NAME_LENGTH - ext.length - 1).coerceAtLeast(1)
            if (ext.isEmpty()) stem.take(MAX_NAME_LENGTH) else stem.take(keep) + "." + ext
        } else {
            cleaned
        }
        return if (isSafeSegment(limited)) limited else null
    }

    /**
     * Нормализует относительный путь из внешнего источника.
     * Возвращает null при любой попытке выйти вверх, абсолютном пути,
     * UNC-пути, диске Windows или превышении лимитов.
     */
    fun normalizeRelative(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_RELATIVE_LENGTH) return null
        if (raw.indexOf('\u0000') >= 0) return null
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) return null // абсолютный
        if (unified.length >= 2 && unified[1] == ':') return null // C:/...
        val parts = unified.split('/')
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue // "a//b" и "a/./b" безобидны
            if (part == "..") return null // подъём вверх не «схлопываем», а запрещаем
            if (!isSafeSegment(part)) return null
            out.add(part)
        }
        if (out.isEmpty() || out.size > MAX_DEPTH) return null
        return out.joinToString("/")
    }

    /**
     * Единственный разрешённый способ получить File внутри корня.
     * Двойная проверка: сначала лексическая (normalizeRelative), затем
     * канонизация — она снимает символические ссылки, через которые
     * «безопасный» путь всё равно может указать наружу.
     */
    fun resolveInside(root: File, relative: String): File? {
        val norm = normalizeRelative(relative) ?: return null
        val candidate = File(root, norm)
        return if (isInside(root, candidate)) candidate else null
    }

    fun isInside(root: File, child: File): Boolean = try {
        val rootPath = root.canonicalFile.toPath().normalize()
        val childPath = child.canonicalFile.toPath().normalize()
        childPath.startsWith(rootPath) && childPath != rootPath
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
