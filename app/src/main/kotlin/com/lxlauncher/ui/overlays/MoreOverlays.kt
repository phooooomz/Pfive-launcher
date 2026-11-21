// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.overlays

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lxlauncher.R
import com.lxlauncher.core.FolderItem
import com.lxlauncher.core.NativeCore
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.desktop.IconView
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface
import com.lxlauncher.ui.theme.Wallpapers

/**
 * Launcher-wide search: apps (ranked by the native core), LX tools, settings
 * pages, workspaces, and a hand-off to file search in LX Files.
 */
@SuppressLint("ViewConstructor")
class SearchOverlay(private val shell: Shell, private val host: OverlayHost, initial: String) : LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    private val field = EditText(context)
    private val results = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
    private var firstAction: (() -> Unit)? = null

    private data class Result(val label: CharSequence, val detail: CharSequence?, val icon: Int, val appKey: String?, val action: () -> Unit)

    /** Tallest the panel gets; below that it follows its frame, which shrinks above the keyboard. */
    var maxHeight = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    init {
        orientation = VERTICAL
        background = PanelDrawable(this, shell.glass, Surface.GLASS).apply { minOpacity = LxTheme.MENU_OPACITY }
        val p = theme.dpi(12f)
        setPadding(p, p, p, p)
        isClickable = true
        field.apply {
            typeface = theme.font
            hint = context.getString(R.string.search_hint)
            setText(initial)
            setSelection(initial.length)
            setTextColor(theme.textPrimary)
            setHintTextColor(theme.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.textTitle)
            isSingleLine = true
            // In landscape the keyboard would otherwise cover LX with its own full-screen editor.
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            backgroundTintList = ColorStateList.valueOf(theme.accent)
            contentDescription = context.getString(R.string.search_open)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = update()
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    firstAction?.invoke()
                    true
                } else false
            }
        }
        addView(Ui.row(context).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(theme.accent)
            }, Ui.lp(theme.dpi(22f), theme.dpi(22f)).apply { marginEnd = theme.dpi(8f) })
            addView(field, Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.iconButton(context, theme, R.drawable.ic_close, context.getString(R.string.action_close)) { host.pop() })
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(ScrollView(context).apply { addView(results) }, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = theme.dpi(8f) })
        update()
        field.post {
            field.requestFocus()
            context.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun close(then: () -> Unit) {
        host.remove(this)
        then()
    }

    private fun update() {
        val q = field.text.toString().trim()
        val out = ArrayList<Result>()
        val ctx = context
        val apps = shell.apps.apps.filter { it.key !in shell.model.snapshot.hidden }
        if (q.isEmpty()) {
            shell.model.snapshot.recent.mapNotNull { shell.apps.find(it) }.take(6).forEach { a ->
                out += Result(a.label, ctx.getString(R.string.search_recent), 0, a.key) { close { shell.launchApp(a.key, null) } }
            }
        } else {
            NativeCore.rank(q, apps.map { it.label }.toTypedArray(), 8).forEach { i ->
                val a = apps[i]
                out += Result(a.label, ctx.getString(R.string.search_kind_app), 0, a.key) { close { shell.launchApp(a.key, null) } }
            }
            val tools = listOf(
                Triple("settings", R.string.tool_settings, R.drawable.ic_settings),
                Triple("files", R.string.tool_files, R.drawable.ic_folder),
                Triple("monitor", R.string.tool_monitor, R.drawable.ic_monitor),
                Triple("notes", R.string.tool_notes, R.drawable.ic_notes),
                Triple("tedit", R.string.tool_tedit, com.lxlauncher.ui.theme.LxIcons.TEDIT),
            )
            val toolLabels = tools.map { ctx.getString(it.second) }
            NativeCore.rank(q, toolLabels.toTypedArray(), 5).forEach { i ->
                val t = tools[i]
                out += Result(toolLabels[i], ctx.getString(R.string.search_kind_tool), t.third, null) { close { shell.openTool(t.first) } }
            }
            val pages = listOf(
                Triple("theme", R.string.settings_sec_theme, R.drawable.ic_image),
                Triple("fonts", R.string.settings_sec_fonts, R.drawable.ic_text_fields),
                Triple("panels", R.string.settings_sec_panels, R.drawable.ic_tile),
                Triple("motion", R.string.settings_sec_motion, R.drawable.ic_animation),
                Triple("desktop", R.string.settings_desktop, R.drawable.ic_home),
                Triple("workspaces", R.string.settings_workspaces, R.drawable.ic_workspaces),
                Triple("apps", R.string.settings_sec_apps, R.drawable.ic_android),
                Triple("widgets", R.string.settings_widgets, R.drawable.ic_widgets),
                Triple("dock", R.string.settings_sec_dock, R.drawable.ic_apps),
                Triple("accessibility", R.string.settings_accessibility, R.drawable.ic_accessibility),
                Triple("performance", R.string.settings_performance, R.drawable.ic_monitor),
                Triple("privacy", R.string.settings_privacy, R.drawable.ic_lock),
                Triple("backup", R.string.settings_backup, R.drawable.ic_file),
                Triple("about", R.string.settings_about, R.drawable.ic_info),
            )
            val extra = ctx.resources.getStringArray(R.array.search_settings_keywords)
            val pageLabels = pages.mapIndexed { i, p -> ctx.getString(p.second) + " " + extra.getOrElse(i) { "" } }
            NativeCore.rank(q, pageLabels.toTypedArray(), 3).forEach { i ->
                val page = pages[i]
                out += Result(ctx.getString(page.second), ctx.getString(R.string.search_kind_setting), page.third, null) {
                    close { shell.openTool("settings", page.first) }
                }
            }
            val ws = shell.model.snapshot.workspaces
            NativeCore.rank(q, ws.map { it.name }.toTypedArray(), 3).forEach { i ->
                val w = ws[i]
                out += Result(w.name, ctx.getString(R.string.search_kind_workspace), R.drawable.ic_widgets, null) {
                    close { shell.model.edit { NativeCore.setActiveWorkspace(it, w.id) } }
                }
            }
            out += Result(ctx.getString(R.string.search_files_for, q), ctx.getString(R.string.search_kind_files), R.drawable.ic_folder, null) {
                close { shell.openTool("files", q) }
            }
            out += Result(ctx.getString(R.string.search_android_settings), ctx.getString(R.string.search_kind_android), R.drawable.ic_android, null) {
                close { shell.startSafely(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        render(out, q)
    }

    private fun render(list: List<Result>, q: String) {
        results.removeAllViews()
        firstAction = list.firstOrNull()?.action
        if (list.isEmpty()) {
            results.addView(Ui.text(context, theme, context.getString(if (q.isEmpty()) R.string.search_empty_prompt else R.string.search_no_results),
                theme.textBody, theme.textMuted, lines = 3).apply { setPadding(theme.dpi(8f), theme.dpi(16f), theme.dpi(8f), 0) })
            return
        }
        for ((index, r) in list.withIndex()) {
            results.addView(Ui.row(context).apply {
                minimumHeight = theme.touchTarget + theme.dpi(4f)
                setPadding(theme.dpi(8f), 0, theme.dpi(8f), 0)
                // The first row is what Enter opens, so it carries the selected tone.
                background = Ui.stateBackground(theme, selected = index == 0)
                isFocusable = true
                contentDescription = listOfNotNull(r.label, r.detail).joinToString(", ")
                val icon = ImageView(context)
                if (r.appKey != null) {
                    icon.setImageDrawable(shell.apps.cachedIcon(r.appKey, theme.dpi(32f)))
                    shell.apps.icon(r.appKey, theme.dpi(32f)) { icon.setImageDrawable(it) }
                } else {
                    com.lxlauncher.ui.theme.LxIcons.applyRes(icon, r.icon, theme.accent)
                }
                addView(icon, Ui.lp(theme.dpi(if (r.appKey != null) 32f else 22f), theme.dpi(if (r.appKey != null) 32f else 22f)).apply {
                    marginEnd = theme.dpi(12f)
                })
                addView(Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                    addView(Ui.text(context, theme, r.label, theme.textBody, theme.textPrimary))
                    // Secondary text on the selected first row takes the text colour, which is checked on the selection fill.
                    if (r.detail != null) addView(Ui.text(context, theme, r.detail, theme.textCaption,
                        if (index == 0) theme.textPrimary else theme.textMuted))
                }, Ui.lp(0, Ui.WRAP, 1f))
                setOnClickListener { r.action() }
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
    }
}

/** All workspaces with a small preview; tap to switch, long press for options. */
@SuppressLint("ViewConstructor")
class WorkspaceOverview(private val shell: Shell, private val host: OverlayHost, private val onOptions: (View, Int) -> Unit) :
    LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui
    /** Every workspace shares one wallpaper: its preview is drawn once per overview, not once per card. */
    private val thumbnails = HashMap<String, Bitmap?>()

    init {
        orientation = VERTICAL
        background = PanelDrawable(this, shell.glass, Surface.GLASS).apply { minOpacity = LxTheme.MENU_OPACITY }
        val p = theme.dpi(16f)
        setPadding(p, p, p, p)
        isClickable = true
        contentDescription = context.getString(R.string.overview_title)
        addView(Ui.row(context).apply {
            addView(Ui.text(context, theme, context.getString(R.string.overview_title), theme.textTitle, theme.textPrimary, bold = true),
                Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.iconButton(context, theme, R.drawable.ic_close, context.getString(R.string.action_close)) { host.pop() })
        })
        val s = shell.model.snapshot
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val cols = if (landscape) 4 else 2
        val grid = GridLayout(context).apply { columnCount = cols }
        // The columns keep their count; cards narrow until they fit the screen. Two 150 dp cards needed 372 dp,
        // more than a 360 dp phone has, and more again at a 130% interface size.
        val cardChrome = theme.dpi(6f) * 2 + theme.dpi(4f) * 2
        val room = (shell.activity.resources.displayMetrics.widthPixels - theme.dpi(16f) * 2 - theme.dpi(OVERLAY_MARGIN_DP) * 2) / cols
        val cardW = minOf(theme.dpi(if (landscape) 180f else 150f), room - cardChrome).coerceAtLeast(theme.dpi(96f))
        val thumbH = (cardW * if (landscape) 0.56f else 1.2f).toInt()
        for ((i, ws) in s.workspaces.withIndex()) {
            val active = ws.id == s.active
            val card = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                background = Ui.stateBackground(theme, selected = active)
                isSelected = active
                isFocusable = true
                val pad = theme.dpi(6f)
                setPadding(pad, pad, pad, pad)
                val thumb = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = PanelDrawable(this, shell.glass, Surface.CHIP).apply { focused = active }
                    clipToOutline = true
                    thumbnail(ws.wallpaper, cardW, thumbH)?.let { setImageDrawable(BitmapDrawable(resources, it)) }
                }
                addView(thumb, Ui.lp(cardW, thumbH))
                val windows = s.windows.count { it.ws == ws.id }
                val items = s.shortcuts.count { it.ws == ws.id } + s.folders.count { it.ws == ws.id }
                val widgets = s.widgets.count { it.ws == ws.id }
                addView(Ui.text(context, theme, "${i + 1}  ${ws.name}", theme.textBody, if (active) theme.selectedText else theme.textPrimary, bold = active).apply {
                    setPadding(0, theme.dpi(6f), 0, 0)
                })
                val w = resources.getQuantityString(R.plurals.count_windows, windows, windows)
                val g = resources.getQuantityString(R.plurals.count_widgets, widgets, widgets)
                val c = resources.getQuantityString(R.plurals.count_shortcuts, items, items)
                // A narrow card gives the counts a second line rather than cutting them off.
                addView(Ui.text(context, theme, context.getString(R.string.overview_counts, w, g, c), theme.textCaption,
                    if (active) theme.textPrimary else theme.textMuted, lines = 2).apply { maxWidth = cardW })
                contentDescription = context.getString(R.string.overview_card, ws.name, w, g, c)
                setOnClickListener {
                    host.pop()
                    shell.model.edit { NativeCore.setActiveWorkspace(it, ws.id) }
                }
                setOnLongClickListener {
                    onOptions(it, ws.id)
                    true
                }
            }
            grid.addView(card, GridLayout.LayoutParams().apply { setMargins(theme.dpi(4f), theme.dpi(4f), theme.dpi(4f), theme.dpi(4f)) })
        }
        if (s.workspaces.size < 12) {
            grid.addView(Ui.button(context, theme, context.getString(R.string.workspace_add)) {
                val name = context.getString(R.string.workspace_default_name, s.workspaces.size + 1)
                host.pop()
                shell.model.edit { NativeCore.addWorkspace(it, name) }
            }, GridLayout.LayoutParams().apply { setMargins(theme.dpi(4f), theme.dpi(4f), theme.dpi(4f), theme.dpi(4f)) })
        }
        addView(ScrollView(context).apply { addView(grid) }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(8f) })
    }

    /** Built-in wallpapers render in a millisecond at thumbnail size; others show the panel tint. */
    private fun thumbnail(ref: String, w: Int, h: Int): Bitmap? = thumbnails.getOrPut("$ref@${w}x$h") { drawThumbnail(ref, w, h) }

    private fun drawThumbnail(ref: String, w: Int, h: Int): Bitmap? {
        if (!ref.startsWith("builtin:") && !ref.startsWith("color:")) return null
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ok = if (ref == Wallpapers.THEMED) {
            Wallpapers.drawThemed(bmp, theme.colors)
            true
        } else if (ref.startsWith("color:")) NativeCore.fillBitmap(bmp, android.graphics.Color.parseColor(ref.removePrefix("color:")) and 0xFFFFFF)
        else {
            val style = ref.removePrefix("builtin:")
            NativeCore.renderWallpaper(bmp, if (style in listOf("skyline", "rain", "deep")) style else "skyline", if (style == "rain") 21 else 7)
        }
        return if (ok) bmp else null
    }
}

/** An open folder: its apps, rename on the title, long press to take an app out. */
@SuppressLint("ViewConstructor")
class FolderPopup(private val shell: Shell, private val host: OverlayHost, private val folder: FolderItem) : LinearLayout(shell.activity) {
    private val theme: LxTheme get() = shell.ui

    init {
        orientation = VERTICAL
        background = PanelDrawable(this, shell.glass, Surface.GLASS).apply { minOpacity = LxTheme.MENU_OPACITY }
        val p = theme.dpi(14f)
        setPadding(p, p, p, p)
        isClickable = true
        contentDescription = resources.getQuantityString(R.plurals.folder_description, folder.apps.size, folder.name, folder.apps.size)
        addView(Ui.row(context).apply {
            addView(Ui.text(context, theme, folder.name, theme.textTitle, theme.textPrimary, bold = true).apply {
                background = Ui.stateBackground(theme)
                minHeight = theme.touchTarget
                gravity = Gravity.CENTER_VERTICAL
                setPadding(theme.dpi(6f), 0, theme.dpi(6f), 0)
                isFocusable = true
                contentDescription = context.getString(R.string.folder_rename_named, folder.name)
                setOnClickListener {
                    shell.prompt(context.getString(R.string.folder_rename), folder.name) { n ->
                        if (!shell.model.edit { NativeCore.renameFolder(it, folder.id, n) }) shell.notify(context.getString(R.string.name_rejected))
                        host.remove(this@FolderPopup)
                    }
                }
            }, Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.iconButton(context, theme, R.drawable.ic_close, context.getString(R.string.action_close)) { host.pop() })
        })
        val cols = when {
            folder.apps.size <= 4 -> 2
            folder.apps.size <= 9 -> 3
            else -> 4
        }
        val grid = GridLayout(context).apply { columnCount = cols }
        // Four 84 dp cells needed 364 dp; on a narrower screen the cells narrow instead of being cut off.
        val room = (shell.activity.resources.displayMetrics.widthPixels - theme.dpi(14f) * 2 - theme.dpi(OVERLAY_MARGIN_DP) * 2) / cols
        val cellW = minOf(theme.dpi(84f), room).coerceAtLeast(theme.touchTarget)
        for (key in folder.apps) {
            val app = shell.apps.find(key)
            val v = IconView(context, theme).apply {
                label = app?.label ?: context.getString(R.string.app_unavailable)
                iconSizePx = theme.dpi(48f)
                unavailable = app == null
                icon = shell.apps.cachedIcon(key, theme.dpi(48f))
                if (icon == null) shell.apps.icon(key, theme.dpi(48f)) { icon = it }
                setOnClickListener {
                    host.remove(this@FolderPopup)
                    shell.launchApp(key, it)
                }
                setOnLongClickListener {
                    shell.showMenu(it, listOf(
                        MenuItem(context.getString(R.string.folder_take_out), R.drawable.ic_up) {
                            host.remove(this@FolderPopup)
                            if (shell.model.edit { h -> NativeCore.removeFromFolder(h, folder.id, key) } == 0)
                                shell.notify(context.getString(R.string.desktop_full))
                        },
                        MenuItem(context.getString(R.string.app_info), R.drawable.ic_info) { shell.apps.openAppInfo(key, it) },
                    ))
                    true
                }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = cellW
                height = theme.dpi(92f)
            })
        }
        addView(ScrollView(context).apply { addView(grid) }, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            topMargin = theme.dpi(8f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    companion object {
        fun params(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER)
    }
}

/** The least gap kept between an overlay and the screen's sides. */
private const val OVERLAY_MARGIN_DP = 8f
