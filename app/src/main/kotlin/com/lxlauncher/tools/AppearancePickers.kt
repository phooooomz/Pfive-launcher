// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tools

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.Snapshot
import com.lxlauncher.ui.Cards
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.StableHorizontalScrollView
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.overlays.CardMotion
import com.lxlauncher.ui.overlays.MenuMotion
import com.lxlauncher.ui.theme.FontKind
import com.lxlauncher.ui.theme.LxFonts
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.ThemeColors
import com.lxlauncher.ui.theme.Themes
import com.lxlauncher.ui.theme.WindowStyle
import com.lxlauncher.ui.theme.WindowStyles

/**
 * The colour themes as a row of previews. Each preview paints the theme's own
 * ground, a panel with text in its ink, and its two accents. Choosing one also
 * returns the accent to "from the theme", in one edit.
 */
@SuppressLint("ViewConstructor")
class ThemePicker(private val shell: Shell, s: Snapshot) : StableHorizontalScrollView(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    /** Choosing a theme rebuilds this row; the chosen tile is brought back into view once it has its place. */
    private var activeIndex = -1

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(theme.dpi(32f))
        // The row sits in a Settings card.
        fadeInto = { Cards.raisedColor(theme) }
        val row = LinearLayout(context).apply { setPadding(0, theme.dpi(4f), 0, theme.dpi(8f)) }
        val active = s.setting("theme.palette")
        for (colors in Themes.all) row.addView(tile(colors, colors.id == active),
            LinearLayout.LayoutParams(theme.dpi(120f), Ui.WRAP).apply { marginEnd = theme.dpi(8f) })
        addView(row)
        activeIndex = Themes.all.indexOfFirst { it.id == active }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val row = getChildAt(0) as? LinearLayout ?: return
        if (changed && activeIndex > 0 && r - l > 0) row.getChildAt(activeIndex)?.let { scrollTo(it.left - theme.dpi(24f), 0) }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Once the person scrolls the row themselves, leave it where they put it.
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) activeIndex = -1
        return super.dispatchTouchEvent(ev)
    }

    private fun tile(colors: ThemeColors, active: Boolean): View = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        background = Ui.stateBackground(theme, selected = active)
        setPadding(theme.dpi(6f), theme.dpi(6f), theme.dpi(6f), theme.dpi(6f))
        isFocusable = true
        isSelected = active
        contentDescription = if (active) context.getString(R.string.option_in_use_named, colors.name) else colors.name
        addView(Swatch(colors, active), Ui.lp(Ui.MATCH, theme.dpi(72f)))
        addView(Ui.row(context).apply {
            setPadding(0, theme.dpi(6f), 0, 0)
            addView(Ui.text(context, theme, colors.name, theme.textLabel, theme.textPrimary, bold = active, lines = 2),
                Ui.lp(0, Ui.WRAP, 1f))
            if (active) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(theme.selectedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(theme.dpi(18f), theme.dpi(18f)))
        })
        if (active) addView(Ui.text(context, theme, context.getString(R.string.option_in_use), theme.textCaption, theme.textPrimary))
        setOnClickListener { choose(colors.id) }
        setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_UP && Ui.isActivateKey(code)) {
                choose(colors.id)
                true
            } else false
        }
    }

    private fun choose(id: String) {
        shell.model.edit {
            NativeCore.setSetting(it, "theme.palette", id) && NativeCore.setSetting(it, "theme.accent", "palette")
        }
    }

    /** A miniature of the theme: its ground, a panel holding "Aa" in its ink, and its accents. */
    private inner class Swatch(private val c: ThemeColors, private val active: Boolean) : View(shell.activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val radius = theme.dp(8f)
            r.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.style = Paint.Style.FILL
            paint.color = c.ground
            canvas.drawRoundRect(r, radius, radius, paint)
            val inset = theme.dp(8f)
            r.set(inset, inset, width - inset, height - inset)
            paint.color = c.panel
            canvas.drawRoundRect(r, radius * 0.6f, radius * 0.6f, paint)
            paint.color = c.text
            paint.typeface = theme.fontMedium
            paint.textSize = theme.dp(18f)
            canvas.drawText("Aa", inset * 2, inset + theme.dp(26f), paint)
            paint.color = c.accent
            r.set(inset * 2, height - inset * 2 - theme.dp(8f), inset * 2 + theme.dp(36f), height - inset * 2)
            canvas.drawRoundRect(r, theme.dp(4f), theme.dp(4f), paint)
            paint.color = c.accent2
            canvas.drawCircle(width - inset * 2 - theme.dp(6f), height - inset * 2 - theme.dp(4f), theme.dp(5f), paint)
            if (active) {
                // The active theme also gets an outline in the current accent: shape, not colour alone.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = theme.dp(2f)
                paint.color = theme.accent
                r.set(theme.dp(1f), theme.dp(1f), width - theme.dp(1f), height - theme.dp(1f))
                canvas.drawRoundRect(r, radius, radius, paint)
            }
        }
    }
}

/**
 * The 25 bundled fonts as tiles grouped by how they read (sans, rounded, serif, monospace). Each tile is set in its
 * own face; the one in use has the selection fill, a check and the words "In use", so it is not told by colour alone.
 * Faces load off the main thread and the tile shows in the interface font meanwhile.
 */
@SuppressLint("ViewConstructor")
class FontGrid(private val shell: Shell, columns: Int) : LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui

    init {
        orientation = VERTICAL
        val active = theme.fontId
        for (kind in FontKind.values()) {
            val fonts = LxFonts.all.filter { it.kind == kind }
            if (fonts.isEmpty()) continue
            addView(Ui.text(context, theme, context.getString(kindLabel(kind)), theme.textLabel, theme.textMuted).apply {
                setPadding(theme.dpi(4f), theme.dpi(12f), theme.dpi(4f), theme.dpi(6f))
            })
            fonts.chunked(columns).forEach { chunk ->
                addView(Ui.row(context, gravity = Gravity.NO_GRAVITY).apply {
                    for (i in 0 until columns) {
                        val font = chunk.getOrNull(i)
                        addView(if (font != null) tile(font, font.id == active) else View(context), Ui.lp(0, Ui.WRAP, 1f).apply {
                            if (i > 0) marginStart = theme.dpi(6f)
                        })
                    }
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = theme.dpi(6f) })
            }
        }
    }

    private fun kindLabel(kind: FontKind) = when (kind) {
        FontKind.SANS -> R.string.font_kind_sans
        FontKind.ROUNDED -> R.string.font_kind_rounded
        FontKind.SERIF -> R.string.font_kind_serif
        FontKind.MONO -> R.string.font_kind_mono
    }

    private fun tile(font: com.lxlauncher.ui.theme.FontOption, active: Boolean): View = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        background = com.lxlauncher.ui.Cards.raised(theme)
        foreground = Ui.stateBackground(theme, theme.dp(com.lxlauncher.ui.Cards.RADIUS_DP), selected = active)
        minimumHeight = theme.dpi(76f)
        setPadding(theme.dpi(10f), theme.dpi(8f), theme.dpi(8f), theme.dpi(8f))
        isFocusable = true
        isSelected = active
        contentDescription = if (active) context.getString(R.string.option_in_use_named, font.name) else font.name
        addView(Ui.row(context).apply {
            addView(Ui.text(context, theme, "Ag", theme.textTitle * 1.5f, theme.textPrimary).apply {
                LxFonts.load(context, font.id, medium = false) { typeface = it }
            }, Ui.lp(0, Ui.WRAP, 1f))
            if (active) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(theme.selectedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(theme.dpi(18f), theme.dpi(18f)))
        })
        addView(Ui.text(context, theme, font.name, theme.textLabel, theme.textPrimary, lines = 2).apply {
            setPadding(0, theme.dpi(4f), 0, 0)
            LxFonts.load(context, font.id, medium = true) { typeface = it }
        })
        if (active) addView(Ui.text(context, theme, context.getString(R.string.option_in_use), theme.textCaption, theme.selectedText))
        setOnClickListener { choose(font.id) }
        setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_UP && Ui.isActivateKey(code)) {
                choose(font.id)
                true
            } else false
        }
    }

    private fun choose(id: String) {
        shell.model.edit { NativeCore.setSetting(it, "ui.font", id) }
    }
}

/** The font in use at the sizes LX sets text in: what a change of font does, before going back to the desktop. */
@SuppressLint("ViewConstructor")
class FontPreview(shell: Shell) : LinearLayout(shell.activity) {
    init {
        val t = shell.ui
        orientation = VERTICAL
        background = com.lxlauncher.ui.Cards.raised(t)
        val p = t.dpi(14f)
        setPadding(p, p, p, p)
        val name = LxFonts.option(t.fontId).name
        addView(Ui.row(context).apply {
            addView(Ui.text(context, t, "Aa", t.textDisplay, t.textPrimary).apply { typeface = t.font }, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
                marginEnd = t.dpi(14f)
            })
            addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                addView(Ui.text(context, t, name, t.textTitle, t.textPrimary, bold = true, lines = 2))
                addView(Ui.text(context, t, context.getString(R.string.font_preview_sizes, Math.round(t.textTitle), Math.round(t.textBody),
                    Math.round(t.textLabel)), t.textCaption, t.textMuted, lines = 2).apply {
                    setPadding(0, t.dpi(2f), 0, 0)
                })
            }, Ui.lp(0, Ui.WRAP, 1f))
        })
        addView(Ui.text(context, t, context.getString(R.string.font_sample), t.textBody, t.textPrimary, lines = 2).apply {
            setPadding(0, t.dpi(10f), 0, 0)
        })
        addView(Ui.text(context, t, context.getString(R.string.font_preview_label), t.textLabel, t.textMuted, lines = 2).apply {
            setPadding(0, t.dpi(4f), 0, 0)
        })
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
}

/**
 * LX's panels as the settings make them: a top bar and a menu card in the panel colour at the chosen opacity and
 * corner radius, drawn over a dark half and a light half, so what the opacity lets through shows on both. The
 * sliders below change [opacity] and [cornerDp] while they move.
 */
@SuppressLint("ViewConstructor")
class PanelPreview(private val shell: Shell, opacity: Float, cornerDp: Float) : View(shell.activity) {
    var opacity = opacity
        set(value) {
            field = value
            invalidate()
        }
    var cornerDp = cornerDp
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val clip = android.graphics.Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val t = shell.ui
        val outer = t.dp(com.lxlauncher.ui.Cards.RADIUS_DP)
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.save()
        clip.rewind()
        clip.addRoundRect(r, outer, outer, android.graphics.Path.Direction.CW)
        canvas.clipPath(clip)
        // The two backgrounds: the theme's own ground, and a light wallpaper with a band of the accent.
        paint.color = t.colors.ground
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = LIGHT
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = LxTheme.withAlpha(t.accent, 0.55f)
        canvas.drawRect(width / 2f, height * 0.55f, width.toFloat(), height * 0.70f, paint)
        canvas.restore()
        val fill = LxTheme.withAlpha(t.colors.panel, opacity.coerceIn(0f, 1f))
        val radius = t.dp(cornerDp)
        val m = t.dp(10f)
        // Top panel across both halves.
        r.set(m, m, width - m, m + t.dp(28f))
        paint.color = fill
        canvas.drawRoundRect(r, minOf(radius * 1.5f, r.height() / 2f), minOf(radius * 1.5f, r.height() / 2f), paint)
        paint.color = t.colors.text
        for (i in 0..2) canvas.drawCircle(r.left + t.dp(14f) + i * t.dp(12f), r.centerY(), t.dp(3f), paint)
        // A menu card over the seam, with three lines of text.
        r.set(width * 0.22f, m + t.dp(38f), width * 0.78f, height - m)
        paint.color = fill
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.color = LxTheme.withAlpha(t.colors.text, 0.9f)
        val line = t.dp(4f)
        for (i in 0..2) {
            val y = r.top + t.dp(16f) + i * t.dp(14f)
            if (y + line > r.bottom - t.dp(8f)) break
            canvas.drawRoundRect(r.left + t.dp(12f), y, r.left + r.width() * (if (i == 0) 0.7f else 0.5f), y + line, line / 2, line / 2, paint)
        }
    }

    private companion object {
        val LIGHT = android.graphics.Color.rgb(0xEC, 0xF0, 0xF5)
    }
}

/**
 * A miniature of the Applications menu: a category column with one category open, and the submenu of that category
 * entering by the same [CardMotion] the real menu uses. It plays when it is first laid out and on [play].
 */
@SuppressLint("ViewConstructor")
class MenuMotionPreview(private val shell: Shell, private var motion: MenuMotion) : FrameLayout(shell.activity) {
    private val t: LxTheme get() = shell.ui
    private val rowH = t.dpi(22f)
    private val column = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val card = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val player = CardMotion(card, { t }, { rowH }, { t.dp(12f) })
    private val appRows = ArrayList<View>()
    private var openRow: View? = null
    private var played = false

    init {
        background = com.lxlauncher.ui.Cards.raised(t)
        clipChildren = true
        val pad = t.dpi(10f)
        setPadding(pad, pad, pad, pad)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        for (i in 0 until 4) {
            val open = i == 1
            val row = bar(open, if (open) 0.62f else listOf(0.7f, 0f, 0.5f, 0.66f)[i])
            if (open) openRow = row
            column.addView(row, Ui.lp(Ui.MATCH, rowH).apply { bottomMargin = t.dpi(4f) })
        }
        card.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(t.colors.panel)
                cornerRadius = t.dp(12f)
            }
            elevation = t.dp(6f)
            outlineProvider = player.outline
            setPadding(t.dpi(6f), t.dpi(6f), t.dpi(6f), t.dpi(6f))
        }
        // Four rows fit the preview's height at every interface size; a fifth was cut by its edge.
        for (i in 0 until 4) {
            val row = Ui.row(context).apply {
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(LxTheme.withAlpha(t.accent2, 0.85f))
                    }
                }, Ui.lp(t.dpi(12f), t.dpi(12f)).apply { marginEnd = t.dpi(8f) })
                addView(View(context).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(LxTheme.withAlpha(t.colors.text, 0.75f))
                        cornerRadius = t.dp(2f)
                    }
                }, Ui.lp(0, t.dpi(4f), listOf(0.8f, 0.55f, 0.7f, 0.45f, 0.6f)[i]))
                addView(View(context), Ui.lp(0, 1, 1f - listOf(0.8f, 0.55f, 0.7f, 0.45f, 0.6f)[i]))
            }
            appRows += row
            card.addView(row, Ui.lp(Ui.MATCH, rowH))
        }
        addView(column, FrameLayout.LayoutParams(0, Ui.WRAP))
        addView(card, FrameLayout.LayoutParams(0, Ui.WRAP))
        setOnClickListener { play(motion) }
    }

    /** One category row: an icon dot and a label bar; the open one is filled with the accent, as in the real menu. */
    private fun bar(open: Boolean, length: Float): View = Ui.row(context).apply {
        setPadding(t.dpi(6f), 0, t.dpi(6f), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (open) t.accent else 0)
            cornerRadius = rowH / 2f
        }
        val ink = if (open) t.onAccent else LxTheme.withAlpha(t.colors.text, 0.75f)
        addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ink)
            }
        }, Ui.lp(t.dpi(8f), t.dpi(8f)).apply { marginEnd = t.dpi(6f) })
        if (length > 0f) {
            addView(View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(ink)
                    cornerRadius = t.dp(2f)
                }
            }, Ui.lp(0, t.dpi(4f), length))
            addView(View(context), Ui.lp(0, 1, 1f - length))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val colW = (w * 0.42f).toInt()
        (column.layoutParams as LayoutParams).width = colW
        (card.layoutParams as LayoutParams).apply {
            width = w - colW - t.dpi(6f)
            leftMargin = colW + t.dpi(6f)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        super.onLayout(changed, l, top, r, b)
        if (!played && width > 0) {
            played = true
            post { play(motion) }
        }
    }

    /** Plays [m] from the start: the card leaves at once and enters again. */
    fun play(m: MenuMotion) {
        motion = m
        player.settle()
        val open = openRow ?: return
        player.anchorY = (open.top + open.height / 2) - card.top + column.top
        player.enter(m)
        player.rows(appRows.map { it to (it.top + it.height / 2) }, m, glide = false)
    }
}

/**
 * The window styles as a row of miniature windows, each drawn in its own palette with its title
 * bar, buttons and a selected row. The first tile keeps windows in the LX theme.
 */
@SuppressLint("ViewConstructor")
class WindowStylePicker(private val shell: Shell, s: Snapshot) : StableHorizontalScrollView(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private var activeIndex = -1
    /** The LX theme as windows would show it, for the "follow the LX theme" tile. One for all tiles; each used to build its own. */
    private val lxTheme = LxTheme(context, shell.model.snapshot)

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(theme.dpi(32f))
        // The row sits in a Settings card.
        fadeInto = { Cards.raisedColor(theme) }
        val row = LinearLayout(context).apply { setPadding(0, theme.dpi(4f), 0, theme.dpi(8f)) }
        val active = s.setting("window.style")
        // The custom tile shows the style as it is set up now.
        val options = listOf<WindowStyle?>(null) + WindowStyles.all + WindowStyles.custom(s)
        options.forEachIndexed { i, style ->
            val id = style?.id ?: "theme"
            if (id == active) activeIndex = i
            row.addView(tile(style, id == active), LinearLayout.LayoutParams(theme.dpi(128f), Ui.WRAP).apply { marginEnd = theme.dpi(8f) })
        }
        addView(row)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val row = getChildAt(0) as? LinearLayout ?: return
        if (changed && activeIndex > 0 && r - l > 0) row.getChildAt(activeIndex)?.let { scrollTo(it.left - theme.dpi(24f), 0) }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) activeIndex = -1
        return super.dispatchTouchEvent(ev)
    }

    private fun tile(style: WindowStyle?, active: Boolean): View = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        val name = context.getString(style?.nameRes ?: R.string.window_style_theme)
        background = Ui.stateBackground(theme, selected = active)
        setPadding(theme.dpi(6f), theme.dpi(6f), theme.dpi(6f), theme.dpi(6f))
        isFocusable = true
        isSelected = active
        contentDescription = if (active) context.getString(R.string.option_in_use_named, name) else name
        addView(Miniature(style, active), Ui.lp(Ui.MATCH, theme.dpi(84f)))
        addView(Ui.row(context).apply {
            setPadding(0, theme.dpi(6f), 0, 0)
            addView(Ui.text(context, theme, name, theme.textLabel, theme.textPrimary, bold = active, lines = 2), Ui.lp(0, Ui.WRAP, 1f))
            if (active) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(theme.selectedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(theme.dpi(18f), theme.dpi(18f)))
        })
        if (active) addView(Ui.text(context, theme, context.getString(R.string.option_in_use), theme.textCaption, theme.textPrimary))
        val id = style?.id ?: "theme"
        setOnClickListener { choose(id) }
        setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_UP && Ui.isActivateKey(code)) {
                choose(id)
                true
            } else false
        }
    }

    private fun choose(id: String) {
        shell.model.edit { NativeCore.setSetting(it, "window.style", id) }
    }

    /**
     * A small window in the style: frame, title bar with its three buttons, a selected row and two
     * lines of content. "Follow the LX theme" is drawn in the LX theme's own colours.
     */
    private inner class Miniature(private val style: WindowStyle?, private val active: Boolean) : View(shell.activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE }
        private val r = RectF()
        private val bar = RectF()
        private val path = android.graphics.Path()
        private val lx: LxTheme get() = lxTheme

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val c = style?.colors ?: lx.colors
            val k = 0.45f  // the miniature is drawn at a bit under half size
            val radius = theme.dp((style?.cornerDp ?: 12f) * k + 2f)
            val chamfer = theme.dp((style?.chamferDp ?: 0f) * k)
            r.set(theme.dp(2f), theme.dp(2f), width - theme.dp(2f), height - theme.dp(2f))
            com.lxlauncher.ui.windows.windowPath(path, r, radius, chamfer)
            paint.color = c.panel
            canvas.drawPath(path, paint)
            // Title bar.
            val titleH = theme.dp((style?.titleHeightDp ?: 44f) * k)
            bar.set(r.left, r.top, r.right, r.top + titleH)
            val titleFill = style?.titleFill?.let { LxTheme.withAlpha(it, maxOf(style.titleFillAlpha, 0.35f)) }
            if (titleFill != null) {
                canvas.save()
                canvas.clipPath(path)
                paint.color = titleFill
                canvas.drawRect(bar, paint)
                canvas.restore()
            }
            style?.titleLine?.let {
                stroke.strokeWidth = theme.dp(1f)
                stroke.color = it
                canvas.drawLine(bar.left, bar.bottom, bar.right, bar.bottom, stroke)
            }
            // Title text stand-in and the three buttons.
            paint.color = c.text
            r.set(bar.left + theme.dp(8f), bar.centerY() - theme.dp(2f), bar.left + theme.dp(44f), bar.centerY() + theme.dp(2f))
            canvas.drawRoundRect(r, theme.dp(2f), theme.dp(2f), paint)
            val dots = style?.buttons == com.lxlauncher.ui.theme.CaptionKind.DOTS
            val colors = if (style == null) listOf(lx.textPrimary, lx.textPrimary, lx.textPrimary)
                else listOf(style.minimizeDot ?: style.minimize, style.maximize, style.close)
            var x = bar.right - theme.dp(10f)
            for (i in 2 downTo 0) {
                paint.color = colors[i]
                if (dots || (i == 2 && style?.closeFilled == true)) canvas.drawCircle(x, bar.centerY(), theme.dp(3.5f), paint)
                else {
                    stroke.color = colors[i]
                    stroke.strokeWidth = theme.dp(1.5f)
                    if (style?.buttons == com.lxlauncher.ui.theme.CaptionKind.BOXED) {
                        canvas.drawRect(x - theme.dp(4f), bar.centerY() - theme.dp(4f), x + theme.dp(4f), bar.centerY() + theme.dp(4f), stroke)
                    } else {
                        canvas.drawLine(x - theme.dp(3f), bar.centerY(), x + theme.dp(3f), bar.centerY(), stroke)
                    }
                }
                x -= theme.dp(11f)
            }
            // A selected row and two lines of content.
            val selAlpha = style?.selectedAlpha ?: 0.16f
            val sel = if (style?.selectionOnAccent2 == true) c.accent2 else c.accent
            r.set(theme.dp(8f), bar.bottom + theme.dp(6f), width * 0.55f, bar.bottom + theme.dp(16f))
            paint.color = LxTheme.withAlpha(sel, maxOf(selAlpha, 0.22f))
            canvas.drawRoundRect(r, theme.dp(3f), theme.dp(3f), paint)
            if (style?.outlineSelection == true) {
                stroke.color = sel
                stroke.strokeWidth = theme.dp(1f)
                canvas.drawRoundRect(r, theme.dp(3f), theme.dp(3f), stroke)
            }
            paint.color = c.accent
            canvas.drawCircle(r.left + theme.dp(5f), r.centerY(), theme.dp(2.5f), paint)
            paint.color = LxTheme.withAlpha(c.text, 0.55f)
            for (line in 0..1) {
                val y = bar.bottom + theme.dp(24f) + line * theme.dp(9f)
                r.set(theme.dp(10f), y, width * (if (line == 0) 0.75f else 0.5f), y + theme.dp(3f))
                canvas.drawRoundRect(r, theme.dp(1.5f), theme.dp(1.5f), paint)
            }
            // Frame edge, and the in-use outline in the current accent: shape, not colour alone.
            r.set(theme.dp(2f), theme.dp(2f), width - theme.dp(2f), height - theme.dp(2f))
            com.lxlauncher.ui.windows.windowPath(path, r, radius, chamfer)
            stroke.strokeWidth = theme.dp(1f)
            stroke.color = if (style == null) lx.stroke else LxTheme.withAlpha(style.border, maxOf(style.borderAlpha, 0.5f))
            canvas.drawPath(path, stroke)
            if (active) {
                stroke.strokeWidth = theme.dp(2f)
                stroke.color = theme.accent
                r.set(theme.dp(1f), theme.dp(1f), width - theme.dp(1f), height - theme.dp(1f))
                canvas.drawRoundRect(r, radius + theme.dp(1f), radius + theme.dp(1f), stroke)
            }
        }
    }
}
