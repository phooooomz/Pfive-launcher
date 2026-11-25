// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The device's storage as the File Manager sees it: volumes, the local backend when all-files
 * access is granted, granted SAF trees, recent files and recursive search. One per process:
 * prefer [get]; the first instance built either way becomes the one [get] returns.
 */
class AndroidStorage(context: Context) : StorageCatalog {
    private val context: Context = context.applicationContext
    private val storage = context.getSystemService(StorageManager::class.java)
    private val resolver: ContentResolver = context.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val safBackends = HashMap<Uri, SafBackend>()
    @Volatile private var localBackend: LocalBackend? = null
    /** The shell through LX Bridge, for what all-files access leaves closed. */
    private val shellFs = ShellFs(context)

    private class Snapshot(val volumes: List<Volume>, val mounts: Set<String>, val time: Long)
    @Volatile private var snapshot: Snapshot? = null

    private var receiver: BroadcastReceiver? = null
    private var callback: Any? = null

    init {
        synchronized(AndroidStorage) {
            if (instance == null) instance = this
        }
    }

    override fun volumes(): List<Volume> = takeSnapshot().volumes

    override fun allFilesAccess(): Boolean = allFilesAccess(context)

    override fun fullAccess(): Boolean = allFilesAccess() && shellFs.ready()

    override fun grantThroughBridge(): Boolean {
        if (allFilesAccess()) return true
        if (Build.VERSION.SDK_INT < 30 || !shellFs.ready()) return false
        val pkg = context.packageName
        if (!PACKAGE_NAME.matches(pkg)) return false
        // The uid mode is what Android's own switch sets and what it reads first; the package mode follows it.
        val r = runCatching {
            shellFs.run("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow && appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
        }.getOrNull()
        val granted = allFilesAccess()
        Log.i(TAG, "all-files access through LX Bridge: exit ${r?.exit}, granted $granted")
        return granted
    }

    override fun systemPlaces(): List<Pair<String, String>> {
        if (!fullAccess()) return emptyList()
        val primary = primaryPath() ?: return listOf("root" to "/", "tmp" to "/data/local/tmp")
        return listOf("root" to "/", "data" to "$primary/Android/data", "obb" to "$primary/Android/obb", "tmp" to "/data/local/tmp")
    }

    override fun local(): Backend? = localBackend()

    override fun localNode(path: String): FsNode? = localBackend()?.node(path)

    override fun safTrees(): List<Uri> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }
        .sortedByDescending { it.persistedTime }
        .map { it.uri }

    override fun saf(tree: Uri): Backend = safBackend(tree)

    internal fun safBackend(tree: Uri): SafBackend = synchronized(safBackends) {
        safBackends.getOrPut(tree) { SafBackend(context, tree, this) }
    }

    override fun standardFolders(): List<Pair<String, String>> {
        val base = primaryPath() ?: return emptyList()
        return STANDARD.mapNotNull { name ->
            val dir = File(base, name)
            if (dir.isDirectory) name to dir.absolutePath else null
        }
    }

    @Suppress("DEPRECATION") // MediaColumns.DATA still holds the path, and all-files access may read it.
    override fun recent(limit: Int, cancel: Cancel): List<FsNode> {
        val local = localBackend() ?: return emptyList()
        if (limit <= 0) return emptyList()
        var uri = MediaStore.Files.getContentUri("external")
        val args = Bundle().apply {
            // Folders have no mime type in MediaStore.
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL")
            if (Build.VERSION.SDK_INT >= 30) {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            } else {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            }
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit * 2)
        }
        // MediaProvider before Android 11 drops QUERY_ARG_LIMIT but reads this parameter.
        if (Build.VERSION.SDK_INT < 30) uri = uri.buildUpon().appendQueryParameter("limit", (limit * 2).toString()).build()
        val out = ArrayList<FsNode>(limit)
        val c = try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), args, null)
        } catch (e: RuntimeException) {
            null
        } ?: return out
        c.use {
            // Rows that are gone or are folders do not count; the read still stops after limit * 4 rows.
            var rows = 0
            while (out.size < limit && rows < limit * 4 && c.moveToNext()) {
                if (cancel.cancelled) break
                rows++
                val path = c.getString(0) ?: continue
                if (FileTypes.isLeftoverName(path.substringAfterLast('/'))) continue
                val node = local.node(path) ?: continue
                if (!node.isDir) out += node
            }
        }
        return out
    }

    override fun categoryStats(cancel: Cancel): Map<FileCategory, CategoryStat> {
        if (localBackend() == null) return emptyMap()
        val out = LinkedHashMap<FileCategory, CategoryStat>()
        for (kind in FileCategory.values()) {
            if (cancel.cancelled) break
            var count = 0
            var bytes = 0L
            runCatching {
                resolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.SIZE),
                    selectionOf(kind), null, null)
            }.getOrNull()?.use { c ->
                while (c.moveToNext()) {
                    count++
                    bytes += c.getLong(0).coerceAtLeast(0)
                    if (count and 1023 == 0 && cancel.cancelled) break
                }
            }
            out[kind] = CategoryStat(count, bytes)
        }
        return out
    }

    @Suppress("DEPRECATION") // MediaColumns.DATA still holds the path, and all-files access may read it.
    override fun category(kind: FileCategory, limit: Int, cancel: Cancel): List<FsNode> {
        val local = localBackend() ?: return emptyList()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selectionOf(kind))
            if (Build.VERSION.SDK_INT >= 30) {
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            } else {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            }
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<FsNode>()
        runCatching { resolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA), args, null) }
            .getOrNull()?.use { c ->
                while (out.size < limit && c.moveToNext()) {
                    if (cancel.cancelled) break
                    val path = c.getString(0) ?: continue
                    val node = local.node(path) ?: continue
                    if (!node.isDir) out += node
                }
            }
        return out
    }

    @Suppress("DEPRECATION")
    private fun selectionOf(kind: FileCategory): String {
        val mime = MediaStore.MediaColumns.MIME_TYPE
        val data = MediaStore.MediaColumns.DATA
        fun mimes(vararg m: String) = "$mime IN (" + m.joinToString(",") { "'$it'" } + ")"
        return when (kind) {
            FileCategory.IMAGES -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            FileCategory.VIDEOS -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            FileCategory.AUDIO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}"
            FileCategory.DOCUMENTS -> mimes("application/pdf", "application/msword", "application/rtf", "text/plain", "text/csv",
                "application/epub+zip", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation")
            FileCategory.ARCHIVES -> mimes("application/zip", "application/x-zip-compressed", "application/x-rar-compressed",
                "application/vnd.rar", "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-gzip")
            FileCategory.APKS -> "(${mimes("application/vnd.android.package-archive")} OR $data LIKE '%.apk')"
            FileCategory.DOWNLOADS -> "$data LIKE '%/Download/%' AND $mime IS NOT NULL"
        }
    }

    override fun search(
        from: FsNode,
        query: String,
        cancel: Cancel,
        maxVisited: Int,
        maxHits: Int,
        onBatch: (List<FsNode>) -> Unit,
    ): SearchOutcome {
        val needle = query.lowercase(Locale.ROOT)
        return walk(from, cancel, maxVisited, maxHits, onBatch) { it.name.lowercase(Locale.ROOT).contains(needle) }
    }

    override fun findArchives(from: FsNode, cancel: Cancel, maxVisited: Int, onBatch: (List<FsNode>) -> Unit): SearchOutcome =
        walk(from, cancel, maxVisited, Int.MAX_VALUE, onBatch) { !it.isDir && FileTypes.isArchive(it.name) }

    override fun addVolumeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners += listener
            if (listeners.size == 1) register()
        }
    }

    override fun removeVolumeListener(listener: () -> Unit) {
        synchronized(listeners) {
            listeners -= listener
            if (listeners.isEmpty()) unregister()
        }
    }

    /** A volume by "primary" or UUID, from a snapshot at most two seconds old. */
    internal fun volumeById(id: String): Volume? = fresh().volumes.firstOrNull { it.id.equals(id, ignoreCase = true) }

    internal fun mountPaths(): Set<String> = fresh().mounts

    internal fun localBackend(): LocalBackend? {
        if (!allFilesAccess()) return null
        localBackend?.let { return it }
        synchronized(this) {
            return localBackend ?: LocalBackend(context, ::mountPaths, shellFs).also { localBackend = it }
        }
    }

    private fun fresh(): Snapshot {
        val s = snapshot
        if (s != null && SystemClock.uptimeMillis() - s.time < SNAPSHOT_MS) return s
        return takeSnapshot()
    }

    private fun takeSnapshot(): Snapshot {
        val list = readVolumes()
        return Snapshot(list, list.mapNotNullTo(HashSet()) { it.path }, SystemClock.uptimeMillis()).also { snapshot = it }
    }

    private fun readVolumes(): List<Volume> {
        // Volumes an app may write to get a files dir; the heuristic below reads removable volumes
        // without one as USB drives.
        val appRoots = HashMap<String, String>()
        for (dir in context.getExternalFilesDirs(null)) {
            if (dir == null) continue
            val volume = try {
                storage.getStorageVolume(dir)
            } catch (e: RuntimeException) {
                null
            } ?: continue
            val root = dir.absolutePath.substringBefore("/Android/data/", "")
            if (root.isNotEmpty()) appRoots[idOf(volume)] = root
        }
        return storage.storageVolumes.map { volume ->
            val id = idOf(volume)
            val state = volume.state
            val mounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
            val path = if (mounted) pathOf(volume, id, appRoots) else null
            var total = 0L
            var free = 0L
            if (path != null) {
                try {
                    val fs = StatFs(path)
                    total = fs.totalBytes
                    free = fs.availableBytes
                } catch (e: IllegalArgumentException) {
                    // Unmounted between the state check and here.
                }
            }
            Volume(
                id = id,
                label = volume.getDescription(context),
                kind = when {
                    volume.isPrimary -> VolumeKind.INTERNAL
                    volume.isRemovable && id in appRoots -> VolumeKind.SD_CARD
                    volume.isRemovable -> VolumeKind.USB
                    else -> VolumeKind.OTHER
                },
                path = path,
                mounted = mounted,
                readOnly = state == Environment.MEDIA_MOUNTED_READ_ONLY,
                total = total,
                free = free,
            )
        }
    }

    private fun pathOf(volume: StorageVolume, id: String, appRoots: Map<String, String>): String? {
        if (Build.VERSION.SDK_INT >= 30) return volume.directory?.absolutePath
        appRoots[id]?.let { return it }
        if (volume.isPrimary) return legacyPrimary()
        // A card without an app folder yet still mounts under its UUID on these versions.
        return volume.uuid?.let { "/storage/$it" }?.takeIf { File(it).isDirectory }
    }

    private fun primaryPath(): String? =
        if (Build.VERSION.SDK_INT >= 30) storage.primaryStorageVolume.directory?.absolutePath else legacyPrimary()

    @Suppress("DEPRECATION") // The only path API for the primary volume before Android 11.
    private fun legacyPrimary(): String = Environment.getExternalStorageDirectory().absolutePath

    private fun idOf(volume: StorageVolume): String =
        if (volume.isPrimary) "primary" else volume.uuid ?: volume.getDescription(context)

    private fun walk(
        from: FsNode,
        cancel: Cancel,
        maxVisited: Int,
        maxHits: Int,
        onBatch: (List<FsNode>) -> Unit,
        match: (FsNode) -> Boolean,
    ): SearchOutcome {
        val queue = ArrayDeque<FsNode>()
        queue.addLast(from)
        var visited = 0
        var hits = 0
        var truncated = false
        val batch = ArrayList<FsNode>()
        var flushed = SystemClock.uptimeMillis()
        fun flush() {
            if (batch.isNotEmpty()) {
                onBatch(ArrayList(batch))
                batch.clear()
            }
            flushed = SystemClock.uptimeMillis()
        }
        walk@ while (queue.isNotEmpty()) {
            if (cancel.cancelled) {
                truncated = true
                break
            }
            val dir = queue.removeFirst()
            // An unreadable folder is passed over; the rest of the walk still counts.
            val children = try {
                from.backend.children(dir, cancel)
            } catch (e: FsException) {
                if (e.reason == FsException.Reason.CANCELLED) {
                    truncated = true
                    break
                }
                continue
            } catch (e: IOException) {
                continue
            } catch (e: RuntimeException) {
                continue
            }
            for (child in children) {
                if (visited >= maxVisited || hits >= maxHits) {
                    truncated = true
                    break@walk
                }
                visited++
                if (match(child)) {
                    hits++
                    batch += child
                }
                // Kernel file systems hold thousands of entries that are not files anyone looks for.
                if (child.isDir && child.ref !in PSEUDO_FS) queue.addLast(child)
                if (batch.size >= BATCH_HITS || (batch.isNotEmpty() && SystemClock.uptimeMillis() - flushed >= BATCH_MS)) flush()
            }
        }
        flush()
        return SearchOutcome(visited, hits, truncated)
    }

    private fun changed() {
        snapshot = null
        for (l in listeners) l()
    }

    private fun register() {
        if (Build.VERSION.SDK_INT >= 30) {
            callback = VolumeCallback.register(storage, context) { changed() }
            return
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = changed()
        }
        receiver = r
        // Protected system broadcasts, delivered on the main thread through the handler.
        context.registerReceiver(r, filter, null, main)
    }

    private fun unregister() {
        callback?.let { VolumeCallback.unregister(storage, it) }
        callback = null
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * Android 11+ volume callbacks in a class of their own, so the verifier never meets
     * StorageVolumeCallback inside AndroidStorage on older versions.
     */
    private object VolumeCallback {
        fun register(storage: StorageManager, context: Context, onChange: () -> Unit): Any? {
            if (Build.VERSION.SDK_INT < 30) return null
            val cb = object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) = onChange()
            }
            storage.registerStorageVolumeCallback(context.mainExecutor, cb)
            return cb
        }

        fun unregister(storage: StorageManager, cb: Any) {
            if (Build.VERSION.SDK_INT < 30) return
            runCatching { storage.unregisterStorageVolumeCallback(cb as StorageManager.StorageVolumeCallback) }
        }
    }

    companion object {
        private const val TAG = "LxFiles"
        private const val SNAPSHOT_MS = 2_000L
        /** What a package name may contain before it reaches the shell. */
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")
        private val PSEUDO_FS = setOf("/proc", "/sys", "/dev", "/acct", "/config", "/d", "/debug_ramdisk")
        private const val BATCH_HITS = 50
        private const val BATCH_MS = 250L
        private val STANDARD = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
        )

        // Holds the application context only, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AndroidStorage? = null

        /** True when LX may reach all of shared storage through paths; asks Android only, builds no catalog. */
        fun allFilesAccess(context: Context): Boolean = when {
            Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> false
            // Android 10 without the legacy view cannot reach shared storage through paths.
            Build.VERSION.SDK_INT == 29 -> Environment.isExternalStorageLegacy()
            else -> true
        }

        /** The process-wide catalog; [context] may be any context, only its application context is kept. */
        fun get(context: Context): AndroidStorage = instance ?: synchronized(this) {
            instance ?: AndroidStorage(context)
        }
    }
}
