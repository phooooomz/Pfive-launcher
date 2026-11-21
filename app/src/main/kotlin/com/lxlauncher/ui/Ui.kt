// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.ui.theme.LxTheme

/** Small view builders so every control gets the same states and sizes. */
object Ui {
    fun text(ctx: Context, theme: LxTheme, value: CharSequence = "", sizeSp: Float = theme.textBody,
             color: Int = theme.textPrimary, bold: Boolean = false, lines: Int = 1): TextView =
        text(ctx, theme, value, sizeSp, color, bold, lines, TextView(ctx))

    /** The same text set up on [view], a TextView subclass such as a window title. */
    fun <T : TextView> text(ctx: Context, theme: LxTheme, value: CharSequence = "", sizeSp: Float = theme.textBody,
                            color: Int = theme.textPrimary, bold: Boolean = false, lines: Int = 1, view: T): T =
        view.apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (bold) theme.fontMedium else theme.font
            if (lines > 0) {
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
            includeFontPadding = false
        }

    /** Pressed, hovered and focused states for any tappable surface. */
    fun stateBackground(theme: LxTheme, radius: Float = theme.chipRadius, selected: Boolean = false): Drawable {
        fun shape(color: Int, strokeColor: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke > 0) setStroke(stroke, strokeColor)
        }
        val focusRing = theme.dpi(2f)
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(theme.focusFill, theme.accent, focusRing))
            addState(intArrayOf(android.R.attr.state_hovered), shape(theme.hover))
            // Styles that outline the selection draw the edge too, so selection is not told by colour alone.
            val edge = if (theme.selectionOutline) theme.selectionBase else 0
            val edgeW = if (theme.selectionOutline) theme.dpi(1.5f) else 0
            addState(intArrayOf(android.R.attr.state_selected), shape(theme.selected, edge, edgeW))
            addState(intArrayOf(android.R.attr.state_activated), shape(theme.selected, edge, edgeW))
            addState(intArrayOf(), if (selected) shape(theme.selected, edge, edgeW) else shape(0))
        }
        val mask = shape(0xFFFFFFFF.toInt())
        return RippleDrawable(ColorStateList.valueOf(LxTheme.withAlpha(theme.accent, 0.28f)), states, mask)
    }

    fun iconButton(ctx: Context, theme: LxTheme, icon: Int, description: CharSequence, sizeDp: Float = 48f,
                   iconDp: Float = 22f, tint: Int = theme.textPrimary, onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = maxOf(theme.dpi(sizeDp), if (sizeDp >= 40f) theme.touchTarget else 0)
            val pad = ((size - theme.dpi(iconDp)) / 2).coerceAtLeast(0)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(size, size)
            contentDescription = description
            tooltipText = description
            background = stateBackground(theme)
            isFocusable = true
            setOnClickListener { onClick(it) }
        }

    fun button(ctx: Context, theme: LxTheme, label: CharSequence, primary: Boolean = false, onClick: (View) -> Unit): TextView =
        text(ctx, theme, label, theme.textBody, if (primary) theme.onAccent else theme.textPrimary, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = theme.touchTarget
            minWidth = theme.dpi(88f)
            setPadding(theme.dpi(16f), 0, theme.dpi(16f), 0)
            background = if (primary) {
                val fill = GradientDrawable().apply {
                    cornerRadius = theme.chipRadius
                    setColor(theme.accent)
                }
                val focused = GradientDrawable().apply {
                    cornerRadius = theme.chipRadius
                    setColor(theme.accent)
                    setStroke(theme.dpi(2f), theme.textPrimary)
                }
                RippleDrawable(ColorStateList.valueOf(LxTheme.withAlpha(theme.onAccent, 0.25f)),
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), focused)
                        addState(intArrayOf(), fill)
                    }, null)
            } else stateBackground(theme)
            isFocusable = true
            setOnClickListener { onClick(it) }
        }

    fun row(ctx: Context, vertical: Boolean = false, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            this.gravity = gravity
        }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    fun frame(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Enter and space activate a focused view the same way a tap does. */
    fun isActivateKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
}

/** A thin horizontal meter, used for battery, memory and storage. */
@SuppressLint("ViewConstructor")
class MeterView(context: Context, private val theme: LxTheme) : View(context) {
    var fraction: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color: Int = theme.accent
        set(v) {
            field = v
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        paint.color = theme.track
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        r.right = width * fraction
        paint.color = color
        if (r.right > 0) canvas.drawRoundRect(r, h / 2, h / 2, paint)
    }
}
