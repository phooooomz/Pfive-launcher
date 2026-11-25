// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import com.lxlauncher.ui.theme.Contrast
import com.lxlauncher.ui.theme.LxFonts

/**
 * Tedit draws in its own palette, not in the launcher's: an editor needs the same calm ground and the same syntax
 * colours whatever wallpaper or window style LX uses. The Dark palette is the one DESIGN.md sets out; Light,
 * Midnight and OLED keep its structure. Every text colour is checked against every surface it sits on.
 */
class TeditTheme(context: Context, prefs: TeditPrefs, val uiFont: Typeface, val uiFontMedium: Typeface) {
    enum class Palette(val id: String) { DARK("dark"), LIGHT("light"), MIDNIGHT("midnight"), OLED("oled") }

    enum class Accent(val id: String, val color: Int) {
        PURPLE("purple", Color.parseColor("#8657F5")),
        BLUE("blue", Color.parseColor("#369CFF")),
        TEAL("teal", Color.parseColor("#2BB7A3")),
        AMBER("amber", Color.parseColor("#E5A23B")),
        ROSE("rose", Color.parseColor("#E5687F")),
    }

    /** A colour theme from an installed VS Code extension, when one is chosen and still installed. */
    val ext: com.lxlauncher.tedit.ext.VsTheme? = prefs.extensionTheme()?.let { com.lxlauncher.tedit.ext.ExtensionStore.get(context).theme(it) }
    val palette: Palette = Palette.entries.firstOrNull { it.id == prefs.string(TeditPrefs.THEME) } ?: Palette.DARK
    val accentChoice: Accent = Accent.entries.firstOrNull { it.id == prefs.string(TeditPrefs.ACCENT) } ?: Accent.PURPLE
    val dark = ext?.dark ?: (palette != Palette.LIGHT)

    private val density = context.resources.displayMetrics.density
    /** The UI scaling setting, applied to every size below except the editor's own font size. */
    val scale: Float = prefs.float(TeditPrefs.UI_SCALE).coerceIn(0.85f, 1.3f)
    val compact = prefs.string(TeditPrefs.DENSITY) == "compact"
    val animations = prefs.bool(TeditPrefs.ANIMATIONS)

    // Surfaces, from the ground up.
    val bg: Int
    val panel: Int
    val active: Int
    val border: Int
    val text: Int
    val text2: Int

    init {
        val e = ext
        if (e != null) {
            // Workbench colours may be translucent; each is laid over the surface it is drawn on.
            fun solid(c: Int?, under: Int): Int? = c?.let { if (Color.alpha(it) == 255) it else Contrast.over(it or 0xFF000000.toInt(), Color.alpha(it) / 255f, under) }
            bg = solid(e.color("editor.background"), if (e.dark) c("#1E1E1E") else c("#FFFFFF")) ?: if (e.dark) c("#1E1E1E") else c("#FFFFFF")
            panel = solid(e.color("sideBar.background", "activityBar.background", "editorGroupHeader.tabsBackground", "panel.background"), bg)
                ?: Contrast.over(if (e.dark) Color.BLACK else Color.WHITE, 0.25f, bg)
            active = solid(e.color("list.activeSelectionBackground", "tab.activeBackground", "list.hoverBackground"), panel)
                ?: Contrast.over(if (e.dark) Color.WHITE else Color.BLACK, 0.08f, panel)
            text = solid(e.color("editor.foreground", "foreground"), bg) ?: if (e.dark) c("#D4D4D4") else c("#1F1F1F")
            text2 = solid(e.color("descriptionForeground", "sideBar.foreground", "editorLineNumber.activeForeground"), bg) ?: Contrast.over(text, 0.72f, bg)
            border = solid(e.color("sideBar.border", "panel.border", "editorGroup.border", "contrastBorder", "widget.border"), bg) ?: Contrast.over(text, 0.16f, bg)
        } else when (palette) {
            Palette.DARK -> { bg = c("#0D111C"); panel = c("#141A29"); active = c("#1D2437"); border = c("#293248"); text = c("#F1F4FA"); text2 = c("#A6B0C3") }
            Palette.LIGHT -> { bg = c("#F6F7FB"); panel = c("#FFFFFF"); active = c("#E9ECF5"); border = c("#D3D9E6"); text = c("#161B28"); text2 = c("#4A5468") }
            Palette.MIDNIGHT -> { bg = c("#0A0F20"); panel = c("#0F1730"); active = c("#18234A"); border = c("#24315C"); text = c("#E9EEFB"); text2 = c("#9EABC8") }
            Palette.OLED -> { bg = c("#000000"); panel = c("#08090C"); active = c("#15171D"); border = c("#262932"); text = c("#EDEEF2"); text2 = c("#A2A6B2") }
        }
    }

    private val surfaces = intArrayOf(bg, panel, active)

    /** The accent as a line, caret or indicator colour: 3:1 against every surface is enough for those. */
    /** The theme's own accent when an extension theme names one, else the chosen accent. */
    private val accentBase: Int = ext?.color("focusBorder", "activityBarBadge.background", "button.background", "progressBar.background")
        ?.let { it or 0xFF000000.toInt() } ?: accentChoice.color
    val accent: Int = Contrast.ensure(accentBase, 3.0, *surfaces)
    /** The accent where it is read as text (links, active labels). */
    val accentText: Int = Contrast.ensure(accentBase, 4.5, *surfaces)
    /** Fill of primary buttons; the label on it ([onAccent]) reaches 4.5:1. */
    val accentFill: Int
    val onAccent: Int
    init {
        var fill = accentBase
        var on = if (Contrast.ratio(fill, Color.WHITE) >= Contrast.ratio(fill, Color.BLACK)) Color.WHITE else Color.BLACK
        // Darken (or lighten) the fill a little at a time until its label reads.
        var guard = 0
        while (Contrast.ratio(fill, on) < 4.5 && guard++ < 40) {
            fill = if (on == Color.WHITE) Contrast.over(Color.BLACK, 0.06f, fill) else Contrast.over(Color.WHITE, 0.06f, fill)
        }
        accentFill = fill
        onAccent = on
    }
    /** Second accent from DESIGN.md: the blue, used for information and for the second half of the logo's gradient. */
    val secondary: Int = Contrast.ensure(c("#369CFF"), 4.5, *surfaces)
    val success: Int = Contrast.ensure(if (dark) c("#3DDC97") else c("#18794A"), 4.5, *surfaces)
    val warning: Int = Contrast.ensure(if (dark) c("#F6BC58") else c("#8F5A00"), 4.5, *surfaces)
    val error: Int = Contrast.ensure(if (dark) c("#F07178") else c("#B4232F"), 4.5, *surfaces)
    val textMuted: Int = Contrast.ensure(text2, 4.5, *surfaces)

    /** Selected text in the editor: the accent, weak enough that syntax colours still read on it. */
    val selection: Int = ext?.color("editor.selectionBackground")?.let { if (Color.alpha(it) == 255) withAlpha(it, 0.6f) else it }
        ?: withAlpha(accentBase, if (dark) 0.34f else 0.24f)
    val activeLine: Int = ext?.color("editor.lineHighlightBackground")?.let { if (Color.alpha(it) == 255) withAlpha(it, 0.5f) else it }
        ?: withAlpha(text, if (dark) 0.045f else 0.05f)
    val hover: Int = withAlpha(text, 0.07f)
    val pressed: Int = withAlpha(text, 0.12f)
    val selectedRow: Int = withAlpha(accentBase, if (dark) 0.20f else 0.14f)
    val scrim: Int = withAlpha(if (dark) Color.BLACK else c("#0E1320"), 0.55f)
    val findMatch: Int = withAlpha(c("#F6BC58"), if (dark) 0.30f else 0.38f)
    val findCurrent: Int = withAlpha(c("#F6BC58"), if (dark) 0.62f else 0.70f)
    val bracketBox: Int = withAlpha(text2, 0.55f)
    val indentGuide: Int = withAlpha(text2, if (dark) 0.16f else 0.22f)
    val indentGuideActive: Int = withAlpha(text2, if (dark) 0.42f else 0.50f)
    val gutterText: Int = Contrast.ensure(withOpaque(text2, 0.78f, bg), 3.0, bg)
    val gutterActive: Int = text
    val diffAdded: Int = withAlpha(if (dark) c("#3DDC97") else c("#18794A"), if (dark) 0.16f else 0.14f)
    val diffRemoved: Int = withAlpha(if (dark) c("#F07178") else c("#B4232F"), if (dark) 0.16f else 0.12f)

    /** Syntax colours by [Token] kind, each at 4.5:1 on the editor ground and the active line. */
    val syntax: IntArray = IntArray(Token.COUNT).also { s ->
        // The token colours of VS Code's Dark+ and Light+ themes, which people who edit code know on sight.
        val base = if (dark) intArrayOf(
            c("#D4D4D4"), // PLAIN
            c("#569CD6"), // KEYWORD
            c("#4EC9B0"), // TYPE
            c("#CE9178"), // STRING
            c("#B5CEA8"), // NUMBER
            c("#6A9955"), // COMMENT
            c("#DCDCAA"), // FUNCTION
            c("#D4D4D4"), // OPERATOR
            c("#4FC1FF"), // CONSTANT
            c("#569CD6"), // TAG
            c("#9CDCFE"), // ATTRIBUTE
            c("#DCDCAA"), // ANNOTATION
            c("#D4D4D4"), // PUNCT
            c("#9CDCFE"), // VARIABLE
            c("#569CD6"), // HEADING
            c("#B5CEA8"), // INSERTED
            c("#CE9178"), // DELETED
            c("#F44747"), // INVALID
            c("#C586C0"), // CONTROL
        ) else intArrayOf(
            c("#000000"), c("#0000FF"), c("#267F99"), c("#A31515"), c("#098658"), c("#008000"), c("#795E26"),
            c("#000000"), c("#0070C1"), c("#800000"), c("#E50000"), c("#795E26"), c("#000000"), c("#001080"),
            c("#0451A5"), c("#098658"), c("#A31515"), c("#CD3131"), c("#AF00DB"),
        )
        // An extension theme's token colours, by the TextMate scope that stands for each of Tedit's kinds.
        ext?.let { e ->
            val scopes = arrayOf(
                null, "keyword", "entity.name.type", "string", "constant.numeric", "comment", "entity.name.function",
                "keyword.operator", "constant.language", "entity.name.tag", "entity.other.attribute-name", "meta.decorator",
                "punctuation", "variable", "markup.heading", "markup.inserted", "markup.deleted", "invalid", "keyword.control",
            )
            for (i in base.indices) {
                val scope = scopes.getOrNull(i)
                val col = if (scope == null) e.color("editor.foreground") else e.foreground(scope)
                    ?: if (i == Token.TYPE) e.foreground("support.type") ?: e.foreground("storage.type") else null
                    // Kinds a theme often leaves to the plain text colour.
                    ?: if (i == Token.PUNCT || i == Token.OPERATOR || i == Token.VARIABLE) e.color("editor.foreground") else null
                if (col != null) base[i] = col or 0xFF000000.toInt()
            }
        }
        val lineGround = Contrast.over(text, if (dark) 0.045f else 0.05f, bg)
        val selGround = Contrast.over(accentBase, if (dark) 0.34f else 0.24f, bg)
        // Text on a selection may fall to 3:1: the selection is a passing state, and the shape of the code still reads.
        for (i in s.indices) s[i] = Contrast.ensure(Contrast.ensure(base[i], 4.5, bg, lineGround), 3.0, selGround)
    }

    // Type
    // The interface font is the one LX uses (passed in), so Tedit's panels read like the rest of the launcher.
    /** The code font the Font Family setting names; JetBrains Mono by default, chosen for its clear 0/O and 1/l/I. */
    val codeFont: Typeface = codeTypeface(context, prefs.string(TeditPrefs.FONT_FAMILY))

    // Sized like VS Code on a phone: text a step under the system's 14 sp body, bars and rows close to VS Code's own.
    val textBody = 13f * scale
    val textSmall = 12f * scale
    val textCaption = 11f * scale
    val textTitle = 14f * scale

    // Sizes. Buttons and list rows keep 40 dp (36 compact) to the finger; inline icon buttons 36 dp (32 compact).
    val touch: Int = dpi(if (compact) 36f else 40f)
    val control: Int = dpi(if (compact) 32f else 36f)
    val headerH: Int = dpi(if (compact) 38f else 42f)
    val tabH: Int = dpi(if (compact) 30f else 32f)
    val statusH: Int = dpi(if (compact) 22f else 24f)
    val railW: Int = dpi(if (compact) 40f else 44f)
    val rowH: Int = dpi(if (compact) 28f else 32f)
    val radius: Float = dp(6f)
    val radiusL: Float = dp(10f)
    val iconSize: Float = when (prefs.string(TeditPrefs.ICON_SIZE)) { "small" -> 16f; "large" -> 22f; else -> 18f }
    val stroke: Int = maxOf(1, dpi(1f))

    val durShort: Long = if (animations) 140L else 0L
    val durMedium: Long = if (animations) 200L else 0L

    fun dp(v: Float): Float = v * density * scale
    fun dpi(v: Float): Int = (v * density * scale + 0.5f).toInt()
    /** Real dp, not scaled: the editor font has its own size setting. */
    fun rawDp(v: Float): Float = v * density

    companion object {
        fun c(hex: String) = Color.parseColor(hex)
        fun withAlpha(color: Int, alpha: Float): Int =
            Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        private fun withOpaque(color: Int, alpha: Float, under: Int) = Contrast.over(color, alpha, under)

        val CODE_FONTS = listOf("jetbrainsmono" to "JetBrains Mono", "notosansmono" to "Noto Sans Mono", "system" to "System monospace")

        fun codeTypeface(context: Context, id: String): Typeface = when (id) {
            "system" -> Typeface.MONOSPACE
            "notosansmono", "jetbrainsmono" -> runCatching { LxFonts.typeface(context, id, medium = false) }.getOrDefault(Typeface.MONOSPACE)
            else -> runCatching { LxFonts.typeface(context, "jetbrainsmono", medium = false) }.getOrDefault(Typeface.MONOSPACE)
        }
    }
}
