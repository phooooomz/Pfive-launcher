// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * JSON as VS Code extensions write it: themes, snippets and language configuration files often carry comments and
 * trailing commas, which org.json refuses. Both are removed outside strings before parsing.
 */
object Jsonc {
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        // A BOM at the start is common in files written on Windows.
        if (n > 0 && text[0] == '\uFEFF') i = 1
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    val start = i
                    i++
                    while (i < n && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    i = minOf(i + 1, n)
                    out.append(text, start, i)
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++ }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = minOf(i + 2, n)
                }
                c == ',' -> {
                    // A comma followed only by blanks and comments before } or ] is dropped.
                    var j = i + 1
                    while (j < n) {
                        if (text[j].isWhitespace()) { j++; continue }
                        if (text[j] == '/' && j + 1 < n && text[j + 1] == '/') { while (j < n && text[j] != '\n') j++; continue }
                        if (text[j] == '/' && j + 1 < n && text[j + 1] == '*') { j += 2; while (j + 1 < n && !(text[j] == '*' && text[j + 1] == '/')) j++; j += 2; continue }
                        break
                    }
                    if (j < n && (text[j] == '}' || text[j] == ']')) i++ else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    fun obj(text: String): JSONObject = JSONObject(clean(text))

    fun any(text: String): Any = JSONTokener(clean(text)).nextValue()

    fun array(text: String): JSONArray = JSONArray(clean(text))
}
