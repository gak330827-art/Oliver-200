/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/io/SafeZip.kt
 *  Назначение : разбор и распаковка ZIP (паки шейдеров, ресурспаки, natives).
 *  Безопасность: три классические дыры закрыты здесь и только здесь —
 *               1) Zip Slip: имя записи "../../../shared_prefs/x.xml";
 *               2) Zip-бомба: 42 КБ распаковываются в 4.5 ПБ;
 *               3) исполняемый код в паке ресурсов (.so/.dex/.jar) —
 *                  шейдеру нативная библиотека не нужна никогда.
 *               Заявленный в заголовке размер записи НЕ является правдой,
 *               поэтому лимит проверяется ещё и на лету при копировании.
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.io

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Пределы распаковки. Значения по умолчанию рассчитаны на крупные паки шейдеров. */
data class ZipLimits(
    val maxEntries: Int = 20_000,
    val maxTotalUncompressedBytes: Long = 512L * 1024 * 1024,
    val maxSingleEntryBytes: Long = 128L * 1024 * 1024,
    val maxCompressionRatio: Int = 300,
    val maxPathLength: Int = SafePath.MAX_RELATIVE_LENGTH,
    val allowExecutableEntries: Boolean = false,
) {
    companion object {
        val SHADER_PACK = ZipLimits()
        val NATIVES = ZipLimits(
            maxEntries = 4_000,
            maxTotalUncompressedBytes = 256L * 1024 * 1024,
            allowExecutableEntries = true, // natives — это как раз .so, им можно
        )
    }
}

class ZipSecurityException(message: String) : IOException(message)

/** Одна запись архива после проверки. */
data class SafeZipEntry(
    val rawName: String,
    val normalizedPath: String,
    val isDirectory: Boolean,
    val declaredSize: Long,
    val compressedSize: Long,
)

data class ZipInspection(
    val entries: List<SafeZipEntry>,
    val totalDeclaredSize: Long,
) {
    fun files(): List<SafeZipEntry> = entries.filter { !it.isDirectory }

    /**
     * Общий каталог верхнего уровня, если весь архив упакован в одну папку.
     * Нужен, чтобы «MyShader-1.0/shaders/...» разложить как «shaders/...».
     */
    fun commonRootDirectory(): String? {
        val tops = entries.mapNotNull { it.normalizedPath.substringBefore('/').takeIf { t -> t.isNotEmpty() } }
            .toSet()
        if (tops.size != 1) return null
        val top = tops.first()
        val hasNested = entries.any { it.normalizedPath.contains('/') }
        return if (hasNested) top else null
    }
}

data class ExtractResult(
    val filesWritten: Int,
    val bytesWritten: Long,
    val destination: File,
)

object SafeZip {

    /** Проверяет архив целиком, ничего не записывая на диск. */
    @Throws(IOException::class)
    fun inspect(zip: File, limits: ZipLimits = ZipLimits.SHADER_PACK): ZipInspection {
        ZipFile(zip).use { archive ->
            val result = ArrayList<SafeZipEntry>()
            var total = 0L
            var count = 0
            val seen = HashSet<String>()
            val it = archive.entries()
            while (it.hasMoreElements()) {
                val entry = it.nextElement()
                count++
                if (count > limits.maxEntries) {
                    throw ZipSecurityException("В архиве больше ${limits.maxEntries} записей")
                }
                val safe = validateEntry(entry, limits)
                if (!seen.add(safe.normalizedPath)) {
                    // Две записи с одним путём — приём «последняя перезаписывает
                    // проверенную первую». Не разбираемся, кто прав, — отказ.
                    throw ZipSecurityException("Дубликат пути в архиве: ${safe.normalizedPath}")
                }
                if (!safe.isDirectory) {
                    total += safe.declaredSize.coerceAtLeast(0)
                    if (total > limits.maxTotalUncompressedBytes) {
                        throw ZipSecurityException("Суммарный размер распаковки превышает лимит")
                    }
                }
                result.add(safe)
            }
            if (result.isEmpty()) throw ZipSecurityException("Архив пуст")
            return ZipInspection(result, total)
        }
    }

    private fun validateEntry(entry: ZipEntry, limits: ZipLimits): SafeZipEntry {
        val raw = entry.name
        if (raw.length > limits.maxPathLength) {
            throw ZipSecurityException("Слишком длинный путь в архиве")
        }
        val isDir = entry.isDirectory || raw.endsWith("/") || raw.endsWith("\\")
        val cleaned = raw.trimEnd('/', '\\')
        val normalized = SafePath.normalizeRelative(cleaned)
            ?: throw ZipSecurityException("Небезопасное имя записи: $raw")

        val declared = entry.size
        val compressed = entry.compressedSize

        if (!isDir) {
            if (declared > limits.maxSingleEntryBytes) {
                throw ZipSecurityException("Запись $normalized больше ${limits.maxSingleEntryBytes} байт")
            }
            // Коэффициент сжатия ловит бомбу ещё до распаковки, когда заголовок честный.
            if (declared > 0 && compressed > 0) {
                val ratio = declared / compressed
                if (ratio > limits.maxCompressionRatio) {
                    throw ZipSecurityException("Подозрительная степень сжатия ($ratio:1) у $normalized")
                }
            }
            if (!limits.allowExecutableEntries && SafePath.isExecutableName(normalized)) {
                throw ZipSecurityException("Исполняемый файл внутри пака: $normalized")
            }
        }
        return SafeZipEntry(raw, normalized, isDir, declared, compressed)
    }

    /**
     * Распаковка в [destRoot]. [stripRoot] — префикс каталога, который нужно
     * срезать. [accept] — дополнительный фильтр по нормализованному пути.
     */
    @Throws(IOException::class)
    fun extract(
        zip: File,
        destRoot: File,
        limits: ZipLimits = ZipLimits.SHADER_PACK,
        stripRoot: String? = null,
        accept: (String) -> Boolean = { true },
    ): ExtractResult {
        val inspection = inspect(zip, limits)
        if (!destRoot.exists() && !destRoot.mkdirs()) {
            throw IOException("Не удалось создать каталог ${destRoot.absolutePath}")
        }
        val prefix = stripRoot?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" }

        var written = 0
        var bytes = 0L

        ZipFile(zip).use { archive ->
            for (safe in inspection.entries) {
                val relative = when {
                    prefix == null -> safe.normalizedPath
                    safe.normalizedPath == prefix.trimEnd('/') -> continue
                    safe.normalizedPath.startsWith(prefix) -> safe.normalizedPath.removePrefix(prefix)
                    else -> continue
                }
                if (relative.isEmpty()) continue
                if (!safe.isDirectory && !accept(relative)) continue

                val target = SafePath.resolveInside(destRoot, relative)
                    ?: throw ZipSecurityException("Путь вырывается из каталога: $relative")

                if (safe.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) {
                        throw IOException("Не удалось создать ${target.absolutePath}")
                    }
                    continue
                }
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Не удалось создать ${parent.absolutePath}")
                }
                val entry = archive.getEntry(safe.rawName)
                    ?: throw ZipSecurityException("Запись исчезла из архива: ${safe.rawName}")

                val remaining = limits.maxTotalUncompressedBytes - bytes
                val copied = archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        copyBounded(input, output, minOf(limits.maxSingleEntryBytes, remaining))
                    }
                }
                bytes += copied
                written++
            }
        }
        return ExtractResult(written, bytes, destRoot)
    }

    /**
     * Копирование с жёстким потолком. Заголовок ZIP может врать о размере,
     * поэтому останавливаемся по фактически прочитанным байтам.
     */
    @Throws(IOException::class)
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        if (maxBytes <= 0) throw ZipSecurityException("Исчерпан лимит распаковки")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw ZipSecurityException("Запись превысила лимит распаковки ($maxBytes байт)")
            }
            output.write(buf, 0, n)
        }
        return total
    }
}
