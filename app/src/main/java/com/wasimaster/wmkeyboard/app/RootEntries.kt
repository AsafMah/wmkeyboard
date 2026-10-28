package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.TextSnippet
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Accessibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AspectRatio
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Extension
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Info
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Save
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Security
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ViewAgenda
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.allowsRoute

/** The headings the settings root is divided under, in order. */
internal enum class RootGroup(@StringRes val title: Int) {
    TYPING(R.string.home_group_typing_title),
    KEYBOARD(R.string.home_group_keyboard_title),
    FEATURES(R.string.home_group_features_title),
    ACCESSIBILITY(R.string.home_group_accessibility_title),
    DATA(R.string.home_group_data_title),
    ABOUT(R.string.home_group_about_title),
}

/** One row of the settings root: where it goes, and the words search finds it by. */
internal class RootEntry(
    val route: String,
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    @StringRes val keywords: Int,
    val group: RootGroup,
    /**
     * How far down the settings depth the row sits. Above its depth the row
     * leaves its group for the closed "More" fold at the end of Home; see
     * [SettingLevel].
     */
    val level: SettingLevel = SettingLevel.BASIC,
)

/**
 * The settings root, in display order. Languages first: it is the first thing
 * anyone looks for on a new keyboard. Key layouts is not here — it is a row
 * inside Languages, and one door is enough. What a first-week user never
 * needs (modes, add-ons, data saver, the physical keyboard) sits behind
 * Advanced, still one press away. AI has a row of its own (#759) although
 * its two tools are on the Tools list too: the provider, the chat and the
 * history serve more than one tool, and nobody looks for them under Tools.
 *
 * At the Simple depth the list is the eleven rows other keyboards have an
 * answer to, plus Extensions, Accessibility and About; the rest wait in a
 * closed More fold at the end, named on it. A row a switched-off extension
 * owns (Voice typing, Clipboard, Text expansion, AI) is not drawn at all; see
 * [visibleRootEntries]. Privacy stays: a keyboard that reads what
 * you type owes everyone that door.
 */
internal val RootEntries: List<RootEntry> = listOf(
    RootEntry(
        "languages", Icons.Outlined.Language,
        R.string.home_languages_title, R.string.search_languages_subtitle,
        R.string.search_kw_languages, RootGroup.TYPING,
    ),
    RootEntry(
        "typing", Icons.Outlined.Keyboard,
        R.string.home_typing_title, R.string.home_typing_subtitle,
        R.string.search_kw_typing, RootGroup.TYPING,
    ),
    RootEntry(
        "keypress", Icons.Outlined.TouchApp,
        R.string.home_keypress_title, R.string.home_keypress_subtitle,
        R.string.search_kw_keypress, RootGroup.TYPING,
    ),
    RootEntry(
        "appearance", Icons.Outlined.Palette,
        R.string.home_appearance_title, R.string.home_appearance_subtitle,
        R.string.search_kw_appearance, RootGroup.KEYBOARD,
    ),
    RootEntry(
        "layout", Icons.Outlined.AspectRatio,
        R.string.home_layout_title, R.string.home_layout_subtitle,
        R.string.search_kw_layout, RootGroup.KEYBOARD,
    ),
    RootEntry(
        "rows", Icons.Outlined.ViewAgenda,
        R.string.home_rows_title, R.string.home_rows_subtitle,
        R.string.search_kw_rows, RootGroup.KEYBOARD,
        level = SettingLevel.STANDARD,
    ),
    RootEntry(
        "emoji", Icons.Outlined.EmojiEmotions,
        R.string.home_emoji_title, R.string.home_emoji_subtitle,
        R.string.search_kw_emoji, RootGroup.FEATURES,
    ),
    RootEntry(
        "voice", Icons.Outlined.Mic,
        R.string.home_voice_title, R.string.home_voice_subtitle,
        R.string.search_kw_voice, RootGroup.FEATURES,
    ),
    RootEntry(
        "clipboard", Icons.Outlined.ContentPaste,
        R.string.home_clipboard_title, R.string.home_clipboard_subtitle,
        R.string.search_kw_clipboard, RootGroup.FEATURES,
    ),
    RootEntry(
        "expander", Icons.AutoMirrored.Outlined.TextSnippet,
        R.string.home_expander_title, R.string.home_expander_subtitle,
        R.string.search_kw_expander, RootGroup.FEATURES,
        level = SettingLevel.STANDARD,
    ),
    RootEntry(
        "ai", Icons.Outlined.AutoAwesome,
        R.string.home_ai_title, R.string.home_ai_subtitle,
        R.string.search_kw_ai, RootGroup.FEATURES,
        level = SettingLevel.STANDARD,
    ),
    RootEntry(
        "tools", Icons.Outlined.Widgets,
        R.string.home_tools_title, R.string.home_tools_subtitle,
        R.string.search_kw_tools, RootGroup.FEATURES,
    ),
    // Whole features on or off (SETTINGS_REDESIGN.md §3). Shown at every
    // depth: the person who wants less of the app is the one it is for.
    RootEntry(
        "extensions", Icons.Outlined.Extension,
        R.string.extensions_title, R.string.extensions_subtitle,
        R.string.search_kw_extensions, RootGroup.FEATURES,
    ),
    RootEntry(
        "accessibility", Icons.Outlined.Accessibility,
        R.string.home_accessibility_title, R.string.home_accessibility_subtitle,
        R.string.search_kw_accessibility, RootGroup.ACCESSIBILITY,
    ),
    RootEntry(
        "privacy", Icons.Outlined.Security,
        R.string.home_privacy_title, R.string.home_privacy_subtitle,
        R.string.search_kw_privacy, RootGroup.DATA,
    ),
    RootEntry(
        "backup", Icons.Outlined.Save,
        R.string.home_backup_title, R.string.home_backup_subtitle,
        R.string.search_kw_backup, RootGroup.DATA,
        level = SettingLevel.STANDARD,
    ),
    RootEntry(
        "advanced", Icons.Outlined.Tune,
        R.string.home_advanced_title, R.string.home_advanced_subtitle,
        R.string.search_kw_advanced, RootGroup.DATA,
        level = SettingLevel.STANDARD,
    ),
    RootEntry(
        "about", Icons.Outlined.Info,
        R.string.home_about_title, R.string.home_about_subtitle,
        R.string.search_kw_about, RootGroup.ABOUT,
    ),
)

/**
 * The root rows left once the switched-off extensions have taken theirs: a
 * bundle that owns a Home row owns it by route, so the row goes with the
 * bundle and comes back with it. The search index's root entries are built
 * from the same filter.
 */
internal fun visibleRootEntries(extensions: Set<Extension>): List<RootEntry> =
    RootEntries.filter { extensions.allowsRoute(it.route) }
