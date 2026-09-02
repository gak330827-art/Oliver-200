/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · модуль :app (Android APK)
 *  Назначение : UI и платформенный слой. Вся логика — в :core, здесь только
 *               экраны, сеть, хранилище ключей и рисование скина.
 *  Безопасность сборки:
 *      · minSdk 26 — гарантированы AES/GCM в Keystore и PBKDF2WithHmacSHA256;
 *      · release: R8 + удаление всех вызовов android.util.Log;
 *      · подпись release берётся из окружения, ключей в репозитории нет.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "2.0.21"
}

/** Публичный client_id приложения Microsoft (НЕ секрет, но и не в git). */
fun localProperty(name: String): String {
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
    val f = rootProject.file("local.properties")
    if (f.isFile) {
        val p = Properties()
        f.inputStream().use { p.load(it) }
        p.getProperty(name)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

android {
    namespace = "com.oliver200.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oliver200.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        resourceConfigurations += listOf("ru", "en")

        // Device Code Flow не требует client_secret — в APK нет ни одного секрета.
        buildConfigField(
            "String",
            "MS_CLIENT_ID",
            "\"" + localProperty("OLIVER_MS_CLIENT_ID") + "\"",
        )
    }

    signingConfigs {
        create("release") {
            val store = localProperty("OLIVER_KEYSTORE_FILE")
            if (store.isNotBlank() && file(store).exists()) {
                storeFile = file(store)
                storePassword = localProperty("OLIVER_KEYSTORE_PASSWORD")
                keyAlias = localProperty("OLIVER_KEY_ALIAS")
                keyPassword = localProperty("OLIVER_KEY_PASSWORD")
                enableV1Signing = false // v1 уязвим к Janus, на minSdk 26 не нужен
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val hasKeystore = localProperty("OLIVER_KEYSTORE_FILE").isNotBlank()
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin-плагин добавляет src/main/kotlin сам, но явное объявление
    // избавляет от зависимости от версии плагина.
    sourceSets["main"].java.srcDirs("src/main/kotlin")

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
