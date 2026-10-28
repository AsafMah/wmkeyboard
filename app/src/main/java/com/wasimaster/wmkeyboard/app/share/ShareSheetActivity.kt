package com.wasimaster.wmkeyboard.app.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.app.AppTheme
import com.wasimaster.wmkeyboard.app.rememberLiveSettings
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.History
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PushPin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ShareSheetSettings
import com.wasimaster.wmkeyboard.core.share.SharePreview
import com.wasimaster.wmkeyboard.core.share.ShareTarget
import com.wasimaster.wmkeyboard.core.share.ShareTargets
import com.wasimaster.wmkeyboard.ime.R as ImeR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The settings app's door to the share sheet (#970). Every share the app
 * makes calls [start], which opens [ShareSheetActivity]; the activity
 * decides between the keyboard's sheet and Android's chooser once it has
 * read the setting, so no caller has to wait on the settings store.
 */
internal object AppShareSheet {
    /**
     * Opens the share sheet on [send]. [title] heads the sheet and Android's
     * chooser when that is used instead. True when it opened.
     */
    fun start(context: Context, send: Intent, title: CharSequence? = null): Boolean {
        val intent = Intent(context, ShareSheetActivity::class.java)
            .putExtra(ShareSheetActivity.EXTRA_SEND, send)
            .putExtra(ShareSheetActivity.EXTRA_TITLE, title)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/**
 * The share sheet in the settings app (#970): the same layout as the
 * keyboard's panel, as a sheet over the screen that asked. What is shared,
 * Copy, Open and Android's own sheet, the pinned and recent apps, then a
 * searchable grid of every app that takes it. Holding an app pins it or
 * drops it from the recent row.
 *
 * The send intent arrives as an extra. Its files are the app's own
 * FileProvider's, so the grant on the intent this activity starts is the
 * app's own to give.
 */
class ShareSheetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val send = sendExtra()
        if (send == null) {
            finish()
            return
        }
        val title = intent?.getCharSequenceExtra(EXTRA_TITLE)
        val repository = SettingsRepository(applicationContext)
        setContent {
            val stored = repository.settings.collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            val share = settings.watch { it.share }
            // Off: straight on to Android's chooser, as every share went before.
            if (!share.enabled) {
                LaunchedEffect(Unit) {
                    systemChooser(send, title)
                    finish()
                }
                return@setContent
            }
            AppTheme(settings) {
                ShareSheet(
                    send = send,
                    title = title?.toString(),
                    share = share,
                    repository = repository,
                    onSystem = {
                        systemChooser(send, title)
                        finish()
                    },
                    onDone = ::finish,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendExtra(): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_SEND, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_SEND)
        }
    }.getOrNull()

    private fun systemChooser(send: Intent, title: CharSequence?) {
        runCatching {
            startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    internal companion object {
        const val EXTRA_SEND = "com.wasimaster.wmkeyboard.share.SEND"
        const val EXTRA_TITLE = "com.wasimaster.wmkeyboard.share.TITLE"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareSheet(
    send: Intent,
    title: String?,
    share: ShareSheetSettings,
    repository: SettingsRepository,
    onSystem: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember(send) { ShareTargets.preview(send) }
    val targets by produceState<List<ShareTarget>?>(null, send) {
        value = withContext(Dispatchers.IO) { ShareTargets.query(context, send) }
    }
    var query by remember { mutableStateOf("") }
    var held by remember { mutableStateOf<ShareTarget?>(null) }
    val recents = if (share.recentsEnabled) share.recents else emptyList()
    val layout = remember(targets, share.pinned, recents, query) {
        ShareTargets.arrange(targets.orEmpty(), share.pinned, recents, query)
    }

    fun pick(target: ShareTarget) {
        val started = runCatching { context.startActivity(ShareTargets.launchIntent(send, target)) }.isSuccess
        if (!started) {
            Toast.makeText(context, ImeR.string.ime_share_failed_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (share.recentsEnabled) scope.launch { repository.addShareRecent(target.key) }
        onDone()
    }

    val full: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    SheetFrame(onDismiss = onDone) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            item(key = "title", span = full) {
                Text(
                    title ?: stringResource(ImeR.string.ime_share_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 4.dp),
                )
            }
            item(key = "preview", span = full) { PreviewCard(preview) }
            item(key = "actions", span = full) {
                QuickActions(
                    preview = preview,
                    onCopy = {
                        copy(context, preview)
                        onDone()
                    },
                    onOpen = { if (open(context, send, preview)) onDone() },
                    onSystem = onSystem,
                )
            }
            item(key = "search", span = full) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(ImeR.string.ime_share_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
            if (layout.shortcuts.isNotEmpty()) {
                item(key = "shortcuts-label", span = full) { Label(stringResource(ImeR.string.ime_share_shortcuts_label)) }
                item(key = "shortcuts", span = full) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(layout.shortcuts, key = { "s:${it.key}" }) { target ->
                            TargetCell(
                                target = target,
                                pinned = target.key in share.pinned,
                                menuOpen = held == target,
                                recent = target.key in recents,
                                onTap = { pick(target) },
                                onHold = { held = target },
                                onMenuDismiss = { held = null },
                                onPin = {
                                    held = null
                                    scope.launch { repository.toggleSharePin(target.key) }
                                },
                                onForget = {
                                    held = null
                                    scope.launch { repository.removeShareRecent(target.key) }
                                },
                                modifier = Modifier.width(84.dp),
                            )
                        }
                    }
                }
            }
            val all = layout.all
            when {
                targets == null -> item(key = "loading", span = full) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                all.isEmpty() -> item(key = "empty", span = full) {
                    Text(
                        stringResource(if (query.isBlank()) ImeR.string.ime_share_empty else ImeR.string.ime_share_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                else -> {
                    item(key = "all-label", span = full) { Label(stringResource(ImeR.string.ime_share_all_label)) }
                    items(all, key = { it.key }) { target ->
                        TargetCell(
                            target = target,
                            pinned = target.key in share.pinned,
                            menuOpen = held == target,
                            recent = target.key in recents,
                            onTap = { pick(target) },
                            onHold = { held = target },
                            onMenuDismiss = { held = null },
                            onPin = {
                                held = null
                                scope.launch { repository.toggleSharePin(target.key) }
                            },
                            onForget = {
                                held = null
                                scope.launch { repository.removeShareRecent(target.key) }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A sheet from the bottom over a scrim that closes it, like the selection menu's sheet. */
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .statusBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight * SHEET_HEIGHT)
                // Taps inside the sheet are the sheet's, not the scrim's.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(Modifier.navigationBarsPadding()) { content() }
        }
    }
}

@Composable
private fun PreviewCard(preview: SharePreview) {
    val title = preview.subject ?: when {
        preview.files > 0 -> pluralStringResource(ImeR.plurals.ime_share_preview_files, preview.files, preview.files)
        else -> stringResource(ImeR.string.ime_share_preview_text)
    }
    val body = preview.text?.replace('\n', ' ')?.take(PREVIEW_CHARS)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (body != null && body != title) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickActions(preview: SharePreview, onCopy: () -> Unit, onOpen: () -> Unit, onSystem: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (preview.text != null || preview.firstUri != null) {
            AssistChip(
                onClick = onCopy,
                label = { Text(stringResource(ImeR.string.ime_share_copy)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (preview.link != null || (preview.files == 1 && preview.firstUri != null)) {
            AssistChip(
                onClick = onOpen,
                label = { Text(stringResource(ImeR.string.ime_share_open)) },
                leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        AssistChip(
            onClick = onSystem,
            label = { Text(stringResource(ImeR.string.ime_share_system)) },
            leadingIcon = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Suppress("LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetCell(
    target: ShareTarget,
    pinned: Boolean,
    menuOpen: Boolean,
    recent: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
    onMenuDismiss: () -> Unit,
    onPin: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val second = target.appLabel.takeIf { !target.label.contains(it, ignoreCase = true) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onTap, onLongClick = onHold)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                TargetIcon(target)
                if (pinned) {
                    Icon(
                        Icons.Outlined.PushPin,
                        contentDescription = stringResource(ImeR.string.ime_share_pinned_desc),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(14.dp)
                            .align(Alignment.TopEnd),
                    )
                }
            }
            Text(
                target.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
            if (second != null) {
                Text(
                    second,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) ImeR.string.ime_share_unpin else ImeR.string.ime_share_pin)) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                onClick = onPin,
            )
            if (recent) {
                DropdownMenuItem(
                    text = { Text(stringResource(ImeR.string.ime_share_forget)) },
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    onClick = onForget,
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(ImeR.string.ime_share_cancel)) },
                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                onClick = onMenuDismiss,
            )
        }
    }
}

@Composable
private fun TargetIcon(target: ShareTarget) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { ICON_DP.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, target.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getActivityIcon(ComponentName(target.packageName, target.activityName))
                    .toBitmap(px, px)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
    } else {
        Box(
            Modifier
                .size(ICON_DP.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

/** The text, or the first file, onto the clipboard. */
private fun copy(context: Context, preview: SharePreview) {
    val clip = when {
        preview.text != null -> ClipData.newPlainText("", preview.text)
        preview.firstUri != null -> ClipData.newUri(context.contentResolver, "share", preview.firstUri)
        else -> return
    }
    runCatching { context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip) }
    // Android 13 and later confirm a copy themselves.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, ImeR.string.ime_share_copied_toast, Toast.LENGTH_SHORT).show()
    }
}

/** The link in a browser, or the one file in whatever views its type. True when something opened. */
private fun open(context: Context, send: Intent, preview: SharePreview): Boolean {
    val link = preview.link
    val uri = preview.firstUri
    val view = when {
        link != null -> Intent(Intent.ACTION_VIEW, Uri.parse(link))
        uri != null && preview.files == 1 -> Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, send.type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(null, uri) }
        else -> return false
    }
    val started = runCatching { context.startActivity(view) }.isSuccess
    if (!started) Toast.makeText(context, ImeR.string.ime_share_failed_toast, Toast.LENGTH_SHORT).show()
    return started
}

private const val SHEET_HEIGHT = 0.85f
private const val SCRIM_ALPHA = 0.32f
private const val ICON_DP = 44
private const val PREVIEW_CHARS = 240
