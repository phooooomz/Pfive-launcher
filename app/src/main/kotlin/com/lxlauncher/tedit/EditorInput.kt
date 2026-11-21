// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * The on-screen keyboard's way into [CodeEditView]. Offsets the keyboard uses count characters from the start of
 * the document with one per line break, as an EditText's do. Composing text (a word still being typed, Gboard's
 * underline) is real text in the buffer, replaced as the keyboard revises it and grouped into one undo step.
 */
class EditorInput(private val view: CodeEditView) : BaseInputConnection(view, false) {
    private val session get() = view.session
    private val buffer get() = view.session?.doc?.buffer

    fun reset() {
        session?.composing = null
    }

    override fun beginBatchEdit(): Boolean {
        view.batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (view.batchDepth > 0) view.batchDepth--
        if (view.batchDepth == 0) view.notifyImeSelection()
        return view.batchDepth > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val s = session ?: return false
        val t = text?.toString() ?: return true
        val comp = s.composing
        if (comp != null) {
            // The keyboard settles the word it was composing: replace what it showed with what it commits.
            s.composing = null
            if (t == "\n") {
                view.newline()
                return true
            }
            view.select(comp.first, comp.second, false)
            view.replace(comp.first, comp.second, t, EditHistory.Kind.TYPE)
            return true
        }
        if (t == "\n") view.newline()
        else if (t == "\t") view.tabKey(false)
        else view.typeText(t, fromIme = true)
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val s = session ?: return false
        val t = text?.toString() ?: ""
        val comp = s.composing
        val (a, b) = comp ?: (s.selStart to s.selEnd)
        if (s.doc.readOnly) return false
        val end = view.replace(a, b, t, EditHistory.Kind.TYPE) ?: return false
        s.composing = if (t.isEmpty()) null else (a to end)
        view.notifyImeSelection()
        view.invalidate()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val s = session ?: return false
        val buf = buffer ?: return false
        if (start == end) { s.composing = null; view.invalidate(); return true }
        val a = buf.posOf(minOf(start, end))
        val b = buf.posOf(maxOf(start, end))
        // A region across lines is not something a keyboard composes; ignore it rather than replace lines by accident.
        s.composing = if (a.line == b.line) a to b else null
        view.invalidate()
        return true
    }

    override fun finishComposingText(): Boolean {
        val s = session ?: return false
        s.composing = null
        view.notifyImeSelection()
        view.invalidate()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val s = session ?: return false
        val buf = buffer ?: return false
        s.composing = null
        if (s.hasSelection && beforeLength <= 1 && afterLength == 0) {
            view.backspace()
            return true
        }
        val caret = buf.offsetOf(s.selStart)
        val selEnd = buf.offsetOf(s.selEnd)
        if (beforeLength == 1 && afterLength == 0 && !s.hasSelection) {
            view.backspace()
            return true
        }
        val a = buf.posOf((caret - beforeLength).coerceAtLeast(0))
        if (beforeLength > 0) view.replace(a, s.selStart, "", EditHistory.Kind.DELETE)
        if (afterLength > 0) {
            val from = session?.selEnd ?: return true
            val b = buf.posOf(buf.offsetOf(from) + afterLength)
            view.replace(from, b, "", EditHistory.Kind.DELETE)
        }
        if (beforeLength == 0 && afterLength == 0 && selEnd > caret) Unit
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val s = session ?: return false
        val buf = buffer ?: return false
        val caret = buf.offsetOf(s.selStart)
        val text = buf.text(buf.posOf((caret - beforeLength * 2).coerceAtLeast(0)), s.selStart)
        var units = 0
        var cps = 0
        var i = text.length
        while (i > 0 && cps < beforeLength) {
            i = if (i >= 2 && Character.isLowSurrogate(text[i - 1]) && Character.isHighSurrogate(text[i - 2])) i - 2 else i - 1
            units = text.length - i
            cps++
        }
        return deleteSurroundingText(units, afterLength)
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
        val s = session ?: return ""
        val buf = buffer ?: return ""
        val end = buf.offsetOf(s.selStart)
        return buf.text(buf.posOf((end - n).coerceAtLeast(0)), s.selStart)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
        val s = session ?: return ""
        val buf = buffer ?: return ""
        val start = buf.offsetOf(s.selEnd)
        return buf.text(s.selEnd, buf.posOf(start + n))
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val s = session ?: return null
        return if (s.hasSelection) view.selectedText else null
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val buf = buffer ?: return false
        view.setSelection(buf.posOf(start), buf.posOf(end))
        return true
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        val s = session ?: return null
        val buf = buffer ?: return null
        // A window of text around the cursor: enough for a keyboard's cursor keys and word lookups.
        val caret = buf.offsetOf(s.caret)
        val from = (caret - 2000).coerceAtLeast(0)
        val to = caret + 2000
        val a = buf.posOf(from)
        val text = buf.text(a, buf.posOf(to))
        return ExtractedText().apply {
            this.text = text
            startOffset = buf.offsetOf(a)
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = buf.offsetOf(s.anchor) - startOffset
            selectionEnd = buf.offsetOf(s.caret) - startOffset
            this.flags = 0
        }
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> view.selectAll()
            android.R.id.cut -> view.cut()
            android.R.id.copy -> view.copy()
            android.R.id.paste, android.R.id.pasteAsPlainText -> view.paste()
            android.R.id.undo -> view.undo()
            android.R.id.redo -> view.redo()
            else -> return false
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        view.newline()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Keys from the keyboard (Delete, Enter, arrows) go through the editor's own key handling.
        return if (event.action == KeyEvent.ACTION_DOWN) view.onKeyDown(event.keyCode, event) || super.sendKeyEvent(event)
        else true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun closeConnection() {
        session?.composing = null
        view.batchDepth = 0
        super.closeConnection()
    }
}
