// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.desktop

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme

/**
 * One desktop entry: an icon with a label under it, or a folder
 * preview of up to four icons. Drawn directly for speed; large grids create
 * hundreds of these.
 */
@android.annotation.SuppressLint("ViewConstructor")
class IconView(context: Context, private var theme: LxTheme) : View(context) {
    var label: CharSequence = ""
        set(v) {
            field = v
            contentDescription = v
            invalidate()
        }
    var icon: Drawable? = null
        set(v) {
            field = v
            invalidate()
        }
    var folderIcons: List<Drawable?>? = null
        set(v) {
            field = v
            invalidate()
        }
    var showLabel = true
    var iconSizePx = theme.dpi(48f)
    var dropHighlight = false
        set(v) {
            field = v
            invalidate()
        }
    var unavailable = false
        set(v) {
            field = v
            alpha = if (v) 0.45f else 1f
        }

    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isFocusable = true
        isClickable = true
        isLongClickable = true
        background = Ui.stateBackground(theme)
        setTheme(theme)
    }

    /** Null on a panel (an open folder); otherwise whether the wallpaper behind the label is light. */
    private var wallpaperLight: Boolean? = null

    fun setTheme(t: LxTheme) {
        theme = t
        text.textSize = t.dp(11.5f)
        text.typeface = t.font
        applyLabelColours()
        background = Ui.stateBackground(t)
        invalidate()
    }

    /**
     * Desktop icons sit on the wallpaper, not on a panel, so their labels follow
     * the wallpaper's brightness instead of the theme: dark ink with a pale halo
     * on a light picture, white with a dark shadow on a dark one.
     */
    fun setOnWallpaper(light: Boolean) {
        if (wallpaperLight == light) return
        wallpaperLight = light
        applyLabelColours()
        invalidate()
    }

    private fun applyLabelColours() {
        when (wallpaperLight) {
            null -> {
                text.color = theme.textPrimary
                text.clearShadowLayer()
            }
            true -> {
                text.color = Color.parseColor("#17202D")
                text.setShadowLayer(theme.dp(2f), 0f, 0f, LxTheme.withAlpha(Color.WHITE, 0.85f))
            }
            false -> {
                text.color = Color.WHITE
                text.setShadowLayer(theme.dp(2f), 0f, theme.dp(1f), LxTheme.withAlpha(Color.BLACK, 0.85f))
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = if (showLabel) (text.textSize * 1.4f).toInt() else 0
        val size = minOf(iconSizePx, width - paddingLeft - paddingRight, height - labelH - theme.dpi(8f)).coerceAtLeast(1)
        val left = (width - size) / 2
        val top = ((height - labelH - size) / 2).coerceAtLeast(theme.dpi(2f))
        if (dropHighlight) {
            chip.color = LxTheme.withAlpha(theme.accent, 0.25f)
            rect.set(left - theme.dp(4f), top - theme.dp(4f), left + size + theme.dp(4f), top + size + theme.dp(4f))
            canvas.drawRoundRect(rect, theme.cornerRadius, theme.cornerRadius, chip)
        }
        val folder = folderIcons
        if (folder != null) {
            chip.color = LxTheme.withAlpha(theme.panel, 0.82f)
            rect.set(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat())
            canvas.drawRoundRect(rect, size * 0.24f, size * 0.24f, chip)
            val cell = size / 2
            val pad = size / 10
            folder.take(4).forEachIndexed { i, d ->
                val cx = left + (i % 2) * cell
                val cy = top + (i / 2) * cell
                d?.setBounds(cx + pad, cy + pad, cx + cell - pad / 2, cy + cell - pad / 2)
                d?.draw(canvas)
            }
        } else {
            icon?.let {
                it.setBounds(left, top, left + size, top + size)
                it.draw(canvas)
            }
        }
        if (showLabel && label.isNotEmpty()) {
            val avail = (width - theme.dp(4f))
            val t = TextUtils.ellipsize(label, text, avail, TextUtils.TruncateAt.END)
            val w = text.measureText(t, 0, t.length)
            canvas.drawText(t, 0, t.length, (width - w) / 2, top + size + theme.dp(4f) + text.textSize, text)
        }
    }
}
