// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.desktop

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import com.lxlauncher.R
import com.lxlauncher.core.FolderItem
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.ShortcutItem
import com.lxlauncher.core.Snapshot
import com.lxlauncher.core.WidgetItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.widgets.LxWidget
import com.lxlauncher.widgets.WidgetKinds
import kotlin.math.abs

/** What is being dragged. Travels as the local state of a platform drag. */
sealed interface DragPayload {
    data class Shortcut(val id: Int, val appKey: String) : DragPayload
    data class Folder(val id: Int) : DragPayload
    data class App(val appKey: String) : DragPayload
    data class Pin(val list: Int, val entry: String) : DragPayload
}

fun View.startLxDrag(payload: DragPayload, label: CharSequence): Boolean =
    startDragAndDrop(ClipData.newPlainText(label, label), View.DragShadowBuilder(this), payload, 0)

/**
 * The desktop canvas of the active workspace: app shortcuts and folders on a
 * grid, widgets placed freely above them. Owns the desktop gestures.
 */
@SuppressLint("ViewConstructor")
class DesktopView(private val shell: Shell, private val events: Events) : FrameLayout(shell.activity), WidgetFrame.Callbacks {
    interface Events {
        fun onEmptyLongPress(x: Float, y: Float)
        fun onSwipeWorkspace(direction: Int)
        fun onSwipeUp()
        fun onSwipeDown()
        fun onPinchIn()
        fun onDoubleTap()
        fun onShortcutMenu(view: View, item: ShortcutItem)
        fun onFolderOpen(view: View, item: FolderItem)
        fun onFolderMenu(view: View, item: FolderItem)
        fun onWidgetMenu(frame: WidgetFrame, item: WidgetItem)
        fun createWidgetContent(frame: WidgetFrame, item: WidgetItem): View?
    }

    private val theme: LxTheme get() = shell.ui
    private var snapshot: Snapshot? = null
    private val icons = HashMap<Int, IconView>()
    private val frames = HashMap<Int, WidgetFrame>()
    private val widgetKinds = HashMap<Int, String>()
    private var dropCell: Pair<Int, Int>? = null
    private var dropTarget: IconView? = null
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cellRect = RectF()
    private var activeWidgets = false
    /** The active workspace first, then the ones before it whose widget frames stay built (hidden) for a quick return. */
    private val recentWorkspaces = ArrayDeque<Int>()
    private var editMode = false
    private val liveRect = HashMap<Int, RectF>()
    // A long press both starts a drag and, if the finger does not move, opens
    // the item's menu when the drag ends.
    private var pendingMenu: (() -> Unit)? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragMoved = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onLongPress(e: MotionEvent) {
            if (!scaling) events.onEmptyLongPress(e.x, e.y)
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            events.onDoubleTap()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            val min = theme.dp(48f)
            if (abs(dx) > abs(dy) * 1.3f && abs(dx) > min) {
                events.onSwipeWorkspace(if (dx < 0) 1 else -1)
                return true
            }
            if (abs(dy) > abs(dx) * 1.3f && abs(dy) > min) {
                if (dy < 0) events.onSwipeUp() else events.onSwipeDown()
                return true
            }
            return false
        }
    })
    private var scaling = false
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var total = 1f
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            total = 1f
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            total *= d.scaleFactor
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (total < 0.75f) events.onPinchIn()
            post { scaling = false }
        }
    })

    init {
        clipChildren = false
        setWillNotDraw(false)
        isFocusable = false
        contentDescription = context.getString(R.string.desktop_description)
        setOnDragListener { _, e -> onDrag(e) }
        setOnContextClickListener {
            events.onEmptyLongPress(width / 2f, height / 2f)
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scale.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Mouse right-click on the empty desktop.
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton == MotionEvent.BUTTON_SECONDARY) {
            events.onEmptyLongPress(event.x, event.y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    fun setEditMode(on: Boolean) {
        editMode = on
        frames.values.forEach { it.editing = on }
        invalidate()
    }

    fun setWidgetsActive(on: Boolean) {
        activeWidgets = on
        frames.values.forEach { (it.content as? LxWidget)?.setActive(on && it.visibility == View.VISIBLE) }
    }

    private var wallpaperLight = false

    /** Called when the wallpaper changes; icon labels follow its brightness. */
    fun setWallpaperLight(light: Boolean) {
        wallpaperLight = light
        icons.values.forEach { it.setOnWallpaper(light) }
    }

    fun bind(s: Snapshot) {
        snapshot = s
        val ws = s.active
        if (recentWorkspaces.firstOrNull() != ws) {
            recentWorkspaces.remove(ws)
            recentWorkspaces.addFirst(ws)
            while (recentWorkspaces.size > 1 + KEPT_WORKSPACES) recentWorkspaces.removeLast()
        }
        val shortcuts = s.shortcuts.filter { it.ws == ws }
        val folders = s.folders.filter { it.ws == ws }
        val widgets = s.widgets.filter { it.ws == ws && it.visible }
        val keep = HashSet<Int>()

        val iconSize = theme.dpi(s.int("desktop.iconSize", 52).toFloat())
        val labels = s.bool("desktop.labels")
        for (sc in shortcuts) {
            keep += sc.id
            val v = icons.getOrPut(sc.id) { IconView(context, theme).also { addView(it) } }
            v.setTheme(theme)
            v.setOnWallpaper(wallpaperLight)
            v.iconSizePx = iconSize
            v.showLabel = labels
            val app = shell.apps.find(sc.app)
            v.label = app?.label ?: context.getString(R.string.app_unavailable)
            v.unavailable = app == null && shell.apps.loaded
            v.folderIcons = null
            v.icon = shell.apps.cachedIcon(sc.app, iconSize)
            if (v.icon == null) shell.apps.icon(sc.app, iconSize) { d -> if (v.tag == sc.app) v.icon = d }
            v.tag = sc.app
            v.setOnClickListener { shell.launchApp(sc.app, it) }
            v.setOnLongClickListener {
                val menu = { events.onShortcutMenu(it, sc) }
                if (s.bool("desktop.locked") || !it.startLxDrag(DragPayload.Shortcut(sc.id, sc.app), v.label)) menu()
                else pendingMenu = menu
                true
            }
            v.setOnContextClickListener {
                events.onShortcutMenu(it, sc)
                true
            }
        }
        for (f in folders) {
            keep += f.id
            val v = icons.getOrPut(f.id) { IconView(context, theme).also { addView(it) } }
            v.setTheme(theme)
            v.setOnWallpaper(wallpaperLight)
            v.iconSizePx = iconSize
            v.showLabel = labels
            v.label = f.name
            v.unavailable = false
            v.tag = "folder:${f.id}"
            val previews = MutableList(minOf(4, f.apps.size)) { i -> shell.apps.cachedIcon(f.apps[i], iconSize) }
            v.folderIcons = previews
            f.apps.take(4).forEachIndexed { i, key ->
                if (previews[i] == null) shell.apps.icon(key, iconSize) { d ->
                    previews[i] = d
                    v.folderIcons = previews.toList()
                }
            }
            v.contentDescription = resources.getQuantityString(R.plurals.folder_description, f.apps.size, f.name, f.apps.size)
            v.setOnClickListener { events.onFolderOpen(it, f) }
            v.setOnLongClickListener {
                val menu = { events.onFolderMenu(it, f) }
                if (s.bool("desktop.locked") || !it.startLxDrag(DragPayload.Folder(f.id), f.name)) menu()
                else pendingMenu = menu
                true
            }
            v.setOnContextClickListener {
                events.onFolderMenu(it, f)
                true
            }
        }
        icons.keys.filter { it !in keep }.forEach { id -> icons.remove(id)?.let { removeView(it) } }

        val keepWidgets = HashSet<Int>()
        for (w in widgets) {
            keepWidgets += w.id
            var frame = frames[w.id]
            if (frame == null || widgetKinds[w.id] != w.kind) {
                frame?.let { removeView(it) }
                frame = WidgetFrame(shell, w.id, this)
                frames[w.id] = frame
                widgetKinds[w.id] = w.kind
                addView(frame)
                val content = events.createWidgetContent(frame, w)
                if (content != null) frame.setContent(content)
            }
            frame.visibility = View.VISIBLE
            frame.editing = editMode
            (frame.content as? LxWidget)?.let {
                it.bind(w)
                it.setActive(activeWidgets)
            }
            frame.contentDescription = WidgetKinds.label(context, w.kind)
        }
        // Widgets of the last workspaces stay built but hidden and stopped: coming back shows them without
        // inflating an Android widget again while the workspace slides in. Older ones are let go.
        val parked = s.widgets.filter { it.visible && it.ws != ws && it.ws in recentWorkspaces }.associateBy { it.id }
        frames.keys.filter { it !in keepWidgets }.forEach { id ->
            val w = parked[id]
            val frame = frames[id] ?: return@forEach
            if (w != null && widgetKinds[id] == w.kind) {
                if (frame.visibility != View.GONE) {
                    frame.visibility = View.GONE
                    (frame.content as? LxWidget)?.setActive(false)
                }
                return@forEach
            }
            frames.remove(id)
            (frame.content as? LxWidget)?.setActive(false)
            removeView(frame)
            widgetKinds.remove(id)
        }
        requestLayout()
    }

    fun widgetFrame(id: Int): WidgetFrame? = frames[id]

    fun rebuildWidget(id: Int) {
        frames.remove(id)?.let {
            (it.content as? LxWidget)?.setActive(false)
            removeView(it)
        }
        widgetKinds.remove(id)
        snapshot?.let { bind(it) }
    }

    /** Makes every Android widget's view again, in one pass; LX's own widgets stay as they are. */
    fun rebuildAndroidWidgets() {
        val s = snapshot ?: return
        for (w in s.widgets) {
            if (w.appWidgetId < 0) continue
            frames.remove(w.id)?.let { removeView(it) }
            widgetKinds.remove(w.id)
        }
        bind(s)
    }

    private fun cellSize(): Pair<Float, Float> {
        val s = snapshot ?: return 1f to 1f
        return width / s.cols.toFloat() to height / s.rows.toFloat()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val s = snapshot ?: return
        val (cw, ch) = cellSize()
        val ws = s.active
        for (sc in s.shortcuts) if (sc.ws == ws) icons[sc.id]?.place(sc.col * cw, sc.row * ch, cw, ch)
        for (f in s.folders) if (f.ws == ws) icons[f.id]?.place(f.col * cw, f.row * ch, cw, ch)
        for (w in s.widgets) {
            if (w.ws != ws) continue
            val frame = frames[w.id] ?: continue
            val live = liveRect[w.id]
            if (live != null) frame.place(live.left, live.top, live.width(), live.height())
            else frame.place(w.x * width, w.y * height, w.w * width, w.h * height)
        }
    }

    private fun View.place(x: Float, y: Float, w: Float, h: Float) {
        val wi = w.toInt().coerceAtLeast(1)
        val hi = h.toInt().coerceAtLeast(1)
        measure(MeasureSpec.makeMeasureSpec(wi, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hi, MeasureSpec.EXACTLY))
        layout(x.toInt(), y.toInt(), x.toInt() + wi, y.toInt() + hi)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = snapshot ?: return
        val cell = dropCell ?: return
        // Cell outline only while something is being dropped: it shows where it lands.
        val (cw, ch) = cellSize()
        cellPaint.color = LxTheme.withAlpha(theme.accent, 0.7f)
        cellPaint.strokeWidth = theme.dp(1.5f)
        val inset = theme.dp(4f)
        cellRect.set(cell.first * cw + inset, cell.second * ch + inset, (cell.first + 1) * cw - inset, (cell.second + 1) * ch - inset)
        canvas.drawRoundRect(cellRect, theme.cornerRadius, theme.cornerRadius, cellPaint)
        if (s.cols <= 0) return
    }

    // ---- drag and drop ----

    fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val s = snapshot ?: return null
        val (cw, ch) = cellSize()
        val c = (x / cw).toInt()
        val r = (y / ch).toInt()
        if (c !in 0 until s.cols || r !in 0 until s.rows) return null
        return c to r
    }

    private fun itemAt(cell: Pair<Int, Int>): Any? {
        val s = snapshot ?: return null
        s.shortcuts.firstOrNull { it.ws == s.active && it.col == cell.first && it.row == cell.second }?.let { return it }
        s.folders.firstOrNull { it.ws == s.active && it.col == cell.first && it.row == cell.second }?.let { return it }
        return null
    }

    private fun onDrag(e: DragEvent): Boolean {
        val payload = e.localState as? DragPayload
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                dragStartX = e.x
                dragStartY = e.y
                dragMoved = 0f
                return payload != null && payload !is DragPayload.Pin ||
                    (payload is DragPayload.Pin && payload.entry.startsWith("app:"))
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragMoved = maxOf(dragMoved, abs(e.x - dragStartX) + abs(e.y - dragStartY))
                val cell = cellAt(e.x, e.y)
                if (cell != dropCell) {
                    dropCell = cell
                    dropTarget?.dropHighlight = false
                    dropTarget = (cell?.let { itemAt(it) })?.let { item ->
                        val id = (item as? ShortcutItem)?.id ?: (item as? FolderItem)?.id
                        icons[id]
                    }
                    val self = (payload as? DragPayload.Shortcut)?.id ?: (payload as? DragPayload.Folder)?.id
                    if (dropTarget != null && icons[self] !== dropTarget) dropTarget?.dropHighlight = true
                    invalidate()
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                dragMoved = Float.MAX_VALUE
                clearDrop()
            }
            DragEvent.ACTION_DROP -> {
                val cell = cellAt(e.x, e.y)
                clearDrop()
                if (cell == null || payload == null) return false
                return drop(payload, cell)
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                clearDrop()
                val menu = pendingMenu
                pendingMenu = null
                if (menu != null && dragMoved < theme.dp(16f)) post { menu() }
            }
        }
        return true
    }

    private fun clearDrop() {
        dropCell = null
        dropTarget?.dropHighlight = false
        dropTarget = null
        invalidate()
    }

    /** A drop handed on by the overlay layer, at a point in this view. */
    fun dropAt(p: DragPayload, x: Float, y: Float): Boolean {
        val cell = cellAt(x, y) ?: return false
        return drop(p, cell)
    }

    private fun drop(p: DragPayload, cell: Pair<Int, Int>): Boolean {
        val s = snapshot ?: return false
        val target = itemAt(cell)
        val model = shell.model
        return when (p) {
            is DragPayload.Shortcut -> when (target) {
                is ShortcutItem -> if (target.id == p.id) true else
                    model.edit { NativeCore.createFolder(it, target.id, p.id, context.getString(R.string.folder_default_name)) } != 0
                is FolderItem -> model.edit { NativeCore.addToFolder(it, target.id, p.id) }
                else -> model.edit { NativeCore.moveItem(it, p.id, cell.first, cell.second) }
            }
            is DragPayload.Folder -> if (target is FolderItem && target.id == p.id) true
                else model.edit { NativeCore.moveItem(it, p.id, cell.first, cell.second) }
            is DragPayload.App, is DragPayload.Pin -> {
                val key = if (p is DragPayload.App) p.appKey else (p as DragPayload.Pin).entry.removePrefix("app:")
                when (target) {
                    is FolderItem -> {
                        val id = model.edit { NativeCore.addShortcut(it, s.active, key, -1, -1) }
                        if (id != 0) model.edit { NativeCore.addToFolder(it, target.id, id) } else false
                    }
                    is ShortcutItem -> {
                        val id = model.edit { NativeCore.addShortcut(it, s.active, key, -1, -1) }
                        if (id != 0 && target.app != key) {
                            model.edit { NativeCore.createFolder(it, target.id, id, context.getString(R.string.folder_default_name)) } != 0
                        } else id != 0
                    }
                    else -> {
                        val id = model.edit { NativeCore.addShortcut(it, s.active, key, cell.first, cell.second) }
                        if (id == 0) shell.notify(context.getString(R.string.desktop_full))
                        id != 0
                    }
                }
            }
        }
    }

    // ---- widget editing ----

    override fun onWidgetMenu(frame: WidgetFrame) {
        val w = snapshot?.widgets?.firstOrNull { it.id == frame.widgetId } ?: return
        events.onWidgetMenu(frame, w)
    }

    override fun onWidgetDragged(frame: WidgetFrame, dx: Float, dy: Float, done: Boolean) {
        if (!liveRect.containsKey(frame.widgetId)) frame.bringToFront()  // the widget in hand draws on top
        val r = liveRect.getOrPut(frame.widgetId) {
            RectF(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
        }
        r.offset(dx, dy)
        val w = r.width()
        val h = r.height()
        r.left = r.left.coerceIn(0f, (width - w).coerceAtLeast(0f))
        r.top = r.top.coerceIn(0f, (height - h).coerceAtLeast(0f))
        r.right = r.left + w
        r.bottom = r.top + h
        if (done) commitWidget(frame.widgetId) else requestLayout()
    }

    override fun onWidgetResized(frame: WidgetFrame, dw: Float, dh: Float, done: Boolean) {
        val r = liveRect.getOrPut(frame.widgetId) {
            RectF(frame.left.toFloat(), frame.top.toFloat(), frame.right.toFloat(), frame.bottom.toFloat())
        }
        val min = theme.dp(96f)
        r.right = (r.right + dw).coerceIn(r.left + min, width.toFloat())
        r.bottom = (r.bottom + dh).coerceIn(r.top + min * 0.6f, height.toFloat())
        if (done) commitWidget(frame.widgetId) else requestLayout()
    }

    private fun commitWidget(id: Int) {
        val r = liveRect.remove(id) ?: return
        if (width <= 0 || height <= 0) return
        shell.model.edit {
            NativeCore.setWidgetRect(it, id, r.left / width, r.top / height, r.width() / width, r.height() / height)
        }
    }
}

/** Workspaces besides the active one whose widgets stay built. */
private const val KEPT_WORKSPACES = 2
