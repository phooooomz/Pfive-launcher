// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import java.io.File
import java.io.IOException
import java.util.Locale

/** A backend that can say where a node lives on the device, so two backends that reach one file agree. */
internal interface Located {
    /**
     * An absolute path ("/storage/emulated/0/DCIM") when the node has one, "doc:<authority>/<id>"
     * for a provider whose ids nest like paths, "id:<authority>/<id>" for opaque ids, or null.
     */
    fun location(node: FsNode): String?
}

/**
 * Identity across backends. All-files access and a folder grant can show the same file, and two
 * grants can overlap. A job that took such a file for a different one would replace or delete
 * its own source.
 */
internal object Aliases {
    private const val OPAQUE = "id:"

    /** True when [a] and [b] are the same file or folder. */
    fun same(a: FsNode, b: FsNode): Boolean {
        if (a.backend.key == b.backend.key && a.ref == b.ref) return true
        val la = locate(a) ?: return false
        val lb = locate(b) ?: return false
        return sameLocation(la, lb)
    }

    /** True when [inner] is [outer] or lies below it. */
    fun inside(outer: FsNode, inner: FsNode): Boolean {
        if (outer.backend.key == inner.backend.key && outer.backend.contains(outer, inner)) return true
        val o = locate(outer) ?: return false
        val i = locate(inner) ?: return false
        return sameLocation(o, i) || below(o, i)
    }

    /**
     * Shared storage and FAT cards ignore case, so "A.txt" and "a.txt" are one file there. Taking
     * two files for one costs a numbered copy or a skipped move; taking one file for two can lose it.
     */
    fun sameLocation(a: String, b: String): Boolean =
        if (a.startsWith(OPAQUE) || b.startsWith(OPAQUE)) a == b else a.equals(b, ignoreCase = true)

    /** True when [inner] lies strictly below [outer]; opaque ids have no hierarchy to compare. */
    fun below(outer: String, inner: String): Boolean {
        if (outer.startsWith(OPAQUE) || inner.startsWith(OPAQUE)) return false
        val o = outer.lowercase(Locale.ROOT)
        val i = inner.lowercase(Locale.ROOT)
        if (i.length <= o.length || !i.startsWith(o)) return false
        return o.endsWith('/') || o.endsWith(':') || i[o.length] == '/'
    }

    private fun locate(node: FsNode): String? {
        val raw = (node.backend as? Located)?.location(node) ?: return null
        if (!raw.startsWith("/")) return raw
        // "/sdcard/x" and "/storage/emulated/0/x" name one file through a link.
        return try {
            File(raw).canonicalPath
        } catch (e: IOException) {
            raw
        } catch (e: SecurityException) {
            raw
        }
    }
}
