/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : data/VersionRepository.kt
 *  Назначение : список версий, загрузка version.json, установка версии.
 *  Безопасность: version.json проверяется по SHA-1 ИЗ МАНИФЕСТА — это
 *               ровно тот файл, который решает, откуда качать код и какой
 *               класс запускать. Проверять только jar-файлы и доверять
 *               метаданным — распространённая ошибка: подменив json,
 *               атакующий подменяет и все ссылки внутри него.
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.data

import com.oliver200.launcher.core.hash.Hashing
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.io.SafeZip
import com.oliver200.launcher.core.io.ZipLimits
import com.oliver200.launcher.core.mojang.DownloadItem
import com.oliver200.launcher.core.mojang.DownloadPlan
import com.oliver200.launcher.core.mojang.DownloadPlanner
import com.oliver200.launcher.core.mojang.LaunchEnvironment
import com.oliver200.launcher.core.mojang.VersionDetail
import com.oliver200.launcher.core.mojang.VersionDetailParser
import com.oliver200.launcher.core.mojang.VersionManifest
import com.oliver200.launcher.core.mojang.VersionManifestParser
import com.oliver200.launcher.core.mojang.VersionSummary
import com.oliver200.launcher.core.net.UrlGuard
import com.oliver200.launcher.net.Downloader
import com.oliver200.launcher.net.DownloadReport
import com.oliver200.launcher.net.SecureHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class VersionRepository(private val paths: GamePaths) {

    companion object {
        const val MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

        /** Запасной адрес: исторический, работает до сих пор. */
        const val MANIFEST_URL_FALLBACK =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

        private const val CACHE_FILE = "version_manifest_v2.json"
        private const val CACHE_TTL_MS = 60L * 60 * 1000 // час
        private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        private const val MAX_VERSION_JSON_BYTES = 4L * 1024 * 1024
    }

    private val downloader = Downloader(paths.root, UrlGuard.GAME_DOWNLOADS)

    /* ─────────────────────────── Список версий ─────────────────────────── */

    suspend fun loadManifest(forceNetwork: Boolean = false): VersionManifest =
        withContext(Dispatchers.IO) {
            val cache = File(paths.cacheDir, CACHE_FILE)
            if (!forceNetwork && cache.isFile &&
                System.currentTimeMillis() - cache.lastModified() < CACHE_TTL_MS
            ) {
                runCatching { VersionManifestParser.parse(cache.readText()) }
                    .getOrNull()
                    ?.let { return@withContext it }
            }

            val text = fetchManifestText()
            val parsed = VersionManifestParser.parse(text)
            runCatching { cache.writeText(text) } // кэш — удобство, его отказ не фатален
            parsed
        }

    /** Кэш «на крайний случай»: сети нет — показываем последнее, что видели. */
    suspend fun loadCachedManifest(): VersionManifest? = withContext(Dispatchers.IO) {
        val cache = File(paths.cacheDir, CACHE_FILE)
        if (!cache.isFile) return@withContext null
        runCatching { VersionManifestParser.parse(cache.readText()) }.getOrNull()
    }

    private suspend fun fetchManifestText(): String {
        val errors = ArrayList<String>()
        for (url in listOf(MANIFEST_URL, MANIFEST_URL_FALLBACK)) {
            try {
                val resp = SecureHttp.get(url, UrlGuard.MOJANG_META, maxBody = MAX_MANIFEST_BYTES)
                if (resp.code in 200..299) return resp.text()
                errors.add("HTTP ${resp.code}")
            } catch (e: IOException) {
                errors.add(e.message ?: e.javaClass.simpleName)
            }
        }
        throw IOException("Не удалось получить список версий: ${errors.joinToString("; ")}")
    }

    /* ─────────────────────────── version.json ─────────────────────────── */

    suspend fun loadDetail(summary: VersionSummary): VersionDetail = withContext(Dispatchers.IO) {
        val local = paths.versionJson(summary.id)
        if (local != null && local.isFile) {
            val text = local.readText()
            // Локальную копию тоже сверяем: файл мог быть подменён на устройстве.
            if (summary.sha1 == null ||
                Hashing.constantTimeEqualsHex(
                    summary.sha1,
                    Hashing.sha1(text.toByteArray(StandardCharsets.UTF_8)),
                )
            ) {
                runCatching { VersionDetailParser.parse(text, summary.id) }
                    .getOrNull()
                    ?.let { return@withContext it }
            }
        }

        val resp = SecureHttp.get(summary.url, UrlGuard.MOJANG_META, maxBody = MAX_VERSION_JSON_BYTES)
        if (resp.code !in 200..299) {
            throw IOException("Не удалось получить описание версии (HTTP ${resp.code})")
        }
        if (summary.sha1 != null &&
            !Hashing.constantTimeEqualsHex(summary.sha1, Hashing.sha1(resp.body))
        ) {
            throw IOException("Описание версии ${summary.id} не прошло проверку SHA-1")
        }

        val text = resp.text()
        val detail = VersionDetailParser.parse(text, summary.id)

        paths.versionDir(summary.id)?.let { dir ->
            dir.mkdirs()
            runCatching { File(dir, "${summary.id}.json").writeText(text) }
        }
        detail
    }

    /* ─────────────────────────── Установка ─────────────────────────── */

    fun isInstalled(versionId: String): Boolean =
        paths.clientJar(versionId)?.isFile == true

    fun installedIds(): Set<String> =
        paths.versionsDir.listFiles()
            ?.filter { it.isDirectory && File(it, "${it.name}.jar").isFile }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

    suspend fun buildPlan(detail: VersionDetail, env: LaunchEnvironment): DownloadPlan =
        withContext(Dispatchers.IO) {
            var plan = DownloadPlanner.forVersion(detail, env)
            // Индекс ассетов нужен до того, как мы узнаем список объектов,
            // поэтому качаем его первым и сразу разбираем.
            val indexItem = plan.items.firstOrNull {
                it.kind == com.oliver200.launcher.core.mojang.DownloadKind.ASSET_INDEX
            }
            if (indexItem != null) {
                when (val outcome = downloader.fetch(indexItem)) {
                    is com.oliver200.launcher.net.DownloadOutcome.Done -> {
                        val assets = runCatching {
                            DownloadPlanner.forAssets(outcome.file.readText())
                        }.getOrElse { DownloadPlan(emptyList(), listOf("Индекс ассетов нечитаем")) }
                        plan = plan + assets
                    }
                    is com.oliver200.launcher.net.DownloadOutcome.Failed ->
                        plan = plan + DownloadPlan(
                            emptyList(),
                            listOf("Индекс ассетов не скачался: ${outcome.reason}"),
                        )
                }
            }
            plan
        }

    suspend fun install(
        plan: DownloadPlan,
        onProgress: (done: Int, total: Int, item: DownloadItem) -> Unit,
    ): DownloadReport {
        paths.ensureDirs()
        return downloader.run(plan, onProgress)
    }

    /**
     * Распаковка нативных библиотек: LWJGL ищет .so в java.library.path,
     * а лежат они внутри jar-ов. Распаковка идёт через SafeZip, поэтому
     * запись «../../lib/libc.so» внутри архива никуда не денется.
     */
    suspend fun extractNatives(detail: VersionDetail, env: LaunchEnvironment): Int =
        withContext(Dispatchers.IO) {
            val target = paths.nativesDir(detail.id)
                ?: throw IOException("Недопустимый идентификатор версии")
            target.mkdirs()
            var files = 0
            for (lib in detail.librariesFor(env)) {
                val native = lib.nativeArtifactFor(env) ?: continue
                val jar = paths.libraryFile(native.path) ?: continue
                if (!jar.isFile) continue
                val excludes = lib.extractExcludes
                val result = SafeZip.extract(jar, target, ZipLimits.NATIVES) { path ->
                    excludes.none { path.startsWith(it) }
                }
                files += result.filesWritten
            }
            files
        }

    fun libraryPathFor(relativePath: String): String? =
        SafePath.resolveInside(paths.librariesDir, relativePath)
            ?.takeIf { it.isFile }
            ?.absolutePath
}
