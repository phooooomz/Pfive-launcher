// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import com.lxlauncher.LxApp
import com.lxlauncher.apps.AppLabels
import com.lxlauncher.bridge.AdbKeys
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.Snapshot
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.embed.EmbedProtocol
import com.lxlauncher.embed.IEmbedClient
import com.lxlauncher.embed.IEmbedService
import com.lxlauncher.files.NativeArchive
import com.lxlauncher.ui.overlays.StartMenu
import com.lxlauncher.ui.theme.Contrast
import com.lxlauncher.ui.theme.Decor
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.Themes
import com.lxlauncher.ui.theme.WindowStyles
import com.lxlauncher.ui.windows.WindowFrameDrawable
import com.lxlauncher.ui.windows.WindowTitle
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

/**
 * Integration tests that need a real Android runtime: the JNI bridge, the
 * device filesystem, LauncherApps and the app widget service. Written against
 * android.app.Instrumentation directly so the test APK needs no extra
 * libraries.
 *
 * Run: adb shell am instrument -w com.lxlauncher.test/com.lxlauncher.test.SelfTestInstrumentation
 */
class SelfTestInstrumentation : Instrumentation() {
    private var passed = 0
    private var failed = 0
    private val report = StringBuilder()

    private var pairingPeer: String? = null
    private var holdSeconds: Long? = null
    private var holdApp: String? = null
    private var shellCommand: String? = null
    private var linuxCmd: String? = null
    private var holdHidden = false
    private var holdKeys: List<Int> = emptyList()
    private var only: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        pairingPeer = arguments?.getString("pairingPeer")
        holdSeconds = arguments?.getString("holdDisplay")?.toLongOrNull()
        holdApp = arguments?.getString("holdApp")
        shellCommand = arguments?.getString("shellCommand")
        linuxCmd = arguments?.getString("linuxcmd")
        holdHidden = arguments?.getString("holdHidden") == "true"
        holdKeys = arguments?.getString("holdKeys")?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        only = arguments?.getString("only")
        start()
    }

    private fun check(name: String, block: () -> String?) {
        val error = try {
            block()
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
        val line = if (error == null) "PASS $name" else "FAIL $name: $error"
        if (error == null) passed++ else failed++
        report.append(line).append('\n')
        sendStatus(0, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
    }

    /** A measurement, not a pass or fail: printed and kept in the report. */
    private fun info(line: String) {
        report.append(line).append('\n')
        sendStatus(0, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
    }

    /** Runs [block] on the main thread, where views and themes are built, and hands back its result or its exception. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** A layout with nothing in it but these settings, for building a theme. */
    private fun settings(vararg values: Pair<String, String>) = Snapshot(0, emptyList(), 0, 0, emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), mapOf(*values))

    /** `-e pairingPeer <code>`: waits on [PAIRING_PEER_PORT] for one `adb pair` instead of running the checks. */
    private fun servePairing(code: String) {
        val answer = BridgePeer.listen(PAIRING_PEER_PORT).use { server ->
            server.soTimeout = PAIRING_PEER_WAIT_MS
            runCatching { BridgePeer.pairingServer(server, code, AdbKeys.get(targetContext)) }
        }
        val ok = answer.getOrNull()?.contains(" ") == true
        val line = if (ok) "PASS real adb paired, key ${answer.getOrNull()?.take(24)}" else "FAIL ${answer.exceptionOrNull()}"
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
    }

    private fun helperService(): IEmbedService? {
        val client = EmbedClient.get(targetContext)
        repeat(HELPER_TRIES) {
            val answer = ArrayBlockingQueue<Array<IEmbedService?>>(1)
            runOnMainSync { client.withService { answer.put(arrayOf(it)) } }
            answer.take()[0]?.let { return it }
            SystemClock.sleep(HELPER_WAIT_MS)
        }
        return null
    }

    /**
     * `-e holdDisplay <seconds>`: one app display through the lxdb that runs, empty or with `-e holdApp <component>`
     * on it, so scripts/ui_test_display_awake.sh can read what Android does with it, also on a locked phone. With
     * `-e holdHidden true` it loses its surface as a minimised window does, once the app is up. What the app asks
     * of the display reaches lxdb's log, and here as a status line. `-e holdKeys <key codes>` presses those keys on the
     * display and saves what it shows to cache/held-frame.png.
     */
    private fun holdDisplay(seconds: Long) {
        val service = helperService()
        val height = if (holdKeys.isEmpty()) HELD_SIZE else HELD_TALL
        val reader = ImageReader.newInstance(HELD_SIZE, height, PixelFormat.RGBA_8888, 2)
        val owner = object : IEmbedClient.Stub() {
            override fun onDisplayEmpty(displayId: Int, hadTask: Boolean) {}
            override fun onOrientation(displayId: Int, orientation: Int) {
                sendStatus(0, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, "orientation $orientation\n") })
            }
        }
        val id = service?.let { EmbedProtocol.create(it, reader.surface, HELD_SIZE, height, HELD_DPI, "held", owner) } ?: -1
        val started = holdApp?.let {
            val target = ComponentName.unflattenFromString(it)!!
            service?.start(id, 0, target.packageName, EmbedProtocol.launchIntent(target), EmbedProtocol.launchOptions(id))
        }
        val line = "display $id app $started"
        sendStatus(0, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
        if (holdHidden && id >= 0) {
            SystemClock.sleep(HIDE_AFTER_MS)
            service?.setSurface(id, null)
        }
        if (holdKeys.isNotEmpty() && id >= 0 && service != null) {
            SystemClock.sleep(HIDE_AFTER_MS)
            pressKeys(service, id)
            saveFrame(reader)
        }
        SystemClock.sleep(seconds * 1000)
        if (id >= 0) service?.release(id)
        reader.close()
        finish(if (id >= 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle())
    }

    private fun pressKeys(service: IEmbedService, displayId: Int) {
        for (code in holdKeys) {
            val now = SystemClock.uptimeMillis()
            service.injectKey(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            service.injectKey(displayId, KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            SystemClock.sleep(KEY_GAP_MS)
        }
    }

    private fun saveFrame(reader: ImageReader) {
        SystemClock.sleep(KEY_GAP_MS)
        val image = reader.acquireLatestImage() ?: return
        val plane = image.planes[0]
        val bitmap = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        image.close()
        File(targetContext.cacheDir, "held-frame.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** `-e shellCommand <line>`: the line through lxdb, from LX's own uid. scripts/ui_test_lxdb.sh reads the answer. */
    private fun runShell(command: String) {
        helperService()
        val result = EmbedClient.get(targetContext).shell(command)
        val line = if (result == null) "FAIL no lxdb" else "exit ${result.exit}\n${result.text}"
        finish(if (result != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString(REPORT_KEY_STREAMRESULT, line + "\n") })
    }

    override fun onStart() {
        pairingPeer?.let { return servePairing(it) }
        holdSeconds?.let { return holdDisplay(it) }
        shellCommand?.let { return runShell(it) }
        val ctx = targetContext
        val names = arrayOf("A", "B", "C", "D")
        // `-e only maintenance`: just the checks for the maintenance audit fixes.
        if (only == "maintenance") {
            maintenanceChecks(ctx, this::check) { block -> onMain(block) }
            return finishRun()
        }
        // `-e only fm`: the File Explorer's shell access, the cleaner, the fonts and the menu motions.
        if (only == "fm") {
            helperService()
            fileManagerChecks(ctx, this::check, { block -> onMain(block) }, this::info)
            return finishRun()
        }
        // `-e only tedit`: Tedit's buffer, undo, encodings, search, highlighting, diff, git reader and checks, then a real edit.
        if (only == "tedit") {
            TeditChecks(this, this::check, this::info).run()
            return finishRun()
        }
        // `-e only teditload`: Tedit under load: random edits, every lexer, large files, typing, tabs, search, terminal output, leaks.
        if (only == "teditload" || only?.startsWith("teditload:") == true) {
            val parts = only!!.substringAfter(':', "").split(',').filter { it.isNotBlank() }.toSet()
            TeditStress(this, this::check, this::info, parts).run()
            return finishRun()
        }
        // `-e only linux`: Tedit's Debian: xz, tar, setup, VHDP start, commands. `linux:keep` leaves it installed.
        // `-e linuxcmd "<command>"`: runs one bash command in Tedit's Debian and prints its output.
        linuxCmd?.let { cmd ->
            val rt = com.lxlauncher.tedit.linux.LinuxRuntime.get(ctx)
            val p = ProcessBuilder(rt.command(listOf("/bin/bash", "-c", cmd), null)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            info("exit $code\n$out")
            return finishRun()
        }
        // `-e only textmate`: the TextMate grammar engine on small grammars, plist, includes, random lines.
        if (only == "textmate") {
            TextMateChecks(this::check).run()
            return finishRun()
        }
        // `-e only tmparity`: the TextMate tokenizer against vscode-textmate's own output, prepared by
        // scripts/tmparity/prepare.sh and pushed to /sdcard/Download/tmparity (see TextMateParity).
        val parity = only
        if (parity == "tmparity" || parity?.startsWith("tmparity:") == true) {
            TextMateParity(this::check, this::info, parity.substringAfter(':', "").ifEmpty { null }).run()
            return finishRun()
        }
        // `-e only lsp`: the language server client against an in-process fake server (framing, requests, timeouts).
        if (only == "lsp") {
            LspChecks(this::check).run()
            return finishRun()
        }
        // `-e only ext`: Tedit's extensions: JSONC, .vsix install and refusals, themes, Open VSX. `ext:keep` keeps them.
        if (only == "ext" || only == "ext:keep") {
            ExtChecks(this, this::check, this::info, keep = only == "ext:keep").run()
            return finishRun()
        }
        if (only == "linux:probe") {
            LinuxChecks(this, this::check, this::info, keep = true).probe()
            return finishRun()
        }
        if (only == "linux" || only == "linux:keep") {
            LinuxChecks(this, this::check, this::info, keep = only == "linux:keep").run()
            return finishRun()
        }
        // `-e only dxterm`: Tedit's terminal as dracxterm's: shells on a pseudo terminal, Run, input, Stop, sessions.
        // `-e only dxterm:debian`: Debian set up (kept for the run), its checks, then the terminal's checks in Debian.
        if (only == "dxterm:debian") {
            LinuxChecks(this, this::check, this::info, keep = true).run()
            DxTermChecks(this, this::check, this::info).run()
            return finishRun()
        }
        if (only == "dxterm" || only == "dxterm:tedit") {
            DxTermChecks(this, this::check, this::info, teditOnly = only == "dxterm:tedit").run()
            return finishRun()
        }
        // `-e only showcase`: pictures of LX for the README, in a demo workspace with a demo folder (nothing of the owner's).
        if (only == "showcase") {
            ShowcaseShots(this, this::check, this::info).run()
            return finishRun()
        }
        // `-e only tedit:ime`: Tedit's terminal with the keyboard up, full screen, maximised and floating.
        if (only == "tedit:ime") {
            TeditKeyboardChecks(this, this::check, this::info).run()
            return finishRun()
        }
        // `-e only tedit:lang`: Tedit in each of its languages, nothing left in another, and 50 switches with a shell open.
        if (only == "tedit:lang") {
            TeditLanguageChecks(this, this::check, this::info).run()
            return finishRun()
        }
        // `-e only responsive`: every LX tool at the smallest window size and a few above it: nothing cut or overlapping.
        if (only == "responsive") {
            ResponsiveChecks(this, this::check, this::info).run()
            return finishRun()
        }
        // `-e only windows`: LX windows under an injected finger (open times, resize frames, where an edge press goes).
        if (only == "windows") {
            WindowChecks(this, this::check, this::info).run()
            return finishRun()
        }

        check("engine loads defaults through JNI") {
            val h = NativeCore.create()
            val dir = File(ctx.cacheDir, "selftest-${System.nanoTime()}").apply { mkdirs() }
            val r = JSONObject(NativeCore.load(h, File(dir, "layout.lxl").path, names, "Welcome", false)!!)
            val snap = JSONObject(NativeCore.snapshot(h)!!)
            NativeCore.destroy(h)
            dir.deleteRecursively()
            when {
                !r.getBoolean("ok") || !r.getBoolean("fresh") -> "load report $r"
                snap.getJSONArray("workspaces").length() != 4 -> "expected 4 workspaces"
                else -> null
            }
        }

        check("strings outside the BMP survive the JNI boundary") {
            val h = NativeCore.create()
            NativeCore.resetDefaults(h, names, "Welcome", false, false)
            val ws = JSONObject(NativeCore.snapshot(h)!!).getJSONArray("workspaces").getJSONObject(0).getInt("id")
            val name = "Kerja 🚀 ruang"  // rocket emoji, a surrogate pair in Java
            val ok = NativeCore.renameWorkspace(h, ws, name)
            val back = JSONObject(NativeCore.snapshot(h)!!).getJSONArray("workspaces").getJSONObject(0).getString("name")
            NativeCore.destroy(h)
            if (!ok) "rename refused" else if (back != name) "got '$back'" else null
        }

        check("save, damage and recover the layout on device storage") {
            val dir = File(ctx.cacheDir, "selftest-${System.nanoTime()}").apply { mkdirs() }
            val path = File(dir, "layout.lxl").path
            val h = NativeCore.create()
            NativeCore.load(h, path, names, "Welcome", false)
            NativeCore.setSetting(h, "theme.accent", "purple")
            if (!NativeCore.save(h, path)) return@check "first save failed"
            NativeCore.setSetting(h, "theme.accent", "magenta")
            if (!NativeCore.save(h, path)) return@check "second save failed"
            NativeCore.destroy(h)
            File(path).writeText("LXL1 00000000 2\n{}")
            val h2 = NativeCore.create()
            val r = JSONObject(NativeCore.load(h2, path, names, "Welcome", false)!!)
            val accent = NativeCore.getSetting(h2, "theme.accent")
            NativeCore.destroy(h2)
            dir.deleteRecursively()
            when {
                !r.getBoolean("usedBackup") -> "backup not used: $r"
                accent != "purple" -> "accent $accent"
                else -> null
            }
        }

        check("import rejects hostile and oversized documents") {
            val h = NativeCore.create()
            NativeCore.resetDefaults(h, names, "Welcome", false, false)
            val before = NativeCore.exportDocument(h)
            val deep = "[".repeat(5000) + "]".repeat(5000)
            val results = listOf(deep, "{\"format\":\"lx-launcher-layout\",\"version\":9}", "x".repeat(2 * 1024 * 1024))
                .map { JSONObject(NativeCore.importDocument(h, it)!!).getBoolean("ok") }
            val after = NativeCore.exportDocument(h)
            NativeCore.destroy(h)
            if (results.any { it }) "accepted $results" else if (before != after) "state changed" else null
        }

        check("settings outside the schema are refused") {
            val h = NativeCore.create()
            val bad = listOf("ui.scale" to "9", "dock.position" to "../x", "nope" to "1").count { NativeCore.setSetting(h, it.first, it.second) }
            val good = NativeCore.setSetting(h, "ui.scale", "1.1")
            NativeCore.destroy(h)
            if (bad != 0) "$bad bad values accepted" else if (!good) "valid value refused" else null
        }

        check("LauncherApps lists launchable activities") {
            val la = ctx.getSystemService(LauncherApps::class.java)
            val list = la.getActivityList(null, Process.myUserHandle())
            if (list.isEmpty()) "no activities" else null
        }

        check("a frozen app keeps its places; only an app Android no longer has counts as removed") {
            // Island and other profile owners freeze an app by hiding it, and Android reports that as a removal.
            // The shell cannot hide an app on Android 15, so this checks the question LX asks on each removal.
            val apps = (ctx.applicationContext as LxApp).apps
            val me = Process.myUserHandle()
            when {
                !apps.stillInstalled(ctx.packageName, me) -> "LX itself was not seen as installed"
                apps.stillInstalled("com.lxlauncher.no.such.app", me) -> "a package that does not exist was seen as installed"
                else -> null
            }
        }

        check("kept app labels come back only for the same languages and system build") {
            val file = File(ctx.cacheDir, "app-labels-check").apply { delete() }
            val store = AppLabels(file)
            val entries = mapOf(
                "com.example/.Main#0" to AppLabels.Entry("/data/app/~~a/base.apk", 1L, "Kalkulator"),
                "com.example/.Other#10" to AppLabels.Entry("/data/app/~~b/base.apk", 2L, "Jam (kerja)"),
            )
            store.save("build|id-ID", entries)
            val problems = mutableListOf<String>()
            if (store.load("build|id-ID") != entries) problems += "the saved labels did not come back unchanged"
            if (store.load("build|en-US").isNotEmpty()) problems += "labels came back for another language"
            file.writeBytes(file.readBytes().copyOf(12))
            if (store.load("build|id-ID").isNotEmpty()) problems += "a damaged file gave labels"
            file.delete()
            problems.joinToString("; ").ifEmpty { null }
        }

        check("native search ranks app labels") {
            val labels = arrayOf("Clock", "Calculator", "Calendar", "Camera")
            val r = NativeCore.rank("calc", labels, 5)
            if (r.isEmpty() || labels[r[0]] != "Calculator") "got ${r.toList()}" else null
        }

        check("wallpaper and blur render into an Android bitmap") {
            val bmp = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
            val ok = NativeCore.renderWallpaper(bmp, "skyline", 7) && NativeCore.blurBitmap(bmp, 4, 3)
            val wrong = Bitmap.createBitmap(10, 10, Bitmap.Config.RGB_565)
            val refused = !NativeCore.renderWallpaper(wrong, "skyline", 7)
            val opaque = Color(bmp.getPixel(90, 160)).alpha == 255
            if (!ok) "render failed" else if (!refused) "RGB_565 accepted" else if (!opaque) "not opaque" else null
        }

        check("app widget host allocates and releases ids") {
            val host = AppWidgetHost(ctx, 0x5E1F)
            val id = host.allocateAppWidgetId()
            val held = host.appWidgetIds.contains(id)
            host.deleteAppWidgetId(id)
            val released = !host.appWidgetIds.contains(id)
            host.deleteHost()
            val providers = AppWidgetManager.getInstance(ctx).installedProviders.size
            if (!held || !released) "held=$held released=$released" else if (providers < 0) "providers" else null
        }

        check("window styles and the LX theme keep text at 4.5:1 on plain wallpapers and on focused, hovered and selected rows") {
            val problems = mutableListOf<String>()
            val leafLayers = 1f - (1f - LxTheme.LEAF_ALPHA) * (1f - LxTheme.LEAF_ALPHA) * (1f - LxTheme.LEAF_ALPHA)
            fun checkOn(name: String, t: LxTheme, under: Int, where: String) {
                val focus = Contrast.over(t.accent, LxTheme.FOCUS_FILL_ALPHA, under)
                val hover = Contrast.over(t.textPrimary, LxTheme.HOVER_ALPHA, under)
                val selection = Contrast.over(t.selectionBase, t.selectedAlpha, under)
                listOf("text" to Contrast.ratio(t.textPrimary, under), "muted" to Contrast.ratio(t.textMuted, under),
                    "accent" to Contrast.ratio(t.accent, under), "muted on focus" to Contrast.ratio(t.textMuted, focus),
                    "accent on focus" to Contrast.ratio(t.accent, focus), "danger on focus" to Contrast.ratio(t.danger, focus),
                    "muted on hover" to Contrast.ratio(t.textMuted, hover), "accent on hover" to Contrast.ratio(t.accent, hover),
                    "selected" to Contrast.ratio(t.selectedText, selection), "text on selection" to Contrast.ratio(t.textPrimary, selection))
                    .filter { it.second < 4.5 }
                    .forEach { problems += "$name ${it.first} %.2f $where".format(it.second) }
            }
            // Every preset, and the custom style on every base with every accent it offers.
            val customs = WindowStyles.all.flatMap { base ->
                (listOf("style") + WindowStyles.customAccents.map { it.first }).map { accent ->
                    WindowStyles.custom(settings("window.style" to "custom", "window.custom.base" to base.id,
                        "window.custom.accent" to accent)).copy(id = "custom/${base.id}/$accent")
                }
            }
            for (style in WindowStyles.all + customs) {
                val t = onMain { LxTheme(ctx, null, style) }
                // Plain grey wallpapers from black to white; the window fills over each at the alpha it would
                // use, and with blur off every style fills opaque. Not modelled: a wallpaper whose
                // colour is not grey, and rounding in the GPU's blending.
                for (step in 0..20) {
                    val l = step / 20.0
                    val alphas = if (style.glass) listOf(style.fillAlphaOver(l), 1f) else listOf(1f)
                    for (a in alphas) {
                        val under = Contrast.over(style.colors.panel, a, Contrast.grey(l))
                        checkOn(style.id, t, under, "at L=%.2f alpha=%.2f".format(l, a))
                        if (style.decor == Decor.LEAVES) {
                            checkOn(style.id, t, Contrast.over(style.colors.accent2, leafLayers, under), "on leaves at L=%.2f".format(l))
                        }
                    }
                }
                checkOn(style.id, t, style.colors.surface, "on surface")
            }
            // The LX theme in every palette and accent, at the lowest, the default and full panel opacity: its
            // opaque palette, and over each grey wallpaper its glass at the opacity the glass picks, menus and
            // dialogs, glass without the blurred layer, and flat panels.
            for (palette in Themes.all) for (accent in listOf("palette", "cyan", "magenta", "blue", "purple")) {
                for (opacity in listOf("0.35", "0.74", "1")) {
                    val name = "lx ${palette.id}/$accent/$opacity"
                    val lx = onMain {
                        LxTheme(ctx, settings("theme.palette" to palette.id, "theme.accent" to accent,
                            "theme.panelOpacity" to opacity, "theme.blur" to "true"))
                    }
                    checkOn(name, lx, lx.panel, "on panel")
                    checkOn(name, lx, lx.surface, "on surface")
                    for (step in 0..20) {
                        val l = step / 20.0
                        val glass = lx.glassOpacityOver(l)
                        val fills = listOf("glass" to glass, "menu" to maxOf(glass, LxTheme.MENU_OPACITY),
                            "glass without blur" to 1f,
                            "flat" to maxOf(lx.panelOpacity, if (lx.dark) LxTheme.MENU_OPACITY else LxTheme.LIGHT_PANEL_FLOOR))
                        for ((what, a) in fills) {
                            checkOn(name, lx, Contrast.over(lx.panel, a, Contrast.grey(l)), "$what %.2f at L=%.2f".format(a, l))
                        }
                    }
                }
            }
            problems.take(4).joinToString("; ").ifEmpty { null }
        }

        check("window titles take their full size when they fit and shrink no further than the floor") {
            // Compact at 85% and 90% sits at the 12 sp floor, where autosize used to throw.
            val sizes = WindowStyles.all.map { it.titleSizeSp } + 16f
            val scales = (0..9).map { 0.85f + it * 0.05f }
            val dm = ctx.resources.displayMetrics
            fun px(sp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            onMain {
                val problems = mutableListOf<String>()
                for (size in sizes) for (scale in scales) {
                    val title = WindowTitle(ctx, size * scale).apply { text = "Pengaturan LX, halaman Tampilan dan Font" }
                    if (title.minSp >= title.sizeSp) continue
                    title.measure(View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY), unspecified)
                    if (title.textSize != px(title.sizeSp)) problems += "${size * scale} sp wide: ${title.textSize} px"
                    title.measure(View.MeasureSpec.makeMeasureSpec(40, View.MeasureSpec.EXACTLY), unspecified)
                    if (title.textSize != px(title.minSp.toFloat())) problems += "${size * scale} sp narrow: ${title.textSize} px"
                }
                problems.take(4).joinToString("; ").ifEmpty { null }
            }
        }

        check("the Cyberpunk outline falls back to a rounded rectangle on empty and tiny bounds") {
            // Cut corners on such bounds fold into a path that is not convex, which Android 8 and 9 reject.
            val style = WindowStyles.byId("cyberpunk")!!
            onMain {
                val theme = LxTheme(ctx, null, style)
                val frame = WindowFrameDrawable(View(ctx), GlassEnv(), style, theme)
                fun outline(r: Rect) = Outline().also {
                    frame.bounds = r
                    frame.getOutline(it)
                }
                val rect = Rect()
                val empty = outline(Rect(0, 0, 0, 0))
                val tiny = outline(Rect(0, 0, 12, 12))
                val full = outline(Rect(0, 0, 640, 480))
                when {
                    !empty.isEmpty -> "empty bounds gave a shape"
                    !tiny.getRect(rect) || rect.width() != 12 -> "tiny bounds did not give a rounded rectangle"
                    full.isEmpty -> "full-size outline is empty"
                    full.getRect(rect) -> "full-size outline lost its cut corners"
                    else -> null
                }
            }
        }

        check("the Applications menu's key filter skips dead keys and control characters") {
            val accent = KeyCharacterMap.COMBINING_ACCENT or 0x0301
            when {
                StartMenu.typedText(accent) != null -> "dead key typed"
                StartMenu.typedText(0) != null -> "no-character key typed"
                StartMenu.typedText('\n'.code) != null -> "control character typed"
                StartMenu.typedText('a'.code) != "a" -> "letter lost"
                StartMenu.typedText(0x1F600) != String(Character.toChars(0x1F600)) -> "emoji lost"
                else -> null
            }
        }

        check("the archive engine writes and reads ZIP, TAR and TAR.GZ through file descriptors on the device") {
            val dir = File(ctx.cacheDir, "selftest-${System.nanoTime()}").apply { mkdirs() }
            try {
                val files = linkedMapOf(
                    "MyProject/readme.md" to "# MyProject\n".toByteArray(),
                    "MyProject/src/Ünïcødé ✓.c" to "int main(void) { return 0; }\n".toByteArray(),
                    "MyProject/data/random.bin" to ByteArray(3 * 1024 * 1024 + 17) { (it * 31 + 7).toByte() },
                    "MyProject/empty.txt" to ByteArray(0),
                )
                val problems = ArrayList<String>()
                for (format in listOf(NativeArchive.FORMAT_ZIP, NativeArchive.FORMAT_TAR, NativeArchive.FORMAT_TAR_GZ)) {
                    val archive = File(dir, "out-$format")
                    ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE).use { out ->
                        val w = NativeArchive.openWriter(out.fd, format, 6)
                        if (w == 0L) return@check "openWriter($format): ${NativeArchive.lastError(0)}"
                        NativeArchive.addDirectory(w, "MyProject/folder-without-files", 0L, 0x1ED)
                        for ((path, bytes) in files) {
                            val src = File(dir, "src.bin").apply { writeBytes(bytes) }
                            ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                                val r = NativeArchive.addFile(w, path, input.fd, bytes.size.toLong(), 1_700_000_000_000L, 0x1A4, null)
                                if (r != NativeArchive.OK) problems += "addFile $format $path -> $r ${NativeArchive.lastError(w)}"
                            }
                        }
                        if (NativeArchive.finish(w) != NativeArchive.OK) problems += "finish $format"
                    }
                    ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                        if (NativeArchive.detect(input.fd) != format) problems += "detect $format"
                        val limits = LongArray(NativeArchive.LIMIT_LENGTH).apply {
                            this[NativeArchive.LIMIT_MAX_ENTRIES] = 1000
                            this[NativeArchive.LIMIT_MAX_TOTAL_BYTES] = 64L shl 20
                            this[NativeArchive.LIMIT_MAX_ENTRY_BYTES] = 64L shl 20
                            this[NativeArchive.LIMIT_MAX_RATIO_X100] = 100_000
                            this[NativeArchive.LIMIT_RATIO_GRACE_BYTES] = 1L shl 20
                        }
                        val r = NativeArchive.openReader(input.fd, format, limits)
                        if (r == 0L) return@check "openReader($format): ${NativeArchive.lastError(0)}"
                        val info = LongArray(NativeArchive.INFO_LENGTH)
                        val names = arrayOfNulls<String>(NativeArchive.NAME_LENGTH)
                        val seen = HashMap<String, ByteArray>()
                        var dirs = 0
                        while (true) {
                            val st = NativeArchive.next(r, info, names)
                            if (st == NativeArchive.END) break
                            if (st != NativeArchive.OK) { problems += "next $format -> $st ${NativeArchive.lastError(r)}"; break }
                            if (info[NativeArchive.INFO_VERDICT].toInt() != NativeArchive.PATH_OK) problems += "verdict ${names[NativeArchive.NAME_RAW]}"
                            if (info[NativeArchive.INFO_TYPE].toInt() == NativeArchive.TYPE_DIR) { dirs++; continue }
                            val target = File(dir, "x.bin")
                            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE).use { o ->
                                val e = NativeArchive.extract(r, o.fd, null)
                                if (e != NativeArchive.OK) problems += "extract $format ${names[NativeArchive.NAME_PATH]} -> $e"
                            }
                            seen[names[NativeArchive.NAME_PATH]!!] = target.readBytes()
                        }
                        NativeArchive.closeReader(r)
                        for ((path, bytes) in files) if (!seen[path].contentEquals(bytes)) problems += "$format: $path differs"
                        if (dirs < 1) problems += "$format: empty folder lost"
                    }
                }
                problems.take(5).joinToString("; ").ifEmpty { null }
            } finally {
                dir.deleteRecursively()
            }
        }

        check("archive paths that escape, hide a drive or carry control characters are refused") {
            val out = arrayOfNulls<String>(1)
            val cases = mapOf(
                "../escape.txt" to NativeArchive.PATH_DOTDOT,
                "a/../../b" to NativeArchive.PATH_DOTDOT,
                "/etc/passwd" to NativeArchive.PATH_ABSOLUTE,
                "C:\\Windows\\x" to NativeArchive.PATH_DRIVE,
                "a\u0000b" to NativeArchive.PATH_CONTROL,
                "" to NativeArchive.PATH_EMPTY,
                "ok/./file.txt" to NativeArchive.PATH_OK,
            )
            val wrong = cases.filter { (raw, want) -> NativeArchive.checkPath(raw, false, out) != want }.keys
            when {
                wrong.isNotEmpty() -> "wrong verdict for ${wrong.joinToString { "'$it'" }}"
                NativeArchive.checkPath("ok/./file.txt", false, out) != NativeArchive.PATH_OK || out[0] != "ok/file.txt" -> "not normalised: ${out[0]}"
                NativeArchive.checkName("a:b", true) == NativeArchive.PATH_OK -> "FAT name with a colon accepted"
                NativeArchive.numberedName("MyProject.tar.gz", 2) != "MyProject (2).tar.gz" -> "numbered name ${NativeArchive.numberedName("MyProject.tar.gz", 2)}"
                else -> null
            }
        }

        embedChecks(ctx, this::check) { onMain(it) }
        bridgeChecks(ctx, this::check)
        maintenanceChecks(ctx, this::check) { block -> onMain(block) }
        finishRun()
    }

    private fun finishRun() {
        val result = Bundle().apply {
            putString(REPORT_KEY_STREAMRESULT, "\n$passed passed, $failed failed\n")
            putInt("passed", passed)
            putInt("failed", failed)
        }
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private class Color(private val argb: Int) {
        val alpha get() = (argb ushr 24) and 0xFF
    }

    private companion object {
        const val PAIRING_PEER_PORT = 37123
        const val PAIRING_PEER_WAIT_MS = 30_000
        const val HELPER_TRIES = 20
        const val HELPER_WAIT_MS = 500L
        const val HELD_SIZE = 640
        /** A window's shape: a square Calculator shows its keys and no result. */
        const val HELD_TALL = 1100
        const val HELD_DPI = 320
        const val HIDE_AFTER_MS = 3_000L
        const val KEY_GAP_MS = 300L
    }
}
