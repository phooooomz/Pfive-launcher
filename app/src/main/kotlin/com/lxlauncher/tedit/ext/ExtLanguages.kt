// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import android.util.Log
import com.lxlauncher.tedit.Language
import com.lxlauncher.tedit.Languages
import org.json.JSONArray

/**
 * Languages that installed extensions bring: their file names and extensions, a TextMate grammar for the colours,
 * and the comment, bracket and pairing rules from language-configuration.json. An extension's grammar also
 * replaces Tedit's own colouring of a language it covers, as an installed language extension does in VS Code.
 */
object ExtLanguages {
    private class Built(val key: String, val language: Language)
    private val grammars = HashMap<String, TmGrammar?>()
    private var built: List<Built> = emptyList()
    private var builtFor: List<String> = emptyList()

    /** Tedit's language ids for VS Code's, where they differ. */
    private val TEDIT_IDS = mapOf("shellscript" to "shell", "javascriptreact" to "javascript", "typescriptreact" to "typescript")

    @Synchronized
    fun all(store: ExtensionStore): List<Language> {
        val enabled = store.enabled()
        val stamp = enabled.map { "${it.id}@${it.version}" }
        if (stamp == builtFor) return built.map { it.language }
        grammars.clear()
        val out = ArrayList<Built>()
        for (e in enabled) for (ref in e.languages) {
            val g = e.grammars.firstOrNull { it.language == ref.id } ?: continue
            val grammar = grammar(store, g.scopeName) ?: continue
            val cfg = ref.configuration?.let { path -> runCatching { Jsonc.obj(e.file(path)!!.readText()) }.getOrNull() }
            val teditId = TEDIT_IDS[ref.id] ?: ref.id
            // Tedit's own languages only: byId would ask the extensions again, from inside this very list.
            val builtIn = Languages.all.firstOrNull { it.id == teditId }
            fun pairs(key: String): String? = cfg?.optJSONArray(key)?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    when (val p = a.opt(i)) {
                        is JSONArray -> if (p.length() == 2 && p.optString(0).length == 1 && p.optString(1).length == 1) p.optString(0) + p.optString(1) else null
                        is org.json.JSONObject -> { val o = p.optString("open"); val c = p.optString("close"); if (o.length == 1 && c.length == 1) o + c else null }
                        else -> null
                    }
                }.joinToString("").takeIf { it.isNotEmpty() }
            }
            val comments = cfg?.optJSONObject("comments")
            val block = comments?.optJSONArray("blockComment")?.takeIf { it.length() == 2 }?.let { it.optString(0) to it.optString(1) }
            val lang = Language(
                id = teditId,
                name = ref.aliases.firstOrNull() ?: builtIn?.name ?: ref.id,
                pack = "ext:${e.id}",
                extensions = ref.extensions.map { it.removePrefix(".").lowercase() }.toSet() + (builtIn?.extensions ?: emptySet()),
                fileNames = ref.filenames.toSet() + (builtIn?.fileNames ?: emptySet()),
                lineComment = comments?.optString("lineComment")?.takeIf { it.isNotEmpty() } ?: builtIn?.lineComment,
                blockComment = block ?: builtIn?.blockComment,
                brackets = pairs("brackets") ?: builtIn?.brackets ?: "()[]{}",
                autoClose = pairs("autoClosingPairs") ?: builtIn?.autoClose ?: "()[]{}\"\"''",
                lexer = TmLexer(grammar, { scope -> grammar(store, scope) }, { scope -> injectors(store, scope) }),
            )
            out += Built("${e.id}/${ref.id}", lang)
        }
        // Grammars for a language the extension does not define itself (better-cpp-syntax gives C++ new colours):
        // Tedit's own language keeps its names and rules and takes the grammar.
        for (e in enabled) for (g in e.grammars) {
            val id = g.language ?: continue
            if (e.languages.any { it.id == id }) continue
            val teditId = TEDIT_IDS[id] ?: id
            if (out.any { it.language.id == teditId }) continue
            val builtIn = Languages.all.firstOrNull { it.id == teditId } ?: continue
            val grammar = grammar(store, g.scopeName) ?: continue
            out += Built("${e.id}/$id", Language(builtIn.id, builtIn.name, "ext:${e.id}", builtIn.extensions, builtIn.fileNames,
                builtIn.lineComment, builtIn.blockComment, builtIn.brackets, builtIn.autoClose,
                TmLexer(grammar, { scope -> grammar(store, scope) }, { scope -> injectors(store, scope) })))
        }
        built = out
        builtFor = stamp
        return out.map { it.language }
    }

    /** A grammar by scope name from any enabled extension, parsed once. */
    @Synchronized
    fun grammar(store: ExtensionStore, scope: String): TmGrammar? = grammars.getOrPut(scope) {
        for (e in store.enabled()) {
            val ref = e.grammars.firstOrNull { it.scopeName == scope } ?: continue
            val f = e.file(ref.path) ?: continue
            return@getOrPut runCatching { TmGrammar.parse(f.readText()) }
                .onFailure { Log.w(ExtensionStore.TAG, "grammar $scope in ${e.id} unreadable: ${it.message}") }.getOrNull()
        }
        null
    }

    /**
     * Grammars that enabled extensions inject into [scope] with `injectTo`. As in VS Code, an injection for text.html
     * also reaches text.html.basic: every dotted prefix of the scope counts.
     */
    @Synchronized
    fun injectors(store: ExtensionStore, scope: String): List<TmGrammar> {
        val prefixes = HashSet<String>()
        var s = scope
        while (true) {
            prefixes.add(s)
            val dot = s.lastIndexOf('.')
            if (dot <= 0) break
            s = s.substring(0, dot)
        }
        val out = ArrayList<TmGrammar>()
        for (e in store.enabled()) for (g in e.grammars) {
            if (g.injectTo.none { it in prefixes }) continue
            grammar(store, g.scopeName)?.let { if (it.injectionSelector != null) out.add(it) }
        }
        return out
    }

    /** The extension language for a file, by its name or extension, or null to let Tedit's own detection decide. */
    fun forFile(store: ExtensionStore, name: String): Language? {
        val langs = all(store)
        if (langs.isEmpty()) return null
        langs.firstOrNull { name in it.fileNames }?.let { return it }
        val ext = name.lowercase().substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        return langs.firstOrNull { ext in it.extensions }
    }
}
