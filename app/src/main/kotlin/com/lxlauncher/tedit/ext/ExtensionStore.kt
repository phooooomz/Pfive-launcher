// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/**
 * VS Code extensions installed in Tedit, from Open VSX or a .vsix file. Each lives unpacked in
 * files/tedit/extensions/<publisher.name>/, with Tedit's own record beside its package.json.
 *
 * What Tedit uses from an extension is what it declares rather than what it runs: colour themes, TextMate grammars,
 * language definitions with their comment and bracket settings, and snippets. Extensions whose work is JavaScript
 * run inside VS Code's extension host, which Tedit does not have; they install, and the panel says which of their
 * parts Tedit does not run.
 */
class ExtensionStore private constructor(context: Context) {
    private val ctx = context.applicationContext
    val dir = File(ctx.filesDir, "tedit/extensions")
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var cache: List<Installed>? = null

    class ThemeRef(val label: String, val uiTheme: String, val path: String) {
        val dark get() = uiTheme != "vs" && uiTheme != "hc-light"
    }
    class LanguageRef(val id: String, val aliases: List<String>, val extensions: List<String>, val filenames: List<String>, val configuration: String?)
    /** A grammar an extension brings; [injectTo] names the grammars it injects itself into, such as source.js. */
    class GrammarRef(val language: String?, val scopeName: String, val path: String, val injectTo: List<String> = emptyList())
    class SnippetRef(val language: String, val path: String)

    class Installed(
        val id: String,
        val namespace: String,
        val name: String,
        val displayName: String,
        val version: String,
        val description: String,
        val categories: List<String>,
        val root: File,
        val icon: File?,
        val readme: File?,
        val enabled: Boolean,
        val themes: List<ThemeRef>,
        val languages: List<LanguageRef>,
        val grammars: List<GrammarRef>,
        val snippets: List<SnippetRef>,
        /** JavaScript that VS Code would run (main/browser); Tedit does not. */
        val hasCode: Boolean,
        /** Contribution points Tedit has no use for, by name (keybindings, debuggers, …). */
        val unused: List<String>,
        val installedAt: Long,
    ) {
        fun file(rel: String): File? {
            val f = File(root, rel.removePrefix("./")).canonicalFile
            return f.takeIf { it.path.startsWith(root.canonicalPath + "/") && it.isFile }
        }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() {
        cache = null
        main.post { listeners.forEach { it() } }
    }

    /** Installed extensions, read once and kept until something changes. */
    fun installed(): List<Installed> = cache ?: synchronized(this) {
        cache ?: (dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.mapNotNull { read(it) }?.sortedBy { it.displayName.lowercase() }
            ?: emptyList()).also { cache = it }
    }

    fun get(id: String): Installed? = installed().firstOrNull { it.id == id }

    fun enabled(): List<Installed> = installed().filter { it.enabled }

    private fun read(root: File): Installed? = runCatching {
        val pkg = JSONObject(File(root, "package.json").readText())
        val meta = runCatching { JSONObject(File(root, META).readText()) }.getOrNull() ?: JSONObject()
        val ns = pkg.optString("publisher").ifEmpty { meta.optString("namespace") }
        val name = pkg.getString("name")
        val c = pkg.optJSONObject("contributes") ?: JSONObject()
        fun strings(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotEmpty() } }
        val themes = c.optJSONArray("themes")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val path = o.optString("path").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ThemeRef(o.optString("label").ifEmpty { o.optString("id").ifEmpty { path.substringAfterLast('/') } }, o.optString("uiTheme", "vs-dark"), path)
        } }.orEmpty()
        val languages = c.optJSONArray("languages")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            LanguageRef(o.optString("id").ifEmpty { return@mapNotNull null }, strings(o.optJSONArray("aliases")), strings(o.optJSONArray("extensions")),
                strings(o.optJSONArray("filenames")), o.optString("configuration").takeIf { it.isNotEmpty() })
        } }.orEmpty()
        val grammars = c.optJSONArray("grammars")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            GrammarRef(o.optString("language").takeIf { it.isNotEmpty() }, o.optString("scopeName").ifEmpty { return@mapNotNull null },
                o.optString("path").ifEmpty { return@mapNotNull null }, strings(o.optJSONArray("injectTo")))
        } }.orEmpty()
        val snippets = c.optJSONArray("snippets")?.let { a -> (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            SnippetRef(o.optString("language").ifEmpty { return@mapNotNull null }, o.optString("path").ifEmpty { return@mapNotNull null })
        } }.orEmpty()
        val known = setOf("themes", "languages", "grammars", "snippets")
        val unused = c.keys().asSequence().filter { it !in known }.toList().sorted()
        val icon = pkg.optString("icon").takeIf { it.isNotEmpty() }?.let { File(root, it) }?.takeIf { it.isFile }
        val readme = root.listFiles()?.firstOrNull { it.name.equals("README.md", true) }
        Installed(
            id = "$ns.$name".lowercase(), namespace = ns, name = name,
            displayName = pkg.optString("displayName").ifEmpty { name }, version = pkg.optString("version"),
            description = pkg.optString("description"), categories = strings(pkg.optJSONArray("categories")),
            root = root, icon = icon, readme = readme, enabled = meta.optBoolean("enabled", true),
            themes = themes, languages = languages, grammars = grammars, snippets = snippets,
            hasCode = pkg.has("main") || pkg.has("browser"), unused = unused, installedAt = meta.optLong("installedAt"),
        )
    }.onFailure { Log.w(TAG, "skipped extension ${root.name}: ${it.javaClass.simpleName}") }.getOrNull()

    /**
     * Unpacks a .vsix (a zip with the extension in extension/) and installs it, replacing an older version. Only
     * files below extension/ are taken, no name may leave the folder, and size limits keep a hostile archive from
     * filling the phone.
     */
    fun install(vsix: File, source: JSONObject = JSONObject()): Installed {
        dir.mkdirs()
        val staging = File(dir, ".staging-${System.nanoTime()}")
        try {
            staging.mkdirs()
            val base = staging.canonicalPath + "/"
            var total = 0L
            var count = 0
            ZipFile(vsix).use { zip ->
                for (e in zip.entries()) {
                    if (!e.name.startsWith("extension/") || e.isDirectory) continue
                    if (++count > MAX_ENTRIES) throw IOException("too many files in the extension")
                    val rel = e.name.removePrefix("extension/")
                    if (rel.isEmpty() || rel.split('/').any { it == ".." }) throw IOException("refused name ${e.name}")
                    val out = File(staging, rel)
                    if (!out.canonicalPath.startsWith(base)) throw IOException("refused name ${e.name}")
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw IOException("the extension unpacks to more than ${MAX_BYTES / 1048576} MB")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            val pkg = runCatching { JSONObject(File(staging, "package.json").readText()) }.getOrNull() ?: throw IOException("no package.json in the extension")
            val id = "${pkg.optString("publisher")}.${pkg.optString("name")}".lowercase()
            if (!ID.matches(id)) throw IOException("unusable extension id $id")
            File(staging, META).writeText(source.put("enabled", true).put("installedAt", System.currentTimeMillis()).toString())
            val target = File(dir, id)
            synchronized(this) {
                if (target.exists()) target.deleteRecursively()
                if (!staging.renameTo(target)) throw IOException("cannot move the extension into place")
            }
            changed()
            Log.i(TAG, "installed $id ${pkg.optString("version")}: $count files, $total bytes")
            return get(id) ?: throw IOException("installed but unreadable")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun uninstall(id: String) {
        val target = File(dir, id)
        if (target.parentFile != dir) return
        target.deleteRecursively()
        changed()
    }

    fun setEnabled(id: String, on: Boolean) {
        val f = File(File(dir, id), META)
        val meta = runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
        f.writeText(meta.put("enabled", on).toString())
        changed()
    }

    private val snippetCache = HashMap<String, List<Snippet>>()

    /** Snippets enabled extensions contribute for a Tedit language, each file read once. */
    fun snippetsFor(teditLanguage: String): List<Snippet> {
        val ids = Snippets.vscodeIds(teditLanguage)
        return enabled().flatMap { e ->
            e.snippets.filter { it.language in ids }.flatMap { ref ->
                val key = "${e.id}@${e.version}/${ref.path}"
                synchronized(snippetCache) {
                    snippetCache.getOrPut(key) {
                        runCatching { Snippets.parseFile(e.file(ref.path)!!.readText()) }
                            .onFailure { Log.w(TAG, "snippets ${e.id}/${ref.path} unreadable: ${it.message}") }.getOrDefault(emptyList())
                    }
                }
            }
        }
    }

    /** "<id>/<label>" for a colour theme, as the setting stores it. */
    fun themeKey(e: Installed, t: ThemeRef) = "${e.id}/${t.label}"

    fun themes(): List<Pair<Installed, ThemeRef>> = enabled().flatMap { e -> e.themes.map { e to it } }

    fun theme(key: String): VsTheme? {
        val id = key.substringBefore('/')
        val label = key.substringAfter('/')
        val e = get(id)?.takeIf { it.enabled } ?: return null
        val ref = e.themes.firstOrNull { it.label == label } ?: return null
        return runCatching { VsTheme.load(e, ref) }.onFailure { Log.w(TAG, "theme $key unreadable: ${it.message}") }.getOrNull()
    }

    companion object {
        const val TAG = "LxExt"
        private const val META = ".tedit.json"
        private const val MAX_ENTRIES = 20_000
        private const val MAX_BYTES = 300L * 1024 * 1024
        private val ID = Regex("[a-z0-9][a-z0-9-]*\\.[a-z0-9][a-z0-9._-]*")

        // Holds the application context only.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ExtensionStore? = null
        fun get(context: Context): ExtensionStore = instance ?: synchronized(this) {
            instance ?: ExtensionStore(context).also { instance = it }
        }
    }
}
