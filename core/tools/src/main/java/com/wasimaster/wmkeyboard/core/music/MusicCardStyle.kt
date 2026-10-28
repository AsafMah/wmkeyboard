package com.wasimaster.wmkeyboard.core.music

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * The pictures a recognised song can be put into the field as. A style is a
 * layout, not a file format: the field decides whether it gets a PNG, a
 * WebP or a JPEG, the way every other picture the keyboard sends does.
 * [id] is stored in the settings, so never rename one.
 */
enum class MusicCardStyle(val id: String, @StringRes val labelRes: Int, val width: Int, val height: Int) {
    /** The cover alone, square. */
    COVER("cover", R.string.core_tools_music_card_cover, 600, 600),

    /** The cover with the title, artist and album under it, square: the shape chats show largest. */
    CARD("card", R.string.core_tools_music_card_card, 1080, 1080),

    /** The cover on the left, the words beside it: a link preview's shape. */
    BANNER("banner", R.string.core_tools_music_card_banner, 1200, 630),

    /** Tall, for a story: the cover large, the words under it. */
    STORY("story", R.string.core_tools_music_card_story, 1080, 1920),
    ;

    companion object {
        fun of(id: String?): MusicCardStyle? = entries.firstOrNull { it.id == id }
    }
}
