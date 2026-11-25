// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

/**
 * Chooses BitmapFactory's inSampleSize. Sampling down to the wanted size alone is not enough: a scrolling
 * screenshot 1080 px wide and 25,000 px tall already fits a 640 px preview at full size, and decoding it whole
 * gives a 108 MB bitmap, past the 100 MB Android lets a Canvas draw. So the result is also held under a pixel
 * budget and a longest side.
 */
object BitmapSample {
    /** 16 megapixels: 64 MB at ARGB_8888. */
    const val MAX_PIXELS = 16L * 1024 * 1024
    const val MAX_SIDE = 8192

    /**
     * The largest power of two that keeps both sides at or above [fitW] x [fitH] (pass 1 for a side that does
     * not matter), raised further until the decoded image has at most [maxPixels] pixels and no side longer
     * than [maxSide]. 1 when the size is unknown.
     */
    fun sampleFor(width: Int, height: Int, fitW: Int, fitH: Int, maxPixels: Long = MAX_PIXELS, maxSide: Int = MAX_SIDE): Int {
        if (width <= 0 || height <= 0) return 1
        val w = maxOf(fitW, 1)
        val h = maxOf(fitH, 1)
        var sample = 1
        while (width / (sample * 2) >= w && height / (sample * 2) >= h) sample *= 2
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels ||
            maxOf(width, height) / sample > maxSide) {
            sample *= 2
        }
        return sample
    }
}
