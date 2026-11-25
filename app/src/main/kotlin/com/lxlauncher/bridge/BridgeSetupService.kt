// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.bridge

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lxlauncher.embed.EmbedClient

/**
 * LX Bridge's first pairing without the user. Android gives apps no way to turn on developer options or wireless
 * debugging, or to read the pairing code, so this goes through Settings as a person would, after the user switched
 * this service on. It looks at Settings and System UI only (res/xml/bridge_setup_service.xml), works out the next
 * step from what is on the phone at that moment, so an interruption changes nothing, and switches itself off at the
 * end, so LX keeps no accessibility access. Where the phone asks for the owner's PIN, it waits for the owner.
 */
class BridgeSetupService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy { EmbedClient.get(this) }
    private val labels by lazy { SetupLabels(this) }
    private var startedAt = 0L
    private var buildTaps = 0
    private var lastOpened = 0L
    private var sentCode: String? = null
    private var finished = false

    private val step = object : Runnable {
        override fun run() {
            if (finished) return
            tick()
            main.postDelayed(this, STEP_MS)
        }
    }

    override fun onServiceConnected() {
        if (Build.VERSION.SDK_INT < EmbedClient.MIN_SDK) {
            // The manifest keeps this service off before Android 14; this catches a switch left on by an older build.
            finished = true
            disableSelf()
            return
        }
        startedAt = SystemClock.uptimeMillis()
        if (client.bridgeReady) return finish()
        Log.i(TAG, "setting LX Bridge up; labels missing: ${labels.missing()}")
        client.beginAutomaticPairing()
        main.post(step)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        finished = true
        main.removeCallbacks(step)
        super.onDestroy()
    }

    private fun tick() {
        if (client.bridgeReady) return finish()
        if (SystemClock.uptimeMillis() - startedAt > TIMEOUT_MS) {
            Log.w(TAG, "setup ran out of time")
            client.automaticPairingFailed()
            return finish()
        }
        val root = rootInActiveWindow
        when {
            root != null && answerNetworkQuestion(root) -> {}
            !developerOptionsOn() -> turnOnDeveloperOptions(root)
            !wirelessDebuggingOn() -> turnOnWirelessDebugging(root)
            else -> showPairingCode(root)
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacks(step)
        Log.i(TAG, "setup over; paired: ${client.bridgeReady}")
        performGlobalAction(GLOBAL_ACTION_HOME)
        disableSelf()
    }

    private fun developerOptionsOn() = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    private fun wirelessDebuggingOn() = Settings.Global.getInt(contentResolver, LxBridge.WIRELESS_DEBUGGING, 0) == 1

    /** Opens [intent] unless it was opened a moment ago: pages take a while to appear. */
    private fun open(intent: Intent) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOpened < REOPEN_MS) return
        lastOpened = now
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Log.w(TAG, "page did not open", it) }
    }

    /** Android's question about this Wi-Fi network; [SetupLabels.always] spares the owner it after each restart. */
    private fun answerNetworkQuestion(root: AccessibilityNodeInfo): Boolean {
        if (find(root, labels.networkQuestion) == null) return false
        find(root, labels.always)?.let { box -> if (!box.isChecked) click(box) }
        find(root, labels.allow)?.let(::click)
        return true
    }

    private fun turnOnDeveloperOptions(root: AccessibilityNodeInfo?) {
        // The last tap may bring up the owner's PIN, which is theirs to type.
        if (buildTaps >= BUILD_TAPS) return
        val row = root?.let { find(it, labels.buildNumber) }
        when {
            row != null -> if (click(row)) buildTaps++
            root != null && isOnSettings(root) && lastOpened != 0L && !scroll(root) -> {}
            else -> open(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
        }
    }

    private fun turnOnWirelessDebugging(root: AccessibilityNodeInfo?) {
        val switch = root?.let { find(it, labels.wirelessSwitch) }
        if (switch != null) click(switch) else open(BridgePairing.wirelessDebuggingPage())
    }

    private fun showPairingCode(root: AccessibilityNodeInfo?) {
        if (root == null) return open(BridgePairing.wirelessDebuggingPage())
        val code = root.findAccessibilityNodeInfosByViewId(CODE_VIEW).firstOrNull()?.text?.toString()?.filter(Char::isDigit)
        if (code != null && code.length == CODE_DIGITS) {
            if (code != sentCode) {
                sentCode = code
                client.pairingCode(code)
            }
            return
        }
        val row = find(root, labels.pairWithCode)
        if (row != null) click(row) else open(BridgePairing.wirelessDebuggingPage())
    }

    private fun isOnSettings(root: AccessibilityNodeInfo) = root.packageName == SetupLabels.SETTINGS

    private fun find(root: AccessibilityNodeInfo, label: String?): AccessibilityNodeInfo? =
        label?.let { text -> root.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString() == text } }

    /** Text sits inside the row that takes the tap. */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun scroll(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return false
    }

    private companion object {
        const val TAG = "LxBridge"
        const val STEP_MS = 700L
        const val REOPEN_MS = 3_000L
        /** Long enough for an owner who has to type a PIN. */
        const val TIMEOUT_MS = 120_000L
        const val BUILD_TAPS = 8
        const val CODE_DIGITS = 6
        const val CODE_VIEW = "${SetupLabels.SETTINGS}:id/pairing_code"
    }
}

/**
 * The words on the pages [BridgeSetupService] goes through, read from Settings and System UI themselves, so they fit
 * the phone's language and its maker's wording.
 */
internal class SetupLabels(context: Context) {
    private val settings = runCatching { context.createPackageContext(SETTINGS, 0) }.getOrNull()
    private val systemUi = runCatching { context.createPackageContext(SYSTEM_UI, 0) }.getOrNull()

    val buildNumber = text(settings, "build_number")
    val wirelessSwitch = text(settings, "wireless_debugging_main_switch_title")
    val pairWithCode = text(settings, "adb_pair_method_code_title")
    val networkQuestion = text(systemUi, "wifi_debugging_title")
    val always = text(systemUi, "wifi_debugging_always")
    val allow = text(systemUi, "wifi_debugging_allow")

    fun missing(): List<String> = listOf("build number" to buildNumber, "switch" to wirelessSwitch, "pair" to pairWithCode,
        "question" to networkQuestion, "always" to always, "allow" to allow).filter { it.second == null }.map { it.first }

    // Another app's resources can only be found by name.
    @SuppressLint("DiscouragedApi")
    private fun text(from: Context?, name: String): String? {
        val resources = from?.resources ?: return null
        val id = resources.getIdentifier(name, "string", from.packageName)
        return if (id == 0) null else resources.getString(id)
    }

    companion object {
        const val SETTINGS = "com.android.settings"
        private const val SYSTEM_UI = "com.android.systemui"
    }
}
