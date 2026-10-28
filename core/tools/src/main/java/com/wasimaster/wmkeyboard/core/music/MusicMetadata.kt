package com.wasimaster.wmkeyboard.core.music

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.music.MusicJson.arr
import com.wasimaster.wmkeyboard.core.music.MusicJson.obj
import com.wasimaster.wmkeyboard.core.music.MusicJson.string
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Cover art and a store link for a match that came without them: an
 * ACRCloud answer, an AudioTag one. Two keyless public searches,
 * asked only then, and only with the song's title and artist: the iTunes
 * Search API first (600 px art, an Apple Music link), Deezer when it has
 * nothing. Blocking; call on an IO dispatcher.
 */
object MusicMetadata {

    /** [match] with art and links filled in where a lookup found them; [match] itself when nothing did or the network failed. */
    fun enrich(match: SongMatch): SongMatch {
        if (match.title.isBlank()) return match
        val term = listOf(match.title, match.artist).filter { it.isNotBlank() }.joinToString(" ")
        val fromItunes = runCatching { itunes(term) }.getOrNull()
        val fromDeezer = if (fromItunes?.artUrl == null) runCatching { deezer(match.title, match.artist) }.getOrNull() else null
        if (fromItunes == null && fromDeezer == null) return match
        val links = LinkedHashMap(match.links)
        fromItunes?.links?.forEach { (k, v) -> links.putIfAbsent(k, v) }
        fromDeezer?.links?.forEach { (k, v) -> links.putIfAbsent(k, v) }
        return match.copy(
            artUrl = match.artUrl ?: fromItunes?.artUrl ?: fromDeezer?.artUrl,
            album = match.album.ifBlank { fromItunes?.album ?: fromDeezer?.album.orEmpty() },
            year = match.year.ifBlank { fromItunes?.year.orEmpty() },
            durationMs = if (match.durationMs > 0) match.durationMs else fromItunes?.durationMs ?: fromDeezer?.durationMs ?: 0,
            links = links,
        )
    }

    /** What a lookup adds. */
    data class Found(
        val artUrl: String? = null,
        val album: String = "",
        val year: String = "",
        val durationMs: Long = 0,
        val links: Map<String, String> = emptyMap(),
    )

    fun itunes(term: String): Found? {
        val base = ServiceEndpoints.base(ServiceEndpoint.ITUNES_SEARCH).trimEnd('/')
        val url = "$base/search?entity=song&limit=1&term=" + URLEncoder.encode(term, "UTF-8")
        val body = ToolHttp.get(url, source = NetSource.MUSIC_METADATA, route = NetLog.pathOf(url))
        return parseItunes(body)
    }

    fun parseItunes(body: String): Found? {
        val root = MusicJson.parse(body) as? JsonObject ?: return null
        val first = root["results"].arr()?.firstOrNull().obj() ?: return null
        val art = first.string("artworkUrl100").takeIf { it.isNotEmpty() }?.replace("100x100", "600x600")
        val link = first.string("trackViewUrl").takeIf { it.isNotEmpty() }
        return Found(
            artUrl = art,
            album = first.string("collectionName"),
            year = SongMatch.yearOf(first.string("releaseDate")),
            durationMs = first.string("trackTimeMillis").toLongOrNull() ?: 0,
            links = link?.let { mapOf(MusicLink.APPLE_MUSIC.id to it) }.orEmpty(),
        )
    }

    fun deezer(title: String, artist: String): Found? {
        val base = ServiceEndpoints.base(ServiceEndpoint.DEEZER_API).trimEnd('/')
        val query = buildString {
            append("track:\"").append(title.replace("\"", "")).append('"')
            if (artist.isNotBlank()) append(" artist:\"").append(artist.replace("\"", "")).append('"')
        }
        val url = "$base/search?limit=1&q=" + URLEncoder.encode(query, "UTF-8")
        val body = ToolHttp.get(url, source = NetSource.MUSIC_METADATA, route = NetLog.pathOf(url))
        return parseDeezer(body)
    }

    fun parseDeezer(body: String): Found? {
        val root = MusicJson.parse(body) as? JsonObject ?: return null
        val first = root["data"].arr()?.firstOrNull().obj() ?: return null
        val album = first["album"].obj()
        val link = first.string("link").takeIf { it.isNotEmpty() }
        return Found(
            artUrl = album.string("cover_big").ifEmpty { album.string("cover_medium") }.takeIf { it.isNotEmpty() },
            album = album.string("title"),
            durationMs = first.string("duration").toLongOrNull()?.times(1000) ?: 0,
            links = link?.let { mapOf(MusicLink.DEEZER.id to it) }.orEmpty(),
        )
    }
}
