// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.overlays

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lxlauncher.R
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface

/**
 * Stack of modal surfaces above the desktop: menus, dialogs, the Applications menu,
 * search, overview. Back and Escape close the top one.
 */
@SuppressLint("ViewConstructor")
class OverlayHost(private val shell: Shell) : FrameLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private class Entry(val view: View, val scrim: View, val onClose: (() -> Unit)?, val menu: Boolean)
    private val stack = ArrayList<Entry>()
    private var snack: View? = null
    private val hideSnack = Runnable { dismissSnack() }

    val isEmpty get() = stack.isEmpty()
    /** Menus stay below this y (the top panel) so they never cover it. */
    var menuSafeTop = 0
    fun top(): View? = stack.lastOrNull()?.view

    /**
     * Takes a drop at a point in this layer once every overlay has closed. Set by the activity,
     * which knows the panels underneath.
     */
    var dropBelow: ((Any?, Float, Float) -> Boolean)? = null

    init {
        clipChildren = false
    }

    /**
     * An app dragged out of the Applications menu closes the menu on the way. Android keeps
     * sending that drag to this layer, because it had an interested child when the drag
     * began, so the drop is passed on to what lies underneath instead of being lost.
     */
    override fun dispatchDragEvent(event: DragEvent): Boolean {
        if (event.action == DragEvent.ACTION_DROP && stack.isEmpty()) {
            return dropBelow?.invoke(event.localState, event.x, event.y) ?: false
        }
        return super.dispatchDragEvent(event)
    }

    fun push(view: View, params: LayoutParams, dimAlpha: Float = 0.45f, onClose: (() -> Unit)? = null, menu: Boolean = false) {
        val scrim = View(context).apply {
            setBackgroundColor(theme.scrim(dimAlpha))
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { pop() }
        }
        addView(scrim, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(view, params)
        stack += Entry(view, scrim, onClose, menu)
        // Earlier overlays leave the accessibility tree while covered.
        for (e in stack.dropLast(1)) e.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (theme.animations) {
            view.alpha = 0f
            view.translationY = theme.dp(12f)
            view.animate().alpha(1f).translationY(0f).setDuration(theme.durationMedium).setInterpolator(theme.easeOut).start()
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(theme.durationShort).start()
        }
        view.post { view.requestFocus() }
    }

    /** An overlay that answers Back itself before it closes, such as a menu with a submenu open. */
    interface BackHandler {
        fun onBack(): Boolean
    }

    /** Back or Escape: the top overlay may take it, otherwise it closes. */
    fun back(): Boolean {
        val top = stack.lastOrNull() ?: return false
        if ((top.view as? BackHandler)?.onBack() == true) return true
        return pop()
    }

    fun pop(): Boolean {
        val e = stack.removeLastOrNull() ?: return false
        hideKeyboard(e.view)
        removeView(e.view)
        removeView(e.scrim)
        stack.lastOrNull()?.view?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        e.onClose?.invoke()
        return true
    }

    fun popTo(view: View) {
        while (stack.isNotEmpty() && stack.last().view !== view) pop()
    }

    fun remove(view: View) {
        val i = stack.indexOfFirst { it.view === view }
        if (i < 0) return
        while (stack.size > i) pop()
    }

    /**
     * A menu is placed at the point it was opened from. After a rotation that point is elsewhere,
     * so open menus close; dialogs and search are centred and stay. The keyboard only changes the
     * height, which leaves that point where it was: a menu then closes only if it no longer fits.
     * The Applications menu looks after itself.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw <= 0 || oldh <= 0) return
        while (stack.lastOrNull()?.menu == true) {
            val lp = stack.last().view.layoutParams as LayoutParams
            if (w == oldw && lp.topMargin + lp.height <= h) break
            pop()
        }
    }

    fun clear(): Boolean {
        val had = stack.isNotEmpty()
        while (stack.isNotEmpty()) pop()
        return had
    }

    private fun hideKeyboard(v: View) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }

    // ---- building blocks ----

    // Neon belongs to the focused window and active workspace only; overlays
    // are set apart by the scrim, a denser tint and elevation.
    fun card(vertical: Boolean = true): LinearLayout = Ui.row(context, vertical, Gravity.NO_GRAVITY).apply {
        background = PanelDrawable(this, shell.glass, Surface.GLASS).apply { minOpacity = LxTheme.MENU_OPACITY }
        val p = theme.dpi(8f)
        setPadding(p, p, p, p)
        isClickable = true  // keeps taps from reaching the scrim
        elevation = theme.dp(12f)
    }

    /**
     * Context menu next to an anchor view, or at (x, y) inside it. It opens below the point and
     * slides up just enough to fit, never above the safe top. Items with children cascade.
     */
    fun menu(anchor: View, items: List<MenuItem>, x: Float, y: Float, alignStart: Boolean = false, dense: Boolean = false) {
        if (items.isEmpty()) return
        val loc = IntArray(2)
        val me = IntArray(2)
        anchor.getLocationInWindow(loc)
        getLocationInWindow(me)
        // With alignStart, x counts from the anchor's start edge, which is its right edge in a right-to-left layout.
        val ax = if (alignStart && layoutDirection == View.LAYOUT_DIRECTION_RTL) loc[0] - me[0] + anchor.width - maxOf(x, 0f)
            else loc[0] - me[0] + (if (x >= 0) x else if (alignStart) 0f else anchor.width / 2f)
        val ay = loc[1] - me[1] + (if (y >= 0) y else anchor.height.toFloat())
        val menu = CascadeMenu(this, theme, shell.glass, items, ax.toInt(), ay.toInt(), alignStart, menuSafeTop, dense)
        push(menu, LayoutParams(Ui.MATCH, Ui.MATCH), dimAlpha = 0.3f, menu = true)
        menu.firstRow?.let { first -> first.post { first.requestFocus() } }
    }

    private fun dialogParams(): LayoutParams {
        val w = (width - theme.dpi(32f)).coerceAtMost(theme.dpi(440f))
        return LayoutParams(w, Ui.WRAP, Gravity.CENTER)
    }

    private fun dialog(title: CharSequence, body: View?, buttons: List<View>): LinearLayout {
        val c = card()
        val p = theme.dpi(12f)
        c.setPadding(p, p, p, p)
        c.addView(Ui.text(context, theme, title, theme.textTitle, theme.textPrimary, bold = true, lines = 3).apply {
            setPadding(theme.dpi(4f), theme.dpi(4f), theme.dpi(4f), theme.dpi(12f))
        })
        if (body != null) c.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (buttons.isNotEmpty()) c.addView(Ui.row(context, gravity = Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setPadding(0, theme.dpi(12f), 0, 0)
            buttons.forEach { addView(it, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = theme.dpi(8f) }) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        return c
    }

    fun confirm(title: CharSequence, message: CharSequence, confirmLabel: CharSequence, danger: Boolean, onOk: () -> Unit) {
        val body = ScrollView(context).apply {
            addView(Ui.text(context, theme, message, theme.textBody, theme.textMuted, lines = 0).apply {
                setLineSpacing(theme.dp(3f), 1f)
                setPadding(theme.dpi(4f), 0, theme.dpi(4f), 0)
            })
        }
        val buttons = ArrayList<View>()
        val sameAsClose = confirmLabel == context.getString(R.string.action_close)
        if (!sameAsClose) buttons += Ui.button(context, theme, context.getString(R.string.action_cancel)) { pop() }
        buttons += Ui.button(context, theme, confirmLabel, primary = !danger) {
            pop()
            onOk()
        }.apply { if (danger) setTextColor(theme.danger) }
        val d = dialog(title, body, buttons)
        push(d, dialogParams())
        buttons.last().post { buttons.last().requestFocus() }
    }

    fun prompt(title: CharSequence, initial: String, hint: CharSequence, onOk: (String) -> Unit) {
        val field = EditText(context).apply {
            setText(initial)
            // Typing replaces the old name, as in any rename dialog.
            setSelectAllOnFocus(true)
            selectAll()
            this.hint = hint
            typeface = theme.font
            setTextColor(theme.textPrimary)
            setHintTextColor(theme.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.textBody)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            backgroundTintList = ColorStateList.valueOf(theme.accent)
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        val ok = Ui.button(context, theme, context.getString(R.string.action_ok), primary = true) {
            pop()
            onOk(field.text.toString())
        }
        field.setOnEditorActionListener { _, action, ev ->
            // IME "done", or Enter on a hardware keyboard (which arrives as IME_NULL).
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_DONE || enter) {
                ok.performClick()
                true
            } else false
        }
        val d = dialog(title, field, listOf(Ui.button(context, theme, context.getString(R.string.action_cancel)) { pop() }, ok))
        push(d, dialogParams())
        field.post {
            field.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun choose(title: CharSequence, options: List<CharSequence>, selected: Int, onPick: (Int) -> Unit) {
        val list = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
        var focus: View? = null
        options.forEachIndexed { i, label ->
            val row = Ui.row(context).apply {
                minimumHeight = theme.touchTarget
                setPadding(theme.dpi(8f), 0, theme.dpi(8f), 0)
                background = Ui.stateBackground(theme, selected = i == selected)
                isFocusable = true
                isSelected = i == selected
                addView(Ui.text(context, theme, label, theme.textBody, if (i == selected) theme.selectedText else theme.textPrimary, lines = 2),
                    Ui.lp(0, Ui.WRAP, 1f))
                if (i == selected) addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(theme.accent)
                }, Ui.lp(theme.dpi(20f), theme.dpi(20f)))
                contentDescription = label
                setOnClickListener {
                    pop()
                    onPick(i)
                }
            }
            if (i == selected || (focus == null && selected < 0)) focus = row
            list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val body = ScrollView(context).apply { addView(list) }
        val d = dialog(title, body, listOf(Ui.button(context, theme, context.getString(R.string.action_cancel)) { pop() }))
        val params = dialogParams()
        push(d, params)
        d.post {
            val maxH = height - theme.dpi(48f)
            if (d.height > maxH) {
                params.height = maxH
                d.layoutParams = params
                body.layoutParams = (body.layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 1f
                }
            }
            focus?.requestFocus()
        }
    }

    /** Transient message at the bottom, with an optional action. Not modal. */
    fun snackbar(message: CharSequence, actionLabel: CharSequence?, action: (() -> Unit)?, bottomInset: Int) {
        dismissSnack()
        val bar = Ui.row(context).apply {
            // Opaque: the bar can sit over a window, whose text must not show through it.
            background = PanelDrawable(this, shell.glass, Surface.FLAT).apply { minOpacity = 1f }
            setPadding(theme.dpi(16f), theme.dpi(4f), theme.dpi(8f), theme.dpi(4f))
            minimumHeight = theme.touchTarget
            elevation = theme.dp(10f)
            addView(Ui.text(context, theme, message, theme.textBody, theme.textPrimary, lines = 3), Ui.lp(0, Ui.WRAP, 1f))
            if (actionLabel != null && action != null) addView(Ui.button(context, theme, actionLabel) {
                dismissSnack()
                action()
            }.apply { setTextColor(theme.accent) })
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val w = (width - theme.dpi(32f)).coerceAtMost(theme.dpi(520f))
        addView(bar, LayoutParams(w, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottomInset })
        snack = bar
        bar.announceForAccessibility(message)
        removeCallbacks(hideSnack)
        postDelayed(hideSnack, if (action != null) 6000 else 3500)
    }

    private fun dismissSnack() {
        snack?.let { removeView(it) }
        snack = null
    }
}
