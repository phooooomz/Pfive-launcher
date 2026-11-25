// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import java.util.WeakHashMap

/**
 * Shared state for every glass surface: the theme and the blurred wallpaper.
 * Glass is an accent on floating surfaces (windows, dock, taskbar, overlays);
 * widgets and menus use the flat tint.
 */
class GlassEnv {
    var theme: LxTheme? = null
        private set
    var wallpaper: WallpaperSet? = null
        private set
    var root: View? = null
    private val hosts = WeakHashMap<View, Boolean>()

    fun update(theme: LxTheme, wallpaper: WallpaperSet?) {
        this.theme = theme
        this.wallpaper = wallpaper
        // Not toList(): it trusts the size it read, and a GC can clear a key before the key is read.
        ArrayList(hosts.keys).forEach { it.invalidate() }
    }

    fun register(v: View) {
        hosts[v] = true
    }
}

/**
 * The brightest part of the wallpaper under the host view, read from [WallpaperSet.brightestUnder] again only
 * when the view has moved or resized or the wallpaper has changed. Drawing calls it on every frame.
 */
class WallpaperProbe(private val env: GlassEnv) {
    private var seenWallpaper: WallpaperSet? = null
    private var seenX = 0
    private var seenY = 0
    private var seenW = -1
    private var seenH = -1
    private var luminance: Double? = null

    /** Null when there is no picture to read (the system wallpaper); ([x], [y]) is the host's offset in the root. */
    fun brightest(x: Int, y: Int, width: Int, height: Int): Double? {
        val wp = env.wallpaper ?: return null
        if (wp !== seenWallpaper || x != seenX || y != seenY || width != seenW || height != seenH) {
            seenWallpaper = wp
            seenX = x
            seenY = y
            seenW = width
            seenH = height
            luminance = wp.brightestUnder(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
        }
        return luminance
    }
}

enum class Surface { GLASS, FLAT, CHIP }

/**
 * Background drawable for a panel. With blur enabled it paints the blurred
 * wallpaper region behind the view (a BitmapShader, no per-frame blur), then a
 * tint, then a hairline in the accent colour. [focused] adds the neon edge.
 */
class PanelDrawable(
    private val host: View,
    private val env: GlassEnv,
    private val surface: Surface = Surface.GLASS,
) : Drawable() {
    var focused = false
        set(v) {
            field = v
            invalidateSelf()
        }
    var radiusOverride: Float? = null
    /** Minimum tint opacity; dense text over glass stays readable. */
    var minOpacity = 0f
    /** Neon colour for this surface; defaults to the theme accent. */
    var accentOverride: Int? = null

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val matrix = Matrix()
    private val loc = IntArray(2)
    private val rootLoc = IntArray(2)
    private var shaderBitmap: Bitmap? = null
    private val probe = WallpaperProbe(env)

    init {
        env.register(host)
    }

    override fun draw(canvas: Canvas) {
        val theme = env.theme ?: return
        val r = radiusOverride ?: if (surface == Surface.CHIP) theme.chipRadius else theme.cornerRadius
        rect.set(bounds)
        val wp = env.wallpaper
        val useGlass = surface == Surface.GLASS && theme.blur && wp?.blurred != null
        var offsetX = 0
        var offsetY = 0
        if (useGlass) {
            val bmp = wp!!.blurred!!
            if (shaderBitmap !== bmp) {
                glass.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shaderBitmap = bmp
            }
            host.getLocationInWindow(loc)
            env.root?.getLocationInWindow(rootLoc)
            offsetX = loc[0] - rootLoc[0]
            offsetY = loc[1] - rootLoc[1]
            matrix.setScale(wp.blurScale, wp.blurScale)
            matrix.postTranslate(-offsetX.toFloat(), -offsetY.toFloat())
            glass.shader.setLocalMatrix(matrix)
            canvas.drawRoundRect(rect, r, r, glass)
        }
        val opacity = when (surface) {
            // Without the blurred layer, a window or menu behind would show its text through; fill opaque.
            Surface.GLASS -> if (useGlass) theme.glassOpacityOver(probe.brightest(offsetX, offsetY, bounds.width(), bounds.height()))
                else 1f
            // Flat panels sit on the sharp wallpaper, which can be white anywhere.
            Surface.FLAT -> maxOf(theme.panelOpacity, if (theme.dark) LxTheme.MENU_OPACITY else LxTheme.LIGHT_PANEL_FLOOR)
            Surface.CHIP -> 0.55f
        }.coerceAtLeast(minOpacity)
        fill.color = LxTheme.withAlpha(if (surface == Surface.CHIP) theme.surface else theme.panel, opacity)
        canvas.drawRoundRect(rect, r, r, fill)

        val hair = theme.dp(1f)
        line.strokeWidth = hair
        line.color = theme.stroke
        rect.inset(hair / 2, hair / 2)
        canvas.drawRoundRect(rect, r, r, line)
        if (focused && theme.neon) {
            // Neon edge: a crisp line plus a soft inner halo.
            line.strokeWidth = theme.dp(1.5f)
            val neon = accentOverride ?: theme.accent
            line.color = LxTheme.withAlpha(neon, 0.95f)
            canvas.drawRoundRect(rect, r, r, line)
            line.strokeWidth = theme.dp(5f)
            line.color = LxTheme.withAlpha(neon, 0.12f)
            rect.inset(theme.dp(2.5f), theme.dp(2.5f))
            canvas.drawRoundRect(rect, maxOf(0f, r - theme.dp(2.5f)), maxOf(0f, r - theme.dp(2.5f)), line)
        } else if (focused) {
            line.strokeWidth = theme.dp(2f)
            line.color = theme.accent
            canvas.drawRoundRect(rect, r, r, line)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
