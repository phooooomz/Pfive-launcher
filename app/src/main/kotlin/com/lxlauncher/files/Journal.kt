// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Unfinished work a job leaves on disk: partial files (".name.lxpart-xxxxxx") and extraction
 * staging folders (".lx-extract-xxxxxx"). Each is written here before data goes into it and
 * struck out once it is gone or has its real name, so whatever a killed process left behind is
 * removed at the next start. Cleanup only ever deletes items whose current name matches those
 * patterns.
 *
 * The file is append-only: "+entry" adds, "-entry" strikes out, and it is rewritten with the open
 * entries alone once it grows. Entries are a local absolute path or "saf|<tree>|<docId>", each
 * percent-encoded so a newline in a name cannot split a line. Reading it touches the disk: build
 * one on a worker thread.
 */
internal class Journal(private val file: File) {
    private val live = LinkedHashSet<String>()
    private var lines = 0

    init {
        synchronized(this) { readLive() }
    }

    fun add(entry: String) {
        synchronized(this) {
            if (live.add(entry)) append("+" + encode(entry))
        }
    }

    fun remove(entry: String) {
        synchronized(this) {
            if (!live.remove(entry)) return
            if (live.isEmpty() || lines >= COMPACT_LINES) rewrite() else append("-" + encode(entry))
        }
    }

    /** The open entries. Taken once at start, before any job of this process adds to them. */
    fun leftovers(): List<String> = synchronized(this) { live.toList() }

    /**
     * Deletes what [leftovers] named, when it still has a leftover name. An entry that cannot be
     * reached now (card out, grant missing, no access) stays for the next start; one whose item no
     * longer exists, or no longer has a leftover name, is struck out.
     */
    fun cleanup(context: Context, entries: List<String>) {
        for (entry in entries) {
            val done = try {
                if (entry.startsWith(SAF_PREFIX)) cleanSaf(context, entry) else cleanLocal(context, entry)
            } catch (e: Exception) {
                Log.w(TAG, "cleanup failed (${e.javaClass.simpleName})")
                false
            }
            if (done) remove(entry)
        }
    }

    private fun cleanLocal(context: Context, path: String): Boolean {
        val name = path.substringAfterLast('/')
        if (!path.startsWith("/") || !FileTypes.isLeftoverName(name)) return true
        val p = Paths.get(path)
        val attrs = try {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            // Gone for good, unless its volume is out right now. Other errors (no access) keep it.
            return !path.startsWith("/storage/") || AndroidStorage.get(context).mountPaths().any { LocalBackend.under(it, path) }
        }
        if (attrs.isDirectory) deleteLocalTree(p, 0) else Files.delete(p)
        return true
    }

    /** Never follows a link: a link inside staging is unlinked, not walked. */
    private fun deleteLocalTree(dir: Path, depth: Int) {
        if (depth > MAX_DEPTH) throw IOException("too deep")
        val children = Files.newDirectoryStream(dir).use { it.toList() }
        for (child in children) {
            val a = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (a.isDirectory) deleteLocalTree(child, depth + 1) else Files.delete(child)
        }
        Files.delete(dir)
    }

    private fun cleanSaf(context: Context, entry: String): Boolean {
        val parts = entry.split('|', limit = 3)
        if (parts.size != 3) return true
        val tree = Uri.parse(parts[1])
        // A grant missing now can come back when the user picks the same folder again.
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!granted) return false
        val backend = AndroidStorage.get(context).safBackend(tree)
        val node = try {
            backend.query(parts[2])
        } catch (e: FsException) {
            // GONE: the card is out. Try again next time.
            return e.reason != FsException.Reason.GONE
        } ?: return true
        if (!FileTypes.isLeftoverName(node.name)) return true
        Trees.deleteTree(node, null)
        return true
    }

    private fun readLive() {
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                lines++
                if (line.length < 2) return@forEachLine
                val entry = decode(line.substring(1)) ?: return@forEachLine
                when (line[0]) {
                    '+' -> live += entry
                    '-' -> live -= entry
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "journal unreadable")
        }
    }

    private fun append(line: String) {
        try {
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
            lines++
        } catch (e: IOException) {
            Log.w(TAG, "journal write failed")
        }
    }

    /** Starts the file over with the open entries alone. */
    private fun rewrite() {
        try {
            file.writeText(live.joinToString("") { "+" + encode(it) + "\n" })
            lines = live.size
        } catch (e: IOException) {
            Log.w(TAG, "journal write failed")
        }
    }

    companion object {
        private const val TAG = "LxJournal"
        private const val SAF_PREFIX = "saf|"
        private const val MAX_DEPTH = 256
        /** Lines after which the file is rewritten, so an entry that stays for long cannot make it grow without end. */
        private const val COMPACT_LINES = 256

        private fun encode(entry: String): String = URLEncoder.encode(entry, "UTF-8")

        private fun decode(text: String): String? = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        /** The journal form of [node], or null for a backend the journal cannot reach later. */
        fun entryOf(node: FsNode): String? = when (val b = node.backend) {
            is LocalBackend -> node.ref
            is SafBackend -> b.journalEntry(node.ref)
            // Any other backend that knows the real path is cleaned up through that path.
            is Located -> b.location(node)?.takeIf { it.startsWith("/") }
            else -> null
        }
    }
}
