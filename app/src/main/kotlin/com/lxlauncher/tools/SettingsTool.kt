// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tools

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.lxlauncher.BuildConfig
import com.lxlauncher.R
import com.lxlauncher.core.LauncherModel
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.Snapshot
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.system.Capability
import com.lxlauncher.system.DeviceCapabilities
import com.lxlauncher.system.HomeRole
import com.lxlauncher.system.Support
import com.lxlauncher.ui.Cards
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.StableScrollView
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.overlays.MenuMotion
import com.lxlauncher.ui.theme.LxFonts
import com.lxlauncher.ui.theme.Themes
import com.lxlauncher.ui.theme.WindowStyles
import com.lxlauncher.ui.windows.ToolView
import com.lxlauncher.widgets.WidgetKinds

/**
 * LX Settings. Every control writes through the native settings schema, so a value the core rejects never reaches
 * the UI.
 *
 * In a phone-wide window it opens on a list of its pages, grouped and each with a summary of what is set; a page
 * opens over the list with a back row at its top. From 560 dp the list stays as a column on the left. Each page
 * groups its rows on raised cards, and the pages that change the look (font, panels, the Applications menu's motion)
 * start with a live preview of what they set.
 */
class SettingsTool(private val shell: Shell, initialSection: String?) : ToolView {
    private val ctx = shell.activity
    private val theme get() = shell.ui

    private class Section(val key: String, val title: Int, val icon: Int, val group: Int)

    private val sections = listOf(
        Section("theme", R.string.settings_sec_theme, R.drawable.ic_image, R.string.settings_group_look),
        Section("fonts", R.string.settings_sec_fonts, R.drawable.ic_text_fields, R.string.settings_group_look),
        Section("panels", R.string.settings_sec_panels, R.drawable.ic_tile, R.string.settings_group_look),
        Section("motion", R.string.settings_sec_motion, R.drawable.ic_animation, R.string.settings_group_look),
        Section("desktop", R.string.settings_desktop, R.drawable.ic_home, R.string.settings_group_desktop),
        Section("workspaces", R.string.settings_workspaces, R.drawable.ic_workspaces, R.string.settings_group_desktop),
        Section("dock", R.string.settings_sec_dock, R.drawable.ic_apps, R.string.settings_group_desktop),
        Section("widgets", R.string.settings_widgets, R.drawable.ic_widgets, R.string.settings_group_desktop),
        Section("apps", R.string.settings_sec_apps, R.drawable.ic_android, R.string.settings_group_system),
        Section("accessibility", R.string.settings_accessibility, R.drawable.ic_accessibility, R.string.settings_group_system),
        Section("performance", R.string.settings_performance, R.drawable.ic_monitor, R.string.settings_group_system),
        Section("privacy", R.string.settings_privacy, R.drawable.ic_lock, R.string.settings_group_system),
        Section("backup", R.string.settings_backup, R.drawable.ic_file, R.string.settings_group_system),
        Section("about", R.string.settings_about, R.drawable.ic_info, R.string.settings_group_system),
    )

    /** The open page; null shows the list of pages (phone-wide windows only). */
    private var current: String? = resolve(initialSection) ?: if (initialSection == null) remembered else null

    /** True from 560 dp: the list of pages stays beside the open page. */
    private var wide = false
    private val nav = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val navScroll = StableScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(nav)
    }
    private val content = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        val p = theme.dpi(14f)
        setPadding(p, theme.dpi(2f), p, theme.dpi(32f))
    }
    /** Where the row builders put their rows: the card the page is filling, or the page itself. */
    private var target: LinearLayout = content
    private val scroll = StableScrollView(ctx).apply {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(theme.dpi(28f))
        fadeInto = { shell.ui.windowBody }
        addView(content)
        setOnScrollChangeListener { _, _, y, _, _ -> rememberedScroll = y }
    }
    private val body = Ui.row(ctx, gravity = Gravity.NO_GRAVITY)
    /**
     * Stays above the scrolling page: the page's title (with the way back in a narrow window) and, on the pages that
     * have one, the live preview, so it stays in view while the rows below it are scrolled and changed.
     */
    private val header = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        val p = theme.dpi(14f)
        setPadding(p, 0, p, theme.dpi(4f))
    }

    @SuppressLint("ViewConstructor")
    private inner class Root : FrameLayout(ctx) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val nowWide = w / resources.displayMetrics.density >= WIDE_DP
            // The first size of a narrow window changes nothing: the page was built narrow when it showed.
            if (nowWide != wide) post {
                wide = nowWide
                arrange()
                if (shown) build()
            }
        }
    }

    override val view: View = Root().apply {
        addView(body, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        body.addView(navScroll, Ui.lp(theme.dpi(NAV_DP), Ui.MATCH))
        body.addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(header, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        }, Ui.lp(0, Ui.MATCH, 1f))
    }

    private var tracking = false
    /** A licence text opened from About, by its asset path; null shows the About page itself. */
    private var licence: String? = null
    private var lastSettings: Map<String, String> = emptyMap()
    private var lastStructure = ""
    private val listener = LauncherModel.Listener { s -> onModel(s) }
    /**
     * The apps page shows LX Bridge's state, which changes when it connects or Wi-Fi comes and goes.
     * Registered only while the window shows. The rebuild is posted because build() reads the state itself,
     * and a change found there would otherwise start a second build inside the first.
     */
    private val embed = EmbedClient.get(ctx)
    private val embedListener = EmbedClient.Listener { content.post { if (shown && (current == "apps" || current == null)) build() } }
    /** True while the window shows; the page is built only then, and only once each time it shows. */
    private var shown = false
    /** The page and licence the content on screen was built for; null before the first build. */
    private var builtFor: String? = null
    /** Live previews on the page, which the sliders below them move while they are dragged. */
    private var panelPreview: PanelPreview? = null

    init {
        val restoring = current != null && current == remembered
        remembered = current
        arrange()
        // The page is built when the window shows (onShown). Building it here as well built it twice per opening.
        if (restoring) {
            val y = rememberedScroll
            scroll.post { scroll.scrollTo(0, y) }
        } else rememberedScroll = 0
    }

    /** Old page names still open their pages: links and searches made before the pages were split. */
    private fun resolve(key: String?): String? = when (key) {
        null -> null
        "appearance" -> "theme"
        "windows" -> "apps"
        else -> key.takeIf { k -> sections.any { it.key == k } }
    }

    fun show(section: String) {
        val key = resolve(section) ?: return
        open(key)
    }

    private fun open(key: String?) {
        current = key
        remembered = key
        licence = null
        rememberedScroll = 0
        arrange()
        if (shown) build()
        scroll.scrollTo(0, 0)
    }

    /** The list column beside the page in a wide window; in a narrow one the list is a page of its own. */
    private fun arrange() {
        navScroll.visibility = if (wide) View.VISIBLE else View.GONE
        if (wide && current == null) current = remembered ?: sections.first().key
        buildNav()
    }

    override fun onShown() {
        shown = true
        // Pages that show only LX's own settings stay as built while nothing on them changed. The others read Android's
        // state (LX Bridge, the default Home app, granted folders, app names), which can change while LX is hidden.
        val s = shell.model.snapshot
        val upToDate = builtFor == pageKey() && current in SETTINGS_ONLY_PAGES &&
            s.settings == lastSettings && structureOf(s) == lastStructure
        if (!upToDate) build()
        // Listening starts after the build, so the listener's first call finds nothing new to build for.
        shell.model.addListener(listener)
        embed.addListener(embedListener)
    }

    override fun onHidden() {
        shown = false
        shell.model.removeListener(listener)
        embed.removeListener(embedListener)
    }

    override fun onDestroy() = onHidden()

    /** Back leaves a licence text, then a page for the list of pages; a wide window has no list to go back to. */
    override fun onBack(): Boolean = when {
        licence != null -> {
            openLicence(null)
            true
        }
        !wide && current != null -> {
            open(null)
            true
        }
        else -> false
    }

    private fun structureOf(s: Snapshot) = s.workspaces.joinToString { "${it.id}:${it.name}:${it.wallpaper}" } +
        "|" + s.widgets.joinToString { "${it.id}:${it.visible}" } + "|" + s.dock.joinToString() + "|" + s.active

    private fun onModel(s: Snapshot) {
        // A model change can reach a page that was just hidden or destroyed, from the same round of listeners.
        if (!shown) return
        val structure = structureOf(s)
        if (tracking || (s.settings == lastSettings && structure == lastStructure)) return
        build()
    }

    /** The column of pages in a wide window: grouped, each with its icon; the open one is selected. */
    private fun buildNav() {
        nav.removeAllViews()
        if (!wide) return
        val t = theme
        nav.setPadding(t.dpi(8f), t.dpi(4f), t.dpi(4f), t.dpi(16f))
        var group = 0
        for (sec in sections) {
            if (sec.group != group) {
                group = sec.group
                nav.addView(Ui.text(ctx, t, ctx.getString(group), t.textLabel, t.textMuted).apply {
                    setPadding(t.dpi(10f), t.dpi(if (nav.childCount == 0) 6f else 14f), t.dpi(8f), t.dpi(4f))
                })
            }
            val on = sec.key == current
            nav.addView(Ui.row(ctx).apply {
                minimumHeight = t.dpi(40f).coerceAtLeast(if (t.largeTargets) t.touchTarget else 0)
                setPadding(t.dpi(10f), 0, t.dpi(8f), 0)
                background = Ui.stateBackground(t, t.dp(10f), selected = on)
                isSelected = on
                isFocusable = true
                addView(ImageView(ctx).apply {
                    setImageResource(sec.icon)
                    imageTintList = ColorStateList.valueOf(if (on) t.selectedText else t.textMuted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, Ui.lp(t.dpi(18f), t.dpi(18f)).apply { marginEnd = t.dpi(12f) })
                addView(Ui.text(ctx, t, ctx.getString(sec.title), t.textBody - 1f, if (on) t.selectedText else t.textPrimary, bold = on),
                    Ui.lp(0, Ui.WRAP, 1f))
                contentDescription = ctx.getString(sec.title)
                setOnClickListener { if (current != sec.key) open(sec.key) }
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
    }

    private fun pageKey() = "${current.orEmpty()}/${licence.orEmpty()}/$wide"

    private fun build() {
        val started = android.os.SystemClock.uptimeMillis()
        buildPage()
        android.util.Log.d("LxPerf", "LX Settings page ${current ?: "list"} built in ${android.os.SystemClock.uptimeMillis() - started} ms")
    }

    private fun buildPage() {
        val s = shell.model.snapshot
        lastSettings = s.settings
        lastStructure = structureOf(s)
        builtFor = pageKey()
        content.removeAllViews()
        header.removeAllViews()
        target = content
        panelPreview = null
        val key = current
        header.visibility = if (key == null) View.GONE else View.VISIBLE
        if (key == null) return home(s)
        val sec = sections.firstOrNull { it.key == key } ?: return home(s)
        pageTop(sec)
        when (key) {
            "theme" -> themePage(s)
            "fonts" -> fontsPage(s)
            "panels" -> panelsPage(s)
            "motion" -> motionPage(s)
            "desktop" -> desktop(s)
            "workspaces" -> workspaces(s)
            "apps" -> apps(s)
            "widgets" -> widgets(s)
            "dock" -> dock(s)
            "accessibility" -> accessibility(s)
            "performance" -> performance(s)
            "privacy" -> privacy()
            "backup" -> backup()
            else -> licence?.let { licenceText(it) } ?: about()
        }
    }

    /** The page's title; in a narrow window it is also the way back to the list. */
    private fun pageTop(sec: Section) {
        val t = theme
        if (wide) {
            header.addView(Ui.text(ctx, t, ctx.getString(sec.title), t.textTitle * 1.25f, t.textPrimary, bold = true, lines = 2).apply {
                setPadding(t.dpi(4f), t.dpi(10f), t.dpi(4f), t.dpi(2f))
                accessibilityHeading()
            })
            return
        }
        header.addView(Ui.row(ctx).apply {
            minimumHeight = t.touchTarget
            setPadding(0, t.dpi(2f), t.dpi(8f), t.dpi(2f))
            background = Ui.stateBackground(t, t.dp(10f))
            isFocusable = true
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron)
                scaleX = -1f
                imageTintList = ColorStateList.valueOf(t.textPrimary)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(t.touchTarget, t.dpi(22f)))
            addView(Ui.text(ctx, t, ctx.getString(sec.title), t.textTitle, t.textPrimary, bold = true, lines = 2).apply {
                accessibilityHeading()
            }, Ui.lp(0, Ui.WRAP, 1f))
            contentDescription = ctx.getString(R.string.settings_back_to_list)
            setOnClickListener { open(null) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    /** The list of pages: grouped, each row with its icon, its title and what is set there now. */
    private fun home(s: Snapshot) {
        val t = theme
        var group = 0
        for (sec in sections) {
            if (sec.group != group) {
                group = sec.group
                content.addView(Cards.heading(ctx, t, ctx.getString(group)))
                target = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                    background = Cards.raised(t)
                    setPadding(t.dpi(4f), t.dpi(4f), t.dpi(4f), t.dpi(4f))
                }
                content.addView(target, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            val summary = summaryOf(sec.key, s)
            target.addView(Ui.row(ctx).apply {
                minimumHeight = t.touchTarget + t.dpi(8f)
                setPadding(t.dpi(10f), t.dpi(6f), t.dpi(6f), t.dpi(6f))
                background = Ui.stateBackground(t, t.dp(10f))
                isFocusable = true
                addView(ImageView(ctx).apply {
                    setImageResource(sec.icon)
                    imageTintList = ColorStateList.valueOf(t.textMuted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, Ui.lp(t.dpi(22f), t.dpi(22f)).apply { marginEnd = t.dpi(14f) })
                addView(labelBlock(ctx.getString(sec.title), summary), Ui.lp(0, Ui.WRAP, 1f))
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_chevron)
                    imageTintList = ColorStateList.valueOf(t.textMuted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, Ui.lp(t.dpi(18f), t.dpi(18f)))
                contentDescription = if (summary.isNullOrEmpty()) ctx.getString(sec.title) else ctx.getString(sec.title) + ", " + summary
                setOnClickListener { open(sec.key) }
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        target = content
    }

    /** What a page has set now, in a few words; null for pages that set nothing to sum up. */
    private fun summaryOf(key: String, s: Snapshot): String? {
        fun two(a: String, b: String) = ctx.getString(R.string.settings_summary_two, a, b)
        fun choice(value: String, vararg pairs: Pair<String, Int>) = pairs.firstOrNull { it.first == value }?.second?.let { ctx.getString(it) } ?: value
        return when (key) {
            "theme" -> two(Themes.byId(s.setting("theme.palette")).name,
                choice(s.setting("theme.accent"), "palette" to R.string.accent_palette, "cyan" to R.string.accent_cyan,
                    "magenta" to R.string.accent_magenta, "blue" to R.string.accent_blue, "purple" to R.string.accent_purple))
            "fonts" -> two(LxFonts.option(s.setting("ui.font")).name, pct(s.float("ui.scale")))
            "panels" -> two(ctx.getString(WindowStyles.all.firstOrNull { it.id == s.setting("window.style") }?.nameRes
                ?: if (s.setting("window.style") == "custom") R.string.window_style_custom else R.string.window_style_theme),
                ctx.getString(R.string.settings_summary_opacity, pct(s.float("theme.panelOpacity"))))
            "motion" -> two(choice(s.setting("motion.mode"), "system" to R.string.motion_system, "on" to R.string.motion_on, "off" to R.string.motion_off),
                ctx.getString(MenuMotion.of(s.setting(MenuMotion.KEY)).label))
            "desktop" -> two(wallpaperLabel(s.activeWorkspace.wallpaper), "${s.int("desktop.gridCols")} × ${s.int("desktop.gridRows")}")
            "workspaces" -> ctx.resources.getQuantityString(R.plurals.settings_workspace_count, s.workspaces.size, s.workspaces.size)
            "dock" -> choice(s.setting("dock.position"), "auto" to R.string.position_auto, "left" to R.string.position_left,
                "right" to R.string.position_right, "bottom" to R.string.position_bottom)
            "widgets" -> s.widgets.count { !it.visible }.let { if (it == 0) null else ctx.resources.getQuantityString(R.plurals.settings_widgets_hidden_count, it, it) }
            "apps" -> ctx.getString(when (embed.status) {
                EmbedClient.Status.UNSUPPORTED -> R.string.embed_status_unsupported
                EmbedClient.Status.NOT_SET_UP -> R.string.embed_status_not_set_up
                EmbedClient.Status.NEEDS_WIFI -> R.string.embed_status_needs_wifi
                EmbedClient.Status.READY -> R.string.embed_status_ready
            }).let { ctx.getString(R.string.bridge_name) + ": " + it }
            "accessibility" -> listOfNotNull(
                ctx.getString(R.string.settings_large_targets).takeIf { s.bool("a11y.largeTargets") },
                ctx.getString(R.string.settings_high_contrast).takeIf { s.bool("a11y.highContrast") },
            ).joinToString(" · ").ifEmpty { ctx.getString(R.string.settings_a11y_default) }
            "performance" -> choice(s.setting("perf.mode"), "performance" to R.string.perf_performance,
                "balanced" to R.string.perf_balanced, "quality" to R.string.perf_quality)
            "about" -> BuildConfig.VERSION_NAME
            else -> null
        }
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    // ---- row builders ----

    private fun set(key: String, value: String) {
        if (!shell.model.edit { NativeCore.setSetting(it, key, value) }) shell.notify(ctx.getString(R.string.settings_rejected))
    }

    /**
     * A row whose label block takes the space its control leaves. Aligning the children's baselines, which these
     * centred rows never use, measured that block a second time for every width a resize went through.
     */
    private fun settingsRow(): LinearLayout = Ui.row(ctx).apply { isBaselineAligned = false }

    /**
     * Starts a group: its title over the page, then a raised card the next rows go into. With no title the card
     * follows the last one with a little space.
     */
    private fun section(title: Int?) {
        val t = theme
        if (title != null) target.addView(Cards.heading(ctx, t, ctx.getString(title)))
        val card = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            background = Cards.raised(t)
            setPadding(t.dpi(4f), t.dpi(4f), t.dpi(4f), t.dpi(4f))
        }
        target.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP).apply { if (title == null) topMargin = t.dpi(10f) })
        target = card
    }

    /** A sentence of explanation; inside a card it lines up with the rows' labels. */
    private fun note(text: CharSequence) = target.addView(shell.paragraph(text, muted = true).apply {
        if (target !== content) setPadding(theme.dpi(8f), theme.dpi(4f), theme.dpi(8f), theme.dpi(8f))
    })

    private fun labelBlock(label: CharSequence, detail: CharSequence?): LinearLayout =
        Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(Ui.text(ctx, theme, label, theme.textBody, theme.textPrimary, lines = 2))
            if (!detail.isNullOrEmpty()) addView(Ui.text(ctx, theme, detail, theme.textLabel, theme.textMuted, lines = 0).apply {
                setPadding(0, theme.dpi(2f), 0, 0)
            })
        }

    private fun switchRow(label: Int, key: String, s: Snapshot, detail: Int? = null) {
        val sw = Switch(ctx).apply {
            isChecked = s.bool(key)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(theme.accent, theme.textMuted))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(com.lxlauncher.ui.theme.LxTheme.withAlpha(theme.accent, 0.5f), com.lxlauncher.ui.theme.LxTheme.withAlpha(theme.textMuted, 0.4f)))
            contentDescription = ctx.getString(label)
            setOnCheckedChangeListener { _, on -> set(key, on.toString()) }
        }
        target.addView(settingsRow().apply {
            minimumHeight = theme.touchTarget
            background = Ui.stateBackground(theme)
            setPadding(theme.dpi(8f), theme.dpi(6f), theme.dpi(8f), theme.dpi(6f))
            addView(labelBlock(ctx.getString(label), detail?.let { ctx.getString(it) }), Ui.lp(0, Ui.WRAP, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun sliderRow(label: Int, key: String, s: Snapshot, min: Float, max: Float, step: Float,
                          format: (Float) -> String, toValue: (Float) -> String = { if (step >= 1f) it.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, it) },
                          fromValue: (Snapshot) -> Float = { it.float(key) }, detail: Int? = null,
                          onLive: ((Float) -> Unit)? = null) {
        val steps = ((max - min) / step).toInt()
        val valueText = Ui.text(ctx, theme, format(fromValue(s)), theme.textLabel, theme.accent)
        val bar = SeekBar(ctx).apply {
            this.max = steps
            progress = ((fromValue(s) - min) / step).toInt().coerceIn(0, steps)
            progressTintList = ColorStateList.valueOf(theme.accent)
            thumbTintList = ColorStateList.valueOf(theme.accent)
            contentDescription = ctx.getString(label)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = format(min + p * step)
                    onLive?.invoke(min + p * step)
                    if (fromUser && !tracking) set(key, toValue(min + p * step))  // keyboard and accessibility steps
                }
                override fun onStartTrackingTouch(b: SeekBar) {
                    tracking = true
                }
                override fun onStopTrackingTouch(b: SeekBar) {
                    tracking = false
                    set(key, toValue(min + b.progress * step))
                }
            })
        }
        target.addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            setPadding(theme.dpi(8f), theme.dpi(8f), theme.dpi(8f), theme.dpi(4f))
            addView(Ui.row(ctx).apply {
                addView(Ui.text(ctx, theme, ctx.getString(label), theme.textBody, theme.textPrimary), Ui.lp(0, Ui.WRAP, 1f))
                addView(valueText)
            })
            if (detail != null) addView(Ui.text(ctx, theme, ctx.getString(detail), theme.textLabel, theme.textMuted, lines = 0).apply {
                setPadding(0, theme.dpi(2f), 0, 0)
            })
            addView(bar, Ui.lp(Ui.MATCH, theme.touchTarget))
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun choiceRow(label: Int, key: String, s: Snapshot, options: List<Pair<String, Int>>, detail: Int? = null) {
        val currentValue = s.setting(key)
        val currentLabel = options.firstOrNull { it.first == currentValue }?.second?.let { ctx.getString(it) } ?: currentValue
        actionRow(ctx.getString(label), currentLabel, detail?.let { ctx.getString(it) }) {
            shell.choose(ctx.getString(label), options.map { ctx.getString(it.second) },
                options.indexOfFirst { it.first == currentValue }) { i -> set(key, options[i].first) }
        }
    }

    private fun actionRow(label: CharSequence, value: CharSequence?, detail: CharSequence? = null, danger: Boolean = false,
                          onClick: (View) -> Unit) {
        target.addView(settingsRow().apply {
            minimumHeight = theme.touchTarget
            background = Ui.stateBackground(theme)
            isFocusable = true
            setPadding(theme.dpi(8f), theme.dpi(6f), theme.dpi(8f), theme.dpi(6f))
            val block = labelBlock(label, detail)
            if (danger) (block.getChildAt(0) as TextView).setTextColor(theme.danger)
            addView(block, Ui.lp(0, Ui.WRAP, 1f))
            if (value != null) addView(Ui.text(ctx, theme, value, theme.textLabel, theme.accent).apply {
                setPadding(theme.dpi(8f), 0, 0, 0)
            })
            // The chevron marks the row as something that opens a choice or runs an action.
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(theme.textMuted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(theme.dpi(20f), theme.dpi(20f)))
            contentDescription = if (value != null) "$label, $value" else label
            setOnClickListener { onClick(it) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun pct(v: Float) = "${(v * 100).toInt()}%"

    /** The knobs of the custom window style, shown under the picker while that style is in use. */
    private fun customWindowStyle(s: Snapshot) {
        val bases = WindowStyles.all.map { it.id to it.nameRes }
        choiceRow(R.string.window_custom_base, "window.custom.base", s, bases, R.string.window_custom_base_detail)
        choiceRow(R.string.window_custom_accent, "window.custom.accent", s, listOf("style" to R.string.window_custom_accent_style) +
            WindowStyles.customAccents.map { (id, _) ->
                id to when (id) {
                    "blue" -> R.string.window_custom_accent_blue
                    "cyan" -> R.string.window_custom_accent_cyan
                    "teal" -> R.string.window_custom_accent_teal
                    "green" -> R.string.window_custom_accent_green
                    "amber" -> R.string.window_custom_accent_amber
                    "orange" -> R.string.window_custom_accent_orange
                    "magenta" -> R.string.window_custom_accent_magenta
                    "purple" -> R.string.window_custom_accent_purple
                    else -> R.string.window_custom_accent_red
                }
            })
        val dp = { v: Float -> "${v.toInt()} dp" }
        sliderRow(R.string.window_custom_corner, "window.custom.corner", s, 0f, 28f, 1f, dp,
            fromValue = { it.int("window.custom.corner", 20).toFloat() })
        sliderRow(R.string.window_custom_border, "window.custom.border", s, 0f, 3f, 0.5f, { "%.1f dp".format(it) })
        sliderRow(R.string.window_custom_caption, "window.custom.caption", s, 40f, 64f, 2f, dp,
            fromValue = { it.int("window.custom.caption", 52).toFloat() })
        sliderRow(R.string.window_custom_title_size, "window.custom.titleSize", s, 12f, 18f, 1f, { "${it.toInt()} sp" },
            fromValue = { it.int("window.custom.titleSize", 15).toFloat() })
        sliderRow(R.string.window_custom_icon_size, "window.custom.iconSize", s, 16f, 28f, 2f, dp,
            fromValue = { it.int("window.custom.iconSize", 20).toFloat() })
        choiceRow(R.string.window_custom_buttons, "window.custom.buttons", s, listOf(
            "dots" to R.string.window_custom_buttons_dots, "glyphs" to R.string.window_custom_buttons_glyphs,
            "boxed" to R.string.window_custom_buttons_boxed))
        sliderRow(R.string.window_custom_shadow, "window.custom.shadow", s, 0f, 24f, 2f, dp,
            fromValue = { it.int("window.custom.shadow", 12).toFloat() })
        switchRow(R.string.window_custom_dim, "window.custom.dimInactive", s, R.string.window_custom_dim_detail)
    }

    // ---- sections ----

    private fun themePage(s: Snapshot) {
        section(R.string.settings_theme)
        note(ctx.getString(R.string.settings_theme_detail))
        target.addView(ThemePicker(shell, s), Ui.lp(Ui.MATCH, Ui.WRAP))
        section(R.string.settings_theme_details)
        choiceRow(R.string.settings_accent, "theme.accent", s, listOf(
            "palette" to R.string.accent_palette, "cyan" to R.string.accent_cyan, "magenta" to R.string.accent_magenta,
            "blue" to R.string.accent_blue, "purple" to R.string.accent_purple))
        choiceRow(R.string.settings_preset, "theme.preset", s, listOf(
            "default" to R.string.preset_default, "minimal" to R.string.preset_minimal,
            "cyberpunk" to R.string.preset_cyberpunk, "productivity" to R.string.preset_productivity,
            "custom" to R.string.preset_custom), R.string.settings_preset_detail)
        choiceRow(R.string.settings_icon_style, "theme.iconStyle", s, listOf(
            "vivid" to R.string.icon_style_vivid, "line" to R.string.icon_style_line), R.string.settings_icon_style_detail)
        switchRow(R.string.settings_neon, "theme.neon", s, R.string.settings_neon_detail)
    }

    private fun fontsPage(s: Snapshot) {
        content.addView(FontPreview(shell), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(10f) })
        section(null)
        sliderRow(R.string.settings_ui_scale, "ui.scale", s, 0.85f, 1.3f, 0.05f, ::pct)
        note(ctx.getString(R.string.settings_font_detail))
        content.addView(Cards.heading(ctx, theme, ctx.getString(R.string.settings_font_choose)))
        content.addView(FontGrid(shell, fontColumns()), Ui.lp(Ui.MATCH, Ui.WRAP))
        target = content
    }

    /** Two font tiles side by side in a phone-wide window, three or four in wider ones. */
    private fun fontColumns(): Int {
        val widthDp = (if (scroll.width > 0) scroll.width else view.width) / ctx.resources.displayMetrics.density
        return when {
            widthDp >= 760f -> 4
            widthDp >= 500f -> 3
            else -> 2
        }
    }

    private fun panelsPage(s: Snapshot) {
        val preview = PanelPreview(shell, s.float("theme.panelOpacity"), s.int("theme.cornerRadius").toFloat())
        panelPreview = preview
        header.addView(preview, Ui.lp(Ui.MATCH, theme.dpi(PREVIEW_DP)).apply { topMargin = theme.dpi(6f) })
        header.addView(Ui.text(ctx, theme, ctx.getString(R.string.settings_panel_preview_note), theme.textCaption, theme.textMuted, lines = 3).apply {
            setPadding(theme.dpi(4f), theme.dpi(6f), theme.dpi(4f), 0)
        })
        section(R.string.settings_panel_group)
        sliderRow(R.string.settings_opacity, "theme.panelOpacity", s, 0.35f, 1f, 0.05f, ::pct,
            detail = R.string.settings_opacity_detail) { panelPreview?.opacity = it }
        sliderRow(R.string.settings_corners, "theme.cornerRadius", s, 0f, 20f, 1f, { "${it.toInt()} dp" },
            fromValue = { it.int("theme.cornerRadius").toFloat() }) { panelPreview?.cornerDp = it }
        switchRow(R.string.settings_blur, "theme.blur", s, R.string.settings_blur_detail)
        sliderRow(R.string.settings_blur_radius, "theme.blurRadius", s, 0f, 24f, 1f, { "${it.toInt()} dp" },
            fromValue = { it.int("theme.blurRadius").toFloat() })
        section(R.string.settings_window_style)
        note(ctx.getString(R.string.settings_window_style_detail))
        target.addView(WindowStylePicker(shell, s), Ui.lp(Ui.MATCH, Ui.WRAP))
        if (s.setting("window.style") == "custom") {
            section(R.string.settings_window_custom)
            customWindowStyle(s)
        }
        section(R.string.settings_window_behaviour)
        choiceRow(R.string.settings_window_default, "window.defaultState", s, listOf(
            "auto" to R.string.window_default_auto, "floating" to R.string.window_default_floating,
            "maximized" to R.string.window_default_maximized), R.string.settings_window_default_detail)
        switchRow(R.string.settings_edge_snap, "window.edgeSnap", s, R.string.settings_edge_snap_detail)
    }

    private fun motionPage(s: Snapshot) {
        val chosen = MenuMotion.of(s.setting(MenuMotion.KEY))
        val preview = MenuMotionPreview(shell, chosen)
        header.addView(preview, Ui.lp(Ui.MATCH, theme.dpi(PREVIEW_DP)).apply { topMargin = theme.dpi(6f) })
        header.addView(Ui.row(ctx).apply {
            addView(Ui.text(ctx, theme, if (theme.animations) ctx.getString(R.string.menu_motion_detail)
                else ctx.getString(R.string.menu_motion_paused), theme.textCaption, theme.textMuted, lines = 3), Ui.lp(0, Ui.WRAP, 1f))
            if (theme.animations && chosen != MenuMotion.OFF) addView(com.lxlauncher.files.FmSheets.compact(
                Ui.button(ctx, theme, ctx.getString(R.string.menu_motion_replay)) { preview.play(chosen) }, theme))
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(6f) })
        section(R.string.menu_motion_title)
        for (m in MenuMotion.values()) motionRow(m, m == chosen, preview)
        section(R.string.settings_motion_all)
        choiceRow(R.string.settings_motion, "motion.mode", s, listOf(
            "system" to R.string.motion_system, "on" to R.string.motion_on, "off" to R.string.motion_off),
            R.string.settings_motion_all_detail)
    }

    /** One way the menu's apps can arrive: its name and what it does; the one in use is selected, checked and named. */
    private fun motionRow(m: MenuMotion, active: Boolean, preview: MenuMotionPreview) {
        val t = theme
        target.addView(settingsRow().apply {
            minimumHeight = t.touchTarget
            setPadding(t.dpi(8f), t.dpi(6f), t.dpi(8f), t.dpi(6f))
            background = Ui.stateBackground(t, t.dp(10f), selected = active)
            isSelected = active
            isFocusable = true
            val block = labelBlock(ctx.getString(m.label), ctx.getString(m.detail))
            if (active) (block.getChildAt(0) as TextView).apply {
                setTextColor(t.selectedText)
                typeface = t.fontMedium
            }
            addView(block, Ui.lp(0, Ui.WRAP, 1f))
            if (active) {
                addView(Ui.text(ctx, t, ctx.getString(R.string.option_in_use), t.textLabel, t.selectedText).apply {
                    setPadding(t.dpi(8f), 0, t.dpi(6f), 0)
                })
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(t.selectedText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, Ui.lp(t.dpi(18f), t.dpi(18f)))
            }
            contentDescription = (if (active) ctx.getString(R.string.option_in_use_named, ctx.getString(m.label)) else ctx.getString(m.label)) +
                ". " + ctx.getString(m.detail)
            setOnClickListener {
                if (active) preview.play(m) else set(MenuMotion.KEY, m.id)
            }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun wallpaperLabel(ref: String): String = when {
        ref == "system" -> ctx.getString(R.string.wallpaper_system)
        ref == com.lxlauncher.ui.theme.Wallpapers.THEMED -> ctx.getString(R.string.wallpaper_theme)
        ref == "builtin:skyline" -> ctx.getString(R.string.wallpaper_skyline)
        ref == "builtin:rain" -> ctx.getString(R.string.wallpaper_rain)
        ref == "builtin:deep" -> ctx.getString(R.string.wallpaper_deep)
        ref.startsWith("builtin:") -> com.lxlauncher.ui.theme.Wallpapers.PHOTO_WALLPAPERS
            .firstOrNull { "builtin:${it.name}" == ref }?.let { ctx.getString(it.labelRes) } ?: ref
        ref.startsWith("color:") -> ctx.getString(R.string.wallpaper_color_value, ref.removePrefix("color:"))
        ref.startsWith("content://") || ref.startsWith("local:") -> ctx.getString(R.string.wallpaper_image)
        else -> ref
    }

    private fun desktop(s: Snapshot) {
        section(null)
        actionRow(ctx.getString(R.string.settings_wallpaper), wallpaperLabel(s.activeWorkspace.wallpaper),
            ctx.getString(R.string.settings_wallpaper_detail)) { shell.chooseWallpaper(s.active) }
        sliderRow(R.string.settings_grid_cols, "desktop.gridCols", s, 3f, 12f, 1f, { it.toInt().toString() },
            fromValue = { it.int("desktop.gridCols").toFloat() })
        sliderRow(R.string.settings_grid_rows, "desktop.gridRows", s, 3f, 12f, 1f, { it.toInt().toString() },
            fromValue = { it.int("desktop.gridRows").toFloat() })
        sliderRow(R.string.settings_icon_size, "desktop.iconSize", s, 36f, 72f, 2f, { "${it.toInt()} dp" },
            fromValue = { it.int("desktop.iconSize").toFloat() })
        switchRow(R.string.settings_labels, "desktop.labels", s)
        switchRow(R.string.settings_lock, "desktop.locked", s, R.string.settings_lock_detail)
        // The clock of the top panel and the clock widget; it sat with the look settings before the pages were split.
        switchRow(R.string.settings_clock24, "clock.24h", s)
        actionRow(ctx.getString(R.string.settings_edit_layout), null, ctx.getString(R.string.settings_edit_layout_detail)) {
            shell.setEditMode(true)
        }
    }

    private fun workspaces(s: Snapshot) {
        section(null)
        note(ctx.getString(R.string.settings_workspaces_detail))
        for (ws in s.workspaces) {
            target.addView(settingsRow().apply {
                minimumHeight = theme.touchTarget
                setPadding(theme.dpi(8f), 0, 0, 0)
                addView(labelBlock(ws.name + if (ws.id == s.active) "  ·  " + ctx.getString(R.string.workspace_active) else "", null),
                    Ui.lp(0, Ui.WRAP, 1f))
                addView(Ui.iconButton(ctx, theme, R.drawable.ic_edit, ctx.getString(R.string.workspace_rename_named, ws.name)) {
                    shell.prompt(ctx.getString(R.string.workspace_rename), ws.name) { n ->
                        if (!shell.model.edit { NativeCore.renameWorkspace(it, ws.id, n) }) shell.notify(ctx.getString(R.string.name_rejected))
                    }
                })
                if (s.workspaces.size > 1) addView(Ui.iconButton(ctx, theme, R.drawable.ic_delete,
                    ctx.getString(R.string.workspace_remove_named, ws.name), tint = theme.danger) {
                    shell.removeWorkspace(ws.id)
                })
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        if (s.workspaces.size < 12) {
            target.addView(Ui.button(ctx, theme, ctx.getString(R.string.workspace_add), primary = true) {
                val name = ctx.getString(R.string.workspace_default_name, s.workspaces.size + 1)
                shell.model.edit { NativeCore.addWorkspace(it, name) }
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(12f) })
        } else note(ctx.getString(R.string.workspace_limit))
    }

    private fun apps(s: Snapshot) {
        section(R.string.settings_android_apps)
        val freeform = shell.apps.freeformSupported()
        note(ctx.getString(R.string.tiers_explained))
        switchRow(R.string.settings_freeform, "apps.freeform", s, R.string.settings_freeform_detail)
        note(ctx.getString(if (freeform) R.string.tier2_supported else R.string.tier2_unsupported))
        // The device may still float apps once freeform windows are enabled by hand; point the way there.
        if (!freeform) actionRow(ctx.getString(R.string.settings_open_developer), null) {
            if (!shell.startSafely(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                shell.notify(ctx.getString(R.string.developer_options_hidden))
                shell.startSafely(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        windowFrame(s)
    }

    /** LX frames: Android apps shown inside LX windows, through LX Bridge. */
    private fun windowFrame(s: Snapshot) {
        embed.refresh()
        choiceRow(R.string.settings_window_frame, "apps.windowFrame", s, listOf(
            "system" to R.string.window_frame_system, "lx" to R.string.window_frame_lx), R.string.settings_window_frame_detail)
        val status = embed.status
        val ready = status == EmbedClient.Status.READY
        target.addView(settingsRow().apply {
            minimumHeight = theme.touchTarget
            setPadding(theme.dpi(8f), theme.dpi(6f), theme.dpi(8f), theme.dpi(6f))
            isFocusable = true
            val value = ctx.getString(when (status) {
                EmbedClient.Status.UNSUPPORTED -> R.string.embed_status_unsupported
                EmbedClient.Status.NOT_SET_UP -> R.string.embed_status_not_set_up
                EmbedClient.Status.NEEDS_WIFI -> R.string.embed_status_needs_wifi
                EmbedClient.Status.READY -> R.string.embed_status_ready
            })
            addView(labelBlock(ctx.getString(R.string.bridge_name), null), Ui.lp(0, Ui.WRAP, 1f))
            // The status is in words; colour only repeats it.
            addView(Ui.text(ctx, theme, value, theme.textLabel, if (ready) theme.accent else theme.textMuted, bold = ready, lines = 2).apply {
                setPadding(theme.dpi(8f), 0, 0, 0)
                gravity = Gravity.END
            })
            contentDescription = "${ctx.getString(R.string.bridge_name)}, $value"
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        when (status) {
            EmbedClient.Status.NOT_SET_UP -> {
                note(ctx.getString(R.string.bridge_setup_detail))
                target.addView(Ui.button(ctx, theme, ctx.getString(R.string.bridge_connect), primary = true) { shell.pairBridge() },
                    Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(4f) })
                target.addView(Ui.button(ctx, theme, ctx.getString(R.string.bridge_connect_code)) { shell.pairBridgeWithCode() },
                    Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(4f) })
            }
            EmbedClient.Status.NEEDS_WIFI -> note(ctx.getString(R.string.bridge_wifi_detail))
            else -> {}
        }
        if (embed.wirelessKeptOn) note(ctx.getString(R.string.bridge_wireless_kept_on))
        if (s.setting("apps.windowFrame") != "lx") return
        section(R.string.settings_always_android)
        note(ctx.getString(R.string.settings_always_android_detail))
        val kept = embed.alwaysAndroidKeys().map { it to (shell.apps.find(it)?.label ?: it.substringBefore('/')) }
            .sortedBy { it.second.lowercase() }
        for ((key, label) in kept) {
            target.addView(Ui.row(ctx).apply {
                minimumHeight = theme.touchTarget
                setPadding(theme.dpi(8f), 0, 0, 0)
                addView(Ui.text(ctx, theme, label, theme.textBody, theme.textPrimary), Ui.lp(0, Ui.WRAP, 1f))
                addView(Ui.iconButton(ctx, theme, R.drawable.ic_close, ctx.getString(R.string.action_remove_named, label)) {
                    embed.setAlwaysAndroid(key, false)
                    build()
                })
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        // Games and work apps keep Android's windows anyway, so they are not offered.
        val addable = shell.apps.apps.filter { !it.isWorkProfile && !shell.apps.isGame(it.key) && !embed.alwaysAndroid(it.key) }
        if (addable.isNotEmpty()) actionRow(ctx.getString(R.string.settings_always_android_add), ctx.getString(R.string.action_add)) {
            shell.choose(ctx.getString(R.string.settings_always_android_add), addable.map { it.label }, -1) { i ->
                embed.setAlwaysAndroid(addable[i].key, true)
                build()
            }
        }
    }

    private fun widgets(s: Snapshot) {
        section(R.string.settings_widgets_add)
        val ws = s.activeWorkspace
        for (kind in WidgetKinds.all) {
            actionRow(WidgetKinds.label(ctx, kind), ctx.getString(R.string.action_add)) { shell.addNativeWidget(kind, ws.id) }
        }
        actionRow(ctx.getString(R.string.widget_android_add), ctx.getString(R.string.action_add),
            ctx.getString(R.string.widget_android_add_detail)) { shell.pickAndroidWidget(ws.id) }
        note(ctx.getString(R.string.widgets_not_offered))
        val hidden = s.widgets.filter { !it.visible }
        section(R.string.settings_widgets_hidden)
        if (hidden.isEmpty()) note(ctx.getString(R.string.widgets_none_hidden))
        for (w in hidden) {
            actionRow(WidgetKinds.label(ctx, w.kind), ctx.getString(R.string.action_show),
                s.workspace(w.ws)?.name) { shell.model.edit { NativeCore.setWidgetVisible(it, w.id, true) } }
        }
    }

    private fun dock(s: Snapshot) {
        section(null)
        switchRow(R.string.settings_dock_visible, "dock.visible", s)
        choiceRow(R.string.settings_dock_position, "dock.position", s, listOf(
            "auto" to R.string.position_auto, "left" to R.string.position_left,
            "right" to R.string.position_right, "bottom" to R.string.position_bottom), R.string.settings_dock_position_detail)
        switchRow(R.string.settings_dock_autohide, "dock.autoHide", s, R.string.settings_dock_autohide_detail)
        sliderRow(R.string.settings_dock_icon, "dock.iconSize", s, 28f, 56f, 2f, { "${it.toInt()} dp" },
            fromValue = { it.int("dock.iconSize").toFloat() })
        section(R.string.settings_dock_entries)
        for ((i, entry) in s.dock.withIndex()) {
            target.addView(Ui.row(ctx).apply {
                minimumHeight = theme.touchTarget
                setPadding(theme.dpi(8f), 0, 0, 0)
                addView(Ui.text(ctx, theme, pinLabel(entry), theme.textBody, theme.textPrimary), Ui.lp(0, Ui.WRAP, 1f))
                if (i > 0) addView(Ui.iconButton(ctx, theme, R.drawable.ic_up, ctx.getString(R.string.action_move_up)) {
                    shell.model.edit { NativeCore.movePin(it, NativeCore.LIST_DOCK, entry, -1) }
                })
                addView(Ui.iconButton(ctx, theme, R.drawable.ic_close, ctx.getString(R.string.action_remove_named, pinLabel(entry))) {
                    shell.model.edit { NativeCore.unpin(it, NativeCore.LIST_DOCK, entry) }
                })
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val addable = listOf("action:home", "tool:files", "intent:browser", "tool:settings", "action:drawer", "action:games",
            "intent:gallery", "tool:monitor", "tool:notes", "tool:tedit", "action:search").filter { it !in s.dock }
        if (addable.isNotEmpty()) actionRow(ctx.getString(R.string.dock_add_entry), ctx.getString(R.string.action_add)) {
            shell.choose(ctx.getString(R.string.dock_add_entry), addable.map { pinLabel(it) }, -1) { i ->
                shell.model.edit { NativeCore.pin(it, NativeCore.LIST_DOCK, addable[i], -1) }
            }
        }
        note(ctx.getString(R.string.dock_apps_hint))
        section(R.string.settings_taskbar)
        switchRow(R.string.settings_taskbar_labels, "taskbar.labels", s)
        switchRow(R.string.settings_taskbar_windows, "taskbar.showWindows", s)
        switchRow(R.string.settings_top_panel, "panel.top", s)
        switchRow(R.string.settings_search_bar, "panel.search", s, R.string.settings_search_bar_detail)
        switchRow(R.string.settings_immersive, "display.immersive", s, R.string.settings_immersive_detail)
    }

    fun pinLabel(entry: String): String = when {
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

    private fun accessibility(s: Snapshot) {
        section(null)
        switchRow(R.string.settings_large_targets, "a11y.largeTargets", s, R.string.settings_large_targets_detail)
        switchRow(R.string.settings_high_contrast, "a11y.highContrast", s, R.string.settings_high_contrast_detail)
        choiceRow(R.string.settings_motion, "motion.mode", s, listOf(
            "system" to R.string.motion_system, "on" to R.string.motion_on, "off" to R.string.motion_off))
        note(ctx.getString(R.string.a11y_notes))
    }

    private fun performance(s: Snapshot) {
        section(null)
        choiceRow(R.string.settings_perf_mode, "perf.mode", s, listOf(
            "performance" to R.string.perf_performance, "balanced" to R.string.perf_balanced, "quality" to R.string.perf_quality))
        note(ctx.getString(R.string.perf_explained))
    }

    private fun privacy() {
        section(null)
        note(ctx.getString(R.string.privacy_statement))
        section(R.string.privacy_permissions)
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        for (p in info.requestedPermissions.orEmpty()) target.addView(shell.paragraph("•  ${p.substringAfterLast('.')}"))
        section(R.string.privacy_folders)
        val grants = ctx.contentResolver.persistedUriPermissions.filter { DocumentsContract.isTreeUri(it.uri) }
        if (grants.isEmpty()) note(ctx.getString(R.string.privacy_no_folders))
        for (g in grants) {
            val name = DocumentsContract.getTreeDocumentId(g.uri)
            actionRow(name, ctx.getString(R.string.action_revoke)) {
                ctx.contentResolver.releasePersistableUriPermission(g.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                build()
            }
        }
    }

    private fun backup() {
        section(null)
        actionRow(ctx.getString(R.string.backup_export), null, ctx.getString(R.string.backup_export_detail)) { shell.exportLayout() }
        // An import replaces everything, as a reset does, so it asks first as a reset does.
        actionRow(ctx.getString(R.string.backup_import), null, ctx.getString(R.string.backup_import_detail)) {
            shell.confirm(ctx.getString(R.string.backup_import), ctx.getString(R.string.backup_import_confirm),
                ctx.getString(R.string.action_import), danger = true) { shell.importLayout() }
        }
        actionRow(ctx.getString(R.string.backup_reset_layout), null, ctx.getString(R.string.backup_reset_layout_detail), danger = true) {
            shell.confirm(ctx.getString(R.string.backup_reset_layout), ctx.getString(R.string.backup_reset_layout_confirm),
                ctx.getString(R.string.action_reset), danger = true) { (ctx as? Resettable)?.resetLayout(keepSettings = true) }
        }
        actionRow(ctx.getString(R.string.backup_defaults), null, ctx.getString(R.string.backup_defaults_detail), danger = true) {
            shell.confirm(ctx.getString(R.string.backup_defaults), ctx.getString(R.string.backup_defaults_confirm),
                ctx.getString(R.string.action_reset), danger = true) { (ctx as? Resettable)?.resetLayout(keepSettings = false) }
        }
    }

    private fun about() {
        section(null)
        note(ctx.getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        val held = HomeRole.isDefaultHome(ctx)
        actionRow(ctx.getString(R.string.about_home_status),
            ctx.getString(if (held) R.string.home_is_default_short else R.string.home_not_default_short)) {
            if (held) HomeRole.openHomeSettings(ctx) else HomeRole.request(ctx)
        }
        actionRow(ctx.getString(R.string.about_rollback), null, ctx.getString(R.string.about_rollback_detail)) {
            HomeRole.openHomeSettings(ctx)
        }
        section(R.string.about_capabilities)
        val caps = DeviceCapabilities.probe(ctx, shell.apps)
        DeviceCapabilities.log(caps)
        for (c in caps) capabilityRow(c)
        section(R.string.about_layout_health)
        val load = shell.model.lastLoad
        note(ctx.getString(R.string.about_layout_body, load.skipped,
            ctx.getString(if (load.usedBackup) R.string.yes else R.string.no),
            ctx.getString(if (load.mainCorrupt) R.string.yes else R.string.no)))
        section(R.string.about_diagnostics)
        val diag = shell.model.diagnostics()
        if (diag.isEmpty()) note(ctx.getString(R.string.about_no_diagnostics))
        diag.takeLast(12).forEach { note(it) }
        section(R.string.about_licenses)
        val notice = runCatching { ctx.assets.open("NOTICE.txt").bufferedReader().use { it.readText() } }.getOrNull()
        note(notice ?: ctx.getString(R.string.about_no_notice))
        val texts = listOf(
            "licenses/GPL-3.0.txt" to ctx.getString(R.string.licence_gpl),
            "licenses/GPL-2.0.txt" to ctx.getString(R.string.licence_gpl2),
            "licenses/Apache-2.0.txt" to ctx.getString(R.string.licence_apache),
        ) + LxFonts.all.map { "licenses/fonts/${it.id}.txt" to ctx.getString(R.string.licence_ofl, it.name) }
        for ((path, label) in texts) actionRow(label, null) { openLicence(path) }
    }

    /** One capability: its name and what decides it, with the status in words so colour is never the only sign. */
    private fun capabilityRow(c: Capability) {
        target.addView(settingsRow().apply {
            minimumHeight = theme.touchTarget
            setPadding(theme.dpi(8f), theme.dpi(6f), theme.dpi(8f), theme.dpi(6f))
            isFocusable = true
            val status = ctx.getString(c.support.label)
            addView(labelBlock(ctx.getString(c.label), c.detail), Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.text(ctx, theme, status, theme.textLabel,
                if (c.support == Support.SUPPORTED) theme.accent else theme.textMuted, bold = c.support == Support.SUPPORTED).apply {
                setPadding(theme.dpi(8f), 0, 0, 0)
            })
            contentDescription = "${ctx.getString(c.label)}, $status. ${c.detail}"
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun openLicence(path: String?) {
        licence = path
        build()
        scroll.scrollTo(0, 0)
    }

    private fun licenceText(path: String) {
        target.addView(Ui.button(ctx, theme, ctx.getString(R.string.licence_back)) { openLicence(null) },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val text = runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
        // The files are wrapped at about 70 columns; join lines inside a paragraph so they reflow on a phone.
        note(text?.replace(Regex("(?<!\n)\n(?!\n)"), " ")?.replace(Regex(" {2,}"), " ")?.trim()
            ?: ctx.getString(R.string.about_no_notice))
    }

    /** Implemented by the launcher activity; reset needs the screen orientation and default names. */
    interface Resettable {
        fun resetLayout(keepSettings: Boolean)
    }

    private companion object {
        /**
         * A theme, font or contrast change rebuilds every view, this window
         * included. The page and the scroll position live here so the person
         * stays where they were.
         */
        var remembered: String? = null
        var rememberedScroll = 0

        /** Sections built from LX's own settings and layout alone, with nothing read from Android. */
        val SETTINGS_ONLY_PAGES = setOf("theme", "fonts", "panels", "motion", "desktop", "workspaces", "widgets", "accessibility",
            "performance", "backup")

        /** From this window width the list of pages stays beside the open page. */
        const val WIDE_DP = 560f
        /** The live previews: tall enough to read, short enough to leave room for their rows on a phone. */
        const val PREVIEW_DP = 132f
        const val NAV_DP = 210f
    }
}
