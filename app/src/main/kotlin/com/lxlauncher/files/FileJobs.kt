// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.lxlauncher.files.FsException.Reason
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide [JobEngine]. Two workers, so a delete does not wait behind a long copy.
 * Progress reaches listeners on the main thread at most ten times a second, plus once at the end.
 *
 * Every file a job writes starts under a hidden part name, recorded in the [Journal] before data
 * goes in, and gets its real name only after its size matched the source. A job that is cancelled
 * or fails removes its unfinished part; whatever it already finished stays and is reported. A
 * destination that cannot rename gets files under their final names instead, and no replacing.
 */
object FileJobs : JobEngine {
    private const val TAG = "LxJobs"
    private const val PROGRESS_MS = 100L
    private const val BUFFER = 256 * 1024
    /** Bytes the kernel copies between two Cancel checks. */
    private const val TRANSFER_CHUNK = 4L * 1024 * 1024
    private const val MIB = 1024L * 1024
    /** Kept free on the destination while extracting. */
    private const val EXTRACT_RESERVE = 64 * MIB
    /** Asked for on top of each file's own size. */
    private const val FILE_RESERVE = 32 * MIB
    private const val MAX_EXTRACT = 256L * 1024 * MIB
    private const val MAX_ENTRIES = 100_000L
    /** 1000:1, times 100 as the native limits expect. */
    private const val MAX_RATIO_X100 = 100_000L
    private const val RATIO_GRACE = MIB
    private const val MAX_UNSAFE_NAMES = 100
    private const val MODE_DIR = 493 // 0755
    private const val MODE_FILE = 420 // 0644
    /** Native progress arrives about once per MiB, so a limit counts as reached this close to it. */
    private const val LIMIT_SLACK = 2 * MIB
    private const val JOURNAL = "fm-journal.txt"

    /** Failures that would repeat for every remaining item; they end the job. */
    private val FATAL = setOf(Reason.CANCELLED, Reason.NO_SPACE, Reason.GONE, Reason.READ_ONLY)

    private val workers = AtomicInteger(0)
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { r ->
        Thread(r, "lx-files-" + workers.incrementAndGet()).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())
    /** Where listeners are called: the main thread. Tests run them in place. */
    @Volatile internal var mainThread: Executor = Executor { main.post(it) }
    private val ids = AtomicInteger(0)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val listeners = CopyOnWriteArrayList<JobListener>()
    // The application context, which lives as long as the process and this object.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var app: Context? = null
    private val journalLock = Any()
    @Volatile private var journalRef: Journal? = null

    /**
     * Call once per process before the first job; any thread will do. Removes, on a worker, what
     * jobs of an earlier process left unfinished.
     */
    fun init(context: Context) {
        val ctx = context.applicationContext
        synchronized(this) {
            if (app != null) return
            app = ctx
        }
        // Reading the journal touches the disk, and callers are usually on the main thread.
        pool.execute {
            try {
                journal(ctx)
            } catch (t: Throwable) {
                Log.w(TAG, "journal not opened (${t.javaClass.simpleName})")
            }
        }
    }

    /** Opens the journal once and schedules removal of what an earlier process left unfinished. */
    private fun journal(ctx: Context): Journal {
        journalRef?.let { return it }
        synchronized(journalLock) {
            journalRef?.let { return it }
            val j = Journal(File(ctx.filesDir, JOURNAL))
            // Taken before any job can run, so the cleanup never sees parts this process adds.
            val leftovers = j.leftovers()
            journalRef = j
            if (leftovers.isNotEmpty()) {
                pool.execute {
                    try {
                        j.cleanup(ctx, leftovers)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cleanup stopped (${t.javaClass.simpleName})")
                    }
                }
            }
            return j
        }
    }

    override fun copy(sources: List<FsNode>, destDir: FsNode, move: Boolean, resolver: ConflictResolver): Int =
        start(if (move) JobKind.MOVE else JobKind.COPY, resolver) { copyJob(sources, destDir, move) }

    override fun delete(nodes: List<FsNode>): Int = start(JobKind.DELETE, null) { deleteJob(nodes) }

    override fun compress(sources: List<FsNode>, destDir: FsNode, name: String, format: ArchiveFormat, resolver: ConflictResolver): Int =
        start(JobKind.COMPRESS, resolver) { compressJob(sources, destDir, name, format) }

    override fun extract(archive: FsNode, destDir: FsNode, ownFolder: Boolean, resolver: ConflictResolver): Int =
        start(JobKind.EXTRACT, resolver) { extractJob(archive, destDir, ownFolder) }

    override fun test(archive: FsNode): Int = start(JobKind.TEST, null) { testJob(archive) }

    override fun cancel(id: Int) {
        jobs[id]?.cancel?.cancel()
    }

    override fun running(): List<JobProgress> = jobs.values.sortedBy { it.id }.map { it.snapshot() }

    override fun addListener(listener: JobListener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: JobListener) {
        listeners.remove(listener)
    }

    private fun start(kind: JobKind, resolver: ConflictResolver?, work: Run.() -> Unit): Int {
        val ctx = app ?: throw IllegalStateException("FileJobs.init was not called")
        val job = Job(ids.incrementAndGet(), kind)
        jobs[job.id] = job
        pool.execute {
            val result = try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                Run(ctx, journal(ctx), job, resolver).execute(work)
            } catch (t: Throwable) {
                // Run.execute catches everything itself; this is a journal that could not be opened.
                Log.w(TAG, "job ${job.id} not started (${t.javaClass.simpleName})")
                JobResult(job.id, kind, false, false, 0, 0, 0, FsErrors.of(t, ""), emptyList(), null)
            } finally {
                jobs.remove(job.id)
            }
            val last = job.snapshot()
            mainThread.execute {
                for (l in listeners) l.onProgress(last)
                for (l in listeners) l.onFinished(result)
            }
        }
        return job.id
    }

    /**
     * The extraction limits come from free space when that is the smaller figure. Reaching them
     * then means the destination is full, not that the archive is bad. [spaceCap] is -1 when the
     * limits did not come from free space; [produced] counts bytes written, [declared] the sizes
     * the entries so far claim.
     */
    internal fun limitIsSpace(spaceCap: Long, produced: Long, declared: Long): Boolean =
        spaceCap >= 0 && (produced + LIMIT_SLACK >= spaceCap || declared > spaceCap)

    private class Job(val id: Int, val kind: JobKind) {
        val cancel = Cancel()
        // Written by the worker only, read from the main thread.
        @Volatile var doneBytes = 0L
        @Volatile var totalBytes = -1L
        @Volatile var doneItems = 0
        @Volatile var totalItems = 0
        @Volatile var current = ""
        var lastPost = 0L

        fun snapshot() = JobProgress(id, kind, doneBytes, totalBytes, doneItems, totalItems, current)
    }

    /** A source walked before the job writes anything: the snapshot it works from. */
    private class Item(val node: FsNode, val parent: FsNode?, val children: List<Item>?, val special: Boolean) {
        val count: Int = 1 + (children?.sumOf { it.count } ?: 0)
        val bytes: Long = when {
            children != null -> children.sumOf { it.bytes }
            special -> 0
            else -> maxOf(node.size, 0)
        }
    }

    /** The source and the item it would replace turned out to be one file; nothing was changed. */
    private class SameFile : Exception()

    private class ArchiveHandle(
        val handle: Long,
        val pfd: ParcelFileDescriptor,
        val format: ArchiveFormat,
        val cache: File?,
        val cacheEntry: String?,
    )

    private class Run(
        val ctx: Context,
        val journal: Journal,
        val job: Job,
        val resolver: ConflictResolver?,
    ) {
        val cancel = job.cancel
        var done = 0
        var skipped = 0
        var failed = 0
        var error: FsException? = null
        var cancelled = false
        val created = ArrayList<FsNode>()
        var archive: ArchiveInfo? = null
        private var allChoice: ConflictChoice? = null
        private val unsafe = ArrayList<String>()
        /** Backends where a part could not be renamed into place; this job writes there without parts. */
        private val noRename = HashSet<String>()
        /** One copy buffer for the whole job, not a new 256 KB array for every file. */
        private val buffer by lazy { ByteArray(BUFFER) }

        fun execute(work: Run.() -> Unit): JobResult {
            try {
                work()
            } catch (e: FsException) {
                if (e.reason == Reason.CANCELLED) cancelled = true else if (error == null) error = e
            } catch (t: Throwable) {
                // Errors too, such as UnsatisfiedLinkError: the job fails, the launcher stays up.
                Log.w(TAG, "job ${job.id} stopped (${t.javaClass.simpleName})")
                if (error == null) error = FsErrors.of(t, "")
            }
            val ok = !cancelled && error == null && failed == 0
            return JobResult(job.id, job.kind, ok, cancelled, done, skipped, failed, if (ok) null else error, created.toList(), archive)
        }

        // ---- progress and bookkeeping ----

        fun progress(force: Boolean = false) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - job.lastPost < PROGRESS_MS) return
            job.lastPost = now
            val snap = job.snapshot()
            mainThread.execute { for (l in listeners) l.onProgress(snap) }
        }

        fun working(node: FsNode) {
            job.current = node.name
            progress()
        }

        fun countDone(items: Int, bytes: Long = 0) {
            done += items
            job.doneItems += items
            job.doneBytes += bytes
            progress()
        }

        fun countSkipped(items: Int, bytes: Long) {
            skipped += items
            job.doneItems += items
            job.doneBytes += bytes
            progress()
        }

        /** Records a failed item and carries on, unless the failure would repeat for everything left. */
        fun countFailed(e: Exception, path: String, items: Int, bytes: Long) {
            val f = FsErrors.of(e, path)
            if (f.reason in FATAL) throw f
            failed += items
            if (error == null) error = f
            job.doneItems += items
            job.doneBytes += bytes
            Log.w(TAG, "job ${job.id}: item failed (${f.reason})")
            progress()
        }

        fun recordUnsafe(name: String) {
            skipped++
            if (unsafe.size < MAX_UNSAFE_NAMES) unsafe += name
        }

        /** A problem that did not stop the item, such as a hidden leftover that could not be removed. */
        fun note(e: FsException) {
            if (error == null) error = e
            Log.w(TAG, "job ${job.id}: ${e.reason} left behind")
        }

        /** [e] naming [path] as the place it concerns, with its reason kept. */
        fun relabel(e: Exception, path: String): FsException = FsException(FsErrors.of(e, path).reason, path, e)

        /** Removes an unfinished part or staging folder. Its journal entry goes only once it is really gone. */
        fun discard(node: FsNode, entry: String?) {
            val gone = try {
                if (node.isDir) Trees.deleteTree(node, null) else node.backend.delete(node)
                true
            } catch (e: Exception) {
                try {
                    node.backend.refresh(node) == null
                } catch (e2: Exception) {
                    false
                }
            }
            if (gone) entry?.let { journal.remove(it) } else Log.w(TAG, "job ${job.id}: unfinished item kept for the next start")
        }

        /**
         * A new hidden part in [destDir], or null when the destination cannot rename it into place,
         * as with a provider without rename. Learned once per backend and job.
         */
        fun newPart(destDir: FsNode, name: String, dir: Boolean): FsNode? {
            val dest = destDir.backend
            if (dest.key in noRename) return null
            val part = if (dir) dest.createDir(destDir, name) else dest.createFile(destDir, name, FileTypes.OCTET_STREAM)
            if (part.can(Caps.RENAME)) return part
            noRename += dest.key
            discard(part, Journal.entryOf(part)?.also { journal.add(it) })
            return null
        }

        /**
         * Gives [incoming], complete under a hidden name in [destDir], the name [name] that
         * [existing] holds. Nothing is deleted before [incoming] has the name: the old item first
         * steps aside under a hidden ".lxkeep" name and returns if the swap fails. Not cancellable.
         */
        fun swap(incoming: FsNode, destDir: FsNode, name: String, existing: FsNode, source: FsNode? = null): FsNode {
            val dest = destDir.backend
            if (source == null && dest is LocalBackend && !incoming.isDir && !existing.isDir) return dest.replaceFile(incoming, existing)
            val aside = try {
                dest.rename(existing, FileTypes.keptName(existing.name))
            } catch (e: Exception) {
                throw relabel(e, display(existing))
            }
            if (source != null && movedWith(source)) {
                // [source] went aside with [existing]: one file seen through two backends.
                try {
                    dest.rename(aside, existing.name)
                } catch (e: Exception) {
                    throw relabel(e, display(aside))
                }
                throw SameFile()
            }
            val placed = try {
                dest.rename(incoming, name)
            } catch (e: Exception) {
                val back = try {
                    dest.rename(aside, existing.name)
                    true
                } catch (e2: Exception) {
                    false
                }
                // When the old item could not return, the error names where it waits.
                throw relabel(e, if (back) display(existing) else display(aside))
            }
            try {
                if (aside.isDir) Trees.deleteTree(aside, null) else dest.delete(aside)
            } catch (e: Exception) {
                // The new item is in place. What is left of the old one stays hidden; the result says where.
                note(relabel(e, display(aside)))
            }
            return placed
        }

        /** True when [source] no longer answers under its name, after the item it was compared with was renamed. */
        private fun movedWith(source: FsNode): Boolean {
            val now = try {
                source.backend.refresh(source)
            } catch (e: Exception) {
                return false
            }
            return now == null || now.name != source.name
        }

        fun display(node: FsNode): String = try {
            node.backend.displayPath(node)
        } catch (e: Exception) {
            node.name
        }

        /** Asks the window, unless an earlier answer applies to all. CANCEL stops the job here. */
        fun choose(existing: FsNode, name: String, incomingIsDir: Boolean, incomingSize: Long, incomingModified: Long): ConflictChoice {
            allChoice?.let { return it }
            val r = resolver ?: return ConflictChoice.SKIP
            val (choice, all) = r.resolve(
                Conflict(name, existing.isDir, incomingIsDir, existing.size, incomingSize, existing.modified, incomingModified),
                cancel,
            )
            if (choice == ConflictChoice.CANCEL) {
                cancel.cancel()
                throw FsException(Reason.CANCELLED)
            }
            cancel.check()
            if (all) allChoice = choice
            return choice
        }

        /** "name (2)", "name (3)" and so on, the first one [dir] does not hold. */
        fun freeName(dir: FsNode, name: String): String {
            for (n in 2..9_999) {
                cancel.check()
                val candidate = NativeArchive.numberedName(name, n)
                if (dir.backend.child(dir, candidate) == null) return candidate
            }
            throw FsException(Reason.EXISTS, name)
        }

        fun plan(node: FsNode, parent: FsNode?, depth: Int, skipParts: Boolean): Item {
            cancel.check()
            if (depth > Trees.MAX_DEPTH) throw FsException(Reason.TOO_DEEP, display(node))
            if (!node.isDir) return Item(node, parent, null, Trees.isSpecial(node))
            working(node)
            var kids = Trees.listAll(node, cancel)
            // Another job's unfinished part is not the user's file.
            if (skipParts) kids = kids.filterNot { FileTypes.isLeftoverName(it.name) }
            return Item(node, parent, kids.map { plan(it, node, depth + 1, skipParts) }, false)
        }

        // ---- copy and move ----

        fun copyJob(sources: List<FsNode>, destDir: FsNode, move: Boolean) {
            for (s in sources) {
                // Across backends too: a granted folder and a local path can be one place.
                if (s.isDir && Aliases.inside(s, destDir)) throw FsException(Reason.TOO_DEEP, display(destDir))
            }
            val plans = sources.map { plan(it, null, 0, skipParts = true) }
            job.totalItems = plans.sumOf { it.count }
            job.totalBytes = plans.sumOf { it.bytes }
            progress(force = true)
            val transfer = Transfer(move, counting = true)
            for (p in plans) {
                cancel.check()
                transfer.place(p, destDir, fresh = false, deferred = null)?.let { created += it }
            }
        }

        /**
         * Copies or moves planned items into a folder. With [counting] false the items were
         * already counted (the extracted entries) and only skips and failures are added.
         */
        inner class Transfer(private val move: Boolean, private val counting: Boolean) {
            private fun finished(items: Int, bytes: Long = 0) {
                if (counting) countDone(items, bytes) else progress()
            }

            /** Puts [item] into [destDir] under its own name, asking when the name is taken. */
            fun place(item: Item, destDir: FsNode, fresh: Boolean, deferred: MutableList<Item>?): FsNode? {
                cancel.check()
                val src = item.node
                working(src)
                if (item.special) {
                    countSkipped(item.count, item.bytes)
                    return null
                }
                val bytesBefore = job.doneBytes
                try {
                    val existing = if (fresh) null else destDir.backend.child(destDir, src.name)
                    if (existing != null && Aliases.same(existing, src)) {
                        // Pasted where it already is, perhaps seen through another backend: a copy
                        // keeps both, a move has nothing to do. Replacing would delete the source.
                        if (move) {
                            countSkipped(item.count, item.bytes)
                            return null
                        }
                        return transfer(item, destDir, freeName(destDir, src.name), deferred)
                    }
                    if (existing == null) return transfer(item, destDir, src.name, deferred)
                    return when (choose(existing, src.name, src.isDir, src.size, src.modified)) {
                        ConflictChoice.SKIP -> {
                            countSkipped(item.count, item.bytes)
                            null
                        }
                        ConflictChoice.KEEP_BOTH -> transfer(item, destDir, freeName(destDir, src.name), deferred)
                        else -> replace(item, destDir, existing, deferred)
                    }
                } catch (e: SameFile) {
                    // Found out during the swap; like a move onto itself, there is nothing to do.
                    job.doneBytes = bytesBefore
                    countSkipped(item.count, item.bytes)
                    return null
                } catch (e: Exception) {
                    if (e is FsException && e.reason in FATAL) throw e
                    // A file that failed half way: its bytes count once, not twice.
                    if (item.children == null) job.doneBytes = bytesBefore
                    countFailed(e, display(src), item.count, item.bytes)
                    return null
                }
            }

            /** [name] is free in [destDir]. */
            private fun transfer(item: Item, destDir: FsNode, name: String, deferred: MutableList<Item>?): FsNode {
                val src = item.node
                if (move && deferred == null && src.backend.key == destDir.backend.key) {
                    moveInside(item, destDir, name)?.let {
                        finished(item.count, item.bytes)
                        return it
                    }
                }
                if (!src.isDir) return copyFile(item, destDir, name, null, deferred)
                val dir = destDir.backend.createDir(destDir, name)
                finished(1)
                for (child in item.children.orEmpty()) place(child, dir, fresh = true, deferred = deferred)
                if (move) removeSourceDir(item, deferred)
                return dir
            }

            private fun replace(item: Item, destDir: FsNode, existing: FsNode, deferred: MutableList<Item>?): FsNode? {
                val src = item.node
                if (src.isDir && existing.isDir) {
                    // REPLACE on two folders merges them; each clash inside is asked about on its own.
                    finished(1)
                    for (child in item.children.orEmpty()) place(child, existing, fresh = false, deferred = deferred)
                    if (move) removeSourceDir(item, deferred)
                    return existing
                }
                // A folder that holds the incoming file cannot make way for it: removing it removes the file.
                if (existing.isDir && Aliases.inside(existing, src)) throw FsException(Reason.TOO_DEEP, display(existing))
                if (move && deferred == null && src.backend.key == destDir.backend.key) {
                    moveSwap(item, destDir, existing)?.let { return it }
                }
                if (!src.isDir) return copyFile(item, destDir, src.name, existing, deferred)
                return folderOverFile(item, destDir, existing, deferred)
            }

            private fun moveInside(item: Item, destDir: FsNode, name: String): FsNode? {
                val src = item.node
                val backend = src.backend
                val parent = item.parent
                return if (backend is SafBackend && parent != null) {
                    backend.moveWithParent(src, parent.ref, destDir, name)
                } else {
                    backend.moveInside(src, destDir, name)
                }
            }

            /**
             * Same-backend move over an existing item: moves under a hidden ".lxkeep" name, then
             * swaps. Moved items are not journaled: they are the user's only copy.
             */
            private fun moveSwap(item: Item, destDir: FsNode, existing: FsNode): FsNode? {
                val parked = moveInside(item, destDir, FileTypes.keptName(item.node.name)) ?: return null
                try {
                    val final = swap(parked, destDir, item.node.name, existing)
                    finished(item.count, item.bytes)
                    return final
                } catch (e: Exception) {
                    // The moved item waits under its hidden name; the error names that place.
                    throw relabel(e, display(parked))
                }
            }

            /** Copies one file through a journaled part file and gives it its name once complete. */
            private fun copyFile(item: Item, destDir: FsNode, name: String, replacing: FsNode?, deferred: MutableList<Item>?): FsNode {
                val src = item.node
                val part = newPart(destDir, FileTypes.partName(name), dir = false)
                    ?: return copyDirect(item, destDir, name, replacing, deferred)
                val entry = Journal.entryOf(part)?.also { journal.add(it) }
                val final = try {
                    val copied = copyBytes(src, part, durable = move)
                    verify(src, part, copied)
                    when {
                        replacing == null -> destDir.backend.rename(part, name)
                        // A move between backends must not replace its own source: the swap checks.
                        move && src.backend.key != destDir.backend.key -> swap(part, destDir, name, replacing, source = src)
                        else -> swap(part, destDir, name, replacing)
                    }
                } catch (t: Throwable) {
                    discard(part, entry)
                    throw t
                }
                entry?.let { journal.remove(it) }
                finishCopy(item, final, deferred)
                return final
            }

            /**
             * For a destination that cannot rename: the file is written under its final name and
             * removed again when the copy fails. Replacing is refused, because the old file would
             * have to go before the new one is complete.
             */
            private fun copyDirect(item: Item, destDir: FsNode, name: String, replacing: FsNode?, deferred: MutableList<Item>?): FsNode {
                val src = item.node
                val dest = destDir.backend
                if (replacing != null) throw FsException(Reason.UNSUPPORTED, display(replacing))
                val target = dest.createFile(destDir, name, FileTypes.OCTET_STREAM)
                try {
                    val copied = copyBytes(src, target, durable = move)
                    verify(src, target, copied)
                } catch (t: Throwable) {
                    try {
                        dest.delete(target)
                    } catch (ignored: Exception) {
                    }
                    throw t
                }
                val final = dest.refresh(target) ?: target
                finishCopy(item, final, deferred)
                return final
            }

            private fun finishCopy(item: Item, final: FsNode, deferred: MutableList<Item>?) {
                val dest = final.backend
                if (dest is LocalBackend) dest.setModified(final, item.node.modified)
                if (!move || finishSource(item, final, deferred)) finished(1)
            }

            /** A folder in place of a file: built under a hidden name, swapped in only when all of it arrived. */
            private fun folderOverFile(item: Item, destDir: FsNode, existing: FsNode, deferred: MutableList<Item>?): FsNode? {
                val src = item.node
                val problemsBefore = failed + skipped
                val doneBefore = done
                val pending = if (move) ArrayList<Item>() else null
                // Without rename the folder could only be built under its name after the file went.
                val tmp = newPart(destDir, FileTypes.partName(src.name), dir = true)
                    ?: throw FsException(Reason.UNSUPPORTED, display(existing))
                val entry = Journal.entryOf(tmp)?.also { journal.add(it) }
                var keep = false
                try {
                    for (child in item.children.orEmpty()) place(child, tmp, fresh = true, deferred = pending)
                    if (failed + skipped != problemsBefore) {
                        // Incomplete: what was there stays, and the copies inside the hidden folder go.
                        done = doneBefore
                        failed++
                        if (error == null) error = FsException(Reason.INCOMPLETE, display(src))
                        return null
                    }
                    val final = try {
                        swap(tmp, destDir, src.name, existing)
                    } catch (e: Exception) {
                        // Its children are counted already; only the folder itself failed.
                        countFailed(e, display(src), 1, 0)
                        return null
                    }
                    keep = true
                    entry?.let { journal.remove(it) }
                    finished(1)
                    if (pending != null) {
                        if (deferred != null) deferred += pending else drain(pending)
                        removeSourceDir(item, deferred)
                    }
                    return final
                } finally {
                    if (!keep) discard(tmp, entry)
                }
            }

            /** After a verified copy during a move: deletes the source file now, or later with [deferred]. */
            private fun finishSource(item: Item, final: FsNode?, deferred: MutableList<Item>?): Boolean {
                if (deferred != null) {
                    deferred += item
                    return true
                }
                // The copy and its source can still be one file seen through two backends.
                if (final != null && Aliases.same(item.node, final)) return true
                return try {
                    item.node.backend.delete(item.node)
                    true
                } catch (e: Exception) {
                    countFailed(e, display(item.node), 1, 0)
                    false
                }
            }

            /** Removes a moved folder's source only once it is empty; files that stayed or appeared keep it. */
            private fun removeSourceDir(item: Item, deferred: MutableList<Item>?) {
                if (deferred != null) {
                    deferred += item
                    return
                }
                val dir = item.node
                try {
                    dir.backend.delete(dir)
                } catch (e: Exception) {
                    val empty = try {
                        Trees.listAll(dir, null).isEmpty()
                    } catch (ignored: Exception) {
                        false
                    }
                    if (empty) countFailed(e, display(dir), 1, 0)
                }
            }

            /** Deletes deferred sources in the order they were recorded: files, then their folders. */
            private fun drain(pending: List<Item>) {
                for (p in pending) {
                    if (p.node.isDir) removeSourceDir(p, null) else finishSource(p, null, null)
                }
            }
        }

        /**
         * Copies [src] into [dst]. With [durable] (a move, whose source is deleted next) the copy is flushed to its
         * storage before it counts as done: on a card or USB drive the bytes could still be in memory when the
         * source went, and a pulled drive or a dead battery lost the file.
         */
        private fun copyBytes(src: FsNode, dst: FsNode, durable: Boolean): Long {
            var total = 0L
            // Both ends where only the shell writes (another app's Android/data, /data/local/tmp): the shell copies
            // between them itself instead of passing every byte through LX twice.
            val local = dst.backend as? LocalBackend
            if (local != null && local.shellCopies(src, dst)) {
                return local.shellCopy(src, dst, cancel) { n ->
                    job.doneBytes += n
                    progress()
                }
            }
            src.backend.openRead(src).use { input ->
                dst.backend.openWrite(dst).use { output ->
                    // Streams over the descriptors; the descriptors close with their owners above.
                    val ins = FileInputStream(input.fileDescriptor)
                    val outs = FileOutputStream(output.fileDescriptor)
                    // Between two ordinary files the kernel copies directly, a chunk at a time so Cancel still
                    // stops it, and the bytes never pass through the Java heap. A pipe or socket from a document
                    // provider goes through the buffer, as it always did.
                    if (regularFile(input) && regularFile(output)) {
                        val inCh = ins.channel
                        val outCh = outs.channel
                        while (true) {
                            cancel.check()
                            val n = inCh.transferTo(total, TRANSFER_CHUNK, outCh)
                            if (n <= 0) break
                            total += n
                            job.doneBytes += n
                            progress()
                        }
                    } else {
                        val buf = buffer
                        while (true) {
                            cancel.check()
                            val n = ins.read(buf)
                            if (n < 0) break
                            outs.write(buf, 0, n)
                            total += n
                            job.doneBytes += n
                            progress()
                        }
                        // A provider that streams through a pipe reports a failure here, not as an early end:
                        // without the check a cut-off download counted as the whole file.
                        input.checkError()
                    }
                    if (durable && regularFile(output)) {
                        try {
                            Os.fsync(output.fileDescriptor)
                        } catch (e: ErrnoException) {
                            throw FsException(Reason.IO, display(dst), IOException("flush failed: ${e.message}"))
                        }
                    }
                }
            }
            dst.backend.commitWrite(dst)
            return total
        }

        private fun regularFile(pfd: ParcelFileDescriptor): Boolean = try {
            OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
        } catch (e: ErrnoException) {
            false
        }

        private fun verify(src: FsNode, part: FsNode, copied: Long) {
            if (src.size >= 0 && copied != src.size) throw FsException(Reason.INCOMPLETE, display(src))
            val now = src.backend.refresh(src) ?: throw FsException(Reason.NOT_FOUND, display(src))
            if (now.size >= 0 && now.size != copied) throw FsException(Reason.INCOMPLETE, display(src))
            val written = part.backend.refresh(part) ?: throw FsException(Reason.NOT_FOUND, display(part))
            if (written.size >= 0 && written.size != copied) {
                throw FsException(Reason.IO, display(part), IOException("wrote ${written.size} of $copied bytes"))
            }
        }

        // ---- delete ----

        fun deleteJob(nodes: List<FsNode>) {
            val plans = nodes.map { plan(it, null, 0, skipParts = false) }
            job.totalItems = plans.sumOf { it.count }
            job.totalBytes = plans.sumOf { it.bytes }
            progress(force = true)
            for (p in plans) remove(p)
        }

        /** Children first. A folder whose children did not all go stays, and counts as failed. */
        private fun remove(item: Item): Boolean {
            cancel.check()
            working(item.node)
            var all = true
            for (child in item.children.orEmpty()) if (!remove(child)) all = false
            if (!all) {
                failed++
                job.doneItems++
                return false
            }
            return try {
                item.node.backend.delete(item.node)
                countDone(1, if (item.children == null) item.bytes else 0)
                true
            } catch (e: Exception) {
                countFailed(e, display(item.node), 1, if (item.children == null) item.bytes else 0)
                false
            }
        }

        // ---- compress ----

        private class Entry(val node: FsNode, val path: String)

        fun compressJob(sources: List<FsNode>, destDir: FsNode, name: String, format: ArchiveFormat) {
            val dest = destDir.backend
            val finalName = withExtension(name.trim(), format)
            if (FileTypes.nameProblem(finalName, dest.fatNames(destDir))) throw FsException(Reason.BAD_NAME, finalName)
            // The archive is written under a hidden name and renamed when complete; a destination
            // that cannot rename is refused before the walk.
            val part = newPart(destDir, FileTypes.partName(finalName), dir = false)
                ?: throw FsException(Reason.UNSUPPORTED, display(destDir))
            val entry = Journal.entryOf(part)?.also { journal.add(it) }
            var placed: FsNode? = null
            try {
                // Paths start at the sources' parent, so "MyProject/..." keeps its folder.
                val entries = ArrayList<Entry>()
                val tops = HashSet<String>()
                for (s in sources) {
                    var top = s.name
                    var n = 2
                    while (!tops.add(top.lowercase(Locale.ROOT))) top = NativeArchive.numberedName(s.name, n++)
                    collect(s, top, 0, entries)
                }
                val files = entries.count { !it.node.isDir }
                val folders = entries.size - files
                val totalBytes = entries.sumOf { if (it.node.isDir) 0L else maxOf(it.node.size, 0) }
                job.totalItems = entries.size
                job.totalBytes = totalBytes
                progress(force = true)

                writeArchive(part, format, entries)
                val written = dest.refresh(part)?.size ?: -1
                archive = ArchiveInfo(format, entries.size, files, folders, totalBytes, maxOf(written, 0), unsafe.toList())
                placed = placeOutput(part, destDir, finalName, written)
            } finally {
                if (placed == null) discard(part, entry) else entry?.let { journal.remove(it) }
            }
            placed?.let { created += it }
        }

        private fun withExtension(name: String, format: ArchiveFormat): String {
            val lower = name.lowercase(Locale.ROOT)
            return when (format) {
                ArchiveFormat.TAR_GZ -> if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) name else "$name.tar.gz"
                else -> if (lower.endsWith("." + format.extension)) name else "$name.${format.extension}"
            }
        }

        private fun collect(node: FsNode, path: String, depth: Int, out: MutableList<Entry>) {
            cancel.check()
            if (depth > Trees.MAX_DEPTH) throw FsException(Reason.TOO_DEEP, display(node))
            // Parts of running jobs, this archive's own part among them.
            if (FileTypes.isLeftoverName(node.name)) return
            if (Trees.isSpecial(node)) {
                recordUnsafe(path)
                return
            }
            out += Entry(node, path)
            if (!node.isDir) return
            working(node)
            for (child in Trees.listAll(node, cancel)) collect(child, path + "/" + child.name, depth + 1, out)
        }

        private fun writeArchive(part: FsNode, format: ArchiveFormat, entries: List<Entry>) {
            val level = if (format == ArchiveFormat.TAR) 0 else 6
            part.backend.openWrite(part).use { out ->
                var handle = NativeArchive.openWriter(out.fd, FileTypes.nativeFormat(format), level)
                if (handle == 0L) throw FsException(Reason.IO, display(part), IOException(NativeArchive.lastError(0)))
                try {
                    for (e in entries) {
                        cancel.check()
                        working(e.node)
                        if (e.node.isDir) {
                            val st = NativeArchive.addDirectory(handle, e.path, e.node.modified, MODE_DIR)
                            if (st != NativeArchive.OK) throw nativeFailure(st, handle, display(e.node))
                            countDone(1)
                        } else {
                            addFile(handle, e)
                        }
                    }
                    val h = handle
                    // finish() closes the handle whatever it returns, so its error text is gone with it.
                    handle = 0L
                    val st = NativeArchive.finish(h)
                    if (st != NativeArchive.OK) throw nativeFailure(st, 0L, display(part), "finishing the archive failed")
                } finally {
                    if (handle != 0L) NativeArchive.abort(handle)
                }
            }
            part.backend.commitWrite(part)
        }

        private fun addFile(handle: Long, e: Entry) {
            val node = e.node
            val now = node.backend.refresh(node) ?: throw FsException(Reason.NOT_FOUND, display(node))
            if (node.size >= 0 && now.size != node.size) throw FsException(Reason.INCOMPLETE, display(node))
            node.backend.openRead(node).use { pfd ->
                val size = if (now.size >= 0) now.size else pfd.statSize
                if (size < 0) throw FsException(Reason.UNSUPPORTED, display(node))
                val base = job.doneBytes
                val st = NativeArchive.addFile(handle, e.path, pfd.fd, size, now.modified, MODE_FILE, nativeProgress(base))
                if (st != NativeArchive.OK) {
                    val after = node.backend.refresh(node)
                    // The native side reports a short or long read as ERR_IO; a changed source says why.
                    if (st == NativeArchive.ERR_IO && (after == null || after.size != size)) {
                        throw FsException(Reason.INCOMPLETE, display(node), IOException(NativeArchive.lastError(handle)))
                    }
                    throw nativeFailure(st, handle, display(node))
                }
                job.doneBytes = base + size
                countDone(1)
            }
        }

        /** Gives the finished part its name; a taken name goes to the resolver. Null when skipped. */
        private fun placeOutput(part: FsNode, destDir: FsNode, name: String, size: Long): FsNode? {
            val dest = destDir.backend
            val existing = dest.child(destDir, name) ?: return dest.rename(part, name)
            return when (choose(existing, name, false, size, System.currentTimeMillis())) {
                ConflictChoice.SKIP -> {
                    // The caller removes the part.
                    skipped++
                    null
                }
                ConflictChoice.KEEP_BOTH -> dest.rename(part, freeName(destDir, name))
                else -> swap(part, destDir, name, existing)
            }
        }

        // ---- extract and test ----

        fun extractJob(archiveNode: FsNode, destDir: FsNode, ownFolder: Boolean) {
            val dest = destDir.backend
            val fat = dest.fatNames(destDir)
            val free = dest.freeBytes(destDir)
            if (free in 0 until EXTRACT_RESERVE) throw FsException(Reason.NO_SPACE, display(destDir))
            // The limit from free space, or -1 when the fixed cap is the smaller one or space is unknown.
            val spaceCap = if (free >= 0 && free - EXTRACT_RESERVE < MAX_EXTRACT) free - EXTRACT_RESERVE else -1L
            val cap = if (spaceCap >= 0) spaceCap else MAX_EXTRACT
            working(archiveNode)
            // Staging comes first, so a destination that cannot rename it into place is refused before reading.
            val stage = dest.createDir(destDir, FileTypes.stagingName())
            var staging: FsNode? = stage
            var stagingEntry: String? = Journal.entryOf(stage)?.also { journal.add(it) }
            var a: ArchiveHandle? = null
            var reader = 0L
            var entries = 0
            var files = 0
            var folders = 0
            var totalBytes = 0L
            var compressed = 0L
            try {
                if (!stage.can(Caps.RENAME)) throw FsException(Reason.UNSUPPORTED, display(destDir))
                val opened = openArchive(archiveNode, cap)
                a = opened
                reader = opened.handle
                // Keys are lower case: on the case-insensitive volumes Android mounts, "A" and "a" are one name.
                val dirs = HashMap<String, FsNode>()
                val written = HashMap<String, FsNode>()
                /** The path each written file came from, as the archive spells it. */
                val writtenPaths = HashMap<String, String>()
                val info = LongArray(NativeArchive.INFO_LENGTH)
                val names = arrayOfNulls<String>(NativeArchive.NAME_LENGTH)
                val normalized = arrayOfNulls<String>(1)
                while (true) {
                    cancel.check()
                    val st = NativeArchive.next(reader, info, names)
                    if (st == NativeArchive.END) break
                    if (st != NativeArchive.OK) {
                        val space = limitIsSpace(spaceCap, job.doneBytes, totalBytes + maxOf(info[NativeArchive.INFO_SIZE], 0))
                        throw nativeFailure(st, reader, display(archiveNode), space = space)
                    }
                    entries++
                    compressed += maxOf(info[NativeArchive.INFO_COMPRESSED], 0)
                    val raw = names[NativeArchive.NAME_RAW] ?: names[NativeArchive.NAME_PATH] ?: ""
                    val type = info[NativeArchive.INFO_TYPE].toInt()
                    var path = names[NativeArchive.NAME_PATH]
                    var verdict = info[NativeArchive.INFO_VERDICT].toInt()
                    if (verdict == NativeArchive.PATH_OK && fat) {
                        // The reader checks POSIX rules; a FAT volume refuses more names.
                        normalized[0] = null
                        verdict = NativeArchive.checkPath(raw, true, normalized)
                        path = normalized[0] ?: path
                    }
                    val parts = path.orEmpty().split('/').filter { it.isNotEmpty() }
                    val refused = verdict != NativeArchive.PATH_OK || parts.isEmpty() ||
                        info[NativeArchive.INFO_ENCRYPTED] != 0L ||
                        (type != NativeArchive.TYPE_FILE && type != NativeArchive.TYPE_DIR)
                    if (refused) {
                        recordUnsafe(raw)
                        continue
                    }
                    job.current = parts.last()
                    progress()
                    if (type == NativeArchive.TYPE_DIR) {
                        if (ensureDir(stage, parts, dirs, written) == null) recordUnsafe(raw) else {
                            folders++
                            countDone(1)
                        }
                        continue
                    }
                    val parent = ensureDir(stage, parts.dropLast(1), dirs, written)
                    val key = parts.joinToString("/") { it.lowercase(Locale.ROOT) }
                    if (parent == null || dirs.containsKey(key)) {
                        // A folder sits where this file would go, or a file where its folder would.
                        recordUnsafe(raw)
                        continue
                    }
                    val size = info[NativeArchive.INFO_SIZE]
                    val room = dest.freeBytes(stage)
                    if (room >= 0 && room < maxOf(size, 0) + FILE_RESERVE) throw FsException(Reason.NO_SPACE, display(destDir))
                    // A later entry with the same path overwrites the earlier one, as a newer copy in a tar does. One
                    // that differs only in case ("Makefile", "makefile") is another file that shared storage cannot
                    // hold beside it: it is left out and reported instead of silently replacing the first.
                    val spelled = parts.joinToString("/")
                    val again = written[key]
                    if (again != null && writtenPaths[key] != spelled) {
                        recordUnsafe(raw)
                        continue
                    }
                    val target = again ?: dest.createFile(parent, parts.last(), FileTypes.OCTET_STREAM).also {
                        written[key] = it
                        writtenPaths[key] = spelled
                    }
                    val base = job.doneBytes
                    val result = dest.openWrite(target).use { pfd -> NativeArchive.extract(reader, pfd.fd, nativeProgress(base)) }
                    dest.commitWrite(target)
                    if (result != NativeArchive.OK) {
                        val space = limitIsSpace(spaceCap, job.doneBytes, totalBytes + maxOf(size, 0))
                        throw nativeFailure(result, reader, display(archiveNode), space = space)
                    }
                    if (dest is LocalBackend) dest.setModified(target, info[NativeArchive.INFO_MTIME_MS])
                    if (again == null) files++
                    totalBytes += maxOf(size, 0)
                    job.doneBytes = base + maxOf(size, 0)
                    countDone(1)
                }
                NativeArchive.closeReader(reader)
                reader = 0L
                archive = ArchiveInfo(opened.format, entries, files, folders, totalBytes, compressed, unsafe.toList())

                val top = Trees.listAll(stage, cancel)
                if (ownFolder && top.size > 1) {
                    val (folder, renamed) = placeStaging(stage, destDir, FileTypes.archiveBaseName(archiveNode.name))
                    if (renamed) {
                        // Staging became the result: it must survive the cleanup below.
                        staging = null
                        stagingEntry?.let { journal.remove(it) }
                        stagingEntry = null
                    }
                    folder?.let { created += it }
                } else {
                    val transfer = Transfer(move = true, counting = false)
                    for (t in top) {
                        cancel.check()
                        val item = plan(t, stage, 0, skipParts = false)
                        transfer.place(item, destDir, fresh = false, deferred = null)?.let { created += it }
                    }
                }
            } finally {
                if (reader != 0L) NativeArchive.closeReader(reader)
                a?.let { h ->
                    if (archive == null) archive = ArchiveInfo(h.format, entries, files, folders, totalBytes, compressed, unsafe.toList())
                    closeArchive(h)
                }
                // What cannot go now stays in the journal for the next start.
                staging?.let { discard(it, stagingEntry) }
            }
        }

        /**
         * Gives the staging folder the archive's name. Returns the resulting folder (null when the
         * user skipped) and whether that folder is the renamed staging folder rather than an
         * existing one it was merged into.
         */
        private fun placeStaging(stage: FsNode, destDir: FsNode, name: String): Pair<FsNode?, Boolean> {
            val dest = destDir.backend
            val existing = dest.child(destDir, name) ?: return dest.rename(stage, name) to true
            return when (choose(existing, name, true, 0, System.currentTimeMillis())) {
                ConflictChoice.SKIP -> {
                    skipped++
                    null to false
                }
                ConflictChoice.KEEP_BOTH -> dest.rename(stage, freeName(destDir, name)) to true
                else -> if (existing.isDir) {
                    val transfer = Transfer(move = true, counting = false)
                    for (child in Trees.listAll(stage, cancel)) {
                        cancel.check()
                        transfer.place(plan(child, stage, 0, skipParts = false), existing, fresh = false, deferred = null)
                    }
                    existing to false
                } else {
                    // A file of that name makes way once the folder holds the name.
                    swap(stage, destDir, name, existing) to true
                }
            }
        }

        /** The staged folder for [parts], created on demand; null when a file already holds one of the names. */
        private fun ensureDir(stage: FsNode, parts: List<String>, dirs: HashMap<String, FsNode>, written: Map<String, FsNode>): FsNode? {
            var current = stage
            var key = ""
            for (p in parts) {
                key = if (key.isEmpty()) p.lowercase(Locale.ROOT) else key + "/" + p.lowercase(Locale.ROOT)
                if (written.containsKey(key)) return null
                current = dirs[key] ?: current.backend.createDir(current, p).also { dirs[key] = it }
            }
            return current
        }

        fun testJob(archiveNode: FsNode) {
            working(archiveNode)
            val a = openArchive(archiveNode, MAX_EXTRACT)
            val reader = a.handle
            var entries = 0
            var files = 0
            var folders = 0
            var totalBytes = 0L
            var compressed = 0L
            try {
                val info = LongArray(NativeArchive.INFO_LENGTH)
                val names = arrayOfNulls<String>(NativeArchive.NAME_LENGTH)
                while (true) {
                    cancel.check()
                    val st = NativeArchive.next(reader, info, names)
                    if (st == NativeArchive.END) break
                    if (st != NativeArchive.OK) throw nativeFailure(st, reader, display(archiveNode))
                    entries++
                    compressed += maxOf(info[NativeArchive.INFO_COMPRESSED], 0)
                    val raw = names[NativeArchive.NAME_RAW] ?: names[NativeArchive.NAME_PATH] ?: ""
                    val type = info[NativeArchive.INFO_TYPE].toInt()
                    job.current = raw.substringAfterLast('/')
                    progress()
                    val encrypted = info[NativeArchive.INFO_ENCRYPTED] != 0L
                    // What an extraction would skip is listed, but the data is still checked when it can be.
                    if (encrypted || info[NativeArchive.INFO_VERDICT].toInt() != NativeArchive.PATH_OK ||
                        (type != NativeArchive.TYPE_FILE && type != NativeArchive.TYPE_DIR)
                    ) {
                        recordUnsafe(raw)
                    }
                    when (type) {
                        NativeArchive.TYPE_DIR -> folders++
                        NativeArchive.TYPE_FILE -> {
                            files++
                            if (!encrypted) {
                                val base = job.doneBytes
                                val r = NativeArchive.extract(reader, -1, nativeProgress(base))
                                if (r != NativeArchive.OK) throw nativeFailure(r, reader, display(archiveNode))
                                val size = maxOf(info[NativeArchive.INFO_SIZE], 0)
                                totalBytes += size
                                job.doneBytes = base + size
                            }
                        }
                    }
                    countDone(1)
                }
            } finally {
                NativeArchive.closeReader(reader)
                archive = ArchiveInfo(a.format, entries, files, folders, totalBytes, compressed, unsafe.toList())
                closeArchive(a)
            }
        }

        /**
         * Opens a reader on [node]. The format comes from the first bytes, else from the name. A pipe
         * is opened again after detect() read from it, and a ZIP on a pipe is copied to the cache
         * first, because ZIP needs its central directory at the end.
         */
        private fun openArchive(node: FsNode, cap: Long): ArchiveHandle {
            var pfd = node.backend.openRead(node)
            var cache: File? = null
            var cacheEntry: String? = null
            try {
                val seekable = try {
                    Os.lseek(pfd.fileDescriptor, 0, OsConstants.SEEK_CUR)
                    true
                } catch (e: ErrnoException) {
                    false
                }
                var native = NativeArchive.detect(pfd.fd)
                if (native == NativeArchive.FORMAT_UNKNOWN) {
                    native = FileTypes.archiveFormat(node.name)?.let { FileTypes.nativeFormat(it) }
                        ?: throw FsException(Reason.BAD_ARCHIVE, display(node), IOException("unknown format"))
                }
                val format = FileTypes.formatOf(native) ?: throw FsException(Reason.BAD_ARCHIVE, display(node))
                if (!seekable) {
                    pfd.close()
                    pfd = node.backend.openRead(node)
                    if (native == NativeArchive.FORMAT_ZIP) {
                        val copy = File(ctx.cacheDir, FileTypes.partName(node.name))
                        cache = copy
                        cacheEntry = copy.absolutePath.also { journal.add(it) }
                        copyToCache(pfd, node, copy)
                        pfd.close()
                        pfd = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                }
                val limits = LongArray(NativeArchive.LIMIT_LENGTH)
                limits[NativeArchive.LIMIT_MAX_ENTRIES] = MAX_ENTRIES
                limits[NativeArchive.LIMIT_MAX_TOTAL_BYTES] = cap
                limits[NativeArchive.LIMIT_MAX_ENTRY_BYTES] = cap
                limits[NativeArchive.LIMIT_MAX_RATIO_X100] = MAX_RATIO_X100
                limits[NativeArchive.LIMIT_RATIO_GRACE_BYTES] = RATIO_GRACE
                val handle = NativeArchive.openReader(pfd.fd, native, limits)
                if (handle == 0L) throw FsException(Reason.BAD_ARCHIVE, display(node), IOException(NativeArchive.lastError(0)))
                return ArchiveHandle(handle, pfd, format, cache, cacheEntry)
            } catch (t: Throwable) {
                closeQuietly(pfd)
                cache?.let { dropCache(it, cacheEntry) }
                throw t
            }
        }

        private fun copyToCache(pfd: ParcelFileDescriptor, node: FsNode, copy: File) {
            val need = maxOf(node.size, 0) + FILE_RESERVE
            val room = try {
                StatFs(ctx.cacheDir.path).availableBytes
            } catch (e: IllegalArgumentException) {
                -1L
            }
            if (room in 0 until need) throw FsException(Reason.NO_SPACE, ctx.cacheDir.path)
            val ins = FileInputStream(pfd.fileDescriptor)
            try {
                FileOutputStream(copy).use { outs ->
                    val buf = buffer
                    while (true) {
                        cancel.check()
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                    }
                }
            } catch (e: IOException) {
                throw FsErrors.of(e, ctx.cacheDir.path)
            }
        }

        private fun closeArchive(a: ArchiveHandle) {
            closeQuietly(a.pfd)
            a.cache?.let { dropCache(it, a.cacheEntry) }
        }

        private fun dropCache(copy: File, entry: String?) {
            if (copy.delete() || !copy.exists()) entry?.let { journal.remove(it) }
        }

        private fun closeQuietly(pfd: ParcelFileDescriptor) {
            try {
                pfd.close()
            } catch (ignored: IOException) {
            }
        }

        private fun nativeProgress(base: Long) = NativeArchive.Progress { bytes ->
            job.doneBytes = base + bytes
            progress()
            !cancel.cancelled
        }

        /**
         * Maps a native status; the handle's error text becomes the cause, read before the handle
         * closes. With [space], a limit that was hit is the free-space limit (see [limitIsSpace]).
         */
        private fun nativeFailure(status: Int, handle: Long, path: String, fallback: String = "", space: Boolean = false): FsException {
            val reason = when (status) {
                NativeArchive.ERR_LIMIT -> if (space) Reason.NO_SPACE else Reason.BAD_ARCHIVE
                NativeArchive.ERR_CRC, NativeArchive.ERR_CORRUPT,
                NativeArchive.ERR_FORMAT, NativeArchive.ERR_UNSUPPORTED -> Reason.BAD_ARCHIVE
                NativeArchive.ERR_NO_SPACE -> Reason.NO_SPACE
                NativeArchive.ERR_CANCELLED -> Reason.CANCELLED
                else -> Reason.IO
            }
            val text = if (handle != 0L) {
                try {
                    NativeArchive.lastError(handle)
                } catch (e: RuntimeException) {
                    fallback
                }
            } else {
                fallback
            }
            return FsException(reason, path, IOException(text.ifEmpty { "status $status" }))
        }
    }
}
