// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.webkit.MimeTypeMap
import java.security.SecureRandom
import java.util.Locale

/** Mime types, archive names and the hidden names jobs use for unfinished work. */
object FileTypes {
    const val OCTET_STREAM = "application/octet-stream"

    /** Bytes a name may take on the file systems Android mounts. */
    private const val NAME_MAX = 255

    /** Types MimeTypeMap misses on some Android versions. */
    private val extra = mapOf(
        "tgz" to "application/gzip",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "7z" to "application/x-7z-compressed",
        "md" to "text/markdown",
        "log" to "text/plain",
        "json" to "application/json",
        "apk" to "application/vnd.android.package-archive",
    )

    private val random = SecureRandom()
    private val partRegex = Regex("^\\..+\\.lxpart-[0-9a-f]{6}$")
    private val stagingRegex = Regex("^\\.lx-extract-[0-9a-f]{6}$")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun mimeOf(name: String): String {
        val ext = extension(name)
        if (ext.isEmpty()) return OCTET_STREAM
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: extra[ext] ?: OCTET_STREAM
    }

    /** The archive format a name promises, or null. ".tar.gz" is checked before ".gz" alone would be. */
    fun archiveFormat(name: String): ArchiveFormat? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".zip") -> ArchiveFormat.ZIP
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            n.endsWith(".tar") -> ArchiveFormat.TAR
            else -> null
        }
    }

    fun isArchive(name: String): Boolean = archiveFormat(name) != null

    /** "photos.tar.gz" gives "photos"; a name that is only an extension keeps its full text. */
    fun archiveBaseName(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        val cut = listOf(".tar.gz", ".tgz", ".zip", ".tar").firstOrNull { n.endsWith(it) } ?: return name
        return name.substring(0, name.length - cut.length).ifEmpty { name }
    }

    fun nativeFormat(format: ArchiveFormat): Int = when (format) {
        ArchiveFormat.ZIP -> NativeArchive.FORMAT_ZIP
        ArchiveFormat.TAR -> NativeArchive.FORMAT_TAR
        ArchiveFormat.TAR_GZ -> NativeArchive.FORMAT_TAR_GZ
    }

    fun formatOf(native: Int): ArchiveFormat? = when (native) {
        NativeArchive.FORMAT_ZIP -> ArchiveFormat.ZIP
        NativeArchive.FORMAT_TAR -> ArchiveFormat.TAR
        NativeArchive.FORMAT_TAR_GZ -> ArchiveFormat.TAR_GZ
        else -> null
    }

    fun hex6(): String {
        val b = ByteArray(3)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** ".report.pdf.lxpart-1a2b3c": hidden, and recognisable by the journal cleanup. */
    fun partName(name: String): String = "." + fitName(name, NAME_MAX - 15) + ".lxpart-" + hex6()

    /**
     * ".report.pdf.lxkeep-1a2b3c": an item a swap moved aside, or a moved item waiting for its
     * name. It holds the user's data, so no cleanup deletes it and walks treat it as a plain file.
     */
    fun keptName(name: String): String = "." + fitName(name, NAME_MAX - 15) + ".lxkeep-" + hex6()

    fun stagingName(): String = ".lx-extract-" + hex6()

    /** True for names only a job of this app creates. Cleanup deletes nothing else. */
    fun isLeftoverName(name: String): Boolean = partRegex.matches(name) || stagingRegex.matches(name)

    fun isStagingName(name: String): Boolean = stagingRegex.matches(name)

    /**
     * Basic name rules every backend enforces: not empty, not "." or "..", no "/" or NUL,
     * at most 255 bytes. With [fat], also no `"*:<>?\|`, control characters, or a trailing dot or space.
     */
    fun nameProblem(name: String, fat: Boolean): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return true
        if (name.any { it == '/' || it == '\u0000' }) return true
        if (name.toByteArray(Charsets.UTF_8).size > NAME_MAX) return true
        if (fat) {
            if (name.any { it.code < 0x20 || it in "\"*:<>?\\|" }) return true
            if (name.endsWith('.') || name.endsWith(' ')) return true
        }
        return false
    }

    /** Cuts [name] by whole code points until it fits [maxBytes] of UTF-8. */
    private fun fitName(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
