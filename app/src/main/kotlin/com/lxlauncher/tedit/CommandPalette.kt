// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.files.FsNode
import org.json.JSONArray

/**
 * The command palette. Typing ">" lists commands, ":" goes to a line, anything else finds files in the workspace and
 * the open editors. Commands run recently come first.
 */
class CommandPalette(private val tool: TeditTool, private val initial: String) {
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())

    private class Entry(val label: String, val detail: String?, val hint: String?, val glyph: TIcon.Glyph?, val enabled: Boolean, val run: () -> Unit)

    private var entries = emptyList<Entry>()
    private var selected = 0
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var close: () -> Unit = {}

    fun show() {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val field = ui.field(ctx.getString(R.string.tedit_palette_hint), initial)
        col.addView(field, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(ctx).apply { addView(list) }
        col.addView(scroll, ui.lp(TeditTool.MATCH, (tool.view.height * 0.5f).toInt().coerceAtLeast(t.dpi(220f))).apply { topMargin = t.dpi(8f) })
        close = tool.overlays.dialog(ctx.getString(R.string.tedit_command_palette), col, emptyList())
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { update(s?.toString() ?: "") }
        })
        field.setOnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { move(1); true }
                KeyEvent.KEYCODE_DPAD_UP -> { move(-1); true }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { pick(selected); true }
                else -> false
            }
        }
        field.setOnEditorActionListener { _, _, _ -> pick(selected); true }
        field.setSelection(field.text.length)
        TeditUi.showKeyboard(field)
        update(initial)
        if (tool.workspace != null && FileIndex.forRoot(tool) == null) FileIndex.build(tool) { main.post { update(field.text.toString()) } }
    }

    private fun move(d: Int) {
        if (entries.isEmpty()) return
        selected = (selected + d).coerceIn(0, entries.size - 1)
        renderSelection()
    }

    private fun pick(i: Int) {
        val e = entries.getOrNull(i) ?: return
        if (!e.enabled) { tool.overlays.snack(e.detail ?: ""); return }
        close()
        e.run()
    }

    private fun update(q: String) {
        entries = when {
            q.startsWith(">") -> commands(q.substring(1).trim())
            q.startsWith(":") -> gotoLine(q.substring(1).trim())
            else -> files(q.trim()) + if (q.isBlank()) listOf(Entry(ctx.getString(R.string.tedit_palette_more), null, ">", TIcon.Glyph.COMMAND, true) { CommandPalette(tool, ">").show() }) else emptyList()
        }
        selected = 0
        render()
    }

    private fun history(): List<String> = runCatching { JSONArray(tool.prefs.raw("cmdHistory") ?: "[]") }.getOrNull()?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    private fun remember(id: String) {
        val list = listOf(id) + history().filter { it != id }.take(9)
        tool.prefs.putRaw("cmdHistory", JSONArray(list).toString())
    }

    private fun commands(q: String): List<Entry> {
        val hist = history()
        val cmds = tool.commands.all.filter { it.id != "run.debug" || q.isNotEmpty() }
        val scored = cmds.mapNotNull { c ->
            val label = ctx.getString(c.title)
            val score = if (q.isEmpty()) 0 else fuzzy(q, label) ?: return@mapNotNull null
            Triple(c, label, score - (if (c.id in hist) 50 - hist.indexOf(c.id) * 4 else 0))
        }.sortedWith(compareBy({ if (q.isEmpty()) (hist.indexOf(it.first.id).takeIf { i -> i >= 0 } ?: 100) else 0 }, { it.third }, { it.second }))
        // One row per command: a command bound to two keys is still one command.
        return scored.distinctBy { it.first.id }.map { (c, label, _) ->
            val why = c.unavailable()
            Entry(label, if (why != 0) ctx.getString(why) else if (c.id in hist && q.isEmpty()) ctx.getString(R.string.tedit_recently_used) else null, c.shortcut, c.glyph, why == 0) {
                remember(c.id)
                tool.runCommand(c.id)
            }
        }
    }

    private fun gotoLine(q: String): List<Entry> {
        val g = tool.activeGroup
        val d = g?.active?.doc
        if (d == null || d.loading || d.binary || d.image) return listOf(Entry(ctx.getString(R.string.tedit_why_no_editor), null, null, TIcon.Glyph.GOTO, false) {})
        val line = q.split(':', ',')[0].trim().toIntOrNull()
        val label = if (line == null) ctx.getString(R.string.tedit_goto_hint, d.buffer.lineCount) else ctx.getString(R.string.tedit_goto_n, line)
        return listOf(Entry(label, null, null, TIcon.Glyph.GOTO, line != null && line >= 1) {
            val col = q.split(':', ',').getOrNull(1)?.trim()?.toIntOrNull() ?: 1
            g.editor.goToLine((line ?: 1) - 1, col - 1)
            tool.focusEditor(g)
        })
    }

    private fun files(q: String): List<Entry> {
        val out = ArrayList<Pair<Entry, Int>>()
        val seen = HashSet<String>()
        for (d in tool.store.docs.values) {
            val score = if (q.isEmpty()) 0 else fuzzy(q, d.name) ?: continue
            seen += d.key
            out += Entry(d.name, ctx.getString(R.string.tedit_palette_open), null, TIcon.Glyph.FILE, true) {
                val grp = tool.groups.firstOrNull { gg -> gg.tabs.any { it.doc === d } } ?: tool.activeGroup ?: return@Entry
                grp.add(d, activate = true); tool.activateGroup(grp); tool.focusEditor(grp)
            } to score - 30
        }
        val index = FileIndex.forRoot(tool)
        if (index != null) {
            for ((rel, node) in index.files) {
                if (out.size > 400) break
                val key = DocSource.of(node).key
                if (key in seen) continue
                val score = if (q.isEmpty()) continue else fuzzy(q, rel) ?: continue
                out += Entry(node.name, rel.substringBeforeLast('/', ""), null, TIcon.Glyph.FILE, true) { tool.openNode(node) } to score
            }
        } else if (tool.workspace != null && q.isNotEmpty()) {
            out += Entry(ctx.getString(R.string.tedit_indexing), null, null, TIcon.Glyph.REFRESH, false) {} to 1000
        }
        if (q.isEmpty()) for (r in tool.store.recents("files").take(8)) {
            if (r.source.key in seen) continue
            out += Entry(r.name, r.path, null, TIcon.Glyph.HISTORY, true) { tool.open(r.source) } to 10
        }
        if (out.isEmpty()) return listOf(Entry(ctx.getString(if (tool.workspace == null && q.isNotEmpty()) R.string.tedit_palette_no_folder else R.string.tedit_no_results), null, null, null, false) {})
        return out.sortedBy { it.second }.take(60).map { it.first }
    }

    /** Lower is better; null when [q]'s letters do not all appear in order in [s]. */
    private fun fuzzy(q: String, s: String): Int? {
        val ql = q.lowercase()
        val sl = s.lowercase()
        val direct = sl.indexOf(ql)
        if (direct >= 0) return direct + (if (direct == 0 || !sl[direct - 1].isLetterOrDigit()) 0 else 10) + s.length / 8
        var i = 0
        var gaps = 0
        var last = -1
        for (c in ql) {
            if (c == ' ') continue
            val j = sl.indexOf(c, i)
            if (j < 0) return null
            if (last >= 0) gaps += j - last - 1
            last = j
            i = j + 1
        }
        return 40 + gaps * 2 + s.length / 6
    }

    private fun render() {
        list.removeAllViews()
        entries.forEachIndexed { i, e ->
            val row = ui.row().apply {
                minimumHeight = t.touch
                setPadding(t.dpi(8f), t.dpi(4f), t.dpi(8f), t.dpi(4f))
                isFocusable = true
                alpha = if (e.enabled) 1f else 0.6f
                contentDescription = e.label + (e.detail?.let { ", $it" } ?: "")
                setOnClickListener { pick(i) }
            }
            if (e.glyph != null) row.addView(ui.icon(e.glyph, t.text2, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(10f) })
            val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            labels.addView(ui.text(e.label, t.textBody, t.text))
            if (e.detail != null && e.detail.isNotEmpty()) labels.addView(ui.text(e.detail, t.textCaption, t.textMuted, lines = 2))
            row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            if (e.hint != null) row.addView(ui.text(e.hint, t.textCaption, t.textMuted).apply { typeface = t.codeFont; setPadding(t.dpi(8f), 0, 0, 0) })
            list.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        }
        renderSelection()
    }

    private fun renderSelection() {
        for (i in 0 until list.childCount) list.getChildAt(i).background = ui.states(selected = i == selected)
        list.getChildAt(selected)?.let { v -> scroll.post { scroll.requestChildRectangleOnScreen(list, android.graphics.Rect(0, v.top, v.width, v.bottom), false) } }
    }
}

/** Files of the workspace for Quick Open, walked once in the background and kept until the folder changes. */
class FileIndex(val rootKey: String, val files: List<Pair<String, FsNode>>) {
    companion object {
        private var current: FileIndex? = null
        private var building: String? = null

        fun forRoot(tool: TeditTool): FileIndex? = current?.takeIf { it.rootKey == tool.workspace?.node?.key }

        fun build(tool: TeditTool, done: () -> Unit) {
            val ws = tool.workspace ?: return
            if (building == ws.node.key) return
            building = ws.node.key
            val skip = setOf(".git", "node_modules", ".gradle", ".idea", "build", "__pycache__", ".svn", ".hg")
            Thread({
                val out = ArrayList<Pair<String, FsNode>>()
                val queue = ArrayDeque<Pair<FsNode, String>>()
                queue += ws.node to ""
                var dirs = 0
                while (queue.isNotEmpty() && out.size < 30000 && dirs < 8000) {
                    val (dir, rel) = queue.removeFirst()
                    dirs++
                    val kids = runCatching { dir.backend.children(dir) }.getOrNull() ?: continue
                    for (k in kids) {
                        val path = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                        if (k.isDir) { if (k.name !in skip) queue += k to path } else out += path to k
                    }
                }
                current = FileIndex(ws.node.key, out)
                building = null
                done()
            }, "tedit-index").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }

        fun invalidate() { current = null }
    }
}

/** The floating bar over a touch selection: Cut, Copy, Paste, Select all, and the full menu. */
object SelectionBar {
    private const val TAG = "tedit-selection-bar"

    fun show(tool: TeditTool, editor: CodeEditView, x: Float, top: Float, bottom: Float) {
        hide(tool)
        val t = tool.theme
        val ui = tool.ui
        val ctx = tool.ctx
        val host = tool.floatLayer
        val bar = ui.row().apply {
            tag = TAG
            background = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.active); setStroke(t.stroke, t.border) }
            elevation = t.dp(10f)
            setPadding(t.dpi(4f), 0, t.dpi(4f), 0)
        }
        fun action(label: Int, enabled: Boolean, run: () -> Unit) {
            bar.addView(ui.text(ctx.getString(label), t.textSmall, if (enabled) t.text else t.textMuted, medium = true).apply {
                gravity = Gravity.CENTER
                minHeight = t.touch
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                background = ui.states()
                isFocusable = true
                isEnabled = enabled
                setOnClickListener { run() }
            })
        }
        val ro = editor.doc?.readOnly == true
        action(R.string.tedit_cut, !ro) { editor.cut(); hide(tool) }
        action(R.string.tedit_copy, true) { editor.copy(); hide(tool); tool.overlays.snack(ctx.getString(R.string.tedit_copied)) }
        action(R.string.tedit_paste, !ro && editor.canPaste()) { editor.paste(); hide(tool) }
        action(R.string.tedit_select_all, true) { editor.selectAll() }
        bar.addView(ui.iconButton(TIcon.Glyph.MORE_H, ctx.getString(R.string.tedit_more), t.text2) { hide(tool); tool.editorMenu(editor, x, bottom) })
        val loc = IntArray(2)
        val hostLoc = IntArray(2)
        editor.getLocationInWindow(loc)
        host.getLocationInWindow(hostLoc)
        val ox = loc[0] - hostLoc[0]
        val oy = loc[1] - hostLoc[1]
        host.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.visibility = View.INVISIBLE
        bar.post {
            val w = bar.width
            val h = bar.height
            val left = (ox + x - w / 2f).coerceIn(t.dp(6f), (host.width - w - t.dp(6f)).coerceAtLeast(t.dp(6f)))
            var topY = oy + top - h - t.dp(8f)
            if (topY < oy) topY = oy + bottom + t.dp(8f)
            bar.translationX = left
            bar.translationY = topY.coerceIn(t.dp(4f), (host.height - h - t.dp(4f)).coerceAtLeast(t.dp(4f)))
            bar.visibility = View.VISIBLE
        }
    }

    /** True when a bar was showing. */
    fun hide(tool: TeditTool): Boolean {
        val host = tool.floatLayer
        val bar = host.findViewWithTag<View>(TAG) ?: return false
        host.removeView(bar)
        return true
    }
}
