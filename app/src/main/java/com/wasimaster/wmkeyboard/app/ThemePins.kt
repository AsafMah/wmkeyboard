package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.activeThemeSpec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.themeName
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * Why a global sizing row is greyed out while the active theme's "Layout for
 * this theme" group pins the value it sets, or null while nothing does.
 *
 * That group seeds its fields from the globals and the keyboard then reads the
 * theme's copy in their place (`applyThemeOverrides`), so the global row keeps
 * moving and persisting while the board ignores it, which read as a broken
 * slider (#88). The row says so instead, and switches itself off: a caller
 * passes the reason's text as its subtitle, `enabled = it == null`, and the
 * reason itself as `disabledReason`, whose long press opens the theme's editor
 * on that group. The global is still stored, and comes back the moment the
 * theme stops pinning it.
 *
 * [pinned] picks the theme field that shadows this row; any non-null value is
 * a pin. Auto-theme resolves through the same [activeThemeSpec] the keyboard
 * uses, with the system's dark mode standing in for the slot, so a schedule or
 * sunset trigger can be showing the other half for a while; the note then
 * follows the system rather than the clock.
 */
@Composable
internal fun themePin(settings: LiveSettings, pinned: (ThemeSpec) -> Any?): DisabledReason? {
    val dark = isSystemInDarkTheme()
    val spec = settings.watch { it.activeThemeSpec(dark) } ?: return null
    if (pinned(spec) == null) return null
    val resources = LocalContext.current.resources
    return DisabledReason(
        stringResource(R.string.appearance_theme_pinned_subtitle, themeName(spec)),
        route = themeEditRoute(spec.id),
        setting = resources.getResourceEntryName(R.string.theme_custom_layout_title),
    )
}
