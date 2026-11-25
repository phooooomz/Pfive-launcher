// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.apps

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.view.View
import java.io.File
import java.util.concurrent.Executors

/** One launchable activity for one user profile. */
data class AppEntry(
    val key: String,              // "package/class#userSerial", the id stored by the native core
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val category: Int,
    val installTime: Long,
    val isWorkProfile: Boolean,
) {
    val packageName: String get() = component.packageName
}

/**
 * App discovery through LauncherApps, which lists launchable activities for
 * every profile the launcher may see without QUERY_ALL_PACKAGES.
 */
class AppRepository(private val context: Context) {
    fun interface Listener {
        fun onAppsChanged(apps: List<AppEntry>)
    }

    sealed interface PackageEvent {
        /** [userSerial] is the profile the app left; the same app in another profile is still installed. */
        data class Removed(val packageName: String, val userSerial: Long) : PackageEvent
        data class Changed(val packageName: String) : PackageEvent
    }

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "lx-apps").apply { isDaemon = true } }
    /**
     * App lists are read one at a time, the newest request last: an install fires several callbacks, and two reads on
     * the icon pool could finish out of order, an older list replacing a newer one.
     */
    private val lists = Executors.newSingleThreadExecutor { r -> Thread(r, "lx-apps-list").apply { isDaemon = true } }
    /** Main thread: the number of the newest list asked for; an older one that finishes late is dropped. */
    private var generation = 0
    private val reloadSoon = Runnable { readList() }
    /** Labels and install times kept between starts of LX's process; [lists] thread only. */
    private val labels = AppLabels(File(context.cacheDir, "app-labels"))
    /**
     * Icons drawn once per size into bitmaps, keyed by app and size and counted in bytes. Each caller gets its own
     * drawable over the shared bitmap, so two views of different sizes no longer move each other's bounds.
     */
    private val iconCache = object : LruCache<String, Bitmap>(iconCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pendingIcons = HashMap<String, MutableList<(Drawable) -> Unit>>()
    private val listeners = LinkedHashSet<Listener>()
    var onPackageEvent: ((PackageEvent) -> Unit)? = null

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    private var infos: Map<String, LauncherActivityInfo> = emptyMap()
    var loaded = false
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            // Freezing an app (Island and other profile owners hide it) is reported as a removal, and unfreezing as
            // an install. Its shortcuts, folder places and pins went with it for good; they stay while Android
            // still has the app installed.
            if (stillInstalled(packageName, user)) Log.i(TAG, "$packageName is hidden, not removed; its places in the layout stay")
            else onPackageEvent?.invoke(PackageEvent.Removed(packageName, userManager.getSerialNumberForUser(user)))
            reload()
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = reload()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = reload()

        private fun changed(pkg: String) {
            iconCache.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { iconCache.remove(it) }
            onPackageEvent?.invoke(PackageEvent.Changed(pkg))
            reload()
        }
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun addListener(l: Listener) {
        listeners += l
        if (loaded) l.onAppsChanged(apps)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /**
     * Whether Android still has [pkg] installed for [user], hidden or not. Only an app it no longer knows counts as
     * removed; when it cannot tell, the app's places stay, as a stale shortcut costs less than a lost layout.
     */
    internal fun stillInstalled(pkg: String, user: UserHandle): Boolean = try {
        val info = launcherApps.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, user)
        info != null && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not tell whether $pkg is still installed", e)
        true
    }

    /** Reads the app list again once package callbacks have been quiet for [RELOAD_DEBOUNCE_MS]. Main thread. */
    fun reload() {
        main.removeCallbacks(reloadSoon)
        // The first read at start shows the menu as soon as it can; later ones gather a burst of callbacks.
        if (!loaded) readList() else main.postDelayed(reloadSoon, RELOAD_DEBOUNCE_MS)
    }

    private fun readList() {
        main.removeCallbacks(reloadSoon)
        val gen = ++generation
        lists.execute {
            val started = SystemClock.uptimeMillis()
            // Labels follow the phone's languages, and a system update can change any app.
            val stamp = Build.FINGERPRINT + "|" + context.resources.configuration.locales.toLanguageTags()
            val known = labels.load(stamp)
            val seen = HashMap<String, AppLabels.Entry>()
            var keptCount = 0
            val list = ArrayList<AppEntry>()
            val infoMap = HashMap<String, LauncherActivityInfo>()
            val me = Process.myUserHandle()
            for (user in launcherApps.profiles) {
                val serial = userManager.getSerialNumberForUser(user)
                val activities = try {
                    launcherApps.getActivityList(null, user)
                } catch (e: SecurityException) {
                    Log.w(TAG, "profile not accessible", e)
                    emptyList()
                }
                for (info in activities) {
                    val cn = info.componentName
                    if (cn.packageName == context.packageName) continue
                    val key = "${cn.packageName}/${cn.className}#$serial"
                    val app = info.applicationInfo
                    // A game that only sets the older flag still belongs under Games.
                    @Suppress("DEPRECATION")
                    val category = if (app.flags and ApplicationInfo.FLAG_IS_GAME != 0) ApplicationInfo.CATEGORY_GAME else app.category
                    // The label opens the app's resources and the install time asks the package manager: both are
                    // kept from the last read while the app's APK is the same one.
                    val apk = app.sourceDir?.let { val f = File(it); "$it|${f.length()}|${f.lastModified()}" }.orEmpty()
                    val kept = known[key]?.takeIf { apk.isNotEmpty() && it.apk == apk }
                    if (kept != null) keptCount++
                    val entry = kept
                        ?: AppLabels.Entry(apk, info.firstInstallTime, info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName })
                    seen[key] = entry
                    list += AppEntry(key, entry.label, cn, user, category, entry.firstInstall, user != me)
                    infoMap[key] = info
                }
            }
            list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            if (seen != known) labels.save(stamp, seen)
            Log.i("LxPerf", "app list read in ${SystemClock.uptimeMillis() - started} ms (${list.size} apps, $keptCount labels kept)")
            main.post {
                if (gen != generation) return@post
                apps = list
                byKey = list.associateBy { it.key }
                infos = infoMap
                loaded = true
                for (l in listeners.toList()) l.onAppsChanged(list)
            }
        }
    }

    fun find(key: String): AppEntry? = byKey[key]

    fun firstForPackage(pkg: String): AppEntry? = apps.firstOrNull { it.packageName == pkg && !it.isWorkProfile }

    /** Loads the badged icon off the main thread, drawn at least [sizePx] across; the callback runs on main. */
    fun icon(key: String, sizePx: Int, onReady: (Drawable) -> Unit) {
        val side = iconBucket(sizePx)
        val cacheKey = "$key@$side"
        iconCache.get(cacheKey)?.let { onReady(drawableOf(it)); return }
        val info = infos[key] ?: return
        synchronized(pendingIcons) {
            val waiting = pendingIcons[cacheKey]
            if (waiting != null) {
                waiting += onReady
                return
            }
            pendingIcons[cacheKey] = mutableListOf(onReady)
        }
        val density = context.resources.displayMetrics.densityDpi.let { if (side > 128) it * 2 else it }
        worker.execute {
            val bitmap = try {
                render(info.getBadgedIcon(density), side)
            } catch (e: Exception) {
                Log.w(TAG, "icon failed for $key", e)
                null
            }
            main.post {
                val callbacks = synchronized(pendingIcons) { pendingIcons.remove(cacheKey) }.orEmpty()
                if (bitmap != null) {
                    iconCache.put(cacheKey, bitmap)
                    callbacks.forEach { it(drawableOf(bitmap)) }
                }
            }
        }
    }

    /** The icon at [sizePx] if it is already drawn; otherwise null and [icon] loads it. */
    fun cachedIcon(key: String, sizePx: Int): Drawable? = iconCache.get("$key@${iconBucket(sizePx)}")?.let { drawableOf(it) }

    private fun drawableOf(bitmap: Bitmap): Drawable = BitmapDrawable(context.resources, bitmap)

    private fun render(d: Drawable, side: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, side, side)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Android tells a launcher when to give memory back. Icons stay while LX is merely behind an app (they are
     * drawn again in a moment); under pressure half of them go, and all of them when the process is about to be
     * killed.
     */
    fun trimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> iconCache.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> iconCache.trimToSize(iconCache.maxSize() / 2)
        }
    }

    sealed interface LaunchResult {
        data object Ok : LaunchResult
        data object Missing : LaunchResult
        data class Failed(val reason: String) : LaunchResult
    }

    /** The shape an app asks for in its manifest, which decides the shape of its window. */
    enum class WindowShape { PORTRAIT, LANDSCAPE, ANY }

    /** Where the next app window of the given shape should open, or null for full screen. Set by the launcher. */
    var windowBounds: ((WindowShape) -> Rect?)? = null

    /**
     * Launch options for an app, a shortcut or a default app: the scale-up from
     * [source], plus a window request when [windowBounds] gives one. Android
     * ignores the window where apps cannot float, and the app opens full screen.
     *
     * Launch bounds alone only take effect on a display that is already in
     * freeform mode (a tablet in desktop mode, Waydroid). On a phone the task must
     * also ask for the freeform windowing mode. The public setter for that,
     * ActivityOptions.setLaunchWindowingMode, is a test API that Android blocks for
     * apps, but the system reads the mode back from this bundle key. Without
     * freeform support Android is expected to open the app full screen; that is
     * not yet tested on such a device. See
     * docs/architecture/ADR-005-app-windows.md before changing this.
     *
     * A game asks for the full-screen mode instead, so it fills the display and Android turns the
     * screen the way the game asks or the way the phone is held.
     */
    fun launchOptions(source: View?, shape: WindowShape = WindowShape.ANY, fullScreen: Boolean = false): Bundle? {
        val opts = source?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height) }
        if (fullScreen) {
            return (opts ?: ActivityOptions.makeBasic()).toBundle().apply { putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FULLSCREEN) }
        }
        val bounds = windowBounds?.invoke(shape) ?: return opts?.toBundle()
        return (opts ?: ActivityOptions.makeBasic()).setLaunchBounds(bounds).toBundle().apply {
            putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
        }
    }

    /** The orientation the app's activity asks for. LauncherActivityInfo exposes it from Android 12; earlier, the package manager does. */
    private fun orientationOf(key: String): Int? {
        val info = infos[key] ?: return null
        return if (Build.VERSION.SDK_INT >= 31) info.activityInfo.screenOrientation
        else runCatching { context.packageManager.getActivityInfo(info.componentName, 0).screenOrientation }.getOrNull()
    }

    fun shapeOf(key: String): WindowShape = shapeFor(orientationOf(key))

    /** The window shape an ActivityInfo.screenOrientation value asks for; null is an app with no entry. */
    fun shapeFor(orientation: Int?): WindowShape = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> WindowShape.PORTRAIT
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> WindowShape.LANDSCAPE
        else -> WindowShape.ANY
    }

    /** The app says it is a game, by its category or by the older flag some games still set instead; the menu lists it under Games. */
    fun isGame(key: String): Boolean = byKey[key]?.category == ApplicationInfo.CATEGORY_GAME

    private fun screenRect(v: View?): Rect? = v?.let {
        val loc = IntArray(2).also { a -> it.getLocationOnScreen(a) }
        Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
    }

    /** Starts the app through LauncherApps, in a window when one is requested. */
    fun launch(key: String, source: View?): LaunchResult {
        val app = byKey[key] ?: return LaunchResult.Missing
        return try {
            launcherApps.startMainActivity(app.component, app.user, screenRect(source),
                launchOptions(source, shapeOf(key), fullScreen = isGame(key)))
            LaunchResult.Ok
        } catch (e: ActivityNotFoundException) {
            LaunchResult.Missing
        } catch (e: SecurityException) {
            LaunchResult.Failed(e.message ?: "security")
        } catch (e: IllegalStateException) {
            LaunchResult.Failed(e.message ?: "state")
        }
    }

    fun openAppInfo(key: String, source: View?) {
        val app = byKey[key] ?: return
        try {
            launcherApps.startAppDetailsActivity(app.component, app.user, null, null)
        } catch (e: Exception) {
            // A ROM without that screen threw here, from a menu tap, and LX went down with it.
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (again: Exception) {
                Log.w(TAG, "no app info screen for ${app.packageName}", again)
            }
        }
    }

    /** Hands over to Android's own uninstall confirmation dialog. */
    fun requestUninstall(key: String) {
        val app = byKey[key] ?: return
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppInfo(key, null)
        }
    }

    fun isSystemApp(key: String): Boolean {
        val info = infos[key] ?: return false
        return info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    /** App shortcuts are only available while LX-LAUNCHER is the default Home. */
    fun canReadShortcuts(): Boolean = try {
        launcherApps.hasShortcutHostPermission()
    } catch (e: Exception) {
        false
    }

    fun shortcuts(key: String): List<ShortcutInfo> {
        val app = byKey[key] ?: return emptyList()
        if (!canReadShortcuts()) return emptyList()
        return try {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            launcherApps.getShortcuts(q, app.user).orEmpty().filter { it.isEnabled }.take(5)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun startShortcut(info: ShortcutInfo, source: View?): Boolean = try {
        // A game's shortcut opens the game, so it opens full screen as the game does.
        val game = byKey.values.any { it.packageName == info.`package` && it.category == ApplicationInfo.CATEGORY_GAME }
        launcherApps.startShortcut(info, screenRect(source), launchOptions(source, fullScreen = game))
        true
    } catch (e: Exception) {
        Log.w(TAG, "shortcut failed", e)
        false
    }

    /** The device reports freeform windows, or they were turned on in Developer options (read when readable). */
    fun freeformSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) || freeformSwitchOn()

    /** "Enable freeform windows" in Developer options; takes effect after a restart. False when it cannot be read. */
    fun freeformSwitchOn(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) != 0
    }.getOrDefault(false)

    companion object {
        private const val TAG = "LxApps"
        private const val RELOAD_DEBOUNCE_MS = 300L
        /** Icons are drawn at multiples of this many pixels, so near sizes share one bitmap. */
        private const val ICON_STEP = 24

        fun iconBucket(sizePx: Int): Int = ((sizePx.coerceAtLeast(1) + ICON_STEP - 1) / ICON_STEP) * ICON_STEP

        /** An eighth of the heap LX may use, at most 24 MB: about 600 icons at 96 px. */
        private fun iconCacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024).toInt()
        /** ActivityOptions.KEY_LAUNCH_WINDOWING_MODE and WindowConfiguration.WINDOWING_MODE_FREEFORM, stable since Android 9. */
        private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"
        private const val WINDOWING_MODE_FREEFORM = 5
        /** WindowConfiguration.WINDOWING_MODE_FULLSCREEN. */
        private const val WINDOWING_MODE_FULLSCREEN = 1
    }
}
