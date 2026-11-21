// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Highlight spans for the lines of one buffer, computed when a line is drawn. The state each line starts in is kept
 * for every line up to the last one drawn; an edit throws away only what it can have changed. Lines longer than
 * [maxLine] are drawn plain and pass their state through, so one minified line does not stall the editor.
 *
 * Tedit's own lexers are fast enough to run while the editor draws. A TextMate grammar from an extension can take a
 * few milliseconds a line (VS Code's C++ grammar does), so for those the lines are worked out on a background thread,
 * as VS Code does: a line whose start state is known is still done at once within a small budget per frame, the rest
 * is drawn with the colours it had (or plain) until the thread posts them, and [onRefresh] redraws the editors.
 * Everything here except the background job itself runs on the main thread.
 */
class Highlighter(private val buffer: TextBuffer, language: Language, var maxLine: Int) {
    var language: Language = language
        set(v) {
            field = v
            invalidateAll()
        }

    private var states = IntArray(buffer.lineCount + 1)
    /** states[i] is right for i < validUpTo. */
    private var validUpTo = 1
    private var spans = arrayOfNulls<IntArray>(buffer.lineCount)
    /** The start state each cached span list was made from. */
    private var spanState = IntArray(buffer.lineCount)
    private val sink = SpanSink()
    private val lexed = com.lxlauncher.tedit.ext.TmLexer.Lexed()
    private val listener: (TextBuffer.Change) -> Unit = { onChange(it) }

    /** Editors showing this buffer, told to draw again when background work lands. */
    val onRefresh = CopyOnWriteArrayList<() -> Unit>()

    // Background work. [version] changes with every edit and language change; a job made for an older version stops.
    @Volatile private var version = 0
    @Volatile private var disposed = false
    @Volatile private var jobId = 0
    private var jobVersion = -1
    private var jobPaintFrom = 0
    private var jobTo = -1
    private var wantFrom = Int.MAX_VALUE
    private var wantTo = -1
    private var startPosted = false
    private val startJob = Runnable { startPosted = false; startJob() }
    // How long lexing may take on the main thread in one frame.
    private var budgetWindow = 0L
    private var budgetSpent = 0L

    private val heavy: Boolean get() = language.lexer is com.lxlauncher.tedit.ext.TmLexer

    init {
        states[0] = language.lexer.initialState
        buffer.addListener(listener)
    }

    fun dispose() {
        disposed = true
        version++
        buffer.removeListener(listener)
        onRefresh.clear()
        main.removeCallbacks(startJob)
    }

    fun invalidateAll() {
        version++
        states = IntArray(buffer.lineCount + 1)
        states[0] = language.lexer.initialState
        validUpTo = 1
        spans = arrayOfNulls(buffer.lineCount)
        spanState = IntArray(buffer.lineCount)
        jobVersion = -1
    }

    private fun onChange(c: TextBuffer.Change) {
        version++
        jobVersion = -1
        val delta = c.newEndLine - c.oldEndLine
        if (delta != 0) {
            val n = buffer.lineCount
            val newSpans = arrayOfNulls<IntArray>(n)
            val newSpanState = IntArray(n)
            // Lines before the change keep their spans; lines after it move by delta.
            System.arraycopy(spans, 0, newSpans, 0, minOf(c.startLine, spans.size, n))
            System.arraycopy(spanState, 0, newSpanState, 0, minOf(c.startLine, spanState.size, n))
            val oldAfter = c.oldEndLine + 1
            val count = spans.size - oldAfter
            if (count > 0 && oldAfter + delta >= 0) {
                System.arraycopy(spans, oldAfter, newSpans, oldAfter + delta, minOf(count, n - (oldAfter + delta)))
                System.arraycopy(spanState, oldAfter, newSpanState, oldAfter + delta, minOf(count, n - (oldAfter + delta)))
            }
            spans = newSpans
            spanState = newSpanState
            val newStates = IntArray(n + 1)
            System.arraycopy(states, 0, newStates, 0, minOf(c.startLine + 1, states.size, n + 1))
            states = newStates
        }
        // A background grammar keeps the old spans of the edited lines to draw until the new ones arrive, marked stale.
        for (i in c.startLine..minOf(c.newEndLine, spans.size - 1)) if (heavy) spanState[i] = STALE else spans[i] = null
        validUpTo = minOf(validUpTo, c.startLine + 1)
    }

    /** The state line [line] starts in, computing earlier lines as needed. */
    fun stateAt(line: Int): Int {
        if (line < validUpTo) return states[line]
        skipLongGap(line)
        var i = validUpTo - 1
        var st = states[i]
        while (i < line) {
            val text = buffer.line(i)
            st = if (text.length > maxLine) st else language.lexer.lex(text, st, null)
            i++
            states[i] = st
        }
        validUpTo = line + 1
        return states[line]
    }

    // A TextMate grammar costs far more per line than Tedit's own lexers: reaching the end of a long file would lex
    // every line before it. Past a long gap colouring starts again from the root state a little above the line, which
    // can only be wrong inside a comment or string spanning it.
    private fun skipLongGap(line: Int) {
        if (line - validUpTo > SLOW_GAP && heavy) {
            val resume = line - SLOW_CONTEXT
            for (k in validUpTo..resume) states[k] = 0
            validUpTo = resume + 1
        }
    }

    /** (start, end, kind) triples for [line]; empty for plain text. */
    fun spans(line: Int): IntArray {
        if (line !in 0 until buffer.lineCount) return EMPTY
        if (!heavy) return spansNow(line)
        val cached = spans[line]
        if (line < validUpTo) {
            if (cached != null && spanState[line] == states[line]) return cached
            // A grammar's first line compiles its patterns; that happens in the background too.
            val warm = (language.lexer as? com.lxlauncher.tedit.ext.TmLexer)?.warm ?: true
            if (warm && withinBudget()) {
                val t0 = System.nanoTime()
                val r = heavyNow(line)
                budgetSpent += System.nanoTime() - t0
                if (r != null) return r
            }
        }
        request(line)
        return cached ?: EMPTY
    }

    private fun spansNow(line: Int): IntArray {
        val st = stateAt(line)
        val cached = spans[line]
        if (cached != null && spanState[line] == st) return cached
        val text = buffer.line(line)
        val result = if (text.length > maxLine || language.lexer === PlainLexer) EMPTY else {
            sink.reset()
            val end = language.lexer.lex(text, st, sink)
            if (line + 1 < states.size && line + 1 == validUpTo) {
                states[line + 1] = end
                validUpTo = line + 2
            }
            sink.toArray()
        }
        spans[line] = result
        spanState[line] = st
        return result
    }

    /**
     * One line of a TextMate grammar on the main thread, its start state known. A line that runs out of time is not
     * kept, neither its state nor its colours, and goes to the background with more time; null then.
     */
    private fun heavyNow(line: Int): IntArray? {
        val lexer = language.lexer as com.lxlauncher.tedit.ext.TmLexer
        val st = states[line]
        val text = buffer.line(line)
        val result = if (text.length > maxLine) {
            lexed.state = st
            EMPTY
        } else {
            sink.reset()
            lexer.lexWithin(text, st, sink, MAIN_LINE_NS, lexed)
            if (!lexed.complete) return null
            sink.toArray()
        }
        if (line + 1 < states.size && line + 1 == validUpTo) {
            states[line + 1] = lexed.state
            validUpTo = line + 2
        }
        spans[line] = result
        spanState[line] = st
        return result
    }

    private fun withinBudget(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - budgetWindow > FRAME_MS) {
            budgetWindow = now
            budgetSpent = 0
        }
        return budgetSpent < MAIN_BUDGET_NS
    }

    /** Asks for [line] from the background; the lines a frame asks for start one job when the frame is drawn. */
    private fun request(line: Int) {
        if (jobVersion == version && line in jobPaintFrom..jobTo) return
        if (line < wantFrom) wantFrom = line
        if (line > wantTo) wantTo = line
        if (!startPosted) {
            startPosted = true
            main.post(startJob)
        }
    }

    private fun startJob() {
        if (disposed || wantTo < 0) return
        val lines = buffer.lineCount
        val paintFrom = minOf(wantFrom, lines - 1)
        val to = minOf(lines - 1, wantTo + AHEAD)
        wantFrom = Int.MAX_VALUE
        wantTo = -1
        if (to < 0 || (jobVersion == version && paintFrom >= jobPaintFrom && to <= jobTo)) return
        skipLongGap(paintFrom)
        // Start where the state is known: the first line asked for, or the first line not worked out yet.
        val from = minOf(paintFrom, validUpTo - 1)
        val texts = Array(to - from + 1) { buffer.line(from + it) }
        val start = states[from]
        val v = version
        val id = ++jobId
        jobVersion = v
        jobPaintFrom = paintFrom
        jobTo = to
        val lexer = language.lexer
        val tm = lexer as? com.lxlauncher.tedit.ext.TmLexer
        val max = maxLine
        worker.execute {
            val out = SpanSink()
            val got = com.lxlauncher.tedit.ext.TmLexer.Lexed()
            // Off the main thread a line may take its time; only a pathological one is cut, as vscode-textmate does
            // when its time limit runs out.
            fun lex(text: String, state: Int, sink: SpanSink?): Int =
                if (tm == null) lexer.lex(text, state, sink) else { tm.lexWithin(text, state, sink, WORKER_LINE_NS, got); got.state }
            val outStates = IntArray(texts.size)
            val outSpans = arrayOfNulls<IntArray>(texts.size)
            var st = start
            var posted = 0
            var lastPost = SystemClock.uptimeMillis()
            for (k in texts.indices) {
                if (version != v || jobId != id || disposed) return@execute
                val text = texts[k]
                val paint = from + k >= paintFrom
                st = when {
                    text.length > max -> { if (paint) outSpans[k] = EMPTY; st }
                    paint -> { out.reset(); lex(text, st, out).also { outSpans[k] = out.toArray() } }
                    else -> lex(text, st, null)
                }
                outStates[k] = st
                val now = SystemClock.uptimeMillis()
                if (k == texts.lastIndex || now - lastPost >= POST_MS) {
                    val a = posted
                    val b = k
                    posted = k + 1
                    lastPost = now
                    main.post { commit(v, from, start, a, b, outStates, outSpans) }
                }
            }
        }
    }

    /** Takes lines [from]+[a]..[from]+[b] of a background job, if nothing changed since it started. */
    private fun commit(v: Int, from: Int, start: Int, a: Int, b: Int, outStates: IntArray, outSpans: Array<IntArray?>) {
        if (v != version || disposed) return
        // The job started from a known state, so its states are the ones to keep: they also replace the guesses a long
        // jump left (see skipLongGap), which would otherwise never match the spans painted from them.
        if (from > validUpTo - 1) return
        for (k in a..b) {
            val i = from + k
            if (i + 1 >= states.size) break
            val begin = if (k == 0) start else outStates[k - 1]
            states[i + 1] = outStates[k]
            if (i + 2 > validUpTo) validUpTo = i + 2
            outSpans[k]?.let { spans[i] = it; spanState[i] = begin }
        }
        onRefresh.forEach { it() }
    }

    /** The kind of text at [col] in [line], for bracket matching and comment detection. */
    fun kindAt(line: Int, col: Int): Int {
        val s = spans(line)
        var i = 0
        while (i < s.size) {
            if (col >= s[i] && col < s[i + 1]) return s[i + 2]
            i += 3
        }
        return Token.PLAIN
    }

    companion object {
        val EMPTY = IntArray(0)
        /** A span state no lexer gives: the spans are an edited line's old ones, kept only to draw. */
        private const val STALE = Int.MIN_VALUE
        private const val SLOW_GAP = 1500
        private const val SLOW_CONTEXT = 200
        /** Lines worked out past the last one asked for, so a little scrolling finds them ready. */
        private const val AHEAD = 60
        private const val FRAME_MS = 16L
        private const val MAIN_BUDGET_NS = 6_000_000L
        /** One line of a TextMate grammar on the main thread; past it the line goes to the background. */
        private const val MAIN_LINE_NS = 20_000_000L
        /** One line in the background: half a second, which only a pathological line reaches. */
        private const val WORKER_LINE_NS = 500_000_000L
        /** How often a background job hands over what it has. */
        private const val POST_MS = 50L

        private val main by lazy { Handler(Looper.getMainLooper()) }
        private val worker: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "tedit-highlight").apply { isDaemon = true }
            }
        }
    }
}
