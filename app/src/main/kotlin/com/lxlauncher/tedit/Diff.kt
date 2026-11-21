// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

/**
 * Line diff with Myers' algorithm (the one git uses by default). Lines common to both ends are trimmed first, which
 * leaves only the changed middle for the search, so a few edits in a long file cost little.
 */
object Diff {
    /** One line of a diff: ' ' kept, '+' added, '-' removed, with its line numbers on each side (-1 when absent). */
    data class Line(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)

    data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<Line>)

    /** Every line of [a] and [b] in order, marked; null when the change is too large to diff within [maxEdits]. */
    fun lines(a: List<String>, b: List<String>, maxEdits: Int = 2000): List<Line>? {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val am = a.subList(pre, a.size - suf)
        val bm = b.subList(pre, b.size - suf)
        val script = myers(am, bm, maxEdits) ?: return null
        val out = ArrayList<Line>(a.size + bm.size)
        for (i in 0 until pre) out += Line(' ', a[i], i, i)
        var i = 0
        var j = 0
        for (op in script) {
            when (op) {
                0 -> { out += Line(' ', am[i], pre + i, pre + j); i++; j++ }
                1 -> { out += Line('-', am[i], pre + i, -1); i++ }
                else -> { out += Line('+', bm[j], -1, pre + j); j++ }
            }
        }
        for (k in 0 until suf) out += Line(' ', a[a.size - suf + k], a.size - suf + k, b.size - suf + k)
        return out
    }

    /** Edit script: 0 keep, 1 delete from a, 2 insert from b. */
    private fun myers(a: List<String>, b: List<String>, maxEdits: Int): IntArray? {
        val n = a.size
        val m = b.size
        if (n == 0) return IntArray(m) { 2 }
        if (m == 0) return IntArray(n) { 1 }
        // Compare hashes first; lines are checked for equality only when hashes match.
        val ha = IntArray(n) { a[it].hashCode() }
        val hb = IntArray(m) { b[it].hashCode() }
        fun eq(x: Int, y: Int) = ha[x] == hb[y] && a[x] == b[y]
        val max = minOf(n + m, maxEdits)
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        outer@ for (d in 0..max) {
            // Only the diagonals this round can read are kept: memory grows with d squared, not with n times d.
            trace += v.copyOfRange(offset - d - 1, offset + d + 2)
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && eq(x, y)) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = d; break@outer }
                k += 2
            }
        }
        if (found < 0) return null
        // Walk back through the saved frontiers.
        val ops = ArrayList<Int>(n + m)
        var x = n
        var y = m
        for (d in found downTo 1) {
            val vd = trace[d]
            val base = d + 1
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[base + k - 1] < vd[base + k + 1])) k + 1 else k - 1
            val prevX = vd[base + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { ops += 0; x--; y-- }
            if (x == prevX) { ops += 2; y-- } else { ops += 1; x-- }
        }
        while (x > 0 && y > 0) { ops += 0; x--; y-- }
        ops.reverse()
        return ops.toIntArray()
    }

    /** Groups [all] into hunks with [context] unchanged lines around each change, as `git diff` prints them. */
    fun hunks(all: List<Line>, context: Int = 3): List<Hunk> {
        val out = ArrayList<Hunk>()
        var i = 0
        while (i < all.size) {
            if (all[i].kind == ' ') { i++; continue }
            val start = maxOf(0, i - context)
            var end = i
            var lastChange = i
            while (end < all.size) {
                if (all[end].kind != ' ') lastChange = end
                else if (end - lastChange > context * 2) break
                end++
            }
            val stop = minOf(all.size, lastChange + context + 1)
            val lines = all.subList(start, stop)
            val oldStart = lines.firstOrNull { it.oldNo >= 0 }?.oldNo ?: 0
            val newStart = lines.firstOrNull { it.newNo >= 0 }?.newNo ?: 0
            out += Hunk(oldStart, lines.count { it.kind != '+' }, newStart, lines.count { it.kind != '-' }, lines.toList())
            i = stop
        }
        return out
    }

    /**
     * Marks for the editor's gutter, one per line of [b]: 1 added, 2 changed, 3 lines removed just below. Null when
     * the files are too different to diff.
     */
    fun gutterMarks(a: List<String>, b: List<String>): IntArray? {
        val all = lines(a, b, 1200) ?: return null
        val marks = IntArray(b.size)
        var i = 0
        while (i < all.size) {
            if (all[i].kind == ' ') { i++; continue }
            var dels = 0
            val adds = ArrayList<Int>()
            while (i < all.size && all[i].kind != ' ') {
                if (all[i].kind == '-') dels++ else adds += all[i].newNo
                i++
            }
            if (adds.isEmpty()) {
                // Removed lines show under the line before them.
                val at = (i.takeIf { it < all.size }?.let { all[it].newNo } ?: b.size) - 1
                if (at >= 0 && at < marks.size && marks[at] == 0) marks[at] = 3
                else if (b.isNotEmpty() && marks[0] == 0) marks[0] = 3
            } else for ((n, line) in adds.withIndex()) if (line in marks.indices) marks[line] = if (n < dels) 2 else 1
        }
        return marks
    }
}
