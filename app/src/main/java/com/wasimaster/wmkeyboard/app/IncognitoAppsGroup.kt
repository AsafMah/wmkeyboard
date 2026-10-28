package com.wasimaster.wmkeyboard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Apps whose fields are always incognito, or never are, whatever a field asks
 * for (#970). Follow private browsing goes by the flag a field sets, and some
 * apps set it everywhere (a whole notes app treated as a private tab) while
 * others never do (a banking app that should be).
 *
 * Each app's row says which way it goes, and a tap turns it the other way. An
 * app comes in as always incognito, the reason most people come here.
 */
@Composable
internal fun IncognitoAppsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apps = settings.watch { it.incognitoApps }
    // Names, not package ids, wherever the app is still installed.
    val labels = remember(apps.keys) {
        val pm = context.packageManager
        apps.keys.associateWith { pkg ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }
    var picking by remember { mutableStateOf(false) }
    SettingsGroup(
        stringResource(R.string.privacy_incognito_apps_title),
        info = stringResource(R.string.privacy_incognito_apps_info),
    ) {
        for ((pkg, always) in apps.entries.sortedBy { labels[it.key]?.lowercase() }) {
            item(key = "incognito:$pkg", level = SettingLevel.ADVANCED) {
                WmRow(
                    title = labels[pkg] ?: pkg,
                    subtitle = stringResource(
                        if (always) R.string.privacy_incognito_apps_always else R.string.privacy_incognito_apps_never,
                    ),
                    trailing = {
                        IconButton(onClick = { scope.launch { repository.setIncognitoApp(pkg, null) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.privacy_incognito_apps_remove_desc))
                        }
                    },
                    onClick = { scope.launch { repository.setIncognitoApp(pkg, !always) } },
                )
            }
        }
        item(level = SettingLevel.ADVANCED) {
            WmRow(
                title = stringResource(R.string.privacy_incognito_apps_add),
                icon = Icons.Outlined.Add,
                onClick = { picking = true },
            )
        }
    }
    if (picking) {
        AppPickerDialog(
            exclude = apps.keys.toList(),
            onPick = { pkg ->
                picking = false
                scope.launch { repository.setIncognitoApp(pkg, true) }
            },
            onDismiss = { picking = false },
        )
    }
}
