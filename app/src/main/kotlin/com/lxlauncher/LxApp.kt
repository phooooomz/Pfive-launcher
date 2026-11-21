// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher

import android.app.Application
import android.content.res.Configuration
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.core.LauncherModel
import com.lxlauncher.core.NativeCore
import com.lxlauncher.ui.theme.Wallpapers
import com.lxlauncher.widgets.AndroidWidgets

/**
 * Process-wide state. The native engine lives here, not in the activity, so
 * rotation and activity recreation never lose layout state.
 */
class LxApp : Application() {
    lateinit var model: LauncherModel
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var wallpapers: Wallpapers
        private set
    lateinit var appWidgets: AndroidWidgets
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds log where a leaked stream, cursor or descriptor was opened.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects().detectLeakedRegistrationObjects().penaltyLog().build())
        }
        val started = android.os.SystemClock.uptimeMillis()
        model = LauncherModel(this)
        val names = Array(4) { getString(R.string.workspace_default_name, it + 1) }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        model.load(names, getString(R.string.tool_welcome), landscape)
        android.util.Log.i("LxPerf", "layout loaded in ${android.os.SystemClock.uptimeMillis() - started} ms " +
            "(${model.snapshot.windows.size} windows, ${model.snapshot.widgets.size} widgets)")
        apps = AppRepository(this)
        apps.onPackageEvent = { e ->
            if (e is AppRepository.PackageEvent.Removed) {
                // Uninstalled: drop shortcuts and pins. The core keeps everything else.
                model.edit { NativeCore.removePackage(it, e.packageName, e.userSerial) }
            }
        }
        apps.start()
        wallpapers = Wallpapers(this)
        appWidgets = AndroidWidgets(this)
        // A reload follows every package change; a new or removed app can bring or take widgets.
        apps.addListener { appWidgets.invalidate() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.d("LxPerf", "Android asks LX to trim memory, level $level")
        if (level >= TRIM_MEMORY_UI_HIDDEN) wallpapers.trim()
        apps.trimMemory(level)
    }
}
