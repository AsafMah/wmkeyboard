package com.wasimaster.wmkeyboard.core.music

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.tools.R

/**
 * The shapes a recognised song can be typed in. [id] is stored in the
 * settings, so never rename one.
 */
enum class MusicTextFormat(val id: String, @StringRes val labelRes: Int) {
    /** "Title – Artist" */
    TITLE_ARTIST("title_artist", R.string.core_tools_music_format_title_artist),

    /** "Artist – Title" */
    ARTIST_TITLE("artist_title", R.string.core_tools_music_format_artist_title),

    /** "Title – Artist (Album, 2019)" */
    WITH_ALBUM("with_album", R.string.core_tools_music_format_with_album),

    /** The first link the match has: song.link, Spotify, Apple Music, Deezer, YouTube. */
    LINK("link", R.string.core_tools_music_format_link),

    /** "Title – Artist" with the link on the next line. */
    TITLE_ARTIST_LINK("title_artist_link", R.string.core_tools_music_format_title_artist_link),

    /** "🎵 Now playing: Title – Artist" */
    NOW_PLAYING("now_playing", R.string.core_tools_music_format_now_playing),
    ;

    companion object {
        fun of(id: String?): MusicTextFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Renders a [SongMatch] in each [MusicTextFormat]. */
object MusicTextFormats {

    private const val DASH = " – "

    /**
     * The link a format types: the one the user prefers ([preferred]) when
     * the match has it, else the first the match has in [MusicLink] order.
     */
    fun linkOf(match: SongMatch, preferred: MusicLink?): String? =
        preferred?.let(match::link) ?: match.openable.firstOrNull()?.let(match::link)

    fun render(match: SongMatch, format: MusicTextFormat, preferred: MusicLink? = null): String {
        val title = match.title.trim()
        val artist = match.artist.trim()
        val titleArtist = listOf(title, artist).filter { it.isNotEmpty() }.joinToString(DASH)
        return when (format) {
            MusicTextFormat.TITLE_ARTIST -> titleArtist
            MusicTextFormat.ARTIST_TITLE -> listOf(artist, title).filter { it.isNotEmpty() }.joinToString(DASH)
            MusicTextFormat.WITH_ALBUM -> {
                val extra = listOf(match.album.trim(), match.year.trim()).filter { it.isNotEmpty() }.joinToString(", ")
                if (extra.isEmpty()) titleArtist else "$titleArtist ($extra)"
            }
            MusicTextFormat.LINK -> linkOf(match, preferred) ?: titleArtist
            MusicTextFormat.TITLE_ARTIST_LINK -> {
                val link = linkOf(match, preferred)
                if (link == null) titleArtist else "$titleArtist\n$link"
            }
            MusicTextFormat.NOW_PLAYING -> "🎵 Now playing: $titleArtist"
        }
    }
}
