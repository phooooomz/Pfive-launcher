// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Options of a search, in the editor's find bar and in Search across files alike. */
data class FindQuery(
    val text: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
) {
    /** The compiled pattern, or the reason it does not compile. */
    fun compile(): Result<Pattern> = runCatching {
        if (text.isEmpty()) throw PatternSyntaxException("empty", text, 0)
        var p = if (regex) text else Pattern.quote(text)
        if (wholeWord) p = "(?<![\\p{L}\\p{N}_])(?:$p)(?![\\p{L}\\p{N}_])"
        Pattern.compile(p, if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    }

    /** The replacement as Java's Matcher reads it: $1 refers to a group with regex on, and is literal text otherwise. */
    fun replacement(raw: String): String = if (regex) raw else Matcher.quoteReplacement(raw)
}

/**
 * Matches of a [FindQuery] in one buffer, as (line, start, end) triples sorted by place. Matches stay within one line,
 * which is what the editor highlights and what replacing one at a time needs.
 *
 * A buffer of [ASYNC_LINES] lines or more is searched on a worker thread when [update] is asked, and the old matches
 * stay on screen until the new ones arrive ([onResult] then runs on the main thread); [updateNow] always waits.
 */
class FindState {
    var query = FindQuery("")
        private set
    var matches = IntArray(0)
        private set
    var count = 0
        private set
    var current = -1
    var error: String? = null
        private set
    var truncated = false
        private set
    /** True while a search for the current query runs on the worker thread. */
    var searching = false
        private set
    /** Runs on the main thread when a search on the worker thread has finished. */
    var onResult: (() -> Unit)? = null
    private var forVersion = -1
    /** Weak, so a closed document's text is not kept alive by the last search run over it. */
    private var forBuffer: java.lang.ref.WeakReference<TextBuffer>? = null
    /** Counts searches started on the worker, so a late answer to an older one is dropped. */
    private var asyncGen = 0
    private var asyncFor: Triple<Int, Int, FindQuery>? = null

    fun set(q: FindQuery) {
        if (q != query) {
            query = q
            forVersion = -1
        }
    }

    fun clear() {
        query = FindQuery("")
        matches = IntArray(0)
        count = 0
        current = -1
        forVersion = -1
        asyncGen++
        asyncFor = null
        searching = false
    }

    val active get() = query.text.isNotEmpty()

    private fun fresh(buffer: TextBuffer) = forBuffer?.get() === buffer && forVersion == buffer.version

    /** Recomputes when the buffer or the query changed; a large buffer is searched on the worker thread. */
    fun update(buffer: TextBuffer, limit: Int = 20000) {
        if (fresh(buffer)) return
        if (query.text.isEmpty() || buffer.lineCount < ASYNC_LINES) { updateNow(buffer, limit); return }
        val key = Triple(System.identityHashCode(buffer), buffer.version, query)
        if (searching && asyncFor == key) return
        asyncFor = key
        searching = true
        val gen = ++asyncGen
        val lines = buffer.snapshot()
        val q = query
        val version = buffer.version
        WORKER.execute {
            val r = scan(lines, q, limit)
            MAIN.post {
                if (gen != asyncGen) return@post
                searching = false
                asyncFor = null
                // The text or the query moved on meanwhile: the caller asks again on its next draw.
                if (buffer.version == version && q == query) take(r, buffer, version)
                onResult?.invoke()
            }
        }
    }

    /** Recomputes now, on this thread, when the buffer or the query changed. Replacing needs exact matches. */
    fun updateNow(buffer: TextBuffer, limit: Int = 20000) {
        if (fresh(buffer) && !(limit > count && truncated)) return
        asyncGen++
        searching = false
        asyncFor = null
        take(scan(buffer.snapshot(), query, limit), buffer, buffer.version)
    }

    private class Scan(val matches: IntArray, val truncated: Boolean, val error: String?)

    private fun take(r: Scan, buffer: TextBuffer, version: Int) {
        forBuffer = java.lang.ref.WeakReference(buffer)
        forVersion = version
        error = r.error
        truncated = r.truncated
        matches = r.matches
        count = r.matches.size / 3
        if (current >= count) current = count - 1
    }

    /** Index of the first match at or after [p] (wrapping round), or -1. */
    fun nextFrom(p: Pos, forward: Boolean): Int {
        if (count == 0) return -1
        if (forward) {
            for (i in 0 until count) {
                val l = matches[i * 3]; val s = matches[i * 3 + 1]
                if (l > p.line || (l == p.line && s >= p.col)) return i
            }
            return 0
        }
        for (i in count - 1 downTo 0) {
            val l = matches[i * 3]; val e = matches[i * 3 + 1]
            if (l < p.line || (l == p.line && e < p.col)) return i
        }
        return count - 1
    }

    /** First match index on [line] or after it (binary search), for drawing. */
    fun firstOnOrAfter(line: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (matches[mid * 3] < line) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Buffers with this many lines or more are searched off the main thread while the query is typed. */
        const val ASYNC_LINES = 20_000
        private val MAIN = android.os.Handler(android.os.Looper.getMainLooper())
        private val WORKER = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "tedit-find").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }

        /**
         * [t] in [line] from [from], ignoring case; [t] is ASCII. Positions are tested on their first character before
         * the full comparison, which keeps the scan a single pass; a non-ASCII character is lowered first, so the
         * Kelvin sign still matches k as the regex engine has it.
         */
        private fun indexOfIgnoreCase(line: String, t: String, from: Int): Int {
            val lo = t[0].lowercaseChar()
            val up = t[0].uppercaseChar()
            val last = line.length - t.length
            var i = from
            while (i <= last) {
                val c = line[i]
                if ((c == lo || c == up || (c.code >= 128 && Character.toLowerCase(c) == lo)) && line.regionMatches(i, t, 0, t.length, ignoreCase = true)) return i
                i++
            }
            return -1
        }

        /** The characters the whole-word pattern counts as part of a word: \p{L}, \p{N} and the underscore. */
        private fun isWordChar(c: Char): Boolean {
            if (c == '_' || Character.isLetter(c)) return true
            val t = Character.getType(c)
            return t == Character.DECIMAL_DIGIT_NUMBER.toInt() || t == Character.LETTER_NUMBER.toInt() || t == Character.OTHER_NUMBER.toInt()
        }

        /**
         * Every match of [q] in [lines], up to [limit]. Plain text is found with indexOf, several times faster than
         * the regex engine on a quoted pattern; regular expressions go through java.util.regex.
         */
        private fun scan(lines: List<String>, q: FindQuery, limit: Int): Scan {
            if (q.text.isEmpty()) return Scan(IntArray(0), false, null)
            var out = IntArray(96)
            var n = 0
            fun add(line: Int, s: Int, e: Int): Boolean {
                if (n + 3 > out.size) out = out.copyOf(out.size * 2)
                out[n++] = line; out[n++] = s; out[n++] = e
                return n / 3 >= limit
            }
            if (!q.regex) {
                val t = q.text
                val ignore = !q.caseSensitive
                // Matching a non-ASCII query without case goes through the regex engine, which folds case the
                // way Unicode defines it; indexOf below compares char by char.
                if (!ignore || t.all { it.code < 128 }) {
                    loop@ for ((li, line) in lines.withIndex()) {
                        var from = 0
                        while (true) {
                            val i = if (ignore) indexOfIgnoreCase(line, t, from) else line.indexOf(t, from)
                            if (i < 0) break
                            val e = i + t.length
                            val ok = !q.wholeWord || ((i == 0 || !isWordChar(line[i - 1])) && (e == line.length || !isWordChar(line[e])))
                            if (ok) {
                                if (add(li, i, e)) return Scan(out.copyOf(n), true, null)
                                from = e
                            } else from = i + 1
                        }
                    }
                    return Scan(out.copyOf(n), false, null)
                }
            }
            val pattern = q.compile().getOrElse {
                return Scan(IntArray(0), false, (it as? java.util.regex.PatternSyntaxException)?.description ?: it.message)
            }
            val m = pattern.matcher("")
            for ((li, line) in lines.withIndex()) {
                m.reset(line)
                var from = 0
                while (from <= line.length && m.find(from)) {
                    val s = m.start()
                    val e = m.end()
                    if (add(li, s, e)) return Scan(out.copyOf(n), true, null)
                    from = if (e > s) e else e + 1
                }
            }
            return Scan(out.copyOf(n), false, null)
        }
    }
}
