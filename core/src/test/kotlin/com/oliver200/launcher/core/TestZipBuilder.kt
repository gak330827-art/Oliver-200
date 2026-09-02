/*
 * Oliver-200 · вспомогательный конструктор ZIP для тестов безопасности.
 * Подпись: OLIVER-200 · см. SIGNATURES.txt
 */
package com.oliver200.launcher.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TestZipBuilder {

    fun build(target: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(target.outputStream().buffered()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return target
    }

    fun text(name: String, content: String): Pair<String, ByteArray> = name to content.toByteArray()

    /** Пак шейдеров, который должен успешно пройти проверку. */
    fun goodShaderPack(target: File, root: String = "SuperShaders-1.0"): File = build(
        target,
        listOf(
            text("$root/shaders/shaders.properties", "profile.LOW=off\n"),
            text("$root/shaders/gbuffers_basic.vsh", "void main() { gl_Position = ftransform(); }"),
            text("$root/shaders/gbuffers_basic.fsh", "void main() { gl_FragColor = vec4(1.0); }"),
            text("$root/README.md", "Тестовый пак"),
        ),
    )
}
