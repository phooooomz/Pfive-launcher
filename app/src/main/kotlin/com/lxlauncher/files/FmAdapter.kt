// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Layout
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.ui.BitmapSample
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxIcons
import com.lxlauncher.ui.theme.LxTheme
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Cells for the grid and rows for the list. Pictures get a small thumbnail, decoded off the main
 * thread at the size it is drawn and kept in a bounded cache.
 */
class FmAdapter(private val ctx: Context, private val theme: () -> LxTheme) : BaseAdapter() {
    var items: List<FsNode> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }
    var list = false
    /** Search and Recent show where each hit lives; keys are [FsNode.key], read off the main thread. */
    var locations: Map<String, String> = emptyMap()
    var selected: Set<String> = emptySet()
    var dimFiles = false

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 2
    override fun getItemViewType(position: Int) = if (list) 1 else 0

    private class Holder(val icon: ImageView, val name: TextView, val meta: TextView?, val check: ImageView)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val t = theme()
        val node = items[position]
        val view = convertView ?: if (list) listRow(t) else gridCell(t)
        val h = view.tag as Holder
        val isSelected = node.key in selected
        // A list binds its visible rows again for every width a resize goes through; text and pictures that did not
        // change are left alone, so the rows do not lay out and redraw for nothing.
        setIfChanged(h.name, fittedName(node.name, h.name, parent, t))
        h.check.visibility = if (isSelected) View.VISIBLE else View.GONE
        view.isActivated = isSelected
        view.alpha = if (dimFiles && !node.isDir) 0.45f else 1f
        h.meta?.let { setIfChanged(it, meta(node)) }
        if (bound.put(h.icon, node.key) != node.key) keysChanged()
        val thumbKey = if (!node.isDir && node.mime.startsWith("image/") && node.size in 1..MAX_THUMB_SOURCE)
            node.key + "@" + node.modified else null
        val cached = thumbKey?.let { thumbs.get(it) }
        if (cached != null) show(cached, h.icon, thumbKey) else showIcon(h.icon, LxIcons.file(node.name, node.mime, node.isDir, t.vividIcons), t.accent)
        if (thumbKey != null && cached == null) thumbnail(node, thumbKey, h.icon, t)
        view.contentDescription = if (isSelected) ctx.getString(R.string.fm_selected_item, node.name) else node.name
        return view
    }

    private fun setIfChanged(view: TextView, text: CharSequence) {
        if (!TextUtils.equals(view.text, text)) view.text = text
    }

    /** What an icon view shows: a drawn icon (its resource and tint) or a thumbnail (its cache key). */
    private class Shown(var res: Int = 0, var tinted: Boolean = false, var tint: Int = 0, var thumb: String? = null)

    private fun showIcon(into: ImageView, choice: LxIcons.Choice, tint: Int) {
        val shown = into.tag as Shown
        if (shown.thumb == null && shown.res == choice.res && shown.tinted == choice.tinted && shown.tint == tint) return
        LxIcons.apply(into, choice, tint)
        into.scaleType = ImageView.ScaleType.FIT_CENTER
        shown.res = choice.res
        shown.tinted = choice.tinted
        shown.tint = tint
        shown.thumb = null
    }

    /**
     * A grid name cut in its middle to fit its lines, so the end of the name stays: two long names that differ
     * only in their last part stay apart, and the extension shows.
     */
    private fun fittedName(name: String, view: TextView, parent: ViewGroup, t: LxTheme): CharSequence {
        val width = (parent as? GridView)?.columnWidth?.takeIf { it > 0 } ?: return name
        val room = (width - 2 * t.dpi(CELL_SIDE_DP)) * (GRID_NAME_LINES - WRAP_LOSS)
        return TextUtils.ellipsize(name, view.paint, room, TextUtils.TruncateAt.MIDDLE)
    }

    private fun meta(node: FsNode): String {
        val parts = ArrayList<String>(3)
        if (!node.isDir) FmText.size(ctx, node.size).takeIf { it.isNotEmpty() }?.let(parts::add)
        FmText.date(node.modified).takeIf { it.isNotEmpty() }?.let(parts::add)
        locations[node.key]?.let(parts::add)
        return parts.joinToString(" · ")
    }

    private fun checkBadge(t: LxTheme) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_check)
        imageTintList = android.content.res.ColorStateList.valueOf(t.onAccent)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(t.accent)
        }
        val p = t.dpi(2f)
        setPadding(p, p, p, p)
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // Layout's constant has the value of LineBreaker's, which lint asks for and Android 8 and 9 lack.
    @SuppressLint("WrongConstant")
    private fun gridCell(t: LxTheme): View {
        val icon = iconView(t, 7f)
        val name = Ui.text(ctx, t, "", t.textCaption, t.textPrimary, lines = GRID_NAME_LINES).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            // Evens the lines out, so a name does not leave one letter alone on the last line.
            breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        }
        val check = checkBadge(t)
        val cell = FrameLayout(ctx).apply {
            background = Ui.stateBackground(t, t.dp(10f))
            val p = t.dpi(CELL_SIDE_DP)
            setPadding(p, t.dpi(8f), p, t.dpi(6f))
            minimumHeight = t.dpi(GRID_ICON_DP + 44f)
            addView(Ui.row(ctx, vertical = true, gravity = Gravity.CENTER_HORIZONTAL).apply {
                addView(icon, Ui.lp(t.dpi(GRID_ICON_DP), t.dpi(GRID_ICON_DP)))
                addView(name, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(5f) })
            }, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            addView(check, FrameLayout.LayoutParams(t.dpi(16f), t.dpi(16f), Gravity.TOP or Gravity.START))
        }
        cell.tag = Holder(icon, name, null, check)
        return cell
    }

    /** A file's picture: rounded, so a thumbnail sits with the drawn icons. */
    private fun iconView(t: LxTheme, radiusDp: Float) = ImageView(ctx).apply {
        tag = Shown()
        val radius = t.dp(radiusDp)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun listRow(t: LxTheme): View {
        val icon = iconView(t, 6f)
        val name = Ui.text(ctx, t, "", t.textBody - 1f, t.textPrimary)
        val meta = Ui.text(ctx, t, "", t.textCaption, t.textMuted)
        val check = checkBadge(t)
        val row = Ui.row(ctx).apply {
            background = Ui.stateBackground(t, t.dp(10f))
            minimumHeight = t.touchTarget
            setPadding(t.dpi(8f), t.dpi(3f), t.dpi(8f), t.dpi(3f))
            addView(FrameLayout(ctx).apply {
                addView(icon, FrameLayout.LayoutParams(t.dpi(LIST_ICON_DP), t.dpi(LIST_ICON_DP), Gravity.CENTER))
                addView(check, FrameLayout.LayoutParams(t.dpi(14f), t.dpi(14f), Gravity.TOP or Gravity.START))
            }, Ui.lp(t.dpi(32f), t.dpi(32f)).apply { marginEnd = t.dpi(10f) })
            addView(Ui.row(ctx, vertical = true, gravity = Gravity.NO_GRAVITY).apply {
                addView(name, Ui.lp(Ui.MATCH, Ui.WRAP))
                addView(meta, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = t.dpi(2f) })
            }, Ui.lp(0, Ui.WRAP, 1f))
        }
        row.tag = Holder(icon, name, meta, check)
        return row
    }

    /**
     * Loads a picture's thumbnail on [loader]. A view scrolled past before its turn is skipped; a picture that will
     * not decode (SVG, or HEIC on Android 8) is remembered and not tried again; a result goes only to the view that
     * shows that file now, without redrawing the whole list.
     */
    private fun thumbnail(node: FsNode, key: String, into: ImageView, t: LxTheme) {
        // The loader skips files no view shows any more; it must see the view bound just now.
        if (keysStale) refreshKeys.run()
        if (key in failed || !pending.add(key)) return
        val target = t.dpi(if (list) LIST_ICON_DP else GRID_ICON_DP)
        val fileKey = node.key
        loader.execute {
            if (fileKey !in onScreen) {
                main.post { pending.remove(key) }
                return@execute
            }
            val bitmap = try {
                node.backend.openRead(node).use { pfd ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                    val sample = BitmapSample.sampleFor(bounds.outWidth, bounds.outHeight, target, target,
                        THUMB_MAX_PIXELS, THUMB_MAX_SIDE)
                    android.system.Os.lseek(pfd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                pending.remove(key)
                if (bitmap == null) {
                    failed += key
                    return@post
                }
                thumbs.put(key, bitmap)
                // Whichever view shows this file now gets it; the view it was asked for may hold another file.
                for ((view, shown) in bound) if (shown == fileKey) show(bitmap, view, key)
            }
        }
    }

    /** A new folder: thumbnails still waiting for the old one are dropped. */
    fun clearQueue() {
        loader.queue.clear()
        pending.clear()
    }

    private fun show(bitmap: Bitmap, into: ImageView, key: String) {
        val shown = into.tag as Shown
        if (shown.thumb == key) return
        shown.thumb = key
        into.imageTintList = null
        into.scaleType = ImageView.ScaleType.CENTER_CROP
        into.setImageBitmap(bitmap)
    }

    fun release() {
        loader.shutdownNow()
        thumbs.evictAll()
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    /** Main thread: pictures that would not decode. */
    private val failed = HashSet<String>()
    /** Main thread: the file each icon view shows now. Weak, so views the list let go of drop out. */
    private val bound = java.util.WeakHashMap<ImageView, String>()
    /** What [bound] holds, readable from [loader]. */
    private val onScreen: Set<String> get() = boundKeys
    @Volatile private var boundKeys: Set<String> = emptySet()
    /**
     * [boundKeys] is copied again once per frame at most, after a view took another file, and right before a thumbnail
     * is queued. Copying it on every bind cost a copy of the whole map per row on each frame of a resize.
     */
    private var keysStale = false
    private val refreshKeys = Runnable {
        keysStale = false
        boundKeys = HashSet(bound.values)
    }

    private fun keysChanged() {
        if (keysStale) return
        keysStale = true
        main.post(refreshKeys)
    }
    private val loader = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "lx-fm-thumbs").apply { isDaemon = true }
    }
    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private companion object {
        const val THUMB_CACHE_BYTES = 8 * 1024 * 1024
        /** A thumbnail is at most a few hundred pixels across; an odd-shaped image must not decode bigger. */
        const val THUMB_MAX_PIXELS = 1024L * 1024
        const val THUMB_MAX_SIDE = 2048
        /** Larger pictures are not decoded for a thumbnail; their icon stays. */
        const val MAX_THUMB_SOURCE = 48L * 1024 * 1024
        const val GRID_ICON_DP = 40f
        const val LIST_ICON_DP = 28f
        const val GRID_NAME_LINES = 2
        const val CELL_SIDE_DP = 2f
        /** The part of a line lost to breaks when a long name wraps. */
        const val WRAP_LOSS = 0.3f
    }
}
