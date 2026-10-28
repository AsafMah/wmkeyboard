package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.wasimaster.wmkeyboard.R

/**
 * "Add to home screen" for a tool with a screen of its own in this app (#970):
 * the clock, the notes, the to-do list. Asks the launcher to pin a shortcut
 * that opens [route] straight away, the way the four long-press shortcuts on
 * the app icon do for theirs.
 *
 * Only for tools that work outside the keyboard. A panel like the text scanner
 * needs a text field under it, which a launcher icon does not have.
 */
@Composable
internal fun HomeScreenShortcutRow(@StringRes label: Int, route: String) {
    val context = LocalContext.current
    val name = stringResource(label)
    ActionRow(
        R.string.home_shortcut_add_title,
        subtitle = stringResource(R.string.home_shortcut_add_subtitle, name),
        action = stringResource(R.string.home_shortcut_add_action),
    ) {
        val shortcut = ShortcutInfoCompat.Builder(context, "tool_$route")
            .setShortLabel(name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_tools))
            // The same address the app icon's own shortcuts carry, read by
            // SettingsDeepLink on the way in.
            .setIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/$route"))
                    .setClassName(context, "com.wasimaster.wmkeyboard.app.MainActivity"),
            )
            .build()
        val asked = ShortcutManagerCompat.isRequestPinShortcutSupported(context) &&
            runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
        if (!asked) Toast.makeText(context, R.string.home_shortcut_unsupported, Toast.LENGTH_SHORT).show()
    }
}
