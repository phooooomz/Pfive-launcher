// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher

import com.lxlauncher.system.DocumentStreams
import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.lxlauncher.apps.AppEntry
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.core.FolderItem
import com.lxlauncher.core.LauncherModel
import com.lxlauncher.core.LoadOutcome
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.ShortcutItem
import com.lxlauncher.core.Snapshot
import com.lxlauncher.core.WidgetItem
import com.lxlauncher.core.WindowItem
import com.lxlauncher.bridge.BridgePairing
import com.lxlauncher.bridge.BridgeSetupService
import com.lxlauncher.embed.EmbedClient
import com.lxlauncher.embed.EmbedKeys
import com.lxlauncher.embed.EmbeddedAppTool
import com.lxlauncher.system.HomeRole
import com.lxlauncher.files.FileManagerTool
import com.lxlauncher.tools.AppManagerTool
import com.lxlauncher.tools.CleanupTool
import com.lxlauncher.tools.MonitorTool
import com.lxlauncher.tools.NotesTool
import com.lxlauncher.tools.SettingsTool
import com.lxlauncher.tools.WelcomeTool
import com.lxlauncher.ui.MenuItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.StyledShell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.desktop.DesktopView
import com.lxlauncher.ui.desktop.DragPayload
import com.lxlauncher.ui.desktop.LxHostView
import com.lxlauncher.ui.desktop.WidgetFrame
import com.lxlauncher.ui.overlays.FolderPopup
import com.lxlauncher.ui.overlays.OverlayHost
import com.lxlauncher.ui.overlays.StartMenu
import com.lxlauncher.ui.overlays.SearchOverlay
import com.lxlauncher.ui.overlays.WorkspaceOverview
import com.lxlauncher.ui.panels.DockView
import com.lxlauncher.ui.panels.SearchBarView
import com.lxlauncher.ui.panels.TaskbarView
import com.lxlauncher.ui.panels.TopPanel
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.theme.WallpaperSet
import com.lxlauncher.ui.theme.Wallpapers
import com.lxlauncher.ui.theme.WindowStyles
import com.lxlauncher.ui.windows.ToolView
import com.lxlauncher.ui.windows.WindowLayer
import com.lxlauncher.widgets.AndroidWidgets
import com.lxlauncher.widgets.WidgetKinds

/**
 * The Home activity. It owns no state of its own beyond view references:
 * everything the user arranges lives in the native engine held by [LxApp].
 */
class LauncherActivity : Activity(), Shell, LauncherModel.Listener, AppRepository.Listener,
    DesktopView.Events, TaskbarView.Events, TopPanel.Overview, SettingsTool.Resettable {

    private val app get() = application as LxApp
    override val activity: Activity get() = this
    override val model: LauncherModel get() = app.model
    override val apps: AppRepository get() = app.apps
    override val appWidgets: AndroidWidgets get() = app.appWidgets
    // Created in onCreate: resources are not available while the activity is constructed.
    override lateinit var ui: LxTheme
        private set
    override val glass = GlassEnv()
    /** Theme, glass and shell of LX's own windows: the chosen window style, or the LX theme itself. */
    private lateinit var windowUi: LxTheme
    private val windowGlass = GlassEnv()
    private lateinit var windowShell: Shell
    override var editMode = false
        private set

    private val main = Handler(Looper.getMainLooper())
    /** One thread for disk work, which ends after a minute idle: a destroyed activity leaves no thread behind. */
    private val io = java.util.concurrent.ThreadPoolExecutor(1, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue()) { r -> Thread(r, "lx-io").apply { isDaemon = true } }
        .apply { allowCoreThreadTimeOut(true) }
    private lateinit var root: RootLayout
    private lateinit var wallpaperView: WallpaperView
    private lateinit var desktop: DesktopView
    private lateinit var windowLayer: WindowLayer
    private lateinit var topPanel: TopPanel
    private lateinit var dock: DockView
    private lateinit var dockEdge: View
    private lateinit var searchBar: SearchBarView
    private lateinit var taskbar: TaskbarView
    private lateinit var overlays: OverlayHost
    private lateinit var editBar: View

    private var themeKey = ""
    /** See [coverDesktop]. */
    private var desktopCovered = false
    /** See [themeInputsOf]; null makes the next model change build the theme. */
    private var themeInputs: List<Any>? = null
    private var wallpaperKey = ""
    private var currentWallpaper: WallpaperSet? = null
    private var lastActive = 0
    private var started = false
    private var dockRevealed = false
    private val hideDock = Runnable { setDockRevealed(false) }
    private val pendingTools = HashMap<String, String?>()
    private val documentCallbacks = HashMap<Int, (Uri?) -> Unit>()
    private var nextDocRequest = 5100

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindowsCompat()
        ui = LxTheme(this, model.snapshot)
        lastConfig = Configuration(resources.configuration)
        buildUi()
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now -> if (now != null && now.onCheckIsTextEditor()) keyboardToLx(now) }
        if (Build.VERSION.SDK_INT < 30) window.decorView.viewTreeObserver.addOnGlobalLayoutListener(legacyImeWatcher)
        reportFirstFrame()
        model.addListener(this)
        apps.addListener(this)
        apps.windowBounds = windowBoundsProvider
        reportLoadProblems(model.lastLoad)
        // A layout saved with a wallpaper per workspace loads with the active one everywhere;
        // images the other workspaces used are no longer referenced.
        if (layoutLoadedCleanly) {
            val refs = model.snapshot.workspaces.map { it.wallpaper }
            io.execute { app.wallpapers.prune(refs) }
        }
    }

    /**
     * Whether the saved layout loaded as it was saved. After a failed or partial load, or one that fell back to the
     * backup or to the defaults, what the saved layout still refers to stays (its wallpaper copies and its Android
     * widgets) in case it is recovered.
     */
    private val layoutLoadedCleanly: Boolean
        get() = model.lastLoad.let { it.ok && !it.fresh && !it.usedBackup && !it.mainCorrupt && it.error.isEmpty() }

    @Suppress("DEPRECATION")
    private fun android.view.Window.setDecorFitsSystemWindowsCompat() {
        if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(false)
        else decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            isNavigationBarContrastEnforced = false
            isStatusBarContrastEnforced = false
        }
    }

    /** Status bar icons sit on the wallpaper: dark over a light picture, white otherwise. */
    @Suppress("DEPRECATION")
    private fun setStatusIconsDark(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                if (dark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else {
            val v = window.decorView
            v.systemUiVisibility = if (dark) v.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                else v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private var immersive = false

    /**
     * Full screen: the bars hide and come back for a moment on a swipe from the
     * edge, so Back, Home and notifications stay reachable.
     */
    @Suppress("DEPRECATION")
    private fun applyImmersive(on: Boolean) {
        if (on == immersive) return
        immersive = on
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            val v = window.decorView
            val flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            v.systemUiVisibility = if (on) v.systemUiVisibility or flags else v.systemUiVisibility and flags.inv()
        }
    }

    private fun reportLoadProblems(o: LoadOutcome) {
        when {
            o.usedBackup -> notify(getString(R.string.load_restored_backup))
            o.mainCorrupt -> notify(getString(R.string.load_corrupt_defaults))
            o.error.isNotEmpty() && !o.fresh -> notify(getString(R.string.load_error_defaults, o.error))
            o.skipped > 0 -> notify(resources.getQuantityString(R.plurals.load_skipped, o.skipped, o.skipped))
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        EmbedClient.get(this).onForeground()
        appWidgets.start()
        desktop.setWidgetsActive(!desktopCovered)
        windowLayer.setStarted(true)
        topPanel.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        started = false
        EmbedClient.get(this).onBackground()
        appWidgets.stop()
        desktop.setWidgetsActive(false)
        windowLayer.setStarted(false)
        topPanel.setActive(false)
        model.saveSoon(0)
    }

    override fun onDestroy() {
        model.removeListener(this)
        apps.removeListener(this)
        // A newer instance may already have installed its own provider.
        if (apps.windowBounds === windowBoundsProvider) apps.windowBounds = null
        // Android can still recreate the activity for a change it does not hand over. Its windows (an app window is
        // parked, not closed, as in a rebuild), overlays, widgets and background thread end with it.
        if (Build.VERSION.SDK_INT < 30) window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(legacyImeWatcher)
        if (::root.isInitialized) {
            overlays.clear()
            windowLayer.destroyAll()
            desktop.setWidgetsActive(false)
            topPanel.setActive(false)
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: close what is open on top.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeOverlays()
            if (editMode) setEditMode(false)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Before Android 11 a dialog or the keyboard can clear the immersive flags; set them again.
        if (hasFocus && immersive && Build.VERSION.SDK_INT < 30) {
            immersive = false
            applyImmersive(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val changes = lastConfig?.let { newConfig.diff(it) } ?: -1
        lastConfig = Configuration(newConfig)
        // Density, font scale, bold text, colour mode and language change every view built so far; rebuild for those.
        // A rotation, a window resize, a new SIM or touch screen only need a new layout, which keeps open dialogs, a
        // half-typed search and each window's state. The activity handles all of these itself (manifest configChanges):
        // being recreated used to leave its windows and overlays behind.
        val boldText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ActivityInfo.CONFIG_FONT_WEIGHT_ADJUSTMENT else 0
        val rebuild = ActivityInfo.CONFIG_DENSITY or ActivityInfo.CONFIG_FONT_SCALE or
            ActivityInfo.CONFIG_LOCALE or ActivityInfo.CONFIG_LAYOUT_DIRECTION or ActivityInfo.CONFIG_COLOR_MODE or boldText
        if (changes and rebuild != 0) {
            Log.i(PERF_TAG, "rebuilding every view for a configuration change (0x${Integer.toHexString(changes)})")
            return rebuildUi()
        }
        // Android's night mode changes none of LX's colours, which come from LX's own settings; rebuilding every view
        // and window for it made Home slow to come back after dark mode switched on at sunset. Android widgets can
        // have night colours of their own, so only they are made again.
        if (changes and ActivityInfo.CONFIG_UI_MODE != 0) desktop.rebuildAndroidWidgets()
        root.requestLayout()
    }

    private var lastConfig: Configuration? = null

    // ---- building the view tree ----

    private fun buildUi() {
        root = RootLayout()
        glass.root = root
        windowGlass.root = root
        val style = WindowStyles.resolve(model.snapshot)
        windowUi = LxTheme(this, model.snapshot, style)
        windowShell = if (style == null) this else StyledShell(this, { windowUi }, windowGlass)
        wallpaperView = WallpaperView()
        desktop = DesktopView(this, this)
        windowLayer = WindowLayer(windowShell) { createTool(it) }
        val embed = EmbedClient.get(this)
        windowLayer.buildHidden = { w -> w.kind == EmbeddedAppTool.KIND && (embed.sessions[w.id]?.displayId ?: -1) >= 0 }
        topPanel = TopPanel(this, this)
        dock = DockView(this)
        dockEdge = View(this).apply {
            contentDescription = getString(R.string.dock_reveal)
            isFocusable = true
            setOnClickListener { setDockRevealed(true) }
            setOnHoverListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER) setDockRevealed(true)
                false
            }
        }
        searchBar = SearchBarView(this)
        taskbar = TaskbarView(this, this)
        overlays = OverlayHost(this)
        overlays.dropBelow = { payload, x, y -> dropUnderOverlays(payload as? DragPayload, x + overlays.left, y + overlays.top) }
        editBar = Ui.row(this).apply {
            // Opaque: the bar can sit over a window, whose text must not show through it.
            background = com.lxlauncher.ui.theme.PanelDrawable(this, glass, com.lxlauncher.ui.theme.Surface.FLAT).apply { minOpacity = 1f }
            setPadding(ui.dpi(16f), 0, ui.dpi(6f), 0)
            addView(Ui.text(this@LauncherActivity, ui, getString(R.string.edit_mode_hint), ui.textBody, ui.textPrimary, lines = 2),
                Ui.lp(0, Ui.WRAP, 1f))
            addView(Ui.button(this@LauncherActivity, ui, getString(R.string.action_done), primary = true) { setEditMode(false) })
            visibility = View.GONE
        }
        root.addView(wallpaperView)
        root.addView(desktop)
        root.addView(topPanel)
        root.addView(dock)
        root.addView(dockEdge)
        root.addView(searchBar)
        root.addView(taskbar)
        root.addView(windowLayer)
        editBar.visibility = if (editMode) View.VISIBLE else View.GONE
        root.addView(editBar)
        root.addView(overlays)
        setContentView(root)
        // Keep the first focus off text fields (a notes widget would open the keyboard).
        root.isFocusableInTouchMode = true
        // The root only holds focus between other views. Without this, focus that returns to it from the
        // keyboard (Enter in a dialog) makes Android draw its default highlight as a veil over the whole screen.
        root.defaultFocusHighlightEnabled = false
        root.requestFocus()
        root.setOnApplyWindowInsetsListener { v, insets ->
            root.insets = insets
            v.requestLayout()
            insets
        }
        themeKey = ""
        wallpaperKey = ""
        onModelChanged(model.snapshot)
    }

    private fun rebuildUi() {
        val rebuildStart = SystemClock.uptimeMillis()
        val hadOverlays = !overlays.isEmpty
        overlays.clear()
        windowLayer.destroyAll()
        // The old desktop and panel are about to be dropped: their widgets and receivers stop now.
        desktop.setWidgetsActive(false)
        topPanel.setActive(false)
        ui = LxTheme(this, model.snapshot)
        themeInputs = themeInputsOf(model.snapshot)
        desktopCovered = false
        buildUi()
        coverDesktop(model.snapshot)
        // The new views start inactive; resume them if the activity is visible.
        if (started) {
            desktop.setWidgetsActive(!desktopCovered)
            windowLayer.setStarted(true)
            topPanel.setActive(true)
        }
        if (hadOverlays) Log.i(TAG, "overlays closed by rebuild")
        Log.i(PERF_TAG, "rebuilt every view in ${SystemClock.uptimeMillis() - rebuildStart} ms")
    }

    /** Logs how long LX took to show itself, from the process start and from this activity's creation. */
    private fun reportFirstFrame() {
        if (Build.VERSION.SDK_INT < 29) return
        val created = SystemClock.uptimeMillis()
        window.decorView.viewTreeObserver.registerFrameCommitCallback {
            val now = SystemClock.uptimeMillis()
            Log.i(PERF_TAG, "first frame ${now - created} ms after the activity was created, " +
                "${now - android.os.Process.getStartUptimeMillis()} ms after the process started; " +
                "${model.snapshot.windows.size} saved windows")
        }
    }

    /**
     * A maximised or full-screen window on the active workspace covers the whole desktop (a phone opens windows
     * maximised). The desktop then is not drawn and its widgets stop ticking until the window shrinks or goes.
     */
    private fun coverDesktop(s: Snapshot) {
        val covered = s.windows.any { it.ws == s.active && !it.minimized && (it.state == "maximized" || it.state == "fullscreen") }
        if (covered == desktopCovered) return
        desktopCovered = covered
        desktop.visibility = if (covered) View.INVISIBLE else View.VISIBLE
        desktop.setWidgetsActive(started && !covered)
    }

    /** What an LxTheme is built from: the settings, the configuration (density, font scale) and Android's animator switch. */
    private fun themeInputsOf(s: Snapshot): List<Any> =
        listOf(s.settings, Configuration(resources.configuration), ValueAnimator.areAnimatorsEnabled())

    // Settings that change colours, type or sizes baked into views at build time.
    private fun themeKeyOf(s: Snapshot) = listOf("theme.palette", "theme.accent", "ui.font", "theme.iconStyle", "ui.scale",
        "a11y.highContrast", "a11y.largeTargets", "theme.cornerRadius", "motion.mode", "perf.mode", "window.style",
        "window.custom.base", "window.custom.accent", "window.custom.corner", "window.custom.border", "window.custom.caption",
        "window.custom.titleSize", "window.custom.iconSize", "window.custom.buttons", "window.custom.shadow",
        "window.custom.dimInactive")
        .joinToString("|") { s.setting(it) }

    override fun onModelChanged(snapshot: Snapshot) {
        if (!::root.isInitialized) return
        val key = themeKeyOf(snapshot)
        if (themeKey.isNotEmpty() && key != themeKey) {
            themeKey = key
            rebuildUi()
            return
        }
        themeKey = key
        // Most edits (a window moved, a note typed, an app opened) leave every setting alone. Then the theme objects
        // stay, and with them the panels can tell that nothing they show has changed.
        val inputs = themeInputsOf(snapshot)
        if (inputs != themeInputs) {
            themeInputs = inputs
            ui = LxTheme(this, snapshot)
            if (::windowUi.isInitialized) windowUi = LxTheme(this, snapshot, windowUi.windowStyle)
            glass.update(ui, currentWallpaper)
            if (::windowUi.isInitialized) windowGlass.update(windowUi, currentWallpaper)
        }
        applyImmersive(snapshot.bool("display.immersive"))
        val landscape = isLandscape()
        val switched = lastActive != 0 && lastActive != snapshot.active
        val direction = if (switched) {
            val ids = snapshot.workspaces.map { it.id }
            if (ids.indexOf(snapshot.active) > ids.indexOf(lastActive)) 1 else -1
        } else 0
        lastActive = snapshot.active
        val phone = phoneLayout(root.width, root.height, snapshot)
        boundShape = if (root.width > 0) layoutShape(root.width, root.height, snapshot) else null
        topPanel.bind(snapshot, withSwitcher = phone)
        val compact = root.width in 1 until ui.dpi(600f)
        dock.bind(snapshot, vertical = snapshot.setting("dock.position") != "bottom", compact = compact)
        taskbar.bind(snapshot, compactSearch = landscape && snapshot.bool("panel.search"), narrow = compact, phone = phone)
        desktop.bind(snapshot)
        windowLayer.bind(snapshot)
        coverDesktop(snapshot)
        root.requestLayout()
        updateWallpaper(snapshot)
        if (switched && ui.animations) {
            val dx = root.width * 0.15f * direction
            for (v in listOf(desktop, windowLayer)) {
                v.translationX = dx
                v.alpha = 0.4f
                v.animate().translationX(0f).alpha(1f).setDuration(ui.durationMedium).setInterpolator(ui.easeOut).start()
            }
        }
        if (switched) {
            val ws = snapshot.activeWorkspace
            root.announceForAccessibility(getString(R.string.workspace_announce, ws.name))
        }
    }

    override fun onAppsChanged(apps: List<AppEntry>) {
        if (::root.isInitialized) onModelChanged(model.snapshot)
        // Widgets whose ids no layout references any more are released, but not while the layout in use is a
        // fallback: the saved one, once recovered, still needs its widgets.
        if (layoutLoadedCleanly) {
            appWidgets.releaseOrphans(model.snapshot.widgets.filter { it.appWidgetId >= 0 }.map { it.appWidgetId }.toSet())
        }
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun updateWallpaper(s: Snapshot) {
        val ref = s.activeWorkspace.wallpaper
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return
        val blurPx = ui.dpi(ui.blurRadiusDp.toFloat())
        val key = "${Wallpapers.keyOf(ref, ui.colors)}@${w}x$h/$blurPx"
        if (key == wallpaperKey) return
        wallpaperKey = key
        // The system wallpaper is composited only when an LX wallpaper does not cover it.
        if (ref == "system") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        app.wallpapers.get(ref, ui.colors, w, h, blurPx) { set ->
            if (set.ref != model.snapshot.activeWorkspace.wallpaper) return@get
            currentWallpaper = set
            wallpaperView.setWallpaper(set)
            desktop.setWallpaperLight(set.light)
            setStatusIconsDark(set.light)
            if (ref != "system") window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            glass.update(ui, set)
            windowGlass.update(windowUi, set)
            if (set.failed) notify(getString(R.string.wallpaper_failed))
        }
    }

    // ---- layout ----

    /**
     * [safe] is the screen inside the system bars and the display cutout; a fullscreen LX window
     * fills it. [windows] is the canvas above the on-screen keyboard, where LX windows may sit.
     */
    private data class Frames(val top: Rect?, val dock: Rect?, val dockEdge: Rect?, val search: Rect?, val taskbar: Rect,
                              val canvas: Rect, val full: Rect, val edit: Rect, val safe: Rect, val windows: Rect)

    /**
     * A phone held upright with the dock on "auto": one bottom bar carries the
     * dock, the pins and open windows within thumb reach, the workspace switcher
     * moves to the top panel, and search opens from the top panel or a swipe
     * down instead of taking a row of its own. Width is measured without the UI
     * scale, so a larger interface does not turn a tablet into a phone.
     */
    private fun phoneLayout(w: Int, h: Int, s: Snapshot): Boolean =
        s.setting("dock.position") == "auto" && w in 1 until h && w / resources.displayMetrics.density < 600f

    private fun computeFrames(w: Int, h: Int, insets: WindowInsets?): Frames {
        val s = model.snapshot
        val phone = phoneLayout(w, h, s)
        @Suppress("DEPRECATION")
        val sys = if (insets == null) Rect() else if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(i.left, i.top, i.right, i.bottom)
        } else Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        val m = ui.dpi(8f)
        val left = sys.left + m
        val right = w - sys.right - m
        var top = sys.top + m / 2
        var bottom = h - sys.bottom - m
        val landscape = w > h
        // Bars hug their controls, as on a desktop: 48 dp, or 62 dp with larger touch targets.
        val taskH = ui.dockTarget + ui.dpi(6f)
        val taskbar = Rect(left, bottom - taskH, right, bottom)
        bottom = taskbar.top - m
        val search = if (s.bool("panel.search") && !landscape && !phone) {
            val r = Rect(left, bottom - ui.dpi(48f), right, bottom)
            bottom = r.top - m
            r
        } else null
        val topRect = if (s.bool("panel.top")) {
            // Tall enough for 48 dp (or 56 dp) touch targets without clipping them.
            val r = Rect(left, top, right, top + ui.barTarget + ui.dpi(4f))
            top = r.bottom + m
            r
        } else {
            top += m / 2
            null
        }
        var cLeft = left
        var cRight = right
        var cBottom = bottom
        var dockRect: Rect? = null
        var edge: Rect? = null
        if (s.bool("dock.visible") && s.dock.isNotEmpty() && !phone) {
            val pos = s.setting("dock.position")
            val icon = ui.dpi(s.int("dock.iconSize", 36).toFloat())
            val autoHide = s.bool("dock.autoHide")
            val labels = pos != "bottom" && !autoHide
            when (pos) {
                "bottom" -> {
                    val dh = icon + ui.dpi(20f)
                    val count = s.dock.size
                    val dw = ((icon + ui.dpi(14f)).coerceAtLeast(ui.touchTarget) * count + ui.dpi(16f)).coerceAtMost(right - left)
                    val dl = (left + right - dw) / 2
                    dockRect = Rect(dl, bottom - dh, dl + dw, bottom)
                    if (!autoHide) cBottom = dockRect.top - m
                    edge = Rect(left, bottom - ui.dpi(12f), right, bottom)
                }
                else -> {
                    // Phones get a slim dock; wide screens leave room for full labels.
                    val extra = if (labels) ui.dpi(if (w < ui.dpi(600f)) 22f else 46f) else ui.dpi(20f)
                    val dw = (icon + extra).coerceAtLeast(ui.touchTarget + ui.dpi(8f))
                    val dt = top
                    dockRect = if (pos == "right") Rect(right - dw, dt, right, bottom) else Rect(left, dt, left + dw, bottom)
                    if (!autoHide) {
                        if (pos == "right") cRight = dockRect.left - m else cLeft = dockRect.right + m
                    }
                    edge = if (pos == "right") Rect(w - sys.right - ui.dpi(12f), dt, w - sys.right, bottom)
                    else Rect(sys.left, dt, sys.left + ui.dpi(12f), bottom)
                }
            }
            if (!autoHide) edge = null
        }
        val canvas = Rect(cLeft, top, cRight, cBottom)
        val editH = ui.dpi(56f)
        val edit = Rect(left, taskbar.top - editH - m, right, taskbar.top - m)
        val safe = Rect(sys.left, sys.top, w - sys.right, h - sys.bottom)
        // The keyboard pushes windows up instead of covering a text field; the desktop stays put.
        val ime = if (Build.VERSION.SDK_INT >= 30) insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0 else legacyImeHeight
        val windows = Rect(canvas)
        if (ime > 0) {
            windows.bottom = (h - ime - m).coerceIn(minOf(windows.top + ui.dpi(120f), windows.bottom), windows.bottom)
            // A fullscreen window ends at the keyboard too. It used to keep the whole screen, and the keyboard covered
            // the bottom of it: Tedit's terminal and its key row, 518 px of 1470 on the Infinix (14 November 2026).
            safe.bottom = (h - ime).coerceIn(minOf(safe.top + ui.dpi(120f), safe.bottom), safe.bottom)
        }
        return Frames(topRect, dockRect, edge, search, taskbar, canvas, Rect(0, 0, w, h), edit, safe, windows)
    }

    /**
     * Before Android 11 an adjustNothing window is not told how tall the keyboard is. The part of the window it
     * leaves visible says where it ends, and a layout pass follows each change of that part.
     */
    private var legacyImeHeight = 0
    private val legacyVisible = Rect()
    private val legacyImeWatcher = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (Build.VERSION.SDK_INT >= 30 || !::root.isInitialized) return@OnGlobalLayoutListener
        val decor = window.decorView
        decor.getWindowVisibleDisplayFrame(legacyVisible)
        @Suppress("DEPRECATION")
        val nav = root.insets?.systemWindowInsetBottom ?: 0
        val covered = (decor.height - legacyVisible.bottom - nav).coerceAtLeast(0)
        // Less than this is a navigation bar changing, not a keyboard.
        val height = if (covered < ui.dpi(KEYBOARD_MIN_DP)) 0 else covered + nav
        if (height != legacyImeHeight) {
            legacyImeHeight = height
            root.requestLayout()
        }
    }

    private var lastCanvas = Rect()
    private var lastViewport = Rect()
    private var lastSafe = Rect()
    private var lastAspectCanvas = Rect()
    private var lastWidth = 0
    /** Phone layout and orientation the panels were last bound with; null while bound before the first layout. */
    private var boundShape: String? = null

    private fun layoutShape(w: Int, h: Int, s: Snapshot) = "${phoneLayout(w, h, s)}/${isLandscape()}"

    @SuppressLint("ViewConstructor")
    private inner class RootLayout : FrameLayout(this@LauncherActivity) {
        var insets: WindowInsets? = null
        private val overlayFrame = Rect()

        // The root holds focus only between other views, without a highlight; arrow keys and Tab never stop on it.
        override fun addFocusables(views: java.util.ArrayList<View>?, direction: Int, focusableMode: Int) {
            super.addFocusables(views, direction, focusableMode)
            views?.remove(this)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }

        private fun View.at(r: Rect?) {
            if (r == null || r.isEmpty) {
                visibility = View.GONE
                return
            }
            measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
            layout(r.left, r.top, r.right, r.bottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val f = computeFrames(w, h, insets)
            val s = model.snapshot
            wallpaperView.at(f.full)
            topPanel.visibility = if (f.top != null) View.VISIBLE else View.GONE
            topPanel.at(f.top)
            searchBar.visibility = if (f.search != null) View.VISIBLE else View.GONE
            searchBar.at(f.search)
            taskbar.at(f.taskbar)
            val autoHide = s.bool("dock.autoHide")
            dock.visibility = if (f.dock != null && (!autoHide || dockRevealed)) View.VISIBLE else View.GONE
            dock.at(f.dock)
            dockEdge.visibility = if (f.dockEdge != null && !dockRevealed) View.VISIBLE else View.GONE
            dockEdge.at(f.dockEdge)
            desktop.at(f.canvas)
            windowLayer.viewport.set(f.windows)
            windowLayer.at(f.full)
            editBar.at(f.edit)
            // Landscape with three-button navigation or a cutout takes room at a side too; menus stay clear of it.
            val (sideL, sideR) = insets?.let { sideInsets(it) } ?: (0 to 0)
            overlayFrame.set(f.full.left + sideL, f.full.top + (insets?.let { topInset(it) } ?: 0), f.full.right - sideR,
                f.full.bottom - (insets?.let { bottomInset(it) } ?: 0))
            overlays.at(overlayFrame)
            overlays.menuSafeTop = (f.top?.bottom ?: 0) - overlayFrame.top
            if (f.canvas != lastCanvas) {
                val widthClassChanged = (lastCanvas.isEmpty || (lastWidth < ui.dpi(600f)) != (w < ui.dpi(600f)))
                lastWidth = w
                lastCanvas = Rect(f.canvas)
                // Dock and taskbar pick a compact form below 600 dp; rebind when that flips.
                if (widthClassChanged) post { onModelChanged(model.snapshot) }
                val assumed = model.untouchedDefaultsLandscape
                if (assumed != null && !f.canvas.isEmpty) {
                    model.untouchedDefaultsLandscape = null
                    val actual = f.canvas.width() > f.canvas.height()
                    // First start in a window shaped differently from the display (freeform, split screen).
                    if (actual != assumed) post { resetLayout(keepSettings = true, announce = false) }
                }
            }
            if (f.windows != lastViewport || f.safe != lastSafe || f.canvas != lastAspectCanvas) {
                lastViewport = Rect(f.windows)
                lastSafe = Rect(f.safe)
                lastAspectCanvas = Rect(f.canvas)
                val c = f.windows
                val sf = f.safe
                val aspect = if (f.canvas.height() > 0) f.canvas.width().toFloat() / f.canvas.height() else 0f
                model.live {
                    NativeCore.setWindowConstraints(it, ui.dp(240f), ui.dp(180f),
                        // The whole title bar stays on screen, however tall the window style draws it.
                        windowUi.dp(maxOf(44f, windowUi.windowStyle?.titleHeightDp ?: 44f)))
                    NativeCore.setViewport(it, c.left.toFloat(), c.top.toFloat(), c.width().toFloat(), c.height().toFloat(),
                        sf.left.toFloat(), sf.top.toFloat(), sf.width().toFloat(), sf.height().toFloat(), aspect)
                }
                // Window bounds depend on the viewport; republish after layout.
                post { model.refresh() }
            }
            // Views rebuilt for a theme change are bound before they have a size, and turning the
            // screen changes the phone layout or the taskbar's search without always crossing 600 dp.
            // Bind again with the real size and orientation.
            if (w > 0 && h > 0 && boundShape != layoutShape(w, h, s)) {
                boundShape = layoutShape(w, h, s)
                post { onModelChanged(model.snapshot) }
            }
            if (w > 0 && h > 0) updateWallpaper(s)
        }
    }

    @Suppress("DEPRECATION")
    private fun sideInsets(i: WindowInsets): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        val s = i.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        s.left to s.right
    } else {
        var l = i.systemWindowInsetLeft
        var r = i.systemWindowInsetRight
        if (Build.VERSION.SDK_INT >= 28) i.displayCutout?.let {
            l = maxOf(l, it.safeInsetLeft)
            r = maxOf(r, it.safeInsetRight)
        }
        l to r
    }

    @Suppress("DEPRECATION")
    private fun topInset(i: WindowInsets) = if (Build.VERSION.SDK_INT >= 30) i.getInsets(WindowInsets.Type.statusBars()).top else i.systemWindowInsetTop
    @Suppress("DEPRECATION")
    private fun bottomInset(i: WindowInsets) = if (Build.VERSION.SDK_INT >= 30)
        i.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.ime()).bottom else i.systemWindowInsetBottom

    private fun setDockRevealed(on: Boolean) {
        dockRevealed = on
        main.removeCallbacks(hideDock)
        if (on && model.snapshot.bool("dock.autoHide")) main.postDelayed(hideDock, 4000)
        root.requestLayout()
    }

    /** Draws the active LX wallpaper; transparent when the system wallpaper is used. */
    @SuppressLint("ViewConstructor")
    private inner class WallpaperView : View(this@LauncherActivity) {
        private var set: WallpaperSet? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setWallpaper(s: WallpaperSet) {
            set = s
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val bmp: Bitmap = set?.sharp ?: run {
                if (set?.ref != "system") canvas.drawColor(ui.background)
                return
            }
            val scale = maxOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - bmp.width * scale) / 2f, (height - bmp.height * scale) / 2f)
            canvas.drawBitmap(bmp, matrix, paint)
        }
    }

    // ---- tools ----

    private fun createTool(w: WindowItem): ToolView {
        val buildStart = SystemClock.uptimeMillis()
        return buildTool(w).also { Log.d(PERF_TAG, "built a ${w.kind} window in ${SystemClock.uptimeMillis() - buildStart} ms") }
    }

    private fun buildTool(w: WindowItem): ToolView {
        val extra = pendingTools.remove(w.kind)
        // Tools see the window shell, so they are drawn in the window style's palette.
        val shell = windowShell
        return when (w.kind) {
            "settings" -> SettingsTool(shell, extra)
            "files" -> FileManagerTool(shell, extra)
            "monitor" -> MonitorTool(shell)
            "notes" -> NotesTool(shell)
            "cleanup" -> CleanupTool(shell)
            "appmgr" -> AppManagerTool(shell)
            "welcome" -> WelcomeTool(shell)
            com.lxlauncher.tedit.TeditTool.KIND -> com.lxlauncher.tedit.TeditTool(shell, extra)
            EmbeddedAppTool.KIND -> EmbeddedAppTool(shell, w.id, w.title)
            else -> object : ToolView {
                override val view: View = Ui.text(this@LauncherActivity, windowUi, getString(R.string.tool_unknown, w.kind),
                    windowUi.textBody, windowUi.textMuted, lines = 3)
            }
        }
    }

    private fun toolTitle(kind: String) = getString(when (kind) {
        "settings" -> R.string.tool_settings
        "files" -> R.string.tool_files
        "monitor" -> R.string.tool_monitor
        "notes" -> R.string.tool_notes
        "cleanup" -> R.string.tool_cleanup
        "appmgr" -> R.string.tool_appmgr
        "welcome" -> R.string.tool_welcome
        com.lxlauncher.tedit.TeditTool.KIND -> R.string.tool_tedit
        else -> R.string.app_unavailable
    })

    override fun openTool(kind: String, extra: String?) {
        closeOverlays()
        val s = model.snapshot
        val existing = s.windows.firstOrNull { it.ws == s.active && it.kind == kind }
        if (existing != null) {
            model.front { NativeCore.focusWindow(it, existing.id) }
            val tool = windowLayer.focusedTool()
            if (extra != null) when (tool) {
                is SettingsTool -> tool.show(extra)
                is FileManagerTool -> tool.search(extra)
                is com.lxlauncher.tedit.TeditTool -> tool.handleExtra(extra)
            }
            return
        }
        pendingTools[kind] = extra
        val state = if (opensMaximized()) "maximized" else "floating"
        val id = model.edit { NativeCore.openWindow(it, kind, toolTitle(kind), s.active, state) }
        if (id == 0) {
            pendingTools.remove(kind)
            notify(getString(R.string.window_limit))
        }
    }

    // ---- Shell: apps and pins ----

    private var cascade = 0
    private val windowBoundsProvider: (AppRepository.WindowShape) -> Rect? = { appWindowBounds(it) }

    /**
     * Whether a new window opens maximised, an LX tool's and an Android app's alike: the "New windows open"
     * setting, where Automatic means maximised on a narrow screen and floating on a wide one.
     */
    private fun opensMaximized(): Boolean = when (model.snapshot.setting("window.defaultState")) {
        "maximized" -> true
        "floating" -> false
        else -> lastCanvas.width() < ui.dpi(600f)
    }

    /**
     * Where the next app window opens, in the desktop area between the panels; null when apps
     * open full screen. A maximised window fills that area, as an LX window does, so the app gets
     * all the room there is and the panels stay in reach. A floating window on a narrow screen
     * (a phone held upright) takes the full width less a margin, because most apps lay themselves
     * out for a phone's width and cut their text short below it, and as much height as still lets
     * four windows step down one under another. On a wide screen it follows the shape the app asks
     * for: a phone shape for portrait apps, a wide one for landscape apps, a middle size otherwise.
     * Each new floating window steps 28 dp further so windows do not cover one another exactly.
     * Sizes are in real dp, not scaled with the LX interface size.
     */
    private fun appWindowBounds(shape: AppRepository.WindowShape): Rect? {
        if (!model.snapshot.bool("apps.freeform")) return null
        val c = lastCanvas.takeUnless { it.isEmpty } ?: return null
        if (opensMaximized()) return Rect(lastViewport.takeUnless { it.isEmpty } ?: c)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val margin = dp(8f)
        val room = Rect(c.left + margin, c.top + margin, c.right - margin, c.bottom - margin)
        val step = dp(28f) * (cascade++ % 4)
        val w: Int
        val h: Int
        if (c.width() < dp(600f)) {
            w = room.width()
            h = room.height() - dp(28f) * 3
            val t = (room.top + step).coerceAtMost(room.bottom - h)
            return Rect(room.left, t, room.left + w, t + h)
        }
        when (shape) {
            AppRepository.WindowShape.PORTRAIT -> {
                h = (room.height() * 0.92f).toInt()
                w = (h * 9 / 16).coerceIn(minOf(dp(360f), room.width()), room.width())
            }
            AppRepository.WindowShape.LANDSCAPE -> {
                w = (room.width() * 0.72f).toInt().coerceAtLeast(minOf(dp(480f), room.width()))
                h = (w * 10 / 16).coerceAtMost(room.height())
            }
            AppRepository.WindowShape.ANY -> {
                w = (room.width() * 0.62f).toInt().coerceIn(minOf(dp(480f), room.width()), room.width())
                h = (room.height() * 0.82f).toInt().coerceIn(minOf(dp(360f), room.height()), room.height())
            }
        }
        val l = (room.left + (room.width() - w) / 2 - dp(42f) + step).coerceIn(room.left, room.right - w)
        val t = (room.top + (room.height() - h) / 2 - dp(42f) + step).coerceIn(room.top, room.bottom - h)
        return Rect(l, t, l + w, t + h)
    }

    override fun launchApp(key: String, source: View?) {
        if (openInLxWindow(key)) return
        when (val r = apps.launch(key, source)) {
            AppRepository.LaunchResult.Ok -> model.edit { NativeCore.pin(it, NativeCore.LIST_RECENT, key, 0) }
            AppRepository.LaunchResult.Missing -> notify(getString(R.string.launch_missing))
            is AppRepository.LaunchResult.Failed -> notify(getString(R.string.launch_failed, r.reason))
        }
    }

    /**
     * Opens the app in an LX window when "App window frame" is LX and the app may go there (see
     * EmbedClient.route); returns false to let Android open it as before. One window per app: the app
     * runs on that window's display, so a second window would have nothing of its own to show.
     */
    private fun openInLxWindow(key: String): Boolean {
        val s = model.snapshot
        val frame = s.setting("apps.windowFrame")
        if (frame != "lx") return false
        val entry = apps.find(key) ?: return false
        val embed = EmbedClient.get(this)
        embed.refresh()
        val route = EmbedClient.route(frame, embed.status, apps.isGame(key), embed.alwaysAndroid(key), key)
        if (route != EmbedClient.Route.LX_WINDOW) {
            val notice = EmbedClient.routeNotice(embed.status)
            // A game or an app on the Android list opens there by choice.
            if (notice != null && !embedNoticeShown && !apps.isGame(key) && !embed.alwaysAndroid(key)) {
                embedNoticeShown = true
                notify(getString(notice, entry.label), getString(R.string.tool_settings)) {
                    openTool("settings", "apps")
                }
            }
            return false
        }
        closeOverlays()
        val existing = s.windows.firstOrNull { it.kind == EmbeddedAppTool.KIND && it.title == key }
        if (existing != null && existing.ws != s.active) onWorkspaceSelected(existing.ws)
        val state = if (opensMaximized()) "maximized" else "floating"
        // The window and the app's place in Recent change together: one republish of the layout, not two.
        val opened = model.edit { h ->
            val id = existing?.id?.also { NativeCore.focusWindow(h, it) }
                ?: NativeCore.openWindow(h, EmbeddedAppTool.KIND, key, s.active, state)
            if (id != 0) NativeCore.pin(h, NativeCore.LIST_RECENT, key, 0)
            id
        }
        if (opened == 0) notify(getString(R.string.window_limit))
        return true
    }

    /** Said once per run: with LX frames chosen on an Android too old for them, apps open in Android windows. */
    private var embedNoticeShown = false

    override fun launchPin(entry: String, source: View?) {
        val s = model.snapshot
        when {
            entry.startsWith("app:") -> launchApp(entry.removePrefix("app:"), source)
            entry.startsWith("tool:") -> openTool(entry.removePrefix("tool:"))
            entry == "action:home" -> {
                closeOverlays()
                model.edit { NativeCore.minimizeAll(it, s.active); true }
            }
            entry == "action:drawer" -> openDrawer()
            entry == "action:games" -> openDrawer("games")
            entry == "action:search" -> openSearch()
            entry == "intent:browser" -> openCategory(Intent.CATEGORY_APP_BROWSER, R.string.no_browser)
            entry == "intent:gallery" -> openCategory(Intent.CATEGORY_APP_GALLERY, R.string.no_gallery)
            else -> notify(getString(R.string.app_unavailable))
        }
    }

    /** The user's default app for a category, through Android's own resolver. */
    private fun openCategory(category: String, missing: Int) {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The default app opens like any other app, in an LX window when the frame setting asks for one.
        val info = packageManager.resolveActivity(intent, 0)?.activityInfo
        val entries = apps.apps.filter { it.packageName == info?.packageName && !it.isWorkProfile }
        // The activity Android chose; a package with one launcher entry may have chosen an alias of it.
        val default = entries.firstOrNull { it.component.className == info?.name } ?: entries.singleOrNull()
        if (default != null) return launchApp(default.key, null)
        val started = try {
            startActivity(intent, apps.launchOptions(null))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "start refused", e)
            false
        }
        if (!started) notify(getString(missing))
    }

    // Offered on Android 14 and later only, where LX Bridge runs.
    override fun pairBridge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (!EmbedClient.get(this).wifiConnected) {
            notify(getString(R.string.bridge_wifi_first))
            startSafely(Intent(Settings.Panel.ACTION_WIFI))
            return
        }
        notify(getString(R.string.bridge_turn_on))
        // The extra points Android's list at LX's entry, on the Android versions that follow it.
        startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", ComponentName(this, BridgeSetupService::class.java).flattenToString()))
    }

    // Offered on Android 14 and later only, where LX Bridge runs and this permission exists.
    override fun pairBridgeWithCode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) openPairing()
        else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_BRIDGE_NOTIFICATIONS)
    }

    private fun openPairing() {
        if (Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0) {
            notify(getString(R.string.bridge_needs_developer))
            startSafely(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            return
        }
        EmbedClient.get(this).beginPairing()
        if (!startSafely(BridgePairing.wirelessDebuggingPage())) startSafely(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BRIDGE_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openPairing()
        else notify(getString(R.string.bridge_needs_notifications))
    }

    override fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "start refused", e)
        false
    }

    override fun openDrawer(filter: String?) = openAppMenu(filter, null)

    /** "Add app" on the desktop: the menu picks an app and its shortcut lands at [cell], or the next free cell. */
    private fun pickAppForDesktop(cell: Pair<Int, Int>?) {
        val ws = model.snapshot.active
        openAppMenu(null) { app ->
            val id = model.edit { NativeCore.addShortcut(it, ws, app.key, cell?.first ?: -1, cell?.second ?: -1) }
            notify(if (id == 0) getString(R.string.desktop_full) else getString(R.string.app_added_desktop, app.label))
        }
    }

    /** Hands a drop that landed on the empty overlay layer to the panel under the point (root coordinates). */
    private fun dropUnderOverlays(p: DragPayload?, x: Float, y: Float): Boolean {
        if (p == null) return false
        fun View.under() = isShown && x >= left && x < right && y >= top && y < bottom
        return when {
            taskbar.under() -> taskbar.drop(p)
            dock.under() -> dock.drop(p)
            desktop.under() -> desktop.dropAt(p, x - desktop.left, y - desktop.top)
            else -> false
        }
    }

    /** The Applications menu grows up from the button at the left end of the bar. */
    private fun openAppMenu(group: String?, onPick: ((AppEntry) -> Unit)?) {
        closeOverlays()
        val bar = IntArray(2)
        val layer = IntArray(2)
        taskbar.getLocationInWindow(bar)
        overlays.getLocationInWindow(layer)
        val shown = taskbar.isShown && taskbar.height > 0
        val left = if (shown) bar[0] - layer[0] + ui.dpi(4f) else ui.dpi(8f)
        val bottom = if (shown) bar[1] - layer[1] - ui.dpi(6f) else overlays.height - ui.dpi(8f)
        val menu = StartMenu(this, overlays, left, bottom, group, onPick)
        taskbar.setAppsOpen(true)
        // Every way the menu closes (a launch, a drag out, Back, a rebuild) runs onClose.
        overlays.push(menu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT), dimAlpha = 0.3f,
            onClose = { taskbar.setAppsOpen(false) })
    }

    override fun openSearch(query: String) {
        closeOverlays()
        val w = (overlays.width - ui.dpi(24f)).coerceAtMost(ui.dpi(640f))
        // It fills the space above the keyboard, up to 70% of the screen it opened on.
        val search = SearchOverlay(this, overlays, query).apply { maxHeight = (overlays.height * 0.7f).toInt() }
        overlays.push(search, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = ui.dpi(56f)
            bottomMargin = ui.dpi(8f)
        })
    }

    override fun showOverview() {
        closeOverlays()
        overlays.push(WorkspaceOverview(this, overlays) { v, id -> onWorkspaceMenu(v, id) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    override fun closeOverlays(): Boolean = if (::overlays.isInitialized) overlays.clear() else false

    // ---- Shell: dialogs ----

    override fun notify(message: CharSequence, actionLabel: CharSequence?, action: (() -> Unit)?) {
        if (!::overlays.isInitialized) return
        val bottom = ui.dpi(80f)
        overlays.post { overlays.snackbar(message, actionLabel, action, bottom) }
    }

    override fun showMenu(anchor: View, items: List<MenuItem>, x: Float, y: Float, alignStart: Boolean, dense: Boolean) =
        overlays.menu(anchor, items, x, y, alignStart, dense)
    override fun prompt(title: CharSequence, initial: String, hint: CharSequence, onOk: (String) -> Unit) =
        overlays.prompt(title, initial, hint, onOk)
    override fun confirm(title: CharSequence, message: CharSequence, confirmLabel: CharSequence, danger: Boolean, onOk: () -> Unit) =
        overlays.confirm(title, message, confirmLabel, danger, onOk)
    override fun choose(title: CharSequence, options: List<CharSequence>, selected: Int, onPick: (Int) -> Unit) =
        overlays.choose(title, options, selected, onPick)

    override fun setEditMode(on: Boolean) {
        editMode = on
        closeOverlays()
        desktop.setEditMode(on)
        // Set here, not in onLayout: a visibility change during layout forces a second pass.
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        root.requestLayout()
        if (on) notify(getString(R.string.edit_mode_on))
    }

    // ---- Shell: pickers and results ----

    override fun requestDocument(intent: Intent, onResult: (Uri?) -> Unit) {
        val code = nextDocRequest++
        documentCallbacks[code] = onResult
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            documentCallbacks.remove(code)
            notify(getString(R.string.picker_missing))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            AndroidWidgets.REQUEST_BIND -> if (resultCode == RESULT_OK) {
                if (appWidgets.configureOrFinish(this)) placePendingWidget()
            } else appWidgets.cancel()
            AndroidWidgets.REQUEST_CONFIGURE -> if (resultCode == RESULT_OK) placePendingWidget() else appWidgets.cancel()
            HomeRole.REQUEST_CODE -> model.refresh()
            else -> documentCallbacks.remove(requestCode)?.invoke(if (resultCode == RESULT_OK) data?.data else null)
        }
    }

    override fun pickWallpaperImage(workspaceId: Int) {
        // Android's photo picker where it exists (no storage permission needed), the document picker otherwise.
        // It ships with SDK extension R 2, which reaches Android 11 and 12 through system updates too.
        val hasPhotoPicker = Build.VERSION.SDK_INT >= 30 &&
            android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2
        val intent = if (hasPhotoPicker) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            notify(getString(R.string.wallpaper_importing))
            io.execute {
                // The image is copied once into LX's own storage, so the wallpaper
                // survives the original being moved or its access being revoked.
                val ref = app.wallpapers.importImage(uri)
                main.post {
                    val set = ref != null && model.edit { NativeCore.setWallpaper(it, workspaceId, ref) }
                    ref?.let { app.wallpapers.release(it) }
                    if (!set) notify(getString(R.string.wallpaper_failed))
                    // Also after a failure: the copied image is no longer protected and nothing uses it.
                    pruneWallpapers()
                }
            }
        }
    }

    override fun chooseWallpaper(workspaceId: Int) {
        val ws = model.snapshot.workspace(workspaceId) ?: return
        val opts = listOf(
            Wallpapers.THEMED to R.string.wallpaper_theme,
            "builtin:skyline" to R.string.wallpaper_skyline, "builtin:rain" to R.string.wallpaper_rain,
            "builtin:deep" to R.string.wallpaper_deep) +
            Wallpapers.PHOTO_WALLPAPERS.map { ("builtin:" + it.name) to it.labelRes } +
            listOf("system" to R.string.wallpaper_system,
            "image" to R.string.wallpaper_pick_image, "color" to R.string.wallpaper_pick_color)
        choose(getString(R.string.wallpaper_title), opts.map { getString(it.second) }, opts.indexOfFirst { it.first == ws.wallpaper }) { i ->
            when (val ref = opts[i].first) {
                "image" -> pickWallpaperImage(workspaceId)
                "color" -> prompt(getString(R.string.wallpaper_pick_color), "#071426", "#RRGGBB") { hex ->
                    if (model.edit { NativeCore.setWallpaper(it, workspaceId, "color:${hex.trim()}") }) pruneWallpapers()
                    else notify(getString(R.string.wallpaper_bad_color))
                }
                else -> if (model.edit { NativeCore.setWallpaper(it, workspaceId, ref) }) pruneWallpapers()
            }
        }
    }

    /** Deletes imported images no workspace uses any more. */
    /** Lists and deletes unused wallpaper copies on [io]: a folder listing and file deletes do not belong on the main thread. */
    private fun pruneWallpapers() {
        val refs = model.snapshot.workspaces.map { it.wallpaper }
        io.execute { app.wallpapers.prune(refs) }
    }

    override fun removeWorkspace(workspaceId: Int) {
        val s = model.snapshot
        val ws = s.workspace(workspaceId) ?: return
        if (s.workspaces.size <= 1) {
            notify(getString(R.string.workspace_last))
            return
        }
        val others = s.workspaces.filter { it.id != workspaceId }
        val options = others.map { getString(R.string.workspace_move_content_to, it.name) as CharSequence } +
            getString(R.string.workspace_delete_content)
        choose(getString(R.string.workspace_remove_title, ws.name), options, -1) { i ->
            val moveTo = if (i < others.size) others[i].id else 0
            val go = {
                val released = model.edit { NativeCore.removeWorkspace(it, workspaceId, moveTo) }
                if (released == null) notify(getString(R.string.workspace_remove_failed))
                else released.forEach { appWidgets.release(it) }
            }
            if (moveTo == 0) confirm(getString(R.string.workspace_remove_title, ws.name),
                getString(R.string.workspace_delete_content_warning), getString(R.string.action_delete), danger = true) { go() }
            else go()
        }
    }

    override fun exportLayout() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "lx-launcher-layout.json")
        requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            val doc = NativeCore.exportDocument(model.handle) ?: return@requestDocument
            io.execute {
                val ok = runCatching {
                    DocumentStreams.write(this, uri)?.use { it.write(doc.toByteArray()) } ?: error("no stream")
                }.isSuccess
                main.post { notify(getString(if (ok) R.string.backup_exported else R.string.backup_export_failed)) }
            }
        }
    }

    override fun importLayout() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        requestDocument(intent) { uri ->
            if (uri == null) return@requestDocument
            io.execute {
                // Bounded read: anything above the native limit is refused before parsing.
                val text = runCatching {
                    DocumentStreams.read(this, uri)?.use { input ->
                        val buf = ByteArray(1024 * 1024 + 1)
                        var n = 0
                        while (n < buf.size) {
                            val r = input.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        if (n > 1024 * 1024) null else String(buf, 0, n, Charsets.UTF_8)
                    }
                }.getOrNull()
                // Every LX export holds a desktop. A file without one replaced the layout with a single empty
                // workspace, and released the Android widgets of the layout it replaced.
                val isLayout = text != null && runCatching { org.json.JSONObject(text).optJSONObject("desktop") != null }.getOrDefault(false)
                main.post {
                    if (text == null) {
                        notify(getString(R.string.backup_import_unreadable))
                        return@post
                    }
                    if (!isLayout) {
                        notify(getString(R.string.backup_import_not_layout))
                        return@post
                    }
                    val report = LoadOutcome.parse(model.live { NativeCore.importDocument(it, text) })
                    if (!report.ok) notify(getString(R.string.backup_import_refused, report.error))
                    else {
                        model.commit()
                        appWidgets.releaseOrphans(model.snapshot.widgets.filter { it.appWidgetId >= 0 }.map { it.appWidgetId }.toSet())
                        pruneWallpapers()
                        notify(if (report.skipped > 0) resources.getQuantityString(R.plurals.backup_imported_skipped, report.skipped, report.skipped) else getString(R.string.backup_imported))
                    }
                }
            }
        }
    }

    override fun resetLayout(keepSettings: Boolean) = resetLayout(keepSettings, announce = true)

    private fun resetLayout(keepSettings: Boolean, announce: Boolean) {
        val old = model.snapshot.widgets.filter { it.appWidgetId >= 0 }.map { it.appWidgetId }
        val names = Array(4) { getString(R.string.workspace_default_name, it + 1) }
        val landscape = if (lastCanvas.isEmpty) isLandscape() else lastCanvas.width() > lastCanvas.height()
        model.live { NativeCore.resetDefaults(it, names, getString(R.string.tool_welcome), landscape, keepSettings) }
        old.forEach { appWidgets.release(it) }
        model.commit()
        if (announce) notify(getString(R.string.backup_reset_done))
    }

    // ---- widgets ----

    override fun addNativeWidget(kind: String, workspaceId: Int) {
        val landscape = if (lastCanvas.isEmpty) isLandscape() else lastCanvas.width() > lastCanvas.height()
        val (w, h) = WidgetKinds.defaultSize(kind, landscape)
        // A negative position lets the core find a spot clear of other widgets and icons.
        val id = model.edit { NativeCore.addWidget(it, workspaceId, kind, -1, -1f, -1f, w, h) }
        if (id == 0) notify(getString(R.string.widget_limit))
        else {
            closeOverlays()
            notify(getString(R.string.widget_added, WidgetKinds.label(this, kind)), getString(R.string.action_arrange)) { setEditMode(true) }
        }
    }

    override fun pickAndroidWidget(workspaceId: Int) {
        if (!appWidgets.hasCachedProviders) notify(getString(R.string.widget_android_loading))
        io.execute {
            val providers = appWidgets.providers { pkg -> app.apps.firstForPackage(pkg)?.label }
            main.post {
                if (isDestroyed) return@post
                if (providers.isEmpty()) {
                    notify(getString(R.string.widget_android_none))
                    return@post
                }
                val labels = providers.map { p -> p.label + if (p.appLabel != null) "  ·  ${p.appLabel}" else "" }
                choose(getString(R.string.widget_android_add), labels, -1) { i ->
                    if (appWidgets.begin(this, providers[i].info, workspaceId)) placePendingWidget()
                }
            }
        }
    }

    private fun placePendingWidget() {
        val p = appWidgets.takePending() ?: return
        val info = appWidgets.info(p.appWidgetId)
        val c = lastCanvas
        val wFrac = if (info != null && c.width() > 0) ((info.minWidth + ui.dpi(24f)) / c.width().toFloat()).coerceIn(0.2f, 1f) else 0.5f
        val hFrac = if (info != null && c.height() > 0) ((info.minHeight + ui.dpi(24f)) / c.height().toFloat()).coerceIn(0.1f, 1f) else 0.25f
        val id = model.edit { NativeCore.addWidget(it, p.workspaceId, "appwidget", p.appWidgetId, -1f, -1f, wFrac, hFrac) }
        if (id == 0) {
            appWidgets.release(p.appWidgetId)
            notify(getString(R.string.widget_limit))
        } else {
            closeOverlays()
            notify(getString(R.string.widget_added, info?.loadLabel(packageManager) ?: getString(R.string.widget_android)),
                getString(R.string.action_arrange)) { setEditMode(true) }
        }
    }

    override fun createWidgetContent(frame: WidgetFrame, item: WidgetItem): View? {
        if (item.kind != "appwidget") return WidgetKinds.create(this, item)
        val v = appWidgets.createView(item.appWidgetId)
        if (v == null) {
            frame.showFailure(getString(R.string.widget_android_failed),
                onRetry = { desktop.rebuildWidget(item.id) },
                onRemove = { removeWidget(item) })
            return null
        }
        // The frame sees the same press first and handles it (drag or menu); the host only covers presses the frame missed.
        (v as? LxHostView)?.onLongPress = { if (!frame.armed) onWidgetMenu(frame, item) }
        v.contentDescription = appWidgets.info(item.appWidgetId)?.loadLabel(packageManager)
        return v
    }

    private fun removeWidget(item: WidgetItem) {
        val released = model.edit { NativeCore.removeWidget(it, item.id) }
        if (released >= 0) appWidgets.release(released)
    }

    // ---- desktop events ----

    override fun onEmptyLongPress(x: Float, y: Float) {
        val s = model.snapshot
        showMenu(desktop, listOf(
            // Desktop content, then windows, then the launcher as a whole.
            MenuItem(getString(R.string.desktop_add_widget), R.drawable.ic_widgets) { openTool("settings", "widgets") },
            MenuItem(getString(R.string.desktop_add_app), R.drawable.ic_apps) { pickAppForDesktop(desktop.cellAt(x, y)) },
            MenuItem(getString(R.string.settings_wallpaper), R.drawable.ic_image) { chooseWallpaper(s.active) },
            MenuItem(getString(if (editMode) R.string.edit_mode_finish else R.string.edit_mode_start), R.drawable.ic_edit) { setEditMode(!editMode) },
            MenuItem(getString(R.string.window_tile_all), R.drawable.ic_tile, groupStart = true) { model.edit { NativeCore.tileWindows(it, s.active); true } },
            MenuItem(getString(R.string.desktop_show), R.drawable.ic_minimize) { model.edit { NativeCore.minimizeAll(it, s.active); true } },
            MenuItem(getString(R.string.overview_title), R.drawable.ic_workspaces, groupStart = true) { showOverview() },
            MenuItem(getString(R.string.tool_settings), R.drawable.ic_settings) { openTool("settings") },
        ), x, y)
    }

    override fun onSwipeWorkspace(direction: Int) {
        val next = model.live { NativeCore.neighbourWorkspace(it, direction) }
        if (next != 0 && next != model.snapshot.active) model.edit { NativeCore.setActiveWorkspace(it, next) }
    }

    override fun onSwipeUp() = openDrawer()
    override fun onSwipeDown() = openSearch()
    override fun onPinchIn() = showOverview()
    override fun onDoubleTap() {
        model.edit { NativeCore.minimizeAll(it, model.snapshot.active); true }
    }

    private fun workspaceItems(onPick: (Int) -> Unit): List<MenuItem> {
        val s = model.snapshot
        return s.workspaces.filter { it.id != s.active }.map { ws ->
            MenuItem(getString(R.string.move_to_workspace, ws.name), R.drawable.ic_widgets) { onPick(ws.id) }
        }
    }

    override fun onShortcutMenu(view: View, item: ShortcutItem) {
        val entry = apps.find(item.app)
        val items = mutableListOf<MenuItem>()
        if (entry != null) {
            items += MenuItem(getString(R.string.action_open), R.drawable.ic_next) { launchApp(item.app, view) }
            for (sc in apps.shortcuts(item.app)) items += MenuItem(sc.shortLabel ?: sc.id, R.drawable.ic_next) {
                if (!apps.startShortcut(sc, view)) notify(getString(R.string.shortcut_failed))
            }
            val pin = "app:${item.app}"
            items += if (pin in model.snapshot.taskbar) MenuItem(getString(R.string.taskbar_unpin), R.drawable.ic_pin) {
                model.edit { NativeCore.unpin(it, NativeCore.LIST_TASKBAR, pin) }
            } else MenuItem(getString(R.string.taskbar_pin), R.drawable.ic_pin) {
                if (!model.edit { NativeCore.pin(it, NativeCore.LIST_TASKBAR, pin, -1) }) notify(getString(R.string.taskbar_pin_failed))
            }
            items += MenuItem(getString(R.string.app_info), R.drawable.ic_info) { apps.openAppInfo(item.app, view) }
        }
        items += workspaceItems { ws -> if (!model.edit { NativeCore.moveItemToWorkspace(it, item.id, ws) }) notify(getString(R.string.desktop_full)) }
        items += MenuItem(getString(R.string.shortcut_remove), R.drawable.ic_close) {
            model.edit { NativeCore.removeItem(it, item.id) }
            notify(getString(R.string.shortcut_removed_note))
        }
        if (entry != null && !apps.isSystemApp(item.app)) {
            items += MenuItem(getString(R.string.app_uninstall), R.drawable.ic_delete, danger = true) { apps.requestUninstall(item.app) }
        }
        showMenu(view, items)
    }

    override fun onFolderOpen(view: View, item: FolderItem) {
        closeOverlays()
        overlays.push(FolderPopup(this, overlays, item), FolderPopup.params())
    }

    override fun onFolderMenu(view: View, item: FolderItem) {
        val items = mutableListOf(
            MenuItem(getString(R.string.action_open), R.drawable.ic_folder) { onFolderOpen(view, item) },
            MenuItem(getString(R.string.folder_rename), R.drawable.ic_edit) {
                prompt(getString(R.string.folder_rename), item.name) { n ->
                    if (!model.edit { NativeCore.renameFolder(it, item.id, n) }) notify(getString(R.string.name_rejected))
                }
            },
        )
        items += workspaceItems { ws -> if (!model.edit { NativeCore.moveItemToWorkspace(it, item.id, ws) }) notify(getString(R.string.desktop_full)) }
        items += MenuItem(getString(R.string.folder_remove), R.drawable.ic_delete, danger = true) {
            confirm(getString(R.string.folder_remove_title, item.name), getString(R.string.folder_remove_message), getString(R.string.action_remove), danger = true) {
                model.edit { NativeCore.removeItem(it, item.id) }
            }
        }
        showMenu(view, items)
    }

    override fun onWidgetMenu(frame: WidgetFrame, item: WidgetItem) {
        val items = mutableListOf(
            MenuItem(getString(if (editMode) R.string.edit_mode_finish else R.string.widget_arrange), R.drawable.ic_edit) { setEditMode(!editMode) },
            MenuItem(getString(R.string.action_refresh), R.drawable.ic_refresh) { desktop.rebuildWidget(item.id) },
        )
        if (item.kind == "clock") {
            val h24 = (item.config["h24"] ?: model.setting("clock.24h")) == "true"
            items += MenuItem(getString(if (h24) R.string.clock_use_12h else R.string.clock_use_24h), R.drawable.ic_clock) {
                model.edit { NativeCore.setWidgetConfig(it, item.id, "h24", (!h24).toString()) }
                desktop.rebuildWidget(item.id)
            }
        }
        if (item.kind == "appwidget" && appWidgets.info(item.appWidgetId)?.configure != null) {
            items += MenuItem(getString(R.string.widget_configure), R.drawable.ic_settings) {
                try {
                    appWidgets.host.startAppWidgetConfigureActivityForResult(this, item.appWidgetId, 0, REQUEST_RECONFIGURE, null)
                } catch (e: Exception) {
                    notify(getString(R.string.widget_configure_failed))
                }
            }
        }
        items += MenuItem(getString(R.string.widget_hide), R.drawable.ic_minimize) {
            model.edit { NativeCore.setWidgetVisible(it, item.id, false) }
            notify(getString(R.string.widget_hidden_note), getString(R.string.action_undo)) {
                model.edit { NativeCore.setWidgetVisible(it, item.id, true) }
            }
        }
        items += workspaceItems { ws -> model.edit { NativeCore.moveWidgetToWorkspace(it, item.id, ws) } }
        items += MenuItem(getString(R.string.action_remove), R.drawable.ic_delete, danger = true) {
            confirm(getString(R.string.widget_remove_title, WidgetKinds.label(this, item.kind)), getString(R.string.widget_remove_message),
                getString(R.string.action_remove), danger = true) { removeWidget(item) }
        }
        showMenu(frame, items)
    }

    // ---- taskbar events ----

    override fun onWorkspaceSelected(id: Int) {
        closeOverlays()
        if (id != model.snapshot.active) model.edit { NativeCore.setActiveWorkspace(it, id) }
    }

    override fun onWorkspaceMenu(view: View, id: Int) {
        val ws = model.snapshot.workspace(id) ?: return
        showMenu(view, listOf(
            MenuItem(getString(R.string.workspace_rename), R.drawable.ic_edit) {
                prompt(getString(R.string.workspace_rename), ws.name) { n ->
                    if (!model.edit { NativeCore.renameWorkspace(it, id, n) }) notify(getString(R.string.name_rejected))
                }
            },
            MenuItem(getString(R.string.settings_wallpaper), R.drawable.ic_image) { chooseWallpaper(id) },
            MenuItem(getString(R.string.overview_title), R.drawable.ic_workspaces) { showOverview() },
            MenuItem(getString(R.string.workspace_remove), R.drawable.ic_delete, danger = true, enabled = model.snapshot.workspaces.size > 1) {
                removeWorkspace(id)
            },
        ))
    }

    override fun onWindowButton(id: Int) {
        val w = model.snapshot.windows.firstOrNull { it.id == id } ?: return
        when {
            w.minimized -> model.edit { NativeCore.restoreWindow(it, id) }
            w.focused -> model.edit { NativeCore.setWindowState(it, id, "minimized") }
            else -> model.front { NativeCore.focusWindow(it, id) }
        }
    }

    override fun onWindowMenu(view: View, id: Int) {
        val w = model.snapshot.windows.firstOrNull { it.id == id } ?: return
        showMenu(view, listOf(
            MenuItem(getString(if (w.minimized) R.string.window_restore else R.string.window_minimize), R.drawable.ic_minimize) { onWindowButton(id) },
            MenuItem(getString(R.string.window_maximize), R.drawable.ic_maximize) { model.edit { NativeCore.setWindowState(it, id, "maximized") } },
            MenuItem(getString(R.string.window_close), R.drawable.ic_close, danger = true) { model.edit { NativeCore.closeWindow(it, id) } },
        ))
    }

    // ---- input ----

    /**
     * The window whose Android app has the phone's keyboard, or [NO_WINDOW] while LX may use it. While an app has it, LX's
     * own window keeps out of the keyboard. Each tap in that window reaches LX first, and Android gives LX the focus for a
     * moment before lxdb hands it back to the app. A window that may use the keyboard makes Android put the app's keyboard
     * away at that moment, so a tap on the app's Send button closed it, and Back closed it and then went back in the app.
     */
    private var keyboardApp = NO_WINDOW
    /** The window whose app content the current press reached, and the window whose frame it reached. */
    private var pressContent = NO_WINDOW
    private var pressFrame = NO_WINDOW

    override fun touchedApp(windowId: Int) {
        pressContent = windowId
    }

    override fun pressedWindow(windowId: Int) {
        if (pressFrame == NO_WINDOW) pressFrame = windowId
    }

    override fun appClaimsKeyboard(windowId: Int) = keyboardToApp(windowId)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return super.dispatchTouchEvent(ev)
        pressContent = NO_WINDOW
        pressFrame = NO_WINDOW
        val handled = super.dispatchTouchEvent(ev)
        // The flag is in place before the focus Android moves for this press reaches LX.
        val app = keyboardAfterPress(keyboardApp, pressContent, pressFrame, handled)
        if (app != NO_WINDOW) keyboardToApp(app) else keyboardToLx(null)
        return handled
    }

    private fun keyboardToApp(windowId: Int) {
        val had = keyboardApp
        keyboardApp = windowId
        if (had != NO_WINDOW) return
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        Log.d(INPUT_TAG, "keyboard goes with the app in window $windowId")
    }

    /** LX may use the keyboard again. [field], an LX text field that just took focus, gets it once Android knows. */
    private fun keyboardToLx(field: View?) {
        if (keyboardApp == NO_WINDOW) return
        Log.d(INPUT_TAG, "keyboard back to LX from window $keyboardApp" + if (field != null) " for a text field" else "")
        keyboardApp = NO_WINDOW
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        // The field asked for the keyboard while the window still kept out of it; it asks again after the next layout.
        field?.postDelayed({
            if (field.isFocused) getSystemService(InputMethodManager::class.java).showSoftInput(field, 0)
        }, KEYBOARD_AFTER_FLAGS_MS)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlays.back()) return
        if (editMode) {
            setEditMode(false)
            return
        }
        // A full-screen window has no title bar to leave it by; Back gives it back its size and its buttons.
        val focused = windowLayer.focusedWindow()
        if (focused?.state == "fullscreen") {
            model.edit { NativeCore.leaveFullscreen(it, focused.id) }
            return
        }
        windowLayer.focusedTool()?.let { if (it.onBack()) return }
        // A launcher has nowhere to go back to; Back does nothing else here.
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Escape closes the top menu or dialog wherever focus is; a dialog does not take focus
        // from the window behind it, so the overlay layer would not see the key itself.
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && !overlays.isEmpty) {
            if (event.action == KeyEvent.ACTION_UP) overlays.back()
            return true
        }
        // The focused LX window gets the first word on a shortcut: Tedit closes a tab on Ctrl+W, not its window.
        if (event.action == KeyEvent.ACTION_DOWN && overlays.isEmpty && !editMode) {
            val tool = windowLayer.focusedTool()
            if (tool != null && tool !is EmbeddedAppTool && tool.onShortcut(event)) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && handleShortcut(event)) return true
        if (forwardKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Keys pressed while an Android app window has focus go to that app, which runs on its own display. */
    private val forwardedKeys = HashMap<Int, EmbeddedAppTool>()

    private fun forwardKey(e: KeyEvent): Boolean {
        // Back reaches the app through onBack. Keys Android applies to the whole phone keep their usual meaning.
        if (e.keyCode == KeyEvent.KEYCODE_BACK || !EmbedKeys.allowed(e.keyCode, e.metaState, e.action)) return false
        // A key from the on-screen keyboard that reached LX belongs to one of LX's own text fields: an app's keyboard
        // sends its keys to the app directly. Passing Delete, Enter or a digit on stole the field's focus and keyboard.
        if ((e.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0) return false
        // A release goes to the app that got its press, wherever focus went since. Any other release stays
        // with LX, which used the press for a shortcut or a control of its own.
        if (e.action == KeyEvent.ACTION_UP) return forwardedKeys.remove(e.keyCode)?.onKey(e) ?: false
        if (!overlays.isEmpty || editMode) return false
        val window = windowLayer.focusedWindow() ?: return false
        val tool = windowLayer.focusedTool() as? EmbeddedAppTool ?: return false
        if (!EmbeddedAppTool.keysGoToApp(currentFocus, windowLayer, windowLayer.frameOf(window.id), tool.view)) return false
        if (!tool.onKey(e)) return false
        forwardedKeys[e.keyCode] = tool
        return true
    }

    private fun handleShortcut(e: KeyEvent): Boolean {
        val s = model.snapshot
        val focused = windowLayer.focusedWindow()
        val ctrl = e.isCtrlPressed
        when {
            e.keyCode == KeyEvent.KEYCODE_SEARCH || (ctrl && (e.keyCode == KeyEvent.KEYCODE_K || e.keyCode == KeyEvent.KEYCODE_SPACE)) -> openSearch()
            ctrl && e.isShiftPressed && e.keyCode == KeyEvent.KEYCODE_A -> openDrawer()
            ctrl && e.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> {
                val ws = s.workspaces.getOrNull(e.keyCode - KeyEvent.KEYCODE_1) ?: return true
                onWorkspaceSelected(ws.id)
            }
            // Alt+Tab brings the window furthest back to the front, so repeated presses go round all of them.
            e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_TAB -> {
                val next = s.windows.filter { it.ws == s.active && !it.minimized }.minByOrNull { it.z } ?: return false
                model.front { NativeCore.focusWindow(it, next.id) }
            }
            ctrl && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> onSwipeWorkspace(1)
            ctrl && e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> onSwipeWorkspace(-1)
            (e.isAltPressed && e.keyCode == KeyEvent.KEYCODE_F4) || (ctrl && e.keyCode == KeyEvent.KEYCODE_W) -> {
                // With a menu or dialog open, that is what closes, as on a desktop. Closing the window behind it
                // left a dialog acting for a window that was gone; App Manager's crashed LX when confirmed.
                if (!overlays.isEmpty) {
                    overlays.back()
                    return true
                }
                focused ?: return false
                model.edit { NativeCore.closeWindow(it, focused.id) }
            }
            ctrl && e.keyCode == KeyEvent.KEYCODE_M -> {
                focused ?: return false
                model.edit { NativeCore.setWindowState(it, focused.id, "minimized") }
            }
            ctrl && e.keyCode == KeyEvent.KEYCODE_DPAD_UP && !e.isAltPressed -> {
                focused ?: return false
                model.edit { NativeCore.toggleMaximize(it, focused.id) }
            }
            e.isMetaPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                focused ?: return false
                model.edit { NativeCore.setWindowState(it, focused.id, "snapped_left") }
            }
            e.isMetaPressed && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focused ?: return false
                model.edit { NativeCore.setWindowState(it, focused.id, "snapped_right") }
            }
            ctrl && e.keyCode == KeyEvent.KEYCODE_COMMA -> openTool("settings")
            ctrl && e.keyCode == KeyEvent.KEYCODE_E -> openTool("files")
            e.keyCode == KeyEvent.KEYCODE_ESCAPE && editMode -> setEditMode(false)
            else -> return false
        }
        return true
    }

    companion object {
        private const val TAG = "LxLauncher"
        /** Who has the phone's keyboard, logged each time it changes hands. */
        private const val INPUT_TAG = "LxInput"
        /** Timings of work a person waits for: startup, rebuilds, opening a window. */
        private const val PERF_TAG = "LxPerf"
        const val NO_WINDOW = -1

        /**
         * Which app window has the keyboard after a press: the window whose app content the press reached ([content]),
         * or the [holder] again when the press only reached that window's own frame ([frame]), or when nothing in LX took
         * the press ([handled] false): the margin beside a maximised app window and the gap above the taskbar lie just
         * past the keys at the keyboard's edges and bottom, and a tap that missed into them closed the keyboard. A press
         * that LX's bars, the desktop, a menu or another window's frame took gives the keyboard back to LX ([NO_WINDOW]).
         */
        fun keyboardAfterPress(holder: Int, content: Int, frame: Int, handled: Boolean): Int = when {
            content != NO_WINDOW -> content
            frame != NO_WINDOW -> if (frame == holder) holder else NO_WINDOW
            !handled -> holder
            else -> NO_WINDOW
        }

        private const val REQUEST_RECONFIGURE = 4203
        private const val REQUEST_BRIDGE_NOTIFICATIONS = 4204
        /** A frame and a little more: the window's new flags reach the keyboard's side of Android in the next layout. */
        private const val KEYBOARD_AFTER_FLAGS_MS = 80L
        /** Before Android 11: a covered strip lower than this is not a keyboard. */
        private const val KEYBOARD_MIN_DP = 80f
    }
}
