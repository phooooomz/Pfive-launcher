// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.files.ArchiveFormat
import com.lxlauncher.files.Caps
import com.lxlauncher.files.Conflict
import com.lxlauncher.files.ConflictChoice
import com.lxlauncher.files.ConflictResolver
import com.lxlauncher.files.FileServices
import com.lxlauncher.files.FsNode
import com.lxlauncher.files.JobListener
import com.lxlauncher.files.JobProgress
import com.lxlauncher.files.JobResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The workspace's file tree. Folders load when opened; copy, move, delete, compress and extract run through LX's
 * file jobs, the same ones the File Explorer uses, so they keep going if the panel closes.
 */
class ExplorerPanel(private val tool: TeditTool) : TPanel {
    override val id = "explorer"
    override val title = R.string.tedit_panel_explorer
    override val glyph = TIcon.Glyph.FILES
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx: Context get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-tree").apply { isDaemon = true } }
    private val jobs = FileServices.jobs(ctx)

    private class Row(val node: FsNode, val depth: Int)
    private val children = HashMap<String, List<FsNode>>()
    private val expanded = HashSet<String>()
    private val loadingDirs = HashSet<String>()
    private val failed = HashMap<String, String>()
    private var rows = ArrayList<Row>()
    private val selected = LinkedHashMap<String, FsNode>()
    private var selecting = false
    private var clip: Pair<List<FsNode>, Boolean>? = null
    private var filter = ""
    private var activeKey: String? = null
    @Volatile private var disposed = false

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root
    private val selectionBar = LinearLayout(ctx)
    private lateinit var filterField: EditText
    private val listView = ListView(ctx)
    private val adapter = TreeAdapter()
    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val jobListener = object : JobListener {
        override fun onProgress(progress: JobProgress) {}
        override fun onFinished(result: JobResult) {
            main.post {
                if (disposed) return@post
                if (!result.ok && !result.cancelled) tool.overlays.snack(result.error?.let { tool.store.errorText(it) } ?: ctx.getString(R.string.tedit_job_failed))
                refresh()
                tool.git.refreshSoon()
            }
        }
    }

    private val gitListener: () -> Unit = { adapter.notifyDataSetChanged() }

    init {
        jobs.addListener(jobListener)
        filterField = ui.field(ctx.getString(R.string.tedit_filter_hint)).apply { visibility = View.GONE }
        filterField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim() ?: ""; rebuildRows() }
        })
        root.addView(filterField, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(10f), t.dpi(8f), t.dpi(10f), t.dpi(4f)) })
        selectionBar.visibility = View.GONE
        root.addView(selectionBar, ui.lp(TeditTool.MATCH, t.touch))
        listView.adapter = adapter
        listView.divider = null
        listView.selector = android.graphics.drawable.ColorDrawable(0)
        listView.isVerticalScrollBarEnabled = true
        listView.itemsCanFocus = true
        root.addView(empty, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        root.addView(listView, ui.lp(TeditTool.MATCH, 0, 1f))
        tool.git.addListener(gitListener)
        rootChanged()
    }

    override fun actions(): List<Triple<TIcon.Glyph, CharSequence, (View) -> Unit>> {
        if (tool.workspace == null) return emptyList()
        return listOf(
            Triple(TIcon.Glyph.NEW_FILE, ctx.getString(R.string.tedit_new_file)) { _ -> newIn(targetDir(), folder = false) },
            Triple(TIcon.Glyph.NEW_FOLDER, ctx.getString(R.string.tedit_new_folder)) { _ -> newIn(targetDir(), folder = true) },
            Triple(TIcon.Glyph.REFRESH, ctx.getString(R.string.tedit_refresh)) { _ -> refresh() },
            Triple(TIcon.Glyph.MORE, ctx.getString(R.string.tedit_more)) { v -> moreMenu(v) },
        )
    }

    private fun moreMenu(anchor: View) {
        val sort = tool.prefs.string(TeditPrefs.SORT)
        tool.overlays.menu(anchor, listOf(
            TItem(ctx.getString(R.string.tedit_filter), TIcon.Glyph.FILTER) { toggleFilter() },
            TItem(ctx.getString(R.string.tedit_collapse_all), TIcon.Glyph.COLLAPSE) { expanded.clear(); rebuildRows() },
            TItem(ctx.getString(R.string.tedit_select_many), TIcon.Glyph.CHECK) { startSelecting(null) },
            TItem(ctx.getString(R.string.tedit_v_name), checked = sort == "name", divider = true) { tool.prefs.set(TeditPrefs.SORT, "name") },
            TItem(ctx.getString(R.string.tedit_v_type), checked = sort == "type") { tool.prefs.set(TeditPrefs.SORT, "type") },
            TItem(ctx.getString(R.string.tedit_v_modified), checked = sort == "modified") { tool.prefs.set(TeditPrefs.SORT, "modified") },
            TItem(ctx.getString(R.string.tedit_s_show_hidden), checked = tool.prefs.bool(TeditPrefs.SHOW_HIDDEN), divider = true) { tool.prefs.set(TeditPrefs.SHOW_HIDDEN, !tool.prefs.bool(TeditPrefs.SHOW_HIDDEN)) },
            TItem(ctx.getString(R.string.tedit_import), TIcon.Glyph.IMPORT, divider = true) { importInto(targetDir()) },
            TItem(ctx.getString(R.string.tedit_close_workspace), TIcon.Glyph.CLOSE) { tool.closeWorkspace() },
        ), title = ctx.getString(R.string.tedit_panel_explorer))
    }

    private fun toggleFilter() {
        val on = filterField.visibility != View.VISIBLE
        filterField.visibility = if (on) View.VISIBLE else View.GONE
        if (on) TeditUi.showKeyboard(filterField) else { filterField.setText(""); TeditUi.hideKeyboard(filterField) }
    }

    fun rootChanged() {
        children.clear()
        expanded.clear()
        failed.clear()
        selected.clear()
        selecting = false
        tool.workspace?.let { expanded += it.node.key; load(it.node) }
        rebuildRows()
        tool.refreshPanelActions()
    }

    fun refresh() {
        FileIndex.invalidate()
        val ws = tool.workspace ?: return
        val open = expanded.toList()
        children.clear()
        failed.clear()
        for (key in open) findNode(key)?.let { load(it) }
        load(ws.node)
        docsChanged()
    }

    private fun findNode(key: String): FsNode? {
        tool.workspace?.node?.let { if (it.key == key) return it }
        for (list in children.values) for (n in list) if (n.key == key) return n
        return null
    }

    private fun targetDir(): FsNode? {
        val sel = selected.values.singleOrNull()
        if (sel != null) return if (sel.isDir) sel else parentOf(sel)
        return tool.workspace?.node
    }

    private fun parentOf(n: FsNode): FsNode? {
        for ((k, list) in children) if (list.any { it.key == n.key }) return findNode(k)
        return null
    }

    private val excluded = setOf(".git", ".svn", ".hg", "CVS", ".DS_Store", "Thumbs.db")

    private fun load(dir: FsNode) {
        if (!loadingDirs.add(dir.key)) return
        rebuildRows()
        io.execute {
            val result = runCatching { dir.backend.children(dir) }
            main.post {
                loadingDirs.remove(dir.key)
                if (disposed) return@post
                result.onSuccess { children[dir.key] = sort(it); failed.remove(dir.key) }
                    .onFailure { failed[dir.key] = tool.store.errorText(it); Log.w(TeditTool.TAG, "tree list failed: ${it.javaClass.simpleName}") }
                rebuildRows()
            }
        }
    }

    private fun sort(list: List<FsNode>): List<FsNode> {
        val hidden = tool.prefs.bool(TeditPrefs.SHOW_HIDDEN)
        val exclude = tool.prefs.bool(TeditPrefs.EXCLUDE)
        val foldersFirst = tool.prefs.bool(TeditPrefs.FOLDERS_FIRST)
        val filtered = list.filter { (hidden || !it.hidden) && !(exclude && it.name in excluded) }
        val byName = compareBy<FsNode, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val cmp = when (tool.prefs.string(TeditPrefs.SORT)) {
            "type" -> compareBy<FsNode> { it.extension }.then(byName)
            "modified" -> compareByDescending<FsNode> { it.modified }.then(byName)
            else -> byName
        }
        return if (foldersFirst) filtered.sortedWith(compareBy<FsNode> { !it.isDir }.then(cmp)) else filtered.sortedWith(cmp)
    }

    private fun rebuildRows() {
        val ws = tool.workspace
        empty.removeAllViews()
        rows = ArrayList()
        if (ws == null) {
            empty.addView(ui.note(ctx.getString(R.string.tedit_no_folder_note)))
            empty.addView(ui.button(ctx.getString(R.string.tedit_open_folder), primary = true) { tool.openFolderDialog() }.apply { maxLines = 3; ellipsize = null; setPadding(t.dpi(10f), t.dpi(8f), t.dpi(10f), t.dpi(8f)) },
                ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(16f), 0, t.dpi(16f), t.dpi(8f)) })
            val active = tool.activeGroup?.active?.doc?.source as? DocSource.Node
            if (active != null) empty.addView(ui.button(ctx.getString(R.string.tedit_open_containing)) { openContaining(active) }.apply { maxLines = 3; ellipsize = null; setPadding(t.dpi(10f), t.dpi(8f), t.dpi(10f), t.dpi(8f)) },
                ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(16f), 0, t.dpi(16f), t.dpi(8f)) })
            for (r in tool.store.recents("folders").take(5)) {
                val row = ui.row().apply { minimumHeight = t.rowH; setPadding(t.dpi(16f), 0, t.dpi(12f), 0); background = ui.states(0f); isFocusable = true }
                row.addView(ui.icon(TIcon.Glyph.FOLDER, t.text2, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
                row.addView(ui.text(r.name, t.textSmall, t.accentText), ui.lp(0, TeditTool.WRAP, 1f))
                row.setOnClickListener { tool.openRecent(r, folder = true) }
                empty.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            }
            adapter.notifyDataSetChanged()
            return
        }
        fun add(dir: FsNode, depth: Int) {
            val kids = children[dir.key] ?: return
            for (k in kids) {
                if (filter.isNotEmpty() && !matchesFilter(k)) continue
                rows += Row(k, depth)
                if (k.isDir && (k.key in expanded || filter.isNotEmpty())) add(k, depth + 1)
            }
        }
        rows += Row(ws.node, 0)
        if (ws.node.key in expanded) add(ws.node, 1)
        failed[ws.node.key]?.let { empty.addView(ui.note(it, t.error)) }
        if (children[ws.node.key]?.isEmpty() == true) empty.addView(ui.note(ctx.getString(R.string.tedit_folder_empty)))
        if (filter.isNotEmpty() && rows.size <= 1) empty.addView(ui.note(ctx.getString(R.string.tedit_filter_none, filter)))
        adapter.notifyDataSetChanged()
    }

    private fun matchesFilter(n: FsNode): Boolean {
        if (n.name.contains(filter, ignoreCase = true)) return true
        if (!n.isDir) return false
        return children[n.key]?.any { matchesFilter(it) } == true
    }

    private fun openContaining(src: DocSource.Node) {
        io.execute {
            val parent = runCatching { tool.store.files.node(src)?.let { it.backend.parent(it) } }.getOrNull()
            main.post { if (parent != null) tool.openFolderNode(parent) }
        }
    }

    /** Dirty marks in the tree follow the documents. */
    fun docsChanged() {
        adapter.notifyDataSetChanged()
    }

    fun activeChanged() {
        activeKey = (tool.activeGroup?.active?.doc?.source as? DocSource.Node)?.let { "${it.backendKey}\u0000${it.ref}" }
        adapter.notifyDataSetChanged()
        docsChanged()
    }

    /** Opens the folders down to [ref] and scrolls it into view. */
    fun reveal(ref: String) {
        val ws = tool.workspace ?: return
        val base = ws.source.ref.trimEnd('/')
        if (!ref.startsWith("$base/")) return
        val parts = ref.removePrefix("$base/").split('/')
        var acc = base
        for (p in parts.dropLast(1)) {
            acc = "$acc/$p"
            val key = ws.node.backend.key + "\u0000" + acc
            expanded += key
            findNode(key)?.let { if (children[it.key] == null) load(it) }
        }
        activeKey = ws.node.backend.key + "\u0000" + ref
        rebuildRows()
        main.postDelayed({
            val i = rows.indexOfFirst { it.node.key == activeKey }
            if (i >= 0) listView.setSelection(maxOf(0, i - 3))
        }, 250)
    }

    // ---- actions ----

    private fun tapped(n: FsNode) {
        if (selecting) { toggleSelect(n); return }
        if (n.isDir) {
            if (!expanded.remove(n.key)) { expanded += n.key; if (children[n.key] == null) load(n) }
            rebuildRows()
            return
        }
        tool.openNode(n)
    }

    private fun startSelecting(first: FsNode?) {
        selecting = true
        selected.clear()
        first?.let { selected[it.key] = it }
        renderSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelect(n: FsNode) {
        if (selected.remove(n.key) == null) selected[n.key] = n
        renderSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun endSelecting() {
        selecting = false
        selected.clear()
        selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun renderSelectionBar() {
        selectionBar.removeAllViews()
        selectionBar.visibility = View.VISIBLE
        selectionBar.setBackgroundColor(t.active)
        selectionBar.gravity = Gravity.CENTER_VERTICAL
        selectionBar.addView(ui.iconButton(TIcon.Glyph.CLOSE, ctx.getString(R.string.tedit_cancel)) { endSelecting() })
        selectionBar.addView(ui.text(ctx.resources.getQuantityString(R.plurals.tedit_selected, selected.size, selected.size), t.textSmall, t.text), ui.lp(0, TeditTool.WRAP, 1f))
        val sel = selected.values.toList()
        val any = sel.isNotEmpty()
        selectionBar.addView(ui.iconButton(TIcon.Glyph.COPY, ctx.getString(R.string.tedit_copy)) { if (any) { clip = sel to false; endSelecting(); tool.overlays.snack(ctx.getString(R.string.tedit_copied_n, sel.size)) } })
        selectionBar.addView(ui.iconButton(TIcon.Glyph.CUT, ctx.getString(R.string.tedit_cut)) { if (any) { clip = sel to true; endSelecting(); tool.overlays.snack(ctx.getString(R.string.tedit_cut_n, sel.size)) } })
        selectionBar.addView(ui.iconButton(TIcon.Glyph.ARCHIVE, ctx.getString(R.string.tedit_compress)) { if (any) compress(sel) })
        selectionBar.addView(ui.iconButton(TIcon.Glyph.TRASH, ctx.getString(R.string.tedit_delete), t.error) { if (any) delete(sel) })
    }

    private fun contextMenu(n: FsNode, anchor: View) {
        val isRoot = n.key == tool.workspace?.node?.key
        val dir = if (n.isDir) n else parentOf(n)
        val archive = !n.isDir && (n.name.endsWith(".zip", true) || n.name.endsWith(".tar", true) || n.name.endsWith(".tar.gz", true) || n.name.endsWith(".tgz", true))
        val canWriteDir = dir?.can(Caps.CREATE) == true
        val noWrite = ctx.getString(R.string.tedit_why_folder_read_only)
        val items = ArrayList<TItem>()
        if (!n.isDir) {
            items += TItem(ctx.getString(R.string.tedit_open), TIcon.Glyph.FILE) { tool.openNode(n) }
            items += TItem(ctx.getString(R.string.tedit_open_side), TIcon.Glyph.SPLIT_RIGHT) { openToSide(n) }
        }
        items += TItem(ctx.getString(R.string.tedit_new_file), TIcon.Glyph.NEW_FILE, enabled = canWriteDir, reason = noWrite, divider = !n.isDir) { newIn(dir, folder = false) }
        items += TItem(ctx.getString(R.string.tedit_new_folder), TIcon.Glyph.NEW_FOLDER, enabled = canWriteDir, reason = noWrite) { newIn(dir, folder = true) }
        items += TItem(ctx.getString(R.string.tedit_rename), TIcon.Glyph.RENAME, "F2", enabled = !isRoot && n.can(Caps.RENAME), divider = true,
            reason = ctx.getString(if (isRoot) R.string.tedit_why_root else R.string.tedit_why_no_rename)) { rename(n) }
        items += TItem(ctx.getString(R.string.tedit_duplicate), TIcon.Glyph.COPY, enabled = !isRoot && canWriteDir, reason = noWrite) { duplicate(n) }
        items += TItem(ctx.getString(R.string.tedit_copy), TIcon.Glyph.COPY, enabled = !isRoot, reason = ctx.getString(R.string.tedit_why_root)) { clip = listOf(n) to false; tool.overlays.snack(ctx.getString(R.string.tedit_copied_n, 1)) }
        items += TItem(ctx.getString(R.string.tedit_cut), TIcon.Glyph.CUT, enabled = !isRoot && n.can(Caps.DELETE), reason = ctx.getString(R.string.tedit_why_root)) { clip = listOf(n) to true; tool.overlays.snack(ctx.getString(R.string.tedit_cut_n, 1)) }
        val c = clip
        items += TItem(ctx.getString(R.string.tedit_paste), TIcon.Glyph.PASTE, enabled = c != null && canWriteDir,
            reason = ctx.getString(if (c == null) R.string.tedit_why_clip_empty else R.string.tedit_why_folder_read_only)) { if (c != null && dir != null) paste(c, dir) }
        items += TItem(ctx.getString(R.string.tedit_compress), TIcon.Glyph.ARCHIVE, enabled = !isRoot && canWriteDir, divider = true, reason = noWrite) { compress(listOf(n)) }
        if (archive) items += TItem(ctx.getString(R.string.tedit_extract_here), TIcon.Glyph.ARCHIVE, enabled = canWriteDir, reason = noWrite) { if (dir != null) jobs.extract(n, dir, true, resolver()) }
        items += TItem(ctx.getString(R.string.tedit_export), TIcon.Glyph.EXPORT, enabled = !n.isDir, reason = ctx.getString(R.string.tedit_why_export_folder)) { export(n) }
        items += TItem(ctx.getString(R.string.tedit_import), TIcon.Glyph.IMPORT, enabled = canWriteDir, reason = noWrite) { importInto(dir) }
        items += TItem(ctx.getString(R.string.tedit_copy_path), TIcon.Glyph.COPY, divider = true) { copyText(n.name, n.backend.displayPath(n)) }
        items += TItem(ctx.getString(R.string.tedit_copy_rel_path)) { copyText(n.name, relPath(n)) }
        items += TItem(ctx.getString(R.string.tedit_select_many), TIcon.Glyph.CHECK) { startSelecting(n) }
        items += TItem(ctx.getString(R.string.tedit_properties), TIcon.Glyph.INFO) { info(n) }
        items += TItem(ctx.getString(R.string.tedit_delete), TIcon.Glyph.TRASH, "Del", enabled = !isRoot && n.can(Caps.DELETE), danger = true, divider = true,
            reason = ctx.getString(if (isRoot) R.string.tedit_why_root else R.string.tedit_why_no_delete)) { delete(listOf(n)) }
        tool.overlays.menu(anchor, items, title = n.name)
    }

    private fun relPath(n: FsNode): String {
        val base = tool.workspace?.source?.ref?.trimEnd('/') ?: return n.name
        return n.ref.removePrefix("$base/").ifEmpty { "." }
    }

    private fun copyText(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        tool.overlays.snack(ctx.getString(R.string.tedit_path_copied))
    }

    private fun openToSide(n: FsNode) {
        if (tool.groups.size < 2) tool.split(null)
        val other = tool.groups.lastOrNull() ?: return
        tool.open(DocSource.of(n), into = other)
    }

    private fun newIn(dir: FsNode?, folder: Boolean) {
        val d = dir ?: return
        if (!d.can(Caps.CREATE)) { tool.overlays.snack(ctx.getString(R.string.tedit_why_folder_read_only)); return }
        tool.overlays.prompt(ctx.getString(if (folder) R.string.tedit_new_folder else R.string.tedit_new_file), "",
            ctx.getString(if (folder) R.string.tedit_folder_name else R.string.tedit_file_name), ctx.getString(R.string.tedit_create),
            validate = { name -> tool.nameProblem(name) ?: if (children[d.key]?.any { it.name == name } == true) ctx.getString(R.string.tedit_err_exists) else null }) { name ->
            io.execute {
                val r = runCatching {
                    if (folder) d.backend.createDir(d, name)
                    else d.backend.createFile(d, name, tool.mimeOf(name) ?: "text/plain")
                }
                main.post {
                    r.onSuccess { made ->
                        expanded += d.key
                        load(d)
                        if (!folder) tool.openNode(made)
                    }.onFailure { tool.overlays.message(ctx.getString(R.string.tedit_create_failed, name), tool.store.errorText(it)) }
                }
            }
        }
    }

    private fun rename(n: FsNode) {
        tool.overlays.prompt(ctx.getString(R.string.tedit_rename), n.name, ctx.getString(R.string.tedit_new_name), ctx.getString(R.string.tedit_rename),
            selectStem = !n.isDir, validate = { tool.nameProblem(it) }) { name ->
            if (name == n.name) return@prompt
            io.execute {
                val r = runCatching { n.backend.rename(n, name) }
                main.post {
                    r.onSuccess { renamed ->
                        // Open documents follow the file to its new name.
                        val oldPrefix = n.ref
                        for (d in tool.store.docs.values.toList()) {
                            val src = d.source as? DocSource.Node ?: continue
                            if (src.backendKey == n.backend.key && (src.ref == oldPrefix || src.ref.startsWith("$oldPrefix/"))) {
                                tool.store.docs.remove(d.key)
                                d.source = DocSource.Node(src.backendKey, renamed.ref + src.ref.removePrefix(oldPrefix))
                                if (src.ref == oldPrefix) d.name = renamed.name
                                tool.store.docs[d.key] = d
                                d.changed()
                            }
                        }
                        tool.groups.forEach { it.refreshTabs() }
                        refresh()
                        tool.git.refreshSoon()
                    }.onFailure { tool.overlays.message(ctx.getString(R.string.tedit_rename_failed, n.name), tool.store.errorText(it)) }
                }
            }
        }
    }

    private fun duplicate(n: FsNode) {
        val dir = parentOf(n) ?: return
        jobs.copy(listOf(n), dir, false, ConflictResolver { ConflictChoice.KEEP_BOTH to true })
        tool.overlays.snack(ctx.getString(R.string.tedit_duplicating, n.name))
    }

    private fun paste(c: Pair<List<FsNode>, Boolean>, dir: FsNode) {
        val (nodes, move) = c
        if (nodes.any { it.isDir && (dir.key == it.key || dir.ref.startsWith(it.ref.trimEnd('/') + "/")) && it.backend.key == dir.backend.key }) {
            tool.overlays.snack(ctx.getString(R.string.tedit_paste_into_self)); return
        }
        jobs.copy(nodes, dir, move, resolver())
        if (move) clip = null
        expanded += dir.key
        tool.overlays.snack(ctx.resources.getQuantityString(if (move) R.plurals.tedit_moving else R.plurals.tedit_copying, nodes.size, nodes.size))
    }

    private fun compress(nodes: List<FsNode>) {
        val dir = parentOf(nodes.first()) ?: tool.workspace?.node ?: return
        val base = if (nodes.size == 1) nodes[0].name.substringBeforeLast('.').ifEmpty { nodes[0].name } else ctx.getString(R.string.tedit_archive_name)
        tool.overlays.prompt(ctx.getString(R.string.tedit_compress), "$base.zip", ctx.getString(R.string.tedit_file_name), ctx.getString(R.string.tedit_compress),
            selectStem = true, validate = { tool.nameProblem(it) }) { name ->
            val format = when { name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ArchiveFormat.TAR_GZ; name.endsWith(".tar") -> ArchiveFormat.TAR; else -> ArchiveFormat.ZIP }
            val stem = name.removeSuffix(".zip").removeSuffix(".tar.gz").removeSuffix(".tgz").removeSuffix(".tar")
            jobs.compress(nodes, dir, stem, format, resolver())
            endSelecting()
            tool.overlays.snack(ctx.getString(R.string.tedit_compressing, name))
        }
    }

    private fun delete(nodes: List<FsNode>) {
        val go = {
            jobs.delete(nodes)
            endSelecting()
            // Documents of deleted files stay open with their text, marked as unsaved, so nothing is lost silently.
            for (d in tool.store.docs.values) {
                val src = d.source as? DocSource.Node ?: continue
                if (nodes.any { n -> src.backendKey == n.backend.key && (src.ref == n.ref || src.ref.startsWith(n.ref + "/")) }) { d.forcedDirty = true; d.disk = null; d.changed() }
            }
        }
        if (!tool.prefs.bool(TeditPrefs.CONFIRM_DELETE)) { go(); return }
        val msg = if (nodes.size == 1) ctx.getString(if (nodes[0].isDir) R.string.tedit_delete_folder_msg else R.string.tedit_delete_file_msg, nodes[0].name)
        else ctx.resources.getQuantityString(R.plurals.tedit_delete_many_msg, nodes.size, nodes.size)
        tool.overlays.confirm(ctx.getString(R.string.tedit_delete), msg, ctx.getString(R.string.tedit_delete), danger = true) { go() }
    }

    private fun export(n: FsNode) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(tool.mimeOf(n.name) ?: "application/octet-stream").putExtra(Intent.EXTRA_TITLE, n.name)
        tool.requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            io.execute {
                val r = runCatching {
                    n.backend.openRead(n).use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { out -> input.copyTo(out, 256 * 1024) }
                        }
                    }
                }
                main.post { r.onSuccess { tool.overlays.snack(ctx.getString(R.string.tedit_exported, n.name)) }.onFailure { tool.overlays.message(ctx.getString(R.string.tedit_export), tool.store.errorText(it)) } }
            }
        }
    }

    private fun importInto(dir: FsNode?) {
        val d = dir ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        tool.requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            io.execute {
                val r = runCatching {
                    val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "imported"
                    if (d.backend.child(d, name) != null) throw com.lxlauncher.files.FsException(com.lxlauncher.files.FsException.Reason.EXISTS, name)
                    val made = d.backend.createFile(d, name, tool.mimeOf(name) ?: "application/octet-stream")
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val pfd = made.backend.openWrite(made)
                        java.io.FileOutputStream(pfd.fileDescriptor).use { out -> input.copyTo(out, 256 * 1024) }
                        pfd.close()
                        made.backend.commitWrite(made)
                    }
                    made
                }
                main.post {
                    r.onSuccess { expanded += d.key; load(d); tool.overlays.snack(ctx.getString(R.string.tedit_imported, it.name)) }
                        .onFailure { tool.overlays.message(ctx.getString(R.string.tedit_import), tool.store.errorText(it)) }
                }
            }
        }
    }

    private fun info(n: FsNode) {
        val lines = ArrayList<String>()
        lines += ctx.getString(R.string.tedit_info_location) + ": " + n.backend.displayPath(n)
        if (!n.isDir) lines += ctx.getString(R.string.tedit_info_size) + ": " + Formatter.formatFileSize(ctx, maxOf(0, n.size))
        if (n.modified > 0) lines += ctx.getString(R.string.tedit_info_modified) + ": " + DateFormat.getMediumDateFormat(ctx).format(n.modified) + " " + DateFormat.getTimeFormat(ctx).format(n.modified)
        if (!n.isDir) lines += ctx.getString(R.string.tedit_info_type) + ": " + (tool.mimeOf(n.name) ?: n.mime.ifEmpty { "?" })
        lines += ctx.getString(R.string.tedit_info_access) + ": " + ctx.getString(if (n.can(Caps.WRITE) || n.can(Caps.CREATE)) R.string.tedit_access_rw else R.string.tedit_access_ro)
        tool.overlays.message(n.name, lines.joinToString("\n"))
        if (n.isDir) io.execute {
            // Count what the folder holds, up to a limit, without blocking the dialog.
            var files = 0
            var bytes = 0L
            fun walk(d: FsNode, depth: Int) {
                if (depth > 20 || files > 20000 || disposed) return
                for (k in runCatching { d.backend.children(d) }.getOrDefault(emptyList())) { if (k.isDir) walk(k, depth + 1) else { files++; bytes += maxOf(0, k.size) } }
            }
            walk(n, 0)
            main.post { if (!disposed) tool.overlays.snack(ctx.resources.getQuantityString(R.plurals.tedit_folder_holds, files, files, Formatter.formatFileSize(ctx, bytes))) }
        }
    }

    /** Asks on the main thread when a copy meets a name that exists; the job's thread waits for the answer. */
    private fun resolver() = object : ConflictResolver {
        override fun resolve(conflict: Conflict): Pair<ConflictChoice, Boolean> {
            val latch = CountDownLatch(1)
            var answer = ConflictChoice.CANCEL to false
            main.post {
                val opts = listOf<CharSequence>(ctx.getString(R.string.tedit_conflict_replace), ctx.getString(R.string.tedit_conflict_keep_both),
                    ctx.getString(R.string.tedit_conflict_skip), ctx.getString(R.string.tedit_conflict_cancel))
                tool.overlays.choose(ctx.getString(R.string.tedit_conflict_title, conflict.name), opts, -1) { i ->
                    answer = when (i) { 0 -> ConflictChoice.REPLACE; 1 -> ConflictChoice.KEEP_BOTH; 2 -> ConflictChoice.SKIP; else -> ConflictChoice.CANCEL } to false
                    latch.countDown()
                }
            }
            // A dialog closed without a choice cancels after a while instead of holding the job forever.
            if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) return ConflictChoice.CANCEL to true
            return answer
        }
    }

    override fun dispose() {
        disposed = true
        jobs.removeListener(jobListener)
        tool.git.removeListener(gitListener)
        io.shutdownNow()
    }

    private inner class TreeAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].node.key.hashCode().toLong()
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = rows[position]
            val n = row.node
            val v = (convertView as? LinearLayout) ?: ui.row().apply {
                minimumHeight = t.rowH
                isFocusable = true
                addView(View(ctx), ui.lp(0, 1))
                addView(android.widget.ImageView(ctx), ui.lp(t.dpi(14f), t.dpi(14f)).apply { marginEnd = t.dpi(2f) })
                addView(android.widget.ImageView(ctx), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(6f) })
                addView(ui.text("", t.textBody, t.text), ui.lp(0, TeditTool.WRAP, 1f))
                addView(ui.text("", t.textCaption, t.textMuted).apply { gravity = Gravity.CENTER; setPadding(t.dpi(4f), 0, 0, 0) }, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginEnd = t.dpi(8f) })
            }
            val isRoot = n.key == tool.workspace?.node?.key
            val sel = selected.containsKey(n.key)
            val active = n.key == activeKey
            // The open file's row is a rounded tile set in from the panel's edges, as in the reference.
            val tile = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = t.dp(8f)
                setColor(if (sel || active) TeditTheme.withAlpha(t.accentChoice.color, if (t.dark) 0.24f else 0.16f) else 0)
            }
            v.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.InsetDrawable(tile, t.dpi(6f), t.dpi(2f), t.dpi(6f), t.dpi(2f)), ui.states(t.dp(8f))))
            v.setPadding(0, 0, 0, 0)
            v.getChildAt(0).layoutParams = ui.lp(t.dpi(6f) + t.dpi(10f) * row.depth, 1)
            val chevron = v.getChildAt(1) as android.widget.ImageView
            val icon = v.getChildAt(2) as android.widget.ImageView
            val label = v.getChildAt(3) as TextView
            val badge = v.getChildAt(4) as TextView
            val open = n.key in expanded || (filter.isNotEmpty() && n.isDir)
            chevron.setImageDrawable(if (n.isDir) TIcon(if (open) TIcon.Glyph.CHEVRON_DOWN else TIcon.Glyph.CHEVRON_RIGHT, t.text2) else null)
            icon.setImageDrawable(when {
                selecting && sel -> TIcon(TIcon.Glyph.CHECK, t.accentText)
                n.isDir -> FileIcon.folder(open)
                else -> FileIcon.forName(n.name, t.text2)
            })
            label.text = if (isRoot) tool.workspace?.name ?: n.name else n.name
            label.setTextColor(t.text)
            label.typeface = if (isRoot) t.uiFontMedium else t.uiFont
            val doc = tool.store.docs.values.firstOrNull { (it.source as? DocSource.Node)?.let { s -> s.backendKey == n.backend.key && s.ref == n.ref } == true }
            val change = if (!n.isDir && n.backend.key == "local") tool.git.repo?.let { r -> n.ref.removePrefix(r.workTree.absolutePath.trimEnd('/') + "/").let { tool.git.changeOf(it) } } else null
            when {
                loadingDirs.contains(n.key) -> { badge.text = "…"; badge.setTextColor(t.textMuted) }
                failed.containsKey(n.key) && n.isDir -> { badge.text = "!"; badge.setTextColor(t.error) }
                doc?.dirty == true -> { badge.text = "●"; badge.setTextColor(t.warning) }
                change != null -> {
                    badge.text = when (change) { GitRepo.Change.ADDED -> "A"; GitRepo.Change.MODIFIED -> "M"; GitRepo.Change.DELETED -> "D"; GitRepo.Change.UNTRACKED -> "U" }
                    badge.setTextColor(when (change) { GitRepo.Change.MODIFIED -> t.warning; GitRepo.Change.DELETED -> t.error; else -> t.success })
                }
                else -> badge.text = ""
            }
            badge.visibility = if (badge.text.isEmpty()) View.GONE else View.VISIBLE
            v.contentDescription = buildString {
                append(label.text)
                if (n.isDir) append(", ").append(ctx.getString(if (open) R.string.tedit_expanded else R.string.tedit_collapsed))
                if (badge.text.isNotEmpty()) append(", ").append(badge.text)
            }
            v.setOnClickListener { tapped(n) }
            v.setOnLongClickListener { if (selecting) toggleSelect(n) else contextMenu(n, it); true }
            return v
        }
    }
}

/** Picks a folder by walking down from [start]: for Save As and Open Folder. */
class FolderPicker(private val tool: TeditTool, start: FsNode, private val title: CharSequence, private val onPick: (FsNode) -> Unit) {
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())
    private val stack = ArrayList<FsNode>().apply { add(start) }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val path = ui.text("", t.textCaption, t.textMuted, lines = 2)
    private var close: () -> Unit = {}

    fun show() {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        col.addView(path, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { bottomMargin = t.dpi(6f) })
        val scroll = ui.scroll(list).apply { layoutParams = ui.lp(TeditTool.MATCH, (tool.view.height * 0.45f).toInt().coerceAtLeast(t.dpi(200f))) }
        col.addView(scroll)
        val cancel = ui.button(ctx.getString(R.string.tedit_cancel)) { close() }
        val pick = ui.button(ctx.getString(R.string.tedit_use_folder), primary = true) { close(); onPick(stack.last()) }
        close = tool.overlays.dialog(title, col, listOf(cancel to false, pick to true))
        render()
    }

    private fun render() {
        val dir = stack.last()
        path.text = dir.backend.displayPath(dir)
        list.removeAllViews()
        list.addView(ui.note(ctx.getString(R.string.tedit_loading)))
        Thread {
            val kids = runCatching { dir.backend.children(dir).filter { it.isDir && !it.hidden }.sortedBy { it.name.lowercase() } }
            main.post {
                list.removeAllViews()
                if (stack.size > 1) list.addView(entry("..", TIcon.Glyph.ARROW_UP) { stack.removeAt(stack.size - 1); render() })
                kids.onSuccess { folders ->
                    if (folders.isEmpty()) list.addView(ui.note(ctx.getString(R.string.tedit_no_subfolders)))
                    for (f in folders) list.addView(entry(f.name, TIcon.Glyph.FOLDER) { stack += f; render() })
                }.onFailure { list.addView(ui.note(tool.store.errorText(it), t.error)) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun entry(label: String, glyph: TIcon.Glyph, onClick: () -> Unit): View {
        val r = ui.row().apply { minimumHeight = t.touch; background = ui.states(); isFocusable = true; contentDescription = label; setPadding(t.dpi(6f), 0, t.dpi(6f), 0) }
        r.addView(ui.icon(glyph, t.text2, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(10f) })
        r.addView(ui.text(label, t.textBody, t.text), ui.lp(0, TeditTool.WRAP, 1f))
        r.setOnClickListener { onClick() }
        return r
    }
}
