// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.apps

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * App labels and install times from the last read of the app list, so the next start of LX's process does not open
 * every app's resources again. On the Infinix test phone that read took 1.2 to 1.5 s for 114 apps, and the icons on
 * the desktop and in the bar waited for it.
 *
 * An entry counts only while its app's APK is the same file: same path, size and time. An update installs the app
 * under a new path, or on Android 8 to 10 under one of two paths it takes in turn, so size and time decide there. The
 * whole file counts only for the [stamp] it was written with, the phone's languages and system build.
 */
internal class AppLabels(private val file: File) {
    /** [apk] is the APK's path, size and time of change, which identify the installed version. */
    data class Entry(val apk: String, val firstInstall: Long, val label: String)

    /** The entries written for [stamp], keyed by app key; none when the file is missing, damaged or from another stamp. */
    fun load(stamp: String): Map<String, Entry> = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION || input.readUTF() != stamp) return emptyMap()
            val count = input.readInt()
            // A damaged count asked for a map too large to make, on every start of LX.
            if (count !in 0..MAX_ENTRIES) {
                file.delete()
                return emptyMap()
            }
            HashMap<String, Entry>(count * 2).apply {
                repeat(count) { put(input.readUTF(), Entry(input.readUTF(), input.readLong(), input.readUTF())) }
            }
        }
    } catch (e: FileNotFoundException) {
        emptyMap()
    } catch (e: Exception) {
        Log.w(TAG, "saved app labels could not be read", e)
        file.delete()
        emptyMap()
    }

    fun save(stamp: String, entries: Map<String, Entry>) {
        val tmp = File(file.path + ".tmp")
        try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(stamp)
                out.writeInt(entries.size)
                for ((key, e) in entries) {
                    out.writeUTF(key)
                    out.writeUTF(e.apk)
                    out.writeLong(e.firstInstall)
                    out.writeUTF(e.label)
                }
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "app labels could not be saved", e)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "LxApps"
        /** 2: entries match the APK by path, size and time; 1 matched the path alone. */
        const val VERSION = 2
        /** Far more launchable apps than a phone holds, across all its profiles. */
        const val MAX_ENTRIES = 20_000
    }
}
