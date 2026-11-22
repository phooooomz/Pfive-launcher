// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui.theme

import android.content.res.ColorStateList
import android.webkit.MimeTypeMap
import android.widget.ImageView
import com.lxlauncher.R

/**
 * Icons for LX's own folders, files and tools: the Vivid Dark set (GPL-3.0,
 * see third_party/vivid-icons) or LX's line glyphs. Installed apps always keep
 * their own icons. Vivid icons are drawn as they are; glyphs take a tint.
 */
object LxIcons {
    class Choice(val res: Int, val tinted: Boolean)

    private fun vivid(res: Int) = Choice(res, tinted = false)
    private fun line(res: Int) = Choice(res, tinted = true)

    /**
     * Tedit's own icon, from the owner's artwork (t-icon.png, 13 November 2026). It stands for the app wherever Tedit
     * is shown, in both icon styles and untinted, as an installed app keeps its own icon.
     */
    val TEDIT: Int = R.drawable.ic_tedit

    /** True for an icon drawn in its own colours, which a tint would flatten into a silhouette. */
    fun ownColours(res: Int): Boolean = res == TEDIT

    /** Dock and taskbar entries that are not apps. Entries with no fitting Vivid icon keep the glyph. */
    fun pin(entry: String, useVivid: Boolean): Choice {
        if (entry == "tool:tedit") return vivid(TEDIT)
        if (useVivid) when (entry) {
            "action:home" -> return vivid(R.drawable.vivid_home)
            // File Manager has its own two-tone icon (drawn for LX), not the plain Vivid folder.
            "tool:files" -> return vivid(R.drawable.ic_file_manager)
            "tool:settings" -> return vivid(R.drawable.vivid_app_settings)
            "action:games" -> return vivid(R.drawable.vivid_app_games)
            "intent:gallery" -> return vivid(R.drawable.vivid_file_image)
            "tool:monitor" -> return vivid(R.drawable.vivid_app_monitor)
            "tool:notes" -> return vivid(R.drawable.vivid_app_notes)
        }
        return line(when (entry) {
            "action:home" -> R.drawable.ic_home
            "tool:files" -> R.drawable.ic_folder
            "intent:browser" -> R.drawable.ic_browser
            "tool:settings" -> R.drawable.ic_settings
            "action:games" -> R.drawable.ic_games
            "intent:gallery" -> R.drawable.ic_image
            "tool:monitor" -> R.drawable.ic_monitor
            "tool:notes" -> R.drawable.ic_notes
            "action:search" -> R.drawable.ic_search
            "tool:cleanup" -> R.drawable.ic_delete
            "tool:appmgr" -> R.drawable.ic_apps
            else -> R.drawable.ic_apps
        })
    }

    /** LX's internal windows in the taskbar. */
    fun tool(kind: String, useVivid: Boolean): Choice = when (kind) {
        "settings" -> pin("tool:settings", useVivid)
        "files" -> pin("tool:files", useVivid)
        "monitor" -> pin("tool:monitor", useVivid)
        "notes" -> pin("tool:notes", useVivid)
        "cleanup" -> line(R.drawable.ic_cleaning)
        "appmgr" -> line(R.drawable.ic_apps)
        "welcome" -> line(R.drawable.ic_info)
        "tedit" -> pin("tool:tedit", useVivid)
        else -> line(R.drawable.ic_apps)
    }

    /** A folder or file in LX Files, chosen by folder name or by MIME type. */
    fun file(name: String, mime: String, isDir: Boolean, useVivid: Boolean): Choice {
        if (isDir) return if (useVivid) vivid(R.drawable.ic_fm_folder) else line(R.drawable.ic_folder)
        // A symbolic link opens where it leads; its glyph says so in both icon styles, as no Vivid icon does.
        if (mime == "inode/symlink") return line(R.drawable.ic_link)
        val type = mime.ifEmpty {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()).orEmpty()
        }
        if (!useVivid) return line(when {
            type.startsWith("image/") -> R.drawable.ic_image
            type.startsWith("audio/") -> R.drawable.ic_music
            type.startsWith("text/") -> R.drawable.ic_notes
            else -> R.drawable.ic_file
        })
        val extension = name.substringAfterLast('.', "").lowercase()
        return vivid(when {
            type.startsWith("image/") -> R.drawable.ic_fm_image
            type.startsWith("audio/") -> R.drawable.ic_fm_audio
            type.startsWith("video/") -> R.drawable.ic_fm_video
            type == "application/pdf" -> R.drawable.ic_fm_pdf
            type == "application/vnd.android.package-archive" -> R.drawable.ic_fm_app
            extension == "iso" || extension == "img" || "iso9660" in type -> R.drawable.ic_fm_disc
            "zip" in type || "x-7z" in type || "x-rar" in type || "x-tar" in type || "gzip" in type ||
                extension in setOf("xz", "bz2", "zst", "rar", "7z", "tgz") -> R.drawable.ic_fm_archive
            type == "text/html" || type == "application/json" || type == "application/xml" || "javascript" in type ||
                "x-sh" in type || "x-python" in type -> R.drawable.ic_fm_code
            type.startsWith("text/") || "wordprocessing" in type || "msword" in type || "opendocument" in type ||
                "spreadsheet" in type || "excel" in type || "presentation" in type || "powerpoint" in type -> R.drawable.ic_fm_doc
            else -> R.drawable.ic_fm_file
        })
    }

    /** Sets the icon, tinting only line glyphs. */
    fun apply(view: ImageView, choice: Choice, tint: Int) {
        view.setImageResource(choice.res)
        view.imageTintList = if (choice.tinted) ColorStateList.valueOf(tint) else null
    }

    /** Sets a glyph resource with [tint], or an icon in its own colours as it is. */
    fun applyRes(view: ImageView, res: Int, tint: Int) {
        view.setImageResource(res)
        view.imageTintList = if (ownColours(res)) null else ColorStateList.valueOf(tint)
    }
}
