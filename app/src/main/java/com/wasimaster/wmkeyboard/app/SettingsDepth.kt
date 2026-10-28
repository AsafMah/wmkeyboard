package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth

/*
 * Settings depth: how much of the settings app is shown.
 *
 * Every row carries a [SettingLevel]; the depth in force ([SettingsDepth],
 * stored in `AppUiSettings.settingsDepth` and resolved by the nav host)
 * decides which levels are drawn. A row above the depth is not drawn at all.
 * It keeps its value, search still finds it (and opens its group with the
 * row showing), and About › App has the two controls: the depth itself, and
 * a switch that puts a "N more settings" row ([MoreSettingsRow]) at the end
 * of each group that hid something, for whoever wants to know where the
 * rest went.
 *
 * The point is parity with the keyboards most people come from: at Simple
 * the app shows about what they offer. Nothing here changes what the
 * keyboard does.
 */

/** How far down the settings depth a row sits. Untagged rows are [BASIC]. */
internal enum class SettingLevel {
    /**
     * Shown at every depth: what other keyboards offer, a feature's master
     * switch, or the list a page exists for.
     */
    BASIC,

    /** Shown at Standard and Everything: a choice a non-technical person could still want. */
    STANDARD,

    /** Shown at Everything only: timings, thresholds, multipliers, per-variant overrides. */
    ADVANCED,
}

/** Whether a row of [level] is drawn at this depth. */
internal fun SettingsDepth.shows(level: SettingLevel): Boolean = when (this) {
    SettingsDepth.SIMPLE -> level == SettingLevel.BASIC
    SettingsDepth.STANDARD -> level != SettingLevel.ADVANCED
    // AUTO never reaches a screen: the nav host publishes the resolved depth.
    SettingsDepth.EVERYTHING, SettingsDepth.AUTO -> true
}

/**
 * The depth in force, whether hidden rows are to be pointed at, and the way
 * to change the depth, published for every screen.
 */
@Stable
internal class DepthControl(
    val depth: SettingsDepth,
    val hiddenRows: Boolean,
    val change: (SettingsDepth) -> Unit,
)

internal val LocalSettingsDepth = compositionLocalOf { DepthControl(SettingsDepth.EVERYTHING, hiddenRows = false) {} }

/** The depth's name, as the row and the sheet say it. */
@Composable
internal fun depthName(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything
    },
)

/** One line on what the depth shows. */
@Composable
internal fun depthDescription(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple_desc
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard_desc
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything_desc
    },
)

/** The three depths a user can pick, named. */
@Composable
internal fun depthOptions(): List<Pair<SettingsDepth, String>> = listOf(
    SettingsDepth.SIMPLE to depthName(SettingsDepth.SIMPLE),
    SettingsDepth.STANDARD to depthName(SettingsDepth.STANDARD),
    SettingsDepth.EVERYTHING to depthName(SettingsDepth.EVERYTHING),
)

/**
 * The row at the end of a group that holds the rows above the depth, drawn
 * only while About › App › "Show where settings are hidden" is on. One row
 * rather than a heading: it sits in the group's own card stack, and the rows
 * it opens grow in under it, so the group stays one object.
 */
@Composable
internal fun MoreSettingsRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    WmRow(
        title = pluralStringResource(R.plurals.settings_depth_more_rows, count, count),
        icon = Icons.Outlined.Tune,
        trailing = { ExpandChevron(open) },
        onClick = onToggle,
    )
}
