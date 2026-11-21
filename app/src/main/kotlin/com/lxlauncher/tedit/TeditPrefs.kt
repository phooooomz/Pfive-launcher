// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.content.SharedPreferences
import com.lxlauncher.R

/**
 * Tedit's settings: one table of [Spec]s that the Settings page draws and the editor reads. Values live in the
 * "tedit" preferences file; a value of the wrong type or out of range reads as the default.
 */
class TeditPrefs(context: Context) {
    enum class Cat(val title: Int) {
        GENERAL(R.string.tedit_set_general),
        EDITOR(R.string.tedit_set_editor),
        APPEARANCE(R.string.tedit_set_appearance),
        FILES(R.string.tedit_set_files),
        WORKSPACE(R.string.tedit_set_workspace),
        SEARCH(R.string.tedit_set_search),
        TERMINAL(R.string.tedit_set_terminal),
        SCM(R.string.tedit_set_scm),
        RUN(R.string.tedit_set_run),
        EXTENSIONS(R.string.tedit_set_extensions),
        PRIVACY(R.string.tedit_set_privacy),
        ADVANCED(R.string.tedit_set_advanced),
    }

    sealed class Kind {
        object Bool : Kind()
        /** [labels] holds string resources, or plain strings for proper names such as fonts. */
        class Choice(val values: List<String>, val labels: List<Any>) : Kind()
        class Num(val min: Float, val max: Float, val step: Float, val unit: String = "") : Kind()
    }

    class Spec(val key: String, val cat: Cat, val title: Int, val summary: Int, val kind: Kind, val default: Any)

    private val sp: SharedPreferences = context.getSharedPreferences("tedit", Context.MODE_PRIVATE)
    /**
     * Every Tedit window hears every change. The listeners used to belong to each window's own TeditPrefs, so a
     * language or theme chosen in a Tedit on one workspace never reached a Tedit open on another (14 November 2026).
     */
    private val listeners get() = sharedListeners

    fun spec(key: String): Spec = specOf(key)

    fun bool(key: String): Boolean = sp.all[key] as? Boolean ?: spec(key).default as Boolean

    fun string(key: String): String {
        val s = spec(key)
        val v = sp.all[key] as? String ?: return s.default as String
        val k = s.kind
        return if (k is Kind.Choice && v !in k.values) s.default as String else v
    }

    fun float(key: String): Float {
        val s = spec(key)
        val k = s.kind as Kind.Num
        val v = (sp.all[key] as? Number)?.toFloat() ?: return (s.default as Number).toFloat()
        return if (v.isNaN() || v < k.min || v > k.max) (s.default as Number).toFloat() else v
    }

    fun int(key: String): Int = float(key).toInt()

    fun isDefault(key: String): Boolean = !sp.contains(key)

    fun set(key: String, value: Any) {
        val e = sp.edit()
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is String -> e.putString(key, value)
            is Number -> e.putFloat(key, value.toFloat())
        }
        e.apply()
        listeners.toList().forEach { it(key) }
    }

    fun reset(keys: Collection<String>) {
        val e = sp.edit()
        keys.forEach { e.remove(it) }
        e.apply()
        keys.forEach { k -> listeners.toList().forEach { it(k) } }
    }

    fun addListener(l: (String) -> Unit) { listeners += l }
    fun removeListener(l: (String) -> Unit) { listeners -= l }

    /** Free-form state that is not a setting: the session, recent files, command history. */
    fun raw(key: String): String? = sp.getString("state.$key", null)
    fun putRaw(key: String, value: String?) {
        sp.edit().apply { if (value == null) remove("state.$key") else putString("state.$key", value) }.apply()
    }

    companion object {
        /** Main thread only, like the windows that listen. */
        private val sharedListeners = ArrayList<(String) -> Unit>()

        // Editor
        const val AUTO_SAVE = "editor.autoSave"
        const val AUTO_SAVE_DELAY = "editor.autoSaveDelay"
        const val WORD_WRAP = "editor.wordWrap"
        const val AUTO_INDENT = "editor.autoIndent"
        const val TAB_SIZE = "editor.tabSize"
        const val INSERT_SPACES = "editor.insertSpaces"
        const val LINE_NUMBERS = "editor.lineNumbers"
        const val HIGHLIGHT_LINE = "editor.highlightLine"
        const val AUTO_CLOSE = "editor.autoClosingBrackets"
        const val BRACKET_MATCH = "editor.bracketMatching"
        const val SMOOTH_SCROLL = "editor.smoothScrolling"
        const val MINIMAP = "editor.minimap"
        const val FONT_FAMILY = "editor.fontFamily"
        const val FONT_SIZE = "editor.fontSize"
        const val LINE_HEIGHT = "editor.lineHeight"
        const val CURSOR_STYLE = "editor.cursorStyle"
        const val CURSOR_BLINK = "editor.cursorBlink"
        const val INDENT_GUIDES = "editor.indentGuides"
        const val WHITESPACE = "editor.renderWhitespace"
        const val KEY_BAR = "editor.keyBar"
        // Appearance
        const val THEME = "ui.theme"
        const val ACCENT = "ui.accent"
        const val PANEL_WIDTH = "ui.panelWidth"
        const val ICON_SIZE = "ui.iconSize"
        const val UI_SCALE = "ui.scale"
        const val ANIMATIONS = "ui.animations"
        const val DENSITY = "ui.density"
        const val PANEL_AFTER_OPEN = "ui.panelAfterOpen"
        const val RAIL = "ui.activityRail"
        // General
        const val CONFIRM_DELETE = "general.confirmDelete"
        const val HOT_EXIT = "general.keepUnsaved"
        // Files
        const val ENCODING = "files.encoding"
        const val EOL = "files.eol"
        const val TRIM_TRAILING = "files.trimTrailingWhitespace"
        const val FINAL_NEWLINE = "files.insertFinalNewline"
        const val SHOW_HIDDEN = "files.showHidden"
        const val LARGE_FILE_MB = "files.largeFileMb"
        // Workspace
        const val RESTORE = "workspace.restore"
        const val EXCLUDE = "workspace.exclude"
        const val SORT = "workspace.sort"
        const val FOLDERS_FIRST = "workspace.foldersFirst"
        // Search
        const val SEARCH_MAX = "search.maxResults"
        const val SEARCH_MAX_KB = "search.maxFileKb"
        const val SEARCH_SKIP_EXCLUDED = "search.useExclude"
        // Terminal
        const val TERM_FONT = "terminal.fontSize"
        const val TERM_SCROLLBACK = "terminal.scrollback"
        const val TERM_SHELL = "terminal.shell"
        // Source control
        const val SCM_ENABLED = "scm.enabled"
        const val SCM_UNTRACKED = "scm.untracked"
        const val SCM_DECORATIONS = "scm.decorations"
        // Run
        const val RUN_SAVE_FIRST = "run.saveBeforeRun"
        const val RUN_CLEAR = "run.clearOutput"
        const val RUN_TIMEOUT = "run.timeoutMinutes"
        // Extensions
        const val EXT_DISABLED = "extensions.disabled"
        const val THEME_EXTENSION = "ui.themeExtension"
        const val MARKETPLACE = "extensions.marketplace"
        // Privacy
        const val KEEP_HISTORY = "privacy.keepHistory"
        // Advanced
        const val UNDO_MB = "advanced.undoMb"
        const val HIGHLIGHT_MAX = "advanced.highlightMaxLine"
        const val LANGUAGE_SERVERS = "editor.languageServers"
        const val SUGGEST = "editor.suggestOnType"
        /** Tedit's own language: "system" (the phone's), or a language tag Tedit has text for. */
        const val LANGUAGE = "general.language"

        private fun choice(vararg pairs: Pair<String, Any>) = Kind.Choice(pairs.map { it.first }, pairs.map { it.second })

        val all: List<Spec> = listOf(
            // Each language by its own name, so a person who cannot read the current one still finds theirs.
            Spec(LANGUAGE, Cat.GENERAL, R.string.tedit_s_language, R.string.tedit_s_language_d,
                choice(TeditLocaleContext.SYSTEM to R.string.tedit_v_lang_system, "in" to "Bahasa Indonesia", "en" to "English", "zh-CN" to "中文（简体）"),
                TeditLocaleContext.SYSTEM),
            Spec(HOT_EXIT, Cat.GENERAL, R.string.tedit_s_hot_exit, R.string.tedit_s_hot_exit_d, Kind.Bool, true),
            Spec(CONFIRM_DELETE, Cat.GENERAL, R.string.tedit_s_confirm_delete, R.string.tedit_s_confirm_delete_d, Kind.Bool, true),

            Spec(AUTO_SAVE, Cat.EDITOR, R.string.tedit_s_auto_save, R.string.tedit_s_auto_save_d,
                choice("off" to R.string.tedit_v_off, "delay" to R.string.tedit_v_after_delay, "focus" to R.string.tedit_v_on_leave), "off"),
            Spec(AUTO_SAVE_DELAY, Cat.EDITOR, R.string.tedit_s_auto_save_delay, 0, Kind.Num(500f, 10000f, 500f, "ms"), 1500f),
            Spec(WORD_WRAP, Cat.EDITOR, R.string.tedit_s_word_wrap, R.string.tedit_s_word_wrap_d, Kind.Bool, false),
            Spec(AUTO_INDENT, Cat.EDITOR, R.string.tedit_s_auto_indent, R.string.tedit_s_auto_indent_d, Kind.Bool, true),
            Spec(TAB_SIZE, Cat.EDITOR, R.string.tedit_s_tab_size, 0, Kind.Num(1f, 8f, 1f), 4f),
            Spec(INSERT_SPACES, Cat.EDITOR, R.string.tedit_s_insert_spaces, R.string.tedit_s_insert_spaces_d, Kind.Bool, true),
            Spec(LINE_NUMBERS, Cat.EDITOR, R.string.tedit_s_line_numbers, 0,
                choice("on" to R.string.tedit_v_on, "relative" to R.string.tedit_v_relative, "off" to R.string.tedit_v_off), "on"),
            Spec(HIGHLIGHT_LINE, Cat.EDITOR, R.string.tedit_s_highlight_line, 0, Kind.Bool, true),
            Spec(AUTO_CLOSE, Cat.EDITOR, R.string.tedit_s_auto_close, R.string.tedit_s_auto_close_d, Kind.Bool, true),
            Spec(BRACKET_MATCH, Cat.EDITOR, R.string.tedit_s_bracket_match, 0, Kind.Bool, true),
            Spec(SMOOTH_SCROLL, Cat.EDITOR, R.string.tedit_s_smooth_scroll, R.string.tedit_s_smooth_scroll_d, Kind.Bool, true),
            Spec(MINIMAP, Cat.EDITOR, R.string.tedit_s_minimap, R.string.tedit_s_minimap_d, Kind.Bool, false),
            Spec(FONT_FAMILY, Cat.EDITOR, R.string.tedit_s_font_family, 0,
                Kind.Choice(TeditTheme.CODE_FONTS.map { it.first }, TeditTheme.CODE_FONTS.map { it.second }), "jetbrainsmono"),
            Spec(FONT_SIZE, Cat.EDITOR, R.string.tedit_s_font_size, 0, Kind.Num(9f, 28f, 1f, "sp"), 13f),
            Spec(LINE_HEIGHT, Cat.EDITOR, R.string.tedit_s_line_height, 0, Kind.Num(1.1f, 2.2f, 0.1f, "×"), 1.4f),
            Spec(CURSOR_STYLE, Cat.EDITOR, R.string.tedit_s_cursor_style, 0,
                choice("line" to R.string.tedit_v_cursor_line, "block" to R.string.tedit_v_cursor_block, "underline" to R.string.tedit_v_cursor_underline), "line"),
            Spec(CURSOR_BLINK, Cat.EDITOR, R.string.tedit_s_cursor_blink, 0, Kind.Bool, true),
            Spec(INDENT_GUIDES, Cat.EDITOR, R.string.tedit_s_indent_guides, 0, Kind.Bool, true),
            Spec(WHITESPACE, Cat.EDITOR, R.string.tedit_s_whitespace, 0,
                choice("none" to R.string.tedit_v_none, "trailing" to R.string.tedit_v_trailing, "all" to R.string.tedit_v_all), "none"),
            Spec(KEY_BAR, Cat.EDITOR, R.string.tedit_s_key_bar, R.string.tedit_s_key_bar_d, Kind.Bool, true),

            Spec(THEME, Cat.APPEARANCE, R.string.tedit_s_theme, 0,
                choice("dark" to R.string.tedit_v_dark, "light" to R.string.tedit_v_light, "midnight" to R.string.tedit_v_midnight, "oled" to R.string.tedit_v_oled), "dark"),
            Spec(ACCENT, Cat.APPEARANCE, R.string.tedit_s_accent, 0,
                choice("purple" to R.string.tedit_v_purple, "blue" to R.string.tedit_v_blue, "teal" to R.string.tedit_v_teal,
                    "amber" to R.string.tedit_v_amber, "rose" to R.string.tedit_v_rose), "purple"),
            Spec(PANEL_WIDTH, Cat.APPEARANCE, R.string.tedit_s_panel_width, R.string.tedit_s_panel_width_d, Kind.Num(200f, 480f, 10f, "dp"), 280f),
            Spec(ICON_SIZE, Cat.APPEARANCE, R.string.tedit_s_icon_size, 0,
                choice("small" to R.string.tedit_v_small, "medium" to R.string.tedit_v_medium, "large" to R.string.tedit_v_large), "medium"),
            Spec(UI_SCALE, Cat.APPEARANCE, R.string.tedit_s_ui_scale, 0, Kind.Num(0.85f, 1.3f, 0.05f, "×"), 1f),
            Spec(ANIMATIONS, Cat.APPEARANCE, R.string.tedit_s_animations, R.string.tedit_s_animations_d, Kind.Bool, true),
            Spec(DENSITY, Cat.APPEARANCE, R.string.tedit_s_density, 0,
                choice("comfortable" to R.string.tedit_v_comfortable, "compact" to R.string.tedit_v_compact), "comfortable"),
            Spec(RAIL, Cat.APPEARANCE, R.string.tedit_s_rail, R.string.tedit_s_rail_d,
                choice("auto" to R.string.tedit_v_auto, "show" to R.string.tedit_v_show, "hide" to R.string.tedit_v_hide), "auto"),
            Spec(PANEL_AFTER_OPEN, Cat.APPEARANCE, R.string.tedit_s_panel_after_open, R.string.tedit_s_panel_after_open_d,
                choice("close" to R.string.tedit_v_close_panel, "keep" to R.string.tedit_v_keep_panel), "close"),

            Spec(ENCODING, Cat.FILES, R.string.tedit_s_encoding, R.string.tedit_s_encoding_d,
                Kind.Choice(TextCodec.entries.map { it.id }, TextCodec.entries.map { it.label }), TextCodec.UTF8.id),
            Spec(EOL, Cat.FILES, R.string.tedit_s_eol, R.string.tedit_s_eol_d, choice("lf" to "LF", "crlf" to "CRLF"), "lf"),
            Spec(TRIM_TRAILING, Cat.FILES, R.string.tedit_s_trim, 0, Kind.Bool, false),
            Spec(FINAL_NEWLINE, Cat.FILES, R.string.tedit_s_final_newline, 0, Kind.Bool, false),
            Spec(SHOW_HIDDEN, Cat.FILES, R.string.tedit_s_show_hidden, 0, Kind.Bool, true),
            Spec(LARGE_FILE_MB, Cat.FILES, R.string.tedit_s_large_file, R.string.tedit_s_large_file_d, Kind.Num(2f, 64f, 2f, "MB"), 16f),

            Spec(RESTORE, Cat.WORKSPACE, R.string.tedit_s_restore, R.string.tedit_s_restore_d, Kind.Bool, true),
            Spec(SORT, Cat.WORKSPACE, R.string.tedit_s_sort, 0,
                choice("name" to R.string.tedit_v_name, "type" to R.string.tedit_v_type, "modified" to R.string.tedit_v_modified), "name"),
            Spec(FOLDERS_FIRST, Cat.WORKSPACE, R.string.tedit_s_folders_first, 0, Kind.Bool, true),
            Spec(EXCLUDE, Cat.WORKSPACE, R.string.tedit_s_exclude, R.string.tedit_s_exclude_d, Kind.Bool, true),

            Spec(SEARCH_MAX, Cat.SEARCH, R.string.tedit_s_search_max, 0, Kind.Num(100f, 20000f, 100f), 2000f),
            Spec(SEARCH_MAX_KB, Cat.SEARCH, R.string.tedit_s_search_max_kb, R.string.tedit_s_search_max_kb_d, Kind.Num(64f, 8192f, 64f, "KB"), 2048f),
            Spec(SEARCH_SKIP_EXCLUDED, Cat.SEARCH, R.string.tedit_s_search_exclude, R.string.tedit_s_search_exclude_d, Kind.Bool, true),

            Spec(TERM_FONT, Cat.TERMINAL, R.string.tedit_s_term_font, 0, Kind.Num(9f, 22f, 1f, "sp"), 12f),
            Spec(TERM_SHELL, Cat.TERMINAL, R.string.tedit_s_term_shell, R.string.tedit_s_term_shell_d,
                choice("debian" to R.string.tedit_linux_shell_debian, "android" to R.string.tedit_linux_shell_android), "debian"),
            Spec(TERM_SCROLLBACK, Cat.TERMINAL, R.string.tedit_s_term_scrollback, R.string.tedit_s_term_scrollback_d, Kind.Num(500f, 20000f, 500f), 5000f),

            Spec(SCM_ENABLED, Cat.SCM, R.string.tedit_s_scm_enabled, R.string.tedit_s_scm_enabled_d, Kind.Bool, true),
            Spec(SCM_UNTRACKED, Cat.SCM, R.string.tedit_s_scm_untracked, 0, Kind.Bool, true),
            Spec(SCM_DECORATIONS, Cat.SCM, R.string.tedit_s_scm_decorations, R.string.tedit_s_scm_decorations_d, Kind.Bool, true),

            Spec(RUN_SAVE_FIRST, Cat.RUN, R.string.tedit_s_run_save, 0, Kind.Bool, true),
            Spec(RUN_CLEAR, Cat.RUN, R.string.tedit_s_run_clear, 0, Kind.Bool, true),
            Spec(RUN_TIMEOUT, Cat.RUN, R.string.tedit_s_run_timeout, R.string.tedit_s_run_timeout_d, Kind.Num(0f, 120f, 1f, "min"), 10f),

            Spec(MARKETPLACE, Cat.PRIVACY, R.string.tedit_s_marketplace, R.string.tedit_s_marketplace_d,
                choice("ask" to R.string.tedit_v_ask_first, "on" to R.string.tedit_v_allowed, "off" to R.string.tedit_v_never), "ask"),
            Spec(KEEP_HISTORY, Cat.PRIVACY, R.string.tedit_s_keep_history, R.string.tedit_s_keep_history_d, Kind.Bool, true),

            Spec(UNDO_MB, Cat.ADVANCED, R.string.tedit_s_undo_mb, R.string.tedit_s_undo_mb_d, Kind.Num(4f, 128f, 4f, "MB"), 32f),
            Spec(LANGUAGE_SERVERS, Cat.EDITOR, R.string.tedit_s_language_servers, R.string.tedit_s_language_servers_d, Kind.Bool, true),
            Spec(SUGGEST, Cat.EDITOR, R.string.tedit_s_suggest, R.string.tedit_s_suggest_d, Kind.Bool, true),
            Spec(HIGHLIGHT_MAX, Cat.ADVANCED, R.string.tedit_s_highlight_max, R.string.tedit_s_highlight_max_d, Kind.Num(1000f, 100000f, 1000f), 20000f),
        )

        /** Extensions keep their on/off state in this one string; it is not a row on the Settings page. */
        private val hidden = listOf(Spec(EXT_DISABLED, Cat.EXTENSIONS, 0, 0, Kind.Choice(emptyList(), emptyList()), ""))

        private val byKey: Map<String, Spec> = (all + hidden).associateBy { it.key }

        fun specOf(key: String): Spec = byKey[key] ?: error("unknown Tedit setting $key")
    }

    /** Ids of disabled built-in extensions. */
    /** The installed extension's colour theme in use ("<extension id>/<label>"), or null for Tedit's own. */
    fun extensionTheme(): String? = sp.getString(THEME_EXTENSION, null)?.takeIf { it.isNotEmpty() }

    fun setExtensionTheme(key: String?) {
        sp.edit().putString(THEME_EXTENSION, key ?: "").apply()
        // Heard as a theme change, so the window repaints in it.
        listeners.toList().forEach { it(THEME) }
    }

    fun disabledExtensions(): Set<String> =
        (sp.getString(EXT_DISABLED, "") ?: "").split(',').filter { it.isNotBlank() }.toSet()

    fun setExtensionEnabled(id: String, on: Boolean) {
        val now = disabledExtensions().toMutableSet()
        if (on) now -= id else now += id
        sp.edit().putString(EXT_DISABLED, now.sorted().joinToString(",")).apply()
        listeners.toList().forEach { it(EXT_DISABLED) }
    }
}
