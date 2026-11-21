// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.lxlauncher.R
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.core.NativeCore
import com.lxlauncher.core.WindowItem
import com.lxlauncher.ui.Shell
import com.lxlauncher.ui.Ui
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.ui.windows.ToolView
import kotlin.math.roundToInt

/**
 * An Android app inside an LX window: the app runs on its own display, which lxdb creates, and that
 * display draws into a TextureView here. Touches, mouse, keys and Back go
 * the other way. The window's title holds the app key, so the window reopens the app after LX restarts.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class EmbeddedAppTool(private val shell: Shell, private val windowId: Int, val appKey: String) : ToolView {
    private enum class Phase { IDLE, STARTING, RUNNING, BLOCKED, FAILED, LOST, MISSING }

    /** A display of [displayW] by [displayH] drawn at [drawW] by [drawH], centred in the window. */
    data class Fit(val displayW: Int, val displayH: Int, val drawW: Int, val drawH: Int)

    private val ctx = shell.activity
    private val theme: LxTheme get() = shell.ui
    private val client = EmbedClient.get(ctx)
    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var failure = 0
    private var shown = false
    private var destroyed = false
    /** Stops this window's wait for lxdb (see [EmbedClient.withService]). */
    private var stopWaiting: () -> Unit = {}
    private var surface: Surface? = null
    /** The texture's size. It fills [slotW] by [slotH], the window's content, unless the app asks for another shape. */
    private var viewW = 0
    private var viewH = 0
    private var slotW = 0
    private var slotH = 0
    /** How much of the view the last frame covers while a new size is on its way; 1 when it fills it. */
    private var heldX = 1f
    private var heldY = 1f
    /** A new size went to the display at this time; the first frame after it replaces the held one. */
    private var resizedAt = 0L
    /** The display keeps its size while the phone's keyboard shortens the window; the view shows its top part. */
    private var showingTop = false
    /** True while the window is being resized by a drag (see [onLiveResize]). */
    private var liveResize = false
    /** Reused for the texture's transform and for scaling each input event; TextureView and MotionEvent copy it. */
    private val transform = Matrix()
    private val eventScale = Matrix()
    /** The texture's transform, read for each input event (see [forward]). */
    private val drawn = Matrix()
    /** Since when a start has waited for the view to fit LX's window ([sizeSettled]); 0 when none waits. */
    private var settlingSince = 0L
    private var titleView: TextView? = null
    private var iconView: ImageView? = null
    private var iconLoaded = false

    /** The display this window shows, shared with a rebuilt copy of the window (a theme change rebuilds every view). */
    private val session: EmbedSession = adoptOrCreate()

    private val texture = TextureView(ctx).apply {
        isOpaque = false
        // Holding Android's focus is how the app keeps the hardware keyboard (see keysGoToApp). The window
        // frame shows which window has focus, so the view draws no highlight over the app.
        isFocusable = true
        isFocusableInTouchMode = true
        defaultFocusHighlightEnabled = false
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = surfaceReady(st, width, height)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = sizeChanged(width, height)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = surfaceGone()
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = frameDrawn()
        }
        setOnTouchListener { v, ev ->
            // A tap in the app gives it the keyboard; tapping one of LX's own text fields takes it back.
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!v.isFocused) v.requestFocus()
                if (phase == Phase.RUNNING) shell.touchedApp(this@EmbeddedAppTool.windowId)
            }
            forward(ev)
        }
        setOnGenericMotionListener { _, ev -> forward(ev) }
        setOnHoverListener { _, ev -> forward(ev) }
    }
    private val spinner = ProgressBar(ctx).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(theme.accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val message = Ui.text(ctx, theme, "", theme.textBody, theme.textPrimary, lines = 0).apply {
        gravity = Gravity.CENTER
        setLineSpacing(theme.dp(3f), 1f)
    }
    private val actions = Ui.row(ctx, vertical = true, gravity = Gravity.CENTER_HORIZONTAL)
    private val panel = ScrollView(ctx).apply {
        isFillViewport = true
        // A frame the app drew before it stopped may still be on the texture; the panel covers it.
        background = ColorDrawable(LxTheme.withAlpha(theme.panel, 1f))
        addView(Ui.row(ctx, vertical = true, gravity = Gravity.CENTER).apply {
            val p = theme.dpi(20f)
            setPadding(p, p, p, p)
            addView(spinner, Ui.lp(theme.dpi(36f), theme.dpi(36f)).apply { bottomMargin = theme.dpi(12f) })
            addView(message, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(actions, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(12f) })
        })
    }

    override val view: View = object : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            placeTexture(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        setBackgroundColor(LxTheme.withAlpha(theme.panel, 1f))
        addView(texture, Ui.frame(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        addView(panel, Ui.frame(Ui.MATCH, Ui.MATCH))
    }

    override val fillsFrame = true

    /** The app's keyboard and controls reach the window's edges; a resize border over them took the app's taps. */
    override val keepsEdgeTouches = true

    private val appsListener = AppRepository.Listener { appsChanged() }
    private val statusListener = EmbedClient.Listener { statusChanged(it) }
    /**
     * The listeners above live in process-wide objects. LauncherActivity does not end its windows' tools when
     * Android destroys it (an unhandled configuration change, for one), so this window ends with the activity:
     * its listeners go, and a running app waits for the recreated window as it does after a theme change.
     */
    private val activityEnd = ActivityEnd(ctx.application, ctx) { onDestroy() }

    // ---- title bar ----

    override fun decorate(title: TextView, icon: ImageView): Boolean {
        titleView = title
        iconView = icon
        iconLoaded = false
        updateTitle()
        return true
    }

    private fun label(): String = shell.apps.find(appKey)?.label ?: appKey.substringBefore('/')

    private fun updateTitle() {
        val label = label()
        titleView?.text = label
        texture.contentDescription = label
        val icon = iconView ?: return
        if (iconLoaded) return
        shell.apps.icon(appKey, theme.dpi(18f)) { d ->
            if (destroyed) return@icon
            iconLoaded = true
            // The real icon replaces the tinted line glyph the frame drew.
            icon.imageTintList = null
            icon.setImageDrawable(d)
        }
    }

    // ---- lifecycle ----

    private fun adoptOrCreate(): EmbedSession {
        val existing = client.sessions[windowId]
        if (existing != null && existing.key == appKey && !existing.closed) {
            existing.adopt(this)
            return existing
        }
        existing?.release()
        return EmbedSession(windowId, appKey, client).also {
            it.owner = this
            client.register(it)
        }
    }

    private fun owns() = !destroyed && session.owner === this

    override fun onShown() {
        shown = true
        main.removeCallbacks(releaseTexture)
        // Putting the view back gives it a new surface; surfaceReady hands it to the display.
        if (texture.parent == null) (view as ViewGroup).addView(texture, 0, Ui.frame(Ui.MATCH, Ui.MATCH, Gravity.CENTER))
        val id = session.displayId
        if (id >= 0) {
            surface?.let { s -> call { it.setSurface(id, s) } }
            claimKeys()
        } else if (waitingToStart()) start()
    }

    /** A failed or lost app waits for Try again instead of starting each time the window shows. */
    private fun waitingToStart() = phase == Phase.IDLE || phase == Phase.BLOCKED

    override fun onHidden() {
        shown = false
        val id = session.displayId
        // A minimised window, or one on another workspace, keeps its app running: its display stays on and draws
        // nowhere until the window shows again. Only closing the window ends the app.
        if (id >= 0) call { it.setSurface(id, null) }
        main.removeCallbacks(releaseTexture)
        main.postDelayed(releaseTexture, RELEASE_HIDDEN_MS)
    }

    /**
     * A window that stays hidden lets go of its texture view, whose last frame is a buffer the size of the display
     * (about 4 MB at 720x1600). The app keeps running; the view comes back with the window.
     */
    private val releaseTexture = Runnable {
        if (!shown && !destroyed && texture.parent != null) (texture.parent as ViewGroup).removeView(texture)
    }

    override fun onBack(): Boolean {
        if (phase != Phase.RUNNING) return false
        val now = SystemClock.uptimeMillis()
        sendKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        sendKey(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
        return true
    }

    /** A hardware key LX received while this window had focus (see LauncherActivity.forwardKey). */
    fun onKey(event: KeyEvent): Boolean {
        if (phase != Phase.RUNNING) return false
        // Android's focus may still sit in a window behind this one; it follows the keys here.
        if (event.action == KeyEvent.ACTION_DOWN && !texture.isFocused) {
            shell.appClaimsKeyboard(windowId)
            texture.requestFocus()
        }
        sendKey(event)
        return true
    }

    /**
     * The focused app window takes the keyboard when its app starts or the window shows again, unless one of
     * LX's own text fields has it. Without this, the first key typed after a tap elsewhere would land on
     * whatever view Android picks when it leaves touch mode.
     */
    private fun claimKeys() {
        if (phase != Phase.RUNNING || !shown || texture.isFocused) return
        val focusedWindow = shell.model.snapshot.windows.firstOrNull { it.id == windowId }?.focused == true
        val current = ctx.currentFocus
        if (focusedWindow && (current == null || !current.onCheckIsTextEditor())) {
            shell.appClaimsKeyboard(windowId)
            texture.requestFocus()
        }
    }

    override fun onDestroy() {
        if (destroyed) return
        stopWaiting()
        main.removeCallbacks(releaseTexture)
        activityEnd.cancel()
        main.removeCallbacks(resizeTask)
        main.removeCallbacks(settledStart)
        main.removeCallbacks(shapeTimeout)
        shell.apps.removeListener(appsListener)
        client.removeListener(statusListener)
        val mine = owns()
        destroyed = true
        if (!mine) return
        // A rebuild destroys every window and builds it again right away; closing removes it from the model, and the
        // app goes with its window.
        val stillOpen = shell.model.snapshot.windows.any { it.id == windowId }
        if (stillOpen && session.displayId >= 0) session.park() else session.close()
    }

    // ---- starting ----

    private fun appsChanged() {
        updateTitle()
        if (phase == Phase.IDLE || phase == Phase.MISSING) start()
    }

    private fun statusChanged(status: EmbedClient.Status) {
        val lxdbBack = status == EmbedClient.Status.READY && client.serviceRunning
        when {
            // A window that gave up on lxdb, or lost its app when lxdb ended, opens the app again once lxdb runs.
            lxdbBack && (phase == Phase.LOST || (phase == Phase.FAILED && failure == R.string.embed_service_failed)) -> retry()
            status == EmbedClient.Status.READY && phase == Phase.BLOCKED -> start()
            phase == Phase.BLOCKED || phase == Phase.LOST || phase == Phase.STARTING -> render()
        }
    }

    private fun start() {
        if (!owns() || !shown || session.displayId >= 0 || phase == Phase.STARTING) return
        if (!shell.apps.loaded) return
        val entry = shell.apps.find(appKey) ?: return setPhase(Phase.MISSING)
        client.refresh()
        // A changed status reaches statusChanged inside refresh(), which may have started the app already.
        if (phase == Phase.STARTING || session.displayId >= 0) return
        if (client.status != EmbedClient.Status.READY) return setPhase(Phase.BLOCKED)
        val s = surface ?: return  // surfaceReady starts again once the texture exists
        if (viewW <= 0 || viewH <= 0 || slotW <= 0 || slotH <= 0) return
        if (!sizeSettled()) return startWhenSettled()
        settlingSince = 0L
        setPhase(Phase.STARTING)
        val dpi = densityDpi()
        val first = fit(slotW, slotH, dpi, shape())
        val w = first.displayW
        val h = first.displayH
        sizeBuffer(w, h)
        val component = entry.component
        // UserHandle's identifier is hidden; its hash code has always been that identifier.
        val userId = entry.user.hashCode()
        stopWaiting()
        stopWaiting = client.withService { svc ->
            if (!owns()) return@withService
            if (svc == null) {
                // LX Bridge can be paired and still fail to start the helper.
                if (client.status == EmbedClient.Status.READY) {
                    failure = R.string.embed_service_failed
                    setPhase(Phase.FAILED)
                } else setPhase(Phase.BLOCKED)
                return@withService
            }
            client.worker.execute {
                val id = try {
                    EmbedProtocol.create(svc, s, w, h, dpi, "app$windowId", session.callback)
                } catch (e: RemoteException) {
                    EmbedProtocol.ERR_FAILED
                }
                val result = if (id < 0) id else try {
                    svc.start(id, userId, component.packageName, EmbedProtocol.launchIntent(component), EmbedProtocol.launchOptions(id))
                } catch (e: RemoteException) {
                    EmbedProtocol.ERR_FAILED
                }
                main.post { started(svc, id, result, w, h, dpi) }
            }
        }
    }

    private fun started(svc: IEmbedService, id: Int, result: Int, w: Int, h: Int, dpi: Int) {
        if (id >= 0 && (result != EmbedProtocol.OK || !owns() || session.closed)) {
            client.worker.execute { runCatching { svc.release(id) } }
        }
        if (!owns()) return
        if (id < 0 || result != EmbedProtocol.OK) {
            failure = failureMessage(id, result)
            return setPhase(Phase.FAILED)
        }
        session.attach(svc, id, w, h, dpi)
        session.slotW = slotW
        session.slotH = slotH
        // The texture may have changed size while the app was starting, which reset its buffer.
        sizeBuffer(w, h)
        showTop(false)
        setPhase(Phase.RUNNING)
        // The window may have been hidden, or its texture replaced, while the app was starting.
        val s = surface
        if (!shown || s == null) call { it.setSurface(id, null) } else call { it.setSurface(id, s) }
        scheduleResize()
        claimKeys()
    }

    /** Called by the session: the app finished (the window closes) or never arrived on the display. */
    internal fun displayEmpty(hadTask: Boolean) {
        if (!owns()) return
        if (hadTask) {
            shell.model.edit { NativeCore.closeWindow(it, windowId) }
            return
        }
        session.releaseDisplay()
        failure = R.string.embed_start_failed
        setPhase(Phase.FAILED)
    }

    /** Called by the session: the app asks for another orientation than the display has. */
    internal fun orientationChanged(orientation: Int) {
        if (!owns()) return
        val wanted = shell.apps.shapeFor(orientation)
        if (wanted == shape()) return
        session.shape = wanted
        view.requestLayout()
        scheduleResize()
    }

    /** Called by the session when lxdb ended and took the display with it. */
    internal fun serviceLost() {
        if (!owns()) return
        if (phase == Phase.RUNNING || phase == Phase.STARTING) setPhase(Phase.LOST)
    }

    // ---- surface and size ----

    private fun surfaceReady(st: SurfaceTexture, width: Int, height: Int) {
        viewW = width
        viewH = height
        surface?.release()
        val s = Surface(st)
        surface = s
        val id = session.displayId
        if (id >= 0 && owns()) {
            sizeBuffer(session.width, session.height)
            if (shown) call { it.setSurface(id, s) }
            scheduleResize()
        } else if (waitingToStart()) start()
    }

    private fun sizeChanged(width: Int, height: Int) {
        viewW = width
        viewH = height
        // TextureView has just set the buffer to its own size.
        if (session.displayId >= 0) {
            sizeBuffer(session.width, session.height)
            // The top view is worked out from the view's size too, so it follows the new size.
            if (showingTop) showTop(true) else holdShape()
        }
        if (settlingSince > 0) startWhenSettled()
        scheduleResize()
    }

    /**
     * Draws the last frame in a view of a new size without stretching it, from the top left corner. The app
     * lays itself out once the size has settled, so a drag does not make it start over at every step.
     */
    private fun holdShape() {
        if (showingTop || session.width <= 0 || session.height <= 0 || viewW <= 0 || viewH <= 0) return
        val k = minOf(viewW.toFloat() / session.width, viewH.toFloat() / session.height)
        heldX = session.width * k / viewW
        heldY = session.height * k / viewH
        texture.setTransform(transform.apply { setScale(heldX, heldY) })
    }

    private fun releaseShape() {
        main.removeCallbacks(shapeTimeout)
        resizedAt = 0L
        if (heldX == 1f && heldY == 1f) return
        heldX = 1f
        heldY = 1f
        if (!showingTop) texture.setTransform(null)
    }

    private val shapeTimeout = Runnable { releaseShape() }

    /** The app's own frame at its new size has arrived, unless this one was still on its way when the size changed. */
    private fun frameDrawn() {
        if (resizedAt > 0 && SystemClock.uptimeMillis() - resizedAt >= FRAME_AFTER_RESIZE_MS) releaseShape()
    }

    /**
     * Keeps the texture's buffer at the display's size, for the view to scale. With a smaller buffer Android
     * draws the display at full size and cuts it off, which lost the right side of a scaled-down app.
     */
    private fun sizeBuffer(w: Int, h: Int) {
        if (w > 0 && h > 0) texture.surfaceTexture?.setDefaultBufferSize(w, h)
    }

    /** The shape the app asks for: what it said on its display, or else what its manifest says. */
    private fun shape() = session.shape ?: shell.apps.shapeOf(appKey)

    /** Sizes the texture inside a window content of [width] by [height], during measuring. */
    private fun placeTexture(width: Int, height: Int) {
        slotW = width
        slotH = height
        if (width <= 0 || height <= 0) return
        val f = fit(width, height, densityDpi(), shape())
        val lp = texture.layoutParams as FrameLayout.LayoutParams
        lp.width = f.drawW
        lp.height = f.drawH
    }

    /**
     * Windows LX reopens after a restart first lay out larger than LX's own window, for some hundred
     * milliseconds: Settings got a 1048x1796 display on a 720x1600 phone, shrank to 656x1084, and kept parts of
     * the bigger layout. A window opened from a search box is shorter than it will be until the keyboard goes.
     * A start waits for both, or [SETTLE_MAX_MS] at most (a window dragged partly off the screen never fits).
     */
    private fun sizeSettled(): Boolean {
        val root = texture.rootView
        if (slotW <= root.width && slotH <= root.height && !keyboardShown()) return true
        if (settlingSince == 0L) settlingSince = SystemClock.uptimeMillis()
        return SystemClock.uptimeMillis() - settlingSince >= SETTLE_MAX_MS
    }

    private fun startWhenSettled() {
        main.removeCallbacks(settledStart)
        main.postDelayed(settledStart, SETTLE_MS)
    }

    private val settledStart = Runnable { if (waitingToStart()) start() }

    private fun surfaceGone(): Boolean {
        val id = session.displayId
        if (id >= 0 && owns()) call { it.setSurface(id, null) }
        surface?.release()
        surface = null
        return true
    }

    private fun densityDpi() = ctx.resources.displayMetrics.densityDpi

    /**
     * A live resize changes the view on every frame, and each display resize makes the app lay itself out
     * again; many apps start over for it. The display follows once the size has stayed put for
     * [RESIZE_SETTLE_MS], and [holdShape] keeps the last frame in shape meanwhile.
     */
    private fun scheduleResize() {
        main.removeCallbacks(resizeTask)
        if (!liveResize) main.postDelayed(resizeTask, RESIZE_SETTLE_MS)
    }

    /**
     * While a resize drag lasts the display keeps its size, however long the finger rests: each display resize is a
     * configuration change that many apps start over on, and a pause in the middle of a drag made the app do so before
     * the drag was over. The last frame follows the edge scaled ([holdShape]); the display takes the size once, at the end.
     */
    override fun onLiveResize(active: Boolean) {
        liveResize = active
        scheduleResize()
    }

    private val resizeTask = Runnable {
        val id = session.displayId
        val dpi = densityDpi()
        if (id < 0 || !owns() || viewW <= 0 || viewH <= 0 || slotW <= 0 || slotH <= 0) return@Runnable
        val f = fit(slotW, slotH, dpi, shape())
        val w = f.displayW
        val h = f.displayH
        // LX lifts its windows above the keyboard of its own text fields by making them shorter. A display
        // resize is a configuration change that many apps start over on, so the display keeps its size for that.
        // The window decides, not the display size: a short window can grow into a display of another width.
        showTop(keyboardShown() && slotW == session.slotW && slotH < session.slotH && dpi == session.dpi)
        if (showingTop) return@Runnable
        if (w == session.width && h == session.height && dpi == session.dpi) return@Runnable releaseShape()
        resizedAt = SystemClock.uptimeMillis()
        main.removeCallbacks(shapeTimeout)
        main.postDelayed(shapeTimeout, SHAPE_TIMEOUT_MS)
        session.width = w
        session.height = h
        session.dpi = dpi
        session.slotW = slotW
        session.slotH = slotH
        sizeBuffer(w, h)
        call { it.resize(id, w, h, dpi) }
    }

    private fun keyboardShown() = Build.VERSION.SDK_INT >= 30 &&
        texture.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /**
     * Draws the display at its own scale from the top when [on] (the bottom lies behind the keyboard), else fitted.
     * The top view replaces a held shape; turning it off holds the last frame in shape again until the display has
     * the view's size. A held shape left behind it drew one size while taps used another.
     */
    private fun showTop(on: Boolean) {
        if (!on && !showingTop) return
        showingTop = on
        heldX = 1f
        heldY = 1f
        if (on) {
            val fitted = session.height.toFloat() * viewW / session.width
            texture.setTransform(transform.apply { setScale(1f, fitted / viewH) })
        } else {
            texture.setTransform(null)
            holdShape()
        }
    }

    // ---- input ----

    /**
     * Sends a touch, mouse or stylus event on the view to the display. The point is mapped back through the transform
     * the view draws the display with, read from the view itself, so a tap lands where the person saw it in every state:
     * fitted, held in shape during a resize, or showing the top above LX's keyboard. Scale factors kept apart from the
     * transform went stale when one state followed another, and taps on the keyboard reached the app's page instead,
     * where a browser took them as a tap off its text field and put the keyboard away.
     */
    private fun forward(ev: MotionEvent): Boolean {
        val id = session.displayId
        if (phase != Phase.RUNNING || id < 0 || !owns() || viewW <= 0 || viewH <= 0) return false
        texture.getTransform(drawn)
        if (!viewToDisplay(drawn, viewW, viewH, session.width, session.height, eventScale)) return false
        val copy = displayEvent(ev, eventScale)
        call { it.injectMotion(id, copy) }
        copy.recycle()
        return true
    }

    private fun sendKey(event: KeyEvent) {
        val id = session.displayId
        if (id >= 0 && owns()) call { it.injectKey(id, event) }
    }

    /** A one-way call to the service from the main thread; a dead service shows up through the session. */
    private inline fun call(block: (IEmbedService) -> Unit) {
        val svc = session.service ?: return
        try {
            block(svc)
        } catch (_: RemoteException) {
        }
    }

    // ---- what the window shows ----

    private fun setPhase(p: Phase) {
        phase = p
        render()
    }

    private fun render() {
        if (destroyed) return
        val running = phase == Phase.RUNNING
        panel.visibility = if (running || phase == Phase.IDLE) View.GONE else View.VISIBLE
        spinner.visibility = if (phase == Phase.STARTING) View.VISIBLE else View.GONE
        actions.removeAllViews()
        val name = label()
        // lxdb failed to start at least once and LX is trying again, as can happen for a minute after a restart.
        val waitingForBridge = phase == Phase.STARTING && !client.serviceRunning && client.failedStarts > 0
        message.text = when (phase) {
            Phase.IDLE, Phase.RUNNING -> ""
            Phase.STARTING -> ctx.getString(if (waitingForBridge) R.string.embed_bridge_retrying else R.string.embed_opening, name)
            Phase.MISSING -> ctx.getString(R.string.launch_missing)
            Phase.FAILED -> ctx.getString(failure, name)
            Phase.LOST -> ctx.getString(R.string.embed_lost, name)
            Phase.BLOCKED -> ctx.getString(when (client.status) {
                EmbedClient.Status.UNSUPPORTED -> R.string.embed_needs_android
                EmbedClient.Status.NOT_SET_UP, EmbedClient.Status.READY -> R.string.bridge_setup
                EmbedClient.Status.NEEDS_WIFI -> R.string.bridge_needs_wifi
            }, name)
        }
        when (phase) {
            Phase.IDLE, Phase.RUNNING -> {}
            Phase.STARTING -> if (waitingForBridge) button(R.string.embed_open_android) { openAsAndroidWindow() }
            Phase.MISSING -> button(R.string.action_close, primary = true) { close() }
            Phase.BLOCKED -> {
                when (client.status) {
                    // NEEDS_WIFI only comes from LX Bridge, which runs on Android 14 and later.
                    EmbedClient.Status.NEEDS_WIFI -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        button(R.string.bridge_open_wifi, primary = true) { shell.startSafely(Intent(Settings.Panel.ACTION_WIFI)) }
                    }
                    EmbedClient.Status.NOT_SET_UP -> {
                        button(R.string.bridge_connect, primary = true) { shell.pairBridge() }
                        button(R.string.bridge_connect_code) { shell.pairBridgeWithCode() }
                    }
                    else -> {}
                }
                button(R.string.embed_open_android) { openAsAndroidWindow() }
            }
            Phase.FAILED, Phase.LOST -> {
                button(R.string.action_retry, primary = true) { retry() }
                button(R.string.embed_open_android) { openAsAndroidWindow() }
                if (phase == Phase.FAILED) actions.addView(Ui.button(ctx, theme, ctx.getString(R.string.embed_always_android, name)) {
                    client.setAlwaysAndroid(appKey, true)
                    openAsAndroidWindow()
                }.apply { maxLines = 2 })
            }
        }
    }

    private fun button(label: Int, primary: Boolean = false, onClick: () -> Unit) {
        actions.addView(Ui.button(ctx, theme, ctx.getString(label), primary) { onClick() }.apply { maxLines = 2 },
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = theme.dpi(6f) })
    }

    private fun retry() {
        session.releaseDisplay()
        view.requestLayout()
        setPhase(Phase.IDLE)
        start()
    }

    private fun close() {
        shell.model.edit { NativeCore.closeWindow(it, windowId) }
    }

    /** Android's own window for this app, the way LX opens apps without its frame; this window closes. */
    private fun openAsAndroidWindow() {
        when (val r = shell.apps.launch(appKey, null)) {
            AppRepository.LaunchResult.Ok -> {
                session.releaseDisplay()
                shell.model.edit { NativeCore.pin(it, NativeCore.LIST_RECENT, appKey, 0) }
                close()
            }
            AppRepository.LaunchResult.Missing -> shell.notify(ctx.getString(R.string.launch_missing))
            is AppRepository.LaunchResult.Failed -> shell.notify(ctx.getString(R.string.launch_failed, r.reason))
        }
    }

    init {
        if (session.displayId >= 0) phase = Phase.RUNNING
        render()
        shell.apps.addListener(appsListener)
        client.addListener(statusListener)
    }

    companion object {
        const val KIND = "app"
        private const val PER_USER_RANGE = 100000
        private const val RESIZE_SETTLE_MS = 180L
        private const val FRAME_AFTER_RESIZE_MS = 50L
        /** An app that draws nothing new, such as a still screen, still leaves the held shape. */
        private const val SHAPE_TIMEOUT_MS = 500L
        private const val SETTLE_MS = 100L
        private const val SETTLE_MAX_MS = 1_500L
        /** How long a hidden window keeps its texture view, in case it comes right back. */
        private const val RELEASE_HIDDEN_MS = 5_000L
        private const val MIN_SHORT_DP = 320f
        /** Width to height of a portrait app in a wide window: still portrait to Android, and as big as can be. */
        private const val PORTRAIT_RATIO = 0.9f
        /** Height to width of a landscape app in a tall window: the shape of a video. */
        private const val LANDSCAPE_RATIO = 9f / 16f

        /**
         * The display for a view of [w] by [h] pixels: the view itself, or, for a view too small for a phone
         * layout, the same shape grown until it is one. The window then shows the whole app, scaled down,
         * instead of the top of an app laid out for a size no phone has. Never past what the service accepts.
         */
        fun displaySize(w: Int, h: Int, dpi: Int): Pair<Int, Int> {
            val grow = maxOf(1f, MIN_SHORT_DP * (dpi / 160f) / minOf(w, h)).coerceAtMost(EmbedProtocol.MAX_SIZE.toFloat() / maxOf(w, h))
            return (w * grow).roundToInt() to (h * grow).roundToInt()
        }

        /**
         * How a window content of [w] by [h] shows an app that asks for [shape]. An Android display cannot turn
         * with its app, so an app whose shape the window lacks gets a display of its own shape, drawn whole
         * inside the window: Android would otherwise letterbox it and lose part of it.
         */
        fun fit(w: Int, h: Int, dpi: Int, shape: AppRepository.WindowShape): Fit {
            var drawW = w
            var drawH = h
            if (shape == AppRepository.WindowShape.PORTRAIT && w >= h) drawW = (h * PORTRAIT_RATIO).roundToInt()
            if (shape == AppRepository.WindowShape.LANDSCAPE && h >= w) drawH = (w * LANDSCAPE_RATIO).roundToInt()
            val (displayW, displayH) = displaySize(drawW, drawH, dpi)
            return Fit(displayW, displayH, drawW, drawH)
        }

        /** The app an LX window of kind [KIND] shows; its title field holds the key. */
        fun appKeyOf(w: WindowItem): String? = w.title.takeIf { w.kind == KIND }

        /** What the taskbar and other lists should call an app window: the app's name, not its key. */
        fun windowLabel(apps: AppRepository, w: WindowItem): String =
            if (w.kind == KIND) apps.find(w.title)?.label ?: w.title.substringBefore('/') else w.title

        /**
         * Whether a key pressed while an app window is LX's focused window belongs to the app, given the view
         * that holds Android's focus. LX keeps it while one of its own controls outside the windows has focus
         * (a text field on the desktop, the bar during keyboard navigation, the soft keyboard's Delete and
         * Enter for those fields) and while focus is on the app window's own title bar buttons. Focus left in
         * a window behind does not count: LX's focus already moved on from there.
         */
        fun keysGoToApp(focus: View?, layer: View, frame: View?, content: View): Boolean = when {
            focus == null -> true
            !isWithin(focus, layer) -> false
            frame == null || !isWithin(focus, frame) -> true
            else -> focus === frame || isWithin(focus, content)
        }

        private fun isWithin(view: View, root: View): Boolean {
            if (view === root) return true
            var p: ViewParent? = view.parent
            while (p != null) {
                if (p === root) return true
                p = p.parent
            }
            return false
        }

        /**
         * [ev] as the display should get it: view-local coordinates scaled by [sx] and [sy]. obtain(ev) keeps
         * everything the event carries, its history and the button behind a press or release included; the
         * public obtain() overloads have no place for that button, and Android drops a button press without it,
         * so right clicks and stylus buttons never reached the app.
         */
        fun displayEvent(ev: MotionEvent, sx: Float, sy: Float, scale: Matrix = Matrix()): MotionEvent =
            displayEvent(ev, scale.apply { setScale(sx, sy) })

        /** A copy of [ev] with its points moved by [toDisplay], for the display. */
        fun displayEvent(ev: MotionEvent, toDisplay: Matrix): MotionEvent = MotionEvent.obtain(ev).apply { transform(toDisplay) }

        /**
         * The matrix taking a point on a [viewW] by [viewH] view to its [displayW] by [displayH] display. A TextureView
         * stretches its buffer (the display) over the view and then applies its transform [drawn]; this is the inverse
         * of the two, written to [out]. False when the transform cannot be undone.
         */
        fun viewToDisplay(drawn: Matrix, viewW: Int, viewH: Int, displayW: Int, displayH: Int, out: Matrix): Boolean {
            if (viewW <= 0 || viewH <= 0 || displayW <= 0 || displayH <= 0) return false
            val shown = Matrix(drawn)
            shown.preScale(viewW.toFloat() / displayW, viewH.toFloat() / displayH)
            return shown.invert(out)
        }

        /** The message for a window whose app did not open: [id] from create(), [result] from start(). */
        fun failureMessage(id: Int, result: Int): Int = when {
            id == EmbedProtocol.ERR_LIMIT -> R.string.embed_display_limit
            id < 0 -> R.string.embed_display_failed
            else -> R.string.embed_start_failed
        }
    }
}

/**
 * Calls [onEnd] once when [activity] is destroyed. [cancel] stops watching, which the owner must do when it
 * ends first: the application keeps every registered callback.
 */
internal class ActivityEnd(private val app: Application, private val activity: Activity, private val onEnd: () -> Unit) :
    Application.ActivityLifecycleCallbacks {
    private var done = false

    init {
        app.registerActivityLifecycleCallbacks(this)
    }

    fun cancel() {
        done = true
        app.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityDestroyed(a: Activity) {
        if (a !== activity || done) return
        cancel()
        onEnd()
    }

    override fun onActivityCreated(a: Activity, state: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
}

/** One embedded display and the window that shows it. Lives in [EmbedClient] so a rebuilt window can take it over. */
internal class EmbedSession(val windowId: Int, val key: String, private val client: EmbedClient) {
    private val main = Handler(Looper.getMainLooper())
    var owner: EmbeddedAppTool? = null
    var service: IEmbedService? = null
        private set
    var displayId = NO_DISPLAY
        private set
    var width = 0
    var height = 0
    var dpi = 0
    /** The window content the display was last sized for. */
    var slotW = 0
    var slotH = 0
    /** What the app asked for on its display; null until it has. */
    var shape: AppRepository.WindowShape? = null
    var closed = false
        private set

    val callback = object : IEmbedClient.Stub() {
        override fun onDisplayEmpty(displayId: Int, hadTask: Boolean) {
            main.post { if (displayId == this@EmbedSession.displayId) owner?.displayEmpty(hadTask) }
        }

        override fun onOrientation(displayId: Int, orientation: Int) {
            main.post { if (displayId == this@EmbedSession.displayId) owner?.orientationChanged(orientation) }
        }
    }

    private val parkTimeout = Runnable { if (owner == null) release() }

    fun attach(svc: IEmbedService, id: Int, w: Int, h: Int, density: Int) {
        service = svc
        displayId = id
        width = w
        height = h
        dpi = density
    }

    fun adopt(tool: EmbeddedAppTool) {
        main.removeCallbacks(parkTimeout)
        owner = tool
    }

    /** The window was destroyed but is still open in the model: its rebuilt copy takes the display over. */
    fun park() {
        owner = null
        main.postDelayed(parkTimeout, PARK_MS)
    }

    /** Gives the display back. With [endApp] the app ends as well, as swiping it out of Recents does. */
    fun releaseDisplay(endApp: Boolean = false) {
        val id = displayId
        val svc = service
        displayId = NO_DISPLAY
        width = 0
        height = 0
        shape = null
        if (id >= 0 && svc != null) client.worker.execute { runCatching { if (endApp) svc.close(id) else svc.release(id) } }
    }

    fun release(endApp: Boolean = false) {
        main.removeCallbacks(parkTimeout)
        closed = true
        releaseDisplay(endApp)
        owner = null
        client.unregister(this)
    }

    /** The person closed the window, so the app closes too: it leaves Recents instead of waiting there. */
    fun close() = release(endApp = true)

    fun serviceLost() {
        displayId = NO_DISPLAY
        service = null
        owner?.serviceLost()
    }

    companion object {
        const val NO_DISPLAY = -1
        private const val PARK_MS = 10_000L
    }
}
