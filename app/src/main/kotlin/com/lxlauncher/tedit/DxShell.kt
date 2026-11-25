// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.lxlauncher.tedit.linux.LinuxRuntime
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shell Tedit's terminal starts, set up the way dracxterm sets up its own (after dracxterm 1.1.3's Bootstrap.kt):
 *
 * - Debian set up: bash as dracxterm's user dracos in Debian through VHDP, with dracxterm's prompt
 *   (`DRAC⚔TERM > (device)`), its sudo, su and identity commands, and the phone's time zone.
 * - No Debian: dracxterm's BusyBox ash with its prompt, BusyBox's commands first on PATH.
 * - BusyBox refused by the phone, or Android's shell chosen in Tedit's settings: Android's sh.
 *
 * Every shell reports to Tedit when it shows its prompt, with the exit status of the command before, over dracxterm's
 * app-control escape (OSC 5391 "tedit:prompt=N"); bash also reports a command it could not find ("tedit:missing=").
 * That is how Run knows a command finished and how it ended, as VS Code's terminal integration does.
 */
object DxShell {
    private const val TAG = "LxDxShell"
    /** Where dracxterm's guest home is, inside Debian. */
    const val GUEST_HOME = "/home/dracos"

    enum class Kind { DEBIAN, BUSYBOX, ANDROID }

    class Spec(val kind: Kind, val argv: Array<String>, val env: Array<String>, val cwd: String)

    /** The shell for a new session in [dir] (a host folder, or null for home). [androidShell] is the setting's choice. */
    fun prepare(ctx: Context, dir: File?, androidShell: Boolean): Spec {
        val files = ctx.filesDir
        val root = File(files, "dracxterm")
        val home = File(root, "home")
        val tmp = File(root, "tmp")
        val usrBin = File(root, "usr/bin")
        listOf(home, tmp, usrBin).forEach { it.mkdirs() }
        val nativeLib = ctx.applicationInfo.nativeLibraryDir
        val rt = LinuxRuntime.get(ctx)
        val cwd = dir?.takeIf { it.isDirectory }
        if (!androidShell && rt.ready) return debian(ctx, rt, cwd, home, tmp, nativeLib)

        writeRcFiles(home)
        val busybox = !androidShell && busyboxReady(nativeLib, usrBin)
        val env = arrayOf(
            "HOME=${home.path}", "PWD=${(cwd ?: home).path}", "USER=dracos", "LOGNAME=dracos", "TMPDIR=${tmp.path}",
            "TERM=xterm-256color", "LANG=C.UTF-8",
            // BusyBox first where it runs, because those commands are the Linux-shaped ones.
            "PATH=" + if (busybox) "${usrBin.path}:$nativeLib:/system/bin" else "/system/bin:$nativeLib",
            "ENV=" + File(home, if (busybox) ".ashrc" else ".systemshellrc").path,
        )
        // Through the link: argv[0] has to read "ash" for BusyBox to start its shell.
        val argv = arrayOf(if (busybox) File(usrBin, "ash").path else "/system/bin/sh")
        return Spec(if (busybox) Kind.BUSYBOX else Kind.ANDROID, argv, env, (cwd ?: home).path)
    }

    private fun debian(ctx: Context, rt: LinuxRuntime, cwd: File?, hostHome: File, tmp: File, nativeLib: String): Spec {
        val guestHome = File(rt.rootfs, GUEST_HOME.trimStart('/'))
        runCatching { prepareGuest(ctx, rt.rootfs, guestHome) }.onFailure { Log.w(TAG, "guest setup: ${it.message}") }
        val tz = posixTz()
        val guestEnv = linkedMapOf(
            "HOME" to GUEST_HOME, "PWD" to (cwd?.path ?: GUEST_HOME), "USER" to "dracos", "LOGNAME" to "dracos",
            "DRAC_HOME" to GUEST_HOME, "DRAC_DEVICE" to deviceBrand(), "SHELL" to "/bin/bash", "TMPDIR" to "/tmp",
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "TZ" to tz,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        val argv = rt.command(listOf("/bin/bash", "-l"), cwd?.path, announcePid = false, baseEnv = guestEnv, home = GUEST_HOME)
        // phdp itself is an Android process: it gets a host environment and hands the guest only guestEnv.
        val env = arrayOf("HOME=${hostHome.path}", "PWD=${hostHome.path}", "TMPDIR=${tmp.path}", "TERM=xterm-256color",
            "LANG=C.UTF-8", "PATH=$nativeLib:/system/bin")
        return Spec(Kind.DEBIAN, argv.toTypedArray(), env, hostHome.path)
    }

    /**
     * dracxterm's user home and commands inside Debian, refreshed at every session start as dracxterm does, so a newer
     * LX brings its newer prompt: ~/.bashrc (dracxterm's, with Tedit's prompt report), ~/.profile, and the identity
     * shims in /usr/local/sbin and /usr/local/bin.
     */
    private fun prepareGuest(ctx: Context, rootfs: File, guestHome: File) {
        guestHome.mkdirs()
        val bashrc = ctx.assets.open("dracxterm/bashrc").use { it.readBytes().toString(Charsets.UTF_8) }
        writeAtomic(File(guestHome, ".bashrc"), bashrc + BASH_INTEGRATION, 0x1A4)
        writeAtomic(File(guestHome, ".profile"), "[ -f \"\$HOME/.bashrc\" ] && . \"\$HOME/.bashrc\"\n", 0x1A4)
        File(guestHome, ".hushlogin").takeIf { !it.exists() }?.writeText("")
        val dirs = listOf("usr/local/sbin", "usr/local/bin").map { File(rootfs, it).apply { mkdirs() } }
        for (name in SHIMS) {
            val bytes = runCatching { ctx.assets.open("dracxterm/compat/$name").use { it.readBytes() } }.getOrNull() ?: continue
            for (d in dirs) writeAtomic(File(d, name), bytes.toString(Charsets.UTF_8), 0x1ED)
        }
        val tz = posixTz()
        if (tz != "UTC0") {
            File(rootfs, "etc/profile.d").mkdirs()
            writeAtomic(File(rootfs, "etc/profile.d/00-dracx-tz.sh"), "# drac-Xterm: device-local time for the guest (no tzdata needed).\nexport TZ='$tz'\n", 0x1A4)
        }
    }

    /**
     * A temporary file renamed over the target, as dracxterm writes its shims: a shell reading the old file never sees
     * it half written, and a target that is a link is replaced rather than followed.
     */
    private fun writeAtomic(f: File, text: String, mode: Int) {
        val tmp = File(f.parentFile, ".${f.name}.new")
        try {
            tmp.writeText(text)
            Os.chmod(tmp.path, mode)
            if (!tmp.renameTo(f)) error("rename to ${f.path} failed")
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "${f.name}: ${e.message}")
        }
    }

    /** dracxterm's prompts for ash and Android's sh, with Tedit's prompt report. Rewritten each start (app managed). */
    private fun writeRcFiles(home: File) {
        val esc = "\u001B"
        // ash counts what lies between \[ \] as taking no room, as bash does; the report and the colours go there.
        val ash = "__tedit_prompt() { printf '$esc]5391;tedit:prompt=%s\\007' \"\$1\"; }\n" +
            "export PS1=\"\\[\\\$(__tedit_prompt \\\$?)\\]\\[$esc[35m\\]dracos\\[$esc[0m\\]:\\[$esc[36m\\]\\w\\[$esc[0m\\]\\\$ \"\n" +
            "whoami() { echo dracos; }\n"
        writeAtomic(File(home, ".ashrc"), ash, 0x1A4)
        // Android's sh (mksh) marks what takes no room with a delimiter named by PS1's first two characters ("\001\r").
        val d = "\u0001"
        val mksh = "__tedit_prompt() { printf '$esc]5391;tedit:prompt=%s\\007' \"\$1\"; }\n" +
            "PS1='$d\r$d\$(__tedit_prompt \$?)$d$d$esc[35m${d}dracos$d$esc[0m$d:$d$esc[36m$d\$PWD$d$esc[0m$d\$ '\n"
        writeAtomic(File(home, ".systemshellrc"), mksh, 0x1A4)
    }

    @Volatile private var busyboxOk: Boolean? = null

    /**
     * BusyBox's commands linked into usr/bin, and whether BusyBox runs on this phone at all. dracxterm found phones whose
     * policy kills it at its first system call (SIGSYS, exit 159); there the session gets Android's sh instead of a
     * terminal that closes as it opens. The links are made again when the app's library folder moves (an update).
     */
    private fun busyboxReady(nativeLib: String, usrBin: File): Boolean {
        val busybox = File(nativeLib, "libbusybox.so")
        if (!busybox.isFile) return false
        val marker = File(usrBin.parentFile?.parentFile, ".busybox-links")
        if (runCatching { marker.readText() }.getOrNull() != nativeLib || !File(usrBin, "ash").exists()) {
            link(busybox.path, File(usrBin, "busybox"))
            val applets = runCatching {
                val p = ProcessBuilder(File(usrBin, "busybox").path, "--list").redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readLines()
                if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) error("busybox --list failed")
                out.filter { it.isNotBlank() && '/' !in it }.also { if (it.size < 16) error("busybox --list listed ${it.size}") }
            }.getOrElse {
                Log.w(TAG, "busybox --list: ${it.message}; linking the core commands")
                CORE_APPLETS
            }
            for (a in applets) link(busybox.path, File(usrBin, a))
            runCatching { marker.writeText(nativeLib) }
            busyboxOk = null
        }
        busyboxOk?.let { return it }
        var status = -1
        val ok = runCatching {
            val p = ProcessBuilder(File(usrBin, "ash").path, "-c", ":").redirectErrorStream(true).start()
            if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else { status = p.exitValue(); status == 0 }
        }.getOrDefault(false)
        // 159 is 128 + SIGSYS, the phone's policy for apps killing it; 127 would mean the link or the binary is missing.
        if (!ok) Log.w(TAG, "BusyBox does not run in apps on this phone (exit status $status); the terminal uses Android's sh")
        busyboxOk = ok
        return ok
    }

    /** Replaces a link, including one that no longer resolves (File.exists() follows links, so delete unconditionally). */
    private fun link(target: String, f: File) {
        f.delete()
        runCatching { Os.symlink(target, f.path) }.onFailure { Log.w(TAG, "link ${f.name}: ${it.message}") }
    }

    /** The phone's brand for the prompt, kept to letters, digits, spaces, '_' and '-' so it cannot reshape the prompt. */
    private fun deviceBrand(): String {
        val raw = Build.BRAND.ifBlank { Build.MANUFACTURER }.ifBlank { "dracxterm" }
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(Regex("\\s+"), " ").take(24).trim()
        return if (cleaned.isEmpty()) "dracxterm" else cleaned.replaceFirstChar { it.titlecase() }
    }

    /** The phone's current UTC offset as a POSIX TZ string ("<+08>-8"), which needs no tzdata in the guest. */
    private fun posixTz(): String = runCatching {
        val offMin = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        if (offMin == 0) return "UTC0"
        val h = Math.abs(offMin) / 60
        val m = Math.abs(offMin) % 60
        val name = "<${if (offMin > 0) "+" else "-"}${"%02d".format(h)}${if (m != 0) "%02d".format(m) else ""}>"
        name + (if (offMin > 0) "-" else "+") + h + (if (m != 0) ":${"%02d".format(m)}" else "")
    }.getOrDefault("UTC0")

    /** The shell is at its prompt again: [status] of the command before, and a command bash could not find, if any. */
    class Prompt(val status: Int, val missing: String?)

    /**
     * Reads one app-control payload from the shell, or null for anything that is not Tedit's. The engine keeps only the
     * newest payload, so everything about one command comes in one: "tedit:prompt=127;missing=python3".
     */
    fun report(payload: String): Prompt? {
        if (!payload.startsWith("tedit:prompt=")) return null
        val parts = payload.removePrefix("tedit:prompt=").split(';')
        val status = parts[0].trim().toIntOrNull() ?: return null
        val missing = parts.drop(1).firstOrNull { it.startsWith("missing=") }?.removePrefix("missing=")?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 80 && it.all { c -> c.isLetterOrDigit() || c in "._+-" } }
        return Prompt(status, missing)
    }

    private val SHIMS = listOf("sudo", "su", "fakeroot", "whoami", "id", "logname")

    private val CORE_APPLETS = listOf("sh", "ash", "ls", "cat", "echo", "env", "pwd", "mkdir", "rm", "cp", "mv", "ln",
        "chmod", "grep", "sed", "awk", "vi", "ps", "kill", "uname", "id", "whoami", "clear", "date", "head", "tail")

    /**
     * Added to dracxterm's ~/.bashrc in Debian: the prompt report through PROMPT_COMMAND (dracxterm's PS1 is left as it
     * is). bash looks a missing command up in a subshell, so the name reaches the next prompt report through a file.
     */
    private const val BASH_INTEGRATION = """
# Tedit: tell Tedit when the prompt is back and how the last command ended (dracxterm's app-control escape, OSC 5391).
__tedit_prompt() {
  local s=${'$'}? m= f="/tmp/.tedit-missing-${'$'}${'$'}"
  [ -f "${'$'}f" ] && { m=";missing=${'$'}(head -c 80 "${'$'}f")"; rm -f "${'$'}f"; }
  printf '\033]5391;tedit:prompt=%s%s\007' "${'$'}s" "${'$'}m"
  return ${'$'}s
}
case ";${'$'}{PROMPT_COMMAND:-};" in *";__tedit_prompt;"*) ;; *) PROMPT_COMMAND="__tedit_prompt${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;; esac
command_not_found_handle() { printf '%s' "${'$'}1" > "/tmp/.tedit-missing-${'$'}${'$'}" 2>/dev/null; printf 'bash: %s: command not found\n' "${'$'}1" >&2; return 127; }
"""
}
