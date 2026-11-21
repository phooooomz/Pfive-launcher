// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import com.lxlauncher.LauncherActivity
import com.lxlauncher.tedit.Checks
import com.lxlauncher.tedit.Diff
import com.lxlauncher.tedit.DocSource
import com.lxlauncher.tedit.Document
import com.lxlauncher.tedit.EditHistory
import com.lxlauncher.tedit.FindQuery
import com.lxlauncher.tedit.FindState
import com.lxlauncher.tedit.Formatter
import com.lxlauncher.tedit.GitRepo
import com.lxlauncher.tedit.Highlighter
import com.lxlauncher.tedit.Languages
import com.lxlauncher.tedit.Pos
import com.lxlauncher.tedit.SpanSink
import com.lxlauncher.tedit.TeditFiles
import com.lxlauncher.tedit.TeditTool
import com.lxlauncher.tedit.TextBuffer
import com.lxlauncher.tedit.TextCodec
import com.lxlauncher.tedit.Token
import com.lxlauncher.tedit.CodeEditView
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater

/**
 * Checks for Tedit (11 November 2026): the text buffer and undo, encodings, search, highlighting, diff, the git reader,
 * the JSON and XML checks, then one real edit through a Tedit window. Run alone with `-e only tedit`. Files are written
 * only under LX's cache, in tedit-check-* folders that are removed afterwards.
 */
internal class TeditChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit) {
    private val ctx = inst.targetContext
    private val scratch = File(ctx.cacheDir, "tedit-check-${System.nanoTime()}").apply { mkdirs() }

    fun run() {
        try {
            buffer()
            history()
            codecs()
            find()
            highlight()
            diff()
            git()
            problems()
            files()
            keepTeditState(ctx) { window() }
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun buffer() {
        check("buffer splits LF, CRLF and CR and keeps an empty last line") {
            val b = TextBuffer("a\nb\r\nc\rd\n")
            if (b.lineCount != 5) return@check "lines ${b.lineCount}"
            if (b.line(3) != "d" || b.line(4) != "") return@check "lines ${(0 until b.lineCount).map { b.line(it) }}"
            null
        }
        check("buffer replaces across lines and reports where the text ends") {
            val b = TextBuffer("one\ntwo\nthree")
            val end = b.replace(Pos(0, 1), Pos(2, 2), "X\nY")
            if (b.text() != "oX\nYree") return@check "text ${b.text()}"
            if (end != Pos(1, 1)) return@check "end $end"
            null
        }
        check("offsets and positions agree after edits") {
            val b = TextBuffer("ab\ncd\nef")
            b.replace(Pos(1, 0), Pos(1, 0), "zz\n")
            for (o in 0..b.length) if (b.offsetOf(b.posOf(o)) != o) return@check "offset $o"
            if (b.posOf(4) != Pos(1, 1)) return@check "pos ${b.posOf(4)}"
            null
        }
    }

    private fun history() {
        check("typing groups into one undo step and undo restores the text") {
            val b = TextBuffer("")
            val h = EditHistory(b)
            var p = Pos.ZERO
            for (c in "hello") p = h.apply(p, p, c.toString(), p to p, EditHistory.Kind.TYPE, 1000L)
            if (b.text() != "hello") return@check "text ${b.text()}"
            h.undo()
            if (b.text() != "") return@check "after undo '${b.text()}'"
            h.redo()
            if (b.text() != "hello") return@check "after redo '${b.text()}'"
            null
        }
        check("a keyboard revising its word stays one undo step") {
            val b = TextBuffer("x ")
            val h = EditHistory(b)
            var end = h.apply(Pos(0, 2), Pos(0, 2), "t", Pos(0, 2) to Pos(0, 2), EditHistory.Kind.TYPE, 1000L)
            end = h.apply(Pos(0, 2), end, "te", end to end, EditHistory.Kind.TYPE, 1010L)
            h.apply(Pos(0, 2), end, "ted", end to end, EditHistory.Kind.TYPE, 1020L)
            if (b.text() != "x ted") return@check "text ${b.text()}"
            h.undo()
            if (b.text() != "x ") return@check "after undo '${b.text()}'"
            null
        }
        check("the saved state survives undo and redo") {
            val d = Document(1, null, "t", Languages.PLAIN)
            d.history.apply(Pos.ZERO, Pos.ZERO, "a", Pos.ZERO to Pos.ZERO, EditHistory.Kind.OTHER, 1L)
            d.markSaved()
            if (d.dirty) return@check "dirty after save"
            d.history.apply(Pos(0, 1), Pos(0, 1), "b", Pos.ZERO to Pos.ZERO, EditHistory.Kind.OTHER, 2L)
            if (!d.dirty) return@check "clean after edit"
            d.history.undo()
            if (d.dirty) return@check "dirty after undoing back to the save"
            null
        }
    }

    private fun codecs() {
        check("encodings are told apart and binary files are recognised") {
            fun det(b: ByteArray) = TextCodec.detect(b, b.size, TextCodec.UTF8)
            val problems = ArrayList<String>()
            if (det("héllo".toByteArray()) != TextCodec.UTF8) problems += "utf8"
            if (det(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61)) != TextCodec.UTF8_BOM) problems += "bom"
            if (det(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0)) != TextCodec.UTF16LE) problems += "utf16"
            if (det(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())) != TextCodec.LATIN1) problems += "latin1"
            // Cut at a read limit inside a character, UTF-8 is still UTF-8.
            val cutUtf8 = "aé".toByteArray().copyOf(2)
            if (TextCodec.detect(cutUtf8, 2, TextCodec.UTF8, cut = true) != TextCodec.UTF8) problems += "cut utf8"
            val bin = ByteArray(64) { it.toByte() }
            if (!TextCodec.looksBinary(bin, bin.size)) problems += "binary"
            if (TextCodec.looksBinary("plain\ttext\n".toByteArray(), 11)) problems += "text as binary"
            val round = TextCodec.decode(TextCodec.UTF16BE.encode("ok"), 6, TextCodec.UTF16BE)
            if (round != "ok") problems += "utf16be round trip '$round'"
            problems.joinToString().ifEmpty { null }
        }
        check("line breaks are detected, mixed ones flagged") {
            val mixed = BooleanArray(1)
            if (Document.detectEol("a\r\nb\r\nc\n", mixed) != "\r\n" || !mixed[0]) return@check "crlf/mixed"
            if (Document.detectEol("a\nb", mixed) != "\n" || mixed[0]) return@check "lf"
            null
        }
    }

    private fun find() {
        check("find honours case, whole word and regex") {
            val b = TextBuffer("Foo foo food\nfoo_bar FOO")
            val f = FindState()
            f.set(FindQuery("foo")); f.update(b)
            if (f.count != 5) return@check "plain ${f.count}"
            f.set(FindQuery("foo", caseSensitive = true)); f.update(b)
            if (f.count != 3) return@check "case ${f.count}"
            f.set(FindQuery("foo", wholeWord = true)); f.update(b)
            if (f.count != 3) return@check "word ${f.count}"
            f.set(FindQuery("fo+d?", regex = true)); f.update(b)
            if (f.count != 5) return@check "regex ${f.count}"
            f.set(FindQuery("(", regex = true)); f.update(b)
            if (f.error == null) return@check "bad regex accepted"
            null
        }
    }

    private fun kinds(lang: String, vararg lines: String): List<List<Int>> {
        val l = Languages.byId(lang)!!
        val b = TextBuffer(lines.joinToString("\n"))
        val h = Highlighter(b, l, 20000)
        return lines.indices.map { i -> h.spans(i).toList().chunked(3).map { it[2] } }
    }

    private fun highlight() {
        check("a block comment carries over lines and ends where it closes") {
            val k = kinds("kotlin", "val a = 1 /* start", "still comment", "end */ val b")
            if (k[1] != listOf(Token.COMMENT)) return@check "line 2 ${k[1]}"
            if (Token.COMMENT !in k[2] || Token.KEYWORD !in k[2]) return@check "line 3 ${k[2]}"
            null
        }
        check("keywords, control words, strings and functions get their colours") {
            val k = kinds("javascript", "if (x) return foo('s', 42)")[0]
            val need = listOf(Token.CONTROL, Token.VARIABLE, Token.FUNCTION, Token.STRING, Token.NUMBER)
            val missing = need.filter { it !in k }
            if (missing.isNotEmpty()) "missing $missing in $k" else null
        }
        check("JSON keys and values are told apart") {
            val k = kinds("json", "{\"key\": \"value\", \"n\": true}")[0]
            if (Token.ATTRIBUTE !in k || Token.STRING !in k || Token.CONSTANT !in k) "kinds $k" else null
        }
        check("an XML comment spans lines") {
            val k = kinds("xml", "<a> <!-- one", "two --> <b/>")
            if (k[0].last() != Token.COMMENT || k[1].first() != Token.COMMENT || Token.TAG !in k[1]) "kinds $k" else null
        }
        check("languages are found by name, extension and #! line") {
            val wrong = ArrayList<String>()
            fun d(name: String, first: String?, want: String) { val got = Languages.detect(name, first, emptySet()).id; if (got != want) wrong += "$name→$got" }
            d("App.kt", null, "kotlin"); d("Makefile", null, "makefile"); d("run", "#!/usr/bin/env bash", "shell")
            d("x.tsx", null, "typescript"); d("notes.txt", null, "plaintext"); d("conf", "<?xml version=\"1.0\"?>", "xml")
            if (Languages.detect("a.kt", null, setOf("jvm")).id != "plaintext") wrong += "disabled pack still used"
            wrong.joinToString().ifEmpty { null }
        }
    }

    private fun diff() {
        check("diff finds added, removed and changed lines") {
            val a = listOf("a", "b", "c", "d")
            val b = listOf("a", "B", "c", "d", "e")
            val lines = Diff.lines(a, b) ?: return@check "no diff"
            val adds = lines.count { it.kind == '+' }
            val dels = lines.count { it.kind == '-' }
            if (adds != 2 || dels != 1) return@check "adds $adds dels $dels"
            val marks = Diff.gutterMarks(a, b)!!.toList()
            if (marks != listOf(0, 2, 0, 0, 1)) return@check "marks $marks"
            if (Diff.hunks(lines).size != 1) return@check "hunks ${Diff.hunks(lines).size}"
            null
        }
    }

    // ---- git: a repository written byte by byte, as git would ----

    private fun sha1(b: ByteArray) = GitRepo.bytesToHex(MessageDigest.getInstance("SHA-1").digest(b))

    private fun writeObject(git: File, type: String, body: ByteArray): String {
        val raw = "$type ${body.size}\u0000".toByteArray() + body
        val sha = sha1(raw)
        val def = Deflater()
        def.setInput(raw); def.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) out.write(buf, 0, def.deflate(buf))
        def.end()
        File(git, "objects/${sha.substring(0, 2)}").mkdirs()
        File(git, "objects/${sha.substring(0, 2)}/${sha.substring(2)}").writeBytes(out.toByteArray())
        return sha
    }

    private fun git() {
        check("the git reader follows HEAD, reads a commit and tells changed files") {
            val repo = File(scratch, "repo").apply { mkdirs() }
            val git = File(repo, ".git").apply { mkdirs() }
            val content = "hello\nworld\n".toByteArray()
            File(repo, "a.txt").writeBytes(content)
            val blob = writeObject(git, "blob", content)
            val tree = writeObject(git, "tree", "100644 a.txt\u0000".toByteArray() + GitRepo.hexToBytes(blob))
            val commit = writeObject(git, "commit", "tree $tree\nauthor T <t@example.invalid> 1700000000 +0000\ncommitter T <t@example.invalid> 1700000000 +0000\n\nFirst\n".toByteArray())
            File(git, "refs/heads").mkdirs()
            File(git, "refs/heads/main").writeText("$commit\n")
            File(git, "HEAD").writeText("ref: refs/heads/main\n")
            // Index version 2 with one entry for a.txt.
            val f = File(repo, "a.txt")
            val entry = ByteArrayOutputStream()
            fun i32(v: Long) { entry.write(((v shr 24) and 0xFF).toInt()); entry.write(((v shr 16) and 0xFF).toInt()); entry.write(((v shr 8) and 0xFF).toInt()); entry.write((v and 0xFF).toInt()) }
            i32(f.lastModified() / 1000); i32(0); i32(f.lastModified() / 1000); i32(0)
            i32(0); i32(0); i32(0x81A4); i32(0); i32(0); i32(content.size.toLong())
            entry.write(GitRepo.hexToBytes(blob))
            val name = "a.txt".toByteArray()
            entry.write(0); entry.write(name.size)
            entry.write(name)
            var len = 62 + name.size
            val pad = 8 - (len % 8)
            repeat(pad) { entry.write(0) }
            len += pad
            val idx = ByteArrayOutputStream()
            idx.write("DIRC".toByteArray()); idx.write(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1))
            idx.write(entry.toByteArray())
            idx.write(MessageDigest.getInstance("SHA-1").digest(idx.toByteArray()))
            File(git, "index").writeBytes(idx.toByteArray())
            val r = GitRepo.find(File(repo, "sub-that-does-not-exist").parentFile!!) ?: return@check "repository not found"
            if (r.branch() != "main" || r.headSha() != commit) return@check "head ${r.branch()} ${r.headSha()}"
            val c = r.commit(commit) ?: return@check "commit unreadable"
            if (c.message.trim() != "First" || c.author != "T") return@check "commit ${c.message} ${c.author}"
            val s1 = r.status(untracked = true) { false }
            if (s1.staged.isNotEmpty() || s1.unstaged.isNotEmpty()) return@check "clean repo shows ${s1.staged} ${s1.unstaged}"
            f.writeText("hello\nthere!\n")
            File(repo, "new.txt").writeText("n")
            val s2 = r.status(untracked = true) { false }
            val changes = s2.unstaged.map { "${it.path}:${it.change}" }.sorted()
            if (changes != listOf("a.txt:MODIFIED", "new.txt:UNTRACKED")) return@check "changes $changes"
            val old = r.blobAt(commit, "a.txt")?.toString(Charsets.UTF_8)
            if (old != "hello\nworld\n") return@check "blob $old"
            null
        }
        val fixture = File("/storage/emulated/0/Download/tedit-test")
        if (File(fixture, ".git/objects/pack").isDirectory) check("the git reader reads packs with deltas (test folder on the device)") {
            val r = GitRepo.find(fixture) ?: return@check "no repository"
            val log = r.log(100)
            if (log.size < 30) return@check "history ${log.size}"
            // Every commit's tree must flatten: each read goes through the pack and its deltas.
            for (c in log) if (r.flatten(c.tree).isEmpty()) return@check "empty tree at ${c.sha.take(7)}"
            val s = r.status(untracked = true) { false }
            info("INFO fixture: ${log.size} commits, ${s.staged.size} staged, ${s.unstaged.size} changed")
            null
        } else info("INFO no git fixture in Download/tedit-test; pack reading checked by hand only")
    }

    private fun problems() {
        check("JSON errors point at the line and column; valid JSON passes") {
            val bad = Checks.json(listOf("{", "  \"a\": [1, 2,],", "}"), ctx)
            if (bad.size != 1 || bad[0].first != 1) return@check "bad $bad"
            if (Checks.json(listOf("{\"a\": [1, {\"b\": null}], // note", "\"c\": -1.5e3}"), ctx).isNotEmpty()) return@check "valid JSON flagged"
            null
        }
        check("unpaired brackets are found outside strings and comments") {
            val p = Checks.brackets(Languages.byId("kotlin")!!, listOf("fun a() {", "  val s = \"}\" // )", "  if (x) {", "}"), ctx)
            if (p.size != 1) "found ${p.map { it.third }}" else null
        }
        check("an XML error is reported with its line") {
            val p = Checks.xml(listOf("<a>", "  <b>", "</a>"), ctx)
            if (p.size != 1 || p[0].first < 1) "found $p" else null
        }
        check("the JSON formatter keeps values exactly") {
            val out = Formatter.json("{\"a\":1.50,\"b\":[],\"c\":{\"d\":\"x,y\"}}", "  ")
            val want = "{\n  \"a\": 1.50,\n  \"b\": [],\n  \"c\": {\n    \"d\": \"x,y\"\n  }\n}"
            if (out != want) "got\n$out" else null
        }
    }

    private fun files() {
        check("a save writes the bytes, in the file's encoding, and replaces the file whole") {
            val tf = TeditFiles(ctx)
            val f = File(scratch, "save.txt").apply { writeText("old") }
            val src = DocSource.Node("local", f.absolutePath)
            if (tf.backendFor("local") == null) return@check null.also { info("INFO no all-files access: local save skipped") }
            tf.write(src, TextCodec.UTF8_BOM.encode("new text\n"))
            val bytes = f.readBytes()
            if (bytes.size != 12 || bytes[0] != 0xEF.toByte()) return@check "bytes ${bytes.size}"
            val read = tf.read(src, 1024, { _, _ -> }, { false })
            if (TextCodec.decode(read.bytes, read.length, TextCodec.UTF8_BOM) != "new text\n") return@check "read back"
            if (scratch.listFiles()?.any { it.name.contains(".tedit-") } == true) return@check "temporary file left behind"
            null
        }
    }

    // ---- one real edit through a Tedit window ----

    private fun findEditor(v: View): CodeEditView? {
        if (v is CodeEditView && v.isAttachedToWindow && v.session != null) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findEditor(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Tedit windows in the layout file on disk; the file is a header line, then the JSON. */
    private fun savedTeditWindows(): Int {
        val raw = runCatching { File(ctx.filesDir, "layout.lxl").readText() }.getOrNull() ?: return 0
        val windows = runCatching { org.json.JSONObject(raw.substringAfter('\n')).optJSONArray("windows") }.getOrNull() ?: return 0
        return (0 until windows.length()).count { windows.optJSONObject(it)?.optString("kind") == TeditTool.KIND }
    }

    private fun window() {
        // The File Explorer hands Tedit local paths only with all-files access; without it there is nothing to hand over.
        if (com.lxlauncher.files.FileServices.storage(ctx).local() == null) {
            info("INFO no all-files access: the window check needs it and was skipped")
            return
        }
        val file = File(scratch, "edit.kt").apply { writeText("fun main() {\n}\n") }
        var activity: LauncherActivity? = null
        check("LX starts and Tedit opens a file the File Explorer hands it") {
            val monitor = inst.addMonitor(LauncherActivity::class.java.name, null, false)
            ctx.startActivity(Intent(Intent.ACTION_MAIN).setClassName(ctx, LauncherActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = inst.waitForMonitorWithTimeout(monitor, 10_000) as? LauncherActivity
            inst.removeMonitor(monitor)
            val a = activity ?: return@check "LauncherActivity did not start"
            inst.waitForIdleSync()
            val opened = SystemClock.uptimeMillis()
            inst.runOnMainSync { a.openTool(TeditTool.KIND, "open\u0000local\u0000${file.absolutePath}") }
            var ed: CodeEditView? = null
            while (SystemClock.uptimeMillis() - opened < 8000) {
                inst.runOnMainSync { ed = findEditor(a.window.decorView)?.takeIf { it.doc?.name == "edit.kt" && it.doc?.loading == false } }
                if (ed != null) break
                SystemClock.sleep(100)
            }
            info("INFO Tedit window and file ready in ${SystemClock.uptimeMillis() - opened} ms")
            if (ed == null) "no editor with edit.kt" else null
        }
        val a = activity ?: return
        check("typing pairs a bracket, Enter indents, undo takes it back, and Ctrl+S saves") {
            var problem: String? = null
            inst.runOnMainSync {
                val ed = findEditor(a.window.decorView) ?: run { problem = "editor gone"; return@runOnMainSync }
                ed.requestFocus()
                ed.select(Pos(0, 12), Pos(0, 12), center = false)
                ed.newline()
                // The keyboard sends one character at a time.
                for (c in "println(") ed.typeText(c.toString())
                val line = ed.doc!!.buffer.line(1)
                if (line != "    println()") problem = "line after typing '$line'"
                ed.typeText(")")
                if (ed.doc!!.buffer.line(1) != "    println()") problem = "closing bracket not stepped over: '${ed.doc!!.buffer.line(1)}'"
                ed.toggleComment()
                if (!ed.doc!!.buffer.line(1).trimStart().startsWith("//")) problem = "comment not added"
                ed.undo()
                if (ed.doc!!.buffer.line(1).trimStart().startsWith("//")) problem = "undo kept the comment"
            }
            if (problem != null) return@check problem
            inst.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            inst.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_S, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            val start = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - start < 4000 && !file.readText().contains("println()")) SystemClock.sleep(100)
            val text = file.readText()
            if (text != "fun main() {\n    println()\n}\n") "file on disk:\n$text" else null
        }
        check("closing the Tedit window leaves no crash and no open editor") {
            inst.runOnMainSync {
                val s = a.model.snapshot
                s.windows.filter { it.kind == TeditTool.KIND }.forEach { w -> a.model.edit { h -> com.lxlauncher.core.NativeCore.closeWindow(h, w.id) } }
            }
            inst.waitForIdleSync()
            var left: CodeEditView? = null
            inst.runOnMainSync { left = findEditor(a.window.decorView) }
            // The layout is saved a moment after an edit; the run ends sooner, so write it now and wait for it.
            a.model.saveSoon(0)
            val since = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - since < 3000 && savedTeditWindows() > 0) SystemClock.sleep(100)
            when {
                left != null -> "an editor is still attached"
                savedTeditWindows() > 0 -> "the saved layout still lists a Tedit window"
                else -> null
            }
        }
    }
}
