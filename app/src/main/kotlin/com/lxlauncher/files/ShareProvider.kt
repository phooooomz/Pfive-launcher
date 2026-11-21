// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * Read-only grants for single local files ("Open with", "Share"). A Uri carries only a random
 * token; the path it stands for stays in this process and is forgotten when the process ends or
 * after [MAX_TOKENS] newer shares.
 */
class ShareProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val path = pathOf(uri) ?: return null
        val file = File(path)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { if (it == OpenableColumns.DISPLAY_NAME) file.name else file.length() }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? = pathOf(uri)?.let { FileTypes.mimeOf(File(it).name) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val path = pathOf(uri) ?: throw FileNotFoundException("unknown share")
        // lstat, not stat: a file swapped for a link after sharing must not open what the link points to.
        val before = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            throw FileNotFoundException("gone")
        }
        if (!OsConstants.S_ISREG(before.st_mode)) throw FileNotFoundException("not a file")
        val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        val after = try {
            Os.fstat(pfd.fileDescriptor)
        } catch (e: ErrnoException) {
            pfd.close()
            throw FileNotFoundException("gone")
        }
        // Swapped between the check and the open: refuse rather than hand out something else.
        if (after.st_dev != before.st_dev || after.st_ino != before.st_ino || !OsConstants.S_ISREG(after.st_mode)) {
            pfd.close()
            throw FileNotFoundException("changed")
        }
        return pfd
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val MAX_TOKENS = 256
        private val random = SecureRandom()

        /** Insertion order, so the oldest share goes first when the map is full. */
        private val tokens = object : LinkedHashMap<String, String>(32, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_TOKENS
        }

        /** A content Uri for the local file at [path]; grant it with FLAG_GRANT_READ_URI_PERMISSION. */
        fun uriFor(context: Context, path: String): Uri {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            synchronized(tokens) { tokens[token] = path }
            return Uri.Builder().scheme("content").authority(context.packageName + ".files").appendPath(token).build()
        }

        private fun pathOf(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.size != 1) return null
            return synchronized(tokens) { tokens[segments[0]] }
        }
    }
}
