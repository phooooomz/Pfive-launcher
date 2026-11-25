// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.service.quicksettings.TileService
import android.util.Log
import com.lxlauncher.R
import com.lxlauncher.embed.EmbedClient
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.Executors

/**
 * The one time the user has to do something: wireless debugging shows a code, and leaving that page closes the
 * code. So LX asks for it in a notification, which the user answers from on top of that page. [onPaired] runs on
 * [main] when the phone took LX's key.
 */
internal class BridgePairing(private val app: Context, private val main: Handler, private val onPaired: () -> Unit) {
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-bridge-pair").apply { isDaemon = true } }
    private val found = ArrayDeque<Int>()
    private var watcher: AdbPorts? = null

    private var active = false
    private var silent = false

    private val expire = Runnable { stop() }

    /** Shows the notification and waits for the phone's pairing page. */
    fun begin() {
        main.removeCallbacks(expire)
        main.postDelayed(expire, WAIT_MS)
        active = true
        found.clear()
        silent = false
        ask(R.string.bridge_code_body)
        watchPorts()
    }

    private fun watchPorts() {
        if (watcher == null) watcher = AdbPorts(app, AdbPorts.PAIRING) { port ->
            found.remove(port)
            found.addFirst(port)
        }.also { it.start() }
    }

    fun stop() {
        main.removeCallbacks(expire)
        active = false
        watcher?.stop()
        watcher = null
        notifications.cancel(NOTIFICATION)
    }

    /** The code as typed in the notification. */
    fun answered(code: String) {
        if (!active) return
        val digits = code.filter { it.isDigit() }
        if (!silent) ask(R.string.bridge_pairing, reply = false)
        main.postDelayed({ tryPorts(digits) }, if (found.isEmpty()) PORT_WAIT_MS else 0L)
    }

    private fun tryPorts(code: String) {
        val ports = found.toList()
        worker.execute {
            val result = ports.firstNotNullOfOrNull { pair(it, code) }
            main.post {
                when (result) {
                    true -> {
                        stop()
                        ask(R.string.bridge_connected, reply = false)
                        onPaired()
                    }
                    false -> if (!silent) ask(R.string.bridge_wrong_code)
                    null -> if (!silent) ask(R.string.bridge_no_page)
                }
            }
        }
    }

    /** Null when that port is no pairing service any more. */
    private fun pair(port: Int, code: String): Boolean? = try {
        AdbPairing.pair(port, code, AdbKeys.get(app))
    } catch (e: IOException) {
        Log.i(TAG, "pairing port $port: $e")
        null
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "pairing failed", e)
        null
    } catch (e: RuntimeException) {
        // The key store can throw ProviderException; uncaught it ended the pairing thread and LX with it.
        Log.w(TAG, "pairing failed", e)
        null
    }

    /** LX types the code itself, so nobody needs the notification that asks for it. */
    fun beginSilently() {
        main.removeCallbacks(expire)
        main.postDelayed(expire, WAIT_MS)
        active = true
        silent = true
        found.clear()
        watchPorts()
    }

    fun automaticFailed() {
        stop()
        ask(R.string.bridge_setup_failed, reply = false)
    }

    private fun ask(body: Int, reply: Boolean = true) {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.bridge_name), NotificationManager.IMPORTANCE_HIGH))
        val text = app.getString(body)
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_usb)
            .setContentTitle(app.getString(R.string.bridge_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        if (reply) {
            // Android writes the typed code into this intent, so it has to stay mutable; it names LX's own receiver.
            val intent = PendingIntent.getBroadcast(app, 0, Intent(app, BridgeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(KEY_CODE).setLabel(app.getString(R.string.bridge_code_hint)).build()
            builder.addAction(Notification.Action.Builder(null, app.getString(R.string.bridge_code_action), intent).addRemoteInput(input).build())
        }
        notifications.notify(NOTIFICATION, builder.build())
    }

    companion object {
        const val KEY_CODE = "code"

        /** The Quick Settings tile's own way in opens the page itself. Where it fails, Android's list is one scroll away. */
        fun wirelessDebuggingPage(): Intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
            .setClassName(SetupLabels.SETTINGS, "${SetupLabels.SETTINGS}.Settings\$DevelopmentSettingsActivity")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(SetupLabels.SETTINGS,
                "${SetupLabels.SETTINGS}.development.qstile.DevelopmentTiles\$WirelessDebugging"))

        private const val TAG = "LxBridge"
        private const val CHANNEL = "lx_bridge"
        private const val NOTIFICATION = 7301
        private const val WAIT_MS = 600_000L
        /** A code typed before the phone's pairing service was seen gets this long to show up. */
        private const val PORT_WAIT_MS = 4_000L
    }
}

/** The answer to [BridgePairing]'s notification. */
class BridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(BridgePairing.KEY_CODE) ?: return
        EmbedClient.get(context).pairingCode(code.toString())
    }
}
