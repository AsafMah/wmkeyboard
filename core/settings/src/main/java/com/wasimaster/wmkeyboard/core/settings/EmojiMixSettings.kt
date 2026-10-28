package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Emoji mixes: two emoji drawn as one sticker, from sources the user adds
 * ([EmojiMixSource]). The keyboard ships no source and names none; until one
 * is added and works, nothing about mixes appears on the keyboard.
 *
 * Nested in [EmojiSettings] like [KaomojiSettings]; its DataStore keys stay
 * flat (`emoji_mix_*`).
 */
data class EmojiMixSettings(
    /** The sources, in the order they answer. */
    val sources: List<EmojiMixSource> = emptyList(),
    /**
     * A row of mixes over the emoji panel's grid when an emoji is picked
     * there: the emoji with the one picked before it first, then with the
     * others. On by default.
     */
    val panelStrip: Boolean = true,
    /** Offer mixes in the suggestion strip when the text ends in one or two emoji. On by default. */
    val typingSuggest: Boolean = true,
    /** How those offers are drawn on the strip; the panel's row is always a tray. */
    val suggestStyle: StickerSuggestStyle = StickerSuggestStyle.STRIP,
    /** Sending an offered mix takes the emoji it was offered for back out of the text. On by default. */
    val replaceTypedEmoji: Boolean = true,
    /** The order an emoji's mixes are listed in. */
    val order: EmojiMixOrder = EmojiMixOrder.YOURS_FIRST,
    /** Whether a mix goes out as a sticker, where the app takes one, or as a picture. */
    val sendMode: MediaSendMode = MediaSendMode.STICKER,
    /** Size of a cell in the Mixes panel's grid, in dp. See [EmojiMixCellSizeRange]. */
    val cellSize: Int = 72,
    /** Rebuild every downloaded index once a month, on Wi-Fi only. Off by default: nothing is fetched unasked. */
    val autoRefresh: Boolean = false,
    /** Pairs the user starred, as `leftKey_rightKey`, newest first. */
    val favourites: List<String> = emptyList(),
    /**
     * The emoji categories the panel's shuffle draws the first emoji from, by
     * catalog name (`smileys`, `animals`…). Empty means every category.
     */
    val shuffleFirst: Set<String> = emptySet(),
    /** The same for the second emoji. Empty means every category. */
    val shuffleSecond: Set<String> = emptySet(),
    /**
     * Bumped whenever an index file is rebuilt, imported or removed, so the
     * running keyboard reloads them. Not a preference; the trick
     * [EmojiSettings.keywordPackVersion] plays.
     */
    val indexVersion: Int = 0,
) {
    /** Whether any source is switched on; the keyboard then looks for mixes. */
    val active: Boolean get() = sources.any { it.enabled }
}

/** How an emoji's mixes are ordered. */
enum class EmojiMixOrder {
    /** The emoji used most lately first, then the source's own order. */
    YOURS_FIRST,

    /** The source's order throughout. */
    SOURCE,
}

/** Bounds for [EmojiMixSettings.cellSize]; the settings slider shares them. */
val EmojiMixCellSizeRange = 56..120

/** Most favourites kept; past it the oldest go. */
const val EmojiMixFavouritesMax = 200

private val mixSourceJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
private val mixSourceListSerializer = ListSerializer(EmojiMixSource.serializer())

/** [EmojiMixSettings.sources] as stored. */
fun encodeEmojiMixSources(sources: List<EmojiMixSource>): String =
    mixSourceJson.encodeToString(mixSourceListSerializer, sources)

/** The stored sources, or none for a missing or unreadable value. */
fun decodeEmojiMixSources(raw: String?): List<EmojiMixSource> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { mixSourceJson.decodeFromString(mixSourceListSerializer, raw) }.getOrDefault(emptyList())
}
