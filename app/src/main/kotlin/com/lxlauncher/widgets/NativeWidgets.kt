// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.widgets

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.WidgetItem
import com.lxlauncher.system.SystemInfo
import com.lxlauncher.ui.MeterView
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Native widget kinds the user can add, in picker order. */
object WidgetKinds {
    val all = listOf("clock", "calendar", "status", "battery", "memory", "storage", "network", "media", "notes", "apps", "sysinfo")

    fun label(ctx: Context, kind: String): String = ctx.getString(when (kind) {
        "clock" -> R.string.widget_clock
        "calendar" -> R.string.widget_calendar
        "status" -> R.string.widget_status
        "battery" -> R.string.widget_battery
        "memory" -> R.string.widget_memory
        "storage" -> R.string.widget_storage
        "network" -> R.string.widget_network
        "media" -> R.string.widget_media
        "notes" -> R.string.widget_notes
        "apps" -> R.string.widget_apps
        "sysinfo" -> R.string.widget_sysinfo
        "appwidget" -> R.string.widget_android
        else -> R.string.widget_unknown
    })

    /** Default size as a fraction of the desktop canvas. */
    fun defaultSize(kind: String, landscape: Boolean): Pair<Float, Float> = when (kind) {
        "calendar" -> if (landscape) 0.24f to 0.4f else 0.7f to 0.32f
        "status" -> if (landscape) 0.28f to 0.32f else 0.94f to 0.24f
        "notes" -> if (landscape) 0.24f to 0.28f else 0.94f to 0.2f
        "apps" -> if (landscape) 0.3f to 0.18f else 0.94f to 0.14f
        "clock" -> if (landscape) 0.28f to 0.22f else 0.94f to 0.15f
        else -> if (landscape) 0.22f to 0.18f else 0.6f to 0.14f
    }

    fun create(shell: Shell, item: WidgetItem): LxWidget = when (item.kind) {
        "clock" -> ClockWidget(shell)
        "calendar" -> CalendarWidget(shell)
        "status" -> StatusWidget(shell)
        "battery" -> BatteryWidget(shell)
        "memory" -> MemoryWidget(shell)
        "storage" -> StorageWidget(shell)
        "network" -> NetworkWidget(shell)
        "media" -> MediaWidget(shell)
        "notes" -> NotesWidget(shell)
        "apps" -> AppsWidget(shell)
        "sysinfo" -> SysInfoWidget(shell)
        else -> UnknownWidget(shell, item.kind)
    }
}

/**
 * A native widget. Widgets only listen for updates while [setActive] is true,
 * which the desktop sets for the visible workspace while the launcher is in
 * the foreground, and while the widget is attached: a widget whose view tree
 * was thrown away (a theme change rebuilds the whole UI) stops its receivers
 * and timers even if nobody told it to.
 */
abstract class LxWidget(protected val shell: Shell) : FrameLayout(shell.activity) {
    protected val theme: LxTheme get() = shell.ui
    protected var item: WidgetItem? = null
    /** What the desktop asked for. */
    private var wanted = false
    /** Whether onStart ran without a matching onStop. */
    private var running = false
    protected val main = Handler(Looper.getMainLooper())

    open fun bind(w: WidgetItem) {
        item = w
    }

    fun setActive(on: Boolean) {
        wanted = on
        apply()
    }

    private fun apply() {
        val on = wanted && isAttachedToWindow
        if (on == running) return
        running = on
        if (on) {
            onStart()
            refresh()
        } else onStop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        apply()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        apply()
    }

    open fun refresh() {}
    protected open fun onStart() {}
    protected open fun onStop() {}

    protected fun title(label: Int, icon: Int): LinearLayout = Ui.row(context).apply {
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(theme.accent)
        }, Ui.lp(theme.dpi(16f), theme.dpi(16f)))
        addView(Ui.text(context, theme, context.getString(label), theme.textLabel, theme.textMuted).apply {
            setPadding(theme.dpi(6f), 0, 0, 0)
        })
    }

    protected fun every(intervalMs: Long, block: () -> Unit): Runnable {
        val r = object : Runnable {
            override fun run() {
                block()
                main.postDelayed(this, intervalMs)
            }
        }
        return r
    }
}

private fun View.pad(theme: LxTheme, dp: Float = 12f) {
    val p = theme.dpi(dp)
    setPadding(p, p, p, p)
}

@SuppressLint("ViewConstructor")
class ClockWidget(shell: Shell) : LxWidget(shell) {
    private val time = Ui.text(context, theme, "", theme.textDisplay, theme.textPrimary).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.textDisplay * 1.2f)
    }
    private val date = Ui.text(context, theme, "", theme.textBody, theme.textMuted)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    init {
        pad(theme, 14f)
        addView(Ui.row(context, vertical = true, gravity = Gravity.START or Gravity.CENTER_VERTICAL).apply {
            addView(time)
            addView(date.apply { setPadding(0, theme.dpi(4f), 0, 0) })
        }, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    // The time scales with the widget so the date line always fits under it.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Sizes are worked out in pixels: font scaling is not linear on Android 14 and later.
        fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
        // A text line is about 1.33 times its size tall with font padding; the date keeps its own line.
        val dateLine = date.paint.fontMetrics.let { it.bottom - it.top } + date.paddingTop
        val byHeight = (h - paddingTop - paddingBottom - dateLine) / 1.33f
        val byWidth = (w - paddingLeft - paddingRight) * 0.22f
        val size = minOf(sp(theme.textDisplay * 1.6f), byHeight, byWidth).coerceAtLeast(sp(theme.textTitle))
        // A size change requests layout; doing it inside this layout pass would be dropped.
        post { time.setTextSize(TypedValue.COMPLEX_UNIT_PX, size) }
    }

    override fun refresh() {
        val now = Date()
        val pattern = if (item?.config?.get("h24") ?: shell.model.setting("clock.24h") == "true") "HH:mm" else "h:mm a"
        time.text = DateFormat.format(pattern, now)
        date.text = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMyyyy"), now)
        contentDescription = "${time.text}, ${date.text}"
    }

    override fun onStart() {
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    override fun onStop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

@SuppressLint("ViewConstructor")
class CalendarWidget(shell: Shell) : LxWidget(shell) {
    private val month = Ui.text(context, theme, "", theme.textTitle, theme.textPrimary, bold = true)
    private val grid = MonthGrid()
    private var day = -1
    private val tick = every(60_000) { if (Calendar.getInstance().get(Calendar.DAY_OF_YEAR) != day) refresh() }

    init {
        pad(theme)
        addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(month)
            addView(grid, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = theme.dpi(8f) })
        }, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    override fun refresh() {
        val c = Calendar.getInstance()
        day = c.get(Calendar.DAY_OF_YEAR)
        month.text = DateFormat.format("MMMM yyyy", c)
        contentDescription = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMyyyy"), c)
        grid.invalidate()
    }

    override fun onStart() {
        main.postDelayed(tick, 60_000)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
    }

    private inner class MonthGrid : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val c = Calendar.getInstance()
            val today = c.get(Calendar.DAY_OF_MONTH)
            val first = c.firstDayOfWeek
            c.set(Calendar.DAY_OF_MONTH, 1)
            val offset = (c.get(Calendar.DAY_OF_WEEK) - first + 7) % 7
            val days = c.getActualMaximum(Calendar.DAY_OF_MONTH)
            val rows = 7  // header + up to six weeks
            val cw = width / 7f
            val ch = height / rows.toFloat()
            paint.textSize = minOf(ch * 0.55f, theme.dp(13f))
            paint.typeface = theme.font
            val names = java.text.DateFormatSymbols.getInstance().shortWeekdays
            for (i in 0 until 7) {
                val dow = (first - 1 + i) % 7 + 1
                paint.color = theme.textMuted
                canvas.drawText(names[dow].take(2), cw * (i + 0.5f), ch * 0.7f, paint)
            }
            for (d in 1..days) {
                val idx = offset + d - 1
                val col = idx % 7
                val row = idx / 7 + 1
                val cx = cw * (col + 0.5f)
                val cy = ch * (row + 0.5f)
                if (d == today) {
                    paint.color = theme.accent
                    canvas.drawCircle(cx, cy, minOf(cw, ch) * 0.42f, paint)
                    paint.color = theme.onAccent
                } else paint.color = theme.textPrimary
                canvas.drawText(d.toString(), cx, cy + paint.textSize * 0.35f, paint)
            }
        }
    }
}

private class Reading(shell: Shell, label: Int, icon: Int) {
    val theme = shell.ui
    val ctx = shell.activity
    val value = Ui.text(ctx, theme, "", theme.textTitle, theme.textPrimary, bold = true)
    val detail = Ui.text(ctx, theme, "", theme.textCaption, theme.textMuted, lines = 1)
    val meter = MeterView(ctx, theme)
    val view: LinearLayout = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        addView(Ui.row(ctx).apply {
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(theme.accent)
            }, Ui.lp(theme.dpi(16f), theme.dpi(16f)))
            addView(Ui.text(ctx, theme, ctx.getString(label), theme.textLabel, theme.textMuted).apply {
                setPadding(theme.dpi(6f), 0, 0, 0)
            })
        })
        addView(value, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(3f) })
        addView(meter, Ui.lp(Ui.MATCH, theme.dpi(4f)).apply { topMargin = theme.dpi(4f) })
        addView(detail, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(3f) })
    }

    fun battery() {
        val b = SystemInfo.battery(ctx)
        if (!b.known) {
            value.text = ctx.getString(R.string.value_unavailable)
            detail.text = ctx.getString(R.string.battery_not_reported)
            meter.fraction = 0f
        } else {
            value.text = "${b.percent}%"
            meter.fraction = b.percent / 100f
            meter.color = if (b.percent <= 15 && !b.charging) theme.danger else theme.accent
            detail.text = ctx.getString(if (b.charging) R.string.battery_charging else R.string.battery_discharging)
        }
        view.contentDescription = "${ctx.getString(R.string.widget_battery)} ${value.text}, ${detail.text}"
    }

    fun memory() {
        val m = SystemInfo.memory(ctx)
        val used = m.totalBytes - m.availBytes
        value.text = ctx.getString(R.string.value_free, SystemInfo.formatBytes(m.availBytes))
        meter.fraction = if (m.totalBytes > 0) used / m.totalBytes.toFloat() else 0f
        meter.color = if (m.lowMemory) theme.danger else theme.accent
        detail.text = ctx.getString(R.string.value_of_total, SystemInfo.formatBytes(m.totalBytes))
        view.contentDescription = "${ctx.getString(R.string.widget_memory)} ${value.text}"
    }

    fun storage() {
        val s = SystemInfo.storage()
        value.text = ctx.getString(R.string.value_free, SystemInfo.formatBytes(s.freeBytes))
        meter.fraction = if (s.totalBytes > 0) 1f - s.freeBytes / s.totalBytes.toFloat() else 0f
        detail.text = ctx.getString(R.string.value_of_total, SystemInfo.formatBytes(s.totalBytes))
        view.contentDescription = "${ctx.getString(R.string.widget_storage)} ${value.text}"
    }

    fun network() {
        val n = SystemInfo.network(ctx)
        value.text = ctx.getString(when (n.type) {
            SystemInfo.Network.Type.WIFI -> R.string.net_wifi
            SystemInfo.Network.Type.CELLULAR -> R.string.net_cellular
            SystemInfo.Network.Type.ETHERNET -> R.string.net_ethernet
            SystemInfo.Network.Type.VPN -> R.string.net_vpn
            SystemInfo.Network.Type.OTHER -> R.string.net_other
            SystemInfo.Network.Type.NONE -> R.string.net_none
        })
        meter.visibility = View.GONE
        detail.text = when {
            !n.connected -> ctx.getString(R.string.net_offline_detail)
            !n.validated -> ctx.getString(R.string.net_no_internet)
            n.downKbps > 0 -> ctx.getString(R.string.net_estimate, n.downKbps / 1000)
            else -> ctx.getString(R.string.net_connected)
        }
        view.contentDescription = "${ctx.getString(R.string.widget_network)} ${value.text}, ${detail.text}"
    }
}

@SuppressLint("ViewConstructor")
class BatteryWidget(shell: Shell) : LxWidget(shell) {
    private val r = Reading(shell, R.string.widget_battery, R.drawable.ic_battery)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    init {
        pad(theme)
        addView(r.view, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun refresh() = r.battery()
    override fun onStart() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
    override fun onStop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}

@SuppressLint("ViewConstructor")
class MemoryWidget(shell: Shell) : LxWidget(shell) {
    private val r = Reading(shell, R.string.widget_memory, R.drawable.ic_memory)
    private val tick = every(5_000) { refresh() }

    init {
        pad(theme)
        addView(r.view, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun refresh() = r.memory()
    override fun onStart() {
        main.postDelayed(tick, 5_000)
    }
    override fun onStop() = main.removeCallbacks(tick)
}

@SuppressLint("ViewConstructor")
class StorageWidget(shell: Shell) : LxWidget(shell) {
    private val r = Reading(shell, R.string.widget_storage, R.drawable.ic_storage)
    private val tick = every(60_000) { refresh() }

    init {
        pad(theme)
        addView(r.view, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun refresh() = r.storage()
    override fun onStart() {
        main.postDelayed(tick, 60_000)
    }
    override fun onStop() = main.removeCallbacks(tick)
}

/** Listens to ConnectivityManager callbacks instead of polling. */
@SuppressLint("ViewConstructor")
class NetworkWidget(shell: Shell) : LxWidget(shell) {
    private val r = Reading(shell, R.string.widget_network, R.drawable.ic_wifi)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            post { refresh() }
        }
        override fun onLost(network: Network) {
            post { refresh() }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            post { refresh() }
        }
    }

    init {
        pad(theme)
        addView(r.view, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun refresh() = r.network()
    override fun onStart() {
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }
    override fun onStop() {
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}

/** Battery, memory, storage and network in one panel, like a system tray. */
@SuppressLint("ViewConstructor")
class StatusWidget(shell: Shell) : LxWidget(shell) {
    private val battery = Reading(shell, R.string.widget_battery, R.drawable.ic_battery)
    private val memory = Reading(shell, R.string.widget_memory, R.drawable.ic_memory)
    private val storage = Reading(shell, R.string.widget_storage, R.drawable.ic_storage)
    private val network = Reading(shell, R.string.widget_network, R.drawable.ic_wifi)
    private val grid = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val tick = every(5_000) { memory.memory() }
    private val batteryRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = battery.battery()
    }
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            post { network.network() }
        }
        override fun onLost(n: Network) {
            post { network.network() }
        }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
            post { network.network() }
        }
    }

    private val readings = listOf(battery, memory, storage, network)
    private val gap = theme.dpi(12f)

    init {
        pad(theme, 10f)
        fun pair(a: Reading, b: Reading) = Ui.row(context, gravity = Gravity.TOP).apply {
            addView(a.view, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = gap })
            addView(b.view, Ui.lp(0, Ui.WRAP, 1f))
        }
        grid.addView(pair(battery, memory), Ui.lp(Ui.MATCH, 0, 1f))
        grid.addView(pair(storage, network), Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = gap / 2 })
        addView(grid, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    // On a short widget the detail lines go first, so the values are never cut off.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cellW = (w - paddingLeft - paddingRight - gap) / 2
        if (cellW <= 0) return
        val exact = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        battery.view.measure(exact, unbounded)
        var full = battery.view.measuredHeight
        if (battery.detail.visibility == View.GONE) {
            battery.detail.measure(exact, unbounded)
            full += battery.detail.measuredHeight + (battery.detail.layoutParams as MarginLayoutParams).topMargin
        }
        val fits = 2 * full + gap / 2 <= h - paddingTop - paddingBottom
        // Visibility changes request layout; doing it inside this layout pass would be dropped.
        post { readings.forEach { it.detail.visibility = if (fits) View.VISIBLE else View.GONE } }
    }

    override fun refresh() {
        battery.battery()
        memory.memory()
        storage.storage()
        network.network()
    }

    override fun onStart() {
        context.registerReceiver(batteryRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        runCatching { cm.registerDefaultNetworkCallback(netCb) }
        main.postDelayed(tick, 5_000)
    }

    override fun onStop() {
        runCatching { context.unregisterReceiver(batteryRx) }
        runCatching { cm.unregisterNetworkCallback(netCb) }
        main.removeCallbacks(tick)
    }
}

/**
 * Sends media keys to whichever app holds the active media session, through
 * AudioManager. Track titles would need notification access, which LX does
 * not request, so the widget shows only whether audio is playing.
 */
@SuppressLint("ViewConstructor")
class MediaWidget(shell: Shell) : LxWidget(shell) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val state = Ui.text(context, theme, "", theme.textBody, theme.textMuted)
    private val playPause: ImageView
    private val skipButtons = ArrayList<View>()
    /** Android says when any playback starts or stops; the widget used to ask every two seconds. */
    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = refresh()
    }
    private var shownPlaying: Boolean? = null

    init {
        pad(theme)
        playPause = Ui.iconButton(context, theme, R.drawable.ic_play, context.getString(R.string.media_play_pause), 52f, 28f, theme.accent) {
            send(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        val controls = Ui.row(context, gravity = Gravity.CENTER).apply {
            addView(Ui.iconButton(context, theme, R.drawable.ic_previous, context.getString(R.string.media_previous)) {
                send(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }.also { skipButtons += it })
            addView(playPause)
            addView(Ui.iconButton(context, theme, R.drawable.ic_next, context.getString(R.string.media_next)) {
                send(KeyEvent.KEYCODE_MEDIA_NEXT)
            }.also { skipButtons += it })
        }
        addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(title(R.string.widget_media, R.drawable.ic_music))
            addView(state, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(4f) })
            addView(controls, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(4f) })
        }, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    private fun send(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        main.postDelayed({ refresh() }, 400)
    }

    override fun refresh() {
        val playing = audio.isMusicActive
        if (playing == shownPlaying) return
        shownPlaying = playing
        state.text = context.getString(if (playing) R.string.media_playing else R.string.media_idle)
        playPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Play can resume the last session; skipping needs something playing, so it reads as inactive.
        skipButtons.forEach { it.alpha = if (playing) 1f else 0.38f }
    }

    override fun onStart() {
        audio.registerAudioPlaybackCallback(playback, main)
    }
    override fun onStop() = audio.unregisterAudioPlaybackCallback(playback)
}

/** Quick notes stored in the widget's own config (max 8 KB). */
@SuppressLint("ViewConstructor")
class NotesWidget(shell: Shell) : LxWidget(shell) {
    private val editor = EditText(context).apply {
        typeface = theme.font
        setTextColor(theme.textPrimary)
        setHintTextColor(theme.textMuted)
        hint = context.getString(R.string.notes_hint)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.textBody)
        background = null
        gravity = Gravity.TOP or Gravity.START
        setPadding(0, theme.dpi(6f), 0, 0)
        filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
    }
    private var loading = false
    private var dirty = false
    private val save = Runnable {
        dirty = false
        val id = item?.id ?: return@Runnable
        shell.model.edit { NativeCore.setWidgetConfig(it, id, "text", editor.text.toString()) }
    }

    init {
        pad(theme)
        addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(title(R.string.widget_notes, R.drawable.ic_notes))
            addView(editor, Ui.lp(Ui.MATCH, 0, 1f))
        }, Ui.frame(Ui.MATCH, Ui.MATCH))
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (loading) return
                dirty = true
                main.removeCallbacks(save)
                main.postDelayed(save, 600)
            }
        })
    }

    override fun bind(w: WidgetItem) {
        super.bind(w)
        val text = w.config["text"].orEmpty()
        if (!editor.hasFocus() && editor.text.toString() != text) {
            loading = true
            editor.setText(text)
            loading = false
        }
    }

    override fun onStop() {
        if (dirty) {
            main.removeCallbacks(save)
            save.run()
        }
    }
}

/** Favourite apps as a row of icons. */
@SuppressLint("ViewConstructor")
class AppsWidget(shell: Shell) : LxWidget(shell) {
    private val row = Ui.row(context, gravity = Gravity.CENTER_VERTICAL)
    private val empty = Ui.text(context, theme, context.getString(R.string.apps_widget_empty), theme.textLabel, theme.textMuted, lines = 3)

    init {
        pad(theme, 8f)
        addView(row, Ui.frame(Ui.MATCH, Ui.MATCH))
        addView(empty, Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER_VERTICAL))
    }

    override fun refresh() {
        row.removeAllViews()
        val favs = shell.model.snapshot.favorites.mapNotNull { shell.apps.find(it) }.take(8)
        empty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        for (app in favs) {
            val icon = ImageView(context).apply {
                contentDescription = app.label
                tooltipText = app.label
                background = Ui.stateBackground(theme)
                isFocusable = true
                val p = theme.dpi(6f)
                setPadding(p, p, p, p)
                setOnClickListener { shell.launchApp(app.key, it) }
            }
            shell.apps.icon(app.key, theme.dpi(40f)) { icon.setImageDrawable(it) }
            row.addView(icon, Ui.lp(0, Ui.MATCH, 1f))
        }
    }

    override fun bind(w: WidgetItem) {
        super.bind(w)
        refresh()
    }
}

@SuppressLint("ViewConstructor")
class SysInfoWidget(shell: Shell) : LxWidget(shell) {
    private val body = Ui.text(context, theme, "", theme.textLabel, theme.textPrimary, lines = 0).apply {
        setLineSpacing(theme.dp(3f), 1f)
    }
    private val tick = every(60_000) { refresh() }

    init {
        pad(theme)
        addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(title(R.string.widget_sysinfo, R.drawable.ic_android))
            addView(body, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(6f) })
        }, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    override fun refresh() {
        val dm = resources.displayMetrics
        val lines = SystemInfo.device().map { it.second } +
            context.getString(R.string.sysinfo_uptime, SystemInfo.formatDuration(SystemInfo.uptimeMillis())) +
            context.getString(R.string.sysinfo_display, dm.widthPixels, dm.heightPixels, dm.densityDpi)
        body.text = lines.joinToString("\n")
    }

    override fun onStart() {
        main.postDelayed(tick, 60_000)
    }
    override fun onStop() = main.removeCallbacks(tick)
}

/** Shown when a layout names a widget kind this build does not know. */
@SuppressLint("ViewConstructor")
class UnknownWidget(shell: Shell, kind: String) : LxWidget(shell) {
    init {
        pad(theme)
        addView(Ui.text(context, theme, context.getString(R.string.widget_unknown_kind, kind), theme.textLabel, theme.textMuted, lines = 3),
            Ui.frame(Ui.MATCH, Ui.WRAP, Gravity.CENTER))
    }
}
