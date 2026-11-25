// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.Context
import android.text.format.Formatter
import com.lxlauncher.R
import java.text.DateFormat
import java.util.Date

/** Where a File Manager window is looking. */
sealed class Place {
    /** A real folder. [chain] runs from the top of its backend to the folder, so Up and the path work on SAF too. */
    data class Folder(val chain: List<FsNode>) : Place() {
        val dir: FsNode get() = chain.last()
        fun into(child: FsNode) = Folder(chain + child)
    }
    /** The start page: storage, kinds of files, tools and, with LX Bridge, the system's own folders. */
    object Home : Place()
    /** The cleaner and its quick tools. */
    object Cleaner : Place()
    data class Category(val kind: FileCategory) : Place()
    object Recent : Place()
    data class Archives(val from: Folder) : Place()
    data class Search(val from: Folder, val query: String) : Place()
}

enum class SortKey { NAME, DATE, SIZE, TYPE }

/** Files the user cut or copied, shared by every File Manager window in the process. */
object FileClipboard {
    @Volatile var nodes: List<FsNode> = emptyList()
        private set
    @Volatile var move = false
        private set

    fun set(items: List<FsNode>, cut: Boolean) {
        nodes = items
        move = cut
    }

    fun clear() {
        nodes = emptyList()
        move = false
    }
}

object FmText {
    /** Folders first, then the chosen key; names compare the way people count ("file 2" before "file 10"). */
    fun comparator(key: SortKey, descending: Boolean): Comparator<FsNode> {
        val byName = Comparator<FsNode> { a, b -> natural(a.name, b.name) }
        val inner: Comparator<FsNode> = when (key) {
            SortKey.NAME -> byName
            SortKey.DATE -> compareBy<FsNode> { it.modified }.then(byName)
            SortKey.SIZE -> compareBy<FsNode> { it.size }.then(byName)
            SortKey.TYPE -> compareBy<FsNode> { it.extension }.then(byName)
        }
        val ordered = if (descending) inner.reversed() else inner
        return compareBy<FsNode> { !it.isDir }.then(ordered)
    }

    fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                // Compares the two numbers in place: leading zeros skipped, then length, then digit by digit.
                var si = i
                var sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                while (si < i && a[si] == '0') si++
                while (sj < j && b[sj] == '0') sj++
                val la = i - si
                val lb = j - sj
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val c = a[si + k].compareTo(b[sj + k])
                    if (c != 0) return c
                }
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    fun size(ctx: Context, bytes: Long): String = if (bytes < 0) "" else Formatter.formatShortFileSize(ctx, bytes)

    fun date(millis: Long): String =
        if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun isArchive(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz")
    }

    /** "MyProject.tar.gz" gives "MyProject". */
    fun archiveBase(name: String): String {
        val n = name.lowercase()
        val cut = when {
            n.endsWith(".tar.gz") -> 7
            n.endsWith(".tgz") || n.endsWith(".zip") || n.endsWith(".tar") -> 4
            else -> 0
        }
        return name.dropLast(cut).ifEmpty { name }
    }

    fun error(ctx: Context, e: Throwable?): String {
        val reason = (e as? FsException)?.reason ?: FsException.Reason.IO
        val res = when (reason) {
            FsException.Reason.NOT_FOUND -> R.string.fm_err_not_found
            FsException.Reason.EXISTS -> R.string.fm_err_exists
            FsException.Reason.NO_ACCESS -> R.string.fm_err_no_access
            FsException.Reason.READ_ONLY -> R.string.fm_err_read_only
            FsException.Reason.NO_SPACE -> R.string.fm_err_no_space
            FsException.Reason.BAD_NAME -> R.string.fm_err_bad_name
            FsException.Reason.UNSUPPORTED -> R.string.fm_err_unsupported
            FsException.Reason.GONE -> R.string.fm_err_gone
            FsException.Reason.CANCELLED -> R.string.fm_err_cancelled
            FsException.Reason.INCOMPLETE -> R.string.fm_err_incomplete
            FsException.Reason.TOO_DEEP -> R.string.fm_err_too_deep
            FsException.Reason.BAD_ARCHIVE -> R.string.fm_err_bad_archive
            FsException.Reason.IO -> R.string.fm_err_io
        }
        return ctx.getString(res)
    }

    /** Explains a PATH_ verdict from [NativeArchive.checkName], or null when the name is fine. */
    fun nameProblem(ctx: Context, verdict: Int, fat: Boolean): String? = when (verdict) {
        NativeArchive.PATH_OK -> null
        NativeArchive.PATH_EMPTY -> ctx.getString(R.string.fm_name_empty)
        NativeArchive.PATH_TOO_LONG, NativeArchive.PATH_COMPONENT_TOO_LONG -> ctx.getString(R.string.fm_name_too_long)
        NativeArchive.PATH_RESERVED -> ctx.getString(if (fat) R.string.fm_name_reserved_fat else R.string.fm_name_reserved)
        else -> ctx.getString(R.string.fm_name_bad_chars)
    }
}
