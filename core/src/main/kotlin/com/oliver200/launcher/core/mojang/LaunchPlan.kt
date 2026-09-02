/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/mojang/LaunchPlan.kt
 *  Назначение : сборка итоговой команды запуска — classpath, аргументы JVM
 *               и аргументы игры с подстановкой ${плейсхолдеров}.
 *  Безопасность: аргументы собираются СПИСКОМ (argv), а не одной строкой для
 *               оболочки — поэтому пробел или ';' в нике не превращается
 *               в отдельную команду. Дополнительно запрещены NUL и переводы
 *               строки в значениях, а access_token не попадает ни в один
 *               отладочный вывод (см. describe()).
 *  Вариант    : БЕЗ ШИФРОВАНИЯ (сам план секретов не хранит; токен —
 *               отдельное поле, помеченное как секретное)
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.mojang

import com.oliver200.launcher.core.crypto.Redact

/** Значения для подстановки. gameDir/assetsDir и прочее — абсолютные пути на устройстве. */
data class LaunchContext(
    val playerName: String,
    val playerUuid: String,
    val accessToken: String,
    val userType: String = "msa",
    val clientId: String = "",
    val xuid: String = "",
    val versionName: String,
    val versionType: String,
    val gameDir: String,
    val assetsDir: String,
    val assetsIndexName: String,
    val nativesDir: String,
    val librariesDir: String,
    val launcherName: String = "Oliver-200",
    val launcherVersion: String = "1.0",
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val classpathSeparator: String = ":",
) {
    fun placeholders(classpath: String): Map<String, String> = buildMap {
        put("auth_player_name", playerName)
        put("auth_uuid", playerUuid)
        put("auth_access_token", accessToken)
        put("auth_session", "token:$accessToken:$playerUuid") // формат до 1.6
        put("auth_xuid", xuid)
        put("clientid", clientId)
        put("user_type", userType)
        put("user_properties", "{}")
        put("version_name", versionName)
        put("version_type", versionType)
        put("game_directory", gameDir)
        put("assets_root", assetsDir)
        put("game_assets", assetsDir)
        put("assets_index_name", assetsIndexName)
        put("natives_directory", nativesDir)
        put("library_directory", librariesDir)
        put("launcher_name", launcherName)
        put("launcher_version", launcherVersion)
        put("classpath", classpath)
        put("classpath_separator", classpathSeparator)
        resolutionWidth?.let { put("resolution_width", it.toString()) }
        resolutionHeight?.let { put("resolution_height", it.toString()) }
    }
}

data class LaunchPlan(
    val mainClass: String,
    val classpath: List<String>,
    val jvmArgs: List<String>,
    val gameArgs: List<String>,
    val unresolvedPlaceholders: Set<String>,
) {
    /** Полная командная строка для JVM-раннера. */
    fun fullArguments(): List<String> = jvmArgs + mainClass + gameArgs

    /** Безопасное для лога представление: токены вырезаны. */
    fun describe(): String = buildString {
        append("mainClass=").append(mainClass).append('\n')
        append("classpath: ").append(classpath.size).append(" элементов\n")
        append("jvm: ").append(jvmArgs.joinToString(" ") { Redact.scrub(it) }).append('\n')
        append("game: ").append(maskGameArgs().joinToString(" ")).append('\n')
        if (unresolvedPlaceholders.isNotEmpty()) {
            append("не подставлено: ").append(unresolvedPlaceholders.joinToString(", "))
        }
    }

    private fun maskGameArgs(): List<String> {
        val out = ArrayList<String>(gameArgs.size)
        var maskNext = false
        for (arg in gameArgs) {
            out.add(if (maskNext) Redact.secret(arg) else Redact.scrub(arg))
            maskNext = arg == "--accessToken" || arg == "--session"
        }
        return out
    }
}

class LaunchPlanException(message: String) : Exception(message)

object LaunchPlanBuilder {

    private val PLACEHOLDER = Regex("\\$\\{([A-Za-z0-9_]{1,64})}")

    /**
     * Аргументы JVM для версий до 1.13 — там секции "jvm" в JSON просто нет,
     * и лаунчер обязан подставить их сам.
     */
    val LEGACY_JVM_ARGS = listOf(
        "-Djava.library.path=\${natives_directory}",
        "-Dminecraft.launcher.brand=\${launcher_name}",
        "-Dminecraft.launcher.version=\${launcher_version}",
        "-cp",
        "\${classpath}",
    )

    /**
     * @param libraryPath как получить путь к jar библиотеки на устройстве.
     *        Возврат null означает «файл не установлен» — библиотека не попадёт
     *        в classpath, и это будет видно вызывающему.
     */
    fun build(
        detail: VersionDetail,
        env: LaunchEnvironment,
        context: LaunchContext,
        libraryPath: (Library) -> String?,
        clientJarPath: String,
    ): LaunchPlan {
        validateValues(context)

        val classpath = LinkedHashSet<String>()
        for (lib in detail.librariesFor(env)) {
            // Чисто нативная библиотека в classpath не нужна: её содержимое
            // распаковывается в natives_directory.
            if (lib.artifact == null) continue
            libraryPath(lib)?.let { classpath.add(it) }
        }
        classpath.add(clientJarPath)

        val classpathString = classpath.joinToString(context.classpathSeparator)
        val values = context.placeholders(classpathString)
        val unresolved = LinkedHashSet<String>()

        val rawJvm = detail.jvmArguments.ifEmpty {
            LEGACY_JVM_ARGS.map { LaunchArgument(listOf(it)) }
        }

        val jvmArgs = expand(rawJvm, env, values, unresolved)
        val gameArgs = expand(detail.gameArguments, env, values, unresolved)

        return LaunchPlan(
            mainClass = detail.mainClass,
            classpath = classpath.toList(),
            jvmArgs = jvmArgs,
            gameArgs = gameArgs,
            unresolvedPlaceholders = unresolved,
        )
    }

    private fun expand(
        args: List<LaunchArgument>,
        env: LaunchEnvironment,
        values: Map<String, String>,
        unresolved: MutableSet<String>,
    ): List<String> {
        val out = ArrayList<String>()
        for (arg in args) {
            if (!Rules.isAllowed(arg.rules, env)) continue
            for (raw in arg.values) {
                out.add(substitute(raw, values, unresolved))
            }
        }
        return out
    }

    fun substitute(raw: String, values: Map<String, String>, unresolved: MutableSet<String>): String =
        PLACEHOLDER.replace(raw) { m ->
            val key = m.groupValues[1]
            val value = values[key]
            if (value == null) {
                unresolved.add(key)
                m.value // оставляем как есть — так видно, чего не хватило
            } else {
                value
            }
        }

    /**
     * Значения, попадающие в argv, не должны содержать NUL и переводов строки:
     * NUL обрезает строку в нативном слое, перевод строки ломает файлы
     * аргументов (@argfile), которыми пользуются JVM-раннеры на Android.
     */
    private fun validateValues(context: LaunchContext) {
        val checks = linkedMapOf(
            "ник" to context.playerName,
            "UUID" to context.playerUuid,
            "каталог игры" to context.gameDir,
            "каталог ассетов" to context.assetsDir,
            "каталог natives" to context.nativesDir,
            "каталог библиотек" to context.librariesDir,
            "имя версии" to context.versionName,
            "индекс ассетов" to context.assetsIndexName,
        )
        for ((label, value) in checks) {
            if (value.isEmpty()) throw LaunchPlanException("Не заполнено: $label")
            if (hasForbiddenChar(value)) {
                throw LaunchPlanException("Недопустимый символ в поле '$label'")
            }
        }
        if (hasForbiddenChar(context.accessToken)) {
            throw LaunchPlanException("Недопустимый символ в токене доступа")
        }
    }

    private fun hasForbiddenChar(value: String): Boolean =
        value.any { it.code == 0 || it == '\n' || it == '\r' }
}
