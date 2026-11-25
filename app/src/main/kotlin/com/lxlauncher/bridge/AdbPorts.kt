// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Finds the ports wireless debugging picked: Android announces them on the network and does not otherwise tell
 * apps. Other phones on the same Wi-Fi are announced too, which does no harm: LX only ever tries a port on its
 * own loopback address, where another phone's port is simply closed. Everything here runs on the main thread.
 *
 * Wireless debugging takes a new port each time it is switched on, by LX, by another app or by Android, and the
 * announcement keeps its name. A one-time look-up kept answering with the port of the time before: LX knocked on a
 * closed port until it gave up, while adbd listened on the new one. On Android 14 and later every service found is
 * followed, so each new port arrives as it changes. Before that, one resolve runs at a time, as Android 13 asks,
 * and [refresh] looks again.
 */
internal class AdbPorts(context: Context, private val type: String, private val found: (Int) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val followed = ArrayList<Any>()
    private var resolving = false
    private var running = false
    private var discovering = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            main.post { serviceFound(info) }
        }

        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "discovery of $serviceType failed: $errorCode")
            // Right after a restart the network service may not take requests yet; it is asked again shortly.
            main.post {
                discovering = false
                if (running) main.postDelayed(restart, RETRY_DISCOVERY_MS)
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    private val restart = Runnable { if (running) refresh() }

    fun start() {
        if (running) return
        running = true
        discover()
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(restart)
        forget()
    }

    /** Looks again from scratch: every announced service is found and read anew. */
    fun refresh() {
        if (!running) return
        main.removeCallbacks(restart)
        forget()
        discover()
    }

    private fun discover() {
        discovering = true
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: IllegalArgumentException) {
            // The listener is still registered from a stop Android has not finished.
            discovering = false
            main.postDelayed(restart, RETRY_DISCOVERY_MS)
        }
    }

    private fun forget() {
        queue.clear()
        unfollowAll()
        if (discovering) {
            discovering = false
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private fun serviceFound(info: NsdServiceInfo) {
        if (!running) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            follow(info)
        } else {
            queue.addLast(info)
            next()
        }
    }

    private fun follow(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "${info.serviceName} cannot be followed: $errorCode")
            }

            override fun onServiceUpdated(updated: NsdServiceInfo) {
                if (running && updated.port > 0) found(updated.port)
            }

            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        try {
            nsd.registerServiceInfoCallback(info, { main.post(it) }, callback)
            followed += callback
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "${info.serviceName} cannot be followed", e)
        }
    }

    private fun unfollowAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        for (callback in followed) runCatching { nsd.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) }
        followed.clear()
    }

    private fun next() {
        if (resolving || !running) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        // Followed instead on Android 14 and later, where its replacement is.
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) = done(resolved.port)
            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) = done(0)
        })
    }

    private fun done(port: Int) {
        main.post {
            resolving = false
            if (running && port > 0) found(port)
            next()
        }
    }

    companion object {
        private const val TAG = "LxBridge"
        const val CONNECT = "_adb-tls-connect._tcp"
        const val PAIRING = "_adb-tls-pairing._tcp"
        private const val RETRY_DISCOVERY_MS = 2_000L
    }
}
