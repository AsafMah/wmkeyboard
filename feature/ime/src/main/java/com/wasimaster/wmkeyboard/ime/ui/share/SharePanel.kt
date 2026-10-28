package com.wasimaster.wmkeyboard.ime.ui.share

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Apps
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AttachFile
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFields
import com.wasimaster.wmkeyboard.core.share.SharePreview
import com.wasimaster.wmkeyboard.core.share.ShareTarget
import com.wasimaster.wmkeyboard.core.share.ShareTargets
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.share.ShareAction
import com.wasimaster.wmkeyboard.ime.share.ShareCallbacks
import com.wasimaster.wmkeyboard.ime.share.ShareUi
import com.wasimaster.wmkeyboard.ime.ui.FullBleedTool
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.LocalKeyPressFeedback
import com.wasimaster.wmkeyboard.ime.ui.MediaHeaderSearchBar
import com.wasimaster.wmkeyboard.ime.ui.PanelFocusTarget
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip

/** One of the sheet's quick actions, in the order the row shows them. */
private enum class ShareQuick(val action: ShareAction) {
    COPY(ShareAction.Copy),
    OPEN(ShareAction.Open),
    INSERT(ShareAction.Insert),
    SYSTEM(ShareAction.System),
}

/** The quick actions [ui]'s share can use: Open needs a link or one file, Insert plain text. */
private fun quickActions(ui: ShareUi): List<ShareQuick> = buildList {
    val preview = ui.preview
    if (preview.text != null || preview.firstUri != null) add(ShareQuick.COPY)
    if (preview.link != null || (preview.files == 1 && preview.firstUri != null)) add(ShareQuick.OPEN)
    if (ui.insertable) add(ShareQuick.INSERT)
    add(ShareQuick.SYSTEM)
}

/**
 * The keyboard's share sheet (#970), the way Firefox's works: what is being
 * shared, a row of quick actions (Copy, Open, Insert, and Android's own sheet),
 * the pinned and recent apps, then every app that takes the share in a grid
 * the header's box searches. A hold pins an app or drops it from the recent
 * row; the menu is drawn inside the panel, like the launcher's.
 */
@Composable
internal fun SharePanel(
    state: KeyboardUiState,
    callbacks: ShareCallbacks,
    onQueryTap: () -> Unit,
) {
    val ui by callbacks.state.collectAsState()
    val act = callbacks.onAction
    val share = state.settings.share
    val query = state.mediaQuery
    val recents = if (share.recentsEnabled) share.recents else emptyList()
    val layout = remember(ui.targets, share.pinned, recents, query) {
        ShareTargets.arrange(ui.targets, share.pinned, recents, query)
    }
    // While the box is being typed into the panel is short over the keys:
    // the grid is all there is room for, and all a search wants.
    val searching = query.isNotBlank() || state.mediaSearchActive
    val actions = if (searching) emptyList() else quickActions(ui)
    val shortcuts = if (searching) emptyList() else layout.shortcuts

    PanelFocusTarget(PanelMode.SHARE, 1, 1, FocusRegion.SEARCH) { onQueryTap() }
    PanelFocusTarget(PanelMode.SHARE, actions.size, actions.size, FocusRegion.ACTIONS) { index ->
        actions.getOrNull(index)?.let { act(it.action) }
    }
    PanelFocusTarget(PanelMode.SHARE, shortcuts.size, shortcuts.size, FocusRegion.CHIPS) { index ->
        shortcuts.getOrNull(index)?.let { act(ShareAction.Pick(it)) }
    }
    // An Adaptive grid's true column count is a layout fact; five is close
    // enough for arrow-key math on a phone.
    PanelFocusTarget(PanelMode.SHARE, layout.all.size, GRID_FOCUS_COLUMNS) { index ->
        layout.all.getOrNull(index)?.let { act(ShareAction.Pick(it)) }
    }

    FullBleedTool(
        state = state,
        title = "",
        // Back out one layer at a time: the hold menu, the search, then the sheet.
        onClose = {
            when {
                ui.held != null -> act(ShareAction.Hold(null))
                state.mediaSearchActive -> onQueryTap()
                else -> act(ShareAction.Dismiss)
            }
        },
        compact = state.mediaSearchActive,
        headerActions = {
            MediaHeaderSearchBar(
                state = state,
                placeholder = stringResource(R.string.ime_share_search_hint),
                onQueryTap = onQueryTap,
                focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            ShareGrid(state, ui, layout.all, shortcuts, actions, searching, callbacks)
            ui.held?.let { held ->
                HoldMenu(
                    target = held,
                    pinned = held.key in share.pinned,
                    recent = held.key in recents,
                    iconFor = callbacks.iconFor,
                    act = act,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun ShareGrid(
    state: KeyboardUiState,
    ui: ShareUi,
    all: List<ShareTarget>,
    shortcuts: List<ShareTarget>,
    actions: List<ShareQuick>,
    searching: Boolean,
    callbacks: ShareCallbacks,
) {
    val kb = LocalKbTheme.current
    val act = callbacks.onAction
    val pinned = state.settings.share.pinned.toSet()
    val focusedCell = state.focusedIndex(FocusRegion.RESULTS)
    val full: (androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan) = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL_MIN_WIDTH.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
    ) {
        if (!searching) {
            item(key = "preview", span = full) { PreviewLine(ui.preview) }
            item(key = "actions", span = full) { QuickRow(state, actions, act) }
            if (shortcuts.isNotEmpty()) {
                item(key = "shortcuts-label", span = full) { SectionLabel(stringResource(R.string.ime_share_shortcuts_label)) }
                item(key = "shortcuts", span = full) {
                    val focused = state.focusedIndex(FocusRegion.CHIPS)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rowItemsIndexed(shortcuts, key = { _, t -> "s:${t.key}" }) { index, target ->
                            TargetCell(
                                target = target,
                                pinned = target.key in pinned,
                                focused = focused == index,
                                iconFor = callbacks.iconFor,
                                onTap = { act(ShareAction.Pick(target)) },
                                onLongPress = { act(ShareAction.Hold(target)) },
                                modifier = Modifier.width(SHORTCUT_WIDTH.dp),
                            )
                        }
                    }
                }
            }
            if (all.isNotEmpty()) {
                item(key = "all-label", span = full) { SectionLabel(stringResource(R.string.ime_share_all_label)) }
            }
        }
        when {
            ui.loading -> item(key = "loading", span = full) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Text(
                            stringResource(R.string.ime_share_loading),
                            color = kb.secondaryText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            all.isEmpty() -> item(key = "empty", span = full) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Apps, contentDescription = null, tint = kb.secondaryText, modifier = Modifier.size(28.dp))
                    Text(
                        stringResource(if (searching) R.string.ime_share_no_match else R.string.ime_share_empty),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            else -> itemsIndexed(all, key = { _, t -> t.key }) { index, target ->
                TargetCell(
                    target = target,
                    pinned = target.key in pinned,
                    focused = focusedCell == index,
                    iconFor = callbacks.iconFor,
                    onTap = { act(ShareAction.Pick(target)) },
                    onLongPress = { act(ShareAction.Hold(target)) },
                )
            }
        }
    }
}

/** What is being shared, in a line: the subject or the text, or how many files. */
@Composable
private fun PreviewLine(preview: SharePreview) {
    val kb = LocalKbTheme.current
    val files = preview.files
    val icon = if (files > 0) Icons.Outlined.AttachFile else Icons.Outlined.TextFields
    val title = preview.subject ?: when {
        files > 0 -> pluralStringResource(R.plurals.ime_share_preview_files, files, files)
        else -> stringResource(R.string.ime_share_preview_text)
    }
    val body = preview.text?.replace('\n', ' ')?.take(PREVIEW_CHARS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(kb.chip)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = kb.chipText, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = 10.dp)) {
            Text(
                title,
                color = kb.chipText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (body != null && body != title) {
                Text(
                    body,
                    color = kb.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickRow(state: KeyboardUiState, actions: List<ShareQuick>, act: (ShareAction) -> Unit) {
    val focused = state.focusedIndex(FocusRegion.ACTIONS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, quick ->
            ToolPanelChip(
                label = stringResource(quickLabel(quick)),
                selected = focused == index,
            ) { act(quick.action) }
        }
    }
}

private fun quickLabel(quick: ShareQuick): Int = when (quick) {
    ShareQuick.COPY -> R.string.ime_share_copy
    ShareQuick.OPEN -> R.string.ime_share_open
    ShareQuick.INSERT -> R.string.ime_share_insert
    ShareQuick.SYSTEM -> R.string.ime_share_system
}

@Composable
private fun SectionLabel(text: String) {
    val kb = LocalKbTheme.current
    Text(
        text,
        color = kb.secondaryText,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetCell(
    target: ShareTarget,
    pinned: Boolean,
    focused: Boolean,
    iconFor: suspend (ShareTarget) -> ImageBitmap?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // Two targets of one app read by their own names ("Save to Drive"); the
    // app's name goes under one whose own name does not already say it.
    val second = target.appLabel.takeIf { !target.label.contains(it, ignoreCase = true) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) kb.chipActive else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    feedback()
                    onLongPress()
                },
            )
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            TargetIcon(target, ICON_SIZE_DP, iconFor)
            if (pinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = stringResource(R.string.ime_share_pinned_desc),
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.TopEnd),
                    tint = kb.toolCircleActiveIcon,
                )
            }
        }
        Text(
            target.label,
            color = kb.suggestionText,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 3.dp, start = 2.dp, end = 2.dp)
                .fillMaxWidth(),
        )
        if (second != null) {
            Text(
                second,
                color = kb.secondaryText,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TargetIcon(target: ShareTarget, sizeDp: Int, iconFor: suspend (ShareTarget) -> ImageBitmap?) {
    val kb = LocalKbTheme.current
    val icon by produceState<ImageBitmap?>(null, target.key) { value = iconFor(target) }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(sizeDp.dp))
    } else {
        Box(
            Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(kb.chip),
        )
    }
}

/** Pin or unpin, drop from the recent row, or back out; drawn over the grid like the launcher's menu. */
@Composable
private fun HoldMenu(
    target: ShareTarget,
    pinned: Boolean,
    recent: Boolean,
    iconFor: suspend (ShareTarget) -> ImageBitmap?,
    act: (ShareAction) -> Unit,
) {
    val kb = LocalKbTheme.current
    val closeLabel = stringResource(R.string.ime_share_cancel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.board.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = closeLabel,
            ) { act(ShareAction.Hold(null)) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                // Swallows taps between rows so they do not reach the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TargetIcon(target, 28, iconFor)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(
                        target.label,
                        color = kb.popupText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (target.appLabel != target.label) {
                        Text(target.appLabel, color = kb.popupText.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            MenuItem(Icons.Outlined.OpenInNew, stringResource(R.string.ime_share_title)) { act(ShareAction.Pick(target)) }
            MenuItem(
                Icons.Outlined.PushPin,
                stringResource(if (pinned) R.string.ime_share_unpin else R.string.ime_share_pin),
            ) { act(ShareAction.TogglePin(target)) }
            if (recent) {
                MenuItem(Icons.Outlined.History, stringResource(R.string.ime_share_forget)) {
                    act(ShareAction.ForgetRecent(target))
                }
            }
            MenuItem(Icons.Outlined.Close, closeLabel) { act(ShareAction.Hold(null)) }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = kb.popupText)
        Text(label, color = kb.popupText, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val CELL_MIN_WIDTH = 72
private const val SHORTCUT_WIDTH = 72
private const val ICON_SIZE_DP = 40
private const val GRID_FOCUS_COLUMNS = 5
private const val PREVIEW_CHARS = 160

