// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.test

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.Log

/**
 * Asks for landscape while it runs, as YouTube does when a video goes full screen, so scripts/ui_test_display_awake.sh can
 * time how soon lxdb hears of it.
 */
class OrientationProbeActivity : Activity() {
    override fun onResume() {
        super.onResume()
        window.decorView.postDelayed({
            Log.i(TAG, "asking for landscape")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }, ASK_AFTER_MS)
    }

    private companion object {
        const val TAG = "LxProbe"
        /** Past lxdb's first look at the display, so only the event can tell it. */
        const val ASK_AFTER_MS = 4_000L
    }
}
