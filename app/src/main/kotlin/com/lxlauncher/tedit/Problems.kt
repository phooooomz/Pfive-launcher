// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Handler
import android.os.Looper
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.lxlauncher.R
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader
import java.util.concurrent.Executors

/** One problem found in a document. */
data class Problem(val docKey: String, val name: String, val line: Int, val col: Int, val error: Boolean, val message: String)

/**
 * Checks Tedit runs on its own, without any compiler: JSON and XML must parse, and brackets in code must pair up.
 * They run on the open documents a moment after typing stops.
 */
object Checks {
    fun run(lang: Language, lines: List<String>, ctx: android.content.Context): List<Triple<Int, Int, String>> = when (lang.id) {
        "json" -> json(lines, ctx)
        "xml", "html" -> if (lang.id == "xml") xml(lines, ctx) else emptyList()
        "plaintext", "markdown", "diff", "yaml", "toml" -> emptyList()
        else -> brackets(lang, lines, ctx)
    }

    /** A strict JSON reader (comments allowed, as in tsconfig and VS Code settings) that reports the first error. */
    fun json(lines: List<String>, ctx: android.content.Context): List<Triple<Int, Int, String>> {
        val text = lines.joinToString("\n")
        val p = JsonCheck(text, ctx)
        return try {
            p.ws(); if (p.i < text.length) p.value(0)
            p.ws()
            if (p.i < text.length) p.fail(ctx.getString(R.string.tedit_chk_json_trailing))
            emptyList()
        } catch (e: JsonCheck.Fail) {
            val (l, c) = p.where(e.at)
            listOf(Triple(l, c, e.message ?: ctx.getString(R.string.tedit_chk_json_bad)))
        }
    }

    private class JsonCheck(val s: String, val ctx: android.content.Context) {
        class Fail(msg: String, val at: Int) : Exception(msg)
        var i = 0
        fun fail(msg: String): Nothing = throw Fail(msg, i)
        fun fail(res: Int, vararg args: Any): Nothing = throw Fail(ctx.getString(res, *args), i)
        fun where(at: Int): Pair<Int, Int> {
            var line = 0
            var col = 0
            for (k in 0 until minOf(at, s.length)) if (s[k] == '\n') { line++; col = 0 } else col++
            return line to col
        }
        fun ws() {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> i++
                    s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                    s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2); if (e < 0) fail(R.string.tedit_chk_json_comment); i = e + 2 }
                    else -> return
                }
            }
        }
        fun value(depth: Int) {
            if (depth > 500) fail(R.string.tedit_chk_json_deep)
            if (i >= s.length) fail(R.string.tedit_chk_json_value)
            when (val c = s[i]) {
                '{' -> {
                    i++; ws()
                    if (i < s.length && s[i] == '}') { i++; return }
                    while (true) {
                        ws()
                        if (i >= s.length || s[i] != '"') fail(if (i < s.length && s[i] == '}') R.string.tedit_chk_json_trailing_comma else R.string.tedit_chk_json_key)
                        string(); ws()
                        if (i >= s.length || s[i] != ':') fail(R.string.tedit_chk_json_colon)
                        i++; ws(); value(depth + 1); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }
                        if (i < s.length && s[i] == '}') { i++; return }
                        fail(R.string.tedit_chk_json_comma_obj)
                    }
                }
                '[' -> {
                    i++; ws()
                    if (i < s.length && s[i] == ']') { i++; return }
                    while (true) {
                        ws()
                        if (i < s.length && s[i] == ']') fail(R.string.tedit_chk_json_trailing_comma)
                        value(depth + 1); ws()
                        if (i < s.length && s[i] == ',') { i++; continue }
                        if (i < s.length && s[i] == ']') { i++; return }
                        fail(R.string.tedit_chk_json_comma_arr)
                    }
                }
                '"' -> string()
                't' -> word("true")
                'f' -> word("false")
                'n' -> word("null")
                else -> if (c == '-' || c.isDigit()) number() else fail(R.string.tedit_chk_json_unexpected, c.toString())
            }
        }
        fun word(w: String) { if (!s.startsWith(w, i)) fail(R.string.tedit_chk_json_word); i += w.length }
        fun string() {
            i++
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { i++; return }
                if (c == '\n') fail(R.string.tedit_chk_json_string_line)
                if (c == '\\') {
                    i++
                    if (i >= s.length) break
                    if (s[i] == 'u') { if (i + 4 >= s.length || !s.substring(i + 1, i + 5).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) fail(R.string.tedit_chk_json_escape); i += 5; continue }
                    if (s[i] !in "\"\\/bfnrt") fail(R.string.tedit_chk_json_escape)
                }
                i++
            }
            fail(R.string.tedit_chk_json_string)
        }
        fun number() {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length || !s[i].isDigit()) fail(R.string.tedit_chk_json_number)
            if (s[i] == '0' && i + 1 < s.length && s[i + 1].isDigit()) fail(R.string.tedit_chk_json_zero)
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') { i++; if (i >= s.length || !s[i].isDigit()) fail(R.string.tedit_chk_json_number); while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) { i++; if (i < s.length && (s[i] == '+' || s[i] == '-')) i++; if (i >= s.length || !s[i].isDigit()) fail(R.string.tedit_chk_json_number); while (i < s.length && s[i].isDigit()) i++ }
            if (i == start) fail(R.string.tedit_chk_json_number)
        }
    }

    fun xml(lines: List<String>, ctx: android.content.Context): List<Triple<Int, Int, String>> {
        val text = lines.joinToString("\n")
        if (text.isBlank()) return emptyList()
        val p = Xml.newPullParser()
        return try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(StringReader(text))
            var guard = 0
            while (p.next() != XmlPullParser.END_DOCUMENT && guard++ < 2_000_000) Unit
            emptyList()
        } catch (e: XmlPullParserException) {
            val msg = e.message?.substringBefore(" (position")?.substringBefore("@")?.trim()?.ifEmpty { null } ?: ctx.getString(R.string.tedit_chk_xml_bad)
            listOf(Triple(maxOf(0, e.lineNumber - 1), maxOf(0, e.columnNumber - 1), msg))
        } catch (e: Exception) {
            listOf(Triple(0, 0, ctx.getString(R.string.tedit_chk_xml_bad)))
        }
    }

    /** Brackets outside strings and comments must close in order. */
    fun brackets(lang: Language, lines: List<String>, ctx: android.content.Context): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        val stack = ArrayList<Triple<Char, Int, Int>>()
        val sink = SpanSink()
        var state = lang.lexer.initialState
        val pairs = "()[]{}"
        for ((li, line) in lines.withIndex()) {
            if (line.length > 20000) continue
            sink.reset()
            val next = lang.lexer.lex(line, state, sink)
            // Mark string and comment ranges to skip.
            val skip = BooleanArray(line.length)
            var k = 0
            while (k < sink.size) {
                val kind = sink.data[k + 2]
                if (kind == Token.STRING || kind == Token.COMMENT || kind == Token.ANNOTATION) for (x in sink.data[k] until minOf(line.length, sink.data[k + 1])) skip[x] = true
                k += 3
            }
            state = next
            for ((ci, c) in line.withIndex()) {
                if (skip[ci]) continue
                val idx = pairs.indexOf(c)
                if (idx < 0) continue
                if (idx % 2 == 0) stack += Triple(c, li, ci)
                else {
                    val open = pairs[idx - 1]
                    val top = stack.lastOrNull()
                    if (top == null) { out += Triple(li, ci, ctx.getString(R.string.tedit_chk_unopened, c.toString())); if (out.size > 50) return out }
                    else if (top.first != open) {
                        out += Triple(li, ci, ctx.getString(R.string.tedit_chk_mismatch, c.toString(), top.first.toString(), top.second + 1))
                        stack.removeAt(stack.size - 1)
                        if (out.size > 50) return out
                    } else stack.removeAt(stack.size - 1)
                }
            }
        }
        for (o in stack.take(20)) out += Triple(o.second, o.third, ctx.getString(R.string.tedit_chk_unclosed, o.first.toString()))
        return out
    }
}

/** Problems of the open documents, recomputed in the background. */
class ProblemsModel(private val tool: TeditTool) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-checks").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    var problems: List<Problem> = emptyList()
        private set
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var gen = 0

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun counts(): Pair<Int, Int> = problems.count { it.error } to problems.count { !it.error }

    fun schedule() {
        main.removeCallbacks(job)
        main.postDelayed(job, 700)
    }

    private val job = Runnable { compute() }

    /** Checks found per document at a text version and language, so an edit rechecks only the document it touched. */
    private class Checked(val version: Int, val lang: String, val found: List<Triple<Int, Int, String>>)
    private val checked = HashMap<String, Checked>()

    private fun compute() {
        if (tool.prefs.disabledExtensions().contains("checks")) { problems = emptyList(); notifyChanged(); return }
        val g = ++gen
        val docs = tool.store.docs.values.filter { !it.loading && !it.binary && !it.image && it.loadError == null && it.buffer.length < 4_000_000 }
        checked.keys.retainAll(docs.map { it.key }.toSet())
        // Only documents changed since their last check are copied and checked again.
        class Job(val d: Document, val lang: Language, val version: Int, val lines: List<String>?, val known: Checked?, val lsp: List<com.lxlauncher.tedit.lsp.Diagnostic>)
        val jobs = docs.map { d ->
            val known = checked[d.key]?.takeIf { it.version == d.buffer.version && it.lang == d.language.id }
            Job(d, d.language, d.buffer.version, if (known == null) d.buffer.snapshot() else null, known, d.diagnostics)
        }
        exec.execute {
            val out = ArrayList<Problem>()
            val fresh = ArrayList<Pair<String, Checked>>()
            for (j in jobs) {
                if (g != gen) return@execute
                val found = j.known?.found ?: runCatching { Checks.run(j.lang, j.lines!!, tool.ctx) }.getOrDefault(emptyList())
                    .also { fresh += j.d.key to Checked(j.version, j.lang.id, it) }
                for ((l, c, m) in found) out += Problem(j.d.key, j.d.name, l, c, error = true, message = m)
                // What the document's language server reports.
                for (dg in j.lsp.filter { it.severity <= 3 }) out += Problem(j.d.key, j.d.name, dg.range.start.line, dg.range.start.character, error = dg.severity <= 1,
                    message = listOfNotNull(dg.source, dg.message).joinToString(": "))
                if (j.d.mixedEol) out += Problem(j.d.key, j.d.name, 0, 0, error = false, message = tool.ctx.getString(R.string.tedit_chk_mixed_eol))
                if (j.d.truncated) out += Problem(j.d.key, j.d.name, 0, 0, error = false, message = tool.ctx.getString(R.string.tedit_ro_large))
            }
            main.post {
                if (g != gen) return@post
                for ((k, c) in fresh) checked[k] = c
                problems = out
                notifyChanged()
            }
        }
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
        tool.refreshStatus()
    }
}

class ProblemsPanel(private val tool: TeditTool) : TPanel {
    override val id = "problems"
    override val title = R.string.tedit_panel_problems
    override val glyph = TIcon.Glyph.PROBLEMS
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = ui.scroll(col)
    private val listener: () -> Unit = { render() }

    init {
        tool.problems.addListener(listener)
        tool.problems.schedule()
        render()
    }

    private fun render() {
        col.removeAllViews()
        val all = tool.problems.problems
        col.addView(ui.note(ctx.getString(R.string.tedit_problems_scope)))
        if (all.isEmpty()) { col.addView(ui.note(ctx.getString(R.string.tedit_problems_none))); return }
        for ((key, list) in all.groupBy { it.docKey }) {
            col.addView(ui.sectionTitle("${list.first().name} · ${list.size}"))
            for (p in list.take(200)) {
                val row = ui.row(gravity = Gravity.TOP).apply {
                    setPadding(t.dpi(14f), t.dpi(6f), t.dpi(12f), t.dpi(6f))
                    minimumHeight = t.rowH
                    background = ui.states(0f)
                    isFocusable = true
                    contentDescription = ctx.getString(if (p.error) R.string.tedit_problem_error else R.string.tedit_problem_warning, p.message, p.line + 1)
                }
                row.addView(ui.icon(if (p.error) TIcon.Glyph.ERROR else TIcon.Glyph.WARNING, if (p.error) t.error else t.warning, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f); topMargin = t.dpi(1f) })
                val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
                labels.addView(ui.text(p.message, t.textSmall, t.text, lines = 3))
                labels.addView(ui.text(ctx.getString(R.string.tedit_problem_where, p.line + 1, p.col + 1), t.textCaption, t.textMuted))
                row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
                row.setOnClickListener { goTo(key, p) }
                col.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            }
        }
    }

    private fun goTo(key: String, p: Problem) {
        val d = tool.store.byKey(key) ?: return
        val g = tool.groups.firstOrNull { g -> g.tabs.any { it.doc === d } } ?: tool.activeGroup ?: return
        g.add(d, activate = true)
        tool.activateGroup(g)
        g.editor.post { g.editor.goToLine(p.line, p.col); tool.focusEditor(g) }
        tool.panelDone()
    }

    override fun dispose() { tool.problems.removeListener(listener) }
}

/** Format Document: JSON is re-indented token by token, so numbers and strings keep exactly the text they had. */
object Formatter {
    /** Null when the language has no formatter; a failure when the text cannot be formatted. */
    fun format(ctx: android.content.Context, d: Document, tabSize: Int, spaces: Boolean, disabled: Set<String>): Result<String>? {
        if ("format.json" in disabled) return null
        if (d.language.id != "json") return null
        val text = d.buffer.text()
        val problems = Checks.json(text.split('\n'), ctx)
        if (problems.isNotEmpty()) {
            val (l, c, m) = problems[0]
            return Result.failure(IllegalArgumentException("$m (${l + 1}:${c + 1})"))
        }
        return Result.success(json(text, if (spaces) " ".repeat(tabSize) else "\t"))
    }

    fun json(s: String, unit: String): String {
        val out = StringBuilder(s.length + s.length / 4)
        var depth = 0
        var i = 0
        fun nl() { out.append('\n'); repeat(depth) { out.append(unit) } }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> {
                    val start = i
                    i++
                    while (i < s.length && s[i] != '"') { if (s[i] == '\\') i++; i++ }
                    out.append(s, start, minOf(s.length, i + 1))
                }
                s.startsWith("//", i) -> { val e = s.indexOf('\n', i).let { if (it < 0) s.length else it }; out.append(s, i, e); i = e - 1; nl() }
                s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }; out.append(s, i, e); i = e - 1 }
                c == '{' || c == '[' -> {
                    // An empty object or array stays on one line.
                    var j = i + 1
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && (s[j] == '}' || s[j] == ']')) { out.append(c).append(s[j]); i = j }
                    else { out.append(c); depth++; nl() }
                }
                c == '}' || c == ']' -> { depth = maxOf(0, depth - 1); nl(); out.append(c) }
                c == ',' -> { out.append(c); nl() }
                c == ':' -> out.append(": ")
                c.isWhitespace() -> {}
                else -> out.append(c)
            }
            i++
        }
        // Lines the comment handling left blank at their end go.
        return out.lines().joinToString("\n") { it.trimEnd() }.let { if (s.endsWith("\n")) it + "\n" else it }
    }
}
