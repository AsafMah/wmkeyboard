package com.wasimaster.wmkeyboard.core.settings

/**
 * The keyboard's own share sheet (#970), grouped like [LauncherToolSettings].
 * The keys stay flat (`share_sheet_*`); the pinned and recent lists are per
 * device in SyncKeys, since they name installed apps.
 */
data class ShareSheetSettings(
    /**
     * Share from the keyboard and the settings app through the keyboard's own
     * sheet. Off hands every share to Android's chooser, as before; the sheet
     * itself always has a button for that chooser too.
     */
    val enabled: Boolean = true,
    /** Remember the apps shared to and lead the sheet with them. */
    val recentsEnabled: Boolean = true,
    /** Pinned targets (`package/activity`), in the user's order. */
    val pinned: List<String> = emptyList(),
    /** Recent targets, newest first, capped at `ShareTargets.MAX_RECENTS`. */
    val recents: List<String> = emptyList(),
)
