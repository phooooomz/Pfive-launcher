// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.graphics.Color
import com.lxlauncher.R
import com.lxlauncher.core.Snapshot

/** How the title bar is filled: across the whole width, as an inset card, or as a tab cut off diagonally before the buttons. */
enum class TitleShape { FULL, CARD, SLANTED_TAB }

/** Caption buttons: plain glyphs, coloured dots with a glyph on them, or classic boxed buttons. */
enum class CaptionKind { GLYPHS, DOTS, BOXED }

/** Faint decoration drawn in code: diagonal hatching in the title tab, or leaves in the window's lower corner. */
enum class Decor { NONE, HATCH, LEAVES }

/**
 * One look for LX's own windows (Settings, Files, Monitor, Notes). Each style brings its own
 * palette, which the window frame and the tool inside it are drawn with; the desktop, menus
 * and bars keep the LX colour theme. [LxTheme] checks every text colour for 4.5:1 on the opaque
 * palette and on the panel at the opacity the window draws it, and adjusts the colours that fall short.
 *
 * Measured from the reference sheet of ten "File Manager" windows, then corrected where the
 * sheet's phone mock-up (its bezel and screen corners) had been mistaken for the window.
 */
data class WindowStyle(
    val id: String,
    val nameRes: Int,
    val colors: ThemeColors,
    val cornerDp: Float,
    /** Cut corners instead of round ones, as on Cyberpunk; 0 for none. */
    val chamferDp: Float = 0f,
    val titleHeightDp: Float = 48f,
    val titleSizeSp: Float = 16f,
    val titleFill: Int?,
    val titleFillAlpha: Float = 1f,
    val titleLine: Int? = null,
    val titleLineDp: Float = 1f,
    val titleShape: TitleShape = TitleShape.FULL,
    val iconTint: Int,
    val buttons: CaptionKind = CaptionKind.GLYPHS,
    /** Colour of the minimise dot; null draws the minimise glyph alone (Material You). */
    val minimizeDot: Int? = null,
    val minimize: Int,
    val maximize: Int,
    val close: Int,
    /** Close drawn as a filled square with a white cross, as on the Linux desktop style. */
    val closeFilled: Boolean = false,
    val dotDp: Float = 16f,
    val closeStrokeDp: Float = 2f,
    /** Fill alpha over the blurred wallpaper; used by glass styles only, the others fill opaque. */
    val fillAlpha: Float = 1f,
    val glass: Boolean = false,
    /** Fill alpha by the luminance of the brightest part of the wallpaper behind the window, darkest first: pairs of (up to luminance, alpha). */
    val adaptiveAlpha: List<Pair<Double, Float>>? = null,
    val border: Int,
    val borderAlpha: Float,
    val borderDp: Float = 1f,
    /** Border alpha for windows without focus, raised so they still separate from a dark wallpaper. */
    val unfocusedBorderAlpha: Float = borderAlpha,
    val focusGlow: Boolean = false,
    val bevel: Boolean = false,
    val shadowDp: Float = 8f,
    /** Selected rows take accent2 instead of accent (Cyberpunk's cyan selection). */
    val selectionOnAccent2: Boolean = false,
    val selectedAlpha: Float = 0.16f,
    val outlineSelection: Boolean = false,
    val chipCornerDp: Float,
    val fontId: String? = null,
    val decor: Decor = Decor.NONE,
    /** Gold corner brackets on the title bar (Tactical). */
    val brackets: Boolean = false,
    val iconDp: Float = 18f,
    /** The title bar shows the tool's own colour icon instead of a tinted glyph. */
    val fullColorIcon: Boolean = false,
    /** How far the title and caption buttons of a window without focus fade, 0 for not at all. */
    val inactiveDim: Float = 0f,
    /** Tint of the window's shadow on Android 9 and later; null keeps the system's. */
    val shadowColor: Int? = null,
) {
    val dark: Boolean get() = colors.dark

    /** The fill alpha to use over a wallpaper whose brightest part has this luminance. */
    fun fillAlphaOver(luminance: Double?): Float {
        val steps = adaptiveAlpha ?: return fillAlpha
        val l = luminance ?: return steps.last().second
        return steps.firstOrNull { l <= it.first }?.second ?: steps.last().second
    }
}

object WindowStyles {
    private fun c(hex: String) = Color.parseColor(hex)
    private fun palette(id: String, ground: String, surface: String, panel: String, accent: String, accent2: String, text: String) =
        ThemeColors("window-$id", id, c(ground), c(surface), c(panel), c(accent), c(accent2), c(text))

    val all: List<WindowStyle> = listOf(
        // The reference look: navy body, a full-width title bar, a thin blue edge, round corners and
        // yellow, green and red buttons. New installs start here.
        WindowStyle(
            id = "lxmodern", nameRes = R.string.window_style_lxmodern,
            colors = palette("lxmodern", "#081322", "#13243A", "#101F33", "#4AA3FF", "#55A9FF", "#F1F5FA"),
            cornerDp = 20f, titleHeightDp = 52f, titleSizeSp = 15f, titleFill = c("#1B2D48"), iconTint = c("#AAB8CD"),
            buttons = CaptionKind.DOTS, minimizeDot = c("#FBD450"), minimize = c("#0A1626"), maximize = c("#66D77F"),
            close = c("#FD4E4A"), dotDp = 18f, fillAlpha = 1f, border = c("#55A9FF"), borderAlpha = 0.42f, borderDp = 1.5f,
            shadowDp = 16f, shadowColor = c("#000A1C"), selectedAlpha = 0.2f, chipCornerDp = 12f, iconDp = 20f,
            fullColorIcon = true, inactiveDim = 0.4f,
        ),
        WindowStyle(
            id = "minimal", nameRes = R.string.window_style_minimal,
            colors = palette("minimal", "#03080F", "#0B151F", "#060D15", "#6EBEF7", "#1E8BF7", "#EEF6FB"),
            cornerDp = 12f, titleFill = c("#111B27"), titleLine = c("#2E3A46"), iconTint = c("#6EBEF7"),
            minimize = c("#E2F2FA"), maximize = c("#E2F2FA"), close = c("#F8392F"), closeStrokeDp = 3f,
            border = c("#6EBEF7"), borderAlpha = 0.22f, unfocusedBorderAlpha = 0.43f, shadowDp = 8f,
            selectedAlpha = 0.12f, chipCornerDp = 7f,
        ),
        WindowStyle(
            id = "glass", nameRes = R.string.window_style_glass,
            colors = palette("glass", "#071A3D", "#183370", "#213F7C", "#9DCDFF", "#57CDEA", "#F5F9FF"),
            cornerDp = 14f, titleHeightDp = 52f, titleFill = c("#B9D3FF"), titleFillAlpha = 0.08f, titleLine = c("#86A6DE"),
            iconTint = c("#CFE3FA"), buttons = CaptionKind.DOTS, minimizeDot = c("#7FA3F0"),
            minimize = c("#071A3D"), maximize = c("#57CDEA"), close = c("#FF6B6B"), dotDp = 18f,
            fillAlpha = 0.70f, glass = true,
            adaptiveAlpha = listOf(0.12 to 0.70f, 0.22 to 0.80f, 0.35 to 0.87f, 0.53 to 0.90f, 1.0 to 0.94f),
            border = c("#E2EEFF"), borderAlpha = 0.42f, shadowDp = 16f, selectedAlpha = 0.14f, chipCornerDp = 10f,
        ),
        WindowStyle(
            id = "cyberpunk", nameRes = R.string.window_style_cyberpunk,
            colors = palette("cyberpunk", "#04010E", "#0E0426", "#060316", "#F72AE8", "#04F4FF", "#EAFBFF"),
            cornerDp = 6f, chamferDp = 10f, titleFill = c("#170630"), titleLine = c("#7A1A80"), titleShape = TitleShape.SLANTED_TAB,
            iconTint = c("#F72AE8"), minimize = c("#04F4FF"), maximize = c("#04F4FF"), close = c("#FB34E7"),
            border = c("#F72AE8"), borderAlpha = 0.9f, borderDp = 1.5f, unfocusedBorderAlpha = 0.66f, focusGlow = true,
            shadowDp = 4f, selectionOnAccent2 = true, selectedAlpha = 0.12f, outlineSelection = true, chipCornerDp = 6f,
            decor = Decor.HATCH,
        ),
        WindowStyle(
            id = "rounded", nameRes = R.string.window_style_rounded,
            colors = palette("rounded", "#050D19", "#0A1626", "#0F1C2F", "#4AA3FC", "#86C8FD", "#F1F5FA"),
            cornerDp = 20f, titleHeightDp = 54f, titleFill = c("#1A2A42"), titleShape = TitleShape.CARD, iconTint = c("#A9D2F8"),
            buttons = CaptionKind.DOTS, minimizeDot = c("#FBD450"), minimize = c("#0A1626"), maximize = c("#66D77F"),
            close = c("#FD4E4A"), dotDp = 16f, border = c("#A9BCD4"), borderAlpha = 0.5f, shadowDp = 12f,
            selectedAlpha = 0.21f, chipCornerDp = 12f,
        ),
        WindowStyle(
            id = "tactical", nameRes = R.string.window_style_tactical,
            colors = palette("tactical", "#02060A", "#070D11", "#0B1217", "#F8C91E", "#F6A523", "#F3F5F6"),
            cornerDp = 10f, titleFill = c("#121B21"), titleLine = c("#8C7420"), titleShape = TitleShape.SLANTED_TAB,
            iconTint = c("#F8C91E"), minimize = c("#F8D41C"), maximize = c("#F8D41C"), close = c("#F8D41C"),
            border = c("#C9A43C"), borderAlpha = 0.55f, shadowDp = 8f, selectedAlpha = 0.22f, outlineSelection = true,
            chipCornerDp = 8f, decor = Decor.HATCH, brackets = true,
        ),
        WindowStyle(
            id = "compact", nameRes = R.string.window_style_compact,
            colors = palette("compact", "#01080D", "#0F1923", "#050C12", "#6EC4F7", "#1F86FA", "#EDF5FA"),
            cornerDp = 8f, titleHeightDp = 40f, titleSizeSp = 14f, titleFill = c("#0F1923"), titleLine = c("#374352"),
            iconTint = c("#6EC4F7"), minimize = c("#EDF5FA"), maximize = c("#EDF5FA"), close = c("#F59A2E"), closeStrokeDp = 1.5f,
            border = c("#8A9AA8"), borderAlpha = 0.35f, unfocusedBorderAlpha = 0.54f, shadowDp = 4f, chipCornerDp = 6f,
        ),
        WindowStyle(
            id = "material", nameRes = R.string.window_style_material,
            colors = palette("material", "#DAD3FB", "#F8F3FE", "#E7E5FC", "#B8185E", "#094CFC", "#191C3A"),
            cornerDp = 14f, titleFill = null, titleFillAlpha = 0f, iconTint = c("#094CFC"), buttons = CaptionKind.DOTS,
            minimizeDot = null, minimize = c("#191C3A"), maximize = c("#70D179"), close = c("#E5403A"), dotDp = 18f,
            fillAlpha = 1f, border = c("#FFFFFF"), borderAlpha = 0.55f, shadowDp = 6f, selectedAlpha = 0.08f, chipCornerDp = 10f,
        ),
        WindowStyle(
            id = "retro", nameRes = R.string.window_style_retro,
            colors = palette("retro", "#DDD4BF", "#F7F0DE", "#F0E8D5", "#3B3428", "#7A5610", "#1C1A16"),
            cornerDp = 4f, titleFill = c("#EAE1CF"), titleLine = c("#B9B5A9"), titleLineDp = 2f, iconTint = c("#1C1A16"),
            buttons = CaptionKind.BOXED, minimize = c("#1C1A16"), maximize = c("#1C1A16"), close = c("#1C1A16"),
            fillAlpha = 1f, border = c("#6E695F"), borderAlpha = 1f, bevel = true, shadowDp = 0f,
            selectedAlpha = 0.24f, chipCornerDp = 6f,
        ),
        WindowStyle(
            id = "nature", nameRes = R.string.window_style_nature,
            colors = palette("nature", "#04150B", "#183522", "#0C2414", "#7EDD91", "#A9C79F", "#EEF6EF"),
            cornerDp = 14f, titleFill = c("#16361F"), titleFillAlpha = 0.45f, titleLine = c("#203F2B"), iconTint = c("#82E395"),
            minimize = c("#B2EEBE"), maximize = c("#B2EEBE"), close = c("#B2EEBE"),
            fillAlpha = 0.80f, glass = true, adaptiveAlpha = listOf(0.22 to 0.80f, 0.35 to 0.84f, 0.53 to 0.88f, 1.0 to 0.91f),
            border = c("#A9C79F"), borderAlpha = 0.55f, shadowDp = 10f, selectedAlpha = 0.24f, chipCornerDp = 10f,
            decor = Decor.LEAVES,
        ),
        WindowStyle(
            id = "linux", nameRes = R.string.window_style_linux,
            colors = palette("linux", "#020D1B", "#05111E", "#0A1522", "#4AA3F8", "#80D4FE", "#F4F8FC"),
            cornerDp = 10f, titleFill = c("#142030"), titleLine = c("#27394D"), iconTint = c("#4AA3F8"),
            minimize = c("#E6EEF6"), maximize = c("#E6EEF6"), close = c("#F5403B"), closeFilled = true,
            border = c("#8FB4D9"), borderAlpha = 0.32f, unfocusedBorderAlpha = 0.44f, shadowDp = 10f,
            selectedAlpha = 0.25f, chipCornerDp = 8f,
        ),
    )

    /** A preset by id, or null for "follow the LX theme" and unknown ids. */
    fun byId(id: String?): WindowStyle? = all.firstOrNull { it.id == id }

    /** The window style chosen in settings, the custom one included, or null for "follow the LX theme". */
    fun resolve(s: Snapshot?): WindowStyle? {
        val id = s?.setting("window.style")
        return if (id == "custom") custom(s) else byId(id)
    }

    /** Fixed hues for the custom style's accent; each still goes through the contrast checks in [LxTheme]. */
    val customAccents: List<Pair<String, Int>> = listOf(
        "blue" to c("#4AA3FF"), "cyan" to c("#22D3EE"), "teal" to c("#2DD4BF"), "green" to c("#4ADE80"),
        "amber" to c("#FBBF24"), "orange" to c("#FB923C"), "magenta" to c("#F472B6"), "purple" to c("#A78BFA"),
        "red" to c("#F87171"),
    )

    /** A base preset with the user's own corner, edge, title bar, buttons, shadow and accent. */
    fun custom(s: Snapshot): WindowStyle {
        val base = byId(s.setting("window.custom.base")) ?: all.first()
        val accent = customAccents.firstOrNull { it.first == s.setting("window.custom.accent") }?.second
        val colors = base.colors.let {
            ThemeColors("window-custom", "custom", it.ground, it.surface, it.panel, accent ?: it.accent, it.accent2, it.text)
        }
        val buttons = when (s.setting("window.custom.buttons")) {
            "glyphs" -> CaptionKind.GLYPHS
            "boxed" -> CaptionKind.BOXED
            else -> CaptionKind.DOTS
        }
        // Dots need dot colours; a base drawn with glyphs or boxes lends its glyph colours the other way round.
        val dots = buttons == CaptionKind.DOTS
        val baseDots = base.buttons == CaptionKind.DOTS
        return base.copy(
            id = "custom", nameRes = R.string.window_style_custom, colors = colors,
            cornerDp = s.int("window.custom.corner", 20).toFloat(),
            titleHeightDp = s.int("window.custom.caption", 52).toFloat(),
            titleSizeSp = s.int("window.custom.titleSize", 15).toFloat(),
            buttons = buttons,
            minimizeDot = if (dots) base.minimizeDot ?: c("#FBD450") else base.minimizeDot,
            minimize = if (dots && !baseDots) c("#0A1626") else if (!dots && baseDots) base.colors.text else base.minimize,
            maximize = if (dots && !baseDots) c("#66D77F") else if (!dots && baseDots) base.colors.text else base.maximize,
            close = if (dots && !baseDots) c("#FD4E4A") else base.close,
            border = accent ?: base.border,
            borderDp = s.float("window.custom.border", 1.5f),
            shadowDp = s.int("window.custom.shadow", 12).toFloat(),
            iconDp = s.int("window.custom.iconSize", 20).toFloat(),
            inactiveDim = if (s.bool("window.custom.dimInactive")) 0.4f else 0f,
        )
    }
}
