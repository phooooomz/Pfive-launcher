// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.lxlauncher.R
import com.lxlauncher.ui.theme.LxTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How the apps of a category arrive in the Applications menu (setting `menu.animation`). Each one says where the
 * list comes from, so the motion explains the menu rather than decorating it.
 */
enum class MenuMotion(val id: String, val label: Int, val detail: Int) {
    /** Unfolds out of the tapped category row; the rows nearest to it arrive first. */
    RIBBON("ribbon", R.string.menu_motion_ribbon, R.string.menu_motion_ribbon_detail),
    /** Pulled out from behind the category column, like a drawer. */
    SLIDE("slide", R.string.menu_motion_slide, R.string.menu_motion_slide_detail),
    /** Grows out of the category row's corner, as a popover does. */
    ZOOM("zoom", R.string.menu_motion_zoom, R.string.menu_motion_zoom_detail),
    /** Rolls down from its top edge; the rows drop in from top to bottom. */
    CURTAIN("curtain", R.string.menu_motion_curtain, R.string.menu_motion_curtain_detail),
    /** Swings open on the edge it shares with the category column, like a door. */
    HINGE("hinge", R.string.menu_motion_hinge, R.string.menu_motion_hinge_detail),
    /** The card is there at once; the rows rise into place one after another. */
    WAVE("wave", R.string.menu_motion_wave, R.string.menu_motion_wave_detail),
    /** No motion: the list is simply there. */
    OFF("off", R.string.menu_motion_off, R.string.menu_motion_off_detail);

    companion object {
        const val KEY = "menu.animation"
        fun of(id: String?): MenuMotion = values().firstOrNull { it.id == id } ?: RIBBON
    }
}

/**
 * Plays a [MenuMotion] on one card and its rows: the Applications menu's submenu, and the miniature of it in LX
 * Settings. Only transforms, opacity and the outline change per frame; the card's content is never drawn again, and
 * the transform moves run on a hardware layer.
 *
 * The card must use [outline] as its outline provider. [bandHeight] is the height of the band the ribbon unfolds
 * from (a category row), [radius] the card's corner radius.
 */
class CardMotion(
    private val card: View,
    private val theme: () -> LxTheme,
    private val bandHeight: () -> Int,
    private val radius: () -> Float,
) {
    /** The card's own y of the category row's middle: where the ribbon unfolds from and the zoom grows from. */
    var anchorY = 0
    /** 0 is nothing showing, 1 all of the card; only the ribbon and the curtain read it. */
    private var shown = 1f
    private var fromTop = false
    private var clip: ValueAnimator? = null

    val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = radius()
            if (shown >= 1f) return outline.setRoundRect(0, 0, view.width, view.height, r)
            val h = if (view.height > 0) view.height else view.measuredHeight
            if (fromTop) {
                val bottom = (h * shown).roundToInt().coerceAtLeast(1)
                return outline.setRoundRect(0, 0, view.width, bottom, minOf(r, bottom / 2f))
            }
            val half = bandHeight() / 2f
            val y = anchorY.toFloat().coerceIn(half, maxOf(half, h - half))
            val top = ((y - half) * (1f - shown)).roundToInt()
            val bottom = maxOf(top, (h - (h - y - half) * (1f - shown)).roundToInt())
            outline.setRoundRect(0, top, view.width, bottom, minOf(r, (bottom - top) / 2f))
        }
    }

    /** Brings the card in. [then] runs once it has fully arrived; it never runs for a cancelled entrance. */
    fun enter(m: MenuMotion, then: () -> Unit = {}) {
        settle()
        val t = theme()
        if (!t.animations || m == MenuMotion.OFF) return then()
        when (m) {
            MenuMotion.RIBBON -> reveal(opening = true, top = false, t.durationMedium, then)
            MenuMotion.CURTAIN -> reveal(opening = true, top = true, t.durationMedium, then)
            MenuMotion.SLIDE -> {
                card.alpha = 0f
                card.translationX = -t.dp(SLIDE_DP)
                card.animate().alpha(1f).translationX(0f).setDuration(t.durationMedium).setInterpolator(t.easeOut)
                    .withLayer().withEndAction(then).start()
            }
            MenuMotion.ZOOM -> {
                card.pivotX = 0f
                card.pivotY = anchorY.toFloat()
                card.alpha = 0f
                card.scaleX = ZOOM_FROM
                card.scaleY = ZOOM_FROM
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(t.durationMedium).setInterpolator(t.easeOut)
                    .withLayer().withEndAction(then).start()
            }
            MenuMotion.HINGE -> {
                hingePivot()
                card.alpha = 0f
                card.rotationY = -HINGE_DEG
                card.animate().alpha(1f).rotationY(0f).setDuration(t.durationMedium + HINGE_EXTRA_MS).setInterpolator(t.easeOut)
                    .withLayer().withEndAction(then).start()
            }
            MenuMotion.WAVE -> {
                card.alpha = 0f
                card.animate().alpha(1f).setDuration(t.durationShort).setInterpolator(t.easeOut).withLayer().withEndAction(then).start()
            }
            MenuMotion.OFF -> then()
        }
    }

    /** Takes the card away the way it came; [then] runs when it is gone (at once without animations). */
    fun leave(m: MenuMotion, then: () -> Unit) {
        val t = theme()
        clip?.cancel()
        clip = null
        card.animate().cancel()
        if (!t.animations || m == MenuMotion.OFF || !card.isLaidOut) return then()
        val d = t.durationShort
        when (m) {
            MenuMotion.RIBBON -> reveal(opening = false, top = false, d, then)
            MenuMotion.CURTAIN -> reveal(opening = false, top = true, d, then)
            MenuMotion.SLIDE -> card.animate().alpha(0f).translationX(-t.dp(SLIDE_DP) * 0.66f).setDuration(d)
                .setInterpolator(t.easeOut).withLayer().withEndAction(then).start()
            MenuMotion.ZOOM -> {
                card.pivotX = 0f
                card.pivotY = anchorY.toFloat()
                card.animate().alpha(0f).scaleX(ZOOM_FROM).scaleY(ZOOM_FROM).setDuration(d).setInterpolator(t.easeOut)
                    .withLayer().withEndAction(then).start()
            }
            MenuMotion.HINGE -> {
                hingePivot()
                card.animate().alpha(0f).rotationY(-HINGE_DEG * 0.8f).setDuration(d).setInterpolator(t.easeOut)
                    .withLayer().withEndAction(then).start()
            }
            MenuMotion.WAVE -> card.animate().alpha(0f).setDuration(d).setInterpolator(t.easeOut).withLayer()
                .withEndAction(then).start()
            MenuMotion.OFF -> then()
        }
    }

    /** Ends any entrance or exit at once, with all of the card showing and no transform left. */
    fun settle() {
        clip?.cancel()
        clip = null
        card.animate().cancel()
        shown = 1f
        card.alpha = 1f
        card.translationX = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        card.rotationY = 0f
        card.clipToOutline = false
        card.invalidateOutline()
    }

    /**
     * The rows in view arrive after the card, in the style's order. [rows] holds each row with its middle in the
     * card's own pixels. [glide] says the card moved over from another category rather than arriving, so styles that
     * leave the rows alone on arrival give them a short fade there, or the new names would simply snap in.
     */
    fun rows(rows: List<Pair<View, Int>>, m: MenuMotion, glide: Boolean) {
        val t = theme()
        if (!t.animations || m == MenuMotion.OFF || rows.isEmpty()) return
        when (m) {
            MenuMotion.RIBBON -> stagger(rows.sortedBy { abs(it.second - anchorY) }, dx = -t.dp(ROW_SHIFT_DP), dy = 0f, CASCADE_STEP_MS)
            MenuMotion.CURTAIN -> stagger(rows.sortedBy { it.second }, dx = 0f, dy = -t.dp(ROW_DROP_DP), CURTAIN_STEP_MS)
            MenuMotion.WAVE -> stagger(rows.sortedBy { it.second }, dx = 0f, dy = t.dp(ROW_RISE_DP), WAVE_STEP_MS)
            else -> if (glide) for ((v, _) in rows) {
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(0).setDuration(t.durationShort).setInterpolator(t.easeOut).start()
            }
        }
    }

    private fun stagger(rows: List<Pair<View, Int>>, dx: Float, dy: Float, step: Long) {
        val t = theme()
        rows.forEachIndexed { i, (v, _) ->
            v.alpha = 0f
            v.translationX = dx
            v.translationY = dy
            v.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(i * step).setDuration(t.durationShort)
                .setInterpolator(t.easeOut).withLayer().start()
        }
    }

    private fun hingePivot() {
        card.pivotX = if (card.layoutDirection == View.LAYOUT_DIRECTION_RTL) card.width.toFloat() else 0f
        card.pivotY = card.height / 2f
        // Far enough that the near edge of a 288 dp card does not swing towards the eye.
        card.cameraDistance = card.resources.displayMetrics.density * CAMERA_DP
    }

    private fun reveal(opening: Boolean, top: Boolean, duration: Long, then: () -> Unit) {
        clip?.cancel()
        fromTop = top
        card.clipToOutline = true
        clip = ValueAnimator.ofFloat(if (opening) 0f else shown, if (opening) 1f else 0f).apply {
            this.duration = duration
            interpolator = theme().easeOut
            addUpdateListener {
                shown = it.animatedValue as Float
                if (!opening) card.alpha = shown
                card.invalidateOutline()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    clip = null
                    if (opening) card.clipToOutline = false
                    then()
                }
            })
            start()
        }
    }

    companion object {
        const val CASCADE_STEP_MS = 16L
        private const val CURTAIN_STEP_MS = 14L
        private const val WAVE_STEP_MS = 22L
        private const val ROW_SHIFT_DP = 8f
        private const val ROW_DROP_DP = 10f
        private const val ROW_RISE_DP = 14f
        private const val SLIDE_DP = 28f
        private const val ZOOM_FROM = 0.88f
        private const val HINGE_DEG = 70f
        private const val HINGE_EXTRA_MS = 60L
        private const val CAMERA_DP = 9000f
    }
}
