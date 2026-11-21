// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lxlauncher.R
import com.lxlauncher.files.FsException
import com.lxlauncher.files.FsNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Every document open in Tedit, how they load and save, and the lists of recent files and folders. Loading and
 * saving run on one background thread in order, so a save never overtakes the load before it.
 */
class DocStore(private val ctx: Context, private val prefs: TeditPrefs) {
    val files = TeditFiles(ctx)
    val backups = Backups(ctx)
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tedit-io").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    val docs = LinkedHashMap<String, Document>()
    private var nextId = (prefs.raw("nextDoc")?.toLongOrNull() ?: 1L)
    @Volatile var destroyed = false

    fun byKey(key: String) = docs[key]

    private fun newId(): Long {
        val id = nextId++
        prefs.putRaw("nextDoc", nextId.toString())
        return id
    }

    fun untitled(id: Long? = null): Document {
        val n = id ?: newId()
        val untitledCount = docs.values.count { it.source == null } + 1
        val d = Document(n, null, ctx.getString(R.string.tedit_untitled, untitledCount), Languages.PLAIN)
        d.codec = TextCodec.byId(prefs.string(TeditPrefs.ENCODING)) ?: TextCodec.UTF8
        d.eol = if (prefs.string(TeditPrefs.EOL) == "crlf") "\r\n" else "\n"
        d.history.limitChars = prefs.int(TeditPrefs.UNDO_MB) * 512L * 1024
        docs[d.key] = d
        restoreBackup(d)
        return d
    }

    /** The document for [src], loading it in the background when it is not open yet. */
    fun open(src: DocSource, onLoaded: (Document) -> Unit): Document {
        docs[src.key]?.let { return it }
        val name = when (src) {
            is DocSource.Node -> src.ref.substringAfterLast('/').ifEmpty { src.ref }
            is DocSource.Content -> ctx.getString(R.string.tedit_loading_name)
        }
        val d = Document(newId(), src, name, Languages.PLAIN)
        d.history.limitChars = prefs.int(TeditPrefs.UNDO_MB) * 512L * 1024
        d.loading = true
        docs[d.key] = d
        load(d, asText = false, onLoaded)
        return d
    }

    /** Reads (or reads again) [d] from disk. With [asText] a binary file is shown as text, read only. */
    fun load(d: Document, asText: Boolean, onLoaded: (Document) -> Unit) {
        val src = d.source ?: return
        d.loading = true
        d.cancelLoad = false
        d.loadError = null
        d.changed()
        val limitMb = prefs.int(TeditPrefs.LARGE_FILE_MB)
        val disabled = prefs.disabledExtensions()
        val fallback = TextCodec.byId(prefs.string(TeditPrefs.ENCODING)) ?: TextCodec.UTF8
        val started = SystemClock.uptimeMillis()
        io.execute {
            try {
                val name = files.nameOf(src)
                val limit = limitMb * 1024L * 1024L
                val read = files.read(src, limit, { done, total ->
                    if (total > 2_000_000) main.post { d.loadProgress = if (total > 0) done.toFloat() / total else -1f; d.changed() }
                }, { destroyed || d.cancelLoad })
                val isImage = !asText && imageExtensions.contains(name.substringAfterLast('.', "").lowercase())
                val binary = !asText && !isImage && TextCodec.looksBinary(read.bytes, read.length)
                var lines: ArrayList<String>? = null
                var codec = fallback
                var eol: String? = null
                val mixed = BooleanArray(1)
                var firstLine: String? = null
                if (!isImage && !binary) {
                    codec = if (asText) TextCodec.LATIN1 else TextCodec.detect(read.bytes, read.length, fallback, cut = read.truncated)
                    val text = TextCodec.decode(read.bytes, read.length, codec)
                    eol = Document.detectEol(text, mixed)
                    val out = ArrayList<String>(maxOf(16, read.length / 40))
                    TextBuffer.splitInto(text, out)
                    lines = out
                    firstLine = out.firstOrNull()
                }
                val lang = if (lines != null) Languages.detect(name, firstLine, disabled) else Languages.PLAIN
                val bytes = if (isImage) read.bytes else null
                main.post {
                    if (destroyed) return@post
                    d.name = name
                    d.disk = read.stat
                    d.image = isImage
                    d.binary = binary
                    d.imageBytes = bytes
                    d.size = read.totalSize
                    d.truncated = read.truncated
                    if (lines != null) {
                        d.buffer.setLines(lines)
                        d.history.clear()
                        d.codec = codec
                        d.eol = eol ?: if (prefs.string(TeditPrefs.EOL) == "crlf") "\r\n" else "\n"
                        d.mixedEol = mixed[0]
                        d.language = lang
                        d.savedState = d.history.stateId
                    }
                    d.readOnly = read.truncated || asText || !read.stat.writable
                    d.readOnlyReason = when {
                        read.truncated -> R.string.tedit_ro_large
                        asText -> R.string.tedit_ro_binary
                        !read.stat.writable -> R.string.tedit_ro_no_write
                        else -> 0
                    }
                    d.loading = false
                    if (lines != null && !d.readOnly) restoreBackup(d)
                    Log.d(TAG, "opened .${name.substringAfterLast('.', "-")} ${read.length} B, ${d.buffer.lineCount} lines in ${SystemClock.uptimeMillis() - started} ms")
                    d.changed()
                    onLoaded(d)
                }
            } catch (e: Throwable) {
                // A read stopped because its tab or window closed is not a failure.
                if ((e as? FsException)?.reason == FsException.Reason.CANCELLED) Log.d(TAG, "open cancelled")
                else Log.w(TAG, "open failed: ${e.javaClass.simpleName} ${(e as? FsException)?.reason ?: ""}")
                main.post {
                    if (destroyed) return@post
                    d.loading = false
                    d.loadError = errorText(e)
                    d.changed()
                    onLoaded(d)
                }
            }
        }
    }

    /** Puts back unsaved changes kept from an earlier session. */
    private fun restoreBackup(d: Document) {
        if (!prefs.bool(TeditPrefs.HOT_EXIT)) return
        val saved = backups.load(d.key) ?: return
        val text = saved.text
        if (d.source != null && text == d.buffer.text()) { backups.delete(d.key); return }
        d.buffer.setText(text)
        d.history.clear()
        d.forcedDirty = true
        TextCodec.byId(saved.meta.optString("codec"))?.let { d.codec = it }
        saved.meta.optString("eol").takeIf { it.isNotEmpty() }?.let { d.eol = it }
        if (d.source == null) {
            saved.meta.optString("name").takeIf { it.isNotEmpty() }?.let { d.name = it }
            Languages.byId(saved.meta.optString("lang"))?.let { d.language = it }
        }
        val diskThen = saved.meta.optLong("diskModified")
        d.restoredOverChange = d.source != null && d.disk != null && diskThen != 0L && d.disk?.modified != diskThen
    }

    fun backupSoon(d: Document) {
        if (!prefs.bool(TeditPrefs.HOT_EXIT)) return
        if (d.buffer.length > 8 * 1024 * 1024) return
        val key = d.key
        val dirty = d.dirty
        if (!dirty) { io.execute { backups.delete(key) }; return }
        // Copy the text on the main thread; the write happens on the I/O thread.
        val snapshot = Document(d.id, d.source, d.name, d.language).also {
            it.buffer.setText(d.buffer.text())
            it.codec = d.codec
            it.eol = d.eol
            it.disk = d.disk
        }
        io.execute { runCatching { backups.save(snapshot) }.onFailure { Log.w(TAG, "backup failed: ${it.javaClass.simpleName}") } }
    }

    /**
     * Saves [d]. When the file changed on disk since it was read, [confirmOverwrite] is asked first. [done] gets
     * null on success or the error text.
     */
    fun save(d: Document, target: DocSource? = null, confirmOverwrite: ((() -> Unit) -> Unit)?, done: (String?) -> Unit) {
        val src = target ?: d.source ?: return done(ctx.getString(R.string.tedit_err_no_place))
        val text = d.textForSave(false, prefs.bool(TeditPrefs.FINAL_NEWLINE))
        val codec = d.codec
        val stateAtSave = d.history.stateId
        val knownDisk = if (target == null) d.disk else null
        val started = SystemClock.uptimeMillis()
        io.execute {
            try {
                val bytes = codec.encode(text)
                // Characters the encoding cannot hold would be lost: say so instead of saving question marks.
                if (codec != TextCodec.UTF8 && codec != TextCodec.UTF8_BOM && codec != TextCodec.UTF16LE && codec != TextCodec.UTF16BE) {
                    val back = String(bytes, codec.charset)
                    if (back != text) { main.post { done(ctx.getString(R.string.tedit_err_codec, codec.label)) }; return@execute }
                }
                val now = files.stat(src)
                if (knownDisk != null && now != null && confirmOverwrite != null && (now.modified != knownDisk.modified || now.size != knownDisk.size) && knownDisk.modified != 0L) {
                    main.post {
                        confirmOverwrite {
                            io.execute { writeAndFinish(d, src, bytes, stateAtSave, started, done) }
                        }
                    }
                    return@execute
                }
                writeAndFinish(d, src, bytes, stateAtSave, started, done)
            } catch (e: Throwable) {
                Log.w(TAG, "save failed: ${e.javaClass.simpleName}")
                main.post { done(errorText(e)) }
            }
        }
    }

    private fun writeAndFinish(d: Document, src: DocSource, bytes: ByteArray, stateAtSave: Int, started: Long, done: (String?) -> Unit) {
        try {
            files.write(src, bytes)
            val stat = files.stat(src)
            val name = files.nameOf(src)
            Log.d(TAG, "saved .${name.substringAfterLast('.', "-")} ${bytes.size} B in ${SystemClock.uptimeMillis() - started} ms")
            backups.delete(d.key)
            main.post {
                if (d.source != src) {
                    docs.remove(d.key)
                    d.source = src
                    d.name = name
                    docs[d.key] = d
                    if (d.language === Languages.PLAIN) d.language = Languages.detect(name, d.buffer.line(0), prefs.disabledExtensions())
                }
                d.disk = stat
                d.readOnly = false
                d.readOnlyReason = 0
                // Edits made while the bytes were being written keep the document dirty.
                d.savedState = stateAtSave
                d.forcedDirty = false
                d.restoredOverChange = false
                d.changed()
                done(null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "save failed: ${e.javaClass.simpleName} ${(e as? FsException)?.reason ?: ""}")
            main.post { done(errorText(e)) }
        }
    }

    fun close(d: Document, keepBackup: Boolean) {
        docs.remove(d.key)
        if (!keepBackup) io.execute { backups.delete(d.key) }
        d.highlighter.dispose()
    }

    fun errorText(e: Throwable): String = when ((e as? FsException)?.reason) {
        FsException.Reason.NOT_FOUND -> ctx.getString(R.string.tedit_err_not_found)
        FsException.Reason.NO_ACCESS -> ctx.getString(R.string.tedit_err_no_access)
        FsException.Reason.READ_ONLY -> ctx.getString(R.string.tedit_err_read_only)
        FsException.Reason.NO_SPACE -> ctx.getString(R.string.tedit_err_no_space)
        FsException.Reason.GONE -> ctx.getString(R.string.tedit_err_gone)
        FsException.Reason.EXISTS -> ctx.getString(R.string.tedit_err_exists)
        FsException.Reason.BAD_NAME -> ctx.getString(R.string.tedit_err_bad_name)
        FsException.Reason.CANCELLED -> ctx.getString(R.string.tedit_err_cancelled)
        else -> when (e) {
            is SecurityException -> ctx.getString(R.string.tedit_err_no_access)
            is java.io.FileNotFoundException -> ctx.getString(R.string.tedit_err_not_found)
            is OutOfMemoryError -> ctx.getString(R.string.tedit_err_memory)
            else -> ctx.getString(R.string.tedit_err_io, e.javaClass.simpleName)
        }
    }

    // ---- recent files and folders ----

    class Recent(val source: DocSource, val name: String, val path: String)

    fun recents(kind: String): List<Recent> {
        val arr = runCatching { JSONArray(prefs.raw("recent.$kind") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val src = DocSource.fromJson(o.optJSONObject("src")) ?: return@mapNotNull null
            Recent(src, o.optString("name"), o.optString("path"))
        }
    }

    fun remember(kind: String, src: DocSource, name: String, path: String) {
        if (!prefs.bool(TeditPrefs.KEEP_HISTORY)) return
        val list = recents(kind).filter { it.source.key != src.key }.take(14)
        val arr = JSONArray()
        arr.put(JSONObject().put("src", src.toJson()).put("name", name).put("path", path))
        list.forEach { arr.put(JSONObject().put("src", it.source.toJson()).put("name", it.name).put("path", it.path)) }
        prefs.putRaw("recent.$kind", arr.toString())
    }

    fun forget(kind: String, key: String?) {
        if (key == null) { prefs.putRaw("recent.$kind", null); return }
        val arr = JSONArray()
        recents(kind).filter { it.source.key != key }.forEach { arr.put(JSONObject().put("src", it.source.toJson()).put("name", it.name).put("path", it.path)) }
        prefs.putRaw("recent.$kind", arr.toString())
    }

    fun shutdown() {
        destroyed = true
        io.shutdown()
    }

    companion object {
        const val TAG = "LxTedit"
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico", "avif")
    }
}

/** A workspace folder: the place Explorer, Search and Source Control work in. */
class WorkspaceRoot(val node: FsNode) {
    val source = DocSource.of(node)
    val name: String get() = node.name.ifEmpty { node.backend.label }
}
