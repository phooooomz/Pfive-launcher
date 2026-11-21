// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.tedit.ext.ExtensionStore
import com.lxlauncher.tedit.ext.Snippet
import com.lxlauncher.tedit.lsp.CompletionItem
import com.lxlauncher.tedit.lsp.TextEdit

/**
 * Suggestions as the person types, as VS Code shows them: the language server's items, the snippets of installed
 * extensions, and words already in the file. The list floats under the cursor without taking the keyboard; a tap,
 * Enter or Tab inserts, the arrows move, Escape closes.
 */
class CompletionPopup(private val tool: TeditTool) {
    private sealed class Entry(val label: String, val detail: String?, val kind: Int) {
        class Lsp(val item: CompletionItem) : Entry(item.label, item.detail, item.kind ?: 1)
        class Snip(val snippet: Snippet, prefix: String) : Entry(prefix, snippet.description ?: snippet.name, 15)
        class Word(word: String) : Entry(word, null, 1)
    }

    private var editor: CodeEditView? = null
    private var start: Pos? = null
    private var prefix = ""
    private var lspItems: List<CompletionItem> = emptyList()
    private val entries = ArrayList<Entry>()
    private var selected = 0
    private var gen = 0
    private var box: FrameLayout? = null
    private var rows: LinearLayout? = null
    private var scroll: ScrollView? = null

    /** One row of the list, kept and filled again as the person types rather than built for every key. */
    private class Row(val view: LinearLayout, val badge: TextView, val label: TextView, val detail: TextView)
    private val pool = ArrayList<Row>()
    private var rowWidth = 0

    // Words of the lines around the cursor and how often each appears, counted once and filtered on every key.
    private var wordsDoc: Document? = null
    private var wordsLine = -1
    private var wordsVersion = -1
    private var wordCounts: Map<String, Int> = emptyMap()

    val showing get() = box != null

    /** After each edit: suggest for the word being typed, or after a character the server asked to be told about. */
    /** The edit that inserts an accepted suggestion does not open the list again. */
    private var justAccepted = false

    fun edited(v: CodeEditView) {
        if (justAccepted) { justAccepted = false; return }
        if (!tool.prefs.bool(TeditPrefs.SUGGEST) || !v.lastEditTyped || !v.hasFocus()) { hide(); return }
        val d = v.doc ?: return
        val s = v.session ?: return
        if (s.hasSelection || d.readOnly) { hide(); return }
        val (wStart, word) = v.wordBeforeCaret() ?: return
        val line = d.buffer.line(s.caret.line)
        val prev = if (s.caret.col in 1..line.length) line[s.caret.col - 1].toString() else ""
        val trigger = prev.takeIf { it in TRIGGERS }
        if (word.isEmpty() && trigger == null) { hide(); return }
        if (word.isNotEmpty() && word.first().isDigit()) { hide(); return }
        query(v, wStart, word, trigger)
    }

    /** Ctrl+Space or the menu: suggest even with nothing typed yet. */
    fun trigger(v: CodeEditView) {
        val (wStart, word) = v.wordBeforeCaret() ?: return
        query(v, wStart, word, null)
    }

    /** The cursor moved without typing: the list stays only while the cursor is still in the word. */
    fun caretMoved(v: CodeEditView) {
        if (!showing || v !== editor) return
        val s = v.session ?: return hide()
        val st = start ?: return hide()
        if (s.caret.line != st.line || s.caret.col < st.col) hide()
    }

    private fun query(v: CodeEditView, wStart: Pos, word: String, trigger: String?) {
        val d = v.doc ?: return
        val s = v.session ?: return
        val sameWord = editor === v && start == wStart
        editor = v
        start = wStart
        prefix = word
        if (!sameWord) lspItems = emptyList()
        val g = ++gen
        if (tool.lsp.available(d)) {
            tool.lsp.completion(d, s.caret, trigger) { items ->
                if (g != gen || editor !== v) return@completion
                lspItems = items
                rebuild()
            }
        }
        rebuild()
    }

    private fun matches(text: String) = prefix.isEmpty() || text.startsWith(prefix, ignoreCase = true) ||
        (prefix.length >= 2 && text.contains(prefix, ignoreCase = true))

    private fun rebuild() {
        val v = editor ?: return
        val d = v.doc ?: return hide()
        entries.clear()
        val seen = HashSet<String>()
        for (it in lspItems.filter { i -> matches(i.filterText ?: i.label) }.sortedBy { i -> (i.sortText ?: i.label).lowercase() }.take(40)) {
            if (seen.add(it.label)) entries += Entry.Lsp(it)
        }
        if (prefix.isNotEmpty()) {
            for (sn in ExtensionStore.get(tool.ctx).snippetsFor(d.language.id)) {
                val p = sn.prefixes.firstOrNull { it.startsWith(prefix, ignoreCase = true) } ?: continue
                if (entries.size < MAX_ENTRIES) entries += Entry.Snip(sn, p)
            }
        }
        // Words already in the file, when the server has nothing (or there is no server).
        if (prefix.length >= 2 && entries.size < 8) {
            for (w in words(d)) if (seen.add(w)) { entries += Entry.Word(w); if (entries.size >= 20) break }
        }
        if (entries.isEmpty()) { hide(); return }
        selected = 0
        render()
    }

    private fun words(d: Document): List<String> {
        val s = editor?.session ?: return emptyList()
        val line = s.caret.line
        // Counted again only when the cursor moved well away or the file changed a lot; a word being typed is not
        // worth recounting 800 lines for on every key.
        if (wordsDoc !== d || kotlin.math.abs(line - wordsLine) > 100 || d.buffer.version - wordsVersion !in 0..WORDS_EDITS) {
            val counts = HashMap<String, Int>()
            val from = maxOf(0, line - 400)
            val to = minOf(d.buffer.lineCount, line + 400)
            for (i in from until to) for (m in WORD.findAll(d.buffer.line(i))) counts[m.value] = (counts[m.value] ?: 0) + 1
            wordCounts = counts
            wordsDoc = d
            wordsLine = line
            wordsVersion = d.buffer.version
        }
        val out = ArrayList<Map.Entry<String, Int>>()
        for (e in wordCounts.entries) if (e.key.length > prefix.length && e.key.startsWith(prefix, ignoreCase = true)) out += e
        return out.sortedByDescending { it.value }.take(20).map { it.key }
    }

    // ---- the list ----

    private fun render() {
        val v = editor ?: return
        val t = tool.theme
        val ui = tool.ui
        val host = tool.floatLayer
        if (box == null) {
            val r = LinearLayout(tool.ctx).apply { orientation = LinearLayout.VERTICAL }
            val sc = ScrollView(tool.ctx).apply { isVerticalScrollBarEnabled = true; addView(r) }
            val b = FrameLayout(tool.ctx).apply {
                background = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.panel); setStroke(t.stroke, t.border) }
                elevation = t.dp(10f)
                setPadding(t.stroke, t.stroke, t.stroke, t.stroke)
                addView(sc)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = tool.ctx.getString(R.string.tedit_suggestions)
            }
            host.addView(b, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            box = b; rows = r; scroll = sc
            v.keyInterceptor = { code, e -> key(code, e) }
            v.onBlur = { hide() }
        }
        val r = rows ?: return
        val rowH = t.dpi(30f)
        val width = minOf(t.dpi(320f), v.width - t.dpi(16f)).coerceAtLeast(t.dpi(180f))
        if (width != rowWidth) {
            rowWidth = width
            for (row in pool) { row.view.layoutParams = ui.lp(width, rowH); row.detail.maxWidth = width / 2 }
        }
        while (pool.size < entries.size) {
            val i = pool.size
            val view = ui.row().apply {
                setPadding(t.dpi(6f), 0, t.dpi(8f), 0)
                setOnClickListener { accept(i) }
            }
            val badge = TextView(tool.ctx).apply {
                textSize = 11f
                typeface = t.codeFont
                gravity = Gravity.CENTER
            }
            val label = ui.text("", t.textSmall, t.text).apply { typeface = t.codeFont }
            val detail = ui.text("", t.textCaption, t.textMuted).apply { maxWidth = width / 2; ellipsize = TextUtils.TruncateAt.END }
            view.addView(badge, ui.lp(t.dpi(18f), TeditTool.MATCH).apply { marginEnd = t.dpi(6f) })
            view.addView(label, ui.lp(0, TeditTool.WRAP, 1f))
            view.addView(detail)
            r.addView(view, ui.lp(width, rowH))
            pool += Row(view, badge, label, detail)
        }
        for ((i, row) in pool.withIndex()) {
            val e = entries.getOrNull(i)
            row.view.visibility = if (e == null) View.GONE else View.VISIBLE
            if (e == null) continue
            row.view.background = ui.states(0f, selected = i == selected)
            val (letter, color) = badge(e.kind)
            row.badge.text = letter
            row.badge.setTextColor(color)
            val label = SpannableString(e.label)
            val at = e.label.indexOf(prefix, ignoreCase = true)
            if (prefix.isNotEmpty() && at >= 0) label.setSpan(StyleSpan(android.graphics.Typeface.BOLD), at, at + prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.label.text = label
            row.detail.text = e.detail ?: ""
            row.detail.visibility = if (e.detail == null) View.GONE else View.VISIBLE
        }
        scroll?.scrollTo(0, 0)
        place(minOf(entries.size, 7) * rowH + 2 * t.stroke, width)
    }

    private fun place(h: Int, w: Int) {
        val v = editor ?: return
        val b = box ?: return
        val caret = v.caretBox() ?: return
        val loc = IntArray(2)
        val hostLoc = IntArray(2)
        v.getLocationInWindow(loc)
        tool.floatLayer.getLocationInWindow(hostLoc)
        val ox = loc[0] - hostLoc[0]
        val oy = loc[1] - hostLoc[1]
        val t = tool.theme
        val hostH = tool.floatLayer.height
        b.layoutParams = (b.layoutParams as FrameLayout.LayoutParams).apply { height = h; width = w + 2 * t.stroke }
        val left = (ox + caret[0] - t.dp(24f)).coerceIn(t.dp(4f), (tool.floatLayer.width - w - t.dp(4f)).coerceAtLeast(t.dp(4f)))
        // Under the cursor's line, or above it when the keyboard leaves no room below.
        var top = oy + caret[2] + t.dp(2f)
        if (top + h > hostH - t.dp(4f)) top = oy + caret[1] - h - t.dp(2f)
        b.translationX = left
        b.translationY = top.coerceAtLeast(t.dp(4f))
    }

    private fun badge(kind: Int): Pair<String, Int> {
        val s = tool.theme.syntax
        return when (kind) {
            2, 3, 4 -> "ƒ" to s[Token.FUNCTION]
            5, 6, 10, 18 -> "v" to s[Token.VARIABLE]
            7, 8, 13, 22, 25 -> "C" to s[Token.TYPE]
            9, 17, 19 -> "M" to s[Token.TYPE]
            14 -> "K" to s[Token.KEYWORD]
            15 -> "S" to s[Token.STRING]
            12, 20, 21 -> "c" to s[Token.CONSTANT]
            24 -> "±" to s[Token.OPERATOR]
            else -> "w" to tool.theme.textMuted
        }
    }

    private fun select(i: Int) {
        if (entries.isEmpty()) return
        selected = (i + entries.size) % entries.size
        val r = rows ?: return
        for (k in 0 until r.childCount) r.getChildAt(k).background = tool.ui.states(0f, selected = k == selected)
        r.getChildAt(selected)?.let { child -> scroll?.post { scroll?.smoothScrollTo(0, (child.top - child.height * 2).coerceAtLeast(0)) } }
    }

    private fun key(code: Int, e: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_DOWN -> { select(selected + 1); true }
        KeyEvent.KEYCODE_DPAD_UP -> { select(selected - 1); true }
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> if (e.isShiftPressed) false else { accept(selected); true }
        KeyEvent.KEYCODE_ESCAPE -> { hide(); true }
        else -> false
    }

    private fun accept(i: Int) {
        val v = editor ?: return
        val e = entries.getOrNull(i) ?: return
        val st = start ?: return
        val s = v.session ?: return
        val d = v.doc ?: return
        hide()
        justAccepted = true
        when (e) {
            is Entry.Lsp -> {
                val item = e.item
                val edit = item.textEdit
                val from = edit?.range?.start?.let { Pos(it.line, it.character) } ?: st
                val text = item.textToInsert
                // The extra edits (an import at the top) go first, from the bottom up, so the cursor's place holds.
                val extra = item.additionalTextEdits.filter { Pos(it.range.end.line, it.range.end.character) <= from }
                    .sortedWith(compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })
                var caret = s.caret
                var at = from
                for (x in extra) {
                    val a = d.buffer.clamp(Pos(x.range.start.line, x.range.start.character))
                    val b = d.buffer.clamp(Pos(x.range.end.line, x.range.end.character))
                    val beforeLen = d.buffer.length
                    val offCaret = d.buffer.offsetOf(caret)
                    val offAt = d.buffer.offsetOf(at)
                    d.history.apply(a, b, x.newText, s.anchor to s.caret, EditHistory.Kind.OTHER, android.os.SystemClock.uptimeMillis())
                    val delta = d.buffer.length - beforeLen
                    caret = d.buffer.posOf(offCaret + delta)
                    at = d.buffer.posOf(offAt + delta)
                }
                v.setSelection(caret, caret)
                if (item.insertTextFormat == 2) v.insertSnippet(text, from = at) else v.replace(at, caret, text)
            }
            is Entry.Snip -> v.insertSnippet(e.snippet.body, from = st)
            is Entry.Word -> v.replace(st, s.caret, e.label)
        }
        justAccepted = false
    }

    fun hide() {
        gen++
        box?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        box = null
        rows = null
        scroll = null
        pool.clear()
        rowWidth = 0
        editor?.keyInterceptor = null
        editor?.onBlur = null
    }

    companion object {
        private val WORD = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
        /** Rows the list holds at most: server items, snippets and words together. */
        private const val MAX_ENTRIES = 50
        /** Edits after which the word counts around the cursor are taken again. */
        private const val WORDS_EDITS = 200
        /** Characters after which servers usually have something to say. */
        private val TRIGGERS = setOf(".", ":", ">", "/", "@", "<", "\"", "'")
    }
}
