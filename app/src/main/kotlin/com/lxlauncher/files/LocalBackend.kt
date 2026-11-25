// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.lxlauncher.files.FsException.Reason
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * The file system seen through all-files access, with absolute paths as refs. Symbolic links are
 * listed as plain entries and never followed, so no walk can loop through them.
 *
 * With LX Bridge running, [shellFs] takes over where all-files access ends: Android/data and Android/obb of each
 * volume, and everything outside the volumes (the device root, /system, /data/local/tmp). Elsewhere LX's own calls
 * stay first, and the shell is only asked when Android refuses one of them.
 */
class LocalBackend internal constructor(
    private val context: Context,
    /** Mount directories of the storage volumes; [parent] stops there. */
    private val mounts: () -> Set<String>,
    private val shellFs: ShellFs? = null,
) : Backend, Located {
    override val key = "local"
    override val label = "/"
    /** Apps cannot change "/" itself, so the root carries no capabilities. */
    override val root = FsNode(this, "/", "/", true, 0, 0, FsNode.DIR_MIME, 0)

    private val storage = context.getSystemService(StorageManager::class.java)

    /** Per mount directory: asking StorageManager costs a binder call, and jobs ask for every file. */
    private val fatByMount = ConcurrentHashMap<String, Boolean>()

    override fun fatNames(dir: FsNode): Boolean {
        val mount = mounts().filter { under(it, dir.ref) }.maxByOrNull { it.length } ?: return false
        return fatByMount.getOrPut(mount) {
            val volume = try {
                storage.getStorageVolume(File(mount))
            } catch (e: RuntimeException) {
                null
            }
            volume != null && volume.isRemovable && !volume.isEmulated
        }
    }

    override fun children(dir: FsNode, cancel: Cancel?): List<FsNode> {
        val sh = shell()
        if (sh != null && closedToApps(dir.ref)) return shellChildren(sh, dir.ref, cancel)
        val names = File(dir.ref).list() ?: return sh?.let { shellChildren(it, dir.ref, cancel) } ?: throw listFailure(dir.ref)
        val parentWritable = access(dir.ref, OsConstants.W_OK or OsConstants.X_OK)
        // Read once for the whole folder, not once per file.
        val mountPoints = mounts()
        val out = ArrayList<FsNode>(names.size)
        for ((i, name) in names.withIndex()) {
            if (i and 255 == 0) cancel?.check()
            val path = join(dir.ref, name)
            // Gone between list() and lstat(): nothing to show.
            val st = lstat(path) ?: continue
            out += build(path, name, st, parentWritable, mountPoints, filesFromParent = true)
        }
        return out
    }

    override fun child(dir: FsNode, name: String): FsNode? {
        if (name.isEmpty() || '/' in name || name == "." || name == "..") return null
        return node(join(dir.ref, name))
    }

    /**
     * The folder above. A volume's own folder has none, so a chain stays rooted at its volume; paths outside the
     * volumes (reached through the shell) climb to "/".
     */
    override fun parent(node: FsNode): FsNode? {
        val path = node.ref
        if (path == "/" || path in mounts()) return null
        val up = File(path).parent ?: return null
        return node(up)
    }

    override fun refresh(node: FsNode): FsNode? = node(node.ref)

    override fun createDir(dir: FsNode, name: String): FsNode {
        val path = target(dir, name)
        elevated(path, { sh -> sh.mkdir(path) }) {
            try {
                Os.mkdir(path, MODE_DIR)
            } catch (e: ErrnoException) {
                throw failure(e, path)
            }
        }
        return node(path) ?: throw FsException(Reason.NOT_FOUND, path)
    }

    override fun createFile(dir: FsNode, name: String, mime: String): FsNode {
        val path = target(dir, name)
        elevated(path, { sh -> sh.createFile(path) }) {
            try {
                val fd = Os.open(path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or CLOEXEC, MODE_FILE)
                Os.close(fd)
            } catch (e: ErrnoException) {
                throw failure(e, path)
            }
        }
        return node(path) ?: throw FsException(Reason.NOT_FOUND, path)
    }

    override fun openRead(node: FsNode): ParcelFileDescriptor {
        val path = node.ref
        return elevated(path, { sh ->
            // As below: only a regular file opens. A FIFO or a device would block or never end.
            val e = sh.stat(path) ?: throw FsException(Reason.NOT_FOUND, path)
            if (!e.isFile) throw FsException(Reason.UNSUPPORTED, path)
            sh.openRead(path, e.size)
        }) {
            // stat follows a link on purpose: opening a link to a file is fine. A FIFO or a device would
            // block or never end, so only regular files open.
            val st = try {
                Os.stat(path)
            } catch (e: ErrnoException) {
                throw failure(e, path)
            }
            if (!OsConstants.S_ISREG(st.st_mode)) throw FsException(Reason.UNSUPPORTED, path)
            open(path, OsConstants.O_RDONLY or CLOEXEC, 0)
        }
    }

    /** Through the shell, the bytes land only in [commitWrite]; callers close the descriptor, then commit. */
    override fun openWrite(node: FsNode): ParcelFileDescriptor = elevated(node.ref, { sh -> sh.openWrite(node.ref) }) {
        open(
            node.ref,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW or CLOEXEC,
            MODE_FILE,
        )
    }

    override fun commitWrite(node: FsNode) {
        shellFs?.commit(node.ref)
    }

    override fun rename(node: FsNode, newName: String): FsNode {
        if (newName == node.name) return node
        val parent = File(node.ref).parent ?: throw FsException(Reason.NO_ACCESS, node.ref)
        if (node.ref in mounts()) throw FsException(Reason.NO_ACCESS, node.ref)
        val path = checkedTarget(parent, newName)
        elevated(node.ref, { sh -> sh.rename(node.ref, path) }) {
            val existing = lstat(path)
            // On a case-insensitive volume "a.txt" to "A.txt" finds the file itself; that is not a clash.
            if (existing != null && !sameFile(existing, lstat(node.ref))) throw FsException(Reason.EXISTS, path)
            try {
                Os.rename(node.ref, path)
            } catch (e: ErrnoException) {
                throw failure(e, node.ref)
            }
        }
        return node(path) ?: throw FsException(Reason.NOT_FOUND, path)
    }

    override fun delete(node: FsNode) {
        val path = node.ref
        if (path == "/" || path in mounts()) throw FsException(Reason.NO_ACCESS, path)
        elevated(path, { sh -> sh.remove(path) }) {
            if (lstat(path) == null) throw FsException(Reason.NOT_FOUND, path)
            try {
                // remove(3): unlink for a file or link, rmdir (empty folders only) for a folder.
                Os.remove(path)
            } catch (e: ErrnoException) {
                throw failure(e, path)
            }
        }
    }

    override fun moveInside(node: FsNode, destDir: FsNode, newName: String): FsNode? {
        if (destDir.backend.key != key) return null
        if (node.ref in mounts()) throw FsException(Reason.NO_ACCESS, node.ref)
        if (node.isDir && contains(node, destDir)) throw FsException(Reason.TOO_DEEP, destDir.ref)
        val path = checkedTarget(destDir.ref, newName)
        if (path == node.ref) return node
        val sh = shell()
        if (sh != null && (closedToApps(node.ref) || closedToApps(destDir.ref))) {
            // One rename where both sit on one file system; across two, the job copies and verifies instead.
            val found = sh.statMany(listOf(node.ref, destDir.ref))
            val src = found[node.ref] ?: throw FsException(Reason.NOT_FOUND, node.ref)
            val dest = found[destDir.ref] ?: throw FsException(Reason.NOT_FOUND, destDir.ref)
            if (src.dev != dest.dev) return null
            sh.rename(node.ref, path)
            return node(path) ?: throw FsException(Reason.NOT_FOUND, path)
        }
        val src = lstat(node.ref) ?: throw FsException(Reason.NOT_FOUND, node.ref)
        val dest = try {
            Os.stat(destDir.ref)
        } catch (e: ErrnoException) {
            throw failure(e, destDir.ref)
        }
        // Another mount needs a copy. Equal st_dev is not a promise (FUSE can refuse with EXDEV), so
        // EXDEV below also falls back to copying.
        if (src.st_dev != dest.st_dev) return null
        val existing = lstat(path)
        if (existing != null && !sameFile(existing, src)) throw FsException(Reason.EXISTS, path)
        try {
            Os.rename(node.ref, path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EXDEV) return null
            throw failure(e, node.ref)
        }
        return node(path) ?: throw FsException(Reason.NOT_FOUND, path)
    }

    override fun freeBytes(dir: FsNode): Long = try {
        StatFs(dir.ref).availableBytes
    } catch (e: IllegalArgumentException) {
        shell()?.freeBytes(dir.ref) ?: -1
    }

    override fun shareUri(node: FsNode): Uri {
        if (node.isDir) throw FsException(Reason.UNSUPPORTED, node.ref)
        val sh = shell()
        // Another app can only be given what LX itself can open: a file only the shell reaches goes as LX's copy.
        if (sh != null && (closedToApps(node.ref) || !access(node.ref, OsConstants.R_OK))) {
            return ShareProvider.uriFor(context, sh.copyForSharing(node.ref, node.name).absolutePath)
        }
        return ShareProvider.uriFor(context, node.ref)
    }

    override fun contains(outer: FsNode, inner: FsNode): Boolean {
        if (outer.backend.key != key || inner.backend.key != key) return false
        if (under(outer.ref, inner.ref)) return true
        // /sdcard and /storage/emulated/0 name the same folder; compare resolved paths too.
        val o = runCatching { File(outer.ref).canonicalPath }.getOrNull() ?: return false
        val i = runCatching { File(inner.ref).canonicalPath }.getOrNull() ?: return false
        return under(o, i)
    }

    override fun displayPath(node: FsNode): String = node.ref

    override fun location(node: FsNode): String = node.ref

    /** Node for an absolute path, or null when nothing is there. */
    fun node(path: String): FsNode? {
        val clean = normalize(path) ?: return null
        val sh = shell()
        if (sh != null && closedToApps(clean)) return shellNode(sh, clean)
        val st = try {
            Os.lstat(clean)
        } catch (e: ErrnoException) {
            // Refused is not missing: the shell may still see it.
            return if (sh != null && denied(e)) shellNode(sh, clean) else null
        }
        val name = if (clean == "/") "/" else clean.substringAfterLast('/')
        val parent = File(clean).parent
        val parentWritable = parent != null && access(parent, OsConstants.W_OK or OsConstants.X_OK)
        return build(clean, name, st, parentWritable)
    }

    /** True for a symbolic link, FIFO, socket or device: walks skip these instead of reading them. */
    internal fun isSpecial(node: FsNode): Boolean {
        if (node.mime == LINK_MIME) return true
        val st = lstat(node.ref)
        if (st == null) {
            val e = shell()?.let { runCatching { it.stat(node.ref) }.getOrNull() } ?: return false
            return !e.isFile && !e.isDir
        }
        return !OsConstants.S_ISREG(st.st_mode) && !OsConstants.S_ISDIR(st.st_mode)
    }

    /**
     * Where the link [node] leads, as a node, or null when it leads nowhere that can be seen. A link is never
     * followed by a walk or a delete; the window follows it only when it is opened.
     */
    fun linkTarget(node: FsNode): FsNode? {
        if (node.mime != LINK_MIME) return null
        val local = runCatching { File(node.ref).canonicalPath }.getOrNull()
        val resolved = local?.takeIf { it != node.ref } ?: shell()?.let { sh ->
            runCatching { sh.run("readlink -f -- ${ShellFs.q(node.ref)}").takeIf { it.exit == 0 }?.text?.trim() }.getOrNull()
        } ?: return null
        return node(resolved)?.takeIf { it.mime != LINK_MIME }
    }

    /** Puts [part] in place of the file [target] in one rename, so the old content stays until the swap. */
    internal fun replaceFile(part: FsNode, target: FsNode): FsNode {
        elevated(target.ref, { sh ->
            val e = sh.stat(target.ref)
            if (e != null && !e.isFile) throw FsException(Reason.EXISTS, target.ref)
            sh.replace(part.ref, target.ref)
        }) {
            val st = lstat(target.ref)
            if (st != null && !OsConstants.S_ISREG(st.st_mode)) throw FsException(Reason.EXISTS, target.ref)
            try {
                Os.rename(part.ref, target.ref)
            } catch (e: ErrnoException) {
                throw failure(e, target.ref)
            }
        }
        return node(target.ref) ?: throw FsException(Reason.NOT_FOUND, target.ref)
    }

    internal fun setModified(node: FsNode, millis: Long) {
        if (millis <= 0) return
        val sh = shell()
        if (sh != null && closedToApps(node.ref)) sh.touch(node.ref, millis)
        else if (!File(node.ref).setLastModified(millis)) sh?.touch(node.ref, millis)
    }

    /** True when both sit where only the shell writes, so the shell copies between them without LX in the middle. */
    internal fun shellCopies(src: FsNode, dst: FsNode): Boolean =
        src.backend === this && dst.backend === this && shell() != null && (closedToApps(src.ref) || closedToApps(dst.ref))

    /** Copies [src] over [dst] inside the shell, reporting bytes as they land. See [shellCopies]. */
    internal fun shellCopy(src: FsNode, dst: FsNode, cancel: Cancel?, onBytes: (Long) -> Unit): Long =
        (shell() ?: throw FsException(Reason.NO_ACCESS, dst.ref)).copy(src.ref, dst.ref, cancel, onBytes)

    // ---- the shell, where Android closes a path to apps ----

    /** The shell while lxdb runs; null otherwise, and then LX's own calls are all there is. */
    private fun shell(): ShellFs? = shellFs?.takeIf { it.ready() }

    /** True when LX Bridge can reach what LX alone cannot. */
    fun fullAccess(): Boolean = shell() != null

    /**
     * True for paths LX cannot reach on its own whatever it was granted: Android/data and Android/obb of a volume
     * (Android 11 and later), and everything outside the storage volumes.
     */
    internal fun closedToApps(path: String): Boolean {
        // LX's own folders open to LX, and the shell runs as another user that cannot read the private ones.
        if (ownPrivate.any { under(it, path) }) return false
        val mount = mounts().filter { under(it, path) }.maxByOrNull { it.length } ?: return true
        if (Build.VERSION.SDK_INT < 30) return false
        val rest = path.removePrefix(mount).trimStart('/')
        val pkg = context.packageName
        for (area in AREAS_CLOSED) {
            if (rest == "$area/$pkg" || rest.startsWith("$area/$pkg/")) return false
        }
        return AREAS_CLOSED.any { rest == it || rest.startsWith("$it/") }
    }

    /** LX's private data, under both names Android gives it, and its device-protected data. */
    private val ownPrivate: List<String> by lazy {
        val info = context.applicationInfo
        val legacy = File(android.os.Environment.getDataDirectory(), "data/${context.packageName}").path
        listOfNotNull(info.dataDir, legacy, info.deviceProtectedDataDir)
            .mapNotNull { normalize(it) }
            .distinct()
    }

    /**
     * Runs [local], or [viaShell] for a path closed to apps, or [viaShell] again when Android refused [local]. Without
     * the shell, [local] alone runs and its error stands.
     */
    private inline fun <T> elevated(path: String, viaShell: (ShellFs) -> T, local: () -> T): T {
        val sh = shell()
        if (sh != null && closedToApps(path)) return viaShell(sh)
        return try {
            local()
        } catch (e: FsException) {
            if (sh != null && e.reason == Reason.NO_ACCESS) viaShell(sh) else throw e
        }
    }

    private fun denied(e: ErrnoException) = e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM

    private fun shellChildren(sh: ShellFs, dir: String, cancel: Cancel?): List<FsNode> {
        val listing = sh.list(dir, cancel)
        val parentWritable = listing.self?.let { sh.writable(it) } ?: false
        val mountPoints = mounts()
        return listing.entries.map { shellBuild(sh, it, parentWritable, mountPoints) }
    }

    private fun shellNode(sh: ShellFs, path: String): FsNode? {
        val parent = File(path).parent
        val found = runCatching { sh.statMany(listOfNotNull(path, parent)) }.getOrNull() ?: return null
        val e = found[path] ?: return null
        val parentWritable = parent != null && found[parent]?.let { sh.writable(it) } == true
        return shellBuild(sh, e, parentWritable, mounts())
    }

    private fun shellBuild(sh: ShellFs, e: ShellFs.Entry, parentWritable: Boolean, mountPoints: Set<String>): FsNode {
        var caps = 0
        if (parentWritable && e.path != "/" && e.path !in mountPoints) caps = caps or Caps.DELETE or Caps.RENAME or Caps.MOVE
        if (e.isDir && sh.writable(e)) caps = caps or Caps.CREATE
        if (e.isFile && sh.writable(e)) caps = caps or Caps.WRITE
        return FsNode(
            backend = this,
            ref = e.path,
            name = e.name,
            isDir = e.isDir,
            size = if (e.isDir) 0 else e.size,
            modified = e.mtimeMs,
            mime = when {
                e.isDir -> FsNode.DIR_MIME
                e.isLink -> LINK_MIME
                else -> FileTypes.mimeOf(e.name)
            },
            caps = caps,
        )
    }

    /**
     * [filesFromParent]: in a listing, a regular file in a folder LX may write to is writable too (shared storage
     * gives no file its own lock), which spares one access() through FUSE per file: a folder of 10,000 files took
     * about 20 seconds to list. Folders are still asked one by one, since Android/data and Android/obb are closed
     * inside an open parent.
     */
    private fun build(path: String, name: String, st: StructStat, parentWritable: Boolean,
                      mountPoints: Set<String> = mounts(), filesFromParent: Boolean = false): FsNode {
        val mode = st.st_mode
        val dir = OsConstants.S_ISDIR(mode)
        val regular = OsConstants.S_ISREG(mode)
        var caps = 0
        if (parentWritable && path !in mountPoints) caps = caps or Caps.DELETE or Caps.RENAME or Caps.MOVE
        if (dir && access(path, OsConstants.W_OK or OsConstants.X_OK)) caps = caps or Caps.CREATE
        if (regular && (if (filesFromParent && parentWritable) true else access(path, OsConstants.W_OK))) caps = caps or Caps.WRITE
        val modified = if (Build.VERSION.SDK_INT >= 27) {
            st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1_000_000
        } else {
            st.st_mtime * 1000
        }
        return FsNode(
            backend = this,
            ref = path,
            name = name,
            isDir = dir,
            size = if (dir) 0 else st.st_size,
            modified = modified,
            mime = when {
                dir -> FsNode.DIR_MIME
                OsConstants.S_ISLNK(mode) -> LINK_MIME
                else -> FileTypes.mimeOf(name)
            },
            caps = caps,
        )
    }

    private fun open(path: String, flags: Int, mode: Int): ParcelFileDescriptor {
        val fd: FileDescriptor = try {
            Os.open(path, flags, mode)
        } catch (e: ErrnoException) {
            throw failure(e, path)
        }
        try {
            return ParcelFileDescriptor.dup(fd)
        } finally {
            try {
                Os.close(fd)
            } catch (ignored: ErrnoException) {
            }
        }
    }

    private fun target(dir: FsNode, name: String): String = checkedTarget(dir.ref, name, fatNames(dir))

    private fun checkedTarget(dir: String, name: String, fat: Boolean = false): String {
        if (FileTypes.nameProblem(name, fat)) throw FsException(Reason.BAD_NAME, name)
        return join(dir, name)
    }

    /** Why list() returned null: gone with its volume, missing, unreadable, or not a folder. */
    private fun listFailure(path: String): FsException {
        val st = lstat(path)
        return when {
            st == null && mounts().none { under(it, path) } && path.startsWith("/storage/") -> FsException(Reason.GONE, path)
            st == null -> FsException(Reason.NOT_FOUND, path)
            !OsConstants.S_ISDIR(st.st_mode) -> FsException(Reason.NOT_FOUND, path)
            !access(path, OsConstants.R_OK) -> FsException(Reason.NO_ACCESS, path)
            else -> FsException(Reason.IO, path)
        }
    }

    /** A missing path on a volume that is no longer mounted means the volume went, not the file. */
    private fun failure(e: ErrnoException, path: String): FsException {
        val f = FsErrors.of(e, path)
        val volumeGone = path.startsWith("/storage/") && mounts().none { under(it, path) }
        return if (f.reason == Reason.NOT_FOUND && volumeGone) FsException(Reason.GONE, path, e) else f
    }

    private fun lstat(path: String): StructStat? = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        null
    }

    private fun access(path: String, mode: Int): Boolean = try {
        Os.access(path, mode)
    } catch (e: ErrnoException) {
        false
    }

    private fun sameFile(a: StructStat?, b: StructStat?): Boolean =
        a != null && b != null && a.st_dev == b.st_dev && a.st_ino == b.st_ino

    companion object {
        /** What a symbolic link is listed as; it opens its target only when the window follows it. */
        const val LINK_MIME = "inode/symlink"

        /** Folders on a volume that Android 11 and later close to apps, except each app's own folder inside them. */
        private val AREAS_CLOSED = arrayOf("Android/data", "Android/obb")

        /** 0777 and 0666; the process umask trims them. */
        private const val MODE_DIR = 511
        private const val MODE_FILE = 438

        /**
         * O_CLOEXEC is public from API 27. On 8.0 the raw descriptor goes without it; it is closed
         * right after ParcelFileDescriptor.dup copies it.
         */
        private val CLOEXEC = if (Build.VERSION.SDK_INT >= 27) OsConstants.O_CLOEXEC else 0

        internal fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

        internal fun under(outer: String, inner: String): Boolean =
            outer == "/" || inner == outer || inner.startsWith("$outer/")

        /** Absolute path without "//", "." or a trailing slash; null for a relative path or "..". */
        internal fun normalize(path: String): String? {
            if (!path.startsWith("/")) return null
            val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) return null
            return "/" + parts.joinToString("/")
        }
    }
}
