// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.panels

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.Snapshot
import com.lxlauncher.core.WindowItem
import com.lxlauncher.embed.EmbeddedAppTool
import com.lxlauncher.system.SystemInfo
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.desktop.DragPayload
import com.lxlauncher.ui.desktop.startLxDrag
import com.lxlauncher.ui.theme.LxIcons
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface
import com.lxlauncher.ui.windows.WindowLayer
import java.util.Date

/** Labels for dock and taskbar entries that are not apps; icons come from [LxIcons]. */
object PinInfo {
    /** Short names that fit under a dock icon. */
    fun shortLabel(ctx: Context, shell: Shell, entry: String): String = when (entry) {
        "tool:files" -> ctx.getString(R.string.dock_files)
        "tool:settings" -> ctx.getString(R.string.dock_settings_short)
        "tool:monitor" -> ctx.getString(R.string.dock_monitor)
        "tool:notes" -> ctx.getString(R.string.tool_notes)
        else -> label(ctx, shell, entry)
    }

    fun label(ctx: Context, shell: Shell, entry: String): String = when {
        entry.startsWith("app:") -> shell.apps.find(entry.removePrefix("app:"))?.label ?: ctx.getString(R.string.app_unavailable)
        else -> ctx.getString(when (entry) {
            "action:home" -> R.string.dock_home
            "tool:files" -> R.string.tool_files
            "intent:browser" -> R.string.dock_browser
            "tool:settings" -> R.string.tool_settings
            "action:drawer" -> R.string.dock_apps
            "action:games" -> R.string.dock_games
            "intent:gallery" -> R.string.dock_gallery
            "tool:monitor" -> R.string.tool_monitor
            "tool:notes" -> R.string.tool_notes
            "tool:tedit" -> R.string.tool_tedit
            "action:search" -> R.string.dock_search
            else -> R.string.app_unavailable
        })
    }
}

/**
 * Top status panel: brand, active workspace, search, Android settings, battery
 * and clock. On a phone held upright it also carries the workspace switcher,
 * because the bottom bar is busy with the dock.
 */
@SuppressLint("ViewConstructor")
class TopPanel(private val shell: Shell, private val events: TaskbarView.Events) : LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private val workspace: TextView
    private val divider: View
    private val switcher = LinearLayout(shell.activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val switcherScroll = android.widget.HorizontalScrollView(shell.activity).apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        // See the taskbar's row: the fade is worked out in the row's own layer.
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(switcher)
        visibility = View.GONE
    }
    private val wordmark: TextView
    private fun mark(text: String) = android.text.SpannableString(text).apply {
        setSpan(android.text.style.ForegroundColorSpan(theme.accent), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    private val fullMark = mark(shell.activity.getString(R.string.app_name_short))
    private var batteryKnown = false
    private val battery: TextView
    private val clock: TextView
    /** The battery last shown; a broadcast that changes nothing on screen draws nothing. */
    private var shownBattery: SystemInfo.Battery? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_BATTERY_CHANGED) showBattery(SystemInfo.battery(i)) else showClock()
        }
    }
    /** What LauncherActivity asked for: the receiver runs only while LX is started and this panel is attached. */
    private var wanted = false
    private var listening = false
    /** The inputs of the last [bind]; the same inputs leave the panel as it is. */
    private var bound: List<Any?>? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = PanelDrawable(this, shell.glass, Surface.GLASS).apply { radiusOverride = theme.radiusL }
        setPadding(theme.dpi(10f), 0, theme.dpi(4f), 0)
        val ctx = context
        // The owner's logo, then the name with "LX" in the accent colour.
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.lx_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, Ui.lp(theme.dpi(24f), theme.dpi(24f)).apply { marginEnd = theme.dpi(4f) })
        wordmark = Ui.text(ctx, theme, fullMark, theme.textBody, theme.textPrimary, bold = true).apply {
            contentDescription = ctx.getString(R.string.app_name_short)
            setPadding(theme.dpi(6f), 0, 0, 0)
        }
        addView(wordmark)
        divider = View(ctx).apply { setBackgroundColor(LxTheme.withAlpha(theme.textMuted, 0.4f)) }
        addView(divider, Ui.lp(theme.dpi(1f), theme.dpi(16f)).apply {
            marginStart = theme.dpi(10f)
            marginEnd = theme.dpi(10f)
        })
        workspace = Ui.text(ctx, theme, "", theme.textLabel * 1.08f, theme.textPrimary, bold = true).apply {
            background = Ui.stateBackground(theme, radius = theme.pill(theme.barTarget.toFloat()))
            minHeight = theme.barTarget
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dpi(10f), 0, theme.dpi(10f), 0)
            // The glyph keeps the size [glyph] gave it; the intrinsic-bounds variant would reset it to 24 dp.
            setCompoundDrawablesRelative(glyph(R.drawable.ic_workspaces, theme.accent2, 16f), null, null, null)
            compoundDrawablePadding = theme.dpi(8f)
            isFocusable = true
            setOnClickListener { (shell.activity as? Overview)?.showOverview() }
        }
        addView(workspace, Ui.lp(0, Ui.WRAP, 1f))
        addView(switcherScroll, Ui.lp(0, Ui.WRAP, 1f))
        addView(barButton(Ui.iconButton(ctx, theme, R.drawable.ic_search, ctx.getString(R.string.search_open)) { shell.openSearch() },
            theme, theme.barTarget, 18f))
        addView(barButton(Ui.iconButton(ctx, theme, R.drawable.ic_android, ctx.getString(R.string.android_settings)) {
            shell.startSafely(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, theme, theme.barTarget, 18f))
        battery = Ui.text(ctx, theme, "", theme.textLabel, theme.textPrimary).apply {
            setPadding(theme.dpi(8f), 0, theme.dpi(8f), 0)
            fontFeatureSettings = "tnum"
            compoundDrawablePadding = theme.dpi(4f)
        }
        clock = Ui.text(ctx, theme, "", theme.textBody, theme.textPrimary, bold = true).apply {
            setPadding(0, 0, theme.dpi(10f), 0)
            fontFeatureSettings = "tnum"
        }
        addView(battery)
        addView(clock)
    }

    interface Overview {
        fun showOverview()
    }

    private fun glyph(res: Int, tint: Int, sizeDp: Float) = context.getDrawable(res)?.mutate()?.apply {
        setTint(tint)
        setBounds(0, 0, theme.dpi(sizeDp), theme.dpi(sizeDp))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // On narrow screens the workspace name matters more than the wordmark; the logo stays.
        val narrow = MeasureSpec.getSize(widthMeasureSpec) < theme.dpi(520f)
        wordmark.visibility = if (narrow) View.GONE else View.VISIBLE
        // Phones already show battery and time in the system status bar.
        battery.visibility = if (narrow || !batteryKnown) View.GONE else View.VISIBLE
        clock.visibility = if (narrow) View.GONE else View.VISIBLE
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun bind(s: Snapshot, withSwitcher: Boolean) {
        val key = listOf(withSwitcher, s.workspaces, s.active, theme, s.setting("clock.24h"))
        if (key == bound) return
        bound = key
        val ws = s.activeWorkspace
        val index = s.workspaces.indexOf(ws) + 1
        workspace.text = context.getString(R.string.workspace_label, index, ws.name)
        workspace.contentDescription = context.getString(R.string.workspace_overview_open, ws.name)
        workspace.visibility = if (withSwitcher) View.GONE else View.VISIBLE
        // The switcher replaces the label and its divider on a phone.
        divider.visibility = workspace.visibility
        switcherScroll.visibility = if (withSwitcher) View.VISIBLE else View.GONE
        switcher.removeAllViews()
        if (withSwitcher) addWorkspaceButtons(shell, events, s, switcher, theme.barTarget)
        showClock()
    }

    fun refreshStatus() {
        shownBattery = null
        showBattery(SystemInfo.battery(context))
        showClock()
    }

    private fun showBattery(b: SystemInfo.Battery) {
        if (b == shownBattery) return
        val glyphChanged = shownBattery?.charging != b.charging
        shownBattery = b
        val wasKnown = batteryKnown
        batteryKnown = b.known
        battery.text = "${b.percent}%"
        // Charging shows in the glyph's shape, a bolt. The glyph stays textPrimary: on the translucent
        // bar an accent glyph can fall under 3:1.
        if (glyphChanged) battery.setCompoundDrawablesRelative(
            glyph(if (b.charging) R.drawable.ic_battery_charging else R.drawable.ic_battery, theme.textPrimary, 18f),
            null, null, null)
        battery.contentDescription = if (b.charging) context.getString(R.string.battery_short_charging, b.percent)
            else context.getString(R.string.battery_short, b.percent)
        if (wasKnown != batteryKnown) requestLayout()
    }

    private fun showClock() {
        val h24 = shell.model.setting("clock.24h") == "true"
        clock.text = DateFormat.format(if (h24) "HH:mm" else "h:mm a", Date())
    }

    /** Started with LX: clock and battery follow their broadcasts. Stopped, nothing listens or redraws. */
    fun setActive(on: Boolean) {
        wanted = on
        listen()
    }

    private fun listen() {
        val on = wanted && isAttachedToWindow
        if (on == listening) return
        listening = on
        if (on) {
            // The battery broadcast is sticky: registering hands over its current value at once.
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            })?.let { showBattery(SystemInfo.battery(it)) }
            showClock()
        } else {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        listen()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        listen()
    }
}

/** A dock or taskbar entry with icon and optional label. Accepts dropped apps. */
@SuppressLint("ViewConstructor")
class PinButton(private val shell: Shell, val entry: String, vertical: Boolean, iconDp: Float, showLabel: Boolean,
                labelSp: Float = shell.ui.textCaption, target: Int = shell.ui.touchTarget) :
    LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    val icon = ImageView(context)
    var indicator: View? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = Ui.stateBackground(theme)
        isFocusable = true
        val label = PinInfo.label(context, shell, entry)
        contentDescription = label
        tooltipText = label
        val pad = theme.dpi(if (vertical) 6f else 4f)
        setPadding(theme.dpi(2f), pad, theme.dpi(2f), pad)
        minimumWidth = target
        minimumHeight = target
        addView(icon, Ui.lp(theme.dpi(iconDp), theme.dpi(iconDp)))
        if (entry.startsWith("app:")) {
            val key = entry.removePrefix("app:")
            shell.apps.icon(key, theme.dpi(iconDp)) { icon.setImageDrawable(it) }
        } else {
            LxIcons.apply(icon, LxIcons.pin(entry, theme.vividIcons), theme.textPrimary)
        }
        if (showLabel) addView(Ui.text(context, theme, PinInfo.shortLabel(context, shell, entry), labelSp, theme.textPrimary).apply {
            gravity = Gravity.CENTER
            setPadding(0, theme.dpi(4f), 0, 0)
        })
        setOnClickListener { shell.launchPin(entry, it) }
    }

    fun setIconDrawable(d: Drawable?) = icon.setImageDrawable(d)
}

/** Vertical or horizontal dock of shortcuts and tools. */
@SuppressLint("ViewConstructor")
class DockView(private val shell: Shell) : FrameLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private val list = LinearLayout(context)
    var vertical = true
        private set
    private val panel = PanelDrawable(this, shell.glass, Surface.GLASS).apply { radiusOverride = theme.radiusL }
    /** The inputs of the last [bind]: the same inputs keep the buttons, their scroll and TalkBack's place. */
    private var bound: List<Any?>? = null

    init {
        background = panel
        addView(list, LayoutParams(Ui.MATCH, Ui.MATCH))
        contentDescription = context.getString(R.string.dock_description)
        setOnDragListener { _, e -> onDrag(e) }
    }

    fun bind(s: Snapshot, vertical: Boolean, compact: Boolean) {
        val key = listOf(s.dock, vertical, compact, s.int("dock.iconSize", 36), s.bool("dock.autoHide"), theme,
            Identity(shell.apps.apps))
        if (key == bound) return
        bound = key
        this.vertical = vertical
        list.removeAllViews()
        list.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        list.gravity = Gravity.CENTER
        val pad = theme.dpi(6f)
        list.setPadding(pad, pad, pad, pad)
        val iconDp = s.int("dock.iconSize", 36).toFloat()
        val showLabel = vertical && iconDp >= 32f && !s.bool("dock.autoHide")
        for (entry in s.dock) {
            val b = PinButton(shell, entry, vertical, iconDp * 0.72f, showLabel,
                if (compact) theme.textCaption * 0.88f else theme.textCaption)
            b.setOnLongClickListener {
                if (entry.startsWith("app:")) it.startLxDrag(DragPayload.Pin(NativeCore.LIST_DOCK, entry), b.contentDescription)
                shell.showMenu(it, listOf(
                    MenuItem(context.getString(R.string.action_move_up), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_DOCK, entry, -1) }
                    },
                    MenuItem(context.getString(R.string.action_move_down), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_DOCK, entry, 1) }
                    },
                    MenuItem(context.getString(R.string.dock_remove), R.drawable.ic_close) {
                        shell.model.edit { h -> NativeCore.unpin(h, NativeCore.LIST_DOCK, entry) }
                    },
                    MenuItem(context.getString(R.string.dock_settings), R.drawable.ic_settings) { shell.openTool("settings", "dock") },
                ))
                true
            }
            list.addView(b, LinearLayout.LayoutParams(if (vertical) Ui.MATCH else Ui.WRAP, Ui.WRAP).apply {
                if (vertical) bottomMargin = theme.dpi(2f) else marginEnd = theme.dpi(2f)
            })
        }
        background.alpha = 255
        alpha = 1f
    }

    private fun onDrag(e: DragEvent): Boolean {
        val p = e.localState as? DragPayload
        return when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> p is DragPayload.App || p is DragPayload.Shortcut
            DragEvent.ACTION_DRAG_ENTERED -> {
                panel.focused = true
                true
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                panel.focused = false
                true
            }
            DragEvent.ACTION_DROP -> drop(p)
            else -> true
        }
    }

    /** Pins a dropped app; also used for drops the overlay layer hands on. */
    fun drop(p: DragPayload?): Boolean {
        val key = (p as? DragPayload.App)?.appKey ?: (p as? DragPayload.Shortcut)?.appKey ?: return false
        if (!shell.model.edit { NativeCore.pin(it, NativeCore.LIST_DOCK, "app:$key", -1) }) {
            shell.notify(context.getString(R.string.dock_pin_failed))
        }
        return true
    }
}

/**
 * Bottom taskbar: the Applications menu button at the left end, then the
 * workspace switcher, pinned apps and running internal windows.
 */
@SuppressLint("ViewConstructor")
class TaskbarView(private val shell: Shell, private val events: Events) : FrameLayout(shell.activity) {
    interface Events {
        fun onWorkspaceSelected(id: Int)
        fun onWorkspaceMenu(view: View, id: Int)
        fun onWindowButton(id: Int)
        fun onWindowMenu(view: View, id: Int)
    }

    private val theme: LxTheme get() = shell.ui
    private val row = LinearLayout(context)
    private val panel = PanelDrawable(this, shell.glass, Surface.GLASS).apply { radiusOverride = theme.radiusL }
    /** The Applications button; filled with the accent while its menu is open. */
    private var appsButton: ImageView? = null
    /** Kept here because [bind] builds a new button while the menu may still be open. */
    private var appsOpen = false

    /** Marks the Applications button open or closed; called by the activity as the menu opens and closes. */
    fun setAppsOpen(open: Boolean) {
        appsOpen = open
        appsButton?.let { showAppsState(it) }
    }

    private fun showAppsState(b: ImageView) {
        b.isActivated = appsOpen
        val state = context.getString(if (appsOpen) R.string.state_open else R.string.state_closed)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            b.stateDescription = state
        } else {
            b.contentDescription = context.getString(R.string.menu_open) + ", " + state
        }
    }

    /** The Applications button: the grid glyph on a quiet rounded container, the accent fill while open. */
    private fun appsButton(): ImageView = barButton(ImageView(context).apply {
        setImageDrawable(AppsGlyphDrawable(theme))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = context.getString(R.string.menu_open)
        tooltipText = contentDescription
        isFocusable = true
        fun shape(fill: Int, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = theme.cornerRadius.coerceAtMost(theme.dp(12f))
            setColor(fill)
            if (stroke != 0) setStroke(theme.dpi(2f), stroke)
        }
        // No resting container: the 3D glyph floats on the bar. A ring marks the open and focused states so both still show.
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), shape(Color.TRANSPARENT, theme.accent))
            addState(intArrayOf(android.R.attr.state_focused), shape(theme.focusFill, theme.accent))
            addState(intArrayOf(android.R.attr.state_hovered), shape(LxTheme.withAlpha(theme.textPrimary, 0.12f)))
            addState(intArrayOf(), shape(Color.TRANSPARENT))
        }
        val inset = theme.dpi(2f)
        background = android.graphics.drawable.InsetDrawable(android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(LxTheme.withAlpha(theme.accent, 0.28f)), states, shape(Color.WHITE)), inset)
        setOnClickListener { shell.openDrawer() }
    }, theme, theme.dockTarget, 22f).also {
        appsButton = it
        showAppsState(it)
    }

    init {
        background = panel
        addView(row, LayoutParams(Ui.MATCH, Ui.MATCH))
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(theme.dpi(6f), 0, theme.dpi(6f), 0)
        contentDescription = context.getString(R.string.taskbar_description)
        setOnDragListener { _, e -> onDrag(e) }
    }

    private fun divider() = View(context).apply { setBackgroundColor(LxTheme.withAlpha(theme.textMuted, 0.35f)) }

    /**
     * [phone] turns the taskbar into the phone's bottom bar: the Applications
     * button, the dock's entries and the taskbar's pins side by side, then open
     * windows. The workspace switcher moves to the top panel.
     */
    private var phoneBar = false

    /** The inputs of the last [bind]; see [bindKey]. */
    private var bound: List<Any?>? = null

    /**
     * Everything the bar shows depends on. Notes being typed, a window moved or an app opened leave it the same, and
     * then the bar keeps its buttons, its scroll position and TalkBack's place.
     */
    private fun bindKey(s: Snapshot, compactSearch: Boolean, narrow: Boolean, phone: Boolean): List<Any?> = listOf(
        compactSearch, narrow, phone, theme, Identity(shell.apps.apps), s.active, s.workspaces, s.dock, s.taskbar,
        s.bool("taskbar.labels"), s.bool("dock.visible"), s.bool("taskbar.showWindows"),
        s.windows.filter { it.ws == s.active }.map { listOf(it.id, it.kind, it.title, it.minimized, it.focused) },
    )

    fun bind(s: Snapshot, compactSearch: Boolean, narrow: Boolean, phone: Boolean = false) {
        val key = bindKey(s, compactSearch, narrow, phone)
        if (key == bound) return
        bound = key
        phoneBar = phone
        row.removeAllViews()
        // A 360 dp phone has to fit the Applications button and the six default dock entries at 48 dp each.
        val pad = theme.dpi(if (phone) 2f else 6f)
        row.setPadding(pad, 0, pad, 0)
        row.addView(appsButton())
        // A thin divider sets the Applications button apart from the apps beside it.
        row.addView(divider(), LinearLayout.LayoutParams(theme.dpi(1f), theme.dpi(24f)).apply {
            marginStart = theme.dpi(if (phone) 6f else 8f)
            marginEnd = theme.dpi(if (phone) 6f else 8f)
        })
        // File Manager comes right after the Applications button and Tedit right after File Manager; each stands for
        // its own window. On a phone they are the first entries of the bar's scrolling row, so they move with the
        // entries next to them.
        val filesWindow = s.windows.firstOrNull { it.ws == s.active && it.kind == "files" }
        val teditWindow = s.windows.firstOrNull { it.ws == s.active && it.kind == TEDIT_KIND }
        // Their icons take the size of the pins beside them (the dock's when it shares the bar on a phone).
        val pinIconDp = if (phone && s.bool("dock.visible")) 26f else 24f
        val fixed = listOf(toolButton("files", R.string.tool_files, filesWindow, pinIconDp),
            toolButton(TEDIT_KIND, R.string.tool_tedit, teditWindow, pinIconDp))
        if (!phone) {
            fixed.forEach { row.addView(it) }
            row.addView(View(context), LinearLayout.LayoutParams(theme.dpi(8f), 1))
        }
        if (!phone) {
            addWorkspaceButtons(shell, events, s, row, theme.dockTarget)
            row.addView(divider(), LinearLayout.LayoutParams(theme.dpi(1f), theme.dpi(24f)).apply {
                marginStart = theme.dpi(6f)
                marginEnd = theme.dpi(6f)
            })
        }
        if (compactSearch) {
            row.addView(Ui.row(context).apply {
                background = PanelDrawable(this, shell.glass, Surface.CHIP)
                minimumHeight = theme.dpi(34f)
                setPadding(theme.dpi(10f), 0, theme.dpi(12f), 0)
                isFocusable = true
                contentDescription = context.getString(R.string.search_open)
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_search)
                    imageTintList = ColorStateList.valueOf(theme.textMuted)
                }, Ui.lp(theme.dpi(18f), theme.dpi(18f)))
                addView(Ui.text(context, theme, context.getString(R.string.search_hint_short), theme.textLabel, theme.textMuted).apply {
                    setPadding(theme.dpi(8f), 0, 0, 0)
                })
                setOnClickListener { shell.openSearch() }
            }, LinearLayout.LayoutParams(theme.dpi(220f), Ui.WRAP).apply { marginEnd = theme.dpi(6f) })
        }
        // Pinned apps scroll if they do not fit.
        val pins = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (phone) fixed.forEach { pins.addView(it) }
        val labels = s.bool("taskbar.labels") && !phone
        val dockHere = phone && s.bool("dock.visible")
        // The bar already starts with the Applications button, so the dock's own entry for it would be a twin.
        if (dockHere) for (entry in s.dock.filter { it != "action:drawer" && it !in FIXED_PINS }) {
            val b = PinButton(shell, entry, false, 26f, false, target = theme.dockTarget)
            b.setOnLongClickListener {
                if (entry.startsWith("app:")) it.startLxDrag(DragPayload.Pin(NativeCore.LIST_DOCK, entry), b.contentDescription)
                shell.showMenu(it, listOf(
                    MenuItem(context.getString(R.string.action_move_left), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_DOCK, entry, -1) }
                    },
                    MenuItem(context.getString(R.string.action_move_right), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_DOCK, entry, 1) }
                    },
                    MenuItem(context.getString(R.string.dock_remove), R.drawable.ic_close) {
                        shell.model.edit { h -> NativeCore.unpin(h, NativeCore.LIST_DOCK, entry) }
                    },
                    MenuItem(context.getString(R.string.dock_settings), R.drawable.ic_settings) { shell.openTool("settings", "dock") },
                ))
                true
            }
            pins.addView(b)
        }
        for (entry in s.taskbar) {
            if (dockHere && entry in s.dock || entry in FIXED_PINS) continue
            val b = PinButton(shell, entry, false, 24f, labels, target = theme.dockTarget)
            b.setOnLongClickListener {
                if (entry.startsWith("app:")) it.startLxDrag(DragPayload.Pin(NativeCore.LIST_TASKBAR, entry), b.contentDescription)
                shell.showMenu(it, listOf(
                    MenuItem(context.getString(R.string.action_move_left), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_TASKBAR, entry, -1) }
                    },
                    MenuItem(context.getString(R.string.action_move_right), R.drawable.ic_up) {
                        shell.model.edit { h -> NativeCore.movePin(h, NativeCore.LIST_TASKBAR, entry, 1) }
                    },
                    MenuItem(context.getString(R.string.taskbar_unpin), R.drawable.ic_close) {
                        shell.model.edit { h -> NativeCore.unpin(h, NativeCore.LIST_TASKBAR, entry) }
                    },
                ))
                true
            }
            pins.addView(b)
        }
        val openWindows = s.bool("taskbar.showWindows") && s.windows.any { it.ws == s.active }
        if (s.taskbar.isEmpty() && !openWindows && !(dockHere && s.dock.isNotEmpty())) {
            pins.addView(Ui.text(context, theme,
                context.getString(if (narrow) R.string.taskbar_empty_short else R.string.taskbar_empty), theme.textCaption, theme.textMuted).apply {
                setPadding(theme.dpi(4f), 0, theme.dpi(4f), 0)
            })
        }
        // Running internal windows of this workspace. On a phone the pins scroll past the edge,
        // so windows sit in a fixed group at the right end where a minimised one stays in view.
        val windowGroup = if (phone) LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL } else pins
        if (s.bool("taskbar.showWindows")) {
            val wins = s.windows.filter { it.ws == s.active && it.id != filesWindow?.id && it.id != teditWindow?.id }
            // Separate windows from pinned apps only when there is something to separate.
            if (wins.isNotEmpty() && pins.childCount > 0) windowGroup.addView(divider(), LinearLayout.LayoutParams(theme.dpi(1f), theme.dpi(24f)).apply {
                marginStart = theme.dpi(6f)
                marginEnd = theme.dpi(6f)
            })
            for (w in wins) {
                // An Android app window keeps its app key as its title: the bar shows the app's name and icon.
                val label = EmbeddedAppTool.windowLabel(shell.apps, w)
                val appKey = EmbeddedAppTool.appKeyOf(w)?.takeIf { shell.apps.find(it) != null }
                val b = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumWidth = theme.dockTarget
                    minimumHeight = theme.dockTarget
                    background = Ui.stateBackground(theme, selected = w.focused)
                    isFocusable = true
                    isActivated = w.focused
                    contentDescription = context.getString(when {
                        w.minimized -> R.string.window_task_minimized
                        w.focused -> R.string.window_task_focused
                        else -> R.string.window_task_open
                    }, label)
                    tooltipText = label
                    addView(ImageView(context).apply {
                        if (appKey != null) {
                            // The app's own icon cannot dim through a tint either; a minimised one fades.
                            shell.apps.icon(appKey, theme.dpi(22f)) { setImageDrawable(it) }
                            if (w.minimized) alpha = 0.55f
                        } else {
                            val choice = LxIcons.tool(w.kind, theme.vividIcons)
                            LxIcons.apply(this, choice, if (w.minimized) theme.textMuted else theme.textPrimary)
                            // A minimised icon in its own colours cannot dim through a tint; fade it instead.
                            if (w.minimized && !choice.tinted) alpha = 0.55f
                        }
                    }, Ui.lp(theme.dpi(22f), theme.dpi(22f)))
                    // Running indicator: a short accent bar under the icon, wider when focused.
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = theme.dp(1.5f)
                            setColor(if (w.minimized) theme.textMuted else theme.accent)
                        }
                    }, Ui.lp(theme.dpi(if (w.focused) 16f else 6f), theme.dpi(3f)).apply { topMargin = theme.dpi(3f) })
                    setOnClickListener { events.onWindowButton(w.id) }
                    setOnLongClickListener {
                        events.onWindowMenu(it, w.id)
                        true
                    }
                }
                windowGroup.addView(b)
            }
        }
        // On a phone, pins that all fit share the bar's width evenly, as in a dock; when they do not
        // fit, they keep their size and scroll. A desktop taskbar keeps them packed at the start.
        if (phone) for (i in 0 until pins.childCount) {
            val c = pins.getChildAt(i)
            if (c is PinButton || c in fixed) c.layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH, 1f)
        }
        row.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = phone
            // When the pins overflow, the cut-off edge fades so the row reads as scrollable.
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(theme.dpi(20f))
            // The bar is glass, so the fade cannot be a gradient into one colour (StableScrollView.fadeInto). Drawn
            // straight into the screen, it copied the part of the frame under the edge on every frame anything moved
            // on the screen, a window resize included, which stalls the tile renderer of the Infinix test phone's GPU.
            // In its own layer the row is drawn, fade and all, only when the row changes; the faded edge lets the
            // glass under the layer show through as before.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addView(pins, LayoutParams(Ui.WRAP, Ui.MATCH))
        }, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        if (windowGroup !== pins && windowGroup.childCount > 0) row.addView(windowGroup, LinearLayout.LayoutParams(Ui.WRAP, Ui.MATCH))
    }

    /**
     * The button of a tool that always has its place on the bar (File Manager, Tedit): opens the tool, or acts like
     * that window's taskbar entry once it is open (restore, focus, minimise), so a second tap never opens a duplicate.
     */
    private fun toolButton(kind: String, label: Int, win: WindowItem?, iconDp: Float): View = FrameLayout(context).apply {
        minimumWidth = theme.dockTarget
        minimumHeight = theme.dockTarget
        background = Ui.stateBackground(theme, selected = win?.focused == true)
        isFocusable = true
        isActivated = win?.focused == true
        val name = context.getString(label)
        contentDescription = if (win == null) name else context.getString(when {
            win.minimized -> R.string.window_task_minimized
            win.focused -> R.string.window_task_focused
            else -> R.string.window_task_open
        }, name)
        tooltipText = name
        // The icon sits on the bar's centre line, level with the pins beside it; the running indicator keeps to the
        // bottom edge, so it neither pushes the icon up nor makes it jump when the window opens.
        addView(ImageView(context).apply {
            val choice = LxIcons.tool(kind, theme.vividIcons)
            LxIcons.apply(this, choice, if (win?.minimized == true) theme.textMuted else theme.textPrimary)
            // An icon in its own colours cannot dim through a tint; a minimised one fades instead.
            if (win?.minimized == true && !choice.tinted) alpha = 0.55f
        }, FrameLayout.LayoutParams(theme.dpi(iconDp), theme.dpi(iconDp), Gravity.CENTER))
        if (win != null) addView(View(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = theme.dp(1.5f)
                setColor(if (win.minimized) theme.textMuted else theme.accent)
            }
        }, FrameLayout.LayoutParams(theme.dpi(if (win.focused) 16f else 6f), theme.dpi(3f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = theme.dpi(3f)
        })
        setOnClickListener { if (win == null) shell.openTool(kind) else events.onWindowButton(win.id) }
        if (win != null) setOnLongClickListener {
            events.onWindowMenu(it, win.id)
            true
        }
    }

    private fun onDrag(e: DragEvent): Boolean {
        val p = e.localState as? DragPayload
        return when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> p is DragPayload.App || p is DragPayload.Shortcut
            DragEvent.ACTION_DRAG_ENTERED -> {
                panel.focused = true
                true
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                panel.focused = false
                true
            }
            DragEvent.ACTION_DROP -> drop(p)
            else -> true
        }
    }

    /** Pins a dropped app; also used for drops the overlay layer hands on. */
    fun drop(p: DragPayload?): Boolean {
        val key = (p as? DragPayload.App)?.appKey ?: (p as? DragPayload.Shortcut)?.appKey ?: return false
        val list = if (phoneBar) NativeCore.LIST_DOCK else NativeCore.LIST_TASKBAR
        if (!shell.model.edit { NativeCore.pin(it, list, "app:$key", -1) }) {
            shell.notify(context.getString(if (phoneBar) R.string.dock_pin_failed else R.string.taskbar_pin_failed))
        }
        return true
    }
}

/**
 * One button per workspace: tap switches, long press opens its menu, and an
 * icon dropped on it moves there. Used by the taskbar, and by the top panel on
 * a phone held upright. The active one is bold on a neon chip, not colour alone.
 */
internal fun addWorkspaceButtons(shell: Shell, events: TaskbarView.Events, s: Snapshot, row: LinearLayout, target: Int) {
    val theme = shell.ui
    val context = row.context
    for ((i, ws) in s.workspaces.withIndex()) {
        val active = ws.id == s.active
        // The active workspace is a pill filled with accent2 and set in Medium: fill and weight, not colour alone.
        val pillH = (target - theme.dpi(4f)).toFloat()
        val inset = theme.dpi(2f)
        val b = Ui.text(context, theme, (i + 1).toString(), theme.textBody, if (active) theme.onAccent2 else theme.textPrimary, bold = active).apply {
            gravity = Gravity.CENTER
            minWidth = target
            minHeight = target
            fontFeatureSettings = "tnum"
            background = if (active) {
                fun shape(stroke: Int) = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = theme.pill(pillH)
                    setColor(theme.accent2)
                    if (stroke != 0) setStroke(theme.dpi(2f), stroke)
                }
                val states = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(theme.onAccent2))
                    addState(intArrayOf(), shape(0))
                }
                android.graphics.drawable.InsetDrawable(android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(LxTheme.withAlpha(theme.onAccent2, 0.25f)), states, shape(0)), inset)
            } else android.graphics.drawable.InsetDrawable(Ui.stateBackground(theme, radius = theme.pill(pillH)), inset)
            isFocusable = true
            isSelected = active
            contentDescription = context.getString(if (active) R.string.workspace_current else R.string.workspace_switch_to, ws.name)
            tooltipText = ws.name
            setOnClickListener { events.onWorkspaceSelected(ws.id) }
            setOnLongClickListener {
                events.onWorkspaceMenu(it, ws.id)
                true
            }
            setOnDragListener { _, e -> workspaceDrop(shell, e, ws.id) }
        }
        row.addView(b, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = theme.dpi(2f) })
    }
}

private fun workspaceDrop(shell: Shell, e: DragEvent, wsId: Int): Boolean {
    val p = e.localState as? DragPayload
    return when (e.action) {
        DragEvent.ACTION_DRAG_STARTED -> p is DragPayload.Shortcut || p is DragPayload.Folder
        DragEvent.ACTION_DROP -> {
            val id = (p as? DragPayload.Shortcut)?.id ?: (p as? DragPayload.Folder)?.id ?: return false
            if (!shell.model.edit { NativeCore.moveItemToWorkspace(it, id, wsId) }) {
                shell.notify(shell.activity.getString(R.string.desktop_full))
            }
            true
        }
        else -> true
    }
}

/** Search field above the taskbar on narrow screens. Tapping opens search. */
@SuppressLint("ViewConstructor")
class SearchBarView(private val shell: Shell) : LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private val shortcutHint: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = PanelDrawable(this, shell.glass, Surface.GLASS)
        setPadding(theme.dpi(14f), 0, theme.dpi(10f), 0)
        isFocusable = true
        contentDescription = context.getString(R.string.search_open)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(theme.textMuted)
        }, Ui.lp(theme.dpi(20f), theme.dpi(20f)))
        addView(Ui.text(context, theme, context.getString(R.string.search_hint), theme.textBody, theme.textMuted).apply {
            setPadding(theme.dpi(12f), 0, 0, 0)
        }, Ui.lp(0, Ui.WRAP, 1f))
        shortcutHint = Ui.text(context, theme, "Ctrl K", theme.textCaption, theme.textMuted).apply {
            background = PanelDrawable(this, shell.glass, Surface.CHIP)
            setPadding(theme.dpi(8f), theme.dpi(4f), theme.dpi(8f), theme.dpi(4f))
        }
        addView(shortcutHint)
        setOnClickListener { shell.openSearch() }
        setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_UP && Ui.isActivateKey(code)) {
                shell.openSearch()
                true
            } else false
        }
        updateKeyboardHint()
    }

    /** The shortcut hint only shows when a hardware keyboard is attached. */
    fun updateKeyboardHint() {
        val kb = resources.configuration.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY
        shortcutHint.visibility = if (kb) View.VISIBLE else View.GONE
    }
}

/** Resizes an icon button to a bar's control size, keeping the glyph centred. */
internal fun barButton(button: ImageView, theme: LxTheme, size: Int, iconDp: Float): ImageView = button.apply {
    val pad = ((size - theme.dpi(iconDp)) / 2).coerceAtLeast(0)
    layoutParams = ViewGroup.LayoutParams(size, size)
    setPadding(pad, pad, pad, pad)
}

/** A small modern app grid for the Applications button: four raised, glossy tiles in the theme accents. */
internal class AppsGlyphDrawable(private val theme: LxTheme) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        if (w <= 0f) return
        val gap = w * 0.16f
        val tile = (w - gap) / 2f
        val radius = tile * 0.3f
        val drop = w * 0.045f
        for (i in 0..1) for (j in 0..1) {
            val base = if ((i + j) % 2 == 0) theme.accent else theme.accent2
            val left = b.left + i * (tile + gap)
            val top = b.top + j * (tile + gap)
            rect.set(left + drop, top + drop, left + tile + drop, top + tile + drop)
            shade.color = LxTheme.withAlpha(Color.BLACK, 0.28f)
            canvas.drawRoundRect(rect, radius, radius, shade)
            rect.set(left, top, left + tile, top + tile)
            fill.shader = LinearGradient(left, top, left, top + tile,
                blend(base, Color.WHITE, 0.5f), base, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())
}

/** The pin File Manager's fixed taskbar button replaces. */
/** The tools the taskbar always shows by name, right after the Applications button; a pin of one would be its twin. */
private val FIXED_PINS = setOf("tool:files", "tool:tedit")
private const val TEDIT_KIND = "tedit"

/** Compares by identity: a list the app repository replaces on every reload, not one to compare item by item. */
internal class Identity(private val target: Any?) {
    override fun equals(other: Any?) = other is Identity && other.target === target
    override fun hashCode() = System.identityHashCode(target)
}
