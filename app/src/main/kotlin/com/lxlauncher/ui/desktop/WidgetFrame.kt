// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.desktop

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import com.lxlauncher.R
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface
import kotlin.math.abs

/**
 * Detects a long press on a view group whose children consume touches, the
 * way Launcher3 does for app widgets.
 */
class LongPressWatcher(private val host: View, private val onLongPress: () -> Unit) {
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    var fired = false
        private set
    /** True from finger down to finger up, so a host can tell touch long presses from accessibility ones. */
    var tracking = false
        private set
    private val trigger = Runnable {
        fired = true
        host.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress()
    }

    /** Returns true when the gesture became a long press and should be intercepted. */
    fun onEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                tracking = true
                downX = ev.x
                downY = ev.y
                // The same down event can arrive twice (intercept, then the host's own onTouchEvent);
                // a second post would open the menu twice.
                host.removeCallbacks(trigger)
                host.postDelayed(trigger, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) host.removeCallbacks(trigger)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                host.removeCallbacks(trigger)
            }
            MotionEvent.ACTION_POINTER_DOWN -> host.removeCallbacks(trigger)
        }
        return fired
    }

    fun cancel() = host.removeCallbacks(trigger)
}

/** AppWidgetHostView that reports long presses to the launcher. */
class LxHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null
    private val watcher = LongPressWatcher(this) { onLongPress?.invoke() }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = watcher.onEvent(ev) || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        watcher.onEvent(event)
        return watcher.fired || super.onTouchEvent(event)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        watcher.cancel()
    }
}

class LxAppWidgetHost(context: Context, id: Int) : AppWidgetHost(context, id) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LxHostView(context)
}

/**
 * Frame around one widget. A long press picks it up: moving the finger drags
 * the widget, lifting it without a move opens the widget menu. In edit mode the
 * body drags the widget and the corner handle resizes it. The desktop converts
 * the result to canvas fractions and stores it in the native core.
 */
@SuppressLint("ViewConstructor")
class WidgetFrame(
    private val shell: Shell,
    val widgetId: Int,
    private val callbacks: Callbacks,
) : FrameLayout(shell.activity) {
    interface Callbacks {
        fun onWidgetMenu(frame: WidgetFrame)
        fun onWidgetDragged(frame: WidgetFrame, dx: Float, dy: Float, done: Boolean)
        fun onWidgetResized(frame: WidgetFrame, dw: Float, dh: Float, done: Boolean)
    }

    private val theme: LxTheme get() = shell.ui
    private val panel = PanelDrawable(this, shell.glass, Surface.FLAT)
    var content: View? = null
        private set
    var editing = false
        set(v) {
            field = v
            handle.visibility = if (v) View.VISIBLE else View.GONE
            menuButton.visibility = if (v) View.VISIBLE else View.GONE
            panel.focused = v
            invalidate()
        }

    /** True after a touch long press until the finger lifts; the widget is in hand. */
    var armed = false
        private set
    private var pressDrag = false
    private val slop = ViewConfiguration.get(shell.activity).scaledTouchSlop
    private var curX = 0f
    private var curY = 0f
    private var armX = 0f
    private var armY = 0f
    private val watcher = LongPressWatcher(this) {
        armed = true
        pressDrag = false
        armX = curX
        armY = curY
        panel.focused = true
        // The desktop would take a sideways move as a workspace swipe.
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }
    private val handle = ResizeHandle()
    private val menuButton = Ui.iconButton(context, theme, R.drawable.ic_more, context.getString(R.string.widget_options), 40f, 20f) {
        callbacks.onWidgetMenu(this)
    }
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        background = panel
        clipToOutline = false
        isFocusable = true
        handle.visibility = View.GONE
        menuButton.visibility = View.GONE
        // Touch long presses go through the watcher, which also sees presses over child views;
        // this listener serves accessibility actions, which bring no touch events.
        setOnLongClickListener {
            if (!watcher.tracking) callbacks.onWidgetMenu(this)
            true
        }
        setOnContextClickListener {
            callbacks.onWidgetMenu(this)
            true
        }
    }

    fun setContent(v: View) {
        content?.let { removeView(it) }
        content = v
        addView(v, 0, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (handle.parent == null) {
            addView(handle, LayoutParams(theme.dpi(36f), theme.dpi(36f), Gravity.END or Gravity.BOTTOM))
            addView(menuButton, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.TOP))
        } else {
            handle.bringToFront()
            menuButton.bringToFront()
        }
    }

    private fun touchInFocusedEditor(ev: MotionEvent): Boolean {
        val focused = findFocus() as? EditText ?: return false
        val loc = IntArray(2)
        val me = IntArray(2)
        focused.getLocationInWindow(loc)
        getLocationInWindow(me)
        val x = ev.x + me[0] - loc[0]
        val y = ev.y + me[1] - loc[1]
        return x >= 0 && y >= 0 && x < focused.width && y < focused.height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (editing) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                lastX = ev.rawX
                lastY = ev.rawY
            }
            // In edit mode the body is a drag handle; the handle and menu keep their touches.
            val onChild = hit(handle, ev) || hit(menuButton, ev)
            return !onChild
        }
        curX = ev.rawX
        curY = ev.rawY
        if (armed) {
            followArmed(ev)
            return true
        }
        if (touchInFocusedEditor(ev)) return false
        return watcher.onEvent(ev)
    }

    /** After a long press: a move drags the widget, lifting without one opens the menu. */
    private fun followArmed(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!pressDrag && (abs(ev.rawX - armX) > slop || abs(ev.rawY - armY) > slop)) {
                    pressDrag = true
                    lastX = armX
                    lastY = armY
                }
                if (pressDrag) {
                    callbacks.onWidgetDragged(this, ev.rawX - lastX, ev.rawY - lastY, false)
                    lastX = ev.rawX
                    lastY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                watcher.onEvent(ev)
                val dragged = pressDrag
                armed = false
                pressDrag = false
                panel.focused = editing
                invalidate()
                if (dragged) callbacks.onWidgetDragged(this, 0f, 0f, true)
                else if (ev.actionMasked == MotionEvent.ACTION_UP) callbacks.onWidgetMenu(this)
            }
        }
    }

    private fun hit(v: View, ev: MotionEvent) = v.visibility == View.VISIBLE &&
        ev.x >= v.left && ev.x < v.right && ev.y >= v.top && ev.y < v.bottom

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editing) {
            curX = ev.rawX
            curY = ev.rawY
            if (armed) {
                followArmed(ev)
                return true
            }
            watcher.onEvent(ev)
            return super.onTouchEvent(ev) || watcher.fired
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.rawX
                lastY = ev.rawY
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                callbacks.onWidgetDragged(this, ev.rawX - lastX, ev.rawY - lastY, false)
                lastX = ev.rawX
                lastY = ev.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                callbacks.onWidgetDragged(this, 0f, 0f, true)
            }
        }
        return true
    }

    private inner class ResizeHandle : View(shell.activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private var lx = 0f
        private var ly = 0f

        init {
            contentDescription = context.getString(R.string.widget_resize)
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = theme.accent
            val s = width.toFloat()
            r.set(s * 0.35f, s * 0.35f, s * 0.85f, s * 0.85f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = theme.dp(2.5f)
            canvas.drawLine(r.left, r.bottom, r.right, r.top, paint)
            canvas.drawLine(r.centerX(), r.bottom, r.right, r.centerY(), paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = ev.rawX
                    ly = ev.rawY
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    callbacks.onWidgetResized(this@WidgetFrame, ev.rawX - lx, ev.rawY - ly, false)
                    lx = ev.rawX
                    ly = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> callbacks.onWidgetResized(this@WidgetFrame, 0f, 0f, true)
            }
            return true
        }
    }

    /** Placeholder for an Android widget whose provider is gone or failed. */
    fun showFailure(message: CharSequence, onRetry: () -> Unit, onRemove: () -> Unit) {
        val box = Ui.row(context, vertical = true, gravity = Gravity.CENTER).apply {
            val p = theme.dpi(10f)
            setPadding(p, p, p, p)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_widgets)
                imageTintList = ColorStateList.valueOf(theme.textMuted)
            }, Ui.lp(theme.dpi(24f), theme.dpi(24f)))
            addView(Ui.text(context, theme, message, theme.textLabel, theme.textMuted, lines = 3).apply {
                gravity = Gravity.CENTER
                setPadding(0, theme.dpi(6f), 0, theme.dpi(6f))
            })
            addView(Ui.row(context, gravity = Gravity.CENTER).apply {
                addView(Ui.button(context, theme, context.getString(R.string.action_retry)) { onRetry() })
                addView(Ui.button(context, theme, context.getString(R.string.action_remove)) { onRemove() })
            })
        }
        setContent(box)
    }
}
