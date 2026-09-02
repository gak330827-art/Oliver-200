/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : settings.gradle.kts
 *  Назначение : сборка проекта. Модуль :app подключается только когда
 *               в системе реально есть Android SDK — тогда :core можно
 *               собирать и тестировать где угодно (CI без SDK, сервер и т.д.).
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Google Maven трогаем ТОЛЬКО ради Android-плагинов: явный фильтр групп
        // не даёт резолверу ходить туда за обычными JVM-зависимостями.
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "Oliver-200"

include(":core")

/** Ищем Android SDK так же, как это делает сам AGP. */
fun androidSdkAvailable(): Boolean {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (!fromEnv.isNullOrBlank() && java.io.File(fromEnv).isDirectory) return true
    val localProps = java.io.File(rootDir, "local.properties")
    if (localProps.isFile) {
        val props = java.util.Properties()
        localProps.inputStream().use { props.load(it) }
        val dir = props.getProperty("sdk.dir")
        if (!dir.isNullOrBlank() && java.io.File(dir).isDirectory) return true
    }
    return false
}

val forcedInclude: String? = System.getProperty("oliver.includeApp")
    ?: gradle.startParameter.projectProperties["oliver.includeApp"]

val includeApp: Boolean = forcedInclude?.toBoolean() ?: androidSdkAvailable()

if (includeApp) {
    include(":app")
} else {
    println(
        "[Oliver-200] Android SDK не найден — модуль :app пропущен. " +
            "Собирается и тестируется только :core. Форс: -Poliver.includeApp=true"
    )
}
