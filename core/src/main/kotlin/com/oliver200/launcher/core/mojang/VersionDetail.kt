/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/mojang/VersionDetail.kt
 *  Назначение : разбор version.json конкретной версии — клиентский jar,
 *               библиотеки, нативные модули, индекс ассетов, аргументы.
 *  Безопасность: КАЖДОЕ поле здесь превращается либо в путь на диске,
 *               либо в URL, либо в аргумент запуска JVM. Все три —
 *               управляемые извне векторы:
 *                 · downloads.artifact.path -> path traversal;
 *                 · downloads.artifact.url  -> подмена исполняемого кода;
 *                 · mainClass               -> запуск чужого класса.
 *               Артефакт без валидного SHA-1 отбрасывается: скачивать то,
 *               целостность чего нельзя проверить, бессмысленно.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.mojang

import com.oliver200.launcher.core.hash.Hashing
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.json.JsonException
import com.oliver200.launcher.core.json.JsonLimits
import com.oliver200.launcher.core.json.JsonValue
import com.oliver200.launcher.core.json.asObj
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.int
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.long
import com.oliver200.launcher.core.json.num
import com.oliver200.launcher.core.json.str
import com.oliver200.launcher.core.json.string
import com.oliver200.launcher.core.net.UrlGuard
import java.util.Locale

/** Скачиваемый файл: путь внутри хранилища, размер, хеш и адрес. */
data class MojangArtifact(
    val path: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

data class AssetIndexRef(
    val id: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long,
    val url: String,
)

data class Library(
    val name: String,
    val artifact: MojangArtifact?,
    val classifiers: Map<String, MojangArtifact>,
    val nativesByOs: Map<String, String>,
    val rules: List<Rule>,
    val extractExcludes: List<String>,
) {
    fun isAllowed(env: LaunchEnvironment): Boolean = Rules.isAllowed(rules, env)

    /**
     * Классификатор нативной части для платформы. В старых версиях в
     * natives-строке встречается плейсхолдер ${arch} (32/64).
     */
    fun nativeClassifierFor(env: LaunchEnvironment): String? {
        val raw = nativesByOs[env.osName.lowercase(Locale.ROOT)] ?: return null
        val bits = if (env.osArch == "arm64" || env.osArch == "x86_64") "64" else "32"
        return raw.replace("\${arch}", bits)
    }

    fun nativeArtifactFor(env: LaunchEnvironment): MojangArtifact? =
        nativeClassifierFor(env)?.let { classifiers[it] }
}

/** Один аргумент запуска: строки плюс условия применимости. */
data class LaunchArgument(
    val values: List<String>,
    val rules: List<Rule> = emptyList(),
)

data class VersionDetail(
    val id: String,
    val mainClass: String,
    val type: String,
    val assetsId: String?,
    val assetIndex: AssetIndexRef?,
    val client: MojangArtifact?,
    val libraries: List<Library>,
    val gameArguments: List<LaunchArgument>,
    val jvmArguments: List<LaunchArgument>,
    val javaMajorVersion: Int?,
    val minimumLauncherVersion: Int,
    val warnings: List<String> = emptyList(),
) {
    fun librariesFor(env: LaunchEnvironment): List<Library> = libraries.filter { it.isAllowed(env) }
}

object VersionDetailParser {

    private val LIMITS = JsonLimits(
        maxInputChars = 4 * 1024 * 1024,
        maxDepth = 32,
        maxStringChars = 16 * 1024,
        maxCollectionSize = 20_000,
    )

    /** Имя Java-класса: буквы/цифры/_/$ через точку. Ничего похожего на путь или флаг. */
    private val MAIN_CLASS = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")

    fun parse(text: String, expectedId: String? = null): VersionDetail =
        parse(Json.parse(text, LIMITS), expectedId)

    fun parse(root: JsonValue, expectedId: String? = null): VersionDetail {
        val warnings = ArrayList<String>()

        val id = root.str("id")
            ?: throw JsonException("В version.json нет поля 'id'")
        if (!SafePath.isSafeSegment(id)) {
            throw JsonException("Небезопасный id версии: $id")
        }
        if (expectedId != null && expectedId != id) {
            // Подменённый JSON под чужим именем — сразу отказ, а не «ну ладно».
            throw JsonException("id версии не совпадает: ожидали '$expectedId', получили '$id'")
        }

        val mainClass = root.str("mainClass")
            ?: throw JsonException("В version.json нет поля 'mainClass'")
        if (!MAIN_CLASS.matches(mainClass)) {
            throw JsonException("Недопустимое имя главного класса: $mainClass")
        }

        val client = root.field("downloads").field("client")
            ?.let { parseArtifact(it, defaultPath = "versions/$id/$id.jar", warnings = warnings) }

        val assetIndex = root.field("assetIndex")?.let { node ->
            val indexId = node.str("id")
            val url = node.str("url")
            val sha1 = node.str("sha1")
            if (indexId == null || !SafePath.isSafeSegment(indexId)) {
                warnings.add("Плохой id индекса ассетов")
                null
            } else if (!Hashing.isValidSha1(sha1)) {
                warnings.add("Индекс ассетов без валидного SHA-1")
                null
            } else if (!UrlGuard.isAllowed(url, UrlGuard.MOJANG_CONTENT + UrlGuard.MOJANG_META)) {
                warnings.add("Индекс ассетов с недопустимого хоста")
                null
            } else {
                AssetIndexRef(
                    id = indexId,
                    sha1 = sha1!!.lowercase(Locale.ROOT),
                    size = node.num("size") ?: 0L,
                    totalSize = node.num("totalSize") ?: 0L,
                    url = url!!,
                )
            }
        }

        val libraries = root.field("libraries").items()
            .mapNotNull { parseLibrary(it, warnings) }

        val argsNode = root.field("arguments")
        val gameArgs: List<LaunchArgument>
        val jvmArgs: List<LaunchArgument>
        if (argsNode != null) {
            gameArgs = parseArguments(argsNode.field("game"))
            jvmArgs = parseArguments(argsNode.field("jvm"))
        } else {
            // Формат до 1.13: одна строка minecraftArguments и никаких JVM-аргументов.
            val legacy = root.str("minecraftArguments").orEmpty()
            gameArgs = legacy.split(' ', '\t', '\n')
                .filter { it.isNotBlank() }
                .map { LaunchArgument(listOf(it)) }
            jvmArgs = emptyList()
        }

        return VersionDetail(
            id = id,
            mainClass = mainClass,
            type = root.str("type") ?: "release",
            assetsId = root.str("assets"),
            assetIndex = assetIndex,
            client = client,
            libraries = libraries,
            gameArguments = gameArgs,
            jvmArguments = jvmArgs,
            javaMajorVersion = root.field("javaVersion").field("majorVersion").int(),
            minimumLauncherVersion = root.field("minimumLauncherVersion").int() ?: 0,
            warnings = warnings,
        )
    }

    private fun parseArtifact(
        node: JsonValue,
        defaultPath: String?,
        warnings: MutableList<String>,
    ): MojangArtifact? {
        val url = node.str("url")
        if (!UrlGuard.isAllowed(url, UrlGuard.MOJANG_CONTENT)) {
            warnings.add("Артефакт с недопустимого хоста отброшен: ${url ?: "<нет url>"}")
            return null
        }
        val sha1 = node.str("sha1")
        if (!Hashing.isValidSha1(sha1)) {
            warnings.add("Артефакт без валидного SHA-1 отброшен: $url")
            return null
        }
        val rawPath = node.str("path") ?: defaultPath
        if (rawPath == null) {
            warnings.add("Артефакт без пути отброшен: $url")
            return null
        }
        val path = SafePath.normalizeRelative(rawPath)
        if (path == null) {
            warnings.add("Артефакт с опасным путём отброшен: $rawPath")
            return null
        }
        val size = node.field("size").long() ?: 0L
        if (size < 0) {
            warnings.add("Артефакт с отрицательным размером отброшен: $path")
            return null
        }
        return MojangArtifact(path = path, sha1 = sha1!!.lowercase(Locale.ROOT), size = size, url = url!!)
    }

    private fun parseLibrary(node: JsonValue, warnings: MutableList<String>): Library? {
        val obj = node.asObj() ?: return null
        val name = obj.str("name") ?: run {
            warnings.add("Библиотека без имени пропущена")
            return null
        }
        val downloads = obj.field("downloads")
        val artifact = downloads.field("artifact")
            ?.let { parseArtifact(it, defaultPath = mavenNameToPath(name), warnings = warnings) }

        val classifiers = LinkedHashMap<String, MojangArtifact>()
        downloads.field("classifiers").asObj()?.fields?.forEach { (key, value) ->
            if (!SafePath.isSafeSegment(key)) {
                warnings.add("Небезопасный классификатор '$key' у $name")
                return@forEach
            }
            parseArtifact(value, defaultPath = null, warnings = warnings)?.let { classifiers[key] = it }
        }

        val natives = LinkedHashMap<String, String>()
        obj.field("natives").asObj()?.fields?.forEach { (osKey, value) ->
            value.string()?.let { natives[osKey.lowercase(Locale.ROOT)] = it }
        }

        val excludes = obj.field("extract").field("exclude").items()
            .mapNotNull { it.string() }
            .filter { it.length <= SafePath.MAX_RELATIVE_LENGTH }

        if (artifact == null && classifiers.isEmpty()) {
            // Библиотека без единого пригодного файла бесполезна, но не фатальна:
            // например, все её артефакты не подошли по правилам.
            warnings.add("У библиотеки $name нет пригодных артефактов")
        }

        return Library(
            name = name,
            artifact = artifact,
            classifiers = classifiers,
            nativesByOs = natives,
            rules = Rules.parse(obj.field("rules")),
            extractExcludes = excludes,
        )
    }

    private fun parseArguments(node: JsonValue?): List<LaunchArgument> =
        node.items().mapNotNull { item ->
            when (item) {
                is JsonValue.Str -> LaunchArgument(listOf(item.value))
                is JsonValue.Obj -> {
                    val values = when (val v = item.fields["value"]) {
                        is JsonValue.Str -> listOf(v.value)
                        is JsonValue.Arr -> v.items.mapNotNull { it.string() }
                        else -> emptyList()
                    }
                    if (values.isEmpty()) null
                    else LaunchArgument(values, Rules.parse(item.fields["rules"]))
                }
                else -> null
            }
        }

    /**
     * "group:artifact:version[:classifier]" -> "group/path/artifact/version/artifact-version[-classifier].jar".
     * Используется как запасной путь, когда downloads.artifact.path отсутствует.
     */
    fun mavenNameToPath(name: String, extension: String = "jar"): String? {
        val parts = name.split(':')
        if (parts.size < 3 || parts.size > 4) return null
        val group = parts[0]
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)
        if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) return null
        val fileName = buildString {
            append(artifact).append('-').append(version)
            if (!classifier.isNullOrEmpty()) append('-').append(classifier)
            append('.').append(extension)
        }
        val path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName
        return SafePath.normalizeRelative(path)
    }
}
