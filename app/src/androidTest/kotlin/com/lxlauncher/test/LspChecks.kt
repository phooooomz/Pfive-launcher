// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import com.lxlauncher.tedit.lsp.CompletionItem
import com.lxlauncher.tedit.lsp.Diagnostic
import com.lxlauncher.tedit.lsp.JsonRpc
import com.lxlauncher.tedit.lsp.Location
import com.lxlauncher.tedit.lsp.LspClient
import com.lxlauncher.tedit.lsp.LspException
import com.lxlauncher.tedit.lsp.LspListener
import com.lxlauncher.tedit.lsp.Position
import com.lxlauncher.tedit.lsp.Range
import com.lxlauncher.tedit.lsp.TextEdit
import com.lxlauncher.tedit.lsp.pathToUri
import com.lxlauncher.tedit.lsp.uriToPath
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Checks for Tedit's language server client (12 November 2026): framing, server requests, timeouts, a connection lost
 * mid-request, a whole LspClient session and the file URI helpers. The servers are fakes in this process, joined to
 * the client by in-memory pipes, so neither the guest nor a real server is needed.
 */
internal class LspChecks(private val check: (String, () -> String?) -> Unit) {
    fun run() {
        framing()
        replies()
        timeout()
        closedMidRequest()
        session()
        startAndStop()
        uris()
    }

    private fun framing() {
        check("lsp reads messages whose header and body arrive in small pieces") {
            val server = RawServer()
            val got = LinkedBlockingQueue<String>()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { m, p -> got.put("$m ${(p as JSONObject).opt("message")}") }, { _, _ -> null }, {})
            try {
                val body = """{"jsonrpc":"2.0","method":"window/logMessage","params":{"type":4,"message":"$MULTIBYTE"}}""".toByteArray(Charsets.UTF_8)
                val acute = body.indexOfFirst { (it.toInt() and 0xff) == 0xC3 }
                val emoji = body.indexOfFirst { (it.toInt() and 0xff) >= 0xF0 }
                server.write("Cont".ascii())
                server.write("ent-Length: ${body.size}\r".ascii())
                server.write("\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n".ascii())
                server.write("\r\n".ascii())
                // Cut inside é (2 bytes) and inside the emoji (4 bytes).
                server.write(body.copyOfRange(0, acute + 1))
                server.write(body.copyOfRange(acute + 1, emoji + 2))
                server.write(body.copyOfRange(emoji + 2, body.size))
                // Two messages in one write, the first with a lowercase header name.
                val two = """{"jsonrpc":"2.0","method":"a","params":{"message":"two"}}""".toByteArray(Charsets.UTF_8)
                val three = """{"jsonrpc":"2.0","method":"b","params":{"message":"three"}}""".toByteArray(Charsets.UTF_8)
                server.write("content-length:${two.size}\r\n\r\n".ascii() + two + "Content-Length: ${three.size}\r\n\r\n".ascii() + three)
                val seen = (1..3).map { got.poll(5, TimeUnit.SECONDS) ?: return@check "message $it never arrived" }
                if (seen != listOf("window/logMessage $MULTIBYTE", "a two", "b three")) return@check "read $seen"
                null
            } finally {
                rpc.close()
            }
        }
        check("lsp writes Content-Length in bytes for multibyte text") {
            val server = RawServer()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { _, _ -> null }, {})
            try {
                rpc.notify("one", JSONObject().put("s", MULTIBYTE))
                rpc.notify("two", JSONObject().put("s", "plain"))
                // A character count would cut the first body short and the second message would never parse.
                val one = server.await("one") ?: return@check "first message unreadable"
                val two = server.await("two") ?: return@check "second message unreadable"
                if (one.getJSONObject("params").getString("s") != MULTIBYTE) return@check "first carried ${one.opt("params")}"
                if (two.getJSONObject("params").getString("s") != "plain") return@check "second carried ${two.opt("params")}"
                null
            } finally {
                rpc.close()
            }
        }
        check("lsp closes the connection on a message over 64 MB") {
            val server = RawServer()
            val closed = LinkedBlockingQueue<Any>()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { _, _ -> null }, { closed.put(it ?: "clean end") })
            try {
                server.write("Content-Length: ${JsonRpc.MAX_MESSAGE + 1}\r\n\r\n".ascii())
                val why = closed.poll(5, TimeUnit.SECONDS) ?: return@check "connection still open"
                if (why !is IOException) return@check "closed with $why"
                if (!rpc.isClosed) return@check "isClosed is false"
                null
            } finally {
                rpc.close()
            }
        }
    }

    private fun replies() {
        check("lsp answers server requests, with -32601 for unknown methods and -32603 for failures") {
            val server = RawServer()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { m, p ->
                when (m) {
                    "echo" -> p
                    "boom" -> throw IllegalStateException("boom")
                    else -> throw UnsupportedOperationException(m)
                }
            }, {})
            try {
                server.write(frame(request(7, "echo", JSONObject().put("s", MULTIBYTE))))
                server.write(frame(request("x8", "unknown/method", null)))
                server.write(frame(request(9, "boom", null)))
                if (!waitFor { server.replies().size >= 3 }) return@check "${server.replies().size} of 3 replies"
                val (a, b, c) = server.replies()
                if (a.opt("id") != 7 || a.optJSONObject("result")?.optString("s") != MULTIBYTE) return@check "echo reply $a"
                if (b.opt("id") != "x8" || b.optJSONObject("error")?.optInt("code") != -32601) return@check "unknown method reply $b"
                if (c.opt("id") != 9 || c.optJSONObject("error")?.optInt("code") != -32603) return@check "failure reply $c"
                null
            } finally {
                rpc.close()
            }
        }
        check("lsp turns an error reply into LspException with its code") {
            val server = RawServer()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { _, _ -> null }, {})
            try {
                val result = LinkedBlockingQueue<Result<Any?>>()
                rpc.request("textDocument/hover", JSONObject()) { result.put(it) }
                val req = server.await("textDocument/hover") ?: return@check "request never sent"
                val error = JSONObject().put("code", -32801).put("message", "content modified")
                server.write(frame(JSONObject().put("jsonrpc", "2.0").put("id", req.get("id")).put("error", error)))
                val failure = result.poll(5, TimeUnit.SECONDS)?.exceptionOrNull() ?: return@check "no failure"
                if (failure !is LspException || failure.code != -32801 || failure.message != "content modified") return@check "got $failure"
                null
            } finally {
                rpc.close()
            }
        }
    }

    private fun timeout() {
        check("lsp fails a request that gets no reply and sends \$/cancelRequest") {
            val server = RawServer()
            val result = LinkedBlockingQueue<Result<Any?>>()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { _, _ -> null }, {})
            try {
                val started = System.nanoTime()
                val id = rpc.request("slow", null, timeoutMs = 300) { result.put(it) }
                val r = result.poll(5, TimeUnit.SECONDS) ?: return@check "no callback after 5 s"
                val failure = r.exceptionOrNull() ?: return@check "succeeded with ${r.getOrNull()}"
                if (failure !is TimeoutException) return@check "failed with $failure"
                val waited = (System.nanoTime() - started) / 1_000_000
                if (waited < 250) return@check "gave up after only $waited ms"
                val cancel = server.await("\$/cancelRequest") ?: return@check "no \$/cancelRequest"
                if (cancel.getJSONObject("params").getInt("id") != id) return@check "cancelled ${cancel.opt("params")}, request was $id"
                server.write(frame(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", 1)))
                if (result.poll(300, TimeUnit.MILLISECONDS) != null) return@check "a late reply ran the callback again"
                null
            } finally {
                rpc.close()
            }
        }
    }

    private fun closedMidRequest() {
        check("lsp fails pending requests when the server's output ends") {
            val server = RawServer()
            val result = LinkedBlockingQueue<Result<Any?>>()
            val closed = LinkedBlockingQueue<Any>()
            val rpc = JsonRpc(server.toClient.input, server.fromClient.output, { _, _ -> }, { _, _ -> null }, { closed.put(it ?: "clean end") })
            try {
                rpc.request("never", null, timeoutMs = 30_000) { result.put(it) }
                server.await("never") ?: return@check "request never sent"
                server.toClient.end()
                val r = result.poll(5, TimeUnit.SECONDS) ?: return@check "pending request not failed within 5 s"
                val failure = r.exceptionOrNull() ?: return@check "succeeded with ${r.getOrNull()}"
                if (failure !is IOException) return@check "failed with $failure"
                if (closed.poll(5, TimeUnit.SECONDS) == null) return@check "onClosed never ran"
                rpc.request("after", null) { result.put(it) }
                if (result.poll(2, TimeUnit.SECONDS)?.isFailure != true) return@check "a request after close did not fail at once"
                null
            } finally {
                rpc.close()
            }
        }
    }

    private fun session() {
        check("lsp client session: initialize, diagnostics, configuration, completion, hover, definition, formatting, shutdown") {
            val toClient = Pipe()
            val toServer = Pipe()
            val seen = Collections.synchronizedList(ArrayList<String>())
            val params = ConcurrentHashMap<String, Any>()
            val configReply = LinkedBlockingQueue<Any>()
            val docUri = pathToUri("/w/a.py")
            lateinit var server: JsonRpc
            server = JsonRpc(
                toServer.input,
                toClient.output,
                onNotification = { m, p ->
                    seen += m
                    if (p != null) params[m] = p
                    when (m) {
                        "textDocument/didOpen" -> {
                            val items = JSONArray().put(JSONObject().put("section", "python")).put(JSONObject().put("section", "pylsp"))
                            server.request("workspace/configuration", JSONObject().put("items", items)) { r ->
                                configReply.put(r.getOrNull() ?: r.exceptionOrNull() ?: "null result")
                            }
                            val diagnostic = JSONObject().put("range", jrange(0, 0, 0, 2)).put("severity", 2).put("code", 501).put("source", "fake").put("message", MULTIBYTE)
                            server.notify("textDocument/publishDiagnostics", JSONObject().put("uri", docUri).put("diagnostics", JSONArray().put(diagnostic)))
                        }
                        "exit" -> server.close()
                    }
                },
                onRequest = { m, p ->
                    seen += m
                    if (p != null) params[m] = p
                    fakeAnswer(m)
                },
                onClosed = {},
            )
            val published = LinkedBlockingQueue<Pair<String, List<Diagnostic>>>()
            val listener = object : LspListener {
                override fun onDiagnostics(uri: String, diagnostics: List<Diagnostic>) = published.put(uri to diagnostics)
            }
            val client = LspClient(toClient.input, toServer.output, pathToUri("/w"), listener)
            try {
                val ready = await<JSONObject> { client.start(JSONObject().put("plugins", JSONObject()), onReady = it) }
                    ?: return@check "initialize got no answer in 5 s"
                ready.exceptionOrNull()?.let { return@check "initialize failed: $it" }
                if (client.completionTriggerCharacters != setOf(".")) return@check "trigger characters ${client.completionTriggerCharacters}"
                initializeProblem(params["initialize"] as? JSONObject)?.let { return@check it }

                client.didOpen(docUri, "python", 1, "xs.ap")
                val (uri, list) = published.poll(5, TimeUnit.SECONDS) ?: return@check "no diagnostics in 5 s"
                val d = list.singleOrNull() ?: return@check "${list.size} diagnostics"
                if (uri != docUri || d.message != MULTIBYTE) return@check "diagnostic '${d.message}' for $uri"
                if (d.severity != 2 || d.code != "501" || d.source != "fake" || d.range != range(0, 0, 0, 2)) return@check "diagnostic $d"
                val config = configReply.poll(5, TimeUnit.SECONDS) as? JSONArray ?: return@check "workspace/configuration answer missing"
                if (config.length() != 2 || !config.isNull(0) || !config.isNull(1)) return@check "workspace/configuration answered $config"
                val order = synchronized(seen) { seen.take(3) }
                if (order != listOf("initialize", "initialized", "textDocument/didOpen")) return@check "server saw $order"

                val items = await<List<CompletionItem>> { client.completion(docUri, Position(0, 3), ".", it) }
                    ?.getOrElse { return@check "completion failed: $it" } ?: return@check "completion got no answer"
                val context = (params["textDocument/completion"] as? JSONObject)?.optJSONObject("context")
                if (context?.optInt("triggerKind") != 2 || context?.optString("triggerCharacter") != ".") return@check "completion context $context"
                if (items.size != 2) return@check "${items.size} completion items"
                val (append, loop) = items
                if (append.textEdit != TextEdit(range(0, 3, 0, 5), "append")) return@check "InsertReplaceEdit read as ${append.textEdit}"
                if (append.documentation != "Adds **x**." || append.kind != 2 || append.detail != "list.append(x)") return@check "item $append"
                if (loop.insertTextFormat != 2 || loop.textToInsert != SNIPPET) return@check "snippet item $loop"

                val hover = await<String?> { client.hover(docUri, Position(0, 1), it) }
                if (hover?.getOrNull() != HOVER) return@check "hover gave $hover"
                val definition = await<List<Location>> { client.definition(docUri, Position(0, 1), it) }
                if (definition?.getOrNull() != listOf(Location("file:///w/b.py", range(1, 4, 1, 7)))) return@check "definition gave $definition"
                val edits = await<List<TextEdit>> { client.formatting(docUri, 4, true, it) }
                if (edits?.getOrNull() != listOf(TextEdit(range(0, 0, 0, 3), "    "))) return@check "formatting gave $edits"
                val options = (params["textDocument/formatting"] as? JSONObject)?.optJSONObject("options")
                if (options?.optInt("tabSize") != 4 || options?.optBoolean("insertSpaces") != true) return@check "formatting options $options"

                client.didSave(docUri, "xs.append")
                if (!waitFor { params.containsKey("textDocument/didSave") }) return@check "didSave never arrived"
                if ((params["textDocument/didSave"] as JSONObject).optString("text") != "xs.append") return@check "didSave left out the text the server asked for"

                val done = CountDownLatch(1)
                client.shutdown { done.countDown() }
                if (!done.await(3, TimeUnit.SECONDS)) return@check "shutdown did not finish in 3 s"
                if (!client.isClosed) return@check "client still open after shutdown"
                if (!waitFor { "exit" in seen }) return@check "server never got exit"
                val tail = synchronized(seen) { seen.takeLast(2) }
                if (tail != listOf("shutdown", "exit")) return@check "server ended with $tail"
                null
            } finally {
                client.close()
                server.close()
            }
        }
    }

    private fun startAndStop() {
        check("lsp client holds document changes until initialized and sends only the newest change") {
            val server = RawServer()
            val client = LspClient(server.toClient.input, server.fromClient.output, null)
            try {
                val early = await<String?> { client.hover("file:///x", Position(0, 0), it) }
                if (early?.exceptionOrNull() !is IllegalStateException) return@check "hover before start gave $early"
                client.didOpen("file:///x", "plaintext", 1, "a")
                client.didChange("file:///x", 2, "ab")
                client.didChange("file:///x", 3, "abc")
                val ready = LinkedBlockingQueue<Result<JSONObject>>()
                client.start(null) { ready.put(it) }
                val init = server.await("initialize") ?: return@check "initialize never sent"
                if (!init.getJSONObject("params").isNull("rootUri")) return@check "rootUri ${init.getJSONObject("params").opt("rootUri")}"
                server.reply(init.get("id"), JSONObject().put("capabilities", JSONObject()))
                if (ready.poll(5, TimeUnit.SECONDS)?.isSuccess != true) return@check "start did not succeed"
                val change = server.await("textDocument/didChange") ?: return@check "held changes never sent"
                val methods = server.methods()
                if (methods != listOf("initialize", "initialized", "textDocument/didOpen", "textDocument/didChange")) return@check "server saw $methods"
                val version = change.getJSONObject("params").getJSONObject("textDocument").getInt("version")
                if (version != 3) return@check "sent change version $version, expected only 3"
                // The server offered no hover, so the answer is empty and nothing goes over the wire.
                val hover = await<String?> { client.hover("file:///x", Position(0, 0), it) }
                if (hover == null || hover.isFailure || hover.getOrNull() != null) return@check "hover without hoverProvider gave $hover"
                if ("textDocument/hover" in server.methods()) return@check "hover was sent to a server without hoverProvider"
                null
            } finally {
                client.close()
            }
        }
        check("lsp client shutdown sends exit after 2 s when the server never answers") {
            val server = RawServer()
            val client = LspClient(server.toClient.input, server.fromClient.output, pathToUri("/w"))
            try {
                val ready = LinkedBlockingQueue<Result<JSONObject>>()
                client.start(null) { ready.put(it) }
                val init = server.await("initialize") ?: return@check "initialize never sent"
                server.reply(init.get("id"), JSONObject().put("capabilities", JSONObject()))
                if (ready.poll(5, TimeUnit.SECONDS)?.isSuccess != true) return@check "start did not succeed"
                val done = CountDownLatch(1)
                val started = System.nanoTime()
                client.shutdown { done.countDown() }
                if (!done.await(4, TimeUnit.SECONDS)) return@check "shutdown still waiting after 4 s"
                val waited = (System.nanoTime() - started) / 1_000_000
                if (waited < 1_500) return@check "gave up after $waited ms, before the 2 s cap"
                if (server.await("exit") == null) return@check "exit never sent"
                if (!client.isClosed) return@check "client still open"
                null
            } finally {
                client.close()
            }
        }
    }

    private fun uris() {
        check("lsp file URIs round-trip spaces, #, % and non-ASCII names") {
            val path = "/storage/emulated/0/My Code/#1 50% caf\u00e9/\u4e2d.py"
            val uri = pathToUri(path)
            if (uri != "file:///storage/emulated/0/My%20Code/%231%2050%25%20caf%C3%A9/%E4%B8%AD.py") return@check "encoded as $uri"
            if (uriToPath(uri) != path) return@check "decoded back as ${uriToPath(uri)}"
            if (uriToPath("file://localhost/a%20b") != "/a b") return@check "localhost gave ${uriToPath("file://localhost/a%20b")}"
            if (uriToPath("file:///C%3A/x") != "/C:/x") return@check "%3A gave ${uriToPath("file:///C%3A/x")}"
            if (uriToPath("file:///raw caf\u00e9") != "/raw caf\u00e9") return@check "unencoded URI gave ${uriToPath("file:///raw caf\u00e9")}"
            if (uriToPath("https://example.org/x") != null || uriToPath("file://host/x") != null) return@check "accepted a non-local URI"
            null
        }
    }

    private fun initializeProblem(p: JSONObject?): String? {
        if (p == null) return "initialize params missing"
        if (!p.isNull("processId")) return "processId ${p.opt("processId")}"
        if (p.optJSONObject("clientInfo")?.optString("name") != "Tedit") return "clientInfo ${p.opt("clientInfo")}"
        if (p.optString("rootUri") != "file:///w") return "rootUri ${p.opt("rootUri")}"
        if (p.optJSONArray("workspaceFolders")?.optJSONObject(0)?.optString("uri") != "file:///w") return "workspaceFolders ${p.opt("workspaceFolders")}"
        if (p.optJSONObject("initializationOptions")?.has("plugins") != true) return "initializationOptions ${p.opt("initializationOptions")}"
        val caps = p.optJSONObject("capabilities") ?: return "no capabilities"
        if (caps.optJSONObject("general")?.optJSONArray("positionEncodings")?.optString(0) != "utf-16") return "positionEncodings ${caps.opt("general")}"
        val doc = caps.optJSONObject("textDocument") ?: return "no textDocument capabilities"
        if (doc.optJSONObject("completion")?.optJSONObject("completionItem")?.optBoolean("snippetSupport") != true) return "snippetSupport ${doc.opt("completion")}"
        if (doc.optJSONObject("synchronization")?.optBoolean("didSave") != true) return "synchronization ${doc.opt("synchronization")}"
        val workspace = caps.optJSONObject("workspace")
        if (workspace?.optBoolean("configuration") != true || workspace?.optBoolean("workspaceFolders") != true) return "workspace capabilities $workspace"
        return null
    }

    /** A pipe that keeps every write as its own chunk, so the reader sees exactly the splits the writer made. */
    private class Pipe {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        @Volatile private var ended = false

        val input: InputStream = object : InputStream() {
            private var cur = ByteArray(0)
            private var pos = 0

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (pos >= cur.size) {
                    val next = chunks.take()
                    if (next === END) {
                        chunks.put(END)
                        return -1
                    }
                    cur = next
                    pos = 0
                }
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }

            override fun close() = end()
        }

        val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (ended) throw IOException("pipe closed")
                if (len > 0) chunks.put(b.copyOfRange(off, off + len))
            }

            override fun close() = end()
        }

        fun end() {
            if (ended) return
            ended = true
            chunks.put(END)
        }

        companion object {
            private val END = ByteArray(0)
        }
    }

    /** The far end of a client: logs every message the client sends, and the check writes raw bytes back. */
    private class RawServer {
        val toClient = Pipe()
        val fromClient = Pipe()
        private val log = ArrayList<JSONObject>()

        init {
            thread(isDaemon = true, name = "lsp-check-server") {
                while (true) {
                    val m = try {
                        readFrame(fromClient.input)
                    } catch (e: Exception) {
                        null
                    } ?: break
                    synchronized(log) { log += m }
                }
            }
        }

        fun write(bytes: ByteArray) = toClient.output.write(bytes)

        fun reply(id: Any, result: Any?) = write(frame(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result ?: JSONObject.NULL)))

        fun methods(): List<String> = synchronized(log) { log.mapNotNull { it.opt("method") as? String } }

        fun replies(): List<JSONObject> = synchronized(log) { log.filter { !it.has("method") } }

        fun await(method: String, timeoutMs: Long = 5_000): JSONObject? {
            var found: JSONObject? = null
            waitFor(timeoutMs) {
                found = synchronized(log) { log.firstOrNull { it.opt("method") == method } }
                found != null
            }
            return found
        }
    }

    private companion object {
        const val MULTIBYTE = "café 中文 😀 ok"
        const val HOVER = "**f**(x)\n\nReturns $MULTIBYTE."
        const val SNIPPET = "for \${1:x} in \${2:xs}:\n\t\$0"

        fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)

        fun request(id: Any, method: String, params: Any?): JSONObject {
            val m = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
            if (params != null) m.put("params", params)
            return m
        }

        fun frame(json: JSONObject): ByteArray {
            val body = json.toString().toByteArray(Charsets.UTF_8)
            return "Content-Length: ${body.size}\r\n\r\n".ascii() + body
        }

        /** Reads one framed message the slow, obvious way, independent of JsonRpc. Null at end of stream. */
        fun readFrame(input: InputStream): JSONObject? {
            var length = -1
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return null
                    if (b == '\n'.code) break
                    line.append(b.toChar())
                }
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) break
                if (text.startsWith("Content-Length:", ignoreCase = true)) length = text.substringAfter(':').trim().toInt()
            }
            val body = ByteArray(length)
            DataInputStream(input).readFully(body)
            return JSONObject(String(body, Charsets.UTF_8))
        }

        fun jrange(l1: Int, c1: Int, l2: Int, c2: Int): JSONObject = JSONObject()
            .put("start", JSONObject().put("line", l1).put("character", c1))
            .put("end", JSONObject().put("line", l2).put("character", c2))

        fun range(l1: Int, c1: Int, l2: Int, c2: Int) = Range(Position(l1, c1), Position(l2, c2))

        fun waitFor(timeoutMs: Long = 5_000, done: () -> Boolean): Boolean {
            val end = System.nanoTime() + timeoutMs * 1_000_000
            while (!done()) {
                if (System.nanoTime() > end) return false
                Thread.sleep(10)
            }
            return true
        }

        fun <T> await(timeoutMs: Long = 5_000, call: ((Result<T>) -> Unit) -> Unit): Result<T>? {
            val q = LinkedBlockingQueue<Result<T>>()
            call { q.put(it) }
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        /** The fake server's replies, in the shapes that need their own parsing: InsertReplaceEdit, MarkupContent, LocationLink. */
        fun fakeAnswer(method: String): Any? = when (method) {
            "initialize" -> JSONObject().put(
                "capabilities",
                JSONObject()
                    .put("textDocumentSync", JSONObject().put("openClose", true).put("change", 1).put("save", JSONObject().put("includeText", true)))
                    .put("completionProvider", JSONObject().put("triggerCharacters", JSONArray().put(".")))
                    .put("hoverProvider", true)
                    .put("definitionProvider", true)
                    .put("documentFormattingProvider", true),
            )
            "textDocument/completion" -> {
                val insertReplace = JSONObject().put("newText", "append").put("insert", jrange(0, 3, 0, 5)).put("replace", jrange(0, 3, 0, 9))
                val append = JSONObject().put("label", "append").put("kind", 2).put("detail", "list.append(x)")
                    .put("documentation", JSONObject().put("kind", "markdown").put("value", "Adds **x**."))
                    .put("textEdit", insertReplace)
                val loop = JSONObject().put("label", "for").put("kind", 15).put("insertText", SNIPPET).put("insertTextFormat", 2)
                JSONObject().put("isIncomplete", false).put("items", JSONArray().put(append).put(loop))
            }
            "textDocument/hover" -> JSONObject().put("contents", JSONObject().put("kind", "markdown").put("value", HOVER))
            "textDocument/definition" -> JSONArray().put(
                JSONObject()
                    .put("originSelectionRange", jrange(0, 0, 0, 2))
                    .put("targetUri", "file:///w/b.py")
                    .put("targetRange", jrange(0, 0, 3, 0))
                    .put("targetSelectionRange", jrange(1, 4, 1, 7)),
            )
            "textDocument/formatting" -> JSONArray().put(JSONObject().put("range", jrange(0, 0, 0, 3)).put("newText", "    "))
            "shutdown" -> null
            else -> throw UnsupportedOperationException(method)
        }
    }
}
