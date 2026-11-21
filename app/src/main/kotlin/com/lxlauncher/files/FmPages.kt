// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxIcons
import com.lxlauncher.ui.theme.LxTheme
import java.util.concurrent.Executors

/** Shared look of the File Explorer's pages: a raised card one tone step above the window, and section titles. */
internal object FmLook {
    fun card(t: LxTheme): GradientDrawable = com.lxlauncher.ui.Cards.raised(t)

    fun heading(ctx: Context, t: LxTheme, label: CharSequence): TextView = com.lxlauncher.ui.Cards.heading(ctx, t, label)

    /** A row with an icon, a title, a detail line and a chevron: one place to go. */
    fun linkRow(ctx: Context, t: LxTheme, icon: Int, title: CharSequence, detail: CharSequence?, tinted: Boolean = true,
                onClick: () -> Unit): LinearLayout = Ui.row(ctx).apply {
        minimumHeight = t.touchTarget + t.dpi(4f)
        setPadding(t.dpi(10f), t.dpi(6f), t.dpi(6f), t.dpi(6f))
        background = Ui.stateBackground(t, t.dp(10f))
        isFocusable = true
        addView(ImageView(ctx).apply {
            setImageResource(icon)
            if (tinted) imageTintList = ColorStateList.valueOf(t.textMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, Ui.lp(t.dpi(22f), t.dpi(22f)).apply { marginEnd = t.dpi(14f) })
        addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(Ui.text(ctx, t, title, t.textBody, t.textPrimary, lines = 2))
            if (!detail.isNullOrEmpty()) addView(Ui.text(ctx, t, detail, t.textLabel, t.textMuted, lines = 2).apply {
                setPadding(0, t.dpi(2f), 0, 0)
            })
        }, Ui.lp(0, Ui.WRAP, 1f))
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(t.textMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, Ui.lp(t.dpi(18f), t.dpi(18f)))
        contentDescription = if (detail.isNullOrEmpty()) title else "$title, $detail"
        setOnClickListener { onClick() }
    }
}

/** A used-space bar at its true ratio: the track in the text colour at 12%, the used part in the accent. */
@SuppressLint("ViewConstructor")
internal class UsageBar(context: Context, private val t: LxTheme) : View(context) {
    var ratio = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = t.track
        canvas.drawRoundRect(r, radius, radius, paint)
        if (ratio <= 0f) return
        // A sliver still shows as a dot, so a nearly empty card does not read as unknown.
        r.right = maxOf(width * ratio, height.toFloat())
        paint.color = t.accent
        canvas.drawRoundRect(r, radius, radius, paint)
    }
}

/**
 * The File Explorer's start page. It answers "where is my space and where are my files": each volume with its used
 * space at its true ratio, the kinds of files Android's media index counts, the tools, and, while LX Bridge runs, the
 * system's own folders.
 */
@SuppressLint("ViewConstructor")
internal class FmHomePage(
    private val ctx: Context,
    private val theme: () -> LxTheme,
    private val storage: StorageCatalog,
    private val host: Host,
) : ScrollView(ctx) {
    interface Host {
        fun openVolume(v: Volume)
        fun openCategory(kind: FileCategory)
        fun openPlace(place: Place)
        fun openPath(path: String)
        fun askAllFiles()
    }

    private val column = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-fm-home").apply { isDaemon = true } }
    private var loadCancel: Cancel? = null
    private var released = false
    /** Category values from the last count, kept so a refresh does not flash back to "counting". */
    private var stats: Map<FileCategory, CategoryStat>? = null
    private val metaViews = HashMap<FileCategory, TextView>()

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        val p = theme().dpi(12f)
        addView(column.apply { setPadding(p, theme().dpi(2f), p, theme().dpi(20f)) })
    }

    /** Reads volumes, access and counts again, off the main thread. */
    fun refresh() {
        loadCancel?.cancel()
        val cancel = Cancel().also { loadCancel = it }
        io.execute {
            val volumes = runCatching { storage.volumes() }.getOrDefault(emptyList()).filter { it.mounted }
            val access = storage.allFilesAccess()
            val system = runCatching { storage.systemPlaces() }.getOrDefault(emptyList())
            main.post { if (!released && !cancel.cancelled) build(volumes, access, system) }
            if (!access) return@execute
            val started = SystemClock.uptimeMillis()
            val counted = runCatching { storage.categoryStats(cancel) }.getOrNull()
            android.util.Log.d("LxPerf", "file kinds counted in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                if (released || cancel.cancelled) return@post
                stats = counted ?: emptyMap()
                showStats(failed = counted == null)
            }
        }
    }

    fun release() {
        released = true
        loadCancel?.cancel()
        io.shutdownNow()
    }

    private fun build(volumes: List<Volume>, access: Boolean, system: List<Pair<String, String>>) {
        val t = theme()
        column.removeAllViews()
        metaViews.clear()
        column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_home_storage)))
        if (volumes.isEmpty()) column.addView(Ui.text(ctx, t, ctx.getString(R.string.fm_home_no_volumes), t.textBody, t.textMuted, lines = 3))
        for (v in volumes) column.addView(volumeCard(v, t), Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = t.dpi(8f) })

        column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_home_kinds)))
        if (!access) {
            column.addView(Ui.text(ctx, t, ctx.getString(R.string.fm_home_kinds_locked), t.textBody, t.textMuted, lines = 4).apply {
                setPadding(t.dpi(4f), 0, t.dpi(4f), t.dpi(8f))
            })
            column.addView(Ui.button(ctx, t, ctx.getString(R.string.fm_allow_all), primary = true) { host.askAllFiles() },
                Ui.lp(Ui.WRAP, Ui.WRAP))
        } else column.addView(kindGrid(t), Ui.lp(Ui.MATCH, Ui.WRAP))

        column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_home_tools)))
        val tools = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply { background = FmLook.card(t) }
        tools.addView(FmLook.linkRow(ctx, t, R.drawable.ic_cleaning, ctx.getString(R.string.fm_cleaner), ctx.getString(R.string.fm_cleaner_detail)) {
            host.openPlace(Place.Cleaner)
        })
        if (access) {
            tools.addView(FmLook.linkRow(ctx, t, R.drawable.ic_clock, ctx.getString(R.string.fm_recent), ctx.getString(R.string.fm_recent_detail)) {
                host.openPlace(Place.Recent)
            })
        }
        column.addView(tools, Ui.lp(Ui.MATCH, Ui.WRAP))

        if (system.isNotEmpty()) {
            column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_home_system)))
            val box = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply { background = FmLook.card(t) }
            for ((key, path) in system) {
                val (icon, title, detail) = systemRow(key, path)
                box.addView(FmLook.linkRow(ctx, t, icon, title, detail) { host.openPath(path) })
            }
            column.addView(box, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        if (access) showStats(failed = false)
    }

    private fun systemRow(key: String, path: String): Triple<Int, String, String> = when (key) {
        "root" -> Triple(R.drawable.ic_storage, ctx.getString(R.string.fm_place_root), ctx.getString(R.string.fm_place_root_detail))
        "data" -> Triple(R.drawable.ic_android, ctx.getString(R.string.fm_place_app_data), "Android/data")
        "obb" -> Triple(R.drawable.ic_games, ctx.getString(R.string.fm_place_obb), "Android/obb")
        else -> Triple(R.drawable.ic_folder, ctx.getString(R.string.fm_place_tmp), path)
    }

    private fun volumeCard(v: Volume, t: LxTheme): View = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
        background = FmLook.card(t)
        val p = t.dpi(14f)
        setPadding(p, t.dpi(12f), p, t.dpi(12f))
        isFocusable = true
        foreground = Ui.stateBackground(t, t.dp(12f))
        val label = if (v.kind == VolumeKind.INTERNAL) ctx.getString(R.string.fm_internal) else v.label
        val used = (v.total - v.free).coerceAtLeast(0)
        addView(Ui.row(ctx).apply {
            addView(ImageView(ctx).apply {
                setImageResource(when (v.kind) {
                    VolumeKind.SD_CARD -> R.drawable.ic_sd_card
                    VolumeKind.USB -> R.drawable.ic_usb
                    else -> R.drawable.ic_storage
                })
                imageTintList = ColorStateList.valueOf(t.textMuted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(t.dpi(20f), t.dpi(20f)).apply { marginEnd = t.dpi(10f) })
            addView(Ui.text(ctx, t, label, t.textBody, t.textPrimary, bold = true), Ui.lp(0, Ui.WRAP, 1f))
            if (v.total > 0) addView(Ui.text(ctx, t, ctx.getString(R.string.fm_home_free, FmText.size(ctx, v.free)), t.textLabel, t.textMuted))
        })
        if (v.total > 0) {
            addView(UsageBar(ctx, t).apply { ratio = used.toFloat() / v.total }, Ui.lp(Ui.MATCH, t.dpi(8f)).apply {
                topMargin = t.dpi(10f)
                bottomMargin = t.dpi(8f)
            })
            addView(Ui.text(ctx, t, ctx.getString(R.string.fm_home_used, FmText.size(ctx, used), FmText.size(ctx, v.total)),
                t.textLabel, t.textMuted))
        }
        contentDescription = if (v.total > 0) "$label, " + ctx.getString(R.string.fm_home_used, FmText.size(ctx, used), FmText.size(ctx, v.total)) +
            ", " + ctx.getString(R.string.fm_home_free, FmText.size(ctx, v.free)) else label
        setOnClickListener { host.openVolume(v) }
    }

    /** The kinds of files, two to a row in a phone-wide window and four in a wider one. */
    private fun kindGrid(t: LxTheme): View {
        val widthDp = (if (width > 0) width else rootView.width) / resources.displayMetrics.density
        val perRow = if (widthDp >= 520f) 4 else 2
        val grid = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
        // A short last row stretches its tiles across the width rather than leaving an empty cell.
        FileCategory.values().toList().chunked(perRow).forEach { chunk ->
            grid.addView(Ui.row(ctx, gravity = Gravity.NO_GRAVITY).apply {
                chunk.forEachIndexed { i, kind ->
                    addView(kindTile(kind, t), Ui.lp(0, Ui.WRAP, 1f).apply { if (i > 0) marginStart = t.dpi(6f) })
                }
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = t.dpi(6f) })
        }
        return grid
    }

    private fun kindTile(kind: FileCategory, t: LxTheme): View = Ui.row(ctx).apply {
        background = FmLook.card(t)
        foreground = Ui.stateBackground(t, t.dp(12f))
        minimumHeight = t.dpi(60f)
        setPadding(t.dpi(10f), t.dpi(8f), t.dpi(8f), t.dpi(8f))
        isFocusable = true
        val icon = ImageView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        LxIcons.apply(icon, LxIcons.file(sampleName(kind), sampleMime(kind), false, t.vividIcons), t.accent)
        addView(icon, Ui.lp(t.dpi(30f), t.dpi(30f)).apply { marginEnd = t.dpi(10f) })
        val meta = Ui.text(ctx, t, ctx.getString(R.string.fm_counting), t.textCaption, t.textMuted, lines = 2).apply { minLines = 2 }
        metaViews[kind] = meta
        addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(Ui.text(ctx, t, ctx.getString(kindLabel(kind)), t.textBody, t.textPrimary))
            addView(meta.apply { setPadding(0, t.dpi(2f), 0, 0) })
        }, Ui.lp(0, Ui.WRAP, 1f))
        contentDescription = ctx.getString(kindLabel(kind))
        setOnClickListener { host.openCategory(kind) }
    }

    private fun showStats(failed: Boolean) {
        val s = stats ?: return
        for ((kind, view) in metaViews) {
            val stat = s[kind]
            view.text = when {
                // The count and the size on lines of their own, so a narrow tile never breaks "282 MB" in two.
                stat != null -> ctx.resources.getQuantityString(R.plurals.fm_files, stat.count, stat.count) + "\n" +
                    FmText.size(ctx, stat.bytes).replace(' ', '\u00A0')
                failed -> ctx.getString(R.string.fm_home_count_failed)
                else -> ctx.getString(R.string.fm_counting)
            }
            (view.parent?.parent as? View)?.contentDescription = ctx.getString(kindLabel(kind)) + ", " + view.text.toString().replace('\n', ' ')
        }
    }

    companion object {
        fun kindLabel(kind: FileCategory) = when (kind) {
            FileCategory.IMAGES -> R.string.fm_kind_images
            FileCategory.VIDEOS -> R.string.fm_kind_videos
            FileCategory.AUDIO -> R.string.fm_kind_audio
            FileCategory.DOCUMENTS -> R.string.fm_kind_documents
            FileCategory.ARCHIVES -> R.string.fm_kind_archives
            FileCategory.APKS -> R.string.fm_kind_apks
            FileCategory.DOWNLOADS -> R.string.fm_kind_downloads
        }

        /** A name and type that give each kind its file icon, so the tiles use the icons the lists use. */
        private fun sampleName(kind: FileCategory) = when (kind) {
            FileCategory.IMAGES -> "a.jpg"
            FileCategory.VIDEOS -> "a.mp4"
            FileCategory.AUDIO -> "a.mp3"
            FileCategory.DOCUMENTS -> "a.pdf"
            FileCategory.ARCHIVES -> "a.zip"
            FileCategory.APKS -> "a.apk"
            FileCategory.DOWNLOADS -> "a"
        }

        private fun sampleMime(kind: FileCategory) = when (kind) {
            FileCategory.IMAGES -> "image/jpeg"
            FileCategory.VIDEOS -> "video/mp4"
            FileCategory.AUDIO -> "audio/mpeg"
            FileCategory.DOCUMENTS -> "application/pdf"
            FileCategory.ARCHIVES -> "application/zip"
            FileCategory.APKS -> "application/vnd.android.package-archive"
            FileCategory.DOWNLOADS -> "application/octet-stream"
        }
    }
}

/**
 * The cleaner. One scan fills a row per kind of junk as each finishes; the rows say what each kind is, how much it
 * holds and what is picked. The kinds that need a look before they go (installers, large files, copies) open to
 * their files. One button at the foot removes what is picked, after one confirmation, and says what it freed.
 * Two quick tools follow the rows.
 */
@SuppressLint("ViewConstructor")
internal class FmCleanerPage(
    private val ctx: Context,
    private val theme: () -> LxTheme,
    private val sheets: FmSheets,
    private val notify: (CharSequence) -> Unit,
    private val onCleaned: () -> Unit,
) : LinearLayout(ctx) {
    private val cleaner = Cleaner(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-fm-clean").apply { isDaemon = true } }
    private var scanCancel: Cancel? = null
    private var scannedAt = 0L
    private var scanning = false
    private var cleaning = false
    private var released = false
    /** Set while [update] puts a check box in step with the picks, so its listener does not take it as a tap. */
    private var syncing = false

    private val groups = LinkedHashMap<JunkKind, JunkGroup>()
    /** Picked items per kind, by path. */
    private val picked = HashMap<JunkKind, MutableSet<String>>()
    private val expanded = HashSet<JunkKind>()
    private val rows = HashMap<JunkKind, Row>()

    private val column = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
    private val scroll = ScrollView(ctx)
    private val summary: TextView
    private val cleanButton: TextView
    private val rescan: View

    private inner class Row(val kind: JunkKind) {
        val box = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
        val check = CheckBox(ctx)
        val title = Ui.text(ctx, theme(), "", theme().textBody, theme().textPrimary, lines = 2)
        val detail = Ui.text(ctx, theme(), "", theme().textLabel, theme().textMuted, lines = 3)
        val size = Ui.text(ctx, theme(), "", theme().textLabel, theme().textPrimary, bold = true)
        val chevron = ImageView(ctx)
        val items = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY)
    }

    init {
        orientation = VERTICAL
        val t = theme()
        val p = t.dpi(12f)
        scroll.addView(column.apply { setPadding(p, t.dpi(2f), p, t.dpi(16f)) })
        scroll.isFillViewport = true
        addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        summary = Ui.text(ctx, t, "", t.textLabel, t.textMuted, lines = 2)
        cleanButton = FmSheets.compact(Ui.button(ctx, t, ctx.getString(R.string.fm_clean_none), primary = true) { confirmClean() }, t)
        rescan = Ui.iconButton(ctx, t, R.drawable.ic_refresh, ctx.getString(R.string.fm_clean_rescan), 40f, 20f) { scan(force = true) }
        addView(Ui.row(ctx).apply {
            background = FmLook.card(t)
            setPadding(t.dpi(14f), t.dpi(6f), t.dpi(8f), t.dpi(6f))
            addView(summary, Ui.lp(0, Ui.WRAP, 1f))
            addView(rescan)
            addView(cleanButton, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = t.dpi(4f) })
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply {
            setMargins(t.dpi(8f), 0, t.dpi(8f), t.dpi(6f))
        })
        buildRows()
    }

    /** Scans unless a scan finished in the last two minutes. */
    fun start() = scan(force = false)

    fun release() {
        released = true
        scanCancel?.cancel()
        io.shutdownNow()
    }

    private fun buildRows() {
        val t = theme()
        column.removeAllViews()
        rows.clear()
        column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_clean_found)))
        val card = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            background = FmLook.card(t)
            setPadding(0, t.dpi(4f), 0, t.dpi(4f))
        }
        for (kind in JunkKind.values()) {
            val row = Row(kind)
            rows[kind] = row
            card.addView(rowView(row, t), Ui.lp(Ui.MATCH, Ui.WRAP))
            update(kind)
        }
        column.addView(card, Ui.lp(Ui.MATCH, Ui.WRAP))
        column.addView(FmLook.heading(ctx, t, ctx.getString(R.string.fm_clean_tools)))
        val tools = Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply { background = FmLook.card(t) }
        tools.addView(toolRow(t, R.drawable.ic_memory, R.string.fm_tool_background, R.string.fm_tool_background_detail) { done ->
            val gained = cleaner.closeBackgroundApps()
            when {
                gained < 0 -> ctx.getString(R.string.fm_tool_needs_bridge)
                gained == 0L -> ctx.getString(R.string.fm_tool_background_none)
                else -> ctx.getString(R.string.fm_tool_background_done, FmText.size(ctx, gained))
            }.let(done)
        })
        tools.addView(toolRow(t, R.drawable.ic_storage, R.string.fm_tool_trim, R.string.fm_tool_trim_detail) { done ->
            done(when {
                !cleaner.bridgeReady() -> ctx.getString(R.string.fm_tool_needs_bridge)
                cleaner.trimStorage() -> ctx.getString(R.string.fm_tool_trim_done)
                else -> ctx.getString(R.string.fm_tool_trim_refused)
            })
        })
        column.addView(tools, Ui.lp(Ui.MATCH, Ui.WRAP))
        updateFooter()
    }

    private fun rowView(row: Row, t: LxTheme): View {
        val reviewable = row.kind in REVIEW
        row.check.apply {
            buttonTintList = ColorStateList.valueOf(t.accent)
            setOnCheckedChangeListener { _, on ->
                if (syncing || cleaning) return@setOnCheckedChangeListener
                val g = groups[row.kind] ?: return@setOnCheckedChangeListener
                val set = picked.getOrPut(row.kind) { HashSet() }
                when {
                    !on -> set.clear()
                    // Large files are picked one by one: the switch opens their list instead of picking them all.
                    row.kind == JunkKind.LARGE && set.isEmpty() -> expanded += row.kind
                    set.isEmpty() -> g.items.mapTo(set) { it.path }
                }
                update(row.kind)
                updateFooter()
            }
        }
        row.chevron.apply {
            setImageResource(R.drawable.ic_chevron)
            imageTintList = ColorStateList.valueOf(t.textMuted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = if (reviewable || row.kind == JunkKind.APP_CACHE || row.kind == JunkKind.EXTERNAL_CACHE) View.VISIBLE else View.INVISIBLE
        }
        val head = Ui.row(ctx).apply {
            minimumHeight = t.touchTarget + t.dpi(8f)
            setPadding(t.dpi(4f), t.dpi(6f), t.dpi(8f), t.dpi(6f))
            background = Ui.stateBackground(t, t.dp(10f))
            isFocusable = true
            addView(row.check, Ui.lp(t.touchTarget, t.touchTarget))
            addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                addView(row.title)
                addView(row.detail.apply { setPadding(0, t.dpi(2f), 0, 0) })
            }, Ui.lp(0, Ui.WRAP, 1f))
            addView(row.size.apply { setPadding(t.dpi(8f), 0, t.dpi(4f), 0) })
            addView(row.chevron, Ui.lp(t.dpi(18f), t.dpi(18f)))
            setOnClickListener {
                if (row.chevron.visibility != View.VISIBLE) row.check.toggle()
                else {
                    if (!expanded.remove(row.kind)) expanded += row.kind
                    update(row.kind)
                }
            }
        }
        row.box.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP))
        row.box.addView(row.items, Ui.lp(Ui.MATCH, Ui.WRAP).apply { marginStart = t.dpi(12f) })
        return row.box
    }

    private fun toolRow(t: LxTheme, icon: Int, title: Int, detail: Int, work: ((String) -> Unit) -> Unit): View {
        val result = Ui.text(ctx, t, ctx.getString(detail), t.textLabel, t.textMuted, lines = 3)
        val button = FmSheets.compact(Ui.button(ctx, t, ctx.getString(R.string.fm_tool_run)) {}, t)
        button.setOnClickListener {
            button.isEnabled = false
            button.alpha = 0.5f
            result.text = ctx.getString(R.string.fm_tool_running)
            io.execute {
                work { message ->
                    main.post {
                        if (released) return@post
                        button.isEnabled = true
                        button.alpha = 1f
                        result.text = message
                    }
                }
            }
        }
        return Ui.row(ctx).apply {
            minimumHeight = t.touchTarget + t.dpi(8f)
            setPadding(t.dpi(10f), t.dpi(6f), t.dpi(6f), t.dpi(6f))
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(t.textMuted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, Ui.lp(t.dpi(22f), t.dpi(22f)).apply { marginEnd = t.dpi(14f) })
            addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                addView(Ui.text(ctx, t, ctx.getString(title), t.textBody, t.textPrimary, lines = 2))
                addView(result.apply { setPadding(0, t.dpi(2f), 0, 0) })
            }, Ui.lp(0, Ui.WRAP, 1f))
            addView(button, Ui.lp(Ui.WRAP, Ui.WRAP).apply { marginStart = t.dpi(6f) })
        }
    }

    private fun scan(force: Boolean) {
        if (cleaning || released) return
        if (scanning && !force) return
        if (!force && scannedAt > 0 && SystemClock.uptimeMillis() - scannedAt < RESCAN_AFTER_MS) return
        scanCancel?.cancel()
        val cancel = Cancel().also { scanCancel = it }
        scanning = true
        groups.clear()
        picked.clear()
        for (k in JunkKind.values()) update(k)
        updateFooter()
        val started = SystemClock.uptimeMillis()
        io.execute {
            val result = runCatching {
                cleaner.scan(cancel) { g -> main.post { if (!released && !cancel.cancelled) arrived(g) } }
            }
            main.post {
                if (released || cancel.cancelled) return@post
                scanning = false
                scannedAt = SystemClock.uptimeMillis()
                result.exceptionOrNull()?.let { e ->
                    android.util.Log.w("LxCleaner", "scan failed", e)
                    summary.text = ctx.getString(R.string.fm_clean_scan_failed)
                }
                android.util.Log.d("LxPerf", "cleaner scan in ${SystemClock.uptimeMillis() - started} ms")
                for (k in JunkKind.values()) if (k !in groups) update(k)
                updateFooter()
            }
        }
    }

    private fun arrived(g: JunkGroup) {
        groups[g.kind] = g
        picked[g.kind] = g.items.filter { it.picked && (g.kind != JunkKind.LARGE) }.mapTo(HashSet()) { it.path }
        update(g.kind)
        updateFooter()
    }

    private fun update(kind: JunkKind) {
        val row = rows[kind] ?: return
        val t = theme()
        val g = groups[kind]
        val set = picked[kind].orEmpty()
        row.title.text = ctx.getString(titleOf(kind))
        row.detail.text = when {
            g == null && scanning -> ctx.getString(R.string.fm_clean_scanning)
            g == null -> ctx.getString(detailOf(kind))
            g.needsBridge -> ctx.getString(R.string.fm_tool_needs_bridge)
            g.items.isEmpty() -> ctx.getString(R.string.fm_clean_nothing)
            kind == JunkKind.APP_CACHE -> ctx.getString(R.string.fm_clean_app_cache_note)
            kind == JunkKind.APKS -> {
                val installed = g.items.count { it.detail == Cleaner.DETAIL_INSTALLED }
                ctx.resources.getQuantityString(R.plurals.fm_clean_apks, g.items.size, g.items.size, installed)
            }
            kind == JunkKind.EMPTY_FOLDERS || kind == JunkKind.THUMBNAILS -> ctx.resources.getQuantityString(R.plurals.fm_folders, g.items.size, g.items.size)
            kind == JunkKind.EXTERNAL_CACHE -> ctx.resources.getQuantityString(R.plurals.fm_clean_apps, g.items.size, g.items.size)
            else -> ctx.resources.getQuantityString(R.plurals.fm_files, g.items.size, g.items.size)
        }
        val pickedBytes = g?.items?.filter { it.path in set }?.sumOf { it.bytes } ?: 0L
        row.size.text = when {
            g == null || g.items.isEmpty() -> ""
            kind == JunkKind.EMPTY_FOLDERS -> ""
            set.isEmpty() -> FmText.size(ctx, g.bytes)
            pickedBytes == g.bytes -> FmText.size(ctx, g.bytes)
            else -> ctx.getString(R.string.fm_clean_picked_of, FmText.size(ctx, pickedBytes), FmText.size(ctx, g.bytes))
        }
        val usable = g != null && g.items.isNotEmpty() && !g.needsBridge && !cleaning
        row.check.isEnabled = usable
        row.check.alpha = if (usable) 1f else 0.4f
        if (row.check.isChecked != set.isNotEmpty()) {
            syncing = true
            row.check.isChecked = set.isNotEmpty()
            syncing = false
        }
        row.check.contentDescription = ctx.getString(titleOf(kind))
        row.chevron.rotation = if (kind in expanded) 90f else 0f
        // Only a group with something in it opens.
        row.chevron.visibility = if (g != null && g.items.isNotEmpty() && (kind in REVIEW || kind == JunkKind.APP_CACHE ||
                kind == JunkKind.EXTERNAL_CACHE)) View.VISIBLE else View.INVISIBLE
        row.items.removeAllViews()
        if (kind in expanded && g != null && g.items.isNotEmpty()) {
            val shown = g.items.take(MAX_SHOWN)
            for (item in shown) row.items.addView(itemRow(kind, item, item.path in set, t))
            if (g.items.size > shown.size) row.items.addView(Ui.text(ctx, t,
                ctx.resources.getQuantityString(R.plurals.fm_clean_more, g.items.size - shown.size, g.items.size - shown.size),
                t.textLabel, t.textMuted).apply {
                setPadding(t.dpi(12f), t.dpi(6f), t.dpi(8f), t.dpi(8f))
            })
        }
    }

    private fun itemRow(kind: JunkKind, item: JunkItem, on: Boolean, t: LxTheme): View = Ui.row(ctx).apply {
        minimumHeight = t.touchTarget
        setPadding(0, t.dpi(2f), t.dpi(10f), t.dpi(2f))
        val selectable = kind != JunkKind.APP_CACHE && kind != JunkKind.EXTERNAL_CACHE
        val box = CheckBox(ctx).apply {
            isChecked = on
            buttonTintList = ColorStateList.valueOf(t.accent)
            isEnabled = selectable && !cleaning
            visibility = if (selectable) View.VISIBLE else View.INVISIBLE
            contentDescription = item.name
            setOnCheckedChangeListener { _, checked ->
                val set = picked.getOrPut(kind) { HashSet() }
                if (checked) set += item.path else set -= item.path
                post { update(kind); updateFooter() }
            }
        }
        addView(box, Ui.lp(t.touchTarget, t.touchTarget))
        val detail = when {
            item.detail == Cleaner.DETAIL_INSTALLED -> ctx.getString(R.string.fm_clean_apk_installed)
            kind == JunkKind.DUPLICATES -> ctx.getString(R.string.fm_clean_copy_of, item.detail.substringAfterLast('/'))
            kind == JunkKind.APP_CACHE || kind == JunkKind.EXTERNAL_CACHE -> item.detail
            else -> item.detail.ifEmpty { item.path.substringBeforeLast('/') }
        }
        addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
            addView(Ui.text(ctx, t, item.name, t.textLabel, t.textPrimary, lines = 2))
            if (detail.isNotEmpty()) addView(Ui.text(ctx, t, detail, t.textCaption, t.textMuted, lines = 2).apply {
                setPadding(0, t.dpi(1f), 0, 0)
            })
        }, Ui.lp(0, Ui.WRAP, 1f))
        if (kind != JunkKind.EMPTY_FOLDERS) addView(Ui.text(ctx, t, FmText.size(ctx, item.bytes), t.textCaption, t.textMuted).apply {
            setPadding(t.dpi(8f), 0, 0, 0)
        })
        if (selectable) {
            background = Ui.stateBackground(t, t.dp(8f))
            isFocusable = true
            setOnClickListener { box.toggle() }
        }
    }

    private fun pickedItems(): Map<JunkKind, List<JunkItem>> = groups.mapNotNull { (kind, g) ->
        val set = picked[kind].orEmpty()
        val list = g.items.filter { it.path in set }
        if (list.isEmpty()) null else kind to list
    }.toMap()

    private fun updateFooter() {
        val chosen = pickedItems()
        val bytes = chosen.values.sumOf { list -> list.sumOf { it.bytes } }
        val any = chosen.isNotEmpty()
        cleanButton.isEnabled = any && !scanning && !cleaning
        cleanButton.alpha = if (cleanButton.isEnabled) 1f else 0.5f
        cleanButton.text = if (any) ctx.getString(R.string.fm_clean_button, FmText.size(ctx, bytes)) else ctx.getString(R.string.fm_clean_none)
        rescan.isEnabled = !cleaning
        rescan.alpha = if (cleaning) 0.4f else 1f
        if (cleaning) return
        summary.text = when {
            scanning -> ctx.getString(R.string.fm_clean_scanning)
            groups.isEmpty() -> ""
            else -> ctx.getString(R.string.fm_clean_summary, FmText.size(ctx, groups.values.filter { !it.needsBridge }.sumOf { it.bytes }))
        }
    }

    private fun confirmClean() {
        val chosen = pickedItems()
        if (chosen.isEmpty() || cleaning) return
        val lines = chosen.entries.joinToString("\n") { (kind, list) ->
            "· " + ctx.getString(titleOf(kind)) + if (kind == JunkKind.EMPTY_FOLDERS) "" else " (" + FmText.size(ctx, list.sumOf { it.bytes }) + ")"
        }
        sheets.confirm(ctx.getString(R.string.fm_clean_confirm_title), ctx.getString(R.string.fm_clean_confirm_body, lines),
            ctx.getString(R.string.fm_clean_confirm), danger = true) { clean(chosen) }
    }

    private fun clean(chosen: Map<JunkKind, List<JunkItem>>) {
        cleaning = true
        val cancel = Cancel()
        for (k in JunkKind.values()) update(k)
        updateFooter()
        summary.text = ctx.getString(R.string.fm_clean_cleaning)
        io.execute {
            val report = runCatching {
                cleaner.clean(chosen, cancel) { done, total ->
                    main.post { if (!released) summary.text = ctx.getString(R.string.fm_clean_progress, done, total) }
                }
            }
            main.post {
                if (released) return@post
                cleaning = false
                val r = report.getOrNull()
                if (r == null) {
                    notify(ctx.getString(R.string.fm_clean_failed))
                } else {
                    val freed = ctx.getString(R.string.fm_clean_done, FmText.size(ctx, r.freedBytes))
                    val kept = if (r.failed > 0) " " + ctx.resources.getQuantityString(R.plurals.fm_clean_kept, r.failed, r.failed) else ""
                    notify(freed + kept)
                }
                onCleaned()
                scan(force = true)
            }
        }
    }

    companion object {
        private const val RESCAN_AFTER_MS = 120_000L
        private const val MAX_SHOWN = 60
        private val REVIEW = setOf(JunkKind.APKS, JunkKind.DUPLICATES, JunkKind.LARGE, JunkKind.TEMP_LOGS, JunkKind.EMPTY_FOLDERS, JunkKind.THUMBNAILS)

        fun titleOf(kind: JunkKind) = when (kind) {
            JunkKind.APP_CACHE -> R.string.fm_junk_app_cache
            JunkKind.EXTERNAL_CACHE -> R.string.fm_junk_external_cache
            JunkKind.THUMBNAILS -> R.string.fm_junk_thumbnails
            JunkKind.TEMP_LOGS -> R.string.fm_junk_temp
            JunkKind.EMPTY_FOLDERS -> R.string.fm_junk_empty
            JunkKind.APKS -> R.string.fm_junk_apks
            JunkKind.DUPLICATES -> R.string.fm_junk_duplicates
            JunkKind.LARGE -> R.string.fm_junk_large
        }

        fun detailOf(kind: JunkKind) = when (kind) {
            JunkKind.APP_CACHE -> R.string.fm_junk_app_cache_detail
            JunkKind.EXTERNAL_CACHE -> R.string.fm_junk_external_cache_detail
            JunkKind.THUMBNAILS -> R.string.fm_junk_thumbnails_detail
            JunkKind.TEMP_LOGS -> R.string.fm_junk_temp_detail
            JunkKind.EMPTY_FOLDERS -> R.string.fm_junk_empty_detail
            JunkKind.APKS -> R.string.fm_junk_apks_detail
            JunkKind.DUPLICATES -> R.string.fm_junk_duplicates_detail
            JunkKind.LARGE -> R.string.fm_junk_large_detail
        }
    }
}
