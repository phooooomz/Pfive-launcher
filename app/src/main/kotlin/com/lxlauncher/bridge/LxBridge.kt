// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.provider.Settings
import com.lxlauncher.BuildConfig
import com.lxlauncher.embed.IEmbedService
import com.lxlauncher.embed.ShellHelper
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * LX's own way to the rights Android keeps for the shell, which LX windows need. Pairing once with
 * wireless debugging makes this phone's adbd trust LX's key and gives LX WRITE_SECURE_SETTINGS. From then on LX
 * switches wireless debugging on by itself when it needs to, starts lxdb through adb and switches it back off:
 * lxdb stays until the phone restarts, and it comes back to LX when LX restarts. Only adbd can start a process as
 * the shell on a phone without root, so this is the one moment LX needs a network; lxdb itself needs none.
 *
 * Android stops adbd when neither USB nor wireless debugging is on, and init then ends everything adbd started.
 * So with USB debugging off, wireless debugging has to stay on for as long as lxdb runs.
 *
 * One start tries what it can in the time EmbedClient gives it: every port wireless debugging announces, the
 * setting again when Android or another app switches it off midway, and lxdb again when it never reaches LX.
 * Runs on [main] except the socket work, which keeps to one background thread. [BridgeLog] keeps the steps.
 */
internal class LxBridge(private val app: Context, private val main: Handler, private val onChanged: () -> Unit,
                        private val onAttached: (IBinder) -> Unit) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-bridge").apply { isDaemon = true } }
    /** Lives as long as LX's process: the helper sees LX go and comes back to the next one. */
    private val lifeline = Binder()
    private var ports: AdbPorts? = null
    private var wifiNetworks = 0
    @Volatile
    private var launched = false
    /** adbd turned LX's key down during this start, and no port of it took the key since. */
    @Volatile
    private var refused = false
    private var helperUp = false
    /** How often each port of this start was closed when LX knocked. */
    private val closedTries = HashMap<Int, Int>()
    /** The port lxdb last started through, to start it there once more if it never reaches LX. */
    private var lastPort = 0
    private var relaunches = 0
    private var switchedBack = 0
    private var watchingWireless = false

    /** A start is under way; [abandon], [stop] or an attach ends it. */
    var starting = false
        private set

    /**
     * Android kept switching wireless debugging off as soon as LX switched it on: it does so on a Wi-Fi network it has
     * not been allowed on, and asks the owner first. Starting again makes no sense until something changes.
     */
    var networkRefused = false
        private set

    /**
     * Pairing went through. LX may not hold WRITE_SECURE_SETTINGS yet: the phone can freeze LX behind Android's
     * pairing page before the first session ran, and the next start finishes it while wireless debugging is on.
     */
    val ready: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    /** Wireless debugging needs a Wi-Fi network; it need not reach the internet. */
    val wifi: Boolean get() = wifiNetworks > 0

    fun watchWifi() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wifiChanged(1)
            override fun onLost(network: Network) = wifiChanged(-1)
        }, main)
    }

    private fun wifiChanged(by: Int) {
        wifiNetworks = (wifiNetworks + by).coerceAtLeast(0)
        // A new network may be one Android allows wireless debugging on.
        networkRefused = false
        BridgeLog.i(app, "wifi networks: $wifiNetworks")
        onChanged()
    }

    val usbDebuggingOff: Boolean get() = Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == 0

    fun markPaired() {
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
    }

    private fun maySwitchWireless() = app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---- starting the helper ----

    /** An lxdb started since the phone last booted, which may be on its way back to a new LX process. */
    private fun helperExpected() = prefs.getInt(KEY_BOOT, -1) == bootCount() && prefs.getString(KEY_TOKEN, null) != null

    private fun bootCount() = Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0)

    fun start() {
        if (starting) return
        starting = true
        launched = false
        refused = false
        networkRefused = false
        closedTries.clear()
        lastPort = 0
        relaunches = 0
        switchedBack = 0
        main.removeCallbacks(restoreLater)
        val expected = helperExpected()
        BridgeLog.i(app, "start: boot ${bootCount()}, wifi networks $wifiNetworks, wireless debugging ${wireless()}, helper expected $expected")
        main.postDelayed(launch, if (expected) HELPER_RETURN_MS else 0L)
    }

    private val launch = Runnable {
        watchWireless(true)
        switchWirelessOn()
        ports = AdbPorts(app, AdbPorts.CONNECT, ::portFound).also { it.start() }
    }

    /** This start is over; EmbedClient may begin another, so wireless debugging stays as it is meanwhile. */
    fun abandon() {
        main.removeCallbacks(launch)
        main.removeCallbacks(notBack)
        if (refused) {
            BridgeLog.w(app, "adbd no longer knows LX's key; pairing is needed again")
            prefs.edit().remove(KEY_PAIRED).apply()
        }
        starting = false
        ports?.stop()
        ports = null
        watchWireless(false)
    }

    /** No more starts for now: wireless debugging goes back to how LX found it. */
    fun stop() {
        abandon()
        main.removeCallbacks(restoreLater)
        restoreWireless()
    }

    private fun switchWirelessOn() {
        if (!maySwitchWireless() || wireless() == 1) return
        BridgeLog.i(app, "switching wireless debugging on")
        prefs.edit().putBoolean(KEY_SWITCHED_ON, true).apply()
        setWireless(1)
    }

    private fun restoreWireless() {
        if (!prefs.getBoolean(KEY_SWITCHED_ON, false)) return
        if (helperUp && usbDebuggingOff) {
            BridgeLog.i(app, "USB debugging is off, so wireless debugging stays on for lxdb")
            return
        }
        prefs.edit().remove(KEY_SWITCHED_ON).apply()
        BridgeLog.i(app, "switching wireless debugging off")
        setWireless(0)
    }

    /** Another app may have switched wireless debugging on at the same moment, as Shizuku does after a restart. */
    private val restoreLater = Runnable { if (!starting) restoreWireless() }

    private fun wireless() = Settings.Global.getInt(app.contentResolver, WIRELESS_DEBUGGING, 0)

    private fun setWireless(value: Int) {
        try {
            Settings.Global.putInt(app.contentResolver, WIRELESS_DEBUGGING, value)
        } catch (e: SecurityException) {
            BridgeLog.w(app, "wireless debugging not switched", e)
        }
    }

    private val wirelessObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = wirelessChanged()
    }

    private fun watchWireless(on: Boolean) {
        if (on == watchingWireless) return
        watchingWireless = on
        val uri = Settings.Global.getUriFor(WIRELESS_DEBUGGING)
        if (on) app.contentResolver.registerContentObserver(uri, false, wirelessObserver)
        else app.contentResolver.unregisterContentObserver(wirelessObserver)
    }

    /**
     * Android switches wireless debugging off by itself when the Wi-Fi is not fully up yet, as right after a restart, or
     * is one it has not been allowed on, and another app can switch it off too. LX switches it back on a few times.
     */
    private fun wirelessChanged() {
        val now = wireless()
        BridgeLog.i(app, "wireless debugging is now $now")
        if (!starting || launched || now == 1 || !maySwitchWireless()) return
        if (switchedBack >= SWITCH_BACK_TIMES) {
            networkRefused = true
            BridgeLog.w(app, "Android keeps switching wireless debugging off on this network")
            return
        }
        switchedBack++
        prefs.edit().putBoolean(KEY_SWITCHED_ON, true).apply()
        main.postDelayed({ if (starting && !launched && wireless() == 0) setWireless(1) }, SWITCH_BACK_MS)
    }

    /** One announced port, on the main thread. A port closed too often is an old one, announced before a switch. */
    private fun portFound(port: Int) {
        if (!starting || launched || (closedTries[port] ?: 0) >= PORT_TRIES) return
        worker.execute { launchOn(port) }
    }

    private fun portClosed(port: Int) {
        val tries = (closedTries[port] ?: 0) + 1
        closedTries[port] = tries
        if (!starting || launched) return
        // adbd may announce a port a moment before it listens there.
        if (tries < PORT_TRIES) main.postDelayed({ portFound(port) }, PORT_RETRY_MS)
        else ports?.refresh()
    }

    /** One port on the loopback address: adbd's own, or closed. Runs on [worker]. */
    private fun launchOn(port: Int) {
        if (launched || !starting) return
        val token = ShellHelper.newToken()
        try {
            AdbLink(port, AdbKeys.get(app)).use { link ->
                if (!maySwitchWireless()) BridgeLog.i(app, "grant: ${link.shell(GRANT).trim()}")
                prefs.edit().putString(KEY_TOKEN, token).putInt(KEY_BOOT, bootCount()).apply()
                val apk = app.applicationInfo.sourceDir
                val library = ShellHelper.library(apk, abi(), app.applicationInfo.nativeLibraryDir)
                link.shell(ShellHelper.command(apk, library, token, Process.myUid() / PER_USER_RANGE))
            }
            launched = true
            // A session on this port proves adbd still knows LX's key. A TLS error on another port of the same start
            // (a connection reset mid-handshake while Android switched wireless debugging) no longer drops the
            // pairing when lxdb then attaches.
            refused = false
            BridgeLog.i(app, "lxdb launched through port $port")
            main.post {
                lastPort = port
                main.removeCallbacks(notBack)
                main.postDelayed(notBack, ATTACH_WAIT_MS)
            }
        } catch (e: SSLException) {
            BridgeLog.w(app, "adbd on port $port turned LX down", e)
            if (!launched) refused = true
        } catch (e: IOException) {
            BridgeLog.i(app, "port $port: $e")
            main.post { portClosed(port) }
        } catch (e: GeneralSecurityException) {
            BridgeLog.w(app, "LX's adb key is unusable", e)
        } catch (e: RuntimeException) {
            // The key store can throw ProviderException; uncaught here it ended this thread and LX with it.
            BridgeLog.w(app, "port $port could not be used", e)
        }
    }

    /** lxdb started but never reached LX: it ended before it could. It is started once more. */
    private val notBack = Runnable {
        if (!starting || helperUp) return@Runnable
        BridgeLog.w(app, "lxdb did not reach LX within $ATTACH_WAIT_MS ms")
        if (relaunches >= RELAUNCHES) return@Runnable
        relaunches++
        launched = false
        portFound(lastPort)
    }

    /** The ABI of the code in LX's APK that this phone runs. LX ships 64-bit code only. */
    private fun abi() = Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it in APK_ABIS } ?: APK_ABIS.first()

    // ---- lxdb's way back ----

    /** From EmbedBinderProvider, on a binder thread: lxdb's binder, a request to end it, or a refusal (null). */
    fun attach(uid: Int, token: String?, binder: IBinder?, apk: String?): Bundle? {
        if (!ShellHelper.trustedCaller(uid) || binder == null || token == null) return null
        val want = prefs.getString(KEY_TOKEN, null) ?: return null
        if (!MessageDigest.isEqual(want.toByteArray(), token.toByteArray())) return null
        val descriptor = try {
            binder.interfaceDescriptor
        } catch (e: RemoteException) {
            null
        }
        if (descriptor != IEmbedService.DESCRIPTOR) return null
        // An lxdb from before an update runs the old LX's code against the new one. Refused, it ends.
        if (apk != app.applicationInfo.sourceDir) return null
        main.post {
            BridgeLog.i(app, "lxdb attached")
            helperUp = true
            abandon()
            main.postDelayed(restoreLater, RESTORE_AFTER_MS)
            onAttached(binder)
        }
        return Bundle().apply { putBinder(ShellHelper.KEY_LX, lifeline) }
    }

    fun helperLost() {
        helperUp = false
        BridgeLog.i(app, "lxdb ended")
    }

    /** Whoever turned wireless debugging on for the pairing wants it off afterwards, as it was. */
    fun switchWirelessOffLater() {
        if (wireless() == 0) prefs.edit().putBoolean(KEY_SWITCHED_ON, true).apply()
    }

    companion object {
        /** Settings.Global.ADB_WIFI_ENABLED, hidden. */
        const val WIRELESS_DEBUGGING = "adb_wifi_enabled"
        private const val PREFS = "lx_bridge"
        private const val KEY_PAIRED = "paired"
        private const val KEY_TOKEN = "token"
        private const val KEY_BOOT = "boot"
        private const val KEY_SWITCHED_ON = "wireless_switched_on"
        private const val GRANT = "pm grant ${BuildConfig.APPLICATION_ID} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        private const val PER_USER_RANGE = 100000
        private val APK_ABIS = listOf("arm64-v8a", "x86_64")
        /** How long an lxdb of this boot gets to come back to a new LX process before LX starts another. */
        private const val HELPER_RETURN_MS = 3_000L
        private const val PORT_RETRY_MS = 1_000L
        private const val PORT_TRIES = 3
        /** lxdb reaches LX in about 20 ms; a busy phone right after a restart takes longer, but not this long. */
        private const val ATTACH_WAIT_MS = 6_000L
        private const val RELAUNCHES = 2
        private const val SWITCH_BACK_MS = 1_500L
        private const val SWITCH_BACK_TIMES = 3
        private const val RESTORE_AFTER_MS = 10_000L
    }
}
