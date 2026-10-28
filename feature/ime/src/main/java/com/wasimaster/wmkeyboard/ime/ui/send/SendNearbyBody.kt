package com.wasimaster.wmkeyboard.ime.ui.send

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Computer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Devices
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Laptop
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInBrowser
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCodeScanner
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Smartphone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tablet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tv
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wifi
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceType
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairState
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransfer
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import com.wasimaster.wmkeyboard.core.send.LanTransfer
import com.wasimaster.wmkeyboard.ime.send.BrowserFailure
import com.wasimaster.wmkeyboard.ime.send.BrowserUi
import com.wasimaster.wmkeyboard.ime.send.DirectFailure
import com.wasimaster.wmkeyboard.ime.send.DirectUi
import com.wasimaster.wmkeyboard.ime.send.JoinStep
import com.wasimaster.wmkeyboard.ime.send.SendAction
import com.wasimaster.wmkeyboard.ime.send.SendSheet
import com.wasimaster.wmkeyboard.ime.send.SendSource
import com.wasimaster.wmkeyboard.ime.send.SendUi
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockCard
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.SectionLabel

/**
 * The Nearby mode (#729): devices on this Wi-Fi from the KDE Connect engine,
 * the direct link between two phones, and the transfers under way. Network
 * state is read straight from [KdeConnectHub.state], as the KDE panel does,
 * so a progress tick never enters the keyboard state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SendNearbyBody(state: KeyboardUiState, ui: SendUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    if (!ui.nearbyAvailable) {
        EmptyState(
            Icons.Outlined.Wifi,
            stringResource(R.string.ime_send_nearby_off_title),
            stringResource(R.string.ime_send_nearby_off_body),
        ) {
            PillButton(stringResource(R.string.ime_send_nearby_turn_on), primary = true) { act(SendAction.OpenSettings) }
        }
        return
    }
    val hub by KdeConnectHub.state.collectAsState()
    val draft = ui.draft
    val direct = ui.nearby.direct
    val browser = ui.nearby.browser
    val motion = kb.motion.any
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        // What is going: the draft, or the ways to fill it.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (draft.isEmpty) {
                MetaText(stringResource(R.string.ime_send_nearby_add_files))
                ToolPanelChip(stringResource(R.string.ime_send_source_files)) { act(SendAction.AddFrom(SendSource.FILES)) }
                ToolPanelChip(stringResource(R.string.ime_send_source_gallery)) { act(SendAction.AddFrom(SendSource.GALLERY)) }
                ToolPanelChip(stringResource(R.string.ime_send_source_clipboard)) { act(SendAction.AddFrom(SendSource.CLIPBOARD)) }
                ToolPanelChip(stringResource(R.string.ime_send_source_text)) { act(SendAction.AddFrom(SendSource.FIELD_TEXT))
                }
            } else {
                Text(
                    pluralStringResource(R.plurals.ime_send_files_count, draft.files.size, draft.files.size) + " · " + bytesText(context, draft.totalSize),
                    color = kb.suggestionText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
                ToolPanelChip(stringResource(R.string.ime_send_clear)) { act(SendAction.ClearFiles) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Radar(Modifier.size(18.dp), animate = hub.running)
            MetaText(stringResource(R.string.ime_send_nearby_searching))
            ToolPanelChip(stringResource(R.string.ime_send_nearby_refresh)) { act(SendAction.RefreshDevices) }
            if (browser == BrowserUi.Off) {
                ToolPanelChip(stringResource(R.string.ime_send_browser_start), icon = Icons.Outlined.OpenInBrowser) { act(SendAction.StartBrowser) }
            }
            if (direct == DirectUi.Off) {
                ToolPanelChip(stringResource(R.string.ime_send_direct_host), icon = Icons.Outlined.WifiTethering) { act(SendAction.HostDirect) }
                if (ui.canScan) ToolPanelChip(stringResource(R.string.ime_send_direct_scan), icon = Icons.Outlined.QrCodeScanner) { act(SendAction.ScanDirect) }
            }
        }
        AnimatedVisibility(visible = browser != BrowserUi.Off, enter = fadeIn(tween(if (motion) 160 else 0)), exit = fadeOut(tween(if (motion) 120 else 0))) {
            Column {
                Spacer(Modifier.height(6.dp))
                BrowserCard(browser, draft.files.size, act)
            }
        }
        AnimatedVisibility(visible = direct != DirectUi.Off, enter = fadeIn(tween(if (motion) 160 else 0)), exit = fadeOut(tween(if (motion) 120 else 0))) {
            Column {
                Spacer(Modifier.height(6.dp))
                DirectCard(direct, act)
            }
        }
        Spacer(Modifier.height(6.dp))
        val paired = hub.paired.sortedByDescending { it.reachable }
        val nearby = hub.nearby
        val transfers = hub.devices.flatMap { d -> d.transfers.map { d to it } }.sortedByDescending { it.second.id }
        if (paired.isEmpty() && nearby.isEmpty() && transfers.isEmpty()) {
            EmptyState(
                Icons.Outlined.Devices,
                stringResource(R.string.ime_send_nearby_empty_title),
                stringResource(R.string.ime_send_nearby_empty_body),
                modifier = Modifier.weight(1f),
            )
            if (direct == DirectUi.Off) {
                Text(stringResource(R.string.ime_send_direct_body), color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (paired.isNotEmpty()) {
                item(key = "h-paired") { SectionLabel(stringResource(R.string.ime_send_nearby_paired)) }
                items(paired, key = { "p-" + it.id }) { device ->
                    DeviceRow(device, rowMotion(motion)) {
                        when {
                            device.pairState == KdePairState.REQUESTED_BY_PEER -> PairChips(device, act)
                            device.reachable && !draft.isEmpty -> PillButton(stringResource(R.string.ime_send_nearby_send), primary = true, height = 30.dp) {
                                act(SendAction.SendNearby(device.id))
                            }
                            device.reachable -> MetaText(stringResource(R.string.ime_send_nearby_connected), color = ConnectedGreen)
                            else -> MetaText(stringResource(R.string.ime_send_nearby_unreachable))
                        }
                    }
                }
            }
            if (nearby.isNotEmpty()) {
                item(key = "h-nearby") { SectionLabel(stringResource(R.string.ime_send_nearby_found)) }
                items(nearby, key = { "n-" + it.id }) { device ->
                    DeviceRow(device, rowMotion(motion)) {
                        when (device.pairState) {
                            KdePairState.REQUESTED_BY_PEER -> PairChips(device, act)
                            KdePairState.REQUESTED -> MetaText(stringResource(R.string.ime_send_nearby_pairing, device.verificationKey.orEmpty()))
                            else -> ToolPanelChip(stringResource(R.string.ime_send_nearby_pair), selected = true) { act(SendAction.PairDevice(device.id)) }
                        }
                    }
                }
            }
            if (transfers.isNotEmpty()) {
                item(key = "h-transfers") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel(stringResource(R.string.ime_send_transfers), Modifier.weight(1f))
                        val finished = transfers.any { it.second.state != KdeTransferState.RUNNING && it.second.state != KdeTransferState.WAITING }
                        if (finished) {
                            ToolPanelChip(stringResource(R.string.ime_send_transfer_clear)) {
                                transfers.map { it.first.id }.distinct().forEach { act(SendAction.ClearTransfers(it)) }
                            }
                        }
                    }
                }
                items(transfers, key = { "t-" + it.first.id + "-" + it.second.id }) { (device, transfer) ->
                    TransferRow(device, transfer, rowMotion(motion), act)
                }
            }
        }
    }
}

/** A list row that fades in and settles rather than snapping into place. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.rowMotion(animate: Boolean): Modifier = if (!animate) {
    Modifier
} else {
    Modifier.animateItem(
        fadeInSpec = tween(180),
        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
        fadeOutSpec = tween(140),
    )
}

@Composable
private fun DeviceRow(device: KdeDevice, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(kb.keyRadiusDp.dp))
            .background(kb.chip.copy(alpha = kb.chip.alpha * 0.6f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(deviceIcon(device.type), contentDescription = null, tint = if (device.reachable) kb.accent else kb.secondaryText, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = kb.chipText, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (device.address.isNotEmpty()) MetaText(device.address)
        }
        trailing()
    }
}

@Composable
private fun PairChips(device: KdeDevice, act: (SendAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        device.verificationKey?.let { MetaText(it) }
        ToolPanelChip(stringResource(R.string.ime_send_nearby_accept), selected = true) { act(SendAction.AcceptPair(device.id)) }
        ToolPanelChip(stringResource(R.string.ime_send_nearby_reject)) { act(SendAction.RejectPair(device.id)) }
    }
}

@Composable
private fun TransferRow(device: KdeDevice, t: KdeTransfer, modifier: Modifier, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val active = t.state == KdeTransferState.WAITING || t.state == KdeTransferState.RUNNING
    ClockCard(modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon("", t.fileName), contentDescription = null, tint = kb.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.fileName, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaText(
                    device.name + " · " + stringResource(
                        when (t.state) {
                            KdeTransferState.WAITING -> R.string.ime_send_transfer_waiting
                            KdeTransferState.RUNNING -> if (t.outgoing) R.string.ime_send_transfer_sending else R.string.ime_send_transfer_receiving
                            KdeTransferState.DONE -> R.string.ime_send_transfer_done
                            KdeTransferState.FAILED -> R.string.ime_send_transfer_failed
                            KdeTransferState.CANCELLED -> R.string.ime_send_transfer_cancelled
                        },
                    ) + if (t.size > 0) " · " + bytesText(context, t.size) else "",
                    color = if (t.state == KdeTransferState.FAILED) FailedRed else null,
                )
            }
            when {
                active -> ToolPanelChip(stringResource(R.string.ime_send_cancel)) { act(SendAction.CancelTransfer(t.id)) }
                !t.outgoing && t.state == KdeTransferState.DONE && t.location.isNotEmpty() ->
                    ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenTransfer(t.location, t.fileName)) }
            }
        }
        if (t.state == KdeTransferState.RUNNING) {
            Spacer(Modifier.height(4.dp))
            if (t.size > 0) {
                LinearProgressIndicator(
                    progress = { (t.done.toFloat() / t.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = kb.accent,
                    trackColor = kb.divider,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = kb.accent, trackColor = kb.divider)
            }
        }
    }
}

/** The direct link's state: opening, waiting with the code, joining step by step, or why it failed. */
@Composable
private fun DirectCard(direct: DirectUi, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    ClockCard(Modifier.fillMaxWidth(), highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                if (direct is DirectUi.Starting || (direct is DirectUi.Hosting && direct.joined == null) || (direct is DirectUi.Joining && direct.step != JoinStep.LINKED)) {
                    Radar(Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.WifiTethering, contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                when (direct) {
                    DirectUi.Off -> Unit
                    DirectUi.Starting -> Text(stringResource(R.string.ime_send_direct_starting), color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    is DirectUi.Hosting -> {
                        Text(
                            if (direct.joined != null) stringResource(R.string.ime_send_direct_joined, direct.joined) else stringResource(R.string.ime_send_direct_hosting_title),
                            color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        MetaText(stringResource(R.string.ime_send_direct_wifi, direct.code.ssid))
                    }
                    is DirectUi.Joining -> {
                        Text(
                            stringResource(
                                when (direct.step) {
                                    JoinStep.WIFI -> R.string.ime_send_direct_step_wifi
                                    JoinStep.FINDING -> R.string.ime_send_direct_step_finding
                                    JoinStep.PAIRING -> R.string.ime_send_direct_step_pairing
                                    JoinStep.LINKED -> R.string.ime_send_direct_step_linked
                                },
                                direct.code.name,
                            ),
                            color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        MetaText(stringResource(R.string.ime_send_direct_wifi, direct.code.ssid))
                    }
                    is DirectUi.Failed -> Text(
                        stringResource(
                            when (direct.reason) {
                                DirectFailure.NO_PERMISSION -> R.string.ime_send_direct_failed_permission
                                DirectFailure.WIFI_OFF -> R.string.ime_send_direct_failed_wifi_off
                                DirectFailure.UNSUPPORTED -> R.string.ime_send_direct_failed_unsupported
                                DirectFailure.GROUP_FAILED -> R.string.ime_send_direct_failed_group
                                DirectFailure.JOIN_FAILED -> R.string.ime_send_direct_failed_join
                                DirectFailure.NOT_FOUND -> R.string.ime_send_direct_failed_not_found
                                DirectFailure.TIMEOUT -> R.string.ime_send_direct_failed_timeout
                            },
                        ),
                        color = kb.chipText, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (direct) {
                is DirectUi.Hosting -> {
                    ToolPanelChip(stringResource(R.string.ime_send_direct_show_code), icon = Icons.Outlined.QrCode2) { act(SendAction.OpenSheet(SendSheet.DIRECT_CODE)) }
                    Spacer(Modifier.width(4.dp))
                    ToolPanelChip(stringResource(R.string.ime_send_direct_stop)) { act(SendAction.StopDirect) }
                }
                is DirectUi.Failed -> {
                    if (direct.reason == DirectFailure.NO_PERMISSION) {
                        ToolPanelChip(stringResource(R.string.ime_send_direct_allow), selected = true) { act(SendAction.AskNearbyPermission) }
                        Spacer(Modifier.width(4.dp))
                    }
                    ToolPanelChip(stringResource(R.string.ime_send_close)) { act(SendAction.StopDirect) }
                }
                DirectUi.Off -> Unit
                else -> ToolPanelChip(stringResource(R.string.ime_send_direct_stop)) { act(SendAction.StopDirect) }
            }
        }
    }
}

/**
 * The page served to browsers (#927): opening a Wi-Fi for it, up with its
 * address and what went through it, or why it could not start.
 */
@Composable
private fun BrowserCard(browser: BrowserUi, fileCount: Int, act: (SendAction) -> Unit) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    ClockCard(Modifier.fillMaxWidth(), highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(kb.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                if (browser is BrowserUi.Starting) {
                    Radar(Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                when (browser) {
                    BrowserUi.Off -> Unit
                    BrowserUi.Starting -> Text(stringResource(R.string.ime_send_direct_starting), color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    is BrowserUi.Serving -> {
                        Text(
                            if (fileCount == 0) stringResource(R.string.ime_send_browser_receiving_only)
                            else pluralStringResource(R.plurals.ime_send_browser_sharing, fileCount, fileCount),
                            color = kb.chipText, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        MetaText(
                            browser.hotspot?.let { stringResource(R.string.ime_send_direct_wifi, it.ssid) } ?: browser.url.removePrefix("http://"),
                        )
                    }
                    is BrowserUi.Failed -> Text(
                        stringResource(
                            when (browser.hotspot) {
                                DirectFailure.NO_PERMISSION -> R.string.ime_send_direct_failed_permission
                                DirectFailure.WIFI_OFF -> R.string.ime_send_direct_failed_wifi_off
                                DirectFailure.UNSUPPORTED -> R.string.ime_send_direct_failed_unsupported
                                null -> when (browser.reason) {
                                    BrowserFailure.NO_NETWORK -> R.string.ime_send_browser_failed_network
                                    BrowserFailure.SERVER -> R.string.ime_send_browser_failed_server
                                    BrowserFailure.HOTSPOT -> R.string.ime_send_direct_failed_group
                                }
                                else -> R.string.ime_send_direct_failed_group
                            },
                        ),
                        color = kb.chipText, fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            when (browser) {
                is BrowserUi.Serving -> {
                    ToolPanelChip(stringResource(R.string.ime_send_direct_show_code), icon = Icons.Outlined.QrCode2) { act(SendAction.OpenSheet(SendSheet.BROWSER_CODE)) }
                    Spacer(Modifier.width(4.dp))
                    ToolPanelChip(stringResource(R.string.ime_send_browser_stop)) { act(SendAction.StopBrowser) }
                }
                is BrowserUi.Failed -> {
                    when {
                        browser.hotspot == DirectFailure.NO_PERMISSION ->
                            ToolPanelChip(stringResource(R.string.ime_send_direct_allow), selected = true) { act(SendAction.OpenBrowserHotspot) }
                        browser.reason == BrowserFailure.NO_NETWORK ->
                            ToolPanelChip(stringResource(R.string.ime_send_browser_open_hotspot), selected = true) { act(SendAction.OpenBrowserHotspot) }
                    }
                    Spacer(Modifier.width(4.dp))
                    ToolPanelChip(stringResource(R.string.ime_send_close)) { act(SendAction.StopBrowser) }
                }
                BrowserUi.Off -> Unit
                BrowserUi.Starting -> ToolPanelChip(stringResource(R.string.ime_send_browser_stop)) { act(SendAction.StopBrowser) }
            }
        }
        if (browser is BrowserUi.Serving) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ToolPanelChip(stringResource(R.string.ime_send_browser_uploads), selected = browser.uploads) { act(SendAction.BrowserUploads(!browser.uploads)) }
                if (browser.hotspot == null) {
                    ToolPanelChip(stringResource(R.string.ime_send_browser_hotspot), icon = Icons.Outlined.WifiTethering) { act(SendAction.OpenBrowserHotspot) }
                }
                ToolPanelChip(stringResource(R.string.ime_send_copy)) { act(SendAction.CopyBrowserLink) }
            }
            browser.transfers.take(BROWSER_ROWS).forEach { t ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(t.type, t.name), contentDescription = null, tint = kb.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, color = kb.chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MetaText(
                            listOf(
                                t.peer,
                                stringResource(
                                    when (t.state) {
                                        LanTransfer.State.RUNNING -> if (t.incoming) R.string.ime_send_transfer_receiving else R.string.ime_send_transfer_sending
                                        LanTransfer.State.DONE -> R.string.ime_send_transfer_done
                                        LanTransfer.State.FAILED -> R.string.ime_send_transfer_failed
                                    },
                                ),
                                if (t.size > 0) bytesText(context, t.size) else "",
                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = if (t.state == LanTransfer.State.FAILED) FailedRed else null,
                        )
                        if (t.state == LanTransfer.State.RUNNING && t.size > 0) {
                            Spacer(Modifier.height(3.dp))
                            LinearProgressIndicator(
                                progress = { (t.done.toFloat() / t.size).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                                color = kb.accent,
                                trackColor = kb.divider,
                            )
                        }
                    }
                    val location = t.location
                    if (t.incoming && t.state == LanTransfer.State.DONE && location != null) {
                        Spacer(Modifier.width(6.dp))
                        ToolPanelChip(stringResource(R.string.ime_send_open), selected = true) { act(SendAction.OpenTransfer(location, t.name)) }
                    }
                }
            }
        }
    }
}

/** Transfers the browser card lists under itself; the rest are in History. */
private const val BROWSER_ROWS = 3

private fun deviceIcon(type: KdeDeviceType): ImageVector = when (type) {
    KdeDeviceType.DESKTOP -> Icons.Outlined.Computer
    KdeDeviceType.LAPTOP -> Icons.Outlined.Laptop
    KdeDeviceType.PHONE -> Icons.Outlined.Smartphone
    KdeDeviceType.TABLET -> Icons.Outlined.Tablet
    KdeDeviceType.TV -> Icons.Outlined.Tv
}
