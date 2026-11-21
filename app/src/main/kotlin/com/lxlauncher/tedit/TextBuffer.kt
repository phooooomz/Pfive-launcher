// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

/** A place in a [TextBuffer]: a line, and a UTF-16 index into that line. */
data class Pos(val line: Int, val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int = if (line != other.line) line.compareTo(other.line) else col.compareTo(other.col)

    companion object {
        val ZERO = Pos(0, 0)
        fun min(a: Pos, b: Pos) = if (a <= b) a else b
        fun max(a: Pos, b: Pos) = if (a >= b) a else b
    }
}

/**
 * The text of one document as a list of lines without their line breaks. Lines make the common work cheap: drawing
 * the visible part, highlighting line by line, searching, and inserting a character, which copies one line only.
 * Global offsets (for the keyboard) come from a table of line starts that is rebuilt lazily from the first edited line.
 */
class TextBuffer(text: CharSequence = "") {
    /** One edit: lines [startLine, oldEndLine] were replaced by lines [startLine, newEndLine]. */
    data class Change(val startLine: Int, val oldEndLine: Int, val newEndLine: Int)

    private val lines = ArrayList<String>()
    private var starts = IntArray(0)
    /** Entries of [starts] below this index are correct. */
    private var startsValid = 0
    private val listeners = ArrayList<(Change) -> Unit>()

    /** Grows by one on every change; caches compare against it. */
    var version = 0
        private set

    init {
        setText(text)
    }

    val lineCount: Int get() = lines.size
    fun line(i: Int): String = lines[i]
    /** A copy of the line list, for reading on another thread while this buffer keeps changing. */
    fun snapshot(): List<String> = ArrayList(lines)
    fun lineLength(i: Int): Int = lines[i].length
    val lastPos: Pos get() = Pos(lines.size - 1, lines[lines.size - 1].length)

    fun addListener(l: (Change) -> Unit) { listeners += l }
    fun removeListener(l: (Change) -> Unit) { listeners -= l }

    /** Replaces everything; lines are split on \n, \r\n and lone \r. */
    fun setText(text: CharSequence) {
        val old = lines.size
        lines.clear()
        splitInto(text, lines)
        starts = IntArray(lines.size + 1)
        startsValid = 0
        version++
        val change = Change(0, maxOf(0, old - 1), lines.size - 1)
        listeners.toList().forEach { it(change) }
    }

    /** Adopts lines read elsewhere (a loader thread); the list must not be empty. */
    fun setLines(newLines: ArrayList<String>) {
        require(newLines.isNotEmpty())
        val old = lines.size
        lines.clear()
        lines.addAll(newLines)
        starts = IntArray(lines.size + 1)
        startsValid = 0
        version++
        val change = Change(0, maxOf(0, old - 1), lines.size - 1)
        listeners.toList().forEach { it(change) }
    }

    fun clamp(p: Pos): Pos {
        val l = p.line.coerceIn(0, lines.size - 1)
        return Pos(l, p.col.coerceIn(0, lines[l].length))
    }

    /** The text between [a] and [b], lines joined with \n. */
    fun text(a: Pos = Pos.ZERO, b: Pos = lastPos): String {
        val s = clamp(Pos.min(a, b))
        val e = clamp(Pos.max(a, b))
        if (s.line == e.line) return lines[s.line].substring(s.col, e.col)
        val sb = StringBuilder()
        sb.append(lines[s.line], s.col, lines[s.line].length)
        for (i in s.line + 1 until e.line) sb.append('\n').append(lines[i])
        sb.append('\n').append(lines[e.line], 0, e.col)
        return sb.toString()
    }

    /** Characters in the document, counting each line break as one. */
    val length: Int get() = offsetOf(lastPos)

    fun offsetOf(p: Pos): Int {
        val c = clamp(p)
        ensureStarts(c.line)
        return starts[c.line] + c.col
    }

    fun posOf(offset: Int): Pos {
        ensureStarts(lines.size - 1)
        val o = offset.coerceIn(0, starts[lines.size - 1] + lines[lines.size - 1].length)
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return Pos(lo, (o - starts[lo]).coerceAtMost(lines[lo].length))
    }

    private fun ensureStarts(upTo: Int) {
        if (starts.size < lines.size + 1) starts = starts.copyOf(lines.size + 1 + lines.size / 4)
        if (startsValid == 0) {
            starts[0] = 0
            startsValid = 1
        }
        var i = startsValid
        while (i <= upTo) {
            starts[i] = starts[i - 1] + lines[i - 1].length + 1
            i++
        }
        if (upTo + 1 > startsValid) startsValid = upTo + 1
    }

    /**
     * Replaces the text between [a] and [b] with [text] and returns where the new text ends. Undo is the caller's
     * business ([EditHistory]); this is the one place the lines change after loading.
     */
    fun replace(a: Pos, b: Pos, text: CharSequence): Pos {
        val s = clamp(Pos.min(a, b))
        val e = clamp(Pos.max(a, b))
        val head = lines[s.line].substring(0, s.col)
        val tail = lines[e.line].substring(e.col)
        val parts = ArrayList<String>()
        splitInto(text, parts)
        val endPos: Pos
        if (parts.size == 1) {
            val newLine = head + parts[0] + tail
            endPos = Pos(s.line, head.length + parts[0].length)
            if (s.line == e.line) {
                lines[s.line] = newLine
            } else {
                lines[s.line] = newLine
                lines.subList(s.line + 1, e.line + 1).clear()
            }
        } else {
            parts[0] = head + parts[0]
            val lastIdx = parts.size - 1
            val lastLen = parts[lastIdx].length
            parts[lastIdx] = parts[lastIdx] + tail
            endPos = Pos(s.line + lastIdx, lastLen)
            // Replace the old span [s.line, e.line] by parts, touching the list once.
            val oldCount = e.line - s.line + 1
            val common = minOf(oldCount, parts.size)
            for (i in 0 until common) lines[s.line + i] = parts[i]
            if (parts.size > oldCount) lines.addAll(s.line + oldCount, parts.subList(oldCount, parts.size))
            else if (oldCount > parts.size) lines.subList(s.line + parts.size, e.line + 1).clear()
        }
        if (startsValid > s.line + 1) startsValid = s.line + 1
        version++
        val change = Change(s.line, e.line, endPos.line)
        listeners.toList().forEach { it(change) }
        return endPos
    }

    companion object {
        fun splitInto(text: CharSequence, out: MutableList<String>) {
            var start = 0
            var i = 0
            val n = text.length
            while (i < n) {
                val ch = text[i]
                if (ch == '\n' || ch == '\r') {
                    out.add(text.subSequence(start, i).toString())
                    if (ch == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    start = i + 1
                }
                i++
            }
            out.add(text.subSequence(start, n).toString())
        }

        /** Where [text] ends when inserted at [start]. */
        fun endOf(start: Pos, text: CharSequence): Pos {
            var line = start.line
            var lastBreak = -1
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\n' || ch == '\r') {
                    if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    line++
                    lastBreak = i
                }
                i++
            }
            return if (lastBreak < 0) Pos(start.line, start.col + text.length) else Pos(line, text.length - lastBreak - 1)
        }
    }
}

/**
 * Undo and redo for a [TextBuffer]. Typing and deleting in one place within a moment form one step, as in most
 * editors; anything else is a step of its own. [stateId] names the current state, so the document can tell whether
 * it still matches what was saved after any mix of edits, undos and redos.
 */
class EditHistory(private val buffer: TextBuffer) {
    enum class Kind { TYPE, DELETE, OTHER }

    class Edit(
        val start: Pos,
        var removed: String,
        var inserted: String,
        val selBefore: Pair<Pos, Pos>,
        var selAfter: Pair<Pos, Pos>,
        val kind: Kind,
        var time: Long,
        val idBefore: Int,
        var idAfter: Int,
    )

    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var nextId = 1
    var stateId = 0
        private set
    /** Characters held by undo and redo together; the oldest steps go first past [limitChars]. */
    private var held = 0L
    var limitChars: Long = 16L * 1024 * 1024
    /** Ends the current typing group, so the next keystroke starts a new undo step. */
    var sealed = true

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun clear() {
        undo.clear()
        redo.clear()
        held = 0
        sealed = true
    }

    /** Applies an edit and records it. Returns where the inserted text ends. */
    fun apply(a: Pos, b: Pos, text: String, selBefore: Pair<Pos, Pos>, kind: Kind, now: Long): Pos {
        val s = buffer.clamp(Pos.min(a, b))
        val e = buffer.clamp(Pos.max(a, b))
        val removed = buffer.text(s, e)
        val end = buffer.replace(s, e, text)
        redo.clear()
        val last = undo.lastOrNull()
        // A keyboard revising the word it composes replaces the tail of the last typed text: still one step.
        val revise = !sealed && last != null && kind == Kind.TYPE && last.kind == Kind.TYPE && now - last.time < 3000 &&
            removed.isNotEmpty() && !removed.contains('\n') && !text.contains('\n') &&
            last.inserted.endsWith(removed) && TextBuffer.endOf(last.start, last.inserted) == e
        if (revise && last != null) {
            last.inserted = last.inserted.substring(0, last.inserted.length - removed.length) + text
            last.time = now
            last.idAfter = nextId++
            last.selAfter = Pair(end, end)
            stateId = last.idAfter
            held += text.length
            return end
        }
        val merge = !sealed && last != null && last.kind == kind && kind != Kind.OTHER && now - last.time < 1200 &&
            !text.contains('\n') && when (kind) {
                Kind.TYPE -> removed.isEmpty() && TextBuffer.endOf(last.start, last.inserted) == s &&
                    // A space after a word ends the word's step, as people undo word by word.
                    !(text == " " && last.inserted.isNotEmpty() && last.inserted.last() != ' ')
                Kind.DELETE -> text.isEmpty() && last.inserted.isEmpty() && (e == last.start || s == last.start)
                else -> false
            }
        if (merge && last != null) {
            if (kind == Kind.TYPE) last.inserted += text
            else if (e == last.start) {
                // Backspace: the removed text grows to the left and the step starts earlier.
                undo.removeLast()
                undo.addLast(Edit(s, removed + last.removed, "", last.selBefore, Pair(s, s), kind, now, last.idBefore, nextId++))
            } else last.removed += removed
            undo.last().time = now
            undo.last().idAfter = nextId++
            undo.last().selAfter = Pair(end, end)
        } else {
            undo.addLast(Edit(s, removed, text, selBefore, Pair(end, end), kind, now, stateId, nextId++))
        }
        stateId = undo.last().idAfter
        sealed = kind == Kind.OTHER
        held += removed.length + text.length
        trim()
        return end
    }

    /** Sets the selection a step leaves behind (a paste selects nothing, an indent keeps its lines selected). */
    fun setSelectionAfter(sel: Pair<Pos, Pos>) {
        undo.lastOrNull()?.selAfter = sel
    }

    fun undo(): Pair<Pos, Pos>? {
        val e = undo.removeLastOrNull() ?: return null
        val end = TextBuffer.endOf(e.start, e.inserted)
        buffer.replace(e.start, end, e.removed)
        redo.addLast(e)
        stateId = e.idBefore
        sealed = true
        return e.selBefore
    }

    fun redo(): Pair<Pos, Pos>? {
        val e = redo.removeLastOrNull() ?: return null
        val end = TextBuffer.endOf(e.start, e.removed)
        buffer.replace(e.start, end, e.inserted)
        undo.addLast(e)
        stateId = e.idAfter
        sealed = true
        return e.selAfter
    }

    private fun trim() {
        while (held > limitChars && undo.size > 1) {
            val old = undo.removeFirst()
            held -= old.removed.length + old.inserted.length
        }
    }
}
