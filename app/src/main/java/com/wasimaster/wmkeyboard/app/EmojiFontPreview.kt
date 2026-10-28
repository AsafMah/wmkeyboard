package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.EmojiFontShaping
import com.wasimaster.wmkeyboard.core.fonts.EmojiFontCatalog
import com.wasimaster.wmkeyboard.core.fonts.EmojiFontDownload
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.settings.EmojiFontChoice
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The faces the preview draws — a spread of the shapes people notice first. */
private val PREVIEW_EMOJI = listOf("😀", "😂", "🥰", "😎", "🤔", "👍", "❤️", "🎉")

/**
 * A line of emoji drawn in the chosen font, for the settings screen that lets
 * someone pick one.
 *
 * Shaped exactly as the keyboard shapes them, so the preview shows what the
 * panel will show — ❤️ in particular silently comes from the system font in a
 * font with no variation-selector table (see [EmojiFontShaping]). The tables
 * are read off the main thread, so the row draws unshaped for a frame rather
 * than stalling the screen on a megabyte of font.
 *
 * [refresh] is a counter a caller bumps after importing a font file, since the
 * path stays the same while its contents change.
 */
@Composable
internal fun EmojiFontPreviewRow(
    choice: EmojiFontChoice,
    installedId: String,
    modifier: Modifier = Modifier,
    refresh: Int = 0,
) {
    val context = LocalContext.current
    val family = remember(choice, installedId, refresh) {
        KeyboardFonts.emojiFamily(context, choice, installedId)
    }
    val shaper by produceState(EmojiFontShaping.Identity, choice, installedId, refresh) {
        val file = KeyboardFonts.emojiFontFile(context, choice, installedId)
        withContext(Dispatchers.Default) { EmojiFontShaping.warm(file) }
        value = EmojiFontShaping.forFontFile(file)
    }
    // One Text per emoji: emoji fonts often have no space glyph, so drawing
    // them as a single spaced string makes the glyphs overlap.
    Row(modifier = modifier) {
        for (emoji in PREVIEW_EMOJI) {
            val spelling = shaper.spelling(emoji)
            Text(
                spelling.text,
                fontSize = 24.sp,
                fontFamily = if (spelling.systemFont) null else family,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

/**
 * The one-tap route to a current emoji font.
 *
 * The "Google" choice above it asks the Play services font provider for "Noto
 * Color Emoji", and a downloadable font is requested by family name — there is
 * no version in that request. The provider answers with whatever build it has,
 * which on a phone with a recent system emoji font is the older of the two, so
 * that choice cannot be relied on for new emoji. This row fetches the current
 * file instead and installs it into the font library, which is the same slot
 * an emoji-font add-on lands in — the same file is published there too, for
 * anyone who would rather browse the catalogue.
 *
 * [onInstalled] receives the store id, and points the setting at it.
 */
@Composable
internal fun EmojiFontDownloadRow(
    installedId: String,
    modifier: Modifier = Modifier,
    onInstalled: (String) -> Unit,
) {
    val context = LocalContext.current
    val status by EmojiFontDownload.state.collectAsStateWithLifecycle()
    val published by EmojiFontDownload.published.collectAsStateWithLifecycle()
    // Asked once per process, and quietly: not knowing is the normal offline
    // answer and must not turn into an error on a screen nobody opened for it.
    LaunchedEffect(Unit) { EmojiFontDownload.checkPublished() }
    // Fires once per install: not on every recomposition that sees the
    // finished state, and not again when this row is composed afresh on a
    // later visit to the screen, which is what [EmojiFontDownload.claim] rules
    // out (#430).
    LaunchedEffect(status) {
        val done = status as? EmojiFontDownload.Status.Installed ?: return@LaunchedEffect
        if (EmojiFontDownload.claim(done)) onInstalled(done.fontId)
    }
    // The copy already in the library, if it is this font, and whether the
    // repository has since published a different build of it.
    val installed = remember(installedId, status) {
        FontStore.get(context).emojiFonts().firstOrNull(EmojiFontCatalog::isNoto)
    }
    val update = installed != null && published != null && published?.version != installed.version
    val notifyDownload = rememberDownloadNotifier()
    val downloadName = stringResource(R.string.notify_download_emoji_font)
    val startDownload = {
        EmojiFontDownload.start(context)
        notifyDownload(
            EMOJI_FONT_DOWNLOAD_KEY,
            downloadName,
            DownloadProgressFlows.emojiFont(context),
        )
    }
    if (installed != null && !update && status !is EmojiFontDownload.Status.Failed) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Text(
            stringResource(
                if (update) R.string.langemoji_emoji_font_update_title
                else R.string.langemoji_emoji_font_latest_title,
            ),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (update) {
                stringResource(
                    R.string.langemoji_emoji_font_update_subtitle,
                    published?.version.orEmpty(),
                    formatBytes(published?.sizeBytes ?: EmojiFontCatalog.NOTO_APPROX_BYTES),
                )
            } else {
                stringResource(
                    R.string.langemoji_emoji_font_latest_subtitle,
                    formatBytes(published?.sizeBytes ?: EmojiFontCatalog.NOTO_APPROX_BYTES),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when (val state = status) {
            is EmojiFontDownload.Status.Downloading -> {
                val fraction = if (state.total > 0) {
                    (state.bytes.toFloat() / state.total).coerceIn(0f, 1f)
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is EmojiFontDownload.Status.Failed -> {
                CaptionText(stringResource(state.messageRes), error = true)
                Button(onClick = startDownload) {
                    Text(stringResource(CommonR.string.common_retry))
                }
            }
            else -> Button(onClick = startDownload) {
                Text(
                    stringResource(
                        if (update) R.string.langemoji_emoji_font_update_action
                        else R.string.langemoji_emoji_font_latest_action,
                    ),
                )
            }
        }
    }
}

/**
 * The notification key for the emoji font.
 *
 * A constant rather than an id from the catalog because there is only ever one
 * emoji font download in flight: [EmojiFontDownload] publishes a single state,
 * not a map.
 */
private const val EMOJI_FONT_DOWNLOAD_KEY = "emoji-font"
