// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.files.FileServices
import com.lxlauncher.tedit.CodeEditView
import com.lxlauncher.tedit.DocSource
import com.lxlauncher.tedit.Document
import com.lxlauncher.tedit.EditHistory
import com.lxlauncher.tedit.FindQuery
import com.lxlauncher.tedit.FindState
import com.lxlauncher.tedit.Highlighter
import com.lxlauncher.tedit.Languages
import com.lxlauncher.tedit.Pos
import com.lxlauncher.tedit.SearchPanel
import com.lxlauncher.tedit.SpanSink
import com.lxlauncher.tedit.TeditPrefs
import com.lxlauncher.tedit.TeditTool
import com.lxlauncher.tedit.TextBuffer
import com.lxlauncher.tedit.TextCodec
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Tedit under load (12 November 2026), run alone with `-e only teditload`: random edits against a simple model for
 * the buffer, undo and highlighting, every lexer on random lines, then a Tedit window on the device with large files,
 * fast scrolling, typing, many tabs, a search across thousands of files, a flood of terminal output, and windows opened
 * and closed over and over. Lines starting with PERF are measurements; checks fail only on wrong results, crashes,
 * leaks and frames far past their deadline. Files go under LX's cache and are removed afterwards.
 */
internal class TeditStress(
    private val inst: Instrumentation,
    private val check: (String, () -> String?) -> Unit,
    private val info: (String) -> Unit,
    /** Parts to run (fuzz, files, typing, tabs, search, terminal, cycles, profile); empty runs them all. */
    private val only: Set<String> = emptySet(),
) {
    private fun wants(part: String) = if (only.isEmpty()) part !in setOf("profile", "show", "themes", "lspreal", "showext") else part in only

    private val ctx = inst.targetContext
    private val scratch = File(ctx.cacheDir, "tedit-load-${System.nanoTime()}").apply { mkdirs() }

    fun run() {
        try {
            if (wants("fuzz")) {
                bufferFuzz()
                highlightFuzz()
                lexerFuzz()
                findFuzz()
                asyncFind()
                codecFuzz()
            }
            if (listOf("files", "typing", "tabs", "search", "terminal", "cycles", "profile", "show", "themes", "commands", "lspreal", "showext").any { wants(it) }) keepTeditState(ctx) { device() }
        } finally {
            scratch.deleteRecursively()
        }
    }

    // ---- the editor core against a model ----

    private fun randomText(r: Random, max: Int): String {
        val alphabet = "abcdefgh xyz{}()[];\"'/*\\\t\n\né中😀"
        val n = r.nextInt(max + 1)
        val sb = StringBuilder()
        while (sb.length < n) {
            val i = r.nextInt(alphabet.length)
            val c = alphabet[i]
            // Keep surrogate pairs whole.
            if (Character.isHighSurrogate(c) && i + 1 < alphabet.length) sb.append(c).append(alphabet[i + 1])
            else if (!Character.isLowSurrogate(c)) sb.append(c)
        }
        return sb.toString()
    }

    private fun randomPos(r: Random, b: TextBuffer): Pos {
        val line = r.nextInt(b.lineCount)
        return Pos(line, r.nextInt(b.lineLength(line) + 1))
    }

    private fun bufferFuzz() {
        check("4000 random edits, undos and redos keep the buffer equal to a plain string model") {
            for (seed in 1..3) {
                val r = Random(seed)
                val b = TextBuffer("start\nof the\ntext")
                val h = EditHistory(b)
                // states[i] is the text after i steps; undo walks back, redo forward.
                val states = arrayListOf(b.text())
                var at = 0
                for (op in 0 until 4000) {
                    when (r.nextInt(10)) {
                        in 0..5 -> {
                            val a = randomPos(r, b)
                            val e = if (r.nextInt(3) == 0) randomPos(r, b) else a
                            h.apply(a, e, randomText(r, 12), a to a, EditHistory.Kind.OTHER, op * 5000L)
                            while (states.size > at + 1) states.removeAt(states.size - 1)
                            states += b.text()
                            at++
                        }
                        in 6..7 -> if (h.undo() != null) at--
                        else -> if (h.redo() != null) at++
                    }
                    val want = states[at]
                    if (b.text() != want) return@check "seed $seed op $op: buffer differs from the model"
                    if (b.length != want.length) return@check "seed $seed op $op: length ${b.length} != ${want.length}"
                    if (b.lineCount != want.count { it == '\n' } + 1) return@check "seed $seed op $op: line count"
                }
                // Offsets and positions agree both ways.
                for (k in 0 until 200) {
                    val off = r.nextInt(b.length + 1)
                    if (b.offsetOf(b.posOf(off)) != off) return@check "seed $seed: offset $off does not round-trip"
                }
            }
            null
        }
        check("typing merged into undo steps still undoes to the start and redoes to the end") {
            val r = Random(7)
            val b = TextBuffer("fun main() {\n}\n")
            val h = EditHistory(b)
            val first = b.text()
            var caret = Pos(0, 12)
            var now = 0L
            repeat(3000) {
                now += r.nextLong(30, 1500)
                when (r.nextInt(6)) {
                    in 0..3 -> {
                        val t = if (r.nextInt(12) == 0) "\n" else ('a' + r.nextInt(26)).toString()
                        caret = h.apply(caret, caret, t, caret to caret, EditHistory.Kind.TYPE, now)
                    }
                    4 -> if (caret.col > 0) {
                        val from = Pos(caret.line, caret.col - 1)
                        caret = h.apply(from, caret, "", caret to caret, EditHistory.Kind.DELETE, now)
                    }
                    else -> { caret = randomPos(r, b); h.sealed = true }
                }
            }
            val last = b.text()
            var undos = 0
            while (h.undo() != null) undos++
            if (b.text() != first) return@check "undo of $undos steps left ${b.text().length} chars instead of the start"
            while (h.redo() != null) {}
            if (b.text() != last) "redo did not reach the end" else null
        }
    }

    private val codeSample = """
        package demo
        /* A block comment
           over "two" lines */
        import kotlin.math.max
        // line comment with "quotes"
        class Box<T>(val value: T) {
            fun twice(n: Int): Int = n * 2 + 0x1F
            val s = "string with \"escape\" and ${'$'}{value} template"
            val c = 'c'
            val raw = ""${'"'}raw
        string""${'"'}
        }
        fun main() { println(Box(1).twice(21)) }
    """.trimIndent()

    private fun highlightFuzz() {
        check("highlighting after random edits matches highlighting the same text from scratch") {
            val ids = listOf("kotlin", "java", "javascript", "python", "c", "html", "css", "json", "markdown", "xml", "shell", "yaml")
            for (id in ids) {
                val lang = Languages.byId(id) ?: continue
                val r = Random(id.hashCode())
                val b = TextBuffer((codeSample + "\n").repeat(20))
                val inc = Highlighter(b, lang, 20000)
                for (round in 0 until 60) {
                    repeat(8) {
                        val a = randomPos(r, b)
                        val e = if (r.nextInt(3) == 0) randomPos(r, b) else a
                        val ins = listOf("/*", "*/", "\"", "'", "\n", "<!--", "-->", "```", "#", "{", "}", "\"\"\"", "x", "\\", "`").random(r)
                        b.replace(Pos.min(a, e), Pos.max(a, e), ins)
                    }
                    // Lines are asked for in scrolling order, starting anywhere.
                    val from = r.nextInt(b.lineCount)
                    for (i in from until minOf(b.lineCount, from + 40)) inc.spans(i)
                    if (round % 10 == 9) {
                        val fresh = Highlighter(TextBuffer(b.text()), lang, 20000)
                        for (i in 0 until b.lineCount) {
                            if (!inc.spans(i).contentEquals(fresh.spans(i))) return@check "$id round $round: line $i differs from a fresh highlight"
                        }
                    }
                }
                inc.dispose()
            }
            null
        }
    }

    private fun lexerFuzz() {
        check("every language highlights 3000 random lines without an error or a broken span") {
            val r = Random(11)
            val alphabet = "aZ09 _-+*/\\%<>=!&|^~?:;,.'\"`#@\$(){}[]\té中"
            val sink = SpanSink()
            for (lang in Languages.all) {
                var state = 0
                for (k in 0 until 3000) {
                    val len = if (k % 100 == 0) 3000 else r.nextInt(80)
                    val line = buildString { repeat(len) { append(alphabet[r.nextInt(alphabet.length)]) } }
                    sink.reset()
                    state = try {
                        lang.lexer.lex(line, state, sink)
                    } catch (t: Throwable) {
                        return@check "${lang.id}: ${t.javaClass.simpleName} on a line of $len chars"
                    }
                    var lastEnd = 0
                    var i = 0
                    while (i < sink.size) {
                        val s = sink.data[i]; val e = sink.data[i + 1]
                        if (s < lastEnd || e <= s || e > line.length) return@check "${lang.id}: span $s..$e on a line of ${line.length}"
                        lastEnd = e
                        i += 3
                    }
                }
            }
            null
        }
    }

    private fun findFuzz() {
        check("find agrees with a plain regex scan of each line") {
            val r = Random(3)
            val words = listOf("foo", "Foo", "FOO", "F\u00f3o", "\u0130foo", "\u212Aoo", "bar", "foo_bar", "a", "", "x.y", "foo\u00b2")
            val text = buildString { repeat(400) { append(words.random(r)).append(if (r.nextInt(5) == 0) "\n" else " ") } }
            val b = TextBuffer(text)
            for (k in 0 until 60) {
                val q = FindQuery(listOf("foo", "Foo", "koo", "a", "x.y", "o+", "\\bbar\\b", "^f").random(r), r.nextBoolean(), r.nextBoolean(), r.nextBoolean())
                val f = FindState()
                f.set(q)
                f.update(b)
                val p = q.compile().getOrNull() ?: continue
                var n = 0
                for (i in 0 until b.lineCount) {
                    val m = p.matcher(b.line(i))
                    var from = 0
                    while (from <= b.lineLength(i) && m.find(from)) {
                        if (f.matches[n * 3] != i || f.matches[n * 3 + 1] != m.start() || f.matches[n * 3 + 2] != m.end()) return@check "$q: match $n differs"
                        n++
                        from = if (m.end() > m.start()) m.end() else m.end() + 1
                    }
                }
                if (n != f.count) return@check "$q: ${f.count} matches, the scan found $n"
            }
            null
        }
    }

    private fun asyncFind(): Unit = check("a large buffer is searched off the main thread and finds what a search on the spot finds") {
        val b = TextBuffer((0 until 60_000).joinToString("\n") { i -> if (i % 7 == 0) "value $i Needle_x" else "line $i needle" })
        for (q in listOf(FindQuery("needle"), FindQuery("Needle", caseSensitive = true), FindQuery("needle", wholeWord = true), FindQuery("n[e]+dle", regex = true))) {
            val bg = FindState()
            val latch = CountDownLatch(1)
            var mainThread = false
            var started = 0L
            inst.runOnMainSync {
                bg.onResult = { mainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(); latch.countDown() }
                bg.set(q)
                started = System.nanoTime()
                bg.update(b)
                started = System.nanoTime() - started
            }
            if (!latch.await(20, TimeUnit.SECONDS)) return@check "$q: no answer from the worker"
            val now = FindState().apply { set(q); updateNow(b) }
            if (!mainThread) return@check "$q: the answer did not come back on the main thread"
            if (bg.count != now.count || !bg.matches.contentEquals(now.matches)) return@check "$q: ${bg.count} matches in the background, ${now.count} on the spot"
            if (started > 20_000_000) return@check "$q: update() held the main thread %.1f ms".format(started / 1e6)
        }
        null
    }

    private fun codecFuzz() {
        check("text survives every encoding it can be written in") {
            val r = Random(5)
            for (k in 0 until 300) {
                val s = randomText(r, 200)
                for (c in TextCodec.entries) {
                    val enc = c.charset.newEncoder()
                    if (!enc.canEncode(s)) continue
                    val bytes = c.encode(s)
                    val back = TextCodec.decode(bytes, bytes.size, c)
                    if (back != s) return@check "${c.id} changed the text"
                    if (c.bom.isNotEmpty() && TextCodec.detect(bytes, bytes.size, TextCodec.UTF8) != c) return@check "${c.id} not detected from its BOM"
                }
            }
            null
        }
    }

    // ---- on the device: a real Tedit window ----

    private class FrameStats(val n: Int, val p50: Double, val p90: Double, val p99: Double, val max: Double, val over: Int, val farOver: Int, val deadline: Double) {
        override fun toString() = "frames $n p50 %.1f p90 %.1f p99 %.1f max %.1f ms, late %d, past 3x %d (budget %.1f ms)"
            .format(p50, p90, p99, max, over, farOver, deadline)
    }

    /** One refresh of the display, in ns: the budget a frame has before it shows late. */
    private var vsyncNs = 16_666_667L

    /** Collects the duration of every frame the window draws while running. */
    private inner class Frames(private val window: Window) {
        private val totals = ArrayList<Long>()
        private val deadlines = ArrayList<Long>()
        private val thread = HandlerThread("tedit-load-frames").apply { start() }
        private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            synchronized(totals) {
                totals += m.getMetric(FrameMetrics.TOTAL_DURATION)
                deadlines += if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.DEADLINE) else 16_666_667L
            }
        }

        init { inst.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) } }

        fun stop(): FrameStats {
            SystemClock.sleep(300)
            inst.runOnMainSync { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
            val t: List<Long>
            val d: List<Long>
            synchronized(totals) { t = totals.toList(); d = deadlines.toList() }
            if (t.isEmpty()) return FrameStats(0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0.0)
            val sorted = t.sorted()
            fun pct(p: Double) = sorted[minOf(sorted.size - 1, (sorted.size * p).toInt())] / 1e6
            // Judged against the display's refresh, not the deadline Android reports: a frame that is already late
            // stretches the next deadline, and a run of slow frames would then pass itself.
            val budget = maxOf(vsyncNs, d.minOrNull() ?: vsyncNs)
            val over = t.count { it > budget }
            val farOver = t.count { it > budget * 3 }
            return FrameStats(t.size, pct(0.5), pct(0.9), pct(0.99), sorted.last() / 1e6, over, farOver, budget / 1e6)
        }
    }

    /** Runs [action] once per frame for [n] frames on the main thread; returns the main-thread time [action] took, in ms. */
    private fun perFrame(n: Int, action: (Int) -> Unit): Double {
        val done = CountDownLatch(1)
        var cost = 0L
        var failure: Throwable? = null
        inst.runOnMainSync {
            val ch = Choreographer.getInstance()
            var i = 0
            ch.postFrameCallback(object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (i >= n) { done.countDown(); return }
                    val s = System.nanoTime()
                    // An error here would end LX itself; it is handed to the check instead.
                    try { action(i++) } catch (t: Throwable) { failure = t; done.countDown(); return }
                    cost += System.nanoTime() - s
                    ch.postFrameCallback(this)
                }
            })
        }
        done.await(120, TimeUnit.SECONDS)
        failure?.let { throw it }
        return cost / 1e6
    }

    private fun gc() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(80)
        }
    }

    /** Java heap and native heap in use after a full collection, and the process PSS, all in MB. */
    private fun memory(): String {
        gc()
        val rt = Runtime.getRuntime()
        val java = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        val native = Debug.getNativeHeapAllocatedSize() / 1048576.0
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return "java %.1f MB, native %.1f MB, pss %.1f MB".format(java, native, mi.totalPss / 1024.0)
    }

    private fun javaMb(): Double {
        gc()
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0
    }

    // tedit-find is one worker for the whole process, not one per window.
    private fun teditThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("tedit-") && !it.name.startsWith("tedit-load") && it.name != "tedit-find" }

    private fun toolOf(a: LauncherActivity): TeditTool? {
        val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
        return (f.get(a) as com.lxlauncher.ui.windows.WindowLayer).focusedTool() as? TeditTool
    }

    private fun editorFor(v: View, d: Document): CodeEditView? {
        if (v is CodeEditView && v.isAttachedToWindow && v.doc === d) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) editorFor(v.getChildAt(i), d)?.let { return it }
        return null
    }

    private fun closeTedit(a: LauncherActivity) {
        inst.runOnMainSync {
            a.model.snapshot.windows.filter { it.kind == TeditTool.KIND }.forEach { w -> a.model.edit { h -> NativeCore.closeWindow(h, w.id) } }
        }
        inst.waitForIdleSync()
    }

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Long {
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            var ok = false
            inst.runOnMainSync { ok = test() }
            if (ok) return SystemClock.uptimeMillis() - start
            SystemClock.sleep(20)
        }
        return -1
    }

    private fun openInTedit(a: LauncherActivity, f: File): Pair<TeditTool, Document>? {
        var tool: TeditTool? = null
        inst.runOnMainSync { tool = toolOf(a) }
        val t = tool
        if (t == null) {
            inst.runOnMainSync { a.openTool(TeditTool.KIND, "open\u0000local\u0000${f.absolutePath}") }
            waitFor(5000) { toolOf(a) != null }
            inst.runOnMainSync { tool = toolOf(a) }
        } else {
            inst.runOnMainSync { t.open(DocSource.Node("local", f.absolutePath)) }
        }
        val tt = tool ?: return null
        var doc: Document? = null
        waitFor(60_000) { doc = tt.store.docs.values.firstOrNull { it.name == f.name && !it.loading }; doc != null }
        // The editor draws the document on the next frame.
        waitFor(3000) { doc != null && editorFor(a.window.decorView, doc!!) != null }
        return doc?.let { tt to it }
    }

    private fun closeDoc(tool: TeditTool, d: Document) {
        inst.runOnMainSync {
            for (g in tool.groups.toList()) g.tabs.filter { it.doc === d }.forEach { tool.closeTab(g, it, force = true) }
        }
        inst.waitForIdleSync()
    }

    // Files written for the run.

    private fun writeLines(name: String, lines: Int, line: (Int) -> String): File {
        val f = File(scratch, name)
        f.bufferedWriter(Charsets.UTF_8, 1 shl 16).use { w -> for (i in 0 until lines) { w.write(line(i)); w.write("\n") } }
        return f
    }

    private fun device() {
        if (FileServices.storage(ctx).local() == null) {
            info("INFO no all-files access: the window part needs it and was skipped")
            return
        }
        val sampleLines = codeSample.lines()
        val code = writeLines("code.kt", 30_000) { i -> sampleLines[i % sampleLines.size].replace("Box", "Box${i / sampleLines.size}") }
        val log = writeLines("big.log", 110_000) { i -> "2026-11-12 01:%02d:%02d.%03d I/Worker(%d): request %d finished in %d ms status=ok user=u%d".format(i / 3600 % 60, i / 60 % 60, i % 1000, 1000 + i % 97, i, i % 900, i % 5000) }
        val huge = writeLines("huge.log", 480_000) { i -> "line $i of a file larger than the limit Tedit reads whole, padded with text to about 80 bytes" }
        val minified = File(scratch, "min.json").apply {
            bufferedWriter().use { w -> w.write("{"); for (i in 0 until 90_000) { if (i > 0) w.write(","); w.write("\"k$i\":[$i,\"v$i\",{\"n\":null}]") }; w.write("}") }
        }
        val wide = writeLines("wide.txt", 20_000) { i -> "$i ".padEnd(400, ('a' + i % 26)) }
        info("PERF files: code.kt ${code.length() / 1024} KB, big.log ${log.length() / 1024} KB, huge.log ${huge.length() / 1048576} MB, min.json ${minified.length() / 1024} KB in one line, wide.txt ${wide.length() / 1024} KB")

        var activity: LauncherActivity? = null
        check("LX starts for the load run") {
            val monitor = inst.addMonitor(LauncherActivity::class.java.name, null, false)
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(ctx, LauncherActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = inst.waitForMonitorWithTimeout(monitor, 10_000) as? LauncherActivity
            inst.removeMonitor(monitor)
            inst.waitForIdleSync()
            SystemClock.sleep(1500)
            if (activity == null) "LauncherActivity did not start" else null
        }
        val a = activity ?: return
        @Suppress("DEPRECATION")
        val hz = a.windowManager.defaultDisplay.refreshRate
        if (hz > 1f) vsyncNs = (1e9 / hz).toLong()
        info("PERF display ${"%.0f".format(hz)} Hz; before Tedit: ${memory()}, tedit threads ${teditThreads()}")
        try {
            if (wants("files")) largeFiles(a, code, log, huge, minified, wide)
            if (wants("profile")) profile(a, code, wide, minified)
            if (wants("show") || wants("themes")) show(a)
            if (wants("commands")) commandFuzz(a)
            if (wants("lspreal")) { lspReal(a); runReal(a) }
            if (wants("showext")) showExt(a)
            if (wants("typing")) typing(a, code)
            if (wants("tabs")) tabs(a)
            if (wants("search")) search(a)
            if (wants("terminal")) terminal(a)
            if (wants("cycles")) workspaceReopen(a)
        } finally {
            closeTedit(a)
        }
        if (wants("cycles")) cycles(a, code)
        a.model.saveSoon(0)
        SystemClock.sleep(800)
    }

    private fun scrollRun(a: LauncherActivity, ed: CodeEditView, frames: Int, stepPx: Float): FrameStats {
        val fm = Frames(a.window)
        var y = 0f
        perFrame(frames) { y += stepPx; ed.scrollToY(y) }
        return fm.stop()
    }

    private class Opened(val took: Long, val held: Double, val lines: Int, val readOnly: Boolean, val fast: FrameStats, val slow: FrameStats, val findMs: Double, val hits: Int)

    /** Opens [f], scrolls it, finds in it and closes it; nothing it returns points at the document. */
    private fun openScrollClose(a: LauncherActivity, f: File, before: Double): Opened? {
        val start = SystemClock.uptimeMillis()
        val (tool, d) = openInTedit(a, f) ?: return null
        val took = SystemClock.uptimeMillis() - start
        val held = javaMb() - before
        var ed: CodeEditView? = null
        inst.runOnMainSync { ed = editorFor(a.window.decorView, d) }
        val e = ed ?: return null
        var lines = 0
        var readOnly = false
        inst.runOnMainSync { lines = d.buffer.lineCount; readOnly = d.readOnly }
        // A fling at Android's top speed moves about a tenth of this editor per frame; then a slow read.
        val fast = scrollRun(a, e, 180, e.height / 10f)
        inst.runOnMainSync { e.scrollToY(0f) }
        val slow = scrollRun(a, e, 120, e.height / 40f)
        var findMs = 0.0
        var hits = 0
        inst.runOnMainSync {
            val fs = FindState()
            fs.set(FindQuery("finished", false, false, false))
            val s = System.nanoTime()
            fs.updateNow(d.buffer)
            findMs = (System.nanoTime() - s) / 1e6
            hits = fs.count
        }
        closeDoc(tool, d)
        return Opened(took, held, lines, readOnly, fast, slow, findMs, hits)
    }

    private fun largeFiles(a: LauncherActivity, vararg files: File) {
        for (f in files) {
            check("${f.name} opens, scrolls and closes") {
                val before = javaMb()
                val o = openScrollClose(a, f, before) ?: return@check "did not open"
                val after = javaMb() - before
                info("PERF ${f.name}: open ${o.took} ms, ${o.lines} lines, read-only ${o.readOnly}, holds %.1f MB java".format(o.held))
                info("PERF ${f.name} fling: ${o.fast}")
                info("PERF ${f.name} slow scroll: ${o.slow}")
                info("PERF ${f.name}: find 'finished' ${o.hits} matches in %.1f ms".format(o.findMs))
                info("PERF ${f.name}: java after closing %+.1f MB against before opening".format(after))
                // A heap dump for finding what holds a closed file, when asked for with a flag file in LX's cache.
                val flag = File(ctx.cacheDir, "tedit-dumpheap")
                if (after > 4 && flag.exists()) {
                    flag.delete()
                    Debug.dumpHprofData(File(ctx.cacheDir, "tedit-leak.hprof").absolutePath)
                    info("INFO heap dumped after closing ${f.name}")
                }
                when {
                    f.name == "huge.log" && !o.readOnly -> "a file over the limit opened writable"
                    o.fast.farOver > o.fast.n / 4 -> "fling: ${o.fast.farOver} of ${o.fast.n} frames took over 3x their budget"
                    o.slow.farOver > o.slow.n / 4 -> "slow scroll: ${o.slow.farOver} of ${o.slow.n} frames took over 3x their budget"
                    after > 4 -> "%.1f MB still held after closing".format(after)
                    else -> null
                }
            }
        }
    }

    /**
     * For looking at Tedit on the device: a small project open with the Files panel, then the editor alone, then the
     * terminal, each held for screenshots (INFO lines say when each one is on screen).
     */
    private fun show(a: LauncherActivity) {
        check("show run") {
            val dir = File(scratch, "demo").apply { mkdirs() }
            File(dir, "src").mkdirs()
            File(dir, "src/App.kt").writeText(codeSample + "\n")
            File(dir, "package.json").writeText("{\n  \"name\": \"demo\",\n  \"version\": \"1.0.0\",\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n")
            File(dir, "README.md").writeText("# Demo\n\nA small project for looking at Tedit.\n")
            File(dir, "index.html").writeText("<!doctype html>\n<html>\n  <body>\n    <h1 class=\"title\">Demo</h1>\n  </body>\n</html>\n")
            val (tool, _) = openInTedit(a, File(dir, "src/App.kt")) ?: return@check "did not open"
            openInTedit(a, File(dir, "package.json"))
            openInTedit(a, File(dir, "README.md"))
            openInTedit(a, File(dir, "src/App.kt"))
            val node = FileServices.storage(ctx).localNode(dir.absolutePath) ?: return@check "no node"
            inst.runOnMainSync { tool.openFolderNode(node) }
            inst.waitForIdleSync()
            if ("themes" in only) {
                for (theme in listOf("light", "midnight", "oled", "dark")) {
                    inst.runOnMainSync { tool.prefs.set(TeditPrefs.THEME, theme) }
                    inst.waitForIdleSync()
                    SystemClock.sleep(1200)
                    info("INFO show: theme $theme")
                    SystemClock.sleep(7000)
                }
            }
            info("INFO show: files panel")
            SystemClock.sleep(9000)
            inst.runOnMainSync { tool.closePanel() }
            info("INFO show: editor")
            SystemClock.sleep(9000)
            inst.runOnMainSync { tool.showTerminal() }
            SystemClock.sleep(1500)
            inst.runOnMainSync { tool.terminal.current?.send("ls -la; echo selesai\r") }
            info("INFO show: terminal")
            SystemClock.sleep(9000)
            inst.runOnMainSync { tool.toggleTerminal(); tool.closeWorkspace() }
            null
        }
    }

    /**
     * Every command Tedit offers, picked at random 400 times with typing and Back in between, in a small project.
     * Left out: the clipboard (it belongs to the phone's owner), saving and opening through Android's pickers, and
     * running shell tasks. A command that throws, or anything that ends LX, fails the run.
     */
    private fun commandFuzz(a: LauncherActivity) {
        check("400 random commands with typing and Back leave Tedit working") {
            // A fresh window: one that closed the large files earlier would bring them back on Reopen Closed Tab.
            closeTedit(a)
            SystemClock.sleep(300)
            val dir = File(scratch, "cmd").apply { mkdirs() }
            File(dir, "a.kt").writeText(codeSample + "\n")
            File(dir, "b.json").writeText("{\n  \"a\": [1, 2, 3]\n}\n")
            File(dir, "c.md").writeText("# Title\n\ntext\n")
            val (tool, _) = openInTedit(a, File(dir, "a.kt")) ?: return@check "did not open"
            openInTedit(a, File(dir, "b.json"))
            val node = FileServices.storage(ctx).localNode(dir.absolutePath) ?: return@check "no node"
            inst.runOnMainSync { tool.openFolderNode(node) }
            val skip = setOf("edit.cut", "edit.copy", "edit.paste", "file.save", "file.saveAs", "file.saveAll", "file.open",
                "file.openFolder", "run.task", "run.file", "run.stop", "terminal.new")
            val r = Random(17)
            val ran = HashMap<String, Int>()
            var failure: String? = null
            for (i in 0 until 400) {
                inst.runOnMainSync {
                    try {
                        val ids = tool.commands.all.filter { it.id !in skip && it.unavailable() == 0 }.map { it.id }
                        if (ids.isNotEmpty()) {
                            val id = ids[r.nextInt(ids.size)]
                            ran[id] = (ran[id] ?: 0) + 1
                            tool.commands.run(id)
                        }
                        when (r.nextInt(4)) {
                            0 -> tool.onBack()
                            1 -> tool.activeGroup?.editor?.let { e -> if (e.doc?.readOnly == false) "x = 1\n".forEach { c -> if (c == '\n') e.newline() else e.typeText(c.toString()) } }
                        }
                    } catch (t: Throwable) {
                        if (failure == null) failure = "step $i: ${t.javaClass.simpleName}: ${t.message}"
                    }
                }
                SystemClock.sleep(40)
                if (failure != null) break
            }
            // Whatever is still open is closed the way a person would, with Back.
            repeat(6) { inst.runOnMainSync { tool.onBack() } }
            inst.waitForIdleSync()
            info("PERF command fuzz: ${ran.size} commands, ${ran.values.sum()} runs")
            inst.runOnMainSync { tool.closeWorkspace() }
            failure
        }
    }

    /**
     * A real language server in Debian (pylsp, installed with apt when missing): diagnostics on a Python file, the
     * completion list for `os.`, info, go to definition, and the suggestion list while typing.
     */
    private fun lspReal(a: LauncherActivity) {
        val rt = com.lxlauncher.tedit.linux.LinuxRuntime.get(ctx)
        if (!rt.ready) { info("INFO Debian is not set up; language server checks skipped"); return }
        check("pylsp is installed in Debian") {
            val started = SystemClock.elapsedRealtime()
            val p = ProcessBuilder(rt.command(listOf("/bin/bash", "-c", "dpkg -s python3-pyflakes >/dev/null 2>&1 || (apt-get update -q && apt-get install -y -q python3-pylsp python3-pyflakes python3-pycodestyle python3-autopep8) >/tmp/tedit-pylsp.log 2>&1; command -v pylsp"), null))
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            info("PERF pylsp ready in ${SystemClock.elapsedRealtime() - started} ms")
            if (!out.contains("/pylsp")) "pylsp missing: ${out.takeLast(300)}" else null
        }
        check("pylsp reports, completes, explains and finds definitions in a Tedit window") {
            val dir = File(scratch, "py").apply { mkdirs() }
            val f = File(dir, "demo.py").apply { writeText("import os\n\n\ndef greet(name):\n    return 'hi ' + name\n\n\nprint(greet('x'), undefined_name)\nos.getcwd()\n") }
            val (tool, d) = openInTedit(a, f) ?: return@check "did not open"
            val started = SystemClock.uptimeMillis()
            val diagMs = waitFor(90_000) { d.diagnostics.any { it.message.contains("undefined name", true) } }
            info("PERF first diagnostics after ${diagMs} ms: ${d.diagnostics.map { it.message }.take(4)}")
            if (diagMs < 0) return@check "no diagnostics after ${SystemClock.uptimeMillis() - started} ms: ${d.diagnostics.map { it.message }}"
            var labels: List<String>? = null
            inst.runOnMainSync { tool.lsp.completion(d, com.lxlauncher.tedit.Pos(8, 3), ".") { items -> labels = items.map { it.label } } }
            waitFor(20_000) { labels != null }
            info("INFO completion for os.: ${labels?.size} items, e.g. ${labels?.take(5)}")
            if (labels?.any { it.startsWith("path") } != true) return@check "no os.path in ${labels?.take(10)}"
            var hover: String? = null
            var hoverDone = false
            inst.runOnMainSync { tool.lsp.hover(d, com.lxlauncher.tedit.Pos(7, 8)) { h -> hover = h; hoverDone = true } }
            waitFor(20_000) { hoverDone }
            var defs: List<com.lxlauncher.tedit.lsp.Location>? = null
            inst.runOnMainSync { tool.lsp.definition(d, com.lxlauncher.tedit.Pos(7, 8)) { l -> defs = l } }
            waitFor(20_000) { defs != null }
            info("INFO hover: ${hover?.take(80)}; definition: ${defs?.map { it.range.start.line }}")
            if (defs?.firstOrNull()?.range?.start?.line != 3) return@check "definition of greet: ${defs?.map { it.uri + ":" + it.range.start.line }}"
            // The list while typing: "os.pa" offers path.
            var ed: CodeEditView? = null
            inst.runOnMainSync { ed = editorFor(a.window.decorView, d); ed?.requestFocus(); ed?.select(com.lxlauncher.tedit.Pos(8, 3), com.lxlauncher.tedit.Pos(8, 3), center = false) }
            val e = ed ?: return@check "no editor"
            inst.runOnMainSync { e.typeText("p"); e.typeText("a") }
            val shown = waitFor(20_000) { tool.completion.showing }
            info("INFO suggestion list shown after typing: ${shown} ms")
            inst.runOnMainSync { tool.completion.hide() }
            if (shown < 0) "no suggestion list while typing" else null
        }
    }

    private fun runReal(a: LauncherActivity) {
        val rt = com.lxlauncher.tedit.linux.LinuxRuntime.get(ctx)
        if (!rt.ready) return
        check("Run executes a Python file in Debian, and a missing interpreter is named with its package") {
            val dir = File(scratch, "run").apply { mkdirs() }
            val py = File(dir, "hello.py").apply { writeText("import sys\nprint('run ok', sys.version_info.major)\n") }
            val js = File(dir, "hello.js").apply { writeText("console.log('js')\n") }
            val (tool, d) = openInTedit(a, py) ?: return@check "did not open"
            inst.runOnMainSync { tool.runner.runFile(d) }
            if (waitFor(60_000) { tool.runner.running == null && tool.runner.lastExit != null } < 0) return@check "python run did not finish"
            var out = ""
            var exit: Int? = null
            inst.runOnMainSync { out = tool.runner.output.toString(); exit = tool.runner.lastExit }
            if (exit != 0 || !out.contains("run ok 3")) return@check "python: exit $exit, ${out.takeLast(200)}"
            val (_, dj) = openInTedit(a, js) ?: return@check "js did not open"
            inst.runOnMainSync { tool.runner.runFile(dj) }
            if (waitFor(60_000) { tool.runner.running == null && tool.runner.lastExit != null && tool.runner.output.contains("hello.js") } < 0) return@check "js run did not finish"
            inst.runOnMainSync { out = tool.runner.output.toString(); exit = tool.runner.lastExit }
            info("INFO missing node: exit $exit, ${out.lines().filter { it.contains("not found") }.take(1)}")
            if (exit != 127 && !out.contains("js\n")) "node: exit $exit, ${out.takeLast(200)}" else null
        }
    }

    /** For screenshots: diagnostics, suggestions, the marketplace, an extension theme, the Linux page, a Debian terminal. */
    private fun showExt(a: LauncherActivity) {
        check("showext run") {
            fun hold(name: String) { info("INFO show: $name"); SystemClock.sleep(7000) }
            val dir = File(scratch, "pyshow").apply { mkdirs() }
            val f = File(dir, "app.py").apply { writeText("import os\n\n\ndef greet(name):\n    return 'hi ' + name\n\n\nprint(greet('x'), undefined_name)\nos.getcwd()\n") }
            val (tool, d) = openInTedit(a, f) ?: return@check "did not open"
            val node = FileServices.storage(ctx).localNode(dir.absolutePath)
            if (node != null) inst.runOnMainSync { tool.openFolderNode(node); tool.closePanel() }
            waitFor(60_000) { d.diagnostics.isNotEmpty() }
            hold("diagnostics")
            var ed: CodeEditView? = null
            inst.runOnMainSync { ed = editorFor(a.window.decorView, d); ed?.requestFocus(); ed?.select(com.lxlauncher.tedit.Pos(8, 3), com.lxlauncher.tedit.Pos(8, 3), center = false) }
            inst.runOnMainSync { ed?.typeText("p"); ed?.typeText("a") }
            waitFor(20_000) { tool.completion.showing }
            hold("completion")
            inst.runOnMainSync { tool.completion.hide(); ed?.undo(); ed?.undo() }
            val prefs = ctx.getSharedPreferences("tedit", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString(TeditPrefs.MARKETPLACE, "ask").commit()
            inst.runOnMainSync { tool.showPanel("extensions") }
            hold("ext-consent")
            inst.runOnMainSync { tool.prefs.set(TeditPrefs.MARKETPLACE, "on"); tool.closePanel() }
            inst.runOnMainSync { tool.showPanel("extensions") }
            SystemClock.sleep(4000)
            hold("ext-market")
            val api = com.lxlauncher.tedit.ext.OpenVsx(ctx)
            val item = runCatching { api.detail("dracula-theme", "theme-dracula").item }.getOrNull()
            if (item != null) {
                inst.runOnMainSync { com.lxlauncher.tedit.ExtensionPage(tool, item, com.lxlauncher.tedit.ext.ExtensionStore.get(ctx).get(item.id)).show() }
                SystemClock.sleep(3000)
                hold("ext-detail")
                inst.runOnMainSync { tool.onBack() }
                val store = com.lxlauncher.tedit.ext.ExtensionStore.get(ctx)
                if (store.get(item.id) == null) {
                    var done = false
                    inst.runOnMainSync { com.lxlauncher.tedit.Marketplace.install(tool.ctx, item) { done = true } }
                    waitFor(60_000) { done }
                    inst.runOnMainSync { tool.onBack() }
                }
                store.get(item.id)?.let { e -> inst.runOnMainSync { tool.prefs.setExtensionTheme(store.themeKey(e, e.themes.first())); tool.closePanel() } }
                SystemClock.sleep(1500)
                hold("theme-dracula")
                inst.runOnMainSync { tool.prefs.setExtensionTheme(null) }
            }
            inst.runOnMainSync { com.lxlauncher.tedit.LinuxPage(tool).show() }
            hold("linux")
            inst.runOnMainSync { tool.onBack() }
            var tool2: TeditTool? = null
            inst.runOnMainSync { tool2 = toolOf(a) }
            inst.runOnMainSync { tool2?.showTerminal() }
            SystemClock.sleep(2500)
            inst.runOnMainSync { tool.terminal.current?.send("cat /etc/os-release | head -1; python3 --version; uname -m\r") }
            SystemClock.sleep(2500)
            hold("terminal-debian")
            inst.runOnMainSync { tool.toggleTerminal(); tool.closeWorkspace() }
            null
        }
    }

    /** Long runs for a profiler to sample: fling through three files, type, and switch tabs. */
    private fun profile(a: LauncherActivity, code: File, wide: File, minified: File) {
        check("profile run") {
            for (f in listOf(wide, minified, code)) {
                val (tool, d) = openInTedit(a, f) ?: return@check "${f.name} did not open"
                var ed: CodeEditView? = null
                inst.runOnMainSync { ed = editorFor(a.window.decorView, d) }
                val e = ed ?: return@check "no editor"
                val stats = scrollRun(a, e, 600, e.height / 3f)
                info("PERF profile ${f.name} fling: $stats")
                closeDoc(tool, d)
            }
            null
        }
    }

    private fun typing(a: LauncherActivity, code: File) {
        check("600 keystrokes in the middle of a 30,000 line file keep frames on time") {
            val (tool, d) = openInTedit(a, code) ?: return@check "did not open"
            var ed: CodeEditView? = null
            inst.runOnMainSync {
                ed = editorFor(a.window.decorView, d)
                ed?.requestFocus()
                ed?.select(Pos(15_000, 0), Pos(15_000, 0), center = true)
            }
            val e = ed ?: return@check "no editor"
            val text = "val total = items.sumOf { it.price * (1 + tax) } // keep\n"
            val fm = Frames(a.window)
            val cost = perFrame(600) { i -> val c = text[i % text.length]; if (c == '\n') e.newline() else e.typeText(c.toString()) }
            val stats = fm.stop()
            info("PERF typing: $stats, main thread %.2f ms per key".format(cost / 600))
            var dirty = false
            var undone = false
            inst.runOnMainSync {
                dirty = d.dirty
                val ms = System.nanoTime()
                while (d.history.canUndo) e.undo()
                info("PERF undo of 600 keys: %.1f ms".format((System.nanoTime() - ms) / 1e6))
                undone = !d.dirty
            }
            closeDoc(tool, d)
            when {
                !dirty -> "typing left the document clean"
                !undone -> "undo did not return to the saved text"
                stats.farOver > stats.n / 10 -> "${stats.farOver} of ${stats.n} frames over 3x their deadline"
                else -> null
            }
        }
    }

    private fun tabs(a: LauncherActivity) {
        check("40 tabs open, switch and close") {
            val dir = File(scratch, "tabs").apply { mkdirs() }
            val files = (1..40).map { i -> File(dir, "file$i.kt").apply { writeText(codeSample.repeat(20)) } }
            val before = javaMb()
            val start = SystemClock.uptimeMillis()
            var lastTool: TeditTool? = null
            for (f in files) lastTool = (openInTedit(a, f) ?: return@check "${f.name} did not open").first
            val took = SystemClock.uptimeMillis() - start
            val tool = lastTool!!
            val held = javaMb() - before
            val fm = Frames(a.window)
            perFrame(80) { tool.nextTab(1) }
            val stats = fm.stop()
            var count = 0
            inst.runOnMainSync { count = tool.groups.sumOf { it.tabs.size } }
            info("PERF 40 tabs: opened in $took ms, hold %.1f MB java; switching: $stats".format(held))
            inst.runOnMainSync {
                for (g in tool.groups.toList()) tool.closeTabs(g, g.tabs.toList())
            }
            waitFor(5000) { tool.groups.sumOf { it.tabs.size } == 0 }
            val after = javaMb() - before
            info("PERF 40 tabs: java after closing all %+.1f MB".format(after))
            when {
                count < 40 -> "only $count tabs"
                after > 6 -> "%.1f MB still held after closing every tab".format(after)
                else -> null
            }
        }
    }

    private fun search(a: LauncherActivity) {
        check("searching 3,000 files finds every match and keeps frames going") {
            val ws = File(scratch, "ws").apply { mkdirs() }
            var needles = 0
            for (d in 0 until 40) {
                val sub = File(ws, "pkg$d").apply { mkdirs() }
                for (k in 0 until 75) {
                    val needle = (d * 75 + k) % 1000 == 0
                    if (needle) needles++
                    File(sub, "F$k.kt").writeText(codeSample + if (needle) "\n// needleZed here\n" else "\n")
                }
            }
            val node = FileServices.storage(ctx).localNode(ws.absolutePath) ?: return@check "no node for the folder"
            val opened = openInTedit(a, File(ws, "pkg0/F1.kt")) ?: return@check "Tedit did not open"
            val tool = opened.first
            inst.runOnMainSync { tool.openFolderNode(node); tool.showPanel("search") }
            inst.waitForIdleSync()
            var panel: SearchPanel? = null
            inst.runOnMainSync { panel = tool.panel("search") as? SearchPanel }
            val sp = panel ?: return@check "no search panel"
            val field = SearchPanel::class.java.getDeclaredField("field").apply { isAccessible = true }.get(sp) as EditText
            val startFn = SearchPanel::class.java.getDeclaredMethod("start").apply { isAccessible = true }
            val searching = SearchPanel::class.java.getDeclaredField("searching").apply { isAccessible = true }
            val hitsField = SearchPanel::class.java.getDeclaredField("hits").apply { isAccessible = true }
            fun runSearch(text: String): Triple<Long, Int, FrameStats> {
                val fm = Frames(a.window)
                val s = SystemClock.uptimeMillis()
                inst.runOnMainSync { field.setText(text); startFn.invoke(sp) }
                waitFor(120_000) { searching.getBoolean(sp) == false }
                val took = SystemClock.uptimeMillis() - s
                var n = 0
                inst.runOnMainSync { n = (hitsField.get(sp) as List<*>).size }
                return Triple(took, n, fm.stop())
            }
            val rare = runSearch("needleZed")
            info("PERF search rare word: ${rare.second} hits in ${rare.first} ms; ${rare.third}")
            val common = runSearch("fun")
            info("PERF search common word: ${common.second} hits in ${common.first} ms; ${common.third}")
            inst.runOnMainSync { field.setText(""); startFn.invoke(sp); tool.closeWorkspace() }
            closeDoc(tool, opened.second)
            when {
                rare.second != needles -> "found ${rare.second} of $needles needles"
                common.second == 0 -> "no hits for a common word"
                common.third.farOver > maxOf(3, common.third.n / 10) -> "common word: ${common.third.farOver} of ${common.third.n} frames over 3x their deadline"
                else -> null
            }
        }
    }

    private fun terminal(a: LauncherActivity) {
        check("100,000 lines of terminal output arrive without freezing the window") {
            var tool: TeditTool? = null
            inst.runOnMainSync { tool = toolOf(a) }
            // Run on its own, the part opens a window first.
            if (tool == null) tool = openInTedit(a, File(scratch, "term.sh").apply { writeText("echo hi\n") })?.first
            val t = tool ?: return@check "no Tedit window"
            inst.runOnMainSync { t.showTerminal() }
            inst.waitForIdleSync()
            waitFor(60_000) { (t.terminal.current?.prompts ?: 0) > 0 }
            val s = t.terminal.current ?: return@check "no terminal session"
            val fm = Frames(a.window)
            val start = SystemClock.uptimeMillis()
            val prompts = s.prompts
            inst.runOnMainSync { s.send("seq 1 100000; echo DONE\r") }
            val took = waitFor(120_000) { s.prompts > prompts }
            val stats = fm.stop()
            var text = ""
            inst.runOnMainSync { text = s.allText(t.terminal.terminal.gridRows(), t.terminal.terminal.gridCols()) }
            val kept = text.lines().size
            val scrollback = t.prefs.int(com.lxlauncher.tedit.TeditPrefs.TERM_SCROLLBACK)
            info("PERF terminal 100,000 lines: ${if (took < 0) "not finished" else "$took ms"}, keeps $kept lines; $stats")
            inst.runOnMainSync { t.toggleTerminal() }
            when {
                took < 0 -> "the output did not finish in 2 minutes (${SystemClock.uptimeMillis() - start} ms)"
                !text.lines().any { it.trim() == "DONE" } -> "the end of the output is missing"
                kept > scrollback + 200 -> "kept $kept lines past the scrollback of $scrollback"
                stats.farOver > maxOf(3, stats.n / 4) -> "${stats.farOver} of ${stats.n} frames over 3x their deadline"
                else -> null
            }
        }
    }

    private fun workspaceReopen(a: LauncherActivity) {
        check("a folder open in Tedit survives theme changes and the window reopening with it") {
            val dir = File(scratch, "wsre").apply { mkdirs() }
            File(dir, "x.kt").writeText("fun x() {}\n")
            val (tool, _) = openInTedit(a, File(dir, "x.kt")) ?: return@check "did not open"
            val node = FileServices.storage(ctx).localNode(dir.absolutePath) ?: return@check "no node"
            inst.runOnMainSync { tool.openFolderNode(node) }
            for (theme in listOf("light", "dark")) {
                inst.runOnMainSync { tool.prefs.set(TeditPrefs.THEME, theme) }
                inst.waitForIdleSync()
                SystemClock.sleep(300)
            }
            closeTedit(a)
            SystemClock.sleep(600)
            // The new window restores the session, folder included, and builds the Files panel with it.
            val again = openInTedit(a, File(dir, "x.kt")) ?: return@check "did not reopen"
            var ws: String? = null
            inst.runOnMainSync { again.first.showPanel("explorer"); ws = again.first.workspace?.name }
            inst.waitForIdleSync()
            inst.runOnMainSync { again.first.closeWorkspace() }
            if (ws != "wsre") "folder after reopening: $ws" else null
        }
    }

    private fun openWeak(a: LauncherActivity, f: File): WeakReference<TeditTool>? = openInTedit(a, f)?.let { WeakReference(it.first) }

    private fun cycles(a: LauncherActivity, code: File) {
        check("opening and closing a Tedit window 12 times leaks neither memory nor threads") {
            closeTedit(a)
            val threads0 = teditThreads()
            val before = javaMb()
            val refs = ArrayList<WeakReference<TeditTool>>()
            val times = ArrayList<Long>()
            for (round in 0 until 12) {
                val s = SystemClock.uptimeMillis()
                // Only a weak reference comes back, so this frame does not keep the window it measured alive.
                refs += openWeak(a, code) ?: return@check "round $round: did not open"
                times += SystemClock.uptimeMillis() - s
                closeTedit(a)
                SystemClock.sleep(200)
            }
            gc()
            SystemClock.sleep(500)
            gc()
            val alive = refs.count { it.get() != null }
            val flag = File(ctx.cacheDir, "tedit-dumpheap")
            if (alive > 0 && flag.exists()) {
                flag.delete()
                Debug.dumpHprofData(File(ctx.cacheDir, "tedit-leak.hprof").absolutePath)
                info("INFO heap dumped with $alive closed Tedit windows alive")
            }
            val threads = teditThreads() - threads0
            val after = javaMb() - before
            info("PERF 12 open/close rounds: open ${times.joinToString()} ms; tools alive $alive, tedit threads left $threads, java %+.1f MB".format(after))
            info("PERF after the load run: ${memory()}")
            when {
                alive > 1 -> "$alive closed Tedit windows are still in memory"
                threads > 1 -> "$threads tedit threads outlive their windows"
                after > 10 -> "%.1f MB more java after 12 rounds".format(after)
                else -> null
            }
        }
    }
}

/**
 * Runs [block], then puts Tedit's settings, session and hot-exit backups back as they were, so a check that opens a
 * Tedit window leaves no tab or recent file behind on a device someone uses.
 */
internal fun keepTeditState(ctx: Context, block: () -> Unit) {
    val prefs = ctx.getSharedPreferences("tedit", Context.MODE_PRIVATE)
    val before = HashMap(prefs.all)
    val backups = File(ctx.filesDir, "tedit/backup")
    val backupsBefore = backups.list()?.toSet().orEmpty()
    val hadTeditDir = File(ctx.filesDir, "tedit").exists()
    try {
        block()
    } finally {
        // A closing window writes its session and hot-exit backups a moment later, on its own thread.
        SystemClock.sleep(800)
        val e = prefs.edit().clear()
        for ((k, v) in before) when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
            is Float -> e.putFloat(k, v)
            is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
        }
        e.commit()
        backups.listFiles()?.filter { it.name !in backupsBefore }?.forEach { it.delete() }
        if (!hadTeditDir) File(ctx.filesDir, "tedit").deleteRecursively()
    }
}
