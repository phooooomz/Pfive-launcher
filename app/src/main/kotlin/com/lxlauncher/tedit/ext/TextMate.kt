// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import android.util.Log
import android.util.Xml
import com.lxlauncher.tedit.Lexer
import com.lxlauncher.tedit.SpanSink
import com.lxlauncher.tedit.Token
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/*
 * TextMate grammars as VS Code extensions ship them (.tmLanguage.json, and the older .tmLanguage plist), run as a Tedit
 * Lexer. The tokenizer follows vscode-textmate: a stack of open begin/end rules carried from line to line, and at each
 * position the pattern that matches first wins. Patterns are written for Oniguruma; they are rewritten for
 * java.util.regex, which on Android is ICU underneath.
 */

private const val TAG = "LxTextMate"

private const val K_GROUP = 0
private const val K_MATCH = 1
private const val K_BEGIN_END = 2
private const val K_BEGIN_WHILE = 3
private const val K_INCLUDE = 4

/** Highest capture group read from a grammar. HTML's named entities use group 912. */
private const val MAX_CAPTURE = 2_000

private val ruleIds = AtomicInteger()

/** Repositories visible from a rule, innermost first: `#name` looks here before the grammar's own repository. */
internal class Repo(val rules: HashMap<String, TmRule>, val outer: Repo?)

/** One rule of a grammar. [TmGrammar.parse] builds it and nothing changes it afterwards. */
class TmRule internal constructor(internal val grammar: TmGrammar, internal val repo: Repo?) {
    internal val id = ruleIds.incrementAndGet()
    internal var kind = K_GROUP
    /** The scope name(s) the rule gives its text, as the grammar writes them. */
    var name: String? = null
        internal set
    internal var match: String? = null
    internal var begin: String? = null
    /** `end` of a begin/end rule, `while` of a begin/while rule. */
    internal var end: String? = null
    internal var endBackrefs = false
    internal var include: String? = null
    internal var patterns: List<TmRule> = emptyList()
    internal var applyEndPatternLast = false
    // Token kinds, worked out once from the scope names; -1 means the scope says nothing and the outer one shows.
    internal var nameKind = -1
    internal var contentKind = -1
    internal var contentName: String? = null
    /** `name` and `contentName` when they hold $1 or ${1:/downcase}, worked out from each match. */
    internal var nameDyn: String? = null
    internal var contentDyn: String? = null
    internal var captures: Caps? = null
    internal var beginCaps: Caps? = null
    /** Captures of `end`, or of `while`. */
    internal var endCaps: Caps? = null
}

/**
 * The captures of one pattern by group number: the kind each one gives, the rule holding a capture's own `patterns`
 * (its text is tokenized again with them), and names with $n that depend on the match.
 */
internal class Caps(val kinds: IntArray, val rules: Array<TmRule?>?, val dynamic: Array<String?>?) {
    val size = kinds.size
}

private val CAPTURE_REF = Regex("""\$(\d+)|\$\{(\d+):/(downcase|upcase)\}""")

/** True when a scope name takes text from the match, as storage.type.$1.cpp does. */
private fun isDynamic(name: String?) = name != null && name.indexOf('$') >= 0 && CAPTURE_REF.containsMatchIn(name)

/**
 * [name] with $n and ${n:/downcase} filled from the match as vscode-textmate does: leading dots dropped, a group that
 * did not take part gives nothing, a group the pattern does not have leaves the reference as written.
 */
private fun substitute(name: String, count: Int, start: (Int) -> Int, end: (Int) -> Int, text: String): String =
    CAPTURE_REF.replace(name) { r ->
        val g = (r.groupValues[1].ifEmpty { r.groupValues[2] }).toIntOrNull() ?: return@replace r.value
        if (g > count) return@replace r.value
        val s = start(g)
        var got = if (s < 0) "" else text.substring(s, end(g))
        got = got.trimStart('.')
        when (r.groupValues[3]) {
            "downcase" -> got.lowercase()
            "upcase" -> got.uppercase()
            else -> got
        }
    }

/** A parsed grammar: its scope, the top-level patterns as [root], and the top-level [repository]. */
class TmGrammar private constructor(
    val scopeName: String,
    val name: String?,
    val fileTypes: List<String>,
    val firstLineMatch: String?,
) {
    lateinit var root: TmRule
        private set
    var repository: Map<String, TmRule> = emptyMap()
        private set
    /** The grammar's own `injections`: a scope selector and the patterns it brings where the selector matches. */
    var injections: List<Pair<String, TmRule>> = emptyList()
        private set
    /** Where this grammar injects itself into others (an extension names the hosts with `injectTo`). */
    var injectionSelector: String? = null
        private set

    companion object {
        /**
         * Reads a grammar in JSON (comments and trailing commas allowed) or in the XML plist format. Throws
         * IllegalArgumentException with a readable message when the text is not a usable grammar.
         */
        fun parse(text: String): TmGrammar {
            val t = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            val tree = if (t.startsWith("<")) parsePlist(t) else parseJson(t)
            val scope = tree["scopeName"] as? String
            if (scope.isNullOrBlank()) throw IllegalArgumentException("TextMate grammar has no scopeName")
            val types = (tree["fileTypes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val g = TmGrammar(scope, tree["name"] as? String, types, tree["firstLineMatch"] as? String)
            val top = Repo(HashMap(), null)
            g.fillRepo(tree["repository"], top)
            g.root = TmRule(g, top).apply {
                patterns = g.rules(tree["patterns"], top)
                nameKind = kindOfNames(scope)
                contentKind = nameKind
            }
            g.repository = top.rules
            g.injectionSelector = tree["injectionSelector"] as? String
            g.injections = (tree["injections"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                val selector = k as? String ?: return@mapNotNull null
                val body = v as? Map<*, *> ?: return@mapNotNull null
                selector to g.rule(body, top)
            } ?: emptyList()
            return g
        }

        private fun parseJson(text: String): Map<String, Any?> {
            val clean = cleanJson(text).trim()
            if (!clean.startsWith("{")) throw IllegalArgumentException("TextMate grammar: expected a JSON object or a plist")
            try {
                @Suppress("UNCHECKED_CAST")
                return fromJson(JSONObject(clean)) as Map<String, Any?>
            } catch (e: JSONException) {
                throw IllegalArgumentException("TextMate grammar JSON: ${e.message}", e)
            }
        }

        private fun fromJson(v: Any?): Any? = when {
            v is JSONObject -> {
                val m = LinkedHashMap<String, Any?>()
                val keys = v.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k] = fromJson(v.opt(k)) }
                m
            }
            v is JSONArray -> List(v.length()) { fromJson(v.opt(it)) }
            v == null || v == JSONObject.NULL -> null
            else -> v
        }

        /** Drops comments outside strings and commas right before } or ], which hand-written grammars carry. */
        private fun cleanJson(s: String): String {
            val n = s.length
            val sb = StringBuilder(n)
            var i = 0
            while (i < n) {
                val c = s[i]
                when {
                    c == '"' -> {
                        var j = i + 1
                        while (j < n && s[j] != '"') j += if (s[j] == '\\') 2 else 1
                        j = minOf(j + 1, n)
                        sb.append(s, i, j)
                        i = j
                    }
                    c == '/' && i + 1 < n && (s[i + 1] == '/' || s[i + 1] == '*') -> {
                        i = skipComment(s, i)
                        sb.append(' ')
                    }
                    c == ',' -> {
                        val next = skipBlank(s, i + 1)
                        if (next >= n || (s[next] != '}' && s[next] != ']')) sb.append(c)
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        /** [i] is at a line or block comment; returns the index just past it. */
        private fun skipComment(s: String, i: Int): Int {
            if (s[i + 1] == '/') {
                val nl = s.indexOf('\n', i)
                return if (nl < 0) s.length else nl
            }
            val close = s.indexOf("*/", i + 2)
            return if (close < 0) s.length else close + 2
        }

        private fun skipBlank(s: String, from: Int): Int {
            var i = from
            while (i < s.length) {
                val c = s[i]
                if (c.isWhitespace()) { i++; continue }
                if (c == '/' && i + 1 < s.length && (s[i + 1] == '/' || s[i + 1] == '*')) { i = skipComment(s, i); continue }
                break
            }
            return i
        }

        private fun parsePlist(text: String): Map<String, Any?> {
            try {
                val p = Xml.newPullParser()
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                p.setInput(StringReader(text))
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "dict") {
                        @Suppress("UNCHECKED_CAST")
                        return plistValue(p) as Map<String, Any?>
                    }
                    ev = p.next()
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("TextMate grammar plist: ${e.message}", e)
            }
            throw IllegalArgumentException("TextMate grammar plist has no <dict>")
        }

        /** Reads the element the parser stands on and leaves the parser on its end tag. */
        private fun plistValue(p: XmlPullParser): Any? = when (p.name) {
            "dict" -> {
                val m = LinkedHashMap<String, Any?>()
                var key: String? = null
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.END_TAG) break
                    if (ev == XmlPullParser.END_DOCUMENT) throw IllegalArgumentException("TextMate grammar plist ends inside <dict>")
                    if (ev != XmlPullParser.START_TAG) continue
                    if (p.name == "key") key = p.nextText()
                    else {
                        val v = plistValue(p)
                        if (key != null) m[key] = v
                        key = null
                    }
                }
                m
            }
            "array" -> {
                val list = ArrayList<Any?>()
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.END_TAG) break
                    if (ev == XmlPullParser.END_DOCUMENT) throw IllegalArgumentException("TextMate grammar plist ends inside <array>")
                    if (ev == XmlPullParser.START_TAG) list.add(plistValue(p))
                }
                list
            }
            "string", "integer", "real", "data", "date" -> p.nextText()
            "true", "false" -> {
                val v = p.name == "true"
                skipElement(p)
                v
            }
            else -> { skipElement(p); null }
        }

        private fun skipElement(p: XmlPullParser) {
            var depth = 1
            while (depth > 0) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.END_DOCUMENT -> return
                }
            }
        }
    }

    private fun fillRepo(v: Any?, repo: Repo) {
        val m = v as? Map<*, *> ?: return
        for ((k, entry) in m) {
            val key = k as? String ?: continue
            val body = entry as? Map<*, *> ?: continue
            repo.rules[key] = rule(body, repo)
        }
    }

    private fun rules(v: Any?, repo: Repo?): List<TmRule> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { item -> (item as? Map<*, *>)?.let { rule(it, repo) } }
    }

    private fun rule(m: Map<*, *>, outer: Repo?): TmRule {
        var repo = outer
        if (m["repository"] is Map<*, *>) {
            repo = Repo(HashMap(), outer)
            fillRepo(m["repository"], repo)
        }
        val r = TmRule(this, repo)
        r.name = m["name"] as? String
        val contentName = m["contentName"] as? String
        val match = m["match"] as? String
        val begin = m["begin"] as? String
        val whilePattern = m["while"] as? String
        val include = m["include"] as? String
        when {
            match != null -> {
                r.kind = K_MATCH
                r.match = match
                r.captures = caps(m["captures"], repo)
            }
            begin != null -> {
                r.kind = if (whilePattern != null) K_BEGIN_WHILE else K_BEGIN_END
                r.begin = begin
                r.end = whilePattern ?: m["end"] as? String
                r.endBackrefs = r.end?.let { hasBackref(it) } ?: false
                r.applyEndPatternLast = m["applyEndPatternLast"].let { it == true || it == 1 || it == "1" || it == "true" }
                val shared = m["captures"]
                val ends = (if (whilePattern != null) m["whileCaptures"] else m["endCaptures"]) ?: shared
                r.beginCaps = caps(m["beginCaptures"] ?: shared, repo)
                r.endCaps = caps(ends, repo)
                r.patterns = rules(m["patterns"], repo)
            }
            include != null && m["patterns"] == null -> {
                r.kind = K_INCLUDE
                r.include = include
            }
            else -> r.patterns = rules(m["patterns"], repo)
        }
        r.contentName = contentName
        r.nameKind = kindOfNames(r.name)
        r.contentKind = kindOfNames(contentName)
        if (r.kind == K_MATCH || r.kind == K_BEGIN_END || r.kind == K_BEGIN_WHILE) {
            if (isDynamic(r.name)) r.nameDyn = r.name
            if (isDynamic(contentName)) r.contentDyn = contentName
        }
        return r
    }

    /** The captures a grammar lists, by group number; null when none says anything or has patterns. */
    private fun caps(v: Any?, repo: Repo?): Caps? {
        val found = ArrayList<Triple<Int, Map<*, *>, TmRule?>>()
        fun add(index: Int, body: Any?) {
            if (index !in 0..MAX_CAPTURE) return
            val m = body as? Map<*, *> ?: return
            val again = (m["patterns"] as? List<*>)?.let {
                TmRule(this, repo).apply {
                    name = m["name"] as? String
                    contentName = m["contentName"] as? String
                    patterns = rules(it, repo)
                    nameKind = kindOfNames(name)
                    contentKind = kindOfNames(contentName)
                }
            }?.takeIf { it.patterns.isNotEmpty() }
            found.add(Triple(index, m, again))
        }
        when (v) {
            is Map<*, *> -> for ((k, body) in v) (k as? String)?.trim()?.toIntOrNull()?.let { add(it, body) }
            is List<*> -> v.forEachIndexed { i, body -> add(i, body) }
        }
        val size = (found.maxOfOrNull { it.first } ?: return null) + 1
        val kinds = IntArray(size) { -1 }
        var rules: Array<TmRule?>? = null
        var dynamic: Array<String?>? = null
        for ((i, m, again) in found) {
            val name = m["name"] as? String
            kinds[i] = kindOfNames(name)
            if (again != null) (rules ?: arrayOfNulls<TmRule>(size).also { rules = it })[i] = again
            if (isDynamic(name)) (dynamic ?: arrayOfNulls<String>(size).also { dynamic = it })[i] = name
        }
        if (rules == null && dynamic == null && kinds.all { it < 0 }) return null
        return Caps(kinds, rules, dynamic)
    }
}

/** True when [src] holds \1 to \9 outside an escaped backslash. */
private fun hasBackref(src: String): Boolean {
    var i = 0
    while (i < src.length - 1) {
        if (src[i] == '\\') {
            if (src[i + 1] in '1'..'9') return true
            i += 2
        } else i++
    }
    return false
}

/** A scope selector as vscode-textmate reads it, with its priority: -1 for L:, 1 for R:, 0 otherwise. */
internal class ScopeMatcher(val priority: Int, private val test: (List<String>) -> Boolean) {
    fun matches(scopes: List<String>) = test(scopes)

    companion object {
        private val TOKEN = Regex("""([LR]:|[\w.:][\w.:\-]*|[,|\-()])""")
        private val IDENT = Regex("""[\w.:]+""")

        /**
         * Parses [selector] the way vscode-textmate's matcher does: names in a row are a path matched in order, a
         * space joins operands with "and", - negates, | and , inside parentheses mean "or", and a top-level comma
         * starts another selector with its own L: or R: priority.
         */
        fun parse(selector: String): List<ScopeMatcher> {
            val tokens = TOKEN.findAll(selector).map { it.value }.toList()
            var at = 0
            var token: String? = tokens.getOrNull(0)
            fun next() { at++; token = tokens.getOrNull(at) }
            fun identifier(t: String?) = t != null && IDENT.containsMatchIn(t)
            lateinit var inner: () -> ((List<String>) -> Boolean)
            fun operand(): ((List<String>) -> Boolean)? {
                val t = token
                if (t == "-") {
                    next()
                    val negated = operand()
                    return { s -> negated != null && !negated(s) }
                }
                if (t == "(") {
                    next()
                    val e = inner()
                    if (token == ")") next()
                    return e
                }
                if (identifier(t)) {
                    val names = ArrayList<String>()
                    do { names.add(token!!); next() } while (identifier(token))
                    return { s -> pathMatches(names, s) }
                }
                return null
            }
            fun conjunction(): (List<String>) -> Boolean {
                val all = ArrayList<(List<String>) -> Boolean>()
                while (true) all.add(operand() ?: break)
                return { s -> all.all { it(s) } }
            }
            inner = {
                val any = ArrayList<(List<String>) -> Boolean>()
                while (true) {
                    any.add(conjunction())
                    if (token == "|" || token == ",") { do next() while (token == "|" || token == ",") } else break
                }
                val r: (List<String>) -> Boolean = { s -> any.any { it(s) } }
                r
            }
            val out = ArrayList<ScopeMatcher>()
            while (token != null) {
                var priority = 0
                val t = token!!
                if (t.length == 2 && t[1] == ':') {
                    priority = when (t[0]) { 'R' -> 1; 'L' -> -1; else -> 0 }
                    next()
                }
                out.add(ScopeMatcher(priority, conjunction()))
                if (token != ",") break
                next()
            }
            return out
        }

        /** Each name matches a later scope than the one before, as itself or as a dotted prefix of it. */
        private fun pathMatches(names: List<String>, scopes: List<String>): Boolean {
            if (scopes.size < names.size) return false
            var from = 0
            for (n in names) {
                var found = false
                for (i in from until scopes.size) {
                    val sc = scopes[i]
                    if (sc == n || (sc.length > n.length && sc.startsWith(n) && sc[n.length] == '.')) { from = i + 1; found = true; break }
                }
                if (!found) return false
            }
            return true
        }
    }
}

// Scope prefix to token kind. A scope takes the entry of its longest dotted prefix: keyword.control.flow.js finds
// keyword.control before keyword. The embedded scopes map to PLAIN on purpose, so embedded code and ${...} inside a
// template string do not inherit the colour of what surrounds them. source and text stay out: HTML names the < of
// </script> source.js-ignored-vscode, and that must keep the tag colour its outer capture gave it.
private val SCOPE_KINDS: HashMap<String, Int> = HashMap<String, Int>().apply {
    fun put(kind: Int, vararg scopes: String) { for (s in scopes) this[s] = kind }
    put(Token.COMMENT, "comment", "punctuation.definition.comment", "markup.quote")
    put(Token.STRING, "string", "punctuation.definition.string", "markup.inline.raw")
    put(Token.NUMBER, "constant.numeric")
    put(Token.CONSTANT, "constant", "constant.character.escape", "constant.language", "constant.other", "support.constant",
        "variable.other.constant")
    put(Token.CONTROL, "keyword.control")
    put(Token.OPERATOR, "keyword.operator")
    put(Token.KEYWORD, "keyword", "storage", "storage.modifier", "storage.type.function", "storage.type.class",
        "storage.type.def", "variable.language")
    put(Token.TYPE, "storage.type", "entity.name.type", "entity.name.class", "entity.name.namespace", "support.class",
        "support.type", "entity.other.inherited-class")
    put(Token.FUNCTION, "entity.name.function", "support.function", "meta.function-call.generic")
    put(Token.TAG, "entity.name.tag")
    put(Token.ATTRIBUTE, "entity.other.attribute-name", "support.type.property-name", "punctuation.support.type.property-name")
    put(Token.VARIABLE, "variable", "variable.parameter", "variable.other", "support.variable")
    put(Token.PUNCT, "punctuation")
    put(Token.HEADING, "markup.heading", "entity.name.section")
    put(Token.INSERTED, "markup.inserted")
    put(Token.DELETED, "markup.deleted")
    put(Token.INVALID, "invalid")
    put(Token.ANNOTATION, "meta.decorator", "storage.type.annotation", "entity.name.function.decorator", "punctuation.decorator")
    put(Token.PLAIN, "meta.embedded", "meta.template.expression")
}

/** The kind one scope asks for, or -1 when no prefix of it is known. */
private fun kindOfScope(scope: String): Int {
    var s = scope
    while (true) {
        SCOPE_KINDS[s]?.let { return it }
        val dot = s.lastIndexOf('.')
        if (dot <= 0) return -1
        s = s.substring(0, dot)
    }
}

/** For a space-separated scope list the last scope with an opinion wins, as the innermost does in VS Code. */
internal fun kindOfNames(names: String?): Int {
    if (names.isNullOrBlank()) return -1
    var kind = -1
    for (s in names.split(' ', '\t')) if (s.isNotEmpty()) { val k = kindOfScope(s); if (k >= 0) kind = k }
    return kind
}

/** The [Token] kind for a TextMate scope name (or a space-separated list of them); PLAIN when nothing matches. */
fun tokenFor(scope: String): Int = kindOfNames(scope).let { if (it < 0) Token.PLAIN else it }

// Oniguruma line semantics: ^ and $ work at line breaks, the dot stops at \n, and only \n ends a line.
private const val REGEX_FLAGS = Pattern.MULTILINE or Pattern.UNIX_LINES or Pattern.UNICODE_CASE

/**
 * One grammar pattern compiled twice when it uses \G: as written, for a search that starts where the last begin or
 * while match ended, and with \G made unmatchable for every other position, as vscode-textmate does. A null pattern
 * means the source did not compile; the rule using it is skipped.
 */
internal class TmRegex private constructor(
    /** By [slot]: \G allowed or not, \A allowed or not. Patterns without them share one compiled pattern. */
    private val variants: Array<Pattern?>,
    val hasG: Boolean,
    val hasA: Boolean,
) {
    val ok get() = variants.any { it != null }

    fun pattern(g: Boolean, a: Boolean): Pattern? = variants[slot(g && hasG, a || !hasA)]

    companion object {
        private val cache = HashMap<String, TmRegex>()
        private val reported = HashSet<String>()

        private fun slot(g: Boolean, a: Boolean) = (if (g) 1 else 0) or (if (a) 2 else 0)

        fun get(src: String): TmRegex {
            synchronized(cache) { cache[src]?.let { return it } }
            val called = if (src.contains("\\g<") || src.contains("\\g'")) expandCalls(src) else src
            val full = translateOniguruma(called, keepG = true, keepA = true)
            // \G holds only where the last begin ended, \A only on a document's first line: vscode-textmate compiles
            // a pattern without them for the other places, and so does this.
            val hasG = full != translateOniguruma(called, keepG = false, keepA = true)
            val hasA = full != translateOniguruma(called, keepG = true, keepA = false)
            val variants = arrayOfNulls<Pattern>(4)
            val compiled = HashMap<String, Pattern?>()
            for (g in listOf(false, true)) for (a in listOf(false, true)) {
                val java = translateOniguruma(called, keepG = g || !hasG, keepA = a || !hasA)
                variants[slot(g, a)] = compiled.getOrPut(java) { compile(src, java) }
            }
            val rx = TmRegex(variants, hasG, hasA)
            synchronized(cache) {
                // End patterns filled from begin captures make new sources without limit; start over now and then.
                if (cache.size > 20_000) cache.clear()
                cache[src] = rx
            }
            return rx
        }

        // Oniguruma allows * and + inside a lookbehind (the YAML grammar relies on it). ICU refuses them, and Java
        // accepts some but then fails to match, so they are always bounded first.
        private fun compile(src: String, java: String): Pattern? = try {
            Pattern.compile(boundLookbehinds(java) ?: java, REGEX_FLAGS)
        } catch (e: Exception) {
            report(src, e)
            null
        } catch (e: StackOverflowError) {
            report(src, e)
            null
        }

        private fun report(src: String, e: Throwable) {
            val first = synchronized(reported) { reported.size < 2_000 && reported.add(src) }
            if (first) Log.w(TAG, "skipping a rule, its pattern does not compile: ${src.take(200)} (${e.message?.lineSequence()?.firstOrNull()})")
        }
    }
}

private fun posixClass(name: String): String? = when (name) {
    "alpha" -> "\\p{L}\\p{M}"
    "digit" -> "\\p{Nd}"
    "alnum" -> "\\p{L}\\p{M}\\p{Nd}"
    "upper" -> "\\p{Lu}"
    "lower" -> "\\p{Ll}"
    // Oniguruma counts the ASCII symbols $+<=>^`|~ as punctuation too.
    "punct" -> "\\p{P}\\$+<=>\\^`|~"
    "space" -> "\\s"
    "xdigit" -> "0-9A-Fa-f"
    "word" -> "\\w"
    "blank" -> "\\x20\\t"
    "cntrl" -> "\\p{Cc}"
    "graph" -> "\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}"
    "print" -> "\\p{L}\\p{M}\\p{N}\\p{P}\\p{S}\\x20"
    "ascii" -> "\\x00-\\x7F"
    else -> null
}

/** Long and POSIX property names Java does not know, as class contents that Java and ICU both read. */
private fun propertySet(name: String): String? = when (val key = name.lowercase().filter { it != '_' && it != ' ' && it != '-' }) {
    "alphabetic" -> "\\p{L}\\p{M}\\p{Nl}"
    "surrogate" -> "\\p{Cs}"
    "letter" -> "\\p{L}"
    "mark", "combiningmark" -> "\\p{M}"
    "number" -> "\\p{N}"
    "decimalnumber" -> "\\p{Nd}"
    "punctuation" -> "\\p{P}"
    "symbol" -> "\\p{S}"
    "separator" -> "\\p{Z}"
    "other" -> "\\p{C}"
    "control" -> "\\p{Cc}"
    "uppercase", "uppercaseletter" -> "\\p{Lu}"
    "lowercase", "lowercaseletter" -> "\\p{Ll}"
    "whitespace" -> "\\s"
    else -> posixClass(key)
}

/** Writes \p{name} or \P{name}; names Java does not know go through [propertySet], bracketed outside a class. */
private fun appendProperty(sb: StringBuilder, body: String, negatedEscape: Boolean, inClass: Boolean) {
    val negated = negatedEscape != body.startsWith("^")
    val name = body.removePrefix("^")
    val set = propertySet(name)
    when {
        set == null -> sb.append(if (negated) "\\P{" else "\\p{").append(name).append('}')
        negated -> sb.append("[^").append(set).append(']')
        inClass -> sb.append(set)
        else -> sb.append('[').append(set).append(']')
    }
}

/** How far back a lookbehind written with * or + looks once it is bounded for Java and ICU. */
private const val LOOKBEHIND_REACH = 50

/**
 * The Java pattern [p] with every *, + and {n,} inside a lookbehind bounded by [LOOKBEHIND_REACH], or null when it has
 * none. It matches as Oniguruma does wherever the repeated part is no longer than that, which in grammars is a run of
 * spaces or a word.
 */
internal fun boundLookbehinds(p: String): String? {
    val sb = StringBuilder(p.length + 32)
    val groups = ArrayList<Boolean>()
    var inside = 0
    var changed = false
    var i = 0
    fun modifier() { if (i < p.length && (p[i] == '+' || p[i] == '?')) sb.append(p[i++]) }
    while (i < p.length) {
        val c = p[i]
        when {
            c == '\\' && i + 1 < p.length -> {
                val d = p[i + 1]
                val close = if ((d == 'p' || d == 'P' || d == 'x') && i + 2 < p.length && p[i + 2] == '{') p.indexOf('}', i + 3) else -1
                val stop = if (close > 0) close + 1 else if (d == 'Q') p.indexOf("\\E", i + 2).let { if (it < 0) p.length else it + 2 } else i + 2
                sb.append(p, i, stop)
                i = stop
            }
            c == '[' -> {
                // A class, nested classes included, is copied as written.
                var level = 0
                var j = i
                while (j < p.length) {
                    val k = p[j]
                    if (k == '\\') { j += 2; continue }
                    if (k == '[') level++
                    if (k == ']' && j > i + 1 && !(j == i + 2 && p[i + 1] == '^') && --level == 0) { j++; break }
                    j++
                }
                sb.append(p, i, minOf(j, p.length))
                i = j
            }
            c == '(' -> {
                val behind = p.startsWith("(?<=", i) || p.startsWith("(?<!", i)
                groups.add(behind)
                if (behind) inside++
                sb.append(c)
                i++
            }
            c == ')' -> {
                if (groups.isNotEmpty() && groups.removeAt(groups.size - 1)) inside--
                sb.append(c)
                i++
            }
            // ? after anything but ( is a quantifier; a + or ? right after it is its modifier, not a new quantifier.
            inside > 0 && c == '?' && sb.isNotEmpty() && sb[sb.length - 1] != '(' -> {
                sb.append(c)
                i++
                modifier()
            }
            inside > 0 && (c == '*' || c == '+') -> {
                sb.append(if (c == '*') "{0," else "{1,").append(LOOKBEHIND_REACH).append('}')
                changed = true
                i++
                modifier()
            }
            inside > 0 && c == '{' && quantifierEnd(p, i) > 0 -> {
                val q = quantifierEnd(p, i)
                val body = p.substring(i + 1, q - 1)
                if (body.endsWith(",")) {
                    val lo = body.dropLast(1).toIntOrNull() ?: 0
                    sb.append('{').append(lo).append(',').append(lo + LOOKBEHIND_REACH).append('}')
                    changed = true
                } else sb.append(p, i, q)
                i = q
                modifier()
            }
            else -> { sb.append(c); i++ }
        }
    }
    return if (changed) sb.toString() else null
}

/** True when [s] at [i] reads {n}, {n,}, {n,m} or {,m}; returns the index past the }, or -1. */
private fun quantifierEnd(s: String, i: Int): Int {
    var j = i + 1
    var digits = 0
    while (j < s.length && s[j].isDigit()) { j++; digits++ }
    if (j < s.length && s[j] == ',') {
        j++
        while (j < s.length && s[j].isDigit()) { j++; digits++ }
    }
    return if (digits > 0 && j < s.length && s[j] == '}') j + 1 else -1
}

/**
 * Rewrites an Oniguruma pattern for java.util.regex (ICU on Android). Covers what grammars use and the engines read
 * differently: ^ at the very end of the text, \h and \H (horizontal space in Java, a hex digit here), POSIX
 * [:classes:] and long property names, \x{85 2028} lists, a { that is not a quantifier, {3,0}, (?m) (dot-all in
 * Oniguruma), (?#comments), named groups whose names Java refuses, and spaces or # in a class under (?x). With
 * [keepG] or [keepA] false, \G or \A becomes a group that never matches. Anything else is left as written; if Java
 * cannot compile it, only that rule goes.
 */
internal fun translateOniguruma(src: String, keepG: Boolean, keepA: Boolean = true): String {
    val n = src.length
    val sb = StringBuilder(n + 16)
    var depth = 0
    // Under (?x) the spaces and #comments are dropped here rather than left to the engine: ICU misreads some of them.
    var extended = false
    var names: HashMap<String, String>? = null
    // Open groups outside classes; true marks Oniguruma's absent operator (?~X), written as (?:(?!X)[\s\S])*.
    val groups = ArrayList<Boolean>()
    fun groupName(name: String): String {
        val map = names ?: HashMap<String, String>().also { names = it }
        return map.getOrPut(name) { "tm${map.size}g" }
    }
    // After [ or [^, a ] is a literal in Oniguruma; ICU would close the class there.
    fun openClass(at: Int): Int {
        sb.append('[')
        var j = at + 1
        if (j < n && src[j] == '^') { sb.append('^'); j++ }
        if (j < n && src[j] == ']') { sb.append("\\]"); j++ }
        return j
    }
    var i = 0
    while (i < n) {
        val c = src[i]
        if (c == '\\' && i + 1 < n) {
            val d = src[i + 1]
            when {
                d == 'Q' -> {
                    val e = src.indexOf("\\E", i + 2)
                    val stop = if (e < 0) n else e + 2
                    sb.append(src, i, stop)
                    i = stop
                    continue
                }
                (d == 'p' || d == 'P' || d == 'x' || d == 'o') && i + 2 < n && src[i + 2] == '{' -> {
                    val close = src.indexOf('}', i + 3)
                    if (close < 0) { sb.append(src, i, n); i = n; continue }
                    val body = src.substring(i + 3, close)
                    val points = if (d == 'x') body.split(' ', '\t').filter { it.isNotEmpty() } else emptyList()
                    when {
                        // \x{85 2028 2029} lists several characters in Oniguruma.
                        points.size > 1 -> for (cp in points) sb.append("\\x{").append(cp).append('}')
                        d == 'p' || d == 'P' -> appendProperty(sb, body, d == 'P', depth > 0)
                        else -> sb.append(src, i, close + 1)
                    }
                    i = close + 1
                    continue
                }
                // \p without braces is no property in Oniguruma; read it as the letter.
                (d == 'p' || d == 'P') && (i + 2 >= n || src[i + 2] != '{') -> {
                    sb.append(d)
                    i += 2
                    continue
                }
                d == 'k' && i + 2 < n && src[i + 2] == '<' -> {
                    val close = src.indexOf('>', i + 3)
                    if (close < 0) { sb.append(src, i, n); i = n; continue }
                    val name = src.substring(i + 3, close)
                    if (name.isNotEmpty() && name.all { it.isDigit() }) sb.append("(?:\\").append(name).append(')')
                    else sb.append("\\k<").append(groupName(name)).append('>')
                    i = close + 1
                    continue
                }
                d == 'h' -> sb.append(if (depth > 0) "0-9a-fA-F" else "[0-9a-fA-F]")
                d == 'H' -> sb.append("[^0-9a-fA-F]")
                d == 'G' -> sb.append(if (keepG || depth > 0) "\\G" else "(?!)")
                d == 'A' && depth == 0 && !keepA -> sb.append("(?!)")
                d == 'v' -> sb.append("\\x0B")
                d == 'R' && depth > 0 -> sb.append("\\n\\r\\x0B\\f\\x{85}\\x{2028}\\x{2029}")
                d == 'b' && depth > 0 -> sb.append("\\x08")
                d == 'N' && depth == 0 -> sb.append("[^\\n]")
                else -> sb.append(c).append(d)
            }
            i += 2
            continue
        }
        if (depth > 0) {
            when {
                c == '[' && i + 1 < n && src[i + 1] == ':' -> {
                    val close = src.indexOf(":]", i + 2)
                    val negated = close > 0 && src[i + 2] == '^'
                    val mapped = if (close > 0) posixClass(src.substring(if (negated) i + 3 else i + 2, close)) else null
                    if (mapped == null) { sb.append("\\["); i++; continue }
                    if (negated) sb.append("[^").append(mapped).append(']') else sb.append(mapped)
                    i = close + 2
                    continue
                }
                c == '[' -> { depth++; i = openClass(i); continue }
                c == ']' -> depth--
            }
            when (c) {
                // Java and ICU drop spaces and # inside a class under (?x); Oniguruma keeps them.
                ' ' -> sb.append("\\x20")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '#', '{', '}', '$' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
            continue
        }
        when (c) {
            '[' -> { depth = 1; i = openClass(i); continue }
            '{' -> {
                val q = quantifierEnd(src, i)
                if (q < 0) { sb.append("\\{"); i++; continue }
                val body = src.substring(i + 1, q - 1)
                val comma = body.indexOf(',')
                val lo = if (comma < 0) body else body.substring(0, comma)
                val hi = if (comma < 0) "" else body.substring(comma + 1)
                when {
                    lo.isEmpty() -> sb.append("{0,").append(hi).append('}')
                    // Oniguruma reads {n,m} with n > m as the possessive {m,n}+.
                    hi.isNotEmpty() && (lo.toIntOrNull() ?: 0) > (hi.toIntOrNull() ?: Int.MAX_VALUE) ->
                        sb.append('{').append(hi).append(',').append(lo).append("}+")
                    else -> sb.append(src, i, q)
                }
                i = q
                continue
            }
            '}' -> sb.append("\\}")
            // ICU's ^ also matches after the newline that ends the text; Oniguruma's and Java's do not.
            '^' -> sb.append("(?:^(?=[\\s\\S]))")
            '#' -> if (extended) {
                val nl = src.indexOf('\n', i)
                i = if (nl < 0) n else nl
                continue
            } else sb.append(c)
            ' ', '\t', '\n', '\r', '\u000B', '\u000C' -> if (!extended) sb.append(c)
            ')' -> {
                val absent = groups.isNotEmpty() && groups.removeAt(groups.size - 1)
                sb.append(if (absent) ")[\\s\\S])*" else ")")
            }
            '(' -> if (i + 1 < n && src[i + 1] == '?') {
                val e = i + 2
                when {
                    e < n && src[e] == '#' -> {
                        val close = src.indexOf(')', e)
                        i = if (close < 0) n else close + 1
                        continue
                    }
                    e < n && src[e] == '~' -> {
                        groups.add(true)
                        sb.append("(?:(?!")
                        i = e + 1
                        continue
                    }
                    e + 1 < n && ((src[e] == '<' && src[e + 1] != '=' && src[e + 1] != '!') || src[e] == '\'') -> {
                        val closer = if (src[e] == '<') '>' else '\''
                        val close = src.indexOf(closer, e + 1)
                        if (close > e + 1) {
                            groups.add(false)
                            sb.append("(?<").append(groupName(src.substring(e + 1, close))).append('>')
                            i = close + 1
                            continue
                        }
                        groups.add(false)
                        sb.append(c)
                    }
                    else -> {
                        // Inline options such as (?i), (?x) or (?mi-x:...).
                        var j = e
                        while (j < n && (src[j] == 'i' || src[j] == 'm' || src[j] == 'x' || src[j] == '-')) j++
                        if (j > e && j < n && (src[j] == ')' || src[j] == ':')) {
                            val flags = StringBuilder()
                            var off = false
                            for (k in e until j) {
                                val f = src[k]
                                if (f == '-') off = true
                                // A standalone (?x) is handled here: spaces and comments are dropped from now on.
                                if (f == 'x' && src[j] == ')') { extended = !off; continue }
                                flags.append(if (f == 'm') 's' else f)
                            }
                            if (src[j] == ':') groups.add(false)
                            if (src[j] == ':' || flags.any { it != '-' }) sb.append("(?").append(flags).append(src[j])
                            i = j + 1
                            continue
                        }
                        groups.add(false)
                        sb.append(c)
                    }
                }
            } else {
                groups.add(false)
                sb.append(c)
            }
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/**
 * The matchers of one pattern inside one lexer. The last search is remembered: a search from a later position gives
 * the same answer while that match still lies ahead, so a line costs about one search per pattern, not one per step.
 */
private class Re(val rx: TmRegex) {
    /** A matcher per variant of the pattern, with the last search it made. */
    private class Slot(val m: Matcher) {
        var text: String? = null
        var from = -1
        var start = -1
    }

    private val slots = arrayOfNulls<Slot>(4)

    private fun slot(g: Boolean, a: Boolean): Slot? {
        val i = (if (g) 1 else 0) or (if (a) 2 else 0)
        slots[i]?.let { return it }
        val p = rx.pattern(g, a) ?: return null
        return Slot(p.matcher("")).also { slots[i] = it }
    }

    fun find(text: String, from: Int, anchor: Int, firstLine: Boolean): Matcher? {
        val a = firstLine || !rx.hasA
        if (rx.hasG && from == anchor) {
            // region() makes \G mean "here". Transparent bounds let lookbehind see the text before it where the
            // engine keeps \G in place under them; see [TRANSPARENT_REGIONS].
            val sl = slot(true, a) ?: return null
            val m = sl.m
            if (sl.text !== text) {
                m.reset(text)
                m.useTransparentBounds(TRANSPARENT_REGIONS)
                m.useAnchoringBounds(false)
                sl.text = text
                sl.from = -1
            } else if (sl.from == from) return if (sl.start < 0) null else m
            m.region(from, text.length)
            sl.from = from
            sl.start = if (m.find()) m.start() else -1
            return if (sl.start < 0) null else m
        }
        // Without \G, find(from) searches the whole line from there, which is what a region with transparent bounds
        // gives too. Android copies the input to ICU on every region() call; find(from) does not.
        val sl = slot(false, a) ?: return null
        val m = sl.m
        if (sl.text !== text) {
            m.reset(text)
            sl.text = text
            sl.from = -1
        } else if (sl.from in 0..from && (sl.start < 0 || sl.start >= from)) return if (sl.start < 0) null else m
        sl.from = from
        sl.start = if (m.find(from)) m.start() else -1
        return if (sl.start < 0) null else m
    }
}

/**
 * Whether a search from the anchor may use transparent bounds. Java keeps \G at the region's start inside lookarounds
 * then, and lets lookbehind see the text before the region. ICU, under Android's java.util.regex, moves \G to the look
 * bounds inside (?!...) and (?=...), which breaks the common end pattern (?!\G), and its lookbehind stops at the
 * region's start anyway (ICU ticket 6067). One probe tells the two apart.
 */
private val TRANSPARENT_REGIONS: Boolean by lazy {
    try {
        val m = Pattern.compile("(?!\\G)").matcher("ab")
        m.useTransparentBounds(true)
        m.useAnchoringBounds(false)
        m.region(1, 2)
        m.find() && m.start() == 2
    } catch (e: Exception) {
        false
    }
}

/** The rules tried while one rule is innermost, includes already followed, with their begin or match pattern. */
private class Scanner(val rules: Array<TmRule>, val res: Array<Re>)

/** One open rule. Frames are interned, so equal stacks are the same object and the same state number. */
private class Frame(
    val rule: TmRule,
    val parent: Frame?,
    /** The end or while pattern with \1 to \9 filled in from the begin match. */
    val endSrc: String?,
    /** The begin match took the line break, so \G may match at the start of the next line. */
    val eol: Boolean,
    val nameKind: Int,
    val contentKind: Int,
    /** The scope names this frame adds, $n filled in: its name, then its contentName. Injections select on them. */
    val nameScopes: String?,
    val contentScopes: String?,
) {
    val depth: Int = if (parent == null) 0 else parent.depth + 1
    var index = 0
    var endRe: Re? = null
    /** Every scope name from the root to this frame's content, worked out when an injection first asks. */
    var scopes: List<String>? = null
    /** Indexes of the lexer's injections whose selector matches here. */
    var injections: IntArray? = null

    // The kinds count too: a name with $1 gives one rule different colours, and those must not share a frame.
    override fun equals(other: Any?) =
        other is Frame && rule === other.rule && parent === other.parent && eol == other.eol && endSrc == other.endSrc &&
            nameKind == other.nameKind && contentKind == other.contentKind && nameScopes == other.nameScopes &&
            contentScopes == other.contentScopes

    override fun hashCode() =
        ((((rule.id * 31 + (parent?.index ?: -1)) * 31 + (endSrc?.hashCode() ?: 0)) * 31 + nameKind) * 31 + contentKind) * 2 + (if (eol) 1 else 0)
}

private const val MAX_STATES = 50_000
private const val INDEX_BITS = 17
private const val INDEX_MASK = (1 shl INDEX_BITS) - 1
private const val MAX_GENERATION = (1 shl (31 - INDEX_BITS)) - 1
private const val MAX_DEPTH = 500
private const val MAX_PATTERNS = 4_096
/** How deep captures tokenized again may nest inside one another. */
private const val MAX_NESTING = 6
private const val NO_MATCH = -2
private const val END_MATCH = -1
private const val INJECTED = -3
private const val FIRST_LINE = -1
/** How long one line may take; a line normally needs well under a millisecond. */
private const val LINE_BUDGET_NS = 50_000_000L

/**
 * A [Lexer] for one TextMate grammar. [resolver] supplies the grammars that `include` names by scope, such as
 * source.css inside HTML; a scope it cannot supply is left out. The state of a line is a number for its stack of open
 * rules; 0 is the start of a document.
 */
class TmLexer(
    private val grammar: TmGrammar,
    private val resolver: (String) -> TmGrammar?,
    /** Grammars that inject themselves into this one by `injectTo`, for its scope name. */
    private val injectors: (String) -> List<TmGrammar>,
) : Lexer {
    constructor(grammar: TmGrammar, resolver: (String) -> TmGrammar?) : this(grammar, resolver, { emptyList() })

    private val root = grammar.root.nameKind.let { k -> if (k < 0) Token.PLAIN else k }
        .let { k -> Frame(grammar.root, null, null, false, k, k, grammar.scopeName, null) }

    private class Injection(val matcher: ScopeMatcher, val rule: TmRule)

    // The grammar's own injections, then those other grammars bring, L: first and R: last as vscode-textmate sorts.
    private val injections: List<Injection> by lazy {
        val out = ArrayList<Injection>()
        for ((selector, rule) in grammar.injections) for (m in ScopeMatcher.parse(selector)) out.add(Injection(m, rule))
        val guests = try {
            injectors(grammar.scopeName)
        } catch (e: Exception) {
            Log.w(TAG, "injections for ${grammar.scopeName} could not be loaded", e)
            emptyList()
        }
        for (g in guests) {
            val selector = g.injectionSelector ?: continue
            for (m in ScopeMatcher.parse(selector)) out.add(Injection(m, g.root))
        }
        out.sortedBy { it.matcher.priority }
    }
    private val frames = ArrayList<Frame>()
    private val interned = HashMap<Frame, Frame>()
    private var generation = 1
    private val res = HashMap<String, Re>()
    private val scanners = IdentityHashMap<TmRule, Scanner>()
    private val grammars = HashMap<String, TmGrammar?>()
    private val whiles = ArrayList<Frame>()
    private val dynKinds = HashMap<String, Int>()
    private var kinds = IntArray(256)
    private var enterAt = IntArray(32)
    private var anchorAt = IntArray(32)
    private var paint = false
    /** The line being tokenized is a document's first, where \A can match. */
    private var firstLine = false
    /** When the line being tokenized has to stop, and whether it did. */
    private var deadline = 0L
    private var stoppedEarly = false
    private var budgetNs = LINE_BUDGET_NS
    /** How long one line may take; tests that compare every character with vscode-textmate lift it. */
    internal var lineBudgetNs = LINE_BUDGET_NS
    private var lineLen = 0
    private var failed = false

    init {
        frames.add(root)
        interned[root] = root
    }

    /** It has lexed a line: its first patterns are compiled, so a line costs what lines cost, not a startup. */
    @Volatile var warm = false
        private set

    /** A state of its own for a document's first line; every other line at the root is 0. */
    override val initialState: Int get() = FIRST_LINE

    // One lexer serves every editor of its language and keeps tables, so calls take turns.
    @Synchronized
    override fun lex(line: String, state: Int, sink: SpanSink?): Int = lexLine(line, state, sink, lineBudgetNs)

    /** What [lexWithin] found: the state the line ends in, and whether the line was done to its end in time. */
    class Lexed {
        var state = 0
        var complete = true
    }

    /**
     * [lex] with a time budget of its own. A line cut short ends in a state that may be wrong, so [out] says so: the
     * editor then keeps neither that state nor those colours and works the line out again with more time.
     */
    @Synchronized
    fun lexWithin(line: String, state: Int, sink: SpanSink?, budgetNs: Long, out: Lexed) {
        out.state = lexLine(line, state, sink, budgetNs)
        out.complete = !stoppedEarly
    }

    @Synchronized
    private fun lexLine(line: String, state: Int, sink: SpanSink?, budget: Long): Int = try {
        budgetNs = budget
        tokenize(line, state, sink).also { warm = true }
    } catch (e: Exception) {
        fail(e)
        0
    } catch (e: StackOverflowError) {
        fail(e)
        0
    }

    private fun fail(e: Throwable) {
        if (failed) return
        failed = true
        Log.w(TAG, "tokenizing with ${grammar.scopeName} failed; such lines stay plain", e)
    }

    private fun tokenize(line: String, state: Int, sink: SpanSink?): Int {
        // Every stack ever seen keeps its number, because the highlighter may hand any of them back. A grammar that
        // keeps opening new stacks would grow the table without end, so past MAX_STATES it starts over under a new
        // generation: older numbers then read as the root and those lines highlight from a clean start.
        if (frames.size > MAX_STATES) restart()
        if (res.size > MAX_PATTERNS) res.clear()
        firstLine = state == FIRST_LINE
        stoppedEarly = false
        deadline = System.nanoTime() + budgetNs
        var top = frameOf(state)
        // vscode-textmate tokenizes each line with its \n, and grammars rely on it: comments end at "\n" or "$\n?".
        val text = line + "\n"
        lineLen = line.length
        paint = sink != null
        if (paint) {
            if (kinds.size < lineLen) kinds = IntArray(maxOf(lineLen, kinds.size * 2))
            java.util.Arrays.fill(kinds, 0, lineLen, Token.PLAIN)
        }
        reserve(top.depth + 1)
        for (d in 0..top.depth) { enterAt[d] = -1; anchorAt[d] = -1 }
        var pos = 0
        var anchor = if (top.eol) 0 else -1

        // A begin/while rule lasts only while its pattern matches again on each new line; the outermost goes first.
        whiles.clear()
        var f: Frame? = top
        while (f != null) {
            if (f.rule.kind == K_BEGIN_WHILE) whiles.add(f)
            f = f.parent
        }
        for (w in whiles.indices.reversed()) {
            val wf = whiles[w]
            val m = endRe(wf)?.find(text, pos, anchor, firstLine)
            if (m == null) {
                top = wf.parent ?: root
                break
            }
            val e = m.end()
            fill(pos, e, wf.contentKind)
            captures(m, wf.rule.endCaps, m.start(), e, wf, wf.contentKind, text, 0)
            anchor = e
            if (e > pos) pos = e
        }
        whiles.clear()
        return stateOf(scan(text, pos, anchor, top, 0, sink))
    }

    /**
     * Runs the rules over [text] from [start] with [first] innermost, paints what it passes, and returns the innermost
     * frame at the end. [nesting] counts how deep inside captures that are tokenized again this run is.
     */
    private fun scan(text: String, start: Int, startAnchor: Int, first: Frame, nesting: Int, sink: SpanSink?): Frame {
        val len = text.length
        var top = first
        var pos = start
        var anchor = startAnchor
        val limit = 4 * (len - start) + 100
        var steps = 0
        while (++steps <= limit) {
            // Lexing runs while the editor draws. Past its budget a line keeps the scope it is in for the rest, as
            // vscode-textmate does when its time limit runs out.
            if ((steps and 31) == 0 && System.nanoTime() > deadline) { stoppedEarly = true; break }
            val rule = top.rule
            val sc = scanner(rule)
            val endRe = if (rule.kind == K_BEGIN_END) endRe(top) else null
            var best = Int.MAX_VALUE
            var winner = NO_MATCH
            // Earliest start wins; on a tie the end pattern goes first unless the rule asks for it last.
            if (endRe != null && !rule.applyEndPatternLast) {
                endRe.find(text, pos, anchor, firstLine)?.let { best = it.start(); winner = END_MATCH }
            }
            if (best != pos) {
                val rules = sc.rules
                val rs = sc.res
                for (i in rules.indices) {
                    val s = rs[i].find(text, pos, anchor, firstLine)?.start() ?: continue
                    if (s < best) {
                        best = s
                        winner = i
                        if (s == pos) break
                    }
                }
            }
            if (endRe != null && rule.applyEndPatternLast) {
                endRe.find(text, pos, anchor, firstLine)?.let { if (it.start() < best) { best = it.start(); winner = END_MATCH } }
            }
            // An injection wins when it starts earlier, or at the same place with L: priority.
            var injRule: TmRule? = null
            var injRe: Re? = null
            if (injections.isNotEmpty()) {
                var injBest = Int.MAX_VALUE
                var injPriority = 0
                for (k in activeInjections(top)) {
                    val inj = injections[k]
                    val isc = scanner(inj.rule)
                    for (i in isc.rules.indices) {
                        val at = isc.res[i].find(text, pos, anchor, firstLine)?.start() ?: continue
                        if (at < injBest) {
                            injBest = at
                            injRule = isc.rules[i]
                            injRe = isc.res[i]
                            injPriority = inj.matcher.priority
                            if (at == pos) break
                        }
                    }
                    if (injBest == pos) break
                }
                if (injRule != null && (winner == NO_MATCH || injBest < best || (injPriority == -1 && injBest == best))) {
                    winner = INJECTED
                    best = injBest
                }
            }
            if (winner == NO_MATCH) break
            // Search the winner again: another candidate sharing its matcher may have moved it. Usually a cache hit.
            val m = when (winner) {
                END_MATCH -> endRe?.find(text, pos, anchor, firstLine)
                INJECTED -> injRe?.find(text, pos, anchor, firstLine)
                else -> sc.res[winner].find(text, pos, anchor, firstLine)
            } ?: break
            val s = m.start()
            val e = m.end()
            fill(pos, s, top.contentKind)
            if (winner == END_MATCH) {
                fill(s, e, top.nameKind)
                val caps = rule.endCaps
                // The end's captures see the rule with its name as its content, as vscode-textmate has it then.
                val owner = if (caps?.rules == null || top.contentScopes == null) top
                else intern(Frame(top.rule, top.parent, top.endSrc, top.eol, top.nameKind, top.nameKind, top.nameScopes, null))
                captures(m, caps, s, e, owner, top.nameKind, text, nesting)
                val popped = top
                top = popped.parent ?: root
                anchor = anchorAt[popped.depth]
                if (e == pos && enterAt[popped.depth] == pos) {
                    // Opened and closed on the same spot. vscode-textmate takes that for a slip in the grammar: the
                    // rule stays open, with its name as its content, and the rest of the line goes to it.
                    top = if (popped.contentKind == popped.nameKind && popped.contentScopes == null) popped
                    else intern(Frame(popped.rule, popped.parent, popped.endSrc, popped.eol, popped.nameKind, popped.nameKind, popped.nameScopes, null))
                    break
                }
            } else {
                val r = if (winner == INJECTED) injRule!! else sc.rules[winner]
                val nameKind = inherit(r.nameDyn?.let { dynKind(it, m, text) } ?: r.nameKind, top.contentKind)
                fill(s, e, nameKind)
                if (r.kind == K_MATCH) {
                    // Captures tokenized again sit on top of the matched rule, named but without content.
                    val owner = if (r.captures?.rules == null) top
                    else intern(Frame(r, top, null, false, nameKind, nameKind, r.nameDyn?.let { substitute(it, m.groupCount(), m::start, m::end, text) } ?: r.name, null))
                    captures(m, r.captures, s, e, owner, nameKind, text, nesting)
                    if (e == pos) {
                        // An empty match where the line stands moves nothing. vscode-textmate closes the innermost
                        // rule and gives it the rest of the line, and so does this.
                        top = top.parent ?: top
                        break
                    }
                } else {
                    // Before the captures: tokenizing a capture again may reuse this matcher.
                    val endSrc = r.end?.let { if (r.endBackrefs) fillBackrefs(it, m, text) else it }
                    val contentKind = inherit(r.contentDyn?.let { dynKind(it, m, text) } ?: r.contentKind, nameKind)
                    val nameScopes = r.nameDyn?.let { substitute(it, m.groupCount(), m::start, m::end, text) } ?: r.name
                    val contentScopes = r.contentDyn?.let { substitute(it, m.groupCount(), m::start, m::end, text) } ?: r.contentName
                    val owner = if (r.beginCaps?.rules == null) top
                    else intern(Frame(r, top, endSrc, e == len, nameKind, nameKind, nameScopes, null))
                    captures(m, r.beginCaps, s, e, owner, nameKind, text, nesting)
                    val next = if (top.depth + 1 < MAX_DEPTH) intern(Frame(r, top, endSrc, e == len, nameKind, contentKind, nameScopes, contentScopes)) else null
                    // The same rule again with nothing consumed would loop; vscode-textmate stops the line there.
                    if (e == pos && enteredHere(top, r, pos)) break
                    if (next == null) {
                        // Too deep: the begin is treated as text.
                        if (e > pos) { pos = e; continue }
                        break
                    }
                    reserve(next.depth + 1)
                    enterAt[next.depth] = pos
                    anchorAt[next.depth] = anchor
                    top = next
                    anchor = e
                }
            }
            if (e > pos) pos = e
        }
        fill(pos, len, top.contentKind)
        if (nesting == 0 && sink != null) emit(sink)
        return top
    }

    private fun activeInjections(f: Frame): IntArray {
        f.injections?.let { return it }
        val scopes = scopesOf(f)
        val on = injections.indices.filter { injections[it].matcher.matches(scopes) }.toIntArray()
        f.injections = on
        return on
    }

    private fun scopesOf(f: Frame): List<String> {
        f.scopes?.let { return it }
        val out = ArrayList<String>(f.parent?.let { scopesOf(it) } ?: emptyList())
        for (names in arrayOf(f.nameScopes, f.contentScopes)) names?.split(' ')?.forEach { if (it.isNotEmpty()) out.add(it) }
        f.scopes = out
        return out
    }

    private fun enteredHere(top: Frame, rule: TmRule, pos: Int): Boolean {
        var f: Frame? = top
        while (f != null && enterAt[f.depth] == pos) {
            if (f.rule === rule) return true
            f = f.parent
        }
        return false
    }

    private fun inherit(kind: Int, outer: Int) = if (kind < 0) outer else kind

    private fun fill(from: Int, to: Int, kind: Int) {
        if (!paint) return
        val a = maxOf(from, 0)
        val b = minOf(to, lineLen)
        if (a < b) java.util.Arrays.fill(kinds, a, b, kind)
    }

    /**
     * Paints groups in number order, so a group nested in another (always numbered later) wins inside it. A capture
     * with its own patterns has its text tokenized again with them, as vscode-textmate does: on top of [owner], from
     * the rule's own colour [base], over the line cut at the capture's end. Nothing it opens outlives the capture.
     */
    private fun captures(m: Matcher, caps: Caps?, s: Int, e: Int, owner: Frame, base: Int, text: String, nesting: Int) {
        if (!paint || caps == null) return
        val last = minOf(caps.size - 1, m.groupCount())
        if (last < 0) return
        val kinds = caps.kinds
        if (caps.rules == null && caps.dynamic == null) {
            for (g in 0..last) {
                val k = kinds[g]
                if (k < 0) continue
                val gs = m.start(g)
                if (gs >= 0) fill(maxOf(gs, s), minOf(m.end(g), e), k)
            }
            return
        }
        // Read every group first: tokenizing a capture again can reset this matcher.
        val starts = IntArray(last + 1) { m.start(it) }
        val ends = IntArray(last + 1) { m.end(it) }
        // Text a capture already tokenized again is final: vscode-textmate never goes back over tokens it produced.
        var done = s
        for (g in 0..last) {
            val gs = maxOf(starts[g], done)
            if (starts[g] < 0 || gs > e) continue
            val ge = minOf(ends[g], e)
            if (ge <= gs) continue
            val dyn = caps.dynamic?.get(g)
            val k = if (dyn != null) dynKind(dyn, last, { starts[it] }, { ends[it] }, text) else kinds[g]
            val again = caps.rules?.get(g)
            if (again != null && nesting < MAX_NESTING && owner.depth + 2 < MAX_DEPTH) {
                val nameKind = inherit(k, base)
                fill(gs, ge, nameKind)
                val scope = if (dyn != null) substitute(dyn, last, { starts[it] }, { ends[it] }, text) else again.name
                val frame = intern(Frame(again, owner, null, false, nameKind, inherit(again.contentKind, nameKind), scope, again.contentName))
                reserve(frame.depth + 1)
                enterAt[frame.depth] = gs
                anchorAt[frame.depth] = -1
                scan(text.substring(0, ge), gs, -1, frame, nesting + 1, null)
                done = ge
                continue
            }
            if (k >= 0) fill(gs, ge, k)
        }
    }

    private fun dynKind(name: String, m: Matcher, text: String) = dynKind(name, m.groupCount(), m::start, m::end, text)

    /** The kind a name with $n gives for one match; the filled names are remembered, they repeat a lot. */
    private fun dynKind(name: String, count: Int, start: (Int) -> Int, end: (Int) -> Int, text: String): Int {
        val full = substitute(name, count, start, end, text)
        dynKinds[full]?.let { return it }
        if (dynKinds.size > 4_096) dynKinds.clear()
        return kindOfNames(full).also { dynKinds[full] = it }
    }

    private fun emit(sink: SpanSink) {
        var i = 0
        while (i < lineLen) {
            val k = kinds[i]
            var j = i + 1
            while (j < lineLen && kinds[j] == k) j++
            sink.add(i, j, k)
            i = j
        }
    }

    private fun fillBackrefs(src: String, m: Matcher, text: String): String {
        val sb = StringBuilder(src.length + 16)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val d = src[i + 1]
                if (d in '1'..'9') {
                    val g = d - '0'
                    val got = if (g <= m.groupCount() && m.start(g) >= 0) text.substring(m.start(g), m.end(g)) else ""
                    sb.append(Pattern.quote(got))
                } else sb.append(c).append(d)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun re(src: String): Re = res.getOrPut(src) { Re(TmRegex.get(src)) }

    private fun endRe(f: Frame): Re? {
        val src = f.endSrc ?: return null
        return f.endRe ?: re(src).also { f.endRe = it }
    }

    private fun scanner(rule: TmRule): Scanner {
        scanners[rule]?.let { return it }
        val flat = ArrayList<TmRule>()
        collect(rule.patterns, flat, Collections.newSetFromMap(IdentityHashMap()))
        val rules = ArrayList<TmRule>(flat.size)
        val list = ArrayList<Re>(flat.size)
        for (r in flat) {
            val re = re((if (r.kind == K_MATCH) r.match else r.begin) ?: continue)
            if (!re.rx.ok) continue
            // A begin rule whose fixed end does not compile could never close; drop it like a broken match.
            val end = r.end
            if (r.kind != K_MATCH && end != null && !r.endBackrefs && !re(end).rx.ok) continue
            rules.add(r)
            list.add(re)
        }
        return Scanner(rules.toTypedArray(), list.toTypedArray()).also { scanners[rule] = it }
    }

    /** Flattens includes and pattern groups in order; [seen] stops include cycles and repeats. */
    private fun collect(patterns: List<TmRule>, out: ArrayList<TmRule>, seen: MutableSet<TmRule>) {
        for (p in patterns) {
            if (!seen.add(p)) continue
            when (p.kind) {
                K_INCLUDE -> resolve(p)?.let { collect(listOf(it), out, seen) }
                K_GROUP -> collect(p.patterns, out, seen)
                else -> out.add(p)
            }
        }
    }

    private fun resolve(p: TmRule): TmRule? {
        val inc = p.include ?: return null
        return when {
            inc == "\$self" -> p.grammar.root
            inc == "\$base" -> grammar.root
            inc.startsWith("#") -> {
                val name = inc.substring(1)
                var repo = p.repo
                while (repo != null) {
                    repo.rules[name]?.let { return it }
                    repo = repo.outer
                }
                null
            }
            else -> {
                val hash = inc.indexOf('#')
                val g = grammarFor(if (hash < 0) inc else inc.substring(0, hash)) ?: return null
                if (hash < 0) g.root else g.repository[inc.substring(hash + 1)]
            }
        }
    }

    private fun grammarFor(scope: String): TmGrammar? {
        if (scope == grammar.scopeName) return grammar
        if (grammars.containsKey(scope)) return grammars[scope]
        val g = try {
            resolver(scope)
        } catch (e: Exception) {
            Log.w(TAG, "grammar for $scope could not be loaded", e)
            null
        }
        grammars[scope] = g
        return g
    }

    private fun reserve(size: Int) {
        if (enterAt.size >= size) return
        val n = maxOf(size, enterAt.size * 2)
        enterAt = enterAt.copyOf(n)
        anchorAt = anchorAt.copyOf(n)
    }

    private fun intern(f: Frame): Frame {
        interned[f]?.let { return it }
        f.index = frames.size
        frames.add(f)
        interned[f] = f
        return f
    }

    private fun restart() {
        frames.clear()
        interned.clear()
        frames.add(root)
        interned[root] = root
        generation = if (generation >= MAX_GENERATION) 1 else generation + 1
    }

    // A state packs the generation above INDEX_BITS and the frame's index below, so a number from before a restart
    // can never point at a different stack.
    private fun frameOf(state: Int): Frame {
        if (state <= 0) return root
        val index = state and INDEX_MASK
        if (state ushr INDEX_BITS != generation || index >= frames.size) return root
        return frames[index]
    }

    private fun stateOf(f: Frame): Int = if (f === root || f.index > INDEX_MASK) 0 else (generation shl INDEX_BITS) or f.index
}

// Oniguruma's \g<name> calls a group again, which is how C#, C++ and Swift grammars match nested generics and parens.
// Java has no recursion, so each call is replaced by a copy of the group's body, a few levels deep; a call past the
// last level never matches. The copies capture nothing, so group numbers stay as the grammar counts them.

private const val CALL_LEVELS = 3
private const val CALL_LIMIT = 60_000

private class Group(val open: Int, val bodyStart: Int, val number: Int, val name: String?) {
    var close = -1
}

/** Index just past the character class that opens at [i]. */
private fun classEnd(src: String, i: Int): Int {
    val n = src.length
    var j = i + 1
    if (j < n && src[j] == '^') j++
    if (j < n && src[j] == ']') j++
    var depth = 1
    while (j < n && depth > 0) {
        when (src[j]) {
            '\\' -> j += 2
            '[' -> {
                val posix = if (j + 1 < n && src[j + 1] == ':') src.indexOf(":]", j + 2) else -1
                if (posix > 0) j = posix + 2 else { depth++; j++ }
            }
            ']' -> { depth--; j++ }
            else -> j++
        }
    }
    return minOf(j, n)
}

/** The capturing groups of [src] in opening order, and where (?x) starts, after which # begins a comment. */
private fun findGroups(src: String): Pair<List<Group>, Int> {
    val n = src.length
    val groups = ArrayList<Group>()
    val open = ArrayList<Group?>()
    var extendedFrom = Int.MAX_VALUE
    var i = 0
    while (i < n) {
        val c = src[i]
        when {
            c == '\\' -> {
                if (i + 1 < n && src[i + 1] == 'Q') {
                    val e = src.indexOf("\\E", i + 2)
                    i = if (e < 0) n else e + 2
                } else i += 2
            }
            c == '[' -> i = classEnd(src, i)
            c == '#' && i >= extendedFrom -> {
                val nl = src.indexOf('\n', i)
                i = if (nl < 0) n else nl
            }
            c == '(' && i + 1 < n && src[i + 1] == '?' -> {
                val k = i + 2
                if (k < n && src[k] == '#') {
                    val e = src.indexOf(')', k)
                    i = if (e < 0) n else e + 1
                    continue
                }
                if (k + 1 < n && ((src[k] == '<' && src[k + 1] != '=' && src[k + 1] != '!') || src[k] == '\'')) {
                    val e = src.indexOf(if (src[k] == '<') '>' else '\'', k + 1)
                    if (e > k) {
                        val g = Group(i, e + 1, groups.size + 1, src.substring(k + 1, e))
                        groups.add(g)
                        open.add(g)
                        i = e + 1
                        continue
                    }
                }
                var j = k
                var on = false
                var off = false
                while (j < n && (src[j] == 'i' || src[j] == 'm' || src[j] == 'x' || src[j] == '-')) {
                    if (src[j] == '-') off = true else if (src[j] == 'x' && !off) on = true
                    j++
                }
                if (j > k && j < n && src[j] == ')') {
                    if (on) extendedFrom = minOf(extendedFrom, j + 1)
                    i = j + 1
                    continue
                }
                open.add(null)
                i++
            }
            c == '(' -> {
                val g = Group(i, i + 1, groups.size + 1, null)
                groups.add(g)
                open.add(g)
                i++
            }
            c == ')' -> {
                if (open.isNotEmpty()) open.removeAt(open.size - 1)?.close = i
                i++
            }
            else -> i++
        }
    }
    return groups to extendedFrom
}

/** Replaces \g<name>, \g<2>, \g<-1> and \g<0> calls with bounded copies; returns [src] when it cannot. */
internal fun expandCalls(src: String): String {
    val (groups, extendedFrom) = findGroups(src)
    for (levels in CALL_LEVELS downTo 1) {
        val sb = StringBuilder(src.length * 2)
        if (inline(src, 0, src.length, 0, levels, groups, extendedFrom, sb)) return sb.toString()
    }
    return src
}

private fun callTarget(ref: String, at: Int, groups: List<Group>): Group? {
    val number = when {
        ref.isEmpty() -> return null
        ref[0] == '-' || ref[0] == '+' -> {
            val rel = ref.toIntOrNull() ?: return null
            val before = groups.count { it.open < at }
            if (rel < 0) before + rel + 1 else before + rel
        }
        ref.all { it.isDigit() } -> ref.toIntOrNull() ?: return null
        else -> return groups.firstOrNull { it.name == ref }
    }
    return groups.getOrNull(number - 1)
}

/** Copies src[from, to) into [sb] with calls inlined; false when the copy grows past CALL_LIMIT. */
private fun inline(src: String, from: Int, to: Int, level: Int, levels: Int, groups: List<Group>, extendedFrom: Int, sb: StringBuilder): Boolean {
    var i = from
    while (i < to) {
        if (sb.length > CALL_LIMIT) return false
        val c = src[i]
        when {
            c == '\\' && i + 1 < to -> {
                val d = src[i + 1]
                if (d == 'Q') {
                    val e = src.indexOf("\\E", i + 2)
                    val stop = if (e < 0 || e + 2 > to) to else e + 2
                    sb.append(src, i, stop)
                    i = stop
                    continue
                }
                val closer = if (d == 'g' && i + 2 < to) (if (src[i + 2] == '<') '>' else if (src[i + 2] == '\'') '\'' else ' ') else ' '
                val e = if (closer != ' ') src.indexOf(closer, i + 3) else -1
                if (e in 0 until to) {
                    val ref = src.substring(i + 3, e)
                    val whole = ref == "0"
                    val g = if (whole) null else callTarget(ref, i, groups)
                    if (whole || (g != null && g.close > 0)) {
                        // A call past the last level never matches. [^\s\S] says so and, unlike (?!), takes the
                        // quantifier that often follows (ICU refuses a quantified lookahead).
                        if (level >= levels) sb.append("[^\\s\\S]")
                        else {
                            sb.append("(?:")
                            val ok = if (whole) inline(src, 0, src.length, level + 1, levels, groups, extendedFrom, sb)
                            else inline(src, g!!.bodyStart, g.close, level + 1, levels, groups, extendedFrom, sb)
                            if (!ok) return false
                            sb.append(')')
                        }
                        i = e + 1
                        continue
                    }
                }
                sb.append(c).append(d)
                i += 2
            }
            c == '[' -> {
                val e = minOf(classEnd(src, i), to)
                sb.append(src, i, e)
                i = e
            }
            c == '#' && i >= extendedFrom -> {
                val nl = src.indexOf('\n', i)
                val stop = if (nl < 0 || nl > to) to else nl
                sb.append(src, i, stop)
                i = stop
            }
            c == '(' && level > 0 && i + 1 < to && src[i + 1] != '?' -> {
                sb.append("(?:")
                i++
            }
            c == '(' && level > 0 && i + 2 < to && src[i + 1] == '?' &&
                ((src[i + 2] == '<' && i + 3 < to && src[i + 3] != '=' && src[i + 3] != '!') || src[i + 2] == '\'') -> {
                val e = src.indexOf(if (src[i + 2] == '<') '>' else '\'', i + 3)
                if (e < 0 || e >= to) { sb.append(c); i++; continue }
                sb.append("(?:")
                i = e + 1
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return true
}
