// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import android.view.InputDevice
import com.lxlauncher.R
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.files.AndroidStorage

/** How far a feature works on this device, from what the platform reports. */
enum class Support(val label: Int) {
    SUPPORTED(R.string.cap_supported),
    CONDITIONAL(R.string.cap_conditional),
    PERMISSION_REQUIRED(R.string.cap_permission_required),
    UNSUPPORTED(R.string.cap_unsupported),
    UNKNOWN(R.string.cap_unknown),
}

class Capability(val id: String, val label: Int, val support: Support, val detail: String)

/**
 * Checks what this device lets a launcher do, using public APIs only. Where
 * Android offers no way to ask (whether freeform windows were turned on in
 * Developer options, for one), the status says "conditional" and the detail
 * names what decides it. Nothing here is guessed from the device model.
 */
object DeviceCapabilities {
    private const val TAG = "LxCaps"

    fun probe(ctx: Context, apps: AppRepository): List<Capability> {
        val pm = ctx.packageManager
        val am = ctx.getSystemService(ActivityManager::class.java)
        fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)
        val list = mutableListOf<Capability>()

        list += Capability("lx_windows", R.string.cap_lx_windows, Support.SUPPORTED, s(R.string.cap_lx_windows_detail))
        list += when {
            pm.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ->
                Capability("app_windows", R.string.cap_app_windows, Support.SUPPORTED, s(R.string.cap_app_windows_yes))
            apps.freeformSwitchOn() ->
                Capability("app_windows", R.string.cap_app_windows, Support.SUPPORTED, s(R.string.cap_app_windows_switch))
            else -> Capability("app_windows", R.string.cap_app_windows, Support.CONDITIONAL, s(R.string.cap_app_windows_maybe))
        }
        list += if (am?.isLowRamDevice == true)
            Capability("split_screen", R.string.cap_split, Support.UNSUPPORTED, s(R.string.cap_split_low_ram))
        else Capability("split_screen", R.string.cap_split, Support.CONDITIONAL, s(R.string.cap_split_detail))
        list += if (pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
            Capability("pip", R.string.cap_pip, Support.CONDITIONAL, s(R.string.cap_pip_detail))
        else Capability("pip", R.string.cap_pip, Support.UNSUPPORTED, s(R.string.cap_pip_none))
        // lxdb needs what Android 14 made public; LX Bridge gives it the shell's rights.
        list += if (android.os.Build.VERSION.SDK_INT >= com.lxlauncher.embed.EmbedClient.MIN_SDK)
            Capability("embedding", R.string.cap_embedding, Support.CONDITIONAL, s(R.string.cap_embedding_detail))
        else Capability("embedding", R.string.cap_embedding, Support.UNSUPPORTED, s(R.string.cap_embedding_old))

        val home = HomeRole.isDefaultHome(ctx)
        list += Capability("home_role", R.string.cap_home, if (home) Support.SUPPORTED else Support.PERMISSION_REQUIRED,
            s(if (home) R.string.cap_home_yes else R.string.cap_home_no))
        list += if (apps.canReadShortcuts())
            Capability("app_shortcuts", R.string.cap_shortcuts, Support.SUPPORTED, s(R.string.cap_shortcuts_yes))
        else Capability("app_shortcuts", R.string.cap_shortcuts, Support.PERMISSION_REQUIRED, s(R.string.cap_shortcuts_no))
        list += if (pm.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS))
            Capability("app_widgets", R.string.cap_widgets, Support.SUPPORTED, s(R.string.cap_widgets_yes))
        else Capability("app_widgets", R.string.cap_widgets, Support.UNSUPPORTED, s(R.string.cap_widgets_no))

        val allFiles = AndroidStorage.allFilesAccess(ctx)
        val folders = ctx.contentResolver.persistedUriPermissions.count { it.isReadPermission }
        list += when {
            // Android 11 lists this under special app access; before that it is the app's Storage permission.
            allFiles -> Capability("folders", R.string.cap_folders, Support.SUPPORTED,
                s(if (Build.VERSION.SDK_INT >= 30) R.string.cap_folders_all else R.string.cap_folders_all_legacy))
            folders > 0 -> Capability("folders", R.string.cap_folders, Support.SUPPORTED,
                ctx.resources.getQuantityString(R.plurals.cap_folders_granted, folders, folders))
            else -> Capability("folders", R.string.cap_folders, Support.PERMISSION_REQUIRED, s(R.string.cap_folders_none))
        }
        val removable = try {
            ctx.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.count { it.isRemovable && it.state == Environment.MEDIA_MOUNTED } ?: 0
        } catch (e: Exception) {
            -1
        }
        list += when {
            removable > 0 -> Capability("removable", R.string.cap_removable, Support.SUPPORTED,
                ctx.resources.getQuantityString(if (allFiles) R.plurals.cap_removable_listed else R.plurals.cap_removable_mounted,
                    removable, removable))
            removable == 0 -> Capability("removable", R.string.cap_removable, Support.CONDITIONAL, s(R.string.cap_removable_none))
            else -> Capability("removable", R.string.cap_removable, Support.UNKNOWN, s(R.string.cap_removable_unknown))
        }
        list += if (Build.VERSION.SDK_INT >= 33)
            Capability("photo_picker", R.string.cap_photo_picker, Support.SUPPORTED, s(R.string.cap_photo_picker_yes))
        else Capability("photo_picker", R.string.cap_photo_picker, Support.CONDITIONAL, s(R.string.cap_photo_picker_old))

        list += Capability("metrics", R.string.cap_metrics, Support.SUPPORTED, s(R.string.cap_metrics_detail))
        list += Capability("cpu_load", R.string.cap_cpu, Support.UNSUPPORTED, s(R.string.cap_cpu_detail))

        val keyboard = ctx.resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
        val mouse = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { !it.isVirtual && it.supportsSource(InputDevice.SOURCE_MOUSE) } == true
        }
        list += if (keyboard || mouse)
            Capability("input", R.string.cap_input, Support.SUPPORTED, s(R.string.cap_input_yes,
                listOfNotNull(if (keyboard) s(R.string.cap_input_keyboard) else null, if (mouse) s(R.string.cap_input_mouse) else null)
                    .joinToString(", ")))
        else Capability("input", R.string.cap_input, Support.CONDITIONAL, s(R.string.cap_input_none))

        val cfg = ctx.resources.configuration
        list += Capability("display", R.string.cap_display, Support.SUPPORTED, s(R.string.cap_display_detail,
            cfg.screenWidthDp, cfg.screenHeightDp, "${ctx.resources.displayMetrics.densityDpi} dpi",
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        return list
    }

    /** One line per capability, so a test run or a bug report carries the device's answers. */
    fun log(list: List<Capability>) {
        for (c in list) Log.i(TAG, "${c.id}=${c.support.name} ${c.detail.replace('\n', ' ')}")
    }
}
