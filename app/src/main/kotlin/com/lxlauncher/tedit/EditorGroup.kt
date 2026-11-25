// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.ui.BitmapSample
import kotlin.math.abs

/** An open file in one editor group: the document and this group's view of it. */
class Tab(val doc: Document, val session: EditorSession) {
    var pinned = false
}

/**
 * One column (or row, when split) of the editor area: tabs, the path of the active file, the find bar, and the
 * editor or a preview. Two groups can show one document, each with its own cursor and scroll.
 */
class EditorGroup(val tool: TeditTool) {
    val tabs = ArrayList<Tab>()
    var active: Tab? = null
        private set
    val find = FindState()
    private val ctx: Context get() = tool.ctx
    private val ui get() = tool.ui
    private val t get() = tool.theme

    lateinit var root: LinearLayout
        private set
    private lateinit var strip: TabStrip
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    lateinit var findBar: FindBar
        private set
    private lateinit var banner: LinearLayout
    private lateinit var content: FrameLayout
    lateinit var editor: CodeEditView
        private set
    private var boundDoc: Document? = null
    private val docListener: () -> Unit = { onDocChanged() }
    /** The document on disk changed while it had unsaved edits here. */
    val changedOnDisk = HashSet<String>()

    fun build(): View {
        root = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        root.setBackgroundColor(t.bg)
        strip = TabStrip(ctx, this)
        stripScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            setBackgroundColor(t.panel)
            addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.tabH))
        }
        root.addView(stripScroll, ui.lp(TeditTool.MATCH, t.tabH))
        // The row narrows when the side panel takes its width; keep the active tab in view when that happens.
        stripScroll.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) active?.let { a -> stripScroll.post { scrollToTab(a) } } }
        root.addView(ui.divider())
        crumbs = ui.row()
        crumbScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(crumbs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dpi(24f)))
        }
        root.addView(crumbScroll, ui.lp(TeditTool.MATCH, t.dpi(24f)))
        findBar = FindBar(tool, this)
        root.addView(findBar.build(), ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        banner = ui.row().apply { visibility = View.GONE; setBackgroundColor(t.active); setPadding(t.dpi(12f), t.dpi(4f), t.dpi(4f), t.dpi(4f)) }
        root.addView(banner, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        content = FrameLayout(ctx)
        editor = CodeEditView(ctx, t, tool.editorConfig, tool.editorHost)
        editor.find = find
        // A large file is searched on a worker; its matches arrive here.
        find.onResult = { findBar.resultsArrived(); editor.invalidate() }
        root.addView(content, ui.lp(TeditTool.MATCH, 0, 1f))
        root.setOnClickListener { tool.activateGroup(this) }
        refreshTabs()
        showContent()
        return root
    }

    fun indexOf(doc: Document) = tabs.indexOfFirst { it.doc === doc }

    fun isBuilt() = ::root.isInitialized

    private var compact = false

    /** A short window drops the path bar. */
    fun setCompact(on: Boolean) {
        compact = on
        if (::crumbScroll.isInitialized) crumbScroll.visibility = if (on || active == null) View.GONE else View.VISIBLE
    }

    fun refreshTabsLabels() {
        if (::strip.isInitialized) strip.refreshLabels()
    }

    fun add(doc: Document, activate: Boolean, at: Int = -1): Tab {
        tabs.firstOrNull { it.doc === doc }?.let { if (activate) select(it); return it }
        val tab = Tab(doc, EditorSession(doc))
        val pos = if (at in 0..tabs.size) at else (tabs.indexOf(active) + 1).coerceIn(0, tabs.size).let { p ->
            // New tabs never go between pinned ones.
            maxOf(p, tabs.count { it.pinned })
        }
        tabs.add(pos, tab)
        if (activate || active == null) select(tab) else refreshTabs()
        return tab
    }

    fun select(tab: Tab?) {
        if (tab != null && tab !in tabs) return
        if (active === tab) { refreshTabs(); showContent(); return }
        active = tab
        refreshTabs()
        showContent()
        tool.groupTabChanged(this)
    }

    fun remove(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        if (active === tab) {
            active = null
            select(tabs.getOrNull(minOf(i, tabs.size - 1)))
        } else refreshTabs()
        if (tabs.isEmpty()) { active = null; showContent(); tool.groupTabChanged(this) }
    }

    fun move(tab: Tab, to: Int) {
        val from = tabs.indexOf(tab)
        if (from < 0) return
        tabs.removeAt(from)
        tabs.add(to.coerceIn(0, tabs.size), tab)
        refreshTabs()
    }

    fun refreshTabs() {
        if (!::strip.isInitialized) return
        strip.rebuild()
        active?.let { a -> revealTab(a) }
        refreshCrumbs()
    }

    /**
     * Scrolls the tab row so [tab] is in view. It waits for a layout pass in which both the row and the tab have a
     * width: right after a rebuild, or in a group just created, neither has one yet.
     */
    private fun revealTab(tab: Tab) {
        val obs = stripScroll.viewTreeObserver
        var tries = 0
        val listener = object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val v = strip.viewFor(tab)
                if (v == null || tries++ > 8) { stripScroll.viewTreeObserver.removeOnGlobalLayoutListener(this); return }
                if (v.width == 0 || stripScroll.width == 0) return
                stripScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                // After this pass focus may still land on a tab and pull the row back to it; scroll once that settled.
                stripScroll.postDelayed({ scrollToTab(tab) }, 120)
            }
        }
        obs.addOnGlobalLayoutListener(listener)
        stripScroll.requestLayout()
    }

    private fun scrollToTab(tab: Tab) {
        val tv = strip.viewFor(tab) ?: return
        val x = stripScroll.scrollX
        val w = stripScroll.width
        if (tv.left < x) stripScroll.smoothScrollTo(tv.left, 0) else if (tv.right > x + w) stripScroll.smoothScrollTo(tv.right - w, 0)
    }

    private fun refreshCrumbs() {
        crumbs.removeAllViews()
        val tab = active
        if (tab == null) { crumbScroll.visibility = View.GONE; return }
        crumbScroll.visibility = if (compact) View.GONE else View.VISIBLE
        val parts = tool.pathParts(tab.doc)
        crumbs.setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
        parts.forEachIndexed { i, (label, folder) ->
            if (i > 0) crumbs.addView(ui.icon(TIcon.Glyph.CHEVRON_RIGHT, t.textMuted, 12f), ui.lp(t.dpi(12f), t.dpi(12f)).apply { marginStart = t.dpi(2f); marginEnd = t.dpi(2f) })
            val last = i == parts.size - 1
            val tv = ui.text(label, t.textCaption, if (last) t.text else t.textMuted, medium = last).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(t.dpi(4f), 0, t.dpi(4f), 0)
                minHeight = t.dpi(28f)
                if (folder != null) {
                    background = ui.states()
                    isFocusable = true
                    contentDescription = ctx.getString(R.string.tedit_reveal_folder, label)
                    setOnClickListener { tool.revealFolder(folder) }
                }
            }
            crumbs.addView(tv, ui.lp(TeditTool.WRAP, TeditTool.MATCH))
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun onDocChanged() {
        val d = boundDoc ?: return
        // The tab label carries the dirty mark; the content changes when a load finishes.
        strip.refreshLabels()
        if (stateOf(d) != shownState) showContent()
        else if (d.loading) (content.getChildAt(0)?.findViewWithTag<ProgressStrip>("progress"))?.fraction = d.loadProgress
        updateBanner()
        tool.docStateChanged(d)
    }

    /** What the content area shows for [d]; it is rebuilt only when this changes. */
    private var shownState = ""

    private fun stateOf(d: Document?): String = when {
        d == null -> "welcome"
        d.loading -> "loading"
        d.loadError != null -> "error"
        d.image -> "image"
        d.binary -> "binary"
        else -> "text"
    }

    /** Puts the editor, a preview, a loading line or an error in the content area for the active tab. */
    fun showContent() {
        if (!::content.isInitialized) return
        val tab = active
        val d = tab?.doc
        if (boundDoc !== d) {
            boundDoc?.listeners?.remove(docListener)
            boundDoc = d
            d?.listeners?.add(docListener)
        }
        content.removeAllViews()
        shownState = stateOf(d)
        when {
            tab == null || d == null -> {
                editor.bind(null)
                content.addView(tool.welcomeView(this))
            }
            d.loading -> {
                editor.bind(null)
                content.addView(loadingView(d))
            }
            d.loadError != null -> {
                editor.bind(null)
                content.addView(errorView(tab, d.loadError ?: ""))
            }
            d.image -> {
                editor.bind(null)
                content.addView(imageView(d))
            }
            d.binary -> {
                editor.bind(null)
                content.addView(binaryView(d))
            }
            else -> {
                editor.bind(tab.session)
                (editor.parent as? ViewGroup)?.removeView(editor)
                content.addView(editor, FrameLayout.LayoutParams(TeditTool.MATCH, TeditTool.MATCH))
                tool.lineMarksFor(this)
            }
        }
        if (findBar.visible) findBar.refresh()
        updateBanner()
        refreshCrumbs()
    }

    fun updateBanner() {
        if (!::banner.isInitialized) return
        banner.removeAllViews()
        val d = active?.doc
        if (d == null || d.loading || d.loadError != null) { banner.visibility = View.GONE; return }
        val (glyph, msg, actions) = when {
            d.key in changedOnDisk -> Triple(TIcon.Glyph.WARNING, ctx.getString(R.string.tedit_changed_on_disk), listOf(
                ctx.getString(R.string.tedit_reload) to { changedOnDisk.remove(d.key); tool.reload(d) },
                ctx.getString(R.string.tedit_keep_mine) to { changedOnDisk.remove(d.key); updateBanner() },
            ))
            d.restoredOverChange -> Triple(TIcon.Glyph.WARNING, ctx.getString(R.string.tedit_restored_over_change), listOf(
                ctx.getString(R.string.tedit_ok) to { d.restoredOverChange = false; updateBanner() },
            ))
            d.readOnly && d.readOnlyReason != 0 -> Triple(TIcon.Glyph.LOCK, ctx.getString(d.readOnlyReason), emptyList<Pair<String, () -> Unit>>())
            d.mixedEol -> Triple(TIcon.Glyph.INFO, ctx.getString(R.string.tedit_mixed_eol, if (d.eol == "\r\n") "CRLF" else "LF"), listOf(
                ctx.getString(R.string.tedit_ok) to { d.mixedEol = false; updateBanner() },
            ))
            else -> { banner.visibility = View.GONE; return }
        }
        banner.visibility = View.VISIBLE
        val color = if (glyph == TIcon.Glyph.WARNING) t.warning else t.text2
        banner.addView(ui.icon(glyph, color, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
        banner.addView(ui.text(msg, t.textSmall, t.text, lines = 3), ui.lp(0, TeditTool.WRAP, 1f))
        for ((label, action) in actions) {
            banner.addView(ui.text(label, t.textSmall, t.accentText, medium = true).apply {
                gravity = Gravity.CENTER
                minHeight = t.control
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                background = ui.states()
                isFocusable = true
                setOnClickListener { action() }
            })
        }
    }

    private fun centered(): LinearLayout = ui.row(vertical = true, gravity = Gravity.CENTER).apply {
        setPadding(t.dpi(24f), t.dpi(24f), t.dpi(24f), t.dpi(24f))
    }

    private fun loadingView(d: Document): View {
        val col = centered()
        col.addView(ui.text(ctx.getString(R.string.tedit_opening, d.name), t.textBody, t.text2, lines = 2).apply { gravity = Gravity.CENTER })
        val bar = ProgressStrip(ctx, t).apply { tag = "progress"; fraction = d.loadProgress }
        col.addView(bar, ui.lp(t.dpi(200f), t.dpi(3f)).apply { topMargin = t.dpi(14f) })
        col.addView(ui.button(ctx.getString(R.string.tedit_cancel)) { d.cancelLoad = true; active?.let { tool.closeTab(this, it, force = true) } },
            ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { topMargin = t.dpi(18f) })
        return col
    }

    private fun errorView(tab: Tab, message: String): View {
        val col = centered()
        col.addView(ui.icon(TIcon.Glyph.ERROR, t.error, 32f), ui.lp(t.dpi(32f), t.dpi(32f)))
        col.addView(ui.text(ctx.getString(R.string.tedit_open_failed, tab.doc.name), t.textTitle, t.text, medium = true, lines = 2).apply { gravity = Gravity.CENTER },
            ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { topMargin = t.dpi(12f) })
        col.addView(ui.text(message, t.textSmall, t.text2, lines = 0).apply { gravity = Gravity.CENTER },
            ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { topMargin = t.dpi(6f) })
        val row = ui.row(gravity = Gravity.CENTER)
        row.addView(ui.button(ctx.getString(R.string.tedit_retry), primary = true) { tool.reload(tab.doc) })
        row.addView(ui.button(ctx.getString(R.string.tedit_close_tab)) { tool.closeTab(this, tab, force = true) }, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginStart = t.dpi(8f) })
        col.addView(row, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { topMargin = t.dpi(18f) })
        return col
    }

    private fun infoRows(d: Document, extra: List<Pair<String, String>>): LinearLayout {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val rows = ArrayList<Pair<String, String>>()
        rows += ctx.getString(R.string.tedit_info_size) to Formatter.formatFileSize(ctx, d.size)
        d.disk?.modified?.takeIf { it > 0 }?.let { rows += ctx.getString(R.string.tedit_info_modified) to DateFormat.getMediumDateFormat(ctx).format(it) + " " + DateFormat.getTimeFormat(ctx).format(it) }
        d.source?.let { rows += ctx.getString(R.string.tedit_info_location) to tool.store.files.displayPath(it) }
        rows += extra
        // Label above value: the details stay readable however narrow the editor is.
        for ((k, v) in rows) {
            col.addView(ui.text(k, t.textCaption, t.textMuted), ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(8f) })
            col.addView(ui.text(v, t.textSmall, t.text, lines = 0).apply { setTextIsSelectable(true) }, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(2f) })
        }
        return col
    }

    private fun imageView(d: Document): View {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = d.name }
        col.addView(img, ui.lp(TeditTool.MATCH, 0, 1f))
        val info = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(16f), t.dpi(4f), t.dpi(16f), t.dpi(12f)); setBackgroundColor(t.panel) }
        col.addView(ui.scroll(info), ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        val bytes = d.imageBytes
        tool.store.io.execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            val bmp = if (bytes != null && w > 0 && h > 0) runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = BitmapSample.sampleFor(w, h, 2048, 2048)
                })
            }.getOrNull() else null
            img.post {
                if (bmp != null) img.setImageBitmap(bmp)
                val extra = ArrayList<Pair<String, String>>()
                extra += ctx.getString(R.string.tedit_info_type) to (bounds.outMimeType ?: d.name.substringAfterLast('.').uppercase())
                if (w > 0) extra += ctx.getString(R.string.tedit_info_dimensions) to "$w × $h px"
                if (bmp == null) extra += ctx.getString(R.string.tedit_info_preview) to ctx.getString(R.string.tedit_image_unreadable)
                info.addView(infoRows(d, extra))
            }
        }
        return col
    }

    private fun binaryView(d: Document): View {
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(20f), t.dpi(20f), t.dpi(20f), t.dpi(20f)) }
        val head = ui.row()
        head.addView(ui.icon(TIcon.Glyph.FILE, t.text2, 28f), ui.lp(t.dpi(28f), t.dpi(28f)).apply { marginEnd = t.dpi(12f) })
        head.addView(ui.text(d.name, t.textTitle, t.text, medium = true, lines = 2), ui.lp(0, TeditTool.WRAP, 1f))
        col.addView(head)
        col.addView(ui.text(ctx.getString(R.string.tedit_binary_note), t.textSmall, t.text2, lines = 0).apply { setLineSpacing(0f, 1.25f) },
            ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(12f) })
        val extra = ArrayList<Pair<String, String>>()
        tool.mimeOf(d.name)?.let { extra += ctx.getString(R.string.tedit_info_type) to it }
        tool.apkLabel(d)?.let { extra += ctx.getString(R.string.tedit_info_app) to it }
        col.addView(infoRows(d, extra), ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(12f) })
        val row = ui.row()
        row.addView(ui.button(ctx.getString(R.string.tedit_open_as_text)) { tool.openAsText(d) })
        row.addView(ui.button(ctx.getString(R.string.tedit_share)) { tool.share(d) }, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginStart = t.dpi(8f) })
        col.addView(row, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { topMargin = t.dpi(18f) })
        return ui.scroll(col)
    }

    fun dispose() {
        boundDoc?.listeners?.remove(docListener)
        editor.detach()
    }
}

/**
 * The tab row. A long press lifts a tab: drag it sideways to reorder, or let go in place for its menu.
 * The active tab is underlined in the accent, as in the reference.
 */
@SuppressLint("ViewConstructor")
class TabStrip(context: Context, private val group: EditorGroup) : LinearLayout(context) {
    private val tool: TeditTool get() = group.tool
    private val views = HashMap<Tab, View>()
    private var dragging: Tab? = null
    private var dragStartX = 0f
    private var dragMoved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val gradPaint = Paint()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
    }

    fun viewFor(tab: Tab) = views[tab]

    /** The tabs (and their pins) the views were built for. */
    private var builtTabs: List<Tab> = emptyList()
    private var builtPins: List<Boolean> = emptyList()

    fun rebuild() {
        // Switching tabs changes only which one is active; the views stay and are restyled.
        val same = dragging == null && group.tabs.size == builtTabs.size &&
            group.tabs.indices.all { group.tabs[it] === builtTabs[it] && group.tabs[it].pinned == builtPins[it] }
        if (same) { restyle(); return }
        builtTabs = group.tabs.toList()
        builtPins = group.tabs.map { it.pinned }
        removeAllViews()
        views.clear()
        val t = tool.theme
        val ui = tool.ui
        for (tab in group.tabs) {
            val activeTab = tab === group.active
            val row = ui.row().apply {
                setPadding(t.dpi(8f), 0, t.dpi(2f), 0)
                minimumWidth = t.dpi(56f)
                background = ui.states(0f, selected = false, base = if (activeTab) t.bg else 0)
                isFocusable = true
                contentDescription = tab.doc.name
                isSelected = activeTab
            }
            if (tab.pinned) row.addView(ui.icon(TIcon.Glyph.PIN, t.textMuted, 12f), ui.lp(t.dpi(12f), t.dpi(12f)).apply { marginEnd = t.dpi(4f) })
            row.addView(ImageView(context).apply { setImageDrawable(FileIcon.forName(tab.doc.name, t.text2)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                ui.lp(t.dpi(14f), t.dpi(14f)).apply { marginEnd = t.dpi(5f) })
            val label = ui.text(tab.doc.name, t.textSmall, if (activeTab) t.text else t.textMuted).apply { maxWidth = t.dpi(200f); tag = "label" }
            if (tab.doc.readOnly) label.alpha = 0.92f
            row.addView(label, ui.lp(TeditTool.WRAP, TeditTool.WRAP))
            val close = ui.iconButton(if (tab.doc.dirty) TIcon.Glyph.DOT else TIcon.Glyph.CLOSE,
                context.getString(if (tab.doc.dirty) R.string.tedit_close_unsaved else R.string.tedit_close_tab), t.text2, t.dpi(24f)) {
                tool.closeTab(group, tab, force = false)
            }.apply { tag = "close"; t.dpi(5f).let { setPadding(it, it, it, it) } }
            if (tab.pinned && !tab.doc.dirty) close.visibility = View.INVISIBLE
            row.addView(close, ui.lp(t.dpi(24f), t.dpi(24f)).apply { marginStart = t.dpi(2f) })
            row.setOnClickListener { group.select(tab); tool.activateGroup(group); tool.focusEditor(group) }
            row.setOnLongClickListener {
                dragging = tab
                dragMoved = false
                dragStartX = -1f
                row.alpha = 0.85f
                row.elevation = t.dp(6f)
                parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            row.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed && e.isShiftPressed) when (code) {
                    KeyEvent.KEYCODE_PAGE_UP -> { group.move(tab, group.tabs.indexOf(tab) - 1); true }
                    KeyEvent.KEYCODE_PAGE_DOWN -> { group.move(tab, group.tabs.indexOf(tab) + 1); true }
                    else -> false
                } else if (e.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_MENU)) { tool.tabMenu(group, tab, row); true } else false
            }
            addView(row, LayoutParams(TeditTool.WRAP, TeditTool.MATCH))
            views[tab] = row
            if (group.tabs.last() !== tab) addView(ui.divider(vertical = true).apply { layoutParams = LayoutParams(t.stroke, t.dpi(16f)).apply { gravity = Gravity.CENTER_VERTICAL } })
        }
        invalidate()
    }

    private fun restyle() {
        val t = tool.theme
        val ui = tool.ui
        for ((tab, v) in views) {
            val activeTab = tab === group.active
            if (v.isSelected == activeTab) continue
            v.isSelected = activeTab
            v.background = ui.states(0f, selected = false, base = if (activeTab) t.bg else 0)
            v.findViewWithTag<TextView>("label")?.setTextColor(if (activeTab) t.text else t.textMuted)
        }
        invalidate()
    }

    fun refreshLabels() {
        val t = tool.theme
        for ((tab, v) in views) {
            val close = v.findViewWithTag<ImageView>("close") ?: continue
            val dirty = tab.doc.dirty
            (close.drawable as? TIcon)?.let { if ((close.getTag(R.id.tedit_tag_dirty) as? Boolean) != dirty) {
                close.setImageDrawable(TIcon(if (dirty) TIcon.Glyph.DOT else TIcon.Glyph.CLOSE, t.text2))
                close.contentDescription = context.getString(if (dirty) R.string.tedit_close_unsaved else R.string.tedit_close_tab)
                close.setTag(R.id.tedit_tag_dirty, dirty)
            } }
            val vis = if (tab.pinned && !dirty) View.INVISIBLE else View.VISIBLE
            if (close.visibility != vis) close.visibility = vis
            // Setting the same text still lays the strip out again, and this runs as the cursor moves.
            v.findViewWithTag<TextView>("label")?.let { if (it.text.toString() != tab.doc.name) it.text = tab.doc.name }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = dragging != null || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val tab = dragging ?: return super.onTouchEvent(ev)
        val v = views[tab] ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragStartX < 0) dragStartX = ev.x
                val dx = ev.x - dragStartX
                if (abs(dx) > slop) dragMoved = true
                v.translationX = dx
                // Swap with a neighbour once the lifted tab passes its middle.
                val i = group.tabs.indexOf(tab)
                val center = v.left + v.translationX + v.width / 2f
                val next = group.tabs.getOrNull(i + 1)?.let { views[it] }
                val prev = group.tabs.getOrNull(i - 1)?.let { views[it] }
                if (next != null && center > next.left + next.width / 2f && !(group.tabs[i + 1].pinned && !tab.pinned)) {
                    group.tabs.removeAt(i); group.tabs.add(i + 1, tab)
                    dragStartX += next.width; reorderViews()
                } else if (prev != null && center < prev.left + prev.width / 2f && !(tab.pinned.not() && group.tabs[i - 1].pinned)) {
                    group.tabs.removeAt(i); group.tabs.add(i - 1, tab)
                    dragStartX -= prev.width; reorderViews()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                v.translationX = 0f
                v.alpha = 1f
                v.elevation = 0f
                if (!dragMoved && ev.actionMasked == MotionEvent.ACTION_UP) tool.tabMenu(group, tab, v)
                rebuild()
                tool.sessionChanged()
            }
        }
        return true
    }

    private fun reorderViews() {
        val keep = dragging
        val tx = keep?.let { views[it]?.translationX } ?: 0f
        rebuild()
        keep?.let { k -> views[k]?.let { it.translationX = tx; it.alpha = 0.85f; it.elevation = tool.theme.dp(6f) } }
        dragging = keep
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val a = group.active ?: return
        val v = views[a] ?: return
        val t = tool.theme
        val h = t.dp(2f)
        val left = v.left + v.translationX
        gradPaint.color = t.accent
        canvas.drawRect(left, height - h, left + v.width, height.toFloat(), gradPaint)
    }
}

/** Find and replace in the active editor of a group. */
class FindBar(private val tool: TeditTool, private val group: EditorGroup) {
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    lateinit var root: LinearLayout
    private lateinit var query: EditText
    private lateinit var replace: EditText
    private lateinit var replaceRow: LinearLayout
    private lateinit var count: TextView
    private lateinit var caseBtn: ImageView
    private lateinit var wordBtn: ImageView
    private lateinit var regexBtn: ImageView
    private lateinit var expand: ImageView
    private var opts = FindQuery("")
    val visible get() = ::root.isInitialized && root.visibility == View.VISIBLE

    fun build(): View {
        root = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            visibility = View.GONE
            setBackgroundColor(t.panel)
            setPadding(t.dpi(4f), t.dpi(4f), t.dpi(4f), t.dpi(4f))
        }
        val row1 = ui.row()
        expand = ui.iconButton(TIcon.Glyph.CHEVRON_RIGHT, ctx.getString(R.string.tedit_toggle_replace), t.text2, t.control) { setReplace(replaceRow.visibility != View.VISIBLE) }
        row1.addView(expand)
        query = ui.field(ctx.getString(R.string.tedit_find_hint))
        row1.addView(query, ui.lp(0, TeditTool.WRAP, 1f))
        count = ui.text("", t.textCaption, t.textMuted).apply { setPadding(t.dpi(8f), 0, t.dpi(4f), 0); minWidth = t.dpi(56f); gravity = Gravity.CENTER }
        row1.addView(count)
        row1.addView(ui.iconButton(TIcon.Glyph.CLOSE, ctx.getString(R.string.tedit_close_find), t.text2, t.control) { hide() })
        root.addView(row1)
        val row2 = ui.row()
        row2.setPadding(t.control, 0, 0, 0)
        caseBtn = ui.iconButton(TIcon.Glyph.CASE, ctx.getString(R.string.tedit_match_case), t.text2, t.control) { opts = opts.copy(caseSensitive = !opts.caseSensitive); apply() }
        wordBtn = ui.iconButton(TIcon.Glyph.WORD, ctx.getString(R.string.tedit_whole_word), t.text2, t.control) { opts = opts.copy(wholeWord = !opts.wholeWord); apply() }
        regexBtn = ui.iconButton(TIcon.Glyph.REGEX, ctx.getString(R.string.tedit_use_regex), t.text2, t.control) { opts = opts.copy(regex = !opts.regex); apply() }
        row2.addView(caseBtn); row2.addView(wordBtn); row2.addView(regexBtn)
        row2.addView(View(ctx), ui.lp(0, 1, 1f))
        row2.addView(ui.iconButton(TIcon.Glyph.ARROW_UP, ctx.getString(R.string.tedit_find_prev), t.text2, t.control) { step(false) })
        row2.addView(ui.iconButton(TIcon.Glyph.ARROW_DOWN, ctx.getString(R.string.tedit_find_next), t.text2, t.control) { step(true) })
        root.addView(row2)
        replaceRow = ui.row().apply { visibility = View.GONE; setPadding(t.control, t.dpi(2f), 0, 0) }
        replace = ui.field(ctx.getString(R.string.tedit_replace_hint))
        replaceRow.addView(replace, ui.lp(0, TeditTool.WRAP, 1f))
        replaceRow.addView(ui.iconButton(TIcon.Glyph.REPLACE, ctx.getString(R.string.tedit_replace_one), t.text2, t.control) { replaceOne() })
        replaceRow.addView(ui.text(ctx.getString(R.string.tedit_replace_all), t.textSmall, t.accentText, medium = true).apply {
            gravity = Gravity.CENTER; minHeight = t.control; setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
            background = ui.states(); isFocusable = true
            setOnClickListener { replaceAll() }
        })
        root.addView(replaceRow)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { opts = opts.copy(text = s?.toString() ?: ""); apply(jump = true) }
        })
        val keys = View.OnKeyListener { _, code, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when {
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> { step(!e.isShiftPressed); true }
                code == KeyEvent.KEYCODE_ESCAPE -> { hide(); true }
                code == KeyEvent.KEYCODE_F3 -> { step(!e.isShiftPressed); true }
                e.isAltPressed && code == KeyEvent.KEYCODE_C -> { caseBtn.performClick(); true }
                e.isAltPressed && code == KeyEvent.KEYCODE_W -> { wordBtn.performClick(); true }
                e.isAltPressed && code == KeyEvent.KEYCODE_R -> { regexBtn.performClick(); true }
                else -> false
            }
        }
        query.setOnKeyListener(keys)
        query.setOnEditorActionListener { _, _, _ -> step(true); true }
        replace.setOnKeyListener { v, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (e.isCtrlPressed && e.isAltPressed) replaceAll() else replaceOne(); true
            } else keys.onKey(v, code, e)
        }
        replace.setOnEditorActionListener { _, _, _ -> replaceOne(); true }
        refreshButtons()
        return root
    }

    fun show(withReplace: Boolean) {
        root.visibility = View.VISIBLE
        setReplace(withReplace)
        val sel = group.editor.selectedText
        if (sel.isNotEmpty() && !sel.contains('\n') && sel.length < 200) query.setText(sel)
        query.requestFocus()
        query.selectAll()
        TeditUi.showKeyboard(query)
        apply()
    }

    fun hide() {
        if (!visible) return
        root.visibility = View.GONE
        group.find.clear()
        group.editor.invalidate()
        tool.focusEditor(group)
    }

    private fun setReplace(on: Boolean) {
        replaceRow.visibility = if (on) View.VISIBLE else View.GONE
        expand.setImageDrawable(TIcon(if (on) TIcon.Glyph.CHEVRON_DOWN else TIcon.Glyph.CHEVRON_RIGHT, t.text2))
        if (on) replace.requestFocus()
    }

    private fun refreshButtons() {
        fun mark(v: ImageView, on: Boolean) {
            ui.setIconColor(v, if (on) t.accentText else t.text2)
            v.isActivated = on
            v.background = ui.states(selected = on)
            TeditUi.stateDesc(v, ctx.getString(if (on) R.string.tedit_v_on else R.string.tedit_v_off))
        }
        mark(caseBtn, opts.caseSensitive)
        mark(wordBtn, opts.wholeWord)
        mark(regexBtn, opts.regex)
    }

    fun refresh() = apply()

    private fun apply(jump: Boolean = false) {
        refreshButtons()
        val f = group.find
        f.set(opts)
        val d = group.active?.doc
        if (d == null || d.loading || d.image || d.binary) { count.text = ""; return }
        if (jump) f.updateNow(d.buffer) else f.update(d.buffer)
        if (jump && f.count > 0) {
            val s = group.active?.session ?: return
            f.current = f.nextFrom(s.selStart, true)
            reveal()
        }
        updateCount()
        group.editor.invalidate()
    }

    /** Matches found on the worker thread for a large file. */
    fun resultsArrived() {
        if (!::count.isInitialized) return
        updateCount()
    }

    private fun updateCount() {
        val f = group.find
        count.setTextColor(if (f.error != null || (f.count == 0 && opts.text.isNotEmpty())) t.error else t.textMuted)
        count.text = when {
            f.error != null -> ctx.getString(R.string.tedit_bad_regex)
            opts.text.isEmpty() -> ""
            f.searching && f.count == 0 -> ctx.getString(R.string.tedit_find_searching)
            f.count == 0 -> ctx.getString(R.string.tedit_no_results)
            f.current >= 0 -> ctx.getString(R.string.tedit_n_of_m, f.current + 1, f.count) + if (f.truncated) "+" else ""
            else -> ctx.resources.getQuantityString(R.plurals.tedit_matches, f.count, f.count)
        }
        count.contentDescription = count.text
    }

    private fun reveal() {
        val f = group.find
        if (f.current !in 0 until f.count) return
        val l = f.matches[f.current * 3]
        group.editor.select(Pos(l, f.matches[f.current * 3 + 1]), Pos(l, f.matches[f.current * 3 + 2]), center = true)
    }

    fun step(forward: Boolean) {
        val f = group.find
        val d = group.active?.doc ?: return
        f.updateNow(d.buffer)
        if (f.count == 0) { updateCount(); return }
        val s = group.active?.session ?: return
        f.current = if (f.current in 0 until f.count) {
            if (forward) (f.current + 1) % f.count else (f.current - 1 + f.count) % f.count
        } else f.nextFrom(if (forward) s.selEnd else s.selStart, forward)
        reveal()
        updateCount()
    }

    private fun replaceOne() {
        val f = group.find
        val d = group.active?.doc ?: return
        if (d.readOnly) { tool.editorHost.readOnlyAttempt(group.editor); return }
        f.updateNow(d.buffer)
        if (f.current !in 0 until f.count) { step(true); return }
        val l = f.matches[f.current * 3]
        val a = Pos(l, f.matches[f.current * 3 + 1])
        val b = Pos(l, f.matches[f.current * 3 + 2])
        val text = expand(d.buffer.line(l), a.col, b.col) ?: return
        group.editor.replace(a, b, text)
        f.updateNow(d.buffer)
        f.current = f.nextFrom(Pos(l, a.col + text.length), true)
        reveal()
        updateCount()
    }

    /** The replacement for the match at [s]..[e] of [line], with $1 groups filled in when regex is on. */
    private fun expand(line: String, s: Int, e: Int): String? {
        val raw = replace.text.toString()
        if (!opts.regex) return raw
        val p = opts.compile().getOrNull() ?: return null
        val m = p.matcher(line)
        if (!m.find(s) || m.start() != s || m.end() != e) return raw
        val sb = StringBuffer()
        return runCatching {
            m.appendReplacement(sb, raw)
            sb.substring(s)
        }.getOrElse { tool.overlays.snack(ctx.getString(R.string.tedit_bad_replacement)); null }
    }

    private fun replaceAll() {
        val f = group.find
        val d = group.active?.doc ?: return
        if (d.readOnly) { tool.editorHost.readOnlyAttempt(group.editor); return }
        f.updateNow(d.buffer, Int.MAX_VALUE)
        if (f.count == 0) return
        val n = f.count
        val p = opts.compile().getOrNull() ?: return
        val rep = opts.replacement(replace.text.toString())
        tool.overlays.confirm(ctx.getString(R.string.tedit_replace_all), ctx.resources.getQuantityString(R.plurals.tedit_replace_all_confirm, n, n, d.name),
            ctx.getString(R.string.tedit_replace_all)) {
            // One edit for the whole document, so a single undo brings everything back.
            val first = f.matches[0]
            val last = f.matches[(n - 1) * 3]
            val sb = StringBuilder()
            val m = p.matcher("")
            val ok = runCatching {
                for (i in first..last) {
                    if (i > first) sb.append('\n')
                    m.reset(d.buffer.line(i))
                    sb.append(m.replaceAll(rep))
                }
            }
            if (ok.isFailure) { tool.overlays.snack(ctx.getString(R.string.tedit_bad_replacement)); return@confirm }
            group.editor.replace(Pos(first, 0), Pos(last, d.buffer.lineLength(last)), sb.toString())
            f.current = -1
            apply()
            tool.overlays.snack(ctx.resources.getQuantityString(R.plurals.tedit_replaced, n, n))
        }
    }
}
