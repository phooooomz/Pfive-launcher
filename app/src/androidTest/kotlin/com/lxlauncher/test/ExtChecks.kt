// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.graphics.Color
import android.os.SystemClock
import com.lxlauncher.tedit.ext.ExtensionStore
import com.lxlauncher.tedit.ext.Jsonc
import com.lxlauncher.tedit.ext.OpenVsx
import com.lxlauncher.tedit.ext.VsTheme
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tedit's extensions (12 November 2026), run with `-e only ext`: JSON with comments, the .vsix installer and its
 * refusals, theme loading, then Open VSX for real (search, details, download with its checksum, install, a theme
 * applied). Extensions installed here are removed at the end unless `-e only ext:keep`.
 */
internal class ExtChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit, private val keep: Boolean) {
    private val ctx = inst.targetContext
    private val scratch = File(ctx.cacheDir, "ext-check-${System.nanoTime()}").apply { mkdirs() }
    private val store = ExtensionStore.get(ctx)
    private val installedHere = ArrayList<String>()

    fun run() {
        try {
            jsonc()
            vsix()
            market()
        } finally {
            scratch.deleteRecursively()
            if (!keep) installedHere.forEach { store.uninstall(it) }
        }
    }

    private fun jsonc() {
        check("JSON with comments and trailing commas parses as VS Code writes it") {
            val o = Jsonc.obj("\uFEFF{\n // c\n \"a\": \"x // not a comment\", /* b */ \"b\": [1, 2,],\n \"c\": {\"d\": \"/* kept */\",},\n}")
            when {
                o.getString("a") != "x // not a comment" -> "comment removed inside a string"
                o.getJSONArray("b").length() != 2 -> "trailing comma in array"
                o.getJSONObject("c").getString("d") != "/* kept */" -> "block comment removed inside a string"
                else -> null
            }
        }
    }

    private fun vsix(files: Map<String, String>): File {
        val f = File(scratch, "t${System.nanoTime()}.vsix")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, body) in files) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun vsix() {
        check("a .vsix installs, its theme loads, and it uninstalls") {
            val theme = """{ "name": "Tiny", "type": "dark", "include": "./base.json",
                "colors": { "editor.background": "#101820", "editor.foreground": "#E0E0E0", "editor.selectionBackground": "#3A5F8080" },
                "tokenColors": [ { "scope": ["comment", "punctuation.definition.comment"], "settings": { "foreground": "#7F848E" } },
                                 { "scope": "keyword.control", "settings": { "foreground": "#C678DD" } } ] }"""
            val base = """{ "colors": { "sideBar.background": "#0B1118", "editor.background": "#000000" },
                "tokenColors": [ { "scope": "keyword", "settings": { "foreground": "#61AFEF" } } ] }"""
            val pkg = """{ "name": "tiny-theme", "publisher": "tedit-test", "version": "1.0.0", "displayName": "Tiny Theme",
                "contributes": { "themes": [ { "label": "Tiny Dark", "uiTheme": "vs-dark", "path": "./themes/tiny.json" } ], "keybindings": [] } }"""
            val f = vsix(mapOf("extension.vsixmanifest" to "<x/>", "extension/package.json" to pkg, "extension/themes/tiny.json" to theme, "extension/themes/base.json" to base))
            val e = store.install(f)
            installedHere += e.id
            val t = store.theme(store.themeKey(e, e.themes[0])) ?: return@check "theme did not load"
            when {
                e.id != "tedit-test.tiny-theme" -> "id ${e.id}"
                e.unused != listOf("keybindings") -> "unused ${e.unused}"
                t.color("editor.background") != Color.parseColor("#101820") -> "its own colour did not win over the included one"
                t.color("sideBar.background") != Color.parseColor("#0B1118") -> "included colour lost"
                t.foreground("keyword.control.flow.js") != Color.parseColor("#C678DD") -> "longest scope prefix not used"
                t.foreground("keyword.other") != Color.parseColor("#61AFEF") -> "included rule lost"
                Color.alpha(t.color("editor.selectionBackground") ?: 0) != 0x80 -> "alpha in #RRGGBBAA lost"
                else -> { store.uninstall(e.id); installedHere.remove(e.id); if (store.get(e.id) != null) "still installed" else null }
            }
        }
        check("a .vsix that names files outside its folder is refused") {
            val bad = vsix(mapOf("extension/package.json" to """{"name":"x","publisher":"y"}""", "extension/../../evil" to "x"))
            val r = runCatching { store.install(bad) }
            if (r.isSuccess) { installedHere += "y.x"; "installed" } else if (File(store.dir.parentFile, "evil").exists()) "a file escaped" else null
        }
    }

    /** An install that cannot finish ends with a failure the person is told about, and leaves nothing behind. */
    private fun failedInstall() {
        check("an install that fails reports the failure, stops showing progress and leaves nothing installed") {
            val ghost = OpenVsx.Item("tedit-test-missing", "no-such-extension-${System.nanoTime()}", "Missing", "", "1.0.0", 0, null, false, null, false)
            val before = store.installed().map { it.id }.toSet()
            var result: Result<ExtensionStore.Installed>? = null
            val done = java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync { com.lxlauncher.tedit.Marketplace.install(ctx, ghost) { r -> result = r; done.countDown() } }
            var progressShown = false
            inst.runOnMainSync { progressShown = com.lxlauncher.tedit.Marketplace.installing.containsKey(ghost.id) }
            if (!done.await(60, java.util.concurrent.TimeUnit.SECONDS)) return@check "no answer in a minute"
            var still = true
            inst.runOnMainSync { still = com.lxlauncher.tedit.Marketplace.installing.containsKey(ghost.id) }
            val after = store.installed().map { it.id }.toSet()
            info("INFO failed install: ${result?.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }}")
            when {
                !progressShown -> "the install never showed as running"
                result?.isFailure != true -> "reported success for an extension that does not exist"
                still -> "still shown as installing after it failed"
                after != before -> "the store changed: ${after - before}"
                ctx.cacheDir.listFiles { f -> f.name.startsWith("tedit-ext-") && f.name.endsWith(".vsix") }.orEmpty().isNotEmpty() -> "a download was left in the cache"
                else -> null
            }
        }
    }

    private fun market() {
        failedInstall()
        val api = OpenVsx(ctx)
        var reachable = true
        check("Open VSX search answers by category and order") {
            val started = SystemClock.elapsedRealtime()
            val page = runCatching { api.search("", "Themes", "downloadCount", 0, 10) }.getOrElse {
                reachable = false
                info("INFO Open VSX not reachable from this device (${it.javaClass.simpleName}: ${it.message}); marketplace checks skipped")
                return@check null
            }
            info("PERF Open VSX search ${SystemClock.elapsedRealtime() - started} ms, ${page.total} themes, first ${page.items.take(3).map { it.id }}")
            if (page.items.isEmpty() || page.total < 10) "only ${page.total} results" else null
        }
        if (!reachable) return
        check("a theme from Open VSX downloads, matches its checksum, installs and colours Tedit") {
            val started = SystemClock.elapsedRealtime()
            val d = api.detail("dracula-theme", "theme-dracula")
            val f = File(scratch, "dracula.vsix")
            api.download(d, f, { _, _ -> }, { false })
            val e = store.install(f, org.json.JSONObject().put("source", "open-vsx"))
            installedHere += e.id
            info("PERF Dracula ${d.item.version}: ${f.length() / 1024} KB in ${SystemClock.elapsedRealtime() - started} ms, themes ${e.themes.map { it.label }}")
            val t: VsTheme = store.theme(store.themeKey(e, e.themes.first())) ?: return@check "theme unreadable"
            val bg = t.color("editor.background")
            when {
                bg == null -> "no editor background"
                t.foreground("comment") == null -> "no comment colour"
                !t.dark -> "Dracula read as light"
                else -> null
            }
        }
        check("a grammar extension from Open VSX gives a new language its colours and comment rules") {
            val d = api.detail("wholroyd", "jinja")
            val f = File(scratch, "jinja.vsix")
            api.download(d, f, { _, _ -> }, { false })
            val e = store.install(f)
            installedHere += e.id
            val lang = com.lxlauncher.tedit.ext.ExtLanguages.forFile(store, "page.j2") ?: return@check "no language for .j2 from ${e.languages.map { it.id + it.extensions }}"
            val sink = com.lxlauncher.tedit.SpanSink()
            var state = 0
            val kinds = HashSet<Int>()
            for (line in listOf("{% for item in items %}", "  <li>{{ item.name | upper }}</li>", "{# a comment #}", "{% endfor %}")) {
                sink.reset()
                state = lang.lexer.lex(line, state, sink)
                var i = 0
                while (i < sink.size) { kinds += sink.data[i + 2]; i += 3 }
            }
            info("INFO jinja ${d.item.version}: language ${lang.id} '${lang.name}', comment ${lang.blockComment}, token kinds $kinds")
            if (kinds.size < 2) "only ${kinds.size} token kinds" else null
        }
        check("an installed C++ grammar replaces Tedit's own and keeps up with long files") {
            val d = api.detail("jeff-hykin", "better-cpp-syntax")
            val f = File(scratch, "cpp.vsix")
            api.download(d, f, { _, _ -> }, { false })
            val e = store.install(f)
            installedHere += e.id
            val lang = com.lxlauncher.tedit.ext.ExtLanguages.forFile(store, "main.cpp") ?: return@check "no language for .cpp"
            val code = listOf("#include <vector>", "// sum of squares", "template <typename T>", "T sum(const std::vector<T>& v) {",
                "    T total{}; /* start */", "    for (auto& x : v) total += x * x;", "    return total;", "}", "int main() { return sum<int>({1, 2, 3}) == 14 ? 0 : 1; }")
            val sink = com.lxlauncher.tedit.SpanSink()
            var state = 0
            val started = System.nanoTime()
            var spans = 0
            repeat(2000 / code.size) { for (line in code) { sink.reset(); state = lang.lexer.lex(line, state, sink); spans += sink.size / 3 } }
            val ms = (System.nanoTime() - started) / 1e6
            info("PERF better-cpp-syntax ${d.item.version}: ${lang.name}, 2,000 lines in %.0f ms, $spans spans".format(ms))
            // As the Tedit window wires it: detection asks the extensions first, and that must not loop.
            val before = com.lxlauncher.tedit.Languages.external
            val detected = try {
                com.lxlauncher.tedit.Languages.external = { com.lxlauncher.tedit.ext.ExtLanguages.all(store) }
                com.lxlauncher.tedit.Languages.detect("main.cpp", null, emptySet()) to com.lxlauncher.tedit.Languages.byId("cpp")
            } finally {
                com.lxlauncher.tedit.Languages.external = before
            }
            when {
                detected.first.pack != "ext:${e.id}" || detected.second?.pack != "ext:${e.id}" -> "detection did not pick the extension's C++"
                lang.pack != "ext:${e.id}" -> "Tedit's own C++ still used"
                spans < 2000 -> "few spans: $spans"
                else -> null
            }
        }
        check("an extension's grammar colours in the background and the editor's thread stays within its budget") {
            val lang = com.lxlauncher.tedit.ext.ExtLanguages.forFile(store, "main.cpp") ?: return@check "no C++ grammar installed"
            val code = listOf("#include <vector>", "// sum of squares", "template <typename T>", "T sum(const std::vector<T>& v) {",
                "    T total{}; /* start */", "    for (auto& x : v) total += x * x;", "    return total;", "}", "int main() { return sum<int>({1, 2, 3}) == 14 ? 0 : 1; }")
            val lines = List(3_000) { code[it % code.size] }
            // What lexing every line in order gives: the background must end up with exactly this.
            val expected = arrayOfNulls<IntArray>(lines.size)
            var st = lang.lexer.initialState
            for ((i, l) in lines.withIndex()) {
                val sink = com.lxlauncher.tedit.SpanSink()
                st = lang.lexer.lex(l, st, sink)
                if (i < 60 || i in 2_900 until 2_960) expected[i] = sink.toArray()
            }
            val buf = com.lxlauncher.tedit.TextBuffer(lines.joinToString("\n"))
            val refreshed = java.util.concurrent.Semaphore(0)
            lateinit var hl: com.lxlauncher.tedit.Highlighter
            var frameMs = 0.0
            inst.runOnMainSync {
                hl = com.lxlauncher.tedit.Highlighter(buf, lang, 20_000)
                hl.onRefresh.add { refreshed.release() }
                val t0 = System.nanoTime()
                for (l in 0 until 60) hl.spans(l)
                frameMs = (System.nanoTime() - t0) / 1e6
            }
            fun waitFor(what: () -> Boolean): Long {
                val started = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - started < 30_000) {
                    var ok = false
                    inst.runOnMainSync { ok = what() }
                    if (ok) return SystemClock.elapsedRealtime() - started
                    refreshed.tryAcquire(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return -1
            }
            fun matches(range: IntRange) = range.all { hl.spans(it).contentEquals(expected[it]) }
            fun firstMismatch(range: IntRange): String {
                var out = ""
                inst.runOnMainSync {
                    val l = range.firstOrNull { !hl.spans(it).contentEquals(expected[it]) } ?: return@runOnMainSync
                    out = "line ${l + 1} '${lines[l]}': background ${hl.spans(l).toList()}, in order ${expected[l]?.toList()}"
                }
                return out
            }
            try {
                val screen = waitFor { matches(0 until 60) }
                val screenMiss = if (screen < 0) firstMismatch(0 until 60) else ""
                // An edit: the edited line is coloured again from its new text, not kept from the old.
                var edited = -1
                inst.runOnMainSync {
                    buf.replace(com.lxlauncher.tedit.Pos(4, 0), com.lxlauncher.tedit.Pos(4, 0), "/* note */")
                    hl.spans(4)
                }
                val afterEdit = waitFor { hl.kindAt(4, 1).also { edited = it } == com.lxlauncher.tedit.Token.COMMENT }
                inst.runOnMainSync { buf.replace(com.lxlauncher.tedit.Pos(4, 0), com.lxlauncher.tedit.Pos(4, 10), "") }
                // A jump far down the file gets its colours without lexing every line before it on the main thread.
                var jumpMs = 0.0
                inst.runOnMainSync { val t0 = System.nanoTime(); for (l in 2_900 until 2_960) hl.spans(l); jumpMs = (System.nanoTime() - t0) / 1e6 }
                val far = waitFor { matches(2_900 until 2_960) }
                val farMiss = if (far < 0) firstMismatch(2_900 until 2_960) else ""
                info("PERF background colouring: first screen asked in %.1f ms, right in $screen ms; edit right in $afterEdit ms; jump to line 2,900 asked in %.1f ms, right in $far ms".format(frameMs, jumpMs))
                when {
                    frameMs > 50 -> "asking for the first screen took %.1f ms on the main thread".format(frameMs)
                    jumpMs > 50 -> "asking for line 2,900 took %.1f ms on the main thread".format(jumpMs)
                    screen < 0 -> "the first screen never got the right colours: $screenMiss"
                    afterEdit < 0 -> "the edited line kept its old colours (kind $edited)"
                    far < 0 -> "line 2,900 never got the right colours: $farMiss"
                    else -> null
                }
            } finally {
                inst.runOnMainSync { hl.dispose() }
            }
        }
        check("README and icon come from the registry") {
            val d = api.detail("dracula-theme", "theme-dracula")
            val md = api.readme(d)
            val icon = d.item.iconUrl?.let { api.icon(it, 96) }
            when {
                md.isNullOrBlank() -> "no README"
                icon == null -> "no icon"
                else -> null
            }
        }
    }
}
