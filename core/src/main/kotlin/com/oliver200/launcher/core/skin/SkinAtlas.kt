/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : core/skin/SkinAtlas.kt
 *  Назначение : геометрия скина. Скин — это атлас 64×64 (или 64×32 у старых),
 *               где каждая грань каждой части тела лежит в своём прямоугольнике.
 *               Здесь чистая математика раскладки: никакого Android.Bitmap,
 *               поэтому она проверяется юнит-тестами на обычной JVM.
 *
 *  Логика 3D-модели (это же — раскладка развёртки в Blender/Unity):
 *      · голова      8×8×8   пикселей, шляпа — второй слой поверх;
 *      · корпус      8×12×4, куртка — второй слой;
 *      · руки        4×12×4 (classic) или 3×12×4 (slim);
 *      · ноги        4×12×4.
 *      На атласе 64×32 левых конечностей нет — они зеркалят правые.
 *      Второй слой (overlay) появился только на 64×64.
 *
 *  Вариант    : БЕЗ ШИФРОВАНИЯ
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.core.skin

/** Прямоугольник в координатах атласа, уже умноженный на масштаб текстуры. */
data class SkinRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun scaled(factor: Int) = SkinRect(x * factor, y * factor, width * factor, height * factor)
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

enum class SkinPart { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

enum class SkinFace { FRONT, BACK, RIGHT, LEFT, TOP, BOTTOM }

/**
 * Раскладка конкретного скина.
 * @param scale 1 для 64×64, 2 для 128×128 и т.д.
 * @param legacy true для старого формата 64×32 (без левых конечностей и overlay).
 */
data class SkinLayout(val scale: Int, val legacy: Boolean, val model: SkinModel) {

    val armWidth: Int get() = if (model == SkinModel.SLIM) 3 else 4

    /** Базовый (первый) слой — сама кожа. */
    fun base(part: SkinPart, face: SkinFace = SkinFace.FRONT): SkinRect =
        rectFor(part, face, overlay = false).scaled(scale)

    /**
     * Второй слой: шляпа, куртка, рукава. На 64×32 существует только шляпа.
     * null означает «для этого скина такого слоя нет».
     */
    fun overlay(part: SkinPart, face: SkinFace = SkinFace.FRONT): SkinRect? {
        if (legacy && part != SkinPart.HEAD) return null
        return rectFor(part, face, overlay = true).scaled(scale)
    }

    /** Квадрат лица — то, что показывается как аватар в списке аккаунтов. */
    fun headFront(): SkinRect = base(SkinPart.HEAD, SkinFace.FRONT)

    fun hatFront(): SkinRect? = overlay(SkinPart.HEAD, SkinFace.FRONT)

    private fun rectFor(part: SkinPart, face: SkinFace, overlay: Boolean): SkinRect {
        val aw = armWidth
        return when (part) {
            SkinPart.HEAD -> cube(if (overlay) 32 else 0, 0, 8, 8, 8, face)
            SkinPart.BODY -> cube(16, if (overlay) 32 else 16, 8, 12, 4, face)
            SkinPart.RIGHT_ARM -> cube(40, if (overlay) 32 else 16, aw, 12, 4, face)
            SkinPart.RIGHT_LEG -> cube(0, if (overlay) 32 else 16, 4, 12, 4, face)
            // На 64×32 левой руки/ноги в атласе нет: используем правую как зеркало.
            SkinPart.LEFT_ARM ->
                if (legacy) cube(40, 16, aw, 12, 4, mirror(face))
                else cube(if (overlay) 48 else 32, 48, aw, 12, 4, face)
            SkinPart.LEFT_LEG ->
                if (legacy) cube(0, 16, 4, 12, 4, mirror(face))
                else cube(if (overlay) 0 else 16, 48, 4, 12, 4, face)
        }
    }

    /**
     * Развёртка куба в атласе Minecraft (u,v — левый верхний угол блока):
     *
     *        [top][bottom]
     *  [right][front][left][back]
     *
     * Ширины по горизонтали: depth, width, depth, width.
     */
    private fun cube(u: Int, v: Int, width: Int, height: Int, depth: Int, face: SkinFace): SkinRect =
        when (face) {
            SkinFace.TOP -> SkinRect(u + depth, v, width, depth)
            SkinFace.BOTTOM -> SkinRect(u + depth + width, v, width, depth)
            SkinFace.RIGHT -> SkinRect(u, v + depth, depth, height)
            SkinFace.FRONT -> SkinRect(u + depth, v + depth, width, height)
            SkinFace.LEFT -> SkinRect(u + depth + width, v + depth, depth, height)
            SkinFace.BACK -> SkinRect(u + depth * 2 + width, v + depth, width, height)
        }

    private fun mirror(face: SkinFace): SkinFace = when (face) {
        SkinFace.LEFT -> SkinFace.RIGHT
        SkinFace.RIGHT -> SkinFace.LEFT
        else -> face
    }
}

object SkinAtlas {

    const val BASE_WIDTH = 64
    const val BASE_HEIGHT = 64
    const val LEGACY_HEIGHT = 32

    /** Максимальный масштаб: 1024×1024 — заведомо больше всего, что встречается. */
    const val MAX_SCALE = 16

    /**
     * Определяет раскладку по размеру картинки.
     * null — картинка не является скином Minecraft; показывать её как скин
     * нельзя, иначе UI начнёт вырезать координаты за пределами изображения.
     */
    fun layoutFor(width: Int, height: Int, model: SkinModel = SkinModel.CLASSIC): SkinLayout? {
        if (width <= 0 || height <= 0) return null
        if (width % BASE_WIDTH != 0) return null
        val scale = width / BASE_WIDTH
        if (scale < 1 || scale > MAX_SCALE) return null
        return when (height) {
            BASE_HEIGHT * scale -> SkinLayout(scale, legacy = false, model = model)
            LEGACY_HEIGHT * scale -> SkinLayout(scale, legacy = true, model = model)
            else -> null
        }
    }

    /**
     * Эвристика модели, когда сервер не прислал metadata.model.
     * У slim-скина правая часть колонки руки (x=54..55 на 64×64) полностью
     * прозрачная — именно этим он и отличается от classic.
     * @param alphaAt функция доступа к альфа-каналу пикселя (0 — прозрачно).
     */
    fun guessModel(layout: SkinLayout, alphaAt: (x: Int, y: Int) -> Int): SkinModel {
        if (layout.legacy) return SkinModel.CLASSIC
        val s = layout.scale
        // Колонка x = 54..55 в масштабе 1 — «лишние» пиксели classic-руки.
        for (x in 54 until 56) {
            for (y in 20 until 32) {
                if (alphaAt(x * s, y * s) != 0) return SkinModel.CLASSIC
            }
        }
        return SkinModel.SLIM
    }

    /** Порядок отрисовки фигуры целиком: сначала кожа, потом второй слой. */
    val BODY_DRAW_ORDER: List<SkinPart> = listOf(
        SkinPart.LEFT_LEG,
        SkinPart.RIGHT_LEG,
        SkinPart.BODY,
        SkinPart.LEFT_ARM,
        SkinPart.RIGHT_ARM,
        SkinPart.HEAD,
    )
}
