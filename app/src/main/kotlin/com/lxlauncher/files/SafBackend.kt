// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.util.Log
import com.lxlauncher.files.FsException.Reason
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

/**
 * One folder the user granted through the system picker. Document ids are the refs. Every call
 * goes through DocumentsContract, so it works for any provider; ids of the device's own storage
 * provider ("primary:DCIM/Camera") also give parents, cheap lookups and free space.
 */
class SafBackend internal constructor(
    private val context: Context,
    val tree: Uri,
    private val storage: AndroidStorage,
) : Backend, Located {
    private val resolver: ContentResolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    /** The device's own storage provider, whose ids are "<volume>:<path>". */
    private val external = tree.authority == EXTERNAL_AUTHORITY && ':' in rootId

    override val key = "saf:$tree"

    @Volatile private var cachedRoot: FsNode? = null

    override val root: FsNode
        get() {
            cachedRoot?.let { return it }
            val fresh = runCatching { query(rootId) }.getOrNull()
            // Unreachable for now (revoked, card out): a placeholder that is not cached.
            return fresh?.also { cachedRoot = it } ?: FsNode(this, rootId, fallbackLabel(), true, 0, 0, FsNode.DIR_MIME, 0)
        }

    override val label: String by lazy {
        if (external && rootId.substringAfter(':').isEmpty()) {
            storage.volumeById(rootId.substringBefore(':'))?.label ?: fallbackLabel()
        } else {
            runCatching { query(rootId)?.name }.getOrNull() ?: fallbackLabel()
        }
    }

    /** The storage provider applies FAT rules to every name it creates, even on internal storage. */
    override fun fatNames(dir: FsNode): Boolean = external

    override fun children(dir: FsNode, cancel: Cancel?): List<FsNode> = list(dir, cancel, complete = false)

    /**
     * Lists [dir]. With [complete], a listing the provider marks as still loading or failed is an
     * error instead of a partial answer: a job that trusted it would miss files.
     */
    internal fun list(dir: FsNode, cancel: Cancel?, complete: Boolean): List<FsNode> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.ref)
        val deadline = System.nanoTime() + (if (complete) LOAD_WAIT_JOB_MS else LOAD_WAIT_BROWSE_MS) * 1_000_000
        while (true) {
            cancel?.check()
            val c = try {
                resolver.query(uri, PROJECTION, null, null, null)
            } catch (e: RuntimeException) {
                throw failure(e, displayPath(dir))
            } ?: throw missing(displayPath(dir))
            c.use {
                val extras = c.extras
                val loading = extras.getBoolean(DocumentsContract.EXTRA_LOADING)
                // Cloud providers fill a folder in the background; ask again shortly.
                if (loading && System.nanoTime() < deadline) return@use
                if (complete && (loading || extras.containsKey(DocumentsContract.EXTRA_ERROR))) {
                    throw FsErrors.io(extras.getString(DocumentsContract.EXTRA_ERROR) ?: "listing incomplete", displayPath(dir))
                }
                val out = ArrayList<FsNode>(c.count)
                var i = 0
                while (c.moveToNext()) {
                    if (i++ and 255 == 0) cancel?.check()
                    read(c)?.let { out += it }
                }
                return out
            }
            Thread.sleep(LOAD_POLL_MS)
        }
    }

    override fun child(dir: FsNode, name: String): FsNode? {
        if (name.isEmpty() || '/' in name) return null
        if (external) {
            // The provider resolves "<volume>:<path>/<name>" directly; a missing file is a null cursor.
            val id = if (dir.ref.endsWith(":")) dir.ref + name else dir.ref + "/" + name
            return try {
                query(id)
            } catch (e: FsException) {
                if (e.reason == Reason.GONE || e.reason == Reason.NO_ACCESS) throw e
                null
            }
        }
        // Exact names only: on a provider that tells "README.md" from "readme.md", taking one for
        // the other would offer to replace a file the user never picked.
        return named(list(dir, null, complete = true), name)
    }

    override fun parent(node: FsNode): FsNode? = parentId(node.ref)?.let { runCatching { query(it) }.getOrNull() }

    override fun refresh(node: FsNode): FsNode? = try {
        query(node.ref)
    } catch (e: FsException) {
        if (e.reason == Reason.NOT_FOUND) null else throw e
    }

    override fun createDir(dir: FsNode, name: String): FsNode = create(dir, name, FsNode.DIR_MIME)

    override fun createFile(dir: FsNode, name: String, mime: String): FsNode = create(dir, name, mime)

    override fun openRead(node: FsNode): ParcelFileDescriptor {
        val uri = uriOf(node.ref)
        val direct = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: FileNotFoundException) {
            throw FsException(Reason.NOT_FOUND, displayPath(node), e)
        } catch (e: RuntimeException) {
            // Some Android 13 builds lose the caller inside the MediaProvider hand-off and throw a
            // remote NullPointerException; the MediaStore item opens on this app's own behalf.
            Log.w(TAG, "document open failed (${e.javaClass.simpleName}), trying the MediaStore item")
            mediaFallback(uri) ?: throw failure(e, displayPath(node))
        }
        return direct ?: throw FsException(Reason.NOT_FOUND, displayPath(node))
    }

    override fun openWrite(node: FsNode): ParcelFileDescriptor {
        val uri = uriOf(node.ref)
        return try {
            resolver.openFileDescriptor(uri, "wt")
        } catch (e: IllegalArgumentException) {
            // A provider without "wt"; jobs only write to files they just created, so "w" is enough.
            try {
                resolver.openFileDescriptor(uri, "w")
            } catch (e2: Exception) {
                throw failure(e2, displayPath(node))
            }
        } catch (e: Exception) {
            throw failure(e, displayPath(node))
        } ?: throw FsException(Reason.NOT_FOUND, displayPath(node))
    }

    override fun rename(node: FsNode, newName: String): FsNode {
        if (newName == node.name) return node
        if (node.ref == rootId) throw FsException(Reason.NO_ACCESS, displayPath(node))
        if (FileTypes.nameProblem(newName, fatNames(node))) throw FsException(Reason.BAD_NAME, newName)
        val caseOnly = newName.lowercase(Locale.ROOT) == node.name.lowercase(Locale.ROOT)
        val parent = parent(node)
        if (parent != null && !caseOnly && child(parent, newName) != null) {
            throw FsException(Reason.EXISTS, newName)
        }
        if (caseOnly) {
            // The storage provider sees "A.txt" as taken by "a.txt" and would number it; go through a
            // free name. That name is no journal pattern, so no cleanup can ever delete the file.
            val step = renameRaw(node, node.name + "." + FileTypes.hex6())
            return try {
                renameExact(step, newName)
            } catch (e: FsException) {
                runCatching { renameRaw(step, node.name) }
                throw e
            }
        }
        return renameExact(node, newName)
    }

    override fun delete(node: FsNode) {
        if (node.ref == rootId) throw FsException(Reason.NO_ACCESS, displayPath(node))
        // deleteDocument removes whole trees; the contract is one file or one empty folder.
        if (node.isDir && list(node, null, complete = true).isNotEmpty()) {
            throw FsException(Reason.EXISTS, displayPath(node), IOException("folder not empty"))
        }
        val ok = try {
            DocumentsContract.deleteDocument(resolver, uriOf(node.ref))
        } catch (e: FileNotFoundException) {
            throw FsException(Reason.NOT_FOUND, displayPath(node), e)
        } catch (e: RuntimeException) {
            throw failure(e, displayPath(node))
        }
        if (!ok) throw FsErrors.io("delete refused", displayPath(node))
    }

    override fun moveInside(node: FsNode, destDir: FsNode, newName: String): FsNode? {
        val parentId = parentId(node.ref) ?: return null
        return moveWithParent(node, parentId, destDir, newName)
    }

    /**
     * [moveInside] for callers that know the parent document, which the contract cannot derive for
     * providers with opaque ids.
     */
    internal fun moveWithParent(node: FsNode, parentId: String, destDir: FsNode, newName: String): FsNode? {
        if (destDir.backend.key != key) return null
        if (!supports(node, Document.FLAG_SUPPORTS_MOVE)) return null
        if (node.isDir && contains(node, destDir)) throw FsException(Reason.TOO_DEEP, displayPath(destDir))
        if (FileTypes.nameProblem(newName, fatNames(destDir))) throw FsException(Reason.BAD_NAME, newName)
        if (parentId == destDir.ref) {
            return if (newName == node.name) node else rename(node, newName)
        }
        if (child(destDir, newName) != null) throw FsException(Reason.EXISTS, newName)
        var moving = node
        // moveDocument keeps the name, so a name taken in the destination is changed first, at the source.
        if (newName != node.name) {
            if (child(destDir, node.name) != null) {
                moving = try {
                    rename(node, newName)
                } catch (e: FsException) {
                    return null
                }
            }
        }
        val moved = try {
            DocumentsContract.moveDocument(resolver, uriOf(moving.ref), uriOf(parentId), uriOf(destDir.ref))
        } catch (e: Exception) {
            // Another volume, or a provider that refuses: undo a rename and let the caller copy.
            Log.w(TAG, "moveDocument failed (${e.javaClass.simpleName})")
            null
        }
        if (moved == null) {
            if (moving !== node) runCatching { renameRaw(moving, node.name) }
            return null
        }
        val movedNode = query(DocumentsContract.getDocumentId(moved)) ?: throw FsException(Reason.NOT_FOUND, newName)
        return if (movedNode.name == newName) movedNode else rename(movedNode, newName)
    }

    override fun freeBytes(dir: FsNode): Long {
        if (external) {
            val path = storage.volumeById(dir.ref.substringBefore(':'))?.path
            if (path != null) {
                try {
                    return StatFs(path).availableBytes
                } catch (e: IllegalArgumentException) {
                    // Fall through to the provider's own figure.
                }
            }
        }
        val authority = tree.authority ?: return -1
        return try {
            resolver.query(DocumentsContract.buildRootsUri(authority), ROOT_PROJECTION, null, null, null)?.use { c ->
                var best = -1L
                var bestLen = -1
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    if (c.isNull(1)) continue
                    if (rootCovers(id, rootId) && id.length > bestLen) {
                        bestLen = id.length
                        best = c.getLong(1)
                    }
                }
                best
            } ?: -1
        } catch (e: RuntimeException) {
            -1
        }
    }

    override fun shareUri(node: FsNode): Uri = uriOf(node.ref)

    override fun location(node: FsNode): String? {
        val authority = tree.authority ?: return null
        // The Downloads provider names some documents by path.
        if (node.ref.startsWith("raw:/")) return node.ref.substring(4)
        if (!external) return "id:$authority/${node.ref}"
        return externalLocation(authority, node.ref, storage.volumeById(node.ref.substringBefore(':'))?.path)
    }

    override fun contains(outer: FsNode, inner: FsNode): Boolean {
        if (outer.backend.key != key || inner.backend.key != key) return false
        if (outer.ref == inner.ref) return true
        if (external) {
            val o = outer.ref
            return if (o.endsWith(":")) inner.ref.startsWith(o) else inner.ref.startsWith("$o/")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return DocumentsContract.isChildDocument(resolver, uriOf(outer.ref), uriOf(inner.ref))
            } catch (e: Exception) {
                // The provider cannot tell; fall through.
            }
        }
        return false
    }

    override fun displayPath(node: FsNode): String {
        if (external) {
            val volume = node.ref.substringBefore(':')
            val rel = node.ref.substringAfter(':')
            val base = storage.volumeById(volume)?.let { it.path ?: it.label } ?: volume
            return if (rel.isEmpty()) base else "$base/$rel"
        }
        if (node.ref == rootId) return label
        val names = try {
            DocumentsContract.findDocumentPath(resolver, uriOf(node.ref))?.path
        } catch (e: Exception) {
            null
        }
        if (names != null && names.size <= MAX_PATH_LOOKUPS) {
            val start = names.indexOf(rootId)
            if (start >= 0) {
                val parts = names.drop(start + 1).map { id -> runCatching { query(id)?.name }.getOrNull() ?: "?" }
                return (listOf(label) + parts).joinToString("/")
            }
        }
        return "$label/${node.name}"
    }

    /** "saf|<tree>|<docId>", the journal's form of a node here. */
    internal fun journalEntry(ref: String) = "saf|$tree|$ref"

    /** Metadata of one document, or null when the provider has nothing under that id. */
    internal fun query(id: String): FsNode? {
        val c = try {
            resolver.query(uriOf(id), PROJECTION, null, null, null)
        } catch (e: IllegalArgumentException) {
            // The storage provider throws this from its tree check when the file does not exist.
            if (!granted() || (external && !volumeMounted())) throw FsException(Reason.GONE, id, e)
            return null
        } catch (e: RuntimeException) {
            throw failure(e, id)
        } ?: return null
        return c.use { if (it.moveToFirst()) read(it) else null }
    }

    private fun create(dir: FsNode, name: String, mime: String): FsNode {
        if (FileTypes.nameProblem(name, fatNames(dir))) throw FsException(Reason.BAD_NAME, name)
        // Some providers accept a second document of the same name, so look first. Part and staging
        // names are random; skipping the lookup spares a full listing per file on such providers.
        if (!FileTypes.isLeftoverName(name) && child(dir, name) != null) throw FsException(Reason.EXISTS, name)
        val made = try {
            DocumentsContract.createDocument(resolver, uriOf(dir.ref), mime, name)
        } catch (e: FileNotFoundException) {
            throw FsException(Reason.NOT_FOUND, displayPath(dir), e)
        } catch (e: RuntimeException) {
            throw failure(e, displayPath(dir))
        } ?: throw FsErrors.io("create refused", displayPath(dir))
        val node = query(DocumentsContract.getDocumentId(made)) ?: throw FsException(Reason.NOT_FOUND, name)
        if (node.name != name) {
            // The provider numbered or cleaned the name; the caller asked for this exact one.
            runCatching { DocumentsContract.deleteDocument(resolver, made) }
            throw FsException(if (taken(dir, name)) Reason.EXISTS else Reason.BAD_NAME, name)
        }
        return node
    }

    /** Renames and insists on the exact name; a numbered or cleaned result is undone. */
    private fun renameExact(node: FsNode, newName: String): FsNode {
        val renamed = renameRaw(node, newName)
        if (renamed.name == newName) return renamed
        runCatching { renameRaw(renamed, node.name) }
        val parent = parent(node)
        val clash = parent == null || runCatching { taken(parent, newName) }.getOrDefault(true)
        throw FsException(if (clash) Reason.EXISTS else Reason.BAD_NAME, newName)
    }

    /**
     * Whether a refused or renumbered name clashes with a sibling, ignoring case so that a provider
     * which ignores case counts too. It only picks the error to report; nothing is replaced on it.
     */
    private fun taken(dir: FsNode, name: String): Boolean =
        if (external) child(dir, name) != null else nameTaken(list(dir, null, complete = true), name)

    private fun renameRaw(node: FsNode, newName: String): FsNode {
        val uri = try {
            DocumentsContract.renameDocument(resolver, uriOf(node.ref), newName)
        } catch (e: FileNotFoundException) {
            throw FsException(Reason.NOT_FOUND, displayPath(node), e)
        } catch (e: RuntimeException) {
            throw failure(e, displayPath(node))
        }
        // A null Uri means the id did not change.
        val id = uri?.let { DocumentsContract.getDocumentId(it) } ?: node.ref
        return query(id) ?: throw FsException(Reason.NOT_FOUND, newName)
    }

    private fun supports(node: FsNode, flag: Int): Boolean {
        val c = try {
            resolver.query(uriOf(node.ref), arrayOf(Document.COLUMN_FLAGS), null, null, null)
        } catch (e: RuntimeException) {
            null
        } ?: return false
        return c.use { it.moveToFirst() && !it.isNull(0) && it.getInt(0) and flag != 0 }
    }

    private fun parentId(id: String): String? {
        if (!external || id == rootId) return null
        val colon = id.indexOf(':')
        val path = id.substring(colon + 1)
        val up = id.substring(0, colon + 1) + path.substringBeforeLast('/', "")
        // Never above the granted folder.
        val rootPath = rootId.substring(rootId.indexOf(':') + 1)
        val upPath = up.substring(colon + 1)
        val inside = rootPath.isEmpty() || upPath == rootPath || upPath.startsWith("$rootPath/")
        return if (inside && up.substringBefore(':') == rootId.substringBefore(':')) up else null
    }

    private fun read(c: Cursor): FsNode? {
        val id = c.getString(0) ?: return null
        val name = c.getString(1) ?: id.substringAfterLast('/')
        val mime = c.getString(2) ?: FileTypes.OCTET_STREAM
        val dir = mime == Document.MIME_TYPE_DIR
        val flags = if (c.isNull(5)) 0 else c.getInt(5)
        var caps = 0
        if (flags and Document.FLAG_SUPPORTS_WRITE != 0 && !dir) caps = caps or Caps.WRITE
        if (flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0 && dir) caps = caps or Caps.CREATE
        if (id != rootId) {
            if (flags and Document.FLAG_SUPPORTS_DELETE != 0) caps = caps or Caps.DELETE
            if (flags and Document.FLAG_SUPPORTS_RENAME != 0) caps = caps or Caps.RENAME
            // Without a native move, copy and delete still moves it.
            if (flags and (Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_DELETE) != 0) caps = caps or Caps.MOVE
        }
        return FsNode(
            backend = this,
            ref = id,
            name = name,
            isDir = dir,
            size = if (dir) 0 else if (c.isNull(3)) -1 else c.getLong(3),
            modified = if (c.isNull(4)) 0 else c.getLong(4),
            mime = mime,
            caps = caps,
        )
    }

    private fun mediaFallback(uri: Uri): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < 29) return null
        val media = try {
            MediaStore.getMediaUri(context, uri)
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore lookup failed (${e.javaClass.simpleName})")
            null
        } ?: return null
        return try {
            resolver.openFileDescriptor(media, "r")
        } catch (e: Exception) {
            null
        }
    }

    /** A revoked grant and a removed card both look like failures; tell them apart. */
    private fun failure(e: Exception, path: String): FsException {
        if (e is FsException) return e
        if (!granted()) return FsException(Reason.GONE, path, e)
        if (e is SecurityException) return FsException(Reason.NO_ACCESS, path, e)
        if (e is UnsupportedOperationException) return FsException(Reason.UNSUPPORTED, path, e)
        if (external && !volumeMounted()) return FsException(Reason.GONE, path, e)
        if (e is IllegalArgumentException) return FsException(Reason.NOT_FOUND, path, e)
        return FsErrors.of(e, path)
    }

    private fun missing(id: String): FsException {
        if (!granted() || (external && !volumeMounted())) return FsException(Reason.GONE, id)
        return FsException(Reason.NOT_FOUND, id)
    }

    private fun granted(): Boolean = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    private fun volumeMounted(): Boolean = storage.volumeById(rootId.substringBefore(':'))?.mounted ?: false

    private fun fallbackLabel(): String =
        if (external) rootId.substringAfter(':').substringAfterLast('/').ifEmpty { rootId.substringBefore(':') }
        else tree.authority.orEmpty()

    private fun uriOf(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    companion object {
        private const val TAG = "LxSaf"
        internal const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
        private const val LOAD_POLL_MS = 300L
        private const val LOAD_WAIT_BROWSE_MS = 5_000L
        private const val LOAD_WAIT_JOB_MS = 30_000L
        private const val MAX_PATH_LOOKUPS = 32

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        /** The child called exactly [name]. */
        internal fun named(children: List<FsNode>, name: String): FsNode? = children.firstOrNull { it.name == name }

        internal fun nameTaken(children: List<FsNode>, name: String): Boolean =
            children.any { it.name.equals(name, ignoreCase = true) }

        /**
         * True when the provider root [rootDocId] holds the tree [treeDocId]. A prefix counts only up
         * to a ':' or '/' boundary, so the opaque root "1" does not claim the tree "13".
         */
        internal fun rootCovers(rootDocId: String, treeDocId: String): Boolean {
            if (treeDocId == rootDocId) return true
            if (rootDocId.isEmpty() || !treeDocId.startsWith(rootDocId)) return false
            return rootDocId.endsWith(':') || rootDocId.endsWith('/') || treeDocId[rootDocId.length] in ":/"
        }

        /** Location of an external storage document: its path when the volume is mounted with one. */
        internal fun externalLocation(authority: String, docId: String, volumePath: String?): String {
            if (volumePath == null) return "doc:$authority/$docId"
            val rel = docId.substringAfter(':')
            return if (rel.isEmpty()) volumePath else "$volumePath/$rel"
        }

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
    }
}
