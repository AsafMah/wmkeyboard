package com.wasimaster.wmkeyboard.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.layout.DefaultSymbolChords
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.SymbolChord
import com.wasimaster.wmkeyboard.core.layout.chordOf
import com.wasimaster.wmkeyboard.core.layout.effectiveChords
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The `?123` chords group of the gestures page (issue #754): the switch, one
 * row per chord in force, a row to add one, and a reset. Each chord row opens
 * the same action picker a multi-finger swipe gets; **No action** removes it.
 */
@Composable
internal fun SymbolChordSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val on = settings.watch { it.gesture.symbolChords }
    val bound = settings.watch { it.gesture.chords }
    val layouts = secondaryLayouts(settings.watch { it.customLayouts })
    val modes = settings.watch { it.keyboardModes }
    val inForce = bound.effectiveChords()
    // The key whose chord is being picked, and whether the key is still to be named.
    var picking by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    fun bind(token: String, binding: KeyAlternate?) {
        scope.launch { repository.setSymbolChord(token, binding) }
    }
    SettingsGroup(stringResource(R.string.typing_group_symbol_chords_title)) {
        item {
            ToggleSetting(
                R.string.typing_symbol_chords_title,
                stringResource(R.string.typing_symbol_chords_subtitle),
                on,
                info = stringResource(R.string.typing_symbol_chords_info),
                default = SettingsDefaults.gesture.symbolChords,
            ) { scope.launch { repository.setSymbolChords(it) } }
        }
        for ((token, binding) in inForce) {
            item(visible = on, key = "chord-$token") {
                NavRow(
                    title = stringResource(R.string.typing_symbol_chord_row_title, token.uppercase()),
                    icon = SettingsRowIcons[R.string.typing_symbol_chord_row_title],
                    value = fingerSwipeBindingName(binding, layouts, modes),
                ) { picking = token }
            }
        }
        item(visible = on) {
            NavRow(
                R.string.typing_symbol_chord_add_title,
                stringResource(R.string.typing_symbol_chord_add_subtitle),
            ) { naming = true }
        }
        item(visible = on && bound.isNotEmpty()) {
            NavRow(
                R.string.typing_symbol_chords_reset_title,
                stringResource(R.string.typing_symbol_chords_reset_subtitle),
            ) { scope.launch { repository.resetSymbolChords() } }
        }
    }

    if (naming) {
        ChordKeyDialog(
            onDismiss = { naming = false },
        ) { token ->
            naming = false
            picking = token
        }
    }

    picking?.let { token ->
        KeyBindingPicker(
            current = bound.chordOf(token),
            layouts = layouts,
            modes = modes,
            onDismiss = { picking = null },
        ) { binding ->
            picking = null
            // No action on a chord nobody set up by default just forgets it;
            // on a default it has to be stored, or the default comes back.
            val keep = binding.action != KeyAction.None || token in DefaultSymbolChords
            bind(token, binding.takeIf { keep })
        }
    }
}

/** Names the key a new chord taps: the one character it types. */
@Composable
private fun ChordKeyDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val token = text.trim().lowercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.typing_symbol_chord_key_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.typing_symbol_chord_key_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onDone(token) }, enabled = SymbolChord.isToken(token)) {
                Text(stringResource(CommonR.string.common_next))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
