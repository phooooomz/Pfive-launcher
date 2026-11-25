// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import java.io.File
import java.util.concurrent.Executors

/**
 * What Source Control knows about the workspace: the repository, its branch and its changes. Work happens on its
 * own thread so a large repository never holds up typing.
 */
class GitStatusModel(private val tool: TeditTool) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-git").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    var repo: GitRepo? = null
        private set
    var status: GitRepo.Status? = null
        private set
    var loading = false
        private set
    var error: String? = null
        private set
    /** Why there is no repository to show: no folder, a folder from Android's picker, or no .git above it. */
    var unavailable: Int = R.string.tedit_scm_no_folder
        private set
    val branch: String? get() = status?.let { it.branch ?: it.head?.take(7) }
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var disposed = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() { listeners.toList().forEach { it() }; tool.refreshStatus() }

    fun rootChanged() {
        repo = null
        status = null
        error = null
        val ws = tool.workspace
        when {
            !tool.prefs.bool(TeditPrefs.SCM_ENABLED) -> unavailable = R.string.tedit_scm_disabled
            ws == null -> unavailable = R.string.tedit_scm_no_folder
            ws.source.backendKey != "local" -> unavailable = R.string.tedit_scm_not_local
            else -> {
                unavailable = 0
                val dir = File(ws.source.ref)
                val gen = ++generation
                loading = true
                exec.execute {
                    val r = runCatching { GitRepo.find(dir) }.getOrNull()
                    main.post {
                        if (gen != generation || disposed) return@post
                        repo = r
                        if (r == null) { unavailable = R.string.tedit_scm_no_repo; loading = false; notifyListeners() }
                        else refresh()
                    }
                }
            }
        }
        notifyListeners()
    }

    fun refreshSoon() {
        main.removeCallbacks(refreshRunnable)
        main.postDelayed(refreshRunnable, 600)
    }

    private val refreshRunnable = Runnable { refresh() }

    fun refresh() {
        val r = repo ?: return
        val gen = ++generation
        loading = true
        notifyListeners()
        val untracked = tool.prefs.bool(TeditPrefs.SCM_UNTRACKED)
        val started = System.currentTimeMillis()
        exec.execute {
            val result = runCatching { r.status(untracked) { gen != generation || disposed } }
            main.post {
                if (gen != generation || disposed) return@post
                loading = false
                status = result.getOrNull()
                error = result.exceptionOrNull()?.let { tool.ctx.getString(R.string.tedit_scm_read_failed, it.javaClass.simpleName) }
                if (error != null) Log.w(TeditTool.TAG, "git status failed: ${result.exceptionOrNull()?.javaClass?.simpleName}")
                else Log.d(TeditTool.TAG, "git status: ${status?.staged?.size} staged, ${status?.unstaged?.size} changed in ${System.currentTimeMillis() - started} ms")
                notifyListeners()
                lineMarksForOpen()
            }
        }
    }

    /** The path of [d] inside the repository, or null. */
    fun relPath(d: Document?): String? {
        val r = repo ?: return null
        val src = d?.source as? DocSource.Node ?: return null
        if (src.backendKey != "local") return null
        val root = r.workTree.absolutePath.trimEnd('/') + "/"
        return src.ref.takeIf { it.startsWith(root) }?.removePrefix(root)
    }

    fun changeOf(path: String): GitRepo.Change? {
        val s = status ?: return null
        return s.unstaged.firstOrNull { it.path == path }?.change ?: s.staged.firstOrNull { it.path == path }?.change
    }

    /** Gutter marks for [d]: its text against the last commit. */
    fun lineMarks(d: Document?, done: (IntArray?) -> Unit) {
        val r = repo
        val path = relPath(d)
        if (r == null || path == null || d == null || d.loading || d.binary || d.image) { done(null); return }
        val lines = ArrayList<String>(d.buffer.lineCount)
        for (i in 0 until d.buffer.lineCount) lines += d.buffer.line(i)
        val head = status?.head
        exec.execute {
            val old = runCatching { r.blobAt(head, path) }.getOrNull()
            // A file the last commit does not have (untracked or ignored) gets no marks, as in VS Code.
            val marks = if (old == null) null else {
                val a = ArrayList<String>()
                TextBuffer.splitInto(String(old, d.codec.charset), a)
                Diff.gutterMarks(a, lines)
            }
            main.post { if (!disposed) done(marks) }
        }
    }

    fun lineMarksForOpen() {
        if (!tool.prefs.bool(TeditPrefs.SCM_DECORATIONS)) return
        for (g in tool.groups) if (g.isBuilt()) tool.lineMarksFor(g)
    }

    /** Old (HEAD) and new (working file) text of [path], for the diff view. */
    fun diffOf(path: String, done: (List<Diff.Line>?, String?) -> Unit) {
        val r = repo ?: return done(null, null)
        val head = status?.head
        val openDoc = tool.store.docs.values.firstOrNull { relPath(it) == path && !it.loading && !it.binary }
        val current: List<String>? = openDoc?.let { d -> (0 until d.buffer.lineCount).map { d.buffer.line(it) } }
        exec.execute {
            try {
                val oldBytes = r.blobAt(head, path)
                val f = File(r.workTree, path)
                val newText = current ?: if (f.isFile) {
                    if (f.length() > 8L * 1024 * 1024) { main.post { done(null, tool.ctx.getString(R.string.tedit_diff_too_large)) }; return@execute }
                    val bytes = f.readBytes()
                    if (TextCodec.looksBinary(bytes, bytes.size)) { main.post { done(null, tool.ctx.getString(R.string.tedit_diff_binary)) }; return@execute }
                    ArrayList<String>().also { TextBuffer.splitInto(String(bytes, Charsets.UTF_8), it) }
                } else emptyList()
                if (oldBytes != null && TextCodec.looksBinary(oldBytes, oldBytes.size)) { main.post { done(null, tool.ctx.getString(R.string.tedit_diff_binary)) }; return@execute }
                val oldLines = if (oldBytes == null) emptyList() else ArrayList<String>().also { TextBuffer.splitInto(String(oldBytes, Charsets.UTF_8), it) }
                val result = Diff.lines(oldLines, newText)
                main.post { done(result, if (result == null) tool.ctx.getString(R.string.tedit_diff_too_large) else null) }
            } catch (e: Throwable) {
                main.post { done(null, tool.ctx.getString(R.string.tedit_scm_read_failed, e.javaClass.simpleName)) }
            }
        }
    }

    fun history(limit: Int, done: (List<GitRepo.Commit>?) -> Unit) {
        val r = repo ?: return done(null)
        exec.execute {
            val log = runCatching { r.log(limit) }.getOrNull()
            main.post { if (!disposed) done(log) }
        }
    }

    fun dispose() {
        disposed = true
        generation++
        exec.shutdownNow()
    }
}

/**
 * Source Control: the branch, staged and changed files, a diff for each, and the history. Tedit reads the repository
 * and does not write to it, so staging and committing are shown switched off with that reason.
 */
class ScmPanel(private val tool: TeditTool) : TPanel {
    override val id = "scm"
    override val title = R.string.tedit_panel_scm
    override val glyph = TIcon.Glyph.BRANCH
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = ui.scroll(list)
    private var showHistory = false
    private val listener: () -> Unit = { render() }

    init {
        tool.git.addListener(listener)
        if (tool.git.repo == null && tool.workspace != null) tool.git.rootChanged()
        render()
    }

    override fun actions() = listOf(
        Triple(TIcon.Glyph.REFRESH, ctx.getString(R.string.tedit_refresh) as CharSequence) { _: View -> if (tool.git.repo != null) tool.git.refresh() else tool.git.rootChanged() },
        Triple(TIcon.Glyph.HISTORY, ctx.getString(R.string.tedit_scm_history) as CharSequence) { _: View -> showHistory = !showHistory; render() },
    )

    override fun onShow() { if (tool.git.repo != null && !tool.git.loading) tool.git.refreshSoon() }

    private fun render() {
        list.removeAllViews()
        val git = tool.git
        if (git.repo == null) {
            if (git.loading) list.addView(ui.note(ctx.getString(R.string.tedit_scm_looking)))
            else {
                list.addView(ui.note(ctx.getString(git.unavailable.takeIf { it != 0 } ?: R.string.tedit_scm_no_repo)))
                if (tool.workspace == null) list.addView(ui.button(ctx.getString(R.string.tedit_open_folder)) { tool.openFolderDialog() },
                    ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginStart = t.dpi(16f) })
            }
            return
        }
        val s = git.status
        // Branch line.
        val head = ui.row().apply { setPadding(t.dpi(14f), t.dpi(10f), t.dpi(14f), t.dpi(6f)) }
        head.addView(ui.icon(TIcon.Glyph.BRANCH, t.accentText, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
        head.addView(ui.text(git.branch ?: ctx.getString(R.string.tedit_scm_no_commits), t.textBody, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        if (git.loading) head.addView(ui.text(ctx.getString(R.string.tedit_scm_reading), t.textCaption, t.textMuted))
        list.addView(head)
        // Commit box: shown, switched off, with the reason.
        val box = ui.field(ctx.getString(R.string.tedit_scm_message_hint)).apply { isEnabled = false; alpha = 0.6f }
        list.addView(box, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(12f), t.dpi(4f), t.dpi(12f), 0) })
        val commit = ui.button(ctx.getString(R.string.tedit_scm_commit), primary = true) {}.apply { isEnabled = false; alpha = 0.5f }
        list.addView(commit, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(12f), t.dpi(8f), t.dpi(12f), 0) })
        list.addView(ui.text(ctx.getString(R.string.tedit_scm_read_only), t.textCaption, t.textMuted, lines = 0).apply { setPadding(t.dpi(14f), t.dpi(6f), t.dpi(14f), t.dpi(4f)) })
        git.error?.let { list.addView(ui.note(it, t.error)) }
        if (s == null) { if (!git.loading) list.addView(ui.note(ctx.getString(R.string.tedit_scm_no_status))); return }
        if (showHistory) { renderHistory(); return }
        section(ctx.getString(R.string.tedit_scm_staged, s.staged.size), s.staged, staged = true)
        section(ctx.getString(R.string.tedit_scm_changes, s.unstaged.size), s.unstaged, staged = false)
        if (s.truncated) list.addView(ui.note(ctx.getString(R.string.tedit_scm_truncated)))
        if (s.staged.isEmpty() && s.unstaged.isEmpty()) list.addView(ui.note(ctx.getString(R.string.tedit_scm_clean)))
    }

    private fun section(title: String, changes: List<GitRepo.FileChange>, staged: Boolean) {
        if (changes.isEmpty()) return
        list.addView(ui.sectionTitle(title))
        for (c in changes.take(500)) {
            val row = ui.row().apply {
                minimumHeight = t.rowH
                setPadding(t.dpi(14f), 0, t.dpi(10f), 0)
                background = ui.states(0f)
                isFocusable = true
                contentDescription = "${c.path}, ${ctx.getString(changeLabel(c.change))}"
            }
            row.addView(ui.icon(TIcon.Glyph.FILE, t.text2, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
            val name = c.path.substringAfterLast('/')
            val dir = c.path.substringBeforeLast('/', "")
            val labels = ui.row()
            labels.addView(ui.text(name, t.textSmall, t.text), ui.lp(TeditTool.WRAP, TeditTool.WRAP))
            if (dir.isNotEmpty()) labels.addView(ui.text(dir, t.textCaption, t.textMuted).apply { setPadding(t.dpi(6f), 0, 0, 0) }, ui.lp(0, TeditTool.WRAP, 1f))
            row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            row.addView(ui.text(changeLetter(c.change), t.textCaption, changeColor(c.change), medium = true).apply { minWidth = t.dpi(18f); gravity = Gravity.CENTER })
            row.setOnClickListener { if (c.change == GitRepo.Change.DELETED && !staged) showDiff(c.path) else showDiff(c.path) }
            row.setOnLongClickListener {
                tool.overlays.menu(row, listOf(
                    TItem(ctx.getString(R.string.tedit_scm_open_diff), TIcon.Glyph.DIFF) { showDiff(c.path) },
                    TItem(ctx.getString(R.string.tedit_scm_open_file), TIcon.Glyph.FILE, enabled = c.change != GitRepo.Change.DELETED,
                        reason = ctx.getString(R.string.tedit_scm_why_deleted)) { openFile(c.path) },
                    TItem(ctx.getString(R.string.tedit_scm_stage), enabled = false, reason = ctx.getString(R.string.tedit_scm_read_only_short)),
                    TItem(ctx.getString(R.string.tedit_scm_discard), enabled = false, danger = true, reason = ctx.getString(R.string.tedit_scm_read_only_short)),
                ), title = name)
                true
            }
            list.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        }
        if (changes.size > 500) list.addView(ui.note(ctx.getString(R.string.tedit_scm_more, changes.size - 500)))
    }

    private fun changeLetter(c: GitRepo.Change) = when (c) { GitRepo.Change.ADDED -> "A"; GitRepo.Change.MODIFIED -> "M"; GitRepo.Change.DELETED -> "D"; GitRepo.Change.UNTRACKED -> "U" }
    private fun changeColor(c: GitRepo.Change) = when (c) { GitRepo.Change.ADDED, GitRepo.Change.UNTRACKED -> t.success; GitRepo.Change.MODIFIED -> t.warning; GitRepo.Change.DELETED -> t.error }
    private fun changeLabel(c: GitRepo.Change) = when (c) {
        GitRepo.Change.ADDED -> R.string.tedit_scm_added; GitRepo.Change.MODIFIED -> R.string.tedit_scm_modified
        GitRepo.Change.DELETED -> R.string.tedit_scm_deleted; GitRepo.Change.UNTRACKED -> R.string.tedit_scm_untracked
    }

    private fun openFile(path: String) {
        val r = tool.git.repo ?: return
        tool.open(DocSource.Node("local", File(r.workTree, path).absolutePath))
    }

    private fun showDiff(path: String) {
        tool.git.diffOf(path) { lines, problem -> DiffView.show(tool, path, lines, problem) { openFile(path) } }
    }

    private fun renderHistory() {
        list.addView(ui.sectionTitle(ctx.getString(R.string.tedit_scm_history)))
        val holder = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        holder.addView(ui.note(ctx.getString(R.string.tedit_scm_reading)))
        list.addView(holder)
        tool.git.history(200) { log ->
            holder.removeAllViews()
            if (log.isNullOrEmpty()) { holder.addView(ui.note(ctx.getString(R.string.tedit_scm_no_commits))); return@history }
            for (c in log) {
                val row = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                    setPadding(t.dpi(14f), t.dpi(8f), t.dpi(14f), t.dpi(8f))
                    background = ui.states(0f)
                    isFocusable = true
                }
                row.addView(ui.text(c.message.lineSequence().firstOrNull()?.trim() ?: "", t.textSmall, t.text, lines = 2))
                val when_ = if (c.time > 0) DateUtils.getRelativeTimeSpanString(c.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else ""
                row.addView(ui.text("${c.sha.take(7)} · ${c.author} · $when_", t.textCaption, t.textMuted).apply { typeface = t.codeFont })
                row.setOnClickListener {
                    tool.overlays.message(c.sha.take(10), "${c.author}\n${if (c.time > 0) DateUtils.formatDateTime(ctx, c.time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) else ""}\n\n${c.message.trim()}")
                }
                holder.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            }
        }
    }

    override fun dispose() { tool.git.removeListener(listener) }
}

/** A read-only diff of one file: removed lines in red, added lines in green, with both line numbers. */
object DiffView {
    fun show(tool: TeditTool, path: String, lines: List<Diff.Line>?, problem: String?, openFile: () -> Unit) {
        val t = tool.theme
        val ui = tool.ui
        val ctx = tool.ctx
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        if (lines == null) {
            col.addView(ui.note(problem ?: ctx.getString(R.string.tedit_diff_none)))
        } else {
            val hunks = Diff.hunks(lines)
            if (hunks.isEmpty()) col.addView(ui.note(ctx.getString(R.string.tedit_diff_none)))
            val added = lines.count { it.kind == '+' }
            val removed = lines.count { it.kind == '-' }
            col.addView(ui.text(ctx.getString(R.string.tedit_diff_summary, added, removed), t.textCaption, t.textMuted).apply { setPadding(0, 0, 0, t.dpi(8f)) })
            var shown = 0
            for (h in hunks) {
                col.addView(ui.text("@@ -${h.oldStart + 1},${h.oldCount} +${h.newStart + 1},${h.newCount} @@", t.textCaption, t.syntax[Token.KEYWORD]).apply {
                    typeface = t.codeFont; setPadding(0, t.dpi(6f), 0, t.dpi(2f))
                })
                for (l in h.lines) {
                    if (shown++ > 3000) break
                    val tv = TextView(ctx).apply {
                        text = buildString {
                            append(if (l.oldNo >= 0) (l.oldNo + 1).toString().padStart(4) else "    ")
                            append(' ')
                            append(if (l.newNo >= 0) (l.newNo + 1).toString().padStart(4) else "    ")
                            append(' ').append(l.kind).append(' ').append(l.text)
                        }
                        typeface = t.codeFont
                        textSize = t.textCaption
                        setTextColor(when (l.kind) { '+' -> t.success; '-' -> t.error; else -> t.text2 })
                        setBackgroundColor(when (l.kind) { '+' -> t.diffAdded; '-' -> t.diffRemoved; else -> 0 })
                        setHorizontallyScrolling(true)
                        maxLines = 1
                        contentDescription = when (l.kind) {
                            '+' -> ctx.getString(R.string.tedit_diff_added_line, l.text)
                            '-' -> ctx.getString(R.string.tedit_diff_removed_line, l.text)
                            else -> l.text
                        }
                    }
                    col.addView(tv, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
                }
            }
            if (shown > 3000) col.addView(ui.note(ctx.getString(R.string.tedit_diff_cut)))
        }
        val hs = android.widget.HorizontalScrollView(ctx).apply { addView(col) }
        val scroll = ui.scroll(hs).apply { layoutParams = ui.lp(TeditTool.MATCH, (tool.view.height * 0.6f).toInt()) }
        var close: () -> Unit = {}
        val open = ui.button(ctx.getString(R.string.tedit_scm_open_file)) { close(); openFile() }
        val ok = ui.button(ctx.getString(R.string.tedit_ok), primary = true) { close() }
        close = tool.overlays.dialog(path, scroll, listOf(open to false, ok to true))
    }
}
