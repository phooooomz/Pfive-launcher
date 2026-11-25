// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.tedit.ext.ExtensionStore
import com.lxlauncher.tedit.ext.OpenVsx
import java.io.File
import java.util.concurrent.Executors

/** A built-in extension: a group of languages, or one of Tedit's own features, that can be switched off. */
class Extension(val id: String, val name: Int, val description: Int, val languages: List<Language> = emptyList())

object Extensions {
    val all: List<Extension> by lazy {
        fun langs(pack: String) = Languages.all.filter { it.pack == pack }
        listOf(
            Extension("web", R.string.tedit_ext_web, R.string.tedit_ext_web_d, langs("web")),
            Extension("jvm", R.string.tedit_ext_jvm, R.string.tedit_ext_jvm_d, langs("jvm")),
            Extension("c", R.string.tedit_ext_c, R.string.tedit_ext_c_d, langs("c")),
            Extension("scripting", R.string.tedit_ext_scripting, R.string.tedit_ext_scripting_d, langs("scripting")),
            Extension("systems", R.string.tedit_ext_systems, R.string.tedit_ext_systems_d, langs("systems")),
            Extension("data", R.string.tedit_ext_data, R.string.tedit_ext_data_d, langs("data")),
            Extension("build", R.string.tedit_ext_build, R.string.tedit_ext_build_d, langs("build")),
            Extension("format.json", R.string.tedit_ext_format, R.string.tedit_ext_format_d),
            Extension("checks", R.string.tedit_ext_checks, R.string.tedit_ext_checks_d),
        )
    }
}

/** Shared by the panel and the detail page: the registry, the installed store, and the work that talks to them. */
object Marketplace {
    val io = Executors.newFixedThreadPool(3) { r -> Thread(r, "tedit-ext").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    /** Extension ids being installed right now, with their progress between 0 and 1 (or -1). */
    val installing = HashMap<String, Float>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it() } }

    fun install(context: android.content.Context, item: OpenVsx.Item, onDone: (Result<ExtensionStore.Installed>) -> Unit) {
        if (installing.containsKey(item.id)) return
        installing[item.id] = -1f
        changed()
        val ctx = context.applicationContext
        io.execute {
            val r = runCatching {
                val api = OpenVsx(ctx)
                val d = api.detail(item.namespace, item.name)
                val tmp = File(ctx.cacheDir, "tedit-ext-${System.nanoTime()}.vsix")
                try {
                    var last = 0L
                    api.download(d, tmp, { done, total ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - last > 120) { last = now; main.post { installing[item.id] = if (total > 0) done.toFloat() / total else -1f; changed() } }
                    }, { false })
                    ExtensionStore.get(ctx).install(tmp, org.json.JSONObject().put("source", "open-vsx").put("namespace", item.namespace).put("version", d.item.version))
                } finally {
                    tmp.delete()
                }
            }
            main.post {
                installing.remove(item.id)
                changed()
                onDone(r)
            }
        }
    }
}

/**
 * Extensions, as VS Code's view of them: the Open VSX marketplace to search and install from, the extensions
 * installed in Tedit, and Tedit's built-in language packs. The marketplace is only contacted after the person allows
 * it once (Settings > Privacy can take that back).
 */
class ExtensionsPanel(private val tool: TeditTool) : TPanel {
    override val id = "extensions"
    override val title = R.string.tedit_panel_extensions
    override val glyph = TIcon.Glyph.EXTENSIONS
    private val ui get() = tool.ui
    private val t get() = tool.theme
    private val ctx get() = tool.ctx
    private val store = ExtensionStore.get(tool.ctx)
    private val api = OpenVsx(tool.ctx)
    private val main = Handler(Looper.getMainLooper())
    /** Search, tabs and, on the marketplace, categories and sort above the results; they scroll in a short panel. */
    private val root = PanelColumn(ctx, t.dpi(40f), t.dpi(48f)) { tab == Tab.MARKET && consent() == "on" && items.isEmpty() }
    private val head = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** The marketplace's categories and sort, in the head so they scroll with it. */
    private val marketHead = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = root

    private enum class Tab { MARKET, INSTALLED, BUILTIN }
    private var tab = Tab.MARKET
    private var query = ""
    private var category: String? = null
    private var sort = "downloadCount"
    private val items = ArrayList<OpenVsx.Item>()
    private var total = 0
    private var loading = false
    private var error: String? = null
    @Volatile private var gen = 0
    private val tabsRow = ui.row()
    private val body = FrameLayoutHost()
    private val listView = ListView(ctx)
    private val adapter = MarketAdapter()
    private val storeListener: () -> Unit = { if (tab != Tab.MARKET) renderLocal() else adapter.notifyDataSetChanged(); renderTabs() }
    /**
     * An install reports its progress about eight times a second. Only the action label of the rows on screen changes
     * with it; rebinding the whole list on every report put each row's icon back to the placeholder until it loaded
     * again, so every icon in the list blinked for as long as an install ran (seen on the Infinix, 13 November 2026).
     */
    private val installListener: () -> Unit = {
        for (i in 0 until listView.childCount) (listView.getChildAt(i) as? ExtRow)?.refreshAction()
    }

    /** A plain frame whose content the panel swaps. */
    private inner class FrameLayoutHost : android.widget.FrameLayout(ctx)

    init {
        val field = ui.field(ctx.getString(R.string.tedit_ext_search_market))
        field.imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        var pending: Runnable? = null
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                // Typing waits a moment before asking the registry, so each letter does not send a request.
                pending?.let { main.removeCallbacks(it) }
                pending = Runnable { if (tab == Tab.MARKET) search(reset = true) else renderLocal() }.also { main.postDelayed(it, if (tab == Tab.MARKET) 400 else 0) }
            }
        })
        head.addView(field, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { setMargins(t.dpi(10f), t.dpi(8f), t.dpi(10f), t.dpi(4f)) })
        // A narrow side panel cannot show the three tabs side by side; they scroll instead of being cut off.
        head.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow, android.widget.FrameLayout.LayoutParams(TeditTool.WRAP, t.control)) },
            ui.lp(TeditTool.MATCH, t.control).apply { setMargins(t.dpi(6f), 0, t.dpi(6f), 0) })
        head.addView(ui.divider())
        head.addView(marketHead, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        val headScroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(head)
        }
        root.addView(headScroll, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        root.addView(body, ui.lp(TeditTool.MATCH, 0, 1f))
        root.setParts(headScroll, body)
        listView.divider = null
        listView.selector = android.graphics.drawable.ColorDrawable(0)
        listView.adapter = adapter
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
            override fun onScroll(v: AbsListView?, first: Int, visible: Int, count: Int) {
                // The next page loads before the end is reached.
                if (tab == Tab.MARKET && !loading && error == null && items.size < total && first + visible >= count - 4) search(reset = false)
            }
        })
        store.addListener(storeListener)
        Marketplace.addListener(installListener)
        renderTabs()
        show()
    }

    /** Shown again: the marketplace consent may have changed in Settings meanwhile. */
    private var shownConsent: String? = null

    override fun onShow() {
        if (tab == Tab.MARKET && consent() != shownConsent) show()
    }

    override fun dispose() {
        store.removeListener(storeListener)
        Marketplace.removeListener(installListener)
        gen++
    }

    private fun renderTabs() {
        tabsRow.removeAllViews()
        val n = store.installed().size
        for ((k, label) in listOf(Tab.MARKET to ctx.getString(R.string.tedit_ext_tab_market), Tab.INSTALLED to ctx.getString(R.string.tedit_ext_tab_installed, n),
            Tab.BUILTIN to ctx.getString(R.string.tedit_ext_tab_builtin))) {
            val on = k == tab
            tabsRow.addView(ui.text(label, t.textSmall, if (on) t.text else t.text2, medium = on).apply {
                gravity = Gravity.CENTER
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                background = ui.states(selected = on)
                isFocusable = true
                TeditUi.stateDesc(this, if (on) ctx.getString(R.string.tedit_open_state) else null)
                setOnClickListener { if (tab != k) { tab = k; renderTabs(); show() } }
            }, ui.lp(TeditTool.WRAP, TeditTool.MATCH))
        }
    }

    private fun show() {
        body.removeAllViews()
        marketHead.removeAllViews()
        when (tab) {
            Tab.MARKET -> showMarket()
            else -> renderLocal()
        }
    }

    // ---- marketplace ----

    private fun consent(): String = tool.prefs.string(TeditPrefs.MARKETPLACE)

    private fun showMarket() {
        shownConsent = consent()
        when (consent()) {
            "off" -> { body.addView(ui.scroll(ui.note(ctx.getString(R.string.tedit_ext_market_off)))); return }
            "ask" -> {
                val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY).apply { setPadding(t.dpi(14f), t.dpi(14f), t.dpi(14f), t.dpi(14f)) }
                col.addView(ui.text(ctx.getString(R.string.tedit_ext_market_ask_title), t.textBody, t.text, medium = true))
                col.addView(ui.text(ctx.getString(R.string.tedit_ext_market_ask), t.textSmall, t.text2, lines = 0).apply { setPadding(0, t.dpi(6f), 0, t.dpi(12f)) })
                val row = FlowRow(ctx, t.dpi(8f))
                row.addView(ui.button(ctx.getString(R.string.tedit_ext_market_allow), primary = true) { tool.prefs.set(TeditPrefs.MARKETPLACE, "on"); show() })
                row.addView(ui.button(ctx.getString(R.string.tedit_ext_market_deny)) { tool.prefs.set(TeditPrefs.MARKETPLACE, "off"); show() })
                col.addView(row)
                body.addView(ui.scroll(col))
                return
            }
        }
        // Categories, as on VS Code's marketplace.
        val chips = ui.row().apply { setPadding(t.dpi(6f), t.dpi(4f), t.dpi(6f), t.dpi(4f)) }
        for (c in listOf<String?>(null) + OpenVsx.CATEGORIES) {
            val on = c == category
            chips.addView(ui.text(c?.let { categoryLabel(it) } ?: ctx.getString(R.string.tedit_ext_all), t.textCaption, if (on) t.onAccent else t.text2, medium = true).apply {
                gravity = Gravity.CENTER
                minHeight = t.dpi(28f)
                setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = t.dp(14f)
                    if (on) setColor(t.accentFill) else setStroke(t.stroke, t.border)
                }
                foreground = ui.states(t.dp(14f))
                isFocusable = true
                setOnClickListener { category = c; show(); search(reset = true) }
            }, ui.lp(TeditTool.WRAP, t.dpi(28f)).apply { marginEnd = t.dpi(6f) })
        }
        marketHead.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
        val bar = ui.row().apply { setPadding(t.dpi(12f), 0, t.dpi(4f), 0) }
        status = ui.text("", t.textCaption, t.textMuted, lines = 2)
        bar.addView(status, ui.lp(0, TeditTool.WRAP, 1f))
        bar.addView(ui.text(ctx.getString(R.string.tedit_ext_sort, sortLabel(sort)), t.textCaption, t.accentText, medium = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = t.control
            setPadding(t.dpi(8f), 0, t.dpi(8f), 0)
            background = ui.states()
            isFocusable = true
            setOnClickListener {
                val keys = listOf("downloadCount", "averageRating", "timestamp", "relevance")
                tool.overlays.choose(ctx.getString(R.string.tedit_ext_sort_title), keys.map { sortLabel(it) }, keys.indexOf(sort)) { i -> sort = keys[i]; show(); search(reset = true) }
            }
        })
        marketHead.addView(bar, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
        (listView.parent as? ViewGroup)?.removeView(listView)
        body.addView(listView, android.widget.FrameLayout.LayoutParams(TeditTool.MATCH, TeditTool.MATCH))
        if (items.isEmpty() && !loading) search(reset = true) else renderStatus()
    }

    private var status: TextView? = null

    private fun renderStatus() {
        status?.text = when {
            error != null -> ctx.getString(R.string.tedit_ext_market_error, error)
            loading && items.isEmpty() -> ctx.getString(R.string.tedit_ext_loading)
            items.isEmpty() -> ctx.getString(R.string.tedit_ext_none, query)
            else -> ctx.resources.getQuantityString(R.plurals.tedit_ext_results, total, total)
        }
        status?.setTextColor(if (error != null) t.error else t.textMuted)
    }

    private fun search(reset: Boolean) {
        if (consent() != "on") return
        val g = if (reset) ++gen else gen
        if (reset) { items.clear(); total = 0; adapter.notifyDataSetChanged(); root.requestLayout() }
        loading = true
        error = null
        renderStatus()
        val offset = items.size
        val q = query; val cat = category; val s = sort
        Marketplace.io.execute {
            // Replaced by a newer search, or the panel was closed for good, while this one waited for a free thread.
            if (g != gen) return@execute
            val r = runCatching { api.search(q, cat, s, offset) }
            main.post {
                if (g != gen) return@post
                loading = false
                r.onSuccess { p -> items += p.items; total = p.total }
                r.onFailure { error = it.message ?: it.javaClass.simpleName }
                adapter.notifyDataSetChanged()
                renderStatus()
                root.requestLayout()
            }
        }
    }

    private fun categoryLabel(c: String): String = when (c) {
        "Programming Languages" -> ctx.getString(R.string.tedit_ext_cat_languages)
        "Snippets" -> ctx.getString(R.string.tedit_ext_cat_snippets)
        "Themes" -> ctx.getString(R.string.tedit_ext_cat_themes)
        "Formatters" -> ctx.getString(R.string.tedit_ext_cat_formatters)
        "Linters" -> ctx.getString(R.string.tedit_ext_cat_linters)
        else -> c
    }

    private fun sortLabel(s: String) = ctx.getString(when (s) {
        "averageRating" -> R.string.tedit_ext_sort_rating
        "timestamp" -> R.string.tedit_ext_sort_new
        "relevance" -> R.string.tedit_ext_sort_relevance
        else -> R.string.tedit_ext_sort_installs
    })

    private inner class MarketAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = (convertView as? ExtRow) ?: ExtRow()
            row.bind(item)
            return row
        }
    }

    /** One extension in a list: icon, name, publisher, what it does, how many use it, and Install. */
    private inner class ExtRow : LinearLayout(ctx) {
        val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = ui.text("", t.textBody, t.text, medium = true)
        val meta = ui.text("", t.textCaption, t.text2)
        val desc = ui.text("", t.textSmall, t.text2, lines = 2)
        val action = ui.text("", t.textCaption, t.accentText, medium = true).apply {
            gravity = Gravity.CENTER
            minHeight = t.dpi(28f)
            setPadding(t.dpi(10f), 0, t.dpi(10f), 0)
            isFocusable = true
        }
        var bound: OpenVsx.Item? = null
        /** The extension whose real icon the row shows, so binding the same extension again leaves the icon alone. */
        private var iconShown: String? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            setPadding(t.dpi(12f), t.dpi(8f), t.dpi(8f), t.dpi(8f))
            background = ui.states(0f)
            isFocusable = true
            addView(icon, ui.lp(t.dpi(36f), t.dpi(36f)).apply { marginEnd = t.dpi(10f); topMargin = t.dpi(2f) })
            val col = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            col.addView(name)
            col.addView(desc, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(2f) })
            val foot = ui.row()
            foot.addView(meta, ui.lp(0, TeditTool.WRAP, 1f))
            foot.addView(action, ui.lp(TeditTool.WRAP, t.dpi(28f)))
            col.addView(foot, ui.lp(TeditTool.MATCH, TeditTool.WRAP).apply { topMargin = t.dpi(2f) })
            addView(col, ui.lp(0, TeditTool.WRAP, 1f))
        }

        fun bind(item: OpenVsx.Item) {
            bound = item
            name.text = item.displayName
            desc.text = item.description
            val rating = item.rating?.let { " · ★ %.1f".format(it) } ?: ""
            meta.text = "${item.namespace} · ${compact(item.downloads)}$rating"
            contentDescription = "${item.displayName}, ${item.namespace}"
            refreshAction()
            setOnClickListener { ExtensionPage(tool, item, null).show() }
            showIcon(item)
        }

        /** Install, Update, Installed or the install's progress, as the store and the running installs say now. */
        fun refreshAction() {
            val item = bound ?: return
            val installed = store.get(item.id)
            val progress = Marketplace.installing[item.id]
            action.text = when {
                progress != null -> if (progress < 0) ctx.getString(R.string.tedit_ext_installing) else ctx.getString(R.string.tedit_ext_installing_pct, (progress * 100).toInt())
                installed != null && installed.version != item.version -> ctx.getString(R.string.tedit_ext_update)
                installed != null -> ctx.getString(R.string.tedit_ext_installed)
                else -> ctx.getString(R.string.tedit_ext_install)
            }
            val actionable = progress == null && (installed == null || installed.version != item.version)
            action.setTextColor(if (actionable) t.onAccent else t.textMuted)
            action.background = if (actionable) android.graphics.drawable.GradientDrawable().apply { cornerRadius = t.radius; setColor(t.accentFill) } else null
            action.setOnClickListener(if (actionable) View.OnClickListener { installFrom(item) } else null)
        }

        /**
         * The extension's icon. A row bound again to the extension it already shows keeps its picture; one the registry
         * sent earlier this session is set at once, and only an icon not fetched yet shows the placeholder while it loads.
         */
        private fun showIcon(item: OpenVsx.Item) {
            if (iconShown == item.id) return
            iconShown = null
            icon.tag = item.id
            val url = item.iconUrl
            val cached = url?.let { api.cachedIcon(it) }
            if (cached != null) {
                icon.setImageBitmap(cached)
                iconShown = item.id
                return
            }
            icon.setImageDrawable(TIcon(TIcon.Glyph.EXTENSIONS, t.textMuted))
            if (url == null) return
            val key = item.id
            Marketplace.io.execute {
                val bmp: Bitmap? = runCatching { api.icon(url, t.dpi(36f)) }.getOrNull()
                if (bmp != null) main.post { if (icon.tag == key) { icon.setImageBitmap(bmp); iconShown = key } }
            }
        }
    }

    private fun installFrom(item: OpenVsx.Item) {
        Marketplace.install(tool.ctx, item) { r ->
            r.onSuccess { e -> tool.overlays.snack(ctx.getString(R.string.tedit_ext_installed_named, e.displayName)); offerTheme(tool, e) }
            r.onFailure { tool.overlays.snack(ctx.getString(R.string.tedit_ext_install_failed, it.message ?: it.javaClass.simpleName)) }
        }
    }

    // ---- installed and built-in ----

    private fun renderLocal() {
        if (tab == Tab.MARKET) return
        body.removeAllViews()
        marketHead.removeAllViews()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(ui.scroll(list))
        if (tab == Tab.BUILTIN) { renderBuiltin(list); return }
        val shown = store.installed().filter { query.isEmpty() || it.displayName.contains(query, true) || it.id.contains(query, true) }
        if (shown.isEmpty()) list.addView(ui.note(ctx.getString(if (store.installed().isEmpty()) R.string.tedit_ext_installed_none else R.string.tedit_ext_none, query)))
        for (e in shown) {
            val row = ui.row(gravity = Gravity.TOP).apply {
                setPadding(t.dpi(12f), t.dpi(8f), t.dpi(6f), t.dpi(8f))
                background = ui.states(0f)
                isFocusable = true
                setOnClickListener { ExtensionPage(tool, null, e).show() }
            }
            val icon = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val bmp = e.icon?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }
            if (bmp != null) icon.setImageBitmap(bmp) else icon.setImageDrawable(TIcon(TIcon.Glyph.EXTENSIONS, t.textMuted))
            row.addView(icon, ui.lp(t.dpi(32f), t.dpi(32f)).apply { marginEnd = t.dpi(10f) })
            val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            labels.addView(ui.text(e.displayName, t.textBody, t.text, medium = true))
            labels.addView(ui.text("${e.namespace} · ${e.version}", t.textCaption, t.text2))
            labels.addView(ui.text(ExtensionPage.summary(ctx, e), t.textCaption, if (e.enabled) t.textMuted else t.warning, lines = 2))
            row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            row.addView(ui.toggle(e.enabled, e.displayName) { on -> store.setEnabled(e.id, on) })
            list.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            list.addView(ui.divider())
        }
    }

    private fun renderBuiltin(list: LinearLayout) {
        list.addView(ui.note(ctx.getString(R.string.tedit_ext_builtin_note)))
        val disabled = tool.prefs.disabledExtensions()
        val shown = Extensions.all.filter { e ->
            query.isEmpty() || ctx.getString(e.name).contains(query, true) || e.languages.any { it.name.contains(query, true) || it.extensions.any { x -> x.equals(query.removePrefix("."), true) } }
        }
        if (shown.isEmpty()) list.addView(ui.note(ctx.getString(R.string.tedit_ext_none, query)))
        for (e in shown) {
            val on = e.id !in disabled
            val row = ui.row(gravity = Gravity.TOP).apply { setPadding(t.dpi(14f), t.dpi(10f), t.dpi(6f), t.dpi(10f)) }
            val labels = ui.row(vertical = true, gravity = Gravity.NO_GRAVITY)
            labels.addView(ui.text(ctx.getString(e.name), t.textBody, t.text, medium = true))
            labels.addView(ui.text(ctx.getString(e.description), t.textCaption, t.text2, lines = 0).apply { setPadding(0, t.dpi(3f), 0, 0); setLineSpacing(0f, 1.2f) })
            if (e.languages.isNotEmpty()) labels.addView(ui.text(e.languages.joinToString(" · ") { it.name }, t.textCaption, t.textMuted, lines = 0).apply { setPadding(0, t.dpi(4f), 0, 0) })
            row.addView(labels, ui.lp(0, TeditTool.WRAP, 1f))
            row.addView(ui.toggle(on, ctx.getString(e.name)) { v -> tool.prefs.setExtensionEnabled(e.id, v); show() })
            list.addView(row, ui.lp(TeditTool.MATCH, TeditTool.WRAP))
            list.addView(ui.divider())
        }
    }

    companion object {
        /** 1234567 as "1.2M", the way the marketplace shows install counts. */
        fun compact(n: Long): String = when {
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
            n >= 1_000 -> "%.1fK".format(n / 1_000.0)
            else -> n.toString()
        }.replace(".0", "").let { "⬇ $it" }

        /** After a theme extension installs, offers its themes right away, as VS Code does. */
        fun offerTheme(tool: TeditTool, e: ExtensionStore.Installed) {
            if (e.themes.isEmpty()) return
            val store = ExtensionStore.get(tool.ctx)
            tool.overlays.choose(tool.ctx.getString(R.string.tedit_ext_pick_theme, e.displayName), e.themes.map { it.label }, -1) { i ->
                tool.prefs.setExtensionTheme(store.themeKey(e, e.themes[i]))
            }
        }
    }
}
