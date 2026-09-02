/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/mojang/VersionManifest.kt
 *  Назначение : разбор version_manifest_v2.json — список всех версий игры.
 *               Это источник данных для экрана «Выбор версии».
 *  Безопасность: id версии становится ИМЕНЕМ КАТАЛОГА (versions/<id>/<id>.jar).
 *               id вида "../../shared_prefs" — это запись мимо песочницы.
 *               Каждая запись проверяется на безопасность id и на то, что
 *               url ведёт на разрешённый хост Mojang; кривые записи
 *               отбрасываются, а не «чинятся».
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
import com.oliver200.launcher.core.json.field
import com.oliver200.launcher.core.json.int
import com.oliver200.launcher.core.json.items
import com.oliver200.launcher.core.json.str
import com.oliver200.launcher.core.net.UrlGuard
import java.util.Locale

/** Канал выпуска. Пользователю показываем по-русски, в JSON лежит английское имя. */
enum class ReleaseChannel(val jsonName: String, val ruLabel: String) {
    RELEASE("release", "Релиз"),
    SNAPSHOT("snapshot", "Снапшот"),
    OLD_BETA("old_beta", "Бета"),
    OLD_ALPHA("old_alpha", "Альфа"),
    OTHER("other", "Прочее"),
    ;

    companion object {
        fun from(raw: String?): ReleaseChannel {
            val v = raw?.lowercase(Locale.ROOT) ?: return OTHER
            return entries.firstOrNull { it.jsonName == v } ?: OTHER
        }
    }
}

data class VersionSummary(
    val id: String,
    val channel: ReleaseChannel,
    val rawType: String,
    val url: String,
    val sha1: String?,
    val releaseTime: String,
    val time: String,
    val complianceLevel: Int,
) {
    /** Год выпуска — дешёвая подпись для списка, парсить дату целиком незачем. */
    val year: String get() = releaseTime.take(4)
}

data class VersionManifest(
    val latestRelease: String?,
    val latestSnapshot: String?,
    val versions: List<VersionSummary>,
    val rejected: List<String> = emptyList(),
) {
    fun byId(id: String): VersionSummary? = versions.firstOrNull { it.id == id }

    /** Фильтр для экрана выбора: каналы + поиск по подстроке. */
    fun filter(channels: Set<ReleaseChannel>, query: String? = null): List<VersionSummary> {
        val q = query?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return versions.filter { v ->
            v.channel in channels && (q == null || v.id.lowercase(Locale.ROOT).contains(q))
        }
    }

    /** Новые сверху. ISO-8601 сравнивается лексикографически — этого достаточно. */
    fun sortedByDateDesc(): List<VersionSummary> = versions.sortedByDescending { it.releaseTime }
}

object VersionManifestParser {

    /** Манифест Mojang — около 1 МБ и растёт; лимит с большим запасом. */
    private val LIMITS = JsonLimits(
        maxInputChars = 8 * 1024 * 1024,
        maxDepth = 24,
        maxStringChars = 8 * 1024,
        maxCollectionSize = 50_000,
    )

    fun parse(text: String): VersionManifest {
        val root = Json.parse(text, LIMITS)
        return parse(root)
    }

    fun parse(root: JsonValue): VersionManifest {
        val latest = root.field("latest")
        val out = ArrayList<VersionSummary>()
        val rejected = ArrayList<String>()

        for (node in root.field("versions").items()) {
            val obj = node.asObj()
            if (obj == null) {
                rejected.add("не объект")
                continue
            }
            val id = obj.str("id")
            if (id.isNullOrEmpty()) {
                rejected.add("без id")
                continue
            }
            // id уйдёт в имя каталога — сюда пускаем только безопасный сегмент.
            if (!SafePath.isSafeSegment(id)) {
                rejected.add("небезопасный id: $id")
                continue
            }
            val url = obj.str("url")
            if (!UrlGuard.isAllowed(url, UrlGuard.MOJANG_META)) {
                rejected.add("$id: url вне белого списка")
                continue
            }
            val sha1 = obj.str("sha1")?.takeIf { Hashing.isValidSha1(it) }
            val rawType = obj.str("type") ?: "other"
            out.add(
                VersionSummary(
                    id = id,
                    channel = ReleaseChannel.from(rawType),
                    rawType = rawType,
                    url = url!!,
                    sha1 = sha1,
                    releaseTime = obj.str("releaseTime") ?: "",
                    time = obj.str("time") ?: "",
                    complianceLevel = obj.field("complianceLevel").int() ?: 0,
                ),
            )
        }

        return VersionManifest(
            latestRelease = latest.str("release"),
            latestSnapshot = latest.str("snapshot"),
            versions = out,
            rejected = rejected,
        )
    }
}
