// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.lxlauncher.R
import com.lxlauncher.tedit.ext.ExtensionStore
import com.lxlauncher.tedit.ext.OpenVsx

/**
 * One extension in full, from the marketplace ([item]) or as installed ([local]): who made it, what Tedit uses from
 * it and what it does not run, the actions, and its README.
 */
class ExtensionPage(private val tool: TeditTool, private val item: OpenVsx.Item?, private var local: ExtensionStore.Installed?) {
    private val ctx get() = tool.ctx
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val store = ExtensionStore.get(tool.ctx)
    private val api = OpenVsx(tool.ctx)
    private val main = Handler(Looper.getMainLooper())
    private val body = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(16f), t.dpi(12f), t.dpi(16f), t.dpi(28f)) }
    private var close: () -> Unit = {}
    private var detail: OpenVsx.Detail? = null
    private var readme: CharSequence? = null
    private val id = item?.id ?: local!!.id
    private val listener: () -> Unit = { local = store.get(id); render() }
    /**
     * Progress of a running install, about eight reports a second: only the buttons change. Rebuilding the whole page
     * on each report set the icon back to the placeholder until it loaded again and laid the README out again, so the
     * page blinked for as long as the install ran (seen on the Infinix, 13 November 2026).
     */
    private val progressListener: () -> Unit = { fillActions() }
    /** The icon once decoded, so building the page again shows it at once. */
    private var iconBitmap: android.graphics.Bitmap? = null
    private val actions by lazy { FlowRow(ctx, t.dpi(8f)).apply { setPadding(0, t.dpi(12f), 0, 0) } }

    fun show() {
        val root = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setBackgroundColor(t.bg) }
        val head = ui.row().apply { setBackgroundColor(t.panel); setPadding(t.dpi(2f), 0, t.dpi(8f), 0) }
        head.addView(ui.iconButton(TIcon.Glyph.ARROW_LEFT, ctx.getString(R.string.tedit_back)) { close() })
        head.addView(ui.text(ctx.getString(R.string.tedit_panel_extensions), t.textTitle, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        root.addView(head, ui.lp(TeditTool.MATCH, t.headerH))
        root.addView(ui.divider())
        root.addView(ui.scroll(body), ui.lp(TeditTool.MATCH, 0, 1f))
        val dismiss = tool.overlays.page(root)
        close = { store.removeListener(listener); Marketplace.removeListener(progressListener); dismiss() }
        store.addListener(listener)
        Marketplace.addListener(progressListener)
        render()
        load()
    }

    private fun load() {
        val it = item
        if (it != null && tool.prefs.string(TeditPrefs.MARKETPLACE) == "on") Marketplace.io.execute {
            val d = runCatching { api.detail(it.namespace, it.name) }.getOrNull()
            val md = d?.let { dd -> api.readme(dd) }
            main.post { detail = d; readme = md?.let { m -> markdown(m) }; render() }
        } else local?.readme?.let { f -> Marketplace.io.execute {
            val md = runCatching { f.readText().take(60_000) }.getOrNull()
            main.post { readme = md?.let { m -> markdown(m) }; render() }
        } }
    }

    private fun render() {
        body.removeAllViews()
        val e = local
        val name = item?.displayName ?: e!!.displayName
        val publisher = item?.namespace ?: e!!.namespace
        val version = item?.version ?: e!!.version
        val head = ui.row(gravity = Gravity.TOP)
        val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        head.addView(icon, ui.lp(t.dpi(56f), t.dpi(56f)).apply { marginEnd = t.dpi(14f) })
        showIcon(icon, e)
        val titles = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        titles.addView(ui.text(name, t.textTitle * 1.1f, t.text, medium = true, lines = 2))
        titles.addView(ui.text(listOfNotNull(publisher + if (item?.verified == true) " ✓" else "", version.takeIf { it.isNotEmpty() }?.let { "v$it" }).joinToString(" · "), t.textSmall, t.text2))
        val stats = item?.let { listOfNotNull(ExtensionsPanel.compact(it.downloads), it.rating?.let { r -> "★ %.1f".format(r) }, detail?.license).joinToString(" · ") }
        if (!stats.isNullOrEmpty()) titles.addView(ui.text(stats, t.textCaption, t.textMuted))
        head.addView(titles, ui.lp(0, TeditTool.WRAP, 1f))
        body.addView(head, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        val desc = item?.description ?: e?.description
        if (!desc.isNullOrEmpty()) body.addView(ui.text(desc, t.textBody, t.text2, lines = 0), ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(10f) })

        fillActions()
        body.addView(actions, LinearLayout.LayoutParams(TeditTool.MATCH, TeditTool.WRAP))

        // What Tedit takes from it.
        if (e != null) {
            body.addView(ui.sectionTitle(ctx.getString(R.string.tedit_ext_used)).apply { setPadding(0, t.dpi(18f), 0, t.dpi(4f)) })
            val used = usedLines(ctx, e)
            if (used.isEmpty()) body.addView(ui.note(ctx.getString(R.string.tedit_ext_used_none)))
            for (line in used) body.addView(bullet(line, t.text))
            val notRun = notRunLines(ctx, e)
            if (notRun.isNotEmpty()) {
                body.addView(ui.sectionTitle(ctx.getString(R.string.tedit_ext_not_run)).apply { setPadding(0, t.dpi(14f), 0, t.dpi(4f)) })
                for (line in notRun) body.addView(bullet(line, t.text2))
            }
        } else {
            body.addView(ui.note(ctx.getString(R.string.tedit_ext_before_install)).apply { setPadding(0, t.dpi(14f), 0, 0) })
        }

        readme?.let {
            body.addView(ui.divider().apply { }, ui.lp(TeditTool.MATCH, t.stroke).apply { topMargin = t.dpi(18f); bottomMargin = t.dpi(10f) })
            body.addView(ui.text("", t.textSmall, t.text, lines = 0).apply { text = it; setLineSpacing(0f, 1.25f); setTextIsSelectable(true) })
        }
    }

    /** The buttons for what can be done with the extension now, or the progress of its install. */
    private fun fillActions() {
        actions.removeAllViews()
        val e = local
        val progress = Marketplace.installing[id]
        when {
            progress != null -> actions.addView(ui.button(if (progress < 0) ctx.getString(R.string.tedit_ext_installing) else ctx.getString(R.string.tedit_ext_installing_pct, (progress * 100).toInt())) {}.apply { isEnabled = false })
            e == null && item != null -> actions.addView(ui.button(ctx.getString(R.string.tedit_ext_install), primary = true) { install(item) })
            e != null -> {
                if (item != null && item.version != e.version) actions.addView(ui.button(ctx.getString(R.string.tedit_ext_update), primary = true) { install(item) })
                if (e.themes.isNotEmpty() && e.enabled) actions.addView(ui.button(ctx.getString(R.string.tedit_ext_use_theme), primary = item == null || item.version == e.version) { ExtensionsPanel.offerTheme(tool, e) })
                actions.addView(ui.button(ctx.getString(if (e.enabled) R.string.tedit_ext_disable else R.string.tedit_ext_enable)) { store.setEnabled(e.id, !e.enabled) })
                actions.addView(ui.button(ctx.getString(R.string.tedit_ext_uninstall), danger = true) {
                    tool.overlays.confirm(ctx.getString(R.string.tedit_ext_uninstall_title, e.displayName), ctx.getString(R.string.tedit_ext_uninstall_message),
                        ctx.getString(R.string.tedit_ext_uninstall), danger = true) {
                        if (tool.prefs.extensionTheme()?.startsWith(e.id + "/") == true) tool.prefs.setExtensionTheme(null)
                        store.uninstall(e.id)
                        if (item == null) close()
                    }
                })
            }
        }
    }

    private fun showIcon(view: ImageView, e: ExtensionStore.Installed?) {
        iconBitmap?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(TIcon(TIcon.Glyph.EXTENSIONS, t.textMuted))
        val iconFile = e?.icon
        if (iconFile != null) runCatching { android.graphics.BitmapFactory.decodeFile(iconFile.path) }.getOrNull()?.let { iconBitmap = it; view.setImageBitmap(it) }
        else item?.iconUrl?.let { url ->
            api.cachedIcon(url)?.let { iconBitmap = it; view.setImageBitmap(it); return }
            Marketplace.io.execute { val b = runCatching { api.icon(url, t.dpi(56f)) }.getOrNull(); if (b != null) main.post { iconBitmap = b; view.setImageBitmap(b) } }
        }
    }

    private fun bullet(text: String, color: Int): View = ui.row(gravity = Gravity.TOP).apply {
        setPadding(0, t.dpi(3f), 0, t.dpi(3f))
        addView(ui.text("•", t.textSmall, t.textMuted), ui.lp(t.dpi(14f), TeditTool.WRAP))
        addView(ui.text(text, t.textSmall, color, lines = 0), ui.lp(0, TeditTool.WRAP, 1f))
    }

    private fun install(i: OpenVsx.Item) {
        Marketplace.install(tool.ctx, i) { r ->
            r.onSuccess { inst -> local = inst; tool.overlays.snack(ctx.getString(R.string.tedit_ext_installed_named, inst.displayName)); ExtensionsPanel.offerTheme(tool, inst); render() }
            r.onFailure { tool.overlays.snack(ctx.getString(R.string.tedit_ext_install_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    /** A plain rendering of README Markdown: headings, emphasis, code, lists; images and HTML tags dropped. */
    private fun markdown(src: String): CharSequence {
        val out = SpannableStringBuilder()
        var inCode = false
        val codeBg = TeditTheme.withAlpha(t.text, 0.07f)
        for (raw in src.replace("\r\n", "\n").lines().take(1500)) {
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (inCode) {
                val s = out.length
                out.append(raw).append('\n')
                out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            var line = raw.replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "").replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1").trimEnd()
            if (line.isBlank()) { if (!out.endsWith("\n\n") && out.isNotEmpty()) out.append('\n'); continue }
            val h = Regex("^(#{1,6})\\s+(.*)").find(line)
            if (h != null) {
                val s = out.length
                out.append(h.groupValues[2]).append('\n')
                out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(if (h.groupValues[1].length <= 2) 1.25f else 1.1f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                continue
            }
            line = line.replace(Regex("^\\s*[-*+]\\s+"), "• ")
            val s = out.length
            out.append(line).append('\n')
            // **bold** and `code` inside the line.
            for (m in Regex("\\*\\*([^*]+)\\*\\*").findAll(line)) out.setSpan(StyleSpan(Typeface.BOLD), s + m.range.first, s + m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for (m in Regex("`([^`]+)`").findAll(line)) {
                out.setSpan(TypefaceSpan("monospace"), s + m.range.first, s + m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), s + m.range.first, s + m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    companion object {
        /** One line for a list row: what Tedit uses from [e], or why nothing. */
        fun summary(ctx: Context, e: ExtensionStore.Installed): String {
            if (!e.enabled) return ctx.getString(R.string.tedit_ext_disabled)
            val parts = ArrayList<String>()
            if (e.themes.isNotEmpty()) parts += ctx.resources.getQuantityString(R.plurals.tedit_ext_n_themes, e.themes.size, e.themes.size)
            if (e.grammars.isNotEmpty()) parts += ctx.resources.getQuantityString(R.plurals.tedit_ext_n_grammars, e.grammars.size, e.grammars.size)
            if (e.snippets.isNotEmpty()) parts += ctx.resources.getQuantityString(R.plurals.tedit_ext_n_snippets, e.snippets.size, e.snippets.size)
            com.lxlauncher.tedit.lsp.LspServers.forExtension(e)?.let { parts += ctx.getString(R.string.tedit_ext_server_short, it.name) }
            return if (parts.isEmpty()) ctx.getString(R.string.tedit_ext_nothing_used) else parts.joinToString(" · ")
        }

        fun usedLines(ctx: Context, e: ExtensionStore.Installed): List<String> {
            val out = ArrayList<String>()
            if (e.themes.isNotEmpty()) out += ctx.getString(R.string.tedit_ext_used_themes, e.themes.joinToString(", ") { it.label })
            for (l in e.languages) out += ctx.getString(R.string.tedit_ext_used_language, l.aliases.firstOrNull() ?: l.id, (l.extensions + l.filenames).joinToString(" "))
            if (e.grammars.isNotEmpty()) out += ctx.getString(R.string.tedit_ext_used_grammars, e.grammars.joinToString(", ") { it.scopeName })
            if (e.snippets.isNotEmpty()) out += ctx.getString(R.string.tedit_ext_used_snippets, e.snippets.map { it.language }.distinct().joinToString(", "))
            com.lxlauncher.tedit.lsp.LspServers.forExtension(e)?.let { out += ctx.getString(R.string.tedit_ext_used_server, it.name, it.install) }
            return out
        }

        fun notRunLines(ctx: Context, e: ExtensionStore.Installed): List<String> {
            val out = ArrayList<String>()
            if (e.hasCode) out += ctx.getString(R.string.tedit_ext_code_not_run)
            val unused = e.unused.filter { it !in setOf("configuration", "configurationDefaults") }
            if (unused.isNotEmpty()) out += ctx.getString(R.string.tedit_ext_unused_points, unused.joinToString(", "))
            return out
        }
    }
}
