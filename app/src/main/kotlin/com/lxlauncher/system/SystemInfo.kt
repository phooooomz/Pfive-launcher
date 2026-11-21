// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock

/**
 * Readings that Android exposes to ordinary apps. Anything the platform does
 * not expose (system-wide CPU load, other apps' memory, GPU load) is absent
 * on purpose; the UI says so instead of estimating.
 */
object SystemInfo {
    data class Battery(val percent: Int, val charging: Boolean, val temperatureC: Float?, val known: Boolean)
    data class Memory(val availBytes: Long, val totalBytes: Long, val lowMemory: Boolean)
    data class Storage(val freeBytes: Long, val totalBytes: Long)
    data class Network(val connected: Boolean, val type: Type, val validated: Boolean, val downKbps: Int, val metered: Boolean) {
        enum class Type { NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER }
    }

    fun battery(ctx: Context): Battery {
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Battery(0, false, null, false)
        return battery(sticky)
    }

    /** From an ACTION_BATTERY_CHANGED intent a receiver already has, without asking Android again. */
    fun battery(sticky: Intent): Battery {
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val present = sticky.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        if (level < 0 || scale <= 0 || !present) return Battery(0, false, null, false)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(level * 100 / scale, charging, if (temp == Int.MIN_VALUE) null else temp / 10f, true)
    }

    fun memory(ctx: Context): Memory {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Memory(info.availMem, info.totalMem, info.lowMemory)
    }

    /**
     * LX's own memory (PSS) in KB. Android reads it from /proc/self/smaps, which takes tens of milliseconds, so it is
     * asked for only where it is shown (System Monitor), and never on the main thread.
     */
    fun ownPssKb(): Int = Debug.getPss().toInt()

    fun storage(): Storage {
        val stat = StatFs(Environment.getDataDirectory().path)
        return Storage(stat.availableBytes, stat.totalBytes)
    }

    fun network(ctx: Context): Network = try {
        readNetwork(ctx)
    } catch (e: SecurityException) {
        Network(false, Network.Type.NONE, false, 0, false)
    }

    private fun readNetwork(ctx: Context): Network {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork ?: return Network(false, Network.Type.NONE, false, 0, false)
        val caps = cm.getNetworkCapabilities(active) ?: return Network(false, Network.Type.NONE, false, 0, false)
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Network.Type.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Network.Type.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Network.Type.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Network.Type.ETHERNET
            else -> Network.Type.OTHER
        }
        return Network(
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), type,
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            caps.linkDownstreamBandwidthKbps,
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    /** CPU time this launcher process has used, which Android does expose. */
    fun ownCpuMillis(): Long = Process.getElapsedCpuTime()

    fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

    fun device(): List<Pair<String, String>> = listOf(
        // Many vendors already start the model with their name ("Infinix X6726B").
        "model" to if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
            else "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "abi" to Build.SUPPORTED_ABIS.joinToString(", "),
        "build" to Build.DISPLAY,
    )

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun formatDuration(ms: Long): String {
        val totalMin = ms / 60000
        val d = totalMin / (60 * 24)
        val h = (totalMin / 60) % 24
        val m = totalMin % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }
}
