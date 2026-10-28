package com.wasimaster.wmkeyboard.ime.ui.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.SendMode
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendCallbacks
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.CaptureCallbacks
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.MotionCrossfade
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.blockTouches
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton

/**
 * The Send files tool's panel (#729): a header with the Link | Nearby pill,
 * the instance or device chip, history and settings; a body that is one mode
 * at a time; sheets on a scrim over it; a notice line that fades.
 *
 * Tall while a sheet needs the room (the QR code, the lists). Collapsed over
 * the key rows while one of its boxes (a password, a link, an address) has
 * the keys, the way the KDE panel collapses for its address box.
 */
// The capture bundle is the keyboard's; the panel's boxes draw through
// SearchQueryText, which reads the caret from the same local. Kept as a
// parameter so the dispatcher's shape matches the KDE panel's.
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SendPanel(
    state: KeyboardUiState,
    callbacks: SendCallbacks,
    capture: CaptureCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val kb = LocalKbTheme.current
    val typing = state.sendTypingActive
    val tall = ui.sheet == SendSheet.QR || ui.sheet == SendSheet.HISTORY || ui.sheet == SendSheet.INSTANCES || ui.sheet == SendSheet.DIRECT_CODE ||
        ui.sheet == SendSheet.BROWSER_CODE
    val screen = LocalConfiguration.current.screenHeightDp.dp
    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = if (tall) screen else 0.dp,
        compact = typing,
        compactHeight = TypingCompactHeight,
        headerActions = { SendHeader(ui, act) },
    ) {
        Box(Modifier.fillMaxSize()) {
            MotionCrossfade(
                target = ui.mode,
                animate = kb.motion.panels,
                modifier = Modifier.fillMaxSize(),
                label = "send-mode-body",
            ) { mode ->
                Box(Modifier.fillMaxSize().blockTouches(mode != ui.mode)) {
                    when (mode) {
                        SendMode.LINK -> SendLinkBody(state, ui, act)
                        SendMode.NEARBY -> SendNearbyBody(state, ui, act)
                    }
                }
            }
            SendScrim(visible = ui.sheet != null || ui.dataAsk, onDismiss = { act(SendAction.Dismiss) }) {
                SendSheets(ui, act)
            }
            SendNoticeLine(ui.notice, Modifier.align(Alignment.TopCenter))
        }
    }
}

/** The header row: the mode pill, the instance or device chip, history, settings. */
@Composable
private fun RowScope.SendHeader(ui: SendUi, act: (SendAction) -> Unit) {
    val hub by KdeConnectHub.state.collectAsState()
    Spacer(Modifier.width(2.dp))
    ModePill(ui.mode, nearbyEnabled = ui.nearbyAvailable, onSelect = { act(SendAction.SetMode(it)) })
    Spacer(Modifier.width(8.dp))
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
        when (ui.mode) {
            SendMode.LINK -> ToolPanelChip(
                hostText(ui.instance) + " ▾",
                selected = ui.sheet == SendSheet.INSTANCES,
            ) { act(SendAction.OpenSheet(if (ui.sheet == SendSheet.INSTANCES) null else SendSheet.INSTANCES)) }
            SendMode.NEARBY -> {
                val count = hub.devices.count { it.reachable }
                ToolPanelChip(
                    if (count == 0) stringResource(R.string.ime_send_devices_count_zero) else pluralStringResource(R.plurals.ime_send_devices_count, count, count),
                ) { act(SendAction.RefreshDevices) }
            }
        }
    }
    RoundButton(Icons.Outlined.History, stringResource(R.string.ime_send_history), size = 30.dp, primary = ui.sheet == SendSheet.HISTORY) {
        act(SendAction.OpenSheet(if (ui.sheet == SendSheet.HISTORY) null else SendSheet.HISTORY))
    }
    Spacer(Modifier.width(4.dp))
    RoundButton(Icons.Outlined.Settings, stringResource(R.string.ime_send_settings_desc), size = 30.dp) { act(SendAction.OpenSettings) }
    Spacer(Modifier.width(4.dp))
}

/** Room for the box, its label and its chips over the key rows. */
private val TypingCompactHeight = 136.dp
