// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.lxlauncher.ui.desktop.LxAppWidgetHost

/**
 * Hosts third-party Android widgets. RemoteViews render as real Android views
 * (AppWidgetHostView) inside the desktop; nothing is redrawn by the native core.
 */
class AndroidWidgets(private val context: Context) {
    private val manager = AppWidgetManager.getInstance(context)
    val host: AppWidgetHost = LxAppWidgetHost(context, HOST_ID)

    /** Widget waiting for the bind permission or configuration screen. */
    data class Pending(val appWidgetId: Int, val provider: ComponentName, val workspaceId: Int)

    var pending: Pending? = null
        private set

    fun start() = try {
        host.startListening()
    } catch (e: Exception) {
        Log.w(TAG, "startListening failed", e)
    }

    fun stop() = try {
        host.stopListening()
    } catch (e: Exception) {
        Log.w(TAG, "stopListening failed", e)
    }

    class Provider(val info: AppWidgetProviderInfo, val label: String, val appLabel: String?)

    @Volatile
    private var cached: List<Provider>? = null
    val hasCachedProviders: Boolean get() = cached != null

    /** Each placed widget's provider, asked once: three binder calls each time a workspace came back. Main thread. */
    private val infos = HashMap<Int, AppWidgetProviderInfo>()

    /** Drops the provider list and what is known of placed widgets; call it when installed apps change. */
    fun invalidate() {
        cached = null
        infos.clear()
    }

    /**
     * Installed widget providers, sorted by label. A label loads the provider
     * app's resources, and a phone can have dozens of providers, so the list
     * is built off the main thread and kept until apps change. [knownAppLabel]
     * supplies app names the launcher has already loaded.
     */
    fun providers(knownAppLabel: (String) -> String?): List<Provider> {
        cached?.let { return it }
        val started = android.os.SystemClock.elapsedRealtime()
        val pm = context.packageManager
        val list = manager.getInstalledProvidersForProfile(Process.myUserHandle()).map { p ->
            val pkg = p.provider.packageName
            val appLabel = knownAppLabel(pkg)
                ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            Provider(p, p.loadLabel(pm)?.toString().orEmpty(), appLabel)
        }.sortedBy { it.label.lowercase() }
        Log.i(TAG, "loaded ${list.size} widget providers in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        cached = list
        return list
    }

    fun info(appWidgetId: Int): AppWidgetProviderInfo? =
        infos[appWidgetId] ?: manager.getAppWidgetInfo(appWidgetId)?.also { infos[appWidgetId] = it }

    fun createView(appWidgetId: Int): AppWidgetHostView? {
        val info = info(appWidgetId) ?: return null
        return try {
            host.createView(context, appWidgetId, info).apply { setAppWidget(appWidgetId, info) }
        } catch (e: Exception) {
            Log.w(TAG, "createView failed for $appWidgetId", e)
            null
        }
    }

    /**
     * Step 1: allocate an id and bind it. Returns true when the widget is ready
     * now; otherwise the system bind dialog or the configuration activity was
     * started and the activity result continues the flow.
     */
    fun begin(activity: Activity, provider: AppWidgetProviderInfo, workspaceId: Int): Boolean {
        val id = host.allocateAppWidgetId()
        pending = Pending(id, provider.provider, workspaceId)
        val bound = try {
            manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider, Bundle())
        } catch (e: Exception) {
            false
        }
        if (!bound) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
            return try {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_BIND)
                false
            } catch (e: ActivityNotFoundException) {
                cancel()
                false
            }
        }
        return configureOrFinish(activity)
    }

    /** Step 2: run the provider's configuration screen when it has one. */
    fun configureOrFinish(activity: Activity): Boolean {
        val p = pending ?: return false
        val info = info(p.appWidgetId) ?: run {
            cancel()
            return false
        }
        if (info.configure == null) return true
        return try {
            host.startAppWidgetConfigureActivityForResult(activity, p.appWidgetId, 0, REQUEST_CONFIGURE, null)
            false
        } catch (e: Exception) {
            Log.w(TAG, "configure failed", e)
            true  // place it anyway; most widgets work unconfigured
        }
    }

    fun takePending(): Pending? = pending.also { pending = null }

    fun cancel() {
        pending?.let { host.deleteAppWidgetId(it.appWidgetId) }
        pending = null
    }

    fun release(appWidgetId: Int) {
        infos.remove(appWidgetId)
        if (appWidgetId >= 0) host.deleteAppWidgetId(appWidgetId)
    }

    /** Ids the system still holds for this host but no layout references. */
    fun releaseOrphans(inUse: Set<Int>) {
        for (id in host.appWidgetIds) if (id !in inUse && id != pending?.appWidgetId) {
            infos.remove(id)
            host.deleteAppWidgetId(id)
        }
    }

    companion object {
        private const val TAG = "LxAppWidgets"
        const val HOST_ID = 0x4c58
        const val REQUEST_BIND = 4201
        const val REQUEST_CONFIGURE = 4202
    }
}
