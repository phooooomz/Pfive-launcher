// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit.ext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Open VSX (open-vsx.org, run by the Eclipse Foundation): the extension registry editors outside Microsoft's use,
 * with the same extensions under the same ids as VS Code's marketplace for most of them. Every call blocks; run it
 * on a worker thread. Only the search words, the extension asked about and Tedit's name reach the server.
 */
class OpenVsx(context: Context) {
    private val iconDir = File(context.cacheDir, "tedit-ext-icons")

    class Item(
        val namespace: String,
        val name: String,
        val displayName: String,
        val description: String,
        val version: String,
        val downloads: Long,
        val rating: Double?,
        val verified: Boolean,
        val iconUrl: String?,
        val deprecated: Boolean,
    ) {
        val id get() = "$namespace.$name".lowercase()
    }

    class Page(val items: List<Item>, val total: Int, val offset: Int)

    class Detail(
        val item: Item,
        val categories: List<String>,
        val license: String?,
        val repository: String?,
        val publishedBy: String?,
        val downloadUrl: String,
        val sha256Url: String?,
        val readmeUrl: String?,
        val timestamp: String?,
    )

    /** Search the registry; [category] is one of VS Code's categories or null for all. */
    fun search(query: String, category: String?, sort: String, offset: Int, size: Int = 30): Page {
        val q = buildString {
            append(API).append("/-/search?size=").append(size).append("&offset=").append(offset)
            if (query.isNotBlank()) append("&query=").append(enc(query.trim()))
            if (category != null) append("&category=").append(enc(category))
            // Relevance is the server's default and takes no order.
            if (sort != "relevance") append("&sortBy=").append(sort).append("&sortOrder=desc")
        }
        val o = JSONObject(text(q, 4 * 1024 * 1024))
        val arr = o.optJSONArray("extensions")
        val items = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it)?.let { e -> item(e) } }
        return Page(items, o.optInt("totalSize", items.size), o.optInt("offset", offset))
    }

    fun detail(namespace: String, name: String): Detail {
        val o = JSONObject(text("$API/${enc(namespace)}/${enc(name)}", 4 * 1024 * 1024))
        if (o.has("error")) throw IOException(o.optString("error"))
        val files = o.optJSONObject("files") ?: JSONObject()
        val cats = o.optJSONArray("categories")
        return Detail(
            item = item(o) ?: throw IOException("unreadable answer"),
            categories = (0 until (cats?.length() ?: 0)).map { cats!!.optString(it) },
            license = o.optString("license").takeIf { it.isNotEmpty() },
            repository = o.optString("repository").takeIf { it.isNotEmpty() },
            publishedBy = o.optJSONObject("publishedBy")?.optString("loginName")?.takeIf { it.isNotEmpty() },
            downloadUrl = files.optString("download").takeIf { it.startsWith("https://") } ?: throw IOException("no download for this extension"),
            sha256Url = files.optString("sha256").takeIf { it.startsWith("https://") },
            readmeUrl = files.optString("readme").takeIf { it.startsWith("https://") },
            timestamp = o.optString("timestamp").takeIf { it.isNotEmpty() },
        )
    }

    private fun item(e: JSONObject): Item? {
        val ns = e.optString("namespace").ifEmpty { return null }
        val name = e.optString("name").ifEmpty { return null }
        return Item(ns, name, e.optString("displayName").ifEmpty { name }, e.optString("description"), e.optString("version"),
            e.optLong("downloadCount"), if (e.has("averageRating") && !e.isNull("averageRating")) e.optDouble("averageRating") else null,
            e.optBoolean("verified"), e.optJSONObject("files")?.optString("icon")?.takeIf { it.startsWith("https://") }, e.optBoolean("deprecated"))
    }

    fun readme(d: Detail): String? = d.readmeUrl?.let { runCatching { text(it, 512 * 1024) }.getOrNull() }

    /** Downloads the .vsix and checks it against the registry's SHA-256 when the registry has one. */
    fun download(d: Detail, dest: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val expected = d.sha256Url?.let { runCatching { text(it, 4096).trim().substringBefore(' ').lowercase() }.getOrNull() }
        val md = MessageDigest.getInstance("SHA-256")
        open(d.downloadUrl).use { (c, input) ->
            val total = c.contentLengthLong
            if (total > MAX_VSIX) throw IOException("the extension is larger than ${MAX_VSIX / 1048576} MB")
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled()) throw IOException("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    done += n
                    if (done > MAX_VSIX) throw IOException("the extension is larger than ${MAX_VSIX / 1048576} MB")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    progress(done, total)
                }
            }
        }
        val sum = md.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && expected.length == 64 && expected != sum) {
            dest.delete()
            throw IOException("the download does not match Open VSX's checksum")
        }
        return dest
    }

    /** An icon already decoded this session, or null; cheap enough for the main thread. */
    fun cachedIcon(url: String): Bitmap? = memory.get(url)

    /** An extension's icon, from memory, then the icon cache, then the registry. */
    fun icon(url: String, sizePx: Int): Bitmap? {
        memory.get(url)?.let { return it }
        iconDir.mkdirs()
        val f = File(iconDir, sha1(url))
        val bytes = if (f.isFile) f.readBytes() else runCatching { bytes(url, 1024 * 1024).also { f.writeBytes(it) } }.getOrNull() ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= sizePx && opts.outHeight / (sample * 2) >= sizePx) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        memory.put(url, bmp)
        return bmp
    }

    // ---- HTTP ----

    private fun open(url: String): Pair<HttpURLConnection, InputStream> {
        var u = URL(url)
        repeat(5) {
            if (u.protocol != "https") throw IOException("refused a plain http address")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json, */*")
            val code = c.responseCode
            if (code in 300..399) {
                val to = c.getHeaderField("Location") ?: throw IOException("redirect without a location")
                c.disconnect()
                u = URL(u, to)
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("Open VSX answered HTTP $code")
            }
            return c to c.inputStream
        }
        throw IOException("too many redirects")
    }

    private fun bytes(url: String, max: Int): ByteArray = open(url).use { (_, input) ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("answer too large")
        }
        out.toByteArray()
    }

    private fun text(url: String, max: Int) = String(bytes(url, max), Charsets.UTF_8)

    private inline fun <R> Pair<HttpURLConnection, InputStream>.use(block: (Pair<HttpURLConnection, InputStream>) -> R): R =
        try { block(this) } finally { runCatching { second.close() }; first.disconnect() }

    companion object {
        const val API = "https://open-vsx.org/api"
        private const val USER_AGENT = "Tedit (LX-LAUNCHER)"
        private const val MAX_VSIX = 200L * 1024 * 1024
        private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }

        /** VS Code's extension categories, as the registries name them. */
        val CATEGORIES = listOf(
            "Programming Languages", "Snippets", "Linters", "Themes", "Formatters", "Debuggers", "Keymaps",
            "SCM Providers", "Extension Packs", "Language Packs", "Data Science", "Machine Learning", "Visualization",
            "Notebooks", "Education", "Testing", "Other",
        )

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
