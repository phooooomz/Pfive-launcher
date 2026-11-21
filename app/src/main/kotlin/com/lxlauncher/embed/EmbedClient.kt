// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.lxlauncher.R
import com.lxlauncher.bridge.BridgePairing
import com.lxlauncher.bridge.LxBridge
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Whether LX frames for Android apps can work right now, and the one binding to lxdb, the daemon [LxBridge] starts.
 * Everything here runs on the main thread except binder calls, which go through [worker] so a slow system never stalls
 * a frame.
 */
class EmbedClient private constructor(private val app: Context) {
    /**
     * NOT_SET_UP: LX Bridge is not paired yet. NEEDS_WIFI: it is, and has to wait for Wi-Fi to start. READY: an app
     * can open now, though lxdb may still have to start.
     */
    enum class Status { UNSUPPORTED, NOT_SET_UP, NEEDS_WIFI, READY }

    fun interface Listener {
        fun onEmbedStatus(status: Status)
    }

    /** What happens when the user opens an app. */
    enum class Route { LX_WINDOW, ANDROID_WINDOW }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** Runs every binder call to lxdb, one at a time and in order. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-embed").apply { isDaemon = true } }

    var status: Status = Status.UNSUPPORTED
        private set

    @Volatile
    private var service: IEmbedService? = null
    private val waiting = ArrayList<(IEmbedService?) -> Unit>()
    /** Live embedded windows by LX window id, so a rebuilt window takes over its running display. */
    internal val sessions = HashMap<Int, EmbedSession>()

    private val bridge = LxBridge(app, main, ::networkChanged, ::helperAttached)
    private val pairing = BridgePairing(app, main, ::paired)

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** Reads the state again and tells listeners when it changed. Windows waiting for lxdb hear it when it cannot come. */
    fun refresh() {
        val now = probe()
        if (now == status) return
        Log.i(TAG, "status $status -> $now")
        status = now
        if (now != Status.READY) {
            main.removeCallbacks(retry)
            flush(null)
        }
        tell()
    }

    private fun tell() {
        for (l in listeners.toList()) l.onEmbedStatus(status)
    }

    /** lxdb runs and answers. Status READY alone does not say so: it also means lxdb can be started. */
    val serviceRunning: Boolean get() = service != null

    /** Starts of lxdb that failed in a row since it last ran; windows waiting for it say that LX keeps trying. */
    var failedStarts = 0
        private set

    /** LX stopped trying: Android does not allow wireless debugging on this Wi-Fi until the owner says so. */
    val bridgeRefused: Boolean get() = bridge.networkRefused

    private fun probe(): Status = when {
        Build.VERSION.SDK_INT < MIN_SDK -> Status.UNSUPPORTED
        service != null -> Status.READY
        !bridge.ready -> Status.NOT_SET_UP
        bridge.wifi -> Status.READY
        else -> Status.NEEDS_WIFI
    }

    // ---- LX Bridge ----

    /** lxdb runs and USB debugging is off, so wireless debugging has to stay on for it. */
    val wirelessKeptOn: Boolean get() = service != null && bridge.usbDebuggingOff

    val wifiConnected: Boolean get() = bridge.wifi

    /** Pairing went through; LX may still be on its way to lxdb. */
    val bridgeReady: Boolean get() = bridge.ready

    /** Asks for the pairing code in a notification. The page showing the code is Android's. */
    fun beginPairing() = pairing.begin()

    /** BridgeSetupService reads the code off Android's page and hands it to [pairingCode]. */
    internal fun beginAutomaticPairing() {
        bridge.switchWirelessOffLater()
        pairing.beginSilently()
    }

    internal fun automaticPairingFailed() = pairing.automaticFailed()

    /** From the notification or BridgeSetupService. */
    internal fun pairingCode(code: String) = pairing.answered(code)

    private fun paired() {
        bridge.markPaired()
        startBridge()
    }

    /** LX is in front. This phone may have frozen it mid-start, which runs out the start's time limit. */
    fun onForeground() {
        inFront = true
        bridgeChanged()
    }

    /** Another app is in front. A start under way finishes; no new one begins until LX is back. */
    fun onBackground() {
        inFront = false
        main.removeCallbacks(retry)
        if (!bridge.starting && service == null) bridge.stop()
    }

    private var inFront = false

    /** Starts that ran out of time while no window waited for lxdb, since the network last changed. */
    private var idleFailures = 0

    /** Wi-Fi came or went: a new network may be one Android allows, so it gets a fresh round of tries. */
    private fun networkChanged() {
        idleFailures = 0
        bridgeChanged()
    }

    private fun bridgeChanged() {
        refresh()
        keepBridgeUp()
    }

    /**
     * lxdb is ready before anyone asks, so an app opens at once after a restart too. Each start can take 25 seconds of
     * settings writes, service discovery and TLS, so it only happens while LX is in front; a Wi-Fi change while another
     * app is in front waits for [onForeground]. With no window waiting, LX stops after [IDLE_STARTS] failed starts until
     * the network changes or a window asks ([withService]); windows that wait keep it trying.
     */
    private fun keepBridgeUp() {
        if (!inFront) return
        if (idleFailures >= IDLE_STARTS && waiting.isEmpty()) return
        if (status == Status.READY && bridge.ready && service == null && !bridge.starting) startBridge()
    }

    private fun startBridge() {
        main.removeCallbacks(retry)
        main.removeCallbacks(bridgeTimeout)
        main.postDelayed(bridgeTimeout, BRIDGE_TIMEOUT_MS)
        bridge.start()
    }

    /**
     * One start ran out of time. Right after a restart that is common: the phone is busy, Wi-Fi is still settling and
     * other apps switch wireless debugging too. LX tries again, each time a little later, for as long as it is in front;
     * windows keep waiting instead of giving up after the first try. Only a Wi-Fi Android refuses stops it.
     */
    private val bridgeTimeout = Runnable {
        failedStarts++
        if (waiting.isEmpty()) idleFailures++
        Log.w(TAG, "LX Bridge did not bring lxdb up in $BRIDGE_TIMEOUT_MS ms (failed starts: $failedStarts)")
        bridge.abandon()
        refresh()
        when {
            // refresh() has already told the waiting windows why.
            status != Status.READY -> bridge.stop()
            bridge.networkRefused -> {
                bridge.stop()
                flush(null)
            }
            inFront -> main.postDelayed(retry, retryDelay(failedStarts))
            // The windows keep waiting, unseen; onForeground starts again.
            else -> bridge.stop()
        }
        tell()
    }

    private val retry = Runnable { keepBridgeUp() }

    /** lxdb reached LX, now or after LX restarted. */
    private fun helperAttached(binder: IBinder) {
        main.removeCallbacks(bridgeTimeout)
        main.removeCallbacks(retry)
        val s = IEmbedService.Stub.asInterface(binder)
        if (!binder.pingBinder() || !linkDeath(binder, s)) {
            refresh()
            flush(null)
            return
        }
        Log.i(TAG, "lxdb attached")
        service = s
        failedStarts = 0
        idleFailures = 0
        refresh()
        flush(s)
        // Windows that gave up or lost their app open it again; READY did not change, so they hear it here.
        tell()
    }

    /**
     * Runs [command] as the shell user, as `adb shell` does, through lxdb. This blocks, so it stays off the main thread.
     * Null while lxdb is not running; it needs no network or cable, but it does need lxdb.
     */
    internal fun shell(command: String, input: ByteArray = ByteArray(0), timeoutMs: Int = SHELL_TIMEOUT_MS): EmbedProtocol.ShellResult? {
        val running = service ?: return null
        return try {
            EmbedProtocol.shellResult(running.exec(command, input, timeoutMs))
        } catch (e: android.os.RemoteException) {
            null
        }
    }

    // ---- routing ----

    fun alwaysAndroid(key: String): Boolean = key in alwaysAndroidKeys()

    fun alwaysAndroidKeys(): Set<String> = prefs.getStringSet(KEY_ALWAYS_ANDROID, null).orEmpty()

    fun setAlwaysAndroid(key: String, on: Boolean) {
        val keys = alwaysAndroidKeys().toMutableSet()
        if (on) keys += key else keys -= key
        prefs.edit().putStringSet(KEY_ALWAYS_ANDROID, keys).apply()
    }

    // ---- the service ----

    /**
     * Hands [onReady] the service on the main thread, starting lxdb first when needed; null when it cannot run. The
     * returned function stops waiting, for a window that closes first: a waiter left behind kept LX starting lxdb, and
     * switching wireless debugging, for a window that no longer existed, and held on to that window's views.
     */
    fun withService(onReady: (IEmbedService?) -> Unit): () -> Unit {
        val live = service
        if (live != null && live.asBinder().pingBinder()) {
            onReady(live)
            return {}
        }
        service = null
        waiting += onReady
        val stop = { waiting.remove(onReady); Unit }
        if (bridge.starting) return stop
        refresh()
        // A person asked, so a start waiting out its delay begins now.
        if (status == Status.READY) startBridge() else flush(null)
        return stop
    }

    private fun linkDeath(binder: IBinder, s: IEmbedService): Boolean = try {
        binder.linkToDeath({ main.post { if (service === s) serviceLost() } }, 0)
        true
    } catch (e: android.os.RemoteException) {
        false
    }

    private fun flush(s: IEmbedService?) {
        val list = waiting.toList()
        waiting.clear()
        list.forEach { it(s) }
    }

    /** lxdb ended; its displays, and the apps on them, are gone. */
    private fun serviceLost() {
        Log.i(TAG, "service lost")
        service = null
        bridge.helperLost()
        flush(null)
        for (s in sessions.values.toList()) s.serviceLost()
        refresh()
        keepBridgeUp()
    }

    /** Called by [EmbedBinderProvider] on a binder thread. */
    internal fun helperAttach(uid: Int, token: String?, binder: IBinder?, apk: String?): Bundle? = bridge.attach(uid, token, binder, apk)

    internal fun register(s: EmbedSession) {
        sessions[s.windowId] = s
    }

    internal fun unregister(s: EmbedSession) {
        if (sessions[s.windowId] === s) sessions.remove(s.windowId)
    }

    // Last in the class: a binder thread can make this object while listeners fire on the main thread.
    init {
        if (Build.VERSION.SDK_INT >= MIN_SDK) bridge.watchWifi()
        status = probe()
        main.post(::keepBridgeUp)
    }

    companion object {
        private const val TAG = "LxEmbed"
        private const val PREFS = "lx_embed"
        private const val KEY_ALWAYS_ANDROID = "always_android"
        /** LX's framework describes each display to lxdb, and VirtualDisplayConfig is public from Android 14. */
        const val MIN_SDK = 34
        /** Switching wireless debugging on, finding its port and starting the helper; Android's own prompt can come first. */
        private const val BRIDGE_TIMEOUT_MS = 25_000L
        private const val RETRY_FIRST_MS = 3_000L
        private const val RETRY_LONGEST_MS = 30_000L
        /** Failed starts in a row, with no window waiting, before LX stops trying on its own. */
        private const val IDLE_STARTS = 3

        /** 3, 6, 12 and 24 seconds after the failed starts, then every 30 seconds. */
        fun retryDelay(failedStarts: Int): Long =
            (RETRY_FIRST_MS shl (failedStarts - 1).coerceIn(0, 4)).coerceAtMost(RETRY_LONGEST_MS)
        private const val SHELL_TIMEOUT_MS = 30_000
        /** The native core keeps window titles to 128 bytes; an app window keeps its app key there. */
        private const val MAX_TITLE_BYTES = 128

        // Holds the application context only, which lives as long as the process anyway.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: EmbedClient? = null

        fun get(context: Context): EmbedClient = instance ?: synchronized(this) {
            instance ?: EmbedClient(context.applicationContext).also { instance = it }
        }

        /**
         * Where an app opens. With the "lx" setting every app gets an LX window, also while lxdb cannot start
         * yet: the window says why, offers the way out, and opens the app once it can. Only a phone too old for LX
         * frames falls back to Android's window. Games keep the full screen they ask for and apps the user keeps in
         * Android windows stay there. A key too long to be stored as the window title could not reopen after a
         * restart, so that app keeps Android's window too.
         */
        fun route(windowFrame: String, status: Status, isGame: Boolean, alwaysAndroid: Boolean, key: String): Route = when {
            windowFrame != "lx" -> Route.ANDROID_WINDOW
            status == Status.UNSUPPORTED -> Route.ANDROID_WINDOW
            isGame || alwaysAndroid -> Route.ANDROID_WINDOW
            key.toByteArray(Charsets.UTF_8).size > MAX_TITLE_BYTES -> Route.ANDROID_WINDOW
            else -> Route.LX_WINDOW
        }

        /** What LX says, once, when the LX frame is chosen and this Android cannot have it. */
        fun routeNotice(status: Status): Int? = if (status == Status.UNSUPPORTED) R.string.embed_route_old_android else null
    }
}
