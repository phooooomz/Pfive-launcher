// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.overlays

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.lxlauncher.R
import com.lxlauncher.apps.AppEntry
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.core.NativeCore
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.desktop.DragPayload
import com.lxlauncher.ui.desktop.startLxDrag
import com.lxlauncher.ui.panels.Identity
import com.lxlauncher.ui.theme.LxIcons
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.PanelDrawable
import com.lxlauncher.ui.theme.Surface
import java.text.Collator
import kotlin.math.abs

/**
 * The Applications menu, opened from the button at the left end of the bar: a desktop menu
 * with cascading submenus. Categories fill one column; the apps of a category open as a
 * submenu beside it, level with the category. On a phone held upright both columns narrow
 * and the submenu may overlap the end of the first column, so the menu never fills the screen.
 * Where that overlap would cut the category names (a large interface size on a narrow phone),
 * the first column shrinks to a rail of category icons while a submenu is open.
 *
 * Tap or Enter opens an app. A long press drags it out to the desktop, dock or taskbar,
 * or opens its menu when the finger stays put. Right enters a category, Left leaves it,
 * Back closes the open category before the menu. Typing anywhere in the menu searches.
 *
 * The look follows the reference sheet in the LX theme's own roles: a pill-shaped search well,
 * pill rows whose open category is filled with the accent, apps as rows of icon and name, and
 * a well of LX tools at the foot with a round accent button for LX Settings.
 */
@SuppressLint("ViewConstructor")
class StartMenu(
    private val shell: Shell,
    private val host: OverlayHost,
    /** Where the menu grows from: its left edge and the y it sits on, in the overlay layer. */
    private val anchorLeft: Int,
    private val anchorBottom: Int,
    initialGroup: String?,
    /** When set, the menu picks an app for the caller instead of opening it. */
    private val onPick: ((AppEntry) -> Unit)? = null,
) : FrameLayout(shell.activity), AppRepository.Listener, OverlayHost.BackHandler {
    private val theme: LxTheme get() = shell.ui

    private class Group(val key: String, val label: String, val icon: Int, val apps: List<AppEntry>, val empty: Int)

    // Before the first layout the menu has the overlay layer's size, which it fills.
    private val areaWidth get() = if (width > 0) width else host.width
    private val areaHeight get() = if (height > 0) height else host.height
    /** The width between the menu's outer gaps. */
    private val room get() = (areaWidth - 2 * gap).coerceAtLeast(0)
    /**
     * The first column: 288 dp where both columns fit beside each other, otherwise just wide enough for the footer's
     * tools; LX Settings then sits at the end of the search row (see [settingsAtTop]). A wider column costs a frame:
     * its glass is drawn again each time a category opens.
     */
    private val columnWidth get() =
        (if (compact) maxOf(theme.dpi(200f), FOOTER_TOOLS.size * theme.touchTarget + theme.dpi(16f)) else theme.dpi(288f))
            .coerceAtMost(room)
    /** The first column as a rail: one category icon per row, in a row as wide as a touch target. */
    private val railWidth get() = theme.touchTarget + theme.dpi(16f)
    private val shownColumnWidth get() = if (rail) railWidth else columnWidth
    /** The submenu: whatever is left beside the first column, but never narrower than a readable app name. */
    private val subWidth get() = subWidthBeside(shownColumnWidth)
    private fun subWidthBeside(column: Int) =
        if (!compact) theme.dpi(288f).coerceAtMost(room)
        else (room - column - theme.dpi(4f)).coerceIn(minOf(theme.dpi(180f), room), theme.dpi(288f).coerceAtMost(room))
    private fun columnLeft(width: Int) = anchorLeft.coerceIn(gap, (areaWidth - width - gap).coerceAtLeast(gap))
    private fun subLeft(column: Int, sub: Int) =
        (columnLeft(column) + column + theme.dpi(4f)).coerceAtMost(areaWidth - gap - sub).coerceAtLeast(gap)
    private val rowHeight get() = theme.touchTarget
    private val gap get() = theme.dpi(8f)

    private val search = EditText(context)
    private val searchWell = Ui.row(context)
    /**
     * On a phone held upright the footer has room for the tools only, so the round LX Settings button sits at the end
     * of the search row. Squeezed into the footer beside four tools, it was a few pixels wide.
     */
    private var settingsAtTop: View? = null
    private val clearSearch: ImageView
    private val footer: View
    private val groupList = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val groupScroll = ScrollView(context).apply { addView(groupList) }
    private val root = Column()
    private var sub: Column? = null
    /** A submenu folding back into its category row after the category closed; removed when the fold ends. */
    private var leaving: Column? = null

    /**
     * How the apps of a category arrive. The submenu enters in the style chosen in LX Settings ([MenuMotion]); the
     * default unfolds it out of the category row that was tapped, so the apps read as that category's. Switching to
     * another category glides the open submenu to the new row instead. Search results and lists rebuilt for new apps
     * do neither.
     */
    private enum class Entrance { NONE, UNFOLD, GLIDE }
    /** Set by [openGroup] for the next [placeSub]; a menu opened on a category unfolds it. */
    private var entrance = if (initialGroup != null) Entrance.UNFOLD else Entrance.NONE
    /** Counts placements of the submenu, so a placement finished after a newer one began leaves it alone. */
    private var placements = 0
    /** The categories and rail the category rows were built for, and the category they show as open. */
    private var builtLook: List<Any>? = null
    private var builtOpen: String? = null
    /** App rows of the list last replaced, kept to show other apps: making a row's views is most of a row's cost. */
    private val spareRows = ArrayList<AppRow>()

    private var groups: List<Group> = emptyList()
    /** What [groups] was built from; see [render]. */
    private var groupsFrom: List<Any?>? = null
    private val renderSoon = Runnable { render() }
    private var open: String? = initialGroup
    private val query get() = search.text.toString().trim()
    /**
     * True where two full columns do not fit side by side, as on a phone held upright. The columns
     * narrow and the submenu may overlap the end of the first column, as a desktop submenu does at
     * the edge of the screen; the menu never takes the whole screen.
     */
    private var compact = false
    /**
     * True while a category is open and its submenu would cover part of the category names: the first
     * column then shows only the icons, the submenu takes the rest, and the names return on closing.
     */
    private var rail = false
    private val searchIcon = ImageView(context)
    private var pickTitle: View? = null

    /** How a category's apps arrive, from LX Settings; read when the menu opens, as the rest of its look is. */
    private val menuMotion = MenuMotion.of(shell.model.snapshot.setting(MenuMotion.KEY))

    private var dragMoved = 0f
    private var dragStart = 0f to 0f
    private var pendingMenu: (() -> Unit)? = null
    private var hoverTarget: String? = null
    private val hoverOpen = Runnable { hoverTarget?.let { if (it != open) openGroup(it, focusFirst = false) } }

    /** A card that never grows past [maxHeight]; its scrolling list gives up the difference. */
    private inner class Column : LinearLayout(context) {
        var maxHeight = Int.MAX_VALUE
        /**
         * Plays the chosen [MenuMotion] on this card. While the card unfolds or rolls down, its outline is the part
         * that shows and it clips the card, so the shadow follows the band.
         */
        val motion = CardMotion(this, { theme }, { rowHeight }, { theme.radiusL })

        init {
            orientation = VERTICAL
            background = PanelDrawable(this, shell.glass, Surface.GLASS).apply {
                minOpacity = LxTheme.MENU_OPACITY
                radiusOverride = theme.radiusL
            }
            val p = theme.dpi(4f)
            setPadding(p, p, p, p)
            // The panel drawable reports no outline of its own, so the card gives one for its shadow.
            outlineProvider = motion.outline
            elevation = theme.dp(12f)
            isClickable = true
            isFocusable = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = minOf(maxHeight, MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
        }

        /** Ends an entrance or an exit at once, with all of the card showing. */
        fun settle() = motion.settle()
    }

    init {
        // A tap outside both columns closes the menu; the columns themselves swallow taps.
        setOnClickListener { host.remove(this) }
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        root.contentDescription = context.getString(if (onPick != null) R.string.menu_pick_title else R.string.menu_open)
        if (onPick != null) root.addView(Ui.text(context, theme, context.getString(R.string.menu_pick_title), theme.textTitle,
            theme.textPrimary, bold = true, lines = 2).apply {
            setPadding(theme.dpi(8f), theme.dpi(6f), theme.dpi(8f), theme.dpi(6f))
            pickTitle = this
        })
        search.apply {
            typeface = theme.font
            hint = context.getString(R.string.menu_search_hint)
            setTextColor(theme.textPrimary)
            setHintTextColor(theme.textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.textBody)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            background = null
            setPadding(0, 0, 0, 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Typing renders once the keys pause for a moment instead of on every letter; clearing the
                    // field shows the categories at once.
                    removeCallbacks(renderSoon)
                    if (s.isNullOrBlank()) render() else postDelayed(renderSoon, TYPING_PAUSE_MS)
                }
            })
            // The well's edge thickens while the field has focus: shape, not colour alone.
            setOnFocusChangeListener { _, has -> searchWell.background = wellBackground(theme.pill(theme.dp(44f)), focused = has) }
            setOnEditorActionListener { v, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    results().firstOrNull()?.let { launch(it, v) }
                    true
                } else false
            }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    firstFocusable(if (query.isNotEmpty()) sub else root, skipSearch = true)?.requestFocus()
                    true
                } else false
            }
        }
        clearSearch = Ui.iconButton(context, theme, R.drawable.ic_close, context.getString(R.string.menu_search_clear), 40f, 18f,
            tint = theme.textMuted) {
            search.setText("")
            search.requestFocus()
        }.apply { visibility = View.GONE }
        compact = areaWidth < anchorLeft + 2 * theme.dpi(288f) + 3 * gap
        searchWell.apply {
            background = wellBackground(theme.pill(theme.dp(44f)), focused = false)
            minimumHeight = theme.dpi(44f)
            setPadding(theme.dpi(14f), 0, theme.dpi(2f), 0)
            addView(searchIcon.apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(theme.textMuted)
            }, Ui.lp(theme.dpi(20f), theme.dpi(20f)).apply { marginEnd = theme.dpi(10f) })
            addView(search, Ui.lp(0, Ui.WRAP, 1f))
            addView(clearSearch, Ui.lp(Ui.WRAP, Ui.WRAP))
            // On the rail the well is only its icon; a tap brings the names and the field back first.
            setOnClickListener {
                if (rail) closeGroup()
                search.requestFocus()
            }
        }
        if (compact && onPick == null) {
            val button = settingsButton()
            settingsAtTop = button
            root.addView(Ui.row(context).apply {
                addView(searchWell, Ui.lp(0, Ui.WRAP, 1f))
                addView(button, Ui.lp(theme.touchTarget, theme.touchTarget).apply { marginStart = theme.dpi(2f) })
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(theme.dpi(2f), theme.dpi(2f), theme.dpi(2f), theme.dpi(4f)) })
        } else root.addView(searchWell, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            setMargins(theme.dpi(2f), theme.dpi(2f), theme.dpi(2f), theme.dpi(4f))
        })
        groupScroll.isVerticalScrollBarEnabled = true
        root.addView(groupScroll, Ui.lp(Ui.MATCH, Ui.WRAP, 1f))
        footer = buildFooter()
        root.addView(footer, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = theme.dpi(4f) })
        // Both columns are placed by their left edge on the screen, the menu button's side in either direction:
        // START put them by their right margin, which stays 0, in a right-to-left language.
        @SuppressLint("RtlHardcoded")
        val rootParams = LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.LEFT)
        addView(root, rootParams)
        setOnDragListener { _, e -> onDrag(e) }
        render()
        shell.apps.addListener(this)
    }

    /** Typing while a row has focus goes to the search, as in a desktop menu. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !search.hasFocus() && event.isPrintingKey &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            // A dead key goes to the search as it is, so the field joins its accent to the next letter.
            if (event.unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) {
                search.requestFocus()
                return search.dispatchKeyEvent(event)
            }
            val text = typedText(event.unicodeChar)
            if (text != null) {
                search.requestFocus()
                search.append(text)
                search.setSelection(search.text.length)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- shapes ----

    /** The search well and the footer well: the surface colour, a hairline that thickens with focus. */
    private fun wellBackground(radius: Float, focused: Boolean): Drawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(theme.surface)
        if (focused) setStroke(theme.dpi(2f), theme.accent) else setStroke(theme.dpi(1f), theme.stroke)
    }

    /** A pill inset in its row; filled with the accent when [filled] (the open category, the back row). */
    private fun pill(height: Float, filled: Boolean, inset: Int = theme.dpi(4f)): Drawable {
        val r = theme.pill(height)
        if (!filled) return InsetDrawable(Ui.stateBackground(theme, radius = r), inset, inset, inset, inset)
        fun shape(stroke: Int) = GradientDrawable().apply {
            cornerRadius = r
            setColor(theme.accent)
            if (stroke != 0) setStroke(theme.dpi(2f), stroke)
        }
        val states = StateListDrawable().apply {
            // Focus on a filled pill: a ring in the colour of its text, which reads on the accent.
            addState(intArrayOf(android.R.attr.state_focused), shape(theme.onAccent))
            addState(intArrayOf(), shape(0))
        }
        val ripple = RippleDrawable(ColorStateList.valueOf(LxTheme.withAlpha(theme.onAccent, 0.25f)), states, shape(0))
        return InsetDrawable(ripple, inset, inset, inset, inset)
    }

    private fun categoryIcon(key: String): Int = when (key) {
        "favorites" -> R.drawable.ic_star
        "recent" -> R.drawable.ic_clock
        "all" -> R.drawable.ic_apps_rounded
        "games" -> R.drawable.ic_games
        "social" -> R.drawable.ic_people
        "media" -> R.drawable.ic_media
        "productivity" -> R.drawable.ic_task
        "news" -> R.drawable.ic_news
        "maps" -> R.drawable.ic_map
        "work" -> R.drawable.ic_work
        "hidden" -> R.drawable.ic_visibility_off
        else -> R.drawable.ic_category
    }

    /** LX's own tools at the foot of the menu, with LX Settings as the one round accent button. */
    private fun buildFooter(): View = Ui.row(context).apply {
        background = wellBackground((theme.radiusL - theme.dp(4f)).coerceAtLeast(0f), focused = false)
        setPadding(theme.dpi(4f), theme.dpi(4f), theme.dpi(4f), theme.dpi(4f))
        minimumHeight = theme.dpi(56f)
        clipToPadding = false
        for ((kind, label) in FOOTER_TOOLS) {
            addView(ImageView(context).apply {
                LxIcons.apply(this, LxIcons.tool(kind, theme.vividIcons), theme.textPrimary)
                val pad = (theme.touchTarget - theme.dpi(24f)) / 2
                setPadding(pad, pad, pad, pad)
                background = Ui.stateBackground(theme, radius = theme.pill(theme.dp(40f)))
                contentDescription = context.getString(label)
                tooltipText = contentDescription
                isFocusable = true
                setOnClickListener { shell.openTool(kind) }
            }, Ui.lp(theme.touchTarget, theme.touchTarget))
        }
        if (settingsAtTop != null) return@apply
        addView(View(context), Ui.lp(0, 1, 1f))
        addView(settingsButton(), Ui.lp(theme.touchTarget, theme.touchTarget))
    }

    /** The round accent button that opens LX Settings. */
    private fun settingsButton(): View = ImageView(context).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(theme.onAccent)
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accent)
            }
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.accent)
                setStroke(theme.dpi(2f), theme.onAccent)
            }
            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ring)
                addState(intArrayOf(), circle)
            }
            val inset = theme.dpi(4f)
            background = InsetDrawable(RippleDrawable(ColorStateList.valueOf(LxTheme.withAlpha(theme.onAccent, 0.25f)), states, circle),
                inset, inset, inset, inset)
            // After the background, whose inset would otherwise become the padding: a 22 dp glyph.
            val pad = (theme.touchTarget - theme.dpi(22f)) / 2
            setPadding(pad, pad, pad, pad)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setOval(inset, inset, view.width - inset, view.height - inset)
            }
            elevation = theme.dp(3f)
            contentDescription = context.getString(R.string.tool_settings)
            tooltipText = contentDescription
            isFocusable = true
            setOnClickListener { shell.openTool("settings") }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hoverOpen)
        shell.apps.removeListener(this)
        sub?.settle()
        leaving?.settle()
    }

    override fun onAppsChanged(apps: List<AppEntry>) {
        // Registering for changes answers at once with the list the menu was just built from; rendering it again
        // built every row of the menu twice each time it opened.
        if (apps === renderedApps) return
        render()
    }

    /** The app list the rows were last built from. */
    private var renderedApps: List<AppEntry>? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A new width (rotation, a resized window) moves the button the menu grew from.
        // A new height is the keyboard: the menu shrinks to stay above it.
        if (oldw > 0 && w != oldw) {
            post { host.remove(this) }
            return
        }
        // Rows stay as they are, so a row pressed for its app menu keeps anchoring it.
        if (h != oldh) post { relayout() }
    }

    override fun onBack(): Boolean = when {
        query.isNotEmpty() -> {
            search.setText("")
            true
        }
        open != null -> {
            val was = open
            closeGroup()
            groupRow(was)?.requestFocus()
            true
        }
        else -> false
    }

    // ---- content ----

    private fun buildGroups(): List<Group> {
        val s = shell.model.snapshot
        val hidden = s.hidden.toSet()
        val shown = shell.apps.apps.filter { it.key !in hidden }
        val collator = Collator.getInstance()
        fun g(key: String, label: Int, icon: Int, apps: List<AppEntry>, empty: Int = R.string.menu_no_apps) =
            Group(key, context.getString(label), icon, apps, empty)
        val out = ArrayList<Group>()
        out += g("favorites", R.string.menu_favorites, categoryIcon("favorites"), s.favorites.mapNotNull { shell.apps.find(it) },
            R.string.menu_no_favorites)
        out += g("recent", R.string.menu_recent, categoryIcon("recent"), s.recent.mapNotNull { shell.apps.find(it) }, R.string.menu_no_recent)
        out += g("all", R.string.menu_all, categoryIcon("all"), shown)
        // Android's own app categories, each listed once it has an app, by name; Other last.
        val categories = listOf(
            "games" to R.string.menu_games, "social" to R.string.menu_social, "media" to R.string.menu_media,
            "productivity" to R.string.menu_productivity, "news" to R.string.menu_news, "maps" to R.string.menu_maps,
        ).map { (key, label) -> g(key, label, categoryIcon(key), shown.filter { categoryOf(it) == key }) }
            .filter { it.apps.isNotEmpty() }
            .sortedWith(compareBy(collator) { it.label })
        out += categories
        shown.filter { categoryOf(it) == "other" }.takeIf { it.isNotEmpty() }?.let { out += g("other", R.string.menu_other, categoryIcon("other"), it) }
        shown.filter { it.isWorkProfile }.takeIf { it.isNotEmpty() }?.let { out += g("work", R.string.menu_work, categoryIcon("work"), it) }
        shell.apps.apps.filter { it.key in hidden }.takeIf { it.isNotEmpty() }?.let {
            out += g("hidden", R.string.menu_hidden, categoryIcon("hidden"), it)
        }
        return out
    }

    private fun categoryOf(a: AppEntry): String = when (a.category) {
        ApplicationInfo.CATEGORY_GAME -> "games"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "media"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        else -> "other"
    }

    private fun results(): List<AppEntry> {
        val q = query
        if (q.isEmpty()) return emptyList()
        val hidden = shell.model.snapshot.hidden.toSet()
        val list = shell.apps.apps.filter { it.key !in hidden }
        return NativeCore.rank(q, list.map { it.label }.toTypedArray(), 200).map { list[it] }
    }

    /** Rebuilds both columns from the current apps, query and open category. */
    fun render() {
        removeCallbacks(renderSoon)
        if (areaWidth == 0) return
        renderedApps = shell.apps.apps
        // The categories change only with the apps or the lists that sort them, not with each letter typed.
        val s = shell.model.snapshot
        val from = listOf(Identity(shell.apps.apps), s.hidden, s.favorites, s.recent)
        if (from != groupsFrom) {
            groupsFrom = from
            groups = buildGroups()
        }
        if (open != null && groups.none { it.key == open }) open = null
        val q = query
        rail = compact && open != null && q.isEmpty() && !namesFitBesideSub()
        if (rail && search.hasFocus()) {
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        }
        search.visibility = if (rail) View.GONE else View.VISIBLE
        (searchIcon.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginEnd = if (rail) 0 else theme.dpi(10f)
            searchIcon.layoutParams = it
        }
        searchWell.gravity = if (rail) Gravity.CENTER else Gravity.CENTER_VERTICAL
        searchWell.setPadding(if (rail) 0 else theme.dpi(14f), 0, if (rail) 0 else theme.dpi(2f), 0)
        searchWell.contentDescription = if (rail) context.getString(R.string.menu_search_hint) else null
        pickTitle?.visibility = if (rail) View.GONE else View.VISIBLE
        clearSearch.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        // The tools make room for results and the keyboard, and have no place while picking an app or on the rail.
        footer.visibility = if (q.isEmpty() && onPick == null && !rail) View.VISIBLE else View.GONE
        // The rail is as wide as one icon: LX Settings comes back with the names.
        settingsAtTop?.visibility = if (rail) View.GONE else View.VISIBLE
        // A category row looks the way the categories, the rail and the open category make it. When only the open
        // category changed, the two rows that changed are built again; building all of them on each tap took about a
        // tenth of the main thread's time while categories were switched on the Infinix test phone.
        val openShown = if (q.isEmpty()) open else null
        val look = listOf(groups, rail)
        if (look == builtLook) {
            for (key in setOf(builtOpen, openShown)) {
                val g = groups.firstOrNull { it.key == key } ?: continue
                val old = groupRow(key) ?: continue
                val i = groupList.indexOfChild(old)
                // A row opened by resting the mouse on it keeps the keyboard focus it had.
                val focused = old.hasFocus()
                groupList.removeViewAt(i)
                val row = groupRowView(g)
                groupList.addView(row, i)
                if (focused) row.requestFocus()
            }
        } else {
            groupList.removeAllViews()
            for (g in groups) {
                if (g.key == "all" || (g.key == "hidden" && groupList.childCount > 0)) groupList.addView(divider())
                groupList.addView(groupRowView(g))
            }
            builtLook = look
        }
        builtOpen = openShown
        // A submenu that closes folds where it is: it leaves before relayout() would move it to the top of the menu.
        if (q.isEmpty() && open == null) removeSub()
        relayout()
        if (q.isNotEmpty() || open != null) placeSub()
    }

    /**
     * Whether every category name stays whole beside an open submenu with the first column at full width:
     * the strip the submenu leaves uncovered holds the column's padding, the pill's inset, the row's
     * padding and icon, and the widest name in the weight of an open category.
     */
    private fun namesFitBesideSub(): Boolean {
        val column = columnWidth
        val uncovered = subLeft(column, subWidthBeside(column)) - columnLeft(column)
        val paint = android.graphics.Paint().apply {
            typeface = theme.fontMedium
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, theme.textBody, resources.displayMetrics)
        }
        val widest = groups.maxOfOrNull { paint.measureText(it.label) } ?: 0f
        return uncovered >= theme.dpi(4f + 4f + 12f + 20f + 10f) + widest
    }

    private val bottomEdge get() = minOf(anchorBottom, areaHeight - gap)
    private val safeTop get() = host.menuSafeTop.coerceAtLeast(gap)

    /** Puts the columns in place for the current size without rebuilding their rows. */
    private fun relayout() {
        val p = root.layoutParams as LayoutParams
        val w = shownColumnWidth
        p.width = w
        p.leftMargin = columnLeft(w)
        root.maxHeight = (bottomEdge - safeTop).coerceAtLeast(rowHeight * 3)
        // Sits on the bar: its bottom edge stays at the bar whatever its height.
        root.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(root.maxHeight, MeasureSpec.AT_MOST))
        p.topMargin = (bottomEdge - root.measuredHeight).coerceAtLeast(safeTop)
        root.layoutParams = p
        sub?.let { it.layoutParams = subParams(it) }
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(theme.stroke)
        layoutParams = Ui.lp(Ui.MATCH, theme.dpi(1f)).apply {
            setMargins(theme.dpi(16f), theme.dpi(4f), theme.dpi(16f), theme.dpi(4f))
        }
    }

    private fun groupRow(key: String?): View? = (0 until groupList.childCount).map { groupList.getChildAt(it) }.firstOrNull { it.tag == key }

    private fun groupRowView(g: Group): View = Ui.row(context).apply {
        // While a search runs, the results replace the category, so no category reads as open.
        val isOpen = g.key == open && query.isEmpty()
        tag = g.key
        minimumHeight = rowHeight
        // The pill's inset would replace the padding; the background goes first.
        background = pill(theme.dp(40f), filled = isOpen)
        isFocusable = true
        isSelected = isOpen
        contentDescription = resources.getQuantityString(R.plurals.menu_category, g.apps.size, g.label, g.apps.size)
        val ink = if (isOpen) theme.onAccent else theme.textPrimary
        val icon = ImageView(context).apply {
            setImageResource(g.icon)
            imageTintList = ColorStateList.valueOf(ink)
        }
        if (rail) {
            // The name is spoken and shown as a tooltip; the open category keeps its accent fill.
            gravity = Gravity.CENTER
            tooltipText = g.label
            addView(icon, Ui.lp(theme.dpi(20f), theme.dpi(20f)))
        } else {
            setPadding(theme.dpi(if (compact) 12f else 16f), 0, theme.dpi(if (compact) 10f else 14f), 0)
            addView(icon, Ui.lp(theme.dpi(20f), theme.dpi(20f)).apply { marginEnd = theme.dpi(if (compact) 10f else 12f) })
            addView(Ui.text(context, theme, g.label, theme.textBody, ink, bold = isOpen), Ui.lp(0, Ui.WRAP, 1f))
            // A narrow column keeps the label whole and leaves the count to the spoken description.
            if (!compact) addView(Ui.text(context, theme, g.apps.size.toString(), theme.textLabel, if (isOpen) theme.onAccent else theme.textMuted).apply {
                fontFeatureSettings = "tnum"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = theme.dpi(8f) })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = ColorStateList.valueOf(if (isOpen) theme.onAccent else theme.textMuted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(theme.dpi(16f), theme.dpi(16f)).apply { marginStart = theme.dpi(4f) })
        }
        // Enter from a keyboard steps into the category, as Right does. A tap opens it, and a tap on the open
        // category closes its submenu again, which on a phone uncovers what the submenu overlapped.
        setOnClickListener {
            if (it.isInTouchMode && isOpen) closeGroup() else openGroup(g.key, focusFirst = !it.isInTouchMode)
        }
        setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                openGroup(g.key, focusFirst = true)
                true
            } else false
        }
        // With a mouse, resting on a category opens it, as in any desktop menu.
        setOnHoverListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    hoverTarget = g.key
                    removeCallbacks(hoverOpen)
                    postDelayed(hoverOpen, 180)
                }
                MotionEvent.ACTION_HOVER_EXIT -> if (hoverTarget == g.key) removeCallbacks(hoverOpen)
            }
            false
        }
    }

    private fun emptyNote(list: LinearLayout, text: String) {
        val shown = if (shell.apps.loaded) text else context.getString(R.string.state_loading_apps)
        list.addView(Ui.text(context, theme, shown, theme.textBody, theme.textMuted, lines = 4).apply {
            gravity = Gravity.CENTER
            setPadding(theme.dpi(16f), theme.dpi(24f), theme.dpi(16f), theme.dpi(24f))
        }, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    /** Search results as a ranked list; the first, which Enter opens, is marked. With no match, an offer to search everywhere. */
    private fun fillResults(list: LinearLayout, q: String) {
        val found = results()
        if (found.isEmpty()) {
            emptyNote(list, context.getString(R.string.menu_no_match, q))
            // While picking an app for a pin or a shortcut, leaving for a web search would drop the pick.
            if (onPick != null) return
            list.addView(Ui.row(context).apply {
                minimumHeight = rowHeight
                background = pill(theme.dp(40f), filled = false)
                setPadding(theme.dpi(16f), 0, theme.dpi(14f), 0)
                isFocusable = true
                val label = context.getString(R.string.menu_search_everywhere, q)
                contentDescription = label
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_search)
                    imageTintList = ColorStateList.valueOf(theme.accent)
                }, Ui.lp(theme.dpi(20f), theme.dpi(20f)).apply { marginEnd = theme.dpi(12f) })
                addView(Ui.text(context, theme, label, theme.textBody, theme.textPrimary, lines = 2), Ui.lp(0, Ui.WRAP, 1f))
                setOnClickListener { shell.openSearch(q) }
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
            return
        }
        addRows(list, found)
    }

    /**
     * Adds a row per app. The rows that fit in the menu come at once, in the frame that shows the list; the rest
     * follow [ROW_SLICE_MS] of work per frame, starting after [wait]. Building 40 rows in the frame of a tap held that
     * frame for 80 to 90 ms on the Infinix test phone, and later batches of 40 stalled the frames after it. A list
     * that left the screen stops being filled.
     */
    private fun addRows(list: LinearLayout, apps: List<AppEntry>, start: Int = 0, count: Int = visibleRows(), wait: Long = 0L) {
        val started = SystemClock.uptimeMillis()
        var i = start
        while (i < apps.size && (i - start < count || (count == 0 && SystemClock.uptimeMillis() - started < ROW_SLICE_MS))) {
            list.addView(appRow(apps[i], first = i == 0 && query.isNotEmpty()), Ui.lp(Ui.MATCH, Ui.WRAP))
            i++
        }
        if (i >= apps.size) return
        val next = i
        val more = Runnable { if (list.isAttachedToWindow) addRows(list, apps, next, count = 0) }
        if (wait > 0) list.postDelayed(more, wait) else list.post(more)
    }

    /** As many rows as the menu's full height can show, plus a spare for a row cut by the edge. */
    private fun visibleRows() = (bottomEdge - safeTop) / rowHeight + 2

    /** The apps of a category as a submenu: one row each, icon and name, as in a desktop menu. */
    private fun fillApps(list: LinearLayout, apps: List<AppEntry>, empty: String, wait: Long) {
        if (apps.isEmpty()) {
            emptyNote(list, empty)
            return
        }
        addRows(list, apps, wait = wait)
    }

    private fun labelOf(app: AppEntry) = if (app.isWorkProfile) context.getString(R.string.app_work_label, app.label) else app.label

    private fun appRow(app: AppEntry, first: Boolean): View = (spareRows.removeLastOrNull() ?: AppRow()).apply { bind(app, first) }

    /** A row of an app list, icon and name. [bind] fills it, and fills it again with another app when the list changes. */
    private inner class AppRow : LinearLayout(context) {
        private val size = theme.dpi(if (compact) 28f else 32f)
        private val icon = ImageView(context)
        // A narrow submenu gives the name a second line rather than cutting it short.
        private val name = Ui.text(context, theme, "", theme.textBody, theme.textPrimary, lines = if (compact) 2 else 1)
        private var marked: Boolean? = null

        init {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = rowHeight
            setPadding(theme.dpi(12f), 0, theme.dpi(14f), 0)
            isFocusable = true
            addView(icon, Ui.lp(size, size).apply { marginEnd = theme.dpi(if (compact) 10f else 12f) })
            addView(name, Ui.lp(0, Ui.WRAP, 1f))
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_LEFT) return@setOnKeyListener false
                onBack()
            }
        }

        /** Shows [app]. The [first] search result is what Enter opens; it is marked by its fill and its weight. */
        fun bind(app: AppEntry, first: Boolean) {
            // A row reused from the last list may still be arriving or pressed.
            animate().cancel()
            alpha = 1f
            translationX = 0f
            isPressed = false
            if (marked != first) {
                marked = first
                background = InsetDrawable(Ui.stateBackground(theme, radius = theme.pill(theme.dp(40f)), selected = first), theme.dpi(4f))
                name.setTextColor(if (first) theme.selectedText else theme.textPrimary)
                name.typeface = if (first) theme.fontMedium else theme.font
            }
            background?.jumpToCurrentState()
            val key = app.key
            tag = key
            val label = labelOf(app)
            contentDescription = label
            name.text = label
            icon.setImageDrawable(shell.apps.cachedIcon(key, size))
            // An icon that arrives after the row was filled with another app is dropped.
            if (icon.drawable == null) shell.apps.icon(key, size) { d -> if (tag == key) icon.setImageDrawable(d) }
            wireApp(this, app)
        }
    }

    /** Tap opens the app; a long press drags it out, or opens its menu when the finger stays put. */
    private fun wireApp(v: View, app: AppEntry) = v.apply {
        setOnClickListener { launch(app, it) }
        setOnLongClickListener {
            // The search is done once an app is pressed; the keyboard would cover half of its menu.
            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
            pendingMenu = { appMenu(it, app) }
            if (!it.startLxDrag(DragPayload.App(app.key), app.label)) {
                pendingMenu = null
                appMenu(it, app)
            }
            true
        }
    }

    // ---- the second column ----

    private fun openGroup(key: String, focusFirst: Boolean) {
        removeCallbacks(hoverOpen)
        if (query.isNotEmpty()) search.setText("")
        open = key
        entrance = if (sub == null) Entrance.UNFOLD else Entrance.GLIDE
        render()
        sub?.let { s -> if (focusFirst) s.post { firstFocusable(s)?.requestFocus() } }
    }

    private fun closeGroup() {
        open = null
        render()
    }

    /** Takes the submenu away: it folds back into its category row, or goes at once without animations. */
    private fun removeSub() {
        val s = sub ?: return
        sub = null
        if (!theme.animations || menuMotion == MenuMotion.OFF || !s.isLaidOut) {
            removeView(s)
            return
        }
        leaving?.let { dropLeaving(it) }
        leaving = s
        s.motion.leave(menuMotion) {
            if (leaving === s) leaving = null
            removeView(s)
        }
    }

    private fun dropLeaving(s: Column) {
        s.settle()
        removeView(s)
        if (leaving === s) leaving = null
    }

    /** Opens, fills and positions the second column beside the first, level with its category. */
    private fun placeSub() {
        val q = query
        val g = groups.firstOrNull { it.key == open }
        val shows = if (theme.animations && menuMotion != MenuMotion.OFF && q.isEmpty()) entrance else Entrance.NONE
        entrance = Entrance.NONE
        if (q.isEmpty() && g == null) {
            removeSub()
            return
        }
        val fresh = sub == null
        if (fresh) leaving?.let { dropLeaving(it) }
        val s = sub ?: Column().also { sub = it }
        // Where the card shows now, for the glide to the next category.
        val wasTop = if (!fresh && s.isLaidOut) s.top + s.translationY else null
        s.settle()
        // The rows of the list being replaced show the new apps instead of new rows being made.
        ((s.getChildAt(0) as? ScrollView)?.getChildAt(0) as? LinearLayout)?.let { old ->
            val keep = visibleRows()
            for (i in 0 until old.childCount) (old.getChildAt(i) as? AppRow)?.let { if (spareRows.size < keep) spareRows += it }
            old.removeAllViews()
        }
        s.removeAllViews()
        s.contentDescription = if (q.isNotEmpty()) context.getString(R.string.menu_search_hint) else g!!.label
        val list = Ui.row(context, vertical = true, gravity = Gravity.NO_GRAVITY)
        val started = SystemClock.uptimeMillis()
        if (q.isNotEmpty()) fillResults(list, q)
        // The rows below the edge wait until the arriving rows have settled.
        else fillApps(list, g!!.apps, context.getString(g.empty), wait = if (shows == Entrance.NONE) 0L else theme.durationMedium)
        s.addView(ScrollView(context).apply { addView(list) }, Ui.lp(Ui.MATCH, Ui.WRAP, 1f))
        if (g != null && q.isEmpty()) {
            Log.d(PERF_TAG, "apps menu: ${g.key} shows ${list.childCount} of ${g.apps.size} rows, built in ${SystemClock.uptimeMillis() - started} ms")
        }

        val params = subParams(s)
        if (fresh) {
            addView(s, params)
            // Search results fade in beside the column; with the menu's motion off, nothing moves.
            if (theme.animations && menuMotion != MenuMotion.OFF && shows == Entrance.NONE) {
                s.alpha = 0f
                s.translationX = -theme.dp(8f)
                s.animate().alpha(1f).translationX(0f).setDuration(theme.durationShort).setInterpolator(theme.easeOut).start()
            }
        } else s.layoutParams = params
        // The category rows were built again with the first column and have no place until the next layout. The card
        // is put level with its row after that layout and before the frame is drawn, so it never shows at the top of
        // the menu first; only then does it move.
        val placement = ++placements
        beforeNextDraw {
            if (sub !== s || placement != placements) return@beforeNextDraw
            val p = subParams(s)
            s.offsetLeftAndRight(p.leftMargin - s.left)
            s.offsetTopAndBottom(p.topMargin - s.top)
            s.layoutParams = p
            if (shows == Entrance.NONE) return@beforeNextDraw
            s.motion.anchorY = categoryRowY(s)
            val glide = shows == Entrance.GLIDE && wasTop != null
            if (glide) {
                s.animate().cancel()
                s.translationY = wasTop!! - s.top
                s.animate().translationY(0f).setDuration(theme.durationMedium).setInterpolator(theme.easeOut).start()
            } else s.motion.enter(menuMotion)
            s.motion.rows(rowsInView(s, list), menuMotion, glide)
        }
    }

    /** Runs [action] once, after the next layout and before that frame is drawn. */
    private fun beforeNextDraw(action: () -> Unit) {
        val observer = viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                (if (observer.isAlive) observer else viewTreeObserver).removeOnPreDrawListener(this)
                action()
                return true
            }
        })
    }

    /** The middle of the open category's row, in the card's own pixels; 0 without one. */
    private fun categoryRowY(s: Column): Int {
        val row = groupRow(open) ?: return 0
        val at = IntArray(2)
        row.getLocationInWindow(at)
        val rowMiddle = at[1] + row.height / 2
        s.getLocationInWindow(at)
        return rowMiddle - at[1]
    }

    /** The app rows that show inside the card, each with its middle in the card's own pixels. Rows below its edge stay as they are. */
    private fun rowsInView(s: Column, list: LinearLayout): List<Pair<View, Int>> {
        val at = IntArray(2)
        s.getLocationInWindow(at)
        val cardTop = at[1]
        return (0 until list.childCount).map { list.getChildAt(it) }.mapNotNull { v ->
            v.getLocationInWindow(at)
            if (at[1] - cardTop < s.height) v to (at[1] - cardTop + v.height / 2) else null
        }
    }

    /**
     * The submenu beside the first column, level with its category row (or the top of the menu for
     * search) and moved up as far as it must to fit. Where the screen ends first, it slides left over
     * the end of the first column, which keeps the category names in view.
     */
    @SuppressLint("RtlHardcoded")  // placed by its left margin, like the first column
    private fun subParams(s: Column): LayoutParams {
        val rp = root.layoutParams as LayoutParams
        val w = subWidth
        // A long list scrolls inside the menu's own height instead of rising above the first column,
        // unless that column is too short to give the list room.
        val ceiling = if (root.measuredHeight >= (bottomEdge - safeTop) / 2) rp.topMargin.coerceAtLeast(safeTop) else safeTop
        s.maxHeight = bottomEdge - ceiling
        s.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s.maxHeight, MeasureSpec.AT_MOST))
        val row = if (query.isEmpty()) groupRow(open) else null
        val rowTop = if (row != null && row.isLaidOut) {
            val a = IntArray(2)
            val me = IntArray(2)
            row.getLocationInWindow(a)
            getLocationInWindow(me)
            a[1] - me[1] - theme.dpi(6f)
        } else rp.topMargin
        return LayoutParams(w, Ui.WRAP, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = subLeft(shownColumnWidth, w)
            topMargin = rowTop.coerceAtMost(bottomEdge - s.measuredHeight).coerceAtLeast(ceiling)
        }
    }

    private fun firstFocusable(container: View?, skipSearch: Boolean = false): View? {
        if (container == null) return null
        val found = ArrayList<View>()
        container.addFocusables(found, View.FOCUS_DOWN)
        // The clear button belongs to the search well; it is never the first stop in a list.
        return found.firstOrNull { !skipSearch || (it !== search && it !== clearSearch) }
    }

    internal companion object {
        /** The LX tools in the footer, before the round LX Settings button. */
        val FOOTER_TOOLS = listOf("files" to R.string.tool_files, "monitor" to R.string.tool_monitor, "notes" to R.string.tool_notes,
            "cleanup" to R.string.tool_cleanup)
        const val TYPING_PAUSE_MS = 90L
        /** Work per frame for the app rows below the menu's edge. */
        const val ROW_SLICE_MS = 4L
        const val PERF_TAG = "LxPerf"

        /**
         * The text a key types, or null for no character, a control character, or a dead key:
         * an accent waiting for its letter carries a flag bit that is not part of any character.
         */
        internal fun typedText(unicodeChar: Int): String? {
            if (unicodeChar == 0 || unicodeChar and KeyCharacterMap.COMBINING_ACCENT != 0) return null
            if (!Character.isValidCodePoint(unicodeChar) || Character.isISOControl(unicodeChar)) return null
            return String(Character.toChars(unicodeChar))
        }
    }

    // ---- actions ----

    private fun launch(app: AppEntry, v: View) {
        val pick = onPick
        if (pick != null) pick(app) else shell.launchApp(app.key, v)
        host.remove(this)
    }

    private fun onDrag(e: DragEvent): Boolean {
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                dragMoved = 0f
                dragStart = e.x to e.y
                return e.localState is DragPayload.App
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragMoved = maxOf(dragMoved, abs(e.x - dragStart.first) + abs(e.y - dragStart.second))
                if (dragMoved > theme.dp(24f)) {
                    // Moving away: close the menu so the desktop underneath receives the drop.
                    pendingMenu = null
                    host.remove(this)
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val menu = pendingMenu
                pendingMenu = null
                if (menu != null && dragMoved < theme.dp(16f)) post { menu() }
            }
        }
        return true
    }

    private fun appMenu(anchor: View, app: AppEntry) {
        val s = shell.model.snapshot
        val m = shell.model
        val items = mutableListOf(
            MenuItem(context.getString(R.string.app_add_desktop), R.drawable.ic_home) {
                if (m.edit { NativeCore.addShortcut(it, s.active, app.key, -1, -1) } == 0) shell.notify(context.getString(R.string.desktop_full))
                else shell.notify(context.getString(R.string.app_added_desktop, app.label))
            },
            if ("app:${app.key}" in s.taskbar) MenuItem(context.getString(R.string.taskbar_unpin), R.drawable.ic_pin) {
                m.edit { NativeCore.unpin(it, NativeCore.LIST_TASKBAR, "app:${app.key}") }
            } else MenuItem(context.getString(R.string.taskbar_pin), R.drawable.ic_pin) {
                if (!m.edit { NativeCore.pin(it, NativeCore.LIST_TASKBAR, "app:${app.key}", -1) }) shell.notify(context.getString(R.string.taskbar_pin_failed))
            },
            if ("app:${app.key}" in s.dock) MenuItem(context.getString(R.string.dock_remove), R.drawable.ic_pin) {
                m.edit { NativeCore.unpin(it, NativeCore.LIST_DOCK, "app:${app.key}") }
            } else MenuItem(context.getString(R.string.dock_pin), R.drawable.ic_pin) {
                if (!m.edit { NativeCore.pin(it, NativeCore.LIST_DOCK, "app:${app.key}", -1) }) shell.notify(context.getString(R.string.dock_pin_failed))
            },
            if (app.key in s.favorites) MenuItem(context.getString(R.string.app_unfavorite), R.drawable.ic_check) {
                m.edit { NativeCore.unpin(it, NativeCore.LIST_FAVORITES, app.key) }
                render()
            } else MenuItem(context.getString(R.string.app_favorite), R.drawable.ic_check) {
                m.edit { NativeCore.pin(it, NativeCore.LIST_FAVORITES, app.key, -1) }
                render()
            },
            if (app.key in s.hidden) MenuItem(context.getString(R.string.app_unhide), R.drawable.ic_apps) {
                m.edit { NativeCore.unpin(it, NativeCore.LIST_HIDDEN, app.key) }
                render()
            } else MenuItem(context.getString(R.string.app_hide), R.drawable.ic_apps) {
                m.edit { NativeCore.pin(it, NativeCore.LIST_HIDDEN, app.key, -1) }
                render()
            },
        )
        for (sc in shell.apps.shortcuts(app.key)) {
            items += MenuItem(sc.shortLabel ?: sc.id, R.drawable.ic_next) {
                if (!shell.apps.startShortcut(sc, anchor)) shell.notify(context.getString(R.string.shortcut_failed))
            }
        }
        items += MenuItem(context.getString(R.string.app_info), R.drawable.ic_info) { shell.apps.openAppInfo(app.key, anchor) }
        if (!shell.apps.isSystemApp(app.key)) {
            items += MenuItem(context.getString(R.string.app_uninstall), R.drawable.ic_delete, danger = true) {
                shell.apps.requestUninstall(app.key)
            }
        }
        shell.showMenu(anchor, items)
    }
}
