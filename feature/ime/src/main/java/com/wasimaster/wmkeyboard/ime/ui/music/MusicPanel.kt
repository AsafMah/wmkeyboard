package com.wasimaster.wmkeyboard.ime.ui.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.GraphicEq
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MusicNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Photo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sensors
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Stop
import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.MusicHistoryEntry
import com.wasimaster.wmkeyboard.core.music.MusicTextFormat
import com.wasimaster.wmkeyboard.core.music.MusicTextFormats
import com.wasimaster.wmkeyboard.core.music.SongMatch
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.music.MusicAction
import com.wasimaster.wmkeyboard.ime.music.MusicCallbacks
import com.wasimaster.wmkeyboard.ime.music.MusicController
import com.wasimaster.wmkeyboard.ime.music.MusicPhase
import com.wasimaster.wmkeyboard.ime.music.MusicTab
import com.wasimaster.wmkeyboard.ime.music.MusicUi
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.IconHint
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.ToolCircle
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import com.wasimaster.wmkeyboard.ime.ui.clock.ClockCard
import com.wasimaster.wmkeyboard.ime.ui.clock.EmptyState
import com.wasimaster.wmkeyboard.ime.ui.clock.PillButton
import com.wasimaster.wmkeyboard.ime.ui.clock.RoundButton
import com.wasimaster.wmkeyboard.ime.ui.clock.SectionLabel
import com.wasimaster.wmkeyboard.ime.ui.swipeTabs
import java.text.DateFormat
import java.util.Date

/**
 * The Music ID tool: a big Listen button, then the song it heard, with the
 * ways to type it, send it as a picture and open it elsewhere; a History
 * tab of what it has recognised before.
 */
@Composable
internal fun MusicPanel(
    state: KeyboardUiState,
    callbacks: MusicCallbacks,
    onClose: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val tabs = MusicTab.entries

    PanelFocusTarget(
        panel = PanelMode.MUSIC_ID,
        region = FocusRegion.CHIPS,
        count = tabs.size,
        columns = tabs.size,
    ) { index -> tabs.getOrNull(index)?.let { act(MusicAction.Tab(it)) } }

    FullBleedTool(
        state = state,
        title = "",
        onClose = onClose,
        extraHeight = 36.dp,
        headerActions = { Header(state, ui, act) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .swipeTabs(ui.tab.ordinal, tabs.size) { act(MusicAction.Tab(tabs[it])) },
        ) {
            when (ui.tab) {
                MusicTab.LISTEN -> ListenTab(state, ui, act)
                MusicTab.HISTORY -> HistoryTab(ui, act)
            }
            Notice(ui.notice, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RowScope.Header(state: KeyboardUiState, ui: MusicUi, act: (MusicAction) -> Unit) {
    val focused = state.focusedIndex(FocusRegion.CHIPS)
    Row(
        modifier = Modifier
            .weight(1f)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.width(2.dp))
        ToolPanelChip(
            stringResource(R.string.ime_music_tab_listen),
            selected = ui.tab == MusicTab.LISTEN || focused == 0,
        ) { act(MusicAction.Tab(MusicTab.LISTEN)) }
        ToolPanelChip(
            if (ui.history.isEmpty()) {
                stringResource(R.string.ime_music_tab_history)
            } else {
                stringResource(R.string.ime_music_tab_history_count, ui.history.size)
            },
            selected = ui.tab == MusicTab.HISTORY || focused == 1,
        ) { act(MusicAction.Tab(MusicTab.HISTORY)) }
        Spacer(Modifier.width(2.dp))
    }
    ToolCircle(
        slot = IconSlots.forTool(ToolbarTool.SETTINGS),
        description = stringResource(R.string.ime_music_settings_desc),
        active = false,
    ) { act(MusicAction.OpenSettings) }
}

// ---- Listen ----

@Composable
private fun ListenTab(state: KeyboardUiState, ui: MusicUi, act: (MusicAction) -> Unit) {
    when (val phase = ui.phase) {
        MusicPhase.Idle -> IdleBody(ui, state.settings.reduceMotion, act)
        is MusicPhase.Listening -> ListeningBody(phase, state.settings.reduceMotion, act)
        is MusicPhase.Recognizing -> RecognizingBody(phase, state.settings.reduceMotion)
        is MusicPhase.Result -> ResultBody(phase.match, ui, state, act, nowPlaying = false)
        is MusicPhase.NowPlaying -> ResultBody(phase.match, ui, state, act, nowPlaying = true)
        is MusicPhase.NoMatch -> MessageBody(
            icon = Icons.Outlined.MusicNote,
            title = stringResource(if (phase.silent) R.string.ime_music_silent_title else R.string.ime_music_no_match_title),
            body = stringResource(if (phase.silent) R.string.ime_music_silent_body else R.string.ime_music_no_match_body),
            action = stringResource(R.string.ime_music_listen_again),
            icon2 = Icons.Outlined.Refresh,
        ) { act(MusicAction.Listen) }
        is MusicPhase.Failed -> MessageBody(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.ime_music_failed_title),
            body = phase.message,
            action = stringResource(R.string.ime_music_retry),
            icon2 = Icons.Outlined.Refresh,
        ) { act(MusicAction.Listen) }
        MusicPhase.NeedsMic -> MessageBody(
            icon = Icons.Outlined.Mic,
            title = stringResource(R.string.ime_music_needs_mic_title),
            body = stringResource(R.string.ime_music_needs_mic_body),
            action = stringResource(R.string.ime_music_allow_mic),
            icon2 = Icons.Outlined.Mic,
        ) { act(MusicAction.RequestMic) }
        MusicPhase.NeedsProvider -> MessageBody(
            icon = Icons.Outlined.Sensors,
            title = stringResource(R.string.ime_music_needs_provider_title),
            body = stringResource(R.string.ime_music_needs_provider_body),
            action = stringResource(R.string.ime_music_set_up),
            icon2 = null,
        ) { act(MusicAction.OpenSettings) }
        MusicPhase.MeteredAsk -> MessageBody(
            icon = Icons.Outlined.GraphicEq,
            title = stringResource(R.string.ime_music_metered_title),
            body = stringResource(R.string.ime_music_metered_body),
            action = stringResource(R.string.ime_music_use_data),
            icon2 = null,
        ) { act(MusicAction.UseData) }
    }
}

@Composable
private fun IdleBody(ui: MusicUi, reduceMotion: Boolean, act: (MusicAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ListenButton(level = 0f, listening = false, reduceMotion = reduceMotion, description = stringResource(R.string.ime_music_listen_desc)) {
            act(MusicAction.Listen)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ime_music_idle_hint),
            color = kb.secondaryText,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        SourcesLine(ui, act)
    }
}

/** Who would answer: the recognisers that are on, in order, or the way to get one. */
@Composable
private fun SourcesLine(ui: MusicUi, act: (MusicAction) -> Unit) {
    val kb = LocalKbTheme.current
    if (ui.onlineNames.isEmpty()) {
        ToolPanelChip(stringResource(R.string.ime_music_set_up), icon = Icons.Outlined.Sensors) { act(MusicAction.OpenSettings) }
    } else {
        Text(
            stringResource(R.string.ime_music_sources_online, ui.onlineNames.joinToString(", ")),
            color = kb.secondaryText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ListeningBody(phase: MusicPhase.Listening, reduceMotion: Boolean, act: (MusicAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ListenButton(level = phase.level, listening = true, reduceMotion = reduceMotion, description = stringResource(R.string.ime_music_stop_desc)) {
            act(MusicAction.Stop)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ime_music_listening_progress, phase.secondsLeft),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(stringResource(R.string.ime_music_listening_hint), color = kb.secondaryText, fontSize = 11.sp)
    }
}

@Composable
private fun RecognizingBody(phase: MusicPhase.Recognizing, reduceMotion: Boolean) {
    val kb = LocalKbTheme.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spinner(reduceMotion)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.ime_music_recognizing_online, phase.via),
            color = kb.suggestionText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The big round button: the tool's waveform glyph in the accent, with a
 * ring that breathes while idle and swells with the microphone's level
 * while listening. Under reduced motion it holds still.
 */
@Composable
private fun ListenButton(level: Float, listening: Boolean, reduceMotion: Boolean, description: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val breath = rememberInfiniteTransition(label = "musicBreath")
    val pulse by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val ringScale by animateFloatAsState(
        targetValue = if (reduceMotion) 1f else if (listening) 1.08f + level * RING_LEVEL else 1f + pulse * RING_BREATH,
        animationSpec = tween(RING_MS),
        label = "ring",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(BUTTON_DP.dp)) {
        Box(
            Modifier
                .size(RING_DP.dp)
                .scale(ringScale)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = if (listening) 0.22f else 0.12f)),
        )
        if (listening && !reduceMotion) {
            Box(
                Modifier
                    .size(RING_DP.dp)
                    .scale(1.25f + level * RING_LEVEL * 1.5f)
                    .clip(CircleShape)
                    .background(kb.accent.copy(alpha = 0.08f)),
            )
        }
        IconHint(description) {
            Box(
                Modifier
                    .size(CORE_DP.dp)
                    .clip(CircleShape)
                    .background(kb.accent)
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (listening) Icons.Outlined.Stop else Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    tint = kb.chipActiveText,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun Spinner(reduceMotion: Boolean) {
    val kb = LocalKbTheme.current
    val spin = rememberInfiniteTransition(label = "musicSpin")
    val turn by spin.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
        label = "turn",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        Box(
            Modifier
                .size(56.dp)
                .scale(if (reduceMotion) 1f else 0.85f + 0.15f * kotlin.math.abs(turn - 0.5f) * 2)
                .clip(CircleShape)
                .background(kb.accent.copy(alpha = 0.14f)),
        )
        Icon(Icons.Outlined.GraphicEq, contentDescription = null, tint = kb.accent, modifier = Modifier.size(28.dp))
    }
}

// ---- The result ----

@Composable
private fun ResultBody(match: SongMatch, ui: MusicUi, state: KeyboardUiState, act: (MusicAction) -> Unit, nowPlaying: Boolean) {
    val kb = LocalKbTheme.current
    val settings = state.settings.music
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (nowPlaying) SectionLabel(stringResource(R.string.ime_music_now_playing_title))
        ClockCard(padding = PaddingValues(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(ui, 64.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        match.title,
                        color = kb.suggestionText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (match.artist.isNotBlank()) {
                        Text(match.artist, color = kb.suggestionText.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val extra = listOf(match.album, match.year).filter { it.isNotBlank() }.joinToString(" · ")
                    if (extra.isNotBlank()) {
                        Text(extra, color = kb.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(viaLabel(match), color = kb.secondaryText, fontSize = 10.sp, maxLines = 1)
                }
                RoundButton(Icons.Outlined.Refresh, stringResource(R.string.ime_music_listen_again), size = 32.dp) {
                    act(if (nowPlaying) MusicAction.ListenAnyway else MusicAction.Listen)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // What to type: the default format first, with its dot, then the rest.
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(stringResource(R.string.ime_music_insert), primary = true, height = 32.dp) { act(MusicAction.Insert()) }
            val formats = listOf(settings.textFormat) + MusicTextFormat.entries.filterNot { it == settings.textFormat }
            formats.forEach { format ->
                val preview = MusicTextFormats.render(match, format, settings.openLink).lineSequence().first()
                ToolPanelChip(preview.take(FORMAT_PREVIEW_CHARS), selected = format == settings.textFormat) {
                    act(MusicAction.Insert(format))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPanelChip(stringResource(R.string.ime_music_picture), selected = ui.pictureStrip, icon = Icons.Outlined.Photo) {
                act(MusicAction.PictureStrip(!ui.pictureStrip))
            }
            ToolPanelChip(stringResource(R.string.ime_music_copy), icon = Icons.Outlined.ContentCopy) { act(MusicAction.Copy) }
            val preferred = settings.openLink?.takeIf { match.link(it) != null }
            val links = listOfNotNull(preferred) + match.openable.filterNot { it == preferred }
            links.forEach { link ->
                ToolPanelChip(stringResource(R.string.ime_music_open_in, link.label), icon = Icons.Outlined.OpenInNew) {
                    act(MusicAction.Open(link))
                }
            }
        }
        AnimatedVisibility(visible = ui.pictureStrip) {
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val styles = listOf(settings.cardStyle) + MusicCardStyle.entries.filterNot { it == settings.cardStyle }
                styles.forEach { style ->
                    ToolPanelChip(stringResource(style.labelRes), selected = style == settings.cardStyle) {
                        act(MusicAction.InsertPicture(style))
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun viaLabel(match: SongMatch): String = when (match.via) {
    MusicController.VIA_NOW_PLAYING -> stringResource(R.string.ime_music_now_playing_title)
    else -> stringResource(R.string.ime_music_via, match.via.viaName())
}

/** The provider id as people know it. */
private fun String.viaName(): String = when (this) {
    "audd" -> "AudD"
    "acrcloud" -> "ACRCloud"
    "audiotag" -> "AudioTag"
    "rapid_shazam" -> "Shazam"
    "music_server" -> "your server"
    else -> this
}

@Composable
private fun Cover(ui: MusicUi, size: androidx.compose.ui.unit.Dp) {
    val kb = LocalKbTheme.current
    val art = ui.art
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(kb.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = kb.accent, modifier = Modifier.size(size / 2))
        }
    }
}

@Composable
private fun MessageBody(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    action: String,
    icon2: androidx.compose.ui.graphics.vector.ImageVector?,
    onAction: () -> Unit,
) {
    EmptyState(icon = icon, title = title, body = body) {
        PillButton(action, icon = icon2, primary = true, onClick = onAction)
    }
}

// ---- History ----

@Composable
private fun HistoryTab(ui: MusicUi, act: (MusicAction) -> Unit) {
    val kb = LocalKbTheme.current
    if (ui.history.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.ime_music_history_empty_title),
            body = stringResource(R.string.ime_music_history_empty_body),
        )
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_music_history_clear), size = 30.dp) {
                act(MusicAction.HistoryClear)
            }
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        LazyColumn(
            contentPadding = PaddingValues(bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(ui.history, key = { it.id }) { entry ->
                HistoryRow(entry, format.format(Date(entry.at)), kb.accent, act)
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: MusicHistoryEntry, time: String, accent: androidx.compose.ui.graphics.Color, act: (MusicAction) -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { act(MusicAction.HistoryPick(entry.id)) }
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.match.title,
                color = kb.suggestionText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(entry.match.artist, time).filter { it.isNotBlank() }.joinToString(" · "),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_music_history_delete), size = 28.dp) {
            act(MusicAction.HistoryDelete(entry.id))
        }
    }
}

@Composable
private fun Notice(text: String?, modifier: Modifier) {
    val kb = LocalKbTheme.current
    AnimatedVisibility(visible = text != null, modifier = modifier) {
        Text(
            text.orEmpty(),
            color = kb.suggestionText,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(kb.chip)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private const val BUTTON_DP = 112
private const val RING_DP = 96
private const val CORE_DP = 64
private const val BREATH_MS = 1800
private const val RING_MS = 120
private const val SPIN_MS = 900
private const val RING_BREATH = 0.06f
private const val RING_LEVEL = 0.5f
private const val FORMAT_PREVIEW_CHARS = 28
