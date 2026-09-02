/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · модуль :core
 *  Назначение : вся логика лаунчера, не зависящая от Android — разбор
 *               манифестов Mojang, проверка целостности, безопасная работа
 *               с ZIP и путями, криптоконтейнер, сборка команды запуска.
 *               Ноль внешних runtime-зависимостей: только Kotlin stdlib + JDK.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Ядро подключается к Android-модулю, поэтому никаких JDK-API выше 17.
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
