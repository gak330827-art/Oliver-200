/*
 * ══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · Minecraft Launcher for Android
 *  Файл       : ui/IntroLogoView.kt
 *  Назначение : рисование знака Animal Company · RU Steam Community
 *               и его сборка на экране кадр за кадром.
 *
 *  Экран белый целиком: знак живёт не на диске-аватаре, а на всём листе —
 *  так, как он выглядит в оригинале, чёрным по белому. Диск был рамкой
 *  внутри рамки и мельчил знак; лист даёт ему всю ширину экрана.
 *
 *  Знак собран пропорциями, а не картинкой: PNG на большом экране мылится,
 *  а тут любая плотность — от 1x до 4x — даёт одинаково резкие грани.
 *  Пропорции сняты с оригинала (ширина рамки = 1):
 *
 *      ┌──────────────────────────────┐  ← перо 0.036 высоты рамки,
 *      │  A N I M A L                 │    поле 0.115, стороны 1.736:1
 *      │  C O M P A N Y               │  ← обе строки набраны в ширину поля
 *      │  ▐RU▌ STEAM COMMUNITY        │
 *      └──────────────────────────────┘
 *
 *  Что делает View: НИЧЕГО не решает. Ни одного своего таймера, ни одной
 *  своей секунды — весь ритм приходит готовым кадром из :core, а здесь
 *  только геометрия и краски. Поэтому анимацию можно проверить тестами
 *  без телефона, а этот файл остаётся тупым и предсказуемым.
 *
 *  Производительность: все Paint, Path и шейдеры создаются в onSizeChanged,
 *  в onDraw аллокаций нет (кроме пунктира на время обводки рамки).
 *  Ширины букв считаются один раз на размер — measureText на каждый кадр
 *  для 13 букв это лишние 780 вызовов в секунду.
 *
 *  Подпись    : OLIVER-200 · см. SIGNATURES.txt
 * ══════════════════════════════════════════════════════════════════════════
 */
package com.oliver200.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.oliver200.launcher.R
import com.oliver200.launcher.core.intro.IntroBrand
import com.oliver200.launcher.core.intro.IntroFrame
import com.oliver200.launcher.core.intro.IntroScript

class IntroLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /* ─────────────────────────── Пропорции знака ─────────────────────────── */

    private companion object {
        /** Ширина знака в долях ширины экрана. */
        const val MARK_W_PER_W = 0.72f
        /** Потолок ширины знака по высоте экрана — на случай альбомной ориентации. */
        const val MARK_W_PER_H = 0.42f
        /** Отношение сторон рамки (573 / 330 у оригинала). */
        const val FRAME_ASPECT = 1.736f
        /** Толщина пера рамки в высотах рамки. */
        const val STROKE_PER_H = 0.036f
        /** Внутреннее поле в высотах рамки. */
        const val PAD_PER_H = 0.115f
        /** Высота нижней строки в высотах рамки. */
        const val ROW_PER_H = 0.135f
        /** Просветы между строками. */
        const val GAP_TITLE_PER_H = 0.020f
        const val GAP_ROW_PER_H = 0.030f
        /** Ширина светового блика в долях ширины знака. */
        const val SHINE_W_PER_MARK = 0.55f
        /** Виньетка листа: радиус в долях большей стороны экрана. */
        const val PAPER_VIGNETTE_R = 0.75f
        /** Доля прогресса, за которую проявляется одна буква. */
        const val LETTER_WINDOW = 0.34f
    }

    /* ───────────────────────────── Состояние ───────────────────────────── */

    private var frame: IntroFrame = IntroScript.frameAt(0L)

    private var lineTop = ""
    private var lineBottom = ""
    private var badge = ""
    private var platform = ""

    private val inkColor = context.getColor(R.color.intro_ink)
    private val paperColor = context.getColor(R.color.intro_paper)
    private val paperEdgeColor = context.getColor(R.color.intro_paper_edge)

    /* ─────────────────────────────── Краски ─────────────────────────────── */

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint().apply { color = Color.BLACK }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
        color = inkColor
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        letterSpacing = -0.02f
        color = inkColor
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        letterSpacing = 0f
        color = inkColor
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        color = paperColor
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /* ───────────────────────────── Геометрия ───────────────────────────── */

    private var centerX = 0f
    private var centerY = 0f

    private val frameRect = RectF()
    private val framePath = Path()
    private var framePathLength = 0f

    private var baselineTop = 0f
    private var baselineBottom = 0f
    private var titleSizeTop = 0f
    private var titleSizeBottom = 0f
    private var widthsTop: FloatArray = FloatArray(0)
    private var widthsBottom: FloatArray = FloatArray(0)
    private var startXTop = 0f
    private var startXBottom = 0f

    private val badgeRect = RectF()
    private var badgeBaseline = 0f
    private var badgeTextX = 0f
    private var platformX = 0f
    private var platformBaseline = 0f

    private val bounds = Rect()
    private val shineMatrix = Matrix()
    private var shineShader: LinearGradient? = null
    private var shineWidth = 0f

    private var ready = false

    init {
        // Полупрозрачные слои поверх друг друга — своего слоя View не просит.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = false
        isFocusable = false
    }

    /* ───────────────────────────── Публичное ───────────────────────────── */

    /** Тексты знака. Приходят из шифрованного контейнера, см. SealedIntroBrand. */
    fun setBrand(brand: IntroBrand) {
        lineTop = brand.wordmarkTop
        lineBottom = brand.wordmarkBottom
        badge = brand.badge
        platform = brand.platform
        if (width > 0 && height > 0) layoutMark(width, height)
        invalidate()
    }

    /** Единственный вход анимации: готовый кадр из :core. */
    fun setFrame(value: IntroFrame) {
        frame = value
        invalidate()
    }

    /* ───────────────────────────── Разметка ───────────────────────────── */

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutMark(w, h)
    }

    private fun layoutMark(w: Int, h: Int) {
        ready = false
        if (w <= 0 || h <= 0) return
        if (lineTop.isEmpty() || lineBottom.isEmpty() || badge.isEmpty() || platform.isEmpty()) return

        centerX = w / 2f
        centerY = h * 0.42f

        val frameW = minOf(w * MARK_W_PER_W, h * MARK_W_PER_H)
        if (frameW <= 0f) return
        val frameH = frameW / FRAME_ASPECT
        frameRect.set(
            centerX - frameW / 2f,
            centerY - frameH / 2f,
            centerX + frameW / 2f,
            centerY + frameH / 2f,
        )

        val stroke = frameH * STROKE_PER_H
        framePaint.strokeWidth = stroke
        framePath.reset()
        // Обводка начинается с левого верхнего угла и идёт по часовой стрелке —
        // так «перо» рисует рамку в привычную сторону.
        framePath.addRect(frameRect, Path.Direction.CW)
        framePathLength = PathMeasure(framePath, false).length

        val pad = frameH * PAD_PER_H
        val innerLeft = frameRect.left + stroke / 2f + pad
        val innerRight = frameRect.right - stroke / 2f - pad
        val innerTop = frameRect.top + stroke / 2f + pad
        val innerBottom = frameRect.bottom - stroke / 2f - pad
        val innerWidth = innerRight - innerLeft
        val innerHeight = innerBottom - innerTop
        if (innerWidth <= 0f || innerHeight <= 0f) return

        // ── Замер. Обе строки вордмарка набираются ровно в ширину поля,
        //    как в оригинальном знаке.
        var sizeTop = fitTextSize(titlePaint, lineTop, innerWidth)
        var sizeBottom = fitTextSize(titlePaint, lineBottom, innerWidth)
        var rowHeight = frameH * ROW_PER_H
        var gapTitle = frameH * GAP_TITLE_PER_H
        var gapRow = frameH * GAP_ROW_PER_H

        titlePaint.textSize = sizeTop
        titlePaint.getTextBounds(lineTop, 0, lineTop.length, bounds)
        var heightTop = bounds.height().toFloat()
        titlePaint.textSize = sizeBottom
        titlePaint.getTextBounds(lineBottom, 0, lineBottom.length, bounds)
        var heightBottom = bounds.height().toFloat()

        // ── Подгонка по высоте. Пропорции сняты с оригинала и в норме
        //    сходятся, но длина строк приходит из контейнера: слово длиннее
        //    «COMPANY» даёт более крупный кегль, и набор полез бы за рамку.
        //    Сжимаем весь блок целиком — так рамка остаётся неприкосновенной.
        val measured = heightTop + gapTitle + heightBottom + gapRow + rowHeight
        if (measured > innerHeight && measured > 0f) {
            val squeeze = innerHeight / measured
            sizeTop *= squeeze
            sizeBottom *= squeeze
            heightTop *= squeeze
            heightBottom *= squeeze
            gapTitle *= squeeze
            gapRow *= squeeze
            rowHeight *= squeeze
        }

        titleSizeTop = sizeTop
        titleSizeBottom = sizeBottom

        titlePaint.textSize = titleSizeTop
        widthsTop = charWidths(titlePaint, lineTop)
        startXTop = centerX - sumOf(widthsTop) / 2f

        titlePaint.textSize = titleSizeBottom
        widthsBottom = charWidths(titlePaint, lineBottom)
        startXBottom = centerX - sumOf(widthsBottom) / 2f

        val stackHeight = heightTop + gapTitle + heightBottom + gapRow + rowHeight
        val stackTop = centerY - stackHeight / 2f
        baselineTop = stackTop + heightTop
        baselineBottom = baselineTop + gapTitle + heightBottom

        val rowTop = baselineBottom + gapRow
        val rowBottom = rowTop + rowHeight

        // Плашка «RU»: белые буквы в чёрном прямоугольнике.
        badgePaint.textSize = rowHeight * 0.68f
        val badgePad = rowHeight * 0.22f
        val badgeTextWidth = badgePaint.measureText(badge)
        badgeRect.set(innerLeft, rowTop, innerLeft + badgeTextWidth + badgePad * 2f, rowBottom)
        badgePaint.getTextBounds(badge, 0, badge.length, bounds)
        badgeBaseline = rowTop + rowHeight / 2f + bounds.height() / 2f
        badgeTextX = badgeRect.left + badgePad

        // «STEAM COMMUNITY» занимает всё, что осталось справа от плашки.
        val gapAfterBadge = rowHeight * 0.28f
        platformX = badgeRect.right + gapAfterBadge
        val platformWidth = innerRight - platformX
        if (platformWidth <= 0f) return
        rowPaint.textSize = fitTextSize(rowPaint, platform, platformWidth)
        rowPaint.getTextBounds(platform, 0, platform.length, bounds)
        platformBaseline = rowTop + rowHeight / 2f + bounds.height() / 2f

        // Лист: почти белый в центре, чуть темнее к краям — плоская заливка
        // на телефоне выглядит как выключённый экран с включённой подсветкой.
        paperPaint.shader = RadialGradient(
            centerX,
            centerY,
            maxOf(w, h) * PAPER_VIGNETTE_R,
            paperColor,
            paperEdgeColor,
            Shader.TileMode.CLAMP,
        )

        shineWidth = frameW * SHINE_W_PER_MARK
        val shine = LinearGradient(
            0f,
            0f,
            shineWidth,
            0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        shineShader = shine
        shinePaint.shader = shine

        ready = true
    }

    /** Размер шрифта, при котором строка занимает ровно [targetWidth]. */
    private fun fitTextSize(paint: Paint, text: String, targetWidth: Float): Float {
        if (text.isEmpty() || targetWidth <= 0f) return 1f
        paint.textSize = 100f
        val measured = paint.measureText(text)
        if (measured <= 0f) return 1f
        return 100f * targetWidth / measured
    }

    /**
     * Ширины отдельных букв. Позиционирование идёт по ним же, поэтому
     * сумма ширин и есть ширина строки: расхождения с measureText целой
     * строки (кернинг) не сдвигают набор.
     */
    private fun charWidths(paint: Paint, text: String): FloatArray {
        val out = FloatArray(text.length)
        for (i in text.indices) out[i] = paint.measureText(text, i, i + 1)
        return out
    }

    private fun sumOf(values: FloatArray): Float {
        var sum = 0f
        for (v in values) sum += v
        return sum
    }

    /* ───────────────────────────── Отрисовка ───────────────────────────── */

    override fun onDraw(canvas: Canvas) {
        val f = frame
        val master = f.master
        if (!ready) return

        // Лист. Гаснет вместе со сценой, поэтому уход в тёмный интерфейс
        // приложения идёт через чёрную вуаль ниже, а не рывком.
        paperPaint.alpha = alpha255(f.backdrop * master)
        canvas.drawPaint(paperPaint)

        val markAlpha = f.markAlpha * master
        if (markAlpha > 0f) {
            val save = canvas.save()
            canvas.scale(f.markScale, f.markScale, centerX, centerY)

            drawFrameStroke(canvas, markAlpha, f.frameDraw)
            drawWordmark(canvas, markAlpha, f.titleReveal)
            drawBottomRow(canvas, markAlpha * f.rowAlpha, f.rowRise)
            drawShine(canvas, markAlpha, f.shine)

            canvas.restoreToCount(save)
        }

        // Вуаль: белый лист уходит в чёрный ровно к концу ролика, и главный
        // экран открывается уже на своём тёмном фоне.
        val veil = 1f - master
        if (veil > 0f) {
            veilPaint.alpha = alpha255(veil)
            canvas.drawPaint(veilPaint)
        }
    }

    /** Рамка обводится пером: пунктир длиной в пройденную часть периметра. */
    private fun drawFrameStroke(canvas: Canvas, alpha: Float, progress: Float) {
        if (progress <= 0f) return
        framePaint.alpha = alpha255(alpha)
        framePaint.pathEffect = if (progress >= 1f) {
            null
        } else {
            DashPathEffect(floatArrayOf(framePathLength * progress, framePathLength), 0f)
        }
        canvas.drawPath(framePath, framePaint)
    }

    /** Буквы набираются по очереди — слева направо, сверху вниз. */
    private fun drawWordmark(canvas: Canvas, alpha: Float, reveal: Float) {
        if (reveal <= 0f) return
        val total = lineTop.length + lineBottom.length
        if (total == 0) return

        titlePaint.textSize = titleSizeTop
        drawLetters(canvas, lineTop, widthsTop, startXTop, baselineTop, alpha, reveal, 0, total)

        titlePaint.textSize = titleSizeBottom
        drawLetters(
            canvas, lineBottom, widthsBottom, startXBottom, baselineBottom,
            alpha, reveal, lineTop.length, total,
        )
    }

    private fun drawLetters(
        canvas: Canvas,
        text: String,
        widths: FloatArray,
        startX: Float,
        baseline: Float,
        alpha: Float,
        reveal: Float,
        indexOffset: Int,
        total: Int,
    ) {
        if (widths.size != text.length) return
        var x = startX
        for (i in text.indices) {
            // Последняя буква стартует за LETTER_WINDOW до конца прогресса,
            // поэтому набор заканчивается ровно вместе с titleReveal.
            val start = (indexOffset + i).toFloat() / total * (1f - LETTER_WINDOW)
            val local = ((reveal - start) / LETTER_WINDOW).coerceIn(0f, 1f)
            if (local > 0f) {
                titlePaint.alpha = alpha255(alpha * local)
                canvas.drawText(text, i, i + 1, x, baseline, titlePaint)
            }
            x += widths[i]
        }
    }

    /** Нижняя строка целиком: плашка «RU» и текст платформы. */
    private fun drawBottomRow(canvas: Canvas, alpha: Float, rise: Float) {
        if (alpha <= 0f) return
        val shift = rise * badgeRect.height() * 0.8f
        val save = canvas.save()
        canvas.translate(0f, shift)

        boxPaint.alpha = alpha255(alpha)
        canvas.drawRect(badgeRect, boxPaint)

        badgePaint.alpha = alpha255(alpha)
        canvas.drawText(badge, badgeTextX, badgeBaseline, badgePaint)

        rowPaint.alpha = alpha255(alpha)
        canvas.drawText(platform, platformX, platformBaseline, rowPaint)

        canvas.restoreToCount(save)
    }

    /**
     * Блик проходит по знаку слева направо. На белом листе его не видно —
     * он и нужен только на чёрных буквах и рамке, чтобы знак «блеснул».
     */
    private fun drawShine(canvas: Canvas, alpha: Float, progress: Float) {
        if (progress <= 0f || progress >= 1f) return
        val shader = shineShader ?: return
        val travel = frameRect.width() + shineWidth
        val start = frameRect.left - shineWidth + travel * progress
        shineMatrix.setTranslate(start, 0f)
        shader.setLocalMatrix(shineMatrix)
        shinePaint.alpha = alpha255(alpha)
        canvas.drawRect(frameRect, shinePaint)
    }

    private fun alpha255(value: Float): Int {
        val v = value * 255f
        return when {
            v.isNaN() -> 0
            v <= 0f -> 0
            v >= 255f -> 255
            else -> v.toInt()
        }
    }
}
