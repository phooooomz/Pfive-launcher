// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.lsp

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LxTeditLsp"

/** An error reply from the language server. [code] is the JSON-RPC error code, for example -32801 (content modified). */
class LspException(val code: Int, message: String, val data: Any? = null) : Exception(message)

/**
 * JSON-RPC 2.0 over the LSP base protocol: `Content-Length` framed UTF-8 JSON on a pair of streams.
 *
 * Reading starts in the constructor, on a daemon thread named `tedit-lsp-read`. Params and results are org.json values
 * (JSONObject, JSONArray, String, Number, Boolean) or null; JSON null always arrives as Kotlin null.
 *
 * Threading: every callback ([onNotification], [onRequest], [onClosed] and the request callbacks) runs on one
 * callback thread, in the order the messages arrived, never on the reader thread. A callback may block on slow work,
 * but it must not wait for another reply from this connection: that reply is queued behind it and the wait never ends.
 * UI code has to post to the main thread itself. [request], [notify] and [close] may be called from any thread.
 */
class JsonRpc(
    input: InputStream,
    output: OutputStream,
    private val onNotification: (method: String, params: Any?) -> Unit,
    private val onRequest: (method: String, params: Any?) -> Any?,
    private val onClosed: (Throwable?) -> Unit,
) {
    private class Pending(val method: String, val callback: (Result<Any?>) -> Unit) {
        @Volatile var timeout: ScheduledFuture<*>? = null
    }

    // close() shuts the raw streams: a writer blocked inside the buffered stream holds its lock.
    private val rawInput = input
    private val rawOutput = output
    private val input = BufferedInputStream(input, 64 * 1024)
    private val output = BufferedOutputStream(output, 64 * 1024)
    private val writeLock = Any()
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val closed = AtomicBoolean(false)
    @Volatile private var closeCause: Throwable? = null

    // At most one thread, so callbacks keep message order; it exits when idle, so a closed connection leaks nothing.
    private val callbacks = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { r -> daemon(r, "tedit-lsp-callback") }
    private val timer = ScheduledThreadPoolExecutor(1) { r -> daemon(r, "tedit-lsp-timer") }.apply { removeOnCancelPolicy = true }

    init {
        daemon({ readLoop() }, "tedit-lsp-read").start()
    }

    val isClosed: Boolean get() = closed.get()

    /**
     * Sends a request and returns its id. [callback] runs exactly once: with the result, with an [LspException] for an
     * error reply, with a [TimeoutException] after [timeoutMs] (then `$/cancelRequest` goes to the server), or with an
     * IOException when the connection closes first. A [timeoutMs] of 0 or less waits without limit.
     */
    fun request(method: String, params: Any?, timeoutMs: Long = 10_000, callback: (Result<Any?>) -> Unit): Int {
        val id = nextId.getAndIncrement()
        val p = Pending(method, callback)
        pending[id] = p
        // Checked after the insert: close() marks closed before it drains, so one of the two always fails the call.
        if (closed.get()) {
            fail(id, closedError())
            return id
        }
        if (timeoutMs > 0) {
            try {
                p.timeout = timer.schedule(Runnable { expire(id, timeoutMs) }, timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: RejectedExecutionException) {
                fail(id, closedError())
                return id
            }
        }
        if (!send(message(method, params).put("id", id))) fail(id, closedError())
        return id
    }

    /** Sends a notification. Returns false when the connection is closed and nothing was written. */
    fun notify(method: String, params: Any?): Boolean = send(message(method, params))

    /** Closes both streams and fails every pending request. Calling it again does nothing. */
    fun close() = closeWith(null)

    /** Runs [block] on the callback thread, after every callback queued before it. */
    internal fun post(block: () -> Unit) {
        try {
            callbacks.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(TAG, "language server callback failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "callback dropped", e)
        }
    }

    private fun message(method: String, params: Any?): JSONObject {
        val m = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (params != null) m.put("params", params)
        return m
    }

    private fun send(message: JSONObject): Boolean {
        if (closed.get()) return false
        val body = message.toString().toByteArray(Charsets.UTF_8)
        return try {
            synchronized(writeLock) {
                // The header counts bytes, not characters: é, 中 and emoji take 2 to 4 bytes each.
                output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(body)
                output.flush()
            }
            true
        } catch (e: IOException) {
            closeWith(e)
            false
        }
    }

    private fun expire(id: Int, timeoutMs: Long) {
        val p = pending.remove(id) ?: return
        post { p.callback(Result.failure(TimeoutException("${p.method} got no reply in $timeoutMs ms"))) }
        notify("\$/cancelRequest", JSONObject().put("id", id))
    }

    private fun fail(id: Int, error: Throwable) {
        val p = pending.remove(id) ?: return
        p.timeout?.cancel(false)
        post { p.callback(Result.failure(error)) }
    }

    private fun closedError() = IOException("language server connection closed", closeCause)

    private fun closeWith(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closeCause = cause
        try { rawOutput.close() } catch (e: IOException) {}
        try { rawInput.close() } catch (e: IOException) {}
        timer.shutdownNow()
        val error = closedError()
        for (id in pending.keys.toList()) fail(id, error)
        post { onClosed(cause) }
    }

    private fun readLoop() {
        var error: Throwable? = null
        try {
            while (!closed.get()) dispatch(readMessage() ?: break)
        } catch (e: Exception) {
            error = e
        } catch (e: OutOfMemoryError) {
            error = e
        }
        closeWith(error)
    }

    /** One message body, or null when the stream ended cleanly between messages. */
    private fun readMessage(): ByteArray? {
        var length = -1L
        var sawHeader = false
        while (true) {
            val line = readHeaderLine(eofAllowed = !sawHeader) ?: return null
            if (line.isEmpty()) {
                if (sawHeader) break else continue
            }
            sawHeader = true
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toLongOrNull() ?: throw IOException("bad header: $line")
            }
        }
        if (length < 0) throw IOException("message without Content-Length")
        if (length > MAX_MESSAGE) throw IOException("message of $length bytes is over the ${MAX_MESSAGE shr 20} MB limit")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw EOFException("stream ended inside a message")
            read += n
        }
        return body
    }

    private fun readHeaderLine(eofAllowed: Boolean): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (eofAllowed && sb.isEmpty()) return null
                throw EOFException("stream ended inside a header")
            }
            if (b == '\n'.code) break
            if (sb.length >= MAX_HEADER_LINE) throw IOException("header line over $MAX_HEADER_LINE bytes")
            sb.append(b.toChar())
        }
        if (sb.endsWith('\r')) sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun dispatch(body: ByteArray) {
        val msg = try {
            JSONTokener(String(body, Charsets.UTF_8)).nextValue() as? JSONObject
        } catch (e: JSONException) {
            null
        }
        if (msg == null) {
            send(JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", error(PARSE_ERROR, "not a JSON-RPC message")))
            return
        }
        val method = msg.opt("method") as? String
        val id = plain(msg.opt("id"))
        val params = plain(msg.opt("params"))
        when {
            method != null && id != null -> post { answer(id, method, params) }
            method != null -> post { onNotification(method, params) }
            id != null -> complete(id, msg)
        }
    }

    private fun answer(id: Any, method: String, params: Any?) {
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
        try {
            reply.put("result", JSONObject.wrap(onRequest(method, params)) ?: JSONObject.NULL)
        } catch (e: UnsupportedOperationException) {
            reply.put("error", error(METHOD_NOT_FOUND, "Tedit does not handle $method"))
        } catch (e: Exception) {
            Log.w(TAG, "answering $method failed", e)
            reply.put("error", error(INTERNAL_ERROR, e.message ?: e.toString()))
        }
        send(reply)
    }

    private fun complete(id: Any, msg: JSONObject) {
        val key = (id as? Number)?.toInt() ?: (id as? String)?.toIntOrNull() ?: return
        val p = pending.remove(key) ?: return // late reply to a request that already timed out
        p.timeout?.cancel(false)
        val err = msg.optJSONObject("error")
        val result: Result<Any?> = if (err != null) {
            Result.failure(LspException(err.optInt("code"), err.opt("message") as? String ?: "error", plain(err.opt("data"))))
        } else {
            Result.success(plain(msg.opt("result")))
        }
        post { p.callback(result) }
    }

    private fun error(code: Int, message: String) = JSONObject().put("code", code).put("message", message)

    companion object {
        const val PARSE_ERROR = -32700
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL_ERROR = -32603
        const val REQUEST_CANCELLED = -32800
        const val CONTENT_MODIFIED = -32801

        /** Larger messages close the connection: no editor reply is that big, and the body is held in memory whole. */
        const val MAX_MESSAGE = 64L * 1024 * 1024
        private const val MAX_HEADER_LINE = 8 * 1024

        private fun daemon(r: Runnable, name: String) = Thread(r, name).apply { isDaemon = true }
    }
}

/**
 * A place in a document. [character] counts UTF-16 code units, the LSP default and the only encoding Tedit offers;
 * Kotlin String indexes count the same units, so a buffer column maps across unchanged.
 */
data class Position(val line: Int, val character: Int) {
    fun toJson(): JSONObject = JSONObject().put("line", line).put("character", character)
}

data class Range(val start: Position, val end: Position) {
    fun toJson(): JSONObject = JSONObject().put("start", start.toJson()).put("end", end.toJson())
}

data class Location(val uri: String, val range: Range)

/** [severity] is 1 error, 2 warning, 3 information, 4 hint; a server that leaves it out gets 1. */
data class Diagnostic(val range: Range, val severity: Int, val message: String, val source: String?, val code: String?)

data class TextEdit(val range: Range, val newText: String) {
    fun toJson(): JSONObject = JSONObject().put("range", range.toJson()).put("newText", newText)
}

/**
 * One completion proposal. [insertTextFormat] is 1 for plain text and 2 for a snippet with `$1`, `${2:name}` and
 * `$0` placeholders. For an InsertReplaceEdit, [textEdit] carries the `insert` range.
 */
data class CompletionItem(
    val label: String,
    val kind: Int?,
    val detail: String?,
    val documentation: String?,
    val insertText: String?,
    val insertTextFormat: Int,
    val textEdit: TextEdit?,
    val sortText: String?,
    val filterText: String?,
    val additionalTextEdits: List<TextEdit>,
) {
    /** The text the server wants inserted, before any snippet placeholders are expanded. */
    val textToInsert: String get() = textEdit?.newText ?: insertText ?: label
}

/** Receives what the server sends unasked. Methods run on the connection's callback thread, never the main thread. */
interface LspListener {
    fun onDiagnostics(uri: String, diagnostics: List<Diagnostic>) {}

    /** [type] is 1 error, 2 warning, 3 info, 4 log. */
    fun onMessage(type: Int, text: String) {}

    /** The connection ended: [error] is null for a clean end of stream or a local close. */
    fun onClosed(error: Throwable?) {}
}

/**
 * A client for one language server. The process is started elsewhere; [input] is its stdout and [output] its stdin.
 * [rootUri] is the workspace as the server sees it, so for a server in the Debian guest it must be a guest path.
 *
 * Every public method may be called from any thread and none blocks on the server. Callbacks and [listener] methods
 * run on the connection's callback thread (see [JsonRpc]); post to the main thread before touching views.
 *
 * Document notifications sent before [start] completes are held and sent right after `initialized`. Requests made
 * before then fail with IllegalStateException; requests for a feature the server lacks succeed with an empty result.
 */
class LspClient(input: InputStream, output: OutputStream, val rootUri: String?, private val listener: LspListener = object : LspListener {}) {
    private enum class State { NEW, STARTING, READY, FAILED, STOPPING, CLOSED }

    private val lock = Any()
    @Volatile private var state = State.NEW // written under lock
    private val held = ArrayList<Pair<String, JSONObject>>() // guarded by lock
    @Volatile private var saveIncludesText = false

    /** The server's capabilities from the `initialize` reply, or null before it. Read only; do not modify. */
    @Volatile var capabilities: JSONObject? = null
        private set

    /** Characters that open completion as they are typed, for example ".". Empty before [start] completes. */
    @Volatile var completionTriggerCharacters: Set<String> = emptySet()
        private set

    // Declared last: the reader thread starts here and may call the handlers below at once.
    private val rpc = JsonRpc(input, output, ::onServerNotification, ::onServerRequest, ::onConnectionClosed)

    val isReady: Boolean get() = state == State.READY
    val isClosed: Boolean get() = rpc.isClosed

    /**
     * Sends `initialize`, then `initialized`, and reports the server's capabilities. Servers in the guest can take a
     * while to index on start, so the default wait is longer than for other requests. After a failure the streams stay
     * open; call [shutdown] or [close].
     */
    fun start(initOptions: JSONObject?, timeoutMs: Long = 60_000, onReady: (Result<JSONObject>) -> Unit) {
        synchronized(lock) {
            if (state != State.NEW) {
                rpc.post { onReady(Result.failure(IllegalStateException("start() was already called"))) }
                return
            }
            state = State.STARTING
        }
        rpc.request("initialize", initializeParams(initOptions), timeoutMs) { result ->
            result.fold(
                onSuccess = { reply ->
                    val caps = (reply as? JSONObject)?.optJSONObject("capabilities") ?: JSONObject()
                    remember(caps)
                    val ready = synchronized(lock) {
                        if (state != State.STARTING) return@synchronized false
                        rpc.notify("initialized", JSONObject())
                        for ((method, params) in held) rpc.notify(method, params)
                        held.clear()
                        state = State.READY
                        true
                    }
                    onReady(if (ready) Result.success(caps) else Result.failure(IOException("language server stopped during initialize")))
                },
                onFailure = { e ->
                    synchronized(lock) {
                        if (state == State.STARTING) state = State.FAILED
                        held.clear()
                    }
                    onReady(Result.failure(e))
                },
            )
        }
    }

    fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        val doc = JSONObject().put("uri", uri).put("languageId", languageId).put("version", version).put("text", text)
        sendDoc("textDocument/didOpen", JSONObject().put("textDocument", doc))
    }

    /**
     * Sends the whole text on every change. It is the simplest correct choice: there are no ranges to get wrong, and a
     * server that asks for incremental sync still accepts a change event without a range as a full replacement.
     */
    fun didChange(uri: String, version: Int, fullText: String) {
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri).put("version", version))
            .put("contentChanges", JSONArray().put(JSONObject().put("text", fullText)))
        sendDoc(DID_CHANGE, params)
    }

    /** [text] goes along only when the server asked for it in its save options. */
    fun didSave(uri: String, text: String? = null) {
        val params = JSONObject().put("textDocument", docId(uri))
        if (text != null && saveIncludesText) params.put("text", text)
        sendDoc("textDocument/didSave", params)
    }

    fun didClose(uri: String) = sendDoc("textDocument/didClose", JSONObject().put("textDocument", docId(uri)))

    /** Completion at [pos]. Pass [triggerChar] when a trigger character was just typed. Takes a CompletionList or an array. */
    fun completion(uri: String, pos: Position, triggerChar: String? = null, cb: (Result<List<CompletionItem>>) -> Unit) {
        val context = JSONObject().put("triggerKind", if (triggerChar != null) 2 else 1)
        if (triggerChar != null) context.put("triggerCharacter", triggerChar)
        call("textDocument/completion", positionParams(uri, pos).put("context", context), "completionProvider", emptyList(), cb) { r ->
            val items = when (r) {
                is JSONArray -> r
                is JSONObject -> r.optJSONArray("items")
                else -> null
            }
            objects(items).mapNotNull { runCatching { completionItem(it) }.getOrNull() }
        }
    }

    /** Hover text at [pos], flattened to plain text (Markdown stays as written), or null when there is nothing. */
    fun hover(uri: String, pos: Position, cb: (Result<String?>) -> Unit) {
        call("textDocument/hover", positionParams(uri, pos), "hoverProvider", null, cb) { r ->
            hoverText((r as? JSONObject)?.opt("contents"))
        }
    }

    /** Where the symbol at [pos] is defined. Takes a Location, a Location array or a LocationLink array. */
    fun definition(uri: String, pos: Position, cb: (Result<List<Location>>) -> Unit) {
        call("textDocument/definition", positionParams(uri, pos), "definitionProvider", emptyList(), cb) { r ->
            when (r) {
                is JSONObject -> listOfNotNull(location(r))
                is JSONArray -> objects(r).mapNotNull(::location)
                else -> emptyList()
            }
        }
    }

    /** Edits that format the whole document, in the server's order; apply them against the text the server has. */
    fun formatting(uri: String, tabSize: Int, insertSpaces: Boolean, cb: (Result<List<TextEdit>>) -> Unit) {
        val params = JSONObject()
            .put("textDocument", docId(uri))
            .put("options", JSONObject().put("tabSize", tabSize).put("insertSpaces", insertSpaces))
        call("textDocument/formatting", params, "documentFormattingProvider", emptyList(), cb) { r -> textEdits(r as? JSONArray) }
    }

    /**
     * Sends `shutdown`, then `exit`, then closes the streams. A server that does not answer within 2 s gets `exit`
     * anyway. [onDone] runs once the streams are closed. Killing a process that ignores `exit` is the launcher's job.
     */
    fun shutdown(onDone: (() -> Unit)? = null) {
        val wasReady = synchronized(lock) {
            val ready = state == State.READY
            if (state != State.CLOSED) state = State.STOPPING
            held.clear()
            ready
        }
        if (!wasReady || rpc.isClosed) {
            rpc.close()
            rpc.post { onDone?.invoke() }
            return
        }
        rpc.request("shutdown", null, SHUTDOWN_WAIT_MS) {
            rpc.notify("exit", null)
            rpc.close()
            onDone?.invoke()
        }
    }

    /** Closes the streams at once, without the shutdown handshake. */
    fun close() = rpc.close()

    private fun sendDoc(method: String, params: JSONObject) {
        synchronized(lock) {
            when (state) {
                State.NEW, State.STARTING -> {
                    // Typing while the server starts would otherwise hold a full copy of the file per keystroke.
                    val last = held.lastOrNull()
                    if (method == DID_CHANGE && last != null && last.first == DID_CHANGE && uriOf(last.second) == uriOf(params)) {
                        held[held.size - 1] = method to params
                    } else {
                        held += method to params
                    }
                }
                State.READY -> rpc.notify(method, params)
                else -> Unit
            }
        }
    }

    private fun <T> call(method: String, params: JSONObject, provider: String, empty: T, cb: (Result<T>) -> Unit, parse: (Any?) -> T) {
        if (state != State.READY) {
            rpc.post { cb(Result.failure(IllegalStateException("language server is not ready"))) }
            return
        }
        val offered = capabilities?.opt(provider)
        if (offered != true && offered !is JSONObject) {
            rpc.post { cb(Result.success(empty)) }
            return
        }
        rpc.request(method, params) { r -> cb(r.mapCatching(parse)) }
    }

    private fun remember(caps: JSONObject) {
        capabilities = caps
        completionTriggerCharacters = strings(caps.optJSONObject("completionProvider")?.optJSONArray("triggerCharacters")).toSet()
        val save = (caps.opt("textDocumentSync") as? JSONObject)?.opt("save")
        saveIncludesText = (save as? JSONObject)?.optBoolean("includeText") == true
    }

    private fun initializeParams(initOptions: JSONObject?): JSONObject {
        val markupKinds = { JSONArray().put("plaintext").put("markdown") }
        val textDocument = JSONObject()
            .put("synchronization", JSONObject().put("didSave", true).put("willSave", false).put("willSaveWaitUntil", false))
            .put(
                "completion",
                JSONObject()
                    .put("completionItem", JSONObject().put("snippetSupport", true).put("documentationFormat", markupKinds()))
                    .put("contextSupport", true),
            )
            .put("hover", JSONObject().put("contentFormat", markupKinds()))
            .put("definition", JSONObject())
            .put("formatting", JSONObject())
            .put("publishDiagnostics", JSONObject().put("relatedInformation", false))
        val caps = JSONObject()
            .put("textDocument", textDocument)
            .put("workspace", JSONObject().put("configuration", true).put("workspaceFolders", true))
            .put("general", JSONObject().put("positionEncodings", JSONArray().put("utf-16")))
        val params = JSONObject()
            // The server sees another PID namespace, so LX's pid would point at the wrong process or none.
            .put("processId", JSONObject.NULL)
            .put("clientInfo", JSONObject().put("name", "Tedit"))
            .put("rootUri", rootUri ?: JSONObject.NULL)
            // Deprecated, but some older servers still read only this.
            .put("rootPath", rootUri?.let(::uriToPath) ?: JSONObject.NULL)
            .put("workspaceFolders", workspaceFolders())
            .put("capabilities", caps)
        if (initOptions != null) params.put("initializationOptions", initOptions)
        return params
    }

    private fun workspaceFolders(): Any {
        val root = rootUri ?: return JSONObject.NULL
        val name = uriToPath(root)?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { "/" } ?: root
        return JSONArray().put(JSONObject().put("uri", root).put("name", name))
    }

    private fun onServerRequest(method: String, params: Any?): Any? = when (method) {
        // Tedit keeps no per-server settings, and null tells the server to use its defaults.
        "workspace/configuration" -> JSONArray().apply {
            repeat((params as? JSONObject)?.optJSONArray("items")?.length() ?: 0) { put(JSONObject.NULL) }
        }
        "client/registerCapability", "client/unregisterCapability", "window/workDoneProgress/create" -> null
        "window/showMessageRequest" -> {
            showMessage(params)
            null
        }
        "workspace/workspaceFolders" -> workspaceFolders()
        else -> throw UnsupportedOperationException(method)
    }

    private fun onServerNotification(method: String, params: Any?) {
        when (method) {
            "textDocument/publishDiagnostics" -> {
                val p = params as? JSONObject ?: return
                val uri = p.opt("uri") as? String ?: return
                listener.onDiagnostics(uri, objects(p.optJSONArray("diagnostics")).mapNotNull { runCatching { diagnostic(it) }.getOrNull() })
            }
            "window/showMessage", "window/logMessage" -> showMessage(params)
            else -> Unit // $/progress, telemetry/event and the rest carry nothing Tedit shows
        }
    }

    private fun showMessage(params: Any?) {
        val p = params as? JSONObject ?: return
        listener.onMessage(p.optInt("type", 4), p.opt("message") as? String ?: return)
    }

    private fun onConnectionClosed(error: Throwable?) {
        synchronized(lock) {
            state = State.CLOSED
            held.clear()
        }
        listener.onClosed(error)
    }

    private companion object {
        const val DID_CHANGE = "textDocument/didChange"
        const val SHUTDOWN_WAIT_MS = 2_000L

        fun docId(uri: String): JSONObject = JSONObject().put("uri", uri)

        fun uriOf(params: JSONObject): String? = params.optJSONObject("textDocument")?.opt("uri") as? String

        fun positionParams(uri: String, pos: Position): JSONObject = JSONObject().put("textDocument", docId(uri)).put("position", pos.toJson())
    }
}

/** A `file://` URI for an absolute path. Everything except unreserved ASCII and "/" is percent-encoded as UTF-8. */
fun pathToUri(path: String): String {
    require(path.startsWith("/")) { "not an absolute path: $path" }
    val sb = StringBuilder(path.length + 16).append("file://")
    for (byte in path.toByteArray(Charsets.UTF_8)) {
        val b = byte.toInt() and 0xff
        val c = b.toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '/' || c == '-' || c == '.' || c == '_' || c == '~') {
            sb.append(c)
        } else {
            sb.append('%').append(HEX[b shr 4]).append(HEX[b and 15])
        }
    }
    return sb.toString()
}

/** The path in a `file:` URI, or null for another scheme or a remote host. Accepts unencoded characters too. */
fun uriToPath(uri: String): String? {
    if (!uri.regionMatches(0, "file:", 0, 5, ignoreCase = true)) return null
    var rest = uri.substring(5)
    if (rest.startsWith("//")) {
        val slash = rest.indexOf('/', 2)
        val host = if (slash < 0) rest.substring(2) else rest.substring(2, slash)
        if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
        rest = if (slash < 0) "" else rest.substring(slash)
    }
    rest = rest.substringBefore('#').substringBefore('?')
    if (!rest.startsWith("/")) return null
    val bytes = ByteArrayOutputStream(rest.length)
    var i = 0
    while (i < rest.length) {
        val c = rest[i]
        val hi = if (c == '%' && i + 2 < rest.length) Character.digit(rest[i + 1], 16) else -1
        val lo = if (hi >= 0) Character.digit(rest[i + 2], 16) else -1
        if (hi >= 0 && lo >= 0) {
            bytes.write(hi * 16 + lo)
            i += 3
        } else {
            val end = if (Character.isHighSurrogate(c) && i + 1 < rest.length) i + 2 else i + 1
            bytes.write(rest.substring(i, end).toByteArray(Charsets.UTF_8))
            i = end
        }
    }
    return bytes.toString("UTF-8")
}

private val HEX = "0123456789ABCDEF".toCharArray()

private fun plain(v: Any?): Any? = if (v == JSONObject.NULL) null else v

private fun objects(a: JSONArray?): List<JSONObject> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? JSONObject }

private fun strings(a: JSONArray?): List<String> =
    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

private fun position(o: JSONObject) = Position(o.getInt("line"), o.getInt("character"))

private fun range(o: JSONObject) = Range(position(o.getJSONObject("start")), position(o.getJSONObject("end")))

private fun textEdits(a: JSONArray?): List<TextEdit> =
    objects(a).mapNotNull { runCatching { TextEdit(range(it.getJSONObject("range")), it.getString("newText")) }.getOrNull() }

private fun diagnostic(o: JSONObject) = Diagnostic(
    range = range(o.getJSONObject("range")),
    severity = ((o.opt("severity") as? Number)?.toInt() ?: 1).coerceIn(1, 4),
    message = o.getString("message"),
    source = o.opt("source") as? String,
    code = plain(o.opt("code"))?.toString(),
)

private fun completionItem(o: JSONObject): CompletionItem {
    // A TextEdit has "range"; an InsertReplaceEdit has "insert" and "replace", and inserting keeps the text after the cursor.
    val edit = o.optJSONObject("textEdit")?.let { e ->
        val r = e.optJSONObject("range") ?: e.optJSONObject("insert") ?: return@let null
        TextEdit(range(r), e.getString("newText"))
    }
    return CompletionItem(
        label = o.getString("label"),
        kind = (o.opt("kind") as? Number)?.toInt(),
        detail = o.opt("detail") as? String,
        documentation = markup(o.opt("documentation")),
        insertText = o.opt("insertText") as? String,
        insertTextFormat = (o.opt("insertTextFormat") as? Number)?.toInt() ?: 1,
        textEdit = edit,
        sortText = o.opt("sortText") as? String,
        filterText = o.opt("filterText") as? String,
        additionalTextEdits = textEdits(o.optJSONArray("additionalTextEdits")),
    )
}

/** A plain string or MarkupContent's value. */
private fun markup(v: Any?): String? = when (v) {
    is String -> v
    is JSONObject -> v.opt("value") as? String
    else -> null
}

/** MarkupContent, a MarkedString (string or {language, value}) or an array of MarkedStrings, as text. */
private fun hoverText(v: Any?): String? {
    val text = when (v) {
        is String -> v
        is JSONObject -> v.opt("value") as? String
        is JSONArray -> (0 until v.length()).mapNotNull { hoverText(v.opt(it)) }.joinToString("\n\n")
        else -> null
    }
    return text?.takeIf { it.isNotBlank() }
}

/** A Location, or a LocationLink pointed at the name itself (targetSelectionRange) rather than the whole declaration. */
private fun location(o: JSONObject): Location? = runCatching {
    val target = o.opt("targetUri") as? String
    if (target != null) {
        Location(target, range(o.optJSONObject("targetSelectionRange") ?: o.getJSONObject("targetRange")))
    } else {
        Location(o.getString("uri"), range(o.getJSONObject("range")))
    }
}.getOrNull()
