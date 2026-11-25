// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.os.SystemClock
import com.lxlauncher.tedit.SpanSink
import com.lxlauncher.tedit.ext.TmGrammar
import com.lxlauncher.tedit.ext.TmLexer
import org.json.JSONObject
import java.io.File

/**
 * Tedit's TextMate tokenizer against vscode-textmate itself, on this device's regex engine (ICU on Android), run with
 * `-e only tmparity`. The folder [dir] is what scripts/tmparity/prepare.sh writes on a computer with VS Code, pushed
 * to /sdcard/Download/tmparity; LX needs all-files access while it runs:
 *
 * - `ext/<extension>/syntaxes` and `package.json`: VS Code's built-in grammars and their injectTo;
 * - `corpus.tsv`: one row per file, `scope<TAB>index<TAB>name`, and `files/<index>`: the file;
 * - `ref/<index>.txt`: per line, vscode-textmate's tokens as `start:stack,...`, or `-` for a line VS Code does not
 *   tokenize; `stacks.txt`: the scope stacks those numbers point to, outermost scope first.
 *
 * A stack becomes a token kind through Tedit's own mapping (the innermost scope that maps to one wins), and every
 * non-space character is compared. Lines over 20,000 characters are skipped, as both editors skip them.
 */
internal class TextMateParity(
    private val check: (String, () -> String?) -> Unit,
    private val info: (String) -> Unit,
    /** `-e only tmparity:<index>`: only that file, with its first differing lines printed. */
    private val only: String? = null,
) {
    private val dir = File("/sdcard/Download/tmparity")

    fun run() {
        val ready = listOf("corpus.tsv", "stacks.txt").all { File(dir, it).canRead() } && File(dir, "ext").list()?.isNotEmpty() == true
        if (!ready) {
            check("textmate parity: the prepared folder is on the device") {
                "nothing readable in ${dir.path}: run scripts/tmparity/prepare.sh, push its folder there and give LX all-files " +
                    "access for the run (the script says how)"
            }
            return
        }
        val started = SystemClock.elapsedRealtime()
        val grammars = HashMap<String, TmGrammar>()
        val injectTo = HashMap<String, MutableList<String>>()
        File(dir, "ext").listFiles()?.sortedBy { it.name }?.forEach { ext ->
            File(ext, "syntaxes").walkTopDown().filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".tmLanguage")) }.forEach {
                runCatching { TmGrammar.parse(it.readText()) }.getOrNull()?.let { g -> grammars.putIfAbsent(g.scopeName, g) }
            }
            val pj = runCatching { JSONObject(File(ext, "package.json").readText()) }.getOrNull() ?: return@forEach
            val list = pj.optJSONObject("contributes")?.optJSONArray("grammars") ?: return@forEach
            for (i in 0 until list.length()) {
                val g = list.optJSONObject(i) ?: continue
                val to = g.optJSONArray("injectTo") ?: continue
                for (j in 0 until to.length()) injectTo.getOrPut(to.getString(j)) { ArrayList() }.add(g.getString("scopeName"))
            }
        }
        fun injectors(scope: String): List<TmGrammar> {
            val parts = scope.split('.')
            return (1..parts.size).flatMap { n -> injectTo[parts.take(n).joinToString(".")].orEmpty() }.mapNotNull { grammars[it] }
        }
        info("PERF ${grammars.size} grammars parsed in ${SystemClock.elapsedRealtime() - started} ms")
        if (only == "compile") return compileReport(grammars.values)

        // Each scope stack's kind, the way Tedit's tokenizer picks one: the innermost scope with an opinion.
        val stackKinds = File(dir, "stacks.txt").readLines().map { stack ->
            stack.split(' ').asReversed().firstNotNullOfOrNull { sc -> com.lxlauncher.tedit.ext.kindOfNames(sc).takeIf { it >= 0 } }
                ?: com.lxlauncher.tedit.Token.PLAIN
        }
        var allChars = 0L
        var allBad = 0L
        var allLines = 0
        var allMs = 0L
        val perScope = LinkedHashMap<String, LongArray>()
        val worst = ArrayList<String>()
        for (row in File(dir, "corpus.tsv").readLines().filter { it.isNotBlank() }) {
            val (scope, index, name) = row.split('\t')
            if (only != null && index != only) continue
            var shown = 0
            val g = grammars[scope] ?: continue
            // Long minified lines may pass the editor's time budget per line; every character is compared here.
            val lexer = TmLexer(g, { grammars[it] }, { injectors(it) }).apply { lineBudgetNs = Long.MAX_VALUE / 4 }
            val lines = File(dir, "files/$index").readText().split(Regex("\r?\n"))
            val want = File(dir, "ref/$index.txt").readLines()
            var state = lexer.initialState
            var chars = 0
            var bad = 0
            var firstBad: String? = null
            val sink = SpanSink()
            val t0 = SystemClock.elapsedRealtime()
            for ((n, line) in lines.withIndex()) {
                val w = want.getOrNull(n) ?: break
                if (line.length > 20_000 || w == "-") continue
                sink.reset()
                state = lexer.lex(line, state, sink)
                val mine = IntArray(line.length)
                var k = 0
                while (k < sink.size) {
                    for (c in sink.data[k] until minOf(sink.data[k + 1], line.length)) mine[c] = sink.data[k + 2]
                    k += 3
                }
                val theirs = IntArray(line.length)
                val tokens = if (w.isEmpty()) emptyList() else w.split(',')
                for ((t, tok) in tokens.withIndex()) {
                    val colon = tok.indexOf(':')
                    val from = tok.substring(0, colon).toInt()
                    val to = tokens.getOrNull(t + 1)?.let { it.substring(0, it.indexOf(':')).toInt() } ?: line.length
                    val kind = stackKinds[tok.substring(colon + 1).toInt()]
                    for (c in from until minOf(to, line.length)) theirs[c] = kind
                }
                for (c in line.indices) {
                    if (line[c].isWhitespace()) continue
                    chars++
                    if (mine[c] == theirs[c]) continue
                    bad++
                    if (firstBad == null) firstBad = "line ${n + 1} col ${c + 1}: tedit ${mine[c]}, vscode ${theirs[c]}"
                    if (only != null && shown < 6 && (shown == 0 || !firstBad!!.startsWith("line ${n + 1} "))) {
                        shown++
                        val alone = SpanSink().also { lexer.lex(line, 0, it) }
                        info("DIFF line ${n + 1}: ${line.take(100)}\n  tedit  ${runs(mine)}\n  vscode ${runs(theirs)}\n  alone  ${spans(alone, line.length)}")
                        firstBad = "line ${n + 1} "
                    }
                }
            }
            val ms = SystemClock.elapsedRealtime() - t0
            allChars += chars
            allBad += bad
            allLines += lines.size
            allMs += ms
            perScope.getOrPut(scope) { LongArray(3) }.let { it[0] += lines.size.toLong(); it[1] += ms; it[2] += bad.toLong() }
            if (bad > 0) worst += "$name ($scope): $bad of $chars differ, first at $firstBad"
        }
        for ((scope, v) in perScope) info("PERF $scope: ${v[0]} lines in ${v[1]} ms (%.2f ms a line), ${v[2]} characters differ".format(v[1].toDouble() / maxOf(v[0], 1)))
        worst.take(10).forEach { info("INFO $it") }
        info("PERF all: $allLines lines in $allMs ms, $allBad of $allChars characters differ from vscode-textmate")
        check("textmate parity: every character gets the colour vscode-textmate gives it") {
            when {
                allChars == 0L -> "nothing compared"
                allBad > 0 -> "$allBad of $allChars characters differ: ${worst.firstOrNull()}"
                else -> null
            }
        }
    }

    /** `-e only tmparity:compile`: every pattern of every grammar through Tedit's translation, and what this engine refuses. */
    private fun compileReport(grammars: Collection<TmGrammar>) {
        val sources = LinkedHashSet<String>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.lxlauncher.tedit.ext.TmRule, Boolean>())
        fun walk(r: com.lxlauncher.tedit.ext.TmRule) {
            if (!seen.add(r)) return
            r.match?.let { sources += it }
            r.begin?.let { sources += it }
            r.end?.let { if (!r.endBackrefs) sources += it }
            r.patterns.forEach { walk(it) }
            r.repo?.rules?.values?.forEach { walk(it) }
        }
        for (g in grammars) { walk(g.root); g.repository.values.forEach { walk(it) }; g.injections.forEach { walk(it.second) } }
        var bad = 0
        for (src in sources) {
            val called = if (src.contains("\\g<") || src.contains("\\g'")) com.lxlauncher.tedit.ext.expandCalls(src) else src
            val translated = com.lxlauncher.tedit.ext.translateOniguruma(called, keepG = true)
            val bounded = com.lxlauncher.tedit.ext.boundLookbehinds(translated) ?: translated
            try {
                java.util.regex.Pattern.compile(bounded, java.util.regex.Pattern.MULTILINE or java.util.regex.Pattern.UNIX_LINES or java.util.regex.Pattern.UNICODE_CASE)
            } catch (e: java.util.regex.PatternSyntaxException) {
                bad++
                val at = e.index
                val near = if (at >= 0) bounded.substring(maxOf(0, at - 40), minOf(bounded.length, at + 20)) else ""
                if (bad <= 40) info("REFUSED ${e.description} at $at: ...${near.replace("\n", "\\n")}...  source: ${src.take(80).replace("\n", "\\n")}")
            }
        }
        info("PERF ${sources.size} pattern sources, $bad refused by this engine")
    }

    private fun runs(kinds: IntArray): String {
        val sb = StringBuilder()
        var c = 0
        while (c < kinds.size) {
            var e = c + 1
            while (e < kinds.size && kinds[e] == kinds[c]) e++
            if (c > 0) sb.append(',')
            sb.append(kinds[c]).append('*').append(e - c)
            c = e
        }
        return sb.toString()
    }

    private fun spans(sink: SpanSink, length: Int): String {
        val k = IntArray(length)
        var i = 0
        while (i < sink.size) { for (c in sink.data[i] until minOf(sink.data[i + 1], length)) k[c] = sink.data[i + 2]; i += 3 }
        return runs(k)
    }
}
