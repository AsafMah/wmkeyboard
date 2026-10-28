package com.wasimaster.wmkeyboard.core.music

import kotlinx.serialization.Serializable

/**
 * Where a recognised song can be opened. [id] is stored in the history file
 * and in the settings, so never rename one.
 */
enum class MusicLink(val id: String, val label: String) {
    SPOTIFY("spotify", "Spotify"),
    APPLE_MUSIC("apple_music", "Apple Music"),
    DEEZER("deezer", "Deezer"),
    YOUTUBE("youtube", "YouTube"),
    YOUTUBE_MUSIC("youtube_music", "YouTube Music"),

    /** AudD's song.link page, which lists every store the song is on. */
    SONG_LINK("song_link", "song.link"),
    MUSICBRAINZ("musicbrainz", "MusicBrainz"),
    SHAZAM("shazam", "Shazam"),
    ;

    companion object {
        fun of(id: String?): MusicLink? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One recognised song: what the service said about it, and where it can be
 * opened. [via] is the provider type id that answered (`audd`, `acrcloud`,
 * …), [offsetMs] where in the song the clip was taken from
 * when the service knows, [score] its confidence in 0..100 when it gives one.
 *
 * Serializable because the history file keeps these as they came.
 */
@Serializable
data class SongMatch(
    val title: String,
    val artist: String,
    val album: String = "",
    val year: String = "",
    val label: String = "",
    val durationMs: Long = 0,
    val offsetMs: Long = 0,
    val score: Int = 0,
    val links: Map<String, String> = emptyMap(),
    val artUrl: String? = null,
    val isrc: String = "",
    val via: String = "",
) {
    val hasArt: Boolean get() = !artUrl.isNullOrBlank()

    fun link(kind: MusicLink): String? = links[kind.id]?.takeIf { it.isNotBlank() }

    /** The links this match has, in [MusicLink] order. */
    val openable: List<MusicLink> get() = MusicLink.entries.filter { link(it) != null }

    /** A song is the same song when its title and artist read the same, whatever service named it. */
    val identity: String get() = (title.trim() + "\u0000" + artist.trim()).lowercase()

    companion object {
        /** The same four digits every service writes a release year as, out of "2019-05-17", "2019" or "17 May 2019". */
        fun yearOf(date: String?): String =
            date?.let { Regex("(19|20)\\d\\d").find(it)?.value }.orEmpty()
    }
}

/** What one try at recognising a clip came to. */
sealed interface MusicOutcome {
    /** [match] from the provider [via] (its row key). */
    data class Match(val match: SongMatch) : MusicOutcome

    /** The provider heard the clip and knew no song in it. */
    data object NoMatch : MusicOutcome

    /** The provider could not be asked, or refused: [message] says why in the user's words. */
    data class Failed(val message: String, val cause: Throwable? = null) : MusicOutcome
}

/** Something a provider said no to for a reason the user can act on, in plain words. */
class MusicProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)
