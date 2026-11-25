// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What one editor shows of a document: its cursor, selection and scroll, kept per tab. */
class EditorSession(val doc: Document) {
    var caret = Pos.ZERO
    var anchor = Pos.ZERO
    var scrollX = 0f
    var scrollY = 0f
    /** Visual column the cursor keeps while moving up and down through shorter lines; -1 when unset. */
    var stickyCol = -1
    /** The IME's composing text, if any. */
    var composing: Pair<Pos, Pos>? = null
    val hasSelection get() = caret != anchor
    val selStart get() = Pos.min(caret, anchor)
    val selEnd get() = Pos.max(caret, anchor)
}

/** Editor settings read once per change of the preferences. */
class EditorConfig(p: TeditPrefs, terminal: Boolean = false) {
    /** Changed live while pinching; the setting is written when the pinch ends. */
    var fontSize = p.float(if (terminal) TeditPrefs.TERM_FONT else TeditPrefs.FONT_SIZE)
    val lineHeight = if (terminal) 1.25f else p.float(TeditPrefs.LINE_HEIGHT)
    val tabSize = if (terminal) 8 else p.int(TeditPrefs.TAB_SIZE).coerceIn(1, 8)
    val insertSpaces = p.bool(TeditPrefs.INSERT_SPACES)
    val wrap = terminal || p.bool(TeditPrefs.WORD_WRAP)
    val lineNumbers = if (terminal) "off" else p.string(TeditPrefs.LINE_NUMBERS)
    val highlightLine = !terminal && p.bool(TeditPrefs.HIGHLIGHT_LINE)
    val autoClose = !terminal && p.bool(TeditPrefs.AUTO_CLOSE)
    val bracketMatch = !terminal && p.bool(TeditPrefs.BRACKET_MATCH)
    val smooth = p.bool(TeditPrefs.SMOOTH_SCROLL)
    val minimap = !terminal && p.bool(TeditPrefs.MINIMAP)
    val cursorStyle = p.string(TeditPrefs.CURSOR_STYLE)
    val blink = p.bool(TeditPrefs.CURSOR_BLINK)
    val guides = !terminal && p.bool(TeditPrefs.INDENT_GUIDES)
    val whitespace = if (terminal) "none" else p.string(TeditPrefs.WHITESPACE)
    val autoIndent = p.bool(TeditPrefs.AUTO_INDENT)
    val highlightMax = p.int(TeditPrefs.HIGHLIGHT_MAX)
    /** False for a terminal's output: the command line under it takes the typing, so a tap opens no keyboard. */
    val keyboard = !terminal
    /** Room to scroll the last line up the screen; a terminal keeps its last line at the bottom instead. */
    val pastEnd = !terminal
}

/** What the editor asks of the window around it. */
interface EditorHost {
    fun editorFocused(v: CodeEditView)
    fun caretMoved(v: CodeEditView)
    fun textEdited(v: CodeEditView)
    /** Shows the bar of Cut, Copy, Paste... above (or below) the rectangle [top]..[bottom] at [x]. */
    fun showSelectionBar(v: CodeEditView, x: Float, top: Float, bottom: Float)
    fun hideSelectionBar(v: CodeEditView)
    fun showEditorMenu(v: CodeEditView, x: Float, y: Float)
    /** Tool-wide shortcuts (save, find, tabs). True when used. */
    fun shortcut(v: CodeEditView, e: KeyEvent): Boolean
    fun readOnlyAttempt(v: CodeEditView)
    fun zoomed(v: CodeEditView, fontSize: Float, done: Boolean)
    fun message(text: CharSequence)
}

/**
 * The code editor: draws only the rows on screen, keeps a monospace grid (tabs and wide characters take their
 * cells), and edits through [EditHistory] so every change can be undone. Touch, mouse, the on-screen keyboard and
 * a hardware keyboard all work on the same [EditorSession].
 */
@SuppressLint("ViewConstructor")
class CodeEditView(context: Context, private var theme: TeditTheme, config: EditorConfig, private val host: EditorHost) : View(context) {
    var config: EditorConfig = config
        set(v) {
            field = v
            measureText()
            invalidateLayout()
        }

    var session: EditorSession? = null
        private set
    val doc: Document? get() = session?.doc
    var find: FindState? = null
        set(v) { field = v; invalidate() }
    /** Lines changed against the last commit, from Source Control: kind per line (1 added, 2 modified, 3 deleted below). */
    var lineMarks: IntArray? = null
        set(v) { field = v; invalidate() }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()

    private var charW = 10f
    private var lineH = 20f
    private var baseline = 15f
    private var gutterW = 0f
    private var contentLeft = 0f
    private var minimapW = 0f
    private val padTop get() = theme.dp(6f)

    private val scroller = OverScroller(context)
    private val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var caretOn = true
    private var lastActivity = 0L
    /** Insertion handle under the cursor, shown after a tap. */
    private var caretHandle = false
    /** Selection handles, shown after a touch selection. */
    private var selectionHandles = false
    private var draggingHandle = 0 // 1 start, 2 end, 3 caret
    private var mouseSelecting = false
    private var thumbDrag = false
    private var minimapDrag = false
    private var scaling = false
    private var maxCols = 0
    private var input: EditorInput? = null
    /** True once the person typed or moved since the keyboard showed: the IME may then forget its composing state. */
    var batchDepth = 0
    var tabMovesFocus = false

    private val listener: (TextBuffer.Change) -> Unit = { onBufferChange(it) }
    /** Colours worked out in the background (an extension's grammar) arrived; draw them. */
    private val refresh: () -> Unit = { invalidate() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        measureText()
        isHorizontalFadingEdgeEnabled = false
    }

    fun setTheme(t: TeditTheme) {
        theme = t
        measureText()
        invalidateLayout()
    }

    fun bind(s: EditorSession?) {
        if (s === session) return
        session?.doc?.buffer?.removeListener(listener)
        session?.doc?.highlighter?.onRefresh?.remove(refresh)
        session = s
        snippet = null
        s?.let { clampSession(it, it.doc.buffer) }
        bracketDoc = null
        bracketResult = null
        s?.doc?.buffer?.addListener(listener)
        s?.doc?.highlighter?.onRefresh?.add(refresh)
        s?.doc?.highlighter?.maxLine = config.highlightMax
        maxCols = 0
        caretHandle = false
        selectionHandles = false
        host.hideSelectionBar(this)
        invalidateLayout()
        input?.reset()
        if (hasFocus()) imm.restartInput(this)
        contentDescription = s?.doc?.name
    }

    private fun measureText() {
        val sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, config.fontSize, resources.displayMetrics)
        textPaint.typeface = theme.codeFont
        textPaint.textSize = sp
        gutterPaint.typeface = theme.codeFont
        gutterPaint.textSize = sp * 0.86f
        gutterPaint.textAlign = Paint.Align.RIGHT
        charW = textPaint.measureText("MMMMMMMMMM") / 10f
        val fm = textPaint.fontMetrics
        val glyph = fm.descent - fm.ascent
        lineH = ceil(max(glyph * 1.05f, sp * config.lineHeight))
        baseline = (lineH - glyph) / 2f - fm.ascent
    }

    // ---- layout of rows ----

    private var wrapCols = 0
    private var rowsPerLine: IntArray? = null
    private var rowStarts: IntArray? = null
    private var rowsValid = false
    private val breakCache = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 600
    }
    private val colCache = object : LinkedHashMap<String, IntArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 600
    }

    private fun invalidateLayout() {
        rowsValid = false
        breakCache.clear()
        colCache.clear()
        updateGutter()
        invalidate()
    }

    private fun updateGutter() {
        val d = doc
        val digits = max(3, (d?.buffer?.lineCount ?: 1).toString().length)
        gutterW = if (config.lineNumbers == "off") theme.dp(14f) else digits * gutterPaint.measureText("0") + theme.dp(22f)
        contentLeft = gutterW + theme.dp(6f)
        minimapW = if (config.minimap && width >= theme.dp(360f)) theme.dp(56f) else 0f
        val usable = width - contentLeft - theme.dp(10f) - minimapW
        wrapCols = max(8, floor(usable / charW).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldCols = wrapCols
        updateGutter()
        if (config.wrap && oldCols != wrapCols) { rowsValid = false; breakCache.clear() }
        clampScroll()
        // The keyboard shrank the window: keep the cursor in sight above it.
        if (h < oldh && hasFocus()) post { ensureCaretVisible(false) }
    }

    /**
     * Keeps [s]'s cursor, selection and composing text inside [buf]. Another view of the same document (a split, or
     * a tab not shown) can remove the lines they were on.
     */
    private fun clampSession(s: EditorSession, buf: TextBuffer) {
        s.caret = buf.clamp(s.caret)
        s.anchor = buf.clamp(s.anchor)
        s.composing = s.composing?.let { (a, b) -> buf.clamp(a) to buf.clamp(b) }
    }

    private fun onBufferChange(c: TextBuffer.Change) {
        val d = doc ?: return
        session?.let { clampSession(it, d.buffer) }
        val rows = rowsPerLine
        if (config.wrap && rowsValid && rows != null) {
            val n = d.buffer.lineCount
            val delta = c.newEndLine - c.oldEndLine
            val next = IntArray(n)
            System.arraycopy(rows, 0, next, 0, c.startLine)
            for (i in c.startLine..c.newEndLine) next[i] = breaksOf(i).size
            val tail = rows.size - (c.oldEndLine + 1)
            if (tail > 0) System.arraycopy(rows, c.oldEndLine + 1, next, c.newEndLine + 1, tail)
            rowsPerLine = next
            rebuildRowStarts(n)
            if (delta != 0) updateGutter()
        } else {
            rowsValid = false
            if (c.newEndLine != c.oldEndLine) updateGutter()
        }
        for (i in c.startLine..min(c.newEndLine, d.buffer.lineCount - 1)) {
            val cols = colOf(d.buffer.line(i), d.buffer.lineLength(i))
            if (cols > maxCols) maxCols = cols
        }
        if (c.startLine == 0 && c.oldEndLine > 0 && c.newEndLine == d.buffer.lineCount - 1) maxCols = 0
        invalidate()
    }

    private fun ensureRows() {
        val d = doc ?: return
        if (rowsValid) return
        if (maxCols == 0) {
            var m = 0
            for (i in 0 until d.buffer.lineCount) m = max(m, d.buffer.lineLength(i))
            maxCols = m
        }
        if (!config.wrap) {
            rowsPerLine = null
            rowStarts = null
            rowsValid = true
            return
        }
        val n = d.buffer.lineCount
        val rows = IntArray(n)
        for (i in 0 until n) {
            val s = d.buffer.line(i)
            rows[i] = if (s.length <= wrapCols && colMapOf(s) == null) 1 else breaksOf(i).size
        }
        rowsPerLine = rows
        rebuildRowStarts(n)
        rowsValid = true
    }

    private fun rebuildRowStarts(n: Int) {
        val rows = rowsPerLine ?: return
        val starts = if (rowStarts?.size == n + 1) rowStarts!! else IntArray(n + 1)
        var acc = 0
        for (i in 0 until n) {
            starts[i] = acc
            acc += rows[i]
        }
        starts[n] = acc
        rowStarts = starts
    }

    private fun totalRows(): Int {
        ensureRows()
        return rowStarts?.let { it[it.size - 1] } ?: (doc?.buffer?.lineCount ?: 1)
    }

    private fun firstRowOf(line: Int): Int {
        ensureRows()
        return rowStarts?.get(line) ?: line
    }

    private fun lineAtRow(row: Int): Int {
        ensureRows()
        val starts = rowStarts ?: return row.coerceIn(0, (doc?.buffer?.lineCount ?: 1) - 1)
        var lo = 0
        var hi = starts.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= row) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Visual column after each character of [s], or null when every character is printable ASCII (column = index). */
    private fun colMapOf(s: String): IntArray? {
        // A long line is scanned once and the answer kept with it; every row of a wrapped minified file asks again.
        val long = s.length > LONG_LINE
        if (long) colCache[s]?.let { return if (it === PLAIN_COLS) null else it }
        var simple = true
        for (ch in s) if (ch < ' ' || ch > '~') { simple = false; break }
        if (simple) {
            if (long) colCache[s] = PLAIN_COLS
            return null
        }
        if (!long) colCache[s]?.let { return it }
        val tab = config.tabSize
        val map = IntArray(s.length + 1)
        var col = 0
        var i = 0
        while (i < s.length) {
            map[i] = col
            val ch = s[i]
            when {
                ch == '\t' -> col = (col / tab + 1) * tab
                Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    val cp = Character.toCodePoint(ch, s[i + 1])
                    map[i + 1] = col
                    col += if (isWide(cp)) 2 else 1
                    i++
                }
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || ch == '\u200B' || ch == '\u200D' -> {}
                isWide(ch.code) -> col += 2
                else -> col += 1
            }
            i++
        }
        map[s.length] = col
        colCache[s] = map
        return map
    }

    private fun isWide(cp: Int) = (cp in 0x1100..0x115F) || (cp in 0x2E80..0x303E) || (cp in 0x3041..0x33FF) ||
        (cp in 0x3400..0x4DBF) || (cp in 0x4E00..0x9FFF) || (cp in 0xA000..0xA4CF) || (cp in 0xAC00..0xD7A3) ||
        (cp in 0xF900..0xFAFF) || (cp in 0xFE30..0xFE4F) || (cp in 0xFF00..0xFF60) || (cp in 0xFFE0..0xFFE6) ||
        (cp in 0x1F300..0x1F64F) || (cp in 0x1F900..0x1F9FF) || (cp in 0x20000..0x3FFFD)

    private fun colOf(s: String, idx: Int): Int {
        val m = colMapOf(s) ?: return idx
        return m[idx.coerceIn(0, s.length)]
    }

    /** The index in [s] between [from] and [to] whose column is nearest [col]. */
    private fun indexAtCol(s: String, col: Float, from: Int, to: Int): Int {
        val m = colMapOf(s) ?: return col.roundToInt().coerceIn(from, to)
        var best = from
        var bestD = Float.MAX_VALUE
        // Binary search for the first index at or past col, then compare with its neighbour.
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (m[mid] < col) lo = mid + 1 else hi = mid
        }
        for (k in max(from, lo - 1)..min(to, lo)) {
            if (k < s.length && k > 0 && Character.isLowSurrogate(s[k]) && Character.isHighSurrogate(s[k - 1])) continue
            val d = abs(m[k] - col)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    /** Where each visual row of [line] starts. One row when not wrapping. */
    private fun breaksOf(line: Int): IntArray {
        val d = doc ?: return ONE_ROW
        if (!config.wrap) return ONE_ROW
        val s = d.buffer.line(line)
        if (s.length <= wrapCols && colMapOf(s) == null) return ONE_ROW
        breakCache[s]?.let { return it }
        val m = colMapOf(s)
        fun c(i: Int) = m?.get(i) ?: i
        val out = ArrayList<Int>()
        out.add(0)
        var rowStart = 0
        var lastSpace = -1
        var i = 0
        while (i < s.length) {
            if (c(i + 1) - c(rowStart) > wrapCols && i > rowStart) {
                val br = if (lastSpace > rowStart) lastSpace + 1 else i
                out.add(br)
                rowStart = br
                lastSpace = -1
                if (br <= i) { i = br; continue }
            }
            if (s[i] == ' ' || s[i] == '\t') lastSpace = i
            i++
        }
        val arr = out.toIntArray()
        breakCache[s] = arr
        return arr
    }

    private fun rowIn(br: IntArray, col: Int): Int {
        var k = br.size - 1
        while (k > 0 && br[k] > col) k--
        return k
    }

    // ---- geometry ----

    private fun rowY(row: Int): Float = padTop + row * lineH - (session?.scrollY ?: 0f)

    private fun rowOfPos(p: Pos): Int = firstRowOf(p.line) + rowIn(breaksOf(p.line), p.col)

    fun xOfPos(p: Pos): Float {
        val d = doc ?: return contentLeft
        val s = d.buffer.line(p.line)
        val br = breaksOf(p.line)
        val k = rowIn(br, p.col)
        return contentLeft + (colOf(s, p.col) - colOf(s, br[k])) * charW - (session?.scrollX ?: 0f)
    }

    fun yOfPos(p: Pos): Float = rowY(rowOfPos(p))
    val lineHeightPx get() = lineH

    fun posAt(x: Float, y: Float): Pos {
        val d = doc ?: return Pos.ZERO
        val total = totalRows()
        val row = floor((y - padTop + (session?.scrollY ?: 0f)) / lineH).toInt().coerceIn(0, total - 1)
        val line = lineAtRow(row)
        val br = breaksOf(line)
        val k = (row - firstRowOf(line)).coerceIn(0, br.size - 1)
        val s = d.buffer.line(line)
        val start = br[k]
        val end = if (k + 1 < br.size) br[k + 1] else s.length
        val col0 = colOf(s, start)
        val target = (x - contentLeft + (session?.scrollX ?: 0f)) / charW + col0
        var idx = indexAtCol(s, target, start, end)
        if (k + 1 < br.size && idx >= end) idx = max(start, end - 1)
        return Pos(line, idx)
    }

    // ---- scrolling ----

    private fun maxScrollY(): Float = max(0f, padTop + totalRows() * lineH + (if (config.pastEnd) height * 0.4f else padTop) - height)
    private fun maxScrollX(): Float = if (config.wrap) 0f else max(0f, maxCols * charW + theme.dp(40f) - (width - contentLeft - minimapW))

    private fun clampScroll() {
        val s = session ?: return
        s.scrollY = s.scrollY.coerceIn(0f, maxScrollY())
        s.scrollX = s.scrollX.coerceIn(0f, maxScrollX())
    }

    /** True when the last row is on screen, so new output can keep it there. */
    fun atBottom(): Boolean = (session?.scrollY ?: 0f) >= maxScrollY() - lineH

    /** Scrolls to the last row; the limit is applied again when the view is next drawn at its real size. */
    fun scrollToEnd() {
        val s = session ?: return
        s.scrollY = Float.MAX_VALUE
        invalidate()
    }

    fun scrollToY(y: Float) {
        val s = session ?: return
        s.scrollY = y
        clampScroll()
        invalidate()
    }

    override fun computeScroll() {
        val s = session ?: return
        if (scroller.computeScrollOffset()) {
            s.scrollX = scroller.currX.toFloat()
            s.scrollY = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
            if (scroller.isFinished) afterScroll()
        }
    }

    private fun afterScroll() {
        if (selectionHandles && session?.hasSelection == true) showBar()
    }

    /** Scrolls so the cursor is on screen with a line of room; [center] puts it in the middle (go to line, find). */
    fun ensureCaretVisible(center: Boolean) {
        val s = session ?: return
        if (width == 0 || height == 0) { post { ensureCaretVisible(center) }; return }
        val row = rowOfPos(s.caret)
        val top = padTop + row * lineH
        if (center) {
            s.scrollY = top - height / 2f + lineH / 2f
        } else {
            val margin = lineH
            if (top - margin < s.scrollY) s.scrollY = top - margin
            if (top + lineH + margin > s.scrollY + height) s.scrollY = top + lineH + margin - height
        }
        if (!config.wrap) {
            val d = s.doc
            val line = d.buffer.line(s.caret.line)
            val x = colOf(line, s.caret.col) * charW
            val visible = width - contentLeft - minimapW - theme.dp(12f)
            val m = charW * 4
            if (x - m < s.scrollX) s.scrollX = max(0f, x - m)
            if (x + m > s.scrollX + visible) s.scrollX = x + m - visible
            if (colOf(line, s.caret.col) > maxCols) maxCols = colOf(line, line.length)
        }
        clampScroll()
        invalidate()
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(theme.bg)
        val s = session ?: return
        val d = s.doc
        val buf = d.buffer
        clampSession(s, buf)
        ensureRows()
        clampScroll()
        val total = totalRows()
        val firstRow = max(0, floor((s.scrollY - padTop) / lineH).toInt())
        val lastRow = min(total - 1, ceil((s.scrollY - padTop + height) / lineH).toInt())
        val f = find
        if (f != null && f.active) f.update(buf)
        val selS = s.selStart
        val selE = s.selEnd
        val caretLine = s.caret.line
        val focused = hasFocus()
        val bracket = if (config.bracketMatch && !s.hasSelection) bracketAt(d, s.caret) else null

        canvas.save()
        canvas.clipRect(contentLeft - theme.dp(2f), 0f, width - minimapW, height.toFloat())
        var row = firstRow
        var line = if (row <= lastRow) lineAtRow(row) else 0
        while (row <= lastRow && line < buf.lineCount) {
            val text = buf.line(line)
            val br = breaksOf(line)
            val lineFirstRow = firstRowOf(line)
            val spans = d.highlighter.spans(line)
            for (k in (row - lineFirstRow) until br.size) {
                if (row > lastRow) break
                val y = rowY(row)
                val rs = br[k]
                val re = if (k + 1 < br.size) br[k + 1] else text.length
                val col0 = colOf(text, rs)
                if (config.highlightLine && line == caretLine && !s.hasSelection) {
                    fill.color = theme.activeLine
                    canvas.drawRect(contentLeft - theme.dp(2f), y, width.toFloat(), y + lineH, fill)
                }
                if (config.guides && k == 0) drawGuides(canvas, line, y, caretLine)
                if (f != null && f.count > 0) drawMatches(canvas, f, line, text, rs, re, col0, y)
                if (s.hasSelection && line >= selS.line && line <= selE.line) drawSelection(canvas, text, line, rs, re, col0, y, selS, selE, k == br.size - 1)
                s.composing?.let { (a, b) -> if (line >= a.line && line <= b.line) drawComposing(canvas, text, line, rs, re, col0, y, a, b) }
                if (bracket != null) for (bp in bracket) if (bp.line == line && bp.col in rs until re.coerceAtLeast(rs + 1)) {
                    val x = contentLeft + (colOf(text, bp.col) - col0) * charW - s.scrollX
                    strokePaint.color = theme.bracketBox
                    strokePaint.strokeWidth = theme.dp(1f)
                    rect.set(x, y + theme.dp(1f), x + charW, y + lineH - theme.dp(1f))
                    canvas.drawRect(rect, strokePaint)
                }
                drawRowText(canvas, text, spans, rs, re, col0, y + baseline)
                if (d.diagnostics.isNotEmpty()) drawDiagnostics(canvas, d.diagnostics, line, text, rs, re, col0, y)
                if (config.whitespace != "none") drawWhitespace(canvas, text, rs, re, col0, y + baseline)
                row++
            }
            line++
        }
        // Cursor
        if (focused && (caretOn || !config.blink) && s.caret.line in buf.lineCount.let { 0 until it }) drawCaret(canvas, s)
        canvas.restore()

        drawGutter(canvas, s, firstRow, lastRow, caretLine)
        if (minimapW > 0f) drawMinimap(canvas, s)
        drawScrollbars(canvas, s, total)
        if (selectionHandles && s.hasSelection) {
            drawHandle(canvas, selS, true)
            drawHandle(canvas, selE, false)
        } else if (caretHandle && focused && !s.hasSelection) drawHandle(canvas, s.caret, null)
    }

    private fun drawRowText(canvas: Canvas, text: String, spans: IntArray, rs: Int, re: Int, col0: Int, y: Float) {
        val s = session ?: return
        if (re <= rs) return
        // Only the columns on screen are drawn: a long line costs what fits on screen.
        var a = rs
        var b = re
        if (!config.wrap) {
            val firstCol = floor(s.scrollX / charW) - 1
            val lastCol = firstCol + (width - contentLeft) / charW + 3
            a = max(rs, indexAtCol(text, firstCol, rs, re) - 1)
            b = min(re, indexAtCol(text, lastCol, rs, re) + 1)
            if (a > 0 && Character.isLowSurrogate(text[a])) a--
        }
        var pos = a
        var i = 0
        val colors = theme.syntax
        while (pos < b) {
            // Find the span covering pos (spans are sorted and do not overlap).
            while (i < spans.size && spans[i + 1] <= pos) i += 3
            val kind: Int
            val end: Int
            if (i < spans.size && spans[i] <= pos) {
                kind = spans[i + 2]
                end = min(b, spans[i + 1])
            } else {
                kind = Token.PLAIN
                end = if (i < spans.size) min(b, spans[i]) else b
            }
            textPaint.color = colors[kind]
            drawSegment(canvas, text, pos, end, col0, y)
            pos = end
        }
    }

    /** Holds the characters of one drawn segment. */
    private var segment = CharArray(256)

    /**
     * Draws [text] from [from] to [to]. Android's drawText(String, start, end) copies the whole string across JNI on
     * every call, so a 400-character line drawn in twenty pieces was copied twenty times a frame; only the piece is
     * copied here.
     */
    private fun drawChars(canvas: Canvas, text: String, from: Int, to: Int, x: Float, y: Float) {
        val n = to - from
        if (n <= 0) return
        if (n > segment.size) segment = CharArray(maxOf(n, segment.size * 2))
        text.toCharArray(segment, 0, from, to)
        canvas.drawText(segment, 0, n, x, y, textPaint)
    }

    private fun drawSegment(canvas: Canvas, text: String, from: Int, to: Int, col0: Int, y: Float) {
        val sx = (session?.scrollX ?: 0f)
        val m = colMapOf(text)
        if (m == null) {
            drawChars(canvas, text, from, to, contentLeft + (from - col0) * charW - sx, y)
            return
        }
        var j = from
        while (j < to) {
            val ch = text[j]
            val x = contentLeft + (m[j] - col0) * charW - sx
            when {
                ch in ' '..'~' -> {
                    var k = j + 1
                    while (k < to && text[k] in ' '..'~') k++
                    drawChars(canvas, text, j, k, x, y)
                    j = k
                }
                ch == '\t' -> j++
                Character.isHighSurrogate(ch) && j + 1 < to -> { drawChars(canvas, text, j, j + 2, x, y); j += 2 }
                ch < ' ' || ch == '\u007F' -> {
                    // Control characters show as their Unicode picture (␛ for ESC), in the warning colour.
                    val keep = textPaint.color
                    textPaint.color = theme.syntax[Token.INVALID]
                    canvas.drawText(if (ch == '\u007F') "␡" else (0x2400 + ch.code).toChar().toString(), x, y, textPaint)
                    textPaint.color = keep
                    j++
                }
                else -> { drawChars(canvas, text, j, j + 1, x, y); j++ }
            }
        }
    }

    private fun drawWhitespace(canvas: Canvas, text: String, rs: Int, re: Int, col0: Int, y: Float) {
        val sx = session?.scrollX ?: 0f
        val from = if (config.whitespace == "trailing") max(rs, text.trimEnd(' ', '\t').length) else rs
        val keep = textPaint.color
        textPaint.color = theme.indentGuideActive
        for (j in from until re) {
            val ch = text[j]
            if (ch != ' ' && ch != '\t') continue
            val x = contentLeft + (colOf(text, j) - col0) * charW - sx
            canvas.drawText(if (ch == ' ') "·" else "→", x, y, textPaint)
        }
        textPaint.color = keep
    }

    private fun indentCols(line: Int): Int {
        val d = doc ?: return 0
        val s = d.buffer.line(line)
        var i = 0
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        if (i == s.length) return -1
        return colOf(s, i)
    }

    private fun drawGuides(canvas: Canvas, line: Int, y: Float, caretLine: Int) {
        val d = doc ?: return
        var indent = indentCols(line)
        if (indent < 0) {
            // A blank line takes the smaller indent of the lines around it, so guides do not break at blank lines.
            var up = line - 1
            var upI = -1
            while (up >= 0 && line - up < 200) { upI = indentCols(up); if (upI >= 0) break; up-- }
            var dn = line + 1
            var dnI = -1
            while (dn < d.buffer.lineCount && dn - line < 200) { dnI = indentCols(dn); if (dnI >= 0) break; dn++ }
            indent = min(max(upI, 0), max(dnI, 0))
        }
        val tab = config.tabSize
        val sx = session?.scrollX ?: 0f
        val caretIndent = indentCols(caretLine).let { if (it < 0) 0 else it }
        var level = tab
        while (level < indent + 1 && level <= indent) {
            val x = contentLeft + (level - tab) * charW - sx + charW * 0.1f
            val activeGuide = line != caretLine && level == caretIndent && abs(line - caretLine) < 400
            fill.color = if (activeGuide) theme.indentGuideActive else theme.indentGuide
            canvas.drawRect(x, y, x + max(1f, theme.rawDp(1f)), y + lineH, fill)
            level += tab
        }
    }

    private fun drawSelection(canvas: Canvas, text: String, line: Int, rs: Int, re: Int, col0: Int, y: Float, a: Pos, b: Pos, lastRow: Boolean) {
        val sx = session?.scrollX ?: 0f
        val from = if (line == a.line) max(rs, a.col) else rs
        val to = if (line == b.line) min(re, b.col) else re
        if (from > to || (line == a.line && a.col > re) || (line == b.line && b.col < rs)) return
        val x1 = contentLeft + (colOf(text, from) - col0) * charW - sx
        var x2 = contentLeft + (colOf(text, to) - col0) * charW - sx
        // The line break is part of the selection when it continues below: show it as one cell.
        if (line < b.line && lastRow && to == re) x2 += charW * 0.6f
        if (x2 <= x1) return
        fill.color = theme.selection
        canvas.drawRect(x1, y, x2, y + lineH, fill)
    }

    private fun drawComposing(canvas: Canvas, text: String, line: Int, rs: Int, re: Int, col0: Int, y: Float, a: Pos, b: Pos) {
        val sx = session?.scrollX ?: 0f
        val from = if (line == a.line) max(rs, a.col) else rs
        val to = if (line == b.line) min(re, b.col) else re
        if (to <= from) return
        val x1 = contentLeft + (colOf(text, from) - col0) * charW - sx
        val x2 = contentLeft + (colOf(text, to) - col0) * charW - sx
        fill.color = theme.text
        canvas.drawRect(x1, y + lineH - theme.dp(2.5f), x2, y + lineH - theme.dp(1f), fill)
    }

    private fun drawMatches(canvas: Canvas, f: FindState, line: Int, text: String, rs: Int, re: Int, col0: Int, y: Float) {
        val sx = session?.scrollX ?: 0f
        var i = f.firstOnOrAfter(line)
        while (i < f.count && f.matches[i * 3] == line) {
            val s = max(rs, f.matches[i * 3 + 1])
            val e = min(re, f.matches[i * 3 + 2])
            if (e > s || (f.matches[i * 3 + 1] == f.matches[i * 3 + 2] && s in rs..re)) {
                val x1 = contentLeft + (colOf(text, s) - col0) * charW - sx
                val x2 = max(x1 + theme.dp(2f), contentLeft + (colOf(text, e) - col0) * charW - sx)
                fill.color = if (i == f.current) theme.findCurrent else theme.findMatch
                canvas.drawRect(x1, y, x2, y + lineH, fill)
            }
            i++
        }
    }

    private val squiggle = android.graphics.Path()

    /** A wavy line under what the language server reports: red for errors, amber for warnings, blue for notes. */
    private fun drawDiagnostics(canvas: Canvas, list: List<com.lxlauncher.tedit.lsp.Diagnostic>, line: Int, text: String, rs: Int, re: Int, col0: Int, y: Float) {
        val sx = session?.scrollX ?: 0f
        for (dg in list) {
            val r = dg.range
            if (line < r.start.line || line > r.end.line) continue
            var a = if (line == r.start.line) r.start.character else 0
            var b = if (line == r.end.line) r.end.character else text.length
            // An empty range marks the character at it, or the end of the line.
            if (b <= a) { b = minOf(a + 1, text.length); if (b <= a) { a = maxOf(0, text.length - 1); b = text.length } }
            a = maxOf(a, rs); b = minOf(b, re)
            if (b <= a) continue
            val x1 = contentLeft + (colOf(text, a) - col0) * charW - sx
            val x2 = contentLeft + (colOf(text, b) - col0) * charW - sx
            strokePaint.color = when (dg.severity) { 1 -> theme.error; 2 -> theme.warning; 3 -> theme.secondary; else -> theme.textMuted }
            strokePaint.strokeWidth = theme.dp(1.2f)
            val base = y + lineH - theme.dp(2f)
            val amp = theme.dp(1.5f)
            val step = theme.dp(2.5f)
            squiggle.reset()
            squiggle.moveTo(x1, base)
            var x = x1
            var up = true
            while (x < x2) { x = minOf(x + step, x2); squiggle.lineTo(x, if (up) base - amp else base); up = !up }
            canvas.drawPath(squiggle, strokePaint)
        }
    }

    /** Keys a popup over the editor wants first (the completion list's arrows, Enter, Tab, Escape). */
    var keyInterceptor: ((Int, KeyEvent) -> Boolean)? = null

    /** Where the cursor's line ends on screen, in this view's coordinates: x of the cursor, top and bottom of its row. */
    fun caretBox(): FloatArray? {
        val s = session ?: return null
        if (width == 0) return null
        val y = yOfPos(s.caret)
        return floatArrayOf(xOfPos(s.caret), y, y + lineH)
    }

    /** The identifier (letters, digits, _ and $) that ends at the cursor, with where it starts. */
    fun wordBeforeCaret(): Pair<Pos, String>? {
        val s = session ?: return null
        val line = s.doc.buffer.line(s.caret.line)
        var i = s.caret.col.coerceAtMost(line.length)
        while (i > 0 && (line[i - 1].isLetterOrDigit() || line[i - 1] == '_' || line[i - 1] == '$')) i--
        return Pos(s.caret.line, i) to line.substring(i, s.caret.col.coerceAtMost(line.length))
    }

    private fun drawCaret(canvas: Canvas, s: EditorSession) {
        val x = xOfPos(s.caret)
        val y = yOfPos(s.caret)
        if (y + lineH < 0 || y > height) return
        fill.color = theme.accent
        when (config.cursorStyle) {
            "block" -> {
                fill.color = TeditTheme.withAlpha(theme.accent, 0.55f)
                canvas.drawRect(x, y + theme.dp(1f), x + charW, y + lineH - theme.dp(1f), fill)
            }
            "underline" -> canvas.drawRect(x, y + lineH - theme.dp(3f), x + charW, y + lineH - theme.dp(1f), fill)
            else -> canvas.drawRect(x - theme.rawDp(1f), y + theme.dp(2f), x + theme.rawDp(1f), y + lineH - theme.dp(2f), fill)
        }
    }

    private fun drawGutter(canvas: Canvas, s: EditorSession, firstRow: Int, lastRow: Int, caretLine: Int) {
        fill.color = theme.bg
        canvas.drawRect(0f, 0f, gutterW, height.toFloat(), fill)
        val buf = s.doc.buffer
        val marks = lineMarks
        if (firstRow > lastRow) return
        var line = lineAtRow(firstRow)
        val numbers = config.lineNumbers
        val fm = gutterPaint.fontMetrics
        while (line < buf.lineCount) {
            val row = firstRowOf(line)
            if (row > lastRow) break
            val y = rowY(row)
            if (marks != null && line < marks.size && marks[line] != 0) {
                fill.color = when (marks[line]) { 1 -> theme.success; 2 -> theme.secondary; else -> theme.error }
                val h = if (marks[line] == 3) theme.dp(4f) else lineH * (breaksOf(line).size)
                canvas.drawRect(gutterW - theme.dp(4f), if (marks[line] == 3) y + lineH - theme.dp(2f) else y, gutterW - theme.dp(1.5f), (if (marks[line] == 3) y + lineH - theme.dp(2f) else y) + h, fill)
            }
            if (numbers != "off") {
                val label = if (numbers == "relative" && line != caretLine) abs(line - caretLine).toString() else (line + 1).toString()
                gutterPaint.color = if (line == caretLine) theme.gutterActive else theme.gutterText
                val by = y + (lineH - (fm.descent - fm.ascent)) / 2f - fm.ascent
                canvas.drawText(label, gutterW - theme.dp(10f), by, gutterPaint)
            }
            line++
        }
    }

    private fun drawScrollbars(canvas: Canvas, s: EditorSession, total: Int) {
        val content = padTop + total * lineH + height * 0.4f
        if (content > height * 1.05f) {
            val thumbH = max(theme.dp(36f), height * height / content)
            val top = (height - thumbH) * (s.scrollY / max(1f, maxScrollY()))
            fill.color = TeditTheme.withAlpha(theme.text2, if (thumbDrag) 0.55f else 0.28f)
            val w = if (thumbDrag) theme.dp(6f) else theme.dp(4f)
            rect.set(width - minimapW - w - theme.dp(2f), top, width - minimapW - theme.dp(2f), top + thumbH)
            canvas.drawRoundRect(rect, w, w, fill)
        }
        val mx = maxScrollX()
        if (mx > 0f) {
            val visible = width - contentLeft - minimapW
            val contentW = visible + mx
            val thumbW = max(theme.dp(36f), visible * visible / contentW)
            val left = contentLeft + (visible - thumbW) * (s.scrollX / mx)
            fill.color = TeditTheme.withAlpha(theme.text2, 0.22f)
            rect.set(left, height - theme.dp(5f), left + thumbW, height - theme.dp(2f))
            canvas.drawRoundRect(rect, theme.dp(2f), theme.dp(2f), fill)
        }
    }

    // ---- minimap ----

    private val miniLineH get() = theme.rawDp(2f)

    private fun miniFirstLine(s: EditorSession): Int {
        val buf = s.doc.buffer
        val visibleLines = (height / miniLineH).toInt()
        if (buf.lineCount <= visibleLines) return 0
        val topLine = lineAtRow(max(0, floor((s.scrollY - padTop) / lineH).toInt()))
        val frac = topLine.toFloat() / max(1, buf.lineCount - (height / lineH).toInt())
        return ((buf.lineCount - visibleLines) * frac.coerceIn(0f, 1f)).toInt()
    }

    private fun drawMinimap(canvas: Canvas, s: EditorSession) {
        val left = width - minimapW
        fill.color = theme.panel
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), fill)
        val buf = s.doc.buffer
        val first = miniFirstLine(s)
        val cw = (minimapW - theme.dp(8f)) / 80f
        val h = miniLineH
        var y = 0f
        var line = first
        val colors = theme.syntax
        while (line < buf.lineCount && y < height) {
            val text = buf.line(line)
            if (text.isNotEmpty()) {
                val spans = s.doc.highlighter.spans(line)
                var lead = 0
                while (lead < text.length && (text[lead] == ' ' || text[lead] == '\t')) lead++
                fill.color = TeditTheme.withAlpha(colors[Token.PLAIN], 0.35f)
                val end = min(text.length, 80)
                if (end > lead) canvas.drawRect(left + theme.dp(4f) + lead * cw, y, left + theme.dp(4f) + end * cw, y + h * 0.75f, fill)
                var i = 0
                while (i < spans.size && spans[i] < 80) {
                    fill.color = TeditTheme.withAlpha(colors[spans[i + 2]], 0.8f)
                    canvas.drawRect(left + theme.dp(4f) + spans[i] * cw, y, left + theme.dp(4f) + min(80, spans[i + 1]) * cw, y + h * 0.75f, fill)
                    i += 3
                }
            }
            y += h
            line++
        }
        // The part the editor shows.
        val topLine = lineAtRow(max(0, floor((s.scrollY - padTop) / lineH).toInt()))
        val shown = (height / lineH)
        fill.color = TeditTheme.withAlpha(theme.text, 0.08f)
        canvas.drawRect(left, (topLine - first) * h, width.toFloat(), (topLine - first + shown) * h, fill)
    }

    // ---- handles ----

    private fun handleCenter(p: Pos, start: Boolean?): Pair<Float, Float> {
        val x = xOfPos(p)
        val y = yOfPos(p) + lineH
        val r = theme.dp(11f)
        return when (start) {
            true -> (x - r) to (y + r)
            false -> (x + r) to (y + r)
            null -> x to (y + r * 1.25f)
        }
    }

    private fun drawHandle(canvas: Canvas, p: Pos, start: Boolean?) {
        val x = xOfPos(p)
        val y = yOfPos(p) + lineH
        if (y < 0 || y > height + theme.dp(30f) || x < contentLeft - theme.dp(4f)) return
        val r = theme.dp(11f)
        fill.color = theme.accent
        fill.isAntiAlias = true
        path.reset()
        when (start) {
            true -> { path.addCircle(x - r, y + r, r, Path.Direction.CW); path.addRect(x - r, y, x, y + r, Path.Direction.CW) }
            false -> { path.addCircle(x + r, y + r, r, Path.Direction.CW); path.addRect(x, y, x + r, y + r, Path.Direction.CW) }
            null -> {
                path.addCircle(x, y + r * 1.25f, r, Path.Direction.CW)
                path.moveTo(x, y); path.lineTo(x - r * 0.7f, y + r * 0.6f); path.lineTo(x + r * 0.7f, y + r * 0.6f); path.close()
            }
        }
        canvas.drawPath(path, fill)
        fill.isAntiAlias = false
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val s = session ?: return 0
        val reach = theme.dp(26f)
        fun near(c: Pair<Float, Float>) = abs(c.first - x) < reach && abs(c.second - y) < reach
        if (selectionHandles && s.hasSelection) {
            if (near(handleCenter(s.selEnd, false))) return 2
            if (near(handleCenter(s.selStart, true))) return 1
        } else if (caretHandle && near(handleCenter(s.caret, null))) return 3
        return 0
    }

    // ---- touch ----

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private var axis = 0 // 0 undecided, 1 vertical, 2 horizontal, 3 free

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) scroller.forceFinished(true)
            axis = 0
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val s = session ?: return true
            val p = posAt(e.x, e.y)
            setSelection(p, p)
            caretHandle = true
            selectionHandles = false
            host.hideSelectionBar(this@CodeEditView)
            if (config.keyboard) showKeyboard()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            selectWordAt(posAt(e.x, e.y))
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling) return
            val s = session ?: return
            val p = posAt(e.x, e.y)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (s.hasSelection && p >= s.selStart && p <= s.selEnd) {
                selectionHandles = true
                showBar()
                return
            }
            val line = s.doc.buffer.line(p.line)
            if (line.isEmpty() || p.col >= line.length && line.isBlank()) {
                setSelection(p, p)
                host.showEditorMenu(this@CodeEditView, e.x, e.y)
                return
            }
            selectWordAt(p)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val s = session ?: return false
            if (scaling) return true
            if (axis == 0) axis = when {
                config.wrap -> 1
                abs(dy) > abs(dx) * 1.6f -> 1
                abs(dx) > abs(dy) * 1.6f -> 2
                else -> 3
            }
            if (axis != 2) s.scrollY += dy
            if (axis != 1) s.scrollX += dx
            clampScroll()
            host.hideSelectionBar(this@CodeEditView)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val s = session ?: return false
            if (scaling) return true
            val fx = if (axis == 1 || config.wrap) 0 else -vx.toInt()
            val fy = if (axis == 2) 0 else -vy.toInt()
            if (!config.smooth) return true
            scroller.fling(s.scrollX.toInt(), s.scrollY.toInt(), fx, fy, 0, maxScrollX().toInt(), 0, maxScrollY().toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var size = 14f
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            size = config.fontSize
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            size = (size * detector.scaleFactor).coerceIn(9f, 28f)
            host.zoomed(this@CodeEditView, (size * 2).roundToInt() / 2f, false)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            host.zoomed(this@CodeEditView, (size * 2).roundToInt() / 2f, true)
            postDelayed({ scaling = false }, 120)
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = session ?: return super.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && !hasFocus()) requestFocus()
        val mouse = e.isFromSource(InputDevice.SOURCE_MOUSE)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHandle = hitHandle(e.x, e.y)
                thumbDrag = !mouse && draggingHandle == 0 && e.x > width - minimapW - theme.dp(22f) && e.x < width - minimapW && maxScrollY() > height
                minimapDrag = minimapW > 0 && e.x >= width - minimapW
                if (mouse && e.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    val p = posAt(e.x, e.y)
                    if (!s.hasSelection || p < s.selStart || p > s.selEnd) setSelection(p, p)
                    host.showEditorMenu(this, e.x, e.y)
                    return true
                }
                if (mouse && e.isButtonPressed(MotionEvent.BUTTON_PRIMARY) && !thumbDrag && !minimapDrag) {
                    mouseSelecting = true
                    val p = posAt(e.x, e.y)
                    if (e.metaState and KeyEvent.META_SHIFT_ON != 0) setSelection(s.anchor, p) else setSelection(p, p)
                    caretHandle = false
                    selectionHandles = false
                    return true
                }
                if (draggingHandle != 0 || thumbDrag || minimapDrag) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (thumbDrag || minimapDrag) { dragScroll(e.y); invalidate() }
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mouseSelecting) {
                    setSelection(s.anchor, posAt(e.x, e.y))
                    autoScrollAt(e.x, e.y)
                    return true
                }
                if (thumbDrag || minimapDrag) { dragScroll(e.y); return true }
                if (draggingHandle != 0) {
                    val p = posAt(e.x, e.y - lineH - theme.dp(11f))
                    when (draggingHandle) {
                        1 -> if (p < s.selEnd) setSelection(s.selEnd, p, keepHandles = true)
                        2 -> if (p > s.selStart) setSelection(s.selStart, p, keepHandles = true)
                        3 -> setSelection(p, p, keepHandles = true)
                    }
                    autoScrollAt(e.x, e.y)
                    host.hideSelectionBar(this)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasHandle = draggingHandle
                mouseSelecting = false
                if (thumbDrag || minimapDrag) { thumbDrag = false; minimapDrag = false; invalidate(); return true }
                if (wasHandle != 0) {
                    draggingHandle = 0
                    if (wasHandle != 3) showBar()
                    return true
                }
            }
        }
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP && scroller.isFinished) afterScroll()
        return true
    }

    private fun dragScroll(y: Float) {
        val s = session ?: return
        if (minimapDrag) {
            val buf = s.doc.buffer
            val line = (miniFirstLine(s) + (y / miniLineH).toInt()).coerceIn(0, buf.lineCount - 1)
            s.scrollY = padTop + firstRowOf(line) * lineH - height / 2f
        } else {
            s.scrollY = (y / height).coerceIn(0f, 1f) * maxScrollY()
        }
        clampScroll()
        invalidate()
    }

    private fun autoScrollAt(x: Float, y: Float) {
        val s = session ?: return
        val edge = lineH * 1.5f
        when {
            y < edge -> s.scrollY -= lineH / 2
            y > height - edge -> s.scrollY += lineH / 2
        }
        if (!config.wrap) {
            if (x < contentLeft + charW * 2) s.scrollX -= charW * 2
            if (x > width - minimapW - charW * 2) s.scrollX += charW * 2
        }
        clampScroll()
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val s = session ?: return super.onGenericMotionEvent(e)
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (e.metaState and KeyEvent.META_CTRL_ON != 0) {
                host.zoomed(this, (config.fontSize + if (v > 0) 1f else -1f).coerceIn(9f, 28f), true)
                return true
            }
            if (e.metaState and KeyEvent.META_SHIFT_ON != 0) s.scrollX -= v * lineH * 3 else s.scrollY -= v * lineH * 3
            s.scrollX += h * charW * 4
            clampScroll()
            invalidate()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    private fun showBar() {
        val s = session ?: return
        if (!s.hasSelection) return
        val top = min(yOfPos(s.selStart), yOfPos(s.selEnd))
        val bottom = max(yOfPos(s.selStart), yOfPos(s.selEnd)) + lineH
        val x = ((xOfPos(s.selStart) + xOfPos(s.selEnd)) / 2f).coerceIn(contentLeft, width.toFloat())
        host.showSelectionBar(this, x, top, bottom + theme.dp(24f))
    }

    // ---- focus, keyboard, caret blink ----

    private val blinker = object : Runnable {
        override fun run() {
            if (!hasFocus() || !config.blink) return
            caretOn = SystemClock.uptimeMillis() - lastActivity < 500 || !caretOn
            invalidate()
            postDelayed(this, 530)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        if (!gainFocus) onBlur?.invoke()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        removeCallbacks(blinker)
        if (gainFocus) {
            host.editorFocused(this)
            caretOn = true
            postDelayed(blinker, 530)
        } else {
            caretHandle = false
            session?.composing = null
        }
        invalidate()
    }

    fun showKeyboard() {
        if (!hasFocus()) requestFocus()
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() = imm.hideSoftInputFromWindow(windowToken, 0)

    override fun onCheckIsTextEditor(): Boolean = config.keyboard

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val s = session
        if (s != null) {
            val buf = s.doc.buffer
            outAttrs.initialSelStart = buf.offsetOf(s.selStart)
            outAttrs.initialSelEnd = buf.offsetOf(s.selEnd)
        }
        return EditorInput(this).also { input = it }
    }

    /** Tells the keyboard where the cursor is; skipped while it is in the middle of a batch of edits. */
    fun notifyImeSelection() {
        if (batchDepth > 0) return
        val s = session ?: return
        val buf = s.doc.buffer
        val comp = s.composing
        val cs = comp?.let { buf.offsetOf(it.first) } ?: -1
        val ce = comp?.let { buf.offsetOf(it.second) } ?: -1
        // The keyboard reads selection start as the anchor, as an EditText reports it.
        imm.updateSelection(this, buf.offsetOf(s.anchor), buf.offsetOf(s.caret), cs, ce)
    }

    private fun activity() {
        lastActivity = SystemClock.uptimeMillis()
        caretOn = true
    }

    // ---- selection and editing ----

    fun setSelection(anchor: Pos, caret: Pos, keepHandles: Boolean = false, reveal: Boolean = true) {
        val s = session ?: return
        val buf = s.doc.buffer
        s.anchor = buf.clamp(anchor)
        s.caret = buf.clamp(caret)
        s.stickyCol = -1
        if (!keepHandles) {
            if (s.hasSelection) caretHandle = false else selectionHandles = false
        }
        activity()
        if (reveal) ensureCaretVisible(false)
        notifyImeSelection()
        host.caretMoved(this)
        invalidate()
    }

    fun selectAll() {
        val s = session ?: return
        setSelection(Pos.ZERO, s.doc.buffer.lastPos, reveal = false)
        if (isInTouchMode) { selectionHandles = true; showBar() }
    }

    private fun wordClass(c: Char) = when {
        c.isLetterOrDigit() || c == '_' -> 2
        c.isWhitespace() -> 0
        else -> 1
    }

    fun wordRangeAt(p: Pos): Pair<Pos, Pos> {
        val s = session ?: return p to p
        val line = s.doc.buffer.line(p.line)
        if (line.isEmpty()) return p to p
        var i = p.col.coerceIn(0, line.length - 1)
        if (p.col == line.length || (wordClass(line[i]) == 0 && i > 0 && wordClass(line[i - 1]) != 0)) i = max(0, i - 1)
        val cls = wordClass(line[i])
        var a = i
        var b = i + 1
        while (a > 0 && wordClass(line[a - 1]) == cls) a--
        while (b < line.length && wordClass(line[b]) == cls) b++
        return Pos(p.line, a) to Pos(p.line, b)
    }

    private fun selectWordAt(p: Pos) {
        val (a, b) = wordRangeAt(p)
        setSelection(a, b)
        if (isInTouchMode) {
            selectionHandles = true
            caretHandle = false
            showBar()
        }
    }

    val selectedText: String get() = session?.let { if (it.hasSelection) it.doc.buffer.text(it.selStart, it.selEnd) else "" } ?: ""

    private fun editable(): Boolean {
        val d = doc ?: return false
        if (d.readOnly || d.loading) { host.readOnlyAttempt(this); return false }
        return true
    }

    /** Replaces [a]..[b] with [text] as one undo step, and puts the cursor after it. */
    fun replace(a: Pos, b: Pos, text: String, kind: EditHistory.Kind = EditHistory.Kind.OTHER, select: Boolean = false): Pos? {
        val s = session ?: return null
        if (!editable()) return null
        val before = s.anchor to s.caret
        val start = Pos.min(a, b)
        lastEditTyped = kind == EditHistory.Kind.TYPE
        val end = s.doc.history.apply(a, b, text, before, kind, SystemClock.uptimeMillis())
        if (select) setSelection(start, end) else setSelection(end, end)
        s.doc.history.setSelectionAfter(s.anchor to s.caret)
        host.textEdited(this)
        return end
    }

    fun indentUnit(): String = if (config.insertSpaces) " ".repeat(config.tabSize) else "\t"

    private fun leadingWhitespace(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i)
    }

    /** Text typed by the keyboard or a key: brackets pair up, Enter keeps the indent, a typed closing bracket steps over. */
    fun typeText(raw: String, fromIme: Boolean = false) {
        val s = session ?: return
        if (!editable()) return
        val lang = s.doc.language
        var text = raw
        val buf = s.doc.buffer
        caretHandle = false
        if (text == "\n" || text == "\r\n" || text == "\r") {
            newline()
            return
        }
        if (text.length == 1 && !s.hasSelection) {
            val c = text[0]
            val line = buf.line(s.caret.line)
            val next = if (s.caret.col < line.length) line[s.caret.col] else '\u0000'
            val pairs = lang.autoClose
            // Typing the closing half that is already there steps over it.
            if (config.autoClose && next == c && closesPair(c, pairs)) {
                setSelection(Pos(s.caret.line, s.caret.col + 1), Pos(s.caret.line, s.caret.col + 1))
                return
            }
            val idx = openIndex(c, pairs)
            if (config.autoClose && idx >= 0) {
                val close = pairs[idx + 1]
                val prev = if (s.caret.col > 0) line[s.caret.col - 1] else ' '
                val quote = c == close
                val nextOk = next == '\u0000' || next.isWhitespace() || next in ")]}>,;:" || (quote && false)
                val prevOk = !quote || !(prev.isLetterOrDigit() || prev == '_' || prev == c)
                val inString = quote && s.doc.highlighter.kindAt(s.caret.line, max(0, s.caret.col - 1)).let { it == Token.STRING || it == Token.COMMENT } &&
                    s.caret.col > 0
                if (nextOk && prevOk && !inString) {
                    replace(s.caret, s.caret, "$c$close", EditHistory.Kind.TYPE)
                    val p = Pos(s.caret.line, s.caret.col - 1)
                    setSelection(p, p)
                    return
                }
            }
            // A closing brace typed on a line of only whitespace takes the indent of its opening line.
            if ((c == '}' || c == ']' || c == ')') && config.autoIndent && line.isNotEmpty() && line.substring(0, s.caret.col).isBlank()) {
                val open = matchBracketBackward(Pos(s.caret.line, s.caret.col), c)
                if (open != null) {
                    val indent = leadingWhitespace(buf.line(open.line))
                    replace(Pos(s.caret.line, 0), s.caret, indent + c, EditHistory.Kind.TYPE)
                    return
                }
            }
        } else if (text.length == 1 && s.hasSelection && config.autoClose) {
            // Typing a bracket or quote over a selection wraps it.
            val idx = openIndex(text[0], s.doc.language.autoClose)
            if (idx >= 0 && s.selStart.line == s.selEnd.line) {
                val inner = selectedText
                val a = s.selStart
                replace(s.selStart, s.selEnd, text + inner + s.doc.language.autoClose[idx + 1], EditHistory.Kind.OTHER)
                setSelection(Pos(a.line, a.col + 1), Pos(a.line, a.col + 1 + inner.length))
                return
            }
        }
        if (text.contains('\t') && config.insertSpaces && !fromIme && text == "\t") text = indentUnit()
        replace(s.selStart, s.selEnd, text, EditHistory.Kind.TYPE)
    }

    private fun openIndex(c: Char, pairs: String): Int {
        var i = 0
        while (i + 1 < pairs.length) {
            if (pairs[i] == c) return i
            i += 2
        }
        return -1
    }

    private fun closesPair(c: Char, pairs: String): Boolean {
        var i = 1
        while (i < pairs.length) {
            if (pairs[i] == c) return true
            i += 2
        }
        return false
    }

    fun newline() {
        val s = session ?: return
        if (!editable()) return
        val buf = s.doc.buffer
        val line = buf.line(s.selStart.line)
        var indent = if (config.autoIndent) leadingWhitespace(line).let { if (it.length > s.selStart.col) it.substring(0, s.selStart.col) else it } else ""
        val before = line.substring(0, s.selStart.col).trimEnd()
        val afterLine = buf.line(s.selEnd.line)
        val after = afterLine.substring(s.selEnd.col)
        val opener = before.lastOrNull()
        val opens = config.autoIndent && (opener == '{' || opener == '[' || opener == '(' ||
            (opener == ':' && s.doc.language.id in setOf("python", "yaml")))
        if (opens) {
            val inner = indent + indentUnit()
            val closer = after.trimStart().firstOrNull()
            val pairs = opener == '{' && closer == '}' || opener == '[' && closer == ']' || opener == '(' && closer == ')'
            if (pairs) {
                val start = s.selStart
                replace(s.selStart, s.selEnd, "\n" + inner + "\n" + indent, EditHistory.Kind.OTHER)
                val p = Pos(start.line + 1, inner.length)
                setSelection(p, p)
                return
            }
            indent = inner
        }
        replace(s.selStart, s.selEnd, "\n" + indent, EditHistory.Kind.OTHER)
    }

    fun backspace(word: Boolean = false) {
        val s = session ?: return
        if (!editable()) return
        if (s.hasSelection) { replace(s.selStart, s.selEnd, "", EditHistory.Kind.DELETE); return }
        val c = s.caret
        if (c == Pos.ZERO) return
        val buf = s.doc.buffer
        if (c.col == 0) { replace(Pos(c.line - 1, buf.lineLength(c.line - 1)), c, "", EditHistory.Kind.DELETE); return }
        val line = buf.line(c.line)
        if (word) {
            var i = c.col
            while (i > 0 && line[i - 1].isWhitespace()) i--
            val cls = if (i > 0) wordClass(line[i - 1]) else 0
            while (i > 0 && wordClass(line[i - 1]) == cls && cls != 0) i--
            replace(Pos(c.line, i), c, "", EditHistory.Kind.DELETE)
            return
        }
        // Between a pair typed together: remove both halves.
        val prev = line[c.col - 1]
        val next = if (c.col < line.length) line[c.col] else '\u0000'
        val pairs = s.doc.language.autoClose
        val oi = openIndex(prev, pairs)
        if (config.autoClose && oi >= 0 && pairs[oi + 1] == next) {
            replace(Pos(c.line, c.col - 1), Pos(c.line, c.col + 1), "", EditHistory.Kind.DELETE)
            return
        }
        // In leading spaces, step back to the previous tab stop.
        if (config.insertSpaces && prev == ' ' && line.substring(0, c.col).isBlank()) {
            val col = colOf(line, c.col)
            val target = ((col - 1) / config.tabSize) * config.tabSize
            var i = c.col
            while (i > 0 && line[i - 1] == ' ' && colOf(line, i - 1) >= target) i--
            replace(Pos(c.line, i), c, "", EditHistory.Kind.DELETE)
            return
        }
        val from = if (c.col >= 2 && Character.isLowSurrogate(prev) && Character.isHighSurrogate(line[c.col - 2])) c.col - 2 else c.col - 1
        replace(Pos(c.line, from), c, "", EditHistory.Kind.DELETE)
    }

    fun deleteForward(word: Boolean = false) {
        val s = session ?: return
        if (!editable()) return
        if (s.hasSelection) { replace(s.selStart, s.selEnd, "", EditHistory.Kind.DELETE); return }
        val c = s.caret
        val buf = s.doc.buffer
        val line = buf.line(c.line)
        if (c.col >= line.length) {
            if (c.line + 1 < buf.lineCount) replace(c, Pos(c.line + 1, 0), "", EditHistory.Kind.DELETE)
            return
        }
        var to = c.col + 1
        if (word) {
            while (to < line.length && line[to - 1].isWhitespace() && line[to].isWhitespace()) to++
            val cls = wordClass(line[to - 1])
            while (to < line.length && wordClass(line[to]) == cls) to++
        } else if (Character.isHighSurrogate(line[c.col]) && to < line.length) to++
        replace(c, Pos(c.line, to), "", EditHistory.Kind.DELETE)
    }

    // ---- line commands ----

    private fun selectedLines(): IntRange {
        val s = session ?: return 0..0
        val a = s.selStart
        var b = s.selEnd
        if (s.hasSelection && b.col == 0 && b.line > a.line) b = Pos(b.line - 1, 0)
        return a.line..b.line
    }

    /** Applies [transform] to whole lines as one undo step and keeps them selected when they were. */
    private fun editLines(range: IntRange, transform: (List<String>) -> List<String>, keepSelection: Boolean = true) {
        val s = session ?: return
        if (!editable()) return
        val buf = s.doc.buffer
        val old = range.map { buf.line(it) }
        val new = transform(old)
        if (new == old) return
        val hadSel = s.hasSelection
        val caretBefore = s.caret
        val end = Pos(range.last, buf.lineLength(range.last))
        replace(Pos(range.first, 0), end, new.joinToString("\n"), EditHistory.Kind.OTHER)
        if (keepSelection && hadSel) {
            setSelection(Pos(range.first, 0), Pos(range.first + new.size - 1, new.last().length))
        } else {
            // Keep the cursor on its line, moved by what changed before it.
            val li = caretBefore.line - range.first
            if (li in new.indices) {
                val delta = new[li].length - old.getOrElse(li) { "" }.length
                val p = Pos(caretBefore.line, (caretBefore.col + delta).coerceIn(0, new[li].length))
                setSelection(p, p)
            }
        }
        s.doc.history.setSelectionAfter(s.anchor to s.caret)
    }

    fun indentLines(outdent: Boolean) {
        val unit = indentUnit()
        val tab = config.tabSize
        editLines(selectedLines(), { lines ->
            lines.map { l ->
                if (!outdent) { if (l.isEmpty()) l else unit + l }
                else when {
                    l.startsWith("\t") -> l.substring(1)
                    else -> { var i = 0; while (i < tab && i < l.length && l[i] == ' ') i++; l.substring(i) }
                }
            }
        })
    }

    /** Tab: indents selected lines, or inserts one indent unit at the cursor (spaces to the next tab stop). */
    // ---- snippets ----

    /** Tab stops of the snippet last inserted, as offsets into the buffer; Tab walks them. */
    private class SnippetStops(val stops: MutableList<IntRange>, var index: Int, var length: Int)
    private var snippet: SnippetStops? = null

    /**
     * Inserts a snippet body (VS Code syntax) over [from]..caret, or over the selection, indented like the cursor's
     * line, and selects its first tab stop. Tab and Shift+Tab then move between the stops until the last one.
     */
    fun insertSnippet(body: String, from: Pos? = null) {
        val s = session ?: return
        if (!editable()) return
        val start = from ?: s.selStart
        val end = if (from != null) s.caret else s.selEnd
        val line = s.doc.buffer.line(start.line)
        val indent = line.substring(0, line.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line.length else it })
        val selected = if (from == null && s.hasSelection) s.doc.buffer.text(s.selStart, s.selEnd) else ""
        val exp = com.lxlauncher.tedit.ext.Snippets.expand(body, indent, s.doc.name, selected)
        replace(start, end, exp.text)
        val base = s.doc.buffer.offsetOf(start)
        val stops = exp.stops.map { (it.first + base) until (it.last + 1 + base) }.toMutableList()
        snippet = if (stops.size > 1) SnippetStops(stops, 0, s.doc.buffer.length) else null
        val first = stops.firstOrNull() ?: return
        setSelection(s.doc.buffer.posOf(first.first), s.doc.buffer.posOf(first.last + 1))
    }

    /** Moves to the next (or previous) tab stop of the snippet being filled; false when there is none. */
    private fun snippetStep(back: Boolean): Boolean {
        val sn = snippet ?: return false
        val s = session ?: return false
        val buf = s.doc.buffer
        // What was typed into the current stop moved the stops after it.
        val delta = buf.length - sn.length
        if (delta != 0) for (k in sn.index + 1 until sn.stops.size) sn.stops[k] = (sn.stops[k].first + delta) until (sn.stops[k].last + 1 + delta)
        sn.length = buf.length
        val next = sn.index + if (back) -1 else 1
        if (next !in sn.stops.indices) { snippet = null; return false }
        sn.index = next
        val r = sn.stops[next]
        val a = r.first.coerceIn(0, buf.length)
        val b = (r.last + 1).coerceIn(a, buf.length)
        setSelection(buf.posOf(a), buf.posOf(b))
        if (next == sn.stops.size - 1) snippet = null
        return true
    }

    fun tabKey(shift: Boolean) {
        val s = session ?: return
        if (snippetStep(shift)) return
        if (shift) { indentLines(true); return }
        if (s.hasSelection && s.selStart.line != s.selEnd.line) { indentLines(false); return }
        if (!config.insertSpaces) { typeText("\t", fromIme = true); return }
        val line = s.doc.buffer.line(s.caret.line)
        val col = colOf(line, s.caret.col)
        val spaces = config.tabSize - col % config.tabSize
        replace(s.selStart, s.selEnd, " ".repeat(spaces), EditHistory.Kind.TYPE)
    }

    fun toggleComment() {
        val s = session ?: return
        val lang = s.doc.language
        val lc = lang.lineComment
        val block = lang.blockComment
        if (lc == null && block == null) { host.message(context.getString(com.lxlauncher.R.string.tedit_no_comment, lang.name)); return }
        val range = selectedLines()
        if (lc != null) {
            editLines(range, { lines ->
                val nonBlank = lines.filter { it.isNotBlank() }
                val all = nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(lc) }
                val minIndent = nonBlank.minOfOrNull { leadingWhitespace(it).length } ?: 0
                lines.map { l ->
                    if (l.isBlank()) l
                    else if (all) {
                        val i = l.indexOf(lc)
                        val after = i + lc.length
                        l.substring(0, i) + l.substring(if (after < l.length && l[after] == ' ') after + 1 else after)
                    } else l.substring(0, minIndent) + lc + " " + l.substring(minIndent)
                }
            }, keepSelection = true)
        } else if (block != null) {
            editLines(range, { lines ->
                val joined = lines.joinToString("\n")
                val t = joined.trim()
                if (t.startsWith(block.first) && t.endsWith(block.second)) {
                    val a = joined.indexOf(block.first)
                    val b = joined.lastIndexOf(block.second)
                    var inner = joined.substring(a + block.first.length, b)
                    if (inner.startsWith(" ")) inner = inner.substring(1)
                    if (inner.endsWith(" ")) inner = inner.substring(0, inner.length - 1)
                    (joined.substring(0, a) + inner + joined.substring(b + block.second.length)).split('\n')
                } else {
                    val lead = leadingWhitespace(lines.first())
                    (lead + block.first + " " + joined.substring(lead.length) + " " + block.second).split('\n')
                }
            })
        }
    }

    fun duplicateLines(down: Boolean = true) {
        val s = session ?: return
        if (!editable()) return
        val r = selectedLines()
        val buf = s.doc.buffer
        val block = r.map { buf.line(it) }.joinToString("\n")
        val caret = s.caret
        val anchor = s.anchor
        replace(Pos(r.last, buf.lineLength(r.last)), Pos(r.last, buf.lineLength(r.last)), "\n" + block, EditHistory.Kind.OTHER)
        val shift = if (down) r.last - r.first + 1 else 0
        setSelection(Pos(anchor.line + shift, anchor.col), Pos(caret.line + shift, caret.col))
    }

    fun moveLines(up: Boolean) {
        val s = session ?: return
        if (!editable()) return
        val r = selectedLines()
        val buf = s.doc.buffer
        if (up && r.first == 0 || !up && r.last >= buf.lineCount - 1) return
        val caret = s.caret
        val anchor = s.anchor
        val moved = r.map { buf.line(it) }
        if (up) {
            val above = buf.line(r.first - 1)
            replace(Pos(r.first - 1, 0), Pos(r.last, buf.lineLength(r.last)), (moved + above).joinToString("\n"), EditHistory.Kind.OTHER)
            setSelection(Pos(anchor.line - 1, anchor.col), Pos(caret.line - 1, caret.col))
        } else {
            val below = buf.line(r.last + 1)
            replace(Pos(r.first, 0), Pos(r.last + 1, buf.lineLength(r.last + 1)), (listOf(below) + moved).joinToString("\n"), EditHistory.Kind.OTHER)
            setSelection(Pos(anchor.line + 1, anchor.col), Pos(caret.line + 1, caret.col))
        }
    }

    fun deleteLines() {
        val s = session ?: return
        if (!editable()) return
        val r = selectedLines()
        val buf = s.doc.buffer
        val start = if (r.last + 1 < buf.lineCount) Pos(r.first, 0) else if (r.first > 0) Pos(r.first - 1, buf.lineLength(r.first - 1)) else Pos(0, 0)
        val end = if (r.last + 1 < buf.lineCount) Pos(r.last + 1, 0) else Pos(r.last, buf.lineLength(r.last))
        replace(start, end, "", EditHistory.Kind.OTHER)
    }

    fun insertLine(above: Boolean) {
        val s = session ?: return
        if (!editable()) return
        val buf = s.doc.buffer
        val l = s.caret.line
        val indent = leadingWhitespace(buf.line(l))
        if (above) {
            replace(Pos(l, 0), Pos(l, 0), indent + "\n", EditHistory.Kind.OTHER)
            setSelection(Pos(l, indent.length), Pos(l, indent.length))
        } else {
            val end = Pos(l, buf.lineLength(l))
            replace(end, end, "\n" + indent, EditHistory.Kind.OTHER)
        }
    }

    fun selectLine() {
        val s = session ?: return
        val r = selectedLines()
        val buf = s.doc.buffer
        val end = if (r.last + 1 < buf.lineCount) Pos(r.last + 1, 0) else Pos(r.last, buf.lineLength(r.last))
        setSelection(Pos(r.first, 0), end)
    }

    // ---- clipboard ----

    fun copy(): Boolean {
        val s = session ?: return false
        val text = if (s.hasSelection) selectedText else s.doc.buffer.line(s.caret.line) + "\n"
        clipboard.setPrimaryClip(ClipData.newPlainText(s.doc.name, text))
        return true
    }

    fun cut() {
        val s = session ?: return
        if (!editable()) return
        copy()
        if (s.hasSelection) replace(s.selStart, s.selEnd, "", EditHistory.Kind.OTHER)
        else deleteLines()
    }

    fun paste() {
        val s = session ?: return
        if (!editable()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        replace(s.selStart, s.selEnd, text.replace("\r\n", "\n").replace('\r', '\n'), EditHistory.Kind.OTHER)
        selectionHandles = false
        host.hideSelectionBar(this)
    }

    fun canPaste(): Boolean = clipboard.hasPrimaryClip()

    /** The last edit was typing (not undo, paste or a command): only typing opens suggestions. */
    var lastEditTyped = false
        private set

    /** Called when the editor loses focus, for popups that belong to it. */
    var onBlur: (() -> Unit)? = null

    fun undo() {
        lastEditTyped = false
        val s = session ?: return
        if (s.doc.readOnly) return
        val sel = s.doc.history.undo() ?: return
        setSelection(sel.first, sel.second)
        host.textEdited(this)
    }

    fun redo() {
        lastEditTyped = false
        val s = session ?: return
        if (s.doc.readOnly) return
        val sel = s.doc.history.redo() ?: return
        setSelection(sel.first, sel.second)
        host.textEdited(this)
    }

    fun goToLine(line: Int, col: Int = 0) {
        val s = session ?: return
        val p = s.doc.buffer.clamp(Pos(line, col))
        setSelection(p, p, reveal = false)
        ensureCaretVisible(true)
    }

    fun select(a: Pos, b: Pos, center: Boolean) {
        setSelection(a, b, reveal = !center)
        if (center) ensureCaretVisible(true)
    }

    // ---- brackets ----

    private val bracketPairs = "()[]{}"

    private fun codeAt(line: Int, col: Int): Boolean {
        val k = doc?.highlighter?.kindAt(line, col) ?: return true
        return k != Token.STRING && k != Token.COMMENT
    }

    /** The bracket at or before the cursor and its partner, when both are found within reach. */
    private var bracketDoc: Document? = null
    private var bracketVersion = -1
    private var bracketPos: Pos? = null
    private var bracketResult: List<Pos>? = null

    /** [matchBracket], kept until the cursor or the text changes: the scan can walk 200,000 characters. */
    private fun bracketAt(d: Document, p: Pos): List<Pos>? {
        if (bracketDoc === d && bracketVersion == d.buffer.version && bracketPos == p) return bracketResult
        bracketResult = matchBracket(p)
        bracketDoc = d
        bracketVersion = d.buffer.version
        bracketPos = p
        return bracketResult
    }

    private fun matchBracket(p: Pos): List<Pos>? {
        val d = doc ?: return null
        val line = d.buffer.line(p.line)
        for (col in listOf(p.col, p.col - 1)) {
            if (col !in line.indices) continue
            val c = line[col]
            val i = bracketPairs.indexOf(c)
            if (i < 0 || !codeAt(p.line, col)) continue
            val other = if (i % 2 == 0) scanForward(Pos(p.line, col), c, bracketPairs[i + 1]) else scanBackward(Pos(p.line, col), bracketPairs[i - 1], c)
            return if (other != null) listOf(Pos(p.line, col), other) else null
        }
        return null
    }

    private fun scanForward(from: Pos, open: Char, close: Char): Pos? {
        val buf = doc?.buffer ?: return null
        var depth = 0
        var line = from.line
        var col = from.col
        var budget = 200_000
        while (line < buf.lineCount && budget > 0) {
            val s = buf.line(line)
            while (col < s.length) {
                val c = s[col]
                if ((c == open || c == close) && codeAt(line, col)) {
                    if (c == open) depth++ else { depth--; if (depth == 0) return Pos(line, col) }
                }
                col++
                budget--
            }
            line++
            col = 0
            if (line - from.line > 3000) return null
        }
        return null
    }

    private fun scanBackward(from: Pos, open: Char, close: Char): Pos? {
        val buf = doc?.buffer ?: return null
        var depth = 0
        var line = from.line
        var col = from.col
        var budget = 200_000
        while (line >= 0 && budget > 0) {
            val s = buf.line(line)
            if (col >= s.length) col = s.length - 1
            while (col >= 0) {
                val c = s[col]
                if ((c == open || c == close) && codeAt(line, col)) {
                    if (c == close) depth++ else { depth--; if (depth == 0) return Pos(line, col) }
                }
                col--
                budget--
            }
            line--
            if (line >= 0) col = buf.lineLength(line) - 1
            if (from.line - line > 3000) return null
        }
        return null
    }

    private fun matchBracketBackward(at: Pos, close: Char): Pos? {
        val i = bracketPairs.indexOf(close)
        if (i <= 0) return null
        val open = bracketPairs[i - 1]
        val buf = doc?.buffer ?: return null
        // Scan from just before the cursor with depth starting at one: the brace being typed.
        var depth = 1
        var line = at.line
        var col = at.col - 1
        var budget = 200_000
        while (line >= 0 && budget-- > 0) {
            val s = buf.line(line)
            if (col >= s.length) col = s.length - 1
            while (col >= 0) {
                val c = s[col]
                if ((c == open || c == close) && codeAt(line, col)) {
                    if (c == close) depth++ else { depth--; if (depth == 0) return Pos(line, col) }
                }
                col--
            }
            line--
            if (line >= 0) col = buf.lineLength(line) - 1
        }
        return null
    }

    fun jumpToBracket() {
        val s = session ?: return
        val m = matchBracket(s.caret) ?: return
        val target = if (m[0] == s.caret || m[0].col == s.caret.col - 1 && m[0].line == s.caret.line) m[1] else m[0]
        setSelection(target, target)
    }

    // ---- cursor movement ----

    private fun moveTo(p: Pos, extend: Boolean, keepSticky: Boolean = false) {
        val s = session ?: return
        val sticky = s.stickyCol
        setSelection(if (extend) s.anchor else p, p)
        if (keepSticky) s.stickyCol = sticky
        caretHandle = false
        selectionHandles = false
        host.hideSelectionBar(this)
    }

    private fun moveHorizontal(dir: Int, word: Boolean, extend: Boolean) {
        val s = session ?: return
        val buf = s.doc.buffer
        if (!extend && s.hasSelection && !word) {
            val p = if (dir < 0) s.selStart else s.selEnd
            moveTo(p, false)
            return
        }
        var p = s.caret
        val line = buf.line(p.line)
        if (dir < 0) {
            p = when {
                p.col == 0 -> if (p.line > 0) Pos(p.line - 1, buf.lineLength(p.line - 1)) else p
                word -> {
                    var i = p.col
                    while (i > 0 && line[i - 1].isWhitespace()) i--
                    val cls = if (i > 0) wordClass(line[i - 1]) else 0
                    while (i > 0 && wordClass(line[i - 1]) == cls) i--
                    Pos(p.line, i)
                }
                p.col >= 2 && Character.isLowSurrogate(line[p.col - 1]) -> Pos(p.line, p.col - 2)
                else -> Pos(p.line, p.col - 1)
            }
        } else {
            p = when {
                p.col >= line.length -> if (p.line + 1 < buf.lineCount) Pos(p.line + 1, 0) else p
                word -> {
                    var i = p.col
                    val cls = wordClass(line[i])
                    while (i < line.length && wordClass(line[i]) == cls) i++
                    while (i < line.length && line[i].isWhitespace()) i++
                    Pos(p.line, i)
                }
                Character.isHighSurrogate(line[p.col]) && p.col + 1 < line.length -> Pos(p.line, p.col + 2)
                else -> Pos(p.line, p.col + 1)
            }
        }
        moveTo(p, extend)
    }

    private fun moveVertical(rows: Int, extend: Boolean) {
        val s = session ?: return
        val buf = s.doc.buffer
        val cur = s.caret
        val line = buf.line(cur.line)
        val br = breaksOf(cur.line)
        val k = rowIn(br, cur.col)
        val col = if (s.stickyCol >= 0) s.stickyCol else colOf(line, cur.col) - colOf(line, br[k])
        val target = (rowOfPos(cur) + rows).coerceIn(0, totalRows() - 1)
        if (target == rowOfPos(cur)) {
            moveTo(if (rows < 0) Pos(cur.line, 0) else Pos(cur.line, line.length), extend)
            return
        }
        val tl = lineAtRow(target)
        val tbr = breaksOf(tl)
        val tk = target - firstRowOf(tl)
        val ts = buf.line(tl)
        val start = tbr[tk]
        val end = if (tk + 1 < tbr.size) tbr[tk + 1] - 1 else ts.length
        val idx = indexAtCol(ts, (col + colOf(ts, start)).toFloat(), start, max(start, end))
        moveTo(Pos(tl, idx), extend)
        s.stickyCol = col
    }

    private fun home(extend: Boolean, doc: Boolean) {
        val s = session ?: return
        if (doc) { moveTo(Pos.ZERO, extend); return }
        val line = s.doc.buffer.line(s.caret.line)
        val indent = leadingWhitespace(line).length
        // Home goes to the first character of the code, and to the line start from there.
        moveTo(Pos(s.caret.line, if (s.caret.col == indent) 0 else indent), extend)
    }

    private fun end(extend: Boolean, doc: Boolean) {
        val s = session ?: return
        if (doc) { moveTo(s.doc.buffer.lastPos, extend); return }
        moveTo(Pos(s.caret.line, s.doc.buffer.lineLength(s.caret.line)), extend)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        activity()
        if (keyInterceptor?.invoke(keyCode, event) == true) return true
        if (host.shortcut(this, event)) return true
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveHorizontal(-1, ctrl || alt, shift); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveHorizontal(1, ctrl || alt, shift); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    alt && shift -> duplicateLines(false)
                    alt -> moveLines(true)
                    ctrl -> { s.scrollY -= lineH; clampScroll(); invalidate() }
                    else -> moveVertical(-1, shift)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    alt && shift -> duplicateLines(true)
                    alt -> moveLines(false)
                    ctrl -> { s.scrollY += lineH; clampScroll(); invalidate() }
                    else -> moveVertical(1, shift)
                }
                return true
            }
            KeyEvent.KEYCODE_MOVE_HOME -> { home(shift, ctrl); return true }
            KeyEvent.KEYCODE_MOVE_END -> { end(shift, ctrl); return true }
            KeyEvent.KEYCODE_PAGE_UP -> { moveVertical(-max(1, (height / lineH).toInt() - 1), shift); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { moveVertical(max(1, (height / lineH).toInt() - 1), shift); return true }
            KeyEvent.KEYCODE_TAB -> {
                if (tabMovesFocus || ctrl) return super.onKeyDown(keyCode, event)
                tabKey(shift)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when {
                    ctrl && shift -> insertLine(true)
                    ctrl -> insertLine(false)
                    else -> newline()
                }
                return true
            }
            KeyEvent.KEYCODE_DEL -> { backspace(ctrl || alt); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> { if (shift && !ctrl) cut() else deleteForward(ctrl); return true }
            KeyEvent.KEYCODE_INSERT -> { if (shift) paste() else if (ctrl) copy(); return true }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (s.hasSelection) { setSelection(s.caret, s.caret); host.hideSelectionBar(this); return true }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_CUT -> { cut(); return true }
            KeyEvent.KEYCODE_COPY -> { copy(); return true }
            KeyEvent.KEYCODE_PASTE -> { paste(); return true }
        }
        if (ctrl && !alt) {
            when (keyCode) {
                KeyEvent.KEYCODE_A -> { selectAll(); return true }
                KeyEvent.KEYCODE_C -> { copy(); return true }
                KeyEvent.KEYCODE_X -> { cut(); return true }
                KeyEvent.KEYCODE_V -> { paste(); return true }
                KeyEvent.KEYCODE_Z -> { if (shift) redo() else undo(); return true }
                KeyEvent.KEYCODE_Y -> { redo(); return true }
                KeyEvent.KEYCODE_SLASH -> { toggleComment(); return true }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> { indentLines(false); return true }
                KeyEvent.KEYCODE_LEFT_BRACKET -> { indentLines(true); return true }
                KeyEvent.KEYCODE_K -> if (shift) { deleteLines(); return true }
                KeyEvent.KEYCODE_L -> { selectLine(); return true }
                KeyEvent.KEYCODE_D -> { val (a, b) = wordRangeAt(s.caret); setSelection(a, b); return true }
                KeyEvent.KEYCODE_BACKSLASH -> if (shift) { jumpToBracket(); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { host.zoomed(this, (config.fontSize + 1).coerceAtMost(28f), true); return true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { host.zoomed(this, (config.fontSize - 1).coerceAtLeast(9f), true); return true }
                KeyEvent.KEYCODE_0 -> { host.zoomed(this, TeditPrefs.specOf(TeditPrefs.FONT_SIZE).default as Float, true); return true }
            }
            return super.onKeyDown(keyCode, event)
        }
        val uc = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (uc != 0 && !ctrl) {
            if (uc and KeyCharacterMapCombining != 0) return super.onKeyDown(keyCode, event)
            typeText(String(Character.toChars(uc)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(blinker)
    }

    fun detach() {
        session?.doc?.buffer?.removeListener(listener)
        session?.doc?.highlighter?.onRefresh?.remove(refresh)
        session = null
    }

    companion object {
        private val ONE_ROW = intArrayOf(0)
        /** Lines longer than this keep whether they are plain ASCII in the column cache. */
        private const val LONG_LINE = 128
        /** Cached for a long line whose columns are its indexes. */
        private val PLAIN_COLS = IntArray(0)
        private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
