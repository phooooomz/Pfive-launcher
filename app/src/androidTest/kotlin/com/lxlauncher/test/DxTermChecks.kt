// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.lxlauncher.LauncherActivity
import com.lxlauncher.core.NativeCore
import com.lxlauncher.tedit.DxShell
import com.lxlauncher.tedit.TaskDef
import com.lxlauncher.tedit.TeditPrefs
import com.lxlauncher.tedit.TeditTool
import com.lxlauncher.tedit.TermSession
import java.io.File

/**
 * A dracxterm shell on a pseudo terminal, driven the way a person drives it: type a line, wait for the prompt to come
 * back, read the screen. The shell's prompt report (see DxShell) says when a command ended and how.
 */
internal class ShellProbe(ctx: Context, dir: File?, androidShell: Boolean, val cols: Int = 80, val rows: Int = 24) {
    val session = TermSession("probe", false)
    val spec = DxShell.prepare(ctx, dir, androidShell)
    val started = session.startWith(spec, cols, rows)
    val kind get() = spec.kind

    /** The next prompt report within [ms], or null. */
    fun waitPrompt(ms: Long): DxShell.Prompt? {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            session.takeReport()?.let { return it }
            if (!session.native.running()) return null
            SystemClock.sleep(20)
        }
        return null
    }

    /** Types [line] and Enter, then waits for the prompt that follows it. */
    fun run(line: String, ms: Long = 15_000): DxShell.Prompt? {
        session.takeReport()
        session.send(line + "\r")
        return waitPrompt(ms)
    }

    /** Everything in the session, read at the size it has now (a check may have resized it). */
    fun text(): String = session.allText(session.native.rows, session.native.cols)
    fun close() = session.close()

    /** The shell's own process id, as it reports it (Debian's processes keep their host ids under VHDP). */
    fun shellPid(): Int? {
        run("echo PID:\$\$") ?: return null
        return text().lines().lastOrNull { it.trim().startsWith("PID:") }?.trim()?.removePrefix("PID:")?.toIntOrNull()
    }

    companion object {
        /** Processes LX started itself that are still there (each shell is a child of LX's process): "pid name state". */
        fun childList(): List<String> {
            val me = android.os.Process.myPid().toString()
            return File("/proc").listFiles { f -> f.name.all { it.isDigit() } }.orEmpty().mapNotNull { d ->
                runCatching {
                    val stat = File(d, "stat").readText()
                    val rest = stat.substringAfterLast(')').trim().split(' ')
                    if (rest[1] != me) return@runCatching null
                    val cmd = runCatching { File(d, "cmdline").readText().replace('\u0000', ' ').trim().take(120) }.getOrDefault("")
                    "${d.name} ${stat.substringAfter('(').substringBeforeLast(')')} ${rest[0]} [$cmd]"
                }.getOrNull()
            }
        }

        fun children(): Int = childList().size

        /** Waits up to [ms] for process [pid] to be gone; true when it is. */
        fun gone(pid: Int, ms: Long = 1500): Boolean {
            val end = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < end) {
                if (!File("/proc/$pid").exists()) return true
                SystemClock.sleep(20)
            }
            return !File("/proc/$pid").exists()
        }
    }
}

/**
 * Tedit's terminal as dracxterm's (13 November 2026), run with `-e only dxterm`: the shell on its own, then inside a
 * Tedit window with Run, input to a running program, Stop, sessions, and repeated runs. Uses Debian when it is set up;
 * otherwise the shell the phone allows (BusyBox, or Android's sh where the phone refuses BusyBox).
 */
internal class DxTermChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit,
                             private val teditOnly: Boolean = false) {
    private val ctx = inst.targetContext
    private val scratch = File(ctx.filesDir, "dxterm-check-${System.nanoTime()}").apply { mkdirs() }

    fun run() {
        try {
            shrinkProbe()
            if (!teditOnly) {
                shell(androidShell = false)
                shell(androidShell = true)
                cycles()
            }
            tedit()
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun shell(androidShell: Boolean) {
        val label = if (androidShell) "Android's sh" else "the default shell"
        var probe: ShellProbe? = null
        check("$label starts on a pseudo terminal and shows its prompt") {
            val started = SystemClock.uptimeMillis()
            val p = ShellProbe(ctx, scratch, androidShell)
            probe = p
            if (!p.started) return@check "did not start (${p.kind})"
            val first = p.waitPrompt(if (p.kind == DxShell.Kind.DEBIAN) 60_000 else 15_000)
            info("PERF $label (${p.kind}) first prompt in ${SystemClock.uptimeMillis() - started} ms")
            when {
                first == null -> "no prompt report: ${p.text().takeLast(300)}"
                androidShell && p.kind != DxShell.Kind.ANDROID -> "kind ${p.kind}"
                else -> null
            }
        }
        val p = probe ?: return
        var pid: Int? = null
        try {
            check("$label: a command runs, its output shows and its exit status comes back") {
                val r = p.run("echo dx-\$((6*7)); false") ?: return@check "no prompt after the command"
                val text = p.text()
                when {
                    r.status != 1 -> "status ${r.status}, wanted 1"
                    !text.lines().any { it.trim() == "dx-42" } -> "output missing: ${text.takeLast(300)}"
                    else -> null
                }
            }
            check("$label: exit statuses 0 and 7 come back as they are") {
                val a = p.run("true")?.status
                val b = p.run("(exit 7)")?.status
                if (a != 0 || b != 7) "statuses $a and $b" else null
            }
            check("$label: the program sees a terminal of the session's size, and a resize reaches it") {
                val r1 = p.run("stty size; tty") ?: return@check "no prompt"
                val t1 = p.text()
                p.session.native.resize(100, 30)
                SystemClock.sleep(200)
                p.run("stty size") ?: return@check "no prompt after resize"
                val t2 = p.text()
                when {
                    r1.status != 0 -> "stty failed: ${t1.takeLast(200)}"
                    !t1.contains("24 80") -> "size before: ${t1.takeLast(200)}"
                    !t1.contains("/dev/pts/") -> "not a pseudo terminal: ${t1.takeLast(200)}"
                    !t2.contains("30 100") -> "size after resize: ${t2.takeLast(200)}"
                    else -> null
                }
            }
            check("$label: Ctrl+C stops the running command and the rest of its line") {
                p.session.takeReport()
                p.session.send("sleep 30; echo slept\r")
                SystemClock.sleep(1500)
                val before = SystemClock.uptimeMillis()
                p.session.interrupt()
                val r = p.waitPrompt(8000) ?: return@check "no prompt after Ctrl+C"
                info("PERF $label Ctrl+C answered in ${SystemClock.uptimeMillis() - before} ms, status ${r.status}")
                when {
                    r.status != 130 -> "status ${r.status}, wanted 130"
                    p.text().lines().any { it.trim() == "slept" } -> "the rest of the line ran"
                    else -> null
                }
            }
            if (p.kind == DxShell.Kind.DEBIAN) debianOnly(p)
        } finally {
            pid = p.shellPid()
            p.close()
        }
        check("$label: closing the session ends its shell") {
            val shell = pid ?: return@check "the shell did not say its process id"
            if (!ShellProbe.gone(shell)) "shell $shell still there after close; LX's child processes: ${ShellProbe.childList()}" else null
        }
    }

    /** The prompt after the terminal shrinks, without Tedit around it: by columns, by rows, and both. */
    private fun shrinkProbe() {
        for ((label, size) in listOf("columns" to (37 to 24), "rows" to (87 to 4), "both" to (37 to 4))) {
            check("the prompt stays on screen when the terminal shrinks in $label") {
                val p = ShellProbe(ctx, scratch, false, cols = 87, rows = 40)
                try {
                    if (p.waitPrompt(15_000) == null) return@check "no prompt"
                    p.session.native.resize(size.first, size.second)
                    // What Tedit's panel does after a narrower terminal (see TermSession.redrawAfterNarrowing).
                    if (size.first < 87) { p.session.atPrompt = true; p.session.redrawAfterNarrowing() }
                    SystemClock.sleep(400)
                    val again = p.waitPrompt(1500)
                    SystemClock.sleep(300)
                    val screen = visible(p.session, size.first, size.second)
                    info("INFO shrink $label: prompt report after resize ${again?.status}; screen '${screen.replace("\n", "|")}'")
                    if (!screen.contains("$")) "no prompt: '${screen.replace("\n", "|")}'" else null
                } finally {
                    p.close()
                }
            }
        }
        // Android's sh from the panel's own sizes (46 x 11 to 31 x 3), with and without a short row count before.
        for ((label, steps) in listOf("46x11 to 31x3" to listOf(31 to 3), "46x11 to 31x11" to listOf(31 to 11), "46x11 to 46x3 to 31x3" to listOf(46 to 3, 31 to 3))) {
            check("Android's sh keeps its prompt from $label") {
                val p = ShellProbe(ctx, File("/storage/emulated/0").takeIf { it.canRead() } ?: scratch, true, cols = 46, rows = 11)
                try {
                    if (p.waitPrompt(15_000) == null) return@check "no prompt"
                    p.session.atPrompt = true
                    var last = 46
                    for ((c, r) in steps) {
                        p.session.native.resize(c, r)
                        if (c < last) p.session.redrawAfterNarrowing()
                        last = c
                        SystemClock.sleep(200)
                    }
                    SystemClock.sleep(600)
                    val (c, r) = steps.last()
                    val screen = visible(p.session, c, r)
                    info("INFO mksh $label: screen '${screen.replace("\n", "|")}'")
                    if (!screen.contains("$")) "no prompt: '${screen.replace("\n", "|")}'" else null
                } finally {
                    p.close()
                }
            }
        }
    }

    /** What only dracxterm's Debian setup has: its user and prompt, sudo, and the report of a missing command. */
    private fun debianOnly(p: ShellProbe) {
        check("Debian: dracxterm's user and prompt, and sudo for root") {
            val r = p.run("whoami; sudo whoami; echo \$HOME") ?: return@check "no prompt"
            val text = p.text()
            when {
                r.status != 0 -> "status ${r.status}: ${text.takeLast(300)}"
                !text.lines().any { it.trim() == "dracos" } -> "user: ${text.takeLast(300)}"
                !text.lines().any { it.trim() == "root" } -> "sudo: ${text.takeLast(300)}"
                !text.contains("DRAC⚔TERM") -> "prompt: ${text.takeLast(300)}"
                else -> null
            }
        }
        check("Debian: a command it lacks comes back as missing, so Tedit can offer its package") {
            val r = p.run("nosuchcommand-tedit") ?: return@check "no prompt"
            if (r.status != 127 || r.missing != "nosuchcommand-tedit") "status ${r.status}, missing ${r.missing}" else null
        }
        check("Debian: a full-screen program draws and leaves the screen as it was") {
            p.session.takeReport()
            p.session.send("top -b -n 1 | head -3; echo top-done\r")
            val r = p.waitPrompt(20_000)
            if (r == null || !p.text().contains("top-done")) "top did not finish: ${p.text().takeLast(300)}" else null
        }
    }

    /** Sessions opened and closed many times: each one starts, answers, and leaves no descriptor or thread behind. */
    private fun cycles() {
        check("50 sessions start, answer and close without leaking descriptors or threads") {
            fun fds() = File("/proc/self/fd").list()?.size ?: -1
            fun threads() = Thread.getAllStackTraces().keys.count { it.isAlive }
            // One first, so whatever the first start sets up once is not counted.
            ShellProbe(ctx, scratch, false).apply { waitPrompt(60_000); close() }
            val fd0 = fds()
            val th0 = threads()
            val times = ArrayList<Long>()
            for (i in 0 until 50) {
                val t0 = SystemClock.uptimeMillis()
                val p = ShellProbe(ctx, scratch, false)
                var pid: Int? = null
                try {
                    if (p.waitPrompt(60_000) == null) return@check "cycle $i: no prompt"
                    val r = p.run("echo cycle-$i") ?: return@check "cycle $i: no answer"
                    if (r.status != 0 || !p.text().contains("cycle-$i")) return@check "cycle $i: ${p.text().takeLast(200)}"
                    pid = p.shellPid()
                } finally {
                    p.close()
                }
                if (pid == null) return@check "cycle $i: no process id"
                if (!ShellProbe.gone(pid)) return@check "cycle $i: shell $pid still there after close"
                times += SystemClock.uptimeMillis() - t0
            }
            SystemClock.sleep(500)
            info("INFO LX's child processes after 50 sessions: ${ShellProbe.childList()}")
            val fd1 = fds()
            val th1 = threads()
            times.sort()
            info("PERF 50 sessions: median ${times[times.size / 2]} ms, slowest ${times.last()} ms; descriptors $fd0 -> $fd1, threads $th0 -> $th1")
            when {
                fd1 > fd0 + 5 -> "descriptors grew from $fd0 to $fd1"
                th1 > th0 + 3 -> "threads grew from $th0 to $th1"
                else -> null
            }
        }
    }

    /** The text the terminal shows now, rows joined by newlines, read from the engine's snapshot. */
    private fun visible(s: TermSession, cols: Int, rows: Int): String {
        if (cols <= 0 || rows <= 0) return ""
        val n = cols * rows
        val g = IntArray(n); val f = IntArray(n); val b = IntArray(n); val at = IntArray(n)
        val meta = IntArray(com.dracxterm.NativeTerminal.M_COUNT)
        val cursor = s.native.snapshot(g, f, b, at, meta)
        info("INFO engine grid ${meta[com.dracxterm.NativeTerminal.M_COLS]}x${meta[com.dracxterm.NativeTerminal.M_ROWS]}, " +
            "view offset ${meta[com.dracxterm.NativeTerminal.M_VIEW_OFFSET]}, scrollback ${meta[com.dracxterm.NativeTerminal.M_SCROLLBACK]}, cursor $cursor; " +
            "all text '${s.allText(rows, cols).takeLast(200).replace("\n", "|")}'")
        return (0 until rows).joinToString("\n") { r ->
            buildString { for (c in 0 until cols) { val cp = g[r * cols + c]; appendCodePoint(if (cp == 0) ' '.code else cp) } }.trimEnd()
        }
    }

    private fun waitFor(ms: Long, test: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            inst.runOnMainSync { ok = test() }
            if (ok) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun toolOf(a: LauncherActivity): TeditTool? {
        val f = LauncherActivity::class.java.getDeclaredField("windowLayer").apply { isAccessible = true }
        return (f.get(a) as com.lxlauncher.ui.windows.WindowLayer).focusedTool() as? TeditTool
    }

    /** Tedit's terminal panel and Run, in a real Tedit window. */
    private fun tedit() {
        var activity: LauncherActivity? = null
        check("LX starts for the Tedit part") {
            val monitor = inst.addMonitor(LauncherActivity::class.java.name, null, false)
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(ctx, LauncherActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = inst.waitForMonitorWithTimeout(monitor, 10_000) as? LauncherActivity
            inst.removeMonitor(monitor)
            inst.waitForIdleSync()
            SystemClock.sleep(1000)
            if (activity == null) "LauncherActivity did not start" else null
        }
        val a = activity ?: return
        val ask = File(scratch, "ask.sh").apply { writeText("printf 'name? '\nread n\necho \"hi \$n\"\n") }
        var tool: TeditTool? = null
        check("Tedit opens a script for Run") {
            inst.runOnMainSync { a.openTool(TeditTool.KIND, "open\u0000local\u0000${ask.absolutePath}") }
            if (!waitFor(8000) { toolOf(a)?.store?.docs?.values?.any { it.name == "ask.sh" && !it.loading } == true }) return@check "the script did not open"
            inst.runOnMainSync { tool = toolOf(a) }
            if (tool == null) "no Tedit window" else null
        }
        val t = tool ?: return
        try {
            check("the terminal panel opens a dracxterm session that reaches its prompt") {
                inst.runOnMainSync { t.showTerminal() }
                if (!waitFor(60_000) { (t.terminal.current?.prompts ?: 0) > 0 }) return@check "no prompt: ${t.terminal.current?.kind}"
                info("INFO terminal panel session: ${t.terminal.current?.kind}")
                null
            }
            check("the terminal keeps showing its prompt when the Tedit window gets small") {
                val dp = ctx.resources.displayMetrics.density
                inst.runOnMainSync {
                    val id = a.model.snapshot.windows.first { it.kind == TeditTool.KIND }.id
                    @Suppress("UNUSED_VARIABLE") val resized = a.model.edit { m ->
                        NativeCore.setWindowState(m, id, "floating")
                        val b = NativeCore.windowBounds(m, id) ?: return@edit false
                        NativeCore.resizeWindow(m, id, NativeCore.EDGE_RIGHT or NativeCore.EDGE_BOTTOM, 280 * dp - b[2], 240 * dp - b[3])
                        true
                    }
                }
                inst.waitForIdleSync()
                SystemClock.sleep(1200)
                var screen = ""
                var grid = ""
                inst.runOnMainSync {
                    val tv = t.terminal.terminal
                    val s = t.terminal.current
                    grid = "${tv.gridCols()}x${tv.gridRows()} in ${tv.width}x${tv.height} px, session ${s?.kind} started ${s?.started} prompts ${s?.prompts}"
                    if (s != null && s.started) screen = visible(s, tv.gridCols(), tv.gridRows())
                }
                info("INFO small terminal: $grid; screen '${screen.replace("\n", "|")}'")
                if (!screen.contains("$")) "no prompt on screen ($grid): '${screen.replace("\n", "|")}'" else null
            }
            check("Android's sh in the panel shows its prompt again after the window narrows") {
                val dp = ctx.resources.displayMetrics.density
                fun size(w: Int, h: Int) = inst.runOnMainSync {
                    val id = a.model.snapshot.windows.first { it.kind == TeditTool.KIND }.id
                    @Suppress("UNUSED_VARIABLE") val done = a.model.edit { m ->
                        val b = NativeCore.windowBounds(m, id) ?: return@edit false
                        NativeCore.resizeWindow(m, id, NativeCore.EDGE_RIGHT or NativeCore.EDGE_BOTTOM, w * dp - b[2], h * dp - b[3])
                        true
                    }
                }
                val before = t.prefs.string(TeditPrefs.TERM_SHELL)
                try {
                    inst.runOnMainSync { t.prefs.set(TeditPrefs.TERM_SHELL, "android") }
                    size(360, 480)
                    inst.waitForIdleSync()
                    SystemClock.sleep(800)
                    var s: TermSession? = null
                    inst.runOnMainSync { s = t.terminal.newSession() }
                    if (!waitFor(15_000) { (s?.prompts ?: 0) > 0 }) return@check "no prompt from Android's sh"
                    size(240, 200)
                    inst.waitForIdleSync()
                    SystemClock.sleep(1500)
                    var screen = ""
                    var grid = ""
                    inst.runOnMainSync {
                        val tv = t.terminal.terminal
                        grid = "${tv.gridCols()}x${tv.gridRows()} ${s?.kind}"
                        screen = visible(s!!, tv.gridCols(), tv.gridRows())
                    }
                    info("INFO Android's sh after narrowing ($grid): '${screen.replace("\n", "|")}'")
                    if (!screen.contains("$")) "no prompt ($grid): '${screen.replace("\n", "|")}'" else null
                } finally {
                    inst.runOnMainSync { t.prefs.set(TeditPrefs.TERM_SHELL, before) }
                }
            }
            check("Run file types the run into its own session; the program reads what is typed to it") {
                val d = t.store.docs.values.first { it.name == "ask.sh" }
                inst.runOnMainSync { t.runner.runFile(d) }
                if (!waitFor(60_000) { t.runner.session?.let { it.prompts > 0 } == true }) return@check "the run session did not start"
                SystemClock.sleep(1500)
                inst.runOnMainSync { t.runner.session?.send("Tedit\r") }
                if (!waitFor(20_000) { t.runner.running == null }) return@check "the run did not end: ${t.runner.output.takeLast(300)}"
                val out = t.runner.output.toString()
                when {
                    t.runner.lastExit != 0 -> "exit ${t.runner.lastExit}: ${out.takeLast(300)}"
                    !out.lines().any { it.trim() == "hi Tedit" } -> "the answer is missing: ${out.takeLast(300)}"
                    else -> null
                }
            }
            check("a task's exit status and output reach the Run panel") {
                inst.runOnMainSync { t.runner.run(TaskDef("status", "echo run-\$((40+2)); sh -c 'exit 3'", scratch.path)) }
                if (!waitFor(60_000) { t.runner.running == null && t.runner.lastExit != null }) return@check "did not end"
                val out = t.runner.output.toString()
                when {
                    t.runner.lastExit != 3 -> "exit ${t.runner.lastExit}"
                    !out.lines().any { it.trim() == "run-42" } -> "output: ${out.takeLast(300)}"
                    else -> null
                }
            }
            check("Stop ends a running task with Ctrl+C") {
                inst.runOnMainSync { t.runner.run(TaskDef("sleep", "sleep 60", scratch.path)) }
                if (!waitFor(60_000) { t.runner.session?.let { it.prompts > 0 } == true }) return@check "did not start"
                SystemClock.sleep(1500)
                val before = SystemClock.uptimeMillis()
                inst.runOnMainSync { t.runner.stop() }
                if (!waitFor(10_000) { t.runner.running == null }) return@check "still running after Stop"
                info("PERF Stop answered in ${SystemClock.uptimeMillis() - before} ms, exit ${t.runner.lastExit}")
                if (!t.runner.stoppedByUser || t.runner.lastExit != 130) "stopped ${t.runner.stoppedByUser}, exit ${t.runner.lastExit}" else null
            }
            check("50 runs in a row each end with their own output and status") {
                val times = ArrayList<Long>()
                for (i in 0 until 50) {
                    val t0 = SystemClock.uptimeMillis()
                    inst.runOnMainSync { t.runner.run(TaskDef("n$i", "echo n-$i", scratch.path)) }
                    if (!waitFor(60_000) { t.runner.running == null && t.runner.current?.name == "n$i" }) return@check "run $i did not end"
                    if (t.runner.lastExit != 0 || !t.runner.output.lines().any { it.trim() == "n-$i" }) return@check "run $i: exit ${t.runner.lastExit}, ${t.runner.output.takeLast(200)}"
                    times += SystemClock.uptimeMillis() - t0
                }
                var open = 0
                inst.runOnMainSync { open = t.terminal.sessions().size }
                times.sort()
                info("PERF 50 runs: median ${times[times.size / 2]} ms, slowest ${times.last()} ms; sessions open after: $open")
                // A finished run's session goes when the next starts (the setting to keep output is on by default).
                if (t.prefs.bool(TeditPrefs.RUN_CLEAR) && open > 3) "$open sessions left open" else null
            }
            check("sessions survive closing and opening the panel, and closing one leaves the others") {
                var before = 0
                inst.runOnMainSync { t.terminal.newSession(); before = t.terminal.sessions().size }
                if (!waitFor(60_000) { t.terminal.sessions().all { it.started || it.failed } }) return@check "a session did not start"
                inst.runOnMainSync { t.toggleTerminal(); t.toggleTerminal() }
                var after = 0
                var alive = false
                inst.runOnMainSync { after = t.terminal.sessions().size; alive = t.terminal.sessions().all { it.alive } }
                inst.runOnMainSync { t.terminal.current?.let { t.terminal.killSession(it) } }
                var left = 0
                inst.runOnMainSync { left = t.terminal.sessions().size }
                when {
                    after != before || !alive -> "sessions $before -> $after, alive $alive"
                    left != before - 1 -> "closing one left $left of $before"
                    else -> null
                }
            }
        } finally {
            var sessions: List<TermSession> = emptyList()
            inst.runOnMainSync { sessions = t.terminal.sessions() }
            inst.runOnMainSync {
                a.model.snapshot.windows.filter { it.kind == TeditTool.KIND }.forEach { w -> a.model.edit { h -> NativeCore.closeWindow(h, w.id) } }
            }
            inst.waitForIdleSync()
            check("closing the Tedit window ends its shells") {
                SystemClock.sleep(800)
                val kids = ShellProbe.children()
                if (kids > 0) "$kids processes of ${sessions.size} sessions still there: ${ShellProbe.childList()}" else null
            }
        }
    }
}
