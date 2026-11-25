// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.core

import android.graphics.Bitmap

/**
 * Direct bindings to the C++20 core (core/ and app/src/main/cpp/jni_bridge.cpp).
 * Every call is synchronised on the native side. Use [LauncherModel] instead of
 * calling this object from UI code.
 */
object NativeCore {
    init {
        System.loadLibrary("lxnative")
    }

    const val LIST_DOCK = 0
    const val LIST_TASKBAR = 1
    const val LIST_FAVORITES = 2
    const val LIST_HIDDEN = 3
    const val LIST_RECENT = 4

    const val EDGE_LEFT = 1
    const val EDGE_TOP = 2
    const val EDGE_RIGHT = 4
    const val EDGE_BOTTOM = 8

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun load(handle: Long, path: String, names: Array<String>, welcome: String, landscape: Boolean): String?
    external fun save(handle: Long, path: String): Boolean
    external fun exportDocument(handle: Long): String?
    external fun importDocument(handle: Long, text: String): String?
    external fun resetDefaults(handle: Long, names: Array<String>, welcome: String, landscape: Boolean, keepSettings: Boolean)
    external fun snapshot(handle: Long): String?
    external fun diagnostics(handle: Long): Array<String>?
    external fun saveText(path: String, text: String): Boolean
    external fun loadText(path: String): String?

    external fun setSetting(handle: Long, key: String, value: String): Boolean
    external fun getSetting(handle: Long, key: String): String?
    external fun applyPreset(handle: Long, name: String): Boolean

    external fun addWorkspace(handle: Long, name: String): Int
    external fun renameWorkspace(handle: Long, id: Int, name: String): Boolean
    external fun removeWorkspace(handle: Long, id: Int, moveTo: Int): IntArray?
    external fun setActiveWorkspace(handle: Long, id: Int): Boolean
    external fun neighbourWorkspace(handle: Long, direction: Int): Int
    external fun setWallpaper(handle: Long, id: Int, ref: String): Boolean

    external fun addShortcut(handle: Long, ws: Int, appKey: String, col: Int, row: Int): Int
    external fun moveItem(handle: Long, id: Int, col: Int, row: Int): Boolean
    external fun moveItemToWorkspace(handle: Long, id: Int, ws: Int): Boolean
    external fun removeItem(handle: Long, id: Int): Boolean
    external fun createFolder(handle: Long, a: Int, b: Int, name: String): Int
    external fun addToFolder(handle: Long, folder: Int, shortcut: Int): Boolean
    external fun removeFromFolder(handle: Long, folder: Int, appKey: String): Int
    external fun renameFolder(handle: Long, folder: Int, name: String): Boolean
    /** [userSerial] limits removal to one profile's keys ("pkg/cls#serial"). */
    external fun removePackage(handle: Long, pkg: String, userSerial: Long): Int

    external fun addWidget(handle: Long, ws: Int, kind: String, appWidgetId: Int, x: Float, y: Float, w: Float, h: Float): Int
    external fun setWidgetRect(handle: Long, id: Int, x: Float, y: Float, w: Float, h: Float): Boolean
    external fun removeWidget(handle: Long, id: Int): Int
    external fun setWidgetConfig(handle: Long, id: Int, key: String, value: String): Boolean
    external fun setWidgetVisible(handle: Long, id: Int, visible: Boolean): Boolean
    external fun moveWidgetToWorkspace(handle: Long, id: Int, ws: Int): Boolean

    external fun pin(handle: Long, list: Int, entry: String, index: Int): Boolean
    external fun unpin(handle: Long, list: Int, entry: String): Boolean
    external fun movePin(handle: Long, list: Int, entry: String, delta: Int): Boolean

    external fun setViewport(handle: Long, ux: Float, uy: Float, uw: Float, uh: Float, fx: Float, fy: Float, fw: Float, fh: Float,
                             canvasAspect: Float)
    external fun setWindowConstraints(handle: Long, minW: Float, minH: Float, titleH: Float)
    external fun openWindow(handle: Long, kind: String, title: String, ws: Int, state: String): Int
    external fun closeWindow(handle: Long, id: Int): Boolean
    external fun focusWindow(handle: Long, id: Int): Boolean
    external fun moveWindow(handle: Long, id: Int, dx: Float, dy: Float): FloatArray?
    external fun resizeWindow(handle: Long, id: Int, edges: Int, dx: Float, dy: Float): FloatArray?
    external fun setWindowState(handle: Long, id: Int, state: String): Boolean
    external fun toggleMaximize(handle: Long, id: Int): Boolean
    external fun restoreWindow(handle: Long, id: Int): Boolean
    /** Back from full screen to the state the window had before; false when it is not full screen. */
    external fun leaveFullscreen(handle: Long, id: Int): Boolean
    external fun moveWindowToWorkspace(handle: Long, id: Int, ws: Int): Boolean
    external fun tileWindows(handle: Long, ws: Int)
    external fun minimizeAll(handle: Long, ws: Int)
    external fun snapTarget(handle: Long, x: Float, y: Float, threshold: Float): String?
    external fun windowBounds(handle: Long, id: Int): FloatArray?
    external fun findWindow(handle: Long, ws: Int, kind: String): Int

    external fun rank(query: String, labels: Array<String>, limit: Int): IntArray
    external fun renderWallpaper(bitmap: Bitmap, style: String, seed: Int): Boolean
    external fun blurBitmap(bitmap: Bitmap, radius: Int, passes: Int): Boolean
    external fun fillBitmap(bitmap: Bitmap, rgb: Int): Boolean
}
