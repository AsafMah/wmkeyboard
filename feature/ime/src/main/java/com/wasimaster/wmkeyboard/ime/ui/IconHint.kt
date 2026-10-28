package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.app.MainActivityContract
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.ui.DisabledReason
import kotlinx.coroutines.delay

/**
 * Holding an icon-only button names it: the bubble [ToolCircle] has always
 * shown over the toolbar, for the buttons in the panels too. A panel's header
 * and a clip's action row are bare glyphs — two bars, a pair of scissors, a
 * broom — and the only way to learn what one does was to press it and see.
 *
 * Wraps the button rather than replacing its click: the hold is read on the
 * initial pass, ahead of the button's own clickable, and a hold that becomes a
 * hint swallows the rest of the gesture so the release does not also fire the
 * button. A tap, or a finger that slides off to scroll, passes through
 * untouched. Not for a button that does something of its own on a hold.
 *
 * [modifier] goes on the wrapper, so a caller's `weight` or `align` still
 * reaches the parent. A null or blank [label] wraps nothing. Inside an
 * [IconHintHost] the bubble is drawn by the host rather than in a popup of its
 * own (#810).
 *
 * [disabledReason] is for a button drawn greyed out: pass it while the button
 * is disabled, null while it is not. The hold then says why under the name,
 * stays up long enough to read, and, when the reason names a setting, the
 * bubble is a button that opens it (see [DisabledReason]). A disabled button
 * does not take the press, so the hold reads it through the button the same
 * way. With a reason the hint wraps even a control that has no [label], a
 * chip or a text button, which is how those get a hold of their own.
 */
@Composable
internal fun IconHint(
    label: String?,
    modifier: Modifier = Modifier,
    disabledReason: DisabledReason? = null,
    content: @Composable () -> Unit,
) {
    if (label.isNullOrBlank() && disabledReason == null) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    val name = label?.takeUnless { it.isBlank() }
    var shown by remember { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    val host = LocalIconHintHost.current
    val anchor = remember { HintAnchor() }
    Box(
        modifier = modifier
            .onPlaced { anchor.coordinates = it }
            .pointerInput(name, disabledReason, host) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val slop = viewConfiguration.touchSlop
                    // Null when the timeout ran out with the finger still down and
                    // still: that is a hold.
                    val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var ended = false
                        while (!ended) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id }
                            ended = change == null || !change.pressed || change.isConsumed ||
                                (change.position - down.position).getDistance() > slop
                        }
                        true
                    }
                    if (endedEarly != null) return@awaitEachGesture
                    feedback()
                    if (host?.show(name, anchor.coordinates, disabledReason) != true) shown = true
                    // The button under the finger must not see this release.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        propagateMinConstraints = true,
    ) {
        content()
        if (shown) IconHintBubble(name, disabledReason) { shown = false }
    }
}

/**
 * The name bubble itself, above its anchor: the toolbar's long-press label,
 * gone on its own after a moment. With a [disabledReason] it also says why the
 * button is greyed out, and stays up longer.
 */
@Composable
internal fun IconHintBubble(label: String?, disabledReason: DisabledReason? = null, onDismiss: () -> Unit) {
    val kb = LocalKbTheme.current
    LaunchedEffect(Unit) {
        delay(if (disabledReason == null) IconHintShownMs else DisabledReasonShownMs)
        onDismiss()
    }
    Popup(
        popupPositionProvider = rememberAboveAnchorPopup(),
        onDismissRequest = onDismiss,
    ) {
        GlassPopupBackdrop(kb, FrostSurface.POPUPS)
        IconHintLabel(label, disabledReason, onOpened = onDismiss)
    }
}

/**
 * The bubble's face: the name alone, or, for a greyed-out button, the name
 * over the reason, and a line that opens the setting when the reason names
 * one. The whole bubble is that button then: a small target is the last thing
 * a hint that is about to go away should ask for.
 */
@Composable
private fun IconHintLabel(
    label: String?,
    disabledReason: DisabledReason? = null,
    modifier: Modifier = Modifier,
    onOpened: () -> Unit = {},
) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val opens = disabledReason?.opensSettings == true
    Surface(
        modifier = modifier
            .frostGlass(kb, kb.popupShape(), FrostSurface.POPUPS)
            .then(
                if (!opens) {
                    Modifier
                } else {
                    Modifier.clickable {
                        onOpened()
                        // The service's own context: the activity starts in a
                        // task of its own, as every settings jump from the
                        // keyboard does.
                        runCatching {
                            context.startActivity(
                                MainActivityContract.intent(context, disabledReason?.route, disabledReason?.setting),
                            )
                        }
                    }
                },
            ),
        shape = kb.popupShape(),
        color = kb.popupFill(FrostSurface.POPUPS),
        border = kb.popupSurfaceBorder(),
        shadowElevation = elevationFor(kb.popupShapeKind, 6.dp),
    ) {
        if (disabledReason == null) {
            Text(
                label.orEmpty(),
                color = kb.popupText,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            return@Surface
        }
        Column(Modifier.widthIn(max = DisabledReasonMaxWidth).padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (label != null) {
                Text(label, color = kb.popupText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Text(disabledReason.text, color = kb.popupText, fontSize = 12.sp)
            if (opens) {
                Text(
                    stringResource(CommonR.string.common_disabled_open_setting),
                    color = kb.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Wraps a popup's content so the [IconHint]s inside it draw their bubble in
 * the popup itself (#810). A hint's own [Popup] opened from inside another
 * popup lands in the wrong place: across the board instead of over the icon,
 * the clip card's action row showing its names at the bottom of the panel.
 * Drawn here, the bubble is in the same window as its icon and needs no
 * placing beyond above it, or under it when the popup has no room above.
 *
 * The bubble stays inside the popup's bounds, which is enough for the rows of
 * icons the popups carry: there is always the popup's own body to cover.
 */
@Composable
internal fun IconHintHost(content: @Composable () -> Unit) {
    val host = remember { IconHintHostState() }
    Box(Modifier.onPlaced { host.coordinates = it }) {
        CompositionLocalProvider(LocalIconHintHost provides host, content = content)
        val hint = host.hint
        if (hint != null) {
            key(hint) {
                LaunchedEffect(Unit) {
                    delay(if (hint.reason == null) IconHintShownMs else DisabledReasonShownMs)
                    if (host.hint === hint) host.hint = null
                }
                val gap = with(LocalDensity.current) { HostedHintGap.roundToPx() }
                IconHintLabel(
                    hint.label,
                    hint.reason,
                    onOpened = { if (host.hint === hint) host.hint = null },
                    modifier = Modifier.layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        val size = host.coordinates?.size
                        val width = size?.width ?: constraints.maxWidth
                        val height = size?.height ?: constraints.maxHeight
                        val anchor = hint.anchor
                        val x = (anchor.center.x.toInt() - placeable.width / 2)
                            .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                        val above = anchor.top.toInt() - gap - placeable.height
                        val y = if (above >= 0) above else {
                            (anchor.bottom.toInt() + gap).coerceAtMost((height - placeable.height).coerceAtLeast(0))
                        }
                        // Takes no room: the host's size is its content's.
                        layout(0, 0) { placeable.place(x, y) }
                    },
                )
            }
        }
    }
}

/** What an [IconHintHost] is showing, and where in it the icon is. */
@Stable
internal class IconHintHostState {
    var hint by mutableStateOf<HostedHint?>(null)
    var coordinates: LayoutCoordinates? = null

    /**
     * Shows [label], and [reason] when the button is greyed out, over
     * [anchor]; false when either side is not laid out yet.
     */
    fun show(label: String?, anchor: LayoutCoordinates?, reason: DisabledReason? = null): Boolean {
        val own = coordinates?.takeIf { it.isAttached } ?: return false
        val icon = anchor?.takeIf { it.isAttached } ?: return false
        hint = HostedHint(label, own.localBoundingBoxOf(icon, clipBounds = false), reason)
        return true
    }
}

/** A fresh instance per hold, so holding the same icon again restarts the timer. */
internal class HostedHint(val label: String?, val anchor: Rect, val reason: DisabledReason? = null)

private class HintAnchor {
    var coordinates: LayoutCoordinates? = null
}

internal val LocalIconHintHost = staticCompositionLocalOf<IconHintHostState?> { null }

/** Gap between a hosted bubble and its icon, the same the popup one keeps. */
private val HostedHintGap = 10.dp

/** How long a hint stays up: long enough to read a few words. */
private const val IconHintShownMs = 1200L

/** How long a greyed-out button's reason stays up: a sentence, and time to tap it. */
private const val DisabledReasonShownMs = 4000L

/** How wide a reason runs before it wraps, so a sentence does not span the board. */
private val DisabledReasonMaxWidth = 240.dp
