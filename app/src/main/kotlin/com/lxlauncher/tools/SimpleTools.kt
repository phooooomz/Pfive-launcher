// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tools

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.system.HomeRole
import com.lxlauncher.system.SystemInfo
import com.lxlauncher.ui.MeterView
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.StableScrollView
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.windows.ToolView
import java.util.concurrent.Executors

/** Scrollable column used by most tools. */
fun Shell.column(): Pair<ScrollView, LinearLayout> {
    val col = Ui.row(activity, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        val p = ui.dpi(16f)
        setPadding(p, ui.dpi(8f), p, ui.dpi(28f))
    }
    val scroll = StableScrollView(activity).apply {
        isFillViewport = true
        // A fading edge tells the reader there is more below the window's edge.
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(ui.dpi(28f))
        fadeInto = { ui.windowBody }
        addView(col)
    }
    return scroll to col
}

fun Shell.heading(text: CharSequence): TextView =
    // Two lines: a heading that only fits a wide window wraps on a phone instead of being cut.
    Ui.text(activity, ui, text, ui.textTitle, ui.textPrimary, bold = true, lines = 2).apply {
        setPadding(0, ui.dpi(16f), 0, ui.dpi(8f))
        isFocusable = false
        accessibilityHeading()
    }

fun TextView.accessibilityHeading() {
    if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

fun Shell.paragraph(text: CharSequence, muted: Boolean = false): TextView =
    Ui.text(activity, ui, text, ui.textBody, if (muted) ui.textMuted else ui.textPrimary, lines = 0).apply {
        setLineSpacing(ui.dp(3f), 1f)
        setPadding(0, 0, 0, ui.dpi(8f))
    }

/** First-run window: what LX does and how to make it the Home app. */
class WelcomeTool(private val shell: Shell) : ToolView {
    private val col: LinearLayout
    override val view: View
    private val homeStatus: TextView
    private val homeButton: TextView

    init {
        val (scroll, c) = shell.column()
        col = c
        view = scroll
        val ctx = shell.activity
        col.addView(shell.paragraph(ctx.getString(R.string.welcome_intro)))
        homeStatus = shell.paragraph("")
        col.addView(homeStatus)
        homeButton = Ui.button(ctx, shell.ui, ctx.getString(R.string.home_make_default), primary = true) {
            HomeRole.request(shell.activity)
        }
        col.addView(homeButton, Ui.lp(Ui.WRAP, Ui.WRAP))
        col.addView(shell.heading(ctx.getString(R.string.welcome_gestures_title)))
        for (line in ctx.resources.getStringArray(R.array.welcome_gestures)) col.addView(shell.paragraph("•  $line"))
        col.addView(shell.heading(ctx.getString(R.string.welcome_limits_title)))
        col.addView(shell.paragraph(ctx.getString(R.string.welcome_limits), muted = true))
    }

    override fun onShown() {
        val ctx = shell.activity
        val held = HomeRole.isDefaultHome(ctx)
        homeStatus.text = ctx.getString(if (held) R.string.home_is_default else R.string.home_not_default)
        homeButton.visibility = if (held) View.GONE else View.VISIBLE
    }
}

/** A plain text notepad stored on device with the same atomic writer as the layout. */
class NotesTool(private val shell: Shell) : ToolView {
    private val main = Handler(Looper.getMainLooper())
    private val editor = EditText(shell.activity).apply {
        typeface = shell.ui.font
        setTextColor(shell.ui.textPrimary)
        setHintTextColor(shell.ui.textMuted)
        hint = shell.activity.getString(R.string.notes_hint)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextSize(TypedValue.COMPLEX_UNIT_SP, shell.ui.textBody * 1.05f)
        gravity = Gravity.TOP or Gravity.START
        background = null
        val p = shell.ui.dpi(16f)
        setPadding(p, p, p, p)
        filters = arrayOf(android.text.InputFilter.LengthFilter(200_000))
    }
    private val status = Ui.text(shell.activity, shell.ui, "", shell.ui.textCaption, shell.ui.textMuted).apply {
        setPadding(shell.ui.dpi(16f), 0, shell.ui.dpi(16f), shell.ui.dpi(8f))
    }
    override val view: View = Ui.row(shell.activity, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        addView(editor, Ui.lp(Ui.MATCH, 0, 1f))
        addView(status, Ui.lp(Ui.MATCH, Ui.WRAP))
    }
    private var dirty = false
    private val save = Runnable { flush() }

    init {
        editor.setText(shell.model.loadNotes())
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                dirty = true
                status.text = shell.activity.getString(R.string.notes_unsaved)
                main.removeCallbacks(save)
                main.postDelayed(save, 800)
            }
        })
        status.text = shell.activity.getString(R.string.notes_saved)
    }

    private fun flush() {
        if (!dirty) return
        dirty = false
        shell.model.saveNotes(editor.text.toString())
        status.text = shell.activity.getString(R.string.notes_saved)
    }

    override fun onHidden() = flush()
    override fun onDestroy() = flush()
}

/**
 * Readings Android exposes to apps. System-wide CPU and GPU load are not
 * readable without privileges, so the monitor shows the launcher's own CPU
 * time and says plainly what is missing.
 */
class MonitorTool(private val shell: Shell) : ToolView {
    private val main = Handler(Looper.getMainLooper())
    override val view: View
    private val rows = LinkedHashMap<String, Triple<TextView, TextView, MeterView?>>()
    private var lastCpu = 0L
    private var lastWall = 0L
    /** PSS is read on its own thread; one read at a time, its result shown when it lands. */
    private val pssWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-monitor").apply { isDaemon = true } }
    private var pssReading = false
    private var destroyed = false
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    init {
        val (scroll, col) = shell.column()
        view = scroll
        val ctx = shell.activity
        fun add(key: String, label: Int, icon: Int, meter: Boolean) {
            val value = Ui.text(ctx, shell.ui, "", shell.ui.textTitle, shell.ui.textPrimary, bold = true, lines = 2)
            val detail = Ui.text(ctx, shell.ui, "", shell.ui.textLabel, shell.ui.textMuted, lines = 2)
            val m = if (meter) MeterView(ctx, shell.ui) else null
            col.addView(Ui.row(ctx).apply {
                addView(ImageView(ctx).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(shell.ui.accent)
                }, Ui.lp(shell.ui.dpi(20f), shell.ui.dpi(20f)).apply { marginEnd = shell.ui.dpi(10f) })
                addView(Ui.text(ctx, shell.ui, ctx.getString(label), shell.ui.textLabel, shell.ui.textMuted))
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = shell.ui.dpi(12f) })
            col.addView(value, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = shell.ui.dpi(4f) })
            if (m != null) col.addView(m, Ui.lp(Ui.MATCH, shell.ui.dpi(5f)).apply { topMargin = shell.ui.dpi(6f) })
            col.addView(detail, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = shell.ui.dpi(4f) })
            rows[key] = Triple(value, detail, m)
        }
        add("memory", R.string.monitor_memory, R.drawable.ic_memory, true)
        add("app", R.string.monitor_launcher, R.drawable.ic_monitor, false)
        add("battery", R.string.widget_battery, R.drawable.ic_battery, true)
        add("storage", R.string.widget_storage, R.drawable.ic_storage, true)
        add("network", R.string.widget_network, R.drawable.ic_wifi, false)
        add("device", R.string.widget_sysinfo, R.drawable.ic_android, false)
        col.addView(shell.heading(ctx.getString(R.string.monitor_limits_title)))
        col.addView(shell.paragraph(ctx.getString(R.string.monitor_limits), muted = true))
    }

    private fun refresh() {
        val ctx = shell.activity
        val m = SystemInfo.memory(ctx)
        rows["memory"]?.let { (v, d, meter) ->
            v.text = ctx.getString(R.string.memory_free_of, SystemInfo.formatBytes(m.availBytes), SystemInfo.formatBytes(m.totalBytes))
            meter?.fraction = if (m.totalBytes > 0) 1f - m.availBytes / m.totalBytes.toFloat() else 0f
            meter?.color = if (m.lowMemory) shell.ui.danger else shell.ui.accent
            d.text = ctx.getString(if (m.lowMemory) R.string.memory_low else R.string.memory_ok)
        }
        rows["app"]?.let { (v, d, _) ->
            val cpu = SystemInfo.ownCpuMillis()
            val wall = android.os.SystemClock.elapsedRealtime()
            val pct = if (lastWall > 0 && wall > lastWall) (cpu - lastCpu) * 100f / (wall - lastWall) else 0f
            lastCpu = cpu
            lastWall = wall
            readPss(v)
            d.text = ctx.getString(R.string.monitor_launcher_cpu, "%.1f".format(pct))
        }
        val b = SystemInfo.battery(ctx)
        rows["battery"]?.let { (v, d, meter) ->
            if (!b.known) {
                v.text = ctx.getString(R.string.value_unavailable)
                d.text = ctx.getString(R.string.battery_not_reported)
            } else {
                v.text = "${b.percent}%"
                meter?.fraction = b.percent / 100f
                d.text = ctx.getString(if (b.charging) R.string.battery_charging else R.string.battery_discharging) +
                    (b.temperatureC?.let { " · %.1f °C".format(it) } ?: "")
            }
        }
        val s = SystemInfo.storage()
        rows["storage"]?.let { (v, d, meter) ->
            v.text = ctx.getString(R.string.memory_free_of, SystemInfo.formatBytes(s.freeBytes), SystemInfo.formatBytes(s.totalBytes))
            meter?.fraction = if (s.totalBytes > 0) 1f - s.freeBytes / s.totalBytes.toFloat() else 0f
            d.text = ctx.getString(R.string.storage_internal)
        }
        val n = SystemInfo.network(ctx)
        rows["network"]?.let { (v, d, _) ->
            v.text = ctx.getString(when (n.type) {
                SystemInfo.Network.Type.WIFI -> R.string.net_wifi
                SystemInfo.Network.Type.CELLULAR -> R.string.net_cellular
                SystemInfo.Network.Type.ETHERNET -> R.string.net_ethernet
                SystemInfo.Network.Type.VPN -> R.string.net_vpn
                SystemInfo.Network.Type.OTHER -> R.string.net_other
                SystemInfo.Network.Type.NONE -> R.string.net_none
            })
            d.text = when {
                !n.connected -> ctx.getString(R.string.net_offline_detail)
                !n.validated -> ctx.getString(R.string.net_no_internet)
                n.downKbps > 0 -> ctx.getString(R.string.net_estimate, n.downKbps / 1000) +
                    if (n.metered) " · " + ctx.getString(R.string.net_metered) else ""
                else -> ctx.getString(R.string.net_connected)
            }
        }
        rows["device"]?.let { (v, d, _) ->
            val dev = SystemInfo.device()
            v.text = dev[0].second
            d.text = "${dev[1].second}\n${ctx.getString(R.string.sysinfo_uptime, SystemInfo.formatDuration(SystemInfo.uptimeMillis()))}"
        }
    }

    private fun readPss(into: TextView) {
        if (pssReading || destroyed) return
        pssReading = true
        pssWorker.execute {
            val kb = SystemInfo.ownPssKb()
            main.post {
                pssReading = false
                if (!destroyed) into.text = shell.activity.getString(R.string.monitor_launcher_value, SystemInfo.formatBytes(kb * 1024L))
            }
        }
    }

    override fun onShown() {
        lastWall = 0
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onHidden() = main.removeCallbacks(tick)
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(tick)
        pssWorker.shutdownNow()
    }
}
