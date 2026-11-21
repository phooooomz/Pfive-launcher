// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.tedit.TeditTool

/**
 * Tedit's terminal with the on-screen keyboard up (14 November 2026), run with `-e only tedit:ime`. The owner saw the
 * keyboard cover the terminal when Tedit was full screen. For each window state the terminal is opened and given the
 * keyboard, and the whole terminal panel, its key row included, must end above the keyboard. Then the keyboard goes
 * down and up 50 times in a full-screen window: each time the panel must come back above it, and the shell must last.
 */
internal class TeditKeyboardChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit) {
    private val ctx = inst.targetContext

    private fun <T> main(block: () -> T): T {
        var r: Result<T>? = null
        inst.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (main(ok)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /** The keyboard's top edge in screen pixels, or null while it is down. */
    private fun imeTop(a: LauncherActivity): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val decor = a.window.decorView
        val i = decor.rootWindowInsets ?: return null
        if (!i.isVisible(WindowInsets.Type.ime())) return null
        val h = i.getInsets(WindowInsets.Type.ime()).bottom
        if (h <= 0) return null
        val loc = IntArray(2).also { decor.getLocationOnScreen(it) }
        return loc[1] + decor.height - h
    }

    private fun bottomOnScreen(v: android.view.View): Int {
        val loc = IntArray(2).also { v.getLocationOnScreen(it) }
        return loc[1] + v.height
    }

    fun run() {
        if (Build.VERSION.SDK_INT < 30) { info("INFO keyboard checks need Android 11 or later"); return }
        // The keyboard only comes to the window that has focus. LX is the phone's Home, so it is reached as Home: started
        // in a task of its own, it would go behind the Home instance at the runner's HOME press and never get the keyboard.
        var resumed: LauncherActivity? = null
        val app = ctx.applicationContext as android.app.Application
        val watch = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(x: android.app.Activity) { if (x is LauncherActivity) resumed = x }
            override fun onActivityCreated(x: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(x: android.app.Activity) {}
            override fun onActivityPaused(x: android.app.Activity) {}
            override fun onActivityStopped(x: android.app.Activity) {}
            override fun onActivitySaveInstanceState(x: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(x: android.app.Activity) {}
        }
        main { app.registerActivityLifecycleCallbacks(watch) }
        check("LX starts as Home and has the window focus for the keyboard checks") {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(ctx, LauncherActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // The runner presses HOME once, four seconds in; HOME also sends the keyboard down, so the checks start after it.
            // Run this part without the runner's repeated HOME (no KEEP_HOME).
            SystemClock.sleep(6000)
            inst.waitForIdleSync()
            // The input focus is taken later, with Tedit open (an app window on LX's virtual display may hold it now).
            if (!waitFor(10_000) { resumed != null }) "LX did not come to the front" else null
        }
        main { app.unregisterActivityLifecycleCallbacks(watch) }
        val a = resumed ?: return
        val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
        fun toolOf(): TeditTool? = (f.get(a) as com.lxlauncher.ui.windows.WindowLayer).focusedTool() as? TeditTool
        var tool: TeditTool? = null
        check("Tedit opens") {
            main { a.openTool(TeditTool.KIND, null) }
            if (!waitFor(8000) { toolOf() != null }) return@check "no Tedit window"
            tool = main { toolOf() }
            null
        }
        val t = tool ?: return
        val id = main { a.model.snapshot.windows.first { it.kind == TeditTool.KIND && it.focused }.id }
        // An app window in the person's layout runs on LX's own virtual display and can hold the input focus; a finger
        // on LX's screen takes it back, and so does this tap on the Tedit window's title bar, which only focuses it.
        check("LX's screen has the input focus") {
            if (main { a.hasWindowFocus() }) return@check null
            val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
            val dp = ctx.resources.displayMetrics.density
            val pt = main {
                val layer = f.get(a) as com.lxlauncher.ui.windows.WindowLayer
                (0 until layer.childCount).map { layer.getChildAt(it) }.filterIsInstance<com.lxlauncher.ui.windows.WindowLayer.WindowFrame>()
                    .firstOrNull { it.item.id == id }?.let { fr -> val loc = IntArray(2); fr.getLocationOnScreen(loc); (loc[0] + fr.width / 2f) to (loc[1] + 18 * dp) }
            } ?: return@check "no Tedit window frame"
            val t0 = SystemClock.uptimeMillis()
            inst.sendPointerSync(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, pt.first, pt.second, 0))
            inst.sendPointerSync(android.view.MotionEvent.obtain(t0, t0 + 60, android.view.MotionEvent.ACTION_UP, pt.first, pt.second, 0))
            if (!waitFor(4000) { a.hasWindowFocus() }) "LX's window still has no focus after a tap on Tedit's title bar" else null
        }
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun keyboardDown() {
            main { imm.hideSoftInputFromWindow(a.window.decorView.windowToken, 0) }
            waitFor(4000) { imeTop(a) == null }
            SystemClock.sleep(400)
        }
        try {
            check("the terminal panel starts a shell for the keyboard checks") {
                main { t.showTerminal() }
                if (!waitFor(60_000) { (t.terminal.current?.prompts ?: 0) > 0 }) "no prompt" else null
            }
            for (state in listOf("fullscreen", "maximized", "floating")) {
                check("Tedit $state: the terminal and its key row stay above the keyboard") {
                    keyboardDown()
                    main { a.model.edit { h -> NativeCore.setWindowState(h, id, state) } }
                    inst.waitForIdleSync()
                    SystemClock.sleep(600)
                    main { t.terminal.focusInput() }
                    if (!waitFor(5000) { imeTop(a) != null }) return@check "the keyboard did not come up"
                    // The window follows the keyboard after it has settled.
                    SystemClock.sleep(900)
                    inst.waitForIdleSync()
                    val top = main { imeTop(a) } ?: return@check "the keyboard went down"
                    val panel = main { bottomOnScreen(t.terminal.view) }
                    val term = main { bottomOnScreen(t.terminal.terminal) }
                    val rows = main { t.terminal.terminal.height } / ctx.resources.displayMetrics.density
                    info("INFO $state: keyboard top $top px, terminal bottom $term px, panel bottom $panel px, terminal ${rows.toInt()} dp tall")
                    // What a person sees, keyboard included, for the report: LX's cache, ime/<state>.png.
                    runCatching {
                        val dir = java.io.File(ctx.cacheDir, "ime").apply { mkdirs() }
                        inst.uiAutomation.takeScreenshot()?.let { b -> java.io.File(dir, "$state.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                    }
                    when {
                        panel > top + 2 -> "the keyboard covers ${panel - top} px of the terminal panel"
                        rows < 40 -> "the terminal is only ${rows.toInt()} dp tall above the keyboard"
                        else -> null
                    }
                }
            }
            check("full screen: the keyboard goes down and up 50 times, the terminal comes back above it each time and its shell lasts") {
                keyboardDown()
                main { a.model.edit { h -> NativeCore.setWindowState(h, id, "fullscreen") } }
                inst.waitForIdleSync()
                SystemClock.sleep(600)
                val s = main { t.terminal.current } ?: return@check "no session"
                var worst = 0
                for (i in 1..50) {
                    main { t.terminal.focusInput() }
                    if (!waitFor(5000) { imeTop(a) != null }) return@check "cycle $i: the keyboard did not come up"
                    // Within a second of the keyboard settling the panel must sit above it.
                    val ok = waitFor(1500) { val top = imeTop(a); top != null && bottomOnScreen(t.terminal.view) <= top + 2 }
                    if (!ok) worst = maxOf(worst, main { (imeTop(a)?.let { bottomOnScreen(t.terminal.view) - it }) ?: 0 })
                    if (!ok) return@check "cycle $i: the keyboard still covers $worst px of the terminal"
                    keyboardDown()
                }
                val alive = main { s.alive && t.terminal.sessions().contains(s) }
                main { s.send("echo lx-ime-ok-\$((6*7))\r") }
                val answered = waitFor(10_000) { s.allText(s.native.rows, s.native.cols).contains("lx-ime-ok-42") }
                when {
                    !alive -> "the shell ended"
                    !answered -> "the shell did not answer"
                    else -> null
                }
            }
        } finally {
            keyboardDown()
            main { a.model.snapshot.windows.filter { it.kind == TeditTool.KIND }.forEach { w -> a.model.edit { h -> NativeCore.closeWindow(h, w.id) } } }
            inst.waitForIdleSync()
        }
    }
}
