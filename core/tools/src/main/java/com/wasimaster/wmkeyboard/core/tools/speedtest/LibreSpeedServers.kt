package com.wasimaster.wmkeyboard.core.tools.speedtest

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LibreSpeed's servers: the public list of volunteer backends at
 * librespeed.org, the shape of one entry, and how a server picked from it
 * or typed by hand is kept on a provider credential.
 *
 * The list's `server` is a base address, sometimes written `//host` with no
 * scheme (the frontend takes the page's); the four paths are relative to it
 * unless they are addresses themselves. A PHP backend has `garbage.php`,
 * `empty.php` and `getIP.php`; the Go backend drops the extension.
 */
object LibreSpeedServers {

    const val DEFAULT_DL = "garbage.php"
    const val DEFAULT_UL = "empty.php"
    const val DEFAULT_PING = "empty.php"
    const val DEFAULT_IP = "getIP.php"

    /** The [com.wasimaster.wmkeyboard.core.providers.ProviderCredential.extra] keys a picked server's paths are kept under. */
    const val KEY_DL = "dlURL"
    const val KEY_UL = "ulURL"
    const val KEY_PING = "pingURL"
    const val KEY_IP = "getIpURL"
    const val KEY_SPONSOR = "sponsor"

    /** One server of the public list, as `servers.php` writes it. */
    @Serializable
    data class Entry(
        val id: Int = 0,
        val name: String,
        val server: String,
        val dlURL: String = DEFAULT_DL,
        val ulURL: String = DEFAULT_UL,
        val pingURL: String = DEFAULT_PING,
        val getIpURL: String = DEFAULT_IP,
        val sponsorName: String = "",
        val sponsorURL: String = "",
    ) {
        /** `Amsterdam, Netherlands (Clouvider)` → `Netherlands`; the whole name when it has no comma. */
        val country: String
            get() {
                val beforeParen = name.substringBefore('(').trim()
                return beforeParen.substringAfterLast(',', beforeParen).trim()
            }

        /** `Amsterdam, Netherlands (Clouvider)` → `Amsterdam`. */
        val city: String
            get() = name.substringBefore('(').substringBefore(',').trim()

        fun toServer(): SpeedTestServer.LibreSpeed = SpeedTestServer.LibreSpeed(
            base = normaliseBase(server),
            dlPath = dlURL,
            ulPath = ulURL,
            pingPath = pingURL,
            ipPath = getIpURL,
            name = name,
            sponsor = sponsorName,
        )
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the public list. Entries without a name or an address are skipped. */
    fun parse(body: String): List<Entry> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["servers"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            val server = obj["server"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isEmpty() || server.isEmpty()) return@mapNotNull null
            runCatching { json.decodeFromJsonElement(Entry.serializer(), element) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    @Volatile
    private var cached: Pair<Long, List<Entry>>? = null

    /**
     * The public list, fetched through the network log and kept for a day.
     * Blocking; call on an IO dispatcher. Throws when the fetch fails and
     * nothing is cached.
     */
    fun fetch(now: Long = System.currentTimeMillis()): List<Entry> {
        cached?.takeIf { now - it.first < CACHE_MS }?.let { return it.second }
        val base = ServiceEndpoints.base(ServiceEndpoint.LIBRESPEED_LIST)
        val body = ToolHttp.get(
            "$base/backend-servers/servers.php",
            timeoutMs = 15_000,
            source = NetSource.SPEED_TEST,
            route = "/backend-servers/servers.php",
        )
        val list = parse(body)
        if (list.isNotEmpty()) cached = now to list
        return list
    }

    /** `//host/x/` → `https://host/x`; a bare host gains `https://`; a trailing slash goes. */
    fun normaliseBase(server: String): String {
        var s = server.trim()
        if (s.startsWith("//")) s = "https:$s"
        if (!s.contains("://")) s = "https://$s"
        return s.trimEnd('/')
    }

    /** [path] under [base], or [path] itself when it is already an address. */
    fun resolve(base: String, path: String): String {
        val p = path.trim()
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        if (p.startsWith("//")) return "https:$p"
        return normaliseBase(base) + "/" + p.trimStart('/')
    }

    fun hostOf(base: String): String =
        normaliseBase(base).substringAfter("://").substringBefore('/').substringBefore(':')

    /** A server from what a provider row holds: its address, nickname and the paths the picker kept. */
    fun fromCredential(url: String, name: String, extra: Map<String, String>): SpeedTestServer.LibreSpeed =
        SpeedTestServer.LibreSpeed(
            base = normaliseBase(url),
            dlPath = extra[KEY_DL]?.takeIf { it.isNotBlank() } ?: DEFAULT_DL,
            ulPath = extra[KEY_UL]?.takeIf { it.isNotBlank() } ?: DEFAULT_UL,
            pingPath = extra[KEY_PING]?.takeIf { it.isNotBlank() } ?: DEFAULT_PING,
            ipPath = extra[KEY_IP]?.takeIf { it.isNotBlank() } ?: DEFAULT_IP,
            name = name,
            sponsor = extra[KEY_SPONSOR].orEmpty(),
        )

    /** The extras a picked [entry] writes onto its credential, beside its address and name. */
    fun extrasOf(entry: Entry): Map<String, String> = buildMap {
        put(KEY_DL, entry.dlURL)
        put(KEY_UL, entry.ulURL)
        put(KEY_PING, entry.pingURL)
        put(KEY_IP, entry.getIpURL)
        if (entry.sponsorName.isNotBlank()) put(KEY_SPONSOR, entry.sponsorName)
    }

    /** `103.31.178.116 - Vision Technologies Ltd.` → the address and the name; either may be blank. */
    fun parseIpLine(processed: String): Pair<String, String> {
        val text = processed.trim()
        val dash = text.indexOf(" - ")
        return if (dash < 0) text to "" else text.substring(0, dash).trim() to text.substring(dash + 3).trim()
    }

    private const val CACHE_MS = 24L * 60 * 60 * 1000
}
