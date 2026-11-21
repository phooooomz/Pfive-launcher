// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import com.lxlauncher.system.DocumentStreams
import com.lxlauncher.ui.BitmapSample
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lxlauncher.R
import com.lxlauncher.core.NativeCore
import java.io.File
import java.util.concurrent.Executors

/** A rendered wallpaper and the downscaled, blurred copy glass panels sample. */
class WallpaperSet(val ref: String, val sharp: Bitmap?, val blurred: Bitmap?, val blurScale: Float, val failed: Boolean) {
    /** True when the picture is mostly light; desktop labels switch to dark ink over it. Unknown (system) counts as dark. */
    val light: Boolean = blurred?.let { averageLuminance(it) > 0.55 } ?: false

    /** Columns of [brightness]: the blurred copy in blocks of [CELL] by [CELL] pixels. */
    private val cellCols = blurred?.let { (it.width + CELL - 1) / CELL } ?: 0
    /**
     * The luminance of the brightest pixel in each block of the blurred copy, worked out once here, off
     * the main thread. A window then reads the same value wherever it is, instead of a sample grid that
     * moves with it and can step over a bright patch.
     */
    private val brightness: FloatArray? = blurred?.let { b ->
        val lut = DoubleArray(256) { Contrast.luminance(android.graphics.Color.rgb(it, it, it)) }
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val rows = (b.height + CELL - 1) / CELL
        val out = FloatArray(cellCols * rows)
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val c = px[y * b.width + x]
            val l = (0.2126 * lut[(c shr 16) and 0xFF] + 0.7152 * lut[(c shr 8) and 0xFF] + 0.0722 * lut[c and 0xFF]).toFloat()
            val i = (y / CELL) * cellCols + x / CELL
            if (l > out[i]) out[i] = l
        }
        out
    }

    /**
     * Luminance of the brightest part of the wallpaper under a rectangle in the root view's coordinates.
     * Text can sit over any part of a window, so a bright patch counts even when the rest is dark.
     * Null when there is no picture (the system wallpaper).
     */
    fun brightestUnder(left: Float, top: Float, right: Float, bottom: Float): Double? {
        val b = blurred ?: return null
        val map = brightness ?: return null
        val x0 = (left / blurScale).toInt().coerceIn(0, b.width - 1) / CELL
        val x1 = ((right / blurScale).toInt().coerceIn(1, b.width) - 1) / CELL
        val y0 = (top / blurScale).toInt().coerceIn(0, b.height - 1) / CELL
        val y1 = ((bottom / blurScale).toInt().coerceIn(1, b.height) - 1) / CELL
        var brightest = 0f
        for (cy in y0..maxOf(y0, y1)) for (cx in x0..maxOf(x0, x1)) brightest = maxOf(brightest, map[cy * cellCols + cx])
        return brightest.toDouble()
    }

    private companion object {
        /** Block size of the brightness map, in pixels of the blurred copy (16 screen pixels). */
        const val CELL = 4

        fun averageLuminance(b: Bitmap): Double {
            val stepX = maxOf(1, b.width / 24)
            val stepY = maxOf(1, b.height / 24)
            var sum = 0.0
            var n = 0
            for (y in 0 until b.height step stepY) for (x in 0 until b.width step stepX) {
                sum += Contrast.luminance(b.getPixel(x, y))
                n++
            }
            return if (n == 0) 0.0 else sum / n
        }
    }
}

/**
 * Produces wallpaper bitmaps off the main thread. "system" yields no bitmap:
 * the window then shows the system wallpaper, which apps cannot read, so glass
 * falls back to a denser tint.
 */
class Wallpapers(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-wallpaper").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = LinkedHashMap<String, WallpaperSet>()
    private var generation = 0
    /**
     * The sharp picture at a screen size, apart from its blurred copies, which are a sixteenth of its pixels:
     * moving the blur slider blurs again instead of decoding the photo again. Worker thread only.
     */
    private val sharpCache = LinkedHashMap<String, Pair<Bitmap?, Boolean>>()
    /** The cache key of the set handed out last, the one on screen. */
    private var shownKey: String? = null
    /** Decoded photos kept across process starts (see [WallpaperPixels]). Worker thread only, [prune] aside. */
    private val pixels = WallpaperPixels(File(context.cacheDir, "wallpaper-pixels"))
    /** Changes with every install, and with it the built-in photos LX ships. */
    private val installStamp by lazy { File(context.applicationInfo.sourceDir).lastModified().toString() }

    fun get(ref: String, colors: ThemeColors, width: Int, height: Int, blurRadiusPx: Int, onReady: (WallpaperSet) -> Unit) {
        if (width <= 0 || height <= 0) return
        val base = "${keyOf(ref, colors)}@${width}x$height"
        val key = "$base/$blurRadiusPx"
        cache[key]?.let {
            shownKey = key
            onReady(it)
            return
        }
        val gen = ++generation
        worker.execute {
            val started = SystemClock.uptimeMillis()
            val set = build(ref, colors, width, height, blurRadiusPx, base)
            Log.d("LxPerf", "wallpaper ${ref.substringBefore(':')} at ${width}x$height made in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                cache[key] = set
                // The current wallpaper in both orientations plus one spare; full-screen bitmaps are heavy at high resolutions.
                while (cache.size > 3) cache.remove(cache.keys.first())
                if (gen == generation) {
                    shownKey = key
                    onReady(set)
                }
            }
        }
    }

    /**
     * Lets go of every picture but the one on screen. That one stays because the activity draws it: dropping it from
     * here freed nothing, and LX decoded and blurred the same picture again the next time it laid its screen out.
     */
    fun trim() {
        val keep = shownKey
        cache.keys.retainAll { it == keep }
        val keepBase = keep?.substringBeforeLast('/')
        worker.execute { sharpCache.keys.retainAll { it == keepBase } }
    }

    private fun build(ref: String, colors: ThemeColors, width: Int, height: Int, blurRadiusPx: Int, base: String): WallpaperSet {
        if (ref == "system") return WallpaperSet(ref, null, null, 1f, false)
        val (cachedSharp, cachedFailed) = sharpCache[base] ?: (null to false)
        val (sharp, failed) = if (cachedSharp != null) cachedSharp to cachedFailed else sharp(ref, colors, width, height).also {
            sharpCache[base] = it.first to it.second
            // Both orientations of the current wallpaper.
            while (sharpCache.size > 2) sharpCache.remove(sharpCache.keys.first())
        }
        // Blur at quarter resolution: a radius of r there is 4r on screen.
        val scale = 4f
        val bw = maxOf(1, (width / scale).toInt())
        val bh = maxOf(1, (height / scale).toInt())
        val small = Bitmap.createScaledBitmap(sharp, bw, bh, true).let {
            if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
        NativeCore.blurBitmap(small, maxOf(1, blurRadiusPx / scale.toInt()), 3)
        if (failed) Log.w(TAG, "wallpaper ${ref.substringBefore(':')} could not be shown, using the theme wallpaper")
        return WallpaperSet(ref, sharp, small, scale, failed)
    }

    private fun sharp(ref: String, colors: ThemeColors, width: Int, height: Int): Pair<Bitmap, Boolean> {
        var failed = false
        fun fallback() = themed(colors, width, height).also { failed = true }
        val sharp: Bitmap = try {
            when {
                ref == THEMED -> themed(colors, width, height)
                ref.startsWith("builtin:") -> {
                    val name = ref.removePrefix("builtin:")
                    val asset = PHOTO_WALLPAPERS.firstOrNull { it.name == name }?.asset
                    if (asset != null) photo(ref, installStamp, width, height) {
                        decode({ context.assets.open(asset) }, width, height)
                    } ?: fallback()
                    else builtin(name, width, height)
                }
                ref.startsWith("color:") -> Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    NativeCore.fillBitmap(it, android.graphics.Color.parseColor(ref.removePrefix("color:")) and 0xFFFFFF)
                }
                ref.startsWith("local:") -> {
                    val f = localFile(ref.removePrefix("local:"))
                    if (f == null) Log.w(TAG, "wallpaper copy ${ref.removePrefix("local:")} is missing")
                    f?.let { photo(ref, "${it.lastModified()}:${it.length()}", width, height) {
                        decode({ it.inputStream() }, width, height)
                    } } ?: fallback()
                }
                ref.startsWith("content://") -> decode({ DocumentStreams.read(context, Uri.parse(ref)) }, width, height)
                    ?: fallback()
                else -> fallback()
            }
        } catch (e: Exception) {
            Log.w(TAG, "wallpaper $ref failed", e)
            fallback()
        }
        return sharp to failed
    }

    /**
     * A photo at screen size: its stored pixels while [stamp] says the photo is unchanged, otherwise [decode]d and
     * stored once this set is on its way. Worker thread.
     */
    private fun photo(ref: String, stamp: String, width: Int, height: Int, decode: () -> Bitmap?): Bitmap? {
        val key = "$ref|$stamp|${width}x$height"
        pixels.read(ref, key, width, height)?.let { return it }
        return decode()?.also { bmp -> worker.execute { pixels.write(ref, key, bmp) } }
    }

    private fun builtin(style: String, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val (name, seed) = when (style) {
            "rain" -> "rain" to 21
            "deep" -> "deep" to 1
            else -> "skyline" to 7
        }
        if (!NativeCore.renderWallpaper(bmp, name, seed)) drawThemed(bmp, Themes.plasma)
        return bmp
    }

    private val folder get() = File(context.filesDir, "wallpapers")
    /** Images being copied, or copied and not yet set as a wallpaper; written and read by [prune] on LauncherActivity's io thread. */
    private val pending: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun localFile(token: String): File? {
        if (!token.matches(Regex("[a-z0-9_.-]{1,48}"))) return null
        return File(folder, token).takeIf { it.isFile }
    }

    /**
     * Copies a picked image into LX's private storage and returns its
     * "local:" reference, or null when it cannot be read or is not an image.
     * The copy keeps working after the original is moved or its permission
     * is gone. Runs on the caller's thread; call it off the main thread.
     */
    fun importImage(uri: Uri): String? {
        folder.mkdirs()
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(20) + ".img"
        val tmp = File(folder, "$token.tmp")
        pending += token
        var imported = false
        try {
            DocumentStreams.read(context, uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) error("image larger than ${MAX_IMAGE_BYTES / (1024 * 1024)} MB")
                        out.write(buf, 0, n)
                    }
                }
            } ?: run {
                Log.w(TAG, "picked image could not be opened")
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "picked file is not a decodable image")
                return null
            }
            if (!tmp.renameTo(File(folder, token))) return null
            Log.i(TAG, "imported wallpaper image ${bounds.outWidth}x${bounds.outHeight} (${File(folder, token).length() / 1024} KB)")
            imported = true
            return "local:$token"
        } catch (e: Exception) {
            Log.w(TAG, "could not import wallpaper image", e)
            return null
        } finally {
            tmp.delete()
            if (!imported) pending -= token
        }
    }

    /** Ends the protection [importImage] gives its image, once the caller has set it as the wallpaper or given up. */
    fun release(ref: String) {
        pending -= ref.removePrefix("local:")
    }

    /**
     * Deletes copied images that no workspace refers to any more, and the stored pixels of every picture no workspace
     * shows. An image still being copied, or copied but not yet set as the wallpaper, is kept until [release].
     */
    fun prune(referenced: Collection<String>) {
        val keep = referenced.filter { it.startsWith("local:") }.map { it.removePrefix("local:") }.toSet()
        folder.listFiles()?.forEach {
            if (it.name !in keep && it.name !in pending && it.name.removeSuffix(".tmp") !in pending) it.delete()
        }
        val refs = referenced.toList()
        worker.execute { pixels.forget(refs) }
    }

    /** Decodes an image at roughly screen size and centre-crops it. */
    private fun decode(open: () -> java.io.InputStream?, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null; the size lands in bounds.
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "wallpaper image has no readable size")
            return null
        }
        // A 50 MP photo for a tablet screen would otherwise be decoded whole, about 200 MB.
        val sample = BitmapSample.sampleFor(bounds.outWidth, bounds.outHeight, width, height, DECODE_MAX_PIXELS)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val src = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val s = maxOf(width / src.width.toFloat(), height / src.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((width - src.width * s) / 2f, (height - src.height * s) / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    companion object {
        private const val TAG = "LxWallpaper"
        /** The default: a calm field in the active theme's colours. */
        const val THEMED = "builtin:theme"

        /** The theme wallpaper is a different picture for every colour theme. */
        fun keyOf(ref: String, colors: ThemeColors) = if (ref == THEMED) "$ref/${colors.id}" else ref

        fun themed(colors: ThemeColors, width: Int, height: Int): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { drawThemed(it, colors) }

        /**
         * A flat field of the theme's ground with a lower band one step toward
         * the surface colour, and on dark themes a sparse scatter of small
         * stars. No gradients and nothing bright where icons and widgets sit.
         */
        fun drawThemed(bmp: Bitmap, colors: ThemeColors) {
            val c = Canvas(bmp)
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            c.drawColor(colors.ground)
            val band = Paint().apply { color = Contrast.mix(colors.ground, colors.surface, 0.55f) }
            c.drawRect(0f, h * 0.72f, w, h, band)
            if (!colors.dark) return
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            val rng = java.util.Random(7L + colors.id.hashCode())
            val unit = minOf(w, h) / 360f
            repeat(((w * h) / (unit * unit * 900f)).toInt().coerceIn(40, 400)) { i ->
                val x = rng.nextFloat() * w
                val y = rng.nextFloat() * h * 0.7f
                val accent = i % 23 == 0
                dot.color = LxTheme.withAlpha(if (accent) colors.accent else colors.text, if (accent) 0.55f else 0.12f + rng.nextFloat() * 0.25f)
                c.drawCircle(x, y, unit * (if (accent) 1.1f else 0.5f + rng.nextFloat() * 0.6f), dot)
            }
        }
        private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
        /** 32 MP: a tablet wallpaper keeps full quality, a 50 MP photo is halved before it is decoded. */
        private const val DECODE_MAX_PIXELS = 32L * 1024 * 1024
        val builtinStyles = listOf("skyline", "rain", "deep")

        /** A photo wallpaper bundled in assets, shown in the picker by its appearance. */
        data class Photo(val name: String, val asset: String, val labelRes: Int)

        /** Bundled photo wallpapers, in the order the picker lists them. */
        val PHOTO_WALLPAPERS = listOf(
            Photo("neon_circuit", "wallpapers/neon_circuit.webp", R.string.wallpaper_neon_circuit),
            Photo("violet_blade", "wallpapers/violet_blade.webp", R.string.wallpaper_violet_blade),
            Photo("violet_grid", "wallpapers/violet_grid.webp", R.string.wallpaper_violet_grid),
            Photo("orange_core", "wallpapers/orange_core.webp", R.string.wallpaper_orange_core),
            Photo("ember_shards", "wallpapers/ember_shards.webp", R.string.wallpaper_ember_shards),
            Photo("prism_shards", "wallpapers/prism_shards.webp", R.string.wallpaper_prism_shards),
            Photo("pc_rig", "wallpapers/pc_rig.webp", R.string.wallpaper_pc_rig),
        )
    }
}
