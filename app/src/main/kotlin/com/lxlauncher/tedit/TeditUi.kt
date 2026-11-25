// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R

/** One row of a Tedit menu. A disabled row says why in [reason]; a row with [children] opens a submenu. */
class TItem(
    val label: CharSequence,
    val glyph: TIcon.Glyph? = null,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val reason: CharSequence? = null,
    val danger: Boolean = false,
    val checked: Boolean? = null,
    val children: List<TItem>? = null,
    val divider: Boolean = false,
    val action: (() -> Unit)? = null,
)

/** View builders in Tedit's palette, so every control has the same states, sizes and focus ring. */
class TeditUi(val ctx: Context, var t: TeditTheme) {
    fun text(value: CharSequence = "", size: Float = t.textBody, color: Int = t.text, medium: Boolean = false, lines: Int = 1): TextView =
        TextView(ctx).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = if (medium) t.uiFontMedium else t.uiFont
            includeFontPadding = false
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        }

    /** Pressed, hovered, selected and focused states; focus draws a 2 dp ring in the accent. */
    fun states(radius: Float = t.radius, selected: Boolean = false, base: Int = 0): Drawable {
        fun shape(color: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (strokeW > 0) setStroke(strokeW, stroke)
        }
        val list = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(if (selected) t.selectedRow else t.hover, t.accent, t.dpi(2f)))
            addState(intArrayOf(android.R.attr.state_pressed), shape(t.pressed))
            addState(intArrayOf(android.R.attr.state_hovered), shape(t.hover))
            addState(intArrayOf(android.R.attr.state_selected), shape(t.selectedRow))
            addState(intArrayOf(android.R.attr.state_activated), shape(t.selectedRow))
            addState(intArrayOf(), shape(if (selected) t.selectedRow else base))
        }
        return RippleDrawable(ColorStateList.valueOf(TeditTheme.withAlpha(t.text, 0.10f)), list, shape(0xFFFFFFFF.toInt()))
    }

    fun icon(glyph: TIcon.Glyph, color: Int = t.text2, sizeDp: Float = t.iconSize): ImageView = ImageView(ctx).apply {
        setImageDrawable(TIcon(glyph, color))
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(t.dpi(sizeDp), t.dpi(sizeDp))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun iconButton(glyph: TIcon.Glyph, label: CharSequence, color: Int = t.text2, size: Int = t.touch, onClick: (View) -> Unit): ImageView =
        ImageView(ctx).apply {
            setImageDrawable(TIcon(glyph, color))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = ((size - t.dpi(t.iconSize)) / 2).coerceAtLeast(0)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = label
            tooltipText = label
            background = states()
            isFocusable = true
            setOnClickListener { onClick(it) }
        }

    fun setIconColor(v: ImageView, color: Int) {
        (v.drawable as? TIcon)?.let { it.color = color; v.invalidate() }
    }

    fun button(label: CharSequence, primary: Boolean = false, danger: Boolean = false, onClick: (View) -> Unit): TextView =
        text(label, t.textBody, when { primary -> t.onAccent; danger -> t.error; else -> t.text }, medium = true).apply {
            gravity = Gravity.CENTER
            minHeight = t.touch
            minWidth = t.dpi(72f)
            setPadding(t.dpi(14f), 0, t.dpi(14f), 0)
            background = if (primary) {
                val fill = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.accentFill) }
                val focused = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.accentFill); setStroke(t.dpi(2f), t.text) }
                RippleDrawable(ColorStateList.valueOf(TeditTheme.withAlpha(t.onAccent, 0.2f)),
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), focused)
                        addState(intArrayOf(), fill)
                    }, null)
            } else {
                val base = GradientDrawable().apply { cornerRadius = t.radius; setColor(0); setStroke(t.stroke, t.border) }
                val focused = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.hover); setStroke(t.dpi(2f), t.accent) }
                RippleDrawable(ColorStateList.valueOf(TeditTheme.withAlpha(t.text, 0.1f)),
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), focused)
                        addState(intArrayOf(), base)
                    }, null)
            }
            isFocusable = true
            setOnClickListener { onClick(it) }
        }

    fun field(hint: CharSequence, value: String = "", mono: Boolean = false): EditText = EditText(ctx).apply {
        setText(value)
        this.hint = hint
        setTextColor(t.text)
        setHintTextColor(t.textMuted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textBody)
        typeface = if (mono) t.codeFont else t.uiFont
        isSingleLine = true
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minHeight = t.control
        setPadding(t.dpi(10f), t.dpi(6f), t.dpi(10f), t.dpi(6f))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = t.radius; setColor(t.bg); setStroke(t.dpi(1.5f), t.accent) })
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = t.radius; setColor(t.bg); setStroke(t.stroke, t.border) })
        }
        highlightColor = t.selection
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply { setColor(t.accent); setSize(t.dpi(2f), 1) }
    }

    fun divider(vertical: Boolean = false): View = View(ctx).apply {
        setBackgroundColor(t.border)
        layoutParams = if (vertical) LinearLayout.LayoutParams(t.stroke, ViewGroup.LayoutParams.MATCH_PARENT)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.stroke)
    }

    fun row(vertical: Boolean = false, gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply {
        orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    /** A small toggle: a rounded track with a knob, which an editor's settings use by the dozen. */
    fun toggle(on: Boolean, label: CharSequence, onChange: (Boolean) -> Unit): ToggleView = ToggleView(ctx, t, on, label, onChange)

    /** A section label in a side panel. */
    fun sectionTitle(label: CharSequence): TextView = text(label, t.textCaption, t.textMuted, medium = true).apply {
        setPadding(t.dpi(14f), t.dpi(12f), t.dpi(14f), t.dpi(6f))
        isAllCaps = false
        letterSpacing = 0.02f
    }

    /** Text for an empty, loading or failed state, centred in its panel. */
    fun note(message: CharSequence, color: Int = t.textMuted): TextView = text(message, t.textSmall, color, lines = 0).apply {
        setPadding(t.dpi(16f), t.dpi(14f), t.dpi(16f), t.dpi(14f))
        setLineSpacing(0f, 1.25f)
    }

    fun scroll(child: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        addView(child)
    }

    companion object {
        fun hideKeyboard(v: View) {
            (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
        }

        /** "On", "open" and the like for screen readers; Android has the property from version 11. */
        fun stateDesc(v: View, text: CharSequence?) {
            if (android.os.Build.VERSION.SDK_INT >= 30) v.stateDescription = text
        }

        fun showKeyboard(v: View) {
            v.requestFocus()
            v.post { (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0) }
        }
    }
}

/**
 * A side panel's column: a head (query, tabs, filters) above a body that holds the results, with anything fixed between
 * them. In a short panel a body with something in it keeps [minBody] and the head, which should scroll, takes the rest;
 * when not even [minHead] is left for the head, it takes the whole height. An empty body gives up only a sliver too thin
 * for a row. The body is never hidden: hiding the Search panel's empty list made the Infinix's ROM stop boosting the CPU
 * while windows were resized (0 boosts against 24, and three times the slow frames, 14 November 2026).
 */
@SuppressLint("ViewConstructor")
class PanelColumn(context: Context, private val minHead: Int, private val minBody: Int, private val bodyEmpty: () -> Boolean) : LinearLayout(context) {
    private var head: View? = null
    private var body: View? = null

    init { orientation = VERTICAL }

    fun setParts(head: View, body: View) {
        this.head = head
        this.body = body
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = head
        val b = body
        val lp = h?.layoutParams as? LayoutParams
        lp?.height = LayoutParams.WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (h == null || b == null || lp == null || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val left = b.measuredHeight
        if (bodyEmpty()) {
            if (left in 1 until minBody) {
                lp.height = h.measuredHeight + left
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
            return
        }
        if (left >= minBody) return
        val room = h.measuredHeight + left
        lp.height = if (room >= minHead + minBody) room - minBody else room
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Lays its children out left to right and moves to a new line when one no longer fits: buttons in a narrow panel. */
@SuppressLint("ViewConstructor")
class FlowRow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            // A child with a set size (an icon button) keeps it; the rest take what their content needs.
            val lp = c.layoutParams
            c.measure(if (lp != null && lp.width >= 0) MeasureSpec.makeMeasureSpec(minOf(lp.width, maxW), MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                if (lp != null && lp.height >= 0) MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) else MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(y + lineH + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

@SuppressLint("ViewConstructor")
class ToggleView(context: Context, private val t: TeditTheme, on: Boolean, label: CharSequence, private val onChange: (Boolean) -> Unit) : View(context) {
    var on: Boolean = on
        set(v) { field = v; invalidate(); refreshDescription() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = label

    init {
        isFocusable = true
        isClickable = true
        background = TeditUi(context, t).states(t.dp(14f))
        refreshDescription()
        setOnClickListener {
            this.on = !this.on
            onChange(this.on)
        }
        minimumWidth = t.dpi(52f)
        minimumHeight = t.touch
    }

    private fun refreshDescription() {
        contentDescription = label
        TeditUi.stateDesc(this, context.getString(if (on) R.string.tedit_v_on else R.string.tedit_v_off))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(t.dpi(52f), widthMeasureSpec), resolveSize(t.touch, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = t.dp(36f)
        val h = t.dp(20f)
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        paint.style = Paint.Style.FILL
        paint.color = if (on) t.accentFill else t.active
        canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, paint)
        if (!on) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = t.dp(1f)
            paint.color = t.border
            canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, paint)
            paint.style = Paint.Style.FILL
        }
        paint.color = if (on) t.onAccent else t.text2
        val r = h / 2 - t.dp(3f)
        val cx = if (on) left + w - h / 2 else left + h / 2
        canvas.drawCircle(cx, top + h / 2, r, paint)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER) { performClick(); return true }
        return super.onKeyUp(keyCode, event)
    }
}

/**
 * Dialogs, menus and short messages drawn inside the Tedit window, in its palette. Each layer sits over a scrim;
 * Back, Escape and a tap on the scrim close the top one.
 */
class Overlays(private val host: FrameLayout, private val ui: TeditUi) {
    private val t get() = ui.t
    private val stack = ArrayList<View>()
    val isEmpty get() = stack.isEmpty()

    fun closeTop(): Boolean {
        val top = stack.removeLastOrNull() ?: return false
        TeditUi.hideKeyboard(top)
        host.removeView(top)
        return true
    }

    fun closeAll() { while (closeTop()) Unit }

    private fun layer(content: View, gravity: Int, dim: Boolean = true, onDismiss: (() -> Unit)? = null): FrameLayout {
        onCover?.invoke()
        val layer = object : FrameLayout(host.context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE || event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) { if (stack.lastOrNull() === this) { closeTop(); onDismiss?.invoke() } }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        layer.setBackgroundColor(if (dim) t.scrim else 0)
        layer.isClickable = true
        layer.isFocusable = false
        layer.setOnClickListener { if (stack.lastOrNull() === layer) { closeTop(); onDismiss?.invoke() } }
        content.isClickable = true
        layer.addView(content, FrameLayout.LayoutParams(content.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, gravity))
        host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stack += layer
        if (t.animations) {
            content.alpha = 0f
            content.translationY = t.dp(8f)
            content.animate().alpha(1f).translationY(0f).setDuration(t.durShort).start()
        }
        return layer
    }

    private fun card(): LinearLayout = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        background = GradientDrawable().apply { cornerRadius = t.radiusL; setColor(t.panel); setStroke(t.stroke, t.border) }
        elevation = t.dp(12f)
        setPadding(t.dpi(18f), t.dpi(16f), t.dpi(18f), t.dpi(12f))
    }

    private fun width(): Int = minOf(host.width - t.dpi(32f), t.dpi(420f)).coerceAtLeast(t.dpi(240f))

    /** A dialog with a title, a body and buttons in the end corner. Returns a function that closes it. */
    fun dialog(title: CharSequence, body: View?, buttons: List<Pair<View, Boolean>>, onDismiss: (() -> Unit)? = null): () -> Unit {
        val c = card()
        c.addView(ui.text(title, t.textTitle, t.text, medium = true, lines = 2), ui.lp(Ui_MATCH, Ui_WRAP))
        if (body != null) {
            c.addView(body, ui.lp(Ui_MATCH, Ui_WRAP).apply { topMargin = t.dpi(12f) })
        }
        val row = ui.row(gravity = Gravity.END or Gravity.CENTER_VERTICAL)
        for ((b, _) in buttons) row.addView(b, ui.lp(Ui_WRAP, Ui_WRAP).apply { marginStart = t.dpi(8f) })
        c.addView(row, ui.lp(Ui_MATCH, Ui_WRAP).apply { topMargin = t.dpi(16f) })
        c.layoutParams = FrameLayout.LayoutParams(width(), ViewGroup.LayoutParams.WRAP_CONTENT)
        val layer = layer(c, Gravity.CENTER, onDismiss = onDismiss)
        buttons.firstOrNull { it.second }?.first?.requestFocus()
        return { if (stack.contains(layer)) { stack.remove(layer); TeditUi.hideKeyboard(layer); host.removeView(layer) } }
    }

    /** Called before a page or menu covers the editor, so popups that belong to it close. */
    var onCover: (() -> Unit)? = null

    /** A layer filling the window (the Settings page). Back and Escape close it. */
    fun page(content: View): () -> Unit {
        onCover?.invoke()
        val layer = object : FrameLayout(host.context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (event.action == KeyEvent.ACTION_UP && stack.lastOrNull() === this) closeTop()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        layer.isClickable = true
        layer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stack += layer
        if (t.animations) { content.alpha = 0f; content.animate().alpha(1f).setDuration(t.durShort).start() }
        return { if (stack.contains(layer)) { stack.remove(layer); TeditUi.hideKeyboard(layer); host.removeView(layer) } }
    }

    fun message(title: CharSequence, message: CharSequence) {
        var close: () -> Unit = {}
        val ok = ui.button(host.context.getString(R.string.tedit_ok), primary = true) { close() }
        close = dialog(title, ui.text(message, t.textBody, t.text2, lines = 0).apply { setLineSpacing(0f, 1.25f) }, listOf(ok to true))
    }

    fun confirm(title: CharSequence, message: CharSequence, confirm: CharSequence, danger: Boolean = false, onOk: () -> Unit) {
        var close: () -> Unit = {}
        val cancel = ui.button(host.context.getString(R.string.tedit_cancel)) { close() }
        val ok = ui.button(confirm, primary = !danger, danger = danger) { close(); onOk() }
        close = dialog(title, ui.text(message, t.textBody, t.text2, lines = 0).apply { setLineSpacing(0f, 1.25f) },
            listOf(cancel to false, ok to !danger))
        if (danger) cancel.requestFocus()
    }

    /** Three answers, for closing a file with changes: [save], [discard], or cancel. */
    fun saveChanges(name: String, onSave: () -> Unit, onDiscard: () -> Unit) {
        var close: () -> Unit = {}
        val ctx = host.context
        val cancel = ui.button(ctx.getString(R.string.tedit_cancel)) { close() }
        val discard = ui.button(ctx.getString(R.string.tedit_dont_save), danger = true) { close(); onDiscard() }
        val save = ui.button(ctx.getString(R.string.tedit_save), primary = true) { close(); onSave() }
        close = dialog(ctx.getString(R.string.tedit_unsaved_title, name),
            ui.text(ctx.getString(R.string.tedit_unsaved_message), t.textBody, t.text2, lines = 0),
            listOf(cancel to false, discard to false, save to true))
    }

    fun prompt(title: CharSequence, initial: String, hint: CharSequence, confirm: CharSequence, selectStem: Boolean = false,
               validate: (String) -> CharSequence? = { null }, onOk: (String) -> Unit) {
        var close: () -> Unit = {}
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val field = ui.field(hint, initial)
        val error = ui.text("", t.textSmall, t.error, lines = 2).apply { visibility = View.GONE }
        col.addView(field, ui.lp(Ui_MATCH, Ui_WRAP))
        col.addView(error, ui.lp(Ui_MATCH, Ui_WRAP).apply { topMargin = t.dpi(6f) })
        fun submit() {
            val v = field.text.toString()
            val problem = validate(v)
            if (problem != null) { error.text = problem; error.visibility = View.VISIBLE; return }
            close()
            onOk(v)
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        field.setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_ENTER && e.action == KeyEvent.ACTION_UP) { submit(); true } else false }
        val cancel = ui.button(host.context.getString(R.string.tedit_cancel)) { close() }
        val ok = ui.button(confirm, primary = true) { submit() }
        close = dialog(title, col, listOf(cancel to false, ok to false))
        field.requestFocus()
        if (selectStem && initial.contains('.') && !initial.startsWith(".")) field.setSelection(0, initial.lastIndexOf('.')) else field.selectAll()
        TeditUi.showKeyboard(field)
    }

    fun choose(title: CharSequence, options: List<CharSequence>, selected: Int, onPick: (Int) -> Unit) {
        var close: () -> Unit = {}
        val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        options.forEachIndexed { i, label ->
            val row = ui.row().apply {
                minimumHeight = t.touch
                background = ui.states(selected = i == selected)
                isFocusable = true
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                contentDescription = label
                setOnClickListener { close(); onPick(i) }
            }
            row.addView(ui.text(label, t.textBody, if (i == selected) t.accentText else t.text, medium = i == selected), ui.lp(0, Ui_WRAP, 1f))
            if (i == selected) row.addView(ui.icon(TIcon.Glyph.CHECK, t.accentText, 18f))
            col.addView(row, ui.lp(Ui_MATCH, Ui_WRAP))
        }
        val scroll = ui.scroll(col).apply { layoutParams = ui.lp(Ui_MATCH, minOf(options.size * t.touch, (host.height * 0.6f).toInt())) }
        val cancel = ui.button(host.context.getString(R.string.tedit_cancel)) { close() }
        close = dialog(title, scroll, listOf(cancel to false))
        col.getChildAt(selected.coerceIn(0, maxOf(0, options.size - 1)))?.requestFocus()
    }

    /**
     * A menu beside [anchor] (or at x, y inside it). A row with children opens them in place with a back row,
     * which works with one thumb on a phone. Disabled rows show their reason under the label.
     */
    fun menu(anchor: View, items: List<TItem>, x: Float = -1f, y: Float = -1f, title: CharSequence? = null) {
        onCover?.invoke()
        val c = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            background = GradientDrawable().apply { cornerRadius = t.radiusL; setColor(t.panel); setStroke(t.stroke, t.border) }
            elevation = t.dp(14f)
            setPadding(0, t.dpi(6f), 0, t.dpi(6f))
        }
        val list = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val scroll = ui.scroll(list)
        c.addView(scroll, ui.lp(Ui_MATCH, Ui_WRAP))
        val menuW = minOf(host.width - t.dpi(16f), t.dpi(300f))
        c.layoutParams = FrameLayout.LayoutParams(menuW, ViewGroup.LayoutParams.WRAP_CONTENT)
        lateinit var layer: FrameLayout
        val pages = ArrayList<Pair<CharSequence?, List<TItem>>>()

        fun fill(page: Pair<CharSequence?, List<TItem>>) {
            list.removeAllViews()
            val (heading, rows) = page
            if (pages.size > 1 || heading != null) {
                val back = ui.row().apply {
                    minimumHeight = t.touch
                    setPadding(t.dpi(8f), 0, t.dpi(14f), 0)
                    background = ui.states(0f)
                    isFocusable = pages.size > 1
                }
                if (pages.size > 1) back.addView(ui.icon(TIcon.Glyph.CHEVRON_LEFT, t.text2, 20f), ui.lp(t.dpi(28f), t.dpi(20f)))
                back.addView(ui.text(heading ?: "", t.textSmall, t.textMuted, medium = true), ui.lp(0, Ui_WRAP, 1f))
                if (pages.size > 1) {
                    back.contentDescription = host.context.getString(R.string.tedit_back)
                    back.setOnClickListener { pages.removeAt(pages.size - 1); fill(pages.last()) }
                }
                list.addView(back, ui.lp(Ui_MATCH, Ui_WRAP))
            }
            for (item in rows) {
                if (item.divider) list.addView(ui.divider().apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, t.dpi(4f), 0, t.dpi(4f)) })
                val row = ui.row().apply {
                    minimumHeight = t.touch
                    setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(4f))
                    background = ui.states(0f)
                    isFocusable = true
                    contentDescription = item.label
                    alpha = if (item.enabled) 1f else 0.62f
                }
                val color = when { !item.enabled -> t.textMuted; item.danger -> t.error; else -> t.text }
                if (item.checked != null) row.addView(ui.icon(TIcon.Glyph.CHECK, if (item.checked) t.accentText else 0, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(10f) })
                else if (item.glyph != null) row.addView(ui.icon(item.glyph, if (item.danger && item.enabled) t.error else t.text2, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(10f) })
                else row.addView(View(host.context), ui.lp(t.dpi(18f), 1).apply { marginEnd = t.dpi(10f) })
                val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
                labels.addView(ui.text(item.label, t.textBody, color, lines = 1))
                if (!item.enabled && item.reason != null) labels.addView(ui.text(item.reason, t.textCaption, t.textMuted, lines = 2).apply { setPadding(0, t.dpi(2f), 0, 0) })
                row.addView(labels, ui.lp(0, Ui_WRAP, 1f))
                if (item.shortcut != null && item.enabled) row.addView(ui.text(item.shortcut, t.textCaption, t.textMuted).apply { setPadding(t.dpi(8f), 0, 0, 0) })
                if (item.children != null) row.addView(ui.icon(TIcon.Glyph.CHEVRON_RIGHT, t.text2, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginStart = t.dpi(6f) })
                row.setOnClickListener {
                    when {
                        !item.enabled -> item.reason?.let { r -> row.announceForAccessibility(r) }
                        item.children != null -> { pages += item.label to item.children; fill(pages.last()) }
                        else -> {
                            if (stack.contains(layer)) { stack.remove(layer); host.removeView(layer) }
                            item.action?.invoke()
                        }
                    }
                }
                list.addView(row, ui.lp(Ui_MATCH, Ui_WRAP))
            }
            list.post { (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable && it.alpha == 1f }?.let { if (!host.isInTouchMode) it.requestFocus() } }
        }

        pages += title to items
        fill(pages.last())
        layer = layer(c, Gravity.TOP or Gravity.START, dim = false)
        layer.setBackgroundColor(TeditTheme.withAlpha(t.bg, 0.01f))
        // Place beside the anchor, kept inside the window.
        val loc = IntArray(2)
        val hostLoc = IntArray(2)
        anchor.getLocationInWindow(loc)
        host.getLocationInWindow(hostLoc)
        val ax = loc[0] - hostLoc[0] + if (x >= 0) x else anchor.width / 2f
        val ay = loc[1] - hostLoc[1] + if (y >= 0) y else anchor.height.toFloat()
        c.visibility = View.INVISIBLE
        c.post {
            val maxH = host.height - t.dpi(16f)
            if (c.height > maxH) { c.layoutParams = (c.layoutParams as FrameLayout.LayoutParams).apply { height = maxH } }
            val left = (ax - if (x >= 0) 0f else menuW / 2f).coerceIn(t.dp(8f), (host.width - menuW - t.dp(8f)).coerceAtLeast(t.dp(8f)))
            val h = minOf(c.height, maxH)
            val top = if (ay + h + t.dp(8f) <= host.height) ay else (ay - h - (if (y >= 0) 0 else anchor.height)).coerceAtLeast(t.dp(8f))
            c.translationX = left
            c.translationY = top.coerceIn(t.dp(8f), (host.height - h - t.dp(8f)).coerceAtLeast(t.dp(8f)))
            c.visibility = View.VISIBLE
        }
    }

    /** A short message at the bottom of the window, with an optional action. */
    fun snack(message: CharSequence, action: CharSequence? = null, onAction: (() -> Unit)? = null) {
        val bar = ui.row().apply {
            background = GradientDrawable().apply { cornerRadius = t.radius; setColor(t.active); setStroke(t.stroke, t.border) }
            elevation = t.dp(10f)
            setPadding(t.dpi(14f), t.dpi(4f), t.dpi(6f), t.dpi(4f))
            minimumHeight = t.touch
        }
        bar.addView(ui.text(message, t.textSmall, t.text, lines = 3), ui.lp(0, Ui_WRAP, 1f))
        if (action != null) bar.addView(ui.text(action, t.textSmall, t.accentText, medium = true).apply {
            gravity = Gravity.CENTER
            minHeight = t.touch
            setPadding(t.dpi(12f), 0, t.dpi(12f), 0)
            background = ui.states()
            isFocusable = true
            setOnClickListener { host.removeView(bar); onAction?.invoke() }
        })
        val lp = FrameLayout.LayoutParams(minOf(host.width - t.dpi(24f), t.dpi(480f)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = t.statusH + t.dpi(12f)
        host.findViewWithTag<View>(SNACK_TAG)?.let { host.removeView(it) }
        bar.tag = SNACK_TAG
        host.addView(bar, lp)
        bar.announceForAccessibility(message)
        if (t.animations) { bar.alpha = 0f; bar.animate().alpha(1f).setDuration(t.durShort).start() }
        bar.postDelayed({ if (bar.parent === host) host.removeView(bar) }, if (action != null) 6000L else 3200L)
    }

    /** A determinate progress strip over the top of [host], for loading a large file. */
    fun progressBar(): ProgressStrip = ProgressStrip(host.context, t)

    companion object {
        private const val SNACK_TAG = "tedit-snack"
        const val Ui_MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val Ui_WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

@SuppressLint("ViewConstructor")
class ProgressStrip(context: Context, private val t: TeditTheme) : View(context) {
    var fraction = -1f
        set(v) { field = v; invalidate() }
    private val paint = Paint()
    private var phase = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { phase = it.animatedValue as Float; if (fraction < 0) invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (t.animations) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        paint.color = t.active
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = t.accent
        if (fraction >= 0) canvas.drawRect(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat(), paint)
        else {
            val w = width * 0.3f
            val x = (width + w) * phase - w
            canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
        }
    }
}
