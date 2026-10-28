package com.wasimaster.wmkeyboard.core.tools.speedtest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

/**
 * What Cloudflare says about the connection. `/meta` answers only a request
 * that carries the test page's `Origin` and `Referer`; to anything else it
 * answers `{}`. Every `__down` response carries most of the same facts in
 * headers (`cf-meta-ip`, `city`, `country`, `colo`, `asn`), with the city
 * percent-encoded, so those are the fallback when `/meta` stays empty.
 */
object CloudflareMeta {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses `/meta`; null for a body with nothing in it. */
    fun parse(body: String): SpeedTestMeta? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root.isEmpty()) return null
        fun str(key: String): String = root[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        val colo = root["colo"]?.let { runCatching { it.jsonObject }.getOrNull() }
        fun coloStr(key: String): String = colo?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
        return SpeedTestMeta(
            ip = str("clientIp"),
            isp = str("asOrganization"),
            city = str("city"),
            country = str("country"),
            colo = coloStr("iata"),
            coloCity = coloStr("city"),
        )
    }

    /** The same facts off a `__down` response's headers; header names are matched without case. */
    fun fromHeaders(headers: Map<String, String?>): SpeedTestMeta {
        val lower = headers.entries.mapNotNull { (k, v) -> if (k == null || v == null) null else k.lowercase() to v }.toMap()
        fun decode(value: String?): String =
            value?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }.orEmpty()
        return SpeedTestMeta(
            ip = lower["cf-meta-ip"].orEmpty(),
            isp = lower["asn"]?.takeIf { it.isNotBlank() }?.let { "AS$it" }.orEmpty(),
            city = decode(lower["city"]),
            country = lower["country"].orEmpty(),
            colo = lower["colo"].orEmpty(),
        )
    }
}
