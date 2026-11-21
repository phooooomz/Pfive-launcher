// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What LX Bridge did, kept on the phone. Android's own log on a busy phone holds about a minute, so a start that failed
 * right after a restart was gone before anyone could read it. The last [KEEP] lines stay in LX's no-backup folder, and
 * `run-as com.lxlauncher cat no_backup/lx-bridge.log` reads them. Lines hold states, ports and errors, nothing of the owner.
 */
internal object BridgeLog {
    private const val TAG = "LxBridge"
    private const val NAME = "lx-bridge.log"
    private const val KEEP = 300
    private const val TRIM_AT = 48 * 1024L
    /** Shared by the main thread and LX Bridge's threads; a SimpleDateFormat used that way mixes up its output. */
    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(context: Context, line: String) {
        Log.i(TAG, line)
        keep(context, "I $line")
    }

    fun w(context: Context, line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
        keep(context, "W $line" + (error?.let { ": $it" } ?: ""))
    }

    /** One thread writes the record, in order, so a caller on the main thread never waits for the disk. */
    private val writer = java.util.concurrent.ThreadPoolExecutor(1, 1, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue()) { r -> Thread(r, "lx-bridge-log").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }

    private fun keep(context: Context, line: String) {
        val app = context.applicationContext ?: context
        val stamped = "${stamp.format(LocalDateTime.now())} $line\n"
        writer.execute {
            try {
                val file = File(app.noBackupFilesDir, NAME)
                file.appendText(stamped)
                if (file.length() > TRIM_AT) file.writeText(file.readLines().takeLast(KEEP).joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                // A full disk or a missing folder costs the record, never the bridge.
            }
        }
    }
}
