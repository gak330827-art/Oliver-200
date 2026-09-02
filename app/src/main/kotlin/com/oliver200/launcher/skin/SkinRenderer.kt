/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : skin/SkinRenderer.kt
 *  Назначение : сборка фигуры игрока из атласа скина (вид спереди) и
 *               аватарки-головы для списка аккаунтов.
 *
 *  Геометрия (та же развёртка, что в Blender и в Unity при UV-маппинге
 *  персонажа Minecraft; координаты берутся из core/skin/SkinAtlas):
 *
 *        ширина 16 «пикселей скина», высота 32
 *        ┌────┬────────┬────┐  0
 *        │    │  ГОЛОВА │    │
 *        ├────┼────────┼────┤  8
 *        │рука│  КОРПУС │рука│
 *        ├────┼───┬────┼────┤  20
 *        │    │ног│ ног│    │
 *        └────┴───┴────┴────┘  32
 *
 *  Второй слой (шляпа, куртка, рукава) рисуется поверх базового —
 *  порядок важен, иначе капюшон окажется под волосами.
 *
 *  Масштабирование строго БЕЗ сглаживания: скин 64×64, любое размытие
 *  превращает пиксель-арт в кашу.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.oliver200.launcher.core.skin.SkinAtlas
import com.oliver200.launcher.core.skin.SkinFace
import com.oliver200.launcher.core.skin.SkinLayout
import com.oliver200.launcher.core.skin.SkinModel
import com.oliver200.launcher.core.skin.SkinPart
import com.oliver200.launcher.core.skin.SkinRect

object SkinRenderer {

    /** Размер холста в «пикселях скина». */
    private const val FIGURE_WIDTH = 16
    private const val FIGURE_HEIGHT = 32

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false // пиксель-арт: никакого сглаживания
        isDither = false
    }

    /**
     * @param skin исходный атлас (64×64 или 64×32).
     * @param model classic/slim; если null — определяется по прозрачности.
     * @param zoom во сколько раз увеличить (1 «пиксель скина» = zoom точек).
     * @return готовый вид спереди или null, если картинка не является скином.
     */
    fun renderFront(skin: Bitmap, model: SkinModel?, zoom: Int = 8): Bitmap? {
        val declared = model ?: SkinModel.CLASSIC
        val probe = SkinAtlas.layoutFor(skin.width, skin.height, declared) ?: return null
        val resolved = model ?: SkinAtlas.guessModel(probe) { x, y ->
            if (x < skin.width && y < skin.height) (skin.getPixel(x, y) ushr 24) and 0xFF else 0
        }
        val layout = SkinAtlas.layoutFor(skin.width, skin.height, resolved) ?: return null

        val z = zoom.coerceIn(1, 32)
        val out = Bitmap.createBitmap(FIGURE_WIDTH * z, FIGURE_HEIGHT * z, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val arm = layout.armWidth
        // Тонкая рука уже стандартной, поэтому прижимаем её к корпусу,
        // иначе между рукой и телом появится щель.
        val leftArmX = 12
        val rightArmX = 4 - arm

        val slots = listOf(
            Slot(SkinPart.RIGHT_LEG, 4, 20, 4, 12),
            Slot(SkinPart.LEFT_LEG, 8, 20, 4, 12),
            Slot(SkinPart.BODY, 4, 8, 8, 12),
            Slot(SkinPart.RIGHT_ARM, rightArmX, 8, arm, 12),
            Slot(SkinPart.LEFT_ARM, leftArmX, 8, arm, 12),
            Slot(SkinPart.HEAD, 4, 0, 8, 8),
        )

        // Проход 1 — кожа, проход 2 — второй слой поверх.
        for (slot in slots) {
            draw(canvas, skin, layout.base(slot.part, SkinFace.FRONT), slot, z)
        }
        for (slot in slots) {
            layout.overlay(slot.part, SkinFace.FRONT)?.let { draw(canvas, skin, it, slot, z) }
        }
        return out
    }

    /** Квадратная аватарка: лицо плюс шляпа. */
    fun renderHead(skin: Bitmap, zoom: Int = 8): Bitmap? {
        val layout = SkinAtlas.layoutFor(skin.width, skin.height) ?: return null
        val z = zoom.coerceIn(1, 32)
        val out = Bitmap.createBitmap(8 * z, 8 * z, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val slot = Slot(SkinPart.HEAD, 0, 0, 8, 8)
        draw(canvas, skin, layout.headFront(), slot, z)
        layout.hatFront()?.let { draw(canvas, skin, it, slot, z) }
        return out
    }

    private data class Slot(
        val part: SkinPart,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun draw(canvas: Canvas, skin: Bitmap, src: SkinRect, slot: Slot, zoom: Int) {
        // Страховка: даже если раскладка ошиблась, за пределы картинки не выйдем.
        if (src.x < 0 || src.y < 0 || src.right > skin.width || src.bottom > skin.height) return
        val source = Rect(src.x, src.y, src.right, src.bottom)
        val dest = Rect(
            slot.x * zoom,
            slot.y * zoom,
            (slot.x + slot.width) * zoom,
            (slot.y + slot.height) * zoom,
        )
        canvas.drawBitmap(skin, source, dest, paint)
    }

    /** Раскладка нужна и UI (подпись «модель»), поэтому отдаём наружу. */
    fun layoutOf(skin: Bitmap, model: SkinModel): SkinLayout? =
        SkinAtlas.layoutFor(skin.width, skin.height, model)
}
