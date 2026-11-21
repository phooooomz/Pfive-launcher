// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Reads a git repository the way git stores it: HEAD and refs, the index, loose objects and pack files with their
 * deltas. It never writes to the repository. There is no git program on a phone, so Tedit reads the files itself.
 */
class GitRepo private constructor(val workTree: File, val gitDir: File) {
    data class Commit(val sha: String, val tree: String, val parents: List<String>, val author: String, val time: Long, val message: String)
    data class IndexEntry(val path: String, val sha: String, val size: Long, val mtimeSec: Long, val mtimeNs: Int, val mode: Int)
    enum class Change { ADDED, MODIFIED, DELETED, UNTRACKED }
    data class FileChange(val path: String, val change: Change)
    data class Status(val branch: String?, val head: String?, val staged: List<FileChange>, val unstaged: List<FileChange>, val truncated: Boolean)

    private class Pack(val idx: File, val pack: File) {
        var shas: ByteArray? = null
        var offsets: IntArray? = null
        var largeOffsets: LongArray? = null
        var count = 0
    }

    private var packs: List<Pack>? = null
    private val objectCache = object : LinkedHashMap<String, Pair<Int, ByteArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, ByteArray>>?) = size > 96
    }

    // ---- refs ----

    /** The branch HEAD points at, or null when HEAD is detached. */
    fun branch(): String? {
        val head = File(gitDir, "HEAD").readTextOrNull()?.trim() ?: return null
        return if (head.startsWith("ref: ")) head.removePrefix("ref: ").removePrefix("refs/heads/") else null
    }

    fun headSha(): String? {
        val head = File(gitDir, "HEAD").readTextOrNull()?.trim() ?: return null
        return if (head.startsWith("ref: ")) resolveRef(head.removePrefix("ref: ")) else head.takeIf { isSha(it) }
    }

    fun resolveRef(ref: String): String? {
        File(gitDir, ref).readTextOrNull()?.trim()?.let { v ->
            if (v.startsWith("ref: ")) return resolveRef(v.removePrefix("ref: "))
            if (isSha(v)) return v
        }
        val packed = File(gitDir, "packed-refs").readTextOrNull() ?: return null
        for (line in packed.lineSequence()) {
            if (line.startsWith("#") || line.startsWith("^")) continue
            val parts = line.trim().split(' ')
            if (parts.size == 2 && parts[1] == ref && isSha(parts[0])) return parts[0]
        }
        return null
    }

    // ---- objects ----

    /** (type, content) of the object [sha]: 1 commit, 2 tree, 3 blob, 4 tag. Null when it is not there. */
    fun read(sha: String): Pair<Int, ByteArray>? {
        objectCache[sha]?.let { return it }
        val loose = File(gitDir, "objects/${sha.substring(0, 2)}/${sha.substring(2)}")
        val result = if (loose.isFile) readLoose(loose) else readPacked(sha)
        if (result != null && result.second.size < 4 * 1024 * 1024) objectCache[sha] = result
        return result
    }

    private fun readLoose(f: File): Pair<Int, ByteArray>? {
        val raw = inflate(f.readBytes(), 0, -1) ?: return null
        val sp = raw.indexOf(' '.code.toByte())
        val nul = raw.indexOf(0)
        if (sp < 0 || nul < 0) return null
        val type = when (String(raw, 0, sp)) { "commit" -> 1; "tree" -> 2; "blob" -> 3; "tag" -> 4; else -> return null }
        return type to raw.copyOfRange(nul + 1, raw.size)
    }

    private fun loadPacks(): List<Pack> {
        packs?.let { return it }
        val dir = File(gitDir, "objects/pack")
        val list = dir.listFiles { f -> f.name.endsWith(".idx") }?.mapNotNull { idx ->
            val pack = File(dir, idx.name.removeSuffix(".idx") + ".pack")
            if (pack.isFile) Pack(idx, pack) else null
        } ?: emptyList()
        packs = list
        return list
    }

    private fun loadIdx(p: Pack) {
        if (p.shas != null) return
        val b = p.idx.readBytes()
        // Version 2: magic ff 74 4f 63, version 2, fanout[256], shas, crcs, offsets, large offsets.
        require(b.size > 8 + 1024 && b[0] == 0xFF.toByte() && b[1] == 0x74.toByte() && b[2] == 0x4F.toByte() && b[3] == 0x63.toByte() && int32(b, 4) == 2) { "unsupported pack index" }
        val n = int32(b, 8 + 255 * 4)
        p.count = n
        val shaStart = 8 + 1024
        p.shas = b.copyOfRange(shaStart, shaStart + n * 20)
        val offStart = shaStart + n * 20 + n * 4
        p.offsets = IntArray(n) { int32(b, offStart + it * 4) }
        val largeStart = offStart + n * 4
        val largeCount = (b.size - 40 - largeStart) / 8
        p.largeOffsets = LongArray(maxOf(0, largeCount)) { int64(b, largeStart + it * 8) }
    }

    private fun findInPack(p: Pack, sha: ByteArray): Long {
        loadIdx(p)
        val shas = p.shas ?: return -1
        var lo = 0
        var hi = p.count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            var c = 0
            for (k in 0 until 20) {
                val x = (shas[mid * 20 + k].toInt() and 0xFF) - (sha[k].toInt() and 0xFF)
                if (x != 0) { c = x; break }
            }
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    val o = p.offsets!![mid]
                    return if (o and 0x80000000.toInt() != 0) p.largeOffsets!![o and 0x7FFFFFFF] else o.toLong() and 0xFFFFFFFFL
                }
            }
        }
        return -1
    }

    private fun readPacked(sha: String): Pair<Int, ByteArray>? {
        val bin = hexToBytes(sha)
        for (p in loadPacks()) {
            val off = runCatching { findInPack(p, bin) }.getOrDefault(-1)
            if (off >= 0) return RandomAccessFile(p.pack, "r").use { raf -> readPackObject(p, raf, off, 0) }
        }
        return null
    }

    private fun readPackObject(p: Pack, raf: RandomAccessFile, offset: Long, depth: Int): Pair<Int, ByteArray>? {
        if (depth > 60) return null
        raf.seek(offset)
        var c = raf.read()
        val type = (c ushr 4) and 7
        var size = (c and 15).toLong()
        var shift = 4
        while (c and 0x80 != 0) {
            c = raf.read()
            size = size or ((c and 0x7F).toLong() shl shift)
            shift += 7
        }
        return when (type) {
            1, 2, 3, 4 -> type to (inflateFrom(raf, size.toInt()) ?: return null)
            6 -> {
                // OFS_DELTA: the base lies a variable-length distance before this object.
                c = raf.read()
                var back = (c and 0x7F).toLong()
                while (c and 0x80 != 0) {
                    c = raf.read()
                    back = ((back + 1) shl 7) or (c and 0x7F).toLong()
                }
                val deltaPos = raf.filePointer
                val baseOffset = offset - back
                raf.seek(deltaPos)
                val delta = inflateFrom(raf, size.toInt()) ?: return null
                val base = readPackObject(p, raf, baseOffset, depth + 1) ?: return null
                base.first to (applyDelta(base.second, delta) ?: return null)
            }
            7 -> {
                val baseSha = ByteArray(20)
                raf.readFully(baseSha)
                val delta = inflateFrom(raf, size.toInt()) ?: return null
                val base = read(bytesToHex(baseSha)) ?: return null
                base.first to (applyDelta(base.second, delta) ?: return null)
            }
            else -> null
        }
    }

    private fun inflateFrom(raf: RandomAccessFile, expected: Int): ByteArray? {
        val inf = Inflater()
        val out = ByteArray(expected)
        val buf = ByteArray(8192)
        var n = 0
        try {
            while (!inf.finished() && n < expected) {
                if (inf.needsInput()) {
                    val r = raf.read(buf)
                    if (r <= 0) break
                    inf.setInput(buf, 0, r)
                }
                val got = inf.inflate(out, n, expected - n)
                n += got
                if (got == 0 && inf.needsDictionary()) return null
            }
            if (expected == 0) return out
            return if (n == expected) out else null
        } finally {
            inf.end()
        }
    }

    private fun applyDelta(base: ByteArray, delta: ByteArray): ByteArray? {
        var i = 0
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val c = delta[i++].toInt() and 0xFF
                r = r or ((c and 0x7F).toLong() shl s)
                s += 7
                if (c and 0x80 == 0) return r
            }
        }
        val srcSize = varint()
        val dstSize = varint().toInt()
        if (srcSize != base.size.toLong()) return null
        val out = ByteArray(dstSize)
        var o = 0
        while (i < delta.size) {
            val op = delta[i++].toInt() and 0xFF
            if (op and 0x80 != 0) {
                var off = 0L
                var len = 0
                if (op and 0x01 != 0) off = off or (delta[i++].toLong() and 0xFF)
                if (op and 0x02 != 0) off = off or ((delta[i++].toLong() and 0xFF) shl 8)
                if (op and 0x04 != 0) off = off or ((delta[i++].toLong() and 0xFF) shl 16)
                if (op and 0x08 != 0) off = off or ((delta[i++].toLong() and 0xFF) shl 24)
                if (op and 0x10 != 0) len = len or (delta[i++].toInt() and 0xFF)
                if (op and 0x20 != 0) len = len or ((delta[i++].toInt() and 0xFF) shl 8)
                if (op and 0x40 != 0) len = len or ((delta[i++].toInt() and 0xFF) shl 16)
                if (len == 0) len = 0x10000
                if (off + len > base.size || o + len > out.size) return null
                System.arraycopy(base, off.toInt(), out, o, len)
                o += len
            } else if (op != 0) {
                if (o + op > out.size || i + op > delta.size) return null
                System.arraycopy(delta, i, out, o, op)
                i += op
                o += op
            } else return null
        }
        return if (o == dstSize) out else null
    }

    // ---- commits and trees ----

    fun commit(sha: String): Commit? {
        val (type, data) = read(sha) ?: return null
        if (type != 1) return null
        val text = String(data, Charsets.UTF_8)
        val headEnd = text.indexOf("\n\n").let { if (it < 0) text.length else it }
        var tree = ""
        val parents = ArrayList<String>()
        var author = ""
        var time = 0L
        for (line in text.substring(0, headEnd).lineSequence()) {
            when {
                line.startsWith("tree ") -> tree = line.substring(5).trim()
                line.startsWith("parent ") -> parents += line.substring(7).trim()
                line.startsWith("author ") -> {
                    val rest = line.substring(7)
                    val lt = rest.indexOf('<')
                    author = (if (lt > 0) rest.substring(0, lt) else rest).trim()
                    time = rest.substringAfterLast('>').trim().split(' ').firstOrNull()?.toLongOrNull()?.times(1000) ?: 0L
                }
            }
        }
        val message = if (headEnd < text.length) text.substring(headEnd + 2) else ""
        return Commit(sha, tree, parents, author, time, message)
    }

    /** Every file of the tree [sha], by path. */
    fun flatten(sha: String, prefix: String = "", out: MutableMap<String, String> = HashMap(), budget: IntArray = intArrayOf(200_000)): MutableMap<String, String> {
        val (type, data) = read(sha) ?: return out
        if (type != 2) return out
        var i = 0
        while (i < data.size && budget[0] > 0) {
            val sp = indexOf(data, ' '.code.toByte(), i)
            val nul = indexOf(data, 0, sp)
            if (sp < 0 || nul < 0 || nul + 21 > data.size) break
            val mode = String(data, i, sp - i)
            val name = String(data, sp + 1, nul - sp - 1, Charsets.UTF_8)
            val child = bytesToHex(data.copyOfRange(nul + 1, nul + 21))
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            budget[0]--
            if (mode == "40000") flatten(child, path, out, budget)
            else if (mode != "160000") out[path] = child
            i = nul + 21
        }
        return out
    }

    /** The first-parent history from HEAD, newest first. */
    fun log(limit: Int): List<Commit> {
        val out = ArrayList<Commit>()
        var sha = headSha() ?: return out
        val seen = HashSet<String>()
        while (out.size < limit && seen.add(sha)) {
            val c = commit(sha) ?: break
            out += c
            sha = c.parents.firstOrNull() ?: break
        }
        return out
    }

    fun blobAt(commit: String?, path: String): ByteArray? {
        val c = commit?.let { commit(it) } ?: return null
        var tree = c.tree
        val parts = path.split('/')
        for ((n, part) in parts.withIndex()) {
            val (type, data) = read(tree) ?: return null
            if (type != 2) return null
            var i = 0
            var next: String? = null
            while (i < data.size) {
                val sp = indexOf(data, ' '.code.toByte(), i)
                val nul = indexOf(data, 0, sp)
                if (sp < 0 || nul < 0) return null
                val name = String(data, sp + 1, nul - sp - 1, Charsets.UTF_8)
                if (name == part) { next = bytesToHex(data.copyOfRange(nul + 1, nul + 21)); break }
                i = nul + 21
            }
            tree = next ?: return null
            if (n == parts.size - 1) return read(tree)?.takeIf { it.first == 3 }?.second
        }
        return null
    }

    // ---- index ----

    fun index(): List<IndexEntry> {
        val f = File(gitDir, "index")
        if (!f.isFile) return emptyList()
        val b = f.readBytes()
        if (b.size < 12 || String(b, 0, 4) != "DIRC") return emptyList()
        val version = int32(b, 4)
        val n = int32(b, 8)
        val out = ArrayList<IndexEntry>(n)
        var i = 12
        var prevPath = ""
        for (e in 0 until n) {
            if (i + 62 > b.size) break
            val mtimeSec = int32(b, i + 8).toLong() and 0xFFFFFFFFL
            val mtimeNs = int32(b, i + 12)
            val mode = int32(b, i + 24)
            val size = int32(b, i + 36).toLong() and 0xFFFFFFFFL
            val sha = bytesToHex(b.copyOfRange(i + 40, i + 60))
            val flags = ((b[i + 60].toInt() and 0xFF) shl 8) or (b[i + 61].toInt() and 0xFF)
            var p = i + 62
            if (version >= 3 && flags and 0x4000 != 0) p += 2
            val path: String
            if (version >= 4) {
                // Version 4 stores how many bytes of the previous path to drop, then the new tail.
                var strip = 0L
                var c = b[p++].toInt() and 0xFF
                strip = (c and 0x7F).toLong()
                while (c and 0x80 != 0) {
                    c = b[p++].toInt() and 0xFF
                    strip = ((strip + 1) shl 7) or (c and 0x7F).toLong()
                }
                val nul = indexOf(b, 0, p)
                val prev = prevPath.toByteArray(Charsets.UTF_8)
                val keep = (prev.size - strip.toInt()).coerceAtLeast(0)
                path = String(prev, 0, keep, Charsets.UTF_8) + String(b, p, nul - p, Charsets.UTF_8)
                i = nul + 1
            } else {
                val nul = indexOf(b, 0, p)
                path = String(b, p, nul - p, Charsets.UTF_8)
                val entryLen = nul - i + 1
                i += (entryLen + 7) and 7.inv()
            }
            prevPath = path
            // Stage bits: skip conflict stages other than 0 except to report the path once.
            val stage = (flags ushr 12) and 3
            if (stage == 0 || out.none { it.path == path }) out += IndexEntry(path, sha, size, mtimeSec, mtimeNs, mode)
        }
        return out
    }

    // ---- status ----

    fun status(untracked: Boolean, maxFiles: Int = 40_000, cancelled: () -> Boolean): Status {
        val head = headSha()
        val headFiles = head?.let { commit(it) }?.let { flatten(it.tree) } ?: HashMap()
        val idx = index()
        val idxMap = idx.associateBy { it.path }
        val staged = ArrayList<FileChange>()
        for (e in idx) {
            val h = headFiles[e.path]
            if (h == null) staged += FileChange(e.path, Change.ADDED) else if (h != e.sha) staged += FileChange(e.path, Change.MODIFIED)
        }
        for (p in headFiles.keys) if (p !in idxMap) staged += FileChange(p, Change.DELETED)
        val unstaged = ArrayList<FileChange>()
        var truncated = false
        for (e in idx) {
            if (cancelled()) break
            if (e.mode and 0xF000 == 0xE000) continue // submodule
            val f = File(workTree, e.path)
            if (!f.exists()) { unstaged += FileChange(e.path, Change.DELETED); continue }
            if (f.isDirectory) continue
            val sameStat = f.length() == e.size && (f.lastModified() / 1000) == e.mtimeSec
            if (sameStat) continue
            if (f.length() > 64L * 1024 * 1024) { unstaged += FileChange(e.path, Change.MODIFIED); continue }
            val sha = runCatching { blobSha(f.readBytes()) }.getOrNull() ?: continue
            if (sha != e.sha) unstaged += FileChange(e.path, Change.MODIFIED)
        }
        if (untracked) {
            val ignore = Ignore(this)
            var count = 0
            fun walk(dir: File, rel: String) {
                if (cancelled() || truncated) return
                val kids = dir.listFiles() ?: return
                for (k in kids.sortedBy { it.name }) {
                    if (k.name == ".git") continue
                    val path = if (rel.isEmpty()) k.name else "$rel/${k.name}"
                    if (ignore.ignored(path, k.isDirectory)) continue
                    if (k.isDirectory) walk(k, path)
                    else {
                        if (++count > maxFiles) { truncated = true; return }
                        if (path !in idxMap) unstaged += FileChange(path, Change.UNTRACKED)
                    }
                }
            }
            walk(workTree, "")
        }
        return Status(branch(), head, staged.sortedBy { it.path }, unstaged.sortedBy { it.path }, truncated)
    }

    /** The content a path has in the index, which is what "changes" are measured against. */
    fun indexBlob(path: String): ByteArray? = index().firstOrNull { it.path == path }?.let { read(it.sha)?.second }

    /** .gitignore rules: the root file, nested files, and .git/info/exclude; enough for status to hide build output. */
    class Ignore(private val repo: GitRepo) {
        private class Rule(val base: String, val regex: Regex, val negate: Boolean, val dirOnly: Boolean)
        private val byDir = HashMap<String, List<Rule>>()
        private val global: List<Rule> = parse("", File(repo.gitDir, "info/exclude").readTextOrNull() ?: "")

        private fun rulesFor(dir: String): List<Rule> = byDir.getOrPut(dir) {
            parse(dir, File(repo.workTree, if (dir.isEmpty()) ".gitignore" else "$dir/.gitignore").readTextOrNull() ?: "")
        }

        private fun parse(base: String, text: String): List<Rule> = text.lineSequence().mapNotNull { raw ->
            var line = raw.trimEnd()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val negate = line.startsWith("!")
            if (negate) line = line.substring(1)
            val dirOnly = line.endsWith("/")
            line = line.trimEnd('/')
            val anchored = line.contains('/')
            line = line.trimStart('/')
            Rule(base, Regex((if (anchored) "^" else "(^|.*/)") + glob(line) + "$"), negate, dirOnly)
        }.toList()

        private fun glob(p: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < p.length) {
                val c = p[i]
                when {
                    p.startsWith("**/", i) -> { sb.append("(.*/)?"); i += 3; continue }
                    p.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    c == '[' -> { val e = p.indexOf(']', i); if (e > i) { sb.append(p, i, e + 1); i = e + 1; continue } else sb.append("\\[") }
                    c == '\\' && i + 1 < p.length -> { sb.append(Regex.escape(p[i + 1].toString())); i += 2; continue }
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            return sb.toString()
        }

        fun ignored(path: String, isDir: Boolean): Boolean {
            var result = false
            fun apply(rules: List<Rule>) {
                for (r in rules) {
                    if (r.dirOnly && !isDir) continue
                    val rel = if (r.base.isEmpty()) path else if (path.startsWith(r.base + "/")) path.substring(r.base.length + 1) else continue
                    if (r.regex.matches(rel)) result = !r.negate
                }
            }
            apply(global)
            val parts = path.split('/')
            var dir = ""
            apply(rulesFor(""))
            for (k in 0 until parts.size - 1) {
                dir = if (dir.isEmpty()) parts[k] else "$dir/${parts[k]}"
                apply(rulesFor(dir))
            }
            return result
        }
    }

    companion object {
        /** The repository holding [dir], looking upwards; null when there is none. */
        fun find(dir: File): GitRepo? {
            var d: File? = dir
            var hops = 0
            while (d != null && hops++ < 40) {
                val g = File(d, ".git")
                if (g.isDirectory && File(g, "HEAD").isFile) return GitRepo(d, g)
                if (g.isFile) {
                    // A worktree or submodule: ".git" is a file naming the real directory.
                    val target = g.readTextOrNull()?.trim()?.removePrefix("gitdir:")?.trim()
                    if (target != null) {
                        val real = File(target).let { if (it.isAbsolute) it else File(d, target) }
                        if (File(real, "HEAD").isFile) return GitRepo(d, real.canonicalFile)
                    }
                }
                d = d.parentFile
            }
            return null
        }

        fun blobSha(content: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update("blob ${content.size}\u0000".toByteArray(Charsets.US_ASCII))
            md.update(content)
            return bytesToHex(md.digest())
        }

        fun isSha(s: String) = s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun bytesToHex(b: ByteArray): String {
            val hex = "0123456789abcdef"
            val sb = StringBuilder(b.size * 2)
            for (x in b) { sb.append(hex[(x.toInt() ushr 4) and 15]); sb.append(hex[x.toInt() and 15]) }
            return sb.toString()
        }

        fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }

        private fun int32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        private fun int64(b: ByteArray, o: Int): Long = (int32(b, o).toLong() shl 32) or (int32(b, o + 4).toLong() and 0xFFFFFFFFL)

        private fun indexOf(b: ByteArray, v: Byte, from: Int): Int {
            if (from < 0) return -1
            for (i in from until b.size) if (b[i] == v) return i
            return -1
        }

        private fun ByteArray.indexOf(v: Byte): Int = indexOf(this, v, 0)

        fun inflate(data: ByteArray, off: Int, expected: Int): ByteArray? {
            val inf = Inflater()
            inf.setInput(data, off, data.size - off)
            val out = ByteArrayOutputStream(if (expected > 0) expected else data.size * 3)
            val buf = ByteArray(16384)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } catch (e: java.util.zip.DataFormatException) {
                return null
            } finally {
                inf.end()
            }
        }

        private fun File.readTextOrNull(): String? = try { if (isFile) readText() else null } catch (e: Exception) { null }
    }
}
