package com.wasimaster.wmkeyboard.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.common.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** The smallest share of its own size a preview can be dragged down to (#967). */
const val PreviewScaleMin = 0.35f

/** Steps the accessibility actions take, and the share the label rounds to. */
private const val PreviewScaleStep = 0.05f

/**
 * A keyboard preview the user can make smaller (#967): grips on its two bottom
 * corners that drag the preview, and everything in it, down or back up at its
 * own proportions, centred in the width on offer. A preview pinned under a
 * screen's bar pays for its size out of the controls below it, and on a small
 * phone the full-width keyboard left room for barely one; hiding it instead
 * would mean closing it to change a value and opening it to see the change.
 *
 * [content] is handed the scale and does the scaling itself, since the two
 * previews do it differently: the theme editor's keyboard draws through a
 * scaled layer, the layout editor's grid lays itself out at a scaled density.
 * Either way it should come out [scale] times its full size, which is what the
 * grips measure their drag against.
 *
 * [onScale] follows the finger; [onScaleSettled] is told once it lifts, for a
 * caller that stores the value. A double tap on a grip goes back to full size.
 */
@Composable
fun ScalablePreview(
    scale: Float,
    onScale: (Float) -> Unit,
    onScaleSettled: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(scale)
    val follow by rememberUpdatedState(onScale)
    val settle by rememberUpdatedState(onScaleSettled)
    val description = stringResource(R.string.common_preview_scale_desc)
    val percent = (scale * 100).roundToInt()
    val state = stringResource(R.string.common_preview_scale_value, percent)
    val smaller = stringResource(R.string.common_preview_scale_smaller)
    val larger = stringResource(R.string.common_preview_scale_larger)
    val actions = listOf(
        CustomAccessibilityAction(smaller) {
            settle((current - PreviewScaleStep).coerceIn(PreviewScaleMin, 1f))
            true
        },
        CustomAccessibilityAction(larger) {
            settle((current + PreviewScaleStep).coerceIn(PreviewScaleMin, 1f))
            true
        },
    )

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box {
            // The grips sit in a band of their own under the preview, not on
            // it: on its corners they covered the corner keys, shift and enter
            // among them, and took their presses.
            Box(Modifier.padding(bottom = GripBand).onSizeChanged { frame = it }) { content() }
            for (left in booleanArrayOf(true, false)) {
                // The two grips mirror each other: the preview is centred, so
                // either corner moving out by a pixel widens it by two.
                val outward = if (left) -1f else 1f
                Box(
                    modifier = Modifier
                        .align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                        .size(GripTouch, GripBand)
                        .semantics {
                            contentDescription = description
                            stateDescription = state
                            customActions = actions
                        }
                        .pointerInput(outward) {
                            // One detector for the drag and the double tap: the
                            // drag takes the press at once, so a tap detector
                            // of its own beside it would never see one.
                            var lastTapAt = 0L
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var live = current
                                var travelled = 0f
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    change.consume()
                                    travelled += delta.getDistance()
                                    if (travelled > viewConfiguration.touchSlop) dragging = true
                                    val w = frame.width.toFloat()
                                    val h = frame.height.toFloat()
                                    if (w <= 0f || h <= 0f) return@drag
                                    // Whichever way the finger mostly went: out
                                    // across the width, or down the height.
                                    // Each is a share of the size the preview
                                    // has now, which is [live] of its full one.
                                    val across = live * 2f * delta.x * outward / w
                                    val downward = live * delta.y / h
                                    live = (live + if (abs(across) >= abs(downward)) across else downward)
                                        .coerceIn(PreviewScaleMin, 1f)
                                    follow(live)
                                }
                                dragging = false
                                if (travelled > viewConfiguration.touchSlop) {
                                    lastTapAt = 0L
                                    settle(live)
                                } else {
                                    val now = down.uptimeMillis
                                    if (now - lastTapAt < viewConfiguration.doubleTapTimeoutMillis) {
                                        lastTapAt = 0L
                                        settle(1f)
                                    } else {
                                        lastTapAt = now
                                    }
                                }
                            }
                        },
                    contentAlignment = if (left) Alignment.TopStart else Alignment.TopEnd,
                ) {
                    CornerGrip(left)
                }
            }
            if (dragging) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        state,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** An L under the corner, the shape a resize corner has everywhere else. */
@Composable
private fun CornerGrip(left: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.padding(top = 2.dp).size(GripDrawn)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2f
        val x = if (left) inset else size.width - inset
        val y = size.height - inset
        val farX = if (left) size.width else 0f
        drawLine(color, Offset(x, 0f), Offset(x, y), stroke, StrokeCap.Round)
        drawLine(color, Offset(x, y), Offset(farX, y), stroke, StrokeCap.Round)
    }
}

private val GripTouch = 48.dp
private val GripBand = 24.dp
private val GripDrawn = 18.dp

/**
 * Lays [content] out at [scale] of the width on offer, at a density [scale]
 * times the screen's, so it comes out exactly as it would at full width, only
 * smaller: every dp and sp in it shrinks together. Unlike a scaled layer this
 * keeps layout, drawing and touch in one set of coordinates, which a grid that
 * reads where its cells landed in the window needs to stay right.
 */
@Composable
fun DensityScaled(scale: Float, content: @Composable () -> Unit) {
    val outer = LocalDensity.current
    if (scale >= 1f) {
        content()
        return
    }
    val scaled = remember(outer, scale) { Density(outer.density * scale, outer.fontScale) }
    Layout(content = { CompositionLocalProvider(LocalDensity provides scaled) { content() } }) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) (constraints.maxWidth * scale).roundToInt() else constraints.maxWidth
        val placeable = measurables.first().measure(
            constraints.copy(
                minWidth = constraints.minWidth.coerceAtMost(width),
                maxWidth = width,
            ),
        )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
