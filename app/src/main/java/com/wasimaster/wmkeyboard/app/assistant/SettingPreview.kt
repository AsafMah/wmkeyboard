package com.wasimaster.wmkeyboard.app.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.FlightOrigin
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.ResultIcon
import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.app.takeOffKey
import com.wasimaster.wmkeyboard.app.wmSharedElement
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A settings row the assistant pointed at, drawn before anything is opened:
 * the row's own tile, its title and what it says, the path down to it as a
 * trail of crumbs, its value when it is one the assistant can read, and Open.
 *
 * Opening is the user's tap and nothing else's. The card is the answer to
 * "where is that setting", so it says where first and goes there second.
 *
 * [revealKey] names this card for its one-time entrance: the crumbs come in
 * one after another the first time it is drawn and sit still on every later
 * one, scrolled back to or not. [flightOrigin], when set, lets the tile fly
 * into the destination's heading, as a search result's does.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingPreviewCard(
    entry: SettingsSearchEntry,
    settings: LiveSettings,
    revealKey: String,
    reduceMotion: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    flightOrigin: String? = null,
    /** Drawn as it already stands, crumbs and all: a card that was there before the screen opened. */
    revealed: Boolean = false,
    /** The sheet's roomier layout: the whole subtitle, no tap on the card itself. */
    expanded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "previewPress",
    )
    val open = {
        flightOrigin?.let(FlightOrigin::leaving)
        onOpen()
    }
    Surface(
        shape = CardShape,
        color = if (expanded) colors.surfaceContainerLow else colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardShape)
            .then(if (expanded) Modifier else Modifier.clickable(interactionSource = press, indication = ripple(), onClick = open)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResultIcon(
                    entry,
                    settings,
                    modifier = if (flightOrigin != null) {
                        Modifier.wmSharedElement(takeOffKey("icon", entry.route, flightOrigin))
                    } else {
                        Modifier
                    },
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.subtitle.isNotBlank()) {
                        Text(
                            entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = if (expanded) 8 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            CrumbTrail(entry, revealKey, still = reduceMotion || revealed)
            Row(verticalAlignment = Alignment.CenterVertically) {
                currentValue(entry, settings)?.let { value ->
                    ValuePill(stringResource(R.string.settings_assistant_current, value))
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = open,
                    contentPadding = PaddingValues(start = 18.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_assistant_open))
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * The way down to a row: each screen above it as a quiet chip, then the row
 * itself in the accent, chevrons between. On first sight the chips arrive one
 * after another, left to right, the order a finger would take them in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrumbTrail(entry: SettingsSearchEntry, revealKey: String, still: Boolean) {
    val colors = MaterialTheme.colorScheme
    val crumbs = entry.screenPath + entry.title
    var played by rememberSaveable(revealKey) { mutableStateOf(still) }
    val progress = remember(revealKey) { List(crumbs.size) { Animatable(if (played) 1f else 0f) } }
    LaunchedEffect(revealKey) {
        if (played) return@LaunchedEffect
        coroutineScope {
            progress.forEachIndexed { i, crumb ->
                launch {
                    delay(CRUMB_STAGGER_MS * i + CRUMB_LEAD_MS)
                    crumb.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        }
        played = true
    }
    val where = stringResource(R.string.settings_assistant_where)
    val spoken = crumbs.joinToString(" › ")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$where: $spoken" },
    ) {
        crumbs.forEachIndexed { i, crumb ->
            val last = i == crumbs.lastIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    val p = progress[i].value
                    alpha = p.coerceIn(0f, 1f)
                    translationX = (1f - p) * -CRUMB_TRAVEL.toPx()
                    val s = 0.85f + 0.15f * p
                    scaleX = s
                    scaleY = s
                },
            ) {
                if (i > 0) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    crumb,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (last) FontWeight.SemiBold else null,
                    color = if (last) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (last) colors.primaryContainer else colors.surfaceContainerHighest)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A small rounded label: a setting's value now, beside its Open button. */
@Composable
internal fun ValuePill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * A proposal's two values: the one now, dimmed, an arrow, and the one it
 * would become, in the accent.
 */
@Composable
internal fun ChangeValues(from: String, to: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(colors.surfaceContainerHighest)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(from, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).size(16.dp),
        )
        Text(to, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.primary)
    }
}

/**
 * Three dots that rise and fall one after another while an answer is on its
 * way. Held still under reduced motion.
 */
@Composable
internal fun TypingDots(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val pulse = rememberInfiniteTransition(label = "typingDots")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val phase = if (reduceMotion) {
                remember { mutableStateOf(0.6f) }
            } else {
                pulse.animateFloat(
                    initialValue = 0f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = DOT_CYCLE_MS
                            0f at 0
                            1f at DOT_CYCLE_MS / 4
                            0f at DOT_CYCLE_MS / 2
                        },
                        initialStartOffset = StartOffset(i * DOT_STAGGER_MS),
                    ),
                    label = "dot$i",
                )
            }
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        val p = phase.value
                        alpha = 0.35f + 0.65f * p
                        translationY = -p * 3.dp.toPx()
                    }
                    .clip(CircleShape)
                    .background(colour),
            )
        }
    }
}

/** The live value of [entry] when it is one of the [AssistantSetting]s, as the card shows it. */
@Composable
private fun currentValue(entry: SettingsSearchEntry, settings: LiveSettings): String? {
    val setting = AssistantSetting.all.firstOrNull { it.titleRes != 0 && it.titleRes == entry.titleRes } ?: return null
    val value = settings.watch { setting.read(it) }
    return shown(setting, value)
}

/** A value the way the cards show it: On and Off for a switch, the number and its unit otherwise. */
@Composable
internal fun shown(setting: AssistantSetting, value: String): String = when (setting) {
    is AssistantSetting.Switch ->
        stringResource(if (value == "true") CommonR.string.common_on else CommonR.string.common_off)
    is AssistantSetting.Number -> setting.withUnit(value)
    is AssistantSetting.Choice -> setting.labelOf(value).let { if (it == 0) value else stringResource(it) }
}

internal val CardShape = RoundedCornerShape(20.dp)

private const val PRESSED_SCALE = 0.98f
private const val CRUMB_STAGGER_MS = 70L
private const val CRUMB_LEAD_MS = 120L
private val CRUMB_TRAVEL = 10.dp
private const val DOT_CYCLE_MS = 1200
private const val DOT_STAGGER_MS = 160
