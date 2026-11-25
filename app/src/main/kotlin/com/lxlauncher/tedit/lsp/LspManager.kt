// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.lsp

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lxlauncher.R
import com.lxlauncher.tedit.DocSource
import com.lxlauncher.tedit.Document
import com.lxlauncher.tedit.Pos
import com.lxlauncher.tedit.TaskDef
import com.lxlauncher.tedit.TeditPrefs
import com.lxlauncher.tedit.TeditTool
import com.lxlauncher.tedit.linux.LinuxRuntime
import java.io.File

/**
 * The language servers of one Tedit window. A server starts in Debian the first time a document of its language is
 * opened, one per server and folder, and stops with the window. Documents are kept in sync with their full text a
 * moment after each edit; diagnostics land on the document for the editor and the Problems panel.
 *
 * Everything here is called on the main thread; LspClient's callbacks are posted back to it.
 */
class LspManager(private val tool: TeditTool) {
    private val ctx get() = tool.ctx
    private val main = Handler(Looper.getMainLooper())
    private val rt get() = LinuxRuntime.get(ctx)

    private inner class Session(val preset: LspServers.Preset, val root: String) {
        var client: LspClient? = null
        var process: Process? = null
        var ready = false
        var closed = false
        /** Documents open in this server: key -> (uri, version). */
        val docs = HashMap<String, Pair<String, Int>>()
        val log = StringBuilder()
    }

    private val sessions = HashMap<String, Session>()
    /** Servers Debian does not have yet; asked about once per window. */
    private val missing = HashSet<String>()
    private val pendingChanges = HashMap<String, Runnable>()
    private var disposed = false

    fun enabled() = tool.prefs.bool(TeditPrefs.LANGUAGE_SERVERS) && rt.ready

    private fun pathOf(d: Document): String? = (d.source as? DocSource.Node)?.takeIf { it.backendKey == "local" }?.ref

    private fun eligible(d: Document): Boolean =
        !d.loading && !d.binary && !d.image && d.loadError == null && pathOf(d) != null && d.buffer.lineCount <= MAX_LINES

    private fun rootFor(d: Document): String {
        val path = pathOf(d)!!
        val ws = tool.workspace?.source?.takeIf { it.backendKey == "local" }?.ref
        return if (ws != null && path.startsWith("$ws/")) ws else File(path).parent ?: "/"
    }

    private fun sessionFor(d: Document): Session? {
        if (!enabled() || !eligible(d)) return null
        val p = LspServers.forLanguage(d.language.id) ?: return null
        if (p.id in missing) return null
        val root = rootFor(d)
        val key = "${p.id}|$root"
        sessions[key]?.let { if (!it.closed) return it }
        return start(p, root).also { sessions[key] = it }
    }

    private fun start(p: LspServers.Preset, root: String): Session {
        val s = Session(p, root)
        val started = android.os.SystemClock.uptimeMillis()
        val proc = runCatching {
            ProcessBuilder(rt.command(listOf("/bin/bash", "-lc", "exec " + p.command.joinToString(" ")), root, announcePid = false)).start()
        }.getOrElse { s.closed = true; Log.w(TAG, "${p.id} did not start: ${it.javaClass.simpleName}"); return s }
        s.process = proc
        // The server's own messages, kept short, for when it fails.
        Thread({
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(s.log) { s.log.append(line).append('\n'); if (s.log.length > 8192) s.log.delete(0, s.log.length - 6144) }
                }
            }
        }, "tedit-lsp-err").apply { isDaemon = true }.start()
        val client = LspClient(proc.inputStream, proc.outputStream, pathToUri(root), object : LspListener {
            override fun onDiagnostics(uri: String, diagnostics: List<Diagnostic>) { main.post { applyDiagnostics(uri, diagnostics) } }
            override fun onClosed(error: Throwable?) { main.post { closed(s) } }
        })
        s.client = client
        client.start(null) { r ->
            main.post {
                if (r.isSuccess) {
                    s.ready = true
                    Log.i(TAG, "${p.id} ready in ${android.os.SystemClock.uptimeMillis() - started} ms for $root")
                } else Log.w(TAG, "${p.id} did not initialise: ${r.exceptionOrNull()?.message}")
            }
        }
        return s
    }

    private fun closed(s: Session) {
        if (s.closed) return
        s.closed = true
        s.ready = false
        val code = runCatching { s.process?.waitFor(); s.process?.exitValue() }.getOrNull()
        val log = synchronized(s.log) { s.log.toString() }
        Log.w(TAG, "${s.preset.id} ended (exit $code)")
        for (key in s.docs.keys) tool.store.byKey(key)?.let { if (it.diagnostics.isNotEmpty()) { it.diagnostics = emptyList(); it.changed() } }
        sessions.values.remove(s)
        if (disposed) return
        // bash says "command not found" (exit 127) when the server is not installed in Debian yet.
        if (code == 127 || log.contains("command not found")) {
            missing += s.preset.id
            tool.overlays.snack(ctx.getString(R.string.tedit_lsp_missing, s.preset.name), ctx.getString(R.string.tedit_run_install)) {
                tool.runner.run(TaskDef(ctx.getString(R.string.tedit_run_install_named, s.preset.name), s.preset.install, null))
                tool.showPanel("run")
            }
        }
    }

    // ---- documents ----

    fun opened(d: Document) {
        val s = sessionFor(d) ?: return
        if (s.docs.containsKey(d.key)) return
        val uri = pathToUri(pathOf(d)!!)
        s.docs[d.key] = uri to 1
        s.client?.didOpen(uri, s.preset.lspLanguage(d.language.id), 1, d.buffer.text())
    }

    /** After an edit: the full text goes to the server a moment later, once typing pauses. */
    fun changed(d: Document) {
        if (!enabled()) return
        pendingChanges.remove(d.key)?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pendingChanges.remove(d.key)
            val s = sessionFor(d) ?: return@Runnable
            val cur = s.docs[d.key] ?: run { opened(d); return@Runnable }
            val v = cur.second + 1
            s.docs[d.key] = cur.first to v
            s.client?.didChange(cur.first, v, d.buffer.text())
        }
        pendingChanges[d.key] = r
        main.postDelayed(r, CHANGE_DELAY)
    }

    fun saved(d: Document) {
        flush(d)
        for (s in sessions.values) s.docs[d.key]?.let { s.client?.didSave(it.first) }
    }

    fun closed(d: Document) {
        pendingChanges.remove(d.key)?.let { main.removeCallbacks(it) }
        for (s in sessions.values) s.docs.remove(d.key)?.let { s.client?.didClose(it.first) }
        d.diagnostics = emptyList()
    }

    /** Sends a change still waiting, so a request sees the text as it is now. */
    private fun flush(d: Document) {
        pendingChanges.remove(d.key)?.let { main.removeCallbacks(it); it.run() }
    }

    private fun applyDiagnostics(uri: String, list: List<Diagnostic>) {
        val path = uriToPath(uri) ?: return
        val d = tool.store.docs.values.firstOrNull { pathOf(it) == path } ?: return
        d.diagnostics = list
        d.changed()
        tool.problems.schedule()
        tool.groups.forEach { if (it.isBuilt() && it.active?.doc === d) it.editor.invalidate() }
    }

    // ---- requests ----

    private fun ready(d: Document): Pair<Session, String>? {
        val s = sessionFor(d) ?: return null
        if (!s.ready) return null
        flush(d)
        val uri = s.docs[d.key]?.first ?: run { opened(d); s.docs[d.key]?.first } ?: return null
        return s to uri
    }

    /** True when [d] has a running server that answers requests. */
    fun available(d: Document) = ready(d) != null

    fun completion(d: Document, at: Pos, trigger: String?, cb: (List<CompletionItem>) -> Unit) {
        val (s, uri) = ready(d) ?: return cb(emptyList())
        s.client?.completion(uri, Position(at.line, at.col), trigger) { r -> main.post { cb(r.getOrDefault(emptyList())) } }
    }

    fun hover(d: Document, at: Pos, cb: (String?) -> Unit) {
        val (s, uri) = ready(d) ?: return cb(null)
        s.client?.hover(uri, Position(at.line, at.col)) { r -> main.post { cb(r.getOrNull()) } }
    }

    fun definition(d: Document, at: Pos, cb: (List<Location>) -> Unit) {
        val (s, uri) = ready(d) ?: return cb(emptyList())
        s.client?.definition(uri, Position(at.line, at.col)) { r -> main.post { cb(r.getOrDefault(emptyList())) } }
    }

    fun formatting(d: Document, tabSize: Int, insertSpaces: Boolean, cb: (List<TextEdit>?) -> Unit) {
        val (s, uri) = ready(d) ?: return cb(null)
        if (s.client?.capabilities?.has("documentFormattingProvider") != true) return cb(null)
        s.client?.formatting(uri, tabSize, insertSpaces) { r -> main.post { cb(r.getOrNull()) } }
    }

    /** "pylsp: ready" lines for the status of each server, for the Run and Problems panels. */
    fun status(): List<String> = sessions.values.map { "${it.preset.name}: " + if (it.ready) "ready" else if (it.closed) "stopped" else "starting" }

    /** Forgets which servers were missing, after an install, so the next document tries them again. */
    fun retryMissing() { missing.clear() }

    fun dispose() {
        disposed = true
        pendingChanges.values.forEach { main.removeCallbacks(it) }
        pendingChanges.clear()
        for (s in sessions.values.toList()) {
            val p = s.process
            s.client?.shutdown { runCatching { p?.destroy() } } ?: runCatching { p?.destroy() }
            // A server that ignores exit is ended with its VHDP supervisor.
            main.postDelayed({ runCatching { p?.destroyForcibly() } }, 3000)
        }
        sessions.clear()
    }

    companion object {
        const val TAG = "LxLsp"
        private const val CHANGE_DELAY = 350L
        /** Larger documents are not sent: the whole text goes over the pipe on every change. */
        private const val MAX_LINES = 30_000
    }
}
