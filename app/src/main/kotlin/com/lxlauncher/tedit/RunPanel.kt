// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A task: a shell command run in a folder. Defined in .tedit/tasks.json, or made for the file in the editor. */
data class TaskDef(val name: String, val command: String, val cwd: String?)

/**
 * Runs tasks and the file in the editor in Tedit's terminal, as VS Code runs them in its integrated terminal: each run
 * gets its own dracxterm shell session ("Run: name"), the command is typed at its prompt, and the program can be
 * answered from the keyboard. The shell's prompt report (see [DxShell]) tells when the command ended and its exit
 * status. One task runs at a time; when it ends, the session's text is kept here and the run joins the history.
 */
class TaskRunner(private val tool: TeditTool) {
    private val main = Handler(Looper.getMainLooper())
    /** The run in Debian, where a missing command can be installed. */
    private var ranInDebian = false
    var current: TaskDef? = null
        private set
    var running: String? = null
        private set
    var startedAt = 0L
        private set
    var lastExit: Int? = null
        private set
    var stoppedByUser = false
        private set
    val output = StringBuilder()
    /** The terminal session of the current or last run. */
    var session: TermSession? = null
        private set
    private var commandLine: String? = null
    private var sent = false
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var gen = 0

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() { listeners.toList().forEach { it() }; tool.refreshStatus() }

    private val onPrompt: (TermSession, DxShell.Prompt) -> Unit = { s, p ->
        if (s === session && running != null) {
            if (!sent) {
                // The report comes just before the shell draws its prompt and starts reading the line; a moment later the
                // line editor owns the terminal, so the typed command is not echoed twice.
                sent = true
                val g = gen
                main.postDelayed({ if (g == gen && running != null) s.send(commandLine + "\r") }, TYPE_DELAY_MS)
            } else finish(gen, p.status, null, p.missing)
        }
    }
    private val onExit: (TermSession) -> Unit = { s ->
        if (s === session && running != null) finish(gen, if (stoppedByUser) 130 else -1, tool.ctx.getString(R.string.tedit_task_shell_ended), null)
    }
    private var hooked = false

    private fun hook() {
        if (hooked) return
        hooked = true
        tool.terminal.addPromptListener(onPrompt)
        tool.terminal.addExitListener(onExit)
    }

    /** Tasks from the workspace's .tedit/tasks.json; an error text when the file is there but cannot be read. */
    fun tasks(): Pair<List<TaskDef>, String?> {
        val ws = tool.workspace ?: return emptyList<TaskDef>() to null
        if (ws.source.backendKey != "local") return emptyList<TaskDef>() to null
        val f = File(ws.source.ref, ".tedit/tasks.json")
        if (!f.isFile) return emptyList<TaskDef>() to null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("tasks") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val cmd = t.optString("command").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TaskDef(t.optString("name").ifBlank { cmd }, cmd, t.optString("cwd").takeIf { it.isNotBlank() })
            } to null
        } catch (e: Exception) {
            emptyList<TaskDef>() to tool.ctx.getString(R.string.tedit_tasks_bad, e.message ?: "")
        }
    }

    private val linux get() = com.lxlauncher.tedit.linux.LinuxRuntime.get(tool.ctx)

    /** True when tasks and files run in Debian rather than in BusyBox or Android's sh. */
    private fun inDebian() = tool.prefs.string(TeditPrefs.TERM_SHELL) != "android" && linux.ready

    /** Why the file [d] cannot be run (a string resource), or 0. */
    fun whyNotRunFile(d: Document?): Int = when {
        d == null -> R.string.tedit_why_no_file
        (d.source as? DocSource.Node)?.backendKey != "local" -> R.string.tedit_run_needs_local
        d.language.id == "shell" -> 0
        !inDebian() -> if (RUNNERS.containsKey(d.language.id)) R.string.tedit_run_needs_debian else R.string.tedit_run_only_shell
        !RUNNERS.containsKey(d.language.id) -> R.string.tedit_run_no_runner
        else -> 0
    }

    fun runFile(d: Document) {
        val why = whyNotRunFile(d)
        if (why != 0) {
            if (why == R.string.tedit_run_needs_debian) tool.overlays.snack(tool.ctx.getString(why), tool.ctx.getString(R.string.tedit_linux_title)) { LinuxPage(tool).show() }
            else tool.overlays.snack(tool.ctx.getString(why))
            return
        }
        val path = (d.source as DocSource.Node).ref
        val command = if (inDebian()) (RUNNERS[d.language.id] ?: RUNNERS.getValue("shell")).format(shellQuote(path)) else "sh " + shellQuote(path)
        val go = { run(TaskDef(tool.ctx.getString(R.string.tedit_run_named, d.name), command, File(path).parent)) }
        if (d.dirty && tool.prefs.bool(TeditPrefs.RUN_SAVE_FIRST)) tool.save(d) { ok -> if (ok) go() } else go()
    }

    fun run(task: TaskDef) {
        if (running != null) { tool.overlays.snack(tool.ctx.getString(R.string.tedit_task_busy, running)); return }
        hook()
        if (tool.prefs.bool(TeditPrefs.RUN_SAVE_FIRST)) tool.store.docs.values.filter { it.dirty && it.source != null && !it.readOnly }.forEach { d -> tool.save(d) }
        val ws = tool.workspace?.source?.takeIf { it.backendKey == "local" }?.ref
        val dir = (task.cwd?.let { if (it.startsWith("/")) File(it) else ws?.let { w -> File(w, it) } } ?: ws?.let { File(it) })?.takeIf { it.isDirectory }
            ?: tool.ctx.filesDir
        // A finished run's session goes when the next run starts, unless the setting keeps the output of past runs. It
        // goes before this run counts as running, so its end is not taken for the end of this run.
        val panel = tool.terminal
        session?.let { old -> session = null; if (old.alive && tool.prefs.bool(TeditPrefs.RUN_CLEAR)) panel.killSession(old) }
        if (tool.prefs.bool(TeditPrefs.RUN_CLEAR)) output.setLength(0)
        output.append("$ ").append(task.command).append('\n')
        current = task
        running = task.name
        startedAt = SystemClock.elapsedRealtime()
        lastExit = null
        stoppedByUser = false
        sent = false
        val g = ++gen
        ranInDebian = inDebian()
        // The shell starts in the task's folder; typing the cd as well keeps a folder of the same name after a login
        // profile that changes directory.
        commandLine = "cd -- ${shellQuote(dir.path)} && ${task.command}"
        if (!tool.terminalOpen) tool.toggleTerminal()
        session = panel.newSession(focus = true, dir = dir, forRun = true, name = tool.ctx.getString(R.string.tedit_run_session, task.name))
        Log.d(TeditTool.TAG, "task started in the terminal")
        val timeoutMin = tool.prefs.int(TeditPrefs.RUN_TIMEOUT)
        if (timeoutMin > 0) main.postDelayed({
            if (g == gen && running != null) { output.append(tool.ctx.getString(R.string.tedit_task_timeout, timeoutMin)).append('\n'); stop() }
        }, timeoutMin * 60_000L)
        changed()
    }

    private fun finish(g: Int, exit: Int, message: String?, missing: String?) {
        if (g != gen || running == null) return
        val s = session
        // What the run printed, as the terminal shows it, so the Run panel keeps it after the session is gone.
        if (s != null && s.started) {
            val text = s.allText(tool.terminal.terminal.gridRows(), tool.terminal.terminal.gridCols())
            if (text.isNotEmpty()) output.append(text).append('\n')
        }
        message?.let { output.append(it).append('\n') }
        val took = SystemClock.elapsedRealtime() - startedAt
        output.append('\n').append(tool.ctx.getString(if (stoppedByUser) R.string.tedit_task_stopped_line else R.string.tedit_task_exit_line, exit, DateUtils.formatElapsedTime(took / 1000))).append('\n')
        if (output.length > 400_000) output.delete(0, output.length - 300_000)
        Log.d(TeditTool.TAG, "task finished: exit $exit in $took ms${if (stoppedByUser) " (stopped)" else ""}")
        lastExit = exit
        // An install may have brought a language server that was missing.
        if (exit == 0 && ranInDebian) tool.lsp.retryMissing()
        // A command Debian does not have yet: offer the package that brings it.
        if (exit == 127 && ranInDebian && !stoppedByUser && missing != null) {
            val pkg = PACKAGES[missing] ?: missing
            tool.overlays.snack(tool.ctx.getString(R.string.tedit_run_missing, missing, pkg), tool.ctx.getString(R.string.tedit_run_install)) {
                run(TaskDef(tool.ctx.getString(R.string.tedit_run_install_named, pkg), "apt-get update && apt-get install -y $pkg", null))
            }
        }
        val t = current
        running = null
        if (t != null) remember(t, exit, took)
        changed()
    }

    /**
     * Ctrl+C in the run's terminal, as a person would press it; a program that ignores it gets it once more, then its
     * session is ended.
     */
    fun stop() {
        val s = session ?: return
        if (running == null) return
        stoppedByUser = true
        s.interrupt()
        val g = gen
        main.postDelayed({ if (g == gen && running != null) s.interrupt() }, 1500)
        main.postDelayed({ if (g == gen && running != null) tool.terminal.killSession(s) }, 3000)
    }

    fun restart() {
        val t = current ?: return
        if (running != null) {
            stop()
            addListener(object : () -> Unit {
                override fun invoke() { if (running == null) { removeListener(this); main.post { run(t) } } }
            })
        } else run(t)
    }

    /** Shows the run's session in the terminal panel. */
    fun showTerminal() {
        val s = session ?: return
        if (!tool.terminalOpen) tool.toggleTerminal()
        if (s.alive || s.started) tool.terminal.show(s)
    }

    data class Past(val name: String, val command: String, val cwd: String?, val exit: Int, val millis: Long, val at: Long)

    fun history(): List<Past> {
        val arr = runCatching { JSONArray(tool.prefs.raw("runHistory") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Past(o.optString("name"), o.optString("command"), o.optString("cwd").ifEmpty { null }, o.optInt("exit"), o.optLong("ms"), o.optLong("at"))
        }
    }

    private fun remember(t: TaskDef, exit: Int, ms: Long) {
        if (!tool.prefs.bool(TeditPrefs.KEEP_HISTORY)) return
        val arr = JSONArray()
        arr.put(JSONObject().put("name", t.name).put("command", t.command).put("cwd", t.cwd ?: "").put("exit", exit).put("ms", ms).put("at", System.currentTimeMillis()))
        history().take(19).forEach { arr.put(JSONObject().put("name", it.name).put("command", it.command).put("cwd", it.cwd ?: "").put("exit", it.exit).put("ms", it.millis).put("at", it.at)) }
        tool.prefs.putRaw("runHistory", arr.toString())
    }

    companion object {
        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** Wait between the shell's prompt report and typing the command, in ms. */
        private const val TYPE_DELAY_MS = 250L

        /** How Debian runs a file of each language; %s is the quoted path. */
        val RUNNERS = mapOf(
            "shell" to "bash %s",
            "python" to "python3 %s",
            "javascript" to "node %s",
            "typescript" to "npx --yes tsx %s",
            "ruby" to "ruby %s",
            "php" to "php %s",
            "lua" to "lua5.4 %s",
            "go" to "go run %s",
            "c" to "gcc -O1 -o /tmp/tedit-run %s && /tmp/tedit-run",
            "cpp" to "g++ -O1 -o /tmp/tedit-run %s && /tmp/tedit-run",
            "rust" to "rustc -o /tmp/tedit-run %s && /tmp/tedit-run",
            "java" to "java %s",
        )

        /** Debian packages for commands people run that the base system lacks. */
        val PACKAGES = mapOf(
            "python3" to "python3", "python" to "python-is-python3", "pip3" to "python3-pip", "pip" to "python3-pip",
            "node" to "nodejs", "npm" to "npm", "npx" to "npm", "ruby" to "ruby", "php" to "php-cli", "lua5.4" to "lua5.4",
            "go" to "golang-go", "gcc" to "gcc", "g++" to "g++", "cc" to "gcc", "make" to "make", "rustc" to "rustc",
            "cargo" to "cargo", "java" to "default-jdk-headless", "javac" to "default-jdk-headless", "git" to "git",
            "curl" to "curl", "wget" to "wget", "nano" to "nano", "vim" to "vim", "clangd" to "clangd", "pylsp" to "python3-pylsp",
        )
    }
}

/** Run and Debug: tasks, the file in the editor, the current run with its output, and past runs. */
class RunPanel(private val tool: TeditTool) : TPanel {
    override val id = "run"
    override val title = R.string.tedit_panel_run
    override val glyph = TIcon.Glyph.RUN
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = ui.scroll(col)
    private var outputView: TextView? = null
    private var outputScroll: ScrollView? = null
    private var statusLine: TextView? = null
    private val listener: () -> Unit = { update() }
    private var lastState: String? = null

    override fun actions() = listOf(
        Triple(TIcon.Glyph.FILE, ctx.getString(R.string.tedit_tasks_edit) as CharSequence) { _: View -> editTasks() },
    )

    override fun onShow() { render() }

    fun runDefault() {
        val (tasks, _) = tool.runner.tasks()
        val d = tool.activeGroup?.active?.doc
        when {
            tool.runner.running != null -> tool.overlays.snack(ctx.getString(R.string.tedit_task_busy, tool.runner.running))
            tool.runner.whyNotRunFile(d) == 0 && d != null -> tool.runner.runFile(d)
            tasks.size == 1 -> tool.runner.run(tasks[0])
            tasks.size > 1 -> tool.overlays.choose(ctx.getString(R.string.tedit_run_task), tasks.map { it.name }, -1) { tool.runner.run(tasks[it]) }
            else -> tool.overlays.snack(ctx.getString(R.string.tedit_no_tasks_short))
        }
    }

    private fun stateKey() = "${tool.runner.running}|${tool.runner.lastExit}|${tool.workspace?.source?.key}"

    private fun update() {
        val key = stateKey()
        if (key != lastState) { render(); return }
        outputView?.let { tv ->
            tv.text = tool.runner.output
            outputScroll?.post { outputScroll?.fullScroll(View.FOCUS_DOWN) }
        }
        statusLine?.text = statusText()
    }

    private fun statusText(): String {
        val r = tool.runner
        return when {
            r.running != null -> ctx.getString(R.string.tedit_task_running, r.running, DateUtils.formatElapsedTime((SystemClock.elapsedRealtime() - r.startedAt) / 1000))
            r.lastExit == null -> ctx.getString(R.string.tedit_task_idle)
            r.stoppedByUser -> ctx.getString(R.string.tedit_task_stopped, r.current?.name ?: "")
            r.lastExit == 0 -> ctx.getString(R.string.tedit_task_ok, r.current?.name ?: "")
            else -> ctx.getString(R.string.tedit_task_failed, r.current?.name ?: "", r.lastExit ?: -1)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (tool.runner.running == null) return
            statusLine?.text = statusText()
            main.postDelayed(this, 1000)
        }
    }

    init {
        tool.runner.addListener(listener)
        render()
    }

    private fun render() {
        lastState = stateKey()
        col.removeAllViews()
        val r = tool.runner
        // Current run
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_run_current)))
        val card = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(14f), 0, t.dpi(14f), t.dpi(8f)) }
        val line = ui.row()
        val color = when { r.running != null -> t.accentText; r.lastExit == null -> t.textMuted; r.lastExit == 0 && !r.stoppedByUser -> t.success; else -> t.error }
        line.addView(ui.icon(when { r.running != null -> TIcon.Glyph.RUN; r.lastExit == 0 -> TIcon.Glyph.CHECK; r.lastExit == null -> TIcon.Glyph.INFO; else -> TIcon.Glyph.ERROR }, color, 16f),
            ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
        statusLine = ui.text(statusText(), t.textSmall, t.text, lines = 2)
        line.addView(statusLine, ui.lp(0, TeditTool.WRAP, 1f))
        card.addView(line)
        if (r.running != null) {
            card.addView(ProgressStrip(ctx, t), ui.lp(TeditTool.MATCH, t.dpi(2f)).apply { topMargin = t.dpi(8f) })
            main.removeCallbacks(ticker); main.postDelayed(ticker, 1000)
        }
        val buttons = FlowRow(ctx, t.dpi(8f)).apply { setPadding(0, t.dpi(8f), 0, 0) }
        fun small(v: TextView) = v.apply { minWidth = 0; setPadding(t.dpi(12f), 0, t.dpi(12f), 0) }
        if (r.running != null) buttons.addView(small(ui.button(ctx.getString(R.string.tedit_stop), danger = true) { r.stop() }))
        if (r.current != null) buttons.addView(small(ui.button(ctx.getString(R.string.tedit_restart)) { r.restart() }))
        // The run happens in the terminal: this brings its session up, to read it or to answer the program.
        if (r.session?.started == true && r.session?.let { it in tool.terminal.sessions() } == true)
            buttons.addView(small(ui.button(ctx.getString(R.string.tedit_run_show_terminal)) { r.showTerminal() }))
        if (r.output.isNotEmpty() && r.running == null) buttons.addView(small(ui.button(ctx.getString(R.string.tedit_clear)) { r.output.setLength(0); render() }))
        if (buttons.childCount > 0) card.addView(buttons)
        col.addView(card)
        if (r.output.isNotEmpty()) {
            val out = TextView(ctx).apply {
                text = r.output
                typeface = t.codeFont
                textSize = tool.prefs.float(TeditPrefs.TERM_FONT)
                setTextColor(t.text2)
                setTextIsSelectable(true)
                setPadding(t.dpi(12f), t.dpi(8f), t.dpi(12f), t.dpi(8f))
                setBackgroundColor(t.bg)
                contentDescription = ctx.getString(R.string.tedit_output)
            }
            val sc = ScrollView(ctx).apply { addView(out) }
            outputView = out
            outputScroll = sc
            col.addView(sc, ui.lp(TeditTool.MATCH, t.dpi(220f)).apply { setMargins(t.dpi(10f), 0, t.dpi(10f), t.dpi(8f)) })
            sc.post { sc.fullScroll(View.FOCUS_DOWN) }
        } else { outputView = null; outputScroll = null }
        // Run this file
        val d = tool.activeGroup?.active?.doc
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_run_file)))
        val why = r.whyNotRunFile(d)
        if (why == 0 && d != null) col.addView(taskRow(ctx.getString(R.string.tedit_run_named, d.name), "sh ${d.name}") { r.runFile(d) })
        else col.addView(ui.note(ctx.getString(why)))
        // Tasks
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_tasks)))
        val (tasks, problem) = r.tasks()
        problem?.let { col.addView(ui.note(it, t.error)) }
        if (tasks.isEmpty() && problem == null) {
            col.addView(ui.note(ctx.getString(if (tool.workspace?.source?.backendKey == "local") R.string.tedit_no_tasks else R.string.tedit_tasks_need_local)))
            if (tool.workspace?.source?.backendKey == "local") col.addView(ui.button(ctx.getString(R.string.tedit_tasks_create)) { editTasks() },
                ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginStart = t.dpi(14f) })
        }
        for (task in tasks) col.addView(taskRow(task.name, task.command) { r.run(task) })
        // Debug
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_debug)))
        col.addView(ui.note(ctx.getString(R.string.tedit_debug_unsupported)))
        // History
        val past = r.history()
        if (past.isNotEmpty()) {
            col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_run_history)))
            for (p in past) {
                val row = ui.row().apply { minimumHeight = t.rowH; setPadding(t.dpi(14f), t.dpi(4f), t.dpi(10f), t.dpi(4f)); background = ui.states(0f); isFocusable = true }
                row.addView(ui.icon(if (p.exit == 0) TIcon.Glyph.CHECK else TIcon.Glyph.ERROR, if (p.exit == 0) t.success else t.error, 14f), ui.lp(t.dpi(14f), t.dpi(14f)).apply { marginEnd = t.dpi(8f) })
                val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
                labels.addView(ui.text(p.name, t.textSmall, t.text))
                labels.addView(ui.text(ctx.getString(R.string.tedit_run_past, p.exit, DateUtils.formatElapsedTime(p.millis / 1000),
                    DateUtils.getRelativeTimeSpanString(p.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)), t.textCaption, t.textMuted))
                row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
                row.contentDescription = p.name
                row.setOnClickListener { r.run(TaskDef(p.name, p.command, p.cwd)) }
                col.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            }
        }
    }

    private fun taskRow(name: String, command: String, run: () -> Unit): View {
        val row = ui.row().apply { minimumHeight = t.touch; setPadding(t.dpi(14f), t.dpi(4f), t.dpi(4f), t.dpi(4f)) }
        val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        labels.addView(ui.text(name, t.textSmall, t.text))
        labels.addView(ui.text(command, t.textCaption, t.textMuted).apply { typeface = t.codeFont })
        row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
        row.addView(ui.iconButton(TIcon.Glyph.RUN, ctx.getString(R.string.tedit_run_named, name), t.success) { run() })
        return row
    }

    /** Opens .tedit/tasks.json, creating it with one example task first. */
    private fun editTasks() {
        val ws = tool.workspace
        if (ws == null || ws.source.backendKey != "local") { tool.overlays.snack(ctx.getString(R.string.tedit_tasks_need_local)); return }
        val f = File(ws.source.ref, ".tedit/tasks.json")
        Thread {
            val ok = runCatching {
                if (!f.isFile) {
                    f.parentFile?.mkdirs()
                    f.writeText("{\n  \"tasks\": [\n    {\n      \"name\": \"List files\",\n      \"command\": \"ls -la\",\n      \"cwd\": \".\"\n    }\n  ]\n}\n")
                }
            }.isSuccess
            main.post {
                if (ok) tool.open(DocSource.Node("local", f.absolutePath)) else tool.overlays.snack(ctx.getString(R.string.tedit_err_no_access))
            }
        }.apply { isDaemon = true }.start()
    }

    override fun dispose() {
        tool.runner.removeListener(listener)
        main.removeCallbacks(ticker)
    }
}
