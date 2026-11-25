// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.linux

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Unpacks a tar stream (ustar, GNU long names, pax headers) into a folder for a Linux root filesystem.
 *
 * Nothing lands outside [dest]: names are cleaned, absolute and ".." names are refused, and no file is written
 * through a symbolic link, so a link in the archive cannot redirect a later entry elsewhere. Link targets are kept
 * as they are, since VHDP reads them inside the guest. Hard links become copies (Android refuses link(2) in app
 * storage), devices and FIFOs are skipped (VHDP provides /dev), and folder modes are applied at the end so a
 * read-only folder can still be filled.
 */
class TarUnpacker(private val dest: File, private val stripComponents: Int = 0) {
    class Stats {
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        var skipped = 0
        var bytes = 0L
    }

    private val root = dest.absolutePath.trimEnd('/')
    /** Folders known to be real folders inside [dest]: checked once, then trusted. */
    private val safeDirs = HashSet<String>().apply { add(root) }
    private val dirModes = ArrayList<Pair<String, Int>>()
    private val header = ByteArray(512)
    private val buf = ByteArray(64 * 1024)

    fun unpack(input: InputStream, cancelled: () -> Boolean = { false }): Stats {
        dest.mkdirs()
        val stats = Stats()
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        var zeros = 0
        while (true) {
            if (cancelled()) throw IOException("cancelled")
            if (!readFully(input, header, 512)) break
            if (header.all { it == 0.toByte() }) {
                // Two empty blocks end the archive.
                if (++zeros == 2) break
                continue
            }
            zeros = 0
            if (!checksumOk()) throw IOException("tar: damaged header")
            val type = header[156].toInt().toChar()
            val size = paxSize ?: octal(124, 12)
            when (type) {
                'L' -> { longName = readText(input, size); continue }
                'K' -> { longLink = readText(input, size); continue }
                'x' -> {
                    val pax = parsePax(readText(input, size))
                    pax["path"]?.let { paxPath = it }
                    pax["linkpath"]?.let { paxLink = it }
                    pax["size"]?.toLongOrNull()?.let { paxSize = it }
                    continue
                }
                'g' -> { skip(input, size); continue }
            }
            val rawName = paxPath ?: longName ?: ustarName()
            val rawLink = paxLink ?: longLink ?: field(157, 100)
            val mode = octal(100, 8).toInt()
            longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = null
            val rel = relative(rawName)
            if (rel == null) {
                // The archive's own top folder, or a name stripped to nothing.
                skip(input, size)
                continue
            }
            val path = "$root/$rel"
            when (type) {
                '5' -> {
                    makeDirs(path)
                    dirModes += path to (mode and 0xFFF)
                    stats.dirs++
                    skip(input, size)
                }
                '2' -> {
                    prepare(path)
                    try {
                        Os.symlink(rawLink, path)
                    } catch (e: ErrnoException) {
                        throw IOException("tar: cannot link $rel: ${e.message}")
                    }
                    stats.symlinks++
                    skip(input, size)
                }
                '1' -> {
                    val target = relative(rawLink)
                    val from = target?.let { File("$root/$it") }
                    if (from != null && isRegular(from.path)) {
                        prepare(path)
                        from.inputStream().use { src -> FileOutputStream(path).use { out -> src.copyTo(out, buf.size) } }
                        chmod(path, regularMode(lstatMode(from.path) ?: mode))
                        stats.hardlinks++
                    } else stats.skipped++
                    skip(input, size)
                }
                '0', '\u0000', '7' -> {
                    prepare(path)
                    FileOutputStream(path).use { out ->
                        var left = size
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("tar: archive ends inside $rel")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    padding(input, size)
                    chmod(path, regularMode(mode))
                    stats.files++
                    stats.bytes += size
                }
                else -> {
                    // Character and block devices, FIFOs, and anything unknown.
                    stats.skipped++
                    skip(input, size)
                }
            }
        }
        // Deepest folders first, so a read-only parent is set after its children.
        for ((p, m) in dirModes.sortedByDescending { it.first.length }) chmod(p, m or 0x1C0)
        return stats
    }

    /** setuid, setgid and sticky bits mean nothing here; the owner always keeps read and write. */
    private fun regularMode(mode: Int) = (mode and 0x1FF) or 0x180

    // ---- names ----

    /** The cleaned path below [dest], or null for the archive's root. Throws for a name that would escape. */
    private fun relative(name: String): String? {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("tar: refused name $name")
        if (parts.size <= stripComponents) return null
        val kept = parts.drop(stripComponents)
        if (kept.any { it.contains('\u0000') }) throw IOException("tar: refused name $name")
        return kept.joinToString("/")
    }

    private fun ustarName(): String {
        val name = field(0, 100)
        val magic = String(header, 257, 5, Charsets.US_ASCII)
        val prefix = if (magic == "ustar") field(345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun field(off: Int, len: Int): String {
        var end = off
        while (end < off + len && header[end] != 0.toByte()) end++
        return String(header, off, end - off, Charsets.UTF_8)
    }

    private fun octal(off: Int, len: Int): Long {
        // GNU base-256 for large numbers: the high bit of the first byte is set.
        if (header[off].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (header[i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        for (i in off until off + len) {
            val c = header[i].toInt()
            if (c == 0 || c == ' '.code) { if (v != 0L) break else continue }
            if (c < '0'.code || c > '7'.code) break
            v = v * 8 + (c - '0'.code)
        }
        return v
    }

    private fun checksumOk(): Boolean {
        val stored = octal(148, 8)
        var sum = 0L
        for (i in 0 until 512) sum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xFF
        return sum == stored
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < text.length) {
            val sp = text.indexOf(' ', i)
            if (sp < 0) break
            val len = text.substring(i, sp).toIntOrNull() ?: break
            val bytes = text.substring(i).toByteArray(Charsets.UTF_8)
            if (len > bytes.size) break
            // The length counts bytes, not characters.
            val record = String(bytes, 0, len, Charsets.UTF_8)
            val body = record.substring(record.indexOf(' ') + 1).trimEnd('\n')
            val eq = body.indexOf('=')
            if (eq > 0) out[body.substring(0, eq)] = body.substring(eq + 1)
            i += record.length
        }
        return out
    }

    // ---- reading ----

    private fun readFully(input: InputStream, b: ByteArray, n: Int): Boolean {
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) {
                if (got == 0) return false
                throw IOException("tar: archive ends inside a header")
            }
            got += r
        }
        return true
    }

    private fun readText(input: InputStream, size: Long): String {
        if (size > 1 shl 20) throw IOException("tar: header record too large")
        val b = ByteArray(size.toInt())
        if (size > 0 && !readFully(input, b, size.toInt())) throw IOException("tar: archive ends inside a header")
        padding(input, size)
        var end = b.size
        while (end > 0 && b[end - 1] == 0.toByte()) end--
        return String(b, 0, end, Charsets.UTF_8)
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size + pad(size)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("tar: archive ends early")
            left -= n
        }
    }

    private fun padding(input: InputStream, size: Long) {
        var left = pad(size)
        while (left > 0) {
            val n = input.read(buf, 0, left.toInt())
            if (n < 0) throw IOException("tar: archive ends early")
            left -= n
        }
    }

    private fun pad(size: Long) = (512 - size % 512) % 512

    // ---- writing ----

    /** Makes sure [path]'s folder exists and is a real folder in [dest], and that nothing sits at [path] itself. */
    private fun prepare(path: String) {
        makeDirs(path.substringBeforeLast('/'))
        // Removed without following links, whatever sits there.
        if (lstatMode(path) != null) LinuxRuntime.deleteTree(File(path))
    }

    private fun makeDirs(path: String) {
        if (path in safeDirs) return
        val parent = path.substringBeforeLast('/')
        if (parent.length >= root.length && parent != path) makeDirs(parent)
        val m = lstatMode(path)
        when {
            m == null -> try {
                Os.mkdir(path, 0x1ED)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EEXIST) throw IOException("tar: cannot create ${path.removePrefix(root)}: ${e.message}")
            }
            OsConstants.S_ISLNK(m) -> throw IOException("tar: refused to write through the link ${path.removePrefix(root)}")
            !OsConstants.S_ISDIR(m) -> {
                File(path).delete()
                Os.mkdir(path, 0x1ED)
            }
        }
        safeDirs += path
    }

    private fun lstatMode(path: String): Int? = try { Os.lstat(path).st_mode } catch (_: ErrnoException) { null }

    private fun isRegular(path: String): Boolean = lstatMode(path)?.let { OsConstants.S_ISREG(it) } == true

    private fun chmod(path: String, mode: Int) {
        try { Os.chmod(path, mode) } catch (_: ErrnoException) {}
    }
}
