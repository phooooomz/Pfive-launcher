// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import com.lxlauncher.tedit.SpanSink
import com.lxlauncher.tedit.Token
import com.lxlauncher.tedit.ext.TmGrammar
import com.lxlauncher.tedit.ext.TmLexer
import com.lxlauncher.tedit.ext.tokenFor
import kotlin.random.Random

private const val D = "$"

/** A pattern with 120 groups, the last one a closing bracket: (<)(x)?(x)?...(>). */
private val MANY_GROUPS = "(<)" + "(x)?".repeat(118) + "(>)"

/**
 * Checks for the TextMate tokenizer (12 November 2026), each on a small grammar written below: a JavaScript-like one
 * with comments, strings and template strings, a heredoc whose end repeats its begin, a markdown-style quote held by
 * `while`, an include across two grammars, a plist grammar, broken Oniguruma patterns, 5,000 random lines, and equal
 * stacks sharing one state. [vscodeParity] covers what vscode-textmate does that the first version did not: patterns
 * inside captures, $1 in scope names, high group numbers, lookbehind without a bound, injections, \A on the first line
 * only, and a rule that opens and closes on one spot.
 */
internal class TextMateChecks(private val check: (String, () -> String?) -> Unit) {
    private class Lexed(val line: String, val spans: IntArray, val state: Int)

    private val none: (String) -> TmGrammar? = { null }

    fun run() {
        javascript()
        heredoc()
        whileRule()
        includes()
        plist()
        broken()
        oniguruma()
        scopes()
        random()
        interning()
        vscodeParity()
    }

    private fun lex(lexer: TmLexer, line: String, state: Int): Lexed {
        val sink = SpanSink()
        val end = lexer.lex(line, state, sink)
        return Lexed(line, sink.toArray(), end)
    }

    private fun kindAt(spans: IntArray, col: Int): Int {
        var i = 0
        while (i < spans.size) {
            if (col >= spans[i] && col < spans[i + 1]) return spans[i + 2]
            i += 3
        }
        return Token.PLAIN
    }

    private fun describe(l: Lexed): String = (0 until l.spans.size / 3).joinToString(" ") {
        val s = l.spans[it * 3]
        val e = l.spans[it * 3 + 1]
        "'${l.line.substring(s, minOf(e, l.line.length))}'=${l.spans[it * 3 + 2]}"
    }

    /** Every character of [part] (its first match after [from]) has [kind]; null when it does. */
    private fun has(l: Lexed, part: String, kind: Int, from: Int = 0): String? {
        val at = l.line.indexOf(part, from)
        if (at < 0) return "'$part' not in '${l.line}'"
        for (c in at until at + part.length) {
            if (kindAt(l.spans, c) != kind) return "'$part' in '${l.line}' should be $kind: ${describe(l)}"
        }
        return null
    }

    private fun malformed(spans: IntArray, length: Int): String? {
        if (spans.size % 3 != 0) return "span array of ${spans.size}"
        var last = 0
        var i = 0
        while (i < spans.size) {
            val s = spans[i]
            val e = spans[i + 1]
            val k = spans[i + 2]
            if (s < last || e <= s || e > length) return "span $s..$e after $last in a line of $length"
            if (k !in 0 until Token.COUNT) return "kind $k"
            last = e
            i += 3
        }
        return null
    }

    private val js = """
        // A JavaScript-like grammar with the comments and trailing commas real grammars carry.
        {
          "scopeName": "source.minijs",
          "patterns": [
            { "include": "#comments" },
            { "include": "#strings" },
            /* numbers before words */
            { "match": "\\b\\d+(\\.\\d+)?\\b", "name": "constant.numeric.decimal.minijs" },
            { "match": "\\b(if|else|return)\\b", "name": "keyword.control.flow.minijs" },
            { "match": "\\b(var|let)\\b", "name": "storage.type.minijs" },
            {
              "begin": "`", "end": "`", "name": "string.template.minijs",
              "patterns": [
                { "match": "\\\\.", "name": "constant.character.escape.minijs" },
                {
                  "begin": "\\$D\\{", "end": "\\}", "name": "meta.template.expression.minijs",
                  "beginCaptures": { "0": { "name": "punctuation.definition.template-expression.begin.minijs" } },
                  "endCaptures": { "0": { "name": "punctuation.definition.template-expression.end.minijs" } },
                  "patterns": [ { "include": "${D}self" } ],
                },
              ],
            },
          ],
          "repository": {
            "comments": {
              "patterns": [
                { "begin": "/\\*", "end": "\\*/", "name": "comment.block.minijs" },
                { "match": "//.*$D", "name": "comment.line.double-slash.minijs" },
              ],
            },
            "strings": {
              "begin": "\"", "end": "\"", "name": "string.quoted.double.minijs",
              "beginCaptures": { "0": { "name": "punctuation.definition.string.begin.minijs" } },
              "endCaptures": { "0": { "name": "punctuation.definition.string.end.minijs" } },
              "patterns": [ { "match": "\\\\.", "name": "constant.character.escape.minijs" } ],
            },
          },
        }
    """.trimIndent()

    private fun javascript() {
        check("textmate: JSON grammar with comments and trailing commas parses") {
            val g = TmGrammar.parse(js)
            if (g.scopeName != "source.minijs") return@check "scope ${g.scopeName}"
            if (g.repository.keys != setOf("comments", "strings")) return@check "repository ${g.repository.keys}"
            null
        }
        check("textmate: keywords, numbers and a line comment") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val l = lex(lx, "if (x) return 12.5 // done", 0)
            has(l, "if", Token.CONTROL) ?: has(l, "return", Token.CONTROL) ?: has(l, "12.5", Token.NUMBER)
                ?: has(l, "// done", Token.COMMENT) ?: has(l, "x", Token.PLAIN)
                ?: if (l.state != 0) "state ${l.state} after a closed line" else null
        }
        check("textmate: a block comment carries over lines and returns to the root") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val a = lex(lx, "let a /* open", 0)
            val b = lex(lx, "still inside", a.state)
            val c = lex(lx, "close */ return 1", b.state)
            has(a, "let", Token.TYPE) ?: has(a, "/* open", Token.COMMENT) ?: has(b, "still inside", Token.COMMENT)
                ?: has(c, "close */", Token.COMMENT) ?: has(c, "return", Token.CONTROL) ?: has(c, "1", Token.NUMBER)
                ?: when {
                    a.state == 0 -> "no state inside the comment"
                    b.state != a.state -> "state moved inside the comment: ${a.state} -> ${b.state}"
                    c.state != 0 -> "state ${c.state} after the comment closed"
                    else -> null
                }
        }
        check("textmate: strings with escapes through begin/end captures") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val l = lex(lx, """x = "a\"b" + "c";""", 0)
            has(l, "\"a", Token.STRING) ?: has(l, "\\\"", Token.CONSTANT) ?: has(l, "b\"", Token.STRING)
                ?: has(l, " + ", Token.PLAIN) ?: has(l, "\"c\"", Token.STRING)
                ?: if (l.state != 0) "state ${l.state}" else null
        }
        check("textmate: a template string runs \$self inside \${...}") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val l = lex(lx, "let t = `a ${D}{1 + \"s\"} b`;", 0)
            has(l, "`a ", Token.STRING) ?: has(l, "$D{", Token.PUNCT) ?: has(l, "1", Token.NUMBER)
                ?: has(l, " + ", Token.PLAIN) ?: has(l, "\"s\"", Token.STRING) ?: has(l, "}", Token.PUNCT)
                ?: has(l, " b`", Token.STRING) ?: if (l.state != 0) "state ${l.state}" else null
        }
        check("textmate: a template expression left open continues on the next line") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val a = lex(lx, "`open ${D}{2", 0)
            val b = lex(lx, "} end`", a.state)
            has(a, "`open ", Token.STRING) ?: has(a, "2", Token.NUMBER) ?: has(b, "}", Token.PUNCT)
                ?: has(b, " end`", Token.STRING)
                ?: if (a.state == 0 || b.state != 0) "states ${a.state}, ${b.state}" else null
        }
    }

    private val heredocGrammar = """
        {
          "scopeName": "source.heredoc",
          "patterns": [
            {
              "begin": "<<(\\S+)", "end": "^\\1$D", "name": "string.unquoted.heredoc",
              "beginCaptures": { "1": { "name": "keyword.control.heredoc-token" } },
              "endCaptures": { "0": { "name": "keyword.control.heredoc-token" } }
            },
            { "match": "\\bcat\\b", "name": "support.function.builtin" }
          ]
        }
    """.trimIndent()

    private fun heredoc() {
        check("textmate: an end pattern repeats the begin capture, quoted") {
            val lx = TmLexer(TmGrammar.parse(heredocGrammar), none)
            val a = lex(lx, "cat <<E.F", 0)
            val b = lex(lx, "body E.F tail", a.state)
            val c = lex(lx, "EXF", b.state)
            val d = lex(lx, "E.F", c.state)
            val e = lex(lx, "cat", d.state)
            has(a, "cat", Token.FUNCTION) ?: has(a, "<<", Token.STRING) ?: has(a, "E.F", Token.CONTROL)
                ?: has(b, "body E.F tail", Token.STRING) ?: has(c, "EXF", Token.STRING) ?: has(d, "E.F", Token.CONTROL)
                ?: has(e, "cat", Token.FUNCTION)
                ?: when {
                    a.state == 0 || b.state != a.state || c.state != a.state -> "states ${a.state} ${b.state} ${c.state}"
                    d.state != 0 -> "state ${d.state} after the closing line"
                    lex(lx, "cat <<END", 0).state == a.state -> "two delimiters share one state"
                    else -> null
                }
        }
    }

    private val quoteGrammar = """
        {
          "scopeName": "text.miniquote",
          "patterns": [
            {
              "begin": "(^|\\G)[ ]{0,3}(>) ?", "while": "(^|\\G)[ ]{0,3}(>) ?", "name": "comment.block.quote.miniquote",
              "beginCaptures": { "2": { "name": "punctuation.definition.quote.begin" } },
              "whileCaptures": { "2": { "name": "punctuation.definition.quote.begin" } },
              "patterns": [ { "match": "\\*\\*\\w+\\*\\*", "name": "markup.heading.strong" } ]
            },
            { "match": "\\*\\*\\w+\\*\\*", "name": "markup.inserted.strong" }
          ]
        }
    """.trimIndent()

    private fun whileRule() {
        check("textmate: a while rule holds while each line matches and ends when one does not") {
            val lx = TmLexer(TmGrammar.parse(quoteGrammar), none)
            val a = lex(lx, "> one", 0)
            val b = lex(lx, "  > two **b**", a.state)
            val c = lex(lx, "three **c**", b.state)
            has(a, ">", Token.PUNCT) ?: has(a, "one", Token.COMMENT) ?: has(b, ">", Token.PUNCT)
                ?: has(b, "two", Token.COMMENT) ?: has(b, "**b**", Token.HEADING) ?: has(c, "three", Token.PLAIN)
                ?: has(c, "**c**", Token.INSERTED)
                ?: if (a.state == 0 || b.state != a.state || c.state != 0) "states ${a.state} ${b.state} ${c.state}" else null
        }
    }

    private val host = """
        {
          "scopeName": "source.host",
          "patterns": [
            {
              "begin": "<css>", "end": "</css>", "contentName": "meta.embedded.block.css",
              "beginCaptures": { "0": { "name": "entity.name.tag.open" } },
              "endCaptures": { "0": { "name": "entity.name.tag.close" } },
              "patterns": [ { "include": "source.minicss" } ]
            },
            { "include": "source.minicss#numbers" },
            { "include": "source.absent" },
            { "include": "#nowhere" }
          ]
        }
    """.trimIndent()

    private val css = """
        {
          "scopeName": "source.minicss",
          "patterns": [
            { "match": "\\b(color|margin)(?=\\s*:)", "name": "support.type.property-name.css" },
            { "include": "#numbers" }
          ],
          "repository": { "numbers": { "match": "\\b\\d+px\\b", "name": "constant.numeric.length.css" } }
        }
    """.trimIndent()

    private fun includes() {
        check("textmate: include of another grammar and of its repository entry") {
            val cssGrammar = TmGrammar.parse(css)
            val lx = TmLexer(TmGrammar.parse(host)) { if (it == "source.minicss") cssGrammar else null }
            val l = lex(lx, "a <css>color: 10px</css> 5px color: x", 0)
            val end = l.line.indexOf("</css>")
            has(l, "<css>", Token.TAG) ?: has(l, "color", Token.ATTRIBUTE) ?: has(l, "10px", Token.NUMBER)
                ?: has(l, "</css>", Token.TAG) ?: has(l, "5px", Token.NUMBER) ?: has(l, "color", Token.PLAIN, end)
                ?: if (l.state != 0) "state ${l.state}" else null
        }
        check("textmate: a missing or failing grammar leaves its include out") {
            val quiet = lex(TmLexer(TmGrammar.parse(host), none), "<css>color: 10px</css>", 0)
            val loud = lex(TmLexer(TmGrammar.parse(host)) { throw IllegalStateException("no $it") }, "<css>color: 10px</css>", 0)
            has(quiet, "color", Token.PLAIN) ?: has(quiet, "10px", Token.PLAIN) ?: has(quiet, "</css>", Token.TAG)
                ?: has(loud, "</css>", Token.TAG)
        }
    }

    private val plistGrammar = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>name</key><string>Plisty</string>
          <key>scopeName</key><string>source.plisty</string>
          <key>fileTypes</key><array><string>ply</string></array>
          <key>patterns</key>
          <array>
            <dict><key>match</key><string>\b(def|end)\b</string><key>name</key><string>keyword.other.plisty</string></dict>
            <dict>
              <key>begin</key><string>#</string><key>end</key><string>$D</string><key>applyEndPatternLast</key><true/>
              <key>name</key><string>comment.line.number-sign.plisty</string>
            </dict>
            <dict><key>match</key><string>&lt;\w+&gt;</string><key>name</key><string>entity.name.tag.plisty</string></dict>
            <dict><key>match</key><string>\d+</string><key>name</key><string>constant.numeric.plisty</string></dict>
          </array>
          <key>repository</key><dict/>
        </dict>
        </plist>
    """.trimIndent()

    private fun plist() {
        check("textmate: a plist grammar parses and highlights") {
            val g = TmGrammar.parse(plistGrammar)
            if (g.scopeName != "source.plisty" || g.fileTypes != listOf("ply") || g.name != "Plisty") {
                return@check "read ${g.scopeName} ${g.fileTypes} ${g.name}"
            }
            val l = lex(TmLexer(g, none), "def foo <bar> 42 # hi", 0)
            has(l, "def", Token.KEYWORD) ?: has(l, "<bar>", Token.TAG) ?: has(l, "42", Token.NUMBER)
                ?: has(l, "# hi", Token.COMMENT) ?: has(l, "foo", Token.PLAIN)
                ?: if (l.state != 0) "state ${l.state}" else null
        }
        check("textmate: unusable text is refused with a clear message") {
            val bad = listOf("", "not a grammar", "{ \"patterns\": [] }", "<plist><array/></plist>", "{ \"scopeName\": ")
            for (text in bad) {
                val msg = try {
                    TmGrammar.parse(text)
                    return@check "accepted '$text'"
                } catch (e: IllegalArgumentException) {
                    e.message
                }
                if (msg.isNullOrBlank()) return@check "no message for '$text'"
            }
            null
        }
    }

    private val brokenGrammar = """
        {
          "scopeName": "source.broken",
          "patterns": [
            { "match": "(a)?(?(1)bc|b)", "name": "invalid.illegal.conditional" },
            { "match": "(?<x>a)\\g<x>b", "name": "invalid.illegal.subexpression-call" },
            { "begin": "<<", "end": "(x)?(?(1)y)", "name": "string.never" },
            { "match": "\\bok\\b", "name": "keyword.other.ok" }
          ]
        }
    """.trimIndent()

    private fun broken() {
        check("textmate: rules with patterns Java cannot compile are skipped") {
            val lx = TmLexer(TmGrammar.parse(brokenGrammar), none)
            val l = lex(lx, "abc ab ok << ok", 0)
            has(l, "ok", Token.KEYWORD) ?: has(l, "ok", Token.KEYWORD, l.line.indexOf("<<")) ?: has(l, "b", Token.PLAIN)
                ?: has(l, "<<", Token.PLAIN) ?: if (l.state != 0) "state ${l.state}" else null
        }
    }

    private val onigGrammar = """
        {
          "scopeName": "source.onig",
          "patterns": [
            { "match": "0x\\h+", "name": "constant.numeric.hex" },
            { "match": "\\b[[:upper:]][[:alpha:][:digit:]]*\\b", "name": "entity.name.type.class" },
            { "match": "(?<q_1>['\"]).*?\\k<q_1>", "name": "string.quoted" },
            { "match": "@{", "name": "keyword.other.brace" },
            { "match": "(?x) \\$D [ \\w ]+ # a variable with spaces", "name": "variable.other.spaced" },
            { "match": "(?m)<!--.*?-->", "name": "comment.block" },
            { "match": "(?<p>\\((?:[^()]|\\g<p>)*\\))", "name": "string.other.nested-parens" },
            { "match": "~[\\x{42 43}]+", "name": "constant.other.listed" },
            { "match": "%\\p{Alphabetic}+", "name": "variable.other.percent" },
            { "match": "!{3,1}", "name": "keyword.operator.bang" }
          ]
        }
    """.trimIndent()

    private fun oniguruma() {
        check("textmate: Oniguruma syntax (\\h, POSIX classes, named groups, a bare {, (?x), \\g<p>, \\x{a b}) translates") {
            val lx = TmLexer(TmGrammar.parse(onigGrammar), none)
            val l = lex(lx, "0x1fA Foo2 'q' @{ ${D}ab c <!-- n --> (a(b(c))d) ~BCB %h\u00e9llo !!", 0)
            has(l, "0x1fA", Token.NUMBER) ?: has(l, "Foo2", Token.TYPE) ?: has(l, "'q'", Token.STRING)
                ?: has(l, "@{", Token.KEYWORD) ?: has(l, "${D}ab c", Token.VARIABLE) ?: has(l, "<!-- n -->", Token.COMMENT)
                ?: has(l, "(a(b(c))d)", Token.STRING) ?: has(l, "~BCB", Token.CONSTANT) ?: has(l, "%h\u00e9llo", Token.VARIABLE)
                ?: has(l, "!!", Token.OPERATOR)
        }
    }

    private fun scopes() {
        check("textmate: scope names map to token kinds by their longest prefix") {
            val want = listOf(
                "keyword.control.flow.js" to Token.CONTROL, "keyword.operator.assignment" to Token.OPERATOR,
                "keyword.other.unit" to Token.KEYWORD, "storage.type.function.python" to Token.KEYWORD,
                "storage.type.class.js" to Token.KEYWORD, "storage.type.js" to Token.TYPE,
                "storage.modifier.static" to Token.KEYWORD, "constant.numeric.integer" to Token.NUMBER,
                "constant.character.escape.c" to Token.CONSTANT, "support.constant.math" to Token.CONSTANT,
                "entity.name.function.decorator.ts" to Token.ANNOTATION, "entity.name.function.ts" to Token.FUNCTION,
                "meta.function-call.generic.python" to Token.FUNCTION, "support.class.builtin" to Token.TYPE,
                "entity.other.inherited-class.python" to Token.TYPE, "entity.name.tag.html" to Token.TAG,
                "entity.other.attribute-name.html" to Token.ATTRIBUTE, "variable.parameter.function" to Token.VARIABLE,
                "punctuation.separator.comma" to Token.PUNCT, "punctuation.definition.string.begin.js" to Token.STRING,
                "markup.heading.1.markdown" to Token.HEADING, "markup.inserted.diff" to Token.INSERTED,
                "markup.deleted.diff" to Token.DELETED, "invalid.illegal.character" to Token.INVALID,
                "comment.line.double-slash.js" to Token.COMMENT, "string.quoted.single.py" to Token.STRING,
                "meta.decorator.python" to Token.ANNOTATION, "meta.block.js" to Token.PLAIN, "source.js" to Token.PLAIN,
                "keywordish.thing" to Token.PLAIN, "string.quoted.double punctuation.separator" to Token.PUNCT,
            )
            want.firstOrNull { tokenFor(it.first) != it.second }?.let { "${it.first} -> ${tokenFor(it.first)}, want ${it.second}" }
        }
    }

    private fun random() {
        check("textmate: 5,000 random lines never throw and give well-formed spans") {
            val grammars = listOf(TmGrammar.parse(js), TmGrammar.parse(quoteGrammar), TmGrammar.parse(heredocGrammar))
            val pool = "abcxyz019 \t\"'`/\\*{}()[]$<>#.,;:-+=?!>éß中😀\u00a0"
            val rnd = Random(20261112)
            for (g in grammars) {
                val lx = TmLexer(g, none)
                var state = 0
                repeat(5_000 / grammars.size + 1) { n ->
                    val sb = StringBuilder()
                    repeat(rnd.nextInt(0, 90)) { sb.append(pool[rnd.nextInt(pool.length)]) }
                    val line = sb.toString()
                    // Now and then a state that came from nowhere, as after an edit that the cache did not see.
                    val from = if (n % 97 == 96) rnd.nextInt() else state
                    val l = try {
                        lex(lx, line, from)
                    } catch (e: Throwable) {
                        return@check "${g.scopeName} threw ${e.javaClass.simpleName} on '$line'"
                    }
                    malformed(l.spans, line.length)?.let { return@check "${g.scopeName} on '$line': $it" }
                    val quiet = lx.lex(line, from, null)
                    if (quiet != l.state) return@check "${g.scopeName} on '$line': state $quiet without spans, ${l.state} with"
                    state = l.state
                }
            }
            null
        }
    }

    private fun interning() {
        check("textmate: equal stacks share one state number") {
            val lx = TmLexer(TmGrammar.parse(js), none)
            val a1 = lx.lex("/* open", 0, null)
            val a2 = lx.lex("still", a1, null)
            val b1 = lx.lex("/* open", 0, null)
            val b2 = lx.lex("still", b1, null)
            val other = lx.lex("x = 1 /* another start", 0, null)
            when {
                a1 == 0 -> "no state inside a comment"
                a1 != b1 || a2 != b2 -> "same lines gave $a1,$a2 then $b1,$b2"
                a2 != a1 -> "the comment's state changed from $a1 to $a2"
                other != a1 -> "another line into the same comment gave $other, not $a1"
                else -> null
            }
        }
    }

    private val parityGrammar = """
        {
          "scopeName": "source.parity",
          "patterns": [
            { "match": "\\A#!.*${D}", "name": "comment.line.shebang" },
            { "match": "^(#{1,3})\\s+(.*)${D}", "captures": {
                "1": { "name": "punctuation.definition.heading" },
                "2": { "name": "markup.heading", "patterns": [ { "match": "`[^`]*`", "name": "markup.inline.raw" } ] } } },
            { "match": "\\b(class|struct)\\s+(\\w+)", "captures": {
                "1": { "name": "storage.type.${D}1.parity" }, "2": { "name": "entity.name.type" } } },
            { "match": "(?<=^-{3,}\\s*)x", "name": "keyword.other" },
            { "match": "/\\*\\*(?~\\*/)\\*/", "name": "comment.block.documentation" },
            { "match": "${MANY_GROUPS}", "name": "string.other", "captures": {
                "1": { "name": "punctuation.definition.bracket" }, "120": { "name": "punctuation.definition.bracket" } } },
            { "begin": "//", "end": "${D}", "name": "comment.line" },
            { "begin": "(?<=\\{)", "end": "(?=\\n)", "name": "string.stuck" }
          ],
          "injections": {
            "R:source.parity - comment": { "patterns": [ { "match": "<", "name": "invalid.illegal.bad" } ] },
            "L:source.parity comment": { "patterns": [ { "match": "TODO", "name": "keyword.todo" } ] }
          }
        }
    """.trimIndent()

    private val injectGrammar = """
        { "scopeName": "source.parity.decorators", "injectionSelector": "L:source.parity - comment",
          "patterns": [ { "match": "@\\w+", "name": "entity.name.function.decorator" } ] }
    """.trimIndent()

    private fun vscodeParity() {
        val g = TmGrammar.parse(parityGrammar)
        val guest = TmGrammar.parse(injectGrammar)
        val lx = TmLexer(g, none) { scope -> if (scope == "source.parity") listOf(guest) else emptyList() }
        check("textmate: a capture with its own patterns is tokenized again") {
            val l = lex(lx, "## Use `npm` now", 0)
            has(l, "##", Token.PUNCT) ?: has(l, "Use", Token.HEADING) ?: has(l, "`npm`", Token.STRING) ?: has(l, "now", Token.HEADING)
        }
        check("textmate: \$1 in a scope name takes the matched text") {
            val a = lex(lx, "class Foo", 0)
            val b = lex(lx, "struct Bar", 0)
            // storage.type.class reads as a keyword, storage.type.struct as a type, as in VS Code's themes.
            has(a, "class", Token.KEYWORD) ?: has(b, "struct", Token.TYPE) ?: has(a, "Foo", Token.TYPE)
        }
        check("textmate: capture group 120, a lookbehind written with * and +, and the absent operator (?~...)") {
            val a = lex(lx, "<>", 0)
            val b = lex(lx, "---   x", 0)
            val c = lex(lx, "/** a * b */ z", 0)
            has(a, "<", Token.PUNCT) ?: has(a, ">", Token.PUNCT) ?: has(b, "x", Token.KEYWORD)
                ?: has(c, "/** a * b */", Token.COMMENT) ?: has(c, "z", Token.PLAIN)
        }
        check("textmate: injections follow their selectors, L: before and R: after") {
            val l = lex(lx, "a < @deco // TODO < @deco", 0)
            val comment = l.line.indexOf("//")
            has(l, "<", Token.INVALID) ?: has(l, "@deco", Token.ANNOTATION) ?: has(l, "TODO", Token.KEYWORD)
                ?: has(l, "<", Token.COMMENT, comment) ?: has(l, "@deco", Token.COMMENT, comment)
        }
        check("textmate: \\A matches on a document's first line only") {
            val first = lex(lx, "#!/bin/sh", lx.initialState)
            val later = lex(lx, "#!/bin/sh", 0)
            has(first, "#!/bin/sh", Token.COMMENT) ?: if (kindAt(later.spans, 0) == Token.COMMENT) "\\A matched on a later line" else null
        }
        check("textmate: a rule that opens and closes on one spot stays open, as in vscode-textmate") {
            val a = lex(lx, "f {", 0)
            val b = lex(lx, "y", a.state)
            when {
                a.state == 0 -> "the rule closed"
                else -> has(b, "y", Token.STRING) ?: if (b.state != 0) "state ${b.state} after the next line" else null
            }
        }
    }
}
