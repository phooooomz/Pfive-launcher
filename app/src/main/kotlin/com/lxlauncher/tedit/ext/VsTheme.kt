// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A VS Code colour theme: workbench colours by key (editor.background, sideBar.background, …) and token colour rules
 * by TextMate scope. Themes can include another theme file, and token rules can sit in a .tmTheme plist.
 */
class VsTheme(val name: String, val dark: Boolean, private val colors: Map<String, Int>, private val rules: List<Rule>) {
    class Rule(val selectors: List<String>, val foreground: Int)

    /** The first of [keys] the theme defines. */
    fun color(vararg keys: String): Int? {
        for (k in keys) colors[k]?.let { return it }
        return null
    }

    /**
     * The foreground for [scope], as VS Code picks it: the rule whose selector matches the longest prefix of the scope
     * wins. Only the last part of a descendant selector ("source.js string") is compared.
     */
    fun foreground(scope: String): Int? {
        var best: Int? = null
        var bestLen = -1
        for (r in rules) for (sel in r.selectors) {
            val s = sel.substringAfterLast(' ')
            if (s.isEmpty()) continue
            if ((scope == s || scope.startsWith("$s.")) && s.length > bestLen) { best = r.foreground; bestLen = s.length }
        }
        return best
    }

    companion object {
        fun load(e: ExtensionStore.Installed, ref: ExtensionStore.ThemeRef): VsTheme {
            val colors = LinkedHashMap<String, Int>()
            val rules = ArrayList<Rule>()
            var dark = ref.dark
            fun read(rel: String, base: File?, depth: Int) {
                if (depth > 5) return
                val f = (if (base != null) File(base, rel).canonicalFile.takeIf { it.path.startsWith(e.root.canonicalPath + "/") && it.isFile } else e.file(rel))
                    ?: return
                val text = f.readText()
                if (f.name.endsWith(".tmTheme", true) || text.trimStart().startsWith("<")) {
                    rules += Plist.tmThemeRules(text)
                    return
                }
                val o = Jsonc.obj(text)
                // The included theme first, so this file's own values win.
                o.optString("include").takeIf { it.isNotEmpty() }?.let { read(it, f.parentFile, depth + 1) }
                when (o.optString("type")) { "light", "hcLight" -> dark = false; "dark", "hc", "hcDark" -> dark = true }
                o.optJSONObject("colors")?.let { c -> for (k in c.keys()) parse(c.optString(k))?.let { colors[k] = it } }
                when (val tc = o.opt("tokenColors")) {
                    is JSONArray -> rules += rulesOf(tc)
                    is String -> read(tc, f.parentFile, depth + 1)
                }
            }
            read(ref.path, null, 0)
            return VsTheme(ref.label, dark, colors, rules)
        }

        fun rulesOf(a: JSONArray): List<Rule> {
            val out = ArrayList<Rule>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val fg = o.optJSONObject("settings")?.optString("foreground")?.let { parse(it) } ?: continue
                val selectors = when (val s = o.opt("scope")) {
                    is String -> s.split(',').map { it.trim() }
                    is JSONArray -> (0 until s.length()).map { s.optString(it).trim() }
                    else -> continue
                }.filter { it.isNotEmpty() }
                if (selectors.isNotEmpty()) out += Rule(selectors, fg)
            }
            return out
        }

        /** "#RGB", "#RGBA", "#RRGGBB" or "#RRGGBBAA" as an ARGB colour. */
        fun parse(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            val full = when (h.length) {
                3, 4 -> h.map { "$it$it" }.joinToString("")
                6, 8 -> h
                else -> return null
            }
            val v = full.toLongOrNull(16) ?: return null
            return if (full.length == 6) (0xFF000000 or v).toInt()
            else Color.argb((v and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt())
        }
    }
}

/** The little of Apple's plist format that .tmTheme files need. */
object Plist {
    /** Parses a plist document into JSONObject / JSONArray / String values. */
    fun parse(text: String): Any? {
        val p = android.util.Xml.newPullParser()
        p.setInput(java.io.StringReader(text))
        fun value(): Any? {
            // The parser sits on a START_TAG of a value element.
            return when (p.name) {
                "dict" -> {
                    val o = JSONObject()
                    var key: String? = null
                    while (p.next() != org.xmlpull.v1.XmlPullParser.END_TAG) {
                        if (p.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                        if (p.name == "key") key = p.nextText()
                        else { val v = value(); if (key != null) o.put(key, v); key = null }
                    }
                    o
                }
                "array" -> {
                    val a = JSONArray()
                    while (p.next() != org.xmlpull.v1.XmlPullParser.END_TAG) {
                        if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) a.put(value())
                    }
                    a
                }
                "string", "integer", "real", "date", "data" -> p.nextText()
                "true" -> { p.next(); true }
                "false" -> { p.next(); false }
                else -> { var depth = 1; while (depth > 0) { when (p.next()) { org.xmlpull.v1.XmlPullParser.START_TAG -> depth++; org.xmlpull.v1.XmlPullParser.END_TAG -> depth-- } }; null }
            }
        }
        while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && p.name != "plist") return value()
        }
        return null
    }

    fun tmThemeRules(text: String): List<VsTheme.Rule> {
        val root = parse(text) as? JSONObject ?: return emptyList()
        return VsTheme.rulesOf(root.optJSONArray("settings") ?: return emptyList())
    }
}
