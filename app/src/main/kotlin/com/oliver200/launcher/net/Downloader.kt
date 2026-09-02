/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : net/Downloader.kt
 *  Назначение : выполнение плана загрузки версии.
 *  Безопасность:
 *      · целевой путь берётся ТОЛЬКО через SafePath.resolveInside;
 *      · каждый файл проверяется по SHA-1 из манифеста, сравнение
 *        constant-time; не сошлось — файл удаляется, установка падает;
 *      · запись идёт во временный ".part" и переименовывается лишь после
 *        успешной проверки: оборванная закачка не превратится в «готовый»
 *        jar, который потом исполнится в JVM;
 *      · размер ограничен заявленным в манифесте с запасом — сервер не
 *        забьёт память телефона бесконечным потоком.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.net

import com.oliver200.launcher.core.hash.Hashing
import com.oliver200.launcher.core.io.SafePath
import com.oliver200.launcher.core.mojang.DownloadItem
import com.oliver200.launcher.core.mojang.DownloadPlan
import com.oliver200.launcher.core.net.HostPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed class DownloadOutcome {
    data class Done(val item: DownloadItem, val file: File, val fromCache: Boolean) : DownloadOutcome()
    data class Failed(val item: DownloadItem, val reason: String) : DownloadOutcome()
}

data class DownloadReport(
    val completed: Int,
    val skipped: Int,
    val failures: List<DownloadOutcome.Failed>,
) {
    val success: Boolean get() = failures.isEmpty()
}

class IntegrityException(message: String) : IOException(message)

class Downloader(private val root: File, private val policy: HostPolicy) {

    /** Запас к заявленному размеру: манифест иногда чуть расходится с реальностью. */
    private val sizeSlackBytes = 8L * 1024 * 1024

    /** Абсолютный потолок на один файл — клиентский jar не бывает больше. */
    private val hardCapBytes = 512L * 1024 * 1024

    suspend fun run(
        plan: DownloadPlan,
        onProgress: (done: Int, total: Int, item: DownloadItem) -> Unit = { _, _, _ -> },
    ): DownloadReport = withContext(Dispatchers.IO) {
        var completed = 0
        var skipped = 0
        val failures = ArrayList<DownloadOutcome.Failed>()

        for ((index, item) in plan.items.withIndex()) {
            ensureActive() // отмена установки должна срабатывать сразу
            onProgress(index, plan.items.size, item)
            when (val outcome = fetch(item)) {
                is DownloadOutcome.Done -> {
                    completed++
                    if (outcome.fromCache) skipped++
                }
                is DownloadOutcome.Failed -> failures.add(outcome)
            }
        }
        DownloadReport(completed, skipped, failures)
    }

    suspend fun fetch(item: DownloadItem): DownloadOutcome = withContext(Dispatchers.IO) {
        val target = SafePath.resolveInside(root, item.relativePath)
            ?: return@withContext DownloadOutcome.Failed(item, "Небезопасный путь: ${item.relativePath}")

        if (target.isFile && verify(target, item.sha1)) {
            return@withContext DownloadOutcome.Done(item, target, fromCache = true)
        }

        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            return@withContext DownloadOutcome.Failed(item, "Не удалось создать каталог")
        }

        val part = File(target.parentFile, target.name + ".part")
        try {
            val limit = minOf(
                if (item.size > 0) item.size + sizeSlackBytes else hardCapBytes,
                hardCapBytes,
            )
            val actualSha1 = downloadTo(item.url, part, limit)
            if (!Hashing.constantTimeEqualsHex(item.sha1, actualSha1)) {
                part.delete()
                return@withContext DownloadOutcome.Failed(
                    item,
                    "SHA-1 не совпал (ожидали ${item.sha1.take(8)}…, получили ${actualSha1.take(8)}…)",
                )
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return@withContext DownloadOutcome.Failed(item, "Не удалось заменить существующий файл")
            }
            if (!part.renameTo(target)) {
                part.delete()
                return@withContext DownloadOutcome.Failed(item, "Не удалось переместить файл на место")
            }
            DownloadOutcome.Done(item, target, fromCache = false)
        } catch (e: CancellationException) {
            // Отмена — не ошибка загрузки. Проглотить её здесь значило бы
            // превратить закрытие экрана в «не удалось скачать файл» и
            // продолжить качать остальные.
            part.delete()
            throw e
        } catch (e: Exception) {
            part.delete()
            DownloadOutcome.Failed(item, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Качает и одновременно считает SHA-1: второй проход по файлу не нужен. */
    @Throws(IOException::class)
    private fun downloadTo(url: String, target: File, maxBytes: Long): String {
        val (conn, input) = SecureHttp.openStream(url, policy)
        try {
            val digest = MessageDigest.getInstance(Hashing.SHA1)
            var total = 0L
            val buf = ByteArray(64 * 1024)
            target.outputStream().use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) throw IOException("Файл больше ожидаемого размера")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            return Hashing.hex(digest.digest())
        } finally {
            try {
                input.close()
            } catch (_: IOException) {
                // соединение всё равно закрывается ниже
            }
            conn.disconnect()
        }
    }

    private fun verify(file: File, expectedSha1: String): Boolean = try {
        file.inputStream().use { Hashing.constantTimeEqualsHex(expectedSha1, Hashing.sha1Stream(it)) }
    } catch (_: IOException) {
        false
    }
}
