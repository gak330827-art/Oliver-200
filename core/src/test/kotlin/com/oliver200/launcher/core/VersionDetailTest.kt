/*
 * Oliver-200 · тесты разбора version.json, плана загрузки и команды запуска.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import com.oliver200.launcher.core.json.JsonException
import com.oliver200.launcher.core.mojang.DownloadKind
import com.oliver200.launcher.core.mojang.DownloadPlanner
import com.oliver200.launcher.core.mojang.LaunchContext
import com.oliver200.launcher.core.mojang.LaunchEnvironment
import com.oliver200.launcher.core.mojang.LaunchPlanBuilder
import com.oliver200.launcher.core.mojang.LaunchPlanException
import com.oliver200.launcher.core.mojang.VersionDetailParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class VersionDetailTest {

    private val D = '$'

    private val modern = """
        {
          "id": "1.21.4",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "17",
          "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 },
          "assetIndex": {
            "id": "17", "sha1": "0123456789abcdef0123456789abcdef01234567",
            "size": 4096, "totalSize": 700000000,
            "url": "https://piston-meta.mojang.com/v1/packages/aa/17.json"
          },
          "downloads": {
            "client": {
              "sha1": "aaaabbbbccccddddeeeeffff0000111122223333", "size": 26000000,
              "url": "https://piston-data.mojang.com/v1/objects/aa/client.jar"
            }
          },
          "libraries": [
            {
              "name": "com.mojang:logging:1.2.7",
              "downloads": { "artifact": {
                "path": "com/mojang/logging/1.2.7/logging-1.2.7.jar",
                "sha1": "1111222233334444555566667777888899990000", "size": 15000,
                "url": "https://libraries.minecraft.net/com/mojang/logging/1.2.7/logging-1.2.7.jar" } }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.3",
              "downloads": {
                "artifact": {
                  "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar",
                  "sha1": "abcdefabcdefabcdefabcdefabcdefabcdefabcd", "size": 700000,
                  "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar" },
                "classifiers": {
                  "natives-linux": {
                    "path": "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
                    "sha1": "fedcbafedcbafedcbafedcbafedcbafedcbafedc", "size": 120000,
                    "url": "https://libraries.minecraft.net/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar" }
                }
              },
              "natives": { "linux": "natives-linux", "windows": "natives-windows" },
              "extract": { "exclude": [ "META-INF/" ] }
            },
            {
              "name": "ca.weblite:java-objc-bridge:1.1",
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ],
              "downloads": { "artifact": {
                "path": "ca/weblite/java-objc-bridge/1.1/java-objc-bridge-1.1.jar",
                "sha1": "9999888877776666555544443333222211110000", "size": 40000,
                "url": "https://libraries.minecraft.net/ca/weblite/java-objc-bridge/1.1/java-objc-bridge-1.1.jar" } }
            },
            {
              "name": "evil:traversal:1.0",
              "downloads": { "artifact": {
                "path": "../../../../shared_prefs/oliver_vault.xml",
                "sha1": "5555555555555555555555555555555555555555", "size": 10,
                "url": "https://libraries.minecraft.net/evil/traversal/1.0/traversal-1.0.jar" } }
            },
            {
              "name": "evil:offsite:1.0",
              "downloads": { "artifact": {
                "path": "evil/offsite/1.0/offsite-1.0.jar",
                "sha1": "6666666666666666666666666666666666666666", "size": 10,
                "url": "https://attacker.example.com/offsite-1.0.jar" } }
            }
          ],
          "arguments": {
            "game": [
              "--username", "$D{auth_player_name}",
              "--version", "$D{version_name}",
              "--gameDir", "$D{game_directory}",
              "--assetsDir", "$D{assets_root}",
              "--assetIndex", "$D{assets_index_name}",
              "--uuid", "$D{auth_uuid}",
              "--accessToken", "$D{auth_access_token}",
              { "rules": [ { "action": "allow", "features": { "is_demo_user": true } } ],
                "value": "--demo" },
              { "rules": [ { "action": "allow", "features": { "has_custom_resolution": true } } ],
                "value": [ "--width", "$D{resolution_width}", "--height", "$D{resolution_height}" ] }
            ],
            "jvm": [
              { "rules": [ { "action": "allow", "os": { "name": "osx" } } ],
                "value": [ "-XstartOnFirstThread" ] },
              { "rules": [ { "action": "allow", "os": { "name": "windows" } } ],
                "value": "-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance" },
              "-Djava.library.path=$D{natives_directory}",
              "-Dminecraft.launcher.brand=$D{launcher_name}",
              "-cp", "$D{classpath}"
            ]
          }
        }
    """.trimIndent()

    private val legacy = """
        {
          "id": "1.7.10",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "1.7.10",
          "minecraftArguments": "--username $D{auth_player_name} --version $D{version_name} --gameDir $D{game_directory} --assetsDir $D{assets_root} --assetIndex $D{assets_index_name} --uuid $D{auth_uuid} --accessToken $D{auth_access_token} --userProperties $D{user_properties} --userType $D{user_type}",
          "assetIndex": {
            "id": "1.7.10", "sha1": "0123456789abcdef0123456789abcdef01234567", "size": 100, "totalSize": 100,
            "url": "https://launchermeta.mojang.com/v1/packages/bb/1.7.10.json"
          },
          "downloads": { "client": {
            "sha1": "aaaabbbbccccddddeeeeffff0000111122223333", "size": 5000000,
            "url": "https://launcher.mojang.com/v1/objects/cc/client.jar" } },
          "libraries": []
        }
    """.trimIndent()

    private val android = LaunchEnvironment.android64("14")

    /* ─────────────────────────── Разбор ─────────────────────────── */

    @Test
    fun `современный формат разбирается полностью`() {
        val d = VersionDetailParser.parse(modern, expectedId = "1.21.4")
        assertEquals("net.minecraft.client.main.Main", d.mainClass)
        assertEquals(21, d.javaMajorVersion)
        assertEquals("17", d.assetIndex!!.id)
        assertEquals(26000000L, d.client!!.size)
        assertEquals("versions/1.21.4/1.21.4.jar", d.client!!.path)
    }

    @Test
    @DisplayName("path с ../ и артефакт с чужого хоста выбрасываются")
    fun `опасные библиотеки отбрасываются`() {
        val d = VersionDetailParser.parse(modern)
        val traversal = d.libraries.first { it.name == "evil:traversal:1.0" }
        val offsite = d.libraries.first { it.name == "evil:offsite:1.0" }
        assertNull(traversal.artifact)
        assertNull(offsite.artifact)
        assertTrue(d.warnings.any { it.contains("опасным путём") })
        assertTrue(d.warnings.any { it.contains("недопустимого хоста") })
    }

    @Test
    fun `правила отсекают библиотеку для macOS`() {
        val d = VersionDetailParser.parse(modern)
        val names = d.librariesFor(android).map { it.name }
        assertFalse(names.contains("ca.weblite:java-objc-bridge:1.1"))
        assertTrue(names.contains("org.lwjgl:lwjgl:3.3.3"))
    }

    @Test
    fun `нативный классификатор выбирается по платформе`() {
        val d = VersionDetailParser.parse(modern)
        val lwjgl = d.libraries.first { it.name == "org.lwjgl:lwjgl:3.3.3" }
        assertEquals("natives-linux", lwjgl.nativeClassifierFor(android))
        assertNotNull(lwjgl.nativeArtifactFor(android))
        assertEquals(listOf("META-INF/"), lwjgl.extractExcludes)
    }

    @Test
    fun `подменённый id и опасный mainClass отвергаются`() {
        assertThrows(JsonException::class.java) {
            VersionDetailParser.parse(modern, expectedId = "1.21.5")
        }
        val badClass = modern.replace(
            "\"mainClass\": \"net.minecraft.client.main.Main\"",
            "\"mainClass\": \"..evil rm -rf\"",
        )
        assertThrows(JsonException::class.java) { VersionDetailParser.parse(badClass) }
        val badId = modern.replace("\"id\": \"1.21.4\"", "\"id\": \"../../evil\"")
        assertThrows(JsonException::class.java) { VersionDetailParser.parse(badId) }
    }

    @Test
    fun `старый формат minecraftArguments поддерживается`() {
        val d = VersionDetailParser.parse(legacy, expectedId = "1.7.10")
        assertTrue(d.jvmArguments.isEmpty())
        assertEquals("--username", d.gameArguments.first().values.first())
        assertTrue(d.gameArguments.any { it.values.first() == "--userType" })
    }

    @Test
    fun `maven-имя превращается в путь`() {
        assertEquals(
            "com/mojang/blocklist/1.0.10/blocklist-1.0.10.jar",
            VersionDetailParser.mavenNameToPath("com.mojang:blocklist:1.0.10"),
        )
        assertEquals(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar",
            VersionDetailParser.mavenNameToPath("org.lwjgl:lwjgl:3.3.3:natives-linux"),
        )
        assertNull(VersionDetailParser.mavenNameToPath("сломано"))
        assertNull(VersionDetailParser.mavenNameToPath("a:b:c:d:e"))
    }

    /* ───────────────────────── План загрузки ───────────────────────── */

    @Test
    fun `план загрузки содержит клиент, библиотеки, natives и индекс`() {
        val d = VersionDetailParser.parse(modern)
        val plan = DownloadPlanner.forVersion(d, android)
        val kinds = plan.items.groupBy { it.kind }
        assertEquals(1, kinds[DownloadKind.CLIENT]!!.size)
        assertEquals(1, kinds[DownloadKind.NATIVE]!!.size)
        assertEquals(1, kinds[DownloadKind.ASSET_INDEX]!!.size)
        assertEquals(2, kinds[DownloadKind.LIBRARY]!!.size)

        assertEquals(
            "versions/1.21.4/1.21.4.jar",
            kinds[DownloadKind.CLIENT]!!.first().relativePath,
        )
        assertTrue(
            plan.items.all {
                it.relativePath.startsWith("versions/") ||
                    it.relativePath.startsWith("libraries/") ||
                    it.relativePath.startsWith("assets/")
            },
        )
        assertTrue(plan.totalBytes > 26_000_000)
        assertTrue(plan.skipped.any { it.contains("evil:traversal") })
    }

    @Test
    fun `индекс ассетов превращается в объектные пути`() {
        val index = """
            {"objects":{
              "minecraft/sounds/step.ogg":{"hash":"abcdefabcdefabcdefabcdefabcdefabcdefabcd","size":1000},
              "minecraft/lang/ru_ru.json":{"hash":"abcdefabcdefabcdefabcdefabcdefabcdefabcd","size":1000},
              "битый":{"hash":"нехеш","size":1}
            }}
        """.trimIndent()
        val plan = DownloadPlanner.forAssets(index)
        assertEquals(1, plan.count)
        assertEquals(
            "assets/objects/ab/abcdefabcdefabcdefabcdefabcdefabcdefabcd",
            plan.items[0].relativePath,
        )
        assertEquals(
            "https://resources.download.minecraft.net/ab/abcdefabcdefabcdefabcdefabcdefabcdefabcd",
            plan.items[0].url,
        )
        assertTrue(plan.skipped.any { it.contains("битый") })
    }

    /* ───────────────────────── Команда запуска ───────────────────────── */

    private fun context(name: String = "Steve") = LaunchContext(
        playerName = name,
        playerUuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
        accessToken = "SECRET_ACCESS_TOKEN_1234567890",
        versionName = "1.21.4",
        versionType = "release",
        gameDir = "/data/user/0/com.oliver200.launcher/files/.minecraft",
        assetsDir = "/data/user/0/com.oliver200.launcher/files/.minecraft/assets",
        assetsIndexName = "17",
        nativesDir = "/data/user/0/com.oliver200.launcher/files/.minecraft/natives/1.21.4",
        librariesDir = "/data/user/0/com.oliver200.launcher/files/.minecraft/libraries",
        xuid = "2535000000000000",
    )

    @Test
    fun `команда запуска собирается с подстановкой значений`() {
        val d = VersionDetailParser.parse(modern)
        val plan = LaunchPlanBuilder.build(
            detail = d,
            env = android,
            context = context(),
            libraryPath = { lib -> lib.artifact?.let { "/libs/" + it.path } },
            clientJarPath = "/versions/1.21.4/1.21.4.jar",
        )
        assertEquals("net.minecraft.client.main.Main", plan.mainClass)
        assertEquals(3, plan.classpath.size)
        assertTrue(plan.classpath.last().endsWith("1.21.4.jar"))

        val game = plan.gameArgs
        assertEquals("Steve", game[game.indexOf("--username") + 1])
        assertEquals("17", game[game.indexOf("--assetIndex") + 1])
        assertFalse(game.contains("--demo"))
        assertFalse(game.contains("--width"))

        assertFalse(plan.jvmArgs.contains("-XstartOnFirstThread"))
        assertTrue(plan.jvmArgs.none { it.contains("MojangTricksIntelDrivers") })
        assertTrue(plan.jvmArgs.contains("-cp"))
        assertTrue(plan.jvmArgs.any { it.startsWith("-Djava.library.path=/data/") })
        assertEquals(plan.classpath.joinToString(":"), plan.jvmArgs.last())
        assertTrue(plan.unresolvedPlaceholders.isEmpty())
    }

    @Test
    fun `фича включает дополнительные аргументы`() {
        val d = VersionDetailParser.parse(modern)
        val env = android.copy(features = mapOf("has_custom_resolution" to true, "is_demo_user" to true))
        val plan = LaunchPlanBuilder.build(
            d,
            env,
            context().copy(resolutionWidth = 1280, resolutionHeight = 720),
            { lib -> lib.artifact?.let { "/libs/" + it.path } },
            "/versions/1.21.4/1.21.4.jar",
        )
        assertTrue(plan.gameArgs.contains("--demo"))
        assertEquals("1280", plan.gameArgs[plan.gameArgs.indexOf("--width") + 1])
    }

    @Test
    fun `старый формат получает JVM-аргументы по умолчанию`() {
        val d = VersionDetailParser.parse(legacy)
        val plan = LaunchPlanBuilder.build(
            d,
            android,
            context().copy(versionName = "1.7.10", assetsIndexName = "1.7.10"),
            { null },
            "/versions/1.7.10/1.7.10.jar",
        )
        assertTrue(plan.jvmArgs.contains("-cp"))
        assertTrue(plan.jvmArgs.any { it.startsWith("-Dminecraft.launcher.brand=Oliver-200") })
        assertEquals(1, plan.classpath.size)
    }

    @Test
    @DisplayName("Ник со спецсимволами остаётся ОДНИМ аргументом, а не превращается в команду")
    fun `инъекция через ник невозможна`() {
        val d = VersionDetailParser.parse(modern)
        val evil = "Steve; rm -rf / #"
        val plan = LaunchPlanBuilder.build(
            d,
            android,
            context(evil),
            { lib -> lib.artifact?.let { "/libs/" + it.path } },
            "/versions/1.21.4/1.21.4.jar",
        )
        val idx = plan.gameArgs.indexOf("--username")
        assertEquals(evil, plan.gameArgs[idx + 1])
        assertEquals(1, plan.gameArgs.count { it == evil })
        assertTrue(plan.fullArguments().contains(evil))
    }

    @Test
    fun `перевод строки и NUL в значениях запрещены`() {
        val d = VersionDetailParser.parse(modern)
        assertThrows(LaunchPlanException::class.java) {
            LaunchPlanBuilder.build(d, android, context("Steve\nEvil"), { null }, "/c.jar")
        }
        assertThrows(LaunchPlanException::class.java) {
            LaunchPlanBuilder.build(d, android, context("Steve\u0000Evil"), { null }, "/c.jar")
        }
        assertThrows(LaunchPlanException::class.java) {
            LaunchPlanBuilder.build(d, android, context().copy(gameDir = ""), { null }, "/c.jar")
        }
    }

    @Test
    @DisplayName("describe() не печатает токен доступа")
    fun `отладочный вывод маскирует токен`() {
        val d = VersionDetailParser.parse(modern)
        val plan = LaunchPlanBuilder.build(
            d,
            android,
            context(),
            { lib -> lib.artifact?.let { "/libs/" + it.path } },
            "/versions/1.21.4/1.21.4.jar",
        )
        val text = plan.describe()
        assertFalse(text.contains("SECRET_ACCESS_TOKEN_1234567890"), text)
        assertTrue(text.contains("mainClass=net.minecraft.client.main.Main"))
    }

    @Test
    fun `неизвестный плейсхолдер попадает в список нерешённых`() {
        val unresolved = LinkedHashSet<String>()
        val out = LaunchPlanBuilder.substitute(
            "-Dfoo=" + D + "{no_such_key}" + D + "{version_name}",
            mapOf("version_name" to "1.21.4"),
            unresolved,
        )
        assertTrue(out.endsWith("1.21.4"))
        assertEquals(setOf("no_such_key"), unresolved)
    }
}
