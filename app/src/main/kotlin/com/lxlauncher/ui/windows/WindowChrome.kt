// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.CaptionKind
import com.lxlauncher.ui.theme.Contrast
import com.lxlauncher.ui.theme.Decor
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.TitleShape
import com.lxlauncher.ui.theme.WallpaperProbe
import com.lxlauncher.ui.theme.WindowStyle

/** The outline of a window: round corners, or corners cut at 45 degrees when [chamfer] is set. */
internal fun windowPath(path: Path, r: RectF, radius: Float, chamfer: Float, topOnly: Boolean = false) {
    path.reset()
    if (chamfer > 0f) {
        val c = chamfer
        path.moveTo(r.left + c, r.top)
        path.lineTo(r.right - c, r.top)
        path.lineTo(r.right, r.top + c)
        if (topOnly) {
            path.lineTo(r.right, r.bottom)
            path.lineTo(r.left, r.bottom)
        } else {
            path.lineTo(r.right, r.bottom - c)
            path.lineTo(r.right - c, r.bottom)
            path.lineTo(r.left + c, r.bottom)
            path.lineTo(r.left, r.bottom - c)
        }
        path.lineTo(r.left, r.top + c)
        path.close()
        return
    }
    val radii = if (topOnly) floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f) else FloatArray(8) { radius }
    path.addRoundRect(r, radii, Path.Direction.CW)
}

/**
 * Background of a styled LX window: glass or a solid fill in the style's palette, an optional
 * decoration, and an edge that grows thicker when the window has focus, so focus is not told by
 * colour alone. Everything is drawn inside the bounds; a glow is drawn inward because a
 * background is clipped at the view's edge. Shadows come from [getOutline] and the frame's elevation.
 * The frame calls [drawEdge] after its children, so the title bar's fill cannot cover the edge.
 */
class WindowFrameDrawable(
    private val host: View,
    private val env: GlassEnv,
    private val style: WindowStyle,
    private val theme: LxTheme,
) : Drawable() {
    var focused = false
        set(v) {
            field = v
            invalidateSelf()
        }

    private val rect = RectF()
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val decorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private val rootLoc = IntArray(2)
    private var shaderBitmap: Bitmap? = null
    // Reused on every frame: drawing allocates nothing.
    private val edgeRect = RectF()
    private val edgePath = Path()
    private val leaf = Path()
    private val probe = WallpaperProbe(env)
    private val plain = theme.highContrast
    private val radius = theme.cornerRadius
    private val chamfer = if (style.chamferDp > 0f) theme.dp(style.chamferDp) else 0f

    init {
        env.register(host)
    }

    /** The fill alpha for where the window is now: adaptive styles grow denser over light wallpaper. */
    private fun currentAlpha(x: Int, y: Int): Float {
        if (plain) return 1f
        if (style.adaptiveAlpha == null) return style.fillAlpha
        return style.fillAlphaOver(probe.brightest(x, y, bounds.width(), bounds.height()))
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        windowPath(path, rect, radius, chamfer)
        val wp = env.wallpaper
        // Blur and Neon can be switched without rebuilding the window, so read them from the current theme.
        val live = env.theme ?: theme
        if (!plain && live.blur && style.glass && wp?.blurred != null) {
            val bmp = wp.blurred!!
            if (shaderBitmap !== bmp) {
                glass.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderBitmap = bmp
            }
            host.getLocationInWindow(loc)
            env.root?.getLocationInWindow(rootLoc)
            val offsetX = loc[0] - rootLoc[0]
            val offsetY = loc[1] - rootLoc[1]
            matrix.setScale(wp.blurScale, wp.blurScale)
            matrix.postTranslate(-offsetX.toFloat(), -offsetY.toFloat())
            glass.shader.setLocalMatrix(matrix)
            canvas.drawPath(path, glass)
            fill.color = LxTheme.withAlpha(style.colors.panel, currentAlpha(offsetX, offsetY))
        } else {
            // Without the blurred layer, the window behind would show its text through this one; fill opaque.
            fill.color = LxTheme.withAlpha(style.colors.panel, 1f)
        }
        canvas.drawPath(path, fill)
        if (!plain && style.decor == Decor.LEAVES) drawLeaves(canvas)
    }

    /** Three leaves in the lower right corner, faint enough that text over them keeps its contrast. */
    private fun drawLeaves(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(path)
        decorPaint.color = LxTheme.withAlpha(style.colors.accent2, LxTheme.LEAF_ALPHA)
        val s = theme.dp(64f)
        fun drawLeaf(cx: Float, cy: Float, size: Float, angle: Float) {
            leaf.reset()
            leaf.moveTo(0f, 0f)
            leaf.quadTo(size * 0.55f, -size * 0.32f, size, 0f)
            leaf.quadTo(size * 0.55f, size * 0.32f, 0f, 0f)
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(angle)
            canvas.drawPath(leaf, decorPaint)
            canvas.restore()
        }
        val x = rect.right - theme.dp(8f)
        val y = rect.bottom - theme.dp(8f)
        drawLeaf(x, y, s * 1.6f, 200f)
        drawLeaf(x, y, s * 1.25f, 235f)
        drawLeaf(x, y, s, 265f)
        canvas.restore()
    }

    fun drawEdge(canvas: Canvas) {
        rect.set(bounds)
        val c = style.colors
        if (plain) {
            line.strokeWidth = theme.dp(if (focused) 2.5f else 1.5f)
            line.color = LxTheme.withAlpha(theme.textPrimary, 0.6f)
            insetStroke(canvas)
            return
        }
        if (style.bevel) {
            // Classic raised edge: dark outline, light top and left, darker bottom and right.
            line.strokeWidth = theme.dp(if (focused) 2f else 1f)
            line.color = if (focused) theme.accent else style.border
            insetStroke(canvas)
            val w = theme.dp(1f)
            val inset = line.strokeWidth + w / 2
            line.strokeWidth = w
            line.color = LxTheme.withAlpha(Color.WHITE, 0.9f)
            canvas.drawLine(rect.left + inset, rect.top + inset, rect.right - inset, rect.top + inset, line)
            canvas.drawLine(rect.left + inset, rect.top + inset, rect.left + inset, rect.bottom - inset, line)
            line.color = LxTheme.withAlpha(style.border, 0.7f)
            canvas.drawLine(rect.left + inset, rect.bottom - inset, rect.right - inset, rect.bottom - inset, line)
            canvas.drawLine(rect.right - inset, rect.top + inset, rect.right - inset, rect.bottom - inset, line)
            return
        }
        if (focused) {
            line.strokeWidth = theme.dp(maxOf(style.borderDp + 0.5f, 1.5f))
            line.color = LxTheme.withAlpha(if (style.focusGlow) c.accent else theme.accent, 0.95f)
            insetStroke(canvas)
            if (style.focusGlow && (env.theme ?: theme).neon) {
                // An inner halo: drawn inside the edge, where a background can paint.
                line.strokeWidth = theme.dp(5f)
                line.color = LxTheme.withAlpha(c.accent, 0.18f)
                edgeRect.set(rect)
                edgeRect.inset(theme.dp(3f), theme.dp(3f))
                windowPath(edgePath, edgeRect, maxOf(0f, radius - theme.dp(3f)), maxOf(0f, chamfer - theme.dp(1.2f)))
                canvas.drawPath(edgePath, line)
            }
        } else {
            line.strokeWidth = theme.dp(style.borderDp)
            line.color = LxTheme.withAlpha(style.border, style.unfocusedBorderAlpha)
            insetStroke(canvas)
        }
    }

    /** Strokes the window outline inset by half the stroke, so the whole line stays inside the bounds. */
    private fun insetStroke(canvas: Canvas) {
        val half = line.strokeWidth / 2
        edgeRect.set(rect)
        edgeRect.inset(half, half)
        windowPath(edgePath, edgeRect, maxOf(0f, radius - half), maxOf(0f, chamfer - half * 0.4f))
        canvas.drawPath(edgePath, line)
    }

    override fun getOutline(outline: Outline) {
        rect.set(bounds)
        // The view asks for its outline before the background has bounds. Cut corners on an empty or
        // tiny rect fold back on themselves, and Android 8 and 9 throw on a path that is not convex.
        if (chamfer > 0f && rect.width() > 2 * chamfer && rect.height() > 2 * chamfer) {
            windowPath(path, rect, radius, chamfer)
            @Suppress("DEPRECATION")
            when {
                Build.VERSION.SDK_INT >= 30 -> outline.setPath(path)
                path.isConvex -> outline.setConvexPath(path)
                else -> outline.setRoundRect(bounds, radius)
            }
        } else {
            outline.setRoundRect(bounds, radius)
        }
        // A translucent window casts a lighter shadow, so the shadow does not darken its glass.
        outline.alpha = if (plain) 1f else if (style.glass) style.fillAlpha else 1f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Background of a styled title bar: the full width, an inset card, or a tab cut off diagonally
 * before the buttons, with the style's line, hatching and corner brackets.
 * [buttonsWidth] is where the caption buttons begin, measured from the end edge. In a right-to-left
 * layout the buttons sit on the left, so the tab and its decoration are mirrored.
 */
class TitleBarDrawable(
    private val style: WindowStyle,
    private val theme: LxTheme,
    private val buttonsWidth: () -> Int,
) : Drawable() {
    private val rect = RectF()
    private val card = RectF()
    private val shape = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val plain = theme.highContrast
    private val chamfer = if (style.chamferDp > 0f) theme.dp(style.chamferDp) else 0f

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val fillColor = when {
            plain -> theme.panel
            style.titleFill == null -> null
            else -> LxTheme.withAlpha(style.titleFill, style.titleFillAlpha)
        }
        canvas.save()
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, rect.centerX(), rect.centerY())
        when (style.titleShape) {
            TitleShape.FULL -> windowPath(shape, rect, theme.cornerRadius, chamfer, topOnly = true)
            TitleShape.CARD -> {
                val m = theme.dp(6f)
                card.set(rect.left + m, rect.top + m, rect.right - m, rect.bottom)
                shape.reset()
                shape.addRoundRect(card, theme.dp(14f), theme.dp(14f), Path.Direction.CW)
            }
            TitleShape.SLANTED_TAB -> {
                // The tab runs under the icon and the title and is cut off diagonally before the buttons.
                val end = (rect.right - buttonsWidth() - theme.dp(8f)).coerceAtLeast(rect.left + rect.height() * 2)
                val slant = rect.height() * 0.55f
                shape.reset()
                shape.moveTo(rect.left + chamfer, rect.top)
                shape.lineTo(end, rect.top)
                shape.lineTo(end - slant, rect.bottom)
                shape.lineTo(rect.left, rect.bottom)
                shape.lineTo(rect.left, rect.top + chamfer)
                shape.close()
            }
        }
        if (fillColor != null) {
            fill.color = fillColor
            canvas.drawPath(shape, fill)
        }
        if (!plain && style.decor == Decor.HATCH && style.titleShape == TitleShape.SLANTED_TAB) {
            canvas.save()
            canvas.clipPath(shape)
            line.strokeWidth = theme.dp(1f)
            val tactical = style.brackets
            line.color = LxTheme.withAlpha(if (tactical) style.colors.text else style.colors.accent, if (tactical) 0.06f else 0.08f)
            val step = theme.dp(if (tactical) 6f else 4f)
            var x = rect.left - rect.height()
            while (x < rect.right) {
                canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, line)
                x += step
            }
            canvas.restore()
        }
        val lineColor = if (plain) LxTheme.withAlpha(theme.textPrimary, 0.6f) else style.titleLine
        if (lineColor != null && style.titleShape != TitleShape.CARD) {
            line.strokeWidth = theme.dp(style.titleLineDp)
            line.color = lineColor
            val y = rect.bottom - line.strokeWidth / 2
            canvas.drawLine(rect.left, y, rect.right, y, line)
        }
        if (!plain && style.brackets) {
            line.strokeWidth = theme.dp(1.5f)
            line.color = style.colors.accent
            val leg = theme.dp(12f)
            val i = theme.dp(4f)
            canvas.drawLine(rect.left + i, rect.top + i, rect.left + i + leg, rect.top + i, line)
            canvas.drawLine(rect.left + i, rect.top + i, rect.left + i, rect.top + i + leg, line)
            canvas.drawLine(rect.right - i, rect.bottom - i, rect.right - i - leg, rect.bottom - i, line)
            canvas.drawLine(rect.right - i, rect.bottom - i, rect.right - i, rect.bottom - i - leg, line)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * A caption button drawn in the window style: a plain glyph, a coloured dot with the glyph on it,
 * a classic boxed button, or a filled close square. The glyph is always drawn, so the buttons are
 * told apart by shape and not only by colour. Hover, press and focus come from the theme's states.
 */
@SuppressLint("ViewConstructor")
class CaptionButton(context: Context, private val theme: LxTheme, private val style: WindowStyle, role: Role) : View(context) {
    enum class Role { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

    var role: Role = role
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()

    init {
        isFocusable = true
        isClickable = true
        background = Ui.stateBackground(theme)
    }

    private fun glyphColorOn(fill: Int): Int =
        if (Contrast.ratio(fill, Color.WHITE) >= Contrast.ratio(fill, Color.BLACK)) Color.WHITE else Color.parseColor("#141414")

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val plain = theme.highContrast
        val baseColor = when (role) {
            Role.MINIMIZE -> style.minimize
            Role.MAXIMIZE, Role.RESTORE -> style.maximize
            Role.CLOSE -> style.close
        }
        when {
            plain -> drawGlyph(canvas, cx, cy, theme.dp(14f), theme.textPrimary, theme.dp(2f))
            style.buttons == CaptionKind.DOTS -> {
                val dot = when (role) {
                    Role.MINIMIZE -> style.minimizeDot
                    else -> baseColor
                }
                if (dot == null) {
                    drawGlyph(canvas, cx, cy, theme.dp(12f), baseColor, theme.dp(2f))
                } else {
                    val rad = theme.dp(style.dotDp) / 2f
                    paint.color = dot
                    canvas.drawCircle(cx, cy, rad, paint)
                    drawGlyph(canvas, cx, cy, rad * 0.95f, glyphColorOn(dot), theme.dp(1.5f))
                }
            }
            style.buttons == CaptionKind.BOXED -> {
                val half = theme.dp(13f)
                r.set(cx - half, cy - half, cx + half, cy + half)
                paint.color = style.colors.surface
                canvas.drawRect(r, paint)
                stroke.strokeCap = Paint.Cap.SQUARE
                stroke.strokeWidth = theme.dp(1f)
                stroke.color = Color.WHITE
                canvas.drawLine(r.left + 1, r.top + 1, r.right - 1, r.top + 1, stroke)
                canvas.drawLine(r.left + 1, r.top + 1, r.left + 1, r.bottom - 1, stroke)
                stroke.color = style.border
                canvas.drawRect(r, stroke)
                stroke.strokeCap = Paint.Cap.ROUND
                drawGlyph(canvas, cx, cy, theme.dp(11f), baseColor, theme.dp(2f))
            }
            role == Role.CLOSE && style.closeFilled -> {
                val half = theme.dp(14f)
                r.set(cx - half, cy - half, cx + half, cy + half)
                paint.color = style.close
                canvas.drawRoundRect(r, theme.dp(4f), theme.dp(4f), paint)
                drawGlyph(canvas, cx, cy, theme.dp(12f), Color.WHITE, theme.dp(2f))
            }
            else -> drawGlyph(canvas, cx, cy, theme.dp(14f), baseColor,
                theme.dp(if (role == Role.CLOSE) style.closeStrokeDp else minOf(style.closeStrokeDp, 2f)))
        }
    }

    /** The minimise line, the maximise square, the restore pair or the close cross, [size] wide. */
    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        val h = size / 2f
        when (role) {
            Role.MINIMIZE -> canvas.drawLine(cx - h, cy + h * 0.25f, cx + h, cy + h * 0.25f, stroke)
            Role.MAXIMIZE -> canvas.drawRect(cx - h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.85f, stroke)
            Role.RESTORE -> {
                val s = h * 0.62f
                canvas.drawRect(cx - h * 0.85f, cy - h * 0.45f, cx - h * 0.85f + 2 * s, cy - h * 0.45f + 2 * s, stroke)
                canvas.drawLine(cx - h * 0.45f, cy - h * 0.85f, cx + h * 0.85f, cy - h * 0.85f, stroke)
                canvas.drawLine(cx + h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.45f, stroke)
            }
            Role.CLOSE -> {
                val k = h * 0.8f
                canvas.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
                canvas.drawLine(cx - k, cy + k, cx + k, cy - k, stroke)
            }
        }
    }
}
