// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import com.lxlauncher.tools.Duplicate
import com.lxlauncher.tools.Duplicates
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** What the File Explorer's cleaner looks for. */
enum class JunkKind {
    /** Every app's own cache, cleared by Android itself (`pm trim-caches`). Needs LX Bridge. */
    APP_CACHE,
    /** The cache folders apps keep in Android/data. Needs LX Bridge. */
    EXTERNAL_CACHE,
    /** Gallery thumbnail folders, which Android draws again when it needs them. */
    THUMBNAILS,
    /** Temporary and log files, and what a card check left in LOST.DIR. */
    TEMP_LOGS,
    /** Folders with nothing in them. */
    EMPTY_FOLDERS,
    /** Installer files; the ones whose app is already installed at the same or a newer version are picked. */
    APKS,
    /** Byte-identical copies; the copy in DCIM, then Pictures, then the oldest one stays. */
    DUPLICATES,
    /** Files of 100 MB and more, listed to look over. None is picked for you. */
    LARGE,
}

/** One thing the cleaner can remove. [detail] says why it is there (an app name, "copy of ...", "installed"). */
class JunkItem(val path: String, val name: String, val bytes: Long, val detail: String = "", val picked: Boolean = true,
               internal val duplicate: Duplicate? = null)

/** A finished part of a scan. [needsBridge] marks a group that could not be read without LX Bridge. */
class JunkGroup(val kind: JunkKind, val items: List<JunkItem>, val needsBridge: Boolean = false) {
    val bytes: Long get() = items.sumOf { it.bytes }
}

class CleanReport(val freedBytes: Long, val removed: Int, val failed: Int)

/**
 * Finds and removes junk on shared storage, and, through LX Bridge, the caches of every app. One walk of shared
 * storage reads each entry once and feeds every group that comes from files. Nothing is removed by [scan]; [clean]
 * removes only the items it is given, and reads each again first.
 *
 * Both calls block: run them on a worker thread.
 */
internal class Cleaner(context: Context) {
    private val ctx = context.applicationContext
    private val shellFs = ShellFs(ctx)

    fun bridgeReady() = shellFs.ready()

    /** Scans, handing each group to [onGroup] as it is done. Stops early when [cancel] is set. */
    fun scan(cancel: Cancel, onGroup: (JunkGroup) -> Unit) {
        val started = SystemClock.uptimeMillis()
        onGroup(appCaches())
        cancel.check()
        onGroup(externalCaches())
        cancel.check()
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (root == null || !root.isDirectory || !AndroidStorage.allFilesAccess(ctx)) {
            for (k in listOf(JunkKind.THUMBNAILS, JunkKind.TEMP_LOGS, JunkKind.EMPTY_FOLDERS, JunkKind.APKS, JunkKind.DUPLICATES, JunkKind.LARGE))
                onGroup(JunkGroup(k, emptyList()))
            return
        }
        val walk = Walk(root, cancel)
        walk.run()
        onGroup(JunkGroup(JunkKind.THUMBNAILS, walk.thumbnails))
        onGroup(JunkGroup(JunkKind.TEMP_LOGS, walk.temp))
        onGroup(JunkGroup(JunkKind.EMPTY_FOLDERS, walk.empty))
        onGroup(JunkGroup(JunkKind.APKS, apkItems(walk.apks)))
        cancel.check()
        onGroup(JunkGroup(JunkKind.DUPLICATES, duplicates(walk.bySize, root, cancel)))
        onGroup(JunkGroup(JunkKind.LARGE, walk.large.sortedByDescending { it.bytes }))
        Log.d(TAG, "scan of ${walk.seen} entries took ${SystemClock.uptimeMillis() - started} ms")
    }

    // ---- app caches, through the shell ----

    /**
     * Every app's cache as Android last counted it (`dumpsys diskstats`). Android updates that count about once a
     * day, so the window says so; what [clean] reports is measured on the spot.
     */
    private fun appCaches(): JunkGroup {
        if (!shellFs.ready()) return JunkGroup(JunkKind.APP_CACHE, emptyList(), needsBridge = true)
        val r = runCatching { shellFs.run("dumpsys diskstats", DUMPSYS_TIMEOUT_MS) }.getOrNull()
            ?: return JunkGroup(JunkKind.APP_CACHE, emptyList(), needsBridge = true)
        val text = r.text
        val names = arrayIn(text, "Package Names:").map { it.trim().trim('"') }
        val sizes = arrayIn(text, "Cache Sizes:").map { it.trim().toLongOrNull() ?: 0L }
        val pm = ctx.packageManager
        val items = names.zip(sizes).filter { (_, size) -> size >= MIN_APP_CACHE }.sortedByDescending { it.second }.map { (pkg, size) ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            JunkItem(pkg, label, size, pkg)
        }
        return JunkGroup(JunkKind.APP_CACHE, items)
    }

    /** The values of a `Name: [a,b,c]` line. */
    private fun arrayIn(text: String, label: String): List<String> {
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith(label) } ?: return emptyList()
        val inner = line.substringAfter('[', "").substringBeforeLast(']', "")
        return if (inner.isBlank()) emptyList() else inner.split(',')
    }

    private fun externalCaches(): JunkGroup {
        if (!shellFs.ready()) return JunkGroup(JunkKind.EXTERNAL_CACHE, emptyList(), needsBridge = true)
        val base = primary() ?: return JunkGroup(JunkKind.EXTERNAL_CACHE, emptyList())
        val r = runCatching { shellFs.run("du -sk -- ${ShellFs.q("$base/Android/data")}/*/cache 2>/dev/null; exit 0", DU_TIMEOUT_MS) }.getOrNull()
            ?: return JunkGroup(JunkKind.EXTERNAL_CACHE, emptyList(), needsBridge = true)
        val pm = ctx.packageManager
        val items = r.text.lineSequence().mapNotNull { line ->
            val kb = line.substringBefore('\t').trim().toLongOrNull() ?: return@mapNotNull null
            val path = line.substringAfter('\t', "").trim()
            if (kb * 1024 < MIN_EXTERNAL_CACHE || !path.endsWith("/cache")) return@mapNotNull null
            val pkg = path.removeSuffix("/cache").substringAfterLast('/')
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            JunkItem(path, label, kb * 1024, pkg)
        }.sortedByDescending { it.bytes }.toList()
        return JunkGroup(JunkKind.EXTERNAL_CACHE, items)
    }

    // ---- shared storage ----

    /**
     * One walk over shared storage. Android/ is left to the shell groups above, and hidden folders are not entered
     * (a gallery's trash is a hidden ".trashed-" file, and the ".thumbnails" folders are counted whole on their own).
     * Symbolic links are never followed.
     */
    private inner class Walk(private val root: File, private val cancel: Cancel) {
        val thumbnails = ArrayList<JunkItem>()
        val temp = ArrayList<JunkItem>()
        val empty = ArrayList<JunkItem>()
        val apks = ArrayList<File>()
        val large = ArrayList<JunkItem>()
        val bySize = HashMap<Long, MutableList<File>>()
        var seen = 0
        private val counts = ArrayDeque<Int>()

        private fun countInParent() {
            if (counts.isNotEmpty()) counts.addLast(counts.removeLast() + 1)
        }

        private fun stop() = cancel.cancelled || seen >= MAX_ENTRIES

        fun run() {
            val lostDir = File(root, "LOST.DIR")
            val visitor = object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (stop()) return FileVisitResult.TERMINATE
                    val f = dir.toFile()
                    if (f != root) {
                        if (f.name == ".thumbnails") {
                            val bytes = sizeOf(f)
                            if (bytes > 0) thumbnails += JunkItem(f.absolutePath, f.parentFile?.name.orEmpty() + "/.thumbnails", bytes)
                            countInParent()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (f.name.startsWith(".") || (f.parentFile == root && f.name == "Android")) {
                            countInParent()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    counts.addLast(0)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    seen++
                    countInParent()
                    if (!attrs.isRegularFile) return if (stop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                    val f = file.toFile()
                    val name = f.name
                    val size = attrs.size()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!name.startsWith(".")) {
                        when {
                            f.parentFile == lostDir || ext in TEMP_EXT -> temp += JunkItem(f.absolutePath, name, size)
                            ext == "apk" -> apks += f
                        }
                        if (size >= LARGE_BYTES) large += JunkItem(f.absolutePath, name, size, f.parentFile?.absolutePath.orEmpty(), picked = false)
                        if (size >= MIN_DUPLICATE && ext in DUPLICATE_EXT) bySize.getOrPut(size) { ArrayList() }.add(f)
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
                    if (children == 0 && exc == null && offerable(f)) empty += JunkItem(f.absolutePath, f.name, 0L, f.parentFile?.absolutePath.orEmpty())
                    countInParent()
                    return if (stop()) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            }
            runCatching { Files.walkFileTree(root.toPath(), visitor) }
        }

        /** Top-level media and app folders stay even when they are empty right now; nested empty folders may go. */
        private fun offerable(dir: File): Boolean {
            if (dir == root || dir.name.startsWith(".")) return false
            return !(dir.parentFile == root && dir.name in PROTECTED_ROOTS)
        }
    }

    private fun sizeOf(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < MAX_ENTRIES) {
            val children = stack.removeLast().listFiles() ?: continue
            for (c in children) if (c.isDirectory) stack.addLast(c) else total += c.length()
        }
        return total
    }

    /** APK files, with the ones whose app is already installed (same version or newer) picked. */
    private fun apkItems(files: List<File>): List<JunkItem> {
        val pm = ctx.packageManager
        return files.map { f ->
            val info = runCatching { pm.getPackageArchiveInfo(f.absolutePath, 0) }.getOrNull()
            val installed = info?.packageName?.let { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            }
            val apkVersion = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
            val haveVersion = installed?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
            val covered = apkVersion != null && haveVersion != null && haveVersion >= apkVersion
            JunkItem(f.absolutePath, f.name, f.length(), if (covered) DETAIL_INSTALLED else "", picked = covered)
        }.sortedByDescending { it.bytes }
    }

    /** Same size, then the same first 64 KB, then the same SHA-256: only then is a file a copy. */
    private fun duplicates(bySize: Map<Long, List<File>>, root: File, cancel: Cancel): List<JunkItem> {
        val out = ArrayList<JunkItem>()
        for ((size, group) in bySize) {
            if (group.size < 2) continue
            cancel.check()
            val byHead = HashMap<String, MutableList<File>>()
            for (f in group) Duplicates.sha256(f, HEAD_BYTES)?.let { byHead.getOrPut(it) { ArrayList() }.add(f) }
            for (same in byHead.values) {
                if (same.size < 2) continue
                val byHash = HashMap<String, MutableList<File>>()
                for (f in same) Duplicates.sha256(f)?.let { byHash.getOrPut(it) { ArrayList() }.add(f) }
                for ((hash, copies) in byHash) {
                    if (copies.size < 2) continue
                    val keeper = Duplicates.keeper(copies, root)
                    for (f in copies) if (f != keeper) {
                        out += JunkItem(f.absolutePath, f.name, size, keeper.absolutePath, duplicate = Duplicate(f, keeper, size, hash))
                    }
                }
            }
        }
        return out.sortedByDescending { it.bytes }
    }

    // ---- cleaning ----

    /**
     * Removes [items] of each kind. Files are read again first: a file that changed size since the scan stays, and a
     * copy goes only while it and the copy that stays still match byte for byte. App caches are cleared by Android;
     * the space that frees is measured on the data partition before and after.
     */
    fun clean(items: Map<JunkKind, List<JunkItem>>, cancel: Cancel, onProgress: (Int, Int) -> Unit): CleanReport {
        val total = items.values.sumOf { it.size }
        var done = 0
        var freed = 0L
        var removed = 0
        var failed = 0
        fun step() = onProgress(++done, total)
        for ((kind, list) in items) {
            for (item in list) {
                cancel.check()
                val ok: Long? = when (kind) {
                    JunkKind.APP_CACHE -> null
                    JunkKind.EXTERNAL_CACHE -> clearFolderThroughShell(item)
                    JunkKind.THUMBNAILS -> clearFolder(File(item.path))
                    JunkKind.EMPTY_FOLDERS -> File(item.path).let { if (it.isDirectory && it.list()?.isEmpty() == true && it.delete()) 0L else -1L }
                    JunkKind.DUPLICATES -> item.duplicate?.let { d -> if (Duplicates.stillSafeToDelete(d) && d.file.delete()) d.size else -1L } ?: -1L
                    else -> File(item.path).let { f -> if (f.isFile && f.length() == item.bytes && f.delete()) item.bytes else -1L }
                }
                when {
                    ok == null -> {}
                    ok >= 0 -> {
                        freed += ok
                        removed++
                    }
                    else -> failed++
                }
                if (kind != JunkKind.APP_CACHE) step()
            }
            if (kind == JunkKind.APP_CACHE && list.isNotEmpty()) {
                val gained = trimAppCaches()
                if (gained >= 0) {
                    freed += gained
                    removed += list.size
                } else failed += list.size
                repeat(list.size) { step() }
            }
        }
        Log.i(TAG, "cleaned $removed items, ${failed} kept, $freed bytes")
        return CleanReport(freed, removed, failed)
    }

    /** Asks Android to clear every app's cache; returns the space that freed on the data partition, or -1. */
    private fun trimAppCaches(): Long {
        val before = dataFree()
        val r = runCatching { shellFs.run("pm trim-caches 999999999999999", TRIM_TIMEOUT_MS) }.getOrNull() ?: return -1
        if (r.exit != 0) {
            Log.w(TAG, "trim-caches failed: exit ${r.exit} ${r.text.take(120)}")
            return -1
        }
        return (dataFree() - before).coerceAtLeast(0)
    }

    private fun dataFree(): Long = runCatching { StatFs(Environment.getDataDirectory().absolutePath).availableBytes }.getOrDefault(0L)

    /** Empties an app's cache folder in Android/data and keeps the folder itself; returns the bytes it held, or -1. */
    private fun clearFolderThroughShell(item: JunkItem): Long {
        if (!item.path.endsWith("/cache") || "/Android/data/" !in item.path) return -1
        val q = ShellFs.q(item.path)
        val r = runCatching { shellFs.run("cd -- $q && find . -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +", CLEAN_TIMEOUT_MS) }.getOrNull()
        return if (r?.exit == 0) item.bytes else -1
    }

    private fun clearFolder(dir: File): Long {
        if (!dir.isDirectory) return -1
        val bytes = sizeOf(dir)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        return bytes - sizeOf(dir)
    }

    // ---- quick tools ----

    /**
     * Ends the background processes Android counts as safe to end (`am kill-all`), through the shell. Returns how much
     * memory became available, measured before and after, or -1 without LX Bridge.
     */
    fun closeBackgroundApps(): Long {
        if (!shellFs.ready()) return -1
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return -1
        val before = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val r = runCatching { shellFs.run("am kill-all", KILL_TIMEOUT_MS) }.getOrNull() ?: return -1
        if (r.exit != 0) return -1
        // Android needs a moment to hand back the memory of the ended processes.
        SystemClock.sleep(SETTLE_MS)
        val after = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        return (after - before).coerceAtLeast(0)
    }

    /**
     * Asks Android to tell the flash storage which blocks are free (`sm fstrim`), which it otherwise does only while
     * the phone idles and charges. Returns false without LX Bridge or when Android refused.
     */
    fun trimStorage(): Boolean {
        if (!shellFs.ready()) return false
        val r = runCatching { shellFs.run("sm fstrim", TRIM_TIMEOUT_MS) }.getOrNull() ?: return false
        Log.i(TAG, "fstrim: exit ${r.exit} ${r.text.take(120).trim()}")
        return r.exit == 0
    }

    private fun primary(): String? = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()

    companion object {
        private const val TAG = "LxCleaner"
        const val DETAIL_INSTALLED = "installed"
        private const val MAX_ENTRIES = 300_000
        private const val LARGE_BYTES = 100L * 1024 * 1024
        private const val MIN_DUPLICATE = 256L * 1024
        private const val HEAD_BYTES = 64L * 1024
        private const val MIN_APP_CACHE = 1024L * 1024
        private const val MIN_EXTERNAL_CACHE = 64L * 1024
        private const val DUMPSYS_TIMEOUT_MS = 20_000
        private const val DU_TIMEOUT_MS = 30_000
        private const val TRIM_TIMEOUT_MS = 110_000
        private const val CLEAN_TIMEOUT_MS = 60_000
        private const val KILL_TIMEOUT_MS = 30_000
        private const val SETTLE_MS = 600L
        private val TEMP_EXT = setOf("tmp", "temp", "log")
        private val DUPLICATE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "mp4", "mkv", "mov", "3gp",
            "webm", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "zip",
            "rar", "7z", "apk")
        private val PROTECTED_ROOTS = setOf("Android", "DCIM", "Pictures", "Download", "Movies", "Music",
            "Documents", "Alarms", "Audiobooks", "Notifications", "Podcasts", "Ringtones", "Recordings")
    }
}
