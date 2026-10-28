package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.runtime.Immutable

/**
 * Why a control is drawn greyed out, for the long press that asks.
 *
 * A disabled switch, slider or button used to say nothing at all: it simply
 * did not answer, and the user had to guess which other setting was holding it
 * back. Wherever a caller passes `enabled = false` it can pass one of these
 * beside it, and holding the greyed control shows [text] — in the settings app
 * at the top of the row's long-press menu, on the keyboard in the hold bubble
 * the keyboard's `IconHint` already draws for a button's name.
 *
 * [route] and [setting] are the way out, when there is one: the settings
 * screen holding the switch that lifts the block, and the resource name of
 * that switch's title, so the screen opens on it and pulses it. They are the
 * two halves of a `wmkeyboard://settings/<route>?setting=<name>` link
 * (`MainActivityContract.intent` takes the same pair), named as strings so a
 * library module can write one without knowing the app's resources. Either
 * may be null: [setting] alone lets the app's settings index find the screen,
 * and neither means nothing in settings can help ("Not in this build", "Needs
 * Android 12").
 *
 * Only the reason is passed, never a second `enabled`: a control is drawn
 * disabled by its own flag, and a reason on an enabled control is ignored, so
 * a caller can build it once and hand it over whatever the state.
 */
@Immutable
data class DisabledReason(
    val text: String,
    val route: String? = null,
    val setting: String? = null,
) {
    /** Whether there is a settings screen to send the user to. */
    val opensSettings: Boolean get() = !route.isNullOrEmpty() || !setting.isNullOrEmpty()
}
