/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/mojang/DownloadPlan.kt
 *  Назначение : превращает version.json и индекс ассетов в конкретный список
 *               «что скачать, куда положить, каким SHA-1 проверить».
 *  Безопасность: единственное место, где формируются целевые пути установки.
 *               Всё, что не прошло проверку пути/хоста/хеша, не попадает
 *               в план вообще — загрузчику нечего будет исполнить вслепую.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.mojang

import com.oliver200.launcher.core.hash.Hashing
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.json.Json
import com.oliver200.launcher.core.json.JsonLimits
import com.oliver200.launcher.core.json.JsonValue
import com.oliver200.launcher.core.json.asObj
import com.oliver200.launcher.core.json.bool
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.long
import com.oliver200.launcher.core.json.str
import java.util.Locale

enum class DownloadKind { CLIENT, LIBRARY, NATIVE, ASSET_INDEX, ASSET_OBJECT }

data class DownloadItem(
    val url: String,
    val relativePath: String,
    val sha1: String,
    val size: Long,
    val kind: DownloadKind,
    val label: String,
)

data class DownloadPlan(
    val items: List<DownloadItem>,
    val skipped: List<String> = emptyList(),
) {
    val totalBytes: Long get() = items.sumOf { it.size }
    val count: Int get() = items.size

    operator fun plus(other: DownloadPlan): DownloadPlan =
        DownloadPlan(items + other.items, skipped + other.skipped)
}

object DownloadPlanner {

    const val DIR_VERSIONS = "versions"
    const val DIR_LIBRARIES = "libraries"
    const val DIR_ASSETS = "assets"
    const val ASSET_BASE_URL = "https://resources.download.minecraft.net"

    private val ASSET_LIMITS = JsonLimits(
        maxInputChars = 16 * 1024 * 1024,
        maxDepth = 8,
        maxStringChars = 2_048,
        maxCollectionSize = 200_000,
    )

    /** Клиентский jar, библиотеки, нативные модули и индекс ассетов. */
    fun forVersion(detail: VersionDetail, env: LaunchEnvironment): DownloadPlan {
        val items = ArrayList<DownloadItem>()
        val skipped = ArrayList<String>()

        val client = detail.client
        if (client == null) {
            skipped.add("Нет клиентского jar — версию установить нельзя")
        } else {
            items.add(
                DownloadItem(
                    url = client.url,
                    relativePath = "$DIR_VERSIONS/${detail.id}/${detail.id}.jar",
                    sha1 = client.sha1,
                    size = client.size,
                    kind = DownloadKind.CLIENT,
                    label = "Клиент ${detail.id}",
                ),
            )
        }

        for (lib in detail.libraries) {
            if (!lib.isAllowed(env)) {
                continue // правило не для этой платформы — это норма, не ошибка
            }
            lib.artifact?.let { art ->
                items.add(
                    DownloadItem(
                        url = art.url,
                        relativePath = "$DIR_LIBRARIES/${art.path}",
                        sha1 = art.sha1,
                        size = art.size,
                        kind = DownloadKind.LIBRARY,
                        label = lib.name,
                    ),
                )
            }
            lib.nativeArtifactFor(env)?.let { nat ->
                items.add(
                    DownloadItem(
                        url = nat.url,
                        relativePath = "$DIR_LIBRARIES/${nat.path}",
                        sha1 = nat.sha1,
                        size = nat.size,
                        kind = DownloadKind.NATIVE,
                        label = lib.name + " (natives)",
                    ),
                )
            }
            if (lib.artifact == null && lib.nativeArtifactFor(env) == null) {
                skipped.add("${lib.name}: нет подходящего артефакта")
            }
        }

        detail.assetIndex?.let { idx ->
            items.add(
                DownloadItem(
                    url = idx.url,
                    relativePath = "$DIR_ASSETS/indexes/${idx.id}.json",
                    sha1 = idx.sha1,
                    size = idx.size,
                    kind = DownloadKind.ASSET_INDEX,
                    label = "Индекс ассетов ${idx.id}",
                ),
            )
        } ?: skipped.add("Нет индекса ассетов")

        // Один и тот же файл может встретиться дважды (например, библиотека
        // перечислена и как artifact, и как classifier) — качаем один раз.
        val unique = LinkedHashMap<String, DownloadItem>()
        for (item in items) unique.putIfAbsent(item.relativePath, item)

        return DownloadPlan(unique.values.toList(), skipped)
    }

    /**
     * Разбор индекса ассетов. Файлы кладутся по схеме Mojang:
     * assets/objects/<первые 2 символа хеша>/<хеш>.
     */
    fun forAssets(indexJson: String): DownloadPlan = forAssets(Json.parse(indexJson, ASSET_LIMITS))

    fun forAssets(root: JsonValue): DownloadPlan {
        val items = ArrayList<DownloadItem>()
        val skipped = ArrayList<String>()
        val objects = root.field("objects").asObj()
        if (objects == null) {
            return DownloadPlan(emptyList(), listOf("В индексе ассетов нет секции 'objects'"))
        }
        // virtual/map_to_resources — старые версии, где ассеты нужно ещё и
        // разложить по «человеческим» именам. Скачивание от этого не меняется.
        val seen = HashSet<String>()
        for ((name, node) in objects.fields) {
            val hash = node.str("hash")?.lowercase(Locale.ROOT)
            if (!Hashing.isValidSha1(hash)) {
                skipped.add("$name: некорректный хеш")
                continue
            }
            val prefix = hash!!.substring(0, 2)
            val relative = "$DIR_ASSETS/objects/$prefix/$hash"
            if (SafePath.normalizeRelative(relative) == null) {
                skipped.add("$name: небезопасный путь")
                continue
            }
            if (!seen.add(hash)) continue // один объект переиспользуется многими именами
            items.add(
                DownloadItem(
                    url = "$ASSET_BASE_URL/$prefix/$hash",
                    relativePath = relative,
                    sha1 = hash,
                    size = node.field("size").long() ?: 0L,
                    kind = DownloadKind.ASSET_OBJECT,
                    label = name,
                ),
            )
        }
        return DownloadPlan(items, skipped)
    }

    /** Старые версии требуют «виртуальную» раскладку ассетов по именам. */
    fun assetsAreVirtual(root: JsonValue): Boolean =
        root.field("virtual").bool() == true || root.field("map_to_resources").bool() == true
}
