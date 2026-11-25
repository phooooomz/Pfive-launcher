// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.embed.EmbeddedAppTool
import com.lxlauncher.ui.theme.WallpaperPixels
import com.lxlauncher.ui.theme.Wallpapers
import com.lxlauncher.ui.windows.WindowLayer
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * LX windows under a finger: how long a window takes to open, how smoothly it resizes, and whether a press goes to
 * the window's content or to its resize border. The finger is injected at 60 Hz from this thread, as a person drags;
 * `input swipe` is not used because it sends moves only as fast as LX handles them, so a slow LX looked smooth.
 * Frame times come from FrameMetrics. Run alone with `-e only windows`; it opens and closes LX windows and uses one
 * scratch folder in LX's cache, which it deletes again.
 */
internal class WindowChecks(
    private val inst: Instrumentation,
    private val check: (String, () -> String?) -> Unit,
    private val info: (String) -> Unit,
) {
    private lateinit var activity: LauncherActivity
    private val metricsThread = HandlerThread("lx-frame-metrics").apply { start() }
    private val metricsHandler = Handler(metricsThread.looper)

    fun run() {
        check("LX starts for the window checks") {
            val monitor = inst.addMonitor(LauncherActivity::class.java.name, null, false)
            val intent = Intent(Intent.ACTION_MAIN).setClassName(inst.targetContext, LauncherActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            inst.targetContext.startActivity(intent)
            val a = inst.waitForMonitorWithTimeout(monitor, START_WAIT_MS) as? LauncherActivity
            inst.removeMonitor(monitor)
            if (a == null) return@check "LauncherActivity did not start"
            activity = a
            inst.waitForIdleSync()
            // Apps, icons and the wallpaper load in the background; measure a launcher that has settled.
            SystemClock.sleep(SETTLE_MS)
            null
        }
        if (!::activity.isInitialized) return
        try {
            keyboardHolderChecks()
            wallpaperTrimCheck()
            wallpaperPixelsCheck()
            appEdgeChecks()
            smallestSizeCheck()
            openTimes()
            for (kind in listOf("settings", "files", "notes", "tedit")) resizeRun(kind)
        } finally {
            metricsThread.quitSafely()
        }
    }

    // ---- memory ----

    /**
     * Android asks LX to trim its memory each time LX goes behind an app. The wallpaper on screen stays cached: the
     * activity draws it anyway, and losing it decoded and blurred the same picture again on the next rebuild.
     */
    private fun wallpaperTrimCheck() = check("trimming memory keeps the wallpaper on screen ready") {
        val app = activity.application as com.lxlauncher.LxApp
        var answered = false
        main {
            app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            val ui = activity.ui
            val root = activity.findViewById<View>(android.R.id.content)
            // The request the activity makes for its wallpaper; a cached picture answers before get() returns.
            app.wallpapers.get(activity.model.snapshot.activeWorkspace.wallpaper, ui.colors, root.width, root.height,
                ui.dpi(ui.blurRadiusDp.toFloat())) { answered = true }
        }
        if (!answered) "the wallpaper on screen was made again after a trim" else null
    }

    /**
     * A photo wallpaper decoded once loads from its stored pixels when LX's process starts again, and only for the
     * picture and size it was made for: a changed photo, another size or a damaged file is decoded again.
     */
    private fun wallpaperPixelsCheck() = check("stored wallpaper pixels come back only for their own picture and size") {
        val dir = File(inst.targetContext.cacheDir, "wallpaper-pixels-check").apply { deleteRecursively() }
        val store = WallpaperPixels(dir)
        val ref = "local:check.img"
        val key = "$ref|1|64x32"
        val picture = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        for (x in 0 until 64) for (y in 0 until 32) picture.setPixel(x, y, Color.rgb(x * 4, y * 8, 99))
        val problems = mutableListOf<String>()
        store.write(ref, key, picture)
        if (store.read(ref, key, 64, 32)?.sameAs(picture) != true) problems += "the stored pixels did not come back unchanged"
        if (store.read(ref, "$ref|2|64x32", 64, 32) != null) problems += "a changed photo used the old pixels"
        if (store.read(ref, "$ref|1|32x64", 32, 64) != null) problems += "another size used the old pixels"
        dir.listFiles()?.forEach { f -> RandomAccessFile(f, "rw").use { it.setLength(it.length() - 1) } }
        if (store.read(ref, key, 64, 32) != null) problems += "a damaged file was used"
        store.write(ref, key, picture)
        store.forget(listOf(Wallpapers.THEMED))
        if (!dir.listFiles().isNullOrEmpty()) problems += "the pixels of a picture no workspace shows were kept"
        dir.deleteRecursively()
        problems.joinToString("; ").ifEmpty { null }
    }

    // ---- an Android app's window: its edges and its keyboard ----

    private fun keyboardHolderChecks() = check("a press keeps the keyboard with the app whose content or frame it reached") {
        val none = LauncherActivity.NO_WINDOW
        // holder, content, frame, taken by an LX view (1) or by none (0) -> who has the keyboard after the press
        val cases = listOf(
            listOf(none, 7, 7, 1) to 7,       // a tap in an app takes the keyboard there
            listOf(7, none, 7, 1) to 7,       // its own title bar, border or resize band keeps it there
            listOf(7, none, 9, 1) to none,    // another window's frame gives it back to LX
            listOf(7, none, none, 1) to none, // LX's bars, the desktop or a menu give it back to LX
            listOf(7, none, none, 0) to 7,    // a tap nothing took (beside a maximised app, above the taskbar) keeps it
            listOf(7, 9, 9, 1) to 9,          // a tap in another app moves it to that app
            listOf(none, none, 7, 1) to none, // a frame press while LX has it changes nothing
            listOf(none, none, none, 0) to none,
        )
        cases.firstOrNull { (a, want) -> LauncherActivity.keyboardAfterPress(a[0], a[1], a[2], a[3] == 1) != want }
            ?.let { (a, want) -> "holder ${a[0]}, content ${a[1]}, frame ${a[2]}, taken ${a[3] == 1}: expected $want" }
    }

    /**
     * The keys along the sides and bottom of an app's keyboard sit at the edges of its window. A press there must reach
     * the app, not start a resize: a resize press gave the keyboard to LX, and Android closed the app's keyboard. The
     * window still resizes from the band just outside its edge. The app key names no installed app, so no app starts.
     */
    private fun appEdgeChecks() {
        val key = "com.lxlauncher.test/com.lxlauncher.test.NoSuchApp#0"
        val frame = openNormal(EmbeddedAppTool.KIND) {
            activity.model.edit { h -> NativeCore.openWindow(h, EmbeddedAppTool.KIND, key, activity.model.snapshot.active, "floating") }
        } ?: return check("an app window opens for the edge checks") { "no window" }
        val dp = activity.resources.displayMetrics.density
        fun press(name: String, x: Float, y: Float, dx: Float, dy: Float, grows: Boolean) = check(name) {
            val (w0, h0) = main { frame.width to frame.height }
            drag(x, y, dx, dy, 20)
            SystemClock.sleep(300)
            inst.waitForIdleSync()
            val (w1, h1) = main { frame.width to frame.height }
            val want = if (grows) Pair(w0 + dx.toInt(), h0 + dy.toInt()) else Pair(w0, h0)
            if (Math.abs(w1 - want.first) > 2 || Math.abs(h1 - want.second) > 2) "size ${w0}x$h0 became ${w1}x$h1, expected ${want.first}x${want.second}"
            else null
        }
        val loc = IntArray(2)
        fun box(): Rect = main {
            frame.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
        }
        var b = box()
        press("a press 3 dp inside an app window's right edge goes to the app", b.right - 3 * dp, b.exactCenterY(), 60f, 0f, false)
        b = box()
        press("a press 3 dp inside an app window's bottom edge goes to the app", b.exactCenterX(), b.bottom - 3 * dp, 0f, 40f, false)
        b = box()
        press("a press 3 dp inside an app window's left edge goes to the app", b.left + 3 * dp, b.exactCenterY(), -60f, 0f, false)
        b = box()
        press("a drag starting 4 dp outside an app window's right edge resizes it", b.right + 4 * dp, b.exactCenterY(), 60f, 0f, true)
        b = box()
        press("a drag starting just below an app window's bottom right corner resizes both edges", b.right - 8 * dp, b.bottom + 4 * dp, 40f, 30f, true)
        closeAll(EmbeddedAppTool.KIND)
    }

    /**
     * A finger that pulls a corner past the window's smallest size and turns back finds the corner where it left it:
     * the window waits at its smallest size until the finger returns there. It used to grow again at once, so the
     * corner ended as far from the finger as the finger had gone past.
     */
    private fun smallestSizeCheck() = check("a corner pulled past the smallest size comes back under the finger") {
        val frame = openNormal("notes") ?: return@check "no notes window"
        val dp = activity.resources.displayMetrics.density
        val loc = IntArray(2)
        val (w, h) = main {
            frame.getLocationOnScreen(loc)
            frame.width to frame.height
        }
        val finger = Finger(loc[0] + w - 2f, loc[1] + h - 2f)
        // Far past the smallest size, then hold there while the window's size is read.
        finger.moveBy(-w * 0.9f, -h * 0.9f, 30)
        SystemClock.sleep(200)
        val (minW, minH) = main { frame.width to frame.height }
        // Back out to 40 dp over the smallest size.
        val backX = minW + 40 * dp - (w - w * 0.9f)
        val backY = minH + 40 * dp - (h - h * 0.9f)
        finger.moveBy(backX, backY, 30)
        finger.lift()
        SystemClock.sleep(350)
        inst.waitForIdleSync()
        val (aw, ah) = main { frame.width to frame.height }
        closeAll("notes")
        val wantW = Math.round(minW + 40 * dp)
        val wantH = Math.round(minH + 40 * dp)
        if (Math.abs(aw - wantW) > 1 || Math.abs(ah - wantH) > 1)
            "smallest size ${minW}x$minH; the finger came back to ${wantW}x$wantH, the window ended ${aw}x$ah"
        else null
    }

    // ---- opening ----

    private fun openTimes() {
        // The frame commit callback arrived in Android 10.
        if (Build.VERSION.SDK_INT < 29) return info("PERF open: not measured before Android 10")
        for (kind in listOf("settings", "files", "monitor", "notes", "tedit")) {
            val times = mutableListOf<Double>()
            repeat(OPEN_ROUNDS) {
                closeAll(kind)
                SystemClock.sleep(400)
                val committed = CountDownLatch(1)
                var start = 0L
                var end = 0L
                main {
                    val decor = activity.window.decorView
                    start = System.nanoTime()
                    activity.openTool(kind)
                    // The frame that shows the new window is the next one LX commits after the call.
                    decor.viewTreeObserver.registerFrameCommitCallback {
                        end = System.nanoTime()
                        committed.countDown()
                    }
                    decor.invalidate()
                }
                if (committed.await(5, TimeUnit.SECONDS)) times += (end - start) / 1e6
                inst.waitForIdleSync()
            }
            info("PERF open $kind: ${times.joinToString(" ") { "%.0f".format(it) }} ms (median %.0f ms)".format(median(times)))
            closeAll(kind)
        }
    }

    // ---- resizing ----

    /** Drags the bottom right corner of a [kind] window out and back, three times, and reads every frame drawn. */
    private fun resizeRun(kind: String) {
        val frame = openNormal(kind) ?: return check("a $kind window opens for the resize run") { "no window" }
        val layer = frame.parent as WindowLayer
        val vp = Rect(layer.viewport)
        val loc = IntArray(2)
        main {
            frame.getLocationOnScreen(loc)
            val s = activity.model.snapshot
            info("PERF resize $kind: window ${frame.item.id} on workspace ${frame.item.ws} (active ${s.active}), " +
                "${frame.width}x${frame.height} at ${loc[0]},${loc[1]}, shown ${frame.isShown}, viewport $vp")
        }
        val metrics = mutableListOf<LongArray>()
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            synchronized(metrics) { metrics += read(m) }
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener, metricsHandler) }
        SystemClock.sleep(300)
        synchronized(metrics) { metrics.clear() }
        var tracking = 0
        var worst = ""
        var staleContent: String? = null
        val started = System.nanoTime()
        repeat(RESIZE_ROUNDS) {
            for (outward in listOf(true, false)) {
                val layerLoc = IntArray(2)
                val (w, h) = main {
                    frame.getLocationOnScreen(loc)
                    layer.getLocationOnScreen(layerLoc)
                    frame.width to frame.height
                }
                // Room to grow inside the area windows may use; the way back undoes the same distance.
                val dx = if (outward) minOf(RESIZE_PX, vp.right + layerLoc[0] - (loc[0] + w) - 4).coerceAtLeast(0) else -lastDx
                val dy = if (outward) minOf(RESIZE_PX * 2 / 3, vp.bottom + layerLoc[1] - (loc[1] + h) - 4).coerceAtLeast(0) else -lastDy
                if (outward) {
                    lastDx = dx
                    lastDy = dy
                }
                drag(loc[0] + w - 2f, loc[1] + h - 2f, dx.toFloat(), dy.toFloat())
                SystemClock.sleep(350)
                inst.waitForIdleSync()
                val (aw, ah) = main { frame.width to frame.height }
                val error = maxOf(Math.abs(aw - (w + dx)), Math.abs(ah - (h + dy)))
                if (error >= tracking) worst = "${w}x$h dragged by $dx,$dy became ${aw}x$ah"
                tracking = maxOf(tracking, error)
                // Content that sat out frames of the drag takes the window's size once the finger lifts.
                main {
                    val v = frame.tool.view
                    val lp = v.layoutParams as ViewGroup.MarginLayoutParams
                    val ew = frame.width - lp.leftMargin - lp.rightMargin
                    val eh = frame.height - lp.topMargin - lp.bottomMargin
                    if (v.width != ew || v.height != eh) staleContent = "content ${v.width}x${v.height} in a window that holds ${ew}x$eh"
                }
            }
        }
        val ended = System.nanoTime()
        SystemClock.sleep(200)
        main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        val frames = synchronized(metrics) { metrics.filter { it[VSYNC] in started..ended } }
        val budget = 1e9 / activity.refreshRate()
        fun col(i: Int) = frames.map { it[i] / 1e6 }
        val total = col(TOTAL)
        val slow = frames.count { it[TOTAL] > budget }
        val uiSlow = frames.count { it[UI] > budget }
        info(("PERF resize $kind: ${frames.size} frames, over budget ${slow} (%.1f%%), total median %.1f p90 %.1f p99 %.1f ms; " +
            "ui thread median %.2f p90 %.2f p99 %.2f (over budget $uiSlow); layout median %.2f; draw median %.2f; " +
            "sync p90 %.2f; issue median %.2f p90 %.2f; gpu p90 %.2f; swap median %.2f p90 %.2f; delay median %.2f p90 %.2f ms")
            .format(100.0 * slow / maxOf(1, frames.size), median(total), pct(total, 90), pct(total, 99),
                median(col(UI)), pct(col(UI), 90), pct(col(UI), 99), median(col(LAYOUT)), median(col(DRAW)),
                pct(col(SYNC), 90), median(col(ISSUE)), pct(col(ISSUE), 90), pct(col(GPU), 90),
                median(col(SWAP)), pct(col(SWAP), 90), median(col(DELAY)), pct(col(DELAY), 90)))
        check("a $kind window's corner stays under the finger while it resizes") {
            if (tracking > 1) "the window ended $tracking px away from the finger ($worst)" else null
        }
        check("a $kind window's content fills it again when the resize drag ends") { staleContent }
        closeAll(kind)
    }

    private var lastDx = 0
    private var lastDy = 0

    /**
     * A floating [kind] window a little over half the area windows may use (no smaller than the window minimum), in the
     * top left of it. The place does not depend on where earlier runs left the window, so a resize run always has room.
     */
    private fun openNormal(kind: String, open: () -> Unit = { activity.openTool(kind) }): WindowLayer.WindowFrame? {
        closeAll(kind)
        main { open() }
        inst.waitForIdleSync()
        SystemClock.sleep(500)
        val id = main { frames().firstOrNull { it.item.kind == kind }?.item?.id } ?: return null
        main {
            val layer = frames().first { it.item.id == id }.parent as WindowLayer
            val vp = layer.viewport
            activity.model.edit { h ->
                NativeCore.setWindowState(h, id, "floating")
                val b = NativeCore.windowBounds(h, id) ?: return@edit false
                NativeCore.moveWindow(h, id, vp.left + vp.width() * 0.05f - b[0], vp.top + vp.height() * 0.05f - b[1])
                NativeCore.resizeWindow(h, id, NativeCore.EDGE_RIGHT or NativeCore.EDGE_BOTTOM,
                    vp.width() * 0.55f - b[2], vp.height() * 0.55f - b[3])
                true
            }
        }
        inst.waitForIdleSync()
        SystemClock.sleep(500)
        return main { frames().firstOrNull { it.item.id == id } }
    }

    private fun closeAll(kind: String) = main {
        activity.model.edit { h ->
            var closed = false
            for (w in activity.model.snapshot.windows) if (w.kind == kind) closed = NativeCore.closeWindow(h, w.id) || closed
            closed
        }
    }

    private fun frames(): List<WindowLayer.WindowFrame> {
        val out = mutableListOf<WindowLayer.WindowFrame>()
        fun walk(v: View) {
            if (v is WindowLayer.WindowFrame) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window.decorView)
        return out
    }

    // ---- input ----

    /** A finger pressed at (x, y), moved by (dx, dy) over [steps] frames at 60 Hz, and lifted. */
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, steps: Int = DRAG_STEPS) {
        val finger = Finger(x, y)
        finger.moveBy(dx, dy, steps)
        finger.lift()
    }

    /** One finger on the screen, pressed where it is made and moved at 60 Hz until [lift]. */
    private inner class Finger(private var x: Float, private var y: Float) {
        private val down = SystemClock.uptimeMillis().also { inject(it, it, MotionEvent.ACTION_DOWN, x, y) }

        fun moveBy(dx: Float, dy: Float, steps: Int) {
            val fromX = x
            val fromY = y
            for (i in 1..steps) {
                SystemClock.sleep(STEP_MS)
                x = fromX + dx * i / steps
                y = fromY + dy * i / steps
                inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
            }
        }

        fun lift() {
            SystemClock.sleep(STEP_MS)
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
        }
    }

    private fun inject(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(down, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        inst.uiAutomation.injectInputEvent(e, false)
        e.recycle()
    }

    // ---- helpers ----

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun read(m: FrameMetrics): LongArray = LongArray(10).also {
        it[TOTAL] = m.getMetric(FrameMetrics.TOTAL_DURATION)
        // The UI thread's share: input (where a resize now lays its window out), animation, layout and recording.
        it[UI] = m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
        it[LAYOUT] = m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
        it[DRAW] = m.getMetric(FrameMetrics.DRAW_DURATION)
        it[SYNC] = m.getMetric(FrameMetrics.SYNC_DURATION)
        it[ISSUE] = m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
        it[GPU] = if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else 0L
        it[VSYNC] = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        // The driver hands the frame to the GPU in the swap on this phone (a tile renderer); the delay is time before
        // the frame's work began, waiting on the main thread or on an earlier frame.
        it[SWAP] = m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        it[DELAY] = m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
    }

    private fun median(v: List<Double>) = pct(v, 50)

    private fun pct(v: List<Double>, p: Int): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[((s.size - 1) * p / 100.0).toInt()]
    }

    private companion object {
        const val START_WAIT_MS = 15_000L
        const val SETTLE_MS = 2_500L
        const val OPEN_ROUNDS = 3
        const val RESIZE_ROUNDS = 3
        const val RESIZE_PX = 300
        const val DRAG_STEPS = 45
        const val STEP_MS = 16L
        const val TOTAL = 0
        const val LAYOUT = 1
        const val DRAW = 2
        const val SYNC = 3
        const val ISSUE = 4
        const val GPU = 5
        const val VSYNC = 6
        const val UI = 7
        const val SWAP = 8
        const val DELAY = 9
    }
}

@Suppress("DEPRECATION")
private fun Activity.refreshRate(): Float =
    (if (Build.VERSION.SDK_INT >= 30) display?.refreshRate else windowManager.defaultDisplay.refreshRate) ?: 60f
