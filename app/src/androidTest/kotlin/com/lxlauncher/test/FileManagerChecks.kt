// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.content.Context
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.files.AndroidStorage
import com.lxlauncher.files.Cancel
import com.lxlauncher.files.Cleaner
import com.lxlauncher.files.ConflictChoice
import com.lxlauncher.files.ConflictResolver
import com.lxlauncher.files.FsNode
import com.lxlauncher.files.JobListener
import com.lxlauncher.files.JobProgress
import com.lxlauncher.files.JobResult
import com.lxlauncher.files.JunkItem
import com.lxlauncher.files.JunkKind
import com.lxlauncher.files.LocalBackend
import com.lxlauncher.files.ShellFs
import com.lxlauncher.tools.Duplicate
import com.lxlauncher.tools.Duplicates
import com.lxlauncher.ui.overlays.CardMotion
import com.lxlauncher.ui.overlays.MenuMotion
import com.lxlauncher.ui.theme.LxFonts
import com.lxlauncher.ui.theme.LxTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Checks for the File Explorer's full access through LX Bridge, the cleaner, the 25 fonts and the Applications
 * menu's motions (10 November 2026). Run alone with `-e only fm`. The shell checks work only in
 * /data/local/tmp/lxfm-check-*, which they remove again; the cleaner checks only in LX's own cache.
 */
internal fun fileManagerChecks(ctx: Context, check: (String, () -> String?) -> Unit, onMain: (() -> Unit) -> Unit,
                               info: (String) -> Unit) {
    val shell = ShellFs(ctx)
    val bridge = EmbedClient.get(ctx).serviceRunning
    info("INFO lxdb running: $bridge")
    /** A fresh folder in /data/local/tmp for one check; [cleanup] removes all of them whatever the check did. */
    fun scratch() = "/data/local/tmp/lxfm-check-${System.nanoTime()}"
    fun cleanup() = runCatching { shell.run("rm -rf /data/local/tmp/lxfm-check-*") }

    check("all 25 fonts load in both weights") {
        val missing = LxFonts.all.flatMap { f -> listOf(f.regular to "${f.id} regular", f.medium to "${f.id} medium") }
            .filter { (res, _) -> runCatching { ctx.resources.getFont(res) }.getOrNull() == null }.map { it.second }
        when {
            LxFonts.all.size != 25 -> "expected 25 fonts, found ${LxFonts.all.size}"
            LxFonts.all.map { it.id }.toSet().size != 25 -> "two fonts share an id"
            missing.isNotEmpty() -> "not loaded: $missing"
            else -> null
        }
    }

    check("every menu motion enters, leaves and settles") {
        var error: String? = null
        onMain {
            val theme = LxTheme(ctx, null)
            val host = FrameLayout(ctx)
            val card = LinearLayout(ctx)
            host.addView(card, FrameLayout.LayoutParams(300, 400))
            host.measure(android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(500, android.view.View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 400, 500)
            val motion = CardMotion(card, { theme }, { 48 }, { 12f })
            card.outlineProvider = motion.outline
            for (m in MenuMotion.values()) {
                motion.anchorY = 120
                motion.enter(m)
                motion.rows(listOf(android.view.View(ctx) to 30, android.view.View(ctx) to 90), m, glide = m.ordinal % 2 == 0)
                var gone = false
                motion.leave(m) { gone = true }
                motion.settle()
                if (card.alpha != 1f || card.translationX != 0f || card.scaleX != 1f || card.rotationY != 0f) {
                    error = "${m.id} left the card at alpha ${card.alpha}, x ${card.translationX}, scale ${card.scaleX}, rotation ${card.rotationY}"
                }
                if (m == MenuMotion.OFF && !gone) error = "off did not remove the card at once"
            }
            if (MenuMotion.of("bounce") != MenuMotion.RIBBON || MenuMotion.of(null) != MenuMotion.RIBBON) error = "unknown ids must fall back to ribbon"
        }
        error
    }

    check("paths closed to apps go through the shell, the rest stay local") {
        val local = AndroidStorage.get(ctx).localBackend() ?: return@check "no all-files access"
        val primary = android.os.Environment.getExternalStorageDirectory().absolutePath
        val expect = mapOf(
            "/" to true, "/system" to true, "/data/local/tmp" to true, "$primary/Android/data" to true,
            "$primary/Android/obb/x" to true, "$primary/Android/media" to false, "$primary/Download" to false, primary to false,
            // LX's own folders: the shell cannot read the private one, so LX keeps them to itself.
            ctx.cacheDir.absolutePath to false, "/data/data/${ctx.packageName}/files" to false, "/data/user/0" to true,
            "$primary/Android/data/${ctx.packageName}/files" to false, "$primary/Android/data/${ctx.packageName}x" to true,
        )
        val wrong = expect.filter { (path, closed) -> local.closedToApps(path) != closed }.keys
        if (wrong.isEmpty()) null else "wrong for $wrong"
    }

    if (!bridge) {
        info("SKIP shell checks: lxdb is not running")
    } else {
        check("the shell lists the device root and Android/data") {
            val root = shell.list("/", null)
            val data = shell.list("${android.os.Environment.getExternalStorageDirectory().absolutePath}/Android/data", null)
            when {
                root.entries.none { it.name == "system" && it.isDir } -> "no /system in ${root.entries.take(8).map { it.name }}"
                root.self == null -> "the root's own entry is missing"
                data.entries.none { it.name == ctx.packageName } -> "LX's own folder missing from Android/data"
                data.entries.size < 2 -> "Android/data shows only ${data.entries.size} entries"
                else -> null
            }
        }

        check("the shell makes, writes, reads, renames, copies and removes in /data/local/tmp") {
            val base = scratch()
            try {
            shell.mkdir(base)
            val file = "$base/a b's \"file\".bin"
            val bytes = ByteArray(700_000) { (it * 31 + 7).toByte() }
            shell.createFile(file)
            val dupe = runCatching { shell.createFile(file) }.exceptionOrNull()
            shell.openWrite(file).use { pfd -> FileOutputStream(pfd.fileDescriptor).use { it.write(bytes) } }
            shell.commit(file)
            val small = shell.openRead(file, bytes.size.toLong()).use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
            // Over the spool limit the read comes through a pipe instead.
            val piped = shell.openRead(file, Long.MAX_VALUE).use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
            val renamed = "$base/renamed.bin"
            shell.rename(file, renamed)
            val clash = runCatching { shell.rename(renamed, renamed) }.exceptionOrNull()
            var reported = 0L
            val copied = shell.copy(renamed, "$base/copy.bin", null) { reported += it }
            val copy = shell.openRead("$base/copy.bin", copied).use { pfd -> FileInputStream(pfd.fileDescriptor).readBytes() }
            val entries = shell.list(base, null).entries.map { it.name }.sorted()
            shell.remove("$base/copy.bin")
            shell.remove(renamed)
            shell.remove(base)
            val left = shell.stat(base)
            when {
                dupe == null -> "a second create over the same name succeeded"
                !small.contentEquals(bytes) -> "spooled read differs (${small.size} bytes)"
                !piped.contentEquals(bytes) -> "piped read differs (${piped.size} bytes)"
                clash != null -> "renaming a file onto itself failed: ${clash.message}"
                copied != bytes.size.toLong() || reported != copied -> "copy said $copied, reported $reported"
                !copy.contentEquals(bytes) -> "the copy differs"
                entries != listOf("copy.bin", "renamed.bin") -> "listed $entries"
                left != null -> "the test folder is still there"
                else -> null
            }
            } finally {
                cleanup()
            }
        }

        check("a copy job between two shell-only places lands whole and keeps its source") {
            val storage = AndroidStorage.get(ctx)
            val local = storage.localBackend() ?: return@check "no all-files access"
            val base = scratch()
            try {
            shell.mkdir(base)
            shell.mkdir("$base/from")
            shell.mkdir("$base/to")
            val bytes = ByteArray(300_000) { (it xor 0x5A).toByte() }
            shell.createFile("$base/from/x.dat")
            shell.openWrite("$base/from/x.dat").use { pfd -> FileOutputStream(pfd.fileDescriptor).use { it.write(bytes) } }
            shell.commit("$base/from/x.dat")
            val src = local.node("$base/from/x.dat") ?: return@check "no node for the source"
            val dest = local.node("$base/to") ?: return@check "no node for the destination"
            val engine = com.lxlauncher.files.FileServices.jobs(ctx)
            val result = runJob(engine) { engine.copy(listOf(src), dest, move = false, resolver = ConflictResolver { ConflictChoice.SKIP to true }) }
            val landed = shell.stat("$base/to/x.dat")
            val back = shell.openRead("$base/to/x.dat", bytes.size.toLong()).use { FileInputStream(it.fileDescriptor).readBytes() }
            val leftovers = shell.list("$base/to", null).entries.map { it.name }.filter { it != "x.dat" }
            when {
                result == null -> "the job did not finish in time"
                !result.ok -> "job failed: ${result.error?.reason} ${result.error?.message}"
                landed == null -> "nothing arrived"
                !back.contentEquals(bytes) -> "the copy differs"
                leftovers.isNotEmpty() -> "left behind: $leftovers"
                else -> null
            }
            } finally {
                cleanup()
            }
        }

        check("a symbolic link is listed as a link and opens its target") {
            val local = AndroidStorage.get(ctx).localBackend() ?: return@check "no all-files access"
            val sdcard = local.children(local.node("/")!!, null).firstOrNull { it.name == "sdcard" }
                ?: return@check "no /sdcard entry at the root"
            val target = local.linkTarget(sdcard)
            when {
                sdcard.isDir -> "/sdcard was listed as a folder, which a delete would walk into"
                sdcard.mime != LocalBackend.LINK_MIME -> "mime ${sdcard.mime}"
                target == null || !target.isDir -> "followed to $target"
                else -> null
            }
        }
    }

    check("the cleaner removes only what it was given, and keeps a file that changed") {
        val root = File(ctx.externalCacheDir ?: ctx.cacheDir, "clean-check-${System.nanoTime()}").apply { mkdirs() }
        val temp = File(root, "a.tmp").apply { writeBytes(ByteArray(1000)) }
        val changed = File(root, "b.log").apply { writeBytes(ByteArray(500)) }
        val stays = File(root, "c.tmp").apply { writeBytes(ByteArray(10)) }
        val empty = File(root, "empty").apply { mkdirs() }
        val bytes = ByteArray(4096) { (it * 3).toByte() }
        val keeper = File(root, "keep.jpg").apply { writeBytes(bytes) }
        val copy = File(root, "copy.jpg").apply { writeBytes(bytes) }
        val items = mapOf(
            JunkKind.TEMP_LOGS to listOf(JunkItem(temp.path, temp.name, 1000), JunkItem(changed.path, changed.name, 400)),
            JunkKind.EMPTY_FOLDERS to listOf(JunkItem(empty.path, empty.name, 0)),
            JunkKind.DUPLICATES to listOf(JunkItem(copy.path, copy.name, bytes.size.toLong(),
                duplicate = Duplicate(copy, keeper, bytes.size.toLong(), Duplicates.sha256(keeper)!!))),
        )
        var steps = 0
        val report = Cleaner(ctx).clean(items, Cancel()) { _, _ -> steps++ }
        val result = when {
            temp.exists() -> "the temporary file is still there"
            !changed.exists() -> "a file whose size changed since the scan was removed"
            !stays.exists() -> "a file that was not picked was removed"
            empty.exists() -> "the empty folder is still there"
            copy.exists() -> "the copy is still there"
            !keeper.exists() -> "the original of the copy was removed"
            report.freedBytes != 1000L + bytes.size -> "reported ${report.freedBytes} bytes freed"
            report.removed != 3 || report.failed != 1 -> "removed ${report.removed}, kept ${report.failed}"
            steps != 4 -> "progress came $steps times for 4 items"
            else -> null
        }
        root.deleteRecursively()
        result
    }
}

/** Starts a job and waits up to a minute for its result. */
private fun runJob(engine: com.lxlauncher.files.JobEngine, start: () -> Int): JobResult? {
    val done = ArrayBlockingQueue<JobResult>(4)
    var id = -1
    val listener = object : JobListener {
        override fun onProgress(progress: JobProgress) {}
        override fun onFinished(result: JobResult) {
            done.offer(result)
        }
    }
    engine.addListener(listener)
    try {
        id = start()
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (SystemClock.uptimeMillis() < deadline) {
            val r = done.poll(1, TimeUnit.SECONDS) ?: continue
            if (r.id == id) return r
        }
        return null
    } finally {
        engine.removeListener(listener)
    }
}

@Suppress("unused")
private fun FsNode.describe() = "$ref ($mime, $size)"
