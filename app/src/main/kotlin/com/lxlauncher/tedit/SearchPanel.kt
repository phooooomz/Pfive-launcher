// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.files.FsNode
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Search, and replace, across the workspace (or the open files only). The walk runs on its own thread, reports
 * results as it finds them, and stops when a new search starts or the panel closes.
 */
/** Shortest time between two redraws of the results while a search runs, in ms. */
private const val RENDER_GAP_MS = 150L

class SearchPanel(private val tool: TeditTool) : TPanel {
    override val id = "search"
    override val title = R.string.tedit_panel_search
    override val glyph = TIcon.Glyph.SEARCH
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-search").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var generation = 0

    class Hit(val file: FsNode?, val doc: Document?, val path: String, val line: Int, val start: Int, val end: Int, val text: String)
    private sealed class Item {
        class Header(val path: String, val count: Int) : Item()
        class Match(val hit: Hit) : Item()
    }

    private var query = FindQuery("")
    private var include = ""
    private var exclude = ""
    private var openOnly = false
    private val hits = ArrayList<Hit>()
    private var items = ArrayList<Item>()
    private var searching = false
    private var scanned = 0
    @Volatile private var skippedLarge = 0
    private var truncated = false
    private var problem: String? = null

    private lateinit var boxScroll: ScrollView
    private var listWasEmpty = true
    /** The query area above the results; in a short panel it scrolls and the results keep a row and a half. */
    private val root = PanelColumn(ctx, t.dpi(40f), t.dpi(48f)) { items.isEmpty() }
    override val view: View = root
    private lateinit var field: EditText
    private lateinit var replaceField: EditText
    private lateinit var includeField: EditText
    private lateinit var excludeField: EditText
    private lateinit var caseBtn: ImageView
    private lateinit var wordBtn: ImageView
    private lateinit var regexBtn: ImageView
    private lateinit var summary: TextView
    private lateinit var progress: ProgressStrip
    private val list = ListView(ctx)
    private val adapter = Adapter()
    private var showFilters = false
    private lateinit var filterBox: LinearLayout
    private lateinit var replaceRow: LinearLayout

    init { build() }

    private fun build() {
        val pad = t.dpi(10f)
        val box = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(pad, pad, pad, t.dpi(4f)) }
        // The query takes the panel's full width; its options sit on the row below, so a narrow panel still has room to type.
        val r0 = ui.row()
        lateinit var toggle: ImageView
        toggle = ui.iconButton(TIcon.Glyph.CHEVRON_RIGHT, ctx.getString(R.string.tedit_toggle_replace), t.text2, t.dpi(32f)) {
            val on = replaceRow.visibility != View.VISIBLE
            replaceRow.visibility = if (on) View.VISIBLE else View.GONE
            toggle.setImageDrawable(TIcon(if (on) TIcon.Glyph.CHEVRON_DOWN else TIcon.Glyph.CHEVRON_RIGHT, t.text2))
        }
        r0.addView(toggle, ui.lp(t.dpi(24f), t.control))
        field = ui.field(ctx.getString(R.string.tedit_search_hint))
        r0.addView(field, ui.lp(0, TeditTool.WRAP, 1f))
        box.addView(r0)
        // The options wrap onto a second line in a narrow panel instead of running past its edge.
        val r1 = FlowRow(ctx, 0).apply { setPadding(t.dpi(24f), t.dpi(2f), 0, 0) }
        caseBtn = ui.iconButton(TIcon.Glyph.CASE, ctx.getString(R.string.tedit_match_case), t.text2, t.control) { query = query.copy(caseSensitive = !query.caseSensitive); marks(); start() }
        wordBtn = ui.iconButton(TIcon.Glyph.WORD, ctx.getString(R.string.tedit_whole_word), t.text2, t.control) { query = query.copy(wholeWord = !query.wholeWord); marks(); start() }
        regexBtn = ui.iconButton(TIcon.Glyph.REGEX, ctx.getString(R.string.tedit_use_regex), t.text2, t.control) { query = query.copy(regex = !query.regex); marks(); start() }
        r1.addView(caseBtn); r1.addView(wordBtn); r1.addView(regexBtn)
        r1.addView(ui.text(ctx.getString(R.string.tedit_search_filters), t.textCaption, t.accentText).apply {
            minHeight = t.control; gravity = Gravity.CENTER_VERTICAL; setPadding(t.dpi(6f), 0, t.dpi(6f), 0)
            background = ui.states(); isFocusable = true
            setOnClickListener { showFilters = !showFilters; filterBox.visibility = if (showFilters) View.VISIBLE else View.GONE }
        })
        box.addView(r1)
        summary = ui.text("", t.textCaption, t.textMuted, lines = 0)
        box.addView(summary, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(2f); marginStart = t.dpi(28f) })
        replaceRow = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(28f), t.dpi(4f), 0, 0); visibility = View.GONE }
        replaceField = ui.field(ctx.getString(R.string.tedit_replace_hint))
        replaceRow.addView(replaceField, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        replaceRow.addView(ui.text(ctx.getString(R.string.tedit_replace_all), t.textSmall, t.accentText, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL; minHeight = t.control; setPadding(t.dpi(4f), 0, t.dpi(10f), 0)
            background = ui.states(); isFocusable = true
            setOnClickListener { replaceAll() }
        }, ui.lp(TeditTool.WRAP, TeditTool.WRAP))
        box.addView(replaceRow)
        filterBox = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { visibility = View.GONE; setPadding(0, t.dpi(6f), 0, 0) }
        includeField = ui.field(ctx.getString(R.string.tedit_include_hint))
        excludeField = ui.field(ctx.getString(R.string.tedit_exclude_hint))
        filterBox.addView(includeField)
        filterBox.addView(excludeField, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(6f) })
        val scope = ui.row().apply { setPadding(0, t.dpi(4f), 0, 0) }
        scope.addView(ui.text(ctx.getString(R.string.tedit_search_open_only), t.textSmall, t.text2, lines = 2), ui.lp(0, TeditTool.WRAP, 1f))
        scope.addView(ui.toggle(openOnly, ctx.getString(R.string.tedit_search_open_only)) { openOnly = it; start() })
        filterBox.addView(scope)
        box.addView(filterBox)

        boxScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(box)
        }
        root.addView(boxScroll, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        progress = ProgressStrip(ctx, t).apply { visibility = View.INVISIBLE }
        root.addView(progress, ui.lp(TeditTool.MATCH, t.dpi(2f)))
        list.adapter = adapter
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        list.itemsCanFocus = true
        root.addView(list, ui.lp(TeditTool.MATCH, 0, 1f))
        root.setParts(boxScroll, list)
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { main.removeCallbacks(debounced); main.postDelayed(debounced, 350) }
        }
        field.addTextChangedListener(watcher)
        includeField.addTextChangedListener(watcher)
        excludeField.addTextChangedListener(watcher)
        field.setOnEditorActionListener { _, _, _ -> start(); true }
        field.setOnKeyListener { _, code, e -> if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_ENTER) { start(); true } else false }
        marks()
        render()
    }

    private val debounced = Runnable { start() }

    private fun marks() {
        fun m(v: ImageView, on: Boolean) { ui.setIconColor(v, if (on) t.accentText else t.text2); v.background = ui.states(selected = on); TeditUi.stateDesc(v, ctx.getString(if (on) R.string.tedit_v_on else R.string.tedit_v_off)) }
        m(caseBtn, query.caseSensitive); m(wordBtn, query.wholeWord); m(regexBtn, query.regex)
    }

    fun focusQuery(text: String?) {
        if (!text.isNullOrEmpty() && !text.contains('\n') && text.length < 200) field.setText(text)
        field.requestFocus()
        field.selectAll()
        TeditUi.showKeyboard(field)
    }

    fun rootChanged() { start() }

    override fun dispose() { generation++; exec.shutdownNow(); main.removeCallbacksAndMessages(null) }

    private fun globToRegex(globs: String): List<Regex> = globs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { g ->
        val sb = StringBuilder()
        for (c in g) sb.append(when (c) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(c.toString()) })
        Regex("(^|.*/)" + sb + "(/.*)?$", RegexOption.IGNORE_CASE)
    }

    private fun start() {
        query = query.copy(text = field.text.toString())
        include = includeField.text.toString()
        exclude = excludeField.text.toString()
        val gen = ++generation
        hits.clear()
        scanned = 0
        skippedLarge = 0
        truncated = false
        problem = null
        if (query.text.isEmpty()) { searching = false; render(); return }
        val pattern = query.compile().getOrElse { problem = ctx.getString(R.string.tedit_bad_regex); searching = false; render(); return }
        val ws = tool.workspace
        val maxResults = tool.prefs.int(TeditPrefs.SEARCH_MAX)
        val maxBytes = tool.prefs.int(TeditPrefs.SEARCH_MAX_KB) * 1024L
        val skipExcluded = tool.prefs.bool(TeditPrefs.SEARCH_SKIP_EXCLUDED)
        val inc = globToRegex(include)
        val exc = globToRegex(exclude) + if (skipExcluded) listOf(Regex("(^|.*/)(\\.git|node_modules|\\.gradle|build|\\.idea)(/.*)?$")) else emptyList()
        // Open documents are searched in their current (maybe unsaved) text.
        val openDocs = tool.store.docs.values.filter { !it.loading && !it.binary && !it.image && it.loadError == null }
        val openText = openDocs.associate { d -> d.key to (0 until d.buffer.lineCount).map { d.buffer.line(it) } }
        searching = true
        render()
        val started = System.currentTimeMillis()
        exec.execute {
            val batch = ArrayList<Hit>()
            var count = 0
            fun flush(force: Boolean) {
                if (batch.isEmpty() && !force) return
                val copy = ArrayList(batch)
                batch.clear()
                main.post { if (gen == generation) { hits += copy; renderSoon() } }
            }
            fun scanLines(lines: List<String>, file: FsNode?, doc: Document?, path: String): Boolean {
                val m = pattern.matcher("")
                for ((i, line) in lines.withIndex()) {
                    if (gen != generation) return false
                    m.reset(line)
                    var from = 0
                    while (from <= line.length && m.find(from)) {
                        batch += Hit(file, doc, path, i, m.start(), m.end(), line)
                        if (++count >= maxResults) return false
                        from = if (m.end() > m.start()) m.end() else m.end() + 1
                    }
                }
                return true
            }
            val seen = HashSet<String>()
            for (d in openDocs) {
                if (gen != generation) return@execute
                val path = (d.source as? DocSource.Node)?.let { relOrFull(it.ref) } ?: d.name
                if (inc.isNotEmpty() && inc.none { it.matches(path) }) continue
                if (exc.any { it.matches(path) }) continue
                seen += d.key
                if (!scanLines(openText[d.key] ?: continue, null, d, path)) { truncated = count >= maxResults; break }
                flush(false)
            }
            if (!openOnly && ws != null && count < maxResults) {
                val queue = ArrayDeque<Pair<FsNode, String>>()
                queue += ws.node to ""
                var dirs = 0
                walk@ while (queue.isNotEmpty()) {
                    if (gen != generation) return@execute
                    val (dir, rel) = queue.removeFirst()
                    if (++dirs > 20000) { truncated = true; break }
                    val kids = runCatching { dir.backend.children(dir) }.getOrNull() ?: continue
                    for (k in kids.sortedBy { it.name }) {
                        val path = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                        if (exc.any { it.matches(path) }) continue
                        if (k.isDir) { queue += k to path; continue }
                        if (inc.isNotEmpty() && inc.none { it.matches(path) }) continue
                        if (DocSource.of(k).key in seen) continue
                        if (k.size > maxBytes || k.size < 0) { skippedLarge++; continue }
                        scanned++
                        val bytes = runCatching {
                            k.backend.openRead(k).use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
                        }.getOrNull() ?: continue
                        if (TextCodec.looksBinary(bytes, bytes.size)) continue
                        val codec = TextCodec.detect(bytes, bytes.size, TextCodec.UTF8)
                        val lines = ArrayList<String>()
                        TextBuffer.splitInto(TextCodec.decode(bytes, bytes.size, codec), lines)
                        if (!scanLines(lines, k, null, path)) { truncated = count >= maxResults; break@walk }
                        if (batch.size > 40) flush(false)
                        if (scanned % 50 == 0) main.post { if (gen == generation) renderSoon() }
                    }
                }
            }
            flush(true)
            Log.d(TeditTool.TAG, "search: $count hits in $scanned files, ${System.currentTimeMillis() - started} ms")
            main.post { if (gen == generation) { searching = false; render() } }
        }
    }

    private var renderPosted = false
    private var lastRender = 0L

    /**
     * Redraws the results at most about seven times a second while a search runs: each redraw regroups every hit so
     * far, and doing it per batch kept the window busy until the search ended.
     */
    private fun renderSoon() {
        if (renderPosted) return
        renderPosted = true
        val wait = (RENDER_GAP_MS - (android.os.SystemClock.uptimeMillis() - lastRender)).coerceAtLeast(0L)
        main.postDelayed({ renderPosted = false; render() }, wait)
    }

    private fun relOrFull(ref: String): String {
        val base = tool.workspace?.source?.ref?.trimEnd('/') ?: return ref
        return if (ref.startsWith("$base/")) ref.removePrefix("$base/") else ref
    }

    private fun render() {
        lastRender = android.os.SystemClock.uptimeMillis()
        progress.visibility = if (searching) View.VISIBLE else View.INVISIBLE
        val files = hits.map { it.path }.distinct().size
        summary.setTextColor(if (problem != null) t.error else t.textMuted)
        summary.text = when {
            problem != null -> problem
            query.text.isEmpty() -> if (tool.workspace == null && !openOnly) ctx.getString(R.string.tedit_search_no_folder) else ""
            searching && hits.isEmpty() -> ctx.getString(R.string.tedit_searching, scanned)
            hits.isEmpty() -> ctx.getString(R.string.tedit_no_results)
            else -> ctx.resources.getQuantityString(R.plurals.tedit_search_summary, hits.size, hits.size) + " " + ctx.resources.getQuantityString(R.plurals.tedit_search_in_files, files, files) + if (truncated) " " + ctx.getString(R.string.tedit_search_truncated) else ""
        } + if (!searching && skippedLarge > 0 && query.text.isNotEmpty()) "\n" + ctx.resources.getQuantityString(R.plurals.tedit_search_skipped, skippedLarge, skippedLarge) else ""
        items = ArrayList()
        var last: String? = null
        val counts = hits.groupingBy { it.path }.eachCount()
        for (h in hits) {
            if (h.path != last) { items += Item.Header(h.path, counts[h.path] ?: 0); last = h.path }
            items += Item.Match(h)
        }
        adapter.notifyDataSetChanged()
        // The column shares its height again when the results come or go.
        if (items.isEmpty() != listWasEmpty) {
            listWasEmpty = items.isEmpty()
            root.requestLayout()
        }
    }

    private fun openHit(h: Hit) {
        if (h.doc != null) {
            val src = h.doc.source
            if (src != null) tool.open(src, line = h.line, col = h.start, endCol = h.end)
            else tool.groups.firstOrNull { g -> g.tabs.any { it.doc === h.doc } }?.let { g ->
                g.select(g.tabs.first { it.doc === h.doc }); tool.activateGroup(g)
                g.editor.post { g.editor.select(Pos(h.line, h.start), Pos(h.line, h.end), center = true) }
            }
        } else if (h.file != null) tool.open(DocSource.of(h.file), line = h.line, col = h.start, endCol = h.end)
    }

    /** Replace in every file found: open files change as one undo step each; closed files are written once confirmed. */
    private fun replaceAll() {
        if (hits.isEmpty() || searching) { tool.overlays.snack(ctx.getString(if (searching) R.string.tedit_search_wait else R.string.tedit_no_results)); return }
        val pattern = query.compile().getOrNull() ?: return
        val rep = query.replacement(replaceField.text.toString())
        val byFile = hits.groupBy { it.doc?.key ?: it.file?.key ?: it.path }
        val n = hits.size
        val files = byFile.size
        val closed = byFile.values.count { it.first().doc == null }
        tool.overlays.confirm(ctx.getString(R.string.tedit_replace_all),
            ctx.resources.getQuantityString(R.plurals.tedit_replace_files_confirm, n, n, files) + if (closed > 0) "\n\n" + ctx.resources.getQuantityString(R.plurals.tedit_replace_closed_note, closed, closed) else "",
            ctx.getString(R.string.tedit_replace_all), danger = closed > 0) {
            var done = 0
            for ((_, group) in byFile) {
                val d = group.first().doc
                if (d != null) {
                    if (d.readOnly) continue
                    val text = runCatching { replaceLines((0 until d.buffer.lineCount).map { d.buffer.line(it) }, pattern, rep) }.getOrNull() ?: continue
                    val ed = tool.groups.firstOrNull { it.active?.doc === d }?.editor
                    if (ed != null) ed.replace(Pos.ZERO, d.buffer.lastPos, text)
                    else { d.history.apply(Pos.ZERO, d.buffer.lastPos, text, Pos.ZERO to Pos.ZERO, EditHistory.Kind.OTHER, android.os.SystemClock.uptimeMillis()); d.changed() }
                    done++
                }
            }
            val closedFiles = byFile.values.filter { it.first().doc == null }.mapNotNull { it.first().file }
            if (closedFiles.isEmpty()) { tool.overlays.snack(ctx.resources.getQuantityString(R.plurals.tedit_replaced_files, done, done)); start(); return@confirm }
            exec.execute {
                var ok = 0
                var failed = 0
                for (f in closedFiles) {
                    val r = runCatching {
                        val bytes = f.backend.openRead(f).use { pfd -> java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() } }
                        val codec = TextCodec.detect(bytes, bytes.size, TextCodec.UTF8)
                        val text = TextCodec.decode(bytes, bytes.size, codec)
                        val eol = Document.detectEol(text, BooleanArray(1)) ?: "\n"
                        val lines = ArrayList<String>()
                        TextBuffer.splitInto(text, lines)
                        val out = replaceLines(lines, pattern, rep).replace("\n", eol)
                        if (out != lines.joinToString(eol)) tool.store.files.write(DocSource.of(f), codec.encode(out))
                    }
                    if (r.isSuccess) ok++ else failed++
                }
                main.post {
                    tool.overlays.snack(ctx.resources.getQuantityString(R.plurals.tedit_replaced_files, ok + done, ok + done) +
                        if (failed > 0) " " + ctx.resources.getQuantityString(R.plurals.tedit_replace_failed, failed, failed) else "")
                    tool.git.refreshSoon()
                    start()
                }
            }
        }
    }

    private fun replaceLines(lines: List<String>, p: Pattern, rep: String): String {
        val m = p.matcher("")
        return lines.joinToString("\n") { m.reset(it); m.replaceAll(rep) }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            if (item is Item.Header) {
                val v = (convertView as? LinearLayout) ?: ui.row().apply {
                    minimumHeight = t.rowH
                    setPadding(t.dpi(10f), 0, t.dpi(12f), 0)
                    addView(ui.icon(TIcon.Glyph.FILE, t.text2, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
                    addView(ui.text("", t.textSmall, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
                    addView(ui.text("", t.textCaption, t.textMuted))
                }
                (v.getChildAt(1) as TextView).text = item.path
                (v.getChildAt(2) as TextView).text = item.count.toString()
                v.contentDescription = ctx.resources.getQuantityString(R.plurals.tedit_matches, item.count, item.count) + ", " + item.path
                return v
            }
            val h = (item as Item.Match).hit
            val v = (convertView as? TextView) ?: ui.text("", t.textSmall, t.text2, lines = 1).apply {
                minHeight = t.dpi(34f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(t.dpi(34f), 0, t.dpi(10f), 0)
                background = ui.states(0f)
                isFocusable = true
                typeface = t.codeFont
            }
            // A snippet around the match, with the match itself highlighted.
            val from = maxOf(0, h.start - 24)
            val to = minOf(h.text.length, h.end + 80)
            val lead = h.text.substring(from, h.start).trimStart()
            val sb = SpannableStringBuilder()
            sb.append("${h.line + 1}  ")
            sb.setSpan(ForegroundColorSpan(t.textMuted), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (from > 0) sb.append("…")
            sb.append(lead)
            val ms = sb.length
            sb.append(h.text, h.start, h.end)
            sb.setSpan(BackgroundColorSpan(t.findMatch), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(t.text), ms, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(h.text, h.end, to)
            v.text = sb
            v.contentDescription = ctx.getString(R.string.tedit_hit_desc, h.line + 1, h.text.trim())
            v.setOnClickListener { openHit(h) }
            return v
        }
    }
}
