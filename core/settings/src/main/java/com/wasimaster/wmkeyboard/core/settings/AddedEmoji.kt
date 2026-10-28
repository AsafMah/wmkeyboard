package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One emoji the user added to the panel by hand: the way to reach a face a new
 * Unicode release brought before a build ships it in the bundled catalog.
 *
 * Shaped like a catalog row on purpose: the keyboard turns each one into an
 * `EmojiEntry` and appends it to the catalog before anything indexes it, so
 * the panel, search, suggestions, favourites and recents all treat it as one
 * of their own. See [EmojiSettings.added].
 *
 * [category] is a catalog category id ("smileys", "objects"). [keywords] are
 * stored as typed and lowercased on load, like the catalog's own.
 */
@Serializable
data class AddedEmoji(
    val emoji: String,
    val category: String,
    val name: String = "",
    val keywords: List<String> = emptyList(),
)

/**
 * The most hand-added emoji kept. A whole Unicode release is a few dozen
 * new faces, so this is room for several missed updates while keeping the
 * preference a few tens of kilobytes at worst.
 */
const val MAX_ADDED_EMOJI = 500

private val addedEmojiSerializer = ListSerializer(AddedEmoji.serializer())

private val addedEmojiJson = Json { ignoreUnknownKeys = true }

internal fun encodeAddedEmoji(list: List<AddedEmoji>): String =
    addedEmojiJson.encodeToString(addedEmojiSerializer, list)

/**
 * The stored list, cleaned: blank faces dropped, one entry per face (the
 * first wins), capped at [MAX_ADDED_EMOJI]. A malformed blob reads as an
 * empty list rather than failing the whole settings read.
 */
internal fun decodeAddedEmoji(raw: String?): List<AddedEmoji> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { addedEmojiJson.decodeFromString(addedEmojiSerializer, raw) }
        .getOrDefault(emptyList())
        .let(::cleanAddedEmoji)
}

internal fun cleanAddedEmoji(list: List<AddedEmoji>): List<AddedEmoji> =
    list.asSequence()
        .map { it.copy(emoji = it.emoji.trim(), category = it.category.trim(), name = it.name.trim()) }
        .filter { it.emoji.isNotEmpty() && it.category.isNotEmpty() }
        .distinctBy { it.emoji }
        .take(MAX_ADDED_EMOJI)
        .toList()
