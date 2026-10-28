package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.MusicClip
import com.wasimaster.wmkeyboard.core.music.MusicLink
import com.wasimaster.wmkeyboard.core.music.MusicTextFormat

/**
 * The Music ID tool's settings, one nested bag for the reason
 * [LocationSettings] gives. Each field persists under its own `music_*`
 * DataStore key through the matching setter. Which services are asked is
 * the tool's provider list, not here.
 */
data class MusicSettings(
    /** What Insert types when no other format chip was picked. */
    val textFormat: MusicTextFormat = MusicTextFormat.TITLE_ARTIST,
    /** The picture the Picture chip makes when no other style was picked. */
    val cardStyle: MusicCardStyle = MusicCardStyle.CARD,
    /**
     * Read what the phone is already playing before listening: the media
     * session's title and artist, where notification access allows it. Off,
     * the tool always listens.
     */
    val nowPlayingFirst: Boolean = true,
    /** How long the microphone listens, in seconds, between [MusicClip.MIN_SECONDS] and [MusicClip.MAX_SECONDS]. */
    val clipSeconds: Int = MusicClip.DEFAULT_SECONDS,
    /** Keep the songs recognised in the History tab. Off, nothing is written. */
    val keepHistory: Boolean = true,
    /** Where "Open in" goes when the match has that store; null takes the first the match has. */
    val openLink: MusicLink? = null,
)
