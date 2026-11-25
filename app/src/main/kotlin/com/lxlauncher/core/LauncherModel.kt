// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class WorkspaceItem(val id: Int, val name: String, val wallpaper: String)
data class ShortcutItem(val id: Int, val ws: Int, val app: String, val col: Int, val row: Int)
data class FolderItem(val id: Int, val ws: Int, val name: String, val col: Int, val row: Int, val apps: List<String>)
data class WidgetItem(
    val id: Int, val ws: Int, val kind: String, val appWidgetId: Int,
    val x: Float, val y: Float, val w: Float, val h: Float,
    val visible: Boolean, val config: Map<String, String>,
)
data class WindowItem(
    val id: Int, val kind: String, val title: String, val ws: Int, val state: String, val z: Long,
    val x: Float, val y: Float, val w: Float, val h: Float, val focused: Boolean,
) {
    val minimized get() = state == "minimized"
}

class Snapshot(
    val active: Int,
    val workspaces: List<WorkspaceItem>,
    val cols: Int,
    val rows: Int,
    val shortcuts: List<ShortcutItem>,
    val folders: List<FolderItem>,
    val widgets: List<WidgetItem>,
    val windows: List<WindowItem>,
    val dock: List<String>,
    val taskbar: List<String>,
    val favorites: List<String>,
    val hidden: List<String>,
    val recent: List<String>,
    val settings: Map<String, String>,
) {
    fun setting(key: String) = settings[key].orEmpty()
    fun bool(key: String) = settings[key] == "true"
    fun int(key: String, fallback: Int = 0) = settings[key]?.toIntOrNull() ?: fallback
    fun float(key: String, fallback: Float = 0f) = settings[key]?.toFloatOrNull() ?: fallback
    fun workspace(id: Int) = workspaces.firstOrNull { it.id == id }
    val activeWorkspace get() = workspace(active) ?: workspaces.first()

    companion object {
        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else List(a.length()) { a.getString(it) }

        fun parse(text: String): Snapshot {
            val o = JSONObject(text)
            val ws = o.getJSONArray("workspaces").let { a ->
                List(a.length()) { i ->
                    val w = a.getJSONObject(i)
                    WorkspaceItem(w.getInt("id"), w.getString("name"), w.getString("wallpaper"))
                }
            }
            val shortcuts = o.getJSONArray("shortcuts").let { a ->
                List(a.length()) { i ->
                    val s = a.getJSONObject(i)
                    ShortcutItem(s.getInt("id"), s.getInt("ws"), s.getString("app"), s.getInt("col"), s.getInt("row"))
                }
            }
            val folders = o.getJSONArray("folders").let { a ->
                List(a.length()) { i ->
                    val f = a.getJSONObject(i)
                    FolderItem(f.getInt("id"), f.getInt("ws"), f.getString("name"), f.getInt("col"), f.getInt("row"),
                        strings(f.getJSONArray("apps")))
                }
            }
            val widgets = o.getJSONArray("widgets").let { a ->
                List(a.length()) { i ->
                    val w = a.getJSONObject(i)
                    val cfg = w.getJSONObject("config")
                    val map = HashMap<String, String>()
                    cfg.keys().forEach { k -> map[k] = cfg.getString(k) }
                    WidgetItem(w.getInt("id"), w.getInt("ws"), w.getString("kind"), w.getInt("appWidgetId"),
                        w.getDouble("x").toFloat(), w.getDouble("y").toFloat(),
                        w.getDouble("w").toFloat(), w.getDouble("h").toFloat(),
                        w.getBoolean("visible"), map)
                }
            }
            val windows = o.getJSONArray("windows").let { a ->
                List(a.length()) { i ->
                    val w = a.getJSONObject(i)
                    WindowItem(w.getInt("id"), w.getString("kind"), w.getString("title"), w.getInt("ws"),
                        w.getString("state"), w.getLong("z"),
                        w.getDouble("x").toFloat(), w.getDouble("y").toFloat(),
                        w.getDouble("w").toFloat(), w.getDouble("h").toFloat(), w.getBoolean("focused"))
                }
            }
            val settingsObj = o.getJSONObject("settings")
            val settings = HashMap<String, String>()
            settingsObj.keys().forEach { k -> settings[k] = settingsObj.getString(k) }
            return Snapshot(
                o.getInt("active"), ws, o.getInt("cols"), o.getInt("rows"), shortcuts, folders, widgets, windows,
                strings(o.optJSONArray("dock")), strings(o.optJSONArray("taskbar")),
                strings(o.optJSONArray("favorites")), strings(o.optJSONArray("hidden")),
                strings(o.optJSONArray("recent")), settings,
            )
        }
    }
}

data class LoadOutcome(
    val ok: Boolean, val fresh: Boolean, val usedBackup: Boolean, val mainCorrupt: Boolean,
    val skipped: Int, val error: String,
) {
    companion object {
        fun parse(text: String?): LoadOutcome {
            if (text == null) return LoadOutcome(false, false, false, false, 0, "no response")
            val o = JSONObject(text)
            return LoadOutcome(o.optBoolean("ok"), o.optBoolean("fresh"), o.optBoolean("usedBackup"),
                o.optBoolean("mainCorrupt"), o.optInt("skipped"), o.optString("error"))
        }
    }
}

/**
 * Owns the native engine for the lifetime of the process and publishes
 * snapshots to the UI. Mutations go through [edit], which refreshes the
 * snapshot and schedules a debounced, atomic save on a background thread.
 */
class LauncherModel(private val context: Context) {
    fun interface Listener {
        fun onModelChanged(snapshot: Snapshot)
    }

    val handle: Long = NativeCore.create()
    private val layoutFile = File(context.filesDir, "layout.lxl")
    private val notesFile = File(context.filesDir, "notes.lxl")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lx-save").apply { isDaemon = true } }
    private var pendingSave: java.util.concurrent.ScheduledFuture<*>? = null
    private val listeners = LinkedHashSet<Listener>()

    lateinit var snapshot: Snapshot
        private set
    var lastLoad: LoadOutcome = LoadOutcome(true, true, false, false, 0, "")
        private set

    init {
        check(handle != 0L) { "native engine allocation failed" }
    }

    /**
     * Orientation the default layout was built for, while nobody has changed it
     * yet. The first real layout pass may rebuild it for the actual window shape.
     */
    var untouchedDefaultsLandscape: Boolean? = null

    fun load(defaultNames: Array<String>, welcome: String, landscape: Boolean) {
        lastLoad = LoadOutcome.parse(NativeCore.load(handle, layoutFile.absolutePath, defaultNames, welcome, landscape))
        untouchedDefaultsLandscape = if (lastLoad.fresh) landscape else null
        if (lastLoad.fresh || lastLoad.usedBackup || lastLoad.mainCorrupt) saveSoon(0)
        refresh()
    }

    fun addListener(l: Listener) {
        listeners += l
        if (::snapshot.isInitialized) l.onModelChanged(snapshot)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun refresh() {
        val text = NativeCore.snapshot(handle) ?: return
        snapshot = Snapshot.parse(text)
        for (l in listeners.toList()) l.onModelChanged(snapshot)
    }

    /** Runs a mutation, then republishes and saves when it reported success. */
    fun <T> edit(block: (Long) -> T): T {
        val result = block(handle)
        val changed = when (result) {
            is Boolean -> result
            is Int -> result != 0
            null -> false
            else -> true
        }
        if (changed) {
            refresh()
            saveSoon()
        }
        return result
    }

    /**
     * For a change of what is in front (window focus, Alt+Tab): republishes, but writes nothing. Focus is saved with
     * the next real edit, and when LX stops ([saveSoon] in onStop), instead of a disk write on every tap.
     */
    fun <T> front(block: (Long) -> T): T {
        val result = block(handle)
        if (result == true) refresh()
        return result
    }

    /** For high-frequency updates (window drags): no snapshot, no save. */
    fun <T> live(block: (Long) -> T): T = block(handle)

    fun commit() {
        refresh()
        saveSoon()
    }

    fun saveSoon(delayMs: Long = 400) {
        synchronized(this) {
            pendingSave?.cancel(false)
            pendingSave = io.schedule({
                if (!NativeCore.save(handle, layoutFile.absolutePath)) Log.w(TAG, "layout save failed")
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun setting(key: String): String = snapshot.setting(key)

    fun loadNotes(): String = NativeCore.loadText(notesFile.absolutePath).orEmpty()

    fun saveNotes(text: String) {
        io.execute { NativeCore.saveText(notesFile.absolutePath, text) }
    }

    fun diagnostics(): List<String> = NativeCore.diagnostics(handle)?.toList().orEmpty()

    fun runOnMain(r: Runnable) {
        main.post(r)
    }

    companion object {
        private const val TAG = "LxModel"
    }
}
