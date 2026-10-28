package com.wasimaster.wmkeyboard.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTextAndHistoryTest {

    private val song = SongMatch(
        title = "Around the World",
        artist = "Daft Punk",
        album = "Homework",
        year = "1997",
        links = mapOf(MusicLink.SPOTIFY.id to "https://open.spotify.com/track/1", MusicLink.SONG_LINK.id to "https://lis.tn/x"),
    )

    @Test
    fun `every text format reads as documented`() {
        assertEquals("Around the World – Daft Punk", MusicTextFormats.render(song, MusicTextFormat.TITLE_ARTIST))
        assertEquals("Daft Punk – Around the World", MusicTextFormats.render(song, MusicTextFormat.ARTIST_TITLE))
        assertEquals("Around the World – Daft Punk (Homework, 1997)", MusicTextFormats.render(song, MusicTextFormat.WITH_ALBUM))
        // The first link in MusicLink order, Spotify before song.link.
        assertEquals("https://open.spotify.com/track/1", MusicTextFormats.render(song, MusicTextFormat.LINK))
        // The preferred store when the song is on it.
        assertEquals("https://lis.tn/x", MusicTextFormats.render(song, MusicTextFormat.LINK, MusicLink.SONG_LINK))
        assertEquals("Around the World – Daft Punk\nhttps://open.spotify.com/track/1", MusicTextFormats.render(song, MusicTextFormat.TITLE_ARTIST_LINK))
        assertEquals("🎵 Now playing: Around the World – Daft Punk", MusicTextFormats.render(song, MusicTextFormat.NOW_PLAYING))
    }

    @Test
    fun `a song with no album or links degrades to the title and artist`() {
        val bare = SongMatch(title = "Untitled", artist = "")
        assertEquals("Untitled", MusicTextFormats.render(bare, MusicTextFormat.WITH_ALBUM))
        assertEquals("Untitled", MusicTextFormats.render(bare, MusicTextFormat.LINK))
        assertEquals("Untitled", MusicTextFormats.render(bare, MusicTextFormat.TITLE_ARTIST_LINK))
    }

    @Test
    fun `history folds a repeat within ten minutes and caps at two hundred`() {
        val history = MusicHistory.inMemory()
        val first = history.add(song, now = 1_000L)
        val again = history.add(song.copy(album = "Homework (Deluxe)"), now = 1_000L + 5 * 60 * 1000)
        assertEquals(first.id, again.id)
        assertEquals(1, history.entries.size)
        assertEquals("Homework (Deluxe)", history.entries.single().match.album)
        // The same song an hour later is a new row.
        history.add(song, now = 1_000L + 60 * 60 * 1000)
        assertEquals(2, history.entries.size)
        for (i in 0 until 250) history.add(song.copy(title = "Song $i"), now = 10_000_000L + i)
        assertEquals(MusicHistory.MAX_ENTRIES, history.entries.size)
        assertEquals("Song 249", history.entries.first().match.title)
    }

    @Test
    fun `history rows go one at a time or all at once`() {
        val history = MusicHistory.inMemory()
        val a = history.add(song, now = 1L)
        history.add(song.copy(title = "B"), now = 2L)
        history.remove(a.id)
        assertEquals(listOf("B"), history.entries.map { it.match.title })
        history.clear()
        assertTrue(history.entries.isEmpty())
    }

    @Test
    fun `identity ignores case and spacing, so two services name one song`() {
        assertEquals(song.identity, song.copy(title = " around the world", artist = "DAFT PUNK ").identity)
        assertFalse(song.identity == song.copy(title = "One More Time").identity)
    }

    @Test
    fun `a clip is upsampled to the length the rate implies and heard when loud enough`() {
        val quiet = FloatArray(16_000) { 0.001f }
        val loud = FloatArray(16_000) { if (it % 2 == 0) 0.2f else -0.2f }
        assertFalse(MusicClip.heardSomething(quiet))
        assertTrue(MusicClip.heardSomething(loud))
        val up = MusicClip.upsample(loud, 16_000, 44_100)
        assertEquals(44_100, up.size)
        assertEquals(loud.size * 2, MusicClip.pcm16(loud).size)
        assertTrue(MusicClip.upsample(loud, 16_000, 16_000) === loud)
    }
}
