// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tools

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.apps.AppEntry
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.system.SystemInfo
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.windows.ToolView
import java.util.concurrent.Executors

/**
 * Manages every app on the device through LX's own gated shell (lxdb). It trims every app's cache, and for one
 * chosen app it can force-stop it, wipe its data, open its Android settings, or read its memory use. The wipe and
 * force-stop each wait for a confirmation. LX itself is left out of the list so its pairing can never be cleared.
 * Everything runs as the shell user, so it works only while LX Bridge is up; otherwise each action says so.
 */
class AppManagerTool(private val shell: Shell) : ToolView {
    private val ctx = shell.activity
    private val ui = shell.ui
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-appmgr").apply { isDaemon = true } }
    private val client = EmbedClient.get(ctx)

    @Volatile private var destroyed = false
    private val col: LinearLayout
    override val view: View
    private val list: LinearLayout

    init {
        val (scroll, c) = shell.column()
        col = c
        view = scroll
        col.addView(shell.paragraph(ctx.getString(R.string.appmgr_subtitle), muted = true))
        col.addView(Ui.button(ctx, ui, ctx.getString(R.string.appmgr_trim_all)) { trimAll() }, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            topMargin = ui.dpi(4f)
            bottomMargin = ui.dpi(8f)
        })
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    override fun onShown() = rebuild()

    override fun onDestroy() {
        destroyed = true
        io.shutdownNow()
    }

    private fun rebuild() {
        list.removeAllViews()
        val apps = shell.apps.apps
            .filter { !it.isWorkProfile && it.packageName != ctx.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        if (apps.isEmpty()) {
            list.addView(shell.paragraph(ctx.getString(R.string.appmgr_loading), muted = true))
            return
        }
        for (app in apps) list.addView(row(app), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dpi(2f) })
    }

    private fun row(app: AppEntry): View {
        val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        shell.apps.icon(app.key, ui.dpi(40f)) { d -> if (!destroyed) icon.setImageDrawable(d) }
        val text = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(Ui.text(ctx, ui, app.label, ui.textBody, ui.textPrimary, lines = 1))
            addView(Ui.text(ctx, ui, app.packageName, ui.textCaption, ui.textMuted, lines = 1))
        }
        return Ui.row(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.stateBackground(ui)
            isClickable = true
            isFocusable = true
            contentDescription = app.label
            val p = ui.dpi(8f)
            setPadding(p, p, p, p)
            addView(icon, Ui.lp(ui.dpi(40f), ui.dpi(40f)).apply { marginEnd = ui.dpi(12f) })
            addView(text, Ui.lp(0, Ui.WRAP, 1f))
            setOnClickListener { openMenu(it, app) }
        }
    }

    private fun openMenu(anchor: View, app: AppEntry) {
        val pkg = app.packageName
        shell.showMenu(anchor, listOf(
            MenuItem(ctx.getString(R.string.appmgr_info), R.drawable.ic_info) { openInfo(pkg) },
            MenuItem(ctx.getString(R.string.appmgr_memory), R.drawable.ic_memory) { showMemory(pkg, app.label) },
            MenuItem(ctx.getString(R.string.appmgr_force_stop), R.drawable.ic_close, groupStart = true) { confirmForceStop(pkg, app.label) },
            MenuItem(ctx.getString(R.string.appmgr_clear_data), R.drawable.ic_delete, danger = true) { confirmClearData(pkg, app.label) },
        ))
    }

    // ---- actions ----

    private fun trimAll() {
        shell.confirm(ctx.getString(R.string.appmgr_trim_confirm_title), ctx.getString(R.string.appmgr_trim_confirm),
            ctx.getString(R.string.appmgr_trim_all)) {
            run("pm trim-caches 999999999999") { shell.notify(ctx.getString(R.string.appmgr_trimmed)) }
        }
    }

    private fun confirmForceStop(pkg: String, label: String) {
        shell.confirm(ctx.getString(R.string.appmgr_force_stop_confirm_title, label),
            ctx.getString(R.string.appmgr_force_stop_confirm, label), ctx.getString(R.string.appmgr_force_stop), danger = true) {
            run("am force-stop $pkg") { shell.notify(ctx.getString(R.string.appmgr_stopped, label)) }
        }
    }

    private fun confirmClearData(pkg: String, label: String) {
        shell.confirm(ctx.getString(R.string.appmgr_clear_data_confirm_title, label),
            ctx.getString(R.string.appmgr_clear_data_confirm, label), ctx.getString(R.string.appmgr_clear_data), danger = true) {
            run("pm clear $pkg") { shell.notify(ctx.getString(R.string.appmgr_cleared, label)) }
        }
    }

    private fun showMemory(pkg: String, label: String) {
        run("dumpsys meminfo $pkg") { out ->
            val kb = Regex("TOTAL(?: PSS)?:\\s*(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val text = if (kb != null) SystemInfo.formatBytes(kb * 1024) else ctx.getString(R.string.appmgr_not_running)
            shell.notify(ctx.getString(R.string.appmgr_memory_value, label, text))
        }
    }

    private fun openInfo(pkg: String) {
        shell.startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    /** Runs [command] as the shell user through lxdb, off the main thread; reports on main, or says the bridge is down. */
    private fun run(command: String, onOutput: (String) -> Unit) {
        // A dialog of this window can outlive it (Ctrl+W behind the dialog); its executor is shut down by then.
        if (destroyed || !safe(command)) return
        io.execute {
            val result = runCatching { client.shell(command) }.getOrNull()
            main.post {
                if (destroyed) return@post
                if (result == null) shell.notify(ctx.getString(R.string.appmgr_need_bridge))
                else onOutput(String(result.output, Charsets.UTF_8))
            }
        }
    }

    /** The commands are built from package names, which Android limits to these characters; this blocks shell injection. */
    private fun safe(command: String) = command.all { it.isLetterOrDigit() || it in " ._-" }
}
