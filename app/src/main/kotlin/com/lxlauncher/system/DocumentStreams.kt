// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.system

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Streams for documents picked through the Storage Access Framework.
 *
 * For images, audio and video under shared storage, ExternalStorageProvider
 * hands the open to MediaProvider. On some Android 13 builds (seen on
 * Waydroid) that hand-off fails inside AppOpsService with a remote
 * NullPointerException because the caller's package is lost. Reading the
 * equivalent MediaStore item, which MediaProvider opens on LX's own behalf,
 * avoids the hand-off.
 */
object DocumentStreams {
    private const val TAG = "LxDocs"

    fun read(context: Context, uri: Uri): InputStream? {
        val resolver = context.contentResolver
        val direct = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (e: RuntimeException) {
            Log.w(TAG, "document open failed (${e.javaClass.simpleName}), trying the MediaStore item")
            null
        }
        if (direct != null) return ParcelFileDescriptor.AutoCloseInputStream(direct)
        if (Build.VERSION.SDK_INT < 29 || !DocumentsContract.isDocumentUri(context, uri)) return null
        val media = try {
            MediaStore.getMediaUri(context, uri)
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore lookup failed (${e.javaClass.simpleName})")
            null
        }
        if (media == null) {
            Log.w(TAG, "no readable MediaStore item for the document")
            return null
        }
        return resolver.openFileDescriptor(media, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
    }

    /** "wt" truncates, so a shorter document never keeps the old tail. */
    fun write(context: Context, uri: Uri): OutputStream? =
        context.contentResolver.openFileDescriptor(uri, "wt")?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
}
