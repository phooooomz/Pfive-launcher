// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dracxterm.TerminalSession
import com.dracxterm.TerminalView
import com.lxlauncher.R
import java.io.File
import java.util.concurrent.Executors

/**
 * One session of Tedit's terminal: a dracxterm shell (see [DxShell]) on its own pseudo terminal, drawn and typed into
 * through dracxterm's terminal engine, so full-screen programs (nano, vi, top, htop), colours, Ctrl+C and the shell's
 * own line editing and history work as they do in dracxterm.
 */
class TermSession(val name: String, val forRun: Boolean) {
    val native = TerminalSession()
    var kind: DxShell.Kind? = null
        internal set
    /** The host folder the shell started in; Debian sees it at the same path. */
    var dir: String? = null
        internal set
    /** False while the shell is still being set up off the main thread. */
    var started = false
        internal set
    var failed = false
        internal set
    /** Prompt reports so far; the first one means the shell is ready for a command. */
    var prompts = 0
        internal set
    var lastPrompt: DxShell.Prompt? = null
        internal set
    var ended = false
        internal set
    /** The width the terminal last showed this session at, in columns. */
    internal var shownCols = 0

    /** At its prompt with nothing typed since: the shell is reading a command line, no program is reading input. */
    var atPrompt = false
        internal set

    val alive: Boolean get() = !failed && !ended && (!started || native.running())
    val debian: Boolean get() = kind == DxShell.Kind.DEBIAN

    /** Starts the shell [spec] describes on a [cols] x [rows] terminal. */
    fun startWith(spec: DxShell.Spec, cols: Int, rows: Int): Boolean {
        kind = spec.kind
        dir = spec.cwd
        started = native.start(cols, rows, spec.argv, spec.env, spec.cwd)
        failed = !started
        return started
    }

    /** The shell's newest report, if one came since the last read; for sessions nobody draws, and for tests. */
    fun takeReport(): DxShell.Prompt? = native.appControl().takeIf { it.isNotEmpty() }?.let { DxShell.report(it) }

    /** Types [text] into the shell, as if from the keyboard. */
    fun send(text: String) { if (started) { atPrompt = false; native.writeText(text) } }

    /**
     * After the terminal got narrower, Android's sh (mksh) cuts the prompt it already has to the new width, and a
     * coloured prompt cut that way shows nothing at all; bash and BusyBox's ash draw theirs again at once. An Enter on
     * the empty line makes mksh print a fresh prompt laid out for the new width. It is sent only while mksh waits at
     * its prompt with nothing typed, so it never reaches a program reading input, and an empty line is not history.
     */
    internal fun redrawAfterNarrowing() {
        if (!started || kind != DxShell.Kind.ANDROID || !atPrompt) return
        // After mksh has taken the new size in: sent together with the size change (rows and columns at once), it was
        // drawn and then blanked again, measured on the Infinix on 13 November 2026.
        redraw.removeCallbacks(redrawNow)
        redraw.postDelayed(redrawNow, REDRAW_DELAY_MS)
    }

    private val redrawNow = Runnable { if (atPrompt && native.isValid() && native.running()) native.write(byteArrayOf(13)) }

    /** Ctrl+C: the terminal sends the interrupt character, and the line discipline signals the foreground job. */
    fun interrupt() { if (started) native.write(byteArrayOf(3)) }

    /**
     * Everything in the session, scrollback included. The engine anchors a selection to whole lines of the buffer, so
     * a selection from the oldest line to the newest one, made and dropped here, reads it all.
     */
    fun allText(rows: Int, cols: Int): String {
        if (!started || rows <= 0 || cols <= 0) return ""
        native.scroll(1_000_000)
        native.selectStart(0, 0)
        native.scrollToBottom()
        native.selectExtend(rows - 1, cols - 1)
        val text = native.selectionText()
        native.clearSelection()
        return text.trimEnd()
    }

    fun close() {
        redraw.removeCallbacks(redrawNow)
        native.close()
    }

    private companion object {
        val redraw = Handler(Looper.getMainLooper())
        const val REDRAW_DELAY_MS = 200L
    }
}

/** The terminal panel under the editor: the session picker, the terminal, and dracxterm's row of extra keys. */
class TerminalPanel(private val tool: TeditTool) {
    private val ctx: Context get() = tool.ctx
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ArrayList<TermSession>()
    var current: TermSession? = null
        private set
    private var counter = 0

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val view: View get() = root
    lateinit var terminal: TerminalView
        private set
    private lateinit var sessionLabel: TextView
    private var keys: ExtraKeys? = null
    private var keysFit: View.OnLayoutChangeListener? = null
    private var built = false

    /** Told each time a session's shell is back at its prompt (Run waits for these). */
    private val promptListeners = ArrayList<(TermSession, DxShell.Prompt) -> Unit>()
    fun addPromptListener(l: (TermSession, DxShell.Prompt) -> Unit) { promptListeners += l }
    fun removePromptListener(l: (TermSession, DxShell.Prompt) -> Unit) { promptListeners -= l }
    private val exitListeners = ArrayList<(TermSession) -> Unit>()
    fun addExitListener(l: (TermSession) -> Unit) { exitListeners += l }
    fun removeExitListener(l: (TermSession) -> Unit) { exitListeners -= l }

    private fun build() {
        root.removeAllViews()
        root.setBackgroundColor(t.bg)
        val bar = ui.row().apply { setBackgroundColor(t.panel); setPadding(t.dpi(4f), 0, t.dpi(2f), 0) }
        // The session picker: the current shell's name, tap for the others and a new one.
        sessionLabel = ui.text("", t.textSmall, t.text, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = t.control
            setPadding(t.dpi(8f), 0, t.dpi(8f), 0)
            background = ui.states()
            isFocusable = true
            compoundDrawablePadding = t.dpi(4f)
            setCompoundDrawablesRelative(TIcon(TIcon.Glyph.TERMINAL, t.text2).apply { setBounds(0, 0, t.dpi(16f), t.dpi(16f)) }, null,
                TIcon(TIcon.Glyph.CHEVRON_DOWN, t.text2).apply { setBounds(0, 0, t.dpi(14f), t.dpi(14f)) }, null)
            setOnClickListener { sessionMenu(it) }
        }
        bar.addView(sessionLabel, ui.lp(0, t.control, 1f))
        bar.addView(ui.iconButton(TIcon.Glyph.ADD, ctx.getString(R.string.tedit_new_terminal), t.text2, t.control) { newSession(focus = true) })
        bar.addView(ui.iconButton(TIcon.Glyph.STOP, ctx.getString(R.string.tedit_term_interrupt), t.text2, t.control) { current?.interrupt() })
        bar.addView(ui.iconButton(TIcon.Glyph.MORE, ctx.getString(R.string.tedit_more), t.text2, t.control) { menu(it) })
        bar.addView(ui.iconButton(TIcon.Glyph.CLOSE, ctx.getString(R.string.tedit_close_panel), t.text2, t.control) { tool.toggleTerminal() })
        root.addView(bar, ui.lp(TeditTool.MATCH, t.control))

        terminal = TerminalView(ctx).apply {
            contentDescription = ctx.getString(R.string.tedit_terminal)
            showKeyboard = { v -> TeditUi.showKeyboard(v) }
            onClipboardCopy = { text ->
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(current?.name, text))
                tool.overlays.snack(ctx.getString(R.string.tedit_copied))
            }
            onFontSizeChanged = { dp -> if (dp.toFloat() != tool.prefs.float(TeditPrefs.TERM_FONT)) tool.prefs.set(TeditPrefs.TERM_FONT, dp.toFloat().coerceIn(MIN_FONT, MAX_FONT)) }
            onModifierConsumed = { name -> keys?.release(name) }
            onUserInput = { current?.atPrompt = false }
            onAppControl = { payload -> current?.let { s -> report(s, payload) } }
            onExit = { current?.let { s -> ended(s) } }
        }
        style()
        // The shell hears of a new size once the panel has settled (see TerminalView.settleMs), and a narrower terminal
        // lets a shell that blanks its line on the change draw it again (see redrawAfterNarrowing).
        terminal.settleMs = SETTLE_MS
        terminal.onGridResized = { cols, rows ->
            current?.takeIf { it.started }?.let { s ->
                Log.d(TeditTool.TAG, "terminal ${s.shownCols} -> $cols columns, $rows rows, at prompt ${s.atPrompt}")
                if (cols < s.shownCols) s.redrawAfterNarrowing()
                s.shownCols = cols
            }
        }
        root.addView(terminal, ui.lp(TeditTool.MATCH, 0, 1f))
        val k = ExtraKeys()
        keys = k
        val keyRow = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(t.panel)
            addView(k.row, android.widget.FrameLayout.LayoutParams(TeditTool.WRAP, t.control))
        }
        root.addView(keyRow, ui.lp(TeditTool.MATCH, t.control))
        // In a panel too low for the bar, the keys and a few lines, the keys step aside for the lines; Ctrl+C stays in
        // the bar above. The root outlives a rebuild, so the listener for the old key row goes first: each rebuild
        // used to add one more, which held its key row and ran on every layout after.
        keysFit?.let { root.removeOnLayoutChangeListener(it) }
        keysFit = View.OnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val vis = if (bottom - top < t.dpi(KEYS_MIN_PANEL_DP)) View.GONE else View.VISIBLE
            if (keyRow.visibility != vis) root.post { keyRow.visibility = vis }
        }.also { root.addOnLayoutChangeListener(it) }
        built = true
        current?.let { if (it.started) terminal.switchSession(it.native) }
        renderLabel()
    }

    /** The terminal in Tedit's colours, code font and terminal font size. */
    private fun style() {
        terminal.setTypeface(t.codeFont)
        terminal.setDefaultBg(t.bg)
        terminal.setCursorColor(t.accent)
        terminal.setFontSizeDp(tool.prefs.float(TeditPrefs.TERM_FONT).toInt())
        sessions.forEach { configure(it) }
        terminal.refreshAfterConfigChange()
    }

    private fun configure(s: TermSession) {
        if (s.started) s.native.configure(t.text, t.bg, t.accent, tool.prefs.int(TeditPrefs.TERM_SCROLLBACK))
    }

    /**
     * The panel is on screen. It does not take the keyboard by itself: Tedit opening with the terminal of its last
     * session, or being laid out again, must not raise the keyboard over the window; opening the terminal does (focusInput).
     */
    fun onShow() {
        if (!built) build()
        if (sessions.isEmpty()) newSession()
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    /** Settings or the theme changed: the panel is built again around the same shells, which keep running. */
    fun rebuild() {
        if (!built) return
        build()
    }

    fun focusInput() {
        if (!built) build()
        terminal.requestFocusAndKeyboard()
    }

    private fun cwd(): File {
        tool.workspace?.source?.takeIf { it.backendKey == "local" }?.let { File(it.ref).takeIf { f -> f.isDirectory } }?.let { return it }
        File("/storage/emulated/0").takeIf { it.canRead() }?.let { return it }
        return ctx.filesDir
    }

    /**
     * A new session in [dir] (the workspace by default). Setting the shell up writes a few files and may ask BusyBox
     * for its commands, so it happens off the main thread; the session shows once its shell has started.
     */
    fun newSession(focus: Boolean = false, dir: File? = null, forRun: Boolean = false, name: String? = null): TermSession {
        if (!built) build()
        val s = TermSession(name ?: ctx.getString(R.string.tedit_term_name_dx, ++counter), forRun)
        sessions += s
        show(s)
        val folder = dir ?: cwd()
        val androidShell = tool.prefs.string(TeditPrefs.TERM_SHELL) == "android"
        setup.execute {
            val spec = runCatching { DxShell.prepare(ctx.applicationContext, folder, androidShell) }
                .onFailure { Log.w(TeditTool.TAG, "terminal setup failed", it) }.getOrNull()
            main.post { start(s, spec, focus) }
        }
        return s
    }

    private fun start(s: TermSession, spec: DxShell.Spec?, focus: Boolean) {
        if (s !in sessions) return
        val cols = terminal.gridCols().takeIf { it > 0 } ?: 80
        val rows = terminal.gridRows().takeIf { it > 0 } ?: 24
        if (spec == null || !s.startWith(spec, cols, rows)) {
            s.failed = true
            Log.w(TeditTool.TAG, "terminal session did not start")
            tool.overlays.snack(ctx.getString(R.string.tedit_term_failed))
            exitListeners.toList().forEach { it(s) }
            renderLabel()
            return
        }
        configure(s)
        Log.d(TeditTool.TAG, "terminal session started: ${spec.kind} (${sessions.size} open)")
        if (current === s) {
            terminal.switchSession(s.native)
            s.shownCols = terminal.gridCols()
            if (focus) terminal.requestFocusAndKeyboard()
        }
        renderLabel()
    }

    /** Shows [s] in the terminal. */
    fun show(s: TermSession) {
        current = s
        if (built && s.started) {
            terminal.switchSession(s.native)
            val cols = terminal.gridCols()
            if (cols > 0 && s.shownCols > 0 && cols < s.shownCols) s.redrawAfterNarrowing()
            if (cols > 0) s.shownCols = cols
        }
        renderLabel()
    }

    private fun report(s: TermSession, payload: String) {
        DxShell.report(payload)?.let { onReport(s, it) }
    }

    private fun onReport(s: TermSession, p: DxShell.Prompt) {
        s.prompts++
        s.atPrompt = true
        s.lastPrompt = p
        promptListeners.toList().forEach { it(s, p) }
    }

    private fun ended(s: TermSession) {
        if (s.ended) return
        s.ended = true
        Log.d(TeditTool.TAG, "terminal session ended")
        exitListeners.toList().forEach { it(s) }
        renderLabel()
    }

    /**
     * Sessions not on screen are not drawn, so their prompt reports and their end are read here, four times a second
     * while the panel exists. The session on screen reports through the terminal view on each frame, except the report
     * the view finds waiting when a session is shown, which it takes as its starting point; that one is read here too.
     * The engine hands each report out once, so none is handled twice.
     */
    private val poll: Runnable = object : Runnable {
        override fun run() {
            for (s in sessions.toList()) {
                if (!s.started) continue
                s.takeReport()?.let { onReport(s, it) }
                if (!s.ended && !s.native.running()) ended(s)
            }
            if (sessions.isNotEmpty()) main.postDelayed(this, POLL_MS)
        }
    }

    private fun renderLabel() {
        if (!built) return
        val s = current
        sessionLabel.text = if (s == null) ctx.getString(R.string.tedit_terminal) else s.name + if (!s.alive) " ×" else ""
        sessionLabel.contentDescription = ctx.getString(R.string.tedit_term_sessions, sessionLabel.text)
    }

    private fun sessionMenu(anchor: View) {
        val items = sessions.map { s ->
            TItem(s.name + if (!s.alive) " ×" else "", TIcon.Glyph.TERMINAL, checked = s === current) { show(s); terminal.requestFocusAndKeyboard() }
        } + TItem(ctx.getString(R.string.tedit_new_terminal), TIcon.Glyph.ADD, divider = sessions.isNotEmpty()) { newSession(focus = true) } +
            listOfNotNull(current?.let { cur -> TItem(ctx.getString(R.string.tedit_term_kill), TIcon.Glyph.CLOSE, danger = true) { killSession(cur) } })
        tool.overlays.menu(anchor, items, title = ctx.getString(R.string.tedit_terminal))
    }

    private fun clipboard() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun paste() {
        val text = clipboard().primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
        terminal.clearStickyModifiers()
        terminal.paste(text)
    }

    private fun find() {
        tool.overlays.prompt(ctx.getString(R.string.tedit_term_find), "", ctx.getString(R.string.tedit_term_find_hint), ctx.getString(R.string.tedit_term_find_go)) { q ->
            if (!terminal.find(q)) tool.overlays.snack(ctx.getString(R.string.tedit_term_find_none, q))
        }
    }

    private fun menu(anchor: View) {
        val s = current
        tool.overlays.menu(anchor, listOf(
            TItem(ctx.getString(R.string.tedit_term_copy_all), TIcon.Glyph.COPY, enabled = s?.started == true) {
                val text = s?.allText(terminal.gridRows(), terminal.gridCols()).orEmpty()
                clipboard().setPrimaryClip(ClipData.newPlainText(s?.name, text))
                tool.overlays.snack(ctx.getString(R.string.tedit_copied))
            },
            TItem(ctx.getString(R.string.tedit_paste), TIcon.Glyph.PASTE, enabled = s?.alive == true && clipboard().hasPrimaryClip()) { paste() },
            TItem(ctx.getString(R.string.tedit_term_find), TIcon.Glyph.SEARCH, enabled = s?.started == true) { find() },
            // Ctrl+L, as the shell clears its own screen.
            TItem(ctx.getString(R.string.tedit_clear), TIcon.Glyph.TRASH, enabled = s?.alive == true) { s?.send("\u000c") },
            TItem(ctx.getString(R.string.tedit_term_kill), TIcon.Glyph.CLOSE, enabled = s != null, danger = true, divider = true) { s?.let { killSession(it) } },
            TItem(ctx.getString(R.string.tedit_term_font_bigger), divider = true) { terminal.zoomIn() },
            TItem(ctx.getString(R.string.tedit_term_font_smaller)) { terminal.zoomOut() },
            TItem(ctx.getString(R.string.tedit_linux_title), TIcon.Glyph.TERMINAL, divider = true) { LinuxPage(tool).show() },
            TItem(ctx.getString(R.string.tedit_term_about), TIcon.Glyph.INFO) { tool.overlays.message(ctx.getString(R.string.tedit_terminal), ctx.getString(R.string.tedit_term_limits)) },
        ), title = s?.name)
    }

    fun killSession(s: TermSession) {
        sessions.remove(s)
        s.close()
        if (!s.ended) { s.ended = true; exitListeners.toList().forEach { it(s) } }
        if (current === s) sessions.lastOrNull()?.let { show(it) } ?: run { current = null; renderLabel() }
        if (sessions.isEmpty() && tool.terminalOpen) tool.toggleTerminal()
    }

    /** Ends every Debian session, before the system under them is removed. */
    fun closeDebianSessions() {
        for (s in sessions.filter { it.debian }) killSession(s)
    }

    /** Sessions still open, for tests and for Run. */
    fun sessions(): List<TermSession> = sessions.toList()

    fun dispose() {
        main.removeCallbacks(poll)
        sessions.forEach { it.close() }
        sessions.clear()
        current = null
    }

    /**
     * dracxterm's row of extra keys, in its order: the keys a phone keyboard lacks (Ctrl and Alt hold until the next
     * key), scrolling back to the newest output, text size, find and paste.
     */
    private inner class ExtraKeys {
        val row = ui.row().apply { setPadding(t.dpi(4f), 0, t.dpi(4f), 0) }
        private val sticky = HashMap<String, TextView>()

        init {
            for ((label, name, desc) in KEYS) {
                val stickyKey = name == "CTRL" || name == "ALT"
                val b = ui.text(label, t.textSmall, t.text, medium = true).apply {
                    typeface = t.codeFont
                    gravity = Gravity.CENTER
                    minWidth = t.dpi(40f)
                    minHeight = t.control
                    setPadding(t.dpi(8f), 0, t.dpi(8f), 0)
                    background = ui.states()
                    isFocusable = true
                    contentDescription = desc?.let { ctx.getString(it) } ?: label
                    setOnClickListener { press(name, this) }
                }
                if (stickyKey) sticky[name] = b
                row.addView(b, ui.lp(TeditTool.WRAP, t.control))
            }
        }

        private fun press(name: String, b: TextView) {
            val term = terminal
            when (name) {
                "CTRL", "ALT" -> {
                    val on = !b.isActivated
                    b.isActivated = on
                    b.setTextColor(if (on) t.accentText else t.text)
                    b.background = ui.states(selected = on)
                    if (name == "CTRL") term.setCtrl(on) else term.setAlt(on)
                }
                "PASTE" -> paste()
                "FIND" -> { term.clearStickyModifiers(); find() }
                "ZOOM_IN" -> { term.clearStickyModifiers(); term.zoomIn() }
                "ZOOM_OUT" -> { term.clearStickyModifiers(); term.zoomOut() }
                "SCROLL_BOTTOM" -> { term.clearStickyModifiers(); term.toBottom() }
                else -> term.sendSpecial(name)
            }
            if (name != "FIND") term.requestFocus()
        }

        /** The terminal used a held modifier for one key; let it go. */
        fun release(name: String) {
            val b = sticky[name] ?: return
            b.isActivated = false
            b.setTextColor(t.text)
            b.background = ui.states()
        }
    }

    companion object {
        private const val POLL_MS = 250L
        /** How long the terminal's size has to hold before the shell is told of it. */
        private const val SETTLE_MS = 120L
        /** The panel height under which the extra keys row gives its room to the terminal. */
        private const val KEYS_MIN_PANEL_DP = 150f
        const val MIN_FONT = 9f
        const val MAX_FONT = 22f
        private val setup = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-term-setup").apply { isDaemon = true } }

        /** dracxterm's extra keys in its order: label, the name the terminal knows, what a screen reader says if different. */
        private val KEYS = listOf(
            Triple("▲", "UP", R.string.tedit_key_up), Triple("▼", "DOWN", R.string.tedit_key_down),
            Triple("⇩", "SCROLL_BOTTOM", R.string.tedit_key_bottom),
            Triple("⊖", "ZOOM_OUT", R.string.tedit_term_font_smaller), Triple("⊕", "ZOOM_IN", R.string.tedit_term_font_bigger),
            Triple("CTRL", "CTRL", null), Triple("◀", "LEFT", R.string.tedit_key_left), Triple("▶", "RIGHT", R.string.tedit_key_right),
            Triple("ALT", "ALT", null), Triple("ESC", "ESC", null), Triple("TAB", "TAB", null),
            Triple("HOME", "HOME", null), Triple("END", "END", null), Triple("PGUP", "PGUP", null), Triple("PGDN", "PGDN", null),
            Triple("FIND", "FIND", R.string.tedit_term_find), Triple("PASTE", "PASTE", R.string.tedit_paste),
            Triple("⌫", "BKSP", R.string.tedit_key_backspace),
        )
    }
}
