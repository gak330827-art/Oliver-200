/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : mc/RuntimeBridge.kt
 *  Назначение : передача готовой команды запуска внешней среде выполнения
 *               Java (JVM + трансляция OpenGL), установленной отдельно.
 *
 *  Почему так: Minecraft Java Edition требует настоящую JVM и OpenGL.
 *  Ни того, ни другого в Android нет, а тащить в APK свою сборку OpenJDK
 *  и трансляцию GL — отдельный проект на сотни мегабайт нативного кода.
 *  Наш лаунчер делает свою часть честно: аккаунт, версии с проверкой
 *  целостности, скины, шейдеры и полностью собранная команда запуска.
 *
 *  Безопасность — здесь она важнее всего в приложении:
 *      Access-token Minecraft даёт полный доступ к игровому аккаунту.
 *      Передать его через Intent постороннему приложению — значит отдать
 *      аккаунт. Поэтому:
 *        · по умолчанию токен НЕ передаётся вовсе;
 *        · передача возможна только после явного подтверждения, где
 *          пользователю показано имя пакета-получателя;
 *        · Intent всегда адресный (setPackage) — широковещательный
 *          вариант, который перехватит любое приложение, не создаётся
 *          никогда;
 *        · токен не пишется ни в лог, ни в describe().
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.mc

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.oliver200.launcher.core.mojang.LaunchPlan

data class RuntimeInfo(
    val packageName: String,
    val label: String,
    val activityName: String,
)

object RuntimeBridge {

    /** Действие, которое должна объявить совместимая среда выполнения. */
    const val ACTION_LAUNCH = "com.oliver200.launcher.action.LAUNCH_MINECRAFT"

    const val EXTRA_MAIN_CLASS = "mainClass"
    const val EXTRA_JVM_ARGS = "jvmArgs"
    const val EXTRA_GAME_ARGS = "gameArgs"
    const val EXTRA_CLASSPATH = "classpath"
    const val EXTRA_GAME_DIR = "gameDir"
    const val EXTRA_NATIVES_DIR = "nativesDir"
    const val EXTRA_JAVA_VERSION = "javaMajorVersion"
    const val EXTRA_ACCESS_TOKEN = "accessToken"

    /** Ищет установленные приложения, которые объявили наше действие. */
    fun findRuntimes(pm: PackageManager): List<RuntimeInfo> {
        val intent = Intent(ACTION_LAUNCH)
        val resolved: List<ResolveInfo> = try {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) {
            emptyList()
        }
        return resolved.mapNotNull { info ->
            val activity = info.activityInfo ?: return@mapNotNull null
            RuntimeInfo(
                packageName = activity.packageName,
                label = runCatching { info.loadLabel(pm).toString() }
                    .getOrDefault(activity.packageName),
                activityName = activity.name,
            )
        }
    }

    /**
     * @param includeToken передавать ли access-token. Ставить true можно
     *        ТОЛЬКО после явного подтверждения пользователем с показом
     *        имени пакета-получателя.
     */
    fun buildIntent(
        runtime: RuntimeInfo,
        plan: LaunchPlan,
        gameDir: String,
        nativesDir: String,
        javaMajorVersion: Int?,
        accessToken: String?,
        includeToken: Boolean,
    ): Intent {
        val gameArgs = if (includeToken) plan.gameArgs else stripToken(plan.gameArgs)
        return Intent(ACTION_LAUNCH).apply {
            // Адресный Intent: получатель ровно один и известен заранее.
            setPackage(runtime.packageName)
            setClassName(runtime.packageName, runtime.activityName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_MAIN_CLASS, plan.mainClass)
            putExtra(EXTRA_JVM_ARGS, plan.jvmArgs.toTypedArray())
            putExtra(EXTRA_GAME_ARGS, gameArgs.toTypedArray())
            putExtra(EXTRA_CLASSPATH, plan.classpath.toTypedArray())
            putExtra(EXTRA_GAME_DIR, gameDir)
            putExtra(EXTRA_NATIVES_DIR, nativesDir)
            javaMajorVersion?.let { putExtra(EXTRA_JAVA_VERSION, it) }
            if (includeToken && !accessToken.isNullOrEmpty()) {
                putExtra(EXTRA_ACCESS_TOKEN, accessToken)
            }
        }
    }

    /**
     * Вырезает значение токена из аргументов игры. Именно значение, а не
     * сам флаг: игра ожидает пару "--accessToken <значение>", и если
     * убрать только значение, следующий аргумент займёт его место.
     */
    private fun stripToken(args: List<String>): List<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if ((arg == "--accessToken" || arg == "--session") && i + 1 < args.size) {
                out.add(arg)
                out.add("0") // формат, который лаунчеры используют для запуска без входа
                i += 2
            } else {
                out.add(arg)
                i++
            }
        }
        return out
    }
}
