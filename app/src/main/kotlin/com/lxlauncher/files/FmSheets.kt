// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface

/**
 * Dialogs drawn inside the File Manager window, in the window's style, so a question about a file
 * stays with the window it belongs to. Back closes the top one.
 */
class FmSheets(private val host: FrameLayout, private val theme: () -> LxTheme, private val glass: GlassEnv) {
    class Button(
        val label: CharSequence,
        val primary: Boolean = false,
        val danger: Boolean = false,
        /** False keeps the sheet open, for buttons that validate first. */
        val closes: Boolean = true,
        val action: () -> Unit = {},
    )

    private class Entry(val view: View, val cancelable: Boolean, val onDismiss: (() -> Unit)?)

    private val stack = ArrayList<Entry>()
    private val ctx get() = host.context

    val isOpen get() = stack.isNotEmpty()

    /** Closes the top sheet unless it insists on an answer. Returns true when a sheet was there. */
    fun back(): Boolean {
        val top = stack.lastOrNull() ?: return false
        if (top.cancelable) dismiss(top.view)
        return true
    }

    fun dismissAll() {
        while (stack.isNotEmpty()) dismiss(stack.last().view)
    }

    fun dismiss(view: View) {
        val i = stack.indexOfFirst { it.view === view }
        if (i < 0) return
        val e = stack.removeAt(i)
        ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(e.view.windowToken, 0)
        host.removeView(e.view)
        e.onDismiss?.invoke()
    }

    /** Shows a sheet and returns its root, which [dismiss] accepts. */
    fun show(title: CharSequence, body: View?, buttons: List<Button>, cancelable: Boolean = true, onDismiss: (() -> Unit)? = null): View {
        val t = theme()
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(t.scrim(0.45f))
            isClickable = true
            isFocusable = false
            // Above the sidebar drawer, which has elevation of its own. The scrim itself casts no shadow.
            translationZ = t.dp(16f)
            outlineProvider = null
        }
        val card = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            // Opaque: the window's own content right behind a dialog must not show through its text.
            background = PanelDrawable(this, glass, Surface.GLASS).apply { minOpacity = 1f }
            val p = t.dpi(12f)
            setPadding(p, p, p, t.dpi(8f))
            isClickable = true
            elevation = t.dp(12f)
        }
        card.addView(Ui.text(ctx, t, title, t.textBody, t.textPrimary, bold = true, lines = 3).apply {
            setPadding(t.dpi(2f), 0, t.dpi(2f), t.dpi(8f))
            accessibilityHeading()
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        if (body != null) card.addView(ScrollView(ctx).apply {
            isFillViewport = false
            addView(body)
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        val views = buttons.map { b ->
            compact(Ui.button(ctx, t, b.label, primary = b.primary) {
                if (b.closes) dismiss(root)
                b.action()
            }, t).apply { if (b.danger) setTextColor(t.danger) }
        }
        // Answers stack when there are three, or two on a narrow window, so no label is cut short.
        val stacked = views.size >= 3 || (views.size == 2 && host.width < t.dpi(300f))
        if (views.isNotEmpty()) card.addView(Ui.row(ctx, vertical = stacked,
            gravity = if (stacked) Gravity.NO_GRAVITY else Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setPadding(0, t.dpi(10f), 0, 0)
            views.forEach {
                if (stacked) addView(it, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(4f) })
                else addView(it, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = t.dpi(6f) })
            }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        val w = (host.width - t.dpi(32f)).coerceAtMost(t.dpi(320f)).coerceAtLeast(t.dpi(200f))
        root.addView(card, FrameLayout.LayoutParams(w, Ui.WRAP, Gravity.CENTER))
        if (cancelable) root.setOnClickListener { dismiss(root) }
        host.addView(root, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        stack += Entry(root, cancelable, onDismiss)
        // A sheet taller than the window scrolls its body instead of losing its buttons.
        root.post {
            val maxH = host.height - t.dpi(24f)
            if (card.height > maxH) card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply { height = maxH }
            (card.getChildAt(1) as? ScrollView)?.let { sv ->
                if (card.height >= maxH) sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
            }
            (views.lastOrNull { it.isEnabled } ?: card).requestFocus()
        }
        return root
    }

    fun message(title: CharSequence, message: CharSequence) {
        show(title, bodyText(message), listOf(Button(ctx.getString(R.string.action_close), primary = true)))
    }

    fun confirm(title: CharSequence, message: CharSequence, confirmLabel: CharSequence, danger: Boolean, onOk: () -> Unit) {
        show(title, bodyText(message), listOf(
            Button(ctx.getString(R.string.action_cancel)),
            Button(confirmLabel, primary = !danger, danger = danger, action = onOk),
        ))
    }

    /**
     * Asks for one line of text. [validate] returns an error to show under the field, or null;
     * the sheet stays open until the text passes.
     */
    fun prompt(title: CharSequence, initial: String, hint: CharSequence, okLabel: CharSequence,
               selectBaseName: Boolean = false, validate: (String) -> CharSequence?, onOk: (String) -> Unit) {
        val t = theme()
        val error = Ui.text(ctx, t, "", t.textLabel, t.danger, lines = 3).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val field = EditText(ctx).apply {
            setText(initial)
            this.hint = hint
            typeface = t.font
            setTextColor(t.textPrimary)
            setHintTextColor(t.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textBody - 1f)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            backgroundTintList = ColorStateList.valueOf(t.accent)
            filters = arrayOf(android.text.InputFilter.LengthFilter(255))
        }
        // Renaming "report.pdf" selects "report", so typing keeps the extension.
        val dot = initial.lastIndexOf('.')
        if (selectBaseName && dot > 0) field.setSelection(0, dot) else field.selectAll()
        val body = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(field, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(error, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(4f) })
        }
        lateinit var sheet: View
        val submit = {
            val text = field.text.toString()
            val problem = validate(text)
            if (problem == null) {
                dismiss(sheet)
                onOk(text)
            } else {
                error.text = problem
                error.visibility = View.VISIBLE
            }
        }
        sheet = show(title, body, listOf(
            Button(ctx.getString(R.string.action_cancel)),
            Button(okLabel, primary = true, closes = false) { submit() },
        ))
        field.setOnEditorActionListener { _, action, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (action == EditorInfo.IME_ACTION_DONE || enter) {
                submit()
                true
            } else false
        }
        field.post {
            field.requestFocus()
            ctx.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun bodyText(message: CharSequence): TextView {
        val t = theme()
        return Ui.text(ctx, t, message, t.textBody - 1f, t.textMuted, lines = 0).apply {
            setLineSpacing(t.dp(3f), 1f)
            setPadding(t.dpi(2f), 0, t.dpi(2f), 0)
            setTextIsSelectable(false)
        }
    }

    companion object {
        /** A button at the File Manager's size; the large target option keeps its full height. */
        fun compact(button: TextView, t: LxTheme) = button.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.textBody - 1f)
            minHeight = if (t.largeTargets) t.touchTarget else t.dpi(38f)
            minWidth = t.dpi(72f)
            setPadding(t.dpi(12f), 0, t.dpi(12f), 0)
        }
    }

    private fun View.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }
}
