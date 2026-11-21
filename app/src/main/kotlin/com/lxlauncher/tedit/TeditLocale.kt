// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.tedit

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.view.ContextThemeWrapper
import java.util.Locale

/**
 * The context Tedit builds its views from: LX's window with LX's theme, whose text comes in the language chosen in
 * Tedit's settings (Settings > General > Language) rather than the phone's. Only Tedit changes language; LX around it
 * keeps the phone's. "system" follows the phone again.
 */
class TeditLocaleContext(base: Context, language: String) : ContextThemeWrapper(base, base.theme) {
    private var res: Resources = resourcesFor(language)

    /** Takes [language] for text read from now on; views built before keep theirs until Tedit builds them again. */
    fun setLanguage(language: String) {
        res = resourcesFor(language)
    }

    override fun getResources(): Resources = res

    private fun resourcesFor(language: String): Resources {
        val base = baseContext
        if (language == SYSTEM || language.isBlank()) return base.resources
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(config).resources
    }

    companion object {
        const val SYSTEM = "system"
    }
}
