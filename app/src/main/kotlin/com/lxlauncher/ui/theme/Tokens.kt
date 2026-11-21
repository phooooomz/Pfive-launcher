// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.animation.PathInterpolator
import com.lxlauncher.core.Snapshot

/**
 * One colour theme. It holds only the base colours; every other role (muted
 * text, borders, status colours, text on the accent) is derived in [LxTheme]
 * and checked for contrast there, so no theme needs hand-tuned extras.
 */
class ThemeColors(
    val id: String,
    val name: String,
    val ground: Int,
    val surface: Int,
    val panel: Int,
    val accent: Int,
    val accent2: Int,
    val text: Int,
) {
    val dark: Boolean get() = Contrast.luminance(ground) < 0.4
}

object Themes {
    private fun c(hex: String) = Color.parseColor(hex)

    val plasma = ThemeColors("plasma", "Plasma Night", c("#050816"), c("#0B1120"), c("#111827"), c("#00D9FF"), c("#FF2FCF"), c("#EAF7FF"))
    val all = listOf(
        plasma,
        ThemeColors("arctic", "Arctic Glass", c("#071522"), c("#0D2333"), c("#153347"), c("#64E8FF"), c("#A5F3FC"), c("#F0FBFF")),
        ThemeColors("graphite", "Graphite Mono", c("#101114"), c("#191B20"), c("#23262C"), c("#D6D9E0"), c("#9299A6"), c("#F4F5F7")),
        ThemeColors("solarized", "Solarized Dusk", c("#002B36"), c("#073642"), c("#0B4650"), c("#2AA198"), c("#B58900"), c("#FDF6E3")),
        ThemeColors("rose", "Rose Quartz", c("#1B1019"), c("#2A1726"), c("#382033"), c("#FF79B0"), c("#C4A1FF"), c("#FFF1F7")),
        ThemeColors("forest", "Forest Terminal", c("#07140F"), c("#10231A"), c("#173326"), c("#62E6A7"), c("#B7E36B"), c("#E9FFF2")),
        ThemeColors("amber", "Amber Workstation", c("#191207"), c("#2A1E0B"), c("#382A10"), c("#FFC857"), c("#FF8A4C"), c("#FFF6E5")),
        ThemeColors("oceanic", "Oceanic Blue", c("#081322"), c("#10233A"), c("#18314D"), c("#65AFFF"), c("#56E0D0"), c("#EFF7FF")),
        ThemeColors("lavender", "Lavender KDE", c("#120E20"), c("#201832"), c("#2D2344"), c("#B69CFF"), c("#69D7FF"), c("#F7F2FF")),
        ThemeColors("paper", "Paper Light", c("#F2F4F8"), c("#FFFFFF"), c("#E5EAF1"), c("#2864C5"), c("#7A3DB8"), c("#17202D")),
    )

    fun byId(id: String?): ThemeColors = all.firstOrNull { it.id == id } ?: plasma

    /** Neon accents a user can pin over any theme (the brief's palette). */
    fun pinnedAccent(id: String?): Int? = when (id) {
        "cyan" -> c("#00D9FF")
        "magenta" -> c("#FF2FCF")
        "blue" -> c("#238BFF")
        "purple" -> c("#9256FF")
        else -> null
    }
}

/** WCAG 2 relative luminance and contrast, and colour moves that reach a target ratio. */
object Contrast {
    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    fun luminance(color: Int): Double =
        0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** Moves [color] toward black or white, whichever the backgrounds call for, until it reaches [target] on all of them. */
    fun ensure(color: Int, target: Double, vararg backgrounds: Int): Int {
        val towards = if (backgrounds.all { luminance(it) < 0.4 }) Color.WHITE else Color.BLACK
        var c = color
        var step = 0
        while (backgrounds.any { ratio(c, it) < target } && step < 20) {
            step++
            c = mix(color, towards, step / 20f)
        }
        return c
    }

    /** [color] drawn at [alpha] over [under], with the alpha rounded to a byte as the drawing code does. */
    fun over(color: Int, alpha: Float, under: Int): Int = mix(under, color, (alpha * 255).toInt() / 255f)

    /** The grey whose relative luminance is [l]. */
    fun grey(l: Double): Int {
        val s = if (l <= 0.0031308) 12.92 * l else 1.055 * Math.pow(l, 1 / 2.4) - 0.055
        val v = Math.round(s * 255).toInt().coerceIn(0, 255)
        return Color.rgb(v, v, v)
    }

    /** The lighter of [text] blended into [ground] that still reaches [target] on every background. */
    fun muted(text: Int, ground: Int, target: Double, vararg backgrounds: Int): Int {
        var t = 0.45f
        while (t > 0f) {
            val c = mix(text, ground, t)
            if (backgrounds.all { ratio(c, it) >= target }) return c
            t -= 0.03f
        }
        return text
    }
}

enum class PerfMode { PERFORMANCE, BALANCED, QUALITY }

/**
 * Resolved theme for one snapshot of the settings. Views read colours, type and sizes only from here.
 * With a [windowStyle] it is the theme of LX's windows instead: the style's palette, corners and
 * selection, with the user's accessibility and performance settings still applied.
 */
class LxTheme(context: Context, s: Snapshot?, val windowStyle: WindowStyle? = null) {
    private val density = context.resources.displayMetrics.density
    val scale: Float = (s?.float("ui.scale", 1f) ?: 1f).coerceIn(0.85f, 1.3f)

    val colors: ThemeColors = windowStyle?.colors ?: Themes.byId(s?.setting("theme.palette"))
    /** A pinned neon accent belongs to the LX theme; a window style keeps its own. */
    private val pinnedAccent: Int? = if (windowStyle != null) null else Themes.pinnedAccent(s?.setting("theme.accent"))
    val dark: Boolean = colors.dark
    val highContrast = s?.bool("a11y.highContrast") == true

    // Surfaces
    val background: Int = colors.ground
    val surface: Int = colors.surface
    val panel: Int = colors.panel

    // Text in two tiers: 4.5:1 on every surface, 7:1 with high contrast.
    private val textTarget = if (highContrast) 7.0 else 4.5
    val textPrimary: Int = if (highContrast) (if (dark) Color.WHITE else Color.parseColor("#0B0F14")) else colors.text
    /**
     * What text sits on besides the opaque palette. In a styled window: the panel at the opacity the window
     * draws it, over the brightest wallpaper that opacity is used for (the darkest, for a light palette).
     * Glass styles fill more densely over lighter wallpaper, so each step has its own entry, and where
     * Nature's leaves overlap in the corner, up to three layers of their colour lie under the text.
     * In the LX theme: menus and dialogs at their minimum opacity over a white wallpaper. Windows and bars
     * fill no lighter than that ([glassOpacityOver]). A light theme fills at least [LIGHT_PANEL_FLOOR],
     * so its case is the panel at that opacity over black.
     */
    private val backdrops: IntArray = run {
        if (highContrast) return@run IntArray(0)
        fun behind(l: Double) = if (dark) Contrast.grey(l) else Color.BLACK
        val st = windowStyle ?: return@run intArrayOf(
            Contrast.over(colors.panel, if (dark) MENU_OPACITY else LIGHT_PANEL_FLOOR, behind(1.0)))
        val steps = st.adaptiveAlpha ?: listOf(1.0 to st.fillAlpha)
        val list = steps.map { (l, a) -> Contrast.over(colors.panel, a, behind(l)) }.toMutableList()
        // Without the blurred layer a glass window fills opaque, which is colors.panel, already in [surfaces].
        if (st.decor == Decor.LEAVES) list += list.map { Contrast.over(colors.accent2, LEAF_LAYERS, it) }
        list.toIntArray()
    }
    private val surfaces: IntArray = intArrayOf(colors.panel, colors.surface) + backdrops
    /** Every surface, alone and under the fills a row shows: keyboard focus in [focus], and hover in the text colour. */
    private fun onRows(focus: Int): IntArray =
        surfaces + surfaces.map { Contrast.over(focus, FOCUS_FILL_ALPHA, it) } + surfaces.map { Contrast.over(textPrimary, HOVER_ALPHA, it) }
    /** [color] moved until it reads on every surface, also under a row's focus or hover fill. */
    private fun ensureOnRows(color: Int): Int {
        var c = Contrast.ensure(color, textTarget, *surfaces)
        // The focus fill is made of the accent itself, so moving the accent changes its own fill; repeat until it settles.
        for (round in 0 until 8) {
            val next = Contrast.ensure(color, textTarget, *onRows(c))
            if (next == c) break
            c = next
        }
        return c
    }

    /** Accent for text, icons, lines and fills: the theme's own, or a neon the user pinned over it. */
    val accent: Int = ensureOnRows(pinnedAccent ?: colors.accent)
    /** The fill under a row with keyboard focus; the 2 dp ring around it is what marks focus. */
    val focusFill: Int = withAlpha(accent, FOCUS_FILL_ALPHA)
    val textMuted: Int = Contrast.muted(textPrimary, colors.ground, textTarget, *onRows(accent))
    /** Second accent, used only for the active workspace. */
    val accent2: Int = Contrast.ensure(
        if (pinnedAccent == colors.accent2) colors.accent else colors.accent2,
        textTarget, *onRows(accent))
    /** Text and icons placed on an accent fill. */
    val onAccent: Int = if (Contrast.ratio(accent, Color.WHITE) >= Contrast.ratio(accent, colors.ground)) Color.WHITE
        else colors.ground
    /** Text and icons placed on an accent2 fill (the active workspace). */
    val onAccent2: Int = if (Contrast.ratio(accent2, Color.WHITE) >= Contrast.ratio(accent2, colors.ground)) Color.WHITE
        else colors.ground

    val success: Int = Contrast.ensure(Color.parseColor(if (dark) "#3DDC97" else "#1B7F4B"), textTarget, *onRows(accent))
    val danger: Int = Contrast.ensure(Color.parseColor(if (dark) "#FF5C7A" else "#B4233A"), textTarget, *onRows(accent))

    val perf: PerfMode = when (s?.setting("perf.mode")) {
        "performance" -> PerfMode.PERFORMANCE
        "quality" -> PerfMode.QUALITY
        else -> PerfMode.BALANCED
    }
    val neon = s?.bool("theme.neon") != false && perf != PerfMode.PERFORMANCE && !highContrast &&
        (windowStyle == null || windowStyle.focusGlow)
    val blur = s?.bool("theme.blur") != false && perf != PerfMode.PERFORMANCE && !highContrast &&
        (windowStyle == null || windowStyle.glass)
    /**
     * The colour a window's content sits on when the window's body hides the wallpaper (every style without glass, and
     * glass with blur off), or 0 when the wallpaper shows through. Lists fade into it at their edges (see StableScrollView).
     */
    val windowBody: Int = if (blur) 0 else panel or (0xFF shl 24)
    val blurRadiusDp: Int = when (perf) {
        PerfMode.QUALITY -> s?.int("theme.blurRadius", 14) ?: 14
        else -> ((s?.int("theme.blurRadius", 14) ?: 14) * 0.75f).toInt()
    }
    /** Light panels over a dark wallpaper turn grey, so light themes keep them nearly opaque. */
    private val panelFloor = if (dark) 0f else LIGHT_PANEL_FLOOR
    val panelOpacity: Float = when {
        // Opaque: without blur nothing else hides the widgets behind a window.
        highContrast -> 1f
        !blur -> maxOf(s?.float("theme.panelOpacity", 0.74f) ?: 0.74f, 0.86f, panelFloor)
        else -> maxOf(s?.float("theme.panelOpacity", 0.74f) ?: 0.74f, panelFloor)
    }
    /** Luminance a dark theme's glass may not exceed: a menu at its least opacity over white, where the text colours were tested. */
    private val glassLimit = Contrast.luminance(Contrast.over(colors.panel, MENU_OPACITY, Color.WHITE))
    /**
     * Glass opacity for wallpaper whose brightest part has luminance step/20, rounded up: the panel opacity,
     * raised until the fill is no lighter than [glassLimit]. Light themes and high contrast keep the panel opacity.
     */
    private val glassSteps = FloatArray(21) { step ->
        if (!dark || highContrast) return@FloatArray panelOpacity
        val under = Contrast.grey(step / 20.0)
        var a = panelOpacity
        while (a < MENU_OPACITY && Contrast.luminance(Contrast.over(colors.panel, a, under)) > glassLimit) a += 0.01f
        minOf(a, maxOf(panelOpacity, MENU_OPACITY))
    }
    val cornerRadius: Float = dp(windowStyle?.cornerDp ?: (s?.int("theme.cornerRadius", 12) ?: 12).toFloat())
    /** Corners of chips, list rows and other small shapes inside a surface. */
    val chipRadius: Float = windowStyle?.chipCornerDp?.let { dp(it) } ?: cornerRadius * 0.6f
    /** Corners of large floating cards (the Applications menu, the bars): a little rounder than panels. */
    val radiusL: Float = (cornerRadius * 1.5f).coerceAtMost(dp(28f))
    /** Corner radius that turns a shape of height [h] into a pill, within the user's corner setting. */
    fun pill(h: Float): Float = minOf(h / 2f, cornerRadius * 1.67f)

    val animations: Boolean = when (s?.setting("motion.mode")) {
        "off" -> false
        "on" -> true
        else -> ValueAnimator.areAnimatorsEnabled()
    } && perf != PerfMode.PERFORMANCE

    val largeTargets = s?.bool("a11y.largeTargets") == true
    val touchTarget: Int = dp(if (largeTargets) 56f else 48f).toInt()
    /** Desktop-sized controls in the top panel and the bottom bar; Larger touch targets brings back the full size. */
    val barTarget: Int = if (largeTargets) touchTarget else dp(36f).toInt()
    val dockTarget: Int = if (largeTargets) touchTarget else dp(42f).toInt()

    /** Hairline around panels: the accent at low strength, or the text colour with high contrast. */
    val stroke: Int = withAlpha(if (highContrast) textPrimary else accent, if (highContrast) 0.6f else 0.22f)
    val hover: Int = withAlpha(textPrimary, HOVER_ALPHA)
    /** Quiet fill behind a control that needs a container (the Applications button). */
    val container: Int = withAlpha(textPrimary, 0.10f)
    /** Colour selected rows are built from: the accent, or accent2 for styles that select in their second colour. */
    val selectionBase: Int = if (windowStyle?.selectionOnAccent2 == true) accent2 else accent
    /** Strength of the selection fill: the style's own, lowered where the text colour would no longer read on it. */
    val selectedAlpha: Float = run {
        var a = windowStyle?.selectedAlpha ?: 0.16f
        while (a > 0.06f && surfaces.any { Contrast.ratio(textPrimary, Contrast.over(selectionBase, a, it)) < textTarget }) a -= 0.02f
        a
    }
    val selected: Int = withAlpha(selectionBase, selectedAlpha)
    /** Selected rows also get an outline in [selectionBase] (Cyberpunk, Tactical), so selection is not colour alone. */
    val selectionOutline: Boolean = windowStyle?.outlineSelection == true
    /**
     * Label colour on a selected row: the accent where it reaches the text target on the selection fill
     * over every surface, else the text colour. Secondary text on a selected row uses the text colour too.
     */
    val selectedText: Int =
        if (surfaces.all { Contrast.ratio(accent, Contrast.over(selectionBase, selectedAlpha, it)) >= textTarget }) accent else textPrimary
    val track: Int = withAlpha(textPrimary, 0.12f)
    /** Dims the desktop behind dialogs and menus. Light themes dim with ink, not with their paper. */
    fun scrim(alpha: Float): Int = withAlpha(if (dark) colors.ground else Color.parseColor("#17202D"), alpha)

    // Type
    val fontId: String = LxFonts.option(windowStyle?.fontId ?: s?.setting("ui.font")).id
    val font: Typeface = LxFonts.typeface(context, fontId, medium = false)
    val fontMedium: Typeface = LxFonts.typeface(context, fontId, medium = true)
    /** Vivid icons for folders, files and launcher tools, or the line glyphs. */
    val vividIcons: Boolean = s?.setting("theme.iconStyle") != "line"

    // Type scale in sp, multiplied by the user's UI scale.
    val textDisplay = 34f * scale
    val textTitle = 16f * scale
    val textBody = 14f * scale
    val textLabel = 12f * scale
    val textCaption = 11f * scale

    // Motion
    val durationShort = if (animations) 150L else 0L
    val durationMedium = if (animations) 220L else 0L
    val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    fun dp(v: Float): Float = v * density * scale
    fun dpi(v: Float): Int = (v * density * scale + 0.5f).toInt()

    fun panelFill(): Int = withAlpha(panel, panelOpacity)

    /**
     * Opacity of LX-theme glass over blurred wallpaper whose brightest part has [luminance] (null: unknown,
     * counted as white). Over light wallpaper a dark theme's glass grows denser than the panel opacity, so
     * text keeps the contrast it has on menus.
     */
    fun glassOpacityOver(luminance: Double?): Float =
        glassSteps[Math.ceil((luminance ?: 1.0).coerceIn(0.0, 1.0) * 20).toInt()]

    companion object {
        /** Strength of the focus fill. The 2 dp ring marks focus; the fill only tints the row. */
        const val FOCUS_FILL_ALPHA = 0.10f
        /** Strength of the hover fill, in the text colour. */
        const val HOVER_ALPHA = 0.08f
        /** The least opacity menus and dialogs draw their glass at, whatever the panel opacity setting. */
        const val MENU_OPACITY = 0.92f
        /** The least opacity of a light theme's panels; lower, they turn grey over dark wallpaper. */
        const val LIGHT_PANEL_FLOOR = 0.9f
        /** Strength of each of Nature's leaves, which overlap up to three deep in the window's corner. */
        const val LEAF_ALPHA = 0.06f
        private val LEAF_LAYERS = 1f - (1f - LEAF_ALPHA) * (1f - LEAF_ALPHA) * (1f - LEAF_ALPHA)

        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    }
}
