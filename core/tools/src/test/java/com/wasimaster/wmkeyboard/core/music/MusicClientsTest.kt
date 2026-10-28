package com.wasimaster.wmkeyboard.core.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recognisers' answers, read without a network. */
class MusicClientsTest {

    @Test
    fun `AudD names the song with its stores`() {
        val body = """
            {"status":"success","result":{"artist":"Daft Punk","title":"Around the World","album":"Homework",
             "release_date":"1997-01-20","label":"Virgin","timecode":"00:36","song_link":"https://lis.tn/x",
             "apple_music":{"url":"https://music.apple.com/x","artwork":{"url":"https://a/{w}x{h}bb.jpg"},"durationInMillis":"428000","isrc":"GBDUW0000059"},
             "spotify":{"external_urls":{"spotify":"https://open.spotify.com/track/1"}},
             "deezer":{"link":"https://www.deezer.com/track/2","album":{"cover_big":"https://d/cover.jpg"}},
             "musicbrainz":[{"id":"abc"}]}}
        """.trimIndent()
        val match = (AuddClient.parse(body) as MusicOutcome.Match).match
        assertEquals("Around the World", match.title)
        assertEquals("Daft Punk", match.artist)
        assertEquals("1997", match.year)
        assertEquals(36_000L, match.offsetMs)
        assertEquals(428_000L, match.durationMs)
        assertEquals("https://a/600x600bb.jpg", match.artUrl)
        assertEquals("https://open.spotify.com/track/1", match.link(MusicLink.SPOTIFY))
        assertEquals("https://musicbrainz.org/recording/abc", match.link(MusicLink.MUSICBRAINZ))
        assertEquals("GBDUW0000059", match.isrc)
        assertEquals("audd", match.via)
    }

    @Test
    fun `AudD with nothing is no match, and an error is a failure in its own words`() {
        assertEquals(MusicOutcome.NoMatch, AuddClient.parse("""{"status":"success","result":null}"""))
        val failed = AuddClient.parse("""{"status":"error","error":{"error_code":901,"error_message":"No api_token passed"}}""")
        assertEquals("No api_token passed", (failed as MusicOutcome.Failed).message)
        assertTrue(AuddClient.parse("not json") is MusicOutcome.Failed)
    }

    @Test
    fun `AudD's endpoint is its root, or the server's own address`() {
        assertEquals("https://api.audd.io/", AuddClient.endpoint(""))
        assertEquals("https://music.example.org/recognize/", AuddClient.endpoint("https://music.example.org/recognize/"))
    }

    @Test
    fun `ACRCloud signs with HMAC-SHA1 over the six lines`() {
        // Computed with Python's hmac over "POST\n/v1/identify\nmykey\naudio\n1\n1700000000".
        assertEquals("rSr74sJ2KKQgm2x1J4IqUlnNFgc=", AcrCloudClient.sign("mykey", "mysecret", "1700000000"))
    }

    @Test
    fun `ACRCloud's host becomes the identify endpoint however it was typed`() {
        assertEquals("https://identify-eu-west-1.acrcloud.com/v1/identify", AcrCloudClient.endpoint("identify-eu-west-1.acrcloud.com"))
        assertEquals("https://identify-eu-west-1.acrcloud.com/v1/identify", AcrCloudClient.endpoint("https://identify-eu-west-1.acrcloud.com/"))
        assertEquals("https://h/v1/identify", AcrCloudClient.endpoint("https://h/v1/identify"))
    }

    @Test
    fun `ACRCloud's answer carries the stores by id and its codes mean what they say`() {
        val body = """
            {"status":{"code":0,"msg":"Success"},"metadata":{"music":[{"title":"Get Lucky","artists":[{"name":"Daft Punk"},{"name":"Pharrell Williams"}],
             "album":{"name":"Random Access Memories"},"release_date":"2013-04-19","label":"Columbia","score":100,"duration_ms":369000,"play_offset_ms":12000,
             "external_ids":{"isrc":"USQX91300108"},"external_metadata":{"spotify":{"track":{"id":"s1"}},"deezer":{"track":{"id":"d1"}},"youtube":{"vid":"y1"}}}]}}
        """.trimIndent()
        val match = (AcrCloudClient.parse(body) as MusicOutcome.Match).match
        assertEquals("Daft Punk, Pharrell Williams", match.artist)
        assertEquals("Random Access Memories", match.album)
        assertEquals("2013", match.year)
        assertEquals(100, match.score)
        assertEquals(12_000L, match.offsetMs)
        assertEquals("https://open.spotify.com/track/s1", match.link(MusicLink.SPOTIFY))
        assertEquals("https://www.youtube.com/watch?v=y1", match.link(MusicLink.YOUTUBE))
        assertNull(match.artUrl)
        assertEquals(MusicOutcome.NoMatch, AcrCloudClient.parse("""{"status":{"code":1001,"msg":"No result"}}"""))
        assertEquals("Invalid signature", (AcrCloudClient.parse("""{"status":{"code":3014,"msg":"Invalid signature"}}""") as MusicOutcome.Failed).message)
    }

    @Test
    fun `the Shazam listing's track is read with its sections and hub`() {
        val body = """
            {"matches":[{"id":"1"}],"track":{"title":"Harder, Better, Faster, Stronger","subtitle":"Daft Punk","isrc":"GBDUW0100039",
             "url":"https://www.shazam.com/track/1","images":{"coverart":"https://c/small.jpg","coverarthq":"https://c/big.jpg"},
             "hub":{"options":[{"actions":[{"type":"applemusicopen","uri":"https://music.apple.com/track/1"}]}],
                    "providers":[{"type":"SPOTIFY","actions":[{"uri":"spotify:search:Harder Daft Punk"}]}]},
             "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Discovery"},{"title":"Label","text":"Virgin"},{"title":"Released","text":"2001"}]}]}}
        """.trimIndent()
        val match = (RapidShazamClient.parse(body) as MusicOutcome.Match).match
        assertEquals("Daft Punk", match.artist)
        assertEquals("Discovery", match.album)
        assertEquals("2001", match.year)
        assertEquals("https://c/big.jpg", match.artUrl)
        assertEquals("https://www.shazam.com/track/1", match.link(MusicLink.SHAZAM))
        assertEquals("https://music.apple.com/track/1", match.link(MusicLink.APPLE_MUSIC))
        assertTrue(match.link(MusicLink.SPOTIFY)!!.startsWith("https://open.spotify.com/search/"))
        assertEquals(MusicOutcome.NoMatch, RapidShazamClient.parse("""{"matches":[]}"""))
        assertTrue(RapidShazamClient.parse("""{"message":"You are not subscribed to this API."}""") is MusicOutcome.Failed)
    }

    @Test
    fun `AudioTag hands over a token, then waits, then answers`() {
        assertEquals("tok", AudioTagClient.parseToken("""{"success":true,"job_status":"wait","token":"tok"}""").getOrThrow())
        assertTrue(AudioTagClient.parseToken("""{"success":false,"error":"apikey invalid"}""").isFailure)
        assertNull(AudioTagClient.parseResult("""{"success":true,"result":"wait"}"""))
        assertEquals(MusicOutcome.NoMatch, AudioTagClient.parseResult("""{"success":true,"result":"not found"}"""))
        val found = AudioTagClient.parseResult(
            """{"success":true,"result":"found","data":[{"tracks":[["One More Time","Daft Punk","Discovery","2000"]],"confidence":87,"time":42.5}]}""",
        ) as MusicOutcome.Match
        assertEquals("One More Time", found.match.title)
        assertEquals("2000", found.match.year)
        assertEquals(87, found.match.score)
        assertEquals(42_500L, found.match.offsetMs)
    }

    @Test
    fun `base64 agrees with the reference encoder`() {
        assertEquals("AAEC+vv8/f7/", MusicBase64.encode(byteArrayOf(0, 1, 2, -6, -5, -4, -3, -2, -1)))
        assertEquals("YWI=", MusicBase64.encode("ab".toByteArray()))
        assertEquals("YQ==", MusicBase64.encode("a".toByteArray()))
        assertEquals("YWJj", MusicBase64.encode("abc".toByteArray()))
        assertEquals("", MusicBase64.encode(ByteArray(0)))
    }

    @Test
    fun `timecodes and years are read loosely`() {
        assertEquals(36_000L, MusicJson.timecodeMs("00:36"))
        assertEquals(3_723_000L, MusicJson.timecodeMs("1:02:03"))
        assertEquals(0L, MusicJson.timecodeMs("soon"))
        assertEquals("2019", SongMatch.yearOf("2019-05-17"))
        assertEquals("1997", SongMatch.yearOf("20 Jan 1997"))
        assertEquals("", SongMatch.yearOf(null))
    }

    @Test
    fun `the cover lookups read the first hit`() {
        val itunes = MusicMetadata.parseItunes(
            """{"resultCount":1,"results":[{"trackViewUrl":"https://music.apple.com/t","artworkUrl100":"https://a/100x100bb.jpg","collectionName":"Discovery","releaseDate":"2001-03-12T08:00:00Z","trackTimeMillis":224000}]}""",
        )!!
        assertEquals("https://a/600x600bb.jpg", itunes.artUrl)
        assertEquals("Discovery", itunes.album)
        assertEquals("2001", itunes.year)
        assertEquals(224_000L, itunes.durationMs)
        assertEquals("https://music.apple.com/t", itunes.links[MusicLink.APPLE_MUSIC.id])
        val deezer = MusicMetadata.parseDeezer("""{"data":[{"link":"https://www.deezer.com/track/9","duration":224,"album":{"title":"Discovery","cover_big":"https://d/big.jpg"}}]}""")!!
        assertEquals("https://d/big.jpg", deezer.artUrl)
        assertEquals(224_000L, deezer.durationMs)
        assertNull(MusicMetadata.parseItunes("""{"resultCount":0,"results":[]}"""))
    }
}
