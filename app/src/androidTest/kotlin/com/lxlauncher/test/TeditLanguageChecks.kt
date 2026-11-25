// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lxlauncher.LauncherActivity
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import com.lxlauncher.tedit.SettingsPage
import com.lxlauncher.tedit.TeditLocaleContext
import com.lxlauncher.tedit.TeditPrefs
import com.lxlauncher.tedit.TeditTool
import java.util.Locale

/**
 * Tedit's language setting (14 November 2026), run with `-e only tedit:lang`. For each choice the whole Tedit window is
 * read, with Settings, a side panel and the terminal open: no text, hint or spoken label may still be in another of
 * Tedit's languages. LX around Tedit keeps the phone's language. Then the language is switched 50 times while a
 * terminal session runs: the session must survive every switch and still answer, and the heap must not grow.
 * The person's own choice is put back at the end.
 */
internal class TeditLanguageChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit) {
    private val ctx = inst.targetContext

    private fun res(tag: String): Resources {
        if (tag == TeditLocaleContext.SYSTEM) return ctx.resources
        val c = Configuration(ctx.resources.configuration)
        c.setLocale(Locale.forLanguageTag(tag))
        return ctx.createConfigurationContext(c).resources
    }

    /** Every Tedit string without placeholders: those are the ones that reach the screen word for word. */
    private val ids: List<Int> = R.string::class.java.fields.filter { it.name.startsWith("tedit_") }.map { it.getInt(null) }
        .filter { id -> !ctx.resources.getString(id).contains('%') }

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

    private fun texts(v: View, out: MutableList<String>) {
        if (v is TextView) {
            v.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            v.hint?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        v.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    /** Texts on screen that are another language's version of a Tedit string, and not this language's version of any. */
    private fun leftovers(root: View, want: String): List<String> {
        val expected = res(want)
        val mine = ids.mapTo(HashSet()) { expected.getString(it) }
        val foreign = HashSet<String>()
        for (other in listOf("en", "in", "zh-CN").map { res(it) }) for (id in ids) {
            val v = other.getString(id)
            if (v.length >= 3 && v !in mine) foreign += v
        }
        val seen = ArrayList<String>().also { texts(root, it) }
        return seen.filter { it in foreign }.distinct()
    }

    fun run() {
        // LX is reached as Home, as the person reaches it: started in a task of its own it went behind the Home instance at
        // the runner's HOME press, and the screen then showed the Home instance's Tedit, not the one under test.
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
        check("LX starts as Home for the language checks") {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(ctx, LauncherActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(6000)
            inst.waitForIdleSync()
            // These checks need LX on screen, not the keyboard focus (an app window on LX's virtual display may hold it).
            if (!waitFor(10_000) { resumed != null }) "LX did not come to the front" else null
        }
        main { app.unregisterActivityLifecycleCallbacks(watch) }
        val a = resumed ?: return
        check("a language chosen in one Tedit reaches every open Tedit") {
            val heard = ArrayList<String>()
            val other = TeditPrefs(ctx)
            val l: (String) -> Unit = { heard += it }
            main { other.addListener(l) }
            try {
                // The setting is written as it is, so nothing changes for the person: a reset when it is the default.
                main {
                    val p = TeditPrefs(ctx)
                    if (p.isDefault(TeditPrefs.LANGUAGE)) p.reset(listOf(TeditPrefs.LANGUAGE)) else p.set(TeditPrefs.LANGUAGE, p.string(TeditPrefs.LANGUAGE))
                }
            } finally {
                main { other.removeListener(l) }
            }
            if (TeditPrefs.LANGUAGE !in heard) "another window's settings did not hear the change" else null
        }
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
        val wasDefault = main { t.prefs.isDefault(TeditPrefs.LANGUAGE) }
        val was = main { t.prefs.string(TeditPrefs.LANGUAGE) }
        info("INFO language before the checks: $was${if (wasDefault) " (default)" else ""}; phone: ${ctx.resources.configuration.locales[0]}")
        try {
            check("the terminal panel starts a shell for the language checks") {
                main { t.showTerminal() }
                if (!waitFor(60_000) { (t.terminal.current?.prompts ?: 0) > 0 }) "no prompt: ${main { t.terminal.current?.kind }}" else null
            }
            val lxBefore = main { a.getString(R.string.tool_settings) }
            for (lang in listOf("zh-CN", "en", "in", TeditLocaleContext.SYSTEM)) {
                check("Tedit in $lang: Settings, a side panel and the terminal show no text in another language") {
                    main {
                        t.prefs.set(TeditPrefs.LANGUAGE, lang)
                        t.showPanel("explorer")
                        SettingsPage(t, TeditPrefs.Cat.GENERAL).show()
                    }
                    inst.waitForIdleSync()
                    SystemClock.sleep(400)
                    val want = res(lang).getString(R.string.tedit_settings)
                    val got = main { t.ctx.getString(R.string.tedit_settings) }
                    val left = main { leftovers(t.view, lang) }
                    val lx = main { a.getString(R.string.tool_settings) }
                    // What a person sees in this language, for the report: LX's cache, lang/<tag>.png (Tedit's window only).
                    runCatching {
                        val dir = java.io.File(ctx.cacheDir, "lang").apply { mkdirs() }
                        inst.waitForIdleSync()
                        SystemClock.sleep(600)
                        val r = main { val loc = IntArray(2); t.view.getLocationOnScreen(loc); android.graphics.Rect(loc[0], loc[1], loc[0] + t.view.width, loc[1] + t.view.height) }
                        inst.uiAutomation.takeScreenshot()?.let { full ->
                            val b = android.graphics.Bitmap.createBitmap(full, r.left.coerceAtLeast(0), r.top.coerceAtLeast(0),
                                minOf(r.width(), full.width - r.left.coerceAtLeast(0)), minOf(r.height(), full.height - r.top.coerceAtLeast(0)))
                            java.io.File(dir, "$lang.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                    }
                    main { t.overlays.closeAll() }
                    when {
                        got != want -> "Tedit reads \"$got\", expected \"$want\""
                        left.isNotEmpty() -> "still in another language: ${left.take(8)}"
                        lx != lxBefore -> "LX changed language too: \"$lxBefore\" -> \"$lx\""
                        else -> null
                    }
                }
            }
            check("50 language switches with a shell running: no crash, the session survives and answers, the heap stays flat") {
                val s = main { t.terminal.current } ?: return@check "no terminal session"
                val times = ArrayList<Long>()
                var heapAt10 = 0L
                for (i in 1..50) {
                    val lang = if (i % 2 == 1) "zh-CN" else "en"
                    times += main { val t0 = SystemClock.elapsedRealtimeNanos(); t.prefs.set(TeditPrefs.LANGUAGE, lang); (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000 }
                    inst.waitForIdleSync()
                    if (i == 10) heapAt10 = heap()
                }
                val heapAt50 = heap()
                val alive = main { s.alive && t.terminal.sessions().contains(s) }
                main { s.send("echo lx-lang-ok-\$((6*7))\r") }
                val answered = waitFor(10_000) { s.allText(s.native.rows, s.native.cols).contains("lx-lang-ok-42") }
                times.sort()
                info("INFO language switch on the main thread: median ${times[times.size / 2]} ms, slowest ${times.last()} ms; heap after 10 switches ${heapAt10 / 1024} KiB, after 50 ${heapAt50 / 1024} KiB")
                when {
                    !alive -> "the terminal session ended during the switches"
                    !answered -> "the session did not answer after the switches"
                    heapAt50 - heapAt10 > 4L * 1024 * 1024 -> "heap grew by ${(heapAt50 - heapAt10) / 1024} KiB over 40 switches"
                    else -> null
                }
            }
        } finally {
            main { if (wasDefault) t.prefs.reset(listOf(TeditPrefs.LANGUAGE)) else t.prefs.set(TeditPrefs.LANGUAGE, was) }
            main { a.model.snapshot.windows.filter { it.kind == TeditTool.KIND }.forEach { w -> a.model.edit { h -> NativeCore.closeWindow(h, w.id) } } }
            inst.waitForIdleSync()
            check("the person's language choice is back") {
                val now = ctx.getSharedPreferences("tedit", android.content.Context.MODE_PRIVATE).all[TeditPrefs.LANGUAGE]
                if ((wasDefault && now != null) || (!wasDefault && now != was)) "language is now $now, was $was" else null
            }
        }
    }

    private fun heap(): Long {
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100) }
        val r = Runtime.getRuntime()
        return r.totalMemory() - r.freeMemory()
    }
}
