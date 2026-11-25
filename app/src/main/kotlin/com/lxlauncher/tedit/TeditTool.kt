// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.files.FileServices
import com.lxlauncher.files.FsNode
import com.lxlauncher.files.LocalBackend
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.windows.ToolView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A side panel: Explorer, Search, Source Control, Run and Debug, Extensions or Problems. */
interface TPanel {
    val id: String
    val title: Int
    val glyph: TIcon.Glyph
    val view: View
    /** Buttons for the panel's header: icon, label, action. */
    fun actions(): List<Triple<TIcon.Glyph, CharSequence, (View) -> Unit>> = emptyList()
    fun onShow() {}
    fun onHide() {}
    fun dispose() {}
}

/**
 * Tedit, the text editor studio inside LX: a window with an activity rail, side panels, one or two editor groups,
 * an optional terminal, and a status bar. The File Explorer opens every file here.
 */
@SuppressLint("ViewConstructor")
class TeditTool(private val shell: Shell, initial: String?) : ToolView {
    val prefs = TeditPrefs(shell.activity)
    /** LX's window, with text in the language chosen in Tedit's settings (see TeditLocaleContext). */
    private val localized = TeditLocaleContext(shell.activity, prefs.string(TeditPrefs.LANGUAGE))
    val ctx: Context = localized
    var theme = TeditTheme(ctx, prefs, shell.ui.font, shell.ui.fontMedium)
        private set
    val ui = TeditUi(ctx, theme)
    val store = DocStore(ctx, prefs)
    var editorConfig = EditorConfig(prefs)
        private set
    val commands = CommandRegistry()
    private val main = Handler(Looper.getMainLooper())
    private val storage = FileServices.storage(ctx)

    private val rootFrame = object : FrameLayout(ctx) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            onResized(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (resizeOwed) {
                resizeOwed = false
                onResized(width, height)
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && overlays.isEmpty && onShortcut(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }
    override val view: View get() = rootFrame
    override val fillsFrame: Boolean get() = true
    override val keepsEdgeTouches: Boolean get() = true

    lateinit var overlays: Overlays
        private set
    private val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val overlayHost = FrameLayout(ctx)

    // Layout pieces, rebuilt by build().
    private lateinit var header: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var rail: View
    private lateinit var dock: LinearLayout
    private lateinit var dockContent: FrameLayout
    private lateinit var dockTitle: TextView
    private lateinit var dockActions: LinearLayout
    private lateinit var editorArea: SplitLayout
    private lateinit var bottom: LinearLayout
    private lateinit var status: LinearLayout
    private lateinit var keyBar: HorizontalScrollView
    private var drawer: FrameLayout? = null
    private var workspaceChip: TextView? = null

    val groups = ArrayList<EditorGroup>()
    var activeGroup: EditorGroup? = null
        private set
    var workspace: WorkspaceRoot? = null
        private set
    private val panels = LinkedHashMap<String, TPanel>()
    var panelId: String = "explorer"
        private set
    var panelOpen = false
        private set
    var terminalOpen = false
        private set
    private var narrow = true
    /** Built for a window too small to dock the side panel. */
    private var overlaid = false
    private var landscape = false
    private var titleView: TextView? = null
    private var destroyed = false
    private val closedTabs = ArrayList<DocSource>()
    private val prefListener: (String) -> Unit = { onPref(it) }
    private var pendingExtra: String? = initial

    val editorHost = Host()
    val terminal: TerminalPanel by lazy { TerminalPanel(this) }
    val runner: TaskRunner by lazy { TaskRunner(this) }
    val git: GitStatusModel by lazy { GitStatusModel(this) }
    val problems: ProblemsModel by lazy { ProblemsModel(this) }
    val lsp: com.lxlauncher.tedit.lsp.LspManager by lazy { com.lxlauncher.tedit.lsp.LspManager(this) }
    val completion: CompletionPopup by lazy { CompletionPopup(this) }

    // ---- layout ----

    /**
     * True while a drag resizes the window (see [onLiveResize]). The size class, the side panel's width and the header's
     * height then wait for the finger to lift: each of them asks for a layout of the whole editor, and crossing the
     * 300 dp line rebuilt every view in the middle of the drag (66 ms frames on the Infinix test phone).
     */
    private var liveResize = false
    /** A drag has ended; the next layout applies the size it left (see [onLiveResize]). */
    private var resizeOwed = false

    private fun onResized(w: Int, h: Int) {
        if (w == 0 || h == 0 || liveResize) return
        val dpW = w / ctx.resources.displayMetrics.density
        val nowNarrow = dpW < 600f
        // The phone's orientation, not the window's shape: the keyboard makes a portrait window wider than tall.
        val nowLand = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val nowOverlay = w < theme.dpi(300f)
        if (nowNarrow != narrow || nowLand != landscape || nowOverlay != overlaid) {
            narrow = nowNarrow
            landscape = nowLand
            overlaid = nowOverlay
            rootFrame.post { if (!destroyed) build() }
        }
        // The side panel's share of the window follows the window's width (not its height: the keyboard changes that).
        if (w != lastWidth) {
            lastWidth = w
            rootFrame.post {
                if (destroyed || !::dock.isInitialized || dock.parent !== body) return@post
                val pw = panelWidth()
                val lp = dock.layoutParams
                if (lp != null && lp.width != pw) { lp.width = pw; dock.layoutParams = lp }
            }
        }
        rootFrame.post { updateKeyBar(); applyHeightMode(rootFrame.height); fitTerminal() }
    }

    private var lastWidth = 0
    private var heightMode = -1
    private var statusBox: View? = null

    /**
     * A short window (landscape on a phone, or the keyboard up) keeps the editor in sight: under 420 dp the path bar
     * goes and the header gets lower; under 230 dp, while the keyboard is up, the header and the status bar step aside.
     * A window that is small without the keyboard keeps its lower header: it holds the menu, Run and Settings, and
     * nothing would bring them back. Done in place, without a rebuild, so the keyboard keeps its connection to the editor.
     */
    private fun applyHeightMode(h: Int) {
        if (h <= 0 || !::header.isInitialized) return
        val tiny = h < theme.dpi(230f)
        val mode = when { tiny && imeUp() -> 2; tiny -> 3; h < theme.dpi(420f) -> 1; else -> 0 }
        if (mode == heightMode) return
        heightMode = mode
        header.visibility = if (mode == 2) View.GONE else View.VISIBLE
        header.layoutParams?.let { it.height = if (mode >= 1) theme.dpi(36f) else theme.headerH; header.layoutParams = it }
        // The status bar only informs; a window this short gives its line to the work.
        statusBox?.visibility = if (mode >= 2) View.GONE else View.VISIBLE
        groups.forEach { if (it.isBuilt()) it.setCompact(mode >= 1) }
    }

    /** Only a very small window (under 300 dp) covers the editor with the side panel; any other docks it, as VS Code does. */
    private fun overlayDock(): Boolean = rootFrame.width in 1 until theme.dpi(300f)

    private fun railVisible(): Boolean = when (prefs.string(TeditPrefs.RAIL)) {
        "hide" -> false
        else -> !overlayDock()
    }

    /** Builds every view from the current state; called again when the size class, theme or density changes. */
    fun build() {
        val focusedGroup = activeGroup
        val editorHadFocus = focusedGroup?.isBuilt() == true && focusedGroup.editor.hasFocus()
        content.removeAllViews()
        drawer?.let { rootFrame.removeView(it) }
        drawer = null
        ui.t = theme
        rootFrame.setBackgroundColor(theme.bg)
        content.setBackgroundColor(theme.bg)
        header = buildHeader()
        header.addOnLayoutChangeListener(refit)
        content.addView(header, ui.lp(MATCH, theme.headerH))
        content.addView(ui.divider())
        body = ui.row(gravity = Gravity.NO_GRAVITY)
        // With the keyboard up and the terminal open the body can be shorter than one rail button; a cut-off icon
        // is hidden rather than shown in part.
        body.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            if (::rail.isInitialized) {
                val show = if (bottom - top >= theme.railW + theme.dpi(6f)) View.VISIBLE else View.INVISIBLE
                if (rail.visibility != show) rail.post { rail.visibility = show }
            }
        }
        content.addView(body, ui.lp(MATCH, 0, 1f))
        if (railVisible()) {
            rail = buildRail()
            body.addView(rail, ui.lp(theme.railW, MATCH))
            body.addView(ui.divider(vertical = true))
        }
        dock = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setBackgroundColor(theme.panel) }
        buildDockHeader()
        dockContent = FrameLayout(ctx)
        dock.addView(dockContent, ui.lp(MATCH, 0, 1f))
        val center = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        editorArea = SplitLayout(ctx, this)
        for (g in groups) editorArea.addView(g.build())
        editorArea.vertical = !landscape
        center.addView(editorArea, ui.lp(MATCH, 0, 1f))
        bottom = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        // The terminal sits under the editors on a wide screen, and spans the window on a phone, where it needs the room.
        if (!narrow) center.addView(bottom, ui.lp(MATCH, TeditTool.WRAP))
        if (!overlayDock()) {
            body.addView(dock, ui.lp(panelWidth(), MATCH))
            body.addView(ResizeHandle(ctx, this, horizontal = true) { dx -> resizeDock(dx) }, ui.lp(theme.dpi(if (narrow) 8f else 6f), MATCH))
        }
        body.addView(center, ui.lp(0, MATCH, 1f))
        if (narrow) content.addView(bottom, ui.lp(MATCH, TeditTool.WRAP))
        keyBar = buildKeyBar()
        content.addView(keyBar, ui.lp(MATCH, theme.dpi(38f)))
        content.addView(ui.divider())
        status = ui.row()
        val statusScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(theme.panel)
            addView(status, FrameLayout.LayoutParams(MATCH, theme.statusH))
        }
        content.addView(statusScroll, ui.lp(MATCH, theme.statusH))
        statusBox = statusScroll
        statusScroll.addOnLayoutChangeListener(refit)
        refreshDock()
        refreshBottom()
        refreshStatus()
        updateKeyBar()
        activeGroup = focusedGroup ?: groups.firstOrNull()
        highlightGroup()
        updateTitle()
        if (editorHadFocus) rootFrame.post { focusEditor(activeGroup) }
        heightMode = -1
        rootFrame.post { applyHeightMode(rootFrame.height) }
    }

    /**
     * Side panel width. On a wide screen the Panel width setting; on a phone a share of the window (about two fifths,
     * as in the reference), or what the person dragged it to.
     */
    private fun panelWidth(): Int {
        val w = rootFrame.width
        if (!narrow) return theme.dpi(prefs.float(TeditPrefs.PANEL_WIDTH)).coerceAtMost(maxOf(theme.dpi(200f), w / 2))
        val dragged = prefs.raw("panelNarrowDp")?.toFloatOrNull()
        val px = if (dragged != null) theme.dpi(dragged) else (w * 0.40f).toInt()
        return px.coerceIn(theme.dpi(110f), maxOf(theme.dpi(120f), (w * 0.7f).toInt()))
    }

    private fun resizeDock(dx: Float) {
        val lp = dock.layoutParams ?: return
        val min = theme.dpi(if (narrow) 100f else 180f)
        val w = (lp.width + dx).toInt().coerceIn(min, maxOf(min + theme.dpi(20f), rootFrame.width - theme.dpi(if (narrow) 120f else 260f)))
        lp.width = w
        dock.layoutParams = lp
        val dp = w / (ctx.resources.displayMetrics.density * theme.scale)
        if (narrow) prefs.putRaw("panelNarrowDp", dp.toString()) else prefs.set(TeditPrefs.PANEL_WIDTH, dp.coerceIn(200f, 480f))
    }

    private fun buildHeader(): LinearLayout {
        val t = theme
        val h = ui.row().apply { setBackgroundColor(t.panel); setPadding(t.dpi(2f), 0, t.dpi(4f), 0) }
        h.addView(ui.iconButton(TIcon.Glyph.MENU, ctx.getString(R.string.tedit_main_menu), t.text) { mainMenu(it) })
        // Under 300 dp the header holds only what fits: the logo without the name (the window's title says Tedit), and
        // Settings moves to the More menu, where it is listed already.
        val tight = overlaid
        h.addView(LogoMark(ctx, t), ui.lp(t.dpi(24f), t.dpi(24f)).apply { marginStart = t.dpi(2f); marginEnd = t.dpi(if (tight) 2f else 8f) })
        if (!tight) h.addView(ui.text("Tedit", t.textTitle * 1.07f, t.text, medium = true).apply { typeface = android.graphics.Typeface.create(t.uiFontMedium, android.graphics.Typeface.BOLD) })
        // The workspace picker only where there is room for it.
        workspaceChip = if (!narrow) ui.text(workspace?.name ?: ctx.getString(R.string.tedit_no_folder), t.textSmall, t.text2).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = t.dpi(32f)
            maxWidth = t.dpi(220f)
            setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
            background = GradientDrawable().apply { cornerRadius = t.radius; setStroke(t.stroke, t.border) }
            foreground = ui.states()
            isFocusable = true
            contentDescription = ctx.getString(R.string.tedit_workspace_picker)
            setOnClickListener { workspaceMenu(it) }
        } else null
        workspaceChip?.let { h.addView(it, ui.lp(TeditTool.WRAP, t.dpi(32f)).apply { marginStart = t.dpi(14f) }) }
        h.addView(View(ctx), ui.lp(0, 1, 1f))
        if (!railVisible()) h.addView(ui.iconButton(TIcon.Glyph.FILES, ctx.getString(R.string.tedit_panel_explorer), t.text) { togglePanel("explorer") })
        h.addView(ui.iconButton(TIcon.Glyph.SEARCH, ctx.getString(R.string.tedit_panel_search), t.text) { showPanel("search") })
        // Run: the one filled control in the header, in the success green.
        val run = ui.iconButton(TIcon.Glyph.RUN, ctx.getString(R.string.tedit_run_task), t.success) { runCommand("run.task") }
        val runBg = GradientDrawable().apply { cornerRadius = t.dp(8f); setColor(TeditTheme.withAlpha(t.success, if (t.dark) 0.18f else 0.16f)) }
        keepPadding(run) { run.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.InsetDrawable(runBg, t.dpi(4f)), ui.states(t.dp(8f)))) }
        h.addView(run)
        if (!tight) h.addView(ui.iconButton(TIcon.Glyph.SETTINGS, ctx.getString(R.string.tedit_settings), t.text) { openSettings() })
        h.addView(ui.iconButton(TIcon.Glyph.MORE, ctx.getString(R.string.tedit_more), t.text) { overflowMenu(it) })
        return h
    }

    /** An inset background brings its inset as padding; this keeps the icon's own padding instead. */
    private inline fun keepPadding(v: View, set: () -> Unit) {
        val l = v.paddingLeft; val tp = v.paddingTop; val r = v.paddingRight; val b = v.paddingBottom
        set()
        v.setPadding(l, tp, r, b)
    }

    /**
     * The activity rail. In a window too short for all its buttons it scrolls, so none is cut off out of reach; when
     * they fit, it fills the height and the terminal button keeps to the bottom.
     */
    private fun buildRail(): View {
        val t = theme
        val r = ui.row(vertical = true, gravity = Gravity.CENTER_HORIZONTAL).apply { setPadding(0, t.dpi(6f), 0, t.dpi(4f)) }
        for (id in RAIL_ORDER) {
            val (glyph, title) = PANEL_FACES.getValue(id)
            val on = panelOpen && panelId == id
            val b = ui.iconButton(glyph, ctx.getString(title), if (on) t.text else t.text2, t.railW) { togglePanel(id) }
            // The open panel's icon sits in a rounded tile, as in the reference.
            val tile = GradientDrawable().apply { cornerRadius = t.dp(8f); setColor(if (on) TeditTheme.withAlpha(t.accent, if (t.dark) 0.22f else 0.16f) else 0) }
            keepPadding(b) { b.background = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.InsetDrawable(tile, t.dpi(4f)), ui.states(t.dp(8f)))) }
            b.isSelected = on
            TeditUi.stateDesc(b, if (on) ctx.getString(R.string.tedit_open_state) else null)
            r.addView(b, ui.lp(t.railW, t.railW).apply { bottomMargin = t.dpi(2f) })
        }
        r.addView(View(ctx), ui.lp(1, 0, 1f))
        r.addView(ui.iconButton(TIcon.Glyph.TERMINAL, ctx.getString(R.string.tedit_terminal), if (terminalOpen) t.accentText else t.text2, t.railW) { toggleTerminal() })
        return android.widget.ScrollView(ctx).apply {
            setBackgroundColor(t.panel)
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(r, FrameLayout.LayoutParams(MATCH, TeditTool.WRAP))
        }
    }

    private fun buildDockHeader() {
        val t = theme
        val head = ui.row().apply { setPadding(t.dpi(12f), 0, t.dpi(2f), 0) }
        // The panel's name in small capitals, as VS Code labels its side bar.
        dockTitle = ui.text("", t.textCaption, t.text2, medium = true).apply { isAllCaps = true; letterSpacing = 0.06f }
        head.addView(dockTitle, ui.lp(0, TeditTool.WRAP, 1f))
        dockActions = ui.row()
        head.addView(dockActions)
        if (overlayDock()) head.addView(ui.iconButton(TIcon.Glyph.CLOSE, ctx.getString(R.string.tedit_close_panel)) { closePanel() })
        dock.addView(head, ui.lp(MATCH, t.dpi(36f)))
    }

    fun panel(id: String): TPanel = panels.getOrPut(id) {
        when (id) {
            "explorer" -> ExplorerPanel(this)
            "search" -> SearchPanel(this)
            "scm" -> ScmPanel(this)
            "run" -> RunPanel(this)
            "extensions" -> ExtensionsPanel(this)
            else -> ProblemsPanel(this)
        }
    }

    private fun refreshDock() {
        val t = theme
        if (overlayDock()) {
            (dock.parent as? ViewGroup)?.removeView(dock)
            drawer?.let { rootFrame.removeView(it) }
            drawer = null
            if (panelOpen) {
                val scrim = FrameLayout(ctx).apply {
                    setBackgroundColor(t.scrim)
                    isClickable = true
                    setOnClickListener { closePanel() }
                }
                val card = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setBackgroundColor(t.panel); elevation = t.dp(12f); isClickable = true }
                // Too small for a rail: the panels switch from a row at the top of the drawer.
                val switcher = ui.row().apply { setPadding(t.dpi(4f), 0, t.dpi(4f), 0) }
                for (id in RAIL_ORDER) {
                    val (glyph, title) = PANEL_FACES.getValue(id)
                    val on = panelId == id
                    val b = ui.iconButton(glyph, ctx.getString(title), if (on) t.accentText else t.text2, t.dpi(44f)) { showPanel(id) }
                    b.background = ui.states(selected = on)
                    b.isSelected = on
                    switcher.addView(b, ui.lp(0, t.touch, 1f))
                }
                card.addView(switcher, ui.lp(MATCH, t.touch))
                card.addView(ui.divider())
                card.addView(dock, ui.lp(MATCH, 0, 1f))
                scrim.addView(card, FrameLayout.LayoutParams((rootFrame.width * 0.9f).toInt(), MATCH, Gravity.START))
                rootFrame.addView(scrim, rootFrame.indexOfChild(overlayHost), FrameLayout.LayoutParams(MATCH, MATCH))
                drawer = scrim
                if (t.animations) { card.translationX = -t.dp(40f); card.alpha = 0.6f; card.animate().translationX(0f).alpha(1f).setDuration(t.durShort).start() }
            }
        } else {
            dock.visibility = if (panelOpen) View.VISIBLE else View.GONE
            (body.getChildAt(body.indexOfChild(dock) + 1))?.visibility = dock.visibility
        }
        dockContent.removeAllViews()
        dockActions.removeAllViews()
        if (!panelOpen) return
        val p = panel(panelId)
        dockTitle.text = ctx.getString(p.title)
        fillDockActions(p)
        (p.view.parent as? ViewGroup)?.removeView(p.view)
        dockContent.addView(p.view, FrameLayout.LayoutParams(MATCH, MATCH))
        p.onShow()
    }

    /** A panel's buttons in its header; in a narrow panel all but the last fold into the More menu. */
    private fun fillDockActions(p: TPanel) {
        dockActions.removeAllViews()
        val actions = p.actions()
        val room = (dock.layoutParams?.width ?: panelWidth()) - theme.dpi(90f)
        val fits = actions.size * theme.dpi(32f) <= room
        if (fits || actions.size <= 1) {
            for ((g, label, action) in actions) dockActions.addView(ui.iconButton(g, label, theme.text2, theme.dpi(32f)) { action(it) })
            return
        }
        val last = actions.last()
        val folded = if (last.first == TIcon.Glyph.MORE) actions.dropLast(1) else actions
        dockActions.addView(ui.iconButton(TIcon.Glyph.MORE_H, ctx.getString(R.string.tedit_more), theme.text2, theme.dpi(32f)) { anchor ->
            val items = folded.map { (g, label, action) -> TItem(label, g) { action(anchor) } }.toMutableList()
            if (last.first == TIcon.Glyph.MORE) items += TItem(ctx.getString(R.string.tedit_more), TIcon.Glyph.MORE, divider = true) { last.third(anchor) }
            overlays.menu(anchor, items, title = ctx.getString(p.title))
        })
    }

    fun refreshPanelActions() {
        if (!panelOpen) return
        // Only a panel already built: a panel asks for this while it is still being built (the Files panel with a
        // folder open), and building it again from here never ended. Showing the panel fills its actions anyway.
        fillDockActions(panels[panelId] ?: return)
    }

    fun showPanel(id: String) {
        completion.hide()
        if (panelOpen && panelId != id) panels[panelId]?.onHide()
        panelId = id
        panelOpen = true
        if (railVisible() && ::rail.isInitialized) { val i = body.indexOfChild(rail); body.removeViewAt(i); rail = buildRail(); body.addView(rail, i, ui.lp(theme.railW, MATCH)) }
        refreshDock()
        sessionChanged()
    }

    fun togglePanel(id: String) {
        if (panelOpen && panelId == id) closePanel() else showPanel(id)
    }

    fun closePanel() {
        if (!panelOpen) return
        panels[panelId]?.onHide()
        panelOpen = false
        if (railVisible() && ::rail.isInitialized) { val i = body.indexOfChild(rail); body.removeViewAt(i); rail = buildRail(); body.addView(rail, i, ui.lp(theme.railW, MATCH)) }
        refreshDock()
        focusEditor(activeGroup)
        sessionChanged()
    }

    /**
     * After a file opens: in a very small window the side panel covers the editor and closes. On a phone held upright
     * the docked panel also makes way, so the editor gets the full width, unless the setting keeps it open.
     */
    fun panelDone(userOpened: Boolean = true) {
        if (overlayDock()) { closePanel(); return }
        if (userOpened && panelOpen && narrow && !landscape && prefs.string(TeditPrefs.PANEL_AFTER_OPEN) == "close") closePanel()
    }

    private fun refreshBottom() {
        bottom.removeAllViews()
        if (!terminalOpen) return
        bottom.addView(ResizeHandle(ctx, this, horizontal = false) { dy -> resizeTerminal(dy) }, ui.lp(MATCH, theme.dpi(6f)))
        bottom.addView(ui.divider())
        val v = terminal.view
        (v.parent as? ViewGroup)?.removeView(v)
        bottom.addView(v, ui.lp(MATCH, terminalHeight()))
        terminal.onShow()
    }

    private fun imeUp(): Boolean = if (Build.VERSION.SDK_INT >= 30) rootFrame.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        else ctx.resources.configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_NO

    /** The height the terminal and the editors share: the window less what is drawn above and below both. */
    private fun sharedRoom(): Int {
        if (!::header.isInitialized || rootFrame.height <= 0) return 0
        // A header just built has no height yet; the room is unknown until it is measured (see refit).
        if (header.visibility == View.VISIBLE && header.height == 0) return 0
        fun h(v: View?) = if (v != null && v.visibility == View.VISIBLE) v.height else 0
        return rootFrame.height - h(header) - h(if (::keyBar.isInitialized) keyBar else null) - h(statusBox) - theme.dpi(8f)
    }

    /**
     * The terminal's height: the one the person dragged it to, within what the window leaves. When the window cannot
     * hold both the terminal and a usable editor (its tabs and a few lines), the terminal takes the whole of it, as
     * VS Code's maximised panel does, instead of leaving a sliver of editor; closing the terminal gives the editor back.
     */
    private fun terminalHeight(): Int {
        val saved = theme.dpi(prefs.raw("termHeight")?.toFloatOrNull() ?: 220f)
        val room = sharedRoom()
        if (room <= 0) return saved.coerceIn(theme.dpi(TERM_MIN_DP), maxOf(theme.dpi(TERM_MIN_DP), (rootFrame.height * 0.7f).toInt()))
        val editorMin = theme.dpi(EDITOR_MIN_DP)
        return if (room - theme.dpi(TERM_MIN_DP) < editorMin) room else saved.coerceIn(theme.dpi(TERM_MIN_DP), room - editorMin)
    }

    /** Fits the terminal again once the bars around it have their new heights (a rebuild, a lower header). */
    private val refit = View.OnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top != oldBottom - oldTop) rootFrame.post { fitTerminal() }
    }

    /** True while the terminal takes the window's whole shared height (see [terminalHeight]). */
    private var terminalFills = false

    /** Keeps the terminal within the window after it shrinks, as it does when the keyboard comes up. */
    private fun fitTerminal() {
        if (destroyed || !::body.isInitialized) return
        val fills = terminalOpen && sharedRoom() > 0 && sharedRoom() - theme.dpi(TERM_MIN_DP) < theme.dpi(EDITOR_MIN_DP)
        if (fills != terminalFills) {
            terminalFills = fills
            body.visibility = if (fills) View.GONE else View.VISIBLE
        }
        // The drag handle shares the height between terminal and editors; with no editors on screen it has nothing to do.
        if (::bottom.isInitialized) for (i in 0 until minOf(2, bottom.childCount)) {
            val c = bottom.getChildAt(i)
            if (c !== terminal.view) c.visibility = if (terminalFills) View.GONE else View.VISIBLE
        }
        if (!terminalOpen) return
        val v = terminal.view
        val lp = v.layoutParams ?: return
        val want = terminalHeight()
        if (lp.height != want) { lp.height = want; v.layoutParams = lp }
    }

    private fun resizeTerminal(dy: Float) {
        val v = terminal.view
        val lp = v.layoutParams ?: return
        val room = sharedRoom()
        val most = if (room > 0) maxOf(theme.dpi(TERM_MIN_DP), room - theme.dpi(EDITOR_MIN_DP)) else maxOf(theme.dpi(120f), (rootFrame.height * 0.75f).toInt())
        lp.height = (lp.height - dy).toInt().coerceIn(theme.dpi(TERM_MIN_DP), most)
        v.layoutParams = lp
        prefs.putRaw("termHeight", (lp.height / (ctx.resources.displayMetrics.density * theme.scale)).toString())
    }

    fun toggleTerminal() {
        completion.hide()
        terminalOpen = !terminalOpen
        refreshBottom()
        rootFrame.post { fitTerminal() }
        if (railVisible() && ::rail.isInitialized) { val i = body.indexOfChild(rail); body.removeViewAt(i); rail = buildRail(); body.addView(rail, i, ui.lp(theme.railW, MATCH)) }
        if (terminalOpen) terminal.focusInput() else focusEditor(activeGroup)
        sessionChanged()
    }

    fun showTerminal() {
        if (!terminalOpen) toggleTerminal() else terminal.focusInput()
    }

    // ---- key bar ----

    private fun buildKeyBar(): HorizontalScrollView {
        val t = theme
        val row = ui.row()
        fun key(label: String, desc: String = label, action: () -> Unit) {
            row.addView(ui.text(label, t.textBody, t.text, medium = true).apply {
                typeface = t.codeFont
                gravity = Gravity.CENTER
                minWidth = t.dpi(36f)
                setPadding(t.dpi(7f), 0, t.dpi(7f), 0)
                background = ui.states()
                contentDescription = desc
                isFocusable = false
                setOnClickListener { action(); performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }
            }, ui.lp(TeditTool.WRAP, MATCH))
        }
        fun glyph(g: TIcon.Glyph, desc: String, action: () -> Unit) {
            row.addView(ui.iconButton(g, desc, t.text, t.dpi(38f)) { action(); it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP) }.apply { isFocusable = false })
        }
        val ed = { activeGroup?.editor }
        glyph(TIcon.Glyph.TAB, "Tab") { ed()?.tabKey(false) }
        glyph(TIcon.Glyph.ARROW_LEFT, ctx.getString(R.string.tedit_key_left)) { ed()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) }
        glyph(TIcon.Glyph.ARROW_UP, ctx.getString(R.string.tedit_key_up)) { ed()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)) }
        glyph(TIcon.Glyph.ARROW_DOWN, ctx.getString(R.string.tedit_key_down)) { ed()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)) }
        glyph(TIcon.Glyph.ARROW_RIGHT, ctx.getString(R.string.tedit_key_right)) { ed()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) }
        for (s in listOf("{", "}", "(", ")", "[", "]", ";", "\"", "'", "<", ">", "/", "=", ":", "_", "-", "+", "*", "&", "|", "!", "#", "$", "\\", "`", ".", ",")) key(s) { ed()?.typeText(s) }
        glyph(TIcon.Glyph.UNDO, ctx.getString(R.string.tedit_undo)) { ed()?.undo() }
        glyph(TIcon.Glyph.REDO, ctx.getString(R.string.tedit_redo)) { ed()?.redo() }
        glyph(TIcon.Glyph.KEY_HIDE, ctx.getString(R.string.tedit_hide_keyboard)) { ed()?.hideKeyboard() }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(t.panel)
            addView(row, FrameLayout.LayoutParams(TeditTool.WRAP, MATCH))
            visibility = View.GONE
        }
    }

    /** The extra keys row shows while the editor has focus and the on-screen keyboard is up. */
    fun updateKeyBar() {
        if (!::keyBar.isInitialized) return
        val ed = activeGroup?.editor
        val show = prefs.bool(TeditPrefs.KEY_BAR) && ed != null && ed.hasFocus() && imeUp() && ed.session != null
        val vis = if (show) View.VISIBLE else View.GONE
        if (keyBar.visibility != vis) keyBar.visibility = vis
    }

    // ---- groups and tabs ----

    fun activateGroup(g: EditorGroup) {
        if (activeGroup === g) return
        activeGroup = g
        highlightGroup()
        refreshStatus()
        updateTitle()
    }

    private fun highlightGroup() {
        if (groups.size < 2) { groups.forEach { if (it.isBuilt()) it.root.foreground = null }; return }
        for (g in groups) {
            if (!g.isBuilt()) continue
            g.root.foreground = if (g === activeGroup) GradientDrawable().apply { setStroke(theme.dpi(1f), TeditTheme.withAlpha(theme.accent, 0.5f)); setColor(0) } else null
        }
    }

    fun focusEditor(g: EditorGroup?) {
        val e = g?.editor ?: return
        if (e.session != null) e.requestFocus()
    }

    fun groupTabChanged(g: EditorGroup) {
        if (g === activeGroup) { refreshStatus(); updateTitle() }
        if (prefs.string(TeditPrefs.AUTO_SAVE) == "focus") saveAllQuiet()
        problems.schedule()
        sessionChanged()
        (panels["explorer"] as? ExplorerPanel)?.activeChanged()
    }

    fun docStateChanged(d: Document) {
        if (activeGroup?.active?.doc === d) { refreshStatus(); updateTitle() }
        groups.forEach { if (it.isBuilt()) it.updateBanner() }
        (panels["explorer"] as? ExplorerPanel)?.docsChanged()
    }

    /** Opens [src] in the active group (or [into]), and puts the cursor at [line], [col] once it is loaded. */
    fun open(src: DocSource, into: EditorGroup? = null, line: Int = -1, col: Int = 0, endCol: Int = -1, focus: Boolean = true) {
        val g = into ?: activeGroup ?: groups[0]
        val existing = store.byKey(src.key)
        val doc = existing ?: store.open(src) { d ->
            if (d.loadError == null && line >= 0) jumpWhenReady(g, d, line, col, endCol)
            if (d.loadError == null) store.remember("files", src, d.name, store.files.displayPath(src))
            if (d.loadError == null) lsp.opened(d)
            problems.schedule()
        }
        g.add(doc, activate = true)
        activateGroup(g)
        if (existing != null && line >= 0) jumpWhenReady(g, doc, line, col, endCol)
        if (focus) main.post { focusEditor(g) }
        panelDone(userOpened = focus)
    }

    private fun jumpWhenReady(g: EditorGroup, d: Document, line: Int, col: Int, endCol: Int) {
        if (d.loading) return
        val tab = g.tabs.firstOrNull { it.doc === d } ?: return
        if (g.active !== tab) g.select(tab)
        main.post {
            if (endCol >= 0) g.editor.select(Pos(line, col), Pos(line, endCol), center = true) else g.editor.goToLine(line, col)
        }
    }

    fun openNode(node: FsNode, line: Int = -1) = open(DocSource.of(node), line = line)

    fun newFile() {
        val d = store.untitled()
        val g = activeGroup ?: groups[0]
        g.add(d, activate = true)
        main.post { g.editor.showKeyboard() }
        panelDone()
    }

    fun closeTab(g: EditorGroup, tab: Tab, force: Boolean, after: (() -> Unit)? = null) {
        val d = tab.doc
        val elsewhere = groups.any { it !== g && it.tabs.any { t2 -> t2.doc === d } }
        if (!force && d.dirty && !elsewhere) {
            overlays.saveChanges(d.name, onSave = {
                save(d) { ok -> if (ok) closeTab(g, tab, force = true, after) }
            }, onDiscard = { closeTab(g, tab, force = true, after) })
            return
        }
        g.remove(tab)
        if (!elsewhere) {
            d.cancelLoad = true
            lsp.closed(d)
            store.close(d, keepBackup = false)
            d.source?.let { closedTabs.remove(it); closedTabs += it; if (closedTabs.size > 20) closedTabs.removeAt(0) }
        }
        if (groups.size > 1 && g.tabs.isEmpty()) unsplit(keep = groups.first { it !== g })
        sessionChanged()
        problems.schedule()
        after?.invoke()
    }

    /** Closes [tabs] one after another, asking about each one with unsaved changes. */
    fun closeTabs(g: EditorGroup, tabs: List<Tab>) {
        val queue = ArrayDeque(tabs)
        fun next() {
            val tab = queue.removeFirstOrNull() ?: return
            if (tab !in g.tabs) { next(); return }
            closeTab(g, tab, force = false) { next() }
        }
        next()
    }

    fun reopenClosed() {
        val src = closedTabs.removeLastOrNull() ?: return
        open(src)
    }

    fun tabMenu(g: EditorGroup, tab: Tab, anchor: View) {
        val i = g.tabs.indexOf(tab)
        val items = listOf(
            TItem(ctx.getString(R.string.tedit_close_tab), TIcon.Glyph.CLOSE, "Ctrl+W") { closeTab(g, tab, force = false) },
            TItem(ctx.getString(R.string.tedit_close_others), enabled = g.tabs.size > 1, reason = ctx.getString(R.string.tedit_why_one_tab)) { closeTabs(g, g.tabs.filter { it !== tab && !it.pinned }) },
            TItem(ctx.getString(R.string.tedit_close_right), enabled = i < g.tabs.size - 1, reason = ctx.getString(R.string.tedit_why_last_tab)) { closeTabs(g, g.tabs.drop(i + 1).filter { !it.pinned }) },
            TItem(ctx.getString(R.string.tedit_close_saved)) { g.tabs.filter { !it.doc.dirty && !it.pinned }.forEach { closeTab(g, it, force = true) } },
            TItem(ctx.getString(R.string.tedit_close_all)) { closeTabs(g, g.tabs.filter { !it.pinned }) },
            TItem(ctx.getString(if (tab.pinned) R.string.tedit_unpin else R.string.tedit_pin), TIcon.Glyph.PIN, divider = true) {
                tab.pinned = !tab.pinned
                g.move(tab, if (tab.pinned) g.tabs.count { it.pinned && it !== tab } else g.tabs.count { it.pinned })
                sessionChanged()
            },
            TItem(ctx.getString(R.string.tedit_move_left), TIcon.Glyph.CHEVRON_LEFT, "Ctrl+Shift+PgUp", enabled = i > 0, reason = ctx.getString(R.string.tedit_why_first_tab)) { g.move(tab, i - 1); sessionChanged() },
            TItem(ctx.getString(R.string.tedit_move_right), TIcon.Glyph.CHEVRON_RIGHT, "Ctrl+Shift+PgDn", enabled = i < g.tabs.size - 1, reason = ctx.getString(R.string.tedit_why_last_tab)) { g.move(tab, i + 1); sessionChanged() },
            TItem(ctx.getString(R.string.tedit_split_editor), if (landscape && !narrow) TIcon.Glyph.SPLIT_RIGHT else TIcon.Glyph.SPLIT_DOWN, "Ctrl+\\", divider = true) { split(tab.doc) },
            TItem(ctx.getString(R.string.tedit_copy_path), TIcon.Glyph.COPY, enabled = tab.doc.source != null, reason = ctx.getString(R.string.tedit_why_unsaved_file)) { copyPath(tab.doc) },
            TItem(ctx.getString(R.string.tedit_reveal_explorer), TIcon.Glyph.FILES, enabled = tab.doc.source is DocSource.Node, reason = ctx.getString(R.string.tedit_why_not_in_folder)) { revealInExplorer(tab.doc) },
        )
        overlays.menu(anchor, items, title = tab.doc.name)
    }

    fun nextTab(step: Int) {
        val g = activeGroup ?: return
        if (g.tabs.isEmpty()) return
        val i = g.tabs.indexOf(g.active)
        g.select(g.tabs[((i + step) % g.tabs.size + g.tabs.size) % g.tabs.size])
        focusEditor(g)
    }

    // ---- split ----

    fun split(doc: Document? = activeGroup?.active?.doc) {
        if (groups.size >= 2) {
            // Already split: move the active file to the other group.
            val other = groups.first { it !== activeGroup }
            doc?.let { other.add(it, activate = true) }
            activateGroup(other)
            focusEditor(other)
            return
        }
        val g = EditorGroup(this)
        groups += g
        editorArea.addView(g.build())
        g.setCompact(heightMode >= 1)
        doc?.let { g.add(it, activate = true) }
        activateGroup(g)
        highlightGroup()
        focusEditor(g)
        sessionChanged()
    }

    fun unsplit(keep: EditorGroup) {
        val gone = groups.filter { it !== keep }
        for (g in gone) {
            for (tab in g.tabs.toList()) if (keep.tabs.none { it.doc === tab.doc }) keep.add(tab.doc, activate = false)
            g.dispose()
            editorArea.removeView(g.root)
            groups.remove(g)
        }
        activeGroup = keep
        highlightGroup()
        sessionChanged()
    }

    // ---- saving ----

    fun save(d: Document, done: ((Boolean) -> Unit)? = null) {
        if (d.loading) return
        if (d.source == null || d.readOnly && d.readOnlyReason == R.string.tedit_ro_no_write) { saveAs(d, done); return }
        if (d.readOnly) { overlays.snack(ctx.getString(d.readOnlyReason.takeIf { it != 0 } ?: R.string.tedit_ro_generic)); done?.invoke(false); return }
        if (prefs.bool(TeditPrefs.TRIM_TRAILING)) trimTrailing(d)
        store.save(d, confirmOverwrite = { go ->
            overlays.confirm(ctx.getString(R.string.tedit_overwrite_title), ctx.getString(R.string.tedit_overwrite_message, d.name),
                ctx.getString(R.string.tedit_overwrite), danger = true) { go() }
        }) { err ->
            if (err == null) {
                overlays.snack(ctx.getString(R.string.tedit_saved, d.name))
                groups.forEach { g -> g.changedOnDisk.remove(d.key) }
                lsp.saved(d)
                problems.schedule()
                git.refreshSoon()
            } else overlays.message(ctx.getString(R.string.tedit_save_failed, d.name), err)
            done?.invoke(err == null)
        }
    }

    private fun trimTrailing(d: Document) {
        val buf = d.buffer
        var any = false
        for (i in 0 until buf.lineCount) { val l = buf.line(i); if (l.isNotEmpty() && (l.last() == ' ' || l.last() == '\t')) { any = true; break } }
        if (!any) return
        val text = (0 until buf.lineCount).joinToString("\n") { buf.line(it).trimEnd(' ', '\t') }
        val g = groups.firstOrNull { it.active?.doc === d }
        val caret = g?.active?.session?.caret ?: Pos.ZERO
        d.history.apply(Pos.ZERO, buf.lastPos, text, caret to caret, EditHistory.Kind.OTHER, android.os.SystemClock.uptimeMillis())
        g?.editor?.setSelection(caret, caret)
    }

    /** Save As: a folder of the workspace (or beside the file), or any place through Android's picker. */
    fun saveAs(d: Document, done: ((Boolean) -> Unit)? = null) {
        val options = ArrayList<Pair<CharSequence, () -> Unit>>()
        val here = (d.source as? DocSource.Node)?.let { src -> store.files.backendFor(src.backendKey)?.let { b -> runCatching { store.files.node(src)?.let { b.parent(it) } }.getOrNull() } }
        val ws = workspace?.node
        if (ws != null) options += ctx.getString(R.string.tedit_save_in_workspace, workspace?.name ?: "") to {
            FolderPicker(this, ws, ctx.getString(R.string.tedit_save_as_title)) { dir -> saveInto(d, dir, done) }.show()
        }
        if (here != null && here.key != ws?.key) options += ctx.getString(R.string.tedit_save_beside) to { saveInto(d, here, done) }
        val home = if (ws == null && here == null && storage.local() != null) storage.localNode("/storage/emulated/0") else null
        if (home != null) options += ctx.getString(R.string.tedit_save_in_storage) to {
            FolderPicker(this, home, ctx.getString(R.string.tedit_save_as_title)) { dir -> saveInto(d, dir, done) }.show()
        }
        options += ctx.getString(R.string.tedit_save_with_picker) to {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mimeOf(d.name) ?: "text/plain").putExtra(Intent.EXTRA_TITLE, d.name)
            shell.requestDocument(intent) { uri ->
                if (uri == null) { done?.invoke(false); return@requestDocument }
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                finishSaveAs(d, DocSource.Content(uri.toString()), done)
            }
        }
        if (options.size == 1) { options[0].second(); return }
        overlays.choose(ctx.getString(R.string.tedit_save_as_title), options.map { it.first }, -1) { options[it].second() }
    }

    private fun saveInto(d: Document, dir: FsNode, done: ((Boolean) -> Unit)?) {
        overlays.prompt(ctx.getString(R.string.tedit_save_as_title), d.name, ctx.getString(R.string.tedit_file_name), ctx.getString(R.string.tedit_save),
            selectStem = true, validate = { nameProblem(it) }) { name ->
            store.io.execute {
                try {
                    val existing = dir.backend.child(dir, name)
                    main.post {
                        if (existing != null) {
                            overlays.confirm(ctx.getString(R.string.tedit_overwrite_title), ctx.getString(R.string.tedit_exists_message, name),
                                ctx.getString(R.string.tedit_overwrite), danger = true) { finishSaveAs(d, DocSource.of(existing), done) }
                        } else store.io.execute {
                            try {
                                val src = store.files.create(dir, name)
                                main.post { finishSaveAs(d, src, done) }
                            } catch (e: Throwable) { main.post { overlays.message(ctx.getString(R.string.tedit_save_failed, name), store.errorText(e)); done?.invoke(false) } }
                        }
                    }
                } catch (e: Throwable) { main.post { overlays.message(ctx.getString(R.string.tedit_save_failed, name), store.errorText(e)); done?.invoke(false) } }
            }
        }
    }

    private fun finishSaveAs(d: Document, target: DocSource, done: ((Boolean) -> Unit)?) {
        val oldKey = d.key
        store.save(d, target, confirmOverwrite = null) { err ->
            if (err == null) {
                store.backups.let { b -> store.io.execute { b.delete(oldKey) } }
                store.remember("files", target, d.name, store.files.displayPath(target))
                overlays.snack(ctx.getString(R.string.tedit_saved, d.name))
                groups.forEach { it.refreshTabs() }
                refreshStatus(); updateTitle()
                (panels["explorer"] as? ExplorerPanel)?.refresh()
                sessionChanged()
            } else overlays.message(ctx.getString(R.string.tedit_save_failed, d.name), err)
            done?.invoke(err == null)
        }
    }

    fun saveAll() {
        val dirty = store.docs.values.filter { it.dirty && !it.loading }
        if (dirty.isEmpty()) { overlays.snack(ctx.getString(R.string.tedit_nothing_to_save)); return }
        val queue = ArrayDeque(dirty)
        fun next() { val d = queue.removeFirstOrNull() ?: return; save(d) { next() } }
        next()
    }

    /** Auto save: files that have a place on disk, without asking anything. */
    private fun saveAllQuiet() {
        for (d in store.docs.values) if (d.dirty && d.source != null && !d.readOnly && !d.loading) {
            store.save(d, confirmOverwrite = null) { err -> if (err != null) Log.w(TAG, "auto save failed") }
        }
    }

    fun nameProblem(name: String): CharSequence? = when {
        name.isBlank() -> ctx.getString(R.string.tedit_name_empty)
        name == "." || name == ".." || name.contains('/') || name.contains('\u0000') -> ctx.getString(R.string.tedit_name_bad)
        name.length > 255 -> ctx.getString(R.string.tedit_name_long)
        else -> null
    }

    fun reload(d: Document) {
        val wasDirty = d.dirty
        store.load(d, asText = false) { groups.forEach { it.showContent() } }
        if (wasDirty) overlays.snack(ctx.getString(R.string.tedit_reloaded, d.name))
    }

    fun openAsText(d: Document) = store.load(d, asText = true) { groups.forEach { it.showContent() } }

    // ---- workspace ----

    fun openFolderNode(node: FsNode) {
        store.remember("folders", DocSource.of(node), node.name, node.backend.displayPath(node))
        setWorkspace(node)
        showPanel("explorer")
    }

    /** Every part that works in the workspace hears that it changed: the tree, search, source control, Quick Open. */
    private fun setWorkspace(node: FsNode?) {
        workspace = node?.let { WorkspaceRoot(it) }
        workspaceChip?.text = workspace?.name ?: ctx.getString(R.string.tedit_no_folder)
        FileIndex.invalidate()
        (panels["explorer"] as? ExplorerPanel)?.rootChanged()
        (panels["search"] as? SearchPanel)?.rootChanged()
        (panels["run"] as? RunPanel)?.onShow()
        git.rootChanged()
        refreshPanelActions()
        sessionChanged()
        updateTitle()
    }

    fun closeWorkspace() = setWorkspace(null)

    /** Open Folder: a folder of internal storage (with all-files access), or a folder granted through Android's picker. */
    fun openFolderDialog() {
        val options = ArrayList<Pair<CharSequence, () -> Unit>>()
        if (storage.local() != null) storage.localNode("/storage/emulated/0")?.let { home ->
            options += ctx.getString(R.string.tedit_folder_storage) to { FolderPicker(this, home, ctx.getString(R.string.tedit_open_folder)) { openFolderNode(it) }.show() }
        }
        options += ctx.getString(R.string.tedit_folder_picker) to {
            shell.requestDocument(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { uri ->
                if (uri == null) return@requestDocument
                runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                store.io.execute {
                    val root = runCatching { storage.saf(uri).root }.getOrNull()
                    main.post { if (root != null) openFolderNode(root) else overlays.snack(ctx.getString(R.string.tedit_err_no_access)) }
                }
            }
        }
        if (storage.local() == null) options += ctx.getString(R.string.tedit_folder_why_limited) to {
            overlays.message(ctx.getString(R.string.tedit_storage_limits_title), ctx.getString(R.string.tedit_storage_limits))
        }
        overlays.choose(ctx.getString(R.string.tedit_open_folder), options.map { it.first }, -1) { options[it].second() }
    }

    fun openFileDialog() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        shell.requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .recoverCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            open(DocSource.Content(uri.toString()))
        }
    }

    fun openRecent(r: DocStore.Recent, folder: Boolean) {
        if (!folder) { open(r.source); return }
        val src = r.source as? DocSource.Node ?: return
        store.io.execute {
            val node = runCatching { store.files.node(src) }.getOrNull()
            main.post {
                if (node != null && node.isDir) openFolderNode(node)
                else {
                    overlays.snack(ctx.getString(R.string.tedit_recent_gone, r.name))
                    store.forget("folders", src.key)
                }
            }
        }
    }

    private fun workspaceMenu(anchor: View) {
        val recents = store.recents("folders")
        val items = ArrayList<TItem>()
        items += TItem(ctx.getString(R.string.tedit_open_folder), TIcon.Glyph.FOLDER_OPEN) { openFolderDialog() }
        items += TItem(ctx.getString(R.string.tedit_close_workspace), TIcon.Glyph.CLOSE, enabled = workspace != null, reason = ctx.getString(R.string.tedit_why_no_workspace)) { closeWorkspace() }
        recents.take(8).forEachIndexed { i, r ->
            items += TItem(r.name, TIcon.Glyph.FOLDER, divider = i == 0, checked = null) { openRecent(r, folder = true) }
        }
        overlays.menu(anchor, items, title = ctx.getString(R.string.tedit_workspace_manager))
    }

    /** Label path for the breadcrumb: workspace-relative when inside the workspace. Each folder part opens in Explorer. */
    fun pathParts(d: Document): List<Pair<String, DocSource?>> {
        val src = d.source as? DocSource.Node ?: return listOf(d.name to null)
        val ws = workspace
        if (ws != null && ws.source.backendKey == src.backendKey && src.ref.startsWith(ws.source.ref.trimEnd('/') + "/")) {
            val rel = src.ref.removePrefix(ws.source.ref.trimEnd('/') + "/")
            val parts = rel.split('/')
            val out = ArrayList<Pair<String, DocSource?>>()
            out += ws.name to ws.source
            var acc = ws.source.ref.trimEnd('/')
            for ((i, p) in parts.withIndex()) {
                acc = "$acc/$p"
                out += p to if (i < parts.size - 1) DocSource.Node(src.backendKey, acc) else null
            }
            return out
        }
        if (src.backendKey == "local") {
            val parts = src.ref.trim('/').split('/')
            val tail = parts.takeLast(3)
            val base = "/" + parts.dropLast(tail.size).joinToString("/")
            var acc = base.trimEnd('/')
            return tail.mapIndexed { i, p -> acc = "$acc/$p"; p to (if (i < tail.size - 1) DocSource.Node("local", acc) else null) }
        }
        return listOf(d.name to null)
    }

    fun revealFolder(src: DocSource) {
        val n = src as? DocSource.Node ?: return
        store.io.execute {
            val node = runCatching { store.files.node(n) }.getOrNull()
            main.post {
                if (node == null) return@post
                val ws = workspace
                if (ws == null || ws.source.backendKey != n.backendKey || !(n.ref + "/").startsWith(ws.source.ref.trimEnd('/') + "/")) openFolderNode(node)
                else { showPanel("explorer"); (panel("explorer") as ExplorerPanel).reveal(n.ref) }
            }
        }
    }

    fun revealInExplorer(d: Document) {
        val src = d.source as? DocSource.Node ?: return
        val ws = workspace
        if (ws != null && ws.source.backendKey == src.backendKey && src.ref.startsWith(ws.source.ref.trimEnd('/') + "/")) {
            showPanel("explorer")
            (panel("explorer") as ExplorerPanel).reveal(src.ref)
        } else {
            store.io.execute {
                val node = runCatching { store.files.node(src)?.let { it.backend.parent(it) } }.getOrNull()
                main.post { if (node != null) { openFolderNode(node); (panel("explorer") as ExplorerPanel).reveal(src.ref) } }
            }
        }
    }

    fun copyPath(d: Document) {
        val src = d.source ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(d.name, store.files.displayPath(src)))
        overlays.snack(ctx.getString(R.string.tedit_path_copied))
    }

    fun share(d: Document) {
        val src = d.source ?: return
        store.io.execute {
            val uri: Uri? = when (src) {
                is DocSource.Content -> Uri.parse(src.uri)
                is DocSource.Node -> runCatching { store.files.node(src)?.let { it.backend.shareUri(it) } }.getOrNull()
            }
            main.post {
                if (uri == null) { overlays.snack(ctx.getString(R.string.tedit_err_no_access)); return@post }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mimeOf(d.name) ?: "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(d.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!shell.startSafely(Intent.createChooser(send, ctx.getString(R.string.tedit_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                    overlays.snack(ctx.getString(R.string.tedit_no_share_target))
            }
        }
    }

    fun mimeOf(name: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

    /** Package name and version of an APK on local storage, read with Android's package parser. */
    fun apkLabel(d: Document): String? {
        val src = d.source as? DocSource.Node ?: return null
        if (src.backendKey != "local" || !d.name.endsWith(".apk", true)) return null
        return runCatching {
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(src.ref, 0) ?: return null
            "${info.packageName} ${info.versionName ?: ""}".trim()
        }.getOrNull()
    }

    fun lineMarksFor(g: EditorGroup) {
        if (!prefs.bool(TeditPrefs.SCM_DECORATIONS)) { g.editor.lineMarks = null; return }
        git.lineMarks(g.active?.doc) { marks -> if (g.active?.doc != null) g.editor.lineMarks = marks }
    }

    // ---- welcome ----

    fun welcomeView(g: EditorGroup): View {
        val t = theme
        // On a phone the editor area is narrow: tighter margins, and no shortcut hints (they need a keyboard anyway).
        val tight = narrow
        val pad = t.dpi(if (tight) 14f else 24f)
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(pad, t.dpi(24f), pad, t.dpi(24f)) }
        val head = ui.row()
        head.addView(LogoMark(ctx, t), ui.lp(t.dpi(26f), t.dpi(26f)).apply { marginEnd = t.dpi(10f) })
        val titles = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        titles.addView(ui.text("Tedit", t.textTitle * 1.2f, t.text, medium = true).apply { ellipsize = null })
        titles.addView(ui.text(ctx.getString(R.string.tedit_tagline), t.textSmall, t.textMuted, lines = 3))
        head.addView(titles, ui.lp(0, TeditTool.WRAP, 1f))
        col.addView(head)
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_start)).apply { setPadding(0, t.dpi(24f), 0, t.dpi(6f)) })
        fun action(glyph: TIcon.Glyph, label: String, shortcut: String?, run: () -> Unit) {
            val r = ui.row().apply { minimumHeight = t.touch; background = ui.states(); isFocusable = true; contentDescription = label; setPadding(t.dpi(4f), 0, t.dpi(8f), 0) }
            r.addView(ui.icon(glyph, t.accentText, 20f), ui.lp(t.dpi(20f), t.dpi(20f)).apply { marginEnd = t.dpi(12f) })
            r.addView(ui.text(label, t.textBody, t.accentText, lines = 2), ui.lp(0, TeditTool.WRAP, 1f))
            if (shortcut != null && !tight) r.addView(ui.text(shortcut, t.textCaption, t.textMuted))
            r.setOnClickListener { run() }
            col.addView(r, ui.lp(MATCH, TeditTool.WRAP))
        }
        action(TIcon.Glyph.NEW_FILE, ctx.getString(R.string.tedit_new_file), "Ctrl+N") { activateGroup(g); newFile() }
        action(TIcon.Glyph.FILE, ctx.getString(R.string.tedit_open_file), "Ctrl+O") { activateGroup(g); openFileDialog() }
        action(TIcon.Glyph.FOLDER_OPEN, ctx.getString(R.string.tedit_open_folder), null) { openFolderDialog() }
        action(TIcon.Glyph.COMMAND, ctx.getString(R.string.tedit_command_palette), "Ctrl+Shift+P") { CommandPalette(this, "").show() }
        val files = store.recents("files")
        val folders = store.recents("folders")
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_recent)).apply { setPadding(0, t.dpi(22f), 0, t.dpi(6f)) })
        if (files.isEmpty() && folders.isEmpty()) col.addView(ui.text(ctx.getString(R.string.tedit_recent_empty), t.textSmall, t.textMuted, lines = 0))
        for ((r, folder) in folders.take(4).map { it to true } + files.take(6).map { it to false }) {
            val row = ui.row().apply { minimumHeight = t.touch; background = ui.states(); isFocusable = true; contentDescription = r.name; setPadding(t.dpi(4f), 0, t.dpi(8f), 0) }
            row.addView(ui.icon(if (folder) TIcon.Glyph.FOLDER else TIcon.Glyph.FILE, t.text2, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(12f) })
            val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            labels.addView(ui.text(r.name, t.textBody, t.text))
            labels.addView(ui.text(r.path, t.textCaption, t.textMuted))
            row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            row.setOnClickListener { activateGroup(g); openRecent(r, folder) }
            col.addView(row, ui.lp(MATCH, TeditTool.WRAP))
        }
        return ui.scroll(col)
    }

    // ---- status bar ----

    /**
     * The status bar, as in VS Code: on the left what describes the file (line breaks, encoding, language) and the
     * project (branch, problems, a running task); on the right where the cursor is. Every item opens what changes it.
     */
    /** One part of the status bar; a null [text] is the gap that pushes the rest to the right. */
    private class StatusItem(val text: String?, val glyph: TIcon.Glyph?, val color: Int, val desc: String, val onClick: ((View) -> Unit)?)
    private var shownStatus: List<StatusItem> = emptyList()

    fun refreshStatus() {
        if (!::status.isInitialized) return
        val t = theme
        val items = ArrayList<StatusItem>()
        fun item(text: String, glyph: TIcon.Glyph? = null, color: Int = t.text2, desc: String = text, onClick: ((View) -> Unit)?) {
            items += StatusItem(text, glyph, color, desc, onClick)
        }
        val tab = activeGroup?.active
        val d = tab?.doc
        val textDoc = d != null && !d.loading && !d.image && !d.binary && d.loadError == null
        if (textDoc && d != null) {
            item(if (d.eol == "\r\n") "CRLF" else if (d.eol == "\r") "CR" else "LF", desc = ctx.getString(R.string.tedit_status_eol)) { eolMenu(d) }
            item(d.codec.label, desc = ctx.getString(R.string.tedit_status_encoding, d.codec.label)) { encodingMenu(d) }
            item(d.language.name, desc = ctx.getString(R.string.tedit_status_language, d.language.name)) { languageMenu(d) }
        }
        git.branch?.let { b -> item(b, TIcon.Glyph.BRANCH, desc = ctx.getString(R.string.tedit_status_branch, b)) { showPanel("scm") } }
        val (errs, warns) = problems.counts()
        if (errs + warns > 0) item("$errs · $warns", TIcon.Glyph.ERROR, if (errs > 0) t.error else t.warning, ctx.getString(R.string.tedit_status_problems, errs, warns)) { showPanel("problems") }
        runner.running?.let { task -> item(task, TIcon.Glyph.RUN, t.accentText, ctx.getString(R.string.tedit_status_running, task)) { showPanel("run") } }
        items += StatusItem(null, null, 0, "", null)
        if (textDoc && d != null && tab != null) {
            if (d.readOnly) item(ctx.getString(R.string.tedit_read_only), TIcon.Glyph.LOCK, t.warning) { if (d.readOnlyReason != 0) overlays.message(ctx.getString(R.string.tedit_read_only), ctx.getString(d.readOnlyReason)) }
            if (d.dirty) item(ctx.getString(R.string.tedit_unsaved), TIcon.Glyph.DOT, t.warning) { save(d) }
            val indent = if (editorConfig.insertSpaces) ctx.getString(R.string.tedit_status_spaces, editorConfig.tabSize) else ctx.getString(R.string.tedit_status_tabs, editorConfig.tabSize)
            item(indent) { indentMenu(it) }
            val s = tab.session
            val sel = if (s.hasSelection) d.buffer.text(s.selStart, s.selEnd).length else 0
            val pos = ctx.getString(R.string.tedit_status_pos, s.caret.line + 1, s.caret.col + 1) + if (sel > 0) " " + ctx.getString(R.string.tedit_status_sel, sel) else ""
            item(pos, color = t.text, desc = pos) { gotoLineDialog() }
        }
        // The bar refreshes as the cursor moves; when its parts are the same kinds as before, their views are kept
        // and only text, colour and action change, instead of building every view again on each keystroke.
        val same = items.size == shownStatus.size && status.childCount == items.size && items.indices.all { i ->
            val a = items[i]; val b = shownStatus[i]
            (a.text == null) == (b.text == null) && a.glyph == b.glyph && a.text.isNullOrEmpty() == b.text.isNullOrEmpty() && (a.onClick == null) == (b.onClick == null)
        }
        if (same) {
            for ((i, it) in items.withIndex()) {
                if (it.text == null) continue
                val r = status.getChildAt(i) as? LinearLayout ?: continue
                r.contentDescription = it.desc
                it.onClick?.let { click -> r.setOnClickListener { v -> click(v) } }
                for (k in 0 until r.childCount) when (val c = r.getChildAt(k)) {
                    is TextView -> { if (c.text.toString() != it.text) c.text = it.text; if (c.currentTextColor != it.color) c.setTextColor(it.color) }
                    is android.widget.ImageView -> ui.setIconColor(c, it.color)
                }
            }
            shownStatus = items
            return
        }
        status.removeAllViews()
        status.setPadding(t.dpi(4f), 0, t.dpi(4f), 0)
        for (it in items) {
            if (it.text == null) { status.addView(View(ctx), ui.lp(0, 1, 1f)); continue }
            val click = it.onClick
            val r = ui.row().apply {
                setPadding(t.dpi(9f), 0, t.dpi(9f), 0)
                contentDescription = it.desc
                if (click != null) { background = ui.states(0f); isFocusable = true; setOnClickListener { v -> click(v) } }
            }
            if (it.glyph != null) r.addView(ui.icon(it.glyph, it.color, 14f), ui.lp(t.dpi(14f), t.dpi(14f)).apply { if (it.text.isNotEmpty()) marginEnd = t.dpi(5f) })
            if (it.text.isNotEmpty()) r.addView(ui.text(it.text, t.textSmall, it.color))
            status.addView(r, ui.lp(TeditTool.WRAP, MATCH))
        }
        shownStatus = items
    }

    private fun indentMenu(anchor: View) {
        val items = ArrayList<TItem>()
        items += TItem(ctx.getString(R.string.tedit_indent_spaces), checked = editorConfig.insertSpaces) { prefs.set(TeditPrefs.INSERT_SPACES, true) }
        items += TItem(ctx.getString(R.string.tedit_indent_tabs), checked = !editorConfig.insertSpaces) { prefs.set(TeditPrefs.INSERT_SPACES, false) }
        for ((i, n) in listOf(2, 4, 8).withIndex()) items += TItem(ctx.getString(R.string.tedit_tab_width, n), checked = editorConfig.tabSize == n, divider = i == 0) { prefs.set(TeditPrefs.TAB_SIZE, n) }
        items += TItem(ctx.getString(R.string.tedit_convert_to_spaces), divider = true) { convertIndent(toSpaces = true) }
        items += TItem(ctx.getString(R.string.tedit_convert_to_tabs)) { convertIndent(toSpaces = false) }
        overlays.menu(anchor, items, title = ctx.getString(R.string.tedit_indentation))
    }

    private fun convertIndent(toSpaces: Boolean) {
        val g = activeGroup ?: return
        val d = g.active?.doc ?: return
        if (d.readOnly) { editorHost.readOnlyAttempt(g.editor); return }
        val tab = editorConfig.tabSize
        val buf = d.buffer
        val out = (0 until buf.lineCount).joinToString("\n") { i ->
            val l = buf.line(i)
            var k = 0
            var col = 0
            while (k < l.length && (l[k] == ' ' || l[k] == '\t')) { col = if (l[k] == '\t') (col / tab + 1) * tab else col + 1; k++ }
            val lead = if (toSpaces) " ".repeat(col) else "\t".repeat(col / tab) + " ".repeat(col % tab)
            lead + l.substring(k)
        }
        if (out == buf.text()) return
        g.editor.replace(Pos.ZERO, buf.lastPos, out)
    }

    private fun encodingMenu(d: Document) {
        val labels = TextCodec.entries.map { it.label as CharSequence }
        overlays.choose(ctx.getString(R.string.tedit_encoding_title), labels, TextCodec.entries.indexOf(d.codec)) { i ->
            val codec = TextCodec.entries[i]
            if (codec == d.codec) return@choose
            if (d.source != null && !d.dirty) {
                overlays.choose(ctx.getString(R.string.tedit_encoding_how, codec.label),
                    listOf(ctx.getString(R.string.tedit_reopen_with), ctx.getString(R.string.tedit_save_with)), -1) { how ->
                    if (how == 0) reopenWith(d, codec) else { d.codec = codec; d.forcedDirty = true; d.changed(); save(d) }
                }
            } else { d.codec = codec; d.forcedDirty = true; d.changed() }
        }
    }

    private fun reopenWith(d: Document, codec: TextCodec) {
        val src = d.source ?: return
        store.io.execute {
            try {
                val read = store.files.read(src, prefs.int(TeditPrefs.LARGE_FILE_MB) * 1024L * 1024L, { _, _ -> }, { false })
                val text = TextCodec.decode(read.bytes, read.length, codec)
                main.post {
                    d.buffer.setText(text)
                    d.history.clear()
                    d.codec = codec
                    d.savedState = d.history.stateId
                    d.forcedDirty = false
                    d.changed()
                    groups.forEach { it.showContent() }
                }
            } catch (e: Throwable) { main.post { overlays.message(ctx.getString(R.string.tedit_encoding_title), store.errorText(e)) } }
        }
    }

    private fun eolMenu(d: Document) {
        val opts = listOf("\n" to "LF", "\r\n" to "CRLF")
        overlays.choose(ctx.getString(R.string.tedit_eol_title), opts.map { it.second }, opts.indexOfFirst { it.first == d.eol }) { i ->
            if (d.eol != opts[i].first) { d.eol = opts[i].first; d.mixedEol = false; d.forcedDirty = true; d.changed() }
        }
    }

    fun languageMenu(d: Document) {
        val disabled = prefs.disabledExtensions()
        // An extension's language replaces Tedit's own of the same id in the list.
        val ext = Languages.external?.invoke().orEmpty()
        val langs = ext + Languages.all.filter { (it.pack !in disabled || it === Languages.PLAIN) && ext.none { e -> e.id == it.id } }
        overlays.choose(ctx.getString(R.string.tedit_language_title), langs.map { it.name }, langs.indexOf(d.language)) { i ->
            d.language = langs[i]
            d.changed()
            groups.forEach { it.editor.invalidate() }
            problems.schedule()
        }
    }

    fun gotoLineDialog() {
        val g = activeGroup ?: return
        val d = g.active?.doc ?: return
        if (d.loading || d.image || d.binary) return
        overlays.prompt(ctx.getString(R.string.tedit_goto_line), "", ctx.getString(R.string.tedit_goto_hint, d.buffer.lineCount), ctx.getString(R.string.tedit_go),
            validate = { v ->
                val parts = v.trim().split(':', ',')
                val l = parts.getOrNull(0)?.trim()?.toIntOrNull()
                if (l == null || l < 1) ctx.getString(R.string.tedit_goto_bad, d.buffer.lineCount) else null
            }) { v ->
            val parts = v.trim().split(':', ',')
            val l = (parts[0].trim().toInt() - 1).coerceIn(0, d.buffer.lineCount - 1)
            val c = (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1) - 1
            g.editor.goToLine(l, c.coerceAtLeast(0))
            focusEditor(g)
        }
    }

    // ---- title ----

    override fun decorate(title: TextView, icon: ImageView): Boolean {
        titleView = title
        // Tedit's own icon, in its own colours, as on the taskbar and in the menus.
        icon.setImageResource(com.lxlauncher.ui.theme.LxIcons.TEDIT)
        icon.imageTintList = null
        updateTitle()
        return true
    }

    private fun updateTitle() {
        val d = activeGroup?.active?.doc
        val title = if (d == null) "Tedit" else (if (d.dirty) "● " else "") + d.name + " · Tedit"
        titleView?.let { if (it.text.toString() != title) it.text = title }
        val chip = workspace?.name ?: ctx.getString(R.string.tedit_no_folder)
        workspaceChip?.let { if (it.text.toString() != chip) it.text = chip }
    }

    // ---- menus ----

    private fun cmdItem(id: String, divider: Boolean = false): TItem {
        val c = commands[id] ?: return TItem(id, enabled = false)
        val why = c.unavailable()
        return TItem(ctx.getString(c.title), c.glyph, c.shortcut, enabled = why == 0, reason = if (why != 0) ctx.getString(why) else null, divider = divider) { runCommand(id) }
    }

    fun mainMenu(anchor: View) {
        val recentFiles = store.recents("files").take(8).map { r -> TItem(r.name, TIcon.Glyph.FILE) { open(r.source) } }
        val recentFolders = store.recents("folders").take(6).map { r -> TItem(r.name, TIcon.Glyph.FOLDER) { openRecent(r, folder = true) } }
        val recent = (recentFolders + recentFiles).ifEmpty { listOf(TItem(ctx.getString(R.string.tedit_recent_empty), enabled = false)) } +
            TItem(ctx.getString(R.string.tedit_clear_recent), TIcon.Glyph.TRASH, divider = true, enabled = recentFiles.isNotEmpty() || recentFolders.isNotEmpty(),
                reason = ctx.getString(R.string.tedit_recent_empty)) { store.forget("files", null); store.forget("folders", null); overlays.snack(ctx.getString(R.string.tedit_recent_cleared)) }
        val items = listOf(
            cmdItem("file.new"), cmdItem("file.open"), cmdItem("file.openFolder"),
            TItem(ctx.getString(R.string.tedit_open_recent), TIcon.Glyph.HISTORY, children = recent),
            cmdItem("file.save", divider = true), cmdItem("file.saveAs"), cmdItem("file.saveAll"),
            cmdItem("file.close", divider = true), cmdItem("workspace.close"),
            TItem(ctx.getString(R.string.tedit_workspace_manager), TIcon.Glyph.FOLDER, children = listOf(cmdItem("file.openFolder"), cmdItem("workspace.close")) + recentFolders),
            TItem(ctx.getString(R.string.tedit_menu_edit), divider = true, children = listOf(
                cmdItem("edit.undo"), cmdItem("edit.redo"), cmdItem("edit.cut", divider = true), cmdItem("edit.copy"), cmdItem("edit.paste"),
                cmdItem("edit.find", divider = true), cmdItem("edit.replace"), cmdItem("search.files"),
                cmdItem("edit.comment", divider = true), cmdItem("edit.format"), cmdItem("edit.indent"), cmdItem("edit.outdent"))),
            TItem(ctx.getString(R.string.tedit_menu_selection), children = listOf(
                cmdItem("sel.all"), cmdItem("sel.line"), cmdItem("sel.word"), cmdItem("edit.duplicate", divider = true), cmdItem("edit.moveUp"),
                cmdItem("edit.moveDown"), cmdItem("edit.deleteLine"), cmdItem("go.bracket", divider = true))),
            TItem(ctx.getString(R.string.tedit_menu_view), children = listOf(
                cmdItem("view.palette"), cmdItem("view.explorer", divider = true), cmdItem("view.search"), cmdItem("view.scm"), cmdItem("view.run"),
                cmdItem("view.extensions"), cmdItem("view.problems"), cmdItem("view.sidebar"), cmdItem("view.terminal", divider = true),
                cmdItem("view.split"), cmdItem("view.wrap"), cmdItem("view.minimap"), cmdItem("view.zoomIn", divider = true), cmdItem("view.zoomOut"), cmdItem("view.zoomReset"))),
            TItem(ctx.getString(R.string.tedit_menu_go), children = listOf(cmdItem("go.line"), cmdItem("go.file"), cmdItem("tab.next"), cmdItem("tab.prev"), cmdItem("tab.reopen"))),
            TItem(ctx.getString(R.string.tedit_menu_run), children = listOf(cmdItem("run.task"), cmdItem("run.file"), cmdItem("run.stop"), cmdItem("run.debug"), cmdItem("view.terminal", divider = true), cmdItem("terminal.new"), cmdItem("linux.setup"))),
            TItem(ctx.getString(R.string.tedit_menu_preferences), TIcon.Glyph.SETTINGS, divider = true, children = listOf(
                cmdItem("prefs.settings"), cmdItem("prefs.theme"), cmdItem("prefs.keys"), cmdItem("view.extensions"))),
            cmdItem("help.about"),
        )
        overlays.menu(anchor, items, x = theme.dp(8f), y = anchor.height.toFloat())
    }

    private fun overflowMenu(anchor: View) {
        overlays.menu(anchor, listOf(
            cmdItem("view.palette"), cmdItem("view.terminal"), cmdItem("view.split"), cmdItem("view.wrap"),
            cmdItem("view.extensions", divider = true), cmdItem("view.problems"), cmdItem("prefs.settings", divider = true),
        ), x = anchor.width.toFloat(), y = anchor.height.toFloat())
    }

    fun runCommand(id: String) {
        val why = commands.run(id)
        if (why != 0) overlays.snack(ctx.getString(why))
    }

    fun openSettings(category: TeditPrefs.Cat? = null) = SettingsPage(this, category).show()

    /** Snippets for the document's language from installed extensions, inserted at the cursor. */
    fun snippetPicker() {
        val g = activeGroup ?: return
        val d = g.active?.doc ?: return
        val list = com.lxlauncher.tedit.ext.ExtensionStore.get(ctx).snippetsFor(d.language.id)
        if (list.isEmpty()) { overlays.snack(ctx.getString(R.string.tedit_no_snippets, d.language.name)); return }
        overlays.choose(ctx.getString(R.string.tedit_insert_snippet), list.map { sn -> "${sn.prefixes.firstOrNull() ?: sn.name} · ${sn.description ?: sn.name}" }, -1) { i ->
            g.editor.insertSnippet(list[i].body)
            focusEditor(g)
        }
    }

    /** Colour themes, Tedit's own and those of installed extensions, in one list as VS Code shows them. */
    fun themePicker() {
        val store = com.lxlauncher.tedit.ext.ExtensionStore.get(ctx)
        val builtin = listOf("dark" to R.string.tedit_v_dark, "light" to R.string.tedit_v_light, "midnight" to R.string.tedit_v_midnight, "oled" to R.string.tedit_v_oled)
        val exts = store.themes()
        val labels: List<CharSequence> = builtin.map { ctx.getString(it.second) } + exts.map { (e, th) -> "${th.label} · ${e.displayName}" }
        val active = prefs.extensionTheme()
        val current = if (active != null) builtin.size + exts.indexOfFirst { store.themeKey(it.first, it.second) == active }
            else builtin.indexOfFirst { it.first == prefs.string(TeditPrefs.THEME) }
        overlays.choose(ctx.getString(R.string.tedit_color_theme), labels, current) { i ->
            if (i < builtin.size) {
                if (prefs.extensionTheme() != null) prefs.setExtensionTheme(null)
                prefs.set(TeditPrefs.THEME, builtin[i].first)
            } else {
                val (e, th) = exts[i - builtin.size]
                prefs.setExtensionTheme(store.themeKey(e, th))
            }
        }
    }

    fun requestDocument(intent: Intent, onResult: (Uri?) -> Unit) = shell.requestDocument(intent, onResult)

    /** The layer above the editor for floating bars that do not block it (the selection bar). */
    val floatLayer: FrameLayout get() = overlayHost

    private fun about() {
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        overlays.message("Tedit", ctx.getString(R.string.tedit_about, info?.versionName ?: "", Languages.all.size - 1))
    }

    // ---- commands ----

    private fun activeEditor(): CodeEditView? = activeGroup?.editor?.takeIf { it.session != null }
    private fun needEditor(): Int = if (activeEditor() == null) R.string.tedit_why_no_editor else 0
    private fun needWritable(): Int = activeEditor()?.doc?.let { if (it.readOnly) R.string.tedit_why_read_only else 0 } ?: R.string.tedit_why_no_editor
    private fun needDoc(): Int = if (activeGroup?.active == null) R.string.tedit_why_no_file else 0
    private fun needLsp(): Int = activeEditor()?.doc?.let { if (lsp.available(it)) 0 else R.string.tedit_why_no_lsp } ?: R.string.tedit_why_no_editor

    private fun registerCommands() {
        fun c(id: String, title: Int, glyph: TIcon.Glyph?, chord: Chord?, unavailable: () -> Int = { 0 }, run: () -> Unit) =
            commands.add(Command(id, title, glyph, chord?.label, unavailable, run), *listOfNotNull(chord).toTypedArray())
        c("file.new", R.string.tedit_new_file, TIcon.Glyph.NEW_FILE, Chord(KeyEvent.KEYCODE_N, ctrl = true)) { newFile() }
        c("file.open", R.string.tedit_open_file, TIcon.Glyph.FILE, Chord(KeyEvent.KEYCODE_O, ctrl = true)) { openFileDialog() }
        c("file.openFolder", R.string.tedit_open_folder, TIcon.Glyph.FOLDER_OPEN, null) { openFolderDialog() }
        c("file.save", R.string.tedit_save, TIcon.Glyph.SAVE, Chord(KeyEvent.KEYCODE_S, ctrl = true), { needDoc() }) { activeGroup?.active?.doc?.let { save(it) } }
        c("file.saveAs", R.string.tedit_save_as, null, Chord(KeyEvent.KEYCODE_S, ctrl = true, shift = true), { needDoc() }) { activeGroup?.active?.doc?.let { saveAs(it) } }
        c("file.saveAll", R.string.tedit_save_all, null, Chord(KeyEvent.KEYCODE_S, ctrl = true, alt = true),
            { if (store.docs.values.none { it.dirty }) R.string.tedit_nothing_to_save else 0 }) { saveAll() }
        c("file.close", R.string.tedit_close_file, TIcon.Glyph.CLOSE, Chord(KeyEvent.KEYCODE_W, ctrl = true), { needDoc() }) {
            activeGroup?.let { g -> g.active?.let { closeTab(g, it, force = false) } }
        }
        c("workspace.close", R.string.tedit_close_workspace, null, null, { if (workspace == null) R.string.tedit_why_no_workspace else 0 }) { closeWorkspace() }
        c("edit.undo", R.string.tedit_undo, TIcon.Glyph.UNDO, null, { needEditor().takeIf { it != 0 } ?: if (activeEditor()?.doc?.history?.canUndo != true) R.string.tedit_why_nothing_undo else 0 }) { activeEditor()?.undo() }
        c("edit.redo", R.string.tedit_redo, TIcon.Glyph.REDO, null, { needEditor().takeIf { it != 0 } ?: if (activeEditor()?.doc?.history?.canRedo != true) R.string.tedit_why_nothing_redo else 0 }) { activeEditor()?.redo() }
        c("edit.cut", R.string.tedit_cut, TIcon.Glyph.CUT, null, { needWritable() }) { activeEditor()?.cut() }
        c("edit.copy", R.string.tedit_copy, TIcon.Glyph.COPY, null, { needEditor() }) { activeEditor()?.copy() }
        c("edit.paste", R.string.tedit_paste, TIcon.Glyph.PASTE, null, { needWritable() }) { activeEditor()?.paste() }
        c("edit.find", R.string.tedit_find, TIcon.Glyph.SEARCH, Chord(KeyEvent.KEYCODE_F, ctrl = true), { needEditor() }) { activeGroup?.findBar?.show(false) }
        c("edit.replace", R.string.tedit_replace, TIcon.Glyph.REPLACE, Chord(KeyEvent.KEYCODE_H, ctrl = true), { needWritable() }) { activeGroup?.findBar?.show(true) }
        c("edit.findNext", R.string.tedit_find_next, null, Chord(KeyEvent.KEYCODE_F3), { needEditor() }) { activeGroup?.findBar?.let { if (it.visible) it.step(true) else it.show(false) } }
        c("edit.findPrev", R.string.tedit_find_prev, null, Chord(KeyEvent.KEYCODE_F3, shift = true), { needEditor() }) { activeGroup?.findBar?.let { if (it.visible) it.step(false) else it.show(false) } }
        c("search.files", R.string.tedit_find_in_files, TIcon.Glyph.SEARCH, Chord(KeyEvent.KEYCODE_F, ctrl = true, shift = true)) { showPanel("search"); (panel("search") as SearchPanel).focusQuery(activeEditor()?.selectedText) }
        c("edit.comment", R.string.tedit_toggle_comment, null, null, { needWritable() }) { activeEditor()?.toggleComment() }
        c("edit.format", R.string.tedit_format, null, Chord(KeyEvent.KEYCODE_F, shift = true, alt = true), { needWritable() }) { formatDocument() }
        c("edit.suggest", R.string.tedit_suggest, null, Chord(KeyEvent.KEYCODE_SPACE, ctrl = true), { needWritable() }) { activeEditor()?.let { completion.trigger(it) } }
        c("go.definition", R.string.tedit_go_definition, TIcon.Glyph.GOTO, Chord(KeyEvent.KEYCODE_F12), { needLsp() }) { goToDefinition() }
        c("edit.hover", R.string.tedit_show_hover, TIcon.Glyph.INFO, Chord(KeyEvent.KEYCODE_I, ctrl = true, shift = true), { needLsp() }) { showHover() }
        c("edit.indent", R.string.tedit_indent, null, null, { needWritable() }) { activeEditor()?.indentLines(false) }
        c("edit.outdent", R.string.tedit_outdent, null, null, { needWritable() }) { activeEditor()?.indentLines(true) }
        c("edit.duplicate", R.string.tedit_duplicate_line, null, null, { needWritable() }) { activeEditor()?.duplicateLines() }
        c("edit.moveUp", R.string.tedit_move_line_up, null, null, { needWritable() }) { activeEditor()?.moveLines(true) }
        c("edit.moveDown", R.string.tedit_move_line_down, null, null, { needWritable() }) { activeEditor()?.moveLines(false) }
        c("edit.deleteLine", R.string.tedit_delete_line, null, null, { needWritable() }) { activeEditor()?.deleteLines() }
        c("sel.all", R.string.tedit_select_all, TIcon.Glyph.SELECT_ALL, null, { needEditor() }) { activeEditor()?.selectAll() }
        c("sel.line", R.string.tedit_select_line, null, null, { needEditor() }) { activeEditor()?.selectLine() }
        c("sel.word", R.string.tedit_select_word, null, null, { needEditor() }) { activeEditor()?.let { e -> e.session?.let { s -> val (a, b) = e.wordRangeAt(s.caret); e.setSelection(a, b) } } }
        c("go.bracket", R.string.tedit_go_bracket, null, null, { needEditor() }) { activeEditor()?.jumpToBracket() }
        c("go.line", R.string.tedit_goto_line, TIcon.Glyph.GOTO, Chord(KeyEvent.KEYCODE_G, ctrl = true), { needEditor() }) { gotoLineDialog() }
        c("go.file", R.string.tedit_quick_open, TIcon.Glyph.FILE, Chord(KeyEvent.KEYCODE_P, ctrl = true)) { CommandPalette(this, "").show() }
        c("view.palette", R.string.tedit_command_palette, TIcon.Glyph.COMMAND, Chord(KeyEvent.KEYCODE_P, ctrl = true, shift = true)) { CommandPalette(this, ">").show() }
        commands.add(commands["view.palette"]!!, Chord(KeyEvent.KEYCODE_F1))
        c("view.explorer", R.string.tedit_panel_explorer, TIcon.Glyph.FILES, Chord(KeyEvent.KEYCODE_E, ctrl = true, shift = true)) { showPanel("explorer") }
        c("view.search", R.string.tedit_panel_search, TIcon.Glyph.SEARCH, null) { showPanel("search") }
        c("view.scm", R.string.tedit_panel_scm, TIcon.Glyph.BRANCH, Chord(KeyEvent.KEYCODE_G, ctrl = true, shift = true)) { showPanel("scm") }
        c("view.run", R.string.tedit_panel_run, TIcon.Glyph.RUN, Chord(KeyEvent.KEYCODE_D, ctrl = true, shift = true)) { showPanel("run") }
        c("view.extensions", R.string.tedit_panel_extensions, TIcon.Glyph.EXTENSIONS, Chord(KeyEvent.KEYCODE_X, ctrl = true, shift = true)) { showPanel("extensions") }
        c("view.problems", R.string.tedit_panel_problems, TIcon.Glyph.PROBLEMS, Chord(KeyEvent.KEYCODE_M, ctrl = true, shift = true)) { showPanel("problems") }
        c("view.sidebar", R.string.tedit_toggle_sidebar, null, Chord(KeyEvent.KEYCODE_B, ctrl = true)) { if (panelOpen) closePanel() else showPanel(panelId) }
        c("view.terminal", R.string.tedit_toggle_terminal, TIcon.Glyph.TERMINAL, Chord(KeyEvent.KEYCODE_GRAVE, ctrl = true)) { toggleTerminal() }
        c("terminal.new", R.string.tedit_new_terminal, TIcon.Glyph.TERMINAL, Chord(KeyEvent.KEYCODE_GRAVE, ctrl = true, shift = true)) { showTerminal(); terminal.newSession(focus = true) }
        c("linux.setup", R.string.tedit_linux_title, TIcon.Glyph.TERMINAL, null) { LinuxPage(this).show() }
        c("edit.snippet", R.string.tedit_insert_snippet, null, null, { needWritable() }) { snippetPicker() }
        c("view.split", R.string.tedit_split_editor, TIcon.Glyph.SPLIT_RIGHT, Chord(KeyEvent.KEYCODE_BACKSLASH, ctrl = true), { needDoc() }) { split() }
        c("view.wrap", R.string.tedit_toggle_wrap, TIcon.Glyph.WRAP, Chord(KeyEvent.KEYCODE_Z, alt = true)) { prefs.set(TeditPrefs.WORD_WRAP, !prefs.bool(TeditPrefs.WORD_WRAP)) }
        c("view.minimap", R.string.tedit_toggle_minimap, null, null) { prefs.set(TeditPrefs.MINIMAP, !prefs.bool(TeditPrefs.MINIMAP)) }
        c("view.zoomIn", R.string.tedit_zoom_in, null, null) { prefs.set(TeditPrefs.FONT_SIZE, (prefs.float(TeditPrefs.FONT_SIZE) + 1).coerceAtMost(28f)) }
        c("view.zoomOut", R.string.tedit_zoom_out, null, null) { prefs.set(TeditPrefs.FONT_SIZE, (prefs.float(TeditPrefs.FONT_SIZE) - 1).coerceAtLeast(9f)) }
        c("view.zoomReset", R.string.tedit_zoom_reset, null, null) { prefs.reset(listOf(TeditPrefs.FONT_SIZE)) }
        c("tab.next", R.string.tedit_next_tab, null, Chord(KeyEvent.KEYCODE_TAB, ctrl = true), { needDoc() }) { nextTab(1) }
        commands.add(commands["tab.next"]!!, Chord(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true))
        c("tab.prev", R.string.tedit_prev_tab, null, Chord(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true), { needDoc() }) { nextTab(-1) }
        commands.add(commands["tab.prev"]!!, Chord(KeyEvent.KEYCODE_PAGE_UP, ctrl = true))
        c("tab.reopen", R.string.tedit_reopen_closed, null, Chord(KeyEvent.KEYCODE_T, ctrl = true, shift = true), { if (closedTabs.isEmpty()) R.string.tedit_why_no_closed else 0 }) { reopenClosed() }
        c("tab.closeAll", R.string.tedit_close_all, null, null, { needDoc() }) { activeGroup?.let { g -> closeTabs(g, g.tabs.toList()) } }
        c("run.task", R.string.tedit_run_task, TIcon.Glyph.RUN, Chord(KeyEvent.KEYCODE_F5)) { showPanel("run"); (panel("run") as RunPanel).runDefault() }
        c("run.file", R.string.tedit_run_file, TIcon.Glyph.RUN, null, { runner.whyNotRunFile(activeGroup?.active?.doc) }) { activeGroup?.active?.doc?.let { runner.runFile(it) } }
        c("run.stop", R.string.tedit_stop_task, TIcon.Glyph.STOP, Chord(KeyEvent.KEYCODE_F5, shift = true), { if (runner.running == null) R.string.tedit_why_no_task else 0 }) { runner.stop() }
        c("run.debug", R.string.tedit_start_debug, TIcon.Glyph.BUG, null, { R.string.tedit_debug_unsupported }) {}
        c("prefs.settings", R.string.tedit_settings, TIcon.Glyph.SETTINGS, Chord(KeyEvent.KEYCODE_COMMA, ctrl = true)) { openSettings() }
        c("prefs.theme", R.string.tedit_color_theme, null, null) { themePicker() }
        c("prefs.keys", R.string.tedit_shortcuts, null, null) { shortcutsSheet() }
        c("editor.tabFocus", R.string.tedit_tab_moves_focus, null, Chord(KeyEvent.KEYCODE_M, ctrl = true)) {
            groups.forEach { it.editor.tabMovesFocus = !it.editor.tabMovesFocus }
            overlays.snack(ctx.getString(if (groups[0].editor.tabMovesFocus) R.string.tedit_tab_focus_on else R.string.tedit_tab_focus_off))
        }
        c("help.about", R.string.tedit_about_title, TIcon.Glyph.INFO, null) { about() }
    }

    private fun shortcutsSheet() {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val editorKeys = listOf(
            "Ctrl+Z / Ctrl+Y" to R.string.tedit_undo, "Ctrl+X / C / V" to R.string.tedit_clipboard, "Ctrl+A" to R.string.tedit_select_all,
            "Ctrl+/" to R.string.tedit_toggle_comment, "Ctrl+] / Ctrl+[" to R.string.tedit_indent, "Alt+↑ / Alt+↓" to R.string.tedit_move_line_up,
            "Shift+Alt+↓" to R.string.tedit_duplicate_line, "Ctrl+Shift+K" to R.string.tedit_delete_line, "Ctrl+L" to R.string.tedit_select_line,
            "Ctrl+D" to R.string.tedit_select_word, "Ctrl+Shift+\\" to R.string.tedit_go_bracket, "Ctrl+Enter" to R.string.tedit_insert_line_below,
        )
        for ((k, label) in editorKeys) col.addView(shortcutRow(k, ctx.getString(label)))
        for (c in commands.all) if (c.shortcut != null) col.addView(shortcutRow(c.shortcut, ctx.getString(c.title)))
        val scroll = ui.scroll(col).apply { layoutParams = ui.lp(MATCH, (rootFrame.height * 0.6f).toInt()) }
        var close: () -> Unit = {}
        close = overlays.dialog(ctx.getString(R.string.tedit_shortcuts), scroll, listOf(ui.button(ctx.getString(R.string.tedit_ok), primary = true) { close() } to true))
    }

    private fun shortcutRow(keys: String, label: String): View {
        val r = ui.row().apply { minimumHeight = theme.dpi(32f) }
        r.addView(ui.text(label, theme.textSmall, theme.text, lines = 2), ui.lp(0, TeditTool.WRAP, 1f))
        r.addView(ui.text(keys, theme.textCaption, theme.text2).apply { typeface = theme.codeFont })
        return r
    }

    private fun goToDefinition() {
        val e = activeEditor() ?: return
        val d = e.doc ?: return
        val at = e.session?.caret ?: return
        lsp.definition(d, at) { locs ->
            val loc = locs.firstOrNull() ?: run { overlays.snack(ctx.getString(R.string.tedit_no_definition)); return@definition }
            val path = com.lxlauncher.tedit.lsp.uriToPath(loc.uri) ?: return@definition
            open(DocSource.Node("local", path), line = loc.range.start.line, col = loc.range.start.character)
        }
    }

    private fun showHover() {
        val e = activeEditor() ?: return
        val d = e.doc ?: return
        val at = e.session?.caret ?: return
        val word = e.wordBeforeCaret()?.second.orEmpty()
        lsp.hover(d, at) { text ->
            if (text.isNullOrBlank()) overlays.snack(ctx.getString(R.string.tedit_no_hover))
            else overlays.message(word.ifEmpty { ctx.getString(R.string.tedit_show_hover) }, text.replace(Regex("```[a-zA-Z0-9]*\\n?"), "").trim())
        }
    }

    private fun formatDocument() {
        val e = activeEditor() ?: return
        val d = e.doc ?: return
        // The language server formats when it can; Tedit's own formatter covers JSON otherwise.
        if (lsp.available(d)) {
            lsp.formatting(d, editorConfig.tabSize, editorConfig.insertSpaces) { edits ->
                if (edits == null) { formatBuiltIn(e, d); return@formatting }
                val sb = StringBuilder(d.buffer.text())
                for (x in edits.sortedWith(compareByDescending<com.lxlauncher.tedit.lsp.TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })) {
                    val a = d.buffer.offsetOf(d.buffer.clamp(Pos(x.range.start.line, x.range.start.character)))
                    val b = d.buffer.offsetOf(d.buffer.clamp(Pos(x.range.end.line, x.range.end.character)))
                    sb.replace(a, b, x.newText)
                }
                val text = sb.toString()
                if (text != d.buffer.text()) {
                    val caret = e.session?.caret ?: Pos.ZERO
                    e.replace(Pos.ZERO, d.buffer.lastPos, text)
                    e.setSelection(d.buffer.clamp(caret), d.buffer.clamp(caret))
                }
                overlays.snack(ctx.getString(R.string.tedit_formatted))
            }
            return
        }
        formatBuiltIn(e, d)
    }

    private fun formatBuiltIn(e: CodeEditView, d: Document) {
        val formatted = Formatter.format(ctx, d, editorConfig.tabSize, editorConfig.insertSpaces, prefs.disabledExtensions())
        when {
            formatted == null -> overlays.snack(ctx.getString(R.string.tedit_format_unsupported, d.language.name))
            formatted.isFailure -> overlays.message(ctx.getString(R.string.tedit_format), ctx.getString(R.string.tedit_format_failed, formatted.exceptionOrNull()?.message ?: ""))
            else -> {
                val text = formatted.getOrThrow()
                if (text != d.buffer.text()) e.replace(Pos.ZERO, d.buffer.lastPos, text)
                overlays.snack(ctx.getString(R.string.tedit_formatted))
            }
        }
    }

    // ---- shortcuts and keys ----

    /** Tool-wide shortcuts; LX asks before its own, so Ctrl+W closes a tab here instead of the window. */
    override fun onShortcut(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN || !overlays.isEmpty) return false
        val c = commands.forKey(e) ?: return false
        // Inside a text field of a panel, the field keeps its own editing keys.
        val why = c.unavailable()
        if (why != 0) { overlays.snack(ctx.getString(why)); return true }
        c.run()
        return true
    }

    inner class Host : EditorHost {
        override fun editorFocused(v: CodeEditView) {
            groups.firstOrNull { it.editor === v }?.let { activateGroup(it) }
            updateKeyBar()
        }

        override fun caretMoved(v: CodeEditView) {
            if (groups.firstOrNull { it.editor === v } === activeGroup) refreshStatusSoon()
            completion.caretMoved(v)
        }

        override fun textEdited(v: CodeEditView) {
            val d = v.doc ?: return
            refreshStatusSoon()
            lsp.changed(d)
            completion.edited(v)
            if (!d.dirty) { store.backupSoon(d); return }
            main.removeCallbacks(afterEdit)
            main.postDelayed(afterEdit, 1200)
            groups.forEach { g -> if (g.editor !== v && g.active?.doc === d) g.editor.invalidate() }
            if (prefs.string(TeditPrefs.AUTO_SAVE) == "delay" && d.source != null) {
                main.removeCallbacks(autoSave)
                main.postDelayed(autoSave, prefs.int(TeditPrefs.AUTO_SAVE_DELAY).toLong())
            }
        }

        override fun showSelectionBar(v: CodeEditView, x: Float, top: Float, bottom: Float) = SelectionBar.show(this@TeditTool, v, x, top, bottom)
        override fun hideSelectionBar(v: CodeEditView) { SelectionBar.hide(this@TeditTool) }
        override fun showEditorMenu(v: CodeEditView, x: Float, y: Float) = editorMenu(v, x, y)
        override fun shortcut(v: CodeEditView, e: KeyEvent): Boolean = onShortcut(e)
        override fun readOnlyAttempt(v: CodeEditView) {
            val d = v.doc ?: return
            overlays.snack(when {
                d.loading -> ctx.getString(R.string.tedit_still_loading)
                d.readOnlyReason != 0 -> ctx.getString(d.readOnlyReason)
                else -> ctx.getString(R.string.tedit_ro_generic)
            })
        }

        override fun zoomed(v: CodeEditView, fontSize: Float, done: Boolean) {
            if (done) prefs.set(TeditPrefs.FONT_SIZE, fontSize)
            else { editorConfig = EditorConfig(prefs).also { it.fontSize = fontSize }; groups.forEach { it.editor.config = editorConfig } }
        }

        override fun message(text: CharSequence) = overlays.snack(text)
    }

    private val afterEdit = Runnable {
        store.docs.values.filter { it.dirty }.forEach { store.backupSoon(it) }
        problems.schedule()
        git.lineMarksForOpen()
    }

    private val autoSave = Runnable { saveAllQuiet() }

    private var statusQueued = false
    private fun refreshStatusSoon() {
        if (statusQueued) return
        statusQueued = true
        main.postDelayed({ statusQueued = false; refreshStatus(); updateTitle(); groups.forEach { if (it.isBuilt()) it.refreshTabsLabels() } }, 60)
    }

    fun editorMenu(v: CodeEditView, x: Float, y: Float) {
        val items = listOf(
            cmdItem("edit.cut"), cmdItem("edit.copy"), cmdItem("edit.paste"), cmdItem("sel.all"),
            cmdItem("edit.suggest", divider = true), cmdItem("go.definition"), cmdItem("edit.hover"), cmdItem("edit.snippet"),
            cmdItem("edit.comment", divider = true), cmdItem("edit.format"), cmdItem("edit.duplicate"), cmdItem("edit.deleteLine"),
            cmdItem("edit.find", divider = true), cmdItem("edit.replace"), cmdItem("go.line"), cmdItem("view.palette"),
        )
        overlays.menu(v, items, x, y)
    }

    // ---- preferences ----

    private fun onPref(key: String) {
        when {
            // A new language: every view is built again, as for a new theme, so all of Tedit reads in it at once.
            key.startsWith("ui.") || key == TeditPrefs.LANGUAGE -> {
                if (key == TeditPrefs.LANGUAGE) localized.setLanguage(prefs.string(TeditPrefs.LANGUAGE))
                theme = TeditTheme(ctx, prefs, shell.ui.font, shell.ui.fontMedium)
                ui.t = theme
                editorConfig = EditorConfig(prefs)
                for (g in groups) { g.editor.setTheme(theme); g.editor.config = editorConfig }
                panels.values.forEach { it.dispose() }
                panels.clear()
                terminal.rebuild()
                build()
                SettingsPage.refreshOpen(this)
            }
            key.startsWith("editor.") || key == TeditPrefs.HIGHLIGHT_MAX -> {
                editorConfig = EditorConfig(prefs)
                for (g in groups) { g.editor.config = editorConfig; g.editor.session?.doc?.highlighter?.maxLine = editorConfig.highlightMax }
                refreshStatus()
                updateKeyBar()
            }
            key == TeditPrefs.EXT_DISABLED -> {
                val disabled = prefs.disabledExtensions()
                for (d in store.docs.values) if (d.language.pack in disabled) d.language = Languages.PLAIN
                else if (d.language === Languages.PLAIN && d.source != null) d.language = Languages.detect(d.name, d.buffer.line(0), disabled)
                groups.forEach { it.editor.invalidate() }
                refreshStatus()
                problems.schedule()
            }
            key.startsWith("terminal.") -> terminal.rebuild()
            key.startsWith("scm.") -> { git.rootChanged(); groups.forEach { lineMarksFor(it) } }
            key == TeditPrefs.SHOW_HIDDEN || key == TeditPrefs.SORT || key == TeditPrefs.FOLDERS_FIRST || key == TeditPrefs.EXCLUDE -> (panels["explorer"] as? ExplorerPanel)?.refresh()
        }
    }

    // ---- session ----

    private var sessionQueued = false
    fun sessionChanged() {
        if (sessionQueued || destroyed) return
        sessionQueued = true
        main.postDelayed({ sessionQueued = false; saveSession() }, 400)
    }

    private fun saveSession() {
        if (!prefs.bool(TeditPrefs.RESTORE)) { prefs.putRaw("session", null); return }
        val o = JSONObject()
        workspace?.let { o.put("root", it.source.toJson()) }
        val ga = JSONArray()
        for (g in groups) {
            val tabs = JSONArray()
            for (tab in g.tabs) {
                val s = tab.session
                val to = JSONObject().put("pinned", tab.pinned)
                    .put("cl", s.caret.line).put("cc", s.caret.col).put("al", s.anchor.line).put("ac", s.anchor.col)
                    .put("sx", s.scrollX.toDouble()).put("sy", s.scrollY.toDouble())
                    .put("lang", tab.doc.language.id)
                if (tab.doc.source != null) to.put("src", tab.doc.source!!.toJson()) else to.put("untitled", tab.doc.id)
                tabs.put(to)
            }
            ga.put(JSONObject().put("tabs", tabs).put("active", g.tabs.indexOf(g.active)))
        }
        o.put("groups", ga)
        o.put("activeGroup", groups.indexOf(activeGroup))
        o.put("split", if (::editorArea.isInitialized) editorArea.ratio.toDouble() else 0.5)
        o.put("panel", panelId).put("panelOpen", panelOpen).put("terminal", terminalOpen)
        prefs.putRaw("session", o.toString())
    }

    private fun restoreSession() {
        // A first start opens the Files panel, as VS Code does.
        if (prefs.raw("session") == null) pendingPanel = true
        if (!prefs.bool(TeditPrefs.RESTORE)) return
        val o = runCatching { JSONObject(prefs.raw("session") ?: return) }.getOrNull() ?: return
        panelId = o.optString("panel", "explorer").takeIf { it in RAIL_ORDER } ?: "explorer"
        panelOpen = false
        terminalOpen = false
        splitRatio = o.optDouble("split", 0.5).toFloat()
        DocSource.fromJson(o.optJSONObject("root"))?.let { src ->
            val n = src as? DocSource.Node
            if (n != null) store.io.execute {
                val node = runCatching { store.files.node(n) }.getOrNull()
                main.post { if (node != null && node.isDir && workspace == null && !destroyed) setWorkspace(node) }
            }
        }
        val ga = o.optJSONArray("groups") ?: return
        for (gi in 0 until minOf(2, ga.length())) {
            val go = ga.optJSONObject(gi) ?: continue
            val g = if (gi == 0) groups[0] else EditorGroup(this).also { groups += it }
            val tabs = go.optJSONArray("tabs") ?: continue
            for (ti in 0 until tabs.length()) {
                val to = tabs.optJSONObject(ti) ?: continue
                val src = DocSource.fromJson(to.optJSONObject("src"))
                val d = if (src != null) store.open(src) { d ->
                    groups.forEach { gg -> gg.tabs.filter { it.doc === d }.forEach { tab -> restoreView(gg, tab) } }
                    Languages.byId(to.optString("lang"))?.let { l -> if (d.language === Languages.PLAIN && l !== Languages.PLAIN) d.language = l }
                } else {
                    val id = to.optLong("untitled", -1)
                    if (id < 0 || store.backups.load("untitled:$id") == null) continue
                    store.untitled(id)
                }
                val tab = g.add(d, activate = false)
                tab.pinned = to.optBoolean("pinned")
                tab.session.caret = Pos(to.optInt("cl"), to.optInt("cc"))
                tab.session.anchor = Pos(to.optInt("al"), to.optInt("ac"))
                tab.session.scrollX = to.optDouble("sx", 0.0).toFloat()
                tab.session.scrollY = to.optDouble("sy", 0.0).toFloat()
                if (!d.loading) restoreView(g, tab)
            }
            g.select(g.tabs.getOrNull(go.optInt("active", 0)) ?: g.tabs.firstOrNull())
        }
        if (groups.size > 1 && groups[1].tabs.isEmpty()) groups.removeAt(1)
        activeGroup = groups.getOrNull(o.optInt("activeGroup", 0)) ?: groups[0]
        if (o.optBoolean("terminal")) terminalOpen = true
        if (o.optBoolean("panelOpen") && rootFrame.width == 0) pendingPanel = true
    }

    private var pendingPanel = false
    var splitRatio = 0.5f

    private fun restoreView(g: EditorGroup, tab: Tab) {
        val buf = tab.doc.buffer
        tab.session.caret = buf.clamp(tab.session.caret)
        tab.session.anchor = buf.clamp(tab.session.anchor)
    }

    // ---- extras from the File Explorer ----

    /** "open\u0000backend\u0000ref" opens a file; "folder\u0000backend\u0000ref" opens a folder as the workspace. */
    fun handleExtra(extra: String?) {
        val parts = extra?.split('\u0000') ?: return
        if (parts.size < 3) return
        val src = DocSource.Node(parts[1], parts[2])
        when (parts[0]) {
            "open" -> open(src)
            "folder" -> store.io.execute {
                val node = runCatching { store.files.node(src) }.getOrNull()
                main.post { if (node != null && node.isDir) openFolderNode(node) else overlays.snack(ctx.getString(R.string.tedit_err_not_found)) }
            }
        }
    }

    // ---- ToolView ----

    override fun onShown() {
        if (pendingExtra != null) { val e = pendingExtra; pendingExtra = null; rootFrame.post { handleExtra(e) } }
        if (pendingPanel) { pendingPanel = false; rootFrame.post { if (!panelOpen) showPanel(panelId) } }
        checkDiskChanges()
        git.refreshSoon()
    }

    override fun onHidden() {
        if (prefs.string(TeditPrefs.AUTO_SAVE) == "focus") saveAllQuiet()
        store.docs.values.filter { it.dirty }.forEach { store.backupSoon(it) }
        saveSession()
    }

    override fun onBack(): Boolean {
        if (overlays.closeTop()) return true
        if (SelectionBar.hide(this)) return true
        if (overlayDock() && panelOpen) { closePanel(); return true }
        val g = activeGroup
        if (g != null && g.findBar.visible) { g.findBar.hide(); return true }
        if (terminalOpen) { toggleTerminal(); return true }
        return false
    }

    override fun onDestroy() {
        if (destroyed) return
        store.docs.values.filter { it.dirty }.forEach { store.backupSoon(it) }
        saveSession()
        destroyed = true
        prefs.removeListener(prefListener)
        main.removeCallbacksAndMessages(null)
        runCatching { completion.hide() }
        runCatching { lsp.dispose() }
        runCatching { terminal.dispose() }
        runCatching { runner.stop() }
        panels.values.forEach { runCatching { it.dispose() } }
        groups.forEach { it.dispose() }
        git.dispose()
        store.shutdown()
        Log.d(TAG, "Tedit window closed")
    }

    override fun onLiveResize(active: Boolean) {
        if (liveResize == active) return
        liveResize = active
        // The drag is over: the editor takes on the size it ended at, once, in the layout that gives it that size.
        // Its size may not change in that layout (the last one during the drag had it already), so it is asked for.
        if (!active && !destroyed) {
            resizeOwed = true
            rootFrame.requestLayout()
        }
    }

    /** After coming back to the window: files changed on disk reload when unchanged here, and are flagged when not. */
    private fun checkDiskChanges() {
        val docs = store.docs.values.filter { it.source != null && !it.loading && it.disk != null && it.loadError == null }
        if (docs.isEmpty()) return
        store.io.execute {
            val changed = docs.mapNotNull { d ->
                val now = runCatching { store.files.stat(d.source!!) }.getOrNull() ?: return@mapNotNull null
                val was = d.disk ?: return@mapNotNull null
                if (now.modified != was.modified || now.size != was.size) d else null
            }
            if (changed.isEmpty()) return@execute
            main.post {
                for (d in changed) {
                    if (!d.dirty) { reload(d); overlays.snack(ctx.getString(R.string.tedit_reloaded, d.name)) }
                    else groups.forEach { g -> if (g.tabs.any { it.doc === d }) { g.changedOnDisk += d.key; g.updateBanner() } }
                }
            }
        }
    }

    // Runs after every property above has its initial value: restoring the session sets several of them.
    init {
        overlays = Overlays(overlayHost, ui)
        overlays.onCover = { completion.hide() }
        // Languages that installed extensions bring, ahead of Tedit's own for the same files.
        val extStore = com.lxlauncher.tedit.ext.ExtensionStore.get(ctx)
        Languages.external = { com.lxlauncher.tedit.ext.ExtLanguages.all(extStore) }
        rootFrame.setBackgroundColor(theme.bg)
        rootFrame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        rootFrame.addView(overlayHost, FrameLayout.LayoutParams(MATCH, MATCH))
        overlayHost.isClickable = false
        registerCommands()
        prefs.addListener(prefListener)
        groups += EditorGroup(this)
        activeGroup = groups[0]
        restoreSession()
        build()
        Log.d(TAG, "Tedit window built")
    }

    companion object {
        const val KIND = "tedit"
        val RAIL_ORDER = listOf("explorer", "search", "scm", "run", "extensions", "problems")
        /**
         * Each panel's icon and name, for the rail and the small window's switcher. They used to come from the panels,
         * which built all six on every rebuild: Extensions then searched Open VSX each time, and on a slow network those
         * searches queued up holding their dropped panels (8.9 MB after 40 language switches, 14 November 2026).
         */
        val PANEL_FACES = mapOf(
            "explorer" to (TIcon.Glyph.FILES to R.string.tedit_panel_explorer),
            "search" to (TIcon.Glyph.SEARCH to R.string.tedit_panel_search),
            "scm" to (TIcon.Glyph.BRANCH to R.string.tedit_panel_scm),
            "run" to (TIcon.Glyph.RUN to R.string.tedit_panel_run),
            "extensions" to (TIcon.Glyph.EXTENSIONS to R.string.tedit_panel_extensions),
            "problems" to (TIcon.Glyph.PROBLEMS to R.string.tedit_panel_problems),
        )
        const val TAG = "LxTedit"
        /** The terminal's shortest: its bar and two lines. */
        private const val TERM_MIN_DP = 72f
        /** The least an editor is worth showing in: its tabs and a few lines of code. */
        private const val EDITOR_MIN_DP = 96f
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun openExtra(node: FsNode): String = "open\u0000${node.backend.key}\u0000${node.ref}"
        fun folderExtra(node: FsNode): String = "folder\u0000${node.backend.key}\u0000${node.ref}"
    }
}

/** Tedit's mark: the owner's Tedit icon (t-icon.png, 13 November 2026), the same picture LX shows for Tedit everywhere. */
@SuppressLint("ViewConstructor")
class LogoMark(context: Context, @Suppress("UNUSED_PARAMETER") t: TeditTheme) : android.widget.ImageView(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ScaleType.FIT_CENTER
        setImageResource(com.lxlauncher.ui.theme.LxIcons.TEDIT)
    }
}

/** The two editor groups side by side (landscape) or one above the other (portrait), with a draggable divider. */
@SuppressLint("ViewConstructor")
class SplitLayout(context: Context, private val tool: TeditTool) : ViewGroup(context) {
    var vertical = true
        set(v) { field = v; requestLayout() }
    var ratio: Float
        get() = tool.splitRatio
        set(v) { tool.splitRatio = v.coerceIn(0.2f, 0.8f); requestLayout() }
    private val bar get() = tool.theme.dpi(10f)
    private val paint = Paint()
    private var dragging = false

    init { setWillNotDraw(false) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (childCount == 1) {
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            return
        }
        if (childCount < 2) return
        val total = (if (vertical) h else w) - bar
        val a = (total * ratio).toInt()
        val b = total - a
        if (vertical) {
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(a, MeasureSpec.EXACTLY))
            getChildAt(1).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY))
        } else {
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(a, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            getChildAt(1).measure(MeasureSpec.makeMeasureSpec(b, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 1) { getChildAt(0).layout(0, 0, r - l, b - t); return }
        if (childCount < 2) return
        val c0 = getChildAt(0)
        val c1 = getChildAt(1)
        if (vertical) {
            c0.layout(0, 0, r - l, c0.measuredHeight)
            c1.layout(0, c0.measuredHeight + bar, r - l, b - t)
        } else {
            c0.layout(0, 0, c0.measuredWidth, b - t)
            c1.layout(c0.measuredWidth + bar, 0, r - l, b - t)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (childCount < 2) return
        val th = tool.theme
        paint.color = th.panel
        val c0 = getChildAt(0)
        if (vertical) canvas.drawRect(0f, c0.bottom.toFloat(), width.toFloat(), (c0.bottom + bar).toFloat(), paint)
        else canvas.drawRect(c0.right.toFloat(), 0f, (c0.right + bar).toFloat(), height.toFloat(), paint)
        paint.color = if (dragging) th.accent else th.border
        val g = th.dp(16f)
        if (vertical) { val y = c0.bottom + bar / 2f; canvas.drawRoundRect(width / 2f - g, y - th.dp(1.5f), width / 2f + g, y + th.dp(1.5f), th.dp(2f), th.dp(2f), paint) }
        else { val x = c0.right + bar / 2f; canvas.drawRoundRect(x - th.dp(1.5f), height / 2f - g, x + th.dp(1.5f), height / 2f + g, th.dp(2f), th.dp(2f), paint) }
    }

    private fun onBar(x: Float, y: Float): Boolean {
        if (childCount < 2) return false
        val c0 = getChildAt(0)
        val slop = tool.theme.dp(8f)
        return if (vertical) y >= c0.bottom - slop && y <= c0.bottom + bar + slop else x >= c0.right - slop && x <= c0.right + bar + slop
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && onBar(ev.x, ev.y)) { dragging = true; invalidate(); return true }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return ev.actionMasked == MotionEvent.ACTION_DOWN && onBar(ev.x, ev.y).also { if (it) dragging = true }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> ratio = if (vertical) ev.y / height else ev.x / width
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate(); tool.sessionChanged() }
        }
        return true
    }
}

/** A thin strip that resizes the side panel (dragged sideways) or the terminal (dragged up and down). */
@SuppressLint("ViewConstructor")
class ResizeHandle(context: Context, private val tool: TeditTool, private val horizontal: Boolean, private val onDrag: (Float) -> Unit) : View(context) {
    private var last = 0f
    private var active = false
    private val paint = Paint()

    init {
        contentDescription = context.getString(if (horizontal) R.string.tedit_resize_panel else R.string.tedit_resize_terminal)
        isFocusable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = if (horizontal) e.rawX else e.rawY
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { last = p; active = true; parent?.requestDisallowInterceptTouchEvent(true); invalidate() }
            MotionEvent.ACTION_MOVE -> { val d = p - last; if (abs(d) >= 1f) { onDrag(d); last = p } }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = false; invalidate() }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = tool.theme.dp(16f)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (horizontal) { onDrag(-step); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (horizontal) { onDrag(step); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!horizontal) { onDrag(-step); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!horizontal) { onDrag(step); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        val t = tool.theme
        paint.color = if (active || isFocused) t.accent else 0
        if (horizontal) canvas.drawRect(width / 2f - t.dp(1f), 0f, width / 2f + t.dp(1f), height.toFloat(), paint)
        else canvas.drawRect(0f, height / 2f - t.dp(1f), width.toFloat(), height / 2f + t.dp(1f), paint)
    }
}
