// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.linux

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Debian root filesystem Tedit's terminal, tasks and language servers run in, through VHDP (the vhdp/ folder):
 * a rootless engine that traces the guest with ptrace and maps its programs through a userland loader, so no root
 * and no exec from app storage are needed. Programs inside run as LX's own Android user and see themselves as root.
 *
 * The root filesystem for arm64 devices ships inside the APK; x86_64 devices (Waydroid, emulators) download the
 * matching proot-distro build once, checked against its SHA-256. Everything lives in LX's no-backup folder.
 */
class LinuxRuntime private constructor(context: Context) {
    private val ctx = context.applicationContext

    /** One root filesystem image Tedit knows how to set up. */
    data class Source(val name: String, val arch: String, val asset: String?, val url: String?, val sha256: String, val size: Long)

    enum class Step { SPACE, DOWNLOAD, UNPACK, CONFIGURE, TEST }

    sealed class State {
        object Absent : State()
        object Installing : State()
        data class Ready(val info: JSONObject) : State()
        /** The device has no build of VHDP, or no root filesystem for its processor. */
        data class Unsupported(val why: String) : State()
        data class Broken(val why: String) : State()
    }

    val base = File(ctx.noBackupFilesDir, "linux")
    val rootfs = File(base, "debian")
    private val staging = File(base, "debian.staging")
    private val marker = File(rootfs, ".tedit-ready")
    private val events = File(ctx.cacheDir, "tedit-vhdp-events.log")
    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    val phdp = File(nativeDir, "libphdp.so")
    val loader = File(nativeDir, "libvhdp-loader.so")
    private val installing = AtomicBoolean(false)

    /** "aarch64" or "x86_64" for the processors VHDP supports, else null. */
    val arch: String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> null
    }

    val source: Source? get() = SOURCES.firstOrNull { it.arch == arch }

    fun state(): State {
        if (installing.get()) return State.Installing
        val a = arch ?: return State.Unsupported("processor ${Build.SUPPORTED_ABIS.firstOrNull()}")
        if (!phdp.isFile || !loader.isFile) return State.Unsupported("VHDP is not in ${nativeDir.path}")
        if (!marker.isFile) return if (rootfs.exists()) State.Broken("setup did not finish") else State.Absent
        val info = runCatching { JSONObject(marker.readText()) }.getOrNull() ?: return State.Broken("setup record unreadable")
        if (info.optString("arch") != a) return State.Broken("built for ${info.optString("arch")}, this device is $a")
        return State.Ready(info)
    }

    val ready: Boolean get() = state() is State.Ready

    // ---- setup ----

    /**
     * Downloads (when needed), unpacks, configures and tests the root filesystem. Blocks; run it on a worker thread.
     * [progress] gets the step and its done/total counts (bytes, or 0/0 when unknown).
     */
    fun install(progress: (Step, Long, Long) -> Unit, cancelled: () -> Boolean, picked: (() -> InputStream)? = null): Result<JSONObject> {
        if (!installing.compareAndSet(false, true)) return Result.failure(IOException("setup is already running"))
        val started = SystemClock.elapsedRealtime()
        try {
            val src = source ?: return Result.failure(IOException("no Debian build for this processor"))
            if (!phdp.isFile || !loader.isFile) return Result.failure(IOException("VHDP is missing from this build of LX"))
            progress(Step.SPACE, 0, 0)
            base.mkdirs()
            // The archive unpacks to about five times its size; keep room for apt's first downloads on top.
            val need = src.size * 6 + (if (src.asset == null) src.size else 0)
            val free = StatFs(base.path).availableBytes
            if (free < need) return Result.failure(IOException("needs ${need / 1048576} MB free, ${free / 1048576} MB left"))
            // A file the person picked, the image in the APK, a copy already in Download, or a download, in that order.
            // Whichever it is, its checksum must be the known one, checked while it unpacks.
            val local = localCopy(src)
            val archive: () -> InputStream = when {
                picked != null -> picked
                src.asset != null -> { { ctx.assets.open(src.asset) } }
                local != null -> { { FileInputStream(local) } }
                else -> {
                    val file = download(src, progress, cancelled)
                    ({ FileInputStream(file) })
                }
            }
            deleteTree(staging)
            staging.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            val stats = archive().use { raw ->
                val counted = Counting(raw, digest) { n -> progress(Step.UNPACK, n, src.size) }
                XzInputStream(counted).use { xz ->
                    val st = TarUnpacker(staging, stripComponents = 1).unpack(xz, cancelled)
                    // Tar stops at its end marker; the rest of the stream still counts toward the checksum.
                    val sink = ByteArray(64 * 1024)
                    while (xz.read(sink) >= 0) {}
                    while (counted.read(sink) >= 0) {}
                    st
                }
            }
            val sum = digest.digest().joinToString("") { "%02x".format(it) }
            if (sum != src.sha256) throw IOException("archive checksum $sum does not match ${src.sha256}")
            progress(Step.CONFIGURE, 0, 0)
            configure(staging)
            checkArch(staging, src.arch)
            deleteTree(rootfs)
            if (!staging.renameTo(rootfs)) throw IOException("cannot move the unpacked system into place")
            progress(Step.TEST, 0, 0)
            val test = selfTest()
            val info = JSONObject()
                .put("source", src.name).put("sha256", src.sha256).put("arch", src.arch)
                .put("files", stats.files).put("links", stats.symlinks).put("bytes", stats.bytes)
                .put("installedAt", System.currentTimeMillis()).put("test", test)
            marker.writeText(info.toString())
            if (src.asset == null) File(ctx.cacheDir, DOWNLOADS).listFiles()?.forEach { it.delete() }
            Log.i(TAG, "Debian ready in ${SystemClock.elapsedRealtime() - started} ms: ${stats.files} files, ${stats.symlinks} links, test $test")
            return Result.success(info)
        } catch (e: Throwable) {
            Log.w(TAG, "Debian setup failed: ${e.javaClass.simpleName} ${e.message}")
            deleteTree(staging)
            return Result.failure(e)
        } finally {
            installing.set(false)
        }
    }

    /** Removes the root filesystem and everything installed in it. Links inside are removed, never followed. */
    fun uninstall() {
        deleteTree(staging)
        deleteTree(rootfs)
    }

    /** Writes the setup again (DNS, /dev, apt settings) over a system that is already there. */
    fun repair(): Result<String> = runCatching {
        configure(rootfs)
        selfTest()
    }

    /** The image saved in Download already (with the same name and size), readable with all-files access. */
    fun localCopy(src: Source): File? {
        @Suppress("DEPRECATION")
        val f = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), src.name)
        return f.takeIf { it.canRead() && it.length() == src.size }
    }

    private fun download(src: Source, progress: (Step, Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(ctx.cacheDir, DOWNLOADS).apply { mkdirs() }
        val file = File(dir, src.name)
        if (file.length() == src.size && sha256(file) == src.sha256) return file
        if (file.length() > src.size) file.delete()
        var url = URL(src.url)
        var redirects = 0
        while (true) {
            if (cancelled()) throw IOException("cancelled")
            val have = file.length()
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399) {
                val to = c.getHeaderField("Location") ?: throw IOException("redirect without a location")
                c.disconnect()
                url = URL(url, to)
                if (url.protocol != "https" || ++redirects > 5) throw IOException("refused redirect to $url")
                continue
            }
            if (code != 200 && code != 206) { c.disconnect(); throw IOException("download failed: HTTP $code") }
            val append = code == 206 && have > 0
            c.inputStream.use { input ->
                FileOutputStream(file, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = if (append) have else 0L
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > src.size) throw IOException("download is larger than expected")
                        progress(Step.DOWNLOAD, done, src.size)
                    }
                }
            }
            c.disconnect()
            break
        }
        if (file.length() != src.size) throw IOException("download stopped at ${file.length()} of ${src.size} bytes")
        val sum = sha256(file)
        if (sum != src.sha256) {
            file.delete()
            throw IOException("downloaded file checksum $sum does not match ${src.sha256}")
        }
        return file
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Counts and hashes what the decompressor reads, for the progress bar and the checksum. */
    private class Counting(input: InputStream, private val md: MessageDigest, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { md.update(it.toByte()); add(1) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { md.update(b, off, it); add(it.toLong()) } }
        private fun add(n: Long) {
            count += n
            if (count - lastReport >= 256 * 1024) { lastReport = count; onRead(count) }
        }
    }

    /** The guest's processor must be this device's: VHDP runs programs natively and refuses foreign ones. */
    private fun checkArch(root: File, want: String) {
        val bash = listOf("usr/bin/bash", "bin/bash").map { File(root, it) }.firstOrNull { it.isFile } ?: throw IOException("no /usr/bin/bash in the image")
        val head = ByteArray(20)
        FileInputStream(bash).use { if (it.read(head) < 20) throw IOException("bash is not a program") }
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        val got = when (machine) { 0xB7 -> "aarch64"; 0x3E -> "x86_64"; else -> "machine $machine" }
        if (got != want) throw IOException("the image is for $got, this device runs $want")
    }

    /**
     * First-boot setup of the image, as VHDP's own configure step does it but for root only: mount points, the /dev
     * entries VHDP's minimal projection expects, working DNS, localhost, and apt settings for a session without
     * real privileges (no sandbox user, no pseudo terminal for dpkg).
     */
    private fun configure(root: File) {
        fun dir(path: String, mode: Int = 0x1ED) { val f = File(root, path); f.mkdirs(); chmod(f.path, mode) }
        for (d in listOf("dev", "proc", "sys", "root", "etc", "tmp", "run", "var/tmp")) dir(d)
        chmod(File(root, "tmp").path, 0x3FF)
        chmod(File(root, "var/tmp").path, 0x3FF)
        val dev = File(root, "dev")
        for ((name, target) in listOf("fd" to "/proc/self/fd", "stdin" to "/proc/self/fd/0", "stdout" to "/proc/self/fd/1", "stderr" to "/proc/self/fd/2")) {
            val p = File(dev, name).path
            if (lstat(p) == null) runCatching { Os.symlink(target, p) }
        }
        dir("dev/shm", 0x3FF)
        dir("dev/pts")
        for (n in listOf("null", "zero", "full", "random", "urandom", "tty", "ptmx")) {
            val f = File(dev, n)
            if (lstat(f.path) == null) runCatching { f.createNewFile() }
        }
        // Images often ship resolv.conf as a link into /run, which does not exist here.
        writeForced(File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 2001:4860:4860::8888\nnameserver 2606:4700:4700::1111\n")
        writeIfAbsent(File(root, "etc/nsswitch.conf"), "passwd: files\ngroup: files\nshadow: files\nhosts: files dns\nnetworks: files\nprotocols: files\nservices: files\n")
        writeIfAbsent(File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        writeForced(File(root, "etc/hostname"), "tedit\n")
        File(root, "etc/apt/apt.conf.d").mkdirs()
        writeForced(File(root, "etc/apt/apt.conf.d/99tedit"),
            "// Written by Tedit: apt runs as a fake root without privileges to drop or a terminal for dpkg.\n" +
            "APT::Sandbox::User \"root\";\nDpkg::Use-Pty \"0\";\nAPT::Color \"0\";\nAcquire::Retries \"3\";\n")
        writeIfAbsent(File(root, "root/.hushlogin"), "")
    }

    private fun writeIfAbsent(f: File, text: String) {
        if (lstat(f.path) != null) return
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun writeForced(f: File, text: String) {
        val m = lstat(f.path)
        if (m != null && !OsConstants.S_ISREG(m)) runCatching { Os.remove(f.path) }
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun selfTest(): String {
        val p = ProcessBuilder(command(listOf("/bin/sh", "-c", "echo tedit-ok \$(uname -m)"), cwd = null))
            .redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } } }
        reader.start()
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IOException("the first start of Debian did not finish in a minute")
        }
        reader.join(2000)
        val text = synchronized(out) { out.toString() }
        val line = text.lines().firstOrNull { it.startsWith("tedit-ok") } ?: throw IOException("Debian did not start: ${text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")}")
        return line.removePrefix("tedit-ok").trim()
    }

    // ---- running programs ----

    /** Host folders the guest sees at the same path, so a file opened in Tedit has one name inside and out. */
    private fun binds(extra: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        val storage = File("/storage/emulated/0")
        if (storage.canRead()) out += storage.path
        for (p in extra) {
            val f = File(p)
            if (!f.isDirectory) continue
            // A folder inside one already bound needs no bind of its own.
            if (out.none { p == it || p.startsWith("$it/") }) out += f.absolutePath
        }
        return out.toList()
    }

    /**
     * The process command line that runs [argv] in Debian. The first line of output is the process id, as
     * "__TEDIT_PID__<pid>", for Stop and Ctrl+C. [cwd] is a host folder: it is shared with the guest at the same path
     * and becomes the working folder; without one the guest starts in [home]. [baseEnv] is the environment [env] adds
     * to: by default the one for output read as plain text (no colours, no pager); an interactive terminal brings its
     * own.
     */
    fun command(argv: List<String>, cwd: String?, env: Map<String, String> = emptyMap(), share: List<String> = emptyList(), announcePid: Boolean = true,
                baseEnv: Map<String, String> = BASE_ENV, home: String = "/root"): List<String> {
        val shared = binds(listOfNotNull(cwd) + share)
        val guestCwd = cwd?.takeIf { c -> shared.any { c == it || c.startsWith("$it/") } } ?: home
        // A language server's output is the protocol itself, so it gets no first line of its own.
        val script = if (announcePid) "echo __TEDIT_PID__\$\$; exec \"\$@\" 3>>\"\$0\"" else "exec \"\$@\" 3>>\"\$0\""
        val out = arrayListOf("/system/bin/sh", "-c", script, events.path,
            phdp.path, "run", "--event-fd", "3", "--engine", "rootless", "--uid", "0", "--gid", "0",
            "--proc", "host", "--dev", "minimal", "--cwd", guestCwd, "--clear-env")
        val all = LinkedHashMap(baseEnv)
        all.putAll(env)
        for ((k, v) in all) { out += "-e"; out += "$k=$v" }
        for (b in shared) {
            // VHDP resolves a guest path through the image, so the mount point has to exist there.
            mountPoint(b)
            out += "--bind"; out += "$b:$b:rw"
            // /sdcard here is the path inside Debian, the name people expect for shared storage there.
            @android.annotation.SuppressLint("SdCardPath")
            val alias = "/sdcard"
            if (b == "/storage/emulated/0") { mountPoint(alias); out += "--bind"; out += "$b:$alias:rw" }
        }
        out += rootfs.path
        out += "--"
        out += argv
        if (events.length() > 512 * 1024) events.delete()
        return out
    }

    private fun mountPoint(guest: String) {
        val f = File(rootfs, guest.trimStart('/'))
        val m = lstat(f.path)
        if (m == null) f.mkdirs()
        else if (!OsConstants.S_ISDIR(m)) { runCatching { Os.remove(f.path) }; f.mkdirs() }
    }

    /** The guest's processes under the VHDP supervisor [pid]: the shell it started, and what that shell runs. */
    // As a terminal does for its foreground group: the running commands and the shell itself get SIGINT, so the rest
    // of a command line is dropped too. toybox's pkill -P matched nothing here while pgrep -P listed the processes,
    // so kill is given their ids.
    fun interruptScript(pid: Int): String = "for c in \$(pgrep -P $pid); do kill -INT \$(pgrep -P \$c) \$c; done"

    companion object {
        const val TAG = "LxLinux"
        private const val DOWNLOADS = "linux-download"
        private const val USER_AGENT = "Tedit (LX-LAUNCHER)"

        val SOURCES = listOf(
            // Supplied with the project and packed into the APK.
            Source("debian-trixie-aarch64-pd-v4.37.0.tar.xz", "aarch64", "linux/debian-trixie-aarch64-pd-v4.37.0.tar.xz", null,
                "9bd3b19ff7cd300c7c7bf33124b726eb199f4bab9a3b1472f34749c6d12c9195", 35_401_288),
            // proot-distro's last x86_64 trixie image published on GitHub; its checksum is the one GitHub lists.
            Source("debian-trixie-x86_64-pd-v4.29.0.tar.xz", "x86_64", null,
                "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-x86_64-pd-v4.29.0.tar.xz",
                "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716", 36_728_936),
        )

        private val BASE_ENV = linkedMapOf(
            "HOME" to "/root", "USER" to "root", "LOGNAME" to "root", "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            // Output goes to a text view, not a terminal: no colours, no pager, no full-screen dialogs.
            "TERM" to "dumb", "LANG" to "C.UTF-8", "TMPDIR" to "/tmp",
            "PAGER" to "cat", "GIT_PAGER" to "cat", "SYSTEMD_PAGER" to "", "DEBIAN_FRONTEND" to "noninteractive",
        )

        // Holds the application context only.
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: LinuxRuntime? = null

        fun get(context: Context): LinuxRuntime = instance ?: synchronized(this) {
            instance ?: LinuxRuntime(context).also { instance = it }
        }

        private fun lstat(path: String): Int? = try { Os.lstat(path).st_mode } catch (_: ErrnoException) { null }

        private fun chmod(path: String, mode: Int) { try { Os.chmod(path, mode) } catch (_: ErrnoException) {} }

        /**
         * Deletes [f] and everything below it without following links: a root filesystem holds links to "/" and to
         * absolute paths that would lead a plain recursive delete out of it.
         */
        fun deleteTree(f: File) {
            val m = lstat(f.path) ?: return
            if (OsConstants.S_ISDIR(m)) {
                // A folder whose mode forbids writing cannot be emptied until it is opened up.
                chmod(f.path, (m and 0xFFF) or 0x1C0)
                f.list()?.forEach { deleteTree(File(f, it)) }
                try { Os.remove(f.path) } catch (_: ErrnoException) {}
            } else {
                try { Os.remove(f.path) } catch (_: ErrnoException) {}
            }
        }
    }
}
