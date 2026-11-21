// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.content.Context
import com.lxlauncher.core.NativeCore
import com.lxlauncher.files.Cancel
import com.lxlauncher.files.Conflict
import com.lxlauncher.files.ConflictChoice
import com.lxlauncher.files.ConflictDesk
import com.lxlauncher.tools.Duplicate
import com.lxlauncher.tools.Duplicates
import com.lxlauncher.ui.BitmapSample
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks for the fixes of the 7 November 2026 maintenance audit (docs/audits/maintenance-audit-2026-11-07.md).
 * Run alone with `-e only maintenance`.
 */
internal fun maintenanceChecks(ctx: Context, check: (String, () -> String?) -> Unit, onMain: (() -> Unit) -> Unit) {
    check("P0-1 the camera original stays, not the shorter Download copy") {
        val root = File(ctx.cacheDir, "cleanup-${System.nanoTime()}")
        val original = File(root, "DCIM/Camera/IMG_20261107_1.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(4096) { it.toByte() }) }
        val copy = File(root, "Download/a.jpg").apply { parentFile!!.mkdirs(); writeBytes(original.readBytes()) }
        // The copy is older and has the shorter path; the old rule kept it and deleted the original.
        copy.setLastModified(original.lastModified() - 60_000)
        val keeper = Duplicates.keeper(listOf(copy, original), root)
        root.deleteRecursively()
        if (keeper != original) "kept ${keeper.path}" else null
    }

    check("P0-1 a copy is deleted only while both files still match the scan") {
        val root = File(ctx.cacheDir, "cleanup-${System.nanoTime()}").apply { mkdirs() }
        val bytes = ByteArray(8192) { (it * 7).toByte() }
        fun scene(): Duplicate {
            val keep = File(root, "keep.png").apply { writeBytes(bytes) }
            val copy = File(root, "copy.png").apply { writeBytes(bytes) }
            return Duplicate(copy, keep, bytes.size.toLong(), Duplicates.sha256(keep)!!)
        }
        val intact = Duplicates.stillSafeToDelete(scene())
        val gone = scene().also { it.keeper.delete() }.let { Duplicates.stillSafeToDelete(it) }
        val edited = scene().also { it.keeper.writeBytes(bytes.copyOf().also { b -> b[0] = 1 }) }.let { Duplicates.stillSafeToDelete(it) }
        val changedCopy = scene().also { it.file.writeBytes(bytes.copyOf().also { b -> b[5] = 9 }) }.let { Duplicates.stillSafeToDelete(it) }
        val self = scene().let { Duplicates.stillSafeToDelete(Duplicate(it.keeper, it.keeper, it.size, it.hash)) }
        // A picture in the gallery's trash is a hidden file; keeping it would lose the picture when the trash empties.
        val trashed = scene().let {
            val hidden = File(root, ".trashed-1794000000-keep.png").apply { writeBytes(bytes) }
            Duplicates.stillSafeToDelete(Duplicate(it.file, hidden, it.size, it.hash))
        }
        root.deleteRecursively()
        when {
            !intact -> "an intact pair was refused"
            gone -> "deleted although the kept copy is gone"
            edited -> "deleted although the kept copy changed"
            changedCopy -> "deleted a copy that changed after the scan"
            self -> "a file counted as its own duplicate"
            trashed -> "deleted a copy while the kept one was a hidden file in the trash"
            else -> null
        }
    }

    check("P0-2 uninstalling from the work profile keeps the personal profile's shortcut") {
        val h = NativeCore.create()
        NativeCore.resetDefaults(h, arrayOf("A", "B", "C", "D"), "Welcome", false, false)
        val ws = JSONObject(NativeCore.snapshot(h)!!).getJSONArray("workspaces").getJSONObject(0).getInt("id")
        NativeCore.addShortcut(h, ws, "com.example.mail/com.example.mail.Main#0", 0, 0)
        NativeCore.addShortcut(h, ws, "com.example.mail/com.example.mail.Main#10", 1, 0)
        val removed = NativeCore.removePackage(h, "com.example.mail", 10)
        val shortcuts = JSONObject(NativeCore.snapshot(h)!!).getJSONArray("shortcuts")
        val keys = (0 until shortcuts.length()).map { shortcuts.getJSONObject(it).getString("app") }
        NativeCore.destroy(h)
        when {
            removed != 1 -> "removed $removed"
            keys != listOf("com.example.mail/com.example.mail.Main#0") -> "left $keys"
            else -> null
        }
    }

    check("P0-4 a tall screenshot decodes under the preview budget") {
        // 1080 x 25000 at full size is 108 MB; the old rule picked sample 1 for a 640 px preview.
        val s = BitmapSample.sampleFor(1080, 25_000, 640, 1, 4L * 1024 * 1024, 4096)
        val w = 1080 / s
        val h = 25_000 / s
        val thumb = BitmapSample.sampleFor(100_000, 200, 80, 80, 1024L * 1024, 2048)
        val photo = BitmapSample.sampleFor(8160, 6120, 1440, 3200, 32L * 1024 * 1024)
        val screen = BitmapSample.sampleFor(4000, 3000, 720, 1600)
        when {
            w.toLong() * h > 4L * 1024 * 1024 || maxOf(w, h) > 4096 -> "preview ${w}x$h at sample $s"
            (100_000 / thumb).toLong() * (200 / thumb) > 1024L * 1024 -> "thumbnail sample $thumb"
            (8160 / photo).toLong() * (6120 / photo) > 32L * 1024 * 1024 -> "photo sample $photo"
            screen != 1 -> "an ordinary photo for a phone screen now samples $screen, was 1"
            else -> null
        }
    }

    check("P0-6 a conflict question survives the window going away") {
        val conflict = Conflict("a.txt", false, false, 1, 2, 3, 4)
        val shownTo = ArrayBlockingQueue<String>(4)
        val answers = HashMap<String, (ConflictChoice, Boolean) -> Unit>()
        fun asker(name: String) = ConflictDesk.Asker { _, answer ->
            answers[name] = answer
            shownTo.offer(name)
            ({})
        }
        val first = asker("first")
        val second = asker("second")
        onMain { ConflictDesk.attach(first) }
        val result = AtomicReference<Pair<ConflictChoice, Boolean>?>()
        val job = Thread { result.set(ConflictDesk.resolve(conflict, Cancel())) }.apply { start() }
        val a = shownTo.poll(5, TimeUnit.SECONDS)
        // The window closes without an answer (a theme change does this), and a new one opens.
        onMain { ConflictDesk.detach(first) }
        Thread.sleep(400)
        val stillWaiting = job.isAlive
        onMain { ConflictDesk.attach(second) }
        val b = shownTo.poll(5, TimeUnit.SECONDS)
        // A late tap on the old sheet does not count.
        onMain { answers["first"]?.invoke(ConflictChoice.CANCEL, false) }
        Thread.sleep(300)
        val ignoredStale = job.isAlive
        onMain { answers["second"]?.invoke(ConflictChoice.KEEP_BOTH, true) }
        job.join(5_000)
        onMain { ConflictDesk.detach(second) }
        when {
            a != "first" -> "first window was not asked"
            !stillWaiting -> "job stopped waiting when the window closed: ${result.get()}"
            b != "second" -> "the next window was not asked"
            !ignoredStale -> "the old window's sheet answered: ${result.get()}"
            result.get() != (ConflictChoice.KEEP_BOTH to true) -> "answer ${result.get()}"
            else -> null
        }
    }

    check("P1-13 names sort exactly as before, without substrings") {
        // The comparator before the change, kept here as the reference.
        fun before(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val si = i
                    val sj = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val na = a.substring(si, i).trimStart('0')
                    val nb = b.substring(sj, j).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            val rest = (a.length - i) - (b.length - j)
            return if (rest != 0) rest else a.compareTo(b)
        }
        val random = java.util.Random(20261107)
        val alphabet = "ab0019Z. _-\u0661\u0662"
        fun name() = String(CharArray(1 + random.nextInt(12)) { alphabet[random.nextInt(alphabet.length)] })
        val fixed = listOf("file 2", "file 10", "file 002", "File 2", "a", "", "0", "00", "007", "7", "x10y2", "x10y10")
        val names = fixed + List(3000) { name() }
        var wrong: String? = null
        for (k in 0 until 20_000) {
            val a = names[random.nextInt(names.size)]
            val b = names[random.nextInt(names.size)]
            if (Integer.signum(com.lxlauncher.files.FmText.natural(a, b)) != Integer.signum(before(a, b))) {
                wrong = "'$a' vs '$b'"
                break
            }
        }
        wrong?.let { "differs on $it" }
    }

    check("P0-6 cancelling a waiting job ends the wait") {
        val cancel = Cancel()
        val result = AtomicReference<Pair<ConflictChoice, Boolean>?>()
        val job = Thread { result.set(ConflictDesk.resolve(Conflict("b.txt", false, false, 1, 1, 1, 1), cancel)) }.apply { start() }
        Thread.sleep(300)
        cancel.cancel()
        job.join(3_000)
        if (job.isAlive) "still waiting after cancel" else if (result.get()?.first != ConflictChoice.CANCEL) "answer ${result.get()}" else null
    }
}
