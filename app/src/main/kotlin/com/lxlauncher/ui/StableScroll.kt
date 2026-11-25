// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

import android.content.Context
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * Scroll views that measure their content with no size hint along the axis they scroll. Android passes the scroll view's
 * own size as that hint, so every frame of a window resize changed it and measured the whole content again, the parts
 * that did not change included (a row of theme tiles, or every row of a page when only the height moved). Content that
 * scrolls cannot fill a size it does not know, so the hint carried nothing; Android itself passed none before 6.0.
 *
 * [fadeInto] is the opaque colour the view sits on, or 0 when what lies behind it shows through. With a colour, Android
 * draws the fading edges as a plain gradient into that colour. Without one, it copies the part of the frame already
 * drawn under each edge, on every frame the view is drawn. The Infinix test phone's GPU draws a frame in tiles and has to
 * stop and write its work out for each such copy: about a seventh of the render thread's time while a window was
 * resized there, with the same look on an opaque window as the gradient.
 */
open class StableScrollView(context: Context) : ScrollView(context) {
    var fadeInto: () -> Int = { 0 }

    override fun getSolidColor(): Int = fadeInto()

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        val w = getChildMeasureSpec(parentWidthMeasureSpec, paddingLeft + paddingRight, child.layoutParams.width)
        child.measure(w, UNBOUNDED)
    }

    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                         parentHeightMeasureSpec: Int, heightUsed: Int) {
        val lp = child.layoutParams as MarginLayoutParams
        val w = getChildMeasureSpec(parentWidthMeasureSpec,
            paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin + widthUsed, lp.width)
        child.measure(w, UNBOUNDED)
    }
}

/** The horizontal counterpart of [StableScrollView], with the same [fadeInto]. */
open class StableHorizontalScrollView(context: Context) : HorizontalScrollView(context) {
    var fadeInto: () -> Int = { 0 }

    override fun getSolidColor(): Int = fadeInto()

    override fun measureChild(child: View, parentWidthMeasureSpec: Int, parentHeightMeasureSpec: Int) {
        val h = getChildMeasureSpec(parentHeightMeasureSpec, paddingTop + paddingBottom, child.layoutParams.height)
        child.measure(UNBOUNDED, h)
    }

    override fun measureChildWithMargins(child: View, parentWidthMeasureSpec: Int, widthUsed: Int,
                                         parentHeightMeasureSpec: Int, heightUsed: Int) {
        val lp = child.layoutParams as MarginLayoutParams
        val h = getChildMeasureSpec(parentHeightMeasureSpec,
            paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin + heightUsed, lp.height)
        child.measure(UNBOUNDED, h)
    }
}

private val UNBOUNDED = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
