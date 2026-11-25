// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.os.Parcel
import android.view.Surface

/**
 * LX's half of what it asks of lxdb. The fields of a VirtualDisplayConfig, an Intent or a set of activity options change with
 * every Android and only this phone's framework knows them, so LX writes those parcels here and lxdb passes them on unread.
 */
internal object EmbedProtocol {
    const val OK = 0
    const val ERR_ARGUMENT = -1
    const val ERR_LIMIT = -2
    const val ERR_DISPLAY = -3
    const val ERR_USER = -5
    const val ERR_DENIED = -6
    const val ERR_FAILED = -7
    /** This phone's Android does not have the calls lxdb writes; `lxdb doctor` names them. */
    const val ERR_UNSUPPORTED = -8

    const val MAX_SIZE = 8192
    private const val MAX_LABEL = 40
    private const val EXIT_BYTES = 4

    /**
     * As a launcher starts apps. Without MULTIPLE_TASK an app that already has a task on the main display moves that task
     * into the window (seen on Android 15), so there is one copy of the app, and closing the window closes it. singleTask
     * apps would move anyway.
     */
    private const val START_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

    /** ActivityOptions.KEY_LAUNCH_WINDOWING_MODE and WindowConfiguration.WINDOWING_MODE_FULLSCREEN; both hidden. */
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val WINDOWING_MODE_FULLSCREEN = 1

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*; most of them are hidden.
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    /**
     * The flags scrcpy uses for a new display, then fewer when Android refuses some, as a ROM can. Trusted and own-focus
     * displays keep their own focus and their own keyboard policy, so keys LX forwards reach the app while LX holds the
     * phone's focus.
     *
     * ROTATES_WITH_CONTENT is left out on purpose. With it, a portrait-only app in a wide window turned the display a
     * quarter turn and drew sideways into the window (Android 15, Infinix X6726B). Without it, Android letterboxes such an
     * app upright, as on a tablet.
     */
    fun flagChoices(): List<Int> {
        val base = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val trusted = base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        return listOf(trusted or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP, trusted, base)
    }

    fun displayLabel(name: String) = "lx-" + name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_LABEL).ifEmpty { "window" }

    /** The display id, or a negative ERR_* code once every set of flags has been refused. */
    fun create(service: IEmbedService, surface: Surface?, width: Int, height: Int, densityDpi: Int, name: String, client: IEmbedClient): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ERR_UNSUPPORTED
        var result = ERR_DISPLAY
        for (flags in flagChoices()) {
            val config = VirtualDisplayConfig.Builder(displayLabel(name), width, height, densityDpi).setFlags(flags).setSurface(surface).build()
            result = service.create(width, height, densityDpi, client, config)
            if (result >= 0 || (result != ERR_DENIED && result != ERR_DISPLAY)) return result
        }
        return result
    }

    fun launchIntent(component: ComponentName): ByteArray = marshalled {
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(START_FLAGS).writeToParcel(it, 0)
    }

    /**
     * Starts on [displayId] and fills it. Without the windowing mode, an app the user last had in an Android window brings its
     * freeform task along, at that window's place on the phone's screen: Settings sat at [16,184][704,1392] on a 656x1084
     * display, partly outside it (Android 15, Infinix X6726B).
     */
    fun launchOptions(displayId: Int): ByteArray = marshalled {
        ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle().apply { putInt(KEY_WINDOWING_MODE, WINDOWING_MODE_FULLSCREEN) }.writeToParcel(it, 0)
    }

    private inline fun marshalled(write: (Parcel) -> Unit): ByteArray {
        val parcel = Parcel.obtain()
        try {
            write(parcel)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    /** What a command run by lxdb left behind: its exit status and what it wrote. */
    class ShellResult(val exit: Int, val output: ByteArray) {
        val text: String get() = output.toString(Charsets.UTF_8)
    }

    /** lxdb's answer to exec: four bytes of exit status, then the output. Null for a reply of another shape. */
    fun shellResult(reply: ByteArray?): ShellResult? {
        if (reply == null || reply.size < EXIT_BYTES) return null
        val exit = reply.take(EXIT_BYTES).fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xff) }
        return ShellResult(exit, reply.copyOfRange(EXIT_BYTES, reply.size))
    }
}
