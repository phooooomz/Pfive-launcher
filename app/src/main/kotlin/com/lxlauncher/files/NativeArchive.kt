// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

/**
 * JNI bridge to the archive code in the C++ core (`core/include/lx/archive.hpp`).
 * Readers and writers work on file descriptors the caller opened; native code dups the fd and
 * never closes the caller's copy. A dup shares the file offset, so readers read regular files
 * with pread and leave the offset alone: the same fd can be opened again for a second pass.
 * Handles are not thread safe: use one handle from one thread.
 * Every call that moves data reports through [Progress], which can also stop the work.
 */
object NativeArchive {
    init {
        System.loadLibrary("lxnative")
    }

    const val FORMAT_UNKNOWN = -1
    const val FORMAT_ZIP = 0
    const val FORMAT_TAR = 1
    const val FORMAT_TAR_GZ = 2

    const val TYPE_FILE = 0
    const val TYPE_DIR = 1
    const val TYPE_SYMLINK = 2
    const val TYPE_HARDLINK = 3
    /** Devices, FIFOs and anything else that is not a plain file or folder. */
    const val TYPE_OTHER = 4

    /** Results of [checkPath] and the verdict field of [next]. */
    const val PATH_OK = 0
    const val PATH_EMPTY = 1
    const val PATH_ABSOLUTE = 2
    /**
     * A ".." component, or an entry that would reach outside the folder through a link: an entry
     * at or below a symlink that came earlier in the archive, a hard link into such a folder, or a
     * symlink whose target climbs above the root or uses ".." after a normal component. Symlinks
     * with an absolute target get [PATH_ABSOLUTE].
     */
    const val PATH_DOTDOT = 3
    const val PATH_DRIVE = 4
    /**
     * Bytes below 0x20 and 0x7F, the C1 controls U+0080..U+009F, U+2028/U+2029, and the bidi
     * embeddings, overrides and isolates (U+202A..U+202E, U+2066..U+2069) that can show
     * "invoice", U+202E, "txt.apk" as "invoicekpa.txt".
     */
    const val PATH_CONTROL = 5
    const val PATH_BAD_UTF8 = 6
    const val PATH_TOO_LONG = 7
    const val PATH_COMPONENT_TOO_LONG = 8
    /** A component that is "." or "..", or a FAT-reserved character when FAT rules apply. */
    const val PATH_RESERVED = 9

    /** Status codes. */
    const val OK = 0
    const val END = 1
    const val ERR_IO = -1
    const val ERR_FORMAT = -2
    const val ERR_CORRUPT = -3
    const val ERR_CRC = -4
    const val ERR_LIMIT = -5
    const val ERR_CANCELLED = -6
    /** Encrypted entries, unknown compression methods, or a ZIP on a stream that cannot seek. */
    const val ERR_UNSUPPORTED = -7
    const val ERR_NO_SPACE = -8

    /** Indexes into the `info` array filled by [next]. */
    const val INFO_TYPE = 0
    /** Bytes [extract] writes: file data, a ZIP symlink's target, 0 for folders and other entries. */
    const val INFO_SIZE = 1
    /** Stored bytes, or -1 for TAR.GZ where the stream is compressed as a whole. */
    const val INFO_COMPRESSED = 2
    /**
     * UTC milliseconds. ZIP entries without an extended timestamp only carry a DOS time, which is
     * read in the device's time zone.
     */
    const val INFO_MTIME_MS = 3
    /** Permission bits, or 0 when the archive did not record any. */
    const val INFO_MODE = 4
    /** A PATH_ constant for the entry's path and, for links, their target. */
    const val INFO_VERDICT = 5
    const val INFO_ENCRYPTED = 6
    const val INFO_LENGTH = 7

    /**
     * Indexes into the `names` array filled by [next]. NAME_RAW is the name as stored: ZIP names
     * that are not UTF-8 are decoded as CP437, and bad bytes in TAR names show as U+FFFD.
     */
    const val NAME_RAW = 0
    /** The checked, normalised path, or null when the verdict is not [PATH_OK]. */
    const val NAME_PATH = 1
    /**
     * Symlink target or hard link target as stored, or null. The verdict covers it: a link with a
     * target that leaves the folder does not get [PATH_OK]. A symlink target is relative to the
     * link's folder, a hard link target to the archive root.
     */
    const val NAME_LINK = 2
    const val NAME_LENGTH = 3

    /** Indexes into the `limits` array given to [openReader]. A value of 0 or less turns that limit off. */
    const val LIMIT_MAX_ENTRIES = 0
    const val LIMIT_MAX_TOTAL_BYTES = 1
    const val LIMIT_MAX_ENTRY_BYTES = 2
    /**
     * Highest allowed uncompressed:compressed ratio, times 100. It applies to the archive as a
     * whole, so a bomb split into many small entries still trips it; a ZIP entry is also checked
     * on its own. TAR.GZ data that [next] skips counts as well.
     */
    const val LIMIT_MAX_RATIO_X100 = 3
    /** Bytes the archive (and, for the ZIP per-entry check, an entry) may produce before the ratio check starts. */
    const val LIMIT_RATIO_GRACE_BYTES = 4
    const val LIMIT_LENGTH = 5

    /**
     * Called from the thread that made the native call, about once per MiB and once at the end.
     * An exception thrown here counts as a cancel. Native code looks up [onBytes] by name, so R8
     * must keep it in the interface and in every class that implements it:
     * ```
     * -keep interface com.lxlauncher.files.NativeArchive$Progress { boolean onBytes(long); }
     * -keepclassmembers class * implements com.lxlauncher.files.NativeArchive$Progress { boolean onBytes(long); }
     * ```
     * When it is missing, [extract] and [addFile] return [ERR_IO] with that in [lastError]
     * instead of running.
     */
    fun interface Progress {
        /** [bytes] is the running total for the current call. Return false to stop. */
        fun onBytes(bytes: Long): Boolean
    }

    /** Looks at the first bytes of [fd] (then seeks back when it can). Returns a FORMAT_ constant. */
    external fun detect(fd: Int): Int

    /**
     * Opens [fd] for reading. Returns a handle, or 0 with the reason in [lastError] of handle 0.
     * [FORMAT_UNKNOWN] runs [detect] first and needs a file that can seek, as ZIP does; on a pipe
     * pass the format.
     */
    external fun openReader(fd: Int, format: Int, limits: LongArray): Long

    /**
     * Moves to the next entry and describes it in [info] (INFO_LENGTH longs) and [names]
     * (NAME_LENGTH strings). Returns OK, END, or an ERR_ status. Data of an entry that was not
     * extracted is skipped; for TAR.GZ that means inflating it without [Progress] calls, so call
     * [extract] with -1 first to skip a large entry with progress and a way to stop. After
     * [ERR_LIMIT] or a damaged archive structure every later call returns the same error.
     *
     * TAR.GZ carries one CRC for the whole gzip stream, checked when the end is reached: treat an
     * extraction as complete only once this returns END. An error here (such as [ERR_CRC]) after
     * entries were extracted with OK means their data may be damaged.
     */
    external fun next(handle: Long, info: LongArray, names: Array<String?>): Int

    /**
     * Writes the data of the current entry to [outFd], or only checks it when [outFd] is -1.
     * Verifies the CRC for ZIP and enforces the limits. Returns OK or an ERR_ status; for TAR.GZ
     * the final word on the data comes from [next] returning END. Folders and hard links write
     * nothing; [TYPE_OTHER], encrypted entries and unknown ZIP methods return [ERR_UNSUPPORTED].
     */
    external fun extract(handle: Long, outFd: Int, progress: Progress?): Int

    external fun closeReader(handle: Long)

    /** Opens [fd] for writing a new archive. [level] is 0 (store) to 9. Returns a handle or 0. */
    external fun openWriter(fd: Int, format: Int, level: Int): Long

    /** Adds a folder entry. [path] uses "/" and no leading slash. */
    external fun addDirectory(handle: Long, path: String, mtimeMs: Long, mode: Int): Int

    /**
     * Adds a file of exactly [size] bytes read from [inFd]. Returns ERR_IO when the size does not
     * match. After any error other than a rejected path or a missing [Progress.onBytes] the
     * archive is broken: [abort] it. A [mode] of 0 stores 0644 (0755 for [addDirectory]).
     *
     * ZIP output stays readable for streaming readers such as java.util.zip.ZipInputStream:
     * level 0 uses deflate's stored blocks, and a file just under 4 GiB is stored with its CRC
     * taken in a first pass over [inFd] (with pread, so [Progress] reports 0 during that pass).
     * The first pass needs a regular file; from a pipe such a file is written with ZIP64 records
     * that ZipInputStream cannot read.
     */
    external fun addFile(handle: Long, path: String, inFd: Int, size: Long, mtimeMs: Long, mode: Int, progress: Progress?): Int

    /**
     * Writes the central directory or end blocks and closes the handle. The handle is gone either
     * way, so a failure's message is in [lastError] of handle 0.
     */
    external fun finish(handle: Long): Int

    /** Closes the handle without finishing; the output is incomplete and should be deleted. */
    external fun abort(handle: Long)

    /** Last error message of a handle, or of the last failed open when [handle] is 0. */
    external fun lastError(handle: Long): String

    /** Checks an entry path the way extraction does. [normalized][0] receives the cleaned path. */
    external fun checkPath(raw: String, fat: Boolean, normalized: Array<String?>): Int

    /** Checks one name typed by the user for a new or renamed item. Returns a PATH_ constant. */
    external fun checkName(name: String, fat: Boolean): Int

    /** "report.pdf", 2 gives "report (2).pdf"; double extensions like ".tar.gz" stay together. */
    external fun numberedName(name: String, n: Int): String
}
