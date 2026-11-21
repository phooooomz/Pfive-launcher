// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lxlauncher.R
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** How a font reads, for grouping them in LX Settings. */
enum class FontKind { SANS, ROUNDED, SERIF, MONO }

/** One of the bundled UI fonts: a text weight (400) and an emphasis weight (500). */
class FontOption(val id: String, val name: String, val regular: Int, val medium: Int, val kind: FontKind = FontKind.SANS)

/**
 * The 25 bundled fonts (licences in assets/licenses/fonts, sources in third_party/fonts). They apply inside LX
 * only; the system font is untouched. The ids are stored in layouts, so an id never changes or goes away.
 */
object LxFonts {
    val all = listOf(
        FontOption("roboto", "Roboto", R.font.roboto_regular, R.font.roboto_medium),
        FontOption("inter", "Inter", R.font.inter_regular, R.font.inter_medium),
        FontOption("notosans", "Noto Sans", R.font.notosans_regular, R.font.notosans_medium),
        FontOption("notoserif", "Noto Serif", R.font.notoserif_regular, R.font.notoserif_medium, FontKind.SERIF),
        FontOption("notosansmono", "Noto Sans Mono", R.font.notosansmono_regular, R.font.notosansmono_medium, FontKind.MONO),
        FontOption("sourcesans3", "Source Sans 3", R.font.sourcesans3_regular, R.font.sourcesans3_medium),
        FontOption("nunitosans", "Nunito Sans", R.font.nunitosans_regular, R.font.nunitosans_medium),
        FontOption("lato", "Lato", R.font.lato_regular, R.font.lato_medium),
        FontOption("plexsans", "IBM Plex Sans", R.font.plexsans_regular, R.font.plexsans_medium),
        FontOption("jetbrainsmono", "JetBrains Mono", R.font.jetbrainsmono_regular, R.font.jetbrainsmono_medium, FontKind.MONO),
        FontOption("plusjakartasans", "Plus Jakarta Sans", R.font.plusjakartasans_regular, R.font.plusjakartasans_medium),
        FontOption("manrope", "Manrope", R.font.manrope_regular, R.font.manrope_medium),
        FontOption("poppins", "Poppins", R.font.poppins_regular, R.font.poppins_medium),
        FontOption("montserrat", "Montserrat", R.font.montserrat_regular, R.font.montserrat_medium),
        FontOption("opensans", "Open Sans", R.font.opensans_regular, R.font.opensans_medium),
        FontOption("worksans", "Work Sans", R.font.worksans_regular, R.font.worksans_medium),
        FontOption("dmsans", "DM Sans", R.font.dmsans_regular, R.font.dmsans_medium),
        FontOption("figtree", "Figtree", R.font.figtree_regular, R.font.figtree_medium),
        FontOption("redhattext", "Red Hat Text", R.font.redhattext_regular, R.font.redhattext_medium),
        FontOption("lexend", "Lexend", R.font.lexend_regular, R.font.lexend_medium),
        FontOption("atkinsonnext", "Atkinson Hyperlegible Next", R.font.atkinsonnext_regular, R.font.atkinsonnext_medium),
        FontOption("exo2", "Exo 2", R.font.exo2_regular, R.font.exo2_medium),
        FontOption("rubik", "Rubik", R.font.rubik_regular, R.font.rubik_medium, FontKind.ROUNDED),
        FontOption("nunito", "Nunito", R.font.nunito_regular, R.font.nunito_medium, FontKind.ROUNDED),
        FontOption("literata", "Literata", R.font.literata_regular, R.font.literata_medium, FontKind.SERIF),
    )

    /** Faces of a font LX's interface uses, kept for the life of the process. */
    private val held = HashMap<Int, Typeface>()
    /**
     * Faces loaded only to show a font's name in LX Settings. Held weakly: once that page closes, the inflated font
     * files (about 6.5 MB for all 25 fonts) can go.
     */
    private val previews = HashMap<Int, WeakReference<Typeface>>()
    private val main = Handler(Looper.getMainLooper())
    /** One background thread for preview faces; it ends after a few idle seconds. */
    private val loader = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
        Thread(r, "lx-fonts").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    fun option(id: String?): FontOption = all.firstOrNull { it.id == id } ?: all[0]

    private fun resOf(id: String, medium: Boolean) = option(id).let { if (medium) it.medium else it.regular }

    private fun known(res: Int): Typeface? = synchronized(held) { held[res] ?: previews[res]?.get() }

    /** A bundled face for LX's interface, loaded once per process; a file that will not load falls back to the system sans. */
    fun typeface(context: Context, id: String, medium: Boolean): Typeface {
        val res = resOf(id, medium)
        val face = known(res) ?: read(context, res, id, medium)
        synchronized(held) { held[res] = face }
        return face
    }

    /**
     * Hands [onLoaded] a face to preview on the main thread: at once when it is loaded already, else once a background
     * thread has read it. The row shows in the interface font meanwhile; reading every font at once held LX Settings back.
     */
    fun load(context: Context, id: String, medium: Boolean, onLoaded: (Typeface) -> Unit) {
        val res = resOf(id, medium)
        known(res)?.let { return onLoaded(it) }
        val app = context.applicationContext
        loader.execute {
            val face = known(res) ?: read(app, res, id, medium).also { synchronized(held) { previews[res] = WeakReference(it) } }
            main.post { onLoaded(face) }
        }
    }

    private fun read(context: Context, res: Int, id: String, medium: Boolean): Typeface = try {
        context.resources.getFont(res)
    } catch (e: Exception) {
        Log.w(TAG, "font $id could not be loaded, using the system font", e)
        null
    } ?: Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)

    private const val TAG = "LxFonts"
}
