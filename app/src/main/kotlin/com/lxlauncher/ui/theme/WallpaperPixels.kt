// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Photo wallpapers kept as decoded pixels at the screen size they were decoded for, in LX's cache folder. Decoding the
 * photo again each time LX's process started took 350 to 650 ms on the Infinix test phone, and the desktop showed its
 * plain colour until it was done; the stored pixels load in a few milliseconds.
 *
 * Each file holds one picture at one size, with the key it was made for, and is used only while that key still
 * matches: the same picture, unchanged, at the same size. Only the newest [KEEP] files stay, the picture on screen in
 * both orientations. Not thread-safe: Wallpapers calls all of it from its one worker thread.
 */
internal class WallpaperPixels(private val dir: File) {

    /** The pixels stored for [ref] under [key], or null when there are none or they were made for something else. */
    fun read(ref: String, key: String, width: Int, height: Int): Bitmap? {
        val file = fileFor(ref, key)
        if (!file.isFile) return null
        val keyBytes = key.toByteArray()
        val start = (HEADER + keyBytes.size).toLong()
        val size = width.toLong() * height * 4
        try {
            RandomAccessFile(file, "r").use { raf ->
                val ch = raf.channel
                val head = ByteBuffer.allocate(start.toInt())
                if (ch.size() != start + size || ch.read(head, 0) != head.capacity()) return discard(file)
                head.flip()
                if (head.int != MAGIC || head.int != VERSION || head.int != width || head.int != height ||
                    head.int != keyBytes.size) return discard(file)
                val stored = ByteArray(keyBytes.size).also { head.get(it) }
                if (!stored.contentEquals(keyBytes)) return null
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(ch.map(FileChannel.MapMode.READ_ONLY, start, size))
                // Marked as just used, so [write] keeps it among the newest.
                file.setLastModified(System.currentTimeMillis())
                return bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "stored wallpaper pixels could not be read", e)
            return discard(file)
        }
    }

    /** Stores [bmp] for [ref] under [key] and drops all but the newest [KEEP] files. */
    fun write(ref: String, key: String, bmp: Bitmap) {
        if (bmp.config != Bitmap.Config.ARGB_8888) return
        val file = fileFor(ref, key)
        val tmp = File(dir, file.name + ".tmp")
        val keyBytes = key.toByteArray()
        try {
            dir.mkdirs()
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                val head = ByteBuffer.allocate(HEADER + keyBytes.size)
                    .putInt(MAGIC).putInt(VERSION).putInt(bmp.width).putInt(bmp.height).putInt(keyBytes.size).put(keyBytes)
                head.flip()
                // Written, not mapped: a mapped file is extended without its blocks, and with storage full the copy
                // into it ended LX with SIGBUS, again on every start. A write fails with an IOException instead.
                val pixels = ByteBuffer.allocateDirect(bmp.byteCount)
                bmp.copyPixelsToBuffer(pixels)
                pixels.flip()
                val ch = raf.channel
                while (head.hasRemaining()) ch.write(head)
                while (pixels.hasRemaining()) ch.write(pixels)
                ch.force(false)
            }
            if (!tmp.renameTo(file)) return run { tmp.delete() }
            dir.listFiles { f -> f.name.endsWith(SUFFIX) }
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "wallpaper pixels could not be stored", e)
            tmp.delete()
        }
    }

    /**
     * Deletes the pixels of every picture not in [refs]; the user's own photos do not outlive their wallpaper. Called
     * on the same thread as [write], so a write queued before it cannot bring a forgotten picture back.
     */
    fun forget(refs: Collection<String>) {
        val keep = refs.map { prefix(it) }.toSet()
        dir.listFiles()?.forEach { if (it.name.substringBefore('-') !in keep) it.delete() }
    }

    private fun fileFor(ref: String, key: String) = File(dir, "${prefix(ref)}-${hex(key.hashCode())}$SUFFIX")

    private fun prefix(ref: String) = hex(ref.hashCode())

    private fun hex(h: Int) = Integer.toHexString(h)

    private fun discard(file: File): Bitmap? {
        file.delete()
        return null
    }

    private companion object {
        const val TAG = "LxWallpaper"
        const val MAGIC = 0x4C585758  // "LXWX"
        const val VERSION = 1
        /** Magic, version, width, height and the key's length, four bytes each; the key follows. */
        const val HEADER = 20
        const val SUFFIX = ".px"
        const val KEEP = 2
    }
}
