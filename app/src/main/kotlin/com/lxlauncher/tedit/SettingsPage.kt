// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.files.FileServices
import java.lang.ref.WeakReference

/**
 * Tedit's Settings: twelve categories, each setting applied the moment it changes (the editor behind shows it), kept
 * across restarts, and reset per category or all at once after a confirmation.
 */
class SettingsPage(private val tool: TeditTool, private var category: TeditPrefs.Cat?) {
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val prefs get() = tool.prefs
    private var query = ""
    private var close: () -> Unit = {}
    private val frame = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var contentScroll: ScrollView? = null

    fun show() {
        close = tool.overlays.page(frame)
        open = WeakReference(this)
        render()
    }

    private fun wide() = tool.view.width >= t.dpi(600f)

    fun render() {
        val keepScroll = contentScroll?.scrollY ?: 0
        frame.removeAllViews()
        frame.setBackgroundColor(t.bg)
        val head = ui.row().apply { setBackgroundColor(t.panel); setPadding(t.dpi(2f), 0, t.dpi(8f), 0) }
        head.addView(ui.iconButton(TIcon.Glyph.ARROW_LEFT, ctx.getString(R.string.tedit_back)) { close() })
        head.addView(ui.text(ctx.getString(R.string.tedit_settings), t.textTitle, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        frame.addView(head, ui.lp(TeditTool.MATCH, t.headerH))
        frame.addView(ui.divider())
        val search = ui.field(ctx.getString(R.string.tedit_settings_search), query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                if (q != query) { query = q; renderContent() }
            }
        })
        frame.addView(search, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(12f), t.dpi(10f), t.dpi(12f), t.dpi(4f)) })
        val body = ui.row(gravity = Gravity.NO_GRAVITY)
        val cats = TeditPrefs.Cat.entries
        if (category == null) category = TeditPrefs.Cat.EDITOR
        if (wide()) {
            val nav = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(6f), t.dpi(6f), t.dpi(6f), t.dpi(6f)) }
            for (c in cats) nav.addView(ui.text(ctx.getString(c.title), t.textSmall, if (c == category) t.accentText else t.text, medium = c == category).apply {
                minHeight = t.control; gravity = Gravity.CENTER_VERTICAL; setPadding(t.dpi(12f), 0, t.dpi(12f), 0)
                background = ui.states(selected = c == category); isFocusable = true
                setOnClickListener { category = c; query = ""; render() }
            }, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            body.addView(ScrollView(ctx).apply { addView(nav) }, ui.lp(t.dpi(210f), TeditTool.MATCH))
            body.addView(ui.divider(vertical = true))
        } else {
            val chips = ui.row().apply { setPadding(t.dpi(8f), t.dpi(4f), t.dpi(8f), t.dpi(4f)) }
            for (c in cats) chips.addView(ui.text(ctx.getString(c.title), t.textSmall, if (c == category) t.accentText else t.text2, medium = c == category).apply {
                minHeight = t.control; gravity = Gravity.CENTER; setPadding(t.dpi(12f), 0, t.dpi(12f), 0)
                background = ui.states(selected = c == category); isFocusable = true
                setOnClickListener { category = c; query = ""; render() }
            })
            val hs = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
            frame.addView(hs, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            frame.addView(ui.divider())
            hs.post { chips.getChildAt(cats.indexOf(category)).let { v -> if (v != null) hs.smoothScrollTo(maxOf(0, v.left - t.control), 0) } }
        }
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(t.dpi(4f), 0, t.dpi(4f), t.dpi(32f)) }
        val sc = ScrollView(ctx).apply { addView(content) }
        contentScroll = sc
        body.addView(sc, ui.lp(0, TeditTool.MATCH, 1f))
        frame.addView(body, ui.lp(TeditTool.MATCH, 0, 1f))
        this.content = content
        renderContent()
        sc.post { sc.scrollTo(0, keepScroll) }
        // Focus rests on the page itself after a redraw, so the search field does not pull the keyboard up uninvited.
        frame.isFocusableInTouchMode = true
        frame.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (query.isEmpty()) frame.requestFocus()
    }

    private var content: LinearLayout? = null

    private fun renderContent() {
        val col = content ?: return
        col.removeAllViews()
        if (query.isNotEmpty()) {
            val hits = TeditPrefs.all.filter { s -> ctx.getString(s.title).contains(query, true) || (s.summary != 0 && ctx.getString(s.summary).contains(query, true)) }
            if (hits.isEmpty()) col.addView(ui.note(ctx.getString(R.string.tedit_settings_none, query)))
            for ((cat, list) in hits.groupBy { it.cat }) {
                col.addView(ui.sectionTitle(ctx.getString(cat.title)))
                list.forEach { col.addView(row(it)) }
            }
            return
        }
        val cat = category ?: TeditPrefs.Cat.EDITOR
        val head = ui.row().apply { setPadding(t.dpi(12f), t.dpi(14f), t.dpi(8f), t.dpi(6f)) }
        head.addView(ui.text(ctx.getString(cat.title), t.textTitle, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        val specs = TeditPrefs.all.filter { it.cat == cat }
        if (specs.isNotEmpty()) head.addView(ui.text(ctx.getString(R.string.tedit_reset_category), t.textSmall, t.accentText).apply {
            minHeight = t.control; gravity = Gravity.CENTER; setPadding(t.dpi(10f), 0, t.dpi(10f), 0); background = ui.states(); isFocusable = true
            setOnClickListener {
                tool.overlays.confirm(ctx.getString(R.string.tedit_reset_category_title, ctx.getString(cat.title)), ctx.getString(R.string.tedit_reset_message),
                    ctx.getString(R.string.tedit_reset), danger = true) { prefs.reset(specs.map { it.key }); render() }
            }
        })
        col.addView(head)
        if (cat == TeditPrefs.Cat.APPEARANCE) col.addView(preview())
        if (cat == TeditPrefs.Cat.EDITOR) col.addView(editorPreview())
        for (s in specs) col.addView(row(s))
        when (cat) {
            TeditPrefs.Cat.GENERAL -> about(col)
            TeditPrefs.Cat.TERMINAL -> col.addView(ui.note(ctx.getString(R.string.tedit_term_limits)))
            TeditPrefs.Cat.SCM -> col.addView(ui.note(ctx.getString(R.string.tedit_scm_read_only)))
            TeditPrefs.Cat.RUN -> col.addView(ui.note(ctx.getString(R.string.tedit_debug_unsupported)))
            TeditPrefs.Cat.EXTENSIONS -> extensions(col)
            TeditPrefs.Cat.PRIVACY -> privacy(col)
            TeditPrefs.Cat.ADVANCED -> advanced(col)
            else -> {}
        }
    }

    private fun labelOf(v: Any): String = if (v is Int) ctx.getString(v) else v.toString()

    private fun row(s: TeditPrefs.Spec): View {
        val r = ui.row().apply { minimumHeight = t.dpi(56f); setPadding(t.dpi(12f), t.dpi(8f), t.dpi(4f), t.dpi(8f)) }
        val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
        val title = ui.text(ctx.getString(s.title), t.textBody, t.text, lines = 2)
        labels.addView(title)
        if (s.summary != 0) labels.addView(ui.text(ctx.getString(s.summary), t.textCaption, t.textMuted, lines = 0).apply { setPadding(0, t.dpi(3f), 0, 0); setLineSpacing(0f, 1.2f) })
        if (!prefs.isDefault(s.key)) labels.addView(ui.text(ctx.getString(R.string.tedit_changed), t.textCaption, t.accentText).apply { setPadding(0, t.dpi(3f), 0, 0) })
        r.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
        when (val k = s.kind) {
            is TeditPrefs.Kind.Bool -> r.addView(ui.toggle(prefs.bool(s.key), ctx.getString(s.title)) { v -> prefs.set(s.key, v); renderLater() })
            is TeditPrefs.Kind.Choice -> {
                val cur = prefs.string(s.key)
                val idx = k.values.indexOf(cur).coerceAtLeast(0)
                r.addView(ui.text(labelOf(k.labels[idx]), t.textSmall, t.text).apply {
                    minHeight = t.control; gravity = Gravity.CENTER; setPadding(t.dpi(12f), 0, t.dpi(10f), 0)
                    background = ui.states(); isFocusable = true
                    compoundDrawablePadding = t.dpi(4f)
                    setCompoundDrawablesRelative(null, null, TIcon(TIcon.Glyph.CHEVRON_DOWN, t.text2).apply { setBounds(0, 0, t.dpi(14f), t.dpi(14f)) }, null)
                    contentDescription = ctx.getString(s.title) + ": " + labelOf(k.labels[idx])
                    setOnClickListener { tool.overlays.choose(ctx.getString(s.title), k.labels.map { labelOf(it) }, idx) { i -> prefs.set(s.key, k.values[i]); renderLater() } }
                })
            }
            is TeditPrefs.Kind.Num -> {
                val v = prefs.float(s.key)
                val step = ui.row()
                fun fmt(x: Float) = (if (k.step < 1f) String.format(java.util.Locale.ROOT, "%.2f", x).trimEnd('0').trimEnd('.') else x.toInt().toString()) + if (k.unit.isNotEmpty()) " ${k.unit}" else ""
                val value = ui.text(fmt(v), t.textSmall, t.text).apply { minWidth = t.dpi(64f); gravity = Gravity.CENTER }
                fun change(d: Float) {
                    val nv = (prefs.float(s.key) + d).coerceIn(k.min, k.max)
                    prefs.set(s.key, Math.round(nv / k.step) * k.step)
                    value.text = fmt(prefs.float(s.key))
                    value.announceForAccessibility(value.text)
                }
                step.addView(ui.iconButton(TIcon.Glyph.ARROW_LEFT, ctx.getString(R.string.tedit_less, ctx.getString(s.title)), t.text2, t.control) { change(-k.step) })
                step.addView(value)
                step.addView(ui.iconButton(TIcon.Glyph.ARROW_RIGHT, ctx.getString(R.string.tedit_more_of, ctx.getString(s.title)), t.text2, t.control) { change(k.step) })
                r.addView(step)
            }
        }
        return r
    }

    /** Re-render after the listener rebuilt the theme, so the new colours show at once. */
    private fun renderLater() = frame.post { if (frame.isAttachedToWindow) render() }

    private fun preview(): View {
        val card = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = t.radiusL; setColor(t.panel); setStroke(t.stroke, t.border) }
            setPadding(t.dpi(12f), t.dpi(10f), t.dpi(12f), t.dpi(12f))
        }
        val bar = ui.row()
        bar.addView(LogoMark(ctx, t), ui.lp(t.dpi(16f), t.dpi(16f)).apply { marginEnd = t.dpi(8f) })
        bar.addView(ui.text("main.kt", t.textSmall, t.text, medium = true), ui.lp(0, TeditTool.WRAP, 1f))
        bar.addView(ui.text(ctx.getString(R.string.tedit_preview), t.textCaption, t.textMuted))
        card.addView(bar)
        card.addView(codeSample(), ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(8f) })
        val buttons = ui.row().apply { setPadding(0, t.dpi(10f), 0, 0) }
        buttons.addView(ui.button(ctx.getString(R.string.tedit_save), primary = true) {}.apply { isClickable = false; isFocusable = false })
        buttons.addView(ui.button(ctx.getString(R.string.tedit_cancel)) {}.apply { isClickable = false; isFocusable = false }, ui.lp(TeditTool.WRAP, TeditTool.WRAP).apply { marginStart = t.dpi(8f) })
        card.addView(buttons)
        val wrap = LinearLayout(ctx).apply { setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(8f)) }
        wrap.addView(card, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        return wrap
    }

    private fun editorPreview(): View {
        val wrap = LinearLayout(ctx).apply { setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(8f)) }
        wrap.addView(codeSample(), ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        return wrap
    }

    /** A few lines of Kotlin in the code font, size and colours the settings give. */
    private fun codeSample(): View {
        val lines = listOf(
            "fun greet(name: String): String {" to listOf(0 to 3 to Token.KEYWORD, 4 to 9 to Token.FUNCTION, 16 to 22 to Token.TYPE, 25 to 31 to Token.TYPE),
            "    // say hello" to listOf(4 to 16 to Token.COMMENT),
            "    return \"Hi, \$name\" + 42" to listOf(4 to 10 to Token.KEYWORD, 11 to 22 to Token.STRING, 25 to 27 to Token.NUMBER),
            "}" to emptyList(),
        )
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { i, (text, spans) ->
            val num = "${i + 1}  "
            val base = sb.length
            sb.append(num)
            sb.setSpan(ForegroundColorSpan(t.gutterText), base, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(t.syntax[Token.PLAIN]), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            for ((range, kind) in spans) sb.setSpan(ForegroundColorSpan(t.syntax[kind]), start + range.first, start + minOf(text.length, range.second), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i < lines.size - 1) sb.append('\n')
        }
        return TextView(ctx).apply {
            text = sb
            typeface = t.codeFont
            textSize = prefs.float(TeditPrefs.FONT_SIZE) * 0.9f
            setLineSpacing(0f, prefs.float(TeditPrefs.LINE_HEIGHT) / 1.2f)
            setBackgroundColor(t.bg)
            setPadding(t.dpi(10f), t.dpi(8f), t.dpi(10f), t.dpi(8f))
            setHorizontallyScrolling(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun about(col: LinearLayout) {
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_about_title)))
        col.addView(ui.note(ctx.getString(R.string.tedit_about, info?.versionName ?: "", Languages.all.size - 1)))
    }

    private fun extensions(col: LinearLayout) {
        col.addView(ui.note(ctx.getString(R.string.tedit_ext_builtin_note)))
        val disabled = prefs.disabledExtensions()
        for (e in Extensions.all) {
            val r = ui.row().apply { minimumHeight = t.dpi(56f); setPadding(t.dpi(12f), t.dpi(6f), t.dpi(4f), t.dpi(6f)) }
            val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            labels.addView(ui.text(ctx.getString(e.name), t.textBody, t.text))
            labels.addView(ui.text(ctx.getString(e.description), t.textCaption, t.textMuted, lines = 0))
            r.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            r.addView(ui.toggle(e.id !in disabled, ctx.getString(e.name)) { v -> prefs.setExtensionEnabled(e.id, v) })
            col.addView(r)
        }
    }

    private fun privacy(col: LinearLayout) {
        val storage = FileServices.storage(ctx)
        val access = when {
            storage.fullAccess() -> R.string.tedit_privacy_access_full
            storage.allFilesAccess() -> R.string.tedit_privacy_access_all
            else -> R.string.tedit_privacy_access_picker
        }
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_privacy_storage)))
        col.addView(ui.note(ctx.getString(access)))
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_privacy_data)))
        col.addView(ui.note(ctx.getString(R.string.tedit_privacy_local)))
        val backups = tool.store.backups.count()
        val r = ui.row().apply { setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(4f)) }
        r.addView(ui.button(ctx.getString(R.string.tedit_clear_recent)) {
            tool.store.forget("files", null); tool.store.forget("folders", null); tool.prefs.putRaw("runHistory", null); tool.prefs.putRaw("cmdHistory", null)
            tool.overlays.snack(ctx.getString(R.string.tedit_recent_cleared))
        })
        col.addView(r)
        if (backups == 0) { col.addView(ui.note(ctx.getString(R.string.tedit_no_backups))); return }
        val r2 = ui.row().apply { setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(4f)) }
        r2.addView(ui.button(ctx.resources.getQuantityString(R.plurals.tedit_clear_backups, backups, backups), danger = true) {
            if (backups == 0) return@button
            tool.overlays.confirm(ctx.getString(R.string.tedit_clear_backups_title), ctx.getString(R.string.tedit_clear_backups_message), ctx.getString(R.string.tedit_delete), danger = true) {
                tool.store.io.execute { tool.store.backups.clearAll() }
                renderLater()
            }
        })
        col.addView(r2)
    }

    private fun advanced(col: LinearLayout) {
        col.addView(ui.sectionTitle(ctx.getString(R.string.tedit_reset_all)))
        col.addView(ui.note(ctx.getString(R.string.tedit_reset_all_note)))
        val r = ui.row().apply { setPadding(t.dpi(12f), t.dpi(4f), t.dpi(12f), t.dpi(4f)) }
        r.addView(ui.button(ctx.getString(R.string.tedit_reset_all), danger = true) {
            tool.overlays.confirm(ctx.getString(R.string.tedit_reset_all), ctx.getString(R.string.tedit_reset_all_message), ctx.getString(R.string.tedit_reset), danger = true) {
                prefs.reset(TeditPrefs.all.map { it.key } + TeditPrefs.EXT_DISABLED)
                renderLater()
            }
        })
        col.addView(r)
    }

    companion object {
        private var open: WeakReference<SettingsPage>? = null

        /** The theme changed: draw the open page again in it. */
        fun refreshOpen(tool: TeditTool) {
            val p = open?.get() ?: return
            if (p.tool !== tool || !p.frame.isAttachedToWindow) return
            p.render()
        }
    }
}
