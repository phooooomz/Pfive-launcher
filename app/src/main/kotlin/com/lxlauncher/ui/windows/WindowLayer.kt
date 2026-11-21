// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.windows

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.Snapshot
import com.lxlauncher.core.WindowItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxIcons
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PerfMode
import com.lxlauncher.ui.theme.TitleShape
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface

/** Content of an internal window. */
interface ToolView {
    val view: View
    fun onShown() {}
    fun onHidden() {}
    /** Returns true when the tool handled Back itself (for example, folder up). */
    fun onBack(): Boolean = false
    fun onDestroy() {}
    /**
     * Lets the tool fill the title bar itself and keep it current. Returns true when it does; the frame
     * then leaves the title text alone. An Android app window stores its app key as the window title.
     */
    fun decorate(title: TextView, icon: ImageView): Boolean = false
    /** True when the content runs to the frame's edge, with the window's round corners, as an Android app does. */
    val fillsFrame: Boolean get() = false
    /**
     * True when every press on the content must reach it, up to its very edge: an Android app keeps keys of its keyboard
     * there. Such a window resizes from its border line and from a band just outside it, never from over its content.
     */
    val keepsEdgeTouches: Boolean get() = false

    /**
     * A resize drag on this window started ([active]) or ended. The frame follows the finger on every frame; content
     * that takes long to lay out follows a few frames behind until the drag ends (see WindowFrame.measureChildWithMargins).
     */
    fun onLiveResize(active: Boolean) {}

    /**
     * A key press while this window has focus, offered before LX's own shortcuts. True when the tool used it:
     * Tedit closes a tab on Ctrl+W instead of letting LX close its window.
     */
    fun onShortcut(e: android.view.KeyEvent): Boolean = false
}

/**
 * Lays out the internal windows of the active workspace at the bounds the
 * native window manager computed. Geometry decisions (clamping, snapping,
 * maximise) are made in C++; this class only routes input and draws.
 */
@SuppressLint("ViewConstructor")
class WindowLayer(private val shell: Shell, private val factory: (WindowItem) -> ToolView) : FrameLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private val frames = HashMap<Int, WindowFrame>()
    private val bounds = HashMap<Int, RectF>()
    private var snapshot: Snapshot? = null
    private var snapPreview: RectF? = null
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val previewStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    var viewport = Rect()
    private var started = false

    init {
        setWillNotDraw(false)
        clipChildren = true
    }

    fun setStarted(on: Boolean) {
        started = on
        updateVisibility()
    }

    /**
     * Windows built even while hidden: an app window whose display outlived the views it was shown in (a rebuild parks
     * it for a few seconds), so the new window takes the display over before it is let go.
     */
    var buildHidden: (WindowItem) -> Boolean = { false }

    fun bind(s: Snapshot) {
        snapshot = s
        val visible = s.windows.filter { it.ws == s.active }
        val keep = HashSet<Int>()
        for (w in s.windows) {
            keep += w.id
            bounds[w.id] = RectF(w.x, w.y, w.x + w.w, w.y + w.h)
            val shows = w.ws == s.active && !w.minimized
            // A hidden window is built when it first shows. Building every saved window at once, the minimised ones and
            // those on other workspaces included, held up LX's start and every rebuild.
            val f = frames[w.id] ?: if (shows || buildHidden(w)) WindowFrame(w).also {
                frames[w.id] = it
                addView(it)
            } else continue
            f.item = w
            f.visibility = if (shows) View.VISIBLE else View.GONE
        }
        frames.keys.filter { it !in keep }.forEach { id ->
            frames.remove(id)?.let {
                it.tool.onHidden()
                it.tool.onDestroy()
                removeView(it)
            }
        }
        bounds.keys.retainAll(keep)
        // Children are kept in z order so touch dispatch matches what is drawn.
        visible.sortedBy { it.z }.forEach { frames[it.id]?.bringToFront() }
        // A fullscreen window may cover the panels; otherwise clip to the viewport.
        clipBounds = if (visible.any { it.state == "fullscreen" && !it.minimized }) null else viewport
        updateVisibility()
        requestLayout()
        invalidate()
    }

    /** Ends every tool's lifecycle before the layer is thrown away, so timers and listeners stop with it. */
    fun destroyAll() {
        for (f in frames.values) {
            f.tool.onHidden()
            f.tool.onDestroy()
        }
        frames.clear()
        bounds.clear()
        removeAllViews()
    }

    private fun updateVisibility() {
        for (f in frames.values) {
            val shown = started && f.visibility == View.VISIBLE
            if (shown != f.shownToTool) {
                f.shownToTool = shown
                if (shown) f.tool.onShown() else f.tool.onHidden()
            }
        }
    }

    fun focusedTool(): ToolView? {
        val s = snapshot ?: return null
        val w = s.windows.firstOrNull { it.ws == s.active && it.focused } ?: return null
        return frames[w.id]?.tool
    }

    fun focusedWindow(): WindowItem? {
        val s = snapshot ?: return null
        return s.windows.firstOrNull { it.ws == s.active && it.focused }
    }

    fun frameOf(id: Int): View? = frames[id]

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((id, f) in frames) {
            if (f.visibility != View.VISIBLE) continue
            place(f, bounds[id] ?: continue)
        }
    }

    /**
     * Lays [f] out at [rb]. A window's glass is drawn for where it sits, so a window that moved or changed size draws
     * again. The others keep their picture: redrawing every window on each frame of one window's resize cost them all.
     */
    private fun place(f: WindowFrame, rb: RectF) {
        val w = rb.width().toInt().coerceAtLeast(1)
        val h = rb.height().toInt().coerceAtLeast(1)
        val x = rb.left.toInt()
        val y = rb.top.toInt()
        val changedPlace = f.left != x || f.top != y || f.width != w || f.height != h
        f.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        f.layout(x, y, x + w, y + h)
        if (changedPlace) f.invalidateGlass()
    }

    /** The window a press in the band outside its edge is resizing, until that press ends (see WindowFrame.ringEdges). */
    private var ringFrame: WindowFrame? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        ringFrame = null
        // The bands stay within the margin around the area windows use, off the bars beyond it.
        val margin = theme.dp(8f)
        val inReach = ev.x >= viewport.left - margin && ev.x < viewport.right + margin &&
            ev.y >= viewport.top - margin && ev.y < viewport.bottom + margin
        // Front to back: a window the press is in takes it and covers the bands of the windows behind it. A window's
        // band lies over whatever is behind it, as a desktop window's resize border does.
        for (i in childCount - 1 downTo 0) {
            val f = getChildAt(i) as? WindowFrame ?: continue
            if (f.visibility != View.VISIBLE) continue
            val x = ev.x - f.left
            val y = ev.y - f.top
            if (x >= 0 && x < f.width && y >= 0 && y < f.height) return false
            if (!inReach) continue
            val e = f.ringEdges(x, y)
            if (e != 0) {
                ringFrame = f
                f.startResize(e)
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val f = ringFrame ?: return false
        f.handleDrag(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) ringFrame = null
        return true
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = snapPreview ?: return
        previewPaint.color = LxTheme.withAlpha(theme.accent, 0.12f)
        previewStroke.color = LxTheme.withAlpha(theme.accent, 0.8f)
        previewStroke.strokeWidth = theme.dp(2f)
        canvas.drawRoundRect(p, theme.cornerRadius, theme.cornerRadius, previewPaint)
        canvas.drawRoundRect(p, theme.cornerRadius, theme.cornerRadius, previewStroke)
    }

    private fun previewFor(state: String?): RectF? {
        val vp = viewport
        if (state == null || vp.isEmpty) return null
        val landscape = vp.width() >= vp.height()
        return when (state) {
            "maximized" -> RectF(vp)
            "snapped_left" -> if (landscape) RectF(vp.left.toFloat(), vp.top.toFloat(), vp.exactCenterX(), vp.bottom.toFloat())
                else RectF(vp.left.toFloat(), vp.top.toFloat(), vp.right.toFloat(), vp.exactCenterY())
            "snapped_right" -> if (landscape) RectF(vp.exactCenterX(), vp.top.toFloat(), vp.right.toFloat(), vp.bottom.toFloat())
                else RectF(vp.left.toFloat(), vp.exactCenterY(), vp.right.toFloat(), vp.bottom.toFloat())
            else -> null
        }
    }

    /** One window: title bar, content and resize edges. */
    @SuppressLint("ViewConstructor")
    inner class WindowFrame(initial: WindowItem) : FrameLayout(shell.activity) {
        var item: WindowItem = initial
            set(v) {
                field = v
                if (!toolTitled) title.text = v.title
                panel?.focused = v.focused
                chrome?.focused = v.focused
                // In a window style, the title of the focused window is set in Medium: focus is not colour alone.
                if (style != null) title.typeface = if (v.focused) theme.fontMedium else theme.font
                contentDescription = context.getString(R.string.window_description, title.text)
                val big = v.state == "maximized" || v.state == "fullscreen"
                (maxButton as? ImageView)?.setImageResource(if (big) R.drawable.ic_restore else R.drawable.ic_maximize)
                (maxButton as? CaptionButton)?.role = if (big) CaptionButton.Role.RESTORE else CaptionButton.Role.MAXIMIZE
                maxButton.contentDescription = context.getString(if (big) R.string.window_restore else R.string.window_maximize)
                maxButton.tooltipText = maxButton.contentDescription
                // Styles that fade windows without focus do it to the title bar's content only; the buttons
                // still take taps, and the thicker edge of the focused window keeps focus from being colour alone.
                val dim = style?.inactiveDim ?: 0f
                val alpha = if (v.focused || dim <= 0f || theme.highContrast) 1f else 1f - dim
                for (part in listOf(titleIcon, title, minButton, maxButton, closeButton)) part.alpha = alpha
                showFrame(v.state != "fullscreen")
            }
        /** False while the window is full screen: its content alone fills the screen, and Back gives the frame back. */
        private var framed = true
        private var restingElevation = 0f
        val tool: ToolView = factory(initial)
        var shownToTool = false
        /** The chosen window style, or null when windows follow the LX theme with the glass panel. */
        private val style = theme.windowStyle
        private val panel = if (style == null) PanelDrawable(this, shell.glass, Surface.GLASS) else null
        private val chrome = style?.let { WindowFrameDrawable(this, shell.glass, it, theme) }
        private val titleSize = style?.let { it.titleSizeSp * theme.scale } ?: theme.textTitle
        // Narrow phone windows shrink a long title a little before cutting it off.
        private val title: TextView = Ui.text(context, theme, initial.title, titleSize, theme.textPrimary, bold = true,
            view = WindowTitle(context, titleSize)).apply { gravity = Gravity.CENTER_VERTICAL }
        private val maxButton: View
        private val minButton: View
        private val closeButton: View
        private val titleIcon: ImageView
        private val titleBar: LinearLayout
        /** True when the tool keeps the title text itself (see [ToolView.decorate]). */
        private var toolTitled = false
        /** The resize border. Content sits inside it, so a scroll that starts near the edge never resizes. */
        private val edge = theme.dp(8f)
        /** Near a corner, a press in the band outside an app window takes both edges (see [ringEdges]). */
        private val corner = theme.dp(16f)
        private var mode = 0  // 0 none, 1 move, 2 resize
        private var edges = 0
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        /** Finger travel a resize could not follow yet, because a side stopped at the smallest or largest size. */
        private var owedX = 0f
        private var owedY = 0f
        private var dragging = false
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        /** A card title bar sits this far inside the frame; its buttons are that much shorter than the bar. */
        private val cardInset = if (style?.titleShape == TitleShape.CARD) theme.dpi(6f) else 0
        // Larger touch targets raise the title bar too, so its buttons can grow to full size.
        private val titleH = maxOf(theme.dpi(style?.titleHeightDp ?: 44f), if (theme.largeTargets) theme.touchTarget + cardInset else 0)
        /** Inside the edge line, so the frame covers none of an app that fills it. */
        private val contentInset = if (tool.fillsFrame) theme.dpi(maxOf((style?.borderDp ?: 1f) + 0.5f, 1.5f)) else edge.toInt()
        /**
         * The resize border inside the frame. Content that fills the frame lies under it, so it gives up less of itself
         * there; an Android app gives up none. A border over an app took its own taps, the keys along the sides and bottom
         * of its keyboard among them, and gave the keyboard to LX, which closed it. An app window resizes from the line
         * outside its content and from a band around it ([ringEdges]).
         */
        private val grip = when {
            tool.keepsEdgeTouches -> contentInset.toFloat()
            tool.fillsFrame -> theme.dp(5f)
            else -> edge
        }
        private val doubleTap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                shell.model.edit { NativeCore.toggleMaximize(it, item.id) }
                return true
            }
        })

        /** A caption button: the theme's icon button, or one drawn in the window style. */
        private fun captionButton(icon: Int, role: CaptionButton.Role, label: Int, action: () -> Unit): View {
            val st = style ?: return Ui.iconButton(context, theme, icon, context.getString(label), 40f, 18f) { action() }
            return CaptionButton(context, theme, st, role).apply {
                contentDescription = context.getString(label)
                tooltipText = contentDescription
                setOnClickListener { action() }
                // At least a touch target wide, and as tall as the bar inside its padding (a card bar is inset).
                layoutParams = LinearLayout.LayoutParams(maxOf(theme.touchTarget, minOf(titleH, theme.dpi(56f))), Ui.MATCH)
            }
        }

        init {
            background = panel ?: chrome
            if (style != null && !theme.highContrast && theme.perf != PerfMode.PERFORMANCE) {
                elevation = theme.dp(style.shadowDp)
                if (android.os.Build.VERSION.SDK_INT >= 28) style.shadowColor?.let {
                    outlineSpotShadowColor = it
                    outlineAmbientShadowColor = it
                }
            }
            isFocusable = true
            titleBar = Ui.row(context).apply {
                // The title takes the space the buttons leave; aligning baselines measured it twice on each resize frame.
                isBaselineAligned = false
                // A card title bar sits inside the frame, so its content moves in with it.
                setPadding(theme.dpi(12f) + cardInset, cardInset, theme.dpi(4f) + cardInset, 0)
                // Styles with colour icons show the tool's own icon; the others tint a glyph in the style's colour.
                // Tedit keeps its own icon in every style, as an Android app's window keeps the app's.
                val colour = LxIcons.tool(initial.kind, useVivid = true)
                    .takeIf { !it.tinted && (LxIcons.ownColours(it.res) || style?.fullColorIcon == true && !theme.highContrast) }
                val icon = ImageView(context).apply {
                    if (colour != null) setImageResource(colour.res) else {
                        setImageResource(toolIcon(initial.kind))
                        imageTintList = ColorStateList.valueOf(when {
                            style == null -> theme.accent
                            theme.highContrast -> theme.textPrimary
                            else -> style.iconTint
                        })
                    }
                }
                titleIcon = icon
                val iconSize = theme.dpi(style?.iconDp ?: 18f)
                addView(icon, Ui.lp(iconSize, iconSize).apply { marginEnd = theme.dpi(10f) })
                toolTitled = tool.decorate(title, icon)
                addView(title, Ui.lp(0, Ui.MATCH, 1f).apply {
                    // A long title stops before the tab's diagonal cut instead of running past it.
                    if (style?.titleShape == TitleShape.SLANTED_TAB) marginEnd = theme.dpi(8f) + (titleH * 0.55f).toInt()
                })
                minButton = captionButton(R.drawable.ic_minimize, CaptionButton.Role.MINIMIZE, R.string.window_minimize) {
                    shell.model.edit { NativeCore.setWindowState(it, item.id, "minimized") }
                }
                addView(minButton)
                maxButton = captionButton(R.drawable.ic_maximize, CaptionButton.Role.MAXIMIZE, R.string.window_maximize) {
                    shell.model.edit { NativeCore.toggleMaximize(it, item.id) }
                }
                addView(maxButton)
                val close = captionButton(R.drawable.ic_close, CaptionButton.Role.CLOSE, R.string.window_close) {
                    shell.model.edit { NativeCore.closeWindow(it, item.id) }
                }
                closeButton = close
                addView(close)
                if (style != null) background = TitleBarDrawable(style, theme) {
                    (if (minButton.visibility == View.VISIBLE) minButton.width else 0) + maxButton.width + close.width + paddingEnd
                }
            }
            addView(titleBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, titleH, Gravity.TOP))
            addView(tool.view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = titleH
                leftMargin = contentInset
                rightMargin = contentInset
                bottomMargin = contentInset
            })
            if (tool.fillsFrame) {
                // A style with cut corners cuts more than its radius; the content stays inside the cut.
                val radius = maxOf(0f, maxOf(theme.cornerRadius, theme.dp(style?.chamferDp ?: 0f)) - contentInset)
                tool.view.clipToOutline = true
                tool.view.outlineProvider = object : ViewOutlineProvider() {
                    // The top runs past the view, so only the bottom corners round.
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, -radius.toInt(), view.width, view.height, radius)
                }
            }
            item = initial
            setOnContextClickListener {
                showWindowMenu(it)
                true
            }
            titleBar.setOnLongClickListener {
                showWindowMenu(it)
                true
            }
        }

        fun invalidateGlass() = invalidate()

        private fun showFrame(on: Boolean) {
            if (on == framed) return
            framed = on
            titleBar.visibility = if (on) View.VISIBLE else View.GONE
            (tool.view.layoutParams as LayoutParams).apply {
                topMargin = if (on) titleH else 0
                leftMargin = if (on) contentInset else 0
                rightMargin = if (on) contentInset else 0
                bottomMargin = if (on) contentInset else 0
            }
            background = if (on) panel ?: chrome else null
            if (!on) restingElevation = elevation
            elevation = if (on) restingElevation else 0f
            if (tool.fillsFrame) tool.view.clipToOutline = on
            tool.view.requestLayout()
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (framed) chrome?.drawEdge(canvas)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Narrow windows keep the title readable; minimise stays in the taskbar and the title menu.
            minButton.visibility = if (MeasureSpec.getSize(widthMeasureSpec) < theme.dpi(300f)) View.GONE else View.VISIBLE
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        /** True while a resize drag on this window lasts (see [ToolView.onLiveResize]). */
        private var liveResize = false
        /** The specs the content was last measured with, and until when ([System.nanoTime]) a live resize leaves it be. */
        private var contentWidthSpec = 0
        private var contentHeightSpec = 0
        private var contentDueNs = 0L
        /** Lays the content out when its wait ends, also when the finger has stopped and sends no more moves. */
        private val contentCatchUp = Runnable { if (liveResize) requestLayout() }
        /** For the line logged when a live resize ends: frames, content layouts and the slowest content measure. */
        private var liveFrames = 0
        private var liveLayouts = 0
        private var liveSlowestNs = 0L

        /**
         * During a live resize the frame takes its new size on every frame, but content that took longer than
         * [CONTENT_FRAME_BUDGET_NS] to measure and lay out keeps its last layout for a while, in proportion to that
         * time: a page such as LX Settings rewraps dozens of texts at every width, up to 19 ms a time on the Infinix
         * test phone, and doing so on every frame, or every fourth, made the whole drag stutter. The content is measured again when
         * its wait ends, when the drag ends, or sooner when it asks for a layout itself.
         */
        override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                             parentHeightMeasureSpec: Int, heightUsed: Int) {
            if (child !== tool.view) return super.measureChildWithMargins(child, parentWidthMeasureSpec, widthUsed,
                parentHeightMeasureSpec, heightUsed)
            if (liveResize) liveFrames++
            if (liveResize && contentWidthSpec != 0 && !child.isLayoutRequested && System.nanoTime() < contentDueNs) {
                // The same specs as last time: the content keeps its measured size and does no work.
                child.measure(contentWidthSpec, contentHeightSpec)
                return
            }
            val lp = child.layoutParams as MarginLayoutParams
            val w = getChildMeasureSpec(parentWidthMeasureSpec,
                paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width)
            val h = getChildMeasureSpec(parentHeightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height)
            val started = System.nanoTime()
            child.measure(w, h)
            if (liveResize) {
                contentMeasureNs = System.nanoTime() - started
                contentMeasured = true
                liveLayouts++
            }
            contentWidthSpec = w
            contentHeightSpec = h
        }

        /** The content's measure time in this frame of a live resize; its layout time is added in [onLayout]. */
        private var contentMeasureNs = 0L
        private var contentMeasured = false

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val timed = liveResize && contentMeasured
            val started = if (timed) System.nanoTime() else 0L
            super.onLayout(changed, l, t, r, b)
            if (!timed) return
            // A list or grid does most of its work here, binding its rows again for the new width.
            contentMeasured = false
            val took = contentMeasureNs + System.nanoTime() - started
            liveSlowestNs = maxOf(liveSlowestNs, took)
            removeCallbacks(contentCatchUp)
            if (took <= CONTENT_FRAME_BUDGET_NS) {
                contentDueNs = 0L
                return
            }
            // Content over the budget gets at most a fifth of the drag's time.
            val waitNs = minOf(took * CONTENT_WAIT_FACTOR, MAX_CONTENT_WAIT_NS)
            contentDueNs = System.nanoTime() + waitNs
            postDelayed(contentCatchUp, waitNs / 1_000_000 + 1)
        }

        private fun setLiveResize(on: Boolean) {
            if (on == liveResize) return
            liveResize = on
            contentDueNs = 0L
            removeCallbacks(contentCatchUp)
            contentMeasured = false
            tool.onLiveResize(on)
            if (on) {
                liveFrames = 0
                liveLayouts = 0
                liveSlowestNs = 0L
            } else {
                Log.d(PERF_TAG, "window ${item.id} (${item.kind}) resized over $liveFrames frames; content laid out " +
                    "$liveLayouts times, slowest %.1f ms".format(liveSlowestNs / 1e6))
                // The content may still hold the size of a frame it sat out; it takes the window's size now.
                requestLayout()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(contentCatchUp)
            if (liveResize) {
                liveResize = false
                tool.onLiveResize(false)
            }
        }

        private fun showWindowMenu(anchor: View) {
            val s = snapshot ?: return
            val items = mutableListOf(
                com.lxlauncher.ui.MenuItem(context.getString(R.string.window_minimize), R.drawable.ic_minimize) {
                    shell.model.edit { NativeCore.setWindowState(it, item.id, "minimized") }
                },
                // Also here, not only as a title button: narrow windows can run short of room for buttons.
                com.lxlauncher.ui.MenuItem(context.getString(
                    if (item.state == "maximized" || item.state == "fullscreen") R.string.window_restore else R.string.window_maximize),
                    if (item.state == "maximized" || item.state == "fullscreen") R.drawable.ic_restore else R.drawable.ic_maximize) {
                    shell.model.edit { NativeCore.toggleMaximize(it, item.id) }
                },
                com.lxlauncher.ui.MenuItem(context.getString(R.string.window_snap_left), R.drawable.ic_tile) {
                    shell.model.edit { NativeCore.setWindowState(it, item.id, "snapped_left") }
                },
                com.lxlauncher.ui.MenuItem(context.getString(R.string.window_snap_right), R.drawable.ic_tile) {
                    shell.model.edit { NativeCore.setWindowState(it, item.id, "snapped_right") }
                },
                com.lxlauncher.ui.MenuItem(context.getString(R.string.window_fullscreen), R.drawable.ic_maximize) {
                    shell.model.edit { NativeCore.setWindowState(it, item.id, "fullscreen") }
                },
                com.lxlauncher.ui.MenuItem(context.getString(R.string.window_tile_all), R.drawable.ic_tile) {
                    shell.model.edit { h -> NativeCore.tileWindows(h, s.active); true }
                },
            )
            items += com.lxlauncher.ui.MenuItem(context.getString(R.string.settings_window_style), R.drawable.ic_tile, groupStart = true) {
                val ids = listOf("theme") + com.lxlauncher.ui.theme.WindowStyles.all.map { it.id } + "custom"
                val names = listOf(context.getString(R.string.window_style_theme)) +
                    com.lxlauncher.ui.theme.WindowStyles.all.map { context.getString(it.nameRes) } + context.getString(R.string.window_style_custom)
                shell.choose(context.getString(R.string.settings_window_style), names, ids.indexOf(s.setting("window.style"))) { i ->
                    shell.model.edit { NativeCore.setSetting(it, "window.style", ids[i]) }
                }
            }
            for (ws in s.workspaces) if (ws.id != item.ws) {
                items += com.lxlauncher.ui.MenuItem(context.getString(R.string.move_to_workspace, ws.name), R.drawable.ic_widgets) {
                    shell.model.edit { NativeCore.moveWindowToWorkspace(it, item.id, ws.id) }
                }
            }
            items += com.lxlauncher.ui.MenuItem(context.getString(R.string.window_close), R.drawable.ic_close, danger = true) {
                shell.model.edit { NativeCore.closeWindow(it, item.id) }
            }
            shell.showMenu(anchor, items)
        }

        private fun edgesAt(x: Float, y: Float): Int {
            if (item.state == "fullscreen") return 0
            // A window that fills the screen has nothing to resize towards; every pixel of its app stays the app's.
            if (tool.fillsFrame && item.state == "maximized") return 0
            var e = 0
            // The caption buttons reach the side of the title bar; a press there is a button press, not a resize.
            // The window still resizes from its sides below the bar and from its top corners.
            val inTitleBar = y >= edge * 0.6f && y < titleH
            if (x < grip && !inTitleBar) e = e or NativeCore.EDGE_LEFT
            if (x > width - grip && !inTitleBar) e = e or NativeCore.EDGE_RIGHT
            if (y < edge * 0.6f) e = e or NativeCore.EDGE_TOP
            if (y > height - grip) e = e or NativeCore.EDGE_BOTTOM
            return e
        }

        /**
         * The edges a press at (x, y), outside this frame, takes: a window that keeps its content's edge touches resizes
         * from a band [edge] wide around it, since its border line is too thin to hold. Near a corner it takes both edges.
         */
        fun ringEdges(x: Float, y: Float): Int {
            if (!tool.keepsEdgeTouches || item.minimized || item.state == "fullscreen" || item.state == "maximized") return 0
            if (x >= 0 && x < width && y >= 0 && y < height) return 0
            if (x < -edge || x >= width + edge || y < -edge || y >= height + edge) return 0
            var e = 0
            if (x < corner) e = e or NativeCore.EDGE_LEFT
            if (x >= width - corner) e = e or NativeCore.EDGE_RIGHT
            if (y < corner) e = e or NativeCore.EDGE_TOP
            if (y >= height - corner) e = e or NativeCore.EDGE_BOTTOM
            return e
        }

        /** A press outside the frame took [e] (see [ringEdges]); the press's own events follow through [handleDrag]. */
        fun startResize(e: Int) {
            pressed()
            mode = 2
            edges = e
        }

        /** A press reached this window: it comes to the front, and an app that has the keyboard keeps it. */
        private fun pressed() {
            if (!item.focused) shell.model.front { NativeCore.focusWindow(it, item.id) }
            shell.pressedWindow(item.id)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                pressed()
                val e = edgesAt(ev.x, ev.y)
                if (e != 0) {
                    mode = 2
                    edges = e
                    lastX = ev.rawX
                    lastY = ev.rawY
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            // A touch the content did not use is not a drag (windows move by their title bar only),
            // and it must not fall through to the desktop icons under the window either.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) swallow = mode == 0 && ev.y >= titleH
            if (swallow) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
                return true
            }
            return handleDrag(ev)
        }

        private var swallow = false
        /** Read once when a move starts, not on every finger move. */
        private var snapOnDrag = false
        private val screenLoc = IntArray(2)

        /** Title-bar drags are forwarded here by the title bar's touch listener. */
        fun handleDrag(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    downX = ev.rawX
                    downY = ev.rawY
                    owedX = 0f
                    owedY = 0f
                    dragging = false
                    if (mode == 0) mode = 1
                }
                MotionEvent.ACTION_MOVE -> {
                    // Nothing moves until the finger travels past the touch slop. A resting finger
                    // still jitters, and moving a maximised window detaches it, which pulled the
                    // title bar from under the finger and cancelled the long press menu.
                    if (!dragging) {
                        if (Math.hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) <= touchSlop) return true
                        dragging = true
                        if (mode == 2) setLiveResize(true)
                        snapOnDrag = mode == 1 && shell.model.setting("window.edgeSnap") == "true"
                        // The title bar moves with the finger, so Android would never see the finger
                        // leave it and would still fire the long press at the end of a slow drag.
                        titleBar.cancelLongPress()
                    }
                    follow(ev)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Android places each move a little ahead of the finger to meet the next frame (touch resampling).
                    // The lift says where the finger really was, so the window ends under it, not a few pixels past.
                    if (dragging && ev.actionMasked == MotionEvent.ACTION_UP) follow(ev)
                    val wasMove = mode == 1 && dragging
                    val changed = dragging
                    dragging = false
                    mode = 0
                    setLiveResize(false)
                    val preview = snapPreview
                    snapPreview = null
                    if (wasMove && preview != null && ev.actionMasked == MotionEvent.ACTION_UP) {
                        val loc = IntArray(2).also { this@WindowLayer.getLocationOnScreen(it) }
                        val target = shell.model.live { NativeCore.snapTarget(it, ev.rawX - loc[0], ev.rawY - loc[1], theme.dp(28f)) }
                        if (!target.isNullOrEmpty()) shell.model.live { NativeCore.setWindowState(it, item.id, target) }
                    }
                    this@WindowLayer.invalidate()
                    if (changed) shell.model.commit()
                }
            }
            return true
        }

        /** Moves or resizes the window by as far as the finger went since the last event. */
        private fun follow(ev: MotionEvent) {
            val dx = ev.rawX - lastX
            val dy = ev.rawY - lastY
            lastX = ev.rawX
            lastY = ev.rawY
            val r = if (mode == 2) resizeWithFinger(dx, dy)
                else shell.model.live { NativeCore.moveWindow(it, item.id, dx, dy) }
            if (r == null) return
            val rb = bounds.getOrPut(item.id) { RectF() }
            val sameSize = rb.width().toInt() == r[2].toInt() && rb.height().toInt() == r[3].toInt()
            rb.set(r[0], r[1], r[0] + r[2], r[1] + r[3])
            if (snapOnDrag) {
                this@WindowLayer.getLocationOnScreen(screenLoc)
                val target = shell.model.live {
                    NativeCore.snapTarget(it, ev.rawX - screenLoc[0], ev.rawY - screenLoc[1], theme.dp(28f))
                }
                snapPreview = previewFor(target?.ifEmpty { null })
                this@WindowLayer.invalidate()
            }
            if (mode == 1 && sameSize && isLaidOut) {
                // A move only shifts this frame. A layout pass would measure and lay out every window and
                // the whole screen on each finger move.
                offsetLeftAndRight(rb.left.toInt() - left)
                offsetTopAndBottom(rb.top.toInt() - top)
                invalidateGlass()
            } else if (isLaidOut && !this@WindowLayer.isLayoutRequested) {
                // A resize changes this frame alone: it is laid out here, in the frame the finger moved in,
                // instead of in a layout pass over the whole screen. A layout already on its way does it all.
                place(this, rb)
            } else {
                this@WindowLayer.requestLayout()
            }
        }

        /**
         * Resizes by the finger's travel plus what the window could not follow earlier in this drag. At the smallest or
         * largest size the core stops a side and drops the rest of the travel. Without the carry, a finger that went
         * past the smallest size and turned back grew the window at once, so the side ended that far from the finger;
         * the lift's correction for touch resampling also left the window a pixel over its smallest size.
         */
        private fun resizeWithFinger(dx: Float, dy: Float): FloatArray? = shell.model.live { h ->
            val before = NativeCore.windowBounds(h, item.id)
            val wantX = dx + owedX
            val wantY = dy + owedY
            val after = NativeCore.resizeWindow(h, item.id, edges, wantX, wantY)
            if (before != null && after != null) {
                // How far the side being dragged really moved; bounds are x, y, width, height.
                owedX = when {
                    (edges and NativeCore.EDGE_RIGHT) != 0 -> wantX - (after[0] + after[2] - before[0] - before[2])
                    (edges and NativeCore.EDGE_LEFT) != 0 -> wantX - (after[0] - before[0])
                    else -> 0f
                }
                owedY = when {
                    (edges and NativeCore.EDGE_BOTTOM) != 0 -> wantY - (after[1] + after[3] - before[1] - before[3])
                    (edges and NativeCore.EDGE_TOP) != 0 -> wantY - (after[1] - before[1])
                    else -> 0f
                }
            }
            after
        }

        // The title bar drags the window; its click equivalent for accessibility
        // is the window menu on long press and the buttons, so no performClick.
        @SuppressLint("ClickableViewAccessibility")
        private fun wireTitleBar() {
            titleBar.setOnTouchListener { _, ev ->
                doubleTap.onTouchEvent(ev)
                handleDrag(ev)
                // Let long press reach the title bar's listener while the finger is still.
                false
            }
            titleBar.isLongClickable = true
        }

        init {
            wireTitleBar()
        }
    }

    companion object {
        private const val PERF_TAG = "LxPerf"
        /** A quarter of a 60 Hz frame: content that measures within it follows a live resize on every frame. */
        private const val CONTENT_FRAME_BUDGET_NS = 4_000_000L
        /** Content over the budget waits four times its own layout time, so it takes at most a fifth of a drag. */
        private const val CONTENT_WAIT_FACTOR = 4L
        /** Even the slowest content is laid out again after at most this long of a drag. */
        private const val MAX_CONTENT_WAIT_NS = 150_000_000L

        fun toolIcon(kind: String): Int = when (kind) {
            "settings" -> R.drawable.ic_settings
            "files" -> R.drawable.ic_folder
            "monitor" -> R.drawable.ic_monitor
            "notes" -> R.drawable.ic_notes
            "welcome" -> R.drawable.ic_info
            "tedit" -> LxIcons.TEDIT
            // An Android app window shows this until the app's own icon has loaded.
            "app" -> R.drawable.ic_apps
            else -> R.drawable.ic_apps
        }
    }
}
