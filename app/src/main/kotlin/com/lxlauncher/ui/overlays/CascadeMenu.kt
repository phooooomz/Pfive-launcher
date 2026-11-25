// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.overlays

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lxlauncher.R
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface

/**
 * A menu whose items can open submenus, the way a desktop menu does. A submenu opens beside its
 * row: on the right when it fits, otherwise on the left. When neither side has room, as on a
 * phone held upright, the submenu takes its parent's place and starts with a row that leads back,
 * so the hierarchy is kept without anything running off screen. Taps, mouse hover, arrow keys,
 * Enter and Back all work, and a level taller than the screen scrolls.
 *
 * The whole cascade is one overlay entry: Back closes the deepest level first, a tap outside
 * closes everything, and choosing an item closes everything before its action runs.
 */
@SuppressLint("ViewConstructor")
// Not clickable itself: a tap on empty space falls through to the overlay's scrim, which closes the menu.
class CascadeMenu(
    private val host: OverlayHost,
    private val theme: LxTheme,
    private val glass: GlassEnv,
    private val items: List<MenuItem>,
    /** Opening point in host coordinates. */
    private val anchorX: Int,
    private val anchorY: Int,
    /** True for a menubar or button: the menu starts at [anchorX] instead of centring on it. */
    private val alignStart: Boolean,
    private val safeTop: Int,
    /** Smaller rows and type, for a tool whose own controls are small. The large target option overrides it. */
    private val dense: Boolean = false,
) : FrameLayout(host.context), OverlayHost.BackHandler {

    private class Level(val card: ScrollView, val rows: List<View?>, val items: List<MenuItem>, val parentRow: View?, val drill: Boolean)

    private val levels = ArrayList<Level>()
    private val margin = theme.dpi(8f)
    private val rowHeight = if (dense && !theme.largeTargets) theme.dpi(38f) else theme.touchTarget
    private val textSize = if (dense) theme.textBody - 1f else theme.textBody
    private val iconSize = theme.dpi(if (dense) 17f else 20f)
    private var pendingHover: Runnable? = null
    // Read from the host: this view has no parent yet while init places the first level.
    private val rtl get() = host.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** The first row that can take focus, so keyboard users start inside the menu. */
    var firstRow: View? = null
        private set

    init {
        clipChildren = false
        val level = buildLevel(items, parentRow = null, back = null)
        val w = widthOf(level.card)
        val h = measureHeight(level.card, w)
        val rawLeft = if (alignStart) (if (rtl) anchorX - w else anchorX) else anchorX - w / 2
        val left = rawLeft.coerceIn(margin, (host.width - w - margin).coerceAtLeast(margin))
        val top = (anchorY + theme.dpi(4f)).coerceAtMost(host.height - h - margin).coerceAtLeast(safeTop.coerceAtLeast(margin))
        addLevel(level, left, top, w, h)
        firstRow = level.rows.firstOrNull { it != null && it.isEnabled }
    }

    override fun onBack(): Boolean {
        if (levels.size <= 1) return false
        val parentRow = levels.last().parentRow
        closeFrom(levels.size - 1)
        parentRow?.requestFocus()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val focused = findFocus()
        val levelIndex = levels.indexOfFirst { focused != null && isInside(focused, it.card) }
        if (levelIndex < 0) return super.dispatchKeyEvent(event)
        val level = levels[levelIndex]
        val index = level.rows.indexOf(focused)
        val forward = if (rtl) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        val backward = if (rtl) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                // Up and Down stay inside the level and wrap, instead of jumping to a neighbouring card.
                val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                val n = level.rows.size
                var i = index
                repeat(n) {
                    i = if (i < 0) (if (step > 0) 0 else n - 1) else (i + step + n) % n
                    val row = level.rows[i]
                    if (row != null && row.isEnabled) {
                        row.requestFocus()
                        return true
                    }
                }
                return true
            }
            forward -> {
                val item = level.items.getOrNull(rowItemIndex(level, index)) ?: return true
                if (item.children.isNotEmpty() && focused != null) {
                    openSub(levelIndex, item, focused)
                    levels.last().rows.firstOrNull { it != null && it.isEnabled }?.requestFocus()
                }
                return true
            }
            backward -> return onBack()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /** In a drilled level the first row is the way back, so rows and items are offset by one. */
    private fun rowItemIndex(level: Level, rowIndex: Int) = if (level.drill) rowIndex - 1 else rowIndex

    private fun buildLevel(items: List<MenuItem>, parentRow: View?, back: MenuItem?): Level {
        val list = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
        val card = ScrollView(context).apply {
            background = PanelDrawable(this, glass, Surface.GLASS).apply { minOpacity = LxTheme.MENU_OPACITY }
            val p = theme.dpi(if (dense) 5f else 8f)
            setPadding(p, p, p, p)
            clipToPadding = false
            isClickable = true
            elevation = theme.dp(12f)
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        val rows = ArrayList<View?>()
        if (back != null) {
            val row = backRow(back)
            list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            list.addView(divider(), dividerParams())
            rows += row
        }
        val checkColumn = items.any { it.checked != null }
        for (item in items) {
            if (item.groupStart && list.childCount > 0) list.addView(divider(), dividerParams())
            val row = itemRow(item, checkColumn)
            list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            rows += row
        }
        return Level(card, rows, items, parentRow, drill = back != null)
    }

    private fun divider() = View(context).apply { setBackgroundColor(LxTheme.withAlpha(theme.textMuted, 0.25f)) }
    private fun dividerParams() = Ui.lp(Ui.MATCH, theme.dpi(1f)).apply {
        topMargin = theme.dpi(4f)
        bottomMargin = theme.dpi(4f)
    }

    /** Heads a drilled level with the submenu's own name; pressing it goes back up one level. */
    private fun backRow(submenu: MenuItem): View = Ui.row(context).apply {
        minimumHeight = rowHeight
        setPadding(theme.dpi(6f), 0, theme.dpi(16f), 0)
        background = Ui.stateBackground(theme)
        isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            // The chevron points forward; mirrored it points back, in either text direction.
            scaleX = -1f
            imageTintList = ColorStateList.valueOf(theme.accent)
        }, Ui.lp(iconSize, iconSize).apply { marginEnd = theme.dpi(10f) })
        addView(Ui.text(context, theme, submenu.label, textSize, theme.textPrimary, bold = true), Ui.lp(0, Ui.WRAP, 1f))
        contentDescription = context.getString(R.string.menu_back_to, submenu.label)
        setOnClickListener { onBack() }
    }

    private fun itemRow(item: MenuItem, checkColumn: Boolean): View = Ui.row(context).apply {
        minimumHeight = rowHeight
        setPadding(theme.dpi(10f), 0, theme.dpi(12f), 0)
        background = Ui.stateBackground(theme)
        isFocusable = item.enabled
        isEnabled = item.enabled
        alpha = if (item.enabled) 1f else 0.45f
        val color = if (item.danger) theme.danger else theme.textPrimary
        val slot = Ui.lp(iconSize, iconSize).apply { marginEnd = theme.dpi(if (dense) 10f else 12f) }
        when {
            item.checked == true -> addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = ColorStateList.valueOf(theme.accent)
            }, slot)
            item.icon != 0 -> addView(ImageView(context).apply {
                com.lxlauncher.ui.theme.LxIcons.applyRes(this, item.icon, if (item.danger) theme.danger else theme.accent)
            }, slot)
            checkColumn -> addView(View(context), slot)
        }
        addView(Ui.text(context, theme, item.label, textSize, color), Ui.lp(0, Ui.WRAP, 1f))
        if (item.shortcut != null) addView(Ui.text(context, theme, item.shortcut, theme.textCaption, theme.textMuted),
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = theme.dpi(12f) })
        if (item.children.isNotEmpty()) addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(theme.textMuted)
        }, Ui.lp(theme.dpi(18f), theme.dpi(18f)).apply { marginStart = theme.dpi(8f) })
        contentDescription = when {
            item.children.isNotEmpty() -> context.getString(R.string.menu_opens_submenu, item.label)
            item.checked == true -> context.getString(R.string.menu_checked, item.label)
            else -> item.label
        }
        if (item.checked != null) isSelected = item.checked
        setOnClickListener { row ->
            cancelHover()
            if (item.children.isNotEmpty()) toggleSub(row, item) else choose(item)
        }
        // A mouse opens submenus by resting on them, and leaving them for a sibling closes them.
        setOnHoverListener { row, ev ->
            if (ev.action == MotionEvent.ACTION_HOVER_ENTER) hoverOver(row, item)
            false
        }
    }

    private fun choose(item: MenuItem) {
        host.remove(this)
        item.action()
    }

    private fun levelOf(row: View) = levels.indexOfFirst { it.rows.contains(row) }

    private fun toggleSub(row: View, item: MenuItem) {
        val i = levelOf(row)
        if (i < 0) return
        if (levels.size > i + 1 && levels[i + 1].parentRow === row) {
            closeFrom(i + 1)
            return
        }
        openSub(i, item, row)
    }

    private fun hoverOver(row: View, item: MenuItem) {
        cancelHover()
        val i = levelOf(row)
        if (i < 0) return
        val alreadyOpen = levels.size > i + 1 && levels[i + 1].parentRow === row
        if (alreadyOpen || (item.children.isEmpty() && levels.size == i + 1)) return
        // Drilled levels replace their parent; hovering the parent is impossible there.
        if (levels.getOrNull(i + 1)?.drill == true) return
        val task = Runnable {
            if (item.children.isNotEmpty() && item.enabled) openSub(i, item, row) else closeFrom(i + 1)
        }
        pendingHover = task
        postDelayed(task, HOVER_DELAY_MS)
    }

    private fun cancelHover() {
        pendingHover?.let { removeCallbacks(it) }
        pendingHover = null
    }

    private fun openSub(parentIndex: Int, item: MenuItem, row: View) {
        closeFrom(parentIndex + 1)
        val parent = levels[parentIndex]
        val pl = parent.card.layoutParams as LayoutParams
        var level = buildLevel(item.children, row, back = null)
        var w = widthOf(level.card)
        val overlap = theme.dpi(4f)
        val rightX = pl.leftMargin + pl.width - overlap
        val leftX = pl.leftMargin - w + overlap
        val fitsRight = rightX + w <= host.width - margin
        val fitsLeft = leftX >= margin
        val drill = !fitsRight && !fitsLeft
        if (drill) {
            level = buildLevel(item.children, row, back = item)
            w = widthOf(level.card)
        }
        val h = measureHeight(level.card, w)
        val left = when {
            drill -> pl.leftMargin.coerceIn(margin, (host.width - w - margin).coerceAtLeast(margin))
            // Reading direction decides which side comes first.
            rtl -> if (fitsLeft) leftX else rightX
            else -> if (fitsRight) rightX else leftX
        }
        val rowTop = IntArray(2).also { row.getLocationInWindow(it) }[1] - IntArray(2).also { getLocationInWindow(it) }[1]
        val wantedTop = if (drill) pl.topMargin else rowTop - theme.dpi(8f)
        val top = wantedTop.coerceAtMost(host.height - h - margin).coerceAtLeast(safeTop.coerceAtLeast(margin))
        if (drill) parent.card.visibility = View.INVISIBLE
        addLevel(level, left, top, w, h)
        row.isActivated = true
    }

    private fun closeFrom(index: Int) {
        while (levels.size > index && levels.size > 1) {
            val l = levels.removeAt(levels.size - 1)
            removeView(l.card)
            l.parentRow?.isActivated = false
            levels.lastOrNull()?.card?.visibility = View.VISIBLE
        }
    }

    // Every position here is measured from the left, so the card is pinned left in either direction.
    @SuppressLint("RtlHardcoded")
    private fun addLevel(level: Level, left: Int, top: Int, w: Int, h: Int) {
        addView(level.card, LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left
            topMargin = top
        })
        levels += level
        if (theme.animations) {
            level.card.alpha = 0f
            level.card.animate().alpha(1f).setDuration(theme.durationShort).start()
        }
    }

    /** As wide as the longest row needs, from 264 dp (200 dp when dense) up to 360 dp, and never wider than the screen. */
    private fun widthOf(card: View): Int {
        card.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val max = theme.dpi(360f).coerceAtMost(host.width - 2 * margin)
        return card.measuredWidth.coerceIn(theme.dpi(if (dense) 200f else 264f).coerceAtMost(max), max)
    }

    private fun measureHeight(card: View, w: Int): Int {
        card.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(host.height, MeasureSpec.AT_MOST))
        return card.measuredHeight.coerceAtMost(host.height - 2 * margin)
    }

    override fun onDetachedFromWindow() {
        cancelHover()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val HOVER_DELAY_MS = 180L
    }
}
