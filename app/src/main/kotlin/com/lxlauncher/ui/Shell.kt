// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import com.lxlauncher.apps.AppRepository
import com.lxlauncher.core.LauncherModel
import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme
import com.lxlauncher.widgets.AndroidWidgets

data class MenuItem(
    val label: CharSequence,
    val icon: Int = 0,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    /** Draws a divider before this item to start a new group. */
    val groupStart: Boolean = false,
    /** Opens as a submenu; [action] is not called for an item that has children. */
    val children: List<MenuItem> = emptyList(),
    /** Non-null makes the item checkable; true draws the check mark. */
    val checked: Boolean? = null,
    /** Key hint shown at the end of the row, such as "Ctrl+C". */
    val shortcut: String? = null,
    val action: () -> Unit = {},
)

/** What launcher components may ask of the activity that hosts them. */
interface Shell {
    val activity: Activity
    val model: LauncherModel
    val apps: AppRepository
    val appWidgets: AndroidWidgets
    /** Resolved design tokens (named ui because Activity already has getTheme()). */
    val ui: LxTheme
    val glass: GlassEnv

    fun launchApp(key: String, source: View?)
    fun launchPin(entry: String, source: View?)
    fun openTool(kind: String, extra: String? = null)
    fun openDrawer(filter: String? = null)
    fun openSearch(query: String = "")
    fun closeOverlays(): Boolean

    fun notify(message: CharSequence, actionLabel: CharSequence? = null, action: (() -> Unit)? = null)
    /**
     * Opens a menu at (x, y) inside [anchor], or below its centre. With [alignStart] the menu starts
     * at that point instead of centring on it, as a menubar does; x then counts from the anchor's start
     * edge, the right edge in a right-to-left layout. Items with children cascade. [dense] draws the
     * smaller rows that go with a tool's small controls.
     */
    fun showMenu(anchor: View, items: List<MenuItem>, x: Float = -1f, y: Float = -1f, alignStart: Boolean = false, dense: Boolean = false)
    fun prompt(title: CharSequence, initial: String, hint: CharSequence = "", onOk: (String) -> Unit)
    fun confirm(title: CharSequence, message: CharSequence, confirmLabel: CharSequence, danger: Boolean = false, onOk: () -> Unit)
    fun choose(title: CharSequence, options: List<CharSequence>, selected: Int, onPick: (Int) -> Unit)

    fun startSafely(intent: Intent): Boolean

    /** Sends the user to the one switch LX Bridge's automatic setup needs. */
    fun pairBridge()

    /** Asks Android and the user for what pairing LX Bridge by hand takes, then opens the page that shows the code. */
    fun pairBridgeWithCode()
    /** Starts a system picker (SAF) and delivers the chosen Uri, or null when cancelled. */
    fun requestDocument(intent: Intent, onResult: (android.net.Uri?) -> Unit)
    fun pickWallpaperImage(workspaceId: Int)
    fun pickAndroidWidget(workspaceId: Int)
    fun exportLayout()
    fun importLayout()
    fun addNativeWidget(kind: String, workspaceId: Int)
    fun chooseWallpaper(workspaceId: Int)
    /** Asks for confirmation and offers to move the content to another workspace. */
    fun removeWorkspace(workspaceId: Int)
    fun setEditMode(on: Boolean)
    val editMode: Boolean

    /** A press went to the Android app in window [windowId]; the phone's keyboard stays with that app (see LauncherActivity). */
    fun touchedApp(windowId: Int) {}

    /**
     * A press landed on window [windowId] outside its content: the title bar, the border, the resize edge just outside
     * the window, or the space beside an app drawn narrower than its window. The app keeps the keyboard it has.
     */
    fun pressedWindow(windowId: Int) {}

    /** The app in window [windowId] grabbed focus for the keyboard without a touch; LX yields the IME so its display keeps it. */
    fun appClaimsKeyboard(windowId: Int) {}
}
