// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import android.view.KeyEvent

/**
 * The keys an app in an LX window may receive. LX forwards only these and lxdb injects only these (the
 * list in lxdb/src/rules.cpp is the same), because lxdb injects with the shell's rights. Android acts on some keys for the whole
 * phone whatever display they name: Power, Sleep, Wakeup, End call, Assist, Recents, Notifications,
 * Print Screen, volume and media keys, and the Meta and Alt+Tab shortcuts. Typing, editing, navigation,
 * function, numpad and gamepad keys go to the focused window of the display they name.
 *
 * Back is allowed too: on a display of its own it goes to that display's app, and LX sends it from onBack.
 */
object EmbedKeys {
    fun allowed(keyCode: Int, metaState: Int, action: Int): Boolean {
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        if (!allowedCode(keyCode)) return false
        // Shortcuts fire on the press; a release with a modifier still held must reach the app.
        if (action == KeyEvent.ACTION_DOWN) {
            if ((metaState and KeyEvent.META_META_MASK) != 0) return false
            if (keyCode == KeyEvent.KEYCODE_TAB && (metaState and KeyEvent.META_ALT_MASK) != 0) return false
        }
        return true
    }

    private fun allowedCode(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_POUND,
        in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER,
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL,
        in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_AT,
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE,
        in KeyEvent.KEYCODE_ESCAPE..KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_FUNCTION,
        in KeyEvent.KEYCODE_BREAK..KeyEvent.KEYCODE_FORWARD,
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
        in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16,
        in KeyEvent.KEYCODE_ZENKAKU_HANKAKU..KeyEvent.KEYCODE_KANA,
        in KeyEvent.KEYCODE_CUT..KeyEvent.KEYCODE_PASTE -> true
        else -> false
    }
}
