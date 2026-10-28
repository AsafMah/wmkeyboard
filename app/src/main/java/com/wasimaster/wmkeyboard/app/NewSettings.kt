package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close

/**
 * The settings an update brought (#970), shown where they live: a gold border
 * on each new row, and a bar at the bottom of the screen that walks through
 * them one at a time.
 *
 * Nothing records which version a row arrived in, and nothing needs to. Each
 * version leaves behind the names of the rows it had, route and title
 * resource name, which are the same in every build and every language. The
 * first launch after an update compares the index against them, and what it
 * did not have before is new. The very first install records the names and
 * marks nothing: every setting is new then, and saying so says nothing.
 */
internal object NewSettings {
    private const val PREFS = "new_settings"
    private const val KEY_VERSION = "version_code"
    private const val KEY_KNOWN = "known"
    private const val KEY_FRESH = "fresh"

    /** The new rows, in the index's order, which is the order the screens list them. */
    var entries: List<SettingsSearchEntry> by mutableStateOf(emptyList())
        private set

    /** Title resources of [entries], which the rows match on. */
    var titles: Set<Int> by mutableStateOf(emptySet())
        private set

    /** Which of [entries] the bar last opened, or -1 before it has opened one. */
    var at: Int by mutableIntStateOf(-1)
        private set

    /** Reads, and after an update works out, the new rows. Off the main thread: it builds the index. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrDefault(0L)
        val res = context.resources
        val index = settingsSearchIndex(res).filter { it.titleRes != 0 }
        val keyed = index.associateBy { entry ->
            "${entry.route}#${runCatching { res.getResourceEntryName(entry.titleRes) }.getOrDefault(entry.title)}"
        }
        val fresh = if (prefs.getLong(KEY_VERSION, -1L) == version) {
            prefs.getStringSet(KEY_FRESH, null).orEmpty()
        } else {
            val known = prefs.getStringSet(KEY_KNOWN, null)
            // Nothing known is a first install, or a version from before this
            // existed: no way to tell what is new, so nothing is.
            val added = if (known.isNullOrEmpty()) emptySet() else keyed.keys - known
            prefs.edit()
                .putLong(KEY_VERSION, version)
                .putStringSet(KEY_KNOWN, keyed.keys + known.orEmpty())
                .putStringSet(KEY_FRESH, added)
                .apply()
            added
        }
        val found = keyed.filterKeys { it in fresh }.values.distinctBy { it.titleRes }
        entries = found
        titles = found.mapTo(HashSet()) { it.titleRes }
        at = -1
    }

    /** Moves the bar to [index] and hands back the entry to open there. */
    fun step(index: Int): SettingsSearchEntry? {
        val entry = entries.getOrNull(index) ?: return null
        at = index
        return entry
    }

    /** The user has seen enough: the borders and the bar go until the next update. */
    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FRESH, emptySet()).apply()
        entries = emptyList()
        titles = emptySet()
        at = -1
    }
}

/** The border a new row wears. Gold, not the theme's: it has to read as "new" on every scheme. */
internal val NewSettingGold = Color(0xFFD4A62A)

/**
 * The bar at the bottom of every settings screen while an update's new rows
 * are unseen: how many there are, the way to the previous and next one, and
 * a close button that puts the borders away. [onOpen] arms the row's flash
 * and opens its screen.
 */
@Composable
internal fun NewSettingsBar(onOpen: (SettingsSearchEntry) -> Unit, modifier: Modifier = Modifier) {
    val entries = NewSettings.entries
    if (entries.isEmpty()) return
    val context = LocalContext.current
    val at = NewSettings.at
    val go = { index: Int -> NewSettings.step(index)?.let(onOpen) }
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .widthIn(max = 480.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = { go(if (at <= 0) entries.lastIndex else at - 1) }) {
                Icon(Icons.Outlined.ChevronLeft, contentDescription = stringResource(R.string.shell_new_settings_previous_desc))
            }
            TextButton(onClick = { go(at.coerceAtLeast(0)) }, modifier = Modifier.weight(1f)) {
                Text(
                    if (at < 0) {
                        pluralStringResource(R.plurals.shell_new_settings_count, entries.size, entries.size)
                    } else {
                        stringResource(R.string.shell_new_settings_position, at + 1, entries.size, entries[at].title)
                    },
                    color = NewSettingGold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { go(if (at >= entries.lastIndex) 0 else at + 1) }) {
                Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.shell_new_settings_next_desc))
            }
            IconButton(onClick = { NewSettings.dismiss(context) }) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.shell_new_settings_dismiss_desc))
            }
        }
    }
}
