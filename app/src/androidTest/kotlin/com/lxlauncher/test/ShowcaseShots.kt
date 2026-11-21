// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.files.FileManagerTool
import com.lxlauncher.tedit.CommandPalette
import com.lxlauncher.tedit.TeditPrefs
import com.lxlauncher.tedit.TeditTool
import com.lxlauncher.widgets.WidgetKinds
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pictures of LX for the README and the store listing (15 November 2026), taken with `-e only showcase`. Everything is
 * staged so nothing of the person's own shows: a workspace of its own with a built-in wallpaper and LX's widgets, and
 * a demo folder in Documents for Tedit and the File Explorer. The runner puts LX's data back afterwards, and the demo
 * folder is deleted here. The pictures go to LX's cache, showcase/, without the status bar.
 */
internal class ShowcaseShots(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit) {
    private val ctx = inst.targetContext
    private val out = File(ctx.cacheDir, "showcase").apply { deleteRecursively(); mkdirs() }
    private val demo = File("/storage/emulated/0/Documents/LX-Showcase")

    private fun <T> main(block: () -> T): T {
        var r: Result<T>? = null
        inst.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun waitFor(ms: Long, ok: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (main(ok)) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun settle(ms: Long = 900) {
        inst.waitForIdleSync()
        SystemClock.sleep(ms)
        inst.waitForIdleSync()
    }

    private lateinit var a: LauncherActivity

    private fun shot(name: String) = check("picture $name") {
        settle()
        val full = inst.uiAutomation.takeScreenshot() ?: return@check "no screenshot"
        val top = if (Build.VERSION.SDK_INT >= 30) main { a.window.decorView.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0 } else 0
        val b = Bitmap.createBitmap(full, 0, top, full.width, full.height - top)
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        info("INFO picture $name ${b.width}x${b.height}")
        null
    }

    private fun focused(): Any? {
        val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
        return (f.get(a) as com.lxlauncher.ui.windows.WindowLayer).focusedTool()
    }

    private fun closeWindows() = main {
        a.closeOverlays()
        val s = a.model.snapshot
        a.model.edit { h -> s.windows.filter { it.ws == s.active }.forEach { NativeCore.closeWindow(h, it.id) }; true }
    }

    /** Floating window [kind] at a share of the area windows may use, given as fractions of it. */
    private fun place(kind: String, x: Float, y: Float, w: Float, h: Float) = main {
        val s = a.model.snapshot
        val id = s.windows.lastOrNull { it.ws == s.active && it.kind == kind }?.id ?: return@main
        val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
        val vp = (f.get(a) as com.lxlauncher.ui.windows.WindowLayer).viewport
        a.model.edit { m ->
            NativeCore.setWindowState(m, id, "floating")
            val b = NativeCore.windowBounds(m, id) ?: return@edit false
            NativeCore.moveWindow(m, id, vp.left + vp.width() * x - b[0], vp.top + vp.height() * y - b[1])
            NativeCore.resizeWindow(m, id, NativeCore.EDGE_RIGHT or NativeCore.EDGE_BOTTOM, vp.width() * w - b[2], vp.height() * h - b[3])
            true
        }
    }

    /** Opens the demo folder in the focused File Explorer once it has settled, and says whether its files are on screen. */
    private fun showDemoInFiles(): Boolean {
        val open = FileManagerTool::class.java.getDeclaredMethod("openPath", String::class.java).apply { isAccessible = true }
        repeat(4) {
            SystemClock.sleep(1500)
            main { open.invoke(focused(), demo.absolutePath) }
            if (waitFor(4000) { (focused() as? FileManagerTool)?.let { texts(it.view).any { t -> t == "cuaca.py" } } == true }) return true
        }
        return false
    }

    private fun findText(v: android.view.View, text: String): android.view.View? {
        if (v is android.widget.TextView && v.isShown && v.text.toString() == text) return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    private fun texts(v: android.view.View): List<String> {
        val out = ArrayList<String>()
        fun walk(x: android.view.View) {
            if (x is android.widget.TextView && x.isShown) out += x.text.toString()
            if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        return out
    }

    private fun writeDemo() {
        demo.deleteRecursively()
        File(demo, "src").mkdirs()
        File(demo, "cuaca.py").writeText(
            """
            |""${'"'}Ringkasan cuaca seminggu, ditulis dan dijalankan langsung di Tedit.""${'"'}
            |from dataclasses import dataclass
            |
            |
            |@dataclass
            |class Hari:
            |    nama: str
            |    suhu: float   # derajat Celsius
            |    hujan: int    # peluang, persen
            |
            |
            |MINGGU = [
            |    Hari("Senin", 31.2, 20),
            |    Hari("Selasa", 29.8, 65),
            |    Hari("Rabu", 30.5, 40),
            |    Hari("Kamis", 28.9, 80),
            |    Hari("Jumat", 32.1, 10),
            |]
            |
            |
            |def saran(h: Hari) -> str:
            |    if h.hujan >= 60:
            |        return "bawa payung"
            |    return "cerah" if h.suhu > 30 else "berawan"
            |
            |
            |if __name__ == "__main__":
            |    for h in MINGGU:
            |        print(f"{h.nama:<7} {h.suhu:>5.1f} C  {saran(h)}")
            |""".trimMargin())
        File(demo, "src/Main.kt").writeText(
            """
            |package demo
            |
            |data class Tugas(val judul: String, val selesai: Boolean = false)
            |
            |fun main() {
            |    val daftar = listOf(Tugas("Rapikan desktop", true), Tugas("Coba Tedit"), Tugas("Pasang Debian"))
            |    val sisa = daftar.count { !it.selesai }
            |    println("Tinggal ${'$'}sisa tugas lagi")
            |}
            |""".trimMargin())
        File(demo, "index.html").writeText(
            """
            |<!doctype html>
            |<html lang="id">
            |<head>
            |  <meta charset="utf-8">
            |  <title>Halo dari LX</title>
            |  <link rel="stylesheet" href="style.css">
            |</head>
            |<body>
            |  <h1>Halo dari ponsel</h1>
            |  <p>Halaman ini disunting di Tedit, di dalam LX-LAUNCHER.</p>
            |</body>
            |</html>
            |""".trimMargin())
        File(demo, "style.css").writeText("body {\n  font-family: system-ui, sans-serif;\n  background: #10131a;\n  color: #e6e9ef;\n}\n\nh1 {\n  color: #4fd1c5;\n}\n")
        File(demo, "README.md").writeText("# Proyek demo\n\nContoh berkas untuk melihat Tedit: Python, Kotlin, HTML, dan CSS.\n")
        File(demo, "catatan.txt").writeText("Belanja: kopi, roti, buah.\nTelepon bengkel hari Kamis.\n")
        // A few pictures and an archive, so the File Explorer has something to show.
        val assets = ctx.assets
        for ((i, name) in listOf("neon_circuit", "violet_grid", "prism_shards").withIndex()) {
            assets.open("wallpapers/$name.webp").use { inp -> File(demo, "foto-${i + 1}.webp").outputStream().use { inp.copyTo(it) } }
        }
        ZipOutputStream(File(demo, "proyek-lama.zip").outputStream()).use { z ->
            for (f in listOf("cuaca.py", "index.html", "style.css")) {
                z.putNextEntry(ZipEntry(f)); File(demo, f).inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    fun run() {
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
        check("LX starts as Home for the pictures") {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClassName(ctx, LauncherActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(6000)
            inst.waitForIdleSync()
            // The pictures need LX on screen, not the keyboard focus.
            if (!waitFor(10_000) { resumed != null }) "LX did not come to the front" else null
        }
        main { app.unregisterActivityLifecycleCallbacks(watch) }
        a = resumed ?: return
        try {
            check("the demo folder is written") { writeDemo(); if (File(demo, "cuaca.py").isFile) null else "no demo folder" }
            check("a demo workspace with a built-in wallpaper and LX's widgets") {
                main {
                    a.closeOverlays()
                    a.model.edit { h ->
                        val ws = NativeCore.addWorkspace(h, "Demo")
                        if (ws <= 0) return@edit false
                        NativeCore.setWallpaper(h, ws, "builtin:neon_circuit")
                        NativeCore.setActiveWorkspace(h, ws)
                        for (kind in listOf("clock", "calendar", "battery", "memory")) {
                            val (w, ht) = WidgetKinds.defaultSize(kind, false)
                            NativeCore.addWidget(h, ws, kind, -1, -1f, -1f, w, ht)
                        }
                        true
                    }
                }
                settle(2500)
                val s = main { a.model.snapshot }
                if (s.workspaces.none { it.id == s.active && it.name == "Demo" }) "the demo workspace is not active" else null
            }
            shot("01-desktop")

            main { a.openDrawer(null) }
            settle(1500)
            shot("02-menu-aplikasi")
            main { a.closeOverlays() }

            // Tedit with the demo folder and a Python file. Language servers are off for the pictures: Debian here has
            // none, and the note offering one would cover the code. The runner puts the setting back.
            check("Tedit opens the demo folder and a file") {
                main { TeditPrefs(ctx).set(TeditPrefs.LANGUAGE_SERVERS, false) }
                main { a.openTool(TeditTool.KIND, "folder\u0000local\u0000${demo.absolutePath}") }
                if (!waitFor(10_000) { focused() is TeditTool }) return@check "no Tedit"
                main { a.openTool(TeditTool.KIND, "open\u0000local\u0000${File(demo, "cuaca.py").absolutePath}") }
                if (!waitFor(10_000) { (focused() as? TeditTool)?.store?.docs?.values?.any { it.name == "cuaca.py" && !it.loading } == true }) return@check "cuaca.py did not open"
                main { (focused() as TeditTool).closePanel() }
                null
            }
            val t = main { focused() as? TeditTool }
            if (t != null) {
                settle(2500)
                shot("03-tedit-kode")

                check("the terminal shows a session") {
                    main { t.closePanel(); t.showTerminal() }
                    if (!waitFor(60_000) { (t.terminal.current?.prompts ?: 0) > 0 }) return@check "no prompt"
                    val s = main { t.terminal.current } ?: return@check "no session"
                    main { s.send("clear; cd ${demo.absolutePath}; grep PRETTY /etc/os-release; ls --color=always\r") }
                    SystemClock.sleep(2500)
                    null
                }
                shot("04-tedit-terminal")

                check("the Extensions panel lists Open VSX") {
                    main {
                        if (t.prefs.string(TeditPrefs.MARKETPLACE) != "on") t.prefs.set(TeditPrefs.MARKETPLACE, "on")
                        t.toggleTerminal()
                        t.showPanel("extensions")
                    }
                    SystemClock.sleep(4000)
                    // The Themes category: colourful icons, and only themes in the list.
                    val label = ctx.getString(com.lxlauncher.R.string.tedit_ext_cat_themes)
                    val chip = main { findText(t.panel("extensions").view, label) } ?: return@check "no Themes chip"
                    main { chip.performClick() }
                    if (!waitFor(15_000) { (findText(t.panel("extensions").view, ctx.getString(com.lxlauncher.R.string.tedit_ext_install)) != null) }) return@check "the theme list did not load"
                    SystemClock.sleep(3000)
                    null
                }
                shot("05-tedit-ekstensi")

                main { t.closePanel(); CommandPalette(t, ">").show() }
                settle(1500)
                shot("06-tedit-palet")
                main { t.overlays.closeAll() }
            }
            closeWindows()
            settle(1200)

            var filesShown = false
            check("the File Explorer shows the demo folder") {
                main { a.openTool("files", null) }
                if (!waitFor(8000) { focused() is FileManagerTool }) return@check "no File Explorer"
                filesShown = showDemoInFiles()
                if (filesShown) null else "the demo folder did not show"
            }
            // Never a picture of a folder of the person's own.
            if (filesShown) shot("07-file-explorer")
            closeWindows()
            settle(1200)

            // Three floating windows over the desktop.
            main { a.openTool("monitor", null) }
            settle(1200)
            place("monitor", 0.04f, 0.50f, 0.92f, 0.46f)
            main { a.openTool("files", null) }
            settle(1500)
            val shown = showDemoInFiles()
            place("files", 0.10f, 0.03f, 0.86f, 0.44f)
            settle(1500)
            if (shown) shot("08-jendela") else check("the File Explorer shows the demo folder beside the monitor") { "it did not" }
            closeWindows()
            settle(1200)

            main { a.openTool("settings", "theme") }
            settle(2500)
            shot("09-tema")
            closeWindows()
            settle(1200)

            main { a.openTool("cleanup", null) }
            settle(4000)
            shot("10-pembersih")
        } finally {
            closeWindows()
            demo.deleteRecursively()
            info("INFO demo folder removed: ${!demo.exists()}")
        }
    }
}
