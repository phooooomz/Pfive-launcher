// Copyright 2026 ExsoKamabay
// SPDX-License-Identifier: Apache-2.0+
package com.lxlauncher.ui

import com.lxlauncher.ui.theme.GlassEnv
import com.lxlauncher.ui.theme.LxTheme

/**
 * The shell LX's windows and the tools inside them see. Everything comes from the activity
 * except the colours, type and shapes, which come from the chosen window style. Menus,
 * dialogs and notices a tool opens are still drawn by the activity in the LX theme.
 * The theme is read on each use, because the activity resolves it again whenever settings change.
 */
class StyledShell(base: Shell, private val theme: () -> LxTheme, override val glass: GlassEnv) : Shell by base {
    override val ui: LxTheme get() = theme()
}
