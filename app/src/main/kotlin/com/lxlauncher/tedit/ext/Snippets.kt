// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A snippet from an extension (or a completion item in snippet format): what triggers it and what it inserts. */
class Snippet(val name: String, val prefixes: List<String>, val body: String, val description: String?)

/**
 * VS Code snippets: the JSON files extensions contribute, and the body syntax they share with language servers
 * ($1, ${1:default}, ${1|one,two|}, $0, $TM_FILENAME and the other variables).
 */
object Snippets {
    /** Tedit's language ids where VS Code uses another one. */
    private val VSCODE_IDS = mapOf("shell" to listOf("shellscript", "shell", "bash"), "javascript" to listOf("javascript", "javascriptreact"),
        "typescript" to listOf("typescript", "typescriptreact"))

    fun vscodeIds(teditId: String): List<String> = VSCODE_IDS[teditId] ?: listOf(teditId)

    fun parseFile(text: String): List<Snippet> {
        val o = Jsonc.obj(text)
        val out = ArrayList<Snippet>()
        for (name in o.keys()) {
            val s = o.optJSONObject(name) ?: continue
            val prefixes = when (val p = s.opt("prefix")) {
                is String -> listOf(p)
                is JSONArray -> (0 until p.length()).map { p.optString(it) }
                else -> emptyList()
            }.filter { it.isNotEmpty() }
            val body = when (val b = s.opt("body")) {
                is String -> b
                is JSONArray -> (0 until b.length()).joinToString("\n") { b.optString(it) }
                else -> continue
            }
            out += Snippet(name, prefixes, body, s.optString("description").takeIf { it.isNotEmpty() })
        }
        return out
    }

    /** Text to insert and where its tab stops are (offsets into the text, in visiting order, $0 last). */
    class Expansion(val text: String, val stops: List<IntRange>)

    /**
     * Expands [body]: placeholders become their default text, choices their first option, variables their value,
     * and every line after the first takes [indent] so the snippet sits at the cursor's indentation.
     */
    fun expand(body: String, indent: String, fileName: String, selected: String = ""): Expansion {
        val out = StringBuilder()
        val stops = HashMap<Int, IntRange>()
        var i = 0
        fun variable(name: String): String? {
            val now = Date()
            fun f(p: String) = SimpleDateFormat(p, Locale.getDefault()).format(now)
            return when (name) {
                "TM_FILENAME" -> fileName
                "TM_FILENAME_BASE" -> fileName.substringBeforeLast('.')
                "TM_SELECTED_TEXT", "SELECTION" -> selected
                "TM_CURRENT_LINE", "TM_CURRENT_WORD", "TM_LINE_INDEX", "TM_LINE_NUMBER" -> ""
                "CURRENT_YEAR" -> f("yyyy"); "CURRENT_YEAR_SHORT" -> f("yy"); "CURRENT_MONTH" -> f("MM")
                "CURRENT_MONTH_NAME" -> f("MMMM"); "CURRENT_MONTH_NAME_SHORT" -> f("MMM"); "CURRENT_DATE" -> f("dd")
                "CURRENT_DAY_NAME" -> f("EEEE"); "CURRENT_DAY_NAME_SHORT" -> f("EEE"); "CURRENT_HOUR" -> f("HH")
                "CURRENT_MINUTE" -> f("mm"); "CURRENT_SECOND" -> f("ss"); "CURRENT_SECONDS_UNIX" -> (now.time / 1000).toString()
                "UUID" -> java.util.UUID.randomUUID().toString()
                "BLOCK_COMMENT_START" -> "/*"; "BLOCK_COMMENT_END" -> "*/"; "LINE_COMMENT" -> "//"
                else -> null
            }
        }
        // Reads until [end] (exclusive) at nesting level 0, appending to out; returns the index after [end] or the body's end.
        fun parse(stopAt: Char?): Unit {
            while (i < body.length) {
                val c = body[i]
                if (stopAt != null && c == stopAt) return
                when {
                    c == '\\' && i + 1 < body.length && body[i + 1] in "\$}\\,|" -> { out.append(body[i + 1]); i += 2 }
                    c == '\n' -> { out.append('\n').append(indent); i++ }
                    c == '$' && i + 1 < body.length && body[i + 1].isDigit() -> {
                        var j = i + 1
                        while (j < body.length && body[j].isDigit()) j++
                        val n = body.substring(i + 1, j).toInt()
                        if (!stops.containsKey(n)) stops[n] = out.length until out.length
                        i = j
                    }
                    c == '$' && i + 1 < body.length && body[i + 1] == '{' -> {
                        var j = i + 2
                        if (j < body.length && body[j].isDigit()) {
                            while (j < body.length && body[j].isDigit()) j++
                            val n = body.substring(i + 2, j).toInt()
                            val start = out.length
                            when {
                                j < body.length && body[j] == ':' -> { i = j + 1; parse('}'); i++ }
                                j < body.length && body[j] == '|' -> {
                                    val end = body.indexOf("|}", j + 1).takeIf { it > 0 } ?: body.length
                                    out.append(body.substring(j + 1, end).split(',').firstOrNull() ?: "")
                                    i = minOf(end + 2, body.length)
                                }
                                else -> i = (body.indexOf('}', j).takeIf { it >= 0 } ?: (body.length - 1)) + 1
                            }
                            if (!stops.containsKey(n) || stops[n]!!.isEmpty()) stops[n] = start until out.length
                        } else {
                            while (j < body.length && (body[j].isLetterOrDigit() || body[j] == '_')) j++
                            val name = body.substring(i + 2, j)
                            if (j < body.length && body[j] == ':') {
                                val v = variable(name)
                                i = j + 1
                                if (!v.isNullOrEmpty()) { out.append(v); val keep = out.length; parse('}'); out.setLength(keep) } else parse('}')
                                i++
                            } else {
                                out.append(variable(name) ?: name)
                                i = (body.indexOf('}', j).takeIf { it >= 0 } ?: (body.length - 1)) + 1
                            }
                        }
                    }
                    c == '$' && i + 1 < body.length && (body[i + 1].isLetter() || body[i + 1] == '_') -> {
                        var j = i + 1
                        while (j < body.length && (body[j].isLetterOrDigit() || body[j] == '_')) j++
                        val name = body.substring(i + 1, j)
                        out.append(variable(name) ?: name)
                        i = j
                    }
                    else -> { out.append(c); i++ }
                }
            }
        }
        parse(null)
        val order = stops.keys.filter { it > 0 }.sorted().map { stops[it]!! } + listOfNotNull(stops[0] ?: (out.length until out.length))
        return Expansion(out.toString(), order)
    }
}
