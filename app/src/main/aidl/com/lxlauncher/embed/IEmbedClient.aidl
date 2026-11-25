// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.embed;

/** LX's side of one embedded display. The service also watches this binder to learn that LX died. */
oneway interface IEmbedClient {
    /**
     * No activity is left on the display. [hadTask] is false when the app never showed up there
     * (it refused, or handed off to another display), true when it was there and finished.
     */
    void onDisplayEmpty(int displayId, boolean hadTask) = 1;

    /** The app on the display asks for this ActivityInfo.screenOrientation, now or from the start. */
    void onOrientation(int displayId, int orientation) = 2;
}
