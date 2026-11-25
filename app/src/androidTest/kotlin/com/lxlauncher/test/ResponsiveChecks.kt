// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.ui.windows.WindowLayer
import java.io.File

/**
 * Every LX tool in a small window (13 November 2026), run with `-e only responsive`: each tool's window is set to the
 * smallest size LX allows and to a few sizes above it, and its content is read for what a person would see as broken:
 * a control cut by the window's edge, text cut through its height, a list or editor squeezed below one usable row, and
 * controls lying over each other. Things inside something that scrolls count only where the scroller itself is cut.
 * A picture of the window at each size goes to LX's cache, in responsive/, for a person to look at.
 */
internal class ResponsiveChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit) {
    private lateinit var activity: LauncherActivity
    private val ctx = inst.targetContext
    private val shots = File(ctx.cacheDir, "responsive").apply { deleteRecursively(); mkdirs() }

    fun run() {
        check("LX starts for the small-window checks") {
            val monitor = inst.addMonitor(LauncherActivity::class.java.name, null, false)
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(ctx, LauncherActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val a = inst.waitForMonitorWithTimeout(monitor, 10_000) as? LauncherActivity
            inst.removeMonitor(monitor)
            if (a == null) return@check "LauncherActivity did not start"
            activity = a
            inst.waitForIdleSync()
            SystemClock.sleep(1500)
            null
        }
        if (!::activity.isInitialized) return
        for (kind in TOOLS) tool(kind)
    }

    private fun tool(kind: String) {
        closeAll(kind)
        main { activity.openTool(kind) }
        inst.waitForIdleSync()
        SystemClock.sleep(900)
        val id = main { frames().firstOrNull { it.item.kind == kind }?.item?.id }
        if (id == null) { check("$kind opens") { "no $kind window" }; return }
        val dp = ctx.resources.displayMetrics.density
        for ((w, h) in SIZES) {
            check("$kind at ${w}x$h dp: nothing cut, squeezed or overlapping") {
                main {
                    val layer = frames().first { it.item.id == id }.parent as WindowLayer
                    val vp = layer.viewport
                    activity.model.edit { m ->
                        NativeCore.setWindowState(m, id, "floating")
                        val b = NativeCore.windowBounds(m, id) ?: return@edit false
                        NativeCore.moveWindow(m, id, vp.left - b[0], vp.top - b[1])
                        val b2 = NativeCore.windowBounds(m, id) ?: return@edit false
                        NativeCore.resizeWindow(m, id, NativeCore.EDGE_RIGHT or NativeCore.EDGE_BOTTOM, w * dp - b2[2], h * dp - b2[3])
                        val b3 = NativeCore.windowBounds(m, id) ?: return@edit false
                        NativeCore.moveWindow(m, id, vp.left + 8 * dp - b3[0], vp.top + 8 * dp - b3[1])
                        true
                    }
                }
                inst.waitForIdleSync()
                SystemClock.sleep(700)
                inst.waitForIdleSync()
                val frame = main { frames().firstOrNull { it.item.id == id } } ?: return@check "the window went away"
                val (fw, fh) = main { frame.width to frame.height }
                val problems = main { audit(frame) }
                shot(frame, "$kind-${w}x$h.png")
                info("INFO $kind ${w}x$h dp (frame ${fw}x$fh px): ${problems.size} problems${if (problems.isEmpty()) "" else ": " + problems.take(6).joinToString("; ")}")
                if (problems.isNotEmpty()) info("INFO layout of $kind ${w}x$h: " + main { layoutOf(frame.tool.view) })
                if (problems.isEmpty()) null else problems.take(4).joinToString("; ")
            }
        }
        closeAll(kind)
    }

    /** What is broken in [frame]'s content, as short descriptions. */
    private fun audit(frame: WindowLayer.WindowFrame): List<String> {
        val root = frame.tool.view
        val dp = ctx.resources.displayMetrics.density
        val win = Rect()
        if (!root.getGlobalVisibleRect(win)) return listOf("content not visible")
        val out = ArrayList<String>()
        val controls = ArrayList<Pair<View, Rect>>()

        fun name(v: View): String {
            val t = (v as? TextView)?.text?.toString()?.takeIf { it.isNotBlank() }?.take(24)
            val d = v.contentDescription?.toString()?.take(24)
            val id = if (v.id != View.NO_ID) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
            return listOfNotNull(t?.let { "'$it'" }, d?.let { "<$it>" }, id).firstOrNull() ?: v.javaClass.simpleName
        }

        fun isScroller(v: View) = v is ScrollView || v is HorizontalScrollView || v is AbsListView ||
            v.javaClass.simpleName.contains("Recycler") || v.javaClass.simpleName.contains("Scroll")

        fun walk(v: View, inScroller: Boolean) {
            if (!v.isShown || v.alpha < 0.05f || v.width == 0 || v.height == 0) return
            val r = Rect()
            val visible = v.getGlobalVisibleRect(r)
            val area = v.width * v.height
            val seen = if (visible) r.width() * r.height() else 0
            val scroller = isScroller(v)
            val contentView = v.javaClass.simpleName == "CodeEditView" || v.javaClass.simpleName == "TerminalView"
            if ((scroller || contentView) && !inScroller && visible) {
                if (v is HorizontalScrollView) {
                    // A bar that scrolls sideways is one row tall by design; it is broken only when it is cut.
                    if (r.height() < v.height - 2) out += "bar ${name(v)} cut to ${(r.height() / dp).toInt()} of ${(v.height / dp).toInt()} dp"
                } else if (r.height() < 40 * dp) {
                    // A list, a page, an editor or a terminal that the window squeezes below one usable row.
                    out += "${name(v)} only ${(r.height() / dp).toInt()} dp tall"
                }
            }
            val interactive = v.isClickable || v.isLongClickable
            if (interactive && !inScroller) {
                if (!visible || seen < area * 0.6f) out += "control ${name(v)} cut (${(seen * 100L / maxOf(1, area))}% shown)"
                else controls += v to r
            }
            if (v is TextView && !interactive && !inScroller && v.text.isNotBlank() && v !is android.widget.EditText) {
                if (visible && r.height() < v.height - 2 && r.height() < (v.lineHeight * 0.9f)) out += "text ${name(v)} cut through its height"
            }
            if (v is ViewGroup && !scroller) for (i in 0 until v.childCount) walk(v.getChildAt(i), inScroller)
            else if (v is ViewGroup && scroller) for (i in 0 until v.childCount) walk(v.getChildAt(i), true)
        }
        walk(root, false)
        // A layer that covers its whole frame and takes every touch, like Tedit's panel drawer over its scrim, is modal:
        // the controls drawn under it cannot be reached while it is open, so they are left out of the comparison.
        val order = HashMap<View, Int>().apply { controls.forEachIndexed { i, (v, _) -> put(v, i) } }
        val under = HashSet<View>()
        for ((m, mr) in controls) {
            val frame = m.parent as? android.widget.FrameLayout ?: continue
            val fr = Rect()
            if (m !is ViewGroup || !frame.getGlobalVisibleRect(fr) || mr.width() * mr.height() < fr.width() * fr.height() * 0.95f) continue
            for ((v, _) in controls) if (v !== m && !isAncestor(m, v) && isAncestor(frame, v) && order.getValue(v) < order.getValue(m)) under += v
        }
        // Controls on top of each other: more than a quarter of the smaller one covered, ignoring a control inside another.
        for (i in controls.indices) for (j in i + 1 until controls.size) {
            val (a, ra) = controls[i]
            val (b, rb) = controls[j]
            if (isAncestor(a, b) || isAncestor(b, a) || a in under || b in under) continue
            val x = Rect()
            if (x.setIntersect(ra, rb)) {
                val small = minOf(ra.width() * ra.height(), rb.width() * rb.height())
                if (x.width() * x.height() > small / 4) out += "controls ${name(a)} and ${name(b)} overlap"
            }
        }
        return out.distinct()
    }

    /** The first levels of a view tree with heights in dp, for reading why something was squeezed. */
    private fun layoutOf(root: View): String {
        val dp = ctx.resources.displayMetrics.density
        val sb = StringBuilder()
        fun walk(v: View, depth: Int) {
            if (depth > 5 || v.visibility == View.GONE) return
            sb.append(" | ").append("-".repeat(depth)).append(v.javaClass.simpleName).append(' ').append((v.height / dp).toInt())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString().take(1500)
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p is View) { if (p === a) return true; p = p.parent }
        return false
    }

    private fun shot(frame: WindowLayer.WindowFrame, file: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val loc = IntArray(2)
        val (w, h) = main { frame.getLocationOnScreen(loc); frame.width to frame.height }
        val x = loc[0].coerceIn(0, bmp.width - 1)
        val y = loc[1].coerceIn(0, bmp.height - 1)
        val crop = Bitmap.createBitmap(bmp, x, y, minOf(w, bmp.width - x), minOf(h, bmp.height - y))
        File(shots, file).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        bmp.recycle()
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

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        inst.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    companion object {
        val TOOLS = listOf("files", "settings", "monitor", "notes", "cleanup", "appmgr", "welcome", "tedit")
        /** Window sizes in dp, title bar included: LX's smallest window, then a few steps up. */
        val SIZES = listOf(240 to 180, 280 to 240, 320 to 320, 360 to 480)
    }
}
