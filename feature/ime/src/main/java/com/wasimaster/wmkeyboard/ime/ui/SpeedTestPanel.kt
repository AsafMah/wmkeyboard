package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PlayArrow
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Replay
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLayout
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardRenderer
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestMath
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestPhase
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedUnit
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestAction
import com.wasimaster.wmkeyboard.ime.speedtest.SpeedTestStage
import kotlin.math.min

/**
 * The speed test tool. Four faces in one panel: the Start button with the
 * server to use and what a run costs; the run itself, a dial and a live
 * line; the result, which is the card drawn small beside the chips that
 * change its style, size and layout, and the ways to send it; and the data
 * saver's question or an error, each with one button.
 *
 * Nothing here owns the run. The controller holds it (see SpeedTestUi) and
 * the panel draws what it publishes.
 */
@Composable
internal fun SpeedTestPanel(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val height = keyRowsHeight(state)
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    // The theme's colours go to the controller, which draws the Theme style with them.
    val palette = remember(kb.board, kb.key, kb.accent, kb.keyText, kb.secondaryText) {
        SpeedCardRenderer.themePalette(
            background = kb.board.toArgb(),
            key = kb.key.toArgb(),
            accent = kb.accent.toArgb(),
            text = kb.keyText.toArgb(),
            muted = kb.secondaryText.toArgb(),
        )
    }
    LaunchedEffect(palette) { onAction(SpeedTestAction.Theme(palette)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
    ) {
        when (ui.stage) {
            SpeedTestStage.IDLE -> IdleFace(state, onAction)
            SpeedTestStage.ASK_METERED -> AskFace(state, onAction)
            SpeedTestStage.RUNNING -> RunningFace(state, onAction)
            SpeedTestStage.DONE -> DoneFace(state, onAction)
            SpeedTestStage.ERROR -> ErrorFace(state, onAction)
        }
    }
}

// ---- idle ----

@Composable
private fun IdleFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StartButton(onAction)
        Spacer(Modifier.height(10.dp))
        ProviderChooser(ProviderTool.SPEED_TEST, state.settings)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ime_speed_data_note, state.speedTest.estimatedMb),
            color = kb.secondaryText,
            fontSize = 12.sp,
        )
    }
}

/** The one big control: a pill in the active chip colour with the play glyph. */
@Composable
private fun StartButton(onAction: (SpeedTestAction) -> Unit, again: Boolean = false) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(50)
    val label = stringResource(if (again) R.string.ime_speed_again else R.string.ime_speed_start)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(kb.chipActive)
            .chipBorder(kb, shape)
            .clickable { onAction(if (again) SpeedTestAction.Again else SpeedTestAction.Start) }
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Icon(
            if (again) Icons.Outlined.Replay else Icons.Outlined.PlayArrow,
            contentDescription = null,
            tint = kb.chipActiveText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = kb.chipActiveText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- the data saver's question ----

@Composable
private fun AskFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (ui.canAllow) {
                stringResource(R.string.ime_speed_metered_ask, ui.estimatedMb)
            } else {
                stringResource(R.string.ime_speed_metered_blocked)
            },
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.canAllow) {
                ToolPanelChip(stringResource(R.string.ime_speed_run_anyway), selected = true) {
                    onAction(SpeedTestAction.AllowMetered)
                }
            }
            ToolPanelChip(stringResource(CommonR.string.common_cancel)) { onAction(SpeedTestAction.Cancel) }
        }
    }
}

// ---- running ----

@Composable
private fun RunningFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    val unit = state.settings.speedTest.unit
    val phaseLabel = stringResource(
        when (ui.phase) {
            SpeedTestPhase.META, null -> R.string.ime_speed_phase_meta
            SpeedTestPhase.PING -> R.string.ime_speed_phase_ping
            SpeedTestPhase.DOWNLOAD -> R.string.ime_speed_phase_download
            SpeedTestPhase.UPLOAD -> R.string.ime_speed_phase_upload
        },
    )
    val transferring = ui.phase == SpeedTestPhase.DOWNLOAD || ui.phase == SpeedTestPhase.UPLOAD
    // The engine reports five times a second; the dial and its number glide
    // from one report to the next rather than snapping, which at that rate
    // reads as flicker. Each report is the phase's running mean, so the
    // target itself moves little once the connections have opened.
    // Read only inside the dial's draw pass and the number's own scope, so a
    // frame of the glide redraws the arc and relays out one Text, not the
    // whole face.
    val animated = animateFloatAsState(
        targetValue = ui.liveMbps.toFloat(),
        animationSpec = tween(SPEED_GLIDE_MS),
        label = "speed",
    )
    val shownMbps: State<Double> = if (kb.motion.any) {
        remember(animated) { derivedStateOf { animated.value.toDouble() } }
    } else {
        rememberUpdatedState(ui.liveMbps)
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dial: an arc filled on a log scale, the live speed inside it.
        // Capped so a tall keyboard does not hand it most of the width and
        // leave the phase label to wrap beside it.
        Box(
            modifier = Modifier
                .heightIn(max = DIAL_MAX)
                .fillMaxHeight()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val fraction = SpeedTestMath.gaugeFraction(shownMbps.value)
                val stroke = 10.dp.toPx()
                val radius = min(size.width, size.height) / 2f - stroke
                val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
                val arcSize = Size(radius * 2, radius * 2)
                drawArc(kb.modifierKey, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (transferring && fraction > 0f) {
                    drawArc(kb.accent, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (transferring) {
                    LiveSpeed(shownMbps, unit)
                    Text(SpeedTestMath.unitLabel(unit), color = kb.secondaryText, fontSize = 11.sp)
                } else {
                    Text(
                        ui.pingMs?.let { SpeedTestMath.formatMs(it) + " ms" } ?: "…",
                        color = kb.keyText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                phaseLabel,
                color = kb.keyText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(ui.serverLabel, color = kb.secondaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            LiveLine(
                SpeedTestMath.smooth(ui.liveSamples),
                slots = state.settings.speedTest.duration * SAMPLES_PER_SECOND,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
            )
            Spacer(Modifier.height(10.dp))
            ToolPanelChip(stringResource(CommonR.string.common_cancel)) { onAction(SpeedTestAction.Cancel) }
        }
    }
}

/** The number in the dial, in its own scope: the glide changes it every frame and nothing else need recompose. */
@Composable
private fun LiveSpeed(mbps: State<Double>, unit: SpeedUnit) {
    val kb = LocalKbTheme.current
    val label = remember(mbps, unit) { derivedStateOf { SpeedTestMath.formatSpeed(mbps.value, unit) } }
    Text(
        label.value,
        color = kb.keyText,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The running phase's samples as a thin line on a fixed time axis: one slot
 * per expected sample, so the points already drawn stay put as new ones
 * arrive. The newest point glides in over the time to the next sample and
 * the vertical scale glides with the peak, both read only in the draw pass,
 * and the line is a curve through the points rather than a polyline.
 */
@Composable
private fun LiveLine(samples: List<Float>, slots: Int, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val motion = kb.motion.any
    // A new phase starts from nothing: snap there rather than sweep back.
    val count = animateFloatAsState(
        targetValue = samples.size.toFloat(),
        animationSpec = if (motion && samples.size > 1) tween(SPEED_GLIDE_MS) else snap(),
        label = "points",
    )
    val peakTarget = samples.maxOrNull()?.takeIf { it > 0f } ?: 0f
    val peak = animateFloatAsState(
        targetValue = peakTarget,
        animationSpec = if (motion && peakTarget > 0f) tween(SPEED_GLIDE_MS) else snap(),
        label = "peak",
    )
    Canvas(modifier) {
        val base = size.height - 1.dp.toPx()
        drawLine(kb.divider, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
        val shown = count.value.coerceIn(0f, samples.size.toFloat())
        val top = peak.value
        if (shown < 2f || top <= 0f) return@Canvas
        val step = size.width / (maxOf(slots, samples.size) - 1).coerceAtLeast(1)
        val span = size.height - 4.dp.toPx()
        fun yOf(v: Float) = base - (v / top).coerceIn(0f, 1f) * span
        // The whole points, then the one still gliding in between the last two.
        val whole = shown.toInt().coerceAtMost(samples.size)
        val points = ArrayList<Offset>(whole + 1)
        for (i in 0 until whole) points += Offset(step * i, yOf(samples[i]))
        val fraction = shown - whole
        if (whole < samples.size && fraction > 0f) {
            val from = samples[whole - 1]
            val to = samples[whole]
            points += Offset(step * (whole - 1 + fraction), yOf(from + (to - from) * fraction))
        }
        if (points.size < 2) return@Canvas
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            path.lineTo(points[1].x, points[1].y)
        } else {
            // Through the midpoints, each sample as the control point: smooth,
            // and the curve never overshoots a sample.
            for (i in 1 until points.size - 1) {
                val mid = Offset((points[i].x + points[i + 1].x) / 2f, (points[i].y + points[i + 1].y) / 2f)
                path.quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
            }
            path.lineTo(points.last().x, points.last().y)
        }
        drawPath(path, kb.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---- done ----

@Composable
private fun DoneFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    val ui = state.speedTest
    val s = state.settings.speedTest
    val result = ui.result ?: return
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // The card, as big as the height allows at its own shape, but never
        // more than CARD_WIDTH_SHARE of the width: on a tall keyboard a square
        // card the full height would leave the chips one to a row.
        val aspect = s.cardSize.aspect.coerceIn(0.5f, 1.6f)
        val cardWidth = minOf(maxHeight * aspect, maxWidth * CARD_WIDTH_SHARE)
        val cardHeight = cardWidth / aspect
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(cardWidth, cardHeight)
                    .clip(RoundedCornerShape(10.dp))
                    .background(kb.modifierKey),
                contentAlignment = Alignment.Center,
            ) {
                val preview = ui.preview
                if (preview != null) {
                    Image(preview, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text(
                        SpeedTestMath.formatSpeed(result.downMbps, s.unit) + " " + SpeedTestMath.unitLabel(s.unit),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChipRow {
                    for (style in SpeedCardStyle.entries) {
                        ToolPanelChip(stringResource(styleLabel(style)), selected = s.cardStyle == style) {
                            onAction(SpeedTestAction.SetStyle(style))
                        }
                    }
                }
                ChipRow {
                    for (size in SpeedCardSize.entries) {
                        ToolPanelChip(stringResource(sizeLabel(size)), selected = s.cardSize == size) {
                            onAction(SpeedTestAction.SetSize(size))
                        }
                    }
                }
                ChipRow {
                    for (layout in SpeedCardLayout.entries) {
                        ToolPanelChip(stringResource(layoutLabel(layout)), selected = s.cardLayout == layout) {
                            onAction(SpeedTestAction.SetLayout(layout))
                        }
                    }
                    for (unit in SpeedUnit.entries) {
                        ToolPanelChip(SpeedTestMath.unitLabel(unit), selected = s.unit == unit) {
                            onAction(SpeedTestAction.SetUnit(unit))
                        }
                    }
                }
                ChipRow {
                    val busy = ui.rendering
                    ToolPanelChip(stringResource(R.string.ime_speed_send), selected = true, enabled = !busy) {
                        onAction(SpeedTestAction.Send)
                    }
                    ToolPanelChip(stringResource(R.string.ime_speed_save), icon = Icons.Outlined.Save, enabled = !busy) {
                        onAction(SpeedTestAction.Save)
                    }
                    ToolPanelChip(stringResource(R.string.ime_speed_share), icon = Icons.Outlined.Share, enabled = !busy) {
                        onAction(SpeedTestAction.Share)
                    }
                    ToolPanelChip(stringResource(R.string.ime_speed_insert_text), icon = Icons.Outlined.TextFields) {
                        onAction(SpeedTestAction.InsertText)
                    }
                    ToolPanelChip(stringResource(R.string.ime_speed_again), icon = Icons.Outlined.Replay) {
                        onAction(SpeedTestAction.Again)
                    }
                }
            }
        }
    }
}

/** One scrolling row of chips. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

private fun styleLabel(style: SpeedCardStyle): Int = when (style) {
    SpeedCardStyle.THEME -> R.string.ime_speed_style_theme
    SpeedCardStyle.DARK -> R.string.ime_speed_style_dark
    SpeedCardStyle.LIGHT -> R.string.ime_speed_style_light
    SpeedCardStyle.GRADIENT -> R.string.ime_speed_style_gradient
    SpeedCardStyle.MONO -> R.string.ime_speed_style_mono
}

private fun sizeLabel(size: SpeedCardSize): Int = when (size) {
    SpeedCardSize.SQUARE -> R.string.ime_speed_size_square
    SpeedCardSize.PORTRAIT -> R.string.ime_speed_size_portrait
    SpeedCardSize.STORY -> R.string.ime_speed_size_story
    SpeedCardSize.WIDE -> R.string.ime_speed_size_wide
    SpeedCardSize.STICKER -> R.string.ime_speed_size_sticker
}

private fun layoutLabel(layout: SpeedCardLayout): Int = when (layout) {
    SpeedCardLayout.FULL -> R.string.ime_speed_layout_full
    SpeedCardLayout.GAUGE -> R.string.ime_speed_layout_gauge
    SpeedCardLayout.COMPACT -> R.string.ime_speed_layout_compact
    SpeedCardLayout.MINIMAL -> R.string.ime_speed_layout_minimal
}

/** How long the dial takes to glide from one of the engine's reports to the next; the reports are 200 ms apart. */
private const val SPEED_GLIDE_MS = 220

/** The engine samples every 200 ms, so a phase of N seconds is 5N points on the line. */
private const val SAMPLES_PER_SECOND = 5

/** The dial's largest size; on a short keyboard it is the height instead. */
private val DIAL_MAX: Dp = 150.dp

/** How much of the result face's width the card may take; the chips have the rest. */
private const val CARD_WIDTH_SHARE = 0.45f

// ---- error ----

@Composable
private fun ErrorFace(state: KeyboardUiState, onAction: (SpeedTestAction) -> Unit) {
    val kb = LocalKbTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            state.speedTest.error ?: stringResource(R.string.ime_speed_error),
            color = kb.toolbarIcon,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolPanelChip(stringResource(CommonR.string.common_retry), selected = true) { onAction(SpeedTestAction.Again) }
            ProviderChooser(ProviderTool.SPEED_TEST, state.settings)
        }
    }
}
