// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * Tedit's outline icons, drawn as strokes on a 24-unit grid. They are drawn rather than taken from an icon set so
 * that all of them share one stroke weight and corner, which is what DESIGN.md asks of the editor's icons.
 */
class TIcon(private val glyph: Glyph, var color: Int, private val strokeUnits: Float = 1.6f) : Drawable() {
    enum class Glyph {
        MENU, FILES, SEARCH, BRANCH, RUN, BUG, EXTENSIONS, PROBLEMS, SETTINGS, MORE, MORE_H, CLOSE, ADD, FOLDER, FOLDER_OPEN,
        FILE, CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_UP, CHEVRON_LEFT, REFRESH, COLLAPSE, NEW_FILE, NEW_FOLDER, SPLIT_RIGHT, SPLIT_DOWN,
        TERMINAL, SAVE, UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL, REPLACE, CASE, WORD, REGEX, ARROW_UP, ARROW_DOWN,
        ARROW_LEFT, ARROW_RIGHT, TAB, CHECK, HISTORY, STOP, RESTART, WRAP, DOT, PIN, INFO, ERROR, WARNING, KEYBOARD,
        COMMAND, FILTER, SORT, TRASH, RENAME, IMPORT, EXPORT, ARCHIVE, COMMIT, DIFF, EYE, LOCK, IMAGE, KEY_HIDE, GOTO, LIST, PLUS_MINUS,
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val p = Path()
    private val r = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val s = size / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = strokeUnits
        paint.style = Paint.Style.STROKE
        p.reset()
        build()
        canvas.drawPath(p, paint)
        drawFilled(canvas)
        canvas.restore()
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) { p.moveTo(x1, y1); p.lineTo(x2, y2) }
    private fun poly(vararg xy: Float, close: Boolean = false) {
        p.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) p.close()
    }
    private fun rect(l: Float, t: Float, rr: Float, bb: Float, rad: Float = 2f) { r.set(l, t, rr, bb); p.addRoundRect(r, rad, rad, Path.Direction.CW) }
    private fun circle(x: Float, y: Float, rad: Float) = p.addCircle(x, y, rad, Path.Direction.CW)
    private fun arc(cx: Float, cy: Float, rad: Float, start: Float, sweep: Float) { r.set(cx - rad, cy - rad, cx + rad, cy + rad); p.addArc(r, start, sweep) }

    private fun build() {
        when (glyph) {
            Glyph.MENU -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f) }
            Glyph.FILES -> { poly(8f, 3f, 15f, 3f, 19f, 7f, 19f, 17f, 8f, 17f, close = true); poly(5f, 7f, 5f, 21f, 15f, 21f) }
            Glyph.SEARCH -> { circle(10.5f, 10.5f, 6f); line(15f, 15f, 20f, 20f) }
            Glyph.BRANCH -> { circle(7f, 5.5f, 2f); circle(7f, 18.5f, 2f); circle(17f, 8f, 2f); line(7f, 7.5f, 7f, 16.5f); p.moveTo(17f, 10f); p.cubicTo(17f, 14f, 9f, 13f, 7.6f, 16.6f) }
            Glyph.RUN -> poly(8f, 5f, 18f, 12f, 8f, 19f, close = true)
            Glyph.BUG -> { rect(7.5f, 8f, 16.5f, 20f, 4.5f); line(12f, 11f, 12f, 20f); line(4f, 13f, 7.5f, 13f); line(16.5f, 13f, 20f, 13f); line(4.5f, 18f, 7.8f, 16.8f); line(19.5f, 18f, 16.2f, 16.8f); line(9f, 8.5f, 7f, 5f); line(15f, 8.5f, 17f, 5f) }
            Glyph.EXTENSIONS -> { rect(4f, 9f, 10f, 15f, 1.5f); rect(10f, 15f, 16f, 21f, 1.5f); rect(4f, 15f, 10f, 21f, 1.5f); rect(13f, 4f, 19f, 10f, 1.5f) }
            Glyph.PROBLEMS, Glyph.WARNING -> { poly(12f, 4f, 21f, 19.5f, 3f, 19.5f, close = true); line(12f, 10f, 12f, 14f) }
            Glyph.SETTINGS -> {
                // A gear: eight flat teeth on a ring, with the axle hole.
                circle(12f, 12f, 3.2f)
                val teeth = 8
                for (k in 0 until teeth) {
                    val mid = Math.toRadians(k * 360.0 / teeth - 90)
                    val half = Math.toRadians(13.0)
                    val gap = Math.toRadians(360.0 / teeth / 2)
                    fun pt(angle: Double, rad: Float) = (12f + rad * Math.cos(angle).toFloat()) to (12f + rad * Math.sin(angle).toFloat())
                    val (x0, y0) = pt(mid - gap, 7.1f)
                    val (x1, y1) = pt(mid - half, 7.1f)
                    val (x2, y2) = pt(mid - half * 0.7, 9.4f)
                    val (x3, y3) = pt(mid + half * 0.7, 9.4f)
                    val (x4, y4) = pt(mid + half, 7.1f)
                    if (k == 0) p.moveTo(x0, y0) else p.lineTo(x0, y0)
                    p.lineTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.lineTo(x4, y4)
                }
                p.close()
            }
            Glyph.MORE, Glyph.MORE_H, Glyph.DOT -> {}
            Glyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.ADD -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.FOLDER -> poly(3f, 6f, 9f, 6f, 11f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f, close = true)
            Glyph.FOLDER_OPEN -> { poly(3f, 19f, 3f, 6f, 9f, 6f, 11f, 8.5f, 19f, 8.5f, 19f, 11f); poly(3f, 19f, 6f, 11f, 22f, 11f, 19f, 19f, close = true) }
            Glyph.FILE -> { poly(6f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 6f, 21f, close = true); poly(14f, 3f, 14f, 8f, 19f, 8f) }
            Glyph.CHEVRON_RIGHT -> poly(9f, 6f, 15f, 12f, 9f, 18f)
            Glyph.CHEVRON_DOWN -> poly(6f, 9f, 12f, 15f, 18f, 9f)
            Glyph.CHEVRON_UP -> poly(6f, 15f, 12f, 9f, 18f, 15f)
            Glyph.CHEVRON_LEFT -> poly(15f, 6f, 9f, 12f, 15f, 18f)
            Glyph.REFRESH -> { arc(12f, 12f, 7f, -60f, 300f); poly(16f, 3.5f, 16.5f, 6.4f, 13.6f, 7.2f) }
            Glyph.RESTART -> { arc(12f, 12.5f, 7f, -120f, 300f); poly(5.5f, 4.5f, 7.6f, 6.6f, 5f, 8.2f) }
            Glyph.COLLAPSE -> { rect(4f, 4f, 20f, 20f, 2f); line(8f, 12f, 16f, 12f) }
            Glyph.NEW_FILE -> { poly(13f, 3f, 6f, 3f, 6f, 21f, 12f, 21f); poly(13f, 3f, 18f, 8f, 18f, 12f); line(17f, 15f, 17f, 21f); line(14f, 18f, 20f, 18f) }
            Glyph.NEW_FOLDER -> { poly(13f, 19f, 3f, 19f, 3f, 6f, 9f, 6f, 11f, 8.5f, 21f, 8.5f, 21f, 12f); line(18f, 14f, 18f, 21f); line(14.5f, 17.5f, 21.5f, 17.5f) }
            Glyph.SPLIT_RIGHT -> { rect(3f, 4f, 21f, 20f, 2f); line(12f, 4f, 12f, 20f) }
            Glyph.SPLIT_DOWN -> { rect(3f, 4f, 21f, 20f, 2f); line(3f, 12f, 21f, 12f) }
            Glyph.TERMINAL -> { rect(3f, 4f, 21f, 20f, 2f); poly(7f, 9f, 10f, 12f, 7f, 15f); line(12f, 15.5f, 17f, 15.5f) }
            Glyph.SAVE -> { poly(4f, 4f, 16f, 4f, 20f, 8f, 20f, 20f, 4f, 20f, close = true); rect(8f, 13f, 16f, 20f, 1f); line(8f, 4f, 8f, 8f); line(8f, 8f, 14f, 8f) }
            Glyph.UNDO -> { poly(8f, 5f, 4f, 9f, 8f, 13f); p.moveTo(4f, 9f); p.lineTo(14f, 9f); p.cubicTo(18f, 9f, 20f, 11.5f, 20f, 14.5f); p.cubicTo(20f, 17.5f, 18f, 19f, 14f, 19f); p.lineTo(9f, 19f) }
            Glyph.REDO -> { poly(16f, 5f, 20f, 9f, 16f, 13f); p.moveTo(20f, 9f); p.lineTo(10f, 9f); p.cubicTo(6f, 9f, 4f, 11.5f, 4f, 14.5f); p.cubicTo(4f, 17.5f, 6f, 19f, 10f, 19f); p.lineTo(15f, 19f) }
            Glyph.CUT -> { circle(7f, 17f, 2.6f); circle(17f, 17f, 2.6f); line(8.8f, 15f, 17f, 4f); line(15.2f, 15f, 7f, 4f) }
            Glyph.COPY -> { rect(8f, 8f, 20f, 20f, 2f); poly(16f, 8f, 16f, 4f, 4f, 4f, 4f, 16f, 8f, 16f) }
            Glyph.PASTE -> { poly(9f, 5f, 6f, 5f, 6f, 21f, 18f, 21f, 18f, 5f, 15f, 5f); rect(9f, 3f, 15f, 7f, 1.5f) }
            Glyph.SELECT_ALL -> { rect(4f, 4f, 20f, 20f, 1f); rect(8f, 8f, 16f, 16f, 0.5f) }
            Glyph.REPLACE -> { poly(4f, 8f, 4f, 5f, 11f, 5f); poly(9f, 3f, 11f, 5f, 9f, 7f); rect(13f, 3f, 20f, 10f, 1.5f); rect(4f, 14f, 11f, 21f, 1.5f); poly(20f, 16f, 20f, 19f, 13f, 19f); poly(15f, 17f, 13f, 19f, 15f, 21f) }
            Glyph.CASE, Glyph.WORD, Glyph.REGEX -> {}
            Glyph.ARROW_UP -> { line(12f, 19f, 12f, 5f); poly(6f, 11f, 12f, 5f, 18f, 11f) }
            Glyph.ARROW_DOWN -> { line(12f, 5f, 12f, 19f); poly(6f, 13f, 12f, 19f, 18f, 13f) }
            Glyph.ARROW_LEFT -> { line(19f, 12f, 5f, 12f); poly(11f, 6f, 5f, 12f, 11f, 18f) }
            Glyph.ARROW_RIGHT -> { line(5f, 12f, 19f, 12f); poly(13f, 6f, 19f, 12f, 13f, 18f) }
            Glyph.TAB -> { line(4f, 12f, 17f, 12f); poly(12f, 7f, 17f, 12f, 12f, 17f); line(20f, 6f, 20f, 18f) }
            Glyph.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Glyph.HISTORY -> { arc(12.5f, 12f, 8f, 150f, 300f); poly(3f, 9f, 4.6f, 12.2f, 7.8f, 10.6f); poly(12.5f, 8f, 12.5f, 12f, 15.5f, 14f) }
            Glyph.STOP -> rect(6f, 6f, 18f, 18f, 2f)
            Glyph.WRAP -> { line(4f, 6f, 20f, 6f); p.moveTo(4f, 12f); p.lineTo(17f, 12f); p.cubicTo(19.5f, 12f, 20.5f, 13.5f, 20.5f, 15f); p.cubicTo(20.5f, 16.5f, 19.5f, 18f, 17f, 18f); p.lineTo(12f, 18f); poly(14f, 16f, 12f, 18f, 14f, 20f); line(4f, 18f, 8f, 18f) }
            Glyph.PIN -> { poly(9f, 3f, 15f, 3f, 14f, 10f, 17f, 14f, 7f, 14f, 10f, 10f, close = true); line(12f, 14f, 12f, 21f) }
            Glyph.INFO -> { circle(12f, 12f, 8.5f); line(12f, 11f, 12f, 16.5f) }
            Glyph.ERROR -> { circle(12f, 12f, 8.5f); line(9f, 9f, 15f, 15f); line(15f, 9f, 9f, 15f) }
            Glyph.KEYBOARD -> { rect(3f, 6f, 21f, 18f, 2f); line(8f, 14.5f, 16f, 14.5f) }
            Glyph.KEY_HIDE -> { rect(3f, 4f, 21f, 14f, 2f); line(8f, 11f, 16f, 11f); poly(9f, 18f, 12f, 21f, 15f, 18f) }
            Glyph.COMMAND -> { poly(5f, 7f, 10f, 12f, 5f, 17f); line(12f, 17f, 19f, 17f) }
            Glyph.FILTER -> poly(4f, 5f, 20f, 5f, 14f, 12.5f, 14f, 19f, 10f, 17f, 10f, 12.5f, close = true)
            Glyph.SORT -> { line(4f, 7f, 20f, 7f); line(4f, 12f, 15f, 12f); line(4f, 17f, 10f, 17f) }
            Glyph.TRASH -> { line(4f, 6.5f, 20f, 6.5f); poly(6f, 6.5f, 7f, 20f, 17f, 20f, 18f, 6.5f); poly(9f, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 15f, 6.5f); line(10f, 10f, 10f, 16.5f); line(14f, 10f, 14f, 16.5f) }
            Glyph.RENAME -> { poly(4f, 20f, 5f, 15.5f, 15.5f, 5f, 19f, 8.5f, 8.5f, 19f, close = true); line(13f, 7.5f, 16.5f, 11f) }
            Glyph.IMPORT -> { line(12f, 3f, 12f, 14f); poly(7.5f, 9.5f, 12f, 14f, 16.5f, 9.5f); poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
            Glyph.EXPORT -> { line(12f, 14f, 12f, 3f); poly(7.5f, 7.5f, 12f, 3f, 16.5f, 7.5f); poly(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f) }
            Glyph.ARCHIVE -> { rect(3f, 4f, 21f, 8.5f, 1f); poly(5f, 8.5f, 5f, 20f, 19f, 20f, 19f, 8.5f); line(10f, 12.5f, 14f, 12.5f) }
            Glyph.COMMIT -> { circle(12f, 12f, 3.5f); line(3f, 12f, 8.5f, 12f); line(15.5f, 12f, 21f, 12f) }
            Glyph.DIFF -> { line(12f, 4f, 12f, 12f); line(8f, 8f, 16f, 8f); line(8f, 17f, 16f, 17f); line(6f, 21f, 18f, 21f) }
            Glyph.PLUS_MINUS -> { line(12f, 4f, 12f, 12f); line(8f, 8f, 16f, 8f); line(8f, 17f, 16f, 17f) }
            Glyph.EYE -> { p.moveTo(2.5f, 12f); p.cubicTo(5f, 7f, 8.5f, 5f, 12f, 5f); p.cubicTo(15.5f, 5f, 19f, 7f, 21.5f, 12f); p.cubicTo(19f, 17f, 15.5f, 19f, 12f, 19f); p.cubicTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); circle(12f, 12f, 3f) }
            Glyph.LOCK -> { rect(5f, 10.5f, 19f, 20.5f, 2f); arc(12f, 9f, 4f, 180f, 180f); line(8f, 9f, 8f, 10.5f); line(16f, 9f, 16f, 10.5f) }
            Glyph.IMAGE -> { rect(3f, 4f, 21f, 20f, 2f); circle(8.5f, 9.5f, 1.8f); poly(3.5f, 18f, 9.5f, 12.5f, 13f, 15.5f, 16f, 13f, 20.5f, 17f) }
            Glyph.GOTO -> { line(4f, 12f, 15f, 12f); poly(11f, 8f, 15f, 12f, 11f, 16f); line(19f, 5f, 19f, 19f) }
            Glyph.LIST -> { line(9f, 7f, 20f, 7f); line(9f, 12f, 20f, 12f); line(9f, 17f, 20f, 17f); line(4.5f, 7f, 5f, 7f); line(4.5f, 12f, 5f, 12f); line(4.5f, 17f, 5f, 17f) }
        }
    }

    /** Parts drawn filled or as text: dots, the exclamation points, the find bar's option glyphs. */
    private fun drawFilled(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        when (glyph) {
            Glyph.MORE -> { canvas.drawCircle(12f, 5.5f, 1.7f, paint); canvas.drawCircle(12f, 12f, 1.7f, paint); canvas.drawCircle(12f, 18.5f, 1.7f, paint) }
            Glyph.MORE_H -> { canvas.drawCircle(5.5f, 12f, 1.7f, paint); canvas.drawCircle(12f, 12f, 1.7f, paint); canvas.drawCircle(18.5f, 12f, 1.7f, paint) }
            Glyph.DOT -> canvas.drawCircle(12f, 12f, 4.2f, paint)
            Glyph.PROBLEMS, Glyph.WARNING -> canvas.drawCircle(12f, 16.8f, 1.1f, paint)
            Glyph.INFO -> canvas.drawCircle(12f, 7.8f, 1.1f, paint)
            Glyph.KEYBOARD -> { for (x in listOf(7f, 10.3f, 13.6f, 17f)) canvas.drawCircle(x, 10f, 0.95f, paint) }
            Glyph.KEY_HIDE -> { for (x in listOf(7f, 10.3f, 13.6f, 17f)) canvas.drawCircle(x, 7.6f, 0.95f, paint) }
            Glyph.CASE, Glyph.WORD, Glyph.REGEX -> {
                paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = if (glyph == Glyph.REGEX) 13f else 11.5f
                val label = when (glyph) { Glyph.CASE -> "Aa"; Glyph.WORD -> "ab"; else -> ".*" }
                canvas.drawText(label, 12f, if (glyph == Glyph.REGEX) 15f else 15.5f, paint)
                if (glyph == Glyph.WORD) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.4f
                    canvas.drawLine(4.5f, 18.5f, 19.5f, 18.5f, paint)
                    canvas.drawLine(4.5f, 16.5f, 4.5f, 18.5f, paint)
                    canvas.drawLine(19.5f, 16.5f, 19.5f, 18.5f, paint)
                }
            }
            else -> {}
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
