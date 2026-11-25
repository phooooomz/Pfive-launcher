// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.ui.BitmapSample
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.StableHorizontalScrollView
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.Contrast
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.windows.ToolView
import java.util.concurrent.Executors

/**
 * LX's File Manager: storage volumes, folders the user granted, recent files and archives, in a
 * flat header bar with a path field and one menu button whose menus cascade. The layout follows the window
 * width: a wide window docks the sidebar as a card; a narrower one opens it as a drawer, and a narrow one
 * gives the path field a line of its own.
 *
 * Long work (copy, move, delete, archives) runs in [FileJobs], which outlives this window.
 */
class FileManagerTool(private val shell: Shell, initialQuery: String?) : ToolView {
    private enum class Mode { NARROW, MEDIUM, WIDE }

    /** A pending "choose a destination" request: copy to, move to, extract to, save archive in. */
    private class Pick(val label: CharSequence, val onPick: (FsNode) -> Unit)

    private val ctx: Context = shell.activity
    private val theme: LxTheme get() = shell.ui
    private val storage = FileServices.storage(ctx)
    private val jobs = FileServices.jobs(ctx)
    private val prefs = ctx.getSharedPreferences("lx_files", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-fm-list").apply { isDaemon = true } }
    /** Sorts listings off the main thread: a folder of 10,000 files takes about 140,000 name comparisons. */
    private val sorter = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-fm-sort").apply { isDaemon = true } }
    /** Counts a folder's size for Properties apart from listing, so navigating never waits behind it. */
    private val walker = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-fm-walk").apply { isDaemon = true } }
    /** Bumped for each filter request; a sort that finishes after a newer request is dropped. */
    private val filterSeq = java.util.concurrent.atomic.AtomicInteger()
    /** A big listing is being sorted; until it lands, an empty window says Loading rather than Empty. */
    private var sorting = false
    /** The folder-size walk of the open Properties sheet. */
    private var walkCancel: Cancel? = null
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false

    private var place: Place? = null
    private val history = ArrayList<Place>()
    private val future = ArrayList<Place>()
    private var raw: List<FsNode> = emptyList()
    private var entries: List<FsNode> = emptyList()
    private val selection = LinkedHashMap<String, FsNode>()
    private var listMode = prefs.getString("view", "grid") == "list"
    private var sortKey = runCatching { SortKey.valueOf(prefs.getString("sortKey", null) ?: "NAME") }.getOrDefault(SortKey.NAME)
    private var descending = prefs.getBoolean("sortDesc", false)
    private var showHidden = prefs.getBoolean("hidden", false)
    private var loadSeq = 0
    private var shownPlace: Place? = null
    private var loadCancel: Cancel? = null
    private var pick: Pick? = null
    private var mode = Mode.NARROW
    /** A short window: the location joins the toolbar and the status line steps aside, so the files keep a full row. */
    private var shortWindow = false
    private var statusRow: View? = null
    /** The window's title, which shows the folder when a narrow, short window has no room for the location. */
    private var titleView: TextView? = null
    private var placeLabel = ""
    private var access = false
    private var freeText = ""
    private var searchNote = ""
    private var loading = false
    private var loadError: Throwable? = null
    private val testCallbacks = HashMap<Int, (JobResult) -> Unit>()
    private var lastProgress: JobProgress? = null
    /** Mounted volumes from the last sidebar load; local paths take their names from here. */
    private var volumes: List<Volume> = emptyList()

    private val root = Root()
    private val sheets = FmSheets(root, { theme }, shell.glass)
    private val adapter = FmAdapter(ctx) { theme }

    private val toolbar = Ui.row(ctx)
    private val sidebarButton = icon(R.drawable.ic_sidebar, R.string.fm_sidebar) { toggleDrawer() }
    private val backButton = icon(R.drawable.ic_chevron, R.string.fm_back) { goBack() }.apply { scaleX = -1f }
    private val forwardButton = icon(R.drawable.ic_chevron, R.string.fm_forward) { goForward() }
    private val searchButton = icon(R.drawable.ic_search, R.string.files_search) { startSearch() }
    private val viewButton = icon(R.drawable.ic_view_list, R.string.fm_view_list) { setListMode(!listMode) }
    private val menuButton = icon(R.drawable.ic_menu, R.string.fm_menu) { v -> showAllMenus(v) }
    private val pickBanner = Ui.row(ctx)
    private val pickText = Ui.text(ctx, theme, "", theme.textBody, theme.textPrimary, lines = 2)
    private val pathScroll = StableHorizontalScrollView(ctx)
    private val pathRow = Ui.row(ctx)
    private val pathIcon = ImageView(ctx)
    private val pathMenu = icon(R.drawable.ic_more, R.string.fm_more_actions) { v -> backgroundMenu(v, v.width / 2f, v.height.toFloat()) }
    private val pathPill = Ui.row(ctx)
    /** Holds [pathPill] between the arrows and the search button; a narrow window gives it [pathLine] instead. */
    private val pathSlot = FrameLayout(ctx)
    private val pathLine = FrameLayout(ctx)
    private val searchRow = Ui.row(ctx)
    private val searchField = EditText(ctx)
    private val sidebarList = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val sidebar = ScrollView(ctx)
    private val sidebarDock = FrameLayout(ctx)
    private val sideRows = ArrayList<SideRow>()
    private val drawerScrim = View(ctx)
    private val drawerFrame = FrameLayout(ctx)
    private val grid = GridView(ctx)
    private val listView = ListView(ctx)
    private val stateBox = Ui.row(ctx, vertical = true, gravity = Gravity.CENTER)
    private val stateIcon = ImageView(ctx)
    private val stateText = Ui.text(ctx, theme, "", theme.textBody, theme.textMuted, lines = 6).apply { gravity = Gravity.CENTER }
    // Stacked: two long labels side by side do not fit a phone-wide window.
    private val stateActions = Ui.row(ctx, vertical = true, gravity = Gravity.CENTER_HORIZONTAL)
    private val selectionBar = Ui.row(ctx)
    private val selectionText = Ui.text(ctx, theme, "", theme.textBody, theme.textPrimary, bold = true)
    private val jobRow = Ui.row(ctx)
    private val jobBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val jobText = Ui.text(ctx, theme, "", theme.textLabel, theme.textPrimary, lines = 1)
    private val statusLeft = Ui.text(ctx, theme, "", theme.textCaption, theme.textMuted)
    private val statusRight = Ui.text(ctx, theme, "", theme.textCaption, theme.textMuted)
    /** Holds the grid, the list, the empty state and the pages; built in [buildViews]. */
    private val content = FrameLayout(ctx)
    /** The start page and the cleaner, made the first time they show. */
    private var homePage: FmHomePage? = null
    private var cleanerPage: FmCleanerPage? = null
    private val homeHost = object : FmHomePage.Host {
        override fun openVolume(v: Volume) = this@FileManagerTool.openVolume(v)
        override fun openCategory(kind: FileCategory) = navigate(Place.Category(kind))
        override fun openPlace(place: Place) = navigate(place)
        override fun openPath(path: String) = this@FileManagerTool.openPath(path)
        override fun askAllFiles() = explainAllFiles()
    }

    override val view: View get() = root

    override val fillsFrame = true

    private val volumeListener: () -> Unit = { loadSidebar() }
    /**
     * LX Bridge coming up opens the system's folders and Android/data; going away closes them. The window follows
     * without a word: the sidebar and the start page are read again, and a folder only the shell can list is listed
     * again through the shell.
     */
    private val embed = EmbedClient.get(ctx)
    private var bridgeUp = false
    private val embedListener = EmbedClient.Listener {
        main.post {
            if (destroyed) return@post
            val up = embed.serviceRunning
            if (up == bridgeUp) return@post
            bridgeUp = up
            loadSidebar()
            when (place) {
                Place.Home -> homePage?.refresh()
                is Place.Folder -> refresh()
                null -> openStart()
                else -> {}
            }
        }
    }
    private val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        // Coming back from Android's permission page: pick up a grant made there.
        if (hasFocus && !destroyed && storage.allFilesAccess() != access) {
            loadSidebar()
            if (place == null || !access) openStart()
        }
    }
    private val jobListener = object : JobListener {
        override fun onProgress(progress: JobProgress) {
            if (!destroyed) showProgress(progress)
        }

        override fun onFinished(result: JobResult) {
            if (!destroyed) finished(result)
        }
    }

    /** Jobs ask through [ConflictDesk], which outlives this window; see [asker]. */
    private val resolver: ConflictResolver = ConflictDesk

    /** How [ConflictDesk] reaches this window while it is open. */
    private val asker = ConflictDesk.Asker { conflict, answer -> askConflict(conflict, answer) }

    init {
        buildViews()
        bridgeUp = embed.serviceRunning
        embed.addListener(embedListener)
        jobs.addListener(jobListener)
        ConflictDesk.attach(asker)
        storage.addVolumeListener(volumeListener)
        jobs.running().lastOrNull()?.let { showProgress(it) }
        loadSidebar()
        openStart(initialQuery)
    }

    // ---- layout ----

    /** A sidebar row. [path] is the local folder it opens; [isHere] covers places that have no path. */
    private class SideRow(val view: View, val icon: ImageView, val label: TextView, val path: String?, val isHere: (Place?) -> Boolean)

    @SuppressLint("ViewConstructor")
    private inner class Root : FrameLayout(ctx) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val widthDp = w / resources.displayMetrics.density
            val next = when {
                widthDp >= 560f -> Mode.WIDE
                widthDp >= 480f -> Mode.MEDIUM
                else -> Mode.NARROW
            }
            val short = h / resources.displayMetrics.density < SHORT_DP
            // Very narrow windows drop Forward and the view switch; both stay in the menus.
            post {
                forwardButton.visibility = if (widthDp < 300f) View.GONE else View.VISIBLE
                viewButton.visibility = if (widthDp < 320f) View.GONE else View.VISIBLE
                if (next != mode || short != shortWindow || oldw == 0) { shortWindow = short; applyMode(next) }
            }
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && !sheets.isOpen && shortcut(event)) return true
            return super.dispatchKeyEvent(event)
        }
    }

    // Sized for the five-column grid; the large target option in Accessibility still gets full-size buttons.
    private fun icon(res: Int, label: Int, onClick: (View) -> Unit): ImageView =
        Ui.iconButton(ctx, theme, res, ctx.getString(label), if (theme.largeTargets) 48f else BUTTON_DP, 19f, onClick = onClick)

    /** A row of the sidebar, and the other rows of its size. */
    private val rowHeight get() = if (theme.largeTargets) theme.touchTarget else theme.dpi(ROW_DP)
    private val nameSize get() = theme.textBody - 1f

    private fun menu(anchor: View, items: List<MenuItem>, x: Float = -1f, y: Float = -1f) =
        shell.showMenu(anchor, items, x, y, dense = true)

    // The touch listener only claims a mouse's secondary button for the context menu; taps and long
    // presses still reach the list's own click handling, which TalkBack uses.
    @SuppressLint("ClickableViewAccessibility")
    private fun buildViews() {
        val t = theme
        val field = Contrast.over(t.textPrimary, FIELD_TINT, t.panel)
        val card = Contrast.over(t.textPrimary, CARD_TINT, t.panel)
        toolbar.setPadding(t.dpi(8f), t.dpi(3f), t.dpi(8f), t.dpi(3f))
        toolbar.addView(sidebarButton)
        toolbar.addView(backButton)
        toolbar.addView(forwardButton)
        toolbar.addView(pathSlot, Ui.lp(0, t.dpi(FIELD_DP), 1f))
        toolbar.addView(searchButton)
        toolbar.addView(viewButton)
        toolbar.addView(menuButton)
        pathLine.setPadding(t.dpi(10f), 0, t.dpi(10f), t.dpi(4f))

        pathPill.apply {
            background = rounded(field, t.dp(10f))
            setPadding(t.dpi(10f), 0, 0, 0)
            pathIcon.imageTintList = ColorStateList.valueOf(t.textMuted)
            pathIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(pathIcon, Ui.lp(t.dpi(15f), t.dpi(15f)).apply { marginEnd = t.dpi(2f) })
            addView(pathScroll, Ui.lp(0, Ui.MATCH, 1f))
            val side = t.dpi(9f)
            pathMenu.setPadding(side, side, side, side)
            addView(pathMenu, Ui.lp(t.dpi(FIELD_DP), t.dpi(FIELD_DP)))
        }

        pickBanner.apply {
            setPadding(t.dpi(12f), t.dpi(4f), t.dpi(8f), t.dpi(4f))
            setBackgroundColor(LxTheme.withAlpha(t.accent, 0.16f))
            addView(pickText, Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.button(ctx, t, ctx.getString(R.string.action_cancel)) { endPick() })
            addView(Ui.button(ctx, t, ctx.getString(R.string.fm_pick_here), primary = true) { confirmPick() },
                Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = t.dpi(4f) })
            visibility = View.GONE
        }

        pathScroll.apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(t.dpi(24f))
            // The path scrolls inside its pill, which is drawn in [field].
            fadeInto = { field }
            addView(pathRow.apply { setPadding(t.dpi(4f), 0, t.dpi(4f), 0) })
        }

        searchField.apply {
            hint = ctx.getString(R.string.files_search_hint)
            typeface = t.font
            setTextColor(t.textPrimary)
            setHintTextColor(t.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textBody - 1f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = null
            if (Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(t.accent)
            setOnEditorActionListener { _, action, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) {
                    runSearch(text.toString())
                    true
                } else false
            }
        }
        searchRow.apply {
            background = rounded(field, t.dp(10f))
            setPadding(t.dpi(12f), 0, 0, 0)
            addView(searchField, Ui.lp(0, Ui.WRAP, 1f))
            addView(icon(R.drawable.ic_close, R.string.fm_search_close) { endSearch() })
            visibility = View.GONE
        }

        sidebar.apply {
            isVerticalScrollBarEnabled = true
            addView(sidebarList.apply { setPadding(t.dpi(6f), t.dpi(6f), t.dpi(6f), t.dpi(6f)) })
        }
        sidebarDock.background = rounded(card, t.dp(12f))
        drawerScrim.apply {
            setBackgroundColor(t.scrim(0.4f))
            isClickable = true
            setOnClickListener { closeDrawer() }
            visibility = View.GONE
        }
        drawerFrame.apply {
            background = rounded(card, t.dp(12f))
            elevation = t.dp(8f)
            isClickable = true
            visibility = View.GONE
        }

        adapter.list = listMode
        for (list in listOf<AbsListView>(grid, listView)) list.apply {
            setAdapter(this@FileManagerTool.adapter)
            selector = Ui.stateBackground(t)
            isFocusable = true
            setOnItemClickListener { _, v, pos, _ -> tapped(entries[pos], v) }
            setOnItemLongClickListener { _, v, pos, _ ->
                pressed(entries[pos], v, -1f, -1f)
                true
            }
            // A mouse's secondary button opens the context menu where it was pressed, and a long
            // press on empty space opens the folder menu (the list only reports presses on items).
            val list = this
            val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (list.pointToPosition(e.x.toInt(), e.y.toInt()) == AbsListView.INVALID_POSITION) backgroundMenu(list, e.x, e.y)
                }
            })
            setOnTouchListener { _, ev ->
                gestures.onTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) secondaryClick(this, ev)
                else false
            }
        }
        grid.apply {
            numColumns = GridView.AUTO_FIT
            // In device pixels, not LX's interface size: a phone-wide window keeps its five columns at any size.
            columnWidth = (GRID_COLUMN_DP * resources.displayMetrics.density).toInt()
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = t.dpi(2f)
            setPadding(t.dpi(6f), t.dpi(2f), t.dpi(6f), t.dpi(6f))
            clipToPadding = false
        }
        listView.apply {
            divider = null
            setPadding(t.dpi(8f), t.dpi(2f), t.dpi(8f), t.dpi(8f))
            clipToPadding = false
        }
        stateBox.apply {
            val p = t.dpi(20f)
            setPadding(p, p, p, p)
            stateIcon.setImageResource(R.drawable.ic_fm_folder)
            stateIcon.alpha = 0.6f
            stateIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(stateIcon, Ui.lp(t.dpi(52f), t.dpi(52f)).apply { bottomMargin = t.dpi(10f) })
            addView(stateText, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(stateActions, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = t.dpi(12f) })
        }
        content.apply {
            addView(grid, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(listView, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            addView(stateBox, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
        }
        selectionBar.apply {
            setPadding(t.dpi(4f), 0, t.dpi(4f), 0)
            background = rounded(field, t.dp(12f))
            addView(icon(R.drawable.ic_close, R.string.fm_clear_selection) { clearSelection() })
            addView(selectionText, Ui.lp(0, Ui.WRAP, 1f))
            addView(icon(R.drawable.ic_copy, R.string.fm_copy) { clip(cut = false) })
            addView(icon(R.drawable.ic_cut, R.string.fm_cut) { clip(cut = true) })
            addView(icon(R.drawable.ic_delete, R.string.action_delete) { deleteTargets(targets()) })
            addView(icon(R.drawable.ic_more, R.string.fm_more_actions) { v -> contextMenu(targets(), v, -1f, -1f) })
            visibility = View.GONE
        }

        jobBar.apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(t.accent)
            progressBackgroundTintList = ColorStateList.valueOf(LxTheme.withAlpha(t.textMuted, 0.3f))
            indeterminateTintList = ColorStateList.valueOf(t.accent)
        }
        jobRow.apply {
            setPadding(t.dpi(12f), 0, t.dpi(4f), 0)
            addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                addView(jobText, Ui.lp(Ui.MATCH, Ui.WRAP))
                addView(jobBar, Ui.lp(Ui.MATCH, t.dpi(6f)).apply { topMargin = t.dpi(4f) })
            }, Ui.lp(0, Ui.WRAP, 1f))
            addView(icon(R.drawable.ic_close, R.string.fm_cancel_job) { lastProgress?.let { jobs.cancel(it.id) } })
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        val status = Ui.row(ctx).apply {
            statusRow = this
            setPadding(t.dpi(14f), t.dpi(2f), t.dpi(14f), t.dpi(6f))
            minimumHeight = t.dpi(24f)
            addView(statusLeft, Ui.lp(0, Ui.WRAP, 1f))
            addView(statusRight, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = t.dpi(8f) })
        }

        val column = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(toolbar, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(pathLine, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(pickBanner, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(searchRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
                marginStart = t.dpi(10f)
                marginEnd = t.dpi(10f)
                bottomMargin = t.dpi(4f)
            })
            addView(content, Ui.lp(Ui.MATCH, 0, 1f))
            addView(selectionBar, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
                marginStart = t.dpi(8f)
                marginEnd = t.dpi(8f)
            })
            addView(jobRow, Ui.lp(Ui.MATCH, rowHeight))
            addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        root.addView(Ui.row(ctx, gravity = Gravity.NO_GRAVITY).apply {
            addView(sidebarDock, Ui.lp(t.dpi(180f), Ui.MATCH).apply {
                marginStart = t.dpi(6f)
                topMargin = t.dpi(6f)
                bottomMargin = t.dpi(6f)
            })
            addView(column, Ui.lp(0, Ui.MATCH, 1f))
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(drawerScrim, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(drawerFrame, FrameLayout.LayoutParams(t.dpi(DRAWER_DP), Ui.MATCH, Gravity.START).apply {
            val m = t.dpi(6f)
            setMargins(m, m, m, m)
        })
        applyListMode()
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun applyMode(next: Mode) {
        mode = next
        closeDrawer()
        (sidebar.parent as? FrameLayout)?.removeView(sidebar)
        val wide = next == Mode.WIDE
        if (wide) sidebarDock.addView(sidebar, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        else drawerFrame.addView(sidebar, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        sidebarDock.visibility = if (wide) View.VISIBLE else View.GONE
        sidebarButton.visibility = if (wide) View.GONE else View.VISIBLE
        (pathPill.parent as? ViewGroup)?.removeView(pathPill)
        val pill = FrameLayout.LayoutParams(Ui.MATCH, theme.dpi(FIELD_DP), Gravity.CENTER_VERTICAL)
        // A narrow window gives the location a line of its own. One too short to spare that line has no room for it in
        // the toolbar either: the folder's name goes to the window's title, and the folder's menu is in the main menu.
        val ownLine = next == Mode.NARROW && !shortWindow
        val inTitle = next == Mode.NARROW && shortWindow
        if (ownLine) pathLine.addView(pathPill, pill) else if (!inTitle) pathSlot.addView(pathPill, pill)
        pathLine.visibility = if (ownLine) View.VISIBLE else View.GONE
        statusRow?.visibility = if (shortWindow) View.GONE else View.VISIBLE
        updateTitle()
    }

    override fun decorate(title: TextView, icon: ImageView): Boolean {
        titleView = title
        updateTitle()
        return true
    }

    private fun updateTitle() {
        val name = ctx.getString(R.string.tool_files)
        val text = if (mode == Mode.NARROW && shortWindow && placeLabel.isNotEmpty()) placeLabel else name
        titleView?.let { if (it.text.toString() != text) it.text = text }
    }

    private fun toggleDrawer() = if (drawerFrame.visibility == View.VISIBLE) closeDrawer() else openDrawer()

    private fun openDrawer() {
        if (mode == Mode.WIDE) return
        val width = theme.dpi(DRAWER_DP).coerceAtMost((root.width * 0.85f).toInt())
        drawerFrame.layoutParams = (drawerFrame.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
        drawerScrim.visibility = View.VISIBLE
        drawerFrame.visibility = View.VISIBLE
        if (theme.animations) {
            drawerScrim.alpha = 0f
            drawerScrim.animate().alpha(1f).setDuration(theme.durationShort).start()
            drawerFrame.translationX = drawerAway(width)
            drawerFrame.animate().translationX(0f).setDuration(theme.durationShort).setInterpolator(DecelerateInterpolator()).start()
        }
        sidebarList.getChildAt(0)?.requestFocus()
    }

    /** Where the drawer waits off the window's edge, on the side reading starts from. */
    private fun drawerAway(width: Int) = (width + theme.dpi(8f)) * if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f

    private fun closeDrawer(): Boolean {
        if (drawerFrame.visibility != View.VISIBLE) return false
        val gone = Runnable {
            drawerScrim.visibility = View.GONE
            drawerFrame.visibility = View.GONE
            drawerFrame.translationX = 0f
        }
        if (!theme.animations || !root.isLaidOut) {
            gone.run()
            return true
        }
        drawerScrim.animate().alpha(0f).setDuration(theme.durationShort).start()
        drawerFrame.animate().translationX(drawerAway(drawerFrame.width)).setDuration(theme.durationShort)
            .setInterpolator(AccelerateInterpolator()).withEndAction(gone).start()
        return true
    }

    private fun setListMode(list: Boolean) {
        listMode = list
        prefs.edit().putString("view", if (list) "list" else "grid").apply()
        applyListMode()
    }

    private fun applyListMode() {
        adapter.list = listMode
        showPage()
        // The button offers the other view.
        viewButton.setImageResource(if (listMode) R.drawable.ic_view_grid else R.drawable.ic_view_list)
        val label = ctx.getString(if (listMode) R.string.fm_view_grid else R.string.fm_view_list)
        viewButton.contentDescription = label
        viewButton.tooltipText = label
        adapter.notifyDataSetChanged()
    }

    // ---- sidebar ----

    private fun loadSidebar() {
        io.execute {
            // With LX Bridge running, LX gives itself all-files access as adb would; nothing is asked or shown.
            val granted = storage.allFilesAccess() || storage.grantThroughBridge()
            val volumes = runCatching { storage.volumes() }.getOrDefault(emptyList())
            val folders = if (granted) runCatching { storage.standardFolders() }.getOrDefault(emptyList()) else emptyList()
            val trees = runCatching { storage.safTrees().map { it to runCatching { storage.saf(it).label }.getOrDefault(it.lastPathSegment.orEmpty()) } }
                .getOrDefault(emptyList())
            val system = runCatching { storage.systemPlaces() }.getOrDefault(emptyList())
            main.post {
                if (destroyed) return@post
                val gained = granted && !access
                access = granted
                this.volumes = volumes
                buildPath()
                buildSidebar(volumes, folders, trees, system)
                if (gained && place == null) openStart()
            }
        }
    }

    private fun buildSidebar(volumes: List<Volume>, folders: List<Pair<String, String>>, trees: List<Pair<Uri, String>>,
                             system: List<Pair<String, String>>) {
        sidebarList.removeAllViews()
        sideRows.clear()
        sideRow(R.drawable.ic_home, ctx.getString(R.string.fm_home), "", isHere = { it === Place.Home }) { navigate(Place.Home) }
        if (access) sideRow(R.drawable.ic_clock, ctx.getString(R.string.fm_recent), "", isHere = { it === Place.Recent }) { navigate(Place.Recent) }
        else sideRow(R.drawable.ic_lock, ctx.getString(R.string.fm_allow_all), ctx.getString(R.string.fm_allow_all_short)) { explainAllFiles() }
        val (internal, others) = volumes.partition { it.kind == VolumeKind.INTERNAL }
        for (v in internal) volumeRow(v)
        if (access) {
            gap()
            for ((key, path) in folders) sideRow(placeIcon(key), placeLabel(key), "", path = path) { openPath(path) }
            sideRow(R.drawable.ic_archive, ctx.getString(R.string.fm_archives), "", isHere = { it is Place.Archives }) { openArchivesPlace() }
        }
        sideRow(R.drawable.ic_cleaning, ctx.getString(R.string.fm_cleaner), "", isHere = { it === Place.Cleaner }) { navigate(Place.Cleaner) }
        if (system.isNotEmpty()) gap()
        for ((key, path) in system) {
            val (icon, label) = when (key) {
                "root" -> R.drawable.ic_storage to R.string.fm_place_root
                "data" -> R.drawable.ic_android to R.string.fm_place_app_data
                "obb" -> R.drawable.ic_games to R.string.fm_place_obb
                else -> R.drawable.ic_folder to R.string.fm_place_tmp
            }
            // "/" holds every other row's path; it marks itself only when the open folder lies outside the volumes.
            if (key == "root") sideRow(icon, ctx.getString(label), path, isHere = { p ->
                val here = (p as? Place.Folder)?.dir?.takeIf { it.backend.key == "local" }?.ref
                here != null && volumes.none { v -> v.path != null && (here == v.path || here.startsWith(v.path + "/")) } &&
                    system.none { (k, sp) -> k != "root" && (here == sp || here.startsWith("$sp/")) }
            }) { openPath(path) }
            else sideRow(icon, ctx.getString(label), path, path = path) { openPath(path) }
        }
        if (others.isNotEmpty()) gap()
        for (v in others) volumeRow(v)
        gap()
        for ((uri, label) in trees) sideRow(R.drawable.ic_folder, label, "", onLong = { v -> forgetTreeMenu(uri, label, v) },
            isHere = { (it as? Place.Folder)?.chain?.firstOrNull()?.let { root -> root.backend.key != "local" && rootLabel(root) == label } == true }) { openTree(uri) }
        sideRow(R.drawable.ic_add, ctx.getString(R.string.fm_add_folder), "") { addFolder() }
        markSidebar()
    }

    private fun volumeRow(v: Volume) {
        val iconRes = when (v.kind) {
            VolumeKind.SD_CARD -> R.drawable.ic_sd_card
            VolumeKind.USB -> R.drawable.ic_usb
            else -> R.drawable.ic_storage
        }
        val label = if (v.kind == VolumeKind.INTERNAL) ctx.getString(R.string.fm_internal) else v.label
        // The window's status line shows the free space of the open volume; the row only says it aloud.
        val detail = when {
            !v.mounted -> ctx.getString(R.string.fm_volume_unmounted)
            v.total > 0 -> ctx.getString(R.string.fm_volume_free, FmText.size(ctx, v.free), FmText.size(ctx, v.total)) +
                if (v.readOnly) " · " + ctx.getString(R.string.fm_volume_read_only) else ""
            else -> ""
        }
        sideRow(iconRes, label, detail, enabled = v.mounted, path = v.path) { openVolume(v) }
    }

    /** Space between groups of rows: the sidebar has no headings, its icons and order say what each group is. */
    private fun gap() {
        sidebarList.addView(View(ctx), Ui.lp(Ui.MATCH, theme.dpi(8f)))
    }

    private fun sideRow(iconRes: Int, label: String, detail: String, enabled: Boolean = true, path: String? = null,
                        onLong: ((View) -> Unit)? = null, isHere: (Place?) -> Boolean = { false }, onClick: () -> Unit) {
        val t = theme
        val glyph = ImageView(ctx).apply { setImageResource(iconRes) }
        val text = Ui.text(ctx, t, label, nameSize, t.textPrimary)
        val row = Ui.row(ctx).apply {
            minimumHeight = rowHeight
            setPadding(t.dpi(10f), 0, t.dpi(8f), 0)
            background = Ui.stateBackground(t, t.dp(9f))
            isFocusable = true
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.5f
            addView(glyph, Ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(12f) })
            addView(text, Ui.lp(0, Ui.WRAP, 1f))
            contentDescription = if (detail.isEmpty()) label else "$label, $detail"
            setOnClickListener {
                closeDrawer()
                onClick()
            }
            if (onLong != null) setOnLongClickListener {
                onLong(it)
                true
            }
        }
        sideRows += SideRow(row, glyph, text, path, isHere)
        sidebarList.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    /** Marks the row for the open folder: the deepest row whose path holds it, or the row for a place without one. */
    private fun markSidebar() {
        val t = theme
        val here = (place as? Place.Folder)?.dir?.takeIf { it.backend.key == "local" }?.ref
        val deepest = sideRows.filter { r -> r.path != null && here != null && (here == r.path || here.startsWith(r.path + "/")) }
            .maxByOrNull { it.path!!.length }
        for (r in sideRows) {
            val on = r === deepest || r.isHere(place)
            r.view.isSelected = on
            r.label.setTextColor(if (on) t.selectedText else t.textPrimary)
            r.label.typeface = if (on) t.fontMedium else t.font
            r.icon.imageTintList = ColorStateList.valueOf(if (on) t.selectedText else t.textMuted)
        }
    }

    private fun placeIcon(key: String) = when (key) {
        Environment.DIRECTORY_DOWNLOADS -> R.drawable.ic_download
        Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_DCIM -> R.drawable.ic_image
        Environment.DIRECTORY_MOVIES -> R.drawable.ic_media
        Environment.DIRECTORY_MUSIC -> R.drawable.ic_music
        Environment.DIRECTORY_DOCUMENTS -> R.drawable.ic_notes
        else -> R.drawable.ic_folder
    }

    private fun placeLabel(key: String) = ctx.getString(when (key) {
        Environment.DIRECTORY_DOWNLOADS -> R.string.fm_place_downloads
        Environment.DIRECTORY_DOCUMENTS -> R.string.fm_place_documents
        Environment.DIRECTORY_PICTURES -> R.string.fm_place_pictures
        Environment.DIRECTORY_DCIM -> R.string.fm_place_camera
        Environment.DIRECTORY_MOVIES -> R.string.fm_place_videos
        Environment.DIRECTORY_MUSIC -> R.string.fm_place_music
        else -> R.string.files_folder
    })

    // ---- navigation ----

    private fun openStart(query: String? = null) {
        io.execute {
            val granted = storage.allFilesAccess() || storage.grantThroughBridge()
            // The window opens on its start page, unless it was last left in a folder; a search needs a folder to start from.
            if (query.isNullOrBlank() && prefs.getString("start", "home") == "home") {
                main.post {
                    if (destroyed) return@post
                    access = granted
                    navigate(Place.Home, record = false)
                }
                return@execute
            }
            var start: Place.Folder? = null
            if (granted) {
                val last = prefs.getString("last", null)
                start = last?.let { path -> storage.localNode(path)?.takeIf { it.isDir }?.let { chainFor(it) } }
                    ?: storage.volumes().firstOrNull { it.kind == VolumeKind.INTERNAL && it.path != null }
                        ?.let { v -> storage.localNode(v.path!!)?.let { Place.Folder(listOf(it)) } }
            }
            if (start == null) {
                val trees = runCatching { storage.safTrees() }.getOrDefault(emptyList())
                val saved = prefs.getString("root", null)
                val tree = trees.firstOrNull { it.toString() == saved } ?: trees.firstOrNull()
                start = tree?.let { runCatching { Place.Folder(listOf(storage.saf(it).root)) }.getOrNull() }
            }
            main.post {
                if (destroyed) return@post
                access = granted
                val s = start
                if (s == null) {
                    place = null
                    showNoAccess()
                    return@post
                }
                navigate(s, record = false)
                if (!query.isNullOrBlank()) {
                    startSearch()
                    searchField.setText(query)
                    runSearch(query)
                }
            }
        }
    }

    /** Builds the folder chain from the top of a local volume, so Up and the path line work. Worker thread. */
    private fun chainFor(node: FsNode): Place.Folder {
        val chain = ArrayList<FsNode>()
        var n: FsNode? = node
        var guard = 0
        while (n != null && guard++ < 64) {
            chain += n
            n = n.backend.parent(n)
        }
        chain.reverse()
        return Place.Folder(chain)
    }

    private fun openVolume(v: Volume) {
        if (!v.mounted) return
        if (access && v.path != null) {
            openPath(v.path)
            return
        }
        if (v.kind != VolumeKind.INTERNAL && Build.VERSION.SDK_INT >= 29) grantVolume(v) else explainAllFiles()
    }

    private fun openPath(path: String) {
        io.execute {
            val node = storage.localNode(path)
            val chain = node?.let { chainFor(it) }
            main.post { if (!destroyed && chain != null) navigate(chain) else if (!destroyed) toast(ctx.getString(R.string.fm_err_not_found)) }
        }
    }

    private fun openTree(uri: Uri) {
        io.execute {
            val folder = runCatching { Place.Folder(listOf(storage.saf(uri).root)) }.getOrNull()
            main.post {
                if (destroyed) return@post
                if (folder != null) navigate(folder) else toast(ctx.getString(R.string.fm_err_gone))
            }
        }
    }

    private fun openArchivesPlace() {
        val from = when (val here = place) {
            is Place.Folder -> here
            is Place.Search -> here.from
            is Place.Archives -> here.from
            else -> history.lastOrNull { it is Place.Folder } as? Place.Folder
        } ?: return
        navigate(Place.Archives(Place.Folder(from.chain.take(1))))
    }

    private fun navigate(target: Place, record: Boolean = true) {
        val current = place
        if (record && current != null && current != target) {
            history += current
            if (history.size > 64) history.removeAt(0)
            future.clear()
        }
        place = target
        walkCancel?.cancel()
        clearSelection()
        if (target !is Place.Search) {
            searchRow.visibility = View.GONE
            pathScroll.visibility = View.VISIBLE
        }
        if (target is Place.Folder) remember(target)
        if (target === Place.Home) prefs.edit().putString("start", "home").apply()
        load()
    }

    private fun remember(folder: Place.Folder) {
        val dir = folder.dir
        val e = prefs.edit().putString("start", "folder")
        if (dir.backend.key == "local") e.putString("last", dir.ref) else {
            e.remove("last")
            e.putString("root", dir.backend.key.removePrefix("saf:"))
        }
        e.apply()
    }

    private fun goBack() {
        val prev = history.removeLastOrNull() ?: return
        place?.let { future += it }
        place = prev
        clearSelection()
        load()
    }

    private fun goForward() {
        val next = future.removeLastOrNull() ?: return
        place?.let { history += it }
        place = next
        clearSelection()
        load()
    }

    private fun goUp(): Boolean {
        val f = place as? Place.Folder ?: return false
        if (f.chain.size > 1) {
            navigate(Place.Folder(f.chain.dropLast(1)))
            return true
        }
        return false
    }

    private fun refresh() = load(keep = true)

    // ---- loading ----

    /** Lists the current place. [keep] leaves the old items up until the new ones arrive, for a refresh. */
    private fun load(keep: Boolean = false) {
        val target = place ?: return showNoAccess()
        if (!keep) {
            entries = emptyList()
            adapter.items = emptyList()
        }
        loadCancel?.cancel()
        // A sort still running for the folder being left must not land in this one, nor its thumbnails.
        filterSeq.incrementAndGet()
        adapter.clearQueue()
        sorting = false
        val cancel = Cancel()
        loadCancel = cancel
        val seq = ++loadSeq
        loading = true
        loadError = null
        searchNote = ""
        raw = emptyList()
        adapter.locations = emptyMap()
        updateChrome()
        render()
        io.execute {
            if (cancel.cancelled) return@execute
            try {
                when (target) {
                    is Place.Folder -> {
                        val items = target.dir.backend.children(target.dir, cancel)
                        val free = target.dir.backend.freeBytes(target.dir)
                        post(seq) {
                            freeText = if (free >= 0) ctx.getString(R.string.fm_free, FmText.size(ctx, free)) else ""
                            done(items)
                        }
                    }
                    Place.Home, Place.Cleaner -> post(seq) {
                        // The pages read their own data; the list behind them stays empty.
                        loading = false
                        if (target === Place.Home) home().refresh() else cleaner().start()
                        render()
                    }
                    is Place.Category -> {
                        val items = storage.category(target.kind, CATEGORY_LIMIT, cancel)
                        val where = items.associate { it.key to locationOf(it) }
                        post(seq) {
                            adapter.locations = where
                            done(items)
                        }
                    }
                    Place.Recent -> {
                        val items = storage.recent(RECENT_LIMIT, cancel)
                        val where = items.associate { it.key to locationOf(it) }
                        post(seq) {
                            adapter.locations = where
                            done(items)
                        }
                    }
                    is Place.Archives -> {
                        val found = ArrayList<FsNode>()
                        val outcome = storage.findArchives(target.from.dir, cancel, SEARCH_VISITED) { batch ->
                            val where = batch.associate { it.key to locationOf(it) }
                            post(seq) { append(batch, where) }
                            found += batch
                        }
                        post(seq) {
                            searchNote = searchSummary(outcome)
                            done(found.toList())
                        }
                    }
                    is Place.Search -> {
                        val found = ArrayList<FsNode>()
                        val outcome = storage.search(target.from.dir, target.query, cancel, SEARCH_VISITED, SEARCH_HITS) { batch ->
                            val where = batch.associate { it.key to locationOf(it) }
                            post(seq) { append(batch, where) }
                            found += batch
                        }
                        post(seq) {
                            searchNote = searchSummary(outcome)
                            done(found.toList())
                        }
                    }
                }
            } catch (e: Exception) {
                post(seq) {
                    loading = false
                    loadError = if (e is FsException && e.reason == FsException.Reason.CANCELLED) null else e
                    render()
                }
            }
        }
    }

    private fun locationOf(node: FsNode): String =
        runCatching { node.backend.parent(node)?.let { it.backend.displayPath(it) } }.getOrNull().orEmpty()

    private fun searchSummary(o: SearchOutcome) = if (o.truncated)
        ctx.resources.getQuantityString(R.plurals.fm_search_truncated, o.visited, o.visited) else ""

    private fun post(seq: Int, block: () -> Unit) {
        main.post { if (!destroyed && seq == loadSeq) block() }
    }

    private fun append(batch: List<FsNode>, where: Map<String, String>) {
        raw = raw + batch
        adapter.locations = adapter.locations + where
        applyFilter()
    }

    private fun done(items: List<FsNode>) {
        loading = false
        raw = items
        applyFilter()
    }

    private fun applyFilter() {
        val mine = filterSeq.incrementAndGet()
        val source = raw
        val withHidden = showHidden
        val keepOrder = place is Place.Recent || place is Place.Category
        val comparator = FmText.comparator(sortKey, descending)
        fun order(): List<FsNode> {
            val visible = if (withHidden) source else source.filter { !it.hidden }
            return if (keepOrder) visible else visible.sortedWith(comparator)
        }
        // A small folder sorts at once, without a frame of "Loading"; a big one sorts on its own thread.
        if (source.size <= SORT_ON_MAIN_MAX) return showOrdered(order())
        sorting = true
        sorter.execute {
            if (mine != filterSeq.get()) return@execute
            val ordered = order()
            main.post { if (!destroyed && mine == filterSeq.get()) showOrdered(ordered) }
        }
    }

    private fun showOrdered(ordered: List<FsNode>) {
        sorting = false
        entries = ordered
        // Selection keeps only what is still listed.
        val keys = ordered.mapTo(HashSet()) { it.key }
        selection.keys.retainAll(keys)
        adapter.items = ordered
        render()
    }

    private fun render() {
        val t = theme
        val page = showPage()
        // Without a place the window shows the start buttons of showNoAccess, which stay.
        if (place != null) stateActions.removeAllViews()
        val message: String? = when {
            place == null -> null
            loadError != null -> FmText.error(ctx, loadError)
            (loading || sorting) && entries.isEmpty() -> ctx.getString(R.string.state_loading)
            entries.isEmpty() && raw.isNotEmpty() -> ctx.getString(R.string.fm_only_hidden)
            entries.isEmpty() -> when (val p = place) {
                is Place.Search -> ctx.getString(R.string.files_no_results, p.query)
                is Place.Archives -> ctx.getString(R.string.fm_no_archives)
                is Place.Category -> ctx.getString(R.string.fm_no_kind)
                Place.Recent -> ctx.getString(R.string.fm_no_recent)
                else -> ctx.getString(R.string.files_empty)
            }
            else -> null
        }
        if (place != null) {
            stateText.text = message.orEmpty()
            stateBox.visibility = if (message == null || page) View.GONE else View.VISIBLE
            stateIcon.visibility = if (loadError == null && !loading) View.VISIBLE else View.GONE
            when {
                loadError != null -> {
                    stateAction(R.string.fm_retry, primary = true) { refresh() }
                    if ((place as? Place.Folder)?.chain?.size ?: 0 > 1) stateAction(R.string.files_back_to_top) {
                        (place as? Place.Folder)?.let { navigate(Place.Folder(it.chain.take(1))) }
                    }
                }
                entries.isEmpty() && raw.isNotEmpty() -> stateAction(R.string.fm_show_hidden, primary = true) { setHidden(true) }
            }
        }
        stateActions.visibility = if (stateActions.childCount > 0) View.VISIBLE else View.GONE
        adapter.selected = selection.keys.toSet()
        adapter.dimFiles = pick != null
        adapter.notifyDataSetChanged()
        // A folder's items fade in once, so the old folder's cells do not seem to turn into the new ones.
        if (place !== shownPlace && entries.isNotEmpty()) {
            shownPlace = place
            if (theme.animations) (if (listMode) listView else grid).run {
                alpha = 0f
                animate().alpha(1f).setDuration(theme.durationShort).start()
            }
        }
        updateStatus()
        updateSelectionBar()
    }

    private fun home(): FmHomePage = homePage ?: FmHomePage(ctx, { theme }, storage, homeHost).also {
        homePage = it
        content.addView(it, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    private fun cleaner(): FmCleanerPage = cleanerPage ?: FmCleanerPage(ctx, { theme }, sheets, ::toast) {
        // Space came free: the start page's counts and the free space below are read again when they show.
        homePage?.refresh()
    }.also {
        cleanerPage = it
        content.addView(it, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    /** Shows the page of a page place over the list, or hides the pages. True when a page shows. */
    private fun showPage(): Boolean {
        val p = place
        homePage?.visibility = if (p === Place.Home) View.VISIBLE else View.GONE
        cleanerPage?.visibility = if (p === Place.Cleaner) View.VISIBLE else View.GONE
        val page = p === Place.Home || p === Place.Cleaner
        if (page) {
            grid.visibility = View.GONE
            listView.visibility = View.GONE
        } else {
            grid.visibility = if (listMode) View.GONE else View.VISIBLE
            listView.visibility = if (listMode) View.VISIBLE else View.GONE
        }
        return page
    }

    /** Adds a button under the empty-state message. All of them fill the column, so it is as wide as the longest label. */
    private fun stateAction(label: Int, primary: Boolean = false, action: () -> Unit) {
        stateActions.addView(FmSheets.compact(Ui.button(ctx, theme, ctx.getString(label), primary = primary) { action() }, theme),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { if (stateActions.childCount > 0) topMargin = theme.dpi(6f) })
    }

    private fun showNoAccess() {
        val t = theme
        entries = emptyList()
        adapter.items = emptyList()
        stateText.text = ctx.getString(R.string.fm_start_hint)
        stateActions.removeAllViews()
        stateAction(R.string.fm_allow_all, primary = true) { explainAllFiles() }
        stateAction(R.string.fm_add_folder) { addFolder() }
        stateActions.visibility = View.VISIBLE
        stateBox.visibility = View.VISIBLE
        updateChrome()
        updateStatus()
    }

    private fun updateChrome() {
        backButton.isEnabled = history.isNotEmpty()
        backButton.alpha = if (history.isNotEmpty()) 1f else 0.4f
        forwardButton.isEnabled = future.isNotEmpty()
        forwardButton.alpha = if (future.isNotEmpty()) 1f else 0.4f
        buildPath()
        markSidebar()
    }

    private fun buildPath() {
        val t = theme
        pathRow.removeAllViews()
        placeLabel = ""
        fun crumb(label: String, current: Boolean, onClick: (() -> Unit)?) {
            if (current) placeLabel = label
            if (pathRow.childCount > 0) pathRow.addView(Ui.text(ctx, t, "/", t.textLabel, t.textMuted).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(t.dpi(1f), 0, t.dpi(1f), 0)
            })
            pathRow.addView(Ui.text(ctx, t, label, t.textLabel, if (current) t.textPrimary else t.textMuted, bold = current).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = t.dpi(30f)
                setPadding(t.dpi(5f), 0, t.dpi(5f), 0)
                if (onClick != null) {
                    background = Ui.stateBackground(t)
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }, Ui.lp(Ui.WRAP, Ui.MATCH))
        }
        val p = place
        // The button opens the menu of a folder; search results and Recent are not one.
        pathMenu.visibility = if (p is Place.Folder) View.VISIBLE else View.GONE
        pathIcon.setImageResource(when (p) {
            is Place.Folder -> volumes.firstOrNull { it.path == p.chain.first().ref }?.let {
                when (it.kind) {
                    VolumeKind.SD_CARD -> R.drawable.ic_sd_card
                    VolumeKind.USB -> R.drawable.ic_usb
                    else -> R.drawable.ic_storage
                }
            } ?: if (p.chain.first().ref == "/") R.drawable.ic_storage else R.drawable.ic_folder
            Place.Home -> R.drawable.ic_home
            Place.Cleaner -> R.drawable.ic_cleaning
            is Place.Category -> R.drawable.ic_category
            Place.Recent -> R.drawable.ic_clock
            is Place.Archives -> R.drawable.ic_archive
            is Place.Search -> R.drawable.ic_search
            null -> R.drawable.ic_home
        })
        when (p) {
            is Place.Folder -> p.chain.forEachIndexed { i, node ->
                val label = if (i == 0) rootLabel(node) else node.name
                val last = i == p.chain.lastIndex
                crumb(label, last, if (last) null else ({ navigate(Place.Folder(p.chain.take(i + 1))) }))
            }
            Place.Home -> crumb(ctx.getString(R.string.fm_home), true, null)
            Place.Cleaner -> crumb(ctx.getString(R.string.fm_cleaner), true, null)
            is Place.Category -> crumb(ctx.getString(FmHomePage.kindLabel(p.kind)), true, null)
            Place.Recent -> crumb(ctx.getString(R.string.fm_recent), true, null)
            is Place.Archives -> crumb(ctx.getString(R.string.fm_archives), true, null)
            is Place.Search -> crumb(ctx.getString(R.string.files_search_results, p.query, folderLabel(p.from)), true, null)
            null -> {}
        }
        updateTitle()
        // Show the end of a long path. scrollTo, not fullScroll, so focus stays where it is.
        pathScroll.post { pathScroll.scrollTo(if (pathScroll.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0 else pathRow.width, 0) }
    }

    private fun folderLabel(f: Place.Folder) = if (f.chain.size == 1) rootLabel(f.dir) else f.dir.name

    /** A volume's own name for the top of a local path, the grant's name for a picked folder. */
    private fun rootLabel(node: FsNode): String {
        if (node.backend.key != "local") return node.backend.label
        if (node.ref == "/") return "/"
        val volume = volumes.firstOrNull { it.path == node.ref } ?: return node.name.ifEmpty { node.ref }
        return if (volume.kind == VolumeKind.INTERNAL) ctx.getString(R.string.fm_internal_short) else volume.label
    }

    private fun updateStatus() {
        if (place == null || place === Place.Home || place === Place.Cleaner) {
            statusLeft.text = ""
            statusRight.text = ""
            return
        }
        val count = entries.size
        val left = StringBuilder(ctx.resources.getQuantityString(R.plurals.fm_items, count, count))
        if (selection.isNotEmpty()) left.append(" · ").append(ctx.resources.getQuantityString(R.plurals.fm_selected, selection.size, selection.size))
        if (searchNote.isNotEmpty()) left.append(" · ").append(searchNote)
        statusLeft.text = left
        // Free space means something only where something can be written: "/" and /system say 0 B.
        statusRight.text = if ((place as? Place.Folder)?.dir?.can(Caps.CREATE) == true) freeText else ""
    }

    private fun updateSelectionBar() {
        val n = selection.size
        selectionBar.visibility = if (n > 0 && pick == null) View.VISIBLE else View.GONE
        selectionText.text = ctx.resources.getQuantityString(R.plurals.fm_selected, n, n)
    }

    // ---- taps, selection, menus ----

    private fun tapped(node: FsNode, v: View) {
        if (pick != null) {
            if (node.isDir) openFolder(node)
            return
        }
        if (selection.isNotEmpty()) {
            toggle(node)
            return
        }
        when {
            node.isDir -> openFolder(node)
            node.mime == LocalBackend.LINK_MIME -> followLink(node)
            FmText.isArchive(node.name) -> menu(v, archiveItemMenu(node))
            else -> openFile(node)
        }
    }

    /** Opens where a symbolic link leads: its folder, or its file. The link itself is never walked into by a job. */
    private fun followLink(node: FsNode) {
        val local = node.backend as? LocalBackend ?: return
        io.execute {
            val target = runCatching { local.linkTarget(node) }.getOrNull()
            val chain = target?.takeIf { it.isDir }?.let { chainFor(it) }
            main.post {
                if (destroyed) return@post
                when {
                    target == null -> toast(ctx.getString(R.string.fm_link_nowhere))
                    chain != null -> navigate(chain)
                    else -> openFile(target)
                }
            }
        }
    }

    private fun openFolder(node: FsNode) {
        val p = place
        if (p is Place.Folder) navigate(p.into(node)) else io.execute {
            val chain = chainFor(node)
            main.post { if (!destroyed) navigate(chain) }
        }
    }

    private fun pressed(node: FsNode, v: View, x: Float, y: Float) {
        if (pick != null) return
        if (node.key !in selection) {
            selection.clear()
            selection[node.key] = node
            render()
        }
        contextMenu(targets(), v, x, y)
    }

    private fun secondaryClick(list: AbsListView, ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN || !ev.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) return false
        val pos = list.pointToPosition(ev.x.toInt(), ev.y.toInt())
        if (pos == AbsListView.INVALID_POSITION) backgroundMenu(list, ev.x, ev.y)
        else pressed(entries[pos], list, ev.x, ev.y)
        return true
    }

    private fun toggle(node: FsNode) {
        if (selection.remove(node.key) == null) selection[node.key] = node
        render()
    }

    private fun selectAll() {
        entries.forEach { selection[it.key] = it }
        render()
    }

    private fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        render()
    }

    private fun targets(): List<FsNode> = selection.values.toList()

    private fun currentDir(): FsNode? = (place as? Place.Folder)?.dir

    private fun canWriteHere() = currentDir()?.can(Caps.CREATE) == true

    private fun newMenu() = MenuItem(ctx.getString(R.string.fm_new), R.drawable.ic_add, enabled = canWriteHere(), children = listOf(
        MenuItem(ctx.getString(R.string.files_new_folder), R.drawable.ic_new_folder, shortcut = "Ctrl+Shift+N") { newFolder() },
        MenuItem(ctx.getString(R.string.fm_new_text), R.drawable.ic_notes) { newTextFile() },
    ))

    private fun fileMenu(): List<MenuItem> {
        val sel = targets()
        val one = sel.singleOrNull()
        val files = sel.isNotEmpty() && sel.none { it.isDir }
        return listOf(
            newMenu(),
            MenuItem(ctx.getString(R.string.files_open), R.drawable.ic_file, enabled = one != null, groupStart = true) { one?.let { tapped(it, root) } },
            MenuItem(ctx.getString(R.string.fm_open_tedit_folder), com.lxlauncher.ui.theme.LxIcons.TEDIT, enabled = one?.isDir == true) { one?.let { openFolderInTedit(it) } },
            MenuItem(ctx.getString(R.string.fm_share), R.drawable.ic_share, enabled = files) { share(sel) },
            MenuItem(ctx.getString(R.string.action_rename), R.drawable.ic_edit, enabled = one?.can(Caps.RENAME) == true, shortcut = "F2") { one?.let { rename(it) } },
            MenuItem(ctx.getString(R.string.fm_copy_to), R.drawable.ic_copy, enabled = sel.isNotEmpty(), groupStart = true) { pickDestination(sel, move = false) },
            MenuItem(ctx.getString(R.string.fm_move_to), R.drawable.ic_cut, enabled = sel.isNotEmpty() && sel.all { it.can(Caps.DELETE) }) { pickDestination(sel, move = true) },
            MenuItem(ctx.getString(R.string.action_delete), R.drawable.ic_delete, enabled = sel.isNotEmpty() && sel.all { it.can(Caps.DELETE) },
                danger = true, groupStart = true, shortcut = "Del") { deleteTargets(sel) },
        )
    }

    private fun editMenu(): List<MenuItem> {
        val sel = targets()
        return listOf(
            MenuItem(ctx.getString(R.string.fm_select_all), R.drawable.ic_select_all, enabled = entries.isNotEmpty(), shortcut = "Ctrl+A") { selectAll() },
            MenuItem(ctx.getString(R.string.fm_select_none), enabled = sel.isNotEmpty()) { clearSelection() },
            MenuItem(ctx.getString(R.string.fm_cut), R.drawable.ic_cut, enabled = sel.isNotEmpty() && sel.all { it.can(Caps.DELETE) },
                groupStart = true, shortcut = "Ctrl+X") { clip(cut = true) },
            MenuItem(ctx.getString(R.string.fm_copy), R.drawable.ic_copy, enabled = sel.isNotEmpty(), shortcut = "Ctrl+C") { clip(cut = false) },
            MenuItem(ctx.getString(R.string.files_paste), R.drawable.ic_paste, enabled = FileClipboard.nodes.isNotEmpty() && canWriteHere(),
                shortcut = "Ctrl+V") { paste() },
            MenuItem(ctx.getString(R.string.fm_properties), R.drawable.ic_info, groupStart = true) {
                if (sel.isNotEmpty()) properties(sel) else currentDir()?.let { properties(listOf(it)) }
            },
        )
    }

    private fun viewMenu(): List<MenuItem> = listOf(
        MenuItem(ctx.getString(R.string.fm_view_grid), R.drawable.ic_view_grid, checked = !listMode) { setListMode(false) },
        MenuItem(ctx.getString(R.string.fm_view_list), R.drawable.ic_view_list, checked = listMode) { setListMode(true) },
        MenuItem(ctx.getString(R.string.files_sort), R.drawable.ic_sort, groupStart = true, children = listOf(
            MenuItem(ctx.getString(R.string.files_sort_name), checked = sortKey == SortKey.NAME) { setSort(SortKey.NAME) },
            MenuItem(ctx.getString(R.string.files_sort_date), checked = sortKey == SortKey.DATE) { setSort(SortKey.DATE) },
            MenuItem(ctx.getString(R.string.files_sort_size), checked = sortKey == SortKey.SIZE) { setSort(SortKey.SIZE) },
            MenuItem(ctx.getString(R.string.files_sort_type), checked = sortKey == SortKey.TYPE) { setSort(SortKey.TYPE) },
            MenuItem(ctx.getString(R.string.fm_sort_descending), checked = descending, groupStart = true) { setDescending(!descending) },
        )),
        MenuItem(ctx.getString(R.string.fm_show_hidden), checked = showHidden, shortcut = "Ctrl+H") { setHidden(!showHidden) },
        MenuItem(ctx.getString(R.string.action_refresh), R.drawable.ic_refresh, groupStart = true, shortcut = "F5") { refresh() },
    )

    private fun archiveMenu(): List<MenuItem> {
        val sel = targets()
        val archive = sel.singleOrNull()?.takeIf { !it.isDir && FmText.isArchive(it.name) }
        return listOf(
            compressMenu(sel),
            MenuItem(ctx.getString(R.string.fm_extract_here), R.drawable.ic_unarchive, enabled = archive != null && canWriteHere(), groupStart = true) {
                archive?.let { extractHere(it) }
            },
            MenuItem(ctx.getString(R.string.fm_extract_to), R.drawable.ic_unarchive, enabled = archive != null) { archive?.let { extractTo(it) } },
            MenuItem(ctx.getString(R.string.fm_test_archive), R.drawable.ic_check, enabled = archive != null, groupStart = true) { archive?.let { testArchive(it, show = true) } },
            MenuItem(ctx.getString(R.string.fm_archive_properties), R.drawable.ic_info, enabled = archive != null) { archive?.let { testArchive(it, show = true) } },
        )
    }

    private fun compressMenu(sel: List<FsNode>) = MenuItem(ctx.getString(R.string.fm_compress), R.drawable.ic_archive,
        enabled = sel.isNotEmpty() && canWriteHere(), children = ArchiveFormat.entries.map { f ->
            MenuItem(formatLabel(f)) { compress(sel, f) }
        })

    private fun formatLabel(f: ArchiveFormat) = when (f) {
        ArchiveFormat.ZIP -> "ZIP"
        ArchiveFormat.TAR -> "TAR"
        ArchiveFormat.TAR_GZ -> "TAR.GZ"
    }

    private fun showAllMenus(anchor: View) {
        menu(anchor, listOf(
            MenuItem(ctx.getString(R.string.fm_menu_file), children = fileMenu()),
            MenuItem(ctx.getString(R.string.fm_menu_edit), children = editMenu()),
            MenuItem(ctx.getString(R.string.fm_menu_view), children = viewMenu()),
            MenuItem(ctx.getString(R.string.fm_menu_archive), children = archiveMenu()),
        ), anchor.width.toFloat(), anchor.height.toFloat())
    }

    private fun contextMenu(sel: List<FsNode>, anchor: View, x: Float, y: Float) {
        if (sel.isEmpty()) return
        val one = sel.singleOrNull()
        val archive = one?.takeIf { !it.isDir && FmText.isArchive(it.name) }
        val items = ArrayList<MenuItem>()
        if (one != null) items += MenuItem(ctx.getString(R.string.files_open), R.drawable.ic_file) {
            if (archive != null) openFile(archive) else tapped(one, anchor)
        }
        if (one != null && one.isDir) items += MenuItem(ctx.getString(R.string.fm_open_tedit_folder), com.lxlauncher.ui.theme.LxIcons.TEDIT) { openFolderInTedit(one) }
        if (sel.none { it.isDir }) items += MenuItem(ctx.getString(R.string.fm_share), R.drawable.ic_share) { share(sel) }
        items += MenuItem(ctx.getString(R.string.fm_cut), R.drawable.ic_cut, enabled = sel.all { it.can(Caps.DELETE) }, groupStart = true) { clip(cut = true) }
        items += MenuItem(ctx.getString(R.string.fm_copy), R.drawable.ic_copy) { clip(cut = false) }
        items += MenuItem(ctx.getString(R.string.fm_copy_to), R.drawable.ic_copy) { pickDestination(sel, move = false) }
        items += MenuItem(ctx.getString(R.string.fm_move_to), R.drawable.ic_cut, enabled = sel.all { it.can(Caps.DELETE) }) { pickDestination(sel, move = true) }
        if (one != null) items += MenuItem(ctx.getString(R.string.action_rename), R.drawable.ic_edit, enabled = one.can(Caps.RENAME), groupStart = true) { rename(one) }
        items += compressMenu(sel).copy(groupStart = one == null)
        if (archive != null) items += MenuItem(ctx.getString(R.string.fm_extract), R.drawable.ic_unarchive, children = archiveItemMenu(archive))
        items += MenuItem(ctx.getString(R.string.action_delete), R.drawable.ic_delete, enabled = sel.all { it.can(Caps.DELETE) }, danger = true, groupStart = true) {
            deleteTargets(sel)
        }
        items += MenuItem(ctx.getString(R.string.fm_copy_path), R.drawable.ic_copy) { copyPath(sel) }
        items += MenuItem(ctx.getString(R.string.fm_properties), R.drawable.ic_info) { properties(sel) }
        menu(anchor, items, x, y)
    }

    private fun archiveItemMenu(archive: FsNode) = listOf(
        MenuItem(ctx.getString(R.string.fm_extract_here), R.drawable.ic_unarchive, enabled = canWriteHere()) { extractHere(archive) },
        MenuItem(ctx.getString(R.string.fm_extract_to), R.drawable.ic_unarchive) { extractTo(archive) },
        MenuItem(ctx.getString(R.string.fm_test_archive), R.drawable.ic_check, groupStart = true) { testArchive(archive, show = true) },
        MenuItem(ctx.getString(R.string.fm_open_in_tedit), com.lxlauncher.ui.theme.LxIcons.TEDIT) { openFile(archive) },
    )

    private fun backgroundMenu(anchor: View, x: Float, y: Float) {
        if (place !is Place.Folder || pick != null) return
        menu(anchor, listOf(
            newMenu(),
            MenuItem(ctx.getString(R.string.files_paste), R.drawable.ic_paste, enabled = FileClipboard.nodes.isNotEmpty() && canWriteHere()) { paste() },
            MenuItem(ctx.getString(R.string.fm_select_all), R.drawable.ic_select_all, enabled = entries.isNotEmpty()) { selectAll() },
            MenuItem(ctx.getString(R.string.fm_menu_view), R.drawable.ic_view_grid, groupStart = true, children = viewMenu()),
            MenuItem(ctx.getString(R.string.fm_copy_path), R.drawable.ic_copy) { currentDir()?.let { copyPath(listOf(it)) } },
            MenuItem(ctx.getString(R.string.fm_properties), R.drawable.ic_info) { currentDir()?.let { properties(listOf(it)) } },
        ), x, y)
    }

    private fun setSort(key: SortKey) {
        sortKey = key
        prefs.edit().putString("sortKey", key.name).apply()
        applyFilter()
    }

    private fun setDescending(on: Boolean) {
        descending = on
        prefs.edit().putBoolean("sortDesc", on).apply()
        applyFilter()
    }

    private fun setHidden(on: Boolean) {
        showHidden = on
        prefs.edit().putBoolean("hidden", on).apply()
        applyFilter()
    }

    private fun shortcut(e: KeyEvent): Boolean {
        if (root.findFocus() is EditText) return false
        val ctrl = e.isCtrlPressed
        return when {
            ctrl && e.isShiftPressed && e.keyCode == KeyEvent.KEYCODE_N -> { if (canWriteHere()) newFolder(); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_A -> { selectAll(); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_C -> { if (selection.isNotEmpty()) clip(cut = false); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_X -> { if (selection.isNotEmpty()) clip(cut = true); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_V -> { paste(); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_H -> { setHidden(!showHidden); true }
            ctrl && e.keyCode == KeyEvent.KEYCODE_F -> { startSearch(); true }
            e.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> { if (selection.isNotEmpty()) deleteTargets(targets()); true }
            e.keyCode == KeyEvent.KEYCODE_F2 -> { targets().singleOrNull()?.let { rename(it) }; true }
            e.keyCode == KeyEvent.KEYCODE_F5 -> { refresh(); true }
            e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { goBack(); true }
            e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { goForward(); true }
            e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { goUp(); true }
            e.keyCode == KeyEvent.KEYCODE_DEL -> goUp()
            else -> false
        }
    }

    // ---- actions ----

    private fun toast(message: CharSequence) = shell.notify(message)

    /** Copies the display path of [nodes] to the clipboard; the backend path lookup can block, so it runs off the main thread. */
    private fun copyPath(nodes: List<FsNode>) {
        if (nodes.isEmpty()) return
        io.execute {
            val text = nodes.joinToString("\n") { runCatching { it.backend.displayPath(it) }.getOrNull() ?: it.name }
            main.post {
                if (destroyed) return@post
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("path", text))
                toast(ctx.getString(R.string.fm_path_copied))
            }
        }
    }

    private fun fat(dir: FsNode) = runCatching { dir.backend.fatNames(dir) }.getOrDefault(true)

    private fun validateName(dir: FsNode, name: String, fat: Boolean, except: FsNode? = null): CharSequence? {
        val trimmed = name.trim()
        FmText.nameProblem(ctx, NativeArchive.checkName(trimmed, fat), fat)?.let { return it }
        if (trimmed != name && fat) return ctx.getString(R.string.fm_name_reserved_fat)
        val taken = entries.any { it.name.equals(trimmed, ignoreCase = fat) && it.key != except?.key }
        return if (taken) ctx.getString(R.string.fm_err_exists) else null
    }

    private fun newFolder() {
        val dir = currentDir() ?: return
        val isFat = fat(dir)
        sheets.prompt(ctx.getString(R.string.files_new_folder), ctx.getString(R.string.fm_new_folder_name), ctx.getString(R.string.files_name_hint),
            ctx.getString(R.string.fm_create), validate = { validateName(dir, it, isFat) }) { name ->
            background({ dir.backend.createDir(dir, name.trim()) }) { created ->
                load(keep = true)
                toast(ctx.getString(R.string.fm_created, created.name))
            }
        }
    }

    private fun newTextFile() {
        val dir = currentDir() ?: return
        val isFat = fat(dir)
        sheets.prompt(ctx.getString(R.string.fm_new_text), ctx.getString(R.string.fm_new_text_name), ctx.getString(R.string.files_name_hint),
            ctx.getString(R.string.fm_create), selectBaseName = true, validate = { validateName(dir, it, isFat) }) { name ->
            background({ dir.backend.createFile(dir, name.trim(), "text/plain") }) { created ->
                load(keep = true)
                shell.notify(ctx.getString(R.string.fm_created, created.name), ctx.getString(R.string.files_open)) { openFile(created) }
            }
        }
    }

    private fun rename(node: FsNode) {
        val parent = currentDir() ?: return
        val isFat = fat(parent)
        sheets.prompt(ctx.getString(R.string.action_rename), node.name, ctx.getString(R.string.files_name_hint), ctx.getString(R.string.action_rename),
            selectBaseName = !node.isDir, validate = { if (it.trim() == node.name) null else validateName(parent, it, isFat, except = node) }) { name ->
            if (name.trim() == node.name) return@prompt
            background({ node.backend.rename(node, name.trim()) }) { renamed ->
                load(keep = true)
                toast(ctx.getString(R.string.files_renamed, renamed.name))
            }
        }
    }

    /** Runs one short backend call off the main thread and reports a failure in plain words. */
    private fun <T> background(work: () -> T, onDone: (T) -> Unit) {
        io.execute {
            val result = runCatching(work)
            main.post {
                if (destroyed) return@post
                result.fold(onDone) { toast(FmText.error(ctx, it)) }
            }
        }
    }

    private fun clip(cut: Boolean) {
        val sel = targets()
        if (sel.isEmpty()) return
        FileClipboard.set(sel, cut)
        clearSelection()
        toast(ctx.resources.getQuantityString(if (cut) R.plurals.fm_clip_cut else R.plurals.fm_clip_copied, sel.size, sel.size))
    }

    private fun paste() {
        val dir = currentDir() ?: return
        val nodes = FileClipboard.nodes
        if (nodes.isEmpty() || !dir.can(Caps.CREATE)) return
        val move = FileClipboard.move
        jobs.copy(nodes, dir, move, resolver)
        if (move) FileClipboard.clear()
    }

    private fun deleteTargets(nodes: List<FsNode>) {
        if (nodes.isEmpty()) return
        val one = nodes.singleOrNull()
        val title = if (one != null) ctx.getString(R.string.fm_delete_one_title, one.name) else ctx.getString(R.string.files_delete_title)
        val message = if (one != null) ctx.getString(if (one.isDir) R.string.files_delete_folder_message else R.string.files_delete_message)
        else ctx.resources.getQuantityString(R.plurals.fm_delete_many, nodes.size, nodes.size)
        sheets.confirm(title, message, ctx.getString(R.string.action_delete), danger = true) {
            jobs.delete(nodes)
            clearSelection()
        }
    }

    /**
     * Every file opens in Tedit, LX's editor: text to edit, images to view, anything else with its details. No other
     * app is offered; sharing a file is still there for sending it on.
     */
    private fun openFile(node: FsNode) {
        shell.openTool(com.lxlauncher.tedit.TeditTool.KIND, com.lxlauncher.tedit.TeditTool.openExtra(node))
    }

    /** Opens [dir] as Tedit's workspace folder: its tree in Tedit's Explorer, its files in search and source control. */
    private fun openFolderInTedit(dir: FsNode) {
        shell.openTool(com.lxlauncher.tedit.TeditTool.KIND, com.lxlauncher.tedit.TeditTool.folderExtra(dir))
    }

    private fun share(nodes: List<FsNode>) {
        val files = nodes.filter { !it.isDir }
        if (files.isEmpty()) return
        val uris = files.mapNotNull { runCatching { it.backend.shareUri(it) }.getOrNull() }
        if (uris.isEmpty()) return
        val type = files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        send.type = type
        send.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!shell.startSafely(Intent.createChooser(send, ctx.getString(R.string.fm_share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            toast(ctx.getString(R.string.files_no_viewer))
    }

    private fun pickDestination(nodes: List<FsNode>, move: Boolean) {
        if (nodes.isEmpty()) return
        val label = ctx.resources.getQuantityString(if (move) R.plurals.fm_pick_move else R.plurals.fm_pick_copy, nodes.size, nodes.size)
        startPick(label) { dest -> jobs.copy(nodes, dest, move, resolver) }
    }

    private fun startPick(label: CharSequence, onPick: (FsNode) -> Unit) {
        pick = Pick(label, onPick)
        clearSelection()
        pickText.text = label
        pickBanner.visibility = View.VISIBLE
        render()
    }

    private fun endPick() {
        pick = null
        pickBanner.visibility = View.GONE
        render()
    }

    private fun confirmPick() {
        val p = pick ?: return
        val dir = currentDir()
        if (dir == null || !dir.can(Caps.CREATE)) {
            toast(ctx.getString(R.string.fm_pick_not_writable))
            return
        }
        endPick()
        p.onPick(dir)
    }

    private fun compress(sources: List<FsNode>, format: ArchiveFormat) {
        val dir = currentDir() ?: return
        if (sources.isEmpty()) return
        val base = sources.singleOrNull()?.let { if (it.isDir) it.name else it.name.substringBeforeLast('.', it.name) }
            ?: ctx.getString(R.string.fm_archive_default_name)
        val isFat = fat(dir)
        val suggested = "$base.${format.extension}"
        sheets.prompt(ctx.getString(R.string.fm_compress_title, formatLabel(format)), suggested, ctx.getString(R.string.files_name_hint),
            ctx.getString(R.string.fm_compress), selectBaseName = true,
            // A taken name is fine here: the job asks whether to replace it or keep both.
            validate = { name -> FmText.nameProblem(ctx, NativeArchive.checkName(name.trim(), isFat), isFat) }) { name ->
            jobs.compress(sources, dir, name.trim(), format, resolver)
            clearSelection()
        }
    }

    private fun extractHere(archive: FsNode) {
        val dir = currentDir() ?: return
        jobs.extract(archive, dir, ownFolder = true, resolver = resolver)
    }

    private fun extractTo(archive: FsNode) {
        startPick(ctx.getString(R.string.fm_pick_extract, archive.name)) { dest ->
            jobs.extract(archive, dest, ownFolder = true, resolver = resolver)
        }
    }

    private fun testArchive(archive: FsNode, show: Boolean) {
        val id = jobs.test(archive)
        if (show) testCallbacks[id] = { r -> archiveReport(archive, r) }
    }

    private fun archiveReport(archive: FsNode, r: JobResult) {
        val info = r.archive
        val lines = ArrayList<String>()
        if (info != null) {
            lines += ctx.getString(R.string.fm_archive_format, formatLabel(info.format))
            lines += ctx.resources.getQuantityString(R.plurals.fm_archive_entries, info.entries, info.entries, info.files, info.folders)
            lines += ctx.getString(R.string.fm_archive_sizes, FmText.size(ctx, info.totalBytes), FmText.size(ctx, archive.size.coerceAtLeast(info.compressedBytes)))
            if (info.unsafe.isNotEmpty()) lines += ctx.resources.getQuantityString(R.plurals.fm_archive_unsafe, info.unsafe.size, info.unsafe.size) +
                "\n" + info.unsafe.take(8).joinToString("\n") { "· $it" }
        }
        lines += when {
            r.ok -> ctx.getString(R.string.fm_archive_ok)
            r.cancelled -> ctx.getString(R.string.fm_err_cancelled)
            else -> ctx.getString(R.string.fm_archive_bad, FmText.error(ctx, r.error))
        }
        sheets.message(archive.name, lines.joinToString("\n"))
    }

    private fun properties(nodes: List<FsNode>) {
        if (nodes.isEmpty()) return
        val t = theme
        val body = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
        fun line(label: Int, value: String): TextView {
            val v = Ui.text(ctx, t, value, t.textBody, t.textPrimary, lines = 4)
            body.addView(Ui.text(ctx, t, ctx.getString(label), t.textLabel, t.textMuted), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(8f) })
            body.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP))
            return v
        }
        val one = nodes.singleOrNull()
        val sizeView: TextView
        if (one != null) {
            line(R.string.fm_prop_type, if (one.isDir) ctx.getString(R.string.files_folder) else one.mime.ifEmpty { "application/octet-stream" })
            val where = line(R.string.fm_prop_location, "")
            io.execute {
                val text = runCatching { one.backend.parent(one)?.let { it.backend.displayPath(it) } ?: one.backend.displayPath(one) }.getOrNull()
                main.post { if (!destroyed) where.text = text.orEmpty() }
            }
            sizeView = line(R.string.fm_prop_size, if (one.isDir) ctx.getString(R.string.fm_counting) else FmText.size(ctx, one.size))
            if (one.modified > 0) line(R.string.fm_prop_modified, FmText.date(one.modified))
            line(R.string.fm_prop_access, ctx.getString(if (one.can(Caps.WRITE) || one.can(Caps.CREATE)) R.string.fm_access_rw else R.string.fm_access_ro))
            if (!one.isDir && one.mime.startsWith("image/")) preview(one, body)
            if (!one.isDir && (one.mime.startsWith("text/") || one.size in 1..2048 && one.mime == "application/octet-stream")) textPreview(one, body)
        } else {
            sizeView = line(R.string.fm_prop_size, ctx.getString(R.string.fm_counting))
        }
        // A walk still counting for an earlier sheet stops; this one stops when its sheet closes or the folder changes.
        walkCancel?.cancel()
        val walk = Cancel()
        walkCancel = walk
        sheets.show(one?.name ?: ctx.resources.getQuantityString(R.plurals.fm_items, nodes.size, nodes.size), body,
            listOf(FmSheets.Button(ctx.getString(R.string.action_close), primary = true)), onDismiss = { walk.cancel() })
        if (one == null || one.isDir) walker.execute {
            var bytes = 0L
            var files = 0
            var folders = 0
            val stack = ArrayDeque(nodes)
            val roots = nodes.mapTo(HashSet()) { it.key }
            var visited = 0
            while (stack.isNotEmpty() && visited < PROPERTY_WALK_LIMIT && !destroyed && !walk.cancelled) {
                val n = stack.removeFirst()
                visited++
                if (n.isDir) {
                    if (n.key !in roots) folders++
                    runCatching { n.backend.children(n, walk) }.getOrNull()?.let { stack.addAll(it) }
                } else {
                    files++
                    bytes += n.size.coerceAtLeast(0)
                }
            }
            if (walk.cancelled) return@execute
            val partial = visited >= PROPERTY_WALK_LIMIT
            main.post {
                if (destroyed || walk.cancelled) return@post
                val text = ctx.getString(R.string.fm_prop_size_value, FmText.size(ctx, bytes),
                    ctx.resources.getQuantityString(R.plurals.fm_files, files, files), ctx.resources.getQuantityString(R.plurals.fm_folders, folders, folders))
                sizeView.text = if (partial) ctx.getString(R.string.fm_prop_partial, text) else text
            }
        }
    }

    private fun preview(node: FsNode, body: LinearLayout) {
        val t = theme
        val image = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            contentDescription = ctx.getString(R.string.fm_preview)
        }
        body.addView(image, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(12f) })
        io.execute {
            val bmp = runCatching {
                node.backend.openRead(node).use { pfd ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                    val sample = BitmapSample.sampleFor(bounds.outWidth, bounds.outHeight, PREVIEW_PX, 1,
                        PREVIEW_MAX_PIXELS, PREVIEW_MAX_SIDE)
                    android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                    android.graphics.BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null,
                        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }.getOrNull()
            main.post { if (!destroyed && bmp != null) image.setImageBitmap(bmp) }
        }
    }

    private fun textPreview(node: FsNode, body: LinearLayout) {
        val t = theme
        val view = Ui.text(ctx, t, "", t.textLabel, t.textMuted, lines = 12).apply {
            setPadding(t.dpi(8f), t.dpi(8f), t.dpi(8f), t.dpi(8f))
            setBackgroundColor(LxTheme.withAlpha(t.textMuted, 0.08f))
        }
        body.addView(view, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(12f) })
        io.execute {
            val text = runCatching {
                node.backend.openRead(node).use { pfd ->
                    java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                        val buf = ByteArray(TEXT_PREVIEW_BYTES)
                        val n = input.read(buf).coerceAtLeast(0)
                        String(buf, 0, n, Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            main.post { if (!destroyed && text != null) view.text = text }
        }
    }

    // ---- search ----

    /** Search from the launcher ("Search files for ...") while this window is already open. */
    fun search(query: String) {
        if (place == null) return
        startSearch()
        searchField.setText(query)
        runSearch(query)
    }

    private fun startSearch() {
        if (place == null) return
        pathScroll.visibility = View.GONE
        searchRow.visibility = View.VISIBLE
        searchField.requestFocus()
        ctx.getSystemService(InputMethodManager::class.java).showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun runSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val from = when (val p = place) {
            is Place.Folder -> p
            is Place.Search -> p.from
            is Place.Archives -> p.from
            else -> null
        }
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
        if (from != null) return navigate(Place.Search(from, q))
        // From the start page, the cleaner or a kind of files: the search covers internal storage.
        io.execute {
            val top = storage.volumes().firstOrNull { it.kind == VolumeKind.INTERNAL && it.path != null }
                ?.let { v -> storage.localNode(v.path!!) }
            main.post {
                if (destroyed) return@post
                if (top != null) navigate(Place.Search(Place.Folder(listOf(top)), q)) else toast(ctx.getString(R.string.fm_err_no_access))
            }
        }
    }

    private fun endSearch(): Boolean {
        if (searchRow.visibility != View.VISIBLE) return false
        searchRow.visibility = View.GONE
        pathScroll.visibility = View.VISIBLE
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
        (place as? Place.Search)?.let { navigate(it.from) }
        return true
    }

    // ---- access ----

    private fun explainAllFiles() {
        sheets.show(ctx.getString(R.string.fm_allow_all_title), sheets.bodyText(ctx.getString(R.string.fm_allow_all_body)), listOf(
            FmSheets.Button(ctx.getString(R.string.fm_add_folder)) { addFolder() },
            FmSheets.Button(ctx.getString(R.string.fm_allow_all_open), primary = true) { requestAllFiles() },
        ))
    }

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            if (!shell.startSafely(own)) shell.startSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            shell.activity.requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE)
        }
    }

    private fun addFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        shell.requestDocument(intent) { uri -> uri?.let { takeTree(it) } }
    }

    private fun grantVolume(v: Volume) {
        if (Build.VERSION.SDK_INT < 29) return explainAllFiles()
        val sm = ctx.getSystemService(StorageManager::class.java)
        val sv = sm.storageVolumes.firstOrNull { (it.uuid ?: "primary") == v.id } ?: return explainAllFiles()
        shell.requestDocument(sv.createOpenDocumentTreeIntent()) { uri -> uri?.let { takeTree(it) } }
    }

    private fun takeTree(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            toast(ctx.getString(R.string.files_grant_failed))
            return
        }
        loadSidebar()
        openTree(uri)
    }

    private fun forgetTreeMenu(uri: Uri, label: String, anchor: View) {
        menu(anchor, listOf(MenuItem(ctx.getString(R.string.files_forget), R.drawable.ic_close, danger = true) {
            sheets.confirm(ctx.getString(R.string.files_forget), ctx.getString(R.string.fm_forget_message, label), ctx.getString(R.string.files_forget), danger = true) {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                if ((place as? Place.Folder)?.dir?.backend?.key == "saf:$uri") openStart()
                loadSidebar()
            }
        }))
    }

    // ---- jobs ----

    private fun askConflict(c: Conflict, onAnswer: (ConflictChoice, Boolean) -> Unit): () -> Unit {
        val t = theme
        val body = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
        fun describe(isDir: Boolean, size: Long, modified: Long) = listOfNotNull(
            if (isDir) ctx.getString(R.string.files_folder) else FmText.size(ctx, size).ifEmpty { null },
            FmText.date(modified).ifEmpty { null },
        ).joinToString(" · ")
        body.addView(sheets.bodyText(ctx.getString(R.string.fm_conflict_existing, describe(c.existingIsDir, c.existingSize, c.existingModified))))
        body.addView(sheets.bodyText(ctx.getString(R.string.fm_conflict_incoming, describe(c.incomingIsDir, c.incomingSize, c.incomingModified))),
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(4f) })
        val all = CheckBox(ctx).apply {
            text = ctx.getString(R.string.fm_conflict_all)
            setTextColor(t.textPrimary)
            typeface = t.font
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textBody)
            buttonTintList = ColorStateList.valueOf(t.accent)
            minHeight = t.touchTarget
        }
        body.addView(all, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(8f) })
        val merge = c.existingIsDir && c.incomingIsDir
        val sheet = sheets.show(ctx.getString(R.string.fm_conflict_title, c.name), body, listOf(
            FmSheets.Button(ctx.getString(if (merge) R.string.fm_conflict_merge else R.string.fm_conflict_replace), primary = true) {
                onAnswer(ConflictChoice.REPLACE, all.isChecked)
            },
            FmSheets.Button(ctx.getString(R.string.fm_conflict_keep_both)) { onAnswer(ConflictChoice.KEEP_BOTH, all.isChecked) },
            FmSheets.Button(ctx.getString(R.string.fm_conflict_skip)) { onAnswer(ConflictChoice.SKIP, all.isChecked) },
            FmSheets.Button(ctx.getString(R.string.fm_conflict_stop), danger = true) { onAnswer(ConflictChoice.CANCEL, false) },
        ), cancelable = false)
        return { sheets.dismiss(sheet) }
    }

    private fun showProgress(p: JobProgress) {
        lastProgress = p
        jobRow.visibility = View.VISIBLE
        val verb = ctx.getString(jobVerb(p.kind))
        val count = if (p.totalItems > 0) " · ${p.doneItems}/${p.totalItems}" else ""
        if (p.totalBytes > 0) {
            jobBar.isIndeterminate = false
            jobBar.progress = (p.doneBytes * 1000 / p.totalBytes).toInt().coerceIn(0, 1000)
            jobText.text = "$verb$count · ${(p.doneBytes * 100 / p.totalBytes).coerceIn(0, 100)}%"
        } else {
            jobBar.isIndeterminate = true
            jobText.text = "$verb$count"
        }
        jobRow.contentDescription = jobText.text
    }

    private fun jobVerb(kind: JobKind) = when (kind) {
        JobKind.COPY -> R.string.fm_job_copy
        JobKind.MOVE -> R.string.fm_job_move
        JobKind.DELETE -> R.string.fm_job_delete
        JobKind.COMPRESS -> R.string.fm_job_compress
        JobKind.EXTRACT -> R.string.fm_job_extract
        JobKind.TEST -> R.string.fm_job_test
    }

    private fun finished(r: JobResult) {
        if (lastProgress?.id == r.id) {
            val other = jobs.running().lastOrNull { it.id != r.id }
            if (other != null) showProgress(other) else {
                lastProgress = null
                jobRow.visibility = View.GONE
            }
        }
        testCallbacks.remove(r.id)?.let {
            it(r)
            return
        }
        if (r.kind != JobKind.TEST && place is Place.Folder) load(keep = true)
        val message = when {
            r.cancelled -> ctx.getString(R.string.fm_err_cancelled)
            !r.ok -> ctx.getString(R.string.fm_job_failed, FmText.error(ctx, r.error))
            else -> when (r.kind) {
                JobKind.COPY -> ctx.resources.getQuantityString(R.plurals.fm_done_copy, r.done, r.done)
                JobKind.MOVE -> ctx.resources.getQuantityString(R.plurals.fm_done_move, r.done, r.done)
                JobKind.DELETE -> ctx.resources.getQuantityString(R.plurals.fm_done_delete, r.done, r.done)
                JobKind.COMPRESS -> ctx.getString(R.string.fm_done_compress, r.created.firstOrNull()?.name.orEmpty())
                JobKind.EXTRACT -> ctx.getString(R.string.fm_done_extract, r.created.firstOrNull()?.name.orEmpty())
                JobKind.TEST -> ctx.getString(R.string.fm_archive_ok)
            }
        }
        val unsafe = r.archive?.unsafe?.size ?: 0
        val extra = if (r.kind == JobKind.EXTRACT && unsafe > 0) " " + ctx.resources.getQuantityString(R.plurals.fm_archive_unsafe, unsafe, unsafe) else ""
        val skipped = if (r.skipped > unsafe) " " + ctx.resources.getQuantityString(R.plurals.fm_skipped, r.skipped - unsafe, r.skipped - unsafe) else ""
        toast(message + extra + skipped)
    }

    // ---- ToolView ----

    override fun onShown() {
        root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
    }

    override fun onHidden() {
        root.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
    }

    override fun onBack(): Boolean = when {
        sheets.back() -> true
        closeDrawer() -> true
        pick != null -> {
            endPick()
            true
        }
        endSearch() -> true
        selection.isNotEmpty() -> {
            clearSelection()
            true
        }
        history.isNotEmpty() -> {
            goBack()
            true
        }
        else -> goUp()
    }

    override fun onDestroy() {
        destroyed = true
        embed.removeListener(embedListener)
        homePage?.release()
        cleanerPage?.release()
        loadCancel?.cancel()
        walkCancel?.cancel()
        root.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
        jobs.removeListener(jobListener)
        storage.removeVolumeListener(volumeListener)
        // A question this window showed waits for the next File Explorer window; the job keeps waiting.
        ConflictDesk.detach(asker)
        sheets.dismissAll()
        adapter.release()
        io.shutdownNow()
        sorter.shutdownNow()
        walker.shutdownNow()
    }

    private companion object {
        /** Below this content height (dp) a window counts as short; see [shortWindow]. */
        private const val SHORT_DP = 300f
        const val RECENT_LIMIT = 200
        const val CATEGORY_LIMIT = 500
        const val SEARCH_VISITED = 20000
        const val SEARCH_HITS = 1000
        const val PROPERTY_WALK_LIMIT = 50000
        const val PREVIEW_PX = 640
        /** Up to this many entries a listing is sorted on the main thread. */
        const val SORT_ON_MAIN_MAX = 300
        /** A preview never needs more than this, whatever the shape of the image. */
        const val PREVIEW_MAX_PIXELS = 4L * 1024 * 1024
        const val PREVIEW_MAX_SIDE = 4096
        const val TEXT_PREVIEW_BYTES = 2048
        const val REQUEST_STORAGE = 4107
        /** How far the sidebar card and the path field step up from the window's body, in text colour over panel. */
        const val GRID_COLUMN_DP = 62f
        const val BUTTON_DP = 38f
        const val FIELD_DP = 34f
        const val ROW_DP = 40f
        const val DRAWER_DP = 224f
        const val CARD_TINT = 0.06f
        const val FIELD_TINT = 0.08f
    }
}
