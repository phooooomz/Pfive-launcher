// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

/** Kinds of highlighted text; [TeditTheme.syntax] holds a colour for each. */
object Token {
    const val PLAIN = 0
    const val KEYWORD = 1
    const val TYPE = 2
    const val STRING = 3
    const val NUMBER = 4
    const val COMMENT = 5
    const val FUNCTION = 6
    const val OPERATOR = 7
    const val CONSTANT = 8
    const val TAG = 9
    const val ATTRIBUTE = 10
    const val ANNOTATION = 11
    const val PUNCT = 12
    const val VARIABLE = 13
    const val HEADING = 14
    const val INSERTED = 15
    const val DELETED = 16
    const val INVALID = 17
    /** Flow keywords (if, return, import), which VS Code-style themes colour apart from declarations. */
    const val CONTROL = 18
    const val COUNT = 19
}

/** Collects (start, end, kind) triples for one line; neighbouring spans of one kind are joined. */
class SpanSink {
    var data = IntArray(48)
    var size = 0

    fun reset() { size = 0 }

    fun add(start: Int, end: Int, kind: Int) {
        if (end <= start || kind == Token.PLAIN) return
        if (size >= 3 && data[size - 1] == kind && data[size - 2] == start) {
            data[size - 2] = end
            return
        }
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = start
        data[size++] = end
        data[size++] = kind
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/**
 * Highlights one line given the state the previous line ended in, and returns the state this line ends in.
 * With a null sink only the state is wanted, which is what lines above the visible part need.
 */
interface Lexer {
    fun lex(line: String, state: Int, sink: SpanSink?): Int

    /** The state the first line of a document starts in. 0 is the root state for every lexer. */
    val initialState: Int get() = 0
}

class Language(
    val id: String,
    val name: String,
    /** The built-in extension that brings this language; see [Extensions]. */
    val pack: String,
    val extensions: Set<String>,
    val fileNames: Set<String> = emptySet(),
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val brackets: String = "()[]{}",
    /** Pairs typed together: opening and closing character next to each other. */
    val autoClose: String = "()[]{}\"\"''",
    val lexer: Lexer,
)

private val WORD_START = { c: Char -> c.isLetter() || c == '_' }
private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

/** Options of the lexer shared by the C family and most scripting languages. */
class CLikeSpec(
    val keywords: Set<String>,
    val types: Set<String> = emptySet(),
    val constants: Set<String> = setOf("true", "false", "null"),
    val lineComments: List<String> = listOf("//"),
    val block: Pair<String, String>? = "/*" to "*/",
    val nestedBlock: Boolean = false,
    val quotes: String = "\"'",
    val tripleQuotes: Boolean = false,
    /** ` strings that may span lines (JavaScript templates, Go raw strings). */
    val multilineBacktick: Boolean = false,
    val annotations: Boolean = false,
    val preprocessor: Boolean = false,
    /** $name and ${name} are variables (shell, PHP). */
    val dollarVariables: Boolean = false,
    val caseInsensitive: Boolean = false,
    /** Words starting with a capital are types (Kotlin, Java, C#, Swift, Dart, TypeScript). */
    val capitalTypes: Boolean = false,
    /** Rust: 'a is a lifetime, not the start of a character. */
    val lifetimes: Boolean = false,
    /** Lua: --[[ ... ]] comments. */
    val luaBlocks: Boolean = false,
    /** Characters that may start an identifier besides letters and the underscore. */
    val extraWordStart: String = "",
)

class CLikeLexer(private val spec: CLikeSpec) : Lexer {
    companion object {
        /** Keywords that steer the flow of a program; the rest of [CLikeSpec.keywords] declare things. */
        private val CONTROL_WORDS = setOf("if", "else", "elif", "for", "foreach", "while", "do", "return", "break", "continue",
            "switch", "case", "default", "try", "catch", "finally", "throw", "import", "export", "from", "package", "when",
            "yield", "await", "goto", "then", "fi", "done", "esac", "until", "match", "loop", "use", "include", "require",
            "require_once", "include_once", "end", "unless", "elsif", "repeat", "with", "pass", "raise", "except", "using")
        const val S_NORMAL = 0
        const val S_BLOCK = 1
        const val S_TRIPLE_D = 2
        const val S_TRIPLE_S = 3
        const val S_BACKTICK = 4
        const val S_LUA_BLOCK = 5
        private const val OPS = "+-*/%=&|<>!~^?:"
        private const val PUNCT = "()[]{},;."
    }

    private fun word(s: String, start: Int, end: Int): String {
        val w = s.substring(start, end)
        return if (spec.caseInsensitive) w.lowercase() else w
    }

    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        var i = 0
        val n = line.length
        var st = state and 0xFF
        var depth = state ushr 8
        // Finish what the previous line left open.
        when (st) {
            S_BLOCK -> {
                val r = closeBlock(line, 0, depth)
                if (r < 0) { sink?.add(0, n, Token.COMMENT); return state }
                sink?.add(0, r, Token.COMMENT); i = r; st = S_NORMAL; depth = 0
            }
            S_LUA_BLOCK -> {
                val r = line.indexOf("]]")
                if (r < 0) { sink?.add(0, n, Token.COMMENT); return state }
                sink?.add(0, r + 2, Token.COMMENT); i = r + 2; st = S_NORMAL
            }
            S_TRIPLE_D, S_TRIPLE_S -> {
                val q = if (st == S_TRIPLE_D) "\"\"\"" else "'''"
                val r = line.indexOf(q)
                if (r < 0) { sink?.add(0, n, Token.STRING); return state }
                sink?.add(0, r + 3, Token.STRING); i = r + 3; st = S_NORMAL
            }
            S_BACKTICK -> {
                val r = closeQuote(line, 0, '`')
                if (r < 0) { sink?.add(0, n, Token.STRING); return state }
                sink?.add(0, r, Token.STRING); i = r; st = S_NORMAL
            }
        }
        val firstNonSpace = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) n else it }
        while (i < n) {
            val c = line[i]
            if (c == ' ' || c == '\t') { i++; continue }
            // Comments
            if (spec.luaBlocks && line.startsWith("--[[", i)) {
                val r = line.indexOf("]]", i + 4)
                if (r < 0) { sink?.add(i, n, Token.COMMENT); return S_LUA_BLOCK }
                sink?.add(i, r + 2, Token.COMMENT); i = r + 2; continue
            }
            if (spec.lineComments.any { line.startsWith(it, i) } && !(c == '#' && spec.dollarVariables && i > 0 && line[i - 1] == '$')) {
                sink?.add(i, n, Token.COMMENT); return S_NORMAL
            }
            val block = spec.block
            if (block != null && line.startsWith(block.first, i)) {
                val r = closeBlock(line, i + block.first.length, 1)
                if (r < 0) {
                    sink?.add(i, n, Token.COMMENT)
                    return S_BLOCK or (blockDepthAtEnd(line, i + block.first.length, 1) shl 8)
                }
                sink?.add(i, r, Token.COMMENT); i = r; continue
            }
            if (spec.preprocessor && c == '#' && i == firstNonSpace) {
                var j = i + 1
                while (j < n && line[j] == ' ') j++
                while (j < n && isWordChar(line[j])) j++
                sink?.add(i, j, Token.KEYWORD)
                i = j
                // #include <file>
                while (i < n && line[i] == ' ') i++
                if (i < n && line[i] == '<') {
                    val r = line.indexOf('>', i)
                    if (r > 0) { sink?.add(i, r + 1, Token.STRING); i = r + 1 }
                }
                continue
            }
            // Strings
            if (spec.tripleQuotes && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                val q = line.substring(i, i + 3)
                val r = line.indexOf(q, i + 3)
                if (r < 0) { sink?.add(i, n, Token.STRING); return if (q[0] == '"') S_TRIPLE_D else S_TRIPLE_S }
                sink?.add(i, r + 3, Token.STRING); i = r + 3; continue
            }
            if (c == '`' && spec.multilineBacktick) {
                val r = closeQuote(line, i + 1, '`')
                if (r < 0) { sink?.add(i, n, Token.STRING); return S_BACKTICK }
                sink?.add(i, r, Token.STRING); i = r; continue
            }
            if (c == '\'' && spec.lifetimes) {
                // 'a' is a char, 'a (no closing quote right after) is a lifetime.
                if (i + 2 < n && line[i + 2] == '\'' || (i + 1 < n && line[i + 1] == '\\')) {
                    val r = closeQuote(line, i + 1, '\'')
                    val end = if (r < 0) n else r
                    sink?.add(i, end, Token.STRING); i = end; continue
                }
                var j = i + 1
                while (j < n && isWordChar(line[j])) j++
                sink?.add(i, j, Token.ANNOTATION); i = maxOf(j, i + 1); continue
            }
            if (c in spec.quotes) {
                val r = closeQuote(line, i + 1, c)
                val end = if (r < 0) n else r
                if (sink != null && spec.dollarVariables && c == '"') stringWithVariables(line, i, end, sink)
                else sink?.add(i, end, Token.STRING)
                i = end; continue
            }
            // Numbers
            if (c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit() && (i == 0 || !isWordChar(line[i - 1])))) {
                val end = numberEnd(line, i)
                sink?.add(i, end, Token.NUMBER); i = end; continue
            }
            if (spec.dollarVariables && c == '$' && i + 1 < n) {
                var j = i + 1
                if (line[j] == '{') {
                    val r = line.indexOf('}', j)
                    j = if (r < 0) n else r + 1
                } else if (line[j] == '(' ) {
                    sink?.add(i, i + 1, Token.OPERATOR); i++; continue
                } else if (isWordChar(line[j]) || line[j] in "?#@*!$") {
                    j++
                    while (j < n && isWordChar(line[j])) j++
                }
                sink?.add(i, j, Token.VARIABLE); i = maxOf(j, i + 1); continue
            }
            if (spec.annotations && c == '@' && i + 1 < n && WORD_START(line[i + 1])) {
                var j = i + 1
                while (j < n && (isWordChar(line[j]) || line[j] == '.')) j++
                sink?.add(i, j, Token.ANNOTATION); i = j; continue
            }
            if (WORD_START(c) || c in spec.extraWordStart) {
                var j = i + 1
                while (j < n && (isWordChar(line[j]) || line[j] in spec.extraWordStart)) j++
                if (sink != null) {
                    val w = word(line, i, j)
                    val kind = when {
                        w in spec.keywords -> if (w in CONTROL_WORDS) Token.CONTROL else Token.KEYWORD
                        w in spec.constants -> Token.CONSTANT
                        w in spec.types -> Token.TYPE
                        nextNonSpace(line, j) == '(' -> Token.FUNCTION
                        spec.capitalTypes && c.isUpperCase() -> if (j - i > 1 && line.substring(i, j).all { it.isUpperCase() || it.isDigit() || it == '_' }) Token.CONSTANT else Token.TYPE
                        j - i > 1 && line.substring(i, j).all { it.isUpperCase() || it.isDigit() || it == '_' } -> Token.CONSTANT
                        // Plain names read as variables, as VS Code colours them.
                        else -> Token.VARIABLE
                    }
                    sink.add(i, j, kind)
                }
                i = j; continue
            }
            if (c in OPS) { sink?.add(i, i + 1, Token.OPERATOR); i++; continue }
            if (c in PUNCT) { sink?.add(i, i + 1, Token.PUNCT); i++; continue }
            i++
        }
        return S_NORMAL
    }

    private fun stringWithVariables(line: String, start: Int, end: Int, sink: SpanSink) {
        var i = start
        var from = start
        while (i < end) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == '$' && i + 1 < end && (isWordChar(line[i + 1]) || line[i + 1] == '{')) {
                sink.add(from, i, Token.STRING)
                var j = i + 1
                if (line[j] == '{') { val r = line.indexOf('}', j); j = if (r < 0 || r >= end) end else r + 1 }
                else while (j < end && isWordChar(line[j])) j++
                sink.add(i, j, Token.VARIABLE)
                from = j; i = j; continue
            }
            i++
        }
        sink.add(from, end, Token.STRING)
    }

    /** Index just past the closing [q], skipping escapes; -1 when the line ends first. */
    private fun closeQuote(s: String, from: Int, q: Char): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == q) return i + 1
            i++
        }
        return -1
    }

    private fun closeBlock(s: String, from: Int, depth0: Int): Int {
        val b = spec.block ?: return -1
        var depth = depth0
        var i = from
        while (i < s.length) {
            if (spec.nestedBlock && s.startsWith(b.first, i)) { depth++; i += b.first.length; continue }
            if (s.startsWith(b.second, i)) {
                depth--
                i += b.second.length
                if (depth <= 0 || !spec.nestedBlock) return i
                continue
            }
            i++
        }
        return -1
    }

    private fun blockDepthAtEnd(s: String, from: Int, depth0: Int): Int {
        if (!spec.nestedBlock) return 0
        val b = spec.block ?: return 0
        var depth = depth0
        var i = from
        while (i < s.length) {
            if (s.startsWith(b.first, i)) { depth++; i += b.first.length; continue }
            if (s.startsWith(b.second, i)) { depth--; i += b.second.length; continue }
            i++
        }
        return depth.coerceIn(1, 255)
    }
}

private fun nextNonSpace(s: String, from: Int): Char {
    var i = from
    while (i < s.length && s[i] == ' ') i++
    return if (i < s.length) s[i] else '\u0000'
}

private fun numberEnd(s: String, start: Int): Int {
    var i = start
    val n = s.length
    if (s[i] == '0' && i + 1 < n && (s[i + 1] == 'x' || s[i + 1] == 'X' || s[i + 1] == 'b' || s[i + 1] == 'B' || s[i + 1] == 'o')) {
        i += 2
        while (i < n && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return i
    }
    while (i < n && (s[i].isDigit() || s[i] == '_')) i++
    if (i < n && s[i] == '.' && i + 1 < n && s[i + 1].isDigit()) {
        i++
        while (i < n && (s[i].isDigit() || s[i] == '_')) i++
    }
    if (i < n && (s[i] == 'e' || s[i] == 'E')) {
        var j = i + 1
        if (j < n && (s[j] == '+' || s[j] == '-')) j++
        if (j < n && s[j].isDigit()) {
            i = j
            while (i < n && s[i].isDigit()) i++
        }
    }
    // Suffixes: 10L, 1.5f, 3u, 2.0e3d
    while (i < n && s[i].isLetter() && i - start < 40) i++
    return i
}

/** XML, HTML and SVG. */
object MarkupLexer : Lexer {
    private const val S_TEXT = 0
    private const val S_COMMENT = 10
    private const val S_TAG = 11
    private const val S_CDATA = 12
    private const val S_ATTR_D = 13
    private const val S_ATTR_S = 14

    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        var i = 0
        val n = line.length
        var st = state
        while (i <= n) {
            when (st) {
                S_COMMENT -> {
                    val r = line.indexOf("-->", i)
                    if (r < 0) { sink?.add(i, n, Token.COMMENT); return S_COMMENT }
                    sink?.add(i, r + 3, Token.COMMENT); i = r + 3; st = S_TEXT
                }
                S_CDATA -> {
                    val r = line.indexOf("]]>", i)
                    if (r < 0) { sink?.add(i, n, Token.STRING); return S_CDATA }
                    sink?.add(i, r + 3, Token.STRING); i = r + 3; st = S_TEXT
                }
                S_ATTR_D, S_ATTR_S -> {
                    val q = if (st == S_ATTR_D) '"' else '\''
                    val r = line.indexOf(q, i)
                    if (r < 0) { sink?.add(i, n, Token.STRING); return st }
                    sink?.add(i, r + 1, Token.STRING); i = r + 1; st = S_TAG
                }
                S_TAG -> {
                    if (i >= n) return S_TAG
                    val c = line[i]
                    when {
                        c.isWhitespace() -> i++
                        c == '>' -> { sink?.add(i, i + 1, Token.PUNCT); i++; st = S_TEXT }
                        (c == '/' || c == '?') && i + 1 < n && line[i + 1] == '>' -> { sink?.add(i, i + 2, Token.PUNCT); i += 2; st = S_TEXT }
                        c == '=' -> { sink?.add(i, i + 1, Token.OPERATOR); i++ }
                        c == '"' || c == '\'' -> {
                            val r = line.indexOf(c, i + 1)
                            if (r < 0) { sink?.add(i, n, Token.STRING); return if (c == '"') S_ATTR_D else S_ATTR_S }
                            sink?.add(i, r + 1, Token.STRING); i = r + 1
                        }
                        else -> {
                            var j = i
                            while (j < n && !line[j].isWhitespace() && line[j] != '=' && line[j] != '>' && line[j] != '/' && line[j] != '"') j++
                            if (j == i) j = i + 1
                            sink?.add(i, j, Token.ATTRIBUTE); i = j
                        }
                    }
                }
                else -> {
                    if (i >= n) return S_TEXT
                    val lt = line.indexOf('<', i)
                    val amp = line.indexOf('&', i)
                    if (amp in i until (if (lt < 0) n else lt)) {
                        val semi = line.indexOf(';', amp)
                        if (semi in amp + 2..amp + 10) sink?.add(amp, semi + 1, Token.CONSTANT)
                        i = if (semi in amp + 2..amp + 10) semi + 1 else amp + 1
                        continue
                    }
                    if (lt < 0) return S_TEXT
                    i = lt
                    when {
                        line.startsWith("<!--", i) -> { st = S_COMMENT; sink?.add(i, i + 4, Token.COMMENT); i += 4 }
                        line.startsWith("<![CDATA[", i) -> { st = S_CDATA; sink?.add(i, i + 9, Token.KEYWORD); i += 9 }
                        i + 1 < n && (line[i + 1].isLetter() || line[i + 1] in "/!?") -> {
                            var j = i + 1
                            if (line[j] in "/!?") j++
                            sink?.add(i, j, Token.PUNCT)
                            val nameStart = j
                            while (j < n && (line[j].isLetterOrDigit() || line[j] in ":-_.")) j++
                            sink?.add(nameStart, j, Token.TAG)
                            i = j; st = S_TAG
                        }
                        else -> i++
                    }
                }
            }
        }
        return st
    }
}

object CssLexer : Lexer {
    private const val S_COMMENT = 1
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        var i = 0
        val n = line.length
        var depth = state ushr 4
        if (state and 0xF == S_COMMENT) {
            val r = line.indexOf("*/")
            if (r < 0) { sink?.add(0, n, Token.COMMENT); return state }
            sink?.add(0, r + 2, Token.COMMENT); i = r + 2
        }
        var afterColon = false
        while (i < n) {
            val c = line[i]
            when {
                c.isWhitespace() -> i++
                line.startsWith("/*", i) -> {
                    val r = line.indexOf("*/", i + 2)
                    if (r < 0) { sink?.add(i, n, Token.COMMENT); return (depth shl 4) or S_COMMENT }
                    sink?.add(i, r + 2, Token.COMMENT); i = r + 2
                }
                line.startsWith("//", i) && depth >= 0 && (i == 0 || line[i - 1] != ':') -> { sink?.add(i, n, Token.COMMENT); i = n }
                c == '{' -> { depth++; afterColon = false; sink?.add(i, i + 1, Token.PUNCT); i++ }
                c == '}' -> { depth = maxOf(0, depth - 1); afterColon = false; sink?.add(i, i + 1, Token.PUNCT); i++ }
                c == ';' -> { afterColon = false; sink?.add(i, i + 1, Token.PUNCT); i++ }
                c == ':' && depth > 0 && !afterColon -> { afterColon = true; sink?.add(i, i + 1, Token.PUNCT); i++ }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && line[j] != c) { if (line[j] == '\\') j++; j++ }
                    val end = minOf(n, j + 1)
                    sink?.add(i, end, Token.STRING); i = end
                }
                c == '@' -> { var j = i + 1; while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++; sink?.add(i, j, Token.KEYWORD); i = j }
                c == '$' -> { var j = i + 1; while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++; sink?.add(i, j, Token.VARIABLE); i = j }
                c == '!' -> { var j = i + 1; while (j < n && line[j].isLetter()) j++; sink?.add(i, j, Token.KEYWORD); i = j }
                c == '#' && (depth == 0 && !afterColon) -> { var j = i + 1; while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++; sink?.add(i, j, Token.ATTRIBUTE); i = j }
                c == '#' -> { var j = i + 1; while (j < n && line[j].isLetterOrDigit()) j++; sink?.add(i, j, Token.NUMBER); i = j }
                c.isDigit() || (c == '.' && i + 1 < n && line[i + 1].isDigit()) || (c == '-' && i + 1 < n && line[i + 1].isDigit() && afterColon) -> {
                    var j = i + 1
                    while (j < n && (line[j].isDigit() || line[j] == '.')) j++
                    while (j < n && (line[j].isLetter() || line[j] == '%')) j++
                    sink?.add(i, j, Token.NUMBER); i = j
                }
                c == '.' && depth == 0 -> { var j = i + 1; while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++; sink?.add(i, j, Token.TYPE); i = j }
                c == ':' -> { var j = i + 1; if (j < n && line[j] == ':') j++; while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++; sink?.add(i, j, Token.KEYWORD); i = j }
                c.isLetter() || c == '-' || c == '_' -> {
                    var j = i
                    while (j < n && (isWordChar(line[j]) || line[j] == '-')) j++
                    val kind = when {
                        nextNonSpace(line, j) == '(' -> Token.FUNCTION
                        depth == 0 -> Token.TAG
                        afterColon -> Token.CONSTANT
                        else -> Token.ATTRIBUTE
                    }
                    sink?.add(i, j, kind); i = maxOf(j, i + 1)
                }
                else -> { if (c in ",>+~()[]=*") sink?.add(i, i + 1, Token.PUNCT); i++ }
            }
        }
        return (minOf(depth, 0xFFFF) shl 4)
    }
}

object JsonLexer : Lexer {
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        var i = 0
        val n = line.length
        if (state == 1) {
            val r = line.indexOf("*/")
            if (r < 0) { sink?.add(0, n, Token.COMMENT); return 1 }
            sink?.add(0, r + 2, Token.COMMENT); i = r + 2
        }
        while (i < n) {
            val c = line[i]
            when {
                c == '"' -> {
                    var j = i + 1
                    while (j < n && line[j] != '"') { if (line[j] == '\\') j++; j++ }
                    val end = minOf(n, j + 1)
                    sink?.add(i, end, if (nextNonSpace(line, end) == ':') Token.ATTRIBUTE else Token.STRING)
                    i = end
                }
                line.startsWith("//", i) -> { sink?.add(i, n, Token.COMMENT); return 0 }
                line.startsWith("/*", i) -> {
                    val r = line.indexOf("*/", i + 2)
                    if (r < 0) { sink?.add(i, n, Token.COMMENT); return 1 }
                    sink?.add(i, r + 2, Token.COMMENT); i = r + 2
                }
                c == '-' || c.isDigit() -> { val e = numberEnd(line, if (c == '-' && i + 1 < n) i + 1 else i); sink?.add(i, e, Token.NUMBER); i = maxOf(e, i + 1) }
                c.isLetter() -> {
                    var j = i
                    while (j < n && line[j].isLetter()) j++
                    val w = line.substring(i, j)
                    sink?.add(i, j, if (w == "true" || w == "false" || w == "null") Token.CONSTANT else Token.INVALID)
                    i = j
                }
                c in "{}[],:" -> { sink?.add(i, i + 1, Token.PUNCT); i++ }
                else -> i++
            }
        }
        return 0
    }
}

object YamlLexer : Lexer {
    private val CONST = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        val n = line.length
        var i = 0
        while (i < n && line[i] == ' ') i++
        if (line.startsWith("---", i) || line.startsWith("...", i)) { sink?.add(i, n, Token.KEYWORD); return 0 }
        if (i < n && line[i] == '-' && (i + 1 == n || line[i + 1] == ' ')) { sink?.add(i, i + 1, Token.PUNCT); i += 1; while (i < n && line[i] == ' ') i++ }
        // key:
        var keyEnd = -1
        run {
            var j = i
            if (j < n && (line[j] == '"' || line[j] == '\'')) {
                val r = line.indexOf(line[j], j + 1)
                if (r > 0 && r + 1 < n && line[r + 1] == ':') keyEnd = r + 1
                return@run
            }
            while (j < n && line[j] != ':' && line[j] != '#') j++
            if (j < n && line[j] == ':' && (j + 1 == n || line[j + 1] == ' ') && j > i) keyEnd = j
        }
        if (keyEnd > 0 && sink != null) {
            sink.add(i, keyEnd, Token.ATTRIBUTE)
            sink.add(keyEnd, keyEnd + 1, Token.PUNCT)
        }
        if (keyEnd > 0) i = keyEnd + 1
        while (i < n) {
            val c = line[i]
            when {
                c == '#' && (i == 0 || line[i - 1] == ' ') -> { sink?.add(i, n, Token.COMMENT); return 0 }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && line[j] != c) { if (c == '"' && line[j] == '\\') j++; j++ }
                    val end = minOf(n, j + 1)
                    sink?.add(i, end, Token.STRING); i = end
                }
                c == '&' || c == '*' -> { var j = i + 1; while (j < n && !line[j].isWhitespace()) j++; sink?.add(i, j, Token.VARIABLE); i = j }
                c == '!' -> { var j = i + 1; while (j < n && !line[j].isWhitespace()) j++; sink?.add(i, j, Token.ANNOTATION); i = j }
                c == '|' || c == '>' -> { sink?.add(i, i + 1, Token.OPERATOR); i++ }
                c == ' ' -> i++
                else -> {
                    var j = i
                    while (j < n && !(line[j] == ' ' && j + 1 < n && line[j + 1] == '#')) j++
                    val w = line.substring(i, j).trim()
                    if (sink != null) sink.add(i, i + w.length, when {
                        w.lowercase() in CONST -> Token.CONSTANT
                        w.toDoubleOrNull() != null || w.startsWith("0x") -> Token.NUMBER
                        else -> Token.STRING
                    })
                    i = j
                }
            }
        }
        return 0
    }
}

object MarkdownLexer : Lexer {
    private const val S_FENCE = 20
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        val n = line.length
        val t = line.trimStart()
        val lead = n - t.length
        if (t.startsWith("```") || t.startsWith("~~~")) {
            sink?.add(lead, n, Token.KEYWORD)
            return if (state == S_FENCE) 0 else S_FENCE
        }
        if (state == S_FENCE) { sink?.add(0, n, Token.STRING); return S_FENCE }
        if (sink == null) return 0
        if (t.startsWith("#")) { sink.add(lead, n, Token.HEADING); return 0 }
        if (t.startsWith(">")) { sink.add(lead, n, Token.COMMENT); return 0 }
        var i = lead
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ")) { sink.add(i, i + 1, Token.KEYWORD); i += 2 }
        else {
            var j = i
            while (j < n && line[j].isDigit()) j++
            if (j > i && j < n && (line[j] == '.' || line[j] == ')')) { sink.add(i, j + 1, Token.KEYWORD); i = j + 1 }
        }
        if (t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$"))) { sink.add(lead, n, Token.PUNCT); return 0 }
        while (i < n) {
            val c = line[i]
            when {
                c == '`' -> { val r = line.indexOf('`', i + 1); val e = if (r < 0) n else r + 1; sink.add(i, e, Token.STRING); i = e }
                (c == '*' || c == '_') && i + 1 < n && line[i + 1] == c -> {
                    val r = line.indexOf("$c$c", i + 2)
                    if (r < 0) { i += 2 } else { sink.add(i, r + 2, Token.TYPE); i = r + 2 }
                }
                (c == '*' || c == '_') && i + 1 < n && !line[i + 1].isWhitespace() -> {
                    val r = line.indexOf(c, i + 1)
                    if (r < 0) i++ else { sink.add(i, r + 1, Token.KEYWORD); i = r + 1 }
                }
                c == '!' && i + 1 < n && line[i + 1] == '[' -> i++
                c == '[' -> {
                    val close = line.indexOf(']', i + 1)
                    if (close > 0 && close + 1 < n && line[close + 1] == '(') {
                        val pe = line.indexOf(')', close + 2)
                        sink.add(i, close + 1, Token.FUNCTION)
                        if (pe > 0) { sink.add(close + 1, pe + 1, Token.STRING); i = pe + 1 } else i = close + 1
                    } else i++
                }
                c == '<' && (line.startsWith("<http", i) || line.startsWith("<mailto", i)) -> {
                    val r = line.indexOf('>', i); val e = if (r < 0) n else r + 1; sink.add(i, e, Token.STRING); i = e
                }
                else -> i++
            }
        }
        return 0
    }
}

object IniLexer : Lexer {
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        if (sink == null) return 0
        val n = line.length
        val t = line.trimStart()
        val lead = n - t.length
        if (t.startsWith("#") || t.startsWith(";") || t.startsWith("!")) { sink.add(lead, n, Token.COMMENT); return 0 }
        if (t.startsWith("[")) { sink.add(lead, n, Token.TYPE); return 0 }
        val eq = line.indexOfFirst { it == '=' || it == ':' }
        if (eq < 0) return 0
        sink.add(lead, eq, Token.ATTRIBUTE)
        sink.add(eq, eq + 1, Token.OPERATOR)
        var i = eq + 1
        while (i < n && line[i] == ' ') i++
        val v = line.substring(i).trim()
        val comment = line.indexOf(" #", i).takeIf { it > 0 } ?: -1
        val vEnd = if (comment > 0) comment else n
        sink.add(i, vEnd, when {
            v.startsWith("\"") || v.startsWith("'") -> Token.STRING
            v == "true" || v == "false" -> Token.CONSTANT
            v.toDoubleOrNull() != null -> Token.NUMBER
            else -> Token.STRING
        })
        if (comment > 0) sink.add(comment, n, Token.COMMENT)
        return 0
    }
}

object DiffLexer : Lexer {
    override fun lex(line: String, state: Int, sink: SpanSink?): Int {
        if (sink == null) return 0
        val n = line.length
        when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") -> sink.add(0, n, Token.HEADING)
            line.startsWith("@@") -> sink.add(0, n, Token.KEYWORD)
            line.startsWith("+") -> sink.add(0, n, Token.INSERTED)
            line.startsWith("-") -> sink.add(0, n, Token.DELETED)
        }
        return 0
    }
}

object PlainLexer : Lexer {
    override fun lex(line: String, state: Int, sink: SpanSink?): Int = 0
}

/** Every language Tedit knows, grouped into the built-in extensions the Extensions panel lists. */
object Languages {
    private fun set(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val kotlinKw = set("package import class interface fun val var if else when for while do return break continue object companion data sealed enum open override private public protected internal abstract final lateinit by in is as typealias this super throw try catch finally inline reified suspend operator infix crossinline noinline vararg annotation const constructor init where out tailrec external value expect actual")
    private val javaKw = set("abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record yield sealed permits")
    private val javaTypes = set("boolean byte char double float int long short void String Object Integer Long Boolean")
    private val cKw = set("auto break case const continue default do else enum extern for goto if inline register restrict return sizeof static struct switch typedef union volatile while class namespace template typename public private protected virtual override final new delete this throw try catch using operator friend explicit constexpr noexcept nullptr static_cast dynamic_cast reinterpret_cast const_cast decltype mutable export import module concept requires co_await co_return co_yield")
    private val cTypes = set("void char short int long float double signed unsigned bool size_t ssize_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t wchar_t auto string vector map set std")
    private val csKw = set("abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while async await var get set init record yield where when")
    private val csTypes = set("bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic")
    private val jsKw = set("break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while with yield async await get set as")
    private val tsKw = jsKw + set("interface type enum implements declare namespace module abstract private public protected readonly keyof infer is satisfies override")
    private val tsTypes = set("string number boolean any unknown never void object symbol bigint")
    private val jsConst = set("true false null undefined NaN Infinity")
    private val pyKw = set("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case")
    private val pyConst = set("True False None self cls")
    private val shKw = set("if then else elif fi for while until do done case esac function in return local export readonly declare unset shift break continue exit source alias set trap select time")
    private val goKw = set("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var")
    private val goTypes = set("bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any")
    private val rustKw = set("as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while macro_rules")
    private val rustTypes = set("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box")
    private val dartKw = set("abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory final finally for get hide if implements import in interface is late library mixin new on operator part required rethrow return set show static super switch sync this throw try typedef var void while with yield")
    private val swiftKw = set("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch is super self Self throw throws try async await some any")
    private val phpKw = set("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield")
    private val rubyKw = set("alias and begin break case class def defined? do else elsif end ensure for if in module next not or redo rescue retry return self super then undef unless until when while yield require attr_accessor attr_reader attr_writer private protected public")
    private val luaKw = set("and break do else elseif end for function goto if in local not or repeat return then until while")
    private val sqlKw = set("select from where insert into values update set delete create table drop alter add column index view join inner left right outer full on as and or not null is in between like order by group having limit offset union all distinct primary key foreign references default unique check constraint begin commit rollback transaction case when then else end exists if replace trigger database schema with returning integer text real blob varchar char int boolean date timestamp")
    private val dockerKw = set("from run cmd label maintainer expose env add copy entrypoint volume user workdir arg onbuild stopsignal healthcheck shell as")
    private val cmakeKw = set("if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro set unset option project add_executable add_library target_link_libraries target_include_directories include_directories find_package message cmake_minimum_required install add_subdirectory list string file")
    private val groovyKw = javaKw + set("def in as trait it plugins dependencies repositories android implementation api apply")

    val PLAIN = Language("plaintext", "Plain Text", "core", set("txt text log csv tsv"), lexer = PlainLexer, autoClose = "()[]{}\"\"")

    val all: List<Language> = listOf(
        Language("kotlin", "Kotlin", "jvm", set("kt kts"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(kotlinKw, set("Int Long Short Byte Float Double Boolean Char String Unit Any Nothing Array List Map Set"), set("true false null this it"),
                nestedBlock = true, tripleQuotes = true, annotations = true, dollarVariables = false, capitalTypes = true))),
        Language("java", "Java", "jvm", set("java"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(javaKw, javaTypes, set("true false null this"), annotations = true, capitalTypes = true, tripleQuotes = true))),
        Language("groovy", "Groovy / Gradle", "jvm", set("gradle groovy gvy"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(groovyKw, javaTypes, annotations = true, capitalTypes = true, tripleQuotes = true, dollarVariables = true))),
        Language("c", "C", "c", set("c h"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(cKw, cTypes, set("true false NULL nullptr"), preprocessor = true))),
        Language("cpp", "C++", "c", set("cpp cc cxx c++ hpp hh hxx ino ipp inl"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(cKw, cTypes, set("true false NULL nullptr this"), preprocessor = true))),
        Language("csharp", "C#", "c", set("cs csx"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(csKw, csTypes, set("true false null this"), preprocessor = true, capitalTypes = true))),
        Language("javascript", "JavaScript", "web", set("js mjs cjs jsx"), lineComment = "//", blockComment = "/*" to "*/", autoClose = "()[]{}\"\"''``",
            lexer = CLikeLexer(CLikeSpec(jsKw, emptySet(), jsConst, quotes = "\"'", multilineBacktick = true, extraWordStart = "$"))),
        Language("typescript", "TypeScript", "web", set("ts tsx mts cts"), lineComment = "//", blockComment = "/*" to "*/", autoClose = "()[]{}\"\"''``",
            lexer = CLikeLexer(CLikeSpec(tsKw, tsTypes, jsConst, multilineBacktick = true, annotations = true, capitalTypes = true, extraWordStart = "$"))),
        Language("json", "JSON", "web", set("json jsonc json5 webmanifest geojson har"), setOf(".babelrc", ".eslintrc", "tsconfig.json"),
            lineComment = "//", blockComment = "/*" to "*/", autoClose = "[]{}\"\"", lexer = JsonLexer),
        Language("html", "HTML", "web", set("html htm xhtml shtml"), blockComment = "<!--" to "-->", brackets = "()[]{}<>", autoClose = "\"\"''",
            lexer = MarkupLexer),
        Language("css", "CSS", "web", set("css scss less"), lineComment = null, blockComment = "/*" to "*/", lexer = CssLexer),
        Language("xml", "XML", "data", set("xml svg xsd xsl xslt plist kml gpx iml rss atom wsdl xaml csproj fxml"), blockComment = "<!--" to "-->",
            brackets = "()[]{}<>", autoClose = "\"\"''", lexer = MarkupLexer),
        Language("yaml", "YAML", "data", set("yml yaml"), lineComment = "#", lexer = YamlLexer),
        Language("toml", "TOML / INI", "data", set("toml ini cfg conf properties prop editorconfig gitconfig env desktop service"),
            setOf(".gitconfig", ".editorconfig", ".npmrc", ".env", "gradle.properties", "local.properties"), lineComment = "#", lexer = IniLexer),
        Language("markdown", "Markdown", "data", set("md markdown mdown mkd mdx"), blockComment = "<!--" to "-->", autoClose = "()[]{}\"\"``", lexer = MarkdownLexer),
        Language("sql", "SQL", "data", set("sql ddl dml psql"), lineComment = "--", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(sqlKw, emptySet(), set("true false null"), lineComments = listOf("--"), caseInsensitive = true))),
        Language("diff", "Diff", "data", set("diff patch rej"), autoClose = "", lexer = DiffLexer),
        Language("python", "Python", "scripting", set("py pyw pyi pyx"), setOf("SConstruct", "SConscript"), lineComment = "#",
            lexer = CLikeLexer(CLikeSpec(pyKw, set("int float str bool list dict tuple set bytes object type"), pyConst, lineComments = listOf("#"), block = null,
                tripleQuotes = true, annotations = true, capitalTypes = true))),
        Language("shell", "Shell", "scripting", set("sh bash zsh ksh ash dash bats command"), setOf(".bashrc", ".bash_profile", ".profile", ".zshrc", ".zprofile", "gradlew", "PKGBUILD"),
            lineComment = "#", autoClose = "()[]{}\"\"''``",
            lexer = CLikeLexer(CLikeSpec(shKw, set("echo printf cd ls cat grep sed awk find test read eval exec cp mv rm mkdir chmod chown"), set("true false"),
                lineComments = listOf("#"), block = null, dollarVariables = true))),
        Language("ruby", "Ruby", "scripting", set("rb rake gemspec ru erb"), setOf("Gemfile", "Rakefile", "Podfile", "Fastfile", "Appfile"), lineComment = "#",
            lexer = CLikeLexer(CLikeSpec(rubyKw, emptySet(), set("true false nil"), lineComments = listOf("#"), block = null, capitalTypes = true))),
        Language("lua", "Lua", "scripting", set("lua luau rockspec"), lineComment = "--", blockComment = "--[[" to "]]",
            lexer = CLikeLexer(CLikeSpec(luaKw, emptySet(), set("true false nil self"), lineComments = listOf("--"), block = null, luaBlocks = true))),
        Language("php", "PHP", "scripting", set("php phtml php3 php4 php5 phps"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(phpKw, set("int float string bool array object mixed void"), set("true false null TRUE FALSE NULL"),
                lineComments = listOf("//", "#"), dollarVariables = true, capitalTypes = true))),
        Language("go", "Go", "systems", set("go"), setOf("go.mod", "go.sum"), lineComment = "//", blockComment = "/*" to "*/", autoClose = "()[]{}\"\"''``",
            lexer = CLikeLexer(CLikeSpec(goKw, goTypes, set("true false nil iota"), multilineBacktick = true))),
        Language("rust", "Rust", "systems", set("rs"), lineComment = "//", blockComment = "/*" to "*/", autoClose = "()[]{}\"\"",
            lexer = CLikeLexer(CLikeSpec(rustKw, rustTypes, set("true false None Some Ok Err"), nestedBlock = true, quotes = "\"", lifetimes = true,
                annotations = false, capitalTypes = true))),
        Language("swift", "Swift", "systems", set("swift"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(swiftKw, set("Int Double Float Bool String Character Array Dictionary Set Optional Void"), set("true false nil"),
                nestedBlock = true, quotes = "\"", tripleQuotes = true, annotations = true, capitalTypes = true))),
        Language("dart", "Dart", "systems", set("dart"), lineComment = "//", blockComment = "/*" to "*/",
            lexer = CLikeLexer(CLikeSpec(dartKw, set("int double num bool String List Map Set Future Stream void Object dynamic"), set("true false null this"),
                nestedBlock = true, tripleQuotes = true, annotations = true, capitalTypes = true, dollarVariables = false))),
        Language("makefile", "Makefile", "build", set("mk mak make"), setOf("Makefile", "makefile", "GNUmakefile"), lineComment = "#",
            lexer = CLikeLexer(CLikeSpec(set("ifeq ifneq ifdef ifndef else endif include define endef export override vpath"), emptySet(), emptySet(),
                lineComments = listOf("#"), block = null, dollarVariables = true))),
        Language("dockerfile", "Dockerfile", "build", set("dockerfile containerfile"), setOf("Dockerfile", "Containerfile"), lineComment = "#",
            lexer = CLikeLexer(CLikeSpec(dockerKw, emptySet(), emptySet(), lineComments = listOf("#"), block = null, dollarVariables = true, caseInsensitive = true))),
        Language("cmake", "CMake", "build", set("cmake"), setOf("CMakeLists.txt"), lineComment = "#",
            lexer = CLikeLexer(CLikeSpec(cmakeKw, emptySet(), set("on off true false yes no"), lineComments = listOf("#"), block = null, dollarVariables = true, caseInsensitive = true))),
        PLAIN,
    )

    /** Languages from installed extensions; they come before Tedit's own of the same id. Set by Tedit's window. */
    @Volatile var external: (() -> List<Language>)? = null

    fun byId(id: String?): Language? = external?.invoke()?.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id }

    /** The language for a file name, from its name, its extension, or a #! line in [firstLine]. */
    fun detect(name: String, firstLine: String?, disabledPacks: Set<String>): Language {
        val fromExtensions = external?.invoke().orEmpty()
        if (fromExtensions.isNotEmpty()) {
            fromExtensions.firstOrNull { name in it.fileNames }?.let { return it }
            val x = name.lowercase().substringAfterLast('.', "")
            if (x.isNotEmpty()) fromExtensions.firstOrNull { x in it.extensions }?.let { return it }
        }
        val enabled = all.filter { it.pack !in disabledPacks || it === PLAIN }
        enabled.firstOrNull { name in it.fileNames }?.let { return it }
        val lower = name.lowercase()
        // Two-part extensions first: .d.ts is TypeScript anyway; .tar.gz never reaches here.
        val ext = lower.substringAfterLast('.', "")
        if (ext.isNotEmpty()) enabled.firstOrNull { ext in it.extensions }?.let { return it }
        if (lower.startsWith("dockerfile")) enabled.firstOrNull { it.id == "dockerfile" }?.let { return it }
        val shebang = firstLine?.takeIf { it.startsWith("#!") }
        if (shebang != null) {
            val interp = shebang.substringAfterLast('/').substringAfter("env ").trim().substringBefore(' ')
            val id = when {
                interp.startsWith("python") -> "python"
                interp in setOf("sh", "bash", "zsh", "ksh", "ash", "dash", "mksh") -> "shell"
                interp.startsWith("node") -> "javascript"
                interp.startsWith("ruby") -> "ruby"
                interp.startsWith("php") -> "php"
                interp.startsWith("lua") -> "lua"
                else -> null
            }
            if (id != null) enabled.firstOrNull { it.id == id }?.let { return it }
        }
        if (firstLine != null && firstLine.trimStart().startsWith("<?xml")) enabled.firstOrNull { it.id == "xml" }?.let { return it }
        return PLAIN
    }
}
