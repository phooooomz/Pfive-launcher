// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed;

import android.hardware.display.VirtualDisplayConfig;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.lxlauncher.embed.IEmbedClient;

/**
 * What LX asks of lxdb, its native daemon, which runs as the shell user (lxdb/src/embed_service.cpp). The numbers are that
 * file's too. Only LX's uid may call this. The last argument of create, setSurface and the inject calls goes on to Android
 * unread, so it has to stay last.
 */
interface IEmbedService {
    /**
     * Creates the display LX's framework described. Returns its display id, or a negative EmbedProtocol.ERR_* code. The
     * display is released when [client] dies, so a crashed LX never leaves one behind.
     */
    int create(int width, int height, int densityDpi, IEmbedClient client, in VirtualDisplayConfig config) = 1;

    oneway void resize(int displayId, int width, int height, int densityDpi) = 2;

    /** Null while the LX window is hidden: the app keeps running on a display that draws nowhere. */
    oneway void setSurface(int displayId, in Surface surface) = 3;

    /**
     * Starts an activity of user [userId] on the display: [intent] and [options] are EmbedProtocol's parcels.
     * Returns 0, a negative EmbedProtocol.ERR_* code, or Android's own negative start result.
     */
    int start(int displayId, int userId, String packageName, in byte[] intent, in byte[] options) = 4;

    oneway void injectMotion(int displayId, in MotionEvent event) = 5;

    /** Only keys that stay on the display; keys Android applies to the whole phone are dropped (EmbedKeys). */
    oneway void injectKey(int displayId, in KeyEvent event) = 6;

    /** Removes the display. Android finishes the app's activities with it, but its task stays in Recents. */
    void release(int displayId) = 7;

    /**
     * Runs [command] with sh as the shell user, as `adb shell` does, and feeds it [input]. The answer is four bytes of exit
     * status, then what it wrote, cut at 512 KB so it fits a reply.
     */
    byte[] exec(String command, in byte[] input, int timeoutMs) = 8;

    /** The window's close button: ends the app as swiping it out of Recents does, then removes the display. */
    void close(int displayId) = 9;
}
