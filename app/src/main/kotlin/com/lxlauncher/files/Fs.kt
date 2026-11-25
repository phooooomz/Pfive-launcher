// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract

/*
 * Contract between the File Manager window and the storage code behind it.
 * Every Backend and StorageCatalog call may block on I/O: call them from a worker thread,
 * never from the main thread. FileJobs calls return at once and report on the main thread.
 */

/** Capability bits of an [FsNode]. */
object Caps {
    const val WRITE = 1
    const val DELETE = 2
    const val RENAME = 4
    /** A folder that accepts new children. */
    const val CREATE = 8
    const val MOVE = 16
}

/**
 * One file or folder a [Backend] can see. [ref] belongs to the backend: an absolute path for the
 * local backend, a document id for a granted SAF tree.
 */
data class FsNode(
    val backend: Backend,
    val ref: String,
    val name: String,
    val isDir: Boolean,
    /** Bytes; 0 for folders, -1 when the provider does not say. */
    val size: Long,
    /** Epoch milliseconds; 0 when unknown. */
    val modified: Long,
    /** [DocumentsContract.Document.MIME_TYPE_DIR] for folders. */
    val mime: String,
    val caps: Int,
) {
    // Computed once: sorting a folder of 10,000 files reads them about 140,000 times.
    val hidden: Boolean = name.startsWith(".")
    fun can(cap: Int) = caps and cap != 0
    /** Identity across listings, for selection sets and history. */
    val key: String = backend.key + "\u0000" + ref
    val extension: String = name.substringAfterLast('.', "").lowercase()

    companion object {
        const val DIR_MIME = DocumentsContract.Document.MIME_TYPE_DIR
    }
}

/** An expected failure with a reason the window can explain. Anything else is an I/O error or a bug. */
class FsException(val reason: Reason, val path: String = "", cause: Throwable? = null) :
    java.io.IOException(reason.name + if (path.isEmpty()) "" else ": $path", cause) {
    enum class Reason {
        NOT_FOUND,
        EXISTS,
        NO_ACCESS,
        READ_ONLY,
        NO_SPACE,
        BAD_NAME,
        /** The backend cannot do this, for example a provider without rename. */
        UNSUPPORTED,
        /** The volume was removed or the folder grant was revoked. */
        GONE,
        CANCELLED,
        /** The source changed while it was being copied. */
        INCOMPLETE,
        /** Folders nested deeper than the walker allows, or a folder pasted into itself. */
        TOO_DEEP,
        /** An archive that is damaged, encrypted, or breaks a safety limit. */
        BAD_ARCHIVE,
        IO,
    }
}

/** Cooperative cancellation shared by the window, the worker and native code. */
class Cancel {
    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    fun check() {
        if (cancelled) throw FsException(FsException.Reason.CANCELLED)
    }
}

/** A place files live: the local file system (all-files access) or one granted SAF tree. */
interface Backend {
    /** "local" or "saf:" plus the tree Uri. */
    val key: String
    /** The top this backend can reach: "/" for local, the granted folder for SAF. */
    val root: FsNode
    /** Name shown for [root]. */
    val label: String
    /** True when names follow FAT rules (removable volumes): no reserved characters, no trailing dot or space. */
    fun fatNames(dir: FsNode): Boolean

    fun children(dir: FsNode, cancel: Cancel? = null): List<FsNode>
    fun child(dir: FsNode, name: String): FsNode?
    /** The parent inside this backend, or null at [root] or when the backend cannot tell. */
    fun parent(node: FsNode): FsNode?
    /** Fresh metadata for [node], or null when it no longer exists. */
    fun refresh(node: FsNode): FsNode?

    fun createDir(dir: FsNode, name: String): FsNode
    fun createFile(dir: FsNode, name: String, mime: String): FsNode
    fun openRead(node: FsNode): ParcelFileDescriptor
    /** Opens for writing and truncates. Close the descriptor, then call [commitWrite]. */
    fun openWrite(node: FsNode): ParcelFileDescriptor
    /**
     * Called once a descriptor from [openWrite] is closed. A backend that carries the bytes on after the descriptor
     * (the shell, through LX Bridge) puts them in place here, and throws if that failed.
     */
    fun commitWrite(node: FsNode) {}
    fun rename(node: FsNode, newName: String): FsNode
    /** Deletes one file or one empty folder. Walking a tree is the caller's job. */
    fun delete(node: FsNode)
    /**
     * Moves inside this backend without copying (same volume or same provider), keeping the
     * name [newName]. Returns null when that is not possible; the caller then copies and deletes.
     */
    fun moveInside(node: FsNode, destDir: FsNode, newName: String): FsNode?
    /** Free bytes where [dir] lives, or -1 when unknown. */
    fun freeBytes(dir: FsNode): Long
    /** A content Uri another app can read with a grant (open with, share). */
    fun shareUri(node: FsNode): Uri
    /** True when [inner] is [outer] or lies inside it. */
    fun contains(outer: FsNode, inner: FsNode): Boolean
    /** Human readable location for details and errors, such as "/storage/0000-0000/DCIM". */
    fun displayPath(node: FsNode): String
}

enum class VolumeKind { INTERNAL, SD_CARD, USB, OTHER }

/** Kinds of files the File Explorer's home page counts and lists, from Android's media index. */
enum class FileCategory { IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, APKS, DOWNLOADS }

/** How many files of a [FileCategory] the media index holds, and their total size in bytes. */
data class CategoryStat(val count: Int, val bytes: Long)

data class Volume(
    /** "primary" or the file system UUID. */
    val id: String,
    val label: String,
    val kind: VolumeKind,
    /** Mount directory, or null when unmounted or unknown on this Android version. */
    val path: String?,
    val mounted: Boolean,
    val readOnly: Boolean,
    val total: Long,
    val free: Long,
)

/** Result of a recursive search: how far it got and whether a limit stopped it. */
data class SearchOutcome(val visited: Int, val hits: Int, val truncated: Boolean)

interface StorageCatalog {
    fun volumes(): List<Volume>
    /** MANAGE_EXTERNAL_STORAGE on Android 11+, legacy storage permission on 8 to 10. */
    fun allFilesAccess(): Boolean
    /** True while LX Bridge opens what all-files access leaves closed: Android/data, Android/obb, the device root. */
    fun fullAccess(): Boolean
    /** Gives LX all-files access through LX Bridge when lxdb runs. True when LX has the access afterwards. */
    fun grantThroughBridge(): Boolean
    /**
     * Places only LX Bridge reaches, as (key, path): "root", "data", "obb", "tmp". Empty without it. The keys name
     * the rows in the window; the paths are where they open.
     */
    fun systemPlaces(): List<Pair<String, String>>
    /** The local backend, or null without all-files access. */
    fun local(): Backend?
    /** Node for an absolute path through [local], or null. */
    fun localNode(path: String): FsNode?
    /** Folder grants the user made through the system picker, newest first. */
    fun safTrees(): List<Uri>
    fun saf(tree: Uri): Backend
    /** Standard folders on internal storage (Download, Documents, Pictures, DCIM, Movies, Music) that exist. */
    fun standardFolders(): List<Pair<String, String>>
    /** Recently modified files from MediaStore, newest first. Empty without all-files access. */
    fun recent(limit: Int, cancel: Cancel): List<FsNode>
    /** Files per [FileCategory] in Android's media index. Empty without all-files access. */
    fun categoryStats(cancel: Cancel): Map<FileCategory, CategoryStat>
    /** The newest [limit] files of [kind], newest first. Empty without all-files access. */
    fun category(kind: FileCategory, limit: Int, cancel: Cancel): List<FsNode>
    /**
     * Walks [from] breadth first and reports names containing [query] (case-insensitive) in
     * batches. Stops at [maxVisited] items or [maxHits] hits and says so in the outcome.
     */
    fun search(from: FsNode, query: String, cancel: Cancel, maxVisited: Int, maxHits: Int, onBatch: (List<FsNode>) -> Unit): SearchOutcome
    /** Same walk, reporting .zip, .tar, .tar.gz and .tgz files. */
    fun findArchives(from: FsNode, cancel: Cancel, maxVisited: Int, onBatch: (List<FsNode>) -> Unit): SearchOutcome
    /** Called on the main thread when a volume is mounted, unmounted or removed. */
    fun addVolumeListener(listener: () -> Unit)
    fun removeVolumeListener(listener: () -> Unit)
}

enum class ArchiveFormat(val extension: String) { ZIP("zip"), TAR("tar"), TAR_GZ("tar.gz") }

enum class JobKind { COPY, MOVE, DELETE, COMPRESS, EXTRACT, TEST }

/** Snapshot of a running job. [totalBytes] is -1 while it is still being counted. */
data class JobProgress(
    val id: Int,
    val kind: JobKind,
    val doneBytes: Long,
    val totalBytes: Long,
    val doneItems: Int,
    val totalItems: Int,
    /** Name of the item being worked on. */
    val current: String,
)

data class ArchiveInfo(
    val format: ArchiveFormat,
    val entries: Int,
    val files: Int,
    val folders: Int,
    /** Uncompressed bytes of all files. */
    val totalBytes: Long,
    val compressedBytes: Long,
    /** Entries skipped for safety: path escapes, links, devices, encrypted entries. */
    val unsafe: List<String>,
)

data class JobResult(
    val id: Int,
    val kind: JobKind,
    val ok: Boolean,
    val cancelled: Boolean,
    /** Items finished. */
    val done: Int,
    /** Items the user chose to skip, plus unsafe archive entries. */
    val skipped: Int,
    val failed: Int,
    /** First failure, when [ok] is false. */
    val error: FsException?,
    /** Top-level items the job created (copies, the archive, the extracted folder). */
    val created: List<FsNode>,
    /** For COMPRESS, EXTRACT and TEST. */
    val archive: ArchiveInfo?,
)

enum class ConflictChoice { REPLACE, SKIP, KEEP_BOTH, CANCEL }

data class Conflict(
    val name: String,
    val existingIsDir: Boolean,
    val incomingIsDir: Boolean,
    val existingSize: Long,
    val incomingSize: Long,
    val existingModified: Long,
    val incomingModified: Long,
)

/**
 * Asked on the worker thread when a target name is taken; blocks until the window answers.
 * The second value applies the choice to every later conflict of the same job.
 * REPLACE on two folders merges them.
 */
fun interface ConflictResolver {
    fun resolve(conflict: Conflict): Pair<ConflictChoice, Boolean>

    /** The same, from a job that can be cancelled while it waits. */
    fun resolve(conflict: Conflict, cancel: Cancel?): Pair<ConflictChoice, Boolean> = resolve(conflict)
}

interface JobListener {
    fun onProgress(progress: JobProgress)
    fun onFinished(result: JobResult)
}

/**
 * Long file operations. Jobs belong to the process, not to a window, so closing or rebuilding
 * the File Manager window does not stop them. Each call returns the job id at once.
 */
interface JobEngine {
    fun copy(sources: List<FsNode>, destDir: FsNode, move: Boolean, resolver: ConflictResolver): Int
    fun delete(nodes: List<FsNode>): Int
    fun compress(sources: List<FsNode>, destDir: FsNode, name: String, format: ArchiveFormat, resolver: ConflictResolver): Int
    /**
     * Extracts into [destDir]. With [ownFolder], everything goes into a new folder named after the
     * archive, unless the archive already holds a single top-level folder.
     */
    fun extract(archive: FsNode, destDir: FsNode, ownFolder: Boolean, resolver: ConflictResolver): Int
    /** Reads every entry and checks it without writing anything. */
    fun test(archive: FsNode): Int
    fun cancel(id: Int)
    fun running(): List<JobProgress>
    fun addListener(listener: JobListener)
    fun removeListener(listener: JobListener)
}
