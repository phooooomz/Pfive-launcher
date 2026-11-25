// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tools

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.system.SystemInfo
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.windows.ToolView
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * One round control that frees space and memory. A tap sweeps everything LX can do without privilege: it clears
 * LX's own cache, deletes empty folders and byte-identical duplicate images (after one confirmation), and asks
 * Android to close other apps' background work. The rows under the orb say what the sweep will touch. Clearing
 * other apps' caches would need a shell command LX cannot run on its own, so it is left out rather than faked.
 */
class CleanupTool(private val shell: Shell) : ToolView {
    private val ctx = shell.activity
    private val ui = shell.ui
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-cleanup").apply { isDaemon = true } }

    @Volatile private var destroyed = false
    /** Bumped on each scan so a result from an earlier scan, finishing late, is ignored. */
    @Volatile private var scan = 0
    private var ready = false
    /** When the last full scan finished, and whether it could read shared storage. */
    private var lastScanAt = 0L
    private var scannedWithAccess = false

    private val col: LinearLayout
    override val view: View

    private val orb: FrameLayout
    private val orbFace = Orb(ctx, ui)
    private val orbValue: TextView
    private val orbLabel: TextView
    private val access: TextView
    private val accessButton: TextView
    private val cacheVal: TextView
    private val emptyVal: TextView
    private val dupeVal: TextView
    private val appsVal: TextView

    private var cacheBytes = 0L
    private var emptyDirs: List<File> = emptyList()
    private var dupeFiles: List<Duplicate> = emptyList()
    private var dupeBytes = 0L

    init {
        val (scroll, c) = shell.column()
        col = c.apply { gravity = Gravity.CENTER_HORIZONTAL }
        view = scroll

        val subtitle = Ui.text(ctx, ui, ctx.getString(R.string.cleanup_subtitle), ui.textBody, ui.textMuted, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dpi(4f), 0, ui.dpi(8f))
        }
        col.addView(subtitle, Ui.lp(Ui.MATCH, Ui.WRAP))

        orbValue = Ui.text(ctx, ui, "", ui.textTitle * 1.35f, ui.onAccent, bold = true).apply { gravity = Gravity.CENTER }
        orbLabel = Ui.text(ctx, ui, ctx.getString(R.string.cleanup_clear), ui.textLabel, ui.onAccent).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
        }
        val orbText = Ui.row(ctx, vertical = true, gravity = Gravity.CENTER).apply {
            addView(orbValue, Ui.lp(Ui.WRAP, Ui.WRAP))
            addView(orbLabel, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        val size = ui.dpi(200f)
        orb = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = true
            contentDescription = ctx.getString(R.string.cleanup_clear)
            addView(orbFace, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(orbText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnClickListener { sweep() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        orbFace.held = true; orbFace.invalidate()
                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        orbFace.held = false; orbFace.invalidate()
                        v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                    }
                }
                false
            }
        }
        col.addView(orb, Ui.lp(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ui.dpi(12f)
            bottomMargin = ui.dpi(20f)
        })
        // The orb is the tool's one action, so it stays whole on the first screen of a small window: it shrinks with
        // the window (never past ORB_MIN_DP) and its figure with it, instead of being cut off at the window's edge.
        val valueSp = ui.textTitle * 1.35f
        val labelSp = ui.textLabel
        scroll.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            scroll.post {
                // The room under the line above it, less the orb's own top margin and a little air below.
                val below = (b - t) - col.paddingTop - subtitle.height - ui.dpi(12f) - ui.dpi(6f)
                val want = minOf(((r - l) * 0.62f).toInt(), below).coerceIn(ui.dpi(ORB_MIN_DP), size)
                if (orb.layoutParams.width == want) return@post
                orb.layoutParams = orb.layoutParams.apply { width = want; height = want }
                val k = want.toFloat() / size
                orbValue.textSize = valueSp * maxOf(0.5f, k)
                orbLabel.textSize = labelSp * maxOf(0.8f, k)
            }
        }

        access = shell.paragraph(ctx.getString(R.string.cleanup_need_access), muted = true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        col.addView(access)
        accessButton = Ui.button(ctx, ui, ctx.getString(R.string.cleanup_grant)) { requestStorage() }.apply { visibility = View.GONE }
        col.addView(accessButton, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = ui.dpi(8f)
        })

        cacheVal = resultRow(R.drawable.ic_storage, R.string.cleanup_cache_title)
        emptyVal = resultRow(R.drawable.ic_folder, R.string.cleanup_empty_title)
        dupeVal = resultRow(R.drawable.ic_image, R.string.cleanup_dupes_title)
        appsVal = resultRow(R.drawable.ic_apps, R.string.cleanup_apps_title)

        col.addView(Ui.button(ctx, ui, ctx.getString(R.string.appmgr_open)) { shell.openTool("appmgr") }, Ui.lp(Ui.WRAP, Ui.WRAP).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ui.dpi(20f)
        })
    }

    private fun resultRow(icon: Int, label: Int): TextView {
        val value = Ui.text(ctx, ui, ctx.getString(R.string.cleanup_scanning), ui.textBody, ui.textPrimary)
        col.addView(Ui.row(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(ui.accent)
            }, Ui.lp(ui.dpi(20f), ui.dpi(20f)).apply { marginEnd = ui.dpi(12f) })
            addView(Ui.text(ctx, ui, ctx.getString(label), ui.textBody, ui.textPrimary), Ui.lp(0, Ui.WRAP, 1f))
            addView(value, Ui.lp(Ui.WRAP, Ui.WRAP))
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dpi(12f) })
        return value
    }

    /** A scan of shared storage walks every folder; showing the window again within [RESCAN_AFTER_MS] keeps it. */
    override fun onShown() {
        val fresh = lastScanAt > 0 && SystemClock.uptimeMillis() - lastScanAt < RESCAN_AFTER_MS && hasStorageAccess() == scannedWithAccess
        if (!fresh) rescan()
    }

    override fun onHidden() {
        scan++
    }

    override fun onDestroy() {
        destroyed = true
        scan++
        orbFace.run(false)
        io.shutdownNow()
    }

    // ---- scanning ----

    private fun rescan() {
        val mine = ++scan
        ready = false
        lastScanAt = 0L
        orbFace.run(true)
        orbValue.text = ""
        orbLabel.text = ctx.getString(R.string.cleanup_scanning)
        cacheVal.text = ctx.getString(R.string.cleanup_scanning)
        val canScanFiles = hasStorageAccess()
        scannedWithAccess = canScanFiles
        access.visibility = if (canScanFiles) View.GONE else View.VISIBLE
        accessButton.visibility = if (canScanFiles) View.GONE else View.VISIBLE
        if (canScanFiles) {
            emptyVal.text = ctx.getString(R.string.cleanup_scanning)
            dupeVal.text = ctx.getString(R.string.cleanup_scanning)
        } else {
            emptyDirs = emptyList(); dupeFiles = emptyList(); dupeBytes = 0L
            emptyVal.text = ctx.getString(R.string.cleanup_empty_none)
            dupeVal.text = ctx.getString(R.string.cleanup_dupes_none)
        }
        appsVal.text = ctx.getString(R.string.cleanup_apps_count, userPackages().size)
        io.execute {
            val size = dirBytes(ctx.cacheDir) + (ctx.externalCacheDir?.let { dirBytes(it) } ?: 0L)
            post(mine) {
                cacheBytes = size
                cacheVal.text = if (size > 0) SystemInfo.formatBytes(size) else ctx.getString(R.string.cleanup_cache_none)
                settle(mine)
            }
            if (canScanFiles) scanStorage(mine)
        }
    }

    private fun scanStorage(mine: Int) {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (root == null || !root.isDirectory) {
            post(mine) {
                emptyVal.text = ctx.getString(R.string.cleanup_empty_none)
                dupeVal.text = ctx.getString(R.string.cleanup_dupes_none)
                settle(mine)
            }
            return
        }
        val empties = ArrayList<File>()
        val bySize = HashMap<Long, MutableList<File>>()
        walk(root, mine, empties, bySize)
        val remove = ArrayList<Duplicate>()
        var reclaim = 0L
        for ((size, group) in bySize) {
            if (group.size < 2 || scan != mine) continue
            // Most pictures of the same size differ in their first 64 KB; only those that match are read whole.
            val byHead = HashMap<String, MutableList<File>>()
            for (f in group) Duplicates.sha256(f, HEAD_BYTES)?.let { byHead.getOrPut(it) { ArrayList() }.add(f) }
            val byHash = HashMap<String, MutableList<File>>()
            for (same in byHead.values) {
                if (same.size < 2 || scan != mine) continue
                for (f in same) Duplicates.sha256(f)?.let { byHash.getOrPut(it) { ArrayList() }.add(f) }
            }
            for ((hash, same) in byHash) {
                if (same.size < 2) continue
                val keeper = Duplicates.keeper(same, root)
                for (f in same) {
                    if (f == keeper) continue
                    remove.add(Duplicate(f, keeper, size, hash))
                    reclaim += size
                }
            }
        }
        post(mine) {
            lastScanAt = SystemClock.uptimeMillis()
            emptyDirs = empties
            emptyVal.text = if (empties.isEmpty()) ctx.getString(R.string.cleanup_empty_none)
                else ctx.getString(R.string.cleanup_empty_value, empties.size)
            dupeFiles = remove
            dupeBytes = reclaim
            dupeVal.text = if (remove.isEmpty()) ctx.getString(R.string.cleanup_dupes_none)
                else ctx.getString(R.string.cleanup_dupes_value, remove.size, SystemInfo.formatBytes(reclaim))
            settle(mine)
        }
    }

    /** Lights the orb once the first results are in: it shows the space the sweep would free. */
    private fun settle(mine: Int) {
        if (scan != mine) return
        ready = true
        orbFace.run(false)
        orbValue.text = SystemInfo.formatBytes(cacheBytes + dupeBytes)
        orbLabel.text = ctx.getString(R.string.cleanup_clear)
    }

    // ---- the sweep ----

    private fun sweep() {
        if (!ready) return
        val message = ctx.getString(R.string.cleanup_all_confirm, SystemInfo.formatBytes(cacheBytes),
            emptyDirs.size, dupeFiles.size, SystemInfo.formatBytes(dupeBytes))
        shell.confirm(ctx.getString(R.string.cleanup_all_title), message, ctx.getString(R.string.cleanup_clear), danger = true) {
            val cacheFreed = cacheBytes
            val targetEmpty = emptyDirs
            val targetDupe = dupeFiles
            ready = false
            orbValue.text = ""
            orbLabel.text = ctx.getString(R.string.cleanup_scanning)
            orbFace.run(true)
            orb.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140)
                .withEndAction { orb.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            val startAt = SystemClock.uptimeMillis()
            io.execute {
                ctx.cacheDir?.let { clearDir(it) }
                ctx.externalCacheDir?.let { clearDir(it) }
                // Still a folder, and still empty: a file that took an empty folder's name since the scan stays.
                val goneDirs = targetEmpty.count { it.isDirectory && it.listFiles()?.isEmpty() == true && it.delete() }
                // The scan may be minutes old: each copy and the one that stays are read again, and a copy
                // goes only while both still hold the same bytes.
                var goneDupes = 0
                var dupesFreed = 0L
                for (d in targetDupe) {
                    if (Duplicates.stillSafeToDelete(d) && d.file.delete()) {
                        goneDupes++
                        dupesFreed += d.size
                    }
                }
                val freed = cacheFreed + dupesFreed
                val apps = closeBackgroundApps()
                val wait = (MIN_SWEEP_MS - (SystemClock.uptimeMillis() - startAt)).coerceAtLeast(0L)
                main.postDelayed({
                    if (destroyed) return@postDelayed
                    shell.notify(ctx.getString(R.string.cleanup_all_done, SystemInfo.formatBytes(freed), goneDirs + goneDupes, apps))
                    rescan()
                }, wait)
            }
        }
    }

    // ---- helpers ----

    /**
     * Asks Android to close the background processes of the installed apps; returns how many apps it asked for. Since
     * Android 14 an app may close only its own processes this way, so there the request goes through lxdb, which runs
     * as the shell, in one command for all apps. Without lxdb nothing can be closed there and the count is 0, not a
     * number of calls that did nothing. Runs on [io].
     */
    private fun closeBackgroundApps(): Int {
        val packages = userPackages().filter { PACKAGE_NAME.matches(it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
            var asked = 0
            for (pkg in packages) runCatching { am.killBackgroundProcesses(pkg); asked++ }
            return asked
        }
        if (packages.isEmpty()) return 0
        // `am kill` only ends processes Android counts as safe to end: nothing in front, nothing in an LX window.
        val script = "for p in ${packages.joinToString(" ")}; do am kill \"\$p\" >/dev/null 2>&1; done"
        val result = EmbedClient.get(ctx).shell(script, timeoutMs = KILL_TIMEOUT_MS) ?: return 0
        return if (result.exit == 0) packages.size else 0
    }

    private fun userPackages(): Set<String> = shell.apps.apps.mapTo(HashSet()) { it.packageName }.apply { remove(ctx.packageName) }

    private fun hasStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val own = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
            if (!shell.startSafely(own)) shell.startSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            shell.activity.requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 0)
        }
    }

    private inline fun post(mine: Int, crossinline block: () -> Unit) = main.post {
        if (!destroyed && scan == mine) block()
    }

    /**
     * Walks shared storage once, reading each entry's type and size in the same call that lists it (one stat per
     * entry, where listFiles, isDirectory and length made three or four through FUSE). Hidden folders and Android/
     * are not entered, but still count as content of their parent, so a folder holding only ".thumbnails" is not
     * empty. Symbolic links are never followed.
     */
    private fun walk(root: File, mine: Int, empties: MutableList<File>, bySize: MutableMap<Long, MutableList<File>>) {
        val counts = ArrayDeque<Int>()
        var seen = 0
        fun countInParent() {
            if (counts.isNotEmpty()) counts.addLast(counts.removeLast() + 1)
        }
        fun stop() = scan != mine || seen >= MAX_ENTRIES
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (stop()) return FileVisitResult.TERMINATE
                val f = dir.toFile()
                if (f != root && skipDir(f, root)) {
                    countInParent()
                    return FileVisitResult.SKIP_SUBTREE
                }
                counts.addLast(0)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                seen++
                countInParent()
                // Hidden files are left out, as hidden folders are: a picture in the gallery's trash is a hidden
                // ".trashed-..." file beside the original's place, and as the copy in DCIM it was kept while the
                // visible copy was deleted, until the trash emptied itself and the picture was gone. ".pending-"
                // files are pictures still being written.
                if (attrs.isRegularFile && attrs.size() > 0) {
                    val name = file.fileName?.toString().orEmpty()
                    if (!name.startsWith(".") && isImage(name)) bySize.getOrPut(attrs.size()) { ArrayList() }.add(file.toFile())
                }
                return if (stop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                countInParent()
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                val children = if (counts.isNotEmpty()) counts.removeLast() else 1
                val f = dir.toFile()
                if (children == 0 && exc == null && offerableEmptyDir(f, root)) empties.add(f)
                countInParent()
                return if (stop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }
        }
        runCatching { Files.walkFileTree(root.toPath(), visitor) }
    }

    /** Top-level media and app folders stay, even when momentarily empty; a nested empty folder is fair game. */
    private fun offerableEmptyDir(dir: File, root: File): Boolean {
        if (dir == root || dir.name.startsWith(".")) return false
        val parent = dir.parentFile ?: return false
        if (parent == root && dir.name in PROTECTED_ROOTS) return false
        return true
    }

    private fun skipDir(dir: File, root: File): Boolean {
        if (dir.name.startsWith(".")) return true
        return dir.parentFile == root && dir.name == "Android"
    }

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXT

    private fun dirBytes(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val children = stack.removeLast().listFiles() ?: continue
            for (c in children) if (c.isDirectory) stack.addLast(c) else total += c.length()
        }
        return total
    }

    private fun clearDir(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }


    /**
     * The round face of the control: a glossy sphere in the theme accent with a soft halo, so it reads as one
     * raised button rather than a flat disc. The light sits at the upper left; a press brightens the halo.
     */
    private class Orb(context: Context, private val theme: LxTheme) : View(context) {
        var held = false
        var running = false
            private set
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val body = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()
        private var angle = 0f
        private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 820
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
        }

        /** Spins a bright arc on the sphere while a scan or a sweep is in flight. */
        fun run(on: Boolean) {
            if (running == on) return
            running = on
            if (on) spinner.start() else spinner.cancel()
            invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            spinner.cancel()
        }

        /** The size and press state the shaders were made for; the spinner redraws every frame without new ones. */
        private var shadedFor = -1L

        /** Builds the four gradients once per size or press, not on every frame of the spinner. */
        private fun shade(cx: Float, cy: Float, outer: Float, r: Float) {
            val key = (width.toLong() shl 32) or (height.toLong() shl 1) or (if (held) 1L else 0L)
            if (key == shadedFor) return
            shadedFor = key
            val accent = theme.accent
            val light = blend(accent, Color.WHITE, 0.72f)
            val edge = blend(accent, theme.background, 0.70f)
            val lx = cx - r * 0.34f
            val ly = cy - r * 0.40f
            halo.shader = RadialGradient(cx, cy, outer,
                intArrayOf(LxTheme.withAlpha(accent, if (held) 0.55f else 0.34f), LxTheme.withAlpha(accent, 0f)),
                floatArrayOf(0.76f, 1f), Shader.TileMode.CLAMP)
            body.shader = RadialGradient(lx, ly, r * 1.55f,
                intArrayOf(light, accent, edge), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            rim.strokeWidth = r * 0.025f
            rim.shader = RadialGradient(cx, cy + r * 0.5f, r,
                intArrayOf(LxTheme.withAlpha(Color.WHITE, 0.25f), LxTheme.withAlpha(accent, 0f)),
                floatArrayOf(0.85f, 1f), Shader.TileMode.CLAMP)
            gloss.shader = RadialGradient(lx, ly, r * 0.62f,
                intArrayOf(LxTheme.withAlpha(Color.WHITE, 0.55f), LxTheme.withAlpha(Color.WHITE, 0f)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) / 2f
            val r = outer * 0.78f
            shade(cx, cy, outer, r)
            canvas.drawCircle(cx, cy, outer, halo)
            canvas.drawCircle(cx, cy, r, body)
            canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
            canvas.drawCircle(cx - r * 0.34f, cy - r * 0.40f, r * 0.62f, gloss)

            if (running) {
                arc.strokeWidth = r * 0.06f
                arc.color = LxTheme.withAlpha(Color.WHITE, 0.9f)
                val ar = r * 0.80f
                oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
                canvas.drawArc(oval, angle, 90f, false, arc)
            }
        }

        private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())
    }

    private companion object {
        /** The smallest the orb gets in a small window. */
        private const val ORB_MIN_DP = 64f
        const val KIND = "cleanup"
        const val MIN_SWEEP_MS = 650L
        const val MAX_ENTRIES = 300_000
        const val RESCAN_AFTER_MS = 120_000L
        const val HEAD_BYTES = 64L * 1024
        const val KILL_TIMEOUT_MS = 60_000
        /** What a package name may contain; anything else never reaches the shell. */
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        val PROTECTED_ROOTS = setOf("Android", "DCIM", "Pictures", "Download", "Movies", "Music",
            "Documents", "Alarms", "Audiobooks", "Notifications", "Podcasts", "Ringtones")
    }
}

/** A byte-identical copy a scan found, and the copy that stays. Both are read again right before the delete. */
class Duplicate(val file: File, val keeper: File, val size: Long, val hash: String)

/** Which copy of a picture stays, and whether a copy is still safe to delete. */
object Duplicates {
    /**
     * The copy to keep: one in DCIM (where the camera puts originals) before one in Pictures before anywhere
     * else, then the oldest, then the shortest path. Keeping the shortest path alone kept a Download copy and
     * deleted the camera original.
     */
    fun keeper(copies: List<File>, root: File): File =
        copies.minWith(compareBy<File>({ rank(it, root) }, { it.lastModified() }, { it.path.length }, { it.path }))

    private fun rank(f: File, root: File): Int {
        val top = f.absolutePath.removePrefix(root.absolutePath).trimStart('/').substringBefore('/')
        return when (top) {
            "DCIM" -> 0
            "Pictures" -> 1
            else -> 2
        }
    }

    /**
     * Both files still exist as separate files with the size and hash the scan saw, and the copy that stays is not a
     * hidden file: one in the gallery's trash or still being written does not keep the picture.
     */
    fun stillSafeToDelete(d: Duplicate): Boolean {
        val file = d.file
        val keeper = d.keeper
        if (keeper.name.startsWith(".")) return false
        if (!file.isFile || !keeper.isFile || file.length() != d.size || keeper.length() != d.size) return false
        val same = runCatching { file.canonicalPath == keeper.canonicalPath }.getOrDefault(true)
        if (same) return false
        return sha256(keeper) == d.hash && sha256(file) == d.hash
    }

    /** The SHA-256 of [f], or of its first [limit] bytes. */
    fun sha256(f: File, limit: Long = Long.MAX_VALUE): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var left = limit
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) break
                digest.update(buffer, 0, n)
                left -= n
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
