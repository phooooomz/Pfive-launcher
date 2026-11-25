// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import com.lxlauncher.files.Backend
import com.lxlauncher.files.FileServices
import com.lxlauncher.files.FsException
import com.lxlauncher.files.FsNode
import com.lxlauncher.files.LocalBackend
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text encodings Tedit reads and writes. */
enum class TextCodec(val id: String, val label: String, private val charsetName: String, val bom: ByteArray) {
    UTF8("utf8", "UTF-8", "UTF-8", ByteArray(0)),
    UTF8_BOM("utf8bom", "UTF-8 with BOM", "UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF16LE("utf16le", "UTF-16 LE", "UTF-16LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16BE("utf16be", "UTF-16 BE", "UTF-16BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    LATIN1("latin1", "ISO 8859-1", "ISO-8859-1", ByteArray(0)),
    CP1252("cp1252", "Windows 1252", "windows-1252", ByteArray(0));

    val charset: Charset get() = Charset.forName(charsetName)

    fun encode(text: String): ByteArray {
        val body = text.toByteArray(charset)
        if (bom.isEmpty()) return body
        return bom + body
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }

        /**
         * Reads a BOM, else tries UTF-8 strictly, else falls back to [fallback] (ISO 8859-1 keeps every byte). With [cut]
         * the bytes stop at a read limit, so a character split there does not count against UTF-8.
         */
        fun detect(bytes: ByteArray, len: Int, fallback: TextCodec, cut: Boolean = false): TextCodec {
            fun starts(b: ByteArray) = len >= b.size && b.indices.all { bytes[it] == b[it] }
            if (starts(UTF8_BOM.bom)) return UTF8_BOM
            if (starts(UTF16LE.bom)) return UTF16LE
            if (starts(UTF16BE.bom)) return UTF16BE
            val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                // A file cut at the read limit may end inside a character; ignore up to three bytes there.
                var end = len
                if (cut && end > 0) {
                    var k = end - 1
                    var back = 0
                    while (k >= 0 && back < 3 && (bytes[k].toInt() and 0xC0) == 0x80) { k--; back++ }
                    if (k >= 0 && (bytes[k].toInt() and 0xC0) == 0xC0) {
                        val need = when { bytes[k].toInt() and 0xF0 == 0xF0 -> 4; bytes[k].toInt() and 0xE0 == 0xE0 -> 3; else -> 2 }
                        if (end - k < need) end = k
                    }
                }
                dec.decode(ByteBuffer.wrap(bytes, 0, end))
                UTF8
            } catch (e: java.nio.charset.CharacterCodingException) {
                if (fallback == UTF8 || fallback == UTF8_BOM) LATIN1 else fallback
            }
        }

        fun decode(bytes: ByteArray, len: Int, codec: TextCodec): String {
            val skip = if (len >= codec.bom.size && codec.bom.indices.all { bytes[it] == codec.bom[it] }) codec.bom.size else 0
            return String(bytes, skip, len - skip, codec.charset)
        }

        /** True when the bytes look like a binary file: a NUL byte, or many control characters, in the first 8 KB. */
        fun looksBinary(bytes: ByteArray, len: Int): Boolean {
            if (len >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return false
            val n = minOf(len, 8192)
            var control = 0
            for (i in 0 until n) {
                val b = bytes[i].toInt() and 0xFF
                if (b == 0) return true
                if (b < 0x09 || (b in 0x0E..0x1F && b != 0x1B)) control++
            }
            return n > 0 && control * 10 > n
        }
    }
}

/** Where a document lives: a node of LX's file layer (local path or granted folder), or a single content Uri. */
sealed class DocSource {
    abstract val key: String

    data class Node(val backendKey: String, val ref: String) : DocSource() {
        override val key get() = "node:$backendKey\u0000$ref"
    }

    data class Content(val uri: String) : DocSource() {
        override val key get() = "uri:$uri"
    }

    fun toJson(): JSONObject = when (this) {
        is Node -> JSONObject().put("t", "node").put("b", backendKey).put("r", ref)
        is Content -> JSONObject().put("t", "uri").put("u", uri)
    }

    companion object {
        fun fromJson(o: JSONObject?): DocSource? = when (o?.optString("t")) {
            "node" -> Node(o.optString("b"), o.optString("r")).takeIf { it.backendKey.isNotEmpty() && it.ref.isNotEmpty() }
            "uri" -> Content(o.optString("u")).takeIf { it.uri.isNotEmpty() }
            else -> null
        }

        fun of(node: FsNode) = Node(node.backend.key, node.ref)
    }
}

/** Facts about a file on disk: enough to notice it changed under an open document. */
data class DiskStat(val size: Long, val modified: Long, val writable: Boolean)

/**
 * Reads and writes the bytes behind a [DocSource]. All of it blocks: call it from a worker thread. Writes to a plain
 * local path go to a temporary file beside the target and are renamed over it, so a failed write never leaves the
 * file half written. Other places (a granted folder, a path only LX Bridge reaches) are written in place.
 */
class TeditFiles(context: Context) {
    private val app = context.applicationContext
    private val storage = FileServices.storage(app)

    class Read(val bytes: ByteArray, val length: Int, val totalSize: Long, val truncated: Boolean, val stat: DiskStat)

    fun backendFor(key: String): Backend? = when {
        key == "local" -> storage.local()
        key.startsWith("saf:") -> runCatching { storage.saf(Uri.parse(key.removePrefix("saf:"))) }.getOrNull()
        else -> null
    }

    fun node(src: DocSource.Node): FsNode? {
        val backend = backendFor(src.backendKey) ?: return null
        val stub = FsNode(backend, src.ref, src.ref.substringAfterLast('/'), false, 0, 0, "", 0)
        return backend.refresh(stub)
    }

    fun nodeOrThrow(src: DocSource.Node): FsNode =
        node(src) ?: throw FsException(FsException.Reason.NOT_FOUND, src.ref.substringAfterLast('/'))

    fun displayPath(src: DocSource): String = when (src) {
        is DocSource.Node -> backendFor(src.backendKey)?.let { b ->
            runCatching { b.displayPath(FsNode(b, src.ref, "", false, 0, 0, "", 0)) }.getOrNull()
        } ?: src.ref
        is DocSource.Content -> Uri.parse(src.uri).let { it.lastPathSegment ?: it.toString() }
    }

    fun nameOf(src: DocSource): String = when (src) {
        is DocSource.Node -> node(src)?.name ?: src.ref.substringAfterLast('/')
        is DocSource.Content -> contentName(Uri.parse(src.uri)) ?: "untitled"
    }

    private fun contentName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun stat(src: DocSource): DiskStat? = when (src) {
        is DocSource.Node -> node(src)?.let { DiskStat(it.size, it.modified, it.can(com.lxlauncher.files.Caps.WRITE)) }
        is DocSource.Content -> runCatching {
            val uri = Uri.parse(src.uri)
            var size = -1L
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, "last_modified"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    size = if (c.isNull(0)) -1 else c.getLong(0)
                    val mi = c.getColumnIndex("last_modified")
                    return@runCatching DiskStat(size, if (mi >= 0 && !c.isNull(mi)) c.getLong(mi) else 0L, true)
                }
            }
            DiskStat(size, 0, true)
        }.getOrNull()
    }

    private fun open(src: DocSource): InputStream = when (src) {
        is DocSource.Node -> {
            val node = nodeOrThrow(src)
            ParcelInput(node.backend.openRead(node))
        }
        is DocSource.Content -> app.contentResolver.openInputStream(Uri.parse(src.uri))
            ?: throw FsException(FsException.Reason.NOT_FOUND, src.uri)
    }

    private class ParcelInput(private val pfd: android.os.ParcelFileDescriptor) : FileInputStream(pfd.fileDescriptor) {
        override fun close() {
            try { super.close() } finally { runCatching { pfd.close() } }
        }
    }

    /** Reads up to [limit] bytes, telling [progress] how far it got. */
    fun read(src: DocSource, limit: Long, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): Read {
        val st = stat(src) ?: throw FsException(FsException.Reason.NOT_FOUND, nameOf(src))
        open(src).use { input ->
            val out = ByteArrayOutputStream(if (st.size in 1..limit) st.size.toInt() else 64 * 1024)
            val buf = ByteArray(256 * 1024)
            var total = 0L
            var truncated = false
            while (true) {
                if (cancelled()) throw FsException(FsException.Reason.CANCELLED)
                val want = minOf(buf.size.toLong(), limit - total).toInt()
                if (want <= 0) {
                    truncated = input.read() >= 0
                    break
                }
                val r = input.read(buf, 0, want)
                if (r < 0) break
                out.write(buf, 0, r)
                total += r
                progress(total, st.size)
            }
            val bytes = out.toByteArray()
            return Read(bytes, bytes.size, maxOf(st.size, total), truncated, st)
        }
    }

    /** Writes [bytes] as the whole content of [src]. */
    fun write(src: DocSource, bytes: ByteArray) {
        when (src) {
            is DocSource.Content -> {
                val out = app.contentResolver.openOutputStream(Uri.parse(src.uri), "wt")
                    ?: throw FsException(FsException.Reason.NO_ACCESS, src.uri)
                out.use { it.write(bytes) }
            }
            is DocSource.Node -> {
                val node = nodeOrThrow(src)
                if (node.backend is LocalBackend && writeAtomic(File(node.ref), bytes)) return
                val pfd = node.backend.openWrite(node)
                FileOutputStream(pfd.fileDescriptor).use { it.write(bytes); it.flush(); runCatching { it.fd.sync() } }
                pfd.close()
                node.backend.commitWrite(node)
            }
        }
    }

    /**
     * Temporary file, sync, rename: the file is either the old one or the new one at every moment. False when the
     * folder is not open to LX directly (it is then written through the backend instead).
     */
    private fun writeAtomic(target: File, bytes: ByteArray): Boolean {
        val dir = target.parentFile ?: return false
        if (!dir.canWrite() || !target.canWrite()) return false
        // Renaming over a symbolic link would replace the link itself; write through it instead.
        val isLink = runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(target.absolutePath).st_mode) }.getOrDefault(true)
        if (isLink) return false
        val tmp = File(dir, ".${target.name}.tedit-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Os.rename(tmp.absolutePath, target.absolutePath)
            return true
        } catch (e: Exception) {
            tmp.delete()
            // FUSE folders that refuse rename over an existing file still take a write in place.
            if (e is android.system.ErrnoException) return false
            throw e
        }
    }

    /** Creates a file [name] in [dir] and returns its source. */
    fun create(dir: FsNode, name: String): DocSource.Node {
        val made = dir.backend.createFile(dir, name, android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "text/plain")
        return DocSource.of(made)
    }
}

/**
 * One open file (or an unsaved new one): its text, its undo history, how it is encoded and where it is saved.
 * Several editor views may show one document; each keeps its own cursor and scroll.
 */
class Document(val id: Long, var source: DocSource?, var name: String, language: Language) {
    val buffer = TextBuffer()
    val history = EditHistory(buffer)
    val highlighter = Highlighter(buffer, language, 20000)
    var language: Language
        get() = highlighter.language
        set(v) { highlighter.language = v }
    var codec: TextCodec = TextCodec.UTF8
    var eol: String = "\n"
    /** The file mixed line breaks when it was read; saving writes them all as [eol]. */
    var mixedEol = false
    var savedState = 0
    val dirty: Boolean get() = history.stateId != savedState || forcedDirty
    /** Set when a backup restored changes the file on disk does not have. */
    var forcedDirty = false
    var readOnly = false
    /** Why the document is read only, a string resource, or 0. */
    var readOnlyReason = 0
    var truncated = false
    var disk: DiskStat? = null
    var loading = false
    /** Set to stop a load that is still reading. */
    @Volatile var cancelLoad = false
    var loadError: String? = null
    /** Binary file shown as information instead of text. */
    var binary = false
    var image = false
    /** The bytes of an image, kept until the preview has decoded them. */
    var imageBytes: ByteArray? = null
    /** Size of the file on disk in bytes, also when only part of it was read. */
    var size = 0L
    /** Read progress between 0 and 1 for a large file, or -1 when unknown. */
    var loadProgress = -1f
    /** Unsaved changes from an earlier session were put back over a file that has changed on disk since. */
    var restoredOverChange = false
    /** What the language server reports for this document, drawn under the text and listed in Problems. */
    var diagnostics: List<com.lxlauncher.tedit.lsp.Diagnostic> = emptyList()
    val listeners = ArrayList<() -> Unit>()

    fun changed() = listeners.toList().forEach { it() }

    fun markSaved() {
        savedState = history.stateId
        forcedDirty = false
        changed()
    }

    /** The text as it goes to disk: lines joined with this document's line break. */
    fun textForSave(trimTrailing: Boolean, finalNewline: Boolean): String {
        val sb = StringBuilder(buffer.length + buffer.lineCount)
        val n = buffer.lineCount
        for (i in 0 until n) {
            var l = buffer.line(i)
            if (trimTrailing) l = l.trimEnd(' ', '\t')
            sb.append(l)
            if (i < n - 1) sb.append(eol)
        }
        if (finalNewline && n > 0 && buffer.line(n - 1).isNotEmpty()) sb.append(eol)
        return sb.toString()
    }

    val key: String get() = source?.key ?: "untitled:$id"

    companion object {
        /** The line break most used in [text]; null when it has none. Sets [mixed] when it holds more than one kind. */
        fun detectEol(text: String, mixed: BooleanArray): String? {
            var lf = 0
            var crlf = 0
            var cr = 0
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                if (c == '\r') {
                    if (i + 1 < n && text[i + 1] == '\n') { crlf++; i++ } else cr++
                } else if (c == '\n') lf++
                i++
            }
            mixed[0] = listOf(lf, crlf, cr).count { it > 0 } > 1
            return when {
                lf == 0 && crlf == 0 && cr == 0 -> null
                crlf >= lf && crlf >= cr -> "\r\n"
                lf >= cr -> "\n"
                else -> "\r"
            }
        }
    }
}

/**
 * Keeps unsaved changes across a closed window or a killed process: each dirty document is copied to LX's private
 * storage a moment after it changes, and removed when it is saved or closed without saving. Nothing leaves the phone.
 */
class Backups(context: Context) {
    private val dir = File(context.filesDir, "tedit/backup").apply { mkdirs() }

    private fun fileFor(key: String) = File(dir, Integer.toHexString(key.hashCode()) + "-" + key.length)

    fun save(doc: Document) {
        val base = fileFor(doc.key)
        val meta = JSONObject()
            .put("key", doc.key)
            .put("name", doc.name)
            .put("codec", doc.codec.id)
            .put("eol", doc.eol)
            .put("lang", doc.language.id)
            .put("diskModified", doc.disk?.modified ?: 0L)
            .put("diskSize", doc.disk?.size ?: -1L)
        doc.source?.let { meta.put("source", it.toJson()) }
        val tmp = File(base.path + ".tmp")
        tmp.writeText(doc.buffer.text(), Charsets.UTF_8)
        tmp.renameTo(File(base.path + ".txt"))
        File(base.path + ".json").writeText(meta.toString())
    }

    class Saved(val meta: JSONObject, val text: String)

    fun load(key: String): Saved? {
        val base = fileFor(key)
        val m = File(base.path + ".json")
        val t = File(base.path + ".txt")
        if (!m.exists() || !t.exists()) return null
        return runCatching {
            val meta = JSONObject(m.readText())
            if (meta.optString("key") != key) null else Saved(meta, t.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    fun delete(key: String) {
        val base = fileFor(key)
        File(base.path + ".json").delete()
        File(base.path + ".txt").delete()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun count(): Int = dir.listFiles()?.count { it.name.endsWith(".json") } ?: 0
}
