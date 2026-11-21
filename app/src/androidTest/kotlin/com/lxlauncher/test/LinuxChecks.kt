// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Instrumentation
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.lxlauncher.tedit.linux.LinuxRuntime
import com.lxlauncher.tedit.linux.TarUnpacker
import com.lxlauncher.tedit.linux.XzInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tedit's Debian (12 November 2026), run with `-e only linux`: the xz decoder on the packed image, the tar unpacker's
 * refusals, then a real setup (the download on x86_64), VHDP's first start, and commands inside Debian. With
 * `-e only linux:keep` the installed system stays for later runs; otherwise it is removed at the end.
 */
internal class LinuxChecks(private val inst: Instrumentation, private val check: (String, () -> String?) -> Unit, private val info: (String) -> Unit, private val keep: Boolean) {
    private val ctx = inst.targetContext
    private val scratch = File(ctx.cacheDir, "linux-check-${System.nanoTime()}").apply { mkdirs() }
    private val rt = LinuxRuntime.get(ctx)

    fun run() {
        try {
            xz()
            tar()
            setup()
        } finally {
            LinuxRuntime.deleteTree(scratch)
            if (!keep) rt.uninstall()
        }
    }

    private fun xz() {
        check("the packed aarch64 image decompresses and starts with its top folder") {
            val name = LinuxRuntime.SOURCES.first { it.asset != null }.asset!!
            XzInputStream(ctx.assets.open(name)).use { x ->
                val head = ByteArray(512)
                var got = 0
                while (got < 512) { val n = x.read(head, got, 512 - got); if (n < 0) break; got += n }
                // The first member is a pax header naming the top folder; its magic says ustar.
                val first = String(head, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val magic = String(head, 257, 5, Charsets.US_ASCII)
                // A megabyte more, to cross several xz blocks of the LZMA2 stream.
                val more = ByteArray(1 shl 20)
                var total = 0
                while (total < more.size) { val n = x.read(more, total, more.size - total); if (n < 0) break; total += n }
                when {
                    magic != "ustar" -> "magic '$magic'"
                    !first.contains("debian-trixie-aarch64") -> "first entry '$first'"
                    total < more.size -> "only $total more bytes"
                    else -> null
                }
            }
        }
    }

    // A tar archive built here, entry by entry.
    private class Tar {
        val out = ByteArrayOutputStream()
        fun entry(name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", mode: Int = 0x1A4): Tar {
            val h = ByteArray(512)
            fun put(off: Int, s: String) { val b = s.toByteArray(); System.arraycopy(b, 0, h, off, b.size) }
            put(0, name)
            put(100, "%07o".format(mode))
            put(108, "0000000"); put(116, "0000000")
            put(124, "%011o".format(body.size))
            put(136, "%011o".format(0))
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            h[156] = type.code.toByte()
            put(157, link)
            put(257, "ustar"); put(263, "00")
            val sum = h.sumOf { it.toInt() and 0xFF }
            put(148, "%06o".format(sum)); h[154] = 0; h[155] = ' '.code.toByte()
            out.write(h)
            out.write(body)
            out.write(ByteArray(((512 - body.size % 512) % 512)))
            return this
        }
        fun bytes(): ByteArray { out.write(ByteArray(1024)); return out.toByteArray() }
    }

    private fun tar() {
        check("the tar unpacker writes files, links and copies, and keeps modes") {
            val dest = File(scratch, "ok")
            val t = Tar().entry("top/", '5', mode = 0x1ED)
                .entry("top/bin/", '5', mode = 0x1ED)
                .entry("top/bin/tool", '0', "#!/bin/sh\necho hi\n".toByteArray(), mode = 0x1ED)
                .entry("top/usr", '2', link = "/usr")
                .entry("top/bin/tool2", '1', link = "top/bin/tool")
                .entry("top/dev/null", '3')
            val st = TarUnpacker(dest, stripComponents = 1).unpack(ByteArrayInputStream(t.bytes()))
            val tool = File(dest, "bin/tool")
            when {
                st.files != 1 || st.symlinks != 1 || st.hardlinks != 1 || st.skipped != 1 -> "stats ${st.files}/${st.symlinks}/${st.hardlinks}/${st.skipped}"
                tool.readText() != "#!/bin/sh\necho hi\n" -> "file content"
                Os.lstat(tool.path).st_mode and 0x49 == 0 -> "execute bits lost"
                Os.readlink(File(dest, "usr").path) != "/usr" -> "link target changed"
                File(dest, "bin/tool2").readText() != tool.readText() -> "hard link not copied"
                else -> null
            }
        }
        check("the tar unpacker refuses names that leave the folder and writes through links") {
            fun refuses(t: Tar): Boolean = runCatching { TarUnpacker(File(scratch, "bad${System.nanoTime()}")).unpack(ByteArrayInputStream(t.bytes())) }.isFailure
            val outside = File(scratch, "outside").apply { mkdirs() }
            when {
                !refuses(Tar().entry("../escape", '0', "x".toByteArray())) -> "'..' accepted"
                !refuses(Tar().entry("a/../../escape", '0', "x".toByteArray())) -> "nested '..' accepted"
                !refuses(Tar().entry("evil", '2', link = outside.path).entry("evil/x", '0', "x".toByteArray())) -> "wrote through a link"
                File(outside, "x").exists() -> "a file landed outside"
                else -> null
            }
        }
        check("deleting a tree removes links without following them") {
            val keepMe = File(scratch, "keep").apply { mkdirs() }
            File(keepMe, "f").writeText("stay")
            val tree = File(scratch, "tree/a").apply { mkdirs() }
            Os.symlink(keepMe.path, File(tree, "link").path)
            Os.symlink("/", File(tree, "root").path)
            LinuxRuntime.deleteTree(File(scratch, "tree"))
            if (File(scratch, "tree").exists()) "tree still there" else if (File(keepMe, "f").readText() != "stay") "followed a link" else null
        }
    }

    private fun exec(argv: List<String>, cwd: String? = null, timeoutS: Long = 120): Pair<Int, String> {
        val p = ProcessBuilder(rt.command(argv, cwd)).redirectErrorStream(true).start()
        val out = StringBuilder()
        val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } } }
        t.start()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1 to "timeout" }
        t.join(2000)
        return p.exitValue() to synchronized(out) { out.toString() }.lines().filterNot { it.startsWith("__TEDIT_PID__") }.joinToString("\n")
    }

    /** `-e only linux:probe`: what the process tree and the guest's network look like, printed as INFO lines. */
    fun probe() {
        check("probe") {
            val (c1, net) = exec(listOf("/bin/bash", "-c", "cat /etc/resolv.conf; getent ahosts deb.debian.org | head -2; getent ahosts security.debian.org | head -2; (exec 3<>/dev/udp/8.8.8.8/53 && echo udp-open); apt-get update 2>&1 | tail -8"), timeoutS = 240)
            info("INFO net exit $c1:\n$net")
            val sh = ShellProbe(ctx, null, androidShell = false)
            sh.waitPrompt(60_000)
            sh.session.send("sleep 40\r")
            SystemClock.sleep(1500)
            val ps = ProcessBuilder("/system/bin/sh", "-c", "ps -A -o PID,PPID,STAT,NAME,ARGS | grep -E 'phdp|loader|bash|sleep' | grep -v grep").redirectErrorStream(true).start()
            info("INFO ps:\n" + ps.inputStream.bufferedReader().readText())
            sh.session.interrupt()
            info("INFO after Ctrl+C: ${sh.waitPrompt(8000)?.status}")
            sh.close()
            null
        }
    }

    private fun setup() {
        var ready = rt.ready
        check("Debian sets up from ${rt.source?.name ?: "nothing"} and starts through VHDP") {
            if (rt.source == null) return@check "no image for ${rt.arch}"
            if (ready) { info("INFO Debian was already set up; setup skipped"); return@check null }
            val started = SystemClock.elapsedRealtime()
            var lastStep: LinuxRuntime.Step? = null
            val stepTimes = LinkedHashMap<String, Long>()
            val r = rt.install({ step, _, _ -> if (step != lastStep) { stepTimes[step.name] = SystemClock.elapsedRealtime() - started; lastStep = step } }, { false })
            info("PERF Debian setup ${SystemClock.elapsedRealtime() - started} ms, steps at $stepTimes")
            r.exceptionOrNull()?.let { return@check "${it.javaClass.simpleName}: ${it.message}" }
            ready = rt.ready
            info("INFO ${r.getOrNull()}")
            if (!ready) "not ready after setup" else null
        }
        if (!ready) return
        check("commands run in Debian as root, with its own release and processor") {
            val started = SystemClock.elapsedRealtime()
            val (code, out) = exec(listOf("/bin/bash", "-c", "cat /etc/os-release | head -2; uname -m; id -u; echo done"))
            info("PERF first command ${SystemClock.elapsedRealtime() - started} ms")
            info("INFO $out".take(400))
            when {
                code != 0 -> "exit $code: $out"
                !out.contains("trixie") -> "not Debian trixie: $out"
                !out.contains(rt.arch!!) -> "processor: $out"
                !out.contains("\n0\n") && !out.lines().contains("0") -> "not root: $out"
                else -> null
            }
        }
        check("a shared folder is the same path inside Debian, both ways") {
            val dir = File(scratch, "shared").apply { mkdirs() }
            File(dir, "in.txt").writeText("from android\n")
            val (code, out) = exec(listOf("/bin/bash", "-c", "cat in.txt; echo from debian > out.txt; pwd"), cwd = dir.path)
            when {
                code != 0 -> "exit $code: $out"
                !out.contains("from android") -> "file not seen: $out"
                !out.contains(dir.path) -> "working folder: $out"
                File(dir, "out.txt").readText().trim() != "from debian" -> "file written inside not seen outside"
                else -> null
            }
        }
        check("dracxterm's shell in Debian answers on a terminal, and Ctrl+C stops what it runs") {
            val sh = ShellProbe(ctx, null, androidShell = false)
            try {
                if (sh.kind != com.lxlauncher.tedit.DxShell.Kind.DEBIAN) return@check "the shell is ${sh.kind}, not Debian"
                if (sh.waitPrompt(60_000) == null) return@check "no prompt: ${sh.text().takeLast(300)}"
                val r = sh.run("echo hello-\$((6*7)); cat /etc/debian_version") ?: return@check "no answer"
                if (r.status != 0 || !sh.text().lines().any { it.trim() == "hello-42" }) return@check "no answer: ${sh.text().takeLast(300)}"
                sh.session.takeReport()
                sh.session.send("sleep 30; echo slept\r")
                SystemClock.sleep(2000)
                val before = SystemClock.uptimeMillis()
                sh.session.interrupt()
                val after = sh.waitPrompt(8_000) ?: return@check "Ctrl+C did not stop sleep: ${sh.text().takeLast(300)}"
                info("PERF Ctrl+C answered in ${SystemClock.uptimeMillis() - before} ms")
                val text = sh.text()
                when {
                    after.status != 130 -> "status ${after.status} after Ctrl+C"
                    text.lines().any { it.trim() == "slept" } -> "the rest of the line ran after Ctrl+C"
                    text.contains("cannot set terminal") || text.contains("no job control") -> "bash has no terminal: ${text.takeLast(300)}"
                    else -> null
                }
            } finally {
                sh.close()
            }
        }
        check("apt installs a package from Debian's servers and it runs") {
            val started = SystemClock.elapsedRealtime()
            val (code, out) = exec(listOf("/bin/bash", "-c",
                "apt-get update -q 2>&1 | tail -2; apt-get install -y -q --no-install-recommends python3-minimal 2>&1 | tail -2; python3 -c 'print(6*7)'"), timeoutS = 900)
            info("PERF apt update + python3-minimal ${SystemClock.elapsedRealtime() - started} ms")
            info("INFO ${out.takeLast(400)}")
            if (code != 0 || !out.trim().endsWith("42")) "exit $code: ${out.takeLast(300)}" else null
        }
        check("dpkg and apt work inside Debian") {
            val (code, out) = exec(listOf("/bin/bash", "-c", "dpkg -s bash | grep -m1 Status; apt-get --version | head -1"))
            if (code != 0 || !out.contains("install ok installed")) "exit $code: $out" else null
        }
    }
}
