// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.view.KeyEvent

/**
 * One thing Tedit can do, reachable from the main menu, the command palette and a keyboard shortcut alike, so the
 * three always agree on its name, whether it is available, and why not.
 */
class Command(
    val id: String,
    val title: Int,
    val glyph: TIcon.Glyph? = null,
    /** Shown in menus and the palette, such as "Ctrl+S". */
    val shortcut: String? = null,
    /** The reason it cannot run now (a string resource), or 0 when it can. */
    val unavailable: () -> Int = { 0 },
    val run: () -> Unit,
)

/** A key combination, matched against key events. */
data class Chord(val key: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    fun matches(e: KeyEvent) = e.keyCode == key && e.isCtrlPressed == ctrl && e.isShiftPressed == shift && e.isAltPressed == alt

    val label: String get() = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        if (alt) append("Alt+")
        append(keyName(key))
    }

    companion object {
        fun keyName(code: Int): String = when (code) {
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_BACKSLASH -> "\\"
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_PAGE_UP -> "PgUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PgDn"
            else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let { if (it.length == 1) it else it.lowercase().replaceFirstChar { c -> c.uppercase() } }
        }
    }
}

/** Commands by id, with their key bindings. */
class CommandRegistry {
    private val byId = LinkedHashMap<String, Command>()
    private val bindings = ArrayList<Pair<Chord, String>>()

    fun add(c: Command, vararg chords: Chord) {
        byId[c.id] = c
        chords.forEach { bindings += it to c.id }
    }

    operator fun get(id: String): Command? = byId[id]
    val all: Collection<Command> get() = byId.values

    fun forKey(e: KeyEvent): Command? = bindings.firstOrNull { it.first.matches(e) }?.let { byId[it.second] }

    /** Runs [id] when it is available; returns the reason (string resource) when it is not, else 0. */
    fun run(id: String): Int {
        val c = byId[id] ?: return 0
        val why = c.unavailable()
        if (why != 0) return why
        c.run()
        return 0
    }
}
