// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.widget.TextView
import kotlin.math.ceil

/**
 * A window title of [sizeSp] that shrinks in whole sp steps to three quarters of that size, but not under 12 sp, before
 * it is cut off. A title already at the floor (Compact at a small UI size) keeps its size.
 *
 * Android's autosize chose the same sizes by laying the text out at several of them, and it did so on every frame of a
 * resize: about 8% of the main thread's time while a window was resized on the Infinix test phone. Measuring the
 * text's width at each size costs a fraction of that. Unlike autosize, the full size is [sizeSp] itself, not cut down
 * to a whole number (16.8 sp at an interface size of 105%).
 */
@SuppressLint("ViewConstructor")
internal class WindowTitle(context: Context, val sizeSp: Float) : TextView(context) {
    val minSp = (sizeSp * 0.75f).toInt().coerceAtLeast(12)
    private val probe = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (minSp < sizeSp && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            fit(MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * A new title (an app's name replacing its package name once the app list is in) is fitted again. A title of
     * fixed size only redraws on its own and kept the size picked for the old text.
     */
    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        requestLayout()
    }

    /** Takes the largest size, [sizeSp] or a whole sp below it down to [minSp], at which the title fits in [width] pixels. */
    private fun fit(width: Int) {
        if (width <= 0) return
        val metrics = resources.displayMetrics
        probe.set(paint)
        var sp = sizeSp
        while (sp > minSp) {
            probe.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            if (probe.measureText(text, 0, text.length) <= width) break
            // From a size between whole numbers, the next step down is the whole number below it.
            sp = (ceil(sp) - 1f).coerceAtLeast(minSp.toFloat())
        }
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
        // A new size asks for one more layout, which happens only when the title crosses a step.
        if (px != textSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
    }
}
