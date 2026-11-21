// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * File-type icons for the tree and the tabs, in the way code editors mark files: a coloured badge with the language's
 * short name (JS, TS, Py), a symbol drawn in the language's colour ({} for JSON, <> for markup), or a plain outline.
 * The colours are the ones each language's community uses for itself.
 */
class FileIcon private constructor(private val kind: Kind, private val label: String, private val color: Int, private val ink: Int, private val glyph: TIcon.Glyph?) : Drawable() {
    private enum class Kind { BADGE, TEXT, GLYPH, FOLDER, FOLDER_OPEN, BOOK }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val p = Path()
    private val glyphDrawable = glyph?.let { TIcon(it, color) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        canvas.save()
        canvas.translate(b.left + (b.width() - size) / 2f, b.top + (b.height() - size) / 2f)
        val s = size / 24f
        when (kind) {
            Kind.BADGE -> {
                paint.style = Paint.Style.FILL
                paint.color = color
                r.set(2.5f * s, 4.5f * s, 21.5f * s, 19.5f * s)
                canvas.drawRoundRect(r, 3f * s, 3f * s, paint)
                paint.color = ink
                paint.typeface = BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = (if (label.length >= 3) 7.2f else 9.5f) * s
                val fm = paint.fontMetrics
                canvas.drawText(label, 12f * s, 12f * s - (fm.ascent + fm.descent) / 2f, paint)
            }
            Kind.TEXT -> {
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.typeface = BOLD
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = (if (label.length >= 3) 9f else 13f) * s
                val fm = paint.fontMetrics
                canvas.drawText(label, 12f * s, 12f * s - (fm.ascent + fm.descent) / 2f, paint)
            }
            Kind.GLYPH -> {
                glyphDrawable?.setBounds(0, 0, size.toInt(), size.toInt())
                glyphDrawable?.draw(canvas)
            }
            Kind.FOLDER, Kind.FOLDER_OPEN -> {
                paint.style = Paint.Style.FILL
                paint.color = color
                p.reset()
                // Tab, then body: a filled folder like the ones in the reference.
                p.moveTo(2.5f * s, 6.5f * s)
                p.quadTo(2.5f * s, 5f * s, 4f * s, 5f * s)
                p.lineTo(9f * s, 5f * s)
                p.lineTo(11f * s, 7f * s)
                p.lineTo(20f * s, 7f * s)
                p.quadTo(21.5f * s, 7f * s, 21.5f * s, 8.5f * s)
                p.lineTo(21.5f * s, 17.5f * s)
                p.quadTo(21.5f * s, 19f * s, 20f * s, 19f * s)
                p.lineTo(4f * s, 19f * s)
                p.quadTo(2.5f * s, 19f * s, 2.5f * s, 17.5f * s)
                p.close()
                canvas.drawPath(p, paint)
                if (kind == Kind.FOLDER_OPEN) {
                    paint.color = Color.argb(70, 0, 0, 0)
                    r.set(2.5f * s, 10f * s, 21.5f * s, 19f * s)
                    canvas.drawRoundRect(r, 1.5f * s, 1.5f * s, paint)
                }
            }
            Kind.BOOK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f * s
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = color
                p.reset()
                p.moveTo(12f * s, 7f * s); p.quadTo(8f * s, 5f * s, 3f * s, 5.5f * s); p.lineTo(3f * s, 18f * s); p.quadTo(8f * s, 17.5f * s, 12f * s, 19.5f * s)
                p.quadTo(16f * s, 17.5f * s, 21f * s, 18f * s); p.lineTo(21f * s, 5.5f * s); p.quadTo(16f * s, 5f * s, 12f * s, 7f * s); p.lineTo(12f * s, 19.5f * s)
                canvas.drawPath(p, paint)
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val BOLD = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        private fun c(hex: String) = Color.parseColor(hex)
        private val DARK = c("#1B1F2A")

        private fun badge(label: String, bg: String, ink: Int = Color.WHITE) = FileIcon(Kind.BADGE, label, c(bg), ink, null)
        private fun text(label: String, color: String) = FileIcon(Kind.TEXT, label, c(color), 0, null)
        private fun glyph(g: TIcon.Glyph, color: Int) = FileIcon(Kind.GLYPH, "", color, 0, g)

        fun folder(open: Boolean) = FileIcon(if (open) Kind.FOLDER_OPEN else Kind.FOLDER, "", c("#5B9BF0"), 0, null)

        /** The icon for a file called [name]; [muted] colours the plain outlines. */
        fun forName(name: String, muted: Int): Drawable {
            val lower = name.lowercase()
            val ext = lower.substringAfterLast('.', "")
            when (lower) {
                "dockerfile", "containerfile" -> return badge("D", "#2496ED")
                "makefile", "gnumakefile", "cmakelists.txt" -> return badge("M", "#6D8086")
                ".gitignore", ".gitattributes", ".gitmodules" -> return glyph(TIcon.Glyph.BRANCH, c("#F05033"))
                "license", "licence", "notice", "copying" -> return glyph(TIcon.Glyph.FILE, c("#D4B44A"))
            }
            return when (ext) {
                "js", "mjs", "cjs" -> badge("JS", "#F1D33E", DARK)
                "jsx" -> badge("JSX", "#F1D33E", DARK)
                "ts", "mts", "cts" -> badge("TS", "#3178C6")
                "tsx" -> badge("TSX", "#3178C6")
                "json", "jsonc", "json5", "webmanifest" -> text("{}", "#E8C547")
                "html", "htm", "xhtml" -> text("<>", "#E44D26")
                "xml", "svg", "xsd", "plist", "iml", "xaml" -> text("<>", "#F08D49")
                "css" -> text("#", "#42A5F5")
                "scss", "sass" -> text("#", "#CD6799")
                "less" -> text("#", "#4A7FBF")
                "md", "markdown", "mdx" -> FileIcon(Kind.BOOK, "", c("#6FA8DC"), 0, null)
                "kt", "kts" -> badge("K", "#8F6EF5")
                "java" -> badge("J", "#E76F00")
                "gradle", "groovy" -> badge("G", "#1BA0B4")
                "py", "pyw", "pyi" -> badge("Py", "#3776AB")
                "sh", "bash", "zsh", "ksh", "command" -> text(">_", "#89E051")
                "c", "h" -> badge("C", "#5C6BC0")
                "cpp", "cc", "cxx", "hpp", "hh", "hxx", "ino" -> badge("C++", "#00599C")
                "cs" -> badge("C#", "#68217A")
                "go" -> badge("Go", "#00ADD8")
                "rs" -> badge("Rs", "#DEA584", DARK)
                "swift" -> badge("Sw", "#F05138")
                "dart" -> badge("Dt", "#0175C2")
                "php" -> badge("php", "#777BB4")
                "rb", "rake", "gemspec" -> badge("Rb", "#CC342D")
                "lua" -> badge("Lua", "#3B3FA0")
                "sql" -> text("SQL", "#E38C00")
                "yml", "yaml" -> text("yml", "#E06C75")
                "toml", "ini", "cfg", "conf", "properties", "env", "editorconfig" -> text("ini", "#9DA5B4")
                "diff", "patch" -> text("±", "#E06C75")
                "txt", "log", "csv", "tsv" -> glyph(TIcon.Glyph.LIST, muted)
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif" -> glyph(TIcon.Glyph.IMAGE, c("#A074C4"))
                "zip", "tar", "gz", "tgz", "7z", "rar", "xz", "bz2", "zst" -> glyph(TIcon.Glyph.ARCHIVE, c("#C0A060"))
                "apk", "aab" -> badge("apk", "#3DDC84", DARK)
                "pdf" -> badge("PDF", "#E53935")
                else -> glyph(TIcon.Glyph.FILE, muted)
            }
        }
    }
}
