/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/shader/ShaderPack.kt
 *  Назначение : распознавание и проверка паков шейдеров (.zip).
 *  Безопасность: пак шейдеров пользователь берёт с случайного сайта. Внутри
 *               обязаны быть только текстовые GLSL и картинки. Любой .so,
 *               .dex, .jar или .apk внутри — это попытка выполнить код;
 *               такой архив отвергается целиком (см. SafeZip). Плюс здесь
 *               же ловятся zip-slip и zip-бомбы.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.shader

import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.io.SafeZip
import com.oliver200.launcher.core.io.ZipInspection
import com.oliver200.launcher.core.io.ZipLimits
import com.oliver200.launcher.core.io.ZipSecurityException
import java.io.File
import java.util.Locale

/** Какой рендерер умеет запускать этот пак. */
enum class ShaderKind(val ruLabel: String, val requirement: String) {
    IRIS_OPTIFINE("Iris / OptiFine", "Нужен мод Iris (Fabric) или OptiFine"),
    CANVAS("Canvas", "Нужен мод Canvas Renderer"),
    VANILLA_CORE("Core-шейдеры", "Работают без модов, начиная с 1.17"),
    BEDROCK("Bedrock RTX", "Только Minecraft Bedrock с поддержкой RTX"),
    UNKNOWN("Неопознанный", "Не удалось определить тип пака"),
}

data class ShaderPackInfo(
    val fileName: String,
    val displayName: String,
    val kind: ShaderKind,
    val rootPrefix: String?,
    val fileCount: Int,
    val uncompressedBytes: Long,
    val warnings: List<String>,
) {
    val usable: Boolean get() = kind != ShaderKind.UNKNOWN
}

object ShaderPackInspector {

    private val GLSL_EXTENSIONS = setOf("fsh", "vsh", "gsh", "csh", "glsl", "tcs", "tes")

    /** Расширения, которые вообще имеют право лежать в паке шейдеров. */
    private val ALLOWED_EXTENSIONS = GLSL_EXTENSIONS + setOf(
        "properties", "txt", "json", "mcmeta", "lang", "png", "jpg", "jpeg",
        "md", "html", "css", "vert", "frag", "geom", "comp", "material", "bin",
        "", // файлы без расширения (LICENSE, README) — безобидны
    )

    @Throws(ZipSecurityException::class)
    fun inspect(zip: File, limits: ZipLimits = ZipLimits.SHADER_PACK): ShaderPackInfo {
        val inspection = SafeZip.inspect(zip, limits)
        val warnings = ArrayList<String>()
        val paths = inspection.files().map { it.normalizedPath }

        // Общий верхний каталог — это ЛИБО обёртка вида "BSL_v8.2/", ЛИБО сам
        // полезный каталог "shaders/". Срезать вслепую нельзя: во втором случае
        // мы своими руками уничтожим признак, по которому пак и опознаётся.
        // Поэтому сначала пробуем распознать как есть и срезаем только если
        // без этого не получилось.
        var kind = detectKind(paths)
        var root: String? = null
        var relative = paths

        if (kind == ShaderKind.UNKNOWN) {
            val candidate = inspection.commonRootDirectory()
            if (candidate != null) {
                val stripped = paths.map { it.removePrefix("$candidate/") }
                val strippedKind = detectKind(stripped)
                if (strippedKind != ShaderKind.UNKNOWN) {
                    kind = strippedKind
                    root = candidate
                    relative = stripped
                }
            }
        }

        for (path in relative) {
            val ext = SafePath.extensionOf(path)
            if (ext !in ALLOWED_EXTENSIONS) {
                warnings.add("Необычный файл в паке: $path")
            }
        }
        if (kind == ShaderKind.UNKNOWN) {
            warnings.add("Не найдено ни одного шейдера — возможно, это ресурспак")
        }

        return ShaderPackInfo(
            fileName = zip.name,
            displayName = displayNameFor(zip.name, root),
            kind = kind,
            rootPrefix = root,
            fileCount = inspection.files().size,
            uncompressedBytes = inspection.totalDeclaredSize,
            warnings = warnings,
        )
    }

    fun detectKind(relativePaths: List<String>): ShaderKind {
        val lower = relativePaths.map { it.lowercase(Locale.ROOT) }

        val hasIris = lower.any { path ->
            path.startsWith("shaders/") &&
                (SafePath.extensionOf(path) in GLSL_EXTENSIONS || path == "shaders/shaders.properties")
        }
        if (hasIris) return ShaderKind.IRIS_OPTIFINE

        val hasCanvas = lower.any { it.contains("/pipelines/") || it.startsWith("pipelines/") }
        if (hasCanvas) return ShaderKind.CANVAS

        val hasVanillaCore = lower.any { it.startsWith("assets/") && it.contains("/shaders/") } &&
            lower.any { it == "pack.mcmeta" }
        if (hasVanillaCore) return ShaderKind.VANILLA_CORE

        val hasBedrock = lower.any { it == "manifest.json" } &&
            lower.any { it.contains("shaders/glsl/") || it.contains("materials/") }
        if (hasBedrock) return ShaderKind.BEDROCK

        return ShaderKind.UNKNOWN
    }

    private fun displayNameFor(fileName: String, root: String?): String {
        val fromRoot = root?.takeIf { it.isNotBlank() }
        val base = fromRoot ?: fileName.substringBeforeLast('.', fileName)
        return base.replace('_', ' ').replace('-', ' ').trim().ifEmpty { fileName }
    }

    /** Отдельно доступно для UI: сколько всего распакуется. */
    fun totalSize(inspection: ZipInspection): Long = inspection.totalDeclaredSize
}
