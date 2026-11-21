// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.lxlauncher.R
import com.lxlauncher.tedit.linux.LinuxRuntime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The one Debian setup that may run at a time, kept outside any page or window so closing Tedit does not lose its
 * progress. Listeners are called on the main thread.
 */
object LinuxSetup {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile var running = false
        private set
    @Volatile var step: LinuxRuntime.Step? = null
        private set
    @Volatile var done = 0L
        private set
    @Volatile var total = 0L
        private set
    @Volatile var error: String? = null
        private set
    @Volatile private var cancel = false
    private var lastPost = 0L

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun start(ctx: Context, picked: Uri? = null) {
        if (running) return
        running = true
        cancel = false
        error = null
        step = null
        changed()
        val app = ctx.applicationContext
        Thread({
            val rt = LinuxRuntime.get(app)
            val r = rt.install({ s, d, t ->
                step = s; done = d; total = t
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPost > 150 || d == t) { lastPost = now; changed() }
            }, { cancel }, picked?.let { uri -> { app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open the picked file") } })
            error = r.exceptionOrNull()?.let { if (cancel) null else (it.message ?: it.javaClass.simpleName) }
            running = false
            changed()
        }, "tedit-linux-setup").apply { isDaemon = true }.start()
    }

    fun cancel() { cancel = true }
}

/** Tedit's Linux page: whether Debian is set up, setting it up, repairing or removing it, and which shell to use. */
class LinuxPage(private val tool: TeditTool) {
    private val ctx get() = tool.ctx
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val rt = LinuxRuntime.get(tool.ctx)
    private val body = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(16f), t.dpi(8f), t.dpi(16f), t.dpi(24f)) }
    private var close: () -> Unit = {}
    private val listener: () -> Unit = { render() }
    private var progress: ProgressStrip? = null
    private var progressText: android.widget.TextView? = null
    private var shownRunning = false

    fun show() {
        val root = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setBackgroundColor(t.bg) }
        val head = ui.row().apply { setBackgroundColor(t.panel); setPadding(t.dpi(2f), 0, t.dpi(8f), 0) }
        head.addView(ui.iconButton(TIcon.Glyph.ARROW_LEFT, ctx.getString(R.string.tedit_back)) { close() })
        head.addView(ui.text(ctx.getString(R.string.tedit_linux_title), t.textTitle, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        root.addView(head, ui.lp(TeditTool.MATCH, t.headerH))
        root.addView(ui.divider())
        root.addView(ui.scroll(body), ui.lp(TeditTool.MATCH, 0, 1f))
        val dismiss = tool.overlays.page(root)
        close = { LinuxSetup.removeListener(listener); dismiss() }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { LinuxSetup.removeListener(listener) }
        })
        LinuxSetup.addListener(listener)
        render()
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(ctx, bytes)

    private fun render() {
        // While setup runs only the progress line changes; the rest of the page stays as it is.
        if (LinuxSetup.running && shownRunning) { updateProgress(); return }
        shownRunning = LinuxSetup.running
        body.removeAllViews()
        progress = null
        progressText = null
        body.addView(ui.text(ctx.getString(R.string.tedit_linux_intro), t.textBody, t.text2, lines = 0), ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { bottomMargin = t.dpi(12f) })
        val src = rt.source
        when (val st = rt.state()) {
            is LinuxRuntime.State.Unsupported -> status(TIcon.Glyph.WARNING, t.warning, ctx.getString(R.string.tedit_linux_unsupported, st.why))
            is LinuxRuntime.State.Installing -> running()
            is LinuxRuntime.State.Absent, is LinuxRuntime.State.Broken -> {
                if (LinuxSetup.running) { running(); return }
                if (st is LinuxRuntime.State.Broken) status(TIcon.Glyph.WARNING, t.warning, ctx.getString(R.string.tedit_linux_broken, st.why))
                else status(TIcon.Glyph.INFO, t.text2, ctx.getString(R.string.tedit_linux_absent))
                LinuxSetup.error?.let { status(TIcon.Glyph.ERROR, t.error, ctx.getString(R.string.tedit_linux_failed, it)) }
                if (src != null) {
                    val local = rt.localCopy(src)
                    val what = when {
                        src.asset != null -> ctx.getString(R.string.tedit_linux_from_apk, size(src.size), size(src.size * 5))
                        local != null -> ctx.getString(R.string.tedit_linux_from_download_folder, src.name)
                        else -> ctx.getString(R.string.tedit_linux_from_github, size(src.size), size(src.size * 5))
                    }
                    body.addView(ui.note(what))
                    actions(
                        ctx.getString(R.string.tedit_linux_install) to { LinuxSetup.start(ctx) },
                        ctx.getString(R.string.tedit_linux_pick) to { pick() },
                    )
                }
                if (st is LinuxRuntime.State.Broken) actions(ctx.getString(R.string.tedit_linux_remove) to { confirmRemove() })
            }
            is LinuxRuntime.State.Ready -> {
                val info = st.info
                status(TIcon.Glyph.CHECK, t.success, ctx.getString(R.string.tedit_linux_ready, info.optString("source"), info.optString("test")))
                body.addView(ui.note(ctx.getString(R.string.tedit_linux_ready_note, rt.rootfs.path)))
                actions(
                    ctx.getString(R.string.tedit_linux_open_terminal) to { close(); if (!tool.terminalOpen) tool.toggleTerminal(); tool.terminal.newSession(focus = true) },
                    ctx.getString(R.string.tedit_linux_repair) to { repair() },
                    ctx.getString(R.string.tedit_linux_remove) to { confirmRemove() },
                )
            }
        }
        body.addView(ui.sectionTitle(ctx.getString(R.string.tedit_linux_shell)).apply { setPadding(0, t.dpi(20f), 0, t.dpi(6f)) })
        val debian = tool.prefs.string(TeditPrefs.TERM_SHELL) == "debian"
        for ((value, label) in listOf("debian" to R.string.tedit_linux_shell_debian, "android" to R.string.tedit_linux_shell_android)) {
            val on = (value == "debian") == debian
            val row = ui.row().apply {
                minimumHeight = t.touch
                background = ui.states(selected = on)
                isFocusable = true
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                setOnClickListener { tool.prefs.set(TeditPrefs.TERM_SHELL, value); render() }
            }
            row.addView(ui.icon(if (on) TIcon.Glyph.CHECK else TIcon.Glyph.TERMINAL, if (on) t.accentText else t.text2, 16f), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(10f) })
            row.addView(ui.text(ctx.getString(label), t.textBody, t.text, lines = 2), ui.lp(0, TeditTool.WRAP, 1f))
            TeditUi.stateDesc(row, if (on) ctx.getString(R.string.tedit_open_state) else null)
            body.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        }
        body.addView(ui.note(ctx.getString(R.string.tedit_linux_network_note)).apply { setPadding(0, t.dpi(16f), 0, 0) })
    }

    private fun running() {
        status(TIcon.Glyph.IMPORT, t.accentText, ctx.getString(R.string.tedit_linux_installing))
        progress = tool.overlays.progressBar().also { body.addView(it, ui.lp(TeditTool.MATCH, t.dpi(3f)).apply { topMargin = t.dpi(8f) }) }
        progressText = ui.text("", t.textSmall, t.text2, lines = 2).also { body.addView(it, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(6f) }) }
        actions(ctx.getString(R.string.tedit_cancel) to { LinuxSetup.cancel() })
        updateProgress()
    }

    private fun updateProgress() {
        val s = LinuxSetup.step
        val d = LinuxSetup.done
        val tot = LinuxSetup.total
        progress?.fraction = if (tot > 0) (d.toFloat() / tot).coerceIn(0f, 1f) else -1f
        progressText?.text = when (s) {
            null, LinuxRuntime.Step.SPACE -> ctx.getString(R.string.tedit_linux_step_space)
            LinuxRuntime.Step.DOWNLOAD -> ctx.getString(R.string.tedit_linux_step_download, size(d), size(tot))
            LinuxRuntime.Step.UNPACK -> ctx.getString(R.string.tedit_linux_step_unpack, size(d), size(tot))
            LinuxRuntime.Step.CONFIGURE -> ctx.getString(R.string.tedit_linux_step_configure)
            LinuxRuntime.Step.TEST -> ctx.getString(R.string.tedit_linux_step_test)
        }
    }

    private fun status(glyph: TIcon.Glyph, color: Int, text: String) {
        val row = ui.row(gravity = Gravity.TOP).apply { setPadding(0, t.dpi(4f), 0, t.dpi(4f)) }
        row.addView(ui.icon(glyph, color, 18f), ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(10f); topMargin = t.dpi(1f) })
        row.addView(ui.text(text, t.textBody, t.text, lines = 0), ui.lp(0, TeditTool.WRAP, 1f))
        body.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
    }

    private fun actions(vararg items: Pair<String, () -> Unit>) {
        val row = FlowRow(ctx, t.dpi(8f)).apply { setPadding(0, t.dpi(12f), 0, 0) }
        items.forEachIndexed { i, (label, go) -> row.addView(ui.button(label, primary = i == 0) { go() }) }
        body.addView(row, LinearLayout.LayoutParams(TeditTool.MATCH, TeditTool.WRAP))
    }

    private fun pick() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        tool.requestDocument(intent) { uri -> if (uri != null) LinuxSetup.start(ctx, uri) }
    }

    private fun repair() {
        Thread({
            val r = rt.repair()
            tool.view.post {
                tool.overlays.snack(r.fold({ ctx.getString(R.string.tedit_linux_repaired, it) }, { ctx.getString(R.string.tedit_linux_failed, it.message ?: it.javaClass.simpleName) }))
                render()
            }
        }, "tedit-linux-repair").apply { isDaemon = true }.start()
    }

    private fun confirmRemove() {
        tool.overlays.confirm(ctx.getString(R.string.tedit_linux_remove_title), ctx.getString(R.string.tedit_linux_remove_message),
            ctx.getString(R.string.tedit_linux_remove), danger = true) {
            tool.terminal.closeDebianSessions()
            Thread({ rt.uninstall(); tool.view.post { render() } }, "tedit-linux-remove").apply { isDaemon = true }.start()
        }
    }
}
