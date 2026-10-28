package com.wasimaster.wmkeyboard.app.assistant

import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings

/**
 * What "Offer the Settings assistant" turned off hides (#830), in one place so
 * the search screen, the two AI screens and the route all ask the same thing,
 * and a JVM test can ask it too.
 */
internal object SettingsAssistantGate {

    const val ROUTE = "settings_assistant"

    /** Its own switch (#830). */
    fun offered(settings: KeyboardSettings): Boolean = settings.appUi.settingsAssistant

    /** A search result that would lead to the assistant: its row, or its route. */
    fun opensAssistant(entry: SettingsSearchEntry): Boolean =
        entry.titleRes == R.string.settings_assistant_title || entry.route == ROUTE

    /** The search index with the assistant's results left out while it is off. */
    fun searchable(index: List<SettingsSearchEntry>, offered: Boolean): List<SettingsSearchEntry> =
        if (offered) index else index.filterNot(::opensAssistant)
}
