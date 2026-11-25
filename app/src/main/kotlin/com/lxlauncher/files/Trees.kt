// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

/** Walking helpers shared by jobs and the journal cleanup. */
internal object Trees {
    /** Deeper than this is refused: real folders do not nest so far, loops and attacks do. */
    const val MAX_DEPTH = 64

    /** Every child of [dir]; a provider that is still loading or reports an error fails instead. */
    fun listAll(dir: FsNode, cancel: Cancel?): List<FsNode> {
        val backend = dir.backend
        return if (backend is SafBackend) backend.list(dir, cancel, complete = true) else backend.children(dir, cancel)
    }

    /** A link, FIFO, socket or device on the local backend: copied or archived, it would mislead or hang. */
    fun isSpecial(node: FsNode): Boolean {
        val backend = node.backend
        return !node.isDir && backend is LocalBackend && backend.isSpecial(node)
    }

    /** Deletes [node] and everything below it, children first. Links are removed, never followed. */
    fun deleteTree(node: FsNode, cancel: Cancel?, depth: Int = 0) {
        cancel?.check()
        if (depth > MAX_DEPTH) throw FsException(FsException.Reason.TOO_DEEP, node.backend.displayPath(node))
        if (node.isDir) {
            for (child in listAll(node, cancel)) deleteTree(child, cancel, depth + 1)
        }
        node.backend.delete(node)
    }
}
