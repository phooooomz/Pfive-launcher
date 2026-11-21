// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.embed.EmbedProtocol
import com.lxlauncher.files.FsException.Reason
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The file system as the shell user sees it, through LX Bridge (lxdb runs as uid 2000, as `adb shell` does). It opens
 * what all-files access leaves closed: the root of the device, /system, Android/data and Android/obb of every app,
 * and /data/local/tmp. It is not root: other apps' private folders under /data/data stay closed to it as well.
 *
 * Every call runs a command and blocks, so call it off the main thread. Paths reach the shell only single-quoted.
 * A command's output is capped at 512 KB by lxdb, so file data moves in 256 KB pieces.
 */
internal class ShellFs(context: Context) {
    private val app = context.applicationContext
    private val client = EmbedClient.get(app)
    /** Where small files are read into, to be opened seekable (thumbnails seek back to the start). */
    private val spool = File(app.cacheDir, "shell-read")
    @Volatile private var shellGroups: Set<Int>? = null
    /** Writes in flight, by path: [commit] waits for the pump that carries them to the shell. */
    private val writes = ConcurrentHashMap<String, Pump>()

    /** One entry as `stat` reports it. [mode] holds the type and permission bits. */
    class Entry(val path: String, val name: String, val mode: Int, val size: Long, val mtimeMs: Long, val uid: Int, val gid: Int, val dev: Long) {
        val isDir get() = mode and S_IFMT == S_IFDIR
        val isFile get() = mode and S_IFMT == S_IFREG
        val isLink get() = mode and S_IFMT == S_IFLNK
    }

    /** lxdb runs and answers; without it every call fails with NO_ACCESS. */
    fun ready(): Boolean = client.serviceRunning

    // ---- reading the tree ----

    /** A folder's own entry ([self], null if it could not be read) and its children. */
    class Listing(val self: Entry?, val entries: List<Entry>)

    /** The entries of [dir], "." and ".." left out, with the folder's own entry, in one command. */
    fun list(dir: String, cancel: Cancel?): Listing {
        cancel?.check()
        val started = SystemClock.uptimeMillis()
        val r = run("cd -- ${q(dir)} || exit 3; stat -c '$STAT_FORMAT' -- . 2>/dev/null; " +
            "find . -mindepth 1 -maxdepth 1 -exec stat -c '$STAT_FORMAT' -- {} + 2>/dev/null; exit 0", LIST_TIMEOUT_MS)
        if (r.exit == 3) throw failure(r, dir)
        val all = parse(r.output, dir, keepSelf = true)
        val self = all.firstOrNull { it.name == "." }?.let { withPath(it, dir) }
        val out = if (r.output.size < OUTPUT_CAP) all.filter { it.name != "." } else listInBatches(dir, cancel)
        if (SystemClock.uptimeMillis() - started > SLOW_MS) Log.d(TAG, "listed ${out.size} entries of $dir in ${SystemClock.uptimeMillis() - started} ms")
        return Listing(self, out)
    }

    private fun withPath(e: Entry, path: String) =
        Entry(path, if (path == "/") "/" else path.substringAfterLast('/'), e.mode, e.size, e.mtimeMs, e.uid, e.gid, e.dev)

    /**
     * A folder whose listing passed lxdb's output cap: the names first, separated by NUL so any name survives, then
     * their metadata in batches that each fit under the cap.
     */
    private fun listInBatches(dir: String, cancel: Cancel?): List<Entry> {
        val names = run("cd -- ${q(dir)} || exit 3; find . -mindepth 1 -maxdepth 1 -print0", LIST_TIMEOUT_MS)
        if (names.exit == 3) throw failure(names, dir)
        val all = String(names.output, Charsets.UTF_8).split('\u0000').filter { it.length > 2 }.map { it.removePrefix("./") }
        if (names.output.size >= OUTPUT_CAP) Log.w(TAG, "$dir holds more names than one listing carries; showing ${all.size}")
        val out = ArrayList<Entry>(all.size)
        for (batch in all.chunked(STAT_BATCH)) {
            cancel?.check()
            val input = batch.joinToString("\u0000") { join(dir, it) }.toByteArray(Charsets.UTF_8)
            val r = run("xargs -0 stat -c '$STAT_FORMAT' -- 2>/dev/null; exit 0", LIST_TIMEOUT_MS, input)
            out += parse(r.output, dir)
        }
        return out
    }

    /** The entry at [path], or null when nothing is there (or it cannot be seen even as the shell). */
    fun stat(path: String): Entry? = statMany(listOf(path))[path]

    /** Entries for several paths in one command; a path with nothing there is missing from the map. */
    fun statMany(paths: List<String>): Map<String, Entry> {
        if (paths.isEmpty()) return emptyMap()
        // One line per path, in order; a path stat cannot read prints "-" so the lines stay in step.
        val script = paths.joinToString("; ") { "stat -c '$STAT_FORMAT' -- ${q(it)} 2>/dev/null || echo -" }
        val r = run(script, STAT_TIMEOUT_MS)
        val lines = String(r.output, Charsets.UTF_8).split('\n')
        val out = HashMap<String, Entry>()
        for ((i, path) in paths.withIndex()) {
            val line = lines.getOrNull(i) ?: break
            val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
            parse(line.toByteArray(Charsets.UTF_8), parent, keepSelf = true).firstOrNull()?.let { out[path] = withPath(it, path) }
        }
        return out
    }

    private fun parse(bytes: ByteArray, dir: String, keepSelf: Boolean = false): List<Entry> {
        val out = ArrayList<Entry>()
        for (line in String(bytes, Charsets.UTF_8).split('\n')) {
            val f = line.split('|', limit = 7)
            if (f.size < 7) continue
            val mode = f[0].toIntOrNull(16) ?: continue
            val raw = f[6]
            if (raw.isEmpty()) continue
            val name = raw.substringAfterLast('/').ifEmpty { raw }
            if (name == ".." || (name == "." && !keepSelf)) continue
            val path = if (raw.startsWith("/")) raw else join(dir, raw.removePrefix("./"))
            out += Entry(path, name, mode, f[1].toLongOrNull() ?: 0L, (f[2].toLongOrNull() ?: 0L) * 1000L,
                f[3].toIntOrNull() ?: -1, f[4].toIntOrNull() ?: -1, f[5].toLongOrNull() ?: 0L)
        }
        return out
    }

    /**
     * Whether the shell may write into [e] (a folder: add and remove entries; a file: change it), from its mode bits
     * against uid 2000 and the shell's groups. A read-only mount still refuses later, with its own reason.
     */
    fun writable(e: Entry): Boolean {
        val groups = groups()
        val bits = when {
            e.uid == SHELL_UID -> (e.mode shr 6) and 7
            e.gid in groups -> (e.mode shr 3) and 7
            else -> e.mode and 7
        }
        val w = bits and 2 != 0
        return if (e.isDir) w && bits and 1 != 0 else w
    }

    private fun groups(): Set<Int> {
        shellGroups?.let { return it }
        val r = runCatching { run("id -G", STAT_TIMEOUT_MS) }.getOrNull()
        val g = r?.text?.trim()?.split(' ')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()
        if (g.isNotEmpty()) shellGroups = g
        return g.ifEmpty { setOf(SHELL_UID) }
    }

    // ---- changing the tree ----

    fun mkdir(path: String) = check(run("mkdir -- ${q(path)}"), path)

    /** A new empty file; fails with EXISTS when the name is taken. */
    fun createFile(path: String) =
        check(run("if [ -e ${q(path)} ] || [ -L ${q(path)} ]; then echo 'File exists' >&2; exit 17; fi; : > ${q(path)}"), path)

    /**
     * Renames without replacing: a taken [to] fails with EXISTS, unless it is [from] itself, as "a.txt" and "A.txt"
     * are on a case-insensitive volume.
     */
    fun rename(from: String, to: String) = check(run(
        "if [ -e ${q(to)} ] || [ -L ${q(to)} ]; then " +
            "[ \"$(stat -c %d:%i -- ${q(from)})\" = \"$(stat -c %d:%i -- ${q(to)})\" ] || { echo 'File exists' >&2; exit 17; }; fi; " +
            "mv -- ${q(from)} ${q(to)}"), from)

    /** Puts [from] in place of the file [to] in one rename. */
    fun replace(from: String, to: String) = check(run("mv -f -- ${q(from)} ${q(to)}"), to)

    /** Deletes one file or link, or one empty folder. */
    fun remove(path: String) =
        check(run("if [ -d ${q(path)} ] && [ ! -L ${q(path)} ]; then rmdir -- ${q(path)}; else rm -f -- ${q(path)}; fi"), path)

    fun touch(path: String, millis: Long) {
        if (millis <= 0) return
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(millis))
        runCatching { run("TZ=UTC touch -m -d ${q(stamp)} -- ${q(path)}") }
    }

    /** Free bytes on the file system that holds [path], or -1. */
    fun freeBytes(path: String): Long {
        val r = runCatching { run("stat -f -c '%a %S' -- ${q(path)}", STAT_TIMEOUT_MS) }.getOrNull() ?: return -1
        if (r.exit != 0) return -1
        val parts = r.text.trim().split(' ')
        val blocks = parts.getOrNull(0)?.toLongOrNull() ?: return -1
        val size = parts.getOrNull(1)?.toLongOrNull() ?: return -1
        return blocks * size
    }

    // ---- file data ----

    /**
     * [path] opened for reading. A file up to 32 MB is read into LX's private cache first and opened from there, so
     * it can seek; the copy is unlinked as soon as it is open. A larger one streams through a pipe.
     */
    fun openRead(path: String, size: Long): ParcelFileDescriptor {
        if (size in 0..SPOOL_MAX) {
            spool.mkdirs()
            // createTempFile wants a prefix of three characters or more.
            val copy = File.createTempFile("read-", ".part", spool)
            try {
                FileOutputStream(copy).use { out -> pump(path) { chunk -> out.write(chunk) } }
                return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                copy.delete()
            }
        }
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread({
            // The reader learns of a failure through closeWithError, not as an early end of the file.
            val out = FileOutputStream(write.fileDescriptor)
            try {
                pump(path) { chunk -> out.write(chunk) }
                write.close()
            } catch (e: Exception) {
                runCatching { write.closeWithError(e.message ?: "read failed") }
            }
        }, "lx-shell-read").apply { isDaemon = true }.start()
        return read
    }

    /**
     * Reads [path] in pieces and hands each to [sink]; stops at a short piece, which is the end. dd reads in 64 KB
     * blocks: FUSE may cut a single larger read short, and a short block before the end would look like the end.
     */
    private fun pump(path: String, sink: (ByteArray) -> Unit) {
        var block = 0L
        while (true) {
            // status=none keeps dd quiet on success; on a failure its message comes with a non-zero exit.
            val r = run("dd if=${q(path)} bs=$DD_BLOCK skip=${block * CHUNK_BLOCKS} count=$CHUNK_BLOCKS status=none", DATA_TIMEOUT_MS)
            if (r.exit != 0) throw failure(r, path)
            if (r.output.isNotEmpty()) sink(r.output)
            if (r.output.size < CHUNK) return
            block++
        }
    }

    /**
     * [path] opened for writing, truncated. The bytes reach the file through a pipe and a pump thread that appends
     * them in pieces; [commit] waits for that thread and reports how it ended. The caller must call it after closing.
     */
    fun openWrite(path: String): ParcelFileDescriptor {
        check(run(": > ${q(path)}"), path)
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        val pump = Pump(path, read)
        writes[path]?.let { it.thread.interrupt() }
        writes[path] = pump
        pump.thread.start()
        return write
    }

    /** Waits for the writes to [path] to land. Throws what went wrong on the way. */
    fun commit(path: String) {
        val pump = writes.remove(path) ?: return
        pump.thread.join()
        pump.error?.let { throw it }
    }

    private inner class Pump(val path: String, private val source: ParcelFileDescriptor) {
        @Volatile var error: FsException? = null
        val thread = Thread({
            try {
                FileInputStream(source.fileDescriptor).use { input ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        var n = 0
                        // A full piece, or what is left at the end; a pipe hands over a few KB at a time.
                        while (n < buf.size) {
                            val got = input.read(buf, n, buf.size - n)
                            if (got < 0) break
                            n += got
                        }
                        if (n > 0) check(run("cat >> ${q(path)}", DATA_TIMEOUT_MS, buf.copyOf(n)), path)
                        if (n < buf.size) break
                    }
                }
                source.checkError()
            } catch (e: FsException) {
                error = e
            } catch (e: IOException) {
                error = FsErrors.of(e, path)
            } finally {
                runCatching { source.close() }
            }
        }, "lx-shell-write").apply { isDaemon = true }

        init {
            thread.isDaemon = true
        }
    }

    /**
     * Copies [from] to [to] (created or truncated) inside the shell, 16 MB per command so a cancel stops it within a
     * second or two and [onBytes] can show progress. Returns the bytes copied.
     */
    fun copy(from: String, to: String, cancel: Cancel?, onBytes: (Long) -> Unit): Long {
        check(run(": > ${q(to)}"), to)
        var block = 0L
        var total = 0L
        while (true) {
            cancel?.check()
            val at = block * COPY_BLOCKS
            val r = run("dd if=${q(from)} of=${q(to)} bs=$DD_BLOCK skip=$at seek=$at count=$COPY_BLOCKS conv=notrunc status=none " +
                "|| exit \$?; stat -c %s -- ${q(to)}", DATA_TIMEOUT_MS)
            if (r.exit != 0) throw failure(r, to)
            val size = r.text.trim().toLongOrNull() ?: throw FsErrors.io("copy stopped", to)
            val grew = size - total
            if (grew > 0) onBytes(grew)
            total = size
            if (grew < COPY_BLOCK) return total
            block++
        }
    }

    /**
     * A private copy of [path] for "Open with" and "Share": other apps get LX's own copy, since Android lets LX hand
     * out only files it can open itself. Copies older than a day are cleared on the next call.
     */
    fun copyForSharing(path: String, name: String): File {
        val root = File(app.cacheDir, "shared")
        root.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > DAY_MS) it.deleteRecursively() }
        val dir = File(root, java.lang.Long.toHexString(System.nanoTime())).apply { mkdirs() }
        val copy = File(dir, name)
        FileOutputStream(copy).use { out -> pump(path) { chunk -> out.write(chunk) } }
        return copy
    }

    // ---- running commands ----

    /** Runs [command] as the shell; NO_ACCESS when lxdb is not there. */
    fun run(command: String, timeoutMs: Int = OP_TIMEOUT_MS, input: ByteArray = ByteArray(0)): EmbedProtocol.ShellResult =
        client.shell(command, input, timeoutMs) ?: throw FsException(Reason.NO_ACCESS, "", IOException("LX Bridge is not running"))

    private fun check(r: EmbedProtocol.ShellResult, path: String) {
        if (r.exit != 0) throw failure(r, path)
    }

    /** The shell's words for a failure turned into a reason the window can explain. */
    private fun failure(r: EmbedProtocol.ShellResult, path: String): FsException {
        val msg = r.text
        val reason = when {
            "No such file" in msg || r.exit == 3 && "can't cd" in msg -> Reason.NOT_FOUND
            "File exists" in msg || r.exit == 17 -> Reason.EXISTS
            "Directory not empty" in msg -> Reason.EXISTS
            "Read-only file system" in msg -> Reason.READ_ONLY
            "No space left" in msg -> Reason.NO_SPACE
            "Permission denied" in msg || "Operation not permitted" in msg -> Reason.NO_ACCESS
            "File name too long" in msg || "Invalid argument" in msg -> Reason.BAD_NAME
            r.exit == 3 -> Reason.NO_ACCESS
            else -> Reason.IO
        }
        return FsException(reason, path, IOException("shell exit ${r.exit}: ${msg.take(200).trim()}"))
    }

    companion object {
        private const val TAG = "LxShellFs"
        const val SHELL_UID = 2000
        /** Type, size, mtime seconds, uid, gid, device, name; the name goes last so it may hold '|'. */
        private const val STAT_FORMAT = "%f|%s|%Y|%u|%g|%d|%n"
        private const val S_IFMT = 0xF000
        private const val S_IFDIR = 0x4000
        private const val S_IFREG = 0x8000
        private const val S_IFLNK = 0xA000
        /** lxdb keeps at most 512 KB of a command's output; a listing that reaches this was cut. */
        private const val OUTPUT_CAP = 512 * 1024
        private const val STAT_BATCH = 2_000
        /** dd's block: small enough that FUSE never splits one read. */
        private const val DD_BLOCK = 64 * 1024
        private const val CHUNK_BLOCKS = 4
        const val CHUNK = DD_BLOCK * CHUNK_BLOCKS
        private const val COPY_BLOCKS = 256
        private const val COPY_BLOCK = DD_BLOCK.toLong() * COPY_BLOCKS
        private const val SPOOL_MAX = 32L * 1024 * 1024
        private const val OP_TIMEOUT_MS = 20_000
        private const val STAT_TIMEOUT_MS = 8_000
        private const val LIST_TIMEOUT_MS = 30_000
        private const val DATA_TIMEOUT_MS = 60_000
        private const val SLOW_MS = 300L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** [s] as one single-quoted shell word: nothing inside it is expanded. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"
    }
}
