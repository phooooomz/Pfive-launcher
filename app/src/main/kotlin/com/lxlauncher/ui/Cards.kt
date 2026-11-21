// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.lxlauncher.ui.theme.Contrast
import com.lxlauncher.ui.theme.LxTheme

/**
 * The raised surface LX's tools group their content on (LX Settings, the File Explorer's pages): one tone step above
 * the window, made of the text colour at 6% over the panel, with no line and no shadow. The same step marks the File
 * Explorer's sidebar card, so all of them read as one family.
 */
object Cards {
    const val TINT = 0.06f
    const val RADIUS_DP = 12f

    fun raised(t: LxTheme): GradientDrawable = GradientDrawable().apply {
        setColor(raisedColor(t))
        cornerRadius = t.dp(RADIUS_DP)
    }

    /** The card's colour, opaque in every style: a row that scrolls inside a card fades into it. */
    fun raisedColor(t: LxTheme): Int = Contrast.over(t.textPrimary, TINT, t.panel)

    /** The title over a group: body size in the medium weight, more space above than below. */
    fun heading(ctx: Context, t: LxTheme, label: CharSequence): TextView =
        Ui.text(ctx, t, label, t.textBody, t.textPrimary, bold = true, lines = 2).apply {
            setPadding(t.dpi(4f), t.dpi(18f), t.dpi(4f), t.dpi(8f))
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
}
